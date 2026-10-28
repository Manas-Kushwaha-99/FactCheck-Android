package com.local.factcheck.util

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.local.factcheck.R
import com.local.factcheck.gemini.Claim
import com.local.factcheck.gemini.FactCheckResult
import kotlin.math.abs

/** Renderable states for the floating panel. */
sealed class PanelState {
    object Idle : PanelState()
    data class Checking(val model: String) : PanelState()
    data class Result(val data: FactCheckResult, val model: String) : PanelState()
    data class Error(val message: String) : PanelState()
    data class Hint(val message: String) : PanelState()
}

/**
 * Draws the two always-on-top windows:
 *  • the slim side handle pinned to the right edge (the "hidden sidebar" shortcut,
 *    tap = fact-check now, long-press = open the app)
 *  • a compact result panel that never blocks touches outside its own bounds.
 *
 * Both windows are TYPE_ACCESSIBILITY_OVERLAY, so they are added by the
 * accessibility service and require no extra permission.
 */
class Overlay(
    private val ctx: Context,
    private val onHandleTap: () -> Unit,
    private val onHandleLongPress: () -> Unit,
    private val onRecheck: () -> Unit,
    private val onDismiss: () -> Unit
) {

    companion object {
        private const val TAG = "FactCheckOverlay"
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Overlay views are inflated inside a themed context: a bare Service has
     * no theme, and Material widgets (inflate from the panel layout) crash
     * silently without one.
     */
    private val themedCtx: Context = ContextThemeWrapper(ctx, R.style.Theme_FactCheck)

    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var lastResultText = ""
    private var lastState: PanelState = PanelState.Idle
    private var autoHide: Runnable? = null
    private var shimmerAnim: ValueAnimator? = null

    val visible: Boolean get() = handle != null

    fun dp(v: Int): Int = (v * density).toInt()

    /* ============================ handle ============================ */

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun showHandle() {
        if (handle != null) return
        val v = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_handle, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        // Pull slightly inward: a flush-to-edge handle sits in the gesture-nav
        // back-swipe zone on many phones and touches there are eaten.
        lp.x = dp(6)
        lp.windowAnimations = 0
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try {
            wm.addView(v, lp)
        } catch (t: Throwable) {
            Log.e(TAG, "handle addView failed", t)
            return
        }
        handle = v
        handleParams = lp
        v.setOnTouchListener(handleTouchListener())
        claimHandleFromGestures(v)
    }

    /** Excludes the handle's bounds from the system back-gesture swipe zone. */
    private fun claimHandleFromGestures(v: View) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        v.addOnLayoutChangeListener { view, l, t, r, b, _, _, _, _ ->
            if (r > l && b > t) {
                val rects = ArrayList<Rect>()
                rects.add(Rect(0, 0, r - l, b - t))
                view.systemGestureExclusionRects = rects
            }
        }
    }

    private var downY = 0f
    private var handleYOffset = 0
    private var dragging = false

    private fun handleTouchListener() = View.OnTouchListener { view, ev ->
        val lp = handleParams ?: return@OnTouchListener false
        val bar = view.findViewById<View>(R.id.handle_bar)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.rawY
                dragging = false
                bar?.animate()?.cancel()
                bar?.animate()?.scaleX(0.82f)?.scaleY(0.82f)?.setDuration(90)?.start()
            }
            MotionEvent.ACTION_MOVE -> {
                val d = ev.rawY - downY
                if (!dragging && abs(d) > dp(10)) dragging = true
                if (dragging) {
                    bar?.animate()?.cancel()
                    bar?.scaleX = 1f
                    bar?.scaleY = 1f
                    // lp.y means different things per gravity: under
                    // CENTER_VERTICAL it is an offset from the middle, under
                    // TOP it is an absolute offset from the screen top. The
                    // handle is added centered, so the first drag must switch
                    // gravity using the window's real on-screen position —
                    // reusing the centered-mode lp.y as an absolute one is
                    // what made the handle jump to the top.
                    val centered = lp.gravity == (Gravity.START or Gravity.CENTER_VERTICAL)
                    if (centered) {
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        handleYOffset = loc[1] + d.toInt()
                        lp.gravity = Gravity.START or Gravity.TOP
                    } else {
                        handleYOffset += d.toInt()
                    }
                    lp.y = handleYOffset
                    try {
                        wm.updateViewLayout(view, lp)
                    } catch (_: Throwable) {
                    }
                    downY = ev.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                bar?.animate()?.cancel()
                bar?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)
                    ?.setInterpolator(DecelerateInterpolator(1.5f))?.start()
                if (!dragging) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (ev.eventTime - ev.downTime > 500L) onHandleLongPress() else onHandleTap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                bar?.animate()?.cancel()
                bar?.scaleX = 1f
                bar?.scaleY = 1f
            }
            else -> {}
        }
        true
    }

    /* ============================ panel ============================ */

    fun showPanel(state: PanelState) {
        lastState = state
        val isNew = panel == null
        if (panel == null) {
            panel = try {
                LayoutInflater.from(themedCtx).inflate(R.layout.overlay_panel, null)
            } catch (t: Throwable) {
                Log.e(TAG, "panel inflate failed", t)
                null
            }
        }
        val v = panel ?: return
        if (panelParams == null) {
            panelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
        }
        val lp = panelParams!!.apply {
            val dm = ctx.resources.displayMetrics
            width = minOf((dm.widthPixels * 0.86f).toInt(), dp(420))
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(90)
            windowAnimations = 0
            format = PixelFormat.TRANSLUCENT
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        wirePanel(v)
        render(v, state)

        if (isNew) {
            v.alpha = 0f
            v.scaleX = 0.9f
            v.scaleY = 0.9f
            try {
                wm.addView(v, lp)
            } catch (t: Throwable) {
                Log.e(TAG, "panel addView failed", t)
                panel = null
                panelParams = null
                // The panel can't be drawn — at least make the state visible.
                Toast.makeText(ctx, "FactCheck: " + describe(state), Toast.LENGTH_LONG).show()
                return
            }
            mainHandler.post {
                v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
        } else {
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Throwable) {
            }
        }
        capScrollHeight(v)
        scheduleAutoHide(state)
    }

    private fun wirePanel(v: View) {
        v.findViewById<TextView>(R.id.panel_close)?.setOnClickListener { onDismiss() }
        v.findViewById<View>(R.id.panel_recheck)?.setOnClickListener { onRecheck() }
        v.findViewById<View>(R.id.panel_copy)?.setOnClickListener {
            if (lastResultText.isNotBlank()) copyResult(lastResultText)
        }
        v.findViewById<View>(R.id.panel_open)?.setOnClickListener { openApp() }
    }

    private fun capScrollHeight(v: View) {
        val scroll = v.findViewById<ScrollView>(R.id.panel_scroll) ?: return
        scroll.post {
            val dm = ctx.resources.displayMetrics
            val maxH = (dm.heightPixels * 0.50f).toInt()
            if (scroll.height > maxH) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
                scroll.requestLayout()
            }
        }
    }

    /** Hints dismiss themselves after a few seconds; results/errors stay. */
    private fun scheduleAutoHide(state: PanelState) {
        autoHide?.let { mainHandler.removeCallbacks(it) }
        autoHide = null
        if (state is PanelState.Hint) {
            val r = Runnable { if (lastState is PanelState.Hint) hidePanel() }
            autoHide = r
            mainHandler.postDelayed(r, 7000)
        }
    }

    fun hidePanel() = hidePanelInternal(animated = true)

    private fun hidePanelInternal(animated: Boolean) {
        autoHide?.let { mainHandler.removeCallbacks(it) }
        autoHide = null
        stopShimmer()
        val v = panel ?: return
        panel = null
        panelParams = null
        lastState = PanelState.Idle
        if (!animated) {
            v.animate().cancel()
            try {
                wm.removeView(v)
            } catch (_: Throwable) {
            }
            return
        }
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(170)
            .setInterpolator(AccelerateInterpolator(1.6f))
            .withEndAction {
                try {
                    wm.removeView(v)
                } catch (_: Throwable) {
                }
            }
            .start()
    }

    fun hideAll() {
        hidePanelInternal(animated = false)
        handle?.let {
            try {
                wm.removeView(it)
            } catch (_: Throwable) {
            }
        }
        handle = null
        handleParams = null
    }

    /** Re-attach the side handle after a capture / render cycle. */
    fun restoreHandle() {
        showHandle()
    }

    fun copyResult(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("factcheck", text))
    }

    fun openApp() {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        i?.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        try {
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }

    /* ============================ rendering ============================ */

    private fun render(v: View, state: PanelState) {
        val body = v.findViewById<LinearLayout>(R.id.panel_body) ?: return
        val title = v.findViewById<TextView>(R.id.panel_title_text)
        stopShimmer()
        body.removeAllViews()
        when (state) {
            is PanelState.Checking -> {
                title?.text = ctx.getString(R.string.app_name) + "  •  " + state.model
                body.addView(skeleton())
            }
            is PanelState.Hint -> {
                title?.setText(R.string.app_name)
                body.addView(centerText(state.message, R.color.verdict_warn))
            }
            is PanelState.Error -> {
                title?.setText(R.string.app_name)
                body.addView(
                    TextView(ctx).apply {
                        text = state.message
                        textSize = 13f
                        setTextColor(colorOf(R.color.verdict_false))
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, dp(6))
                    }
                )
            }
            is PanelState.Result -> renderResult(v, body, state)
            is PanelState.Idle -> {}
        }
        // Soft content crossfade whenever the panel's purpose changes.
        body.alpha = 0f
        mainHandler.post {
            body.animate().alpha(1f).setDuration(190).start()
        }
    }

    private fun renderResult(v: View, body: LinearLayout, st: PanelState.Result) {
        val r = st.data
        val title = v.findViewById<TextView>(R.id.panel_title_text)
        title?.text = buildString {
            append(ctx.getString(R.string.app_name))
            append("  •  ")
            append(st.model)
            if (r.claims.size > 1) append("  •  " + r.claims.size + " claims checked")
        }

        lastResultText = resultToPlainText(st)

        if (r.claims.isEmpty()) {
            if (r.overall.isNotBlank()) body.addView(textWrapped(r.overall, R.color.panel_text))
            else body.addView(centerText("Could not find any claims to check.", R.color.panel_text_mute))
        } else {
            val chipRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            r.claims.forEach { c -> chipRow.addView(chip(claimSymbol(c.verdict), verdictColor(c.verdict))) }
            body.addView(chipRow)

            if (r.overall.isNotBlank()) body.addView(textWrapped(r.overall, R.color.panel_text))

            body.addView(gap(6))
            r.claims.forEach { c -> body.addView(claimView(c)) }
        }

        r.uncertainty?.takeIf { it.isNotBlank() }?.let {
            body.addView(gap(4))
            body.addView(mixed(it, R.color.verdict_warn))
        }

        if (r.searched.isNotEmpty()) {
            body.addView(textWrapped(
                "Searched: " + r.searched.take(3).joinToString("  •  "),
                R.color.panel_accent
            ))
        }

        if (r.sources.isNotEmpty()) {
            body.addView(gap(6))
            val sText = "Sources: " + r.sources.take(4).joinToString(", ") { s -> s.domain }
            body.addView(textWrapped(sText, R.color.panel_text_mute))
        }
    }

    private fun resultToPlainText(st: PanelState.Result): String {
        val r = st.data
        val parts = StringBuilder()
        if (r.overall.isNotBlank()) {
            parts.append(r.overall)
            parts.append("\n")
        }
        r.claims.forEach { c ->
            parts.append("\n")
            parts.append(c.verdict.uppercase())
            parts.append(" — ")
            parts.append(c.claim)
            if (c.explanation.isNotBlank()) {
                parts.append("\n")
                parts.append(c.explanation)
            }
        }
        r.uncertainty?.takeIf { it.isNotBlank() }?.let {
            parts.append("\n\nUncertainty: ")
            parts.append(it)
        }
        if (r.sources.isNotEmpty()) {
            parts.append("\n\nSources: ")
            parts.append(r.sources.take(6).joinToString(", ") { s -> s.domain })
        }
        if (r.searched.isNotEmpty()) {
            parts.append("\nSearched: ")
            parts.append(r.searched.take(4).joinToString(", "))
        }
        return parts.toString().trim()
    }

    private fun claimSymbol(ver: String): String = when (ver.uppercase()) {
        "TRUE", "MOSTLY TRUE" -> "✓"
        "FALSE" -> "✕"
        "MISLEADING" -> "⚠"
        else -> "?"
    }

    private fun verdictColor(ver: String): Int = when (ver.uppercase()) {
        "TRUE", "MOSTLY TRUE" -> colorOf(R.color.verdict_true)
        "FALSE" -> colorOf(R.color.verdict_false)
        "MISLEADING" -> colorOf(R.color.verdict_warn)
        else -> colorOf(R.color.verdict_unknown)
    }

    private fun claimView(c: Claim): View {
        val out = SpannableStringBuilder()
        val head = c.verdict.uppercase() + "  "
        out.append(head)
        out.setSpan(
            ForegroundColorSpan(verdictColor(c.verdict)),
            0,
            head.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        out.append(c.claim)

        if (c.explanation.isNotBlank()) {
            out.append("\n")
            val start = out.length
            out.append(c.explanation)
            out.setSpan(
                ForegroundColorSpan(colorOf(R.color.panel_text_mute)),
                start,
                out.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        c.confidence?.takeIf { it.isNotBlank() }?.let {
            val start = out.length
            out.append("   ")
            out.append(it.lowercase())
            out.append(" confidence")
            out.setSpan(
                ForegroundColorSpan(colorOf(R.color.panel_text_mute)),
                start,
                out.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return TextView(ctx).apply {
            text = out
            textSize = 13f
            setTextColor(colorOf(R.color.panel_text))
            setPadding(0, dp(4), 0, dp(6))
        }
    }

    /* ============================ builders ============================ */

    private fun chip(sym: String, color: Int): TextView {
        val t = TextView(ctx)
        t.text = " $sym "
        t.textSize = 12f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(Color.WHITE)
        t.setShadowLayer(dp(4).toFloat(), 0f, 0f, color)
        t.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(0x38, Color.red(color), Color.green(color), Color.blue(color)))
        }
        t.setPadding(dp(9), dp(3), dp(9), dp(3))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(5)
        t.layoutParams = lp
        return t
    }

    private fun textWrapped(value: String, colorRes: Int): TextView = TextView(ctx).apply {
        text = value
        textSize = 13f
        setTextColor(colorOf(colorRes))
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun centerText(value: String, colorRes: Int): TextView = TextView(ctx).apply {
        text = value
        textSize = 12f
        setTextColor(colorOf(colorRes))
        gravity = Gravity.CENTER
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun mixed(value: String, colorRes: Int): TextView = TextView(ctx).apply {
        text = "⚠  " + value
        textSize = 12f
        val color = ContextCompat.getColor(ctx, colorRes)
        setTextColor(color)
        setShadowLayer(dp(3).toFloat(), 0f, 0f, color)
        setPadding(0, dp(4), 0, dp(2))
    }

    /* ============================ shimmer skeleton ============================ */

    /**
     * Glassy "checking the screen…" placeholder: stacked rounded bars with a
     * light-sweep animation. The sweep runs only while this view is on screen
     * (see [stopShimmer]).
     */
    private fun skeleton(): View {
        val container = FrameLayout(ctx).apply {
            clipChildren = true
            clipToPadding = true
        }
        val bars = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        intArrayOf(140, 235, 206, 160, 228).forEachIndexed { i, w ->
            if (i == 2) bars.addView(gap(8))
            val bar = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(11)).apply {
                    topMargin = dp(6)
                }
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(ctx, R.color.skel_bar))
                    cornerRadius = dp(6).toFloat()
                }
            }
            bars.addView(bar)
        }
        container.addView(
            bars,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val sweep = View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    0x00FFFFFF,
                    ContextCompat.getColor(ctx, R.color.shimmer_glint),
                    0x00FFFFFF
                )
            )
            layoutParams = FrameLayout.LayoutParams(
                dp(110), ViewGroup.LayoutParams.MATCH_PARENT
            )
            translationX = -dp(110).toFloat()
        }
        container.addView(sweep)

        container.post {
            val w = container.width
            if (w < dp(40)) return@post
            shimmerAnim?.cancel()
            val anim = ValueAnimator.ofFloat(-dp(110).toFloat(), (w + dp(6)).toFloat()).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a -> sweep.translationX = a.animatedValue as Float }
            }
            anim.start()
            shimmerAnim = anim
        }
        return container
    }

    private fun stopShimmer() {
        shimmerAnim?.cancel()
        shimmerAnim = null
    }

    private fun gap(h: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(h))
    }

    private fun colorOf(res: Int): Int = ContextCompat.getColor(ctx, res)

    /** Short human-readable state for the toast fallback. */
    private fun describe(state: PanelState): String = when (state) {
        is PanelState.Checking -> "Checking the screen…"
        is PanelState.Result -> (state.data.overall.ifBlank { "Result ready" }).take(140)
        is PanelState.Error -> state.message
        is PanelState.Hint -> state.message
        is PanelState.Idle -> ""
    }
}
