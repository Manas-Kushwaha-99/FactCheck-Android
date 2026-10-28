package com.local.factcheck.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.local.factcheck.MainActivity
import com.local.factcheck.gemini.GeminiClient
import com.local.factcheck.groq.GroqClient
import com.local.factcheck.util.Config
import com.local.factcheck.util.Overlay
import com.local.factcheck.util.PanelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * The system-managed "brain" of the app. Accessibility services stay alive
 * while enabled, so we need no foreground service:
 *
 *  • shows the slim side handle (as TYPE_ACCESSIBILITY_OVERLAY — no extra
 *    draw-over-app permission needed)
 *  • when the handle is tapped, takes exactly ONE screenshot via
 *    AccessibilityService.takeScreenshot() and sends it to Gemini
 *  • no MediaProjection, no virtual display, no screen recording
 */
class FactCheckAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FactCheckSvc"

        const val ACTION_CHECK = "com.local.factcheck.action.CHECK"
        const val ACTION_STATE_CHANGED = "com.local.factcheck.action.STATE_CHANGED"

        private const val SETTLE_MS = 180L

        /** Lets MainActivity show whether the service is connected. */
        @JvmStatic
        var isRunning: Boolean = false
            private set
    }

    private lateinit var overlay: Overlay
    private lateinit var gemini: GeminiClient
    private lateinit var groq: GroqClient
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inFlight: Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used: we never read window content.
    }

    override fun onInterrupt() {
        // Not used.
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!Config.ready) Config.init(applicationContext)
        gemini = GeminiClient(Config)
        groq = GroqClient()
        // The screenshot capability is declared via android:canTakeScreenshot="true"
        // in res/xml/factcheck_accessibility.xml.

        overlay = Overlay(
            ctx = this,
            onHandleTap = ::onHandleTapped,
            onHandleLongPress = ::openMainActivity,
            onRecheck = ::onHandleTapped,
            onDismiss = { onPanelDismissed() }
        )
        overlay.showHandle()
        isRunning = true
        sendStateChanged()

        if (!Config.tipShown && Config.hasApiKey) {
            mainHandler.postDelayed({
                if (isRunning && this::overlay.isInitialized) {
                    overlay.showPanel(
                        PanelState.Hint("Ready — tap this handle any time to fact-check the screen.")
                    )
                }
            }, 500)
            Config.tipShown = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CHECK -> mainHandler.post { requestFactCheck() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        sendStateChanged()
        if (this::overlay.isInitialized) overlay.hideAll()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        isRunning = false
        scope.cancel()
        if (this::overlay.isInitialized) overlay.hideAll()
        super.onDestroy()
    }

    /* ------------------------- fact-check flow ------------------------- */

    private fun onHandleTapped() {
        requestFactCheck()
    }

    private fun requestFactCheck() {
        Log.d(TAG, "handle tapped — provider=${Config.providerName}, hasKey=${Config.hasActiveKey}, canShot=${canTakeScreenshot()}")
        if (!this::overlay.isInitialized) return
        if (!Config.hasActiveKey) {
            overlay.showPanel(
                PanelState.Hint("Add your ${Config.providerName} API key in FactCheck first.")
            )
            return
        }
        if (!canTakeScreenshot()) {
            overlay.showPanel(
                PanelState.Hint("Screenshot capability is not granted. Re-enable FactCheck in Settings → Accessibility.")
            )
            return
        }
        inFlight?.cancel()
        inFlight = scope.launch { performCheck() }
    }

    private fun canTakeScreenshot(): Boolean = try {
        serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
    } catch (t: Throwable) {
        false
    }

    private suspend fun performCheck() {
        // Take our own windows off the screen so they don't appear in (or
        // cover) the captured frame.
        overlay.hideAll()
        delay(SETTLE_MS)

        var failureMessage: String? = null
        val bmp: Bitmap? = withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine { cont ->
                takeScreenshotNow { bitmap, error ->
                    if (cont.isActive) {
                        failureMessage = error
                        cont.resume(bitmap)
                    }
                }
            }
        } ?: run {
            // The system never called back — surface it instead of hanging.
            failureMessage = failureMessage ?: "Screenshot did not complete — tap again."
            null
        }
        overlay.restoreHandle()

        if (bmp == null) {
            overlay.showPanel(PanelState.Error(failureMessage ?: "Could not capture the screen. Try again."))
            return
        }

        val key = Config.activeApiKey
        val model = Config.activeModel
        if (key.isNullOrBlank()) {
            overlay.showPanel(PanelState.Error("Set your ${Config.providerName} API key in FactCheck first."))
            bmp.recycle()
            return
        }

        overlay.showPanel(PanelState.Checking(model))
        try {
            val result = if (Config.isGroq) {
                groq.factCheck(bmp, key, model)
            } else {
                gemini.factCheck(bmp, key, model)
            }
            if (result.claims.isEmpty() && result.overall.isBlank()) {
                overlay.showPanel(PanelState.Error("Empty response from ${Config.providerName}."))
            } else {
                overlay.showPanel(PanelState.Result(result, model))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fact-check failed", t)
            val code = when (t) {
                is GeminiClient.GeminiHttpException -> t.httpCode
                is GroqClient.GroqHttpException -> t.httpCode
                else -> -1
            }
            val msg = if (code in 400..499) {
                friendlyApiError(code, model, t.message ?: "request rejected")
            } else if (t is java.net.SocketTimeoutException) {
                "${Config.providerName} timed out. Check your connection and try again."
            } else {
                "Fact-check failed: " + (t.message ?: t.javaClass.simpleName)
            }
            overlay.showPanel(PanelState.Error(msg))
        } finally {
            bmp.recycle()
        }
    }

    /* ------------------------- one-shot screenshot ------------------------- */

    private fun takeScreenshotNow(onDone: (Bitmap?, String?) -> Unit) {
        val executor = Executor { runnable -> mainHandler.post(runnable) }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bmp = result.toSoftwareBitmap()
                        Log.d(TAG, "screenshot captured ${bmp?.width}x${bmp?.height}")
                        onDone(bmp, null)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: $errorCode")
                        onDone(null, errorCodeMessage(errorCode))
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "takeScreenshot threw", t)
            onDone(null, "Screenshot unavailable: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun AccessibilityService.ScreenshotResult.toSoftwareBitmap(): Bitmap? = try {
        val buffer = hardwareBuffer
        if (buffer == null) {
            null
        } else try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
            // Deep-copy into a software bitmap so the buffer can be closed
            // immediately (JPEG compression also needs a software bitmap).
            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
            soft
        } finally {
            buffer.close()
        }
    } catch (t: Throwable) {
        Log.e(TAG, "screenshot buffer decode failed", t)
        null
    }

    private fun errorCodeMessage(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "Screenshot permission missing. Open Settings → Accessibility → FactCheck and enable it."
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "Screenshots are rate-limited — wait a second and tap again."
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY, ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
            "This screen can't be captured right now. Try again."
        else ->
            "Screenshot failed (code $code). Try again."
    }

    /* ------------------------- misc ------------------------- */

    private fun friendlyApiError(code: Int, model: String, message: String): String {
        val p = Config.providerName
        val m = message.lowercase()
        return when {
            code == 401 -> "$p key rejected (401). Paste a valid key in the app."
            code == 403 -> "API key not allowed (403). Check key restrictions in the provider console. " + message
            code == 404 -> "Model '$model' rejected by $p. " + message
            code == 429 -> "Rate limited by $p (429): " + message
            m.contains("api key") || m.contains("permission") -> "Key problem: " + message
            else -> "$p error ($code): " + message
        }
    }

    private fun onPanelDismissed() {
        if (!this::overlay.isInitialized) return
        overlay.hidePanel()
        overlay.restoreHandle()
    }

    private fun openMainActivity() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        try {
            startActivity(i)
        } catch (t: Throwable) {
            Log.w(TAG, "open main activity failed", t)
        }
    }

    private fun sendStateChanged() {
        try {
            sendBroadcast(
                Intent(ACTION_STATE_CHANGED).setPackage(packageName)
            )
        } catch (_: Throwable) {
        }
    }
}
