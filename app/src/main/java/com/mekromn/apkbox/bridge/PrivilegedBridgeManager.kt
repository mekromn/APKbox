package com.mekromn.apkbox.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
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
    val persistentWirelessControl: Boolean = false,
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
    companion object {
        // Settings.Global.ADB_WIFI_ENABLED is @hide from the public SDK; this is its stable AOSP key.
        private const val ADB_WIFI_ENABLED_KEY = "adb_wifi_enabled"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(
        PrivilegedBridgeStatus(
            shizuku = shizuku.status.value,
            adb = adb.status.value,
            persistentWirelessControl = hasPersistentWirelessControl(),
        )
    )
    val status: StateFlow<PrivilegedBridgeStatus> = _status.asStateFlow()

    init {
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

    fun hasPersistentWirelessControl(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * One-time bootstrap. WRITE_SECURE_SETTINGS is an Android development permission, so an already
     * authorized shell/root transport can grant it to APKbox. APKbox then uses the grant only for
     * adb_wifi_enabled, not as a generic settings mutation capability.
     */
    suspend fun bootstrapPersistentWirelessControl(): Boolean {
        if (hasPersistentWirelessControl()) {
            updateStatus()
            return true
        }
        val pkg = appContext.packageName
        val command = "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
        val granted = when {
            shizuku.ensureReady() -> {
                val result = runCatching { shizuku.execute(command, 10) }.getOrNull()
                result != null && !result.timedOut && (result.exitCode == null || result.exitCode == 0)
            }
            adb.ensureConnected() -> {
                val result = runCatching { adb.execute(command, 10) }.getOrNull()
                result != null && !result.timedOut && (result.exitCode == null || result.exitCode == 0)
            }
            else -> false
        }
        if (granted) {
            repeat(5) {
                if (!hasPersistentWirelessControl()) delay(50)
            }
        }
        updateStatus()
        return hasPersistentWirelessControl()
    }

    suspend fun ensureReady(): Boolean = selectTransport() != PrivilegedTransportKind.NONE

    suspend fun execute(command: String, timeoutSeconds: Int = 20): BridgeShellResult {
        val transport = selectTransport()
        val result = when (transport) {
            PrivilegedTransportKind.SHIZUKU_ROOT,
            PrivilegedTransportKind.SHIZUKU_SHELL -> shizuku.execute(command, timeoutSeconds)
            PrivilegedTransportKind.WIRELESS_ADB -> adb.execute(command, timeoutSeconds)
            PrivilegedTransportKind.NONE -> error(unavailableMessage())
        }
        updateStatus()
        return result
    }

    suspend fun executeRaw(
        command: String,
        timeoutSeconds: Int = 20,
        maxBytes: Int = 16 * 1024 * 1024,
    ): BridgeRawResult {
        val transport = selectTransport()
        val result = when (transport) {
            PrivilegedTransportKind.SHIZUKU_ROOT,
            PrivilegedTransportKind.SHIZUKU_SHELL -> shizuku.executeRaw(command, timeoutSeconds, maxBytes)
            PrivilegedTransportKind.WIRELESS_ADB -> adb.executeRaw(command, timeoutSeconds, maxBytes)
            PrivilegedTransportKind.NONE -> error(unavailableMessage())
        }
        updateStatus()
        return result
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
        updateStatus()
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
        updateStatus()
        return result.toPrivileged(transport)
    }

    /**
     * Try every non-interactive route to make APKbox's already-paired Wireless ADB usable.
     *
     * 1. Reuse an existing/self-healed connection.
     * 2. If APKbox has its one-time WRITE_SECURE_SETTINGS grant, enable adb_wifi_enabled locally.
     * 3. If Shizuku/Sui is running, bootstrap that grant (or directly toggle as fallback).
     *
     * Android may still reject Wireless debugging when Wi-Fi is absent or the current network is not
     * trusted. Pairing remains a separate Android authorization and is never bypassed here.
     */
    suspend fun tryStartWirelessDebugging(): Boolean {
        if (adb.ensureConnected()) {
            runCatching { bootstrapPersistentWirelessControl() }
            updateStatus()
            return true
        }

        if (hasPersistentWirelessControl()) {
            if (writeWirelessDebuggingSetting(true)) {
                delay(700L)
                if (adb.autoConnect(timeoutMs = 8_000L)) {
                    updateStatus()
                    return true
                }
            }
        }

        if (!shizuku.ensureReady()) return false
        val persisted = runCatching { bootstrapPersistentWirelessControl() }.getOrDefault(false)
        val enabled = if (persisted) {
            writeWirelessDebuggingSetting(true)
        } else {
            shizuku.enableWirelessDebugging()
        }
        if (!enabled) return false
        delay(700L)
        val connected = adb.autoConnect(timeoutMs = 8_000L)
        updateStatus()
        return connected
    }

    /** Current preferred usable transport, never a stale last-used label. */
    fun activeTransport(): PrivilegedTransportKind = when {
        shizuku.status.value.usable && shizuku.status.value.root -> PrivilegedTransportKind.SHIZUKU_ROOT
        shizuku.status.value.usable -> PrivilegedTransportKind.SHIZUKU_SHELL
        adb.status.value.connected -> PrivilegedTransportKind.WIRELESS_ADB
        else -> PrivilegedTransportKind.NONE
    }

    fun activeTransportLabel(): String = status.value.copy(activeTransport = activeTransport()).activeLabel

    fun rootAvailable(): Boolean = shizuku.status.value.usable && shizuku.status.value.root

    private fun writeWirelessDebuggingSetting(enabled: Boolean): Boolean {
        if (!hasPersistentWirelessControl()) return false
        return runCatching {
            Settings.Global.putInt(
                appContext.contentResolver,
                ADB_WIFI_ENABLED_KEY,
                if (enabled) 1 else 0,
            )
        }.getOrDefault(false)
    }

    /**
     * Selection is shared by every privileged feature. This is where the persistent Wireless ADB
     * self-start capability becomes universal: if Shizuku/Sui is absent and an existing paired ADB
     * server is off, any Screen Agent/build/install/console operation may wake Wireless Debugging and
     * reconnect before reporting that no transport is available.
     */
    private suspend fun selectTransport(): PrivilegedTransportKind {
        if (shizuku.ensureReady()) {
            return if (shizuku.status.value.root) {
                PrivilegedTransportKind.SHIZUKU_ROOT
            } else {
                PrivilegedTransportKind.SHIZUKU_SHELL
            }
        }
        if (adb.ensureConnected()) return PrivilegedTransportKind.WIRELESS_ADB
        if (hasPersistentWirelessControl() && tryStartWirelessDebugging()) {
            return PrivilegedTransportKind.WIRELESS_ADB
        }
        return PrivilegedTransportKind.NONE
    }

    private fun updateStatus() {
        _status.value = PrivilegedBridgeStatus(
            activeTransport = activeTransport(),
            shizuku = shizuku.status.value,
            adb = adb.status.value,
            persistentWirelessControl = hasPersistentWirelessControl(),
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
