package com.mekromn.apkbox.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream


enum class ShizukuPrivilegeMode {
    UNAVAILABLE,
    NEEDS_PERMISSION,
    SHELL,
    ROOT,
}

data class ShizukuBridgeStatus(
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val serviceReady: Boolean = false,
    val uid: Int = -1,
    val mode: ShizukuPrivilegeMode = ShizukuPrivilegeMode.UNAVAILABLE,
    val lastError: String = "",
) {
    val usable: Boolean get() = serviceReady && permissionGranted && uid in setOf(0, 2000)
    val root: Boolean get() = usable && uid == 0
}

/**
 * Process-local Shizuku/Sui transport. The remote UserService executes as shell or root, while all
 * policy and exact-byte verification stays in APKbox's ordinary app process.
 */
class ShizukuBridgeManager(context: Context) {
    companion object {
        const val PERMISSION_REQUEST_CODE = 76_201
        private const val EXIT_MARKER = "__APKBOX_EXIT__="
        private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        private const val MAX_RAW_BYTES = 16 * 1024 * 1024
        private const val INSTALL_TIMEOUT_MS = 5L * 60L * 1_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bindMutex = Mutex()
    private val installMutex = Mutex()
    private val _status = MutableStateFlow(ShizukuBridgeStatus())
    val status: StateFlow<ShizukuBridgeStatus> = _status.asStateFlow()

    @Volatile private var service: IShizukuShellService? = null
    @Volatile private var binding = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, ShizukuShellUserService::class.java),
    )
        .daemon(false)
        .processNameSuffix("privileged")
        .tag("apkbox-privileged-shell")
        .version(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            val remote = binder?.let(IShizukuShellService.Stub::asInterface)
            service = remote
            val uid = runCatching { remote?.privilegedUid ?: -1 }.getOrDefault(-1)
            _status.value = ShizukuBridgeStatus(
                binderAvailable = binderAvailable(),
                permissionGranted = permissionGranted(),
                serviceReady = remote != null && binder?.pingBinder() == true,
                uid = uid,
                mode = modeFor(uid, permissionGranted = true),
                lastError = "",
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            service = null
            refreshStatus("Shizuku privileged service disconnected.")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
        if (permissionGranted()) scope.launch { bindIfAllowed() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binding = false
        service = null
        _status.value = ShizukuBridgeStatus(
            binderAvailable = false,
            permissionGranted = false,
            serviceReady = false,
            uid = -1,
            mode = ShizukuPrivilegeMode.UNAVAILABLE,
            lastError = "Shizuku/Sui service is not running.",
        )
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            refreshStatus()
            scope.launch { bindIfAllowed() }
        } else {
            service = null
            _status.value = _status.value.copy(
                permissionGranted = false,
                serviceReady = false,
                uid = -1,
                mode = ShizukuPrivilegeMode.NEEDS_PERMISSION,
                lastError = "Shizuku permission was denied.",
            )
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshStatus()
    }

    fun requestPermission(): Boolean {
        if (!binderAvailable()) {
            refreshStatus("Start Shizuku, or install/enable Sui on a rooted device.")
            return false
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            refreshStatus("This Shizuku version is too old for APKbox UserService support.")
            return false
        }
        if (permissionGranted()) {
            scope.launch { bindIfAllowed() }
            return true
        }
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            refreshStatus("Shizuku permission is blocked. Allow APKbox from Shizuku's authorized apps screen.")
            return false
        }
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.onFailure { refreshStatus(it.message ?: "Could not request Shizuku permission.") }
            .getOrDefault(false)
    }

    fun refreshStatus(error: String = "") {
        val binder = binderAvailable()
        val granted = binder && permissionGranted()
        val remote = service
        val remoteAlive = remote?.asBinder()?.pingBinder() == true
        val uid = if (remoteAlive) runCatching { remote.privilegedUid }.getOrDefault(-1) else -1
        _status.value = ShizukuBridgeStatus(
            binderAvailable = binder,
            permissionGranted = granted,
            serviceReady = remoteAlive,
            uid = uid,
            mode = when {
                !binder -> ShizukuPrivilegeMode.UNAVAILABLE
                !granted -> ShizukuPrivilegeMode.NEEDS_PERMISSION
                uid == 0 -> ShizukuPrivilegeMode.ROOT
                uid == 2000 -> ShizukuPrivilegeMode.SHELL
                else -> ShizukuPrivilegeMode.UNAVAILABLE
            },
            lastError = error,
        )
    }

    suspend fun ensureReady(timeoutMs: Long = 2_500L): Boolean {
        val remote = service
        if (remote?.asBinder()?.pingBinder() == true) {
            refreshStatus()
            return true
        }
        if (!binderAvailable() || !permissionGranted()) {
            refreshStatus()
            return false
        }
        bindIfAllowed()
        return withTimeoutOrNull(timeoutMs.coerceIn(250L, 10_000L)) {
            status.first { it.serviceReady || !it.binderAvailable || !it.permissionGranted }.serviceReady
        } ?: false
    }

    suspend fun execute(command: String, timeoutSeconds: Int = 20): BridgeShellResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command is empty." }
        check(ensureReady()) { failureMessage() }
        val remote = service ?: error(failureMessage())
        val started = System.currentTimeMillis()
        val wrapped = buildString {
            append(command.trim())
            append("\n__apkbox_rc=$?\nprintf '\\n")
            append(EXIT_MARKER)
            append("%d\\n' \"\$__apkbox_rc\"")
        }
        val descriptor = remote.openShell(wrapped)
        readShellDescriptor(descriptor, timeoutSeconds, MAX_OUTPUT_BYTES, started)
    }

    suspend fun executeRaw(
        command: String,
        timeoutSeconds: Int = 20,
        maxBytes: Int = MAX_RAW_BYTES,
    ): BridgeRawResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command is empty." }
        require(maxBytes in 1..MAX_RAW_BYTES) { "Raw capture size must be 1..$MAX_RAW_BYTES bytes." }
        check(ensureReady()) { failureMessage() }
        val remote = service ?: error(failureMessage())
        val started = System.currentTimeMillis()
        val descriptor = remote.openShell(command.trim())
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        coroutineScope {
            val reader = async(Dispatchers.IO) { readBounded(input, maxBytes) }
            try {
                val (bytes, truncated) = withTimeout(timeoutSeconds.coerceIn(1, 120) * 1_000L) {
                    reader.await()
                }
                BridgeRawResult(
                    bytes = bytes,
                    durationMs = System.currentTimeMillis() - started,
                    truncated = truncated,
                )
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                runCatching { input.close() }
                reader.cancel()
                BridgeRawResult(
                    bytes = ByteArray(0),
                    durationMs = System.currentTimeMillis() - started,
                    timedOut = true,
                )
            } finally {
                runCatching { input.close() }
            }
        }
    }

    suspend fun installVerifiedStream(
        totalBytes: Long,
        allowDowngrade: Boolean = false,
        writer: suspend (OutputStream) -> Unit,
    ): AdbInstallResult = withContext(Dispatchers.IO) {
        require(totalBytes > 0L) { "Refusing to install an empty APK." }
        check(ensureReady()) { failureMessage() }

        installMutex.withLock {
            val started = System.currentTimeMillis()
            var sessionId = -1
            var bytesSent = 0L
            try {
                val created = execute(
                    buildString {
                        append("pm install-create -r ")
                        if (allowDowngrade) append("-d ")
                        append("-S ").append(totalBytes)
                    },
                    30,
                )
                check(!created.timedOut && (created.exitCode == null || created.exitCode == 0)) {
                    "Android could not create a Shizuku install session: ${created.output.take(2_000)}"
                }
                sessionId = parseInstallSessionId(created.output)
                    ?: error("Android did not return an install session ID: ${created.output.take(2_000)}")

                val remote = service ?: error(failureMessage())
                val descriptor = remote.openInstallWrite(sessionId, totalBytes)
                val rawSink = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                try {
                    val countingSink = object : OutputStream() {
                        override fun write(value: Int) {
                            rawSink.write(value)
                            bytesSent++
                        }

                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            rawSink.write(buffer, offset, length)
                            bytesSent += length
                        }

                        override fun flush() = rawSink.flush()
                    }
                    withTimeout(INSTALL_TIMEOUT_MS) {
                        writer(countingSink)
                        countingSink.flush()
                    }
                    check(bytesSent == totalBytes) {
                        "Shizuku install session received $bytesSent of $totalBytes bytes."
                    }
                } finally {
                    runCatching { rawSink.close() }
                }

                val writeOutput = withContext(Dispatchers.IO) {
                    remote.finishInstallWrite(sessionId, (INSTALL_TIMEOUT_MS / 1_000L).toInt())
                }
                val parsedWrite = parseExitCode(writeOutput)
                if (parsedWrite.second != 0 ||
                    !parsedWrite.first.lineSequence().any { it.trim().equals("Success", ignoreCase = true) }
                ) {
                    runCatching { remote.abandonInstall(sessionId) }
                    return@withLock AdbInstallResult(
                        success = false,
                        output = parsedWrite.first.ifBlank { "Android rejected the APK stream before commit." },
                        durationMs = System.currentTimeMillis() - started,
                        bytesSent = bytesSent,
                    )
                }

                // The caller's exact-byte writer has returned successfully, so its full APK SHA-256
                // gate has passed. Only now may Android mutate the installed package.
                val committed = execute("pm install-commit $sessionId", 120)
                val success = !committed.timedOut &&
                    committed.output.lineSequence().any { it.trim().startsWith("Success", ignoreCase = true) }
                if (!success) runCatching { remote.abandonInstall(sessionId) }
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
                service?.let { remote -> if (sessionId >= 0) runCatching { remote.abandonInstall(sessionId) } }
                AdbInstallResult(
                    success = false,
                    output = "Shizuku install timed out after ${INSTALL_TIMEOUT_MS / 1_000L} seconds.",
                    durationMs = System.currentTimeMillis() - started,
                    bytesSent = bytesSent,
                    timedOut = true,
                )
            } catch (failure: Throwable) {
                service?.let { remote -> if (sessionId >= 0) runCatching { remote.abandonInstall(sessionId) } }
                refreshStatus(failure.message ?: failure.javaClass.simpleName)
                throw failure
            }
        }
    }

    suspend fun installApk(
        apkFile: File,
        allowDowngrade: Boolean = false,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): AdbInstallResult {
        require(apkFile.isFile && apkFile.canRead()) { "Verified APK is missing or unreadable." }
        val totalBytes = apkFile.length()
        return installVerifiedStream(totalBytes, allowDowngrade) { sink ->
            FileInputStream(apkFile).use { source ->
                val buffer = ByteArray(1024 * 1024)
                var sent = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    sink.write(buffer, 0, count)
                    sent += count
                    onProgress(sent, totalBytes)
                }
            }
        }
    }

    /** Shell/root Shizuku can flip Android's official ADB_WIFI_ENABLED global setting. */
    suspend fun enableWirelessDebugging(): Boolean {
        if (!ensureReady()) return false
        val result = runCatching { execute("settings put global adb_wifi_enabled 1", 8) }.getOrNull() ?: return false
        return !result.timedOut && (result.exitCode == null || result.exitCode == 0)
    }

    private suspend fun bindIfAllowed() {
        bindMutex.withLock {
            if (service?.asBinder()?.pingBinder() == true || binding) return
            if (!binderAvailable() || !permissionGranted()) return
            binding = true
            runCatching { Shizuku.bindUserService(userServiceArgs, serviceConnection) }
                .onFailure { failure ->
                    binding = false
                    refreshStatus(failure.message ?: "Could not bind APKbox Shizuku UserService.")
                }
        }
    }

    private fun binderAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun permissionGranted(): Boolean = binderAvailable() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private fun modeFor(uid: Int, permissionGranted: Boolean): ShizukuPrivilegeMode = when {
        !binderAvailable() -> ShizukuPrivilegeMode.UNAVAILABLE
        !permissionGranted -> ShizukuPrivilegeMode.NEEDS_PERMISSION
        uid == 0 -> ShizukuPrivilegeMode.ROOT
        uid == 2000 -> ShizukuPrivilegeMode.SHELL
        else -> ShizukuPrivilegeMode.UNAVAILABLE
    }

    private suspend fun readShellDescriptor(
        descriptor: ParcelFileDescriptor,
        timeoutSeconds: Int,
        maxBytes: Int,
        started: Long,
    ): BridgeShellResult {
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        return coroutineScope {
            val reader = async(Dispatchers.IO) { readBounded(input, maxBytes) }
            try {
                val (bytes, truncated) = withTimeout(timeoutSeconds.coerceIn(1, 120) * 1_000L) {
                    reader.await()
                }
                val parsed = parseExitCode(String(bytes, Charsets.UTF_8))
                BridgeShellResult(
                    output = parsed.first,
                    exitCode = parsed.second,
                    durationMs = System.currentTimeMillis() - started,
                    truncated = truncated,
                )
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                runCatching { input.close() }
                reader.cancel()
                BridgeShellResult(
                    output = "Command timed out after ${timeoutSeconds.coerceIn(1, 120)} seconds.",
                    exitCode = null,
                    durationMs = System.currentTimeMillis() - started,
                    timedOut = true,
                )
            } finally {
                runCatching { input.close() }
            }
        }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
        val output = ByteArrayOutputStream(minOf(maxBytes, 128 * 1024))
        val buffer = ByteArray(64 * 1024)
        var stored = 0
        var truncated = false
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            val room = maxBytes - stored
            if (room > 0) {
                val accepted = minOf(room, count)
                output.write(buffer, 0, accepted)
                stored += accepted
                if (accepted < count) truncated = true
            } else {
                truncated = true
            }
        }
        return output.toByteArray() to truncated
    }

    private fun parseExitCode(raw: String): Pair<String, Int?> {
        val match = Regex("(?:^|\\n)${Regex.escape(EXIT_MARKER)}(-?\\d+)\\s*$").find(raw)
            ?: return raw.trimEnd() to null
        val exit = match.groupValues[1].toIntOrNull()
        return raw.substring(0, match.range.first).trimEnd() to exit
    }

    private fun parseInstallSessionId(output: String): Int? {
        val patterns = listOf(
            Regex("\\[(\\d+)]"),
            Regex("session(?: ID)?[ =:]+(\\d+)", RegexOption.IGNORE_CASE),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    private fun failureMessage(): String = when {
        !binderAvailable() -> "Shizuku/Sui is not running."
        !permissionGranted() -> "APKbox does not have Shizuku permission."
        _status.value.lastError.isNotBlank() -> _status.value.lastError
        else -> "APKbox Shizuku privileged service is unavailable."
    }
}
