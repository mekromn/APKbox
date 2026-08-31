package com.mekromn.apkbox

import android.app.Application
import com.mekromn.apkbox.data.TempStorageManager

class ApkBoxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Recover space from interrupted imports/shares and genuinely stale PackageInstaller
        // sessions before the UI or vault does any work.
        runCatching { TempStorageManager.cleanupStartup(this) }
    }
}
