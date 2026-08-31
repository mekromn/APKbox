package com.mekromn.apkbox

import android.app.Application
import com.mekromn.apkbox.data.TempStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ApkBoxApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Cleanup and Auto Scanner catch-up are important, but neither may hold the first frame.
        startupScope.launch {
            runCatching { TempStorageManager.cleanupStartup(this@ApkBoxApplication) }
            runCatching {
                val scanner = ApkBoxServices.autoScanner(this@ApkBoxApplication)
                scanner.reloadFromDisk()
                scanner.scanNow("APKbox process startup")
            }
        }
    }
}
