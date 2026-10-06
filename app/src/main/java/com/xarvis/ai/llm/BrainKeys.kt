package com.xarvis.ai.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps a cloud-AI API key (e.g. Gemini) private: it's encrypted with a key held in the phone's
 * hardware-backed Android Keystore, so the raw key is never in plain prefs, never logged, and
 * never leaves the phone except to the AI provider it belongs to. Rex pastes it in ☰ → BRAIN.
 */
class BrainKeys(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("brainkeys", Context.MODE_PRIVATE)

    /** Stores [apiKey] for provider [id] (encrypted). Blank clears it. */
    fun save(id: String, apiKey: String) {
        val key = apiKey.trim()
        if (key.isEmpty()) return clear(id)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val enc = cipher.doFinal(key.toByteArray())
        prefs.edit()
            .putString("$id.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$id.ct", Base64.encodeToString(enc, Base64.NO_WRAP))
            .apply()
    }

    /** The stored key for [id], or null. */
    fun get(id: String): String? = try {
        val iv = prefs.getString("$id.iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val ct = prefs.getString("$id.ct", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(ct))
    } catch (e: Exception) {
        null
    }

    fun has(id: String): Boolean = prefs.contains("$id.ct")

    fun clear(id: String) {
        prefs.edit().remove("$id.iv").remove("$id.ct").apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        const val GEMINI = "gemini"
        const val BRAVE = "brave"
        const val GITHUB = "github" // XARVIS Code: a GitHub token to create/build separate projects
        private const val ALIAS = "xarvis_brain_keys"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
