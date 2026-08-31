package com.mekromn.apkbox

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.install.ApkInstaller
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.ui.ApkBoxScreen
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val libraryStore by lazy { LibraryStore(applicationContext) }
    private val apkInstaller by lazy { ApkInstaller(applicationContext, libraryStore) }

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private var installWaitingForPermission: ApkRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            APKboxTheme {
                val records = libraryStore.records.collectAsStateWithLifecycle().value
                val stats = libraryStore.stats.collectAsStateWithLifecycle().value
                val isBusy = busy.collectAsStateWithLifecycle().value
                val currentMessage = message.collectAsStateWithLifecycle().value

                val basePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) importBase(uri)
                }
                val revisionPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) importRevision(uri)
                }

                ApkBoxScreen(
                    records = records,
                    stats = stats,
                    busy = isBusy,
                    message = currentMessage,
                    onMessageShown = { message.value = null },
                    onChooseBase = {
                        basePicker.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream",
                            )
                        )
                    },
                    onAddRevision = {
                        revisionPicker.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream",
                            )
                        )
                    },
                    onInstall = ::requestInstall,
                    onDelete = ::deleteRevision,
                    onClearVault = ::clearVault,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            installWaitingForPermission?.let { record ->
                installWaitingForPermission = null
                installRecord(record)
            }
        }
    }

    private fun importBase(uri: Uri) {
        runBusyTask {
            val result = libraryStore.importBase(uri)
            "Base saved: ${result.record.label} ${result.record.versionName}"
        }
    }

    private fun importRevision(uri: Uri) {
        runBusyTask {
            val result = libraryStore.importRevision(uri)
            val percent = if (result.record.sizeBytes == 0L) 0
            else (result.reusedBytes * 100L / result.record.sizeBytes).coerceIn(0L, 100L)
            "Revision saved · $percent% of APK bytes reused"
        }
    }

    private fun deleteRevision(record: ApkRecord) {
        runBusyTask {
            libraryStore.deleteRevision(record.id)
            "${record.versionName} removed and unused chunks cleaned up"
        }
    }

    private fun clearVault() {
        runBusyTask {
            libraryStore.clearVault()
            "APKbox vault cleared"
        }
    }

    private fun requestInstall(record: ApkRecord) {
        if (busy.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            installWaitingForPermission = record
            message.value = "Allow APKbox to install unknown apps, then return here."
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        installRecord(record)
    }

    private fun installRecord(record: ApkRecord) {
        runBusyTask {
            apkInstaller.install(record)
            "${record.versionName} reconstructed and verified · continue in Android installer"
        }
    }

    private fun runBusyTask(block: suspend () -> String) {
        if (busy.value) return
        lifecycleScope.launch {
            busy.value = true
            try {
                message.value = block()
            } catch (t: Throwable) {
                message.value = t.message?.takeIf { it.isNotBlank() }
                    ?: "Operation failed: ${t::class.java.simpleName}"
            } finally {
                busy.value = false
            }
        }
    }
}
