package com.local.factcheck.util

import android.content.Context
import android.content.SharedPreferences

/**
 * App-level configuration. Everything lives locally on the device;
 * the API key is kept encrypted (see [SecureStore]).
 */
object Config {

    const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    const val DEFAULT_GROQ_MODEL = "qwen/qwen3.8-27b"

    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_GROQ = "groq"

    private const val PREF = "factcheck_prefs"
    private const val K_MODEL = "model"
    private const val K_API_KEY = "api_key"
    private const val K_GROQ_MODEL = "groq_model"
    private const val K_GROQ_API_KEY = "groq_api_key"
    private const val K_PROVIDER = "provider"
    private const val K_HINT_SHOWN = "tip_shown_once"
    private const val K_DISCOVERY_HINT = "model_auto_note"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (this::sp.isInitialized) return
        SecureStore.init(context)
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    val ready: Boolean get() = this::sp.isInitialized

    /** Active AI provider: [PROVIDER_GEMINI] (default) or [PROVIDER_GROQ]. */
    var provider: String
        get() = sp.getString(K_PROVIDER, null)?.takeIf { it.isNotBlank() } ?: PROVIDER_GEMINI
        set(value) = sp.edit().putString(K_PROVIDER, value.trim()).apply()

    val isGroq: Boolean get() = provider == PROVIDER_GROQ
    val providerName: String get() = if (isGroq) "Groq" else "Gemini"

    /* -------- Gemini (original path, untouched behavior) -------- */

    var apiKey: String?
        get() = if (ready) SecureStore.get(K_API_KEY) else null
        set(value) {
            if (!ready) return
            if (value.isNullOrBlank()) SecureStore.clear(K_API_KEY)
            else SecureStore.put(K_API_KEY, value.trim())
        }

    val hasApiKey: Boolean get() = !apiKey.isNullOrBlank()

    var model: String
        get() = sp.getString(K_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(value) = sp.edit().putString(K_MODEL, value.trim()).apply()

    /* -------- Groq (v2.5) -------- */

    var groqApiKey: String?
        get() = if (ready) SecureStore.get(K_GROQ_API_KEY) else null
        set(value) {
            if (!ready) return
            if (value.isNullOrBlank()) SecureStore.clear(K_GROQ_API_KEY)
            else SecureStore.put(K_GROQ_API_KEY, value.trim())
        }

    val hasGroqKey: Boolean get() = !groqApiKey.isNullOrBlank()

    var groqModel: String
        get() = sp.getString(K_GROQ_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_GROQ_MODEL
        set(value) = sp.edit().putString(K_GROQ_MODEL, value.trim()).apply()

    /* -------- Active-provider helpers -------- */

    val activeApiKey: String? get() = if (isGroq) groqApiKey else apiKey
    val activeModel: String get() = if (isGroq) groqModel else model
    val hasActiveKey: Boolean get() = !activeApiKey.isNullOrBlank()

    var autoModelNote: String?
        get() = sp.getString(K_DISCOVERY_HINT, null)
        set(value) = sp.edit().putString(K_DISCOVERY_HINT, value).apply()

    var tipShown: Boolean
        get() = sp.getBoolean(K_HINT_SHOWN, false)
        set(value) = sp.edit().putBoolean(K_HINT_SHOWN, value).apply()

    /** Keystore fallback (key stored less safely) — surfaced in the UI. */
    val secureStorageDegraded: Boolean get() = SecureStore.usingFallback
}
