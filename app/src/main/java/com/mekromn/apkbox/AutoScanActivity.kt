package com.mekromn.apkbox

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekromn.apkbox.ui.AutoScanScreen
import com.mekromn.apkbox.ui.theme.APKboxTheme

class AutoScanActivity : ComponentActivity() {
    private val libraryStore by lazy { ApkBoxServices.libraryStore(applicationContext) }
    private val scanner by lazy { ApkBoxServices.autoScanner(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            APKboxTheme {
                val projects = libraryStore.projects.collectAsStateWithLifecycle().value
                AutoScanScreen(
                    manager = scanner,
                    projects = projects,
                    hasDirectFileAccess = hasDirectFileAccess(),
                    onRequestFileAccess = ::requestDirectFileAccess,
                    onDismiss = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scanner.reloadFromDisk()
        scanner.scanAsync("Auto Scanner screen resume")
    }

    private fun hasDirectFileAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun requestDirectFileAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(appIntent) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}
