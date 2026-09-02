package com.mekromn.apkbox.bridge

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.security.auth.x500.X500Principal
import kotlin.math.min

data class AdbBridgeStatus(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val host: String = "127.0.0.1",
    val lastConnectedAtEpochMs: Long = 0L,
    val lastError: String = "",
)

class AdbBridgeManager(context: Context) {
    companion object {
        private const val ADB_KEY_ALIAS = "apkbox-wireless-adb"
        private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        private const val EXIT_MARKER = "__APKBOX_EXIT__="
    }

    private val appContext = context.applicationContext
    private val connection = ApkBoxAdbConnectionManager()
    private val _status = MutableStateFlow(AdbBridgeStatus())
    val status: StateFlow<AdbBridgeStatus> = _status.asStateFlow()

    init {
        connection.setApi(Build.VERSION.SDK_INT)
        connection.setHostAddress("127.0.0.1")
        connection.setTimeout(8, TimeUnit.SECONDS)
        connection.setThrowOnUnauthorised(true)
        refreshStatus()
    }

    suspend fun pair(port: Int, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "Enter the Wireless debugging pairing port." }
        require(pairingCode.matches(Regex("\\d{6}"))) { "Pairing code must be six digits." }
        runCatching {
            connection.pair("127.0.0.1", port, pairingCode)
        }.onSuccess { paired ->
            _status.value = _status.value.copy(
                lastError = if (paired) "" else "Pairing was not accepted.",
            )
        }.onFailure { failure ->
            _status.value = _status.value.copy(lastError = failure.message ?: failure.javaClass.simpleName)
        }.getOrDefault(false)
    }

    suspend fun autoConnect(timeoutMs: Long = 7_000L): Boolean = withContext(Dispatchers.IO) {
        if (connection.isConnected) {
            refreshStatus()
            return@withContext true
        }
        _status.value = _status.value.copy(connecting = true, lastError = "")
        runCatching {
            connection.connectTls(appContext, timeoutMs)
            connection.isConnected
        }.onSuccess { connected ->
            _status.value = AdbBridgeStatus(
                connected = connected,
                connecting = false,
                host = connection.hostAddress,
                lastConnectedAtEpochMs = if (connected) System.currentTimeMillis() else 0L,
                lastError = if (connected) "" else "Wireless ADB was not discovered.",
            )
        }.onFailure { failure ->
            _status.value = _status.value.copy(
                connected = false,
                connecting = false,
                lastError = failure.message ?: failure.javaClass.simpleName,
            )
        }.getOrDefault(false)
    }

    suspend fun connect(port: Int): Boolean = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "Invalid ADB connection port." }
        _status.value = _status.value.copy(connecting = true, lastError = "")
        runCatching {
            connection.connect("127.0.0.1", port)
            connection.isConnected
        }.onSuccess { connected ->
            _status.value = AdbBridgeStatus(
                connected = connected,
                connecting = false,
                host = connection.hostAddress,
                lastConnectedAtEpochMs = if (connected) System.currentTimeMillis() else 0L,
                lastError = if (connected) "" else "ADB connection failed.",
            )
        }.onFailure { failure ->
            _status.value = _status.value.copy(
                connected = false,
                connecting = false,
                lastError = failure.message ?: failure.javaClass.simpleName,
            )
        }.getOrDefault(false)
    }

    fun disconnect() {
        runCatching { connection.disconnect() }
        refreshStatus()
    }

    suspend fun ensureConnected(): Boolean {
        if (connection.isConnected) return true
        return autoConnect()
    }

    suspend fun execute(command: String, timeoutSeconds: Int = 20): BridgeShellResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command is empty." }
        check(ensureConnected()) { "Wireless ADB is not connected." }

        val started = System.currentTimeMillis()
        val wrapped = buildString {
            append(command.trim())
            append("\n__apkbox_rc=$?\nprintf '\\n")
            append(EXIT_MARKER)
            append("%d\\n' \"$__apkbox_rc\"")
        }

        val stream = connection.openStream("shell:$wrapped")
        try {
            coroutineScope {
                val reader = async(Dispatchers.IO) {
                    val accumulator = ByteArrayOutputStream(min(MAX_OUTPUT_BYTES, 256 * 1024))
                    var total = 0
                    var truncated = false
                    val buffer = ByteArray(64 * 1024)
                    stream.openInputStream().use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (accumulator.size() < MAX_OUTPUT_BYTES) {
                                val allowed = min(count, MAX_OUTPUT_BYTES - accumulator.size())
                                accumulator.write(buffer, 0, allowed)
                                if (allowed < count) truncated = true
                            } else {
                                truncated = true
                            }
                        }
                    }
                    accumulator.toString(Charsets.UTF_8.name()) to truncated
                }
                try {
                    val (rawOutput, truncated) = withTimeout(timeoutSeconds.coerceIn(1, 120) * 1_000L) {
                        reader.await()
                    }
                    val parsed = parseExitCode(rawOutput)
                    BridgeShellResult(
                        output = parsed.first,
                        exitCode = parsed.second,
                        durationMs = System.currentTimeMillis() - started,
                        truncated = truncated,
                    )
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    runCatching { stream.close() }
                    reader.cancel()
                    BridgeShellResult(
                        output = "Command timed out after ${timeoutSeconds.coerceIn(1, 120)} seconds.",
                        exitCode = null,
                        durationMs = System.currentTimeMillis() - started,
                        timedOut = true,
                    )
                }
            }
        } finally {
            runCatching { stream.close() }
            refreshStatus()
        }
    }

    fun refreshStatus() {
        val connected = connection.isConnected
        _status.value = _status.value.copy(
            connected = connected,
            connecting = false,
            host = connection.hostAddress,
            lastConnectedAtEpochMs = if (connected && _status.value.lastConnectedAtEpochMs == 0L) {
                System.currentTimeMillis()
            } else _status.value.lastConnectedAtEpochMs,
        )
    }

    private fun parseExitCode(raw: String): Pair<String, Int?> {
        val markerIndex = raw.lastIndexOf(EXIT_MARKER)
        if (markerIndex < 0) return raw.trimEnd() to null
        val after = raw.substring(markerIndex + EXIT_MARKER.length)
        val code = after.lineSequence().firstOrNull()?.trim()?.toIntOrNull()
        return raw.substring(0, markerIndex).trimEnd() to code
    }

    private inner class ApkBoxAdbConnectionManager : AbsAdbConnectionManager() {
        private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        init {
            ensureKey()
        }

        override fun getPrivateKey(): PrivateKey =
            keyStore.getKey(ADB_KEY_ALIAS, null) as PrivateKey

        override fun getCertificate(): Certificate =
            keyStore.getCertificate(ADB_KEY_ALIAS)

        override fun getDeviceName(): String = "APKbox-${Build.MODEL}"

        private fun ensureKey() {
            if (keyStore.containsAlias(ADB_KEY_ALIAS)) return
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ADB_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=APKbox Wireless ADB Bridge"))
                    .setCertificateSerialNumber(java.math.BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 60_000L))
                    .setCertificateNotAfter(Date(now + 20L * 365L * 24L * 60L * 60L * 1_000L))
                    .build()
            )
            generator.generateKeyPair()
        }
    }
}
