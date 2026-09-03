package com.mekromn.apkbox

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.install.ApkInstaller
import com.mekromn.apkbox.install.InstallProgress
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class UnattendedInstallUiState(
    val record: ApkRecord? = null,
    val phase: String = "Preparing…",
    val detail: String = "",
    val progress: InstallProgress? = null,
    val busy: Boolean = true,
    val success: Boolean = false,
)

class UnattendedInstallActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_RECORD_ID = "recordId"

        fun intent(context: Context, recordId: String): Intent =
            Intent(context, UnattendedInstallActivity::class.java)
                .putExtra(EXTRA_RECORD_ID, recordId)
    }

    private val libraryStore by lazy { ApkBoxServices.libraryStore(applicationContext) }
    private val privileged by lazy { ApkBoxServices.privilegedBridge(applicationContext) }
    private val installer by lazy { ApkInstaller(applicationContext, libraryStore) }
    private val state = MutableStateFlow(UnattendedInstallUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            APKboxTheme {
                val ui by state.collectAsStateWithLifecycle()
                UnattendedInstallScreen(
                    state = ui,
                    onClose = { finish() },
                    onRetry = { ui.record?.let(::startInstall) },
                )
            }
        }

        val recordId = intent.getStringExtra(EXTRA_RECORD_ID).orEmpty()
        val record = libraryStore.records.value.firstOrNull { it.id == recordId }
        if (record == null) {
            state.value = UnattendedInstallUiState(
                phase = "APK not found",
                detail = "That archived APK is no longer available in APKbox.",
                busy = false,
            )
            return
        }
        startInstall(record)
    }

    private fun startInstall(record: ApkRecord) {
        if (state.value.busy && state.value.record != null) return
        lifecycleScope.launch {
            state.value = UnattendedInstallUiState(
                record = record,
                phase = "Verifying unattended install",
                detail = "Checking the installed package and available privileged transports…",
                busy = true,
            )

            try {
                var ready = privileged.ensureReady()
                if (!ready) {
                    val shizuku = privileged.shizuku.status.value
                    if (shizuku.binderAvailable && !shizuku.permissionGranted) {
                        privileged.requestShizukuPermission()
                        error("Shizuku is running but APKbox is not authorized yet. Approve the Shizuku permission prompt, then tap Retry.")
                    }

                    val bridgeConfig = ApkBoxServices.bridgePreferences(applicationContext).state.value
                    if (bridgeConfig.paired) {
                        ready = privileged.tryStartWirelessDebugging()
                    }
                    check(ready || privileged.ensureReady()) {
                        if (bridgeConfig.paired) {
                            "Neither Shizuku/Sui nor Wireless ADB is currently usable. APKbox attempted its Wireless ADB reconnect path. Open Remote Debug Bridge if Android requires Wireless debugging attention."
                        } else {
                            "No privileged install transport is ready. Start and authorize Shizuku/Sui, or use Remote Debug Bridge → Auto-open & pair once for Wireless ADB."
                        }
                    }
                }

                val installed = ApkInspector.inspectInstalled(this@UnattendedInstallActivity, record.packageName)
                val signingMismatch = installed != null &&
                    !installed.signingCertSha256.isNullOrBlank() &&
                    !record.signingCertSha256.isNullOrBlank() &&
                    !installed.signingCertSha256.equals(record.signingCertSha256, ignoreCase = true)
                check(!signingMismatch) {
                    "Unattended in-place install was blocked because the installed app is signed with a different certificate. Use the dedicated Uninstall & Reinstall mode for intentional signature-conflict replacement."
                }

                val allowDowngrade = installed?.versionCode?.let { it > record.versionCode } == true
                val transport = privileged.activeTransportLabel()
                state.value = state.value.copy(
                    phase = "Opening verified install session",
                    detail = if (allowDowngrade) {
                        "Older revision detected. APKbox will request an in-place downgrade through $transport. The install session will not commit until the complete outgoing APK SHA-256 is verified; no automatic uninstall is allowed."
                    } else {
                        "APKbox will stream the exact archived bytes through $transport into an uncommitted Android install session. Android is not allowed to commit until the full outgoing APK SHA-256 is verified."
                    },
                )

                val result = installer.installUnattended(
                    record = record,
                    privileged = privileged,
                    allowDowngrade = allowDowngrade,
                    onProgress = { progress ->
                        state.value = state.value.copy(
                            phase = "Installing unattended",
                            detail = "Streaming exact APK bytes through ${privileged.activeTransportLabel()}. Commit remains blocked until full SHA-256 verification succeeds…",
                            progress = progress,
                        )
                    },
                )

                state.value = state.value.copy(
                    phase = "Installed & verified",
                    detail = "The outgoing APK passed full SHA-256 verification before commit, Android package manager reported success, and the installed base.apk SHA-256 matches the APKbox archive. ${result.durationMs} ms via ${privileged.activeTransportLabel()}.",
                    progress = InstallProgress(record.sizeBytes, record.sizeBytes, directPreparedSource = false),
                    busy = false,
                    success = true,
                )

                val launch = packageManager.getLaunchIntentForPackage(record.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    runCatching { startActivity(launch) }
                    finish()
                } else {
                    Toast.makeText(
                        this@UnattendedInstallActivity,
                        "${record.label} installed and verified",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (failure: Throwable) {
                val status = privileged.status.value
                val shizuku = status.shizuku
                val adb = status.adb
                val transportHint = when {
                    shizuku.binderAvailable && !shizuku.permissionGranted ->
                        "\n\nShizuku is running but APKbox permission is not granted."
                    shizuku.lastError.isNotBlank() -> "\n\nShizuku/Sui: ${shizuku.lastError}"
                    adb.userActionRequired -> "\n\nWireless ADB requires attention: ${adb.lastError.ifBlank { "re-enable Wireless debugging or re-pair APKbox." }}"
                    !adb.wifiAvailable -> "\n\nWireless ADB is waiting for Wi-Fi; Shizuku/Sui can work without Wi-Fi when available."
                    adb.lastError.isNotBlank() -> "\n\nWireless ADB: ${adb.lastError}"
                    else -> ""
                }
                state.value = state.value.copy(
                    phase = "Unattended install stopped",
                    detail = (failure.message ?: failure.javaClass.simpleName) + transportHint,
                    progress = null,
                    busy = false,
                    success = false,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnattendedInstallScreen(
    state: UnattendedInstallUiState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Close")
                        }
                    },
                    title = { Text("Unattended install", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (state.busy) {
                    val progress = state.progress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                if (state.success) Icons.Rounded.CheckCircle else Icons.Rounded.Shield,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            state.record?.let { record ->
                Text(
                    record.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${record.versionName} · code ${record.versionCode}\n${record.packageName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.size(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(state.phase, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (state.detail.isNotBlank()) {
                        Text(
                            state.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.progress?.let { progress ->
                        val percent = (progress.fraction * 100f).toInt().coerceIn(0, 100)
                        Text(
                            "$percent% · ${progress.bytesWritten} / ${progress.totalBytes} bytes",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (!state.busy && !state.success && state.record != null) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry unattended install")
                }
            }
            if (!state.busy) {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.success) "Done" else "Close")
                }
            }
        }
    }
}
