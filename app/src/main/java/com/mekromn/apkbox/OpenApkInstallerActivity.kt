package com.mekromn.apkbox

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.SharedApkAnalyzer
import com.mekromn.apkbox.data.SharedApkPreview
import com.mekromn.apkbox.install.ApkInstaller
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.ReplaceReason
import com.mekromn.apkbox.model.ReplaceRequest
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenApkInstallerActivity : ComponentActivity() {
    companion object {
        private const val NEW_PROJECT = "__new_project__"
    }

    private val libraryStore by lazy { ApkBoxServices.libraryStore(applicationContext) }
    private val apkInstaller by lazy { ApkInstaller(applicationContext, libraryStore) }

    private val preview = MutableStateFlow<SharedApkPreview?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val replaceRequest = MutableStateFlow<ReplaceRequest?>(null)

    private var sourceUri: Uri? = null
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
            message.value = "Replacement cancelled · installed app left unchanged"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sourceUri = intent?.data

        setContent {
            APKboxTheme {
                val projects = libraryStore.projects.collectAsStateWithLifecycle().value
                val records = libraryStore.records.collectAsStateWithLifecycle().value
                val currentPreview = preview.collectAsStateWithLifecycle().value
                val isBusy = busy.collectAsStateWithLifecycle().value
                val currentMessage = message.collectAsStateWithLifecycle().value
                val currentReplace = replaceRequest.collectAsStateWithLifecycle().value

                OpenApkInstallerScreen(
                    preview = currentPreview,
                    projects = projects.filter { project ->
                        currentPreview == null || project.packageName == currentPreview.packageName
                    },
                    records = records,
                    busy = isBusy,
                    message = currentMessage,
                    replaceRequest = currentReplace,
                    onCancel = { finish() },
                    onArchiveAndInstall = ::archiveAndInstall,
                    onConfirmReplace = ::confirmReplacement,
                    onCancelReplace = { replaceRequest.value = null },
                )
            }
        }

        val uri = sourceUri
        if (uri == null) {
            message.value = "No APK was supplied."
        } else {
            lifecycleScope.launch {
                busy.value = true
                try {
                    preview.value = SharedApkAnalyzer.analyze(
                        this@OpenApkInstallerActivity,
                        listOf(uri),
                        libraryStore.records.value,
                    ).single()
                } catch (t: Throwable) {
                    message.value = t.message ?: "Android could not inspect this APK."
                } finally {
                    busy.value = false
                }
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

    private fun archiveAndInstall(projectChoice: String) {
        val incoming = preview.value ?: return
        if (busy.value) return

        lifecycleScope.launch {
            busy.value = true
            message.value = null
            try {
                val record = withContext(Dispatchers.IO) {
                    if (projectChoice == NEW_PROJECT) {
                        libraryStore.importBase(incoming.uri, incoming.label).record
                    } else {
                        val alreadyHere = libraryStore.records.value.firstOrNull {
                            it.projectId == projectChoice && it.sha256 == incoming.sha256
                        }
                        alreadyHere ?: libraryStore.importRevision(projectChoice, incoming.uri).record
                    }
                }
                busy.value = false
                requestInstall(record)
            } catch (t: Throwable) {
                message.value = t.message ?: "APKbox could not archive this APK."
                busy.value = false
            }
        }
    }

    private fun requestInstall(record: ApkRecord) {
        if (busy.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            installWaitingForPermission = record
            message.value = "Allow APKbox to install unknown apps, then return here."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        val installed = ApkInspector.inspectInstalled(this, record.packageName)
        if (installed != null) {
            val signingMismatch = installed.signingCertSha256 != null &&
                record.signingCertSha256 != null && installed.signingCertSha256 != record.signingCertSha256
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
        @Suppress("DEPRECATION")
        val uninstallIntent = Intent(
            Intent.ACTION_UNINSTALL_PACKAGE,
            Uri.parse("package:${request.record.packageName}"),
        ).putExtra(Intent.EXTRA_RETURN_RESULT, true)
        runCatching { uninstallLauncher.launch(uninstallIntent) }
            .onFailure { failure ->
                installWaitingForRemoval = null
                message.value = failure.message ?: "Android could not open uninstall confirmation."
            }
    }

    private fun installRecord(record: ApkRecord) {
        if (busy.value) return
        lifecycleScope.launch {
            busy.value = true
            try {
                apkInstaller.install(record)
                finish()
            } catch (t: Throwable) {
                message.value = t.message ?: "Installation could not be staged."
            } finally {
                busy.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenApkInstallerScreen(
    preview: SharedApkPreview?,
    projects: List<com.mekromn.apkbox.model.ApkProject>,
    records: List<ApkRecord>,
    busy: Boolean,
    message: String?,
    replaceRequest: ReplaceRequest?,
    onCancel: () -> Unit,
    onArchiveAndInstall: (String) -> Unit,
    onConfirmReplace: () -> Unit,
    onCancelReplace: () -> Unit,
) {
    var selected by remember(preview?.packageName, projects) {
        mutableStateOf(projects.firstOrNull()?.id ?: NEW_PROJECT)
    }

    LaunchedEffect(preview?.packageName, projects.map { it.id }) {
        if (selected != NEW_PROJECT && projects.none { it.id == selected }) {
            selected = projects.firstOrNull()?.id ?: NEW_PROJECT
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Cancel")
                        }
                    },
                    title = { Text("APKbox Installer", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            if (preview != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
                        Button(enabled = !busy, onClick = { onArchiveAndInstall(selected) }) {
                            Text("Archive & install")
                        }
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
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                preview == null && message == null -> {
                    Spacer(Modifier.size(72.dp))
                    Text("Reading APK…", style = MaterialTheme.typography.titleLarge)
                }
                preview != null -> {
                    IncomingApkIcon(preview)
                    Spacer(Modifier.size(16.dp))
                    Text(
                        preview.label,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        preview.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${preview.versionName} · code ${preview.versionCode} · ${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, preview.sizeBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.size(24.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                "Choose where to archive this APK",
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            projects.forEach { project ->
                                val alreadyStored = records.any {
                                    it.projectId == project.id && it.sha256 == preview.sha256
                                }
                                InstallerProjectRow(
                                    title = project.name,
                                    subtitle = if (alreadyStored) "Exact APK already archived here" else project.packageName,
                                    selected = selected == project.id,
                                    icon = if (alreadyStored) Icons.Rounded.CheckCircle else Icons.Rounded.Folder,
                                    onClick = { selected = project.id },
                                )
                                HorizontalDivider()
                            }
                            InstallerProjectRow(
                                title = "Create new Project",
                                subtitle = "Use ${preview.label} as the new base APK",
                                selected = selected == NEW_PROJECT,
                                icon = Icons.Rounded.CreateNewFolder,
                                onClick = { selected = NEW_PROJECT },
                            )
                        }
                    }

                    Spacer(Modifier.size(16.dp))
                    Text(
                        "APKbox archives and verifies the exact APK first. Android's normal installation confirmation appears afterward.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!message.isNullOrBlank()) {
                Spacer(Modifier.size(18.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }

    replaceRequest?.let { request ->
        val installed = request.installedVersionName
            ?.let { "$it (code ${request.installedVersionCode})" }
            ?: "code ${request.installedVersionCode}"
        val reason = when (request.reason) {
            ReplaceReason.DOWNGRADE -> "This archived APK is older than the currently installed $installed."
            ReplaceReason.SIGNATURE_MISMATCH -> "This archived APK is signed differently from the currently installed $installed."
        }
        AlertDialog(
            onDismissRequest = onCancelReplace,
            title = { Text("Replace installed app?") },
            text = { Text("$reason\n\nAndroid requires the installed app to be removed first. Uninstalling removes that app's local data; the APKbox archive remains safe.") },
            confirmButton = { TextButton(onClick = onConfirmReplace) { Text("Uninstall & install") } },
            dismissButton = { TextButton(onClick = onCancelReplace) { Text("Cancel") } },
        )
    }
}

@Composable
private fun IncomingApkIcon(preview: SharedApkPreview) {
    val bitmap = remember(preview.sha256, preview.iconPng) {
        preview.iconPng?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = preview.label, modifier = Modifier.size(88.dp))
    } else {
        Icon(
            Icons.Rounded.Android,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstallerProjectRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
