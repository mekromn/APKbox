package com.mekromn.apkbox.agent

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
 * Optional read-only credential for private build sources. It is intentionally separate from the
 * Continuity relay token so users can scope each fine-grained token to the minimum repositories and
 * permissions it needs. The value never appears in run/build manifests or backups.
 */
class BuildSourceCredentials(context: Context) {
    companion object {
        private const val PREFS = "apkbox-build-source"
        private const val KEY_ALIAS = "apkbox-build-source-token"
        private const val CIPHER_KEY = "tokenCipher"
        private const val IV_KEY = "tokenIv"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasToken(): Boolean = !readToken().isNullOrBlank()

    fun writeToken(value: String) {
        val clean = value.trim()
        if (clean.isBlank()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(CIPHER_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun readToken(): String? = runCatching {
        val encrypted = prefs.getString(CIPHER_KEY, null) ?: return@runCatching null
        val iv = prefs.getString(IV_KEY, null) ?: return@runCatching null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun clear() {
        prefs.edit().remove(CIPHER_KEY).remove(IV_KEY).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
