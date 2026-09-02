package com.mekromn.apkbox.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class AdbBridgeStatus(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val host: String = "127.0.0.1",
    val lastConnectedAtEpochMs: Long = 0L,
    val lastVerifiedAtEpochMs: Long = 0L,
    val healPhase: AdbHealPhase = AdbHealPhase.DISCONNECTED,
    val consecutiveFailures: Int = 0,
    val nextRetryAtEpochMs: Long = 0L,
    val wifiAvailable: Boolean = true,
    val userActionRequired: Boolean = false,
    val lastFailureKind: AdbHealFailureKind = AdbHealFailureKind.NONE,
    val lastError: String = "",
)

data class AdbInstallResult(
    val success: Boolean,
    val output: String,
    val durationMs: Long,
    val bytesSent: Long,
    val timedOut: Boolean = false,
)

class AdbBridgeManager(context: Context) {
    companion object {
        private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        private const val MAX_RAW_BYTES = 16 * 1024 * 1024
        private const val EXIT_MARKER = "__APKBOX_EXIT__="
        private const val HEALTH_MARKER = "__APKBOX_ADB_HEALTHY__"
        private const val HEALTH_PROBE_INTERVAL_MS = 30_000L
        private const val INSTALL_TIMEOUT_MS = 5L * 60L * 1_000L
    }

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val identity = AdbIdentityStore(appContext).loadOrCreate()
    private val connection = ApkBoxAdbConnectionManager(identity)
    private val healMutex = Mutex()
    private val installMutex = Mutex()
    private val _status = MutableStateFlow(AdbBridgeStatus())
    val status: StateFlow<AdbBridgeStatus> = _status.asStateFlow()

    @Volatile private var lastKnownPort: Int = 0

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
        healMutex.withLock {
            runCatching { connection.pair("127.0.0.1", port, pairingCode) }
                .onSuccess { paired ->
                    if (paired) {
                        _status.value = _status.value.copy(
                            userActionRequired = false,
                            consecutiveFailures = 0,
                            nextRetryAtEpochMs = 0L,
                            lastFailureKind = AdbHealFailureKind.NONE,
                            lastError = "",
                        )
                    } else {
                        recordHealFailure("Pairing was not accepted.")
                    }
                }
                .onFailure(::recordHealFailure)
                .getOrDefault(false)
        }
    }

    /**
     * Explicit/manual reconnect entry point. Background callers should use autoHeal(force = false)
     * so authorization failures and bounded backoff are respected.
     */
    suspend fun autoConnect(timeoutMs: Long = 7_000L): Boolean =
        autoHeal(force = true, timeoutMs = timeoutMs)

    suspend fun connect(port: Int): Boolean = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "Invalid ADB connection port." }
        healMutex.withLock {
            _status.value = _status.value.copy(
                connecting = true,
                healPhase = AdbHealPhase.REDISCOVERING,
                userActionRequired = false,
                lastError = "",
            )
            runCatching {
                runCatching { connection.disconnect() }
                connection.connect("127.0.0.1", port)
                check(connection.isConnected) { "ADB connection failed." }
                check(probeConnection()) { "ADB connected but health probe failed." }
                lastKnownPort = port
                markHealthy()
                true
            }.onFailure(::recordHealFailure).getOrDefault(false)
        }
    }

    suspend fun autoHeal(
        force: Boolean = false,
        timeoutMs: Long = 7_000L,
    ): Boolean = withContext(Dispatchers.IO) {
        healMutex.withLock {
            val now = System.currentTimeMillis()
            val wifi = wifiAvailable()
            if (!wifi) {
                runCatching { connection.disconnect() }
                _status.value = _status.value.copy(
                    connected = false,
                    connecting = false,
                    wifiAvailable = false,
                    healPhase = AdbHealPhase.WAITING_FOR_WIFI,
                    lastError = "Waiting for Wi-Fi before Wireless ADB rediscovery.",
                )
                return@withLock false
            }

            if (_status.value.userActionRequired && !force) {
                _status.value = _status.value.copy(
                    connected = connection.isConnected,
                    connecting = false,
                    wifiAvailable = true,
                    healPhase = AdbHealPhase.USER_ACTION_REQUIRED,
                )
                return@withLock false
            }

            if (connection.isConnected) {
                if (!force && !AdbAutoHealPolicy.shouldProbe(
                        _status.value.lastVerifiedAtEpochMs,
                        now,
                        HEALTH_PROBE_INTERVAL_MS,
                    )
                ) {
                    refreshStatus()
                    return@withLock true
                }

                _status.value = _status.value.copy(
                    connecting = false,
                    wifiAvailable = true,
                    healPhase = AdbHealPhase.VERIFYING,
                )
                if (runCatching { probeConnection() }.getOrDefault(false)) {
                    markHealthy()
                    return@withLock true
                }
                runCatching { connection.disconnect() }
            }

            val current = _status.value
            if (!force && current.nextRetryAtEpochMs > now) {
                _status.value = current.copy(
                    connected = false,
                    connecting = false,
                    wifiAvailable = true,
                    healPhase = AdbHealPhase.BACKOFF,
                )
                return@withLock false
            }

            if (force) {
                _status.value = current.copy(
                    userActionRequired = false,
                    nextRetryAtEpochMs = 0L,
                )
            }
            _status.value = _status.value.copy(
                connected = false,
                connecting = true,
                wifiAvailable = true,
                healPhase = AdbHealPhase.REDISCOVERING,
                lastError = "",
            )

            val discovery = runCatching {
                runCatching { connection.disconnect() }
                connection.connectTls(appContext, timeoutMs.coerceIn(2_000L, 20_000L))
                check(connection.isConnected) { "Wireless ADB was not discovered." }
                check(probeConnection()) { "Wireless ADB TLS connection failed its health probe." }
                markHealthy()
                true
            }
            if (discovery.getOrNull() == true) return@withLock true

            val discoveryFailure = discovery.exceptionOrNull()
            if (lastKnownPort in 1..65535) {
                val fallback = runCatching {
                    runCatching { connection.disconnect() }
                    connection.connect("127.0.0.1", lastKnownPort)
                    check(connection.isConnected) { "Last-known ADB port is no longer active." }
                    check(probeConnection()) { "Last-known ADB port failed its health probe." }
                    markHealthy()
                    true
                }
                if (fallback.getOrNull() == true) return@withLock true
                recordHealFailure(fallback.exceptionOrNull() ?: discoveryFailure ?: IllegalStateException("Wireless ADB reconnect failed."))
            } else {
                recordHealFailure(discoveryFailure ?: IllegalStateException("Wireless ADB reconnect failed."))
            }
            false
        }
    }

    fun disconnect() {
        runCatching { connection.disconnect() }
        _status.value = _status.value.copy(
            connected = false,
            connecting = false,
            healPhase = AdbHealPhase.DISCONNECTED,
            lastError = "",
        )
    }

    suspend fun ensureConnected(): Boolean {
        val now = System.currentTimeMillis()
        if (connection.isConnected &&
            !AdbAutoHealPolicy.shouldProbe(_status.value.lastVerifiedAtEpochMs, now, HEALTH_PROBE_INTERVAL_MS)
        ) {
            return true
        }
        return autoHeal(force = false)
    }

    suspend fun execute(command: String, timeoutSeconds: Int = 20): BridgeShellResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command is empty." }
        check(ensureConnected()) { healFailureMessage() }

        val started = System.currentTimeMillis()
        try {
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
                        markHealthy()
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
        } catch (failure: Throwable) {
            noteTransportFailure(failure)
            throw failure
        }
    }

    suspend fun executeRaw(
        command: String,
        timeoutSeconds: Int = 20,
        maxBytes: Int = MAX_RAW_BYTES,
    ): BridgeRawResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command is empty." }
        require(maxBytes in 1..MAX_RAW_BYTES) { "Raw capture size must be 1..$MAX_RAW_BYTES bytes." }
        check(ensureConnected()) { healFailureMessage() }

        val started = System.currentTimeMillis()
        try {
            val stream = connection.openStream("shell:${command.trim()}")
            try {
                coroutineScope {
                    val reader = async(Dispatchers.IO) { readBounded(stream.openInputStream(), maxBytes) }
                    try {
                        val (bytes, truncated) = withTimeout(timeoutSeconds.coerceIn(1, 120) * 1_000L) {
                            reader.await()
                        }
                        markHealthy()
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
        } catch (failure: Throwable) {
            noteTransportFailure(failure)
            throw failure
        }
    }

    /**
     * Creates an ADB PackageInstaller session, lets the caller write and verify the exact APK bytes,
     * and only commits after the writer returns successfully. This is the path used by APKbox's
     * user-selected Unattended install action so vault reconstruction can remain zero-copy while
     * retaining a hard pre-commit integrity barrier.
     */
    suspend fun installVerifiedStream(
        totalBytes: Long,
        allowDowngrade: Boolean = false,
        writer: suspend (OutputStream) -> Unit,
    ): AdbInstallResult = withContext(Dispatchers.IO) {
        require(totalBytes > 0L) { "Refusing to install an empty APK." }
        check(ensureConnected()) { healFailureMessage() }

        installMutex.withLock {
            val started = System.currentTimeMillis()
            var sessionId = -1
            var bytesSent = 0L
            try {
                val createCommand = buildString {
                    append("pm install-create -r ")
                    if (allowDowngrade) append("-d ")
                    append("-S ").append(totalBytes)
                }
                val created = execute(createCommand, 30)
                check(!created.timedOut && (created.exitCode == null || created.exitCode == 0)) {
                    "Android could not create an unattended install session: ${created.output.take(2_000)}"
                }
                sessionId = parseInstallSessionId(created.output)
                    ?: error("Android did not return an install session ID: ${created.output.take(2_000)}")

                val writeStream = connection.openStream(
                    "shell:pm install-write -S $totalBytes $sessionId base.apk -"
                )
                val writeOutput = try {
                    coroutineScope {
                        val reader = async(Dispatchers.IO) {
                            val (bytes, _) = readBounded(writeStream.openInputStream(), 256 * 1024)
                            String(bytes, Charsets.UTF_8).trim()
                        }
                        try {
                            withTimeout(INSTALL_TIMEOUT_MS) {
                                val rawSink = writeStream.openOutputStream()
                                val countingSink = object : OutputStream() {
                                    override fun write(value: Int) {
                                        rawSink.write(value)
                                        bytesSent++
                                    }

                                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                        rawSink.write(buffer, offset, length)
                                        bytesSent += length
                                    }

                                    override fun flush() {
                                        rawSink.flush()
                                    }
                                }
                                writer(countingSink)
                                countingSink.flush()
                                check(bytesSent == totalBytes) {
                                    "ADB install session received $bytesSent of $totalBytes bytes."
                                }
                                reader.await()
                            }
                        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                            reader.cancel()
                            throw timeout
                        }
                    }
                } finally {
                    runCatching { writeStream.close() }
                    refreshStatus()
                }

                if (!writeOutput.lineSequence().any { it.trim().equals("Success", ignoreCase = true) }) {
                    abandonInstallSession(sessionId)
                    return@withLock AdbInstallResult(
                        success = false,
                        output = writeOutput.ifBlank { "Android rejected the APK stream before commit." },
                        durationMs = System.currentTimeMillis() - started,
                        bytesSent = bytesSent,
                    )
                }

                // The writer has now returned, which means APKbox's staging SHA-256 verification
                // succeeded. Only now is Android allowed to mutate the installed package.
                val committed = execute("pm install-commit $sessionId", 120)
                val success = !committed.timedOut &&
                    committed.output.lineSequence().any { it.trim().startsWith("Success", ignoreCase = true) }
                if (success) {
                    markHealthy()
                } else {
                    abandonInstallSession(sessionId)
                }
                AdbInstallResult(
                    success = success,
                    output = committed.output.ifBlank {
                        if (success) "Success" else "Android package manager returned no commit result."
                    },
                    durationMs = System.currentTimeMillis() - started,
                    bytesSent = bytesSent,
                    timedOut = committed.timedOut,
                )
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                if (sessionId >= 0) abandonInstallSession(sessionId)
                AdbInstallResult(
                    success = false,
                    output = "Unattended ADB install timed out after ${INSTALL_TIMEOUT_MS / 1_000L} seconds.",
                    durationMs = System.currentTimeMillis() - started,
                    bytesSent = bytesSent,
                    timedOut = true,
                )
            } catch (failure: Throwable) {
                if (sessionId >= 0) abandonInstallSession(sessionId)
                refreshStatus()
                throw failure
            }
        }
    }

    /**
     * Streams an already-verified APK directly to Android's package manager over the paired ADB
     * channel. No world-readable staging path is created. The `-S` byte count lets package manager
     * consume exactly the file length from stdin and complete without relying on an EOF signal.
     */
    suspend fun installApk(
        apkFile: File,
        allowDowngrade: Boolean = false,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): AdbInstallResult = withContext(Dispatchers.IO) {
        require(apkFile.isFile && apkFile.canRead()) { "Verified APK is missing or unreadable." }
        val totalBytes = apkFile.length()
        require(totalBytes > 0L) { "Refusing to install an empty APK." }
        check(ensureConnected()) { healFailureMessage() }

        installMutex.withLock {
            val started = System.currentTimeMillis()
            var bytesSent = 0L
            val command = buildString {
                append("pm install -r ")
                if (allowDowngrade) append("-d ")
                append("-S ").append(totalBytes).append(" -")
            }
            try {
                val stream = connection.openStream("shell:$command")
                try {
                    coroutineScope {
                        val reader = async(Dispatchers.IO) {
                            val (bytes, _) = readBounded(stream.openInputStream(), 256 * 1024)
                            String(bytes, Charsets.UTF_8).trim()
                        }
                        try {
                            val output = withTimeout(INSTALL_TIMEOUT_MS) {
                                val sink = stream.openOutputStream()
                                FileInputStream(apkFile).use { source ->
                                    val buffer = ByteArray(1024 * 1024)
                                    while (true) {
                                        val count = source.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        sink.write(buffer, 0, count)
                                        bytesSent += count
                                        onProgress(bytesSent, totalBytes)
                                    }
                                }
                                check(bytesSent == totalBytes) {
                                    "ADB install stream sent $bytesSent of $totalBytes bytes."
                                }
                                sink.flush()
                                reader.await()
                            }
                            val success = output.lineSequence().any { it.trim().equals("Success", ignoreCase = true) }
                            if (success) markHealthy()
                            AdbInstallResult(
                                success = success,
                                output = output.ifBlank { if (success) "Success" else "Package manager returned no result." },
                                durationMs = System.currentTimeMillis() - started,
                                bytesSent = bytesSent,
                            )
                        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                            runCatching { stream.close() }
                            reader.cancel()
                            AdbInstallResult(
                                success = false,
                                output = "Unattended ADB install timed out after ${INSTALL_TIMEOUT_MS / 1_000L} seconds.",
                                durationMs = System.currentTimeMillis() - started,
                                bytesSent = bytesSent,
                                timedOut = true,
                            )
                        }
                    }
                } finally {
                    runCatching { stream.close() }
                    refreshStatus()
                }
            } catch (failure: Throwable) {
                noteTransportFailure(failure)
                throw failure
            }
        }
    }

    fun refreshStatus() {
        val connected = connection.isConnected
        val current = _status.value
        _status.value = current.copy(
            connected = connected,
            connecting = false,
            host = connection.hostAddress,
            wifiAvailable = wifiAvailable(),
            healPhase = when {
                connected && current.healPhase !in setOf(AdbHealPhase.VERIFYING, AdbHealPhase.REDISCOVERING) -> AdbHealPhase.HEALTHY
                !connected && current.userActionRequired -> AdbHealPhase.USER_ACTION_REQUIRED
                else -> current.healPhase
            },
            lastConnectedAtEpochMs = if (connected && current.lastConnectedAtEpochMs == 0L) {
                System.currentTimeMillis()
            } else current.lastConnectedAtEpochMs,
        )
    }

    private suspend fun probeConnection(): Boolean {
        if (!connection.isConnected) return false
        val stream = connection.openStream("shell:printf $HEALTH_MARKER")
        return try {
            val bytes = withTimeout(4_000L) {
                val (payload, _) = readBounded(stream.openInputStream(), 4 * 1024)
                payload
            }
            String(bytes, Charsets.UTF_8).contains(HEALTH_MARKER)
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun markHealthy() {
        val now = System.currentTimeMillis()
        val current = _status.value
        _status.value = current.copy(
            connected = true,
            connecting = false,
            host = connection.hostAddress,
            lastConnectedAtEpochMs = if (current.connected && current.lastConnectedAtEpochMs > 0L) {
                current.lastConnectedAtEpochMs
            } else now,
            lastVerifiedAtEpochMs = now,
            healPhase = AdbHealPhase.HEALTHY,
            consecutiveFailures = 0,
            nextRetryAtEpochMs = 0L,
            wifiAvailable = true,
            userActionRequired = false,
            lastFailureKind = AdbHealFailureKind.NONE,
            lastError = "",
        )
    }

    private fun noteTransportFailure(failure: Throwable) {
        runCatching { connection.disconnect() }
        recordHealFailure(failure)
    }

    private suspend fun abandonInstallSession(sessionId: Int) {
        if (sessionId < 0) return
        runCatching { execute("pm install-abandon $sessionId", 10) }
    }

    private fun parseInstallSessionId(output: String): Int? =
        Regex("\\[(\\d+)]").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun recordHealFailure(failure: Throwable) =
        recordHealFailure(failure.message ?: failure.javaClass.simpleName)

    private fun recordHealFailure(message: String) {
        val now = System.currentTimeMillis()
        val current = _status.value
        val failures = current.consecutiveFailures + 1
        val kind = AdbAutoHealPolicy.failureKind(message)
        val actionRequired = AdbAutoHealPolicy.requiresUserAction(kind)
        _status.value = current.copy(
            connected = false,
            connecting = false,
            healPhase = if (actionRequired) AdbHealPhase.USER_ACTION_REQUIRED else AdbHealPhase.BACKOFF,
            consecutiveFailures = failures,
            nextRetryAtEpochMs = if (actionRequired) 0L else now + AdbAutoHealPolicy.backoffMs(failures),
            wifiAvailable = wifiAvailable(),
            userActionRequired = actionRequired,
            lastFailureKind = kind,
            lastError = message.take(1_000),
        )
    }

    private fun healFailureMessage(): String {
        val state = _status.value
        return when (state.healPhase) {
            AdbHealPhase.WAITING_FOR_WIFI -> "Wireless ADB is waiting for Wi-Fi."
            AdbHealPhase.USER_ACTION_REQUIRED -> "Wireless ADB authorization needs attention: ${state.lastError}"
            AdbHealPhase.BACKOFF -> "Wireless ADB reconnect is backing off after ${state.consecutiveFailures} failures: ${state.lastError}"
            else -> "Wireless ADB is not connected${state.lastError.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}."
        }
    }

    private fun wifiAvailable(): Boolean = runCatching {
        connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrDefault(true)

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
