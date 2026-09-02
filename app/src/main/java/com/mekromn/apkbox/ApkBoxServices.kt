package com.mekromn.apkbox

import android.content.Context
import com.mekromn.apkbox.bridge.AdbBridgeManager
import com.mekromn.apkbox.bridge.BridgeExecutor
import com.mekromn.apkbox.bridge.BridgePreferences
import com.mekromn.apkbox.bridge.BridgeStateStore
import com.mekromn.apkbox.bridge.GitHubRelayClient
import com.mekromn.apkbox.data.AutoScanManager
import com.mekromn.apkbox.data.LibraryStore

/**
 * Process-local service graph so UI, broadcast receivers, background scanners, and the remote
 * bridge share one writer/connection and one set of StateFlows. Master restore only resets vault
 * services; bridge identity and ADB pairing deliberately live independently of the APK vault.
 */
object ApkBoxServices {
    private val lock = Any()

    @Volatile private var libraryStoreInstance: LibraryStore? = null
    @Volatile private var autoScanManagerInstance: AutoScanManager? = null
    @Volatile private var bridgePreferencesInstance: BridgePreferences? = null
    @Volatile private var bridgeStateStoreInstance: BridgeStateStore? = null
    @Volatile private var adbBridgeManagerInstance: AdbBridgeManager? = null
    @Volatile private var relayClientInstance: GitHubRelayClient? = null
    @Volatile private var bridgeExecutorInstance: BridgeExecutor? = null

    fun libraryStore(context: Context): LibraryStore =
        libraryStoreInstance ?: synchronized(lock) {
            libraryStoreInstance ?: LibraryStore(context.applicationContext).also {
                libraryStoreInstance = it
            }
        }

    fun autoScanner(context: Context): AutoScanManager =
        autoScanManagerInstance ?: synchronized(lock) {
            autoScanManagerInstance ?: AutoScanManager(
                context = context.applicationContext,
                libraryStore = libraryStore(context.applicationContext),
            ).also {
                autoScanManagerInstance = it
            }
        }

    fun bridgePreferences(context: Context): BridgePreferences =
        bridgePreferencesInstance ?: synchronized(lock) {
            bridgePreferencesInstance ?: BridgePreferences(context.applicationContext).also {
                bridgePreferencesInstance = it
            }
        }

    fun bridgeStateStore(context: Context): BridgeStateStore =
        bridgeStateStoreInstance ?: synchronized(lock) {
            bridgeStateStoreInstance ?: BridgeStateStore(context.applicationContext).also {
                bridgeStateStoreInstance = it
            }
        }

    fun adbBridge(context: Context): AdbBridgeManager =
        adbBridgeManagerInstance ?: synchronized(lock) {
            adbBridgeManagerInstance ?: AdbBridgeManager(context.applicationContext).also {
                adbBridgeManagerInstance = it
            }
        }

    fun relayClient(): GitHubRelayClient =
        relayClientInstance ?: synchronized(lock) {
            relayClientInstance ?: GitHubRelayClient().also { relayClientInstance = it }
        }

    fun bridgeExecutor(context: Context): BridgeExecutor =
        bridgeExecutorInstance ?: synchronized(lock) {
            bridgeExecutorInstance ?: BridgeExecutor(
                context = context.applicationContext,
                adb = adbBridge(context.applicationContext),
                stateStore = bridgeStateStore(context.applicationContext),
            ).also { bridgeExecutorInstance = it }
        }

    fun resetVaultServices() {
        synchronized(lock) {
            autoScanManagerInstance?.shutdown()
            autoScanManagerInstance = null
            libraryStoreInstance = null
        }
    }
}
