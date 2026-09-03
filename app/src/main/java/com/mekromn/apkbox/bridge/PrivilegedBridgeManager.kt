package com.mekromn.apkbox.bridge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream


enum class PrivilegedTransportKind {
    NONE,
    SHIZUKU_ROOT,
    SHIZUKU_SHELL,
    WIRELESS_ADB,
}

data class PrivilegedBridgeStatus(
    val activeTransport: PrivilegedTransportKind = PrivilegedTransportKind.NONE,
    val shizuku: ShizukuBridgeStatus = ShizukuBridgeStatus(),
    val adb: AdbBridgeStatus = AdbBridgeStatus(),
) {
    val ready: Boolean
        get() = shizuku.usable || adb.connected

    val activeLabel: String
        get() = when (activeTransport) {
            PrivilegedTransportKind.SHIZUKU_ROOT -> "Shizuku/Sui · root"
            PrivilegedTransportKind.SHIZUKU_SHELL -> "Shizuku · shell"
            PrivilegedTransportKind.WIRELESS_ADB -> "Wireless ADB"
            PrivilegedTransportKind.NONE -> when {
                shizuku.usable -> if (shizuku.root) "Shizuku/Sui · root" else "Shizuku · shell"
                adb.connected -> "Wireless ADB"
                else -> "No privileged transport"
            }
        }
}

data class PrivilegedInstallResult(
    val success: Boolean,
    val output: String,
    val durationMs: Long,
    val bytesSent: Long,
    val timedOut: Boolean = false,
    val transport: PrivilegedTransportKind,
)

/**
 * Single privileged execution surface for APKbox.
 *
 * Shizuku/Sui is preferred when it is already authorized and running because it does not require
 * APKbox's Wireless ADB link or a Wi-Fi network. Wireless ADB remains the fully supported peer and
 * automatic fallback. Once an operation is dispatched, APKbox never replays it on another backend:
 * an ambiguous transport failure must not cause a mutating command to execute twice.
 */
class PrivilegedBridgeManager(
    context: Context,
    val adb: AdbBridgeManager,
    val shizuku: ShizukuBridgeManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(
        PrivilegedBridgeStatus(
            shizuku = shizuku.status.value,
            adb = adb.status.value,
        )
    )
    val status: StateFlow<PrivilegedBridgeStatus> = _status.asStateFlow()

    @Volatile private var lastSelected = PrivilegedTransportKind.NONE

    init {
        @Suppress("UNUSED_VARIABLE")
        val appContext = context.applicationContext
        scope.launch {
            shizuku.status.collect { updateStatus() }
        }
        scope.launch {
            adb.status.collect { updateStatus() }
        }
    }

    fun requestShizukuPermission(): Boolean = shizuku.requestPermission()

    fun refreshStatus() {
        shizuku.refreshStatus()
        adb.refreshStatus()
        updateStatus()
    }

    suspend fun ensureReady(): Boolean = selectTransport() != PrivilegedTransportKind.NONE

    suspend fun execute(command: String, timeoutSeconds: Int = 20): BridgeShellResult {
        return when (val transport = selectTransport()) {
            PrivilegedTransportKind.SHIZUKU_ROOT,
            PrivilegedTransportKind.SHIZUKU_SHELL -> shizuku.execute(command, timeoutSeconds)
            PrivilegedTransportKind.WIRELESS_ADB -> adb.execute(command, timeoutSeconds)
            PrivilegedTransportKind.NONE -> error(unavailableMessage())
        }.also { markSelected(transport) }
    }

    suspend fun executeRaw(
        command: String,
        timeoutSeconds: Int = 20,
        maxBytes: Int = 16 * 1024 * 1024,
    ): BridgeRawResult {
        return when (val transport = selectTransport()) {
            PrivilegedTransportKind.SHIZUKU_ROOT,
            PrivilegedTransportKind.SHIZUKU_SHELL -> shizuku.executeRaw(command, timeoutSeconds, maxBytes)
            PrivilegedTransportKind.WIRELESS_ADB -> adb.executeRaw(command, timeoutSeconds, maxBytes)
            PrivilegedTransportKind.NONE -> error(unavailableMessage())
        }.also { markSelected(transport) }
    }

    suspend fun installVerifiedStream(
        totalBytes: Long,
        allowDowngrade: Boolean = false,
        writer: suspend (OutputStream) -> Unit,
    ): PrivilegedInstallResult {
        val transport = selectTransport()
        val result = when (transport) {
            PrivilegedTransportKind.SHIZUKU_ROOT,
            PrivilegedTransportKind.SHIZUKU_SHELL -> shizuku.installVerifiedStream(
                totalBytes = totalBytes,
                allowDowngrade = allowDowngrade,
                writer = writer,
            )
            PrivilegedTransportKind.WIRELESS_ADB -> adb.installVerifiedStream(
                totalBytes = totalBytes,
                allowDowngrade = allowDowngrade,
                writer = writer,
            )
            PrivilegedTransportKind.NONE -> error(unavailableMessage())
        }
        markSelected(transport)
        return result.toPrivileged(transport)
    }

    suspend fun installApk(
        apkFile: File,
        allowDowngrade: Boolean = false,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): PrivilegedInstallResult {
        val transport = selectTransport()
        val result = when (transport) {
            PrivilegedTransportKind.SHIZUKU_ROOT,
            PrivilegedTransportKind.SHIZUKU_SHELL -> shizuku.installApk(apkFile, allowDowngrade, onProgress)
            PrivilegedTransportKind.WIRELESS_ADB -> adb.installApk(apkFile, allowDowngrade, onProgress)
            PrivilegedTransportKind.NONE -> error(unavailableMessage())
        }
        markSelected(transport)
        return result.toPrivileged(transport)
    }

    /**
     * Try to make APKbox's already-paired Wireless ADB transport usable without opening Settings.
     * If Shizuku/Sui is active, it can set Android's official adb_wifi_enabled global setting first.
     * This never performs pairing or weakens Android authorization; an unpaired device still needs
     * the one-time Pairing Assistant flow.
     */
    suspend fun tryStartWirelessDebugging(): Boolean {
        if (adb.ensureConnected()) {
            markSelected(PrivilegedTransportKind.WIRELESS_ADB)
            return true
        }
        if (!shizuku.ensureReady()) return false
        if (!shizuku.enableWirelessDebugging()) return false
        delay(700L)
        val connected = adb.autoConnect(timeoutMs = 8_000L)
        if (connected) markSelected(PrivilegedTransportKind.WIRELESS_ADB)
        return connected
    }

    fun activeTransport(): PrivilegedTransportKind = when {
        lastSelected != PrivilegedTransportKind.NONE -> lastSelected
        shizuku.status.value.usable && shizuku.status.value.root -> PrivilegedTransportKind.SHIZUKU_ROOT
        shizuku.status.value.usable -> PrivilegedTransportKind.SHIZUKU_SHELL
        adb.status.value.connected -> PrivilegedTransportKind.WIRELESS_ADB
        else -> PrivilegedTransportKind.NONE
    }

    fun activeTransportLabel(): String = status.value.copy(activeTransport = activeTransport()).activeLabel

    fun rootAvailable(): Boolean = shizuku.status.value.usable && shizuku.status.value.root

    private suspend fun selectTransport(): PrivilegedTransportKind {
        if (shizuku.ensureReady()) {
            return if (shizuku.status.value.root) {
                PrivilegedTransportKind.SHIZUKU_ROOT
            } else {
                PrivilegedTransportKind.SHIZUKU_SHELL
            }
        }
        if (adb.ensureConnected()) return PrivilegedTransportKind.WIRELESS_ADB
        return PrivilegedTransportKind.NONE
    }

    private fun markSelected(transport: PrivilegedTransportKind) {
        lastSelected = transport
        updateStatus()
    }

    private fun updateStatus() {
        _status.value = PrivilegedBridgeStatus(
            activeTransport = activeTransport(),
            shizuku = shizuku.status.value,
            adb = adb.status.value,
        )
    }

    private fun unavailableMessage(): String {
        val shizukuState = shizuku.status.value
        val adbState = adb.status.value
        return when {
            shizukuState.binderAvailable && !shizukuState.permissionGranted ->
                "No privileged transport is ready. Shizuku is running but APKbox permission is not granted, and Wireless ADB is unavailable."
            !adbState.wifiAvailable ->
                "No privileged transport is ready. Shizuku/Sui is unavailable and Wireless ADB has no Wi-Fi network."
            adbState.userActionRequired ->
                "No privileged transport is ready. Shizuku/Sui is unavailable and Wireless ADB requires authorization attention."
            else -> "No privileged transport is ready. Start/authorize Shizuku or Sui, or connect APKbox Wireless ADB."
        }
    }

    private fun AdbInstallResult.toPrivileged(transport: PrivilegedTransportKind) = PrivilegedInstallResult(
        success = success,
        output = output,
        durationMs = durationMs,
        bytesSent = bytesSent,
        timedOut = timedOut,
        transport = transport,
    )
}
