package com.mekromn.apkbox

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.install.ApkInstaller
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.ReplaceReason
import com.mekromn.apkbox.model.ReplaceRequest
import com.mekromn.apkbox.ui.ApkBoxScreen
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val libraryStore by lazy { LibraryStore(applicationContext) }
    private val apkInstaller by lazy { ApkInstaller(applicationContext, libraryStore) }

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val replaceRequest = MutableStateFlow<ReplaceRequest?>(null)

    private var installWaitingForPermission: ApkRecord? = null
    private var installWaitingForRemoval: ApkRecord? = null

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val record = installWaitingForRemoval ?: return@registerForActivityResult
        installWaitingForRemoval = null

        val stillInstalled = ApkInspector.inspectInstalled(this, record.packageName) != null
        if (result.resultCode == Activity.RESULT_OK || !stillInstalled) {
            requestInstall(record)
        } else {
            message.value = "Replacement cancelled · the installed app was left unchanged"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            APKboxTheme {
                val records = libraryStore.records.collectAsStateWithLifecycle().value
                val stats = libraryStore.stats.collectAsStateWithLifecycle().value
                val isBusy = busy.collectAsStateWithLifecycle().value
                val currentMessage = message.collectAsStateWithLifecycle().value
                val currentReplaceRequest = replaceRequest.collectAsStateWithLifecycle().value

                val basePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) importBase(uri)
                }
                val revisionPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris ->
                    if (uris.isNotEmpty()) importRevisions(uris)
                }

                ApkBoxScreen(
                    records = records,
                    stats = stats,
                    busy = isBusy,
                    message = currentMessage,
                    replaceRequest = currentReplaceRequest,
                    onMessageShown = { message.value = null },
                    onChooseBase = {
                        basePicker.launch(apkMimeTypes())
                    },
                    onAddRevision = {
                        revisionPicker.launch(apkMimeTypes())
                    },
                    onInstall = ::requestInstall,
                    onDelete = ::deleteRevision,
                    onClearVault = ::clearVault,
                    onConfirmReplace = ::confirmReplacement,
                    onCancelReplace = { replaceRequest.value = null },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            installWaitingForPermission?.let { record ->
                installWaitingForPermission = null
                requestInstall(record)
            }
        }
    }

    private fun apkMimeTypes(): Array<String> = arrayOf(
        "application/vnd.android.package-archive",
        "application/octet-stream",
    )

    private fun importBase(uri: Uri) {
        runBusyTask {
            val result = libraryStore.importBase(uri)
            "Base saved · ${result.record.displayName}"
        }
    }

    private fun importRevisions(uris: List<Uri>) {
        runBusyTask {
            var added = 0
            var skipped = 0
            var logicalBytes = 0L
            var reusedBytes = 0L
            var firstFailure: String? = null

            for (uri in uris) {
                runCatching { libraryStore.importRevision(uri) }
                    .onSuccess { result ->
                        added++
                        logicalBytes += result.record.sizeBytes
                        reusedBytes += result.reusedBytes
                    }
                    .onFailure { failure ->
                        skipped++
                        if (firstFailure == null) firstFailure = failure.message
                    }
            }

            if (added == 0) {
                error(firstFailure ?: "No revisions were imported.")
            }

            val reusedPercent = if (logicalBytes == 0L) 0L
            else (reusedBytes * 100L / logicalBytes).coerceIn(0L, 100L)
            buildString {
                append("Saved $added revision")
                if (added != 1) append('s')
                append(" · $reusedPercent% of APK bytes reused")
                if (skipped > 0) append(" · $skipped skipped")
            }
        }
    }

    private fun deleteRevision(record: ApkRecord) {
        runBusyTask {
            libraryStore.deleteRevision(record.id)
            "${record.displayName} removed · unused chunks cleaned up"
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

        val installed = ApkInspector.inspectInstalled(this, record.packageName)
        if (installed != null) {
            val signingMismatch = installed.signingCertSha256 != null &&
                record.signingCertSha256 != null &&
                installed.signingCertSha256 != record.signingCertSha256

            val reason = when {
                signingMismatch -> ReplaceReason.SIGNATURE_MISMATCH
                installed.versionCode > record.versionCode -> ReplaceReason.DOWNGRADE
                else -> null
            }

            if (reason != null) {
                replaceRequest.value = ReplaceRequest(
                    record = record,
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    reason = reason,
                )
                return
            }
        }

        installRecord(record)
    }

    private fun confirmReplacement() {
        val request = replaceRequest.value ?: return
        replaceRequest.value = null
        installWaitingForRemoval = request.record

        // PackageInstaller.uninstall() is restricted to the installer-of-record. APKbox also needs
        // to replace builds that were originally installed by adb, a file manager, or another
        // installer, so use Android's user-confirmed uninstaller activity for this explicit action.
        @Suppress("DEPRECATION")
        val uninstallIntent = Intent(
            Intent.ACTION_UNINSTALL_PACKAGE,
            Uri.parse("package:${request.record.packageName}"),
        ).putExtra(Intent.EXTRA_RETURN_RESULT, true)

        runCatching { uninstallLauncher.launch(uninstallIntent) }
            .onFailure { failure ->
                installWaitingForRemoval = null
                message.value = failure.message ?: "Android could not open the uninstall confirmation."
            }
    }

    private fun installRecord(record: ApkRecord) {
        runBusyTask {
            apkInstaller.install(record)
            "${record.displayName} reconstructed and verified · continue in Android installer"
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
