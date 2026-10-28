package com.local.factcheck.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest storage for small secrets (the Gemini API key).
 *
 * Implementation: an AES-256/GCM key generated inside the Android Keystore
 * (no private key material ever leaves the TEE/Keystore) encrypts each value,
 * and the base64 ciphertext is kept in a plain SharedPreferences file.
 *
 * Fallback: on the rare devices where Keystore misbehaves we degrade to a
 * base64-encoded value (clearly marked in the same file) so the app remains
 * usable — a broken key store should not brick the whole flow.
 */
object SecureStore {

    private const val PREF = "factcheck_secure"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "factcheck_master_aes_gcm_v1"
    private const val FLAG_INSECURE = "_insecure_encoding"
    private const val GCM_TAG_BITS = 128

    private lateinit var sp: SharedPreferences
    private var keystoreBroken = false

    fun init(context: Context) {
        if (this::sp.isInitialized) return
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        keystoreBroken = sp.getBoolean(FLAG_INSECURE, false)
        // Verify the key exists every launch; rebuild it when we are in fallback mode.
        if (keystoreBroken) restoreFallbackKey()
        else ensureKeystoreKey()
    }

    val usingFallback: Boolean get() = keystoreBroken

    fun put(key: String, value: String?) {
        if (!this::sp.isInitialized) return
        val e = sp.edit()
        if (value == null) {
            e.remove(key).remove(key + "_insecure").apply()
            return
        }
        if (keystoreBroken) {
            e.putString(FLAG_INSECURE, "true")
            e.putString(key + "_insecure", Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            e.apply()
        } else {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, masterKey())
                val iv = cipher.iv
                val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                val joined = ByteArray(iv.size + ct.size)
                iv.copyInto(joined, 0)
                ct.copyInto(joined, iv.size)
                e.putString(key, Base64.encodeToString(joined, Base64.NO_WRAP))
                e.remove(key + "_insecure")
                e.apply()
            } catch (t: Throwable) {
                keystoreBroken = true
                put(key, value) // Retry through the fallback path.
            }
        }
    }

    fun get(key: String): String? {
        if (!this::sp.isInitialized) return null
        if (keystoreBroken) {
            val b64 = sp.getString(key + "_insecure", null) ?: return null
            return runCatching {
                String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull()
        }
        val raw = sp.getString(key, null) ?: return null
        return try {
            val joined = Base64.decode(raw, Base64.NO_WRAP)
            val iv = joined.copyOfRange(0, 12)
            val ct = joined.copyOfRange(12, joined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            null // Keystore reset (e.g. device wipe) — treat as no key.
        }
    }

    private fun restoreFallbackKey() {
        // Legacy plaintext? Migrate once.
        val legacy = sp.getString(LEGACY_PREFIX + "api_key", null)
        if (legacy != null) {
            sp.edit().putString("api_key" + "_insecure", Base64.encodeToString(legacy.toByteArray(), Base64.NO_WRAP)).remove(LEGACY_PREFIX + "api_key").apply()
        }
    }

    private const val LEGACY_PREFIX = "_legacy_"

    fun clear(key: String) {
        if (this::sp.isInitialized) sp.edit().remove(key).remove(key + "_insecure").apply()
    }

    private fun ensureKeystoreKey() {
        try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ALIAS)) return
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            kg.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            kg.generateKey()
        } catch (t: Throwable) {
            keystoreBroken = true
        }
    }

    @Suppress("unused")
    private fun keystoreKey(): java.security.Key {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return ks.getKey(ALIAS, null)
            ?: throw IllegalStateException("Keystore key missing")
    }

    private fun masterKey(): java.security.Key = keystoreKey()
}
