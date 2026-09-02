package com.mekromn.apkbox.bridge

import android.content.Context
import android.os.Build
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
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
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
        private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        private const val MAX_RAW_BYTES = 16 * 1024 * 1024
        private const val EXIT_MARKER = "__APKBOX_EXIT__="
    }

    private val appContext = context.applicationContext
    private val identity = AdbIdentityStore(appContext).loadOrCreate()
    private val connection = ApkBoxAdbConnectionManager(identity)
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
        runCatching { connection.pair("127.0.0.1", port, pairingCode) }
            .onSuccess { paired ->
                _status.value = _status.value.copy(lastError = if (paired) "" else "Pairing was not accepted.")
            }
            .onFailure { failure ->
                _status.value = _status.value.copy(lastError = failure.message ?: failure.javaClass.simpleName)
            }
            .getOrDefault(false)
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
            append("%d\\n' \"\$__apkbox_rc\"")
        }

        val stream = connection.openStream("shell:$wrapped")
        try {
            coroutineScope {
                val reader = async(Dispatchers.IO) {
                    val (bytes, truncated) = readBounded(stream.openInputStream(), MAX_OUTPUT_BYTES)
                    String(bytes, Charsets.UTF_8) to truncated
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

    suspend fun executeRaw(
        command: String,
        timeoutSeconds: Int = 20,
        maxBytes: Int = MAX_RAW_BYTES,
    ): BridgeRawResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command is empty." }
        require(maxBytes in 1..MAX_RAW_BYTES) { "Raw capture size must be 1..$MAX_RAW_BYTES bytes." }
        check(ensureConnected()) { "Wireless ADB is not connected." }

        val started = System.currentTimeMillis()
        val stream = connection.openStream("shell:${command.trim()}")
        try {
            coroutineScope {
                val reader = async(Dispatchers.IO) { readBounded(stream.openInputStream(), maxBytes) }
                try {
                    val (bytes, truncated) = withTimeout(timeoutSeconds.coerceIn(1, 120) * 1_000L) {
                        reader.await()
                    }
                    BridgeRawResult(
                        bytes = bytes,
                        durationMs = System.currentTimeMillis() - started,
                        truncated = truncated,
                    )
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    runCatching { stream.close() }
                    reader.cancel()
                    BridgeRawResult(
                        bytes = ByteArray(0),
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

    private fun readBounded(input: java.io.InputStream, limit: Int): Pair<ByteArray, Boolean> {
        input.use {
            val accumulator = ByteArrayOutputStream(min(limit, 256 * 1024))
            var truncated = false
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (accumulator.size() < limit) {
                    val allowed = min(count, limit - accumulator.size())
                    accumulator.write(buffer, 0, allowed)
                    if (allowed < count) truncated = true
                } else {
                    truncated = true
                }
            }
            return accumulator.toByteArray() to truncated
        }
    }

    private fun parseExitCode(raw: String): Pair<String, Int?> {
        val markerIndex = raw.lastIndexOf(EXIT_MARKER)
        if (markerIndex < 0) return raw.trimEnd() to null
        val after = raw.substring(markerIndex + EXIT_MARKER.length)
        val code = after.lineSequence().firstOrNull()?.trim()?.toIntOrNull()
        return raw.substring(0, markerIndex).trimEnd() to code
    }

    private class ApkBoxAdbConnectionManager(
        private val identity: AdbIdentityStore.Identity,
    ) : AbsAdbConnectionManager() {
        override fun getPrivateKey(): PrivateKey = identity.privateKey
        override fun getCertificate(): Certificate = identity.certificate
        override fun getDeviceName(): String = "APKbox-${Build.MODEL}"
    }
}
