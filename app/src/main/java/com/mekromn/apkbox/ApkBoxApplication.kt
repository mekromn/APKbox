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
        // Cleanup is important, but it must not hold the first frame hostage. PackageInstaller and
        // cache inspection are independent of rendering the vault index, so do them concurrently.
        startupScope.launch {
            runCatching { TempStorageManager.cleanupStartup(this@ApkBoxApplication) }
        }
    }
}
