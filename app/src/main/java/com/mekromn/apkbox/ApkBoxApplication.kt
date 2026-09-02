package com.mekromn.apkbox

import android.app.Application
import com.mekromn.apkbox.bridge.RemoteBridgeService
import com.mekromn.apkbox.data.TempStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ApkBoxApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Listen immediately, but mark all persisted scanner history as already seen. New automatic
        // scan outcomes from any trigger are then surfaced as debounced user-visible toasts.
        AutoScanToastObserver.start(this)

        // Cleanup, Auto Scanner catch-up, and bridge resume are important, but none may hold the
        // first frame. The bridge only resumes if the user explicitly left it enabled.
        startupScope.launch {
            runCatching { TempStorageManager.cleanupStartup(this@ApkBoxApplication) }
            delay(900L)
            runCatching {
                val scanner = ApkBoxServices.autoScanner(this@ApkBoxApplication)
                scanner.reloadFromDisk()
                scanner.scanNow("APKbox process startup")
            }
            delay(500L)
            runCatching {
                val bridgePrefs = ApkBoxServices.bridgePreferences(this@ApkBoxApplication)
                if (bridgePrefs.state.value.enabled && bridgePrefs.state.value.hasRelayToken) {
                    RemoteBridgeService.start(this@ApkBoxApplication)
                }
            }
        }
    }
}
