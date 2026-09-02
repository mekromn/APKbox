package com.mekromn.apkbox.bridge

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BridgeConfig(
    val enabled: Boolean,
    val repoOwner: String,
    val repoName: String,
    val deviceId: String,
    val pollSeconds: Int,
    val allowInformational: Boolean,
    val allowPopups: Boolean,
    val trustedUntilEpochMs: Long,
    val paired: Boolean,
    val hasRelayToken: Boolean,
) {
    val trustedNow: Boolean get() = trustedUntilEpochMs > System.currentTimeMillis()
}

class BridgePreferences(context: Context) {
    companion object {
        private const val PREFS = "apkbox-remote-bridge"
        private const val TOKEN_KEY_ALIAS = "apkbox-remote-bridge-token"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secretStore = AndroidSecretStore(prefs, TOKEN_KEY_ALIAS)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<BridgeConfig> = _state.asStateFlow()

    fun relayToken(): String = secretStore.read().orEmpty()

    fun setRelayToken(value: String) {
        if (value.isBlank()) secretStore.clear() else secretStore.write(value.trim())
        refresh()
    }

    fun setEnabled(value: Boolean) = edit { putBoolean("enabled", value) }
    fun setRepo(owner: String, name: String) = edit {
        putString("repoOwner", owner.trim())
        putString("repoName", name.trim())
    }
    fun setPollSeconds(seconds: Int) = edit { putInt("pollSeconds", seconds.coerceIn(5, 300)) }
    fun setAllowInformational(value: Boolean) = edit { putBoolean("allowInformational", value) }
    fun setAllowPopups(value: Boolean) = edit { putBoolean("allowPopups", value) }
    fun setPaired(value: Boolean) = edit { putBoolean("paired", value) }
    fun setTrustedUntil(epochMs: Long) = edit { putLong("trustedUntil", epochMs) }
    fun endTrustedSession() = setTrustedUntil(0L)

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        refresh()
    }

    private fun refresh() {
        _state.value = load()
    }

    private fun load(): BridgeConfig {
        val deviceId = prefs.getString("deviceId", null)?.takeIf { it.isNotBlank() } ?: run {
            val model = Build.MODEL.orEmpty()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(24)
                .ifBlank { "android" }
            val created = "apkbox-$model-${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString("deviceId", created).commit()
            created
        }
        return BridgeConfig(
            enabled = prefs.getBoolean("enabled", false),
            repoOwner = prefs.getString("repoOwner", "mekromn") ?: "mekromn",
            repoName = prefs.getString("repoName", "Continuity") ?: "Continuity",
            deviceId = deviceId,
            pollSeconds = prefs.getInt("pollSeconds", 10).coerceIn(5, 300),
            allowInformational = prefs.getBoolean("allowInformational", true),
            allowPopups = prefs.getBoolean("allowPopups", true),
            trustedUntilEpochMs = prefs.getLong("trustedUntil", 0L),
            paired = prefs.getBoolean("paired", false),
            hasRelayToken = !secretStore.read().isNullOrBlank(),
        )
    }
}

private class AndroidSecretStore(
    private val prefs: android.content.SharedPreferences,
    private val alias: String,
) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("relayTokenCipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("relayTokenIv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun read(): String? = runCatching {
        val cipherText = prefs.getString("relayTokenCipher", null) ?: return@runCatching null
        val iv = prefs.getString("relayTokenIv", null) ?: return@runCatching null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun clear() {
        prefs.edit().remove("relayTokenCipher").remove("relayTokenIv").apply()
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
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
