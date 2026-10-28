package com.local.factcheck

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.local.factcheck.databinding.ActivityMainBinding
import com.local.factcheck.gemini.GeminiClient
import com.local.factcheck.groq.GroqClient
import com.local.factcheck.service.FactCheckAccessibilityService
import com.local.factcheck.util.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup screen: pick the AI provider, paste its key, tune the model, and
 * enable the accessibility service that provides the one-shot screenshot
 * trigger.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inpApiKey.setText(Config.apiKey ?: "")
        binding.inpModel.setText(Config.model)
        binding.inpGroqKey.setText(Config.groqApiKey ?: "")
        binding.inpGroqModel.setText(Config.groqModel)

        binding.btnProviderGemini.setOnClickListener { selectProvider(Config.PROVIDER_GEMINI) }
        binding.btnProviderGroq.setOnClickListener { selectProvider(Config.PROVIDER_GROQ) }
        binding.btnSaveKey.setOnClickListener { saveGeminiCredentials() }
        binding.btnTestKey.setOnClickListener { scope.launch { testGeminiKey() } }
        binding.btnGroqSave.setOnClickListener { saveGroqCredentials() }
        binding.btnGroqTest.setOnClickListener { scope.launch { testGroqKey() } }
        binding.btnEnableAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnCheckNow.setOnClickListener { sendCheckToService() }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        registerStateReceiver()
    }

    override fun onPause() {
        unregisterStateReceiver()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /* --------------------------- settings --------------------------- */

    private fun selectProvider(provider: String) {
        if (provider == Config.PROVIDER_GROQ && Config.groqApiKey.isNullOrBlank()) {
            // Allow selection, but warn — the handle tap checks the key anyway.
            toast("Groq selected — paste your Groq API key before checking")
        }
        Config.provider = provider
        toast("Provider: ${if (provider == Config.PROVIDER_GROQ) "Groq" else "Gemini"}")
        refreshUi()
    }

    private fun saveGeminiCredentials() {
        val key = binding.inpApiKey.text?.toString()?.trim().orEmpty()
        val model = binding.inpModel.text?.toString()?.trim().orEmpty()
        Config.model = model.ifBlank { Config.DEFAULT_MODEL }
        Config.apiKey = key.takeIf { it.isNotEmpty() }
        toast(
            if (Config.apiKey != null) "Gemini key saved (encrypted on device)" else "Gemini key cleared"
        )
        refreshUi()
    }

    private fun saveGroqCredentials() {
        val key = binding.inpGroqKey.text?.toString()?.trim().orEmpty()
        val model = binding.inpGroqModel.text?.toString()?.trim().orEmpty()
        Config.groqModel = model.ifBlank { Config.DEFAULT_GROQ_MODEL }
        Config.groqApiKey = key.takeIf { it.isNotEmpty() }
        toast(
            if (Config.groqApiKey != null) "Groq key saved (encrypted on device)" else "Groq key cleared"
        )
        refreshUi()
    }

    private suspend fun testGeminiKey() {
        val key = binding.inpApiKey.text?.toString()?.trim().orEmpty()
        binding.lblKeyResult.visibility = View.VISIBLE
        if (key.isBlank()) {
            binding.lblKeyResult.text = getString(R.string.key_test_err, "key is empty")
            return
        }
        binding.lblKeyResult.text = "Checking key against Gemini…"
        try {
            val best = withContext(Dispatchers.IO) {
                GeminiClient(Config).pickBestFlashLite(key)
            }
            if (best != null) {
                Config.model = best
                binding.inpModel.setText(best)
                binding.lblKeyResult.text =
                    getString(R.string.key_test_ok, best) + "  • saved"
                // Saving a valid Gemini key auto-fills the stored key too.
                if (Config.apiKey.isNullOrBlank()) Config.apiKey = key
                refreshUi()
            } else {
                binding.lblKeyResult.text =
                    getString(R.string.key_test_err, "no Flash-Lite model visible to this key")
            }
        } catch (t: Throwable) {
            binding.lblKeyResult.text = getString(R.string.key_test_err, t.message ?: "unknown error")
        }
    }

    private suspend fun testGroqKey() {
        val key = binding.inpGroqKey.text?.toString()?.trim().orEmpty()
        binding.lblGroqResult.visibility = View.VISIBLE
        if (key.isBlank()) {
            binding.lblGroqResult.text = getString(R.string.key_test_err, "key is empty")
            return
        }
        binding.lblGroqResult.text = "Checking key against Groq…"
        try {
            val ids = withContext(Dispatchers.IO) { GroqClient().listModelIds(key) }
            val model = binding.inpGroqModel.text?.toString()?.trim().orEmpty()
                .ifBlank { Config.DEFAULT_GROQ_MODEL }
            val hasModel = ids.any { it == model }
            binding.lblGroqResult.text = if (hasModel) {
                getString(R.string.key_test_ok, "$model (${ids.size} models)  • saved")
            } else {
                getString(
                    R.string.key_test_ok,
                    "key ok (${ids.size} models); '$model' not listed — re-check the model id"
                )
            }
            if (Config.groqApiKey.isNullOrBlank()) Config.groqApiKey = key
            refreshUi()
        } catch (t: Throwable) {
            binding.lblGroqResult.text = getString(R.string.key_test_err, t.message ?: "unknown error")
        }
    }

    /* --------------------------- accessibility --------------------------- */

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            toast("Open Settings → Accessibility → FactCheck manually")
        }
        toast("Enable “FactCheck — one-shot screen check” in the list")
    }

    private fun isServiceEnabled(): Boolean {
        val cn = ComponentName(this, FactCheckAccessibilityService::class.java)
        val flat = cn.flattenToString()
        val short = cn.flattenToShortString()
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { entry ->
            entry.equals(flat, ignoreCase = true) ||
                entry.equals(short, ignoreCase = true) ||
                entry.contains("$packageName/.service.FactCheckAccessibilityService")
        }
    }

    /* --------------------------- manual trigger --------------------------- */

    private fun sendCheckToService() {
        if (!isServiceEnabled()) {
            toast("Enable the FactCheck accessibility service first")
            openAccessibilitySettings()
            return
        }
        if (Config.provider == Config.PROVIDER_GROQ) {
            if (Config.groqApiKey.isNullOrBlank()) {
                toast("Save your Groq API key first")
                binding.inpGroqKey.requestFocus()
                return
            }
        } else if (Config.apiKey.isNullOrBlank()) {
            toast("Save your Gemini API key first")
            binding.inpApiKey.requestFocus()
            return
        }
        val i = Intent(this, FactCheckAccessibilityService::class.java)
            .setAction(FactCheckAccessibilityService.ACTION_CHECK)
        try {
            startService(i)
            toast("Fact-checking this screen — the floating panel will show the verdicts")
        } catch (t: Throwable) {
            toast("Could not reach the service: ${t.message}")
        }
    }

    /* --------------------------- UI --------------------------- */

    private fun refreshUi() {
        val rows = binding.lnlStatus
        rows.removeAllViews()

        fun row(label: String, value: String, warn: Boolean) {
            val t = TextView(this).apply {
                text = if (warn) "$label — $value  ⚠" else "$label — $value"
                textSize = 14f
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (warn) R.color.verdict_warn else R.color.status_text
                    )
                )
                setPadding(0, 6, 0, 6)
            }
            rows.addView(t)
        }

        val keySet = !Config.apiKey.isNullOrBlank()
        val groqKeySet = !Config.groqApiKey.isNullOrBlank()
        row("Provider (active)", Config.providerName, false)
        row("Gemini key", if (keySet) "saved ✓" else "not set", !keySet)
        row("Gemini model", Config.model, false)
        row("Groq key", if (groqKeySet) "saved ✓" else "not set", !groqKeySet)
        row("Groq model", Config.groqModel, false)

        val a11yEnabled = isServiceEnabled() || FactCheckAccessibilityService.isRunning
        row(
            "Accessibility",
            if (a11yEnabled) "enabled ✓" else "needed",
            !a11yEnabled
        )
        row("Internet", if (isOnline()) "online ✓" else "offline", !isOnline())
    }

    private fun registerStateReceiver() {
        if (stateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshUi()
            }
        }
        stateReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(FactCheckAccessibilityService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterStateReceiver() {
        stateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        stateReceiver = null
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
