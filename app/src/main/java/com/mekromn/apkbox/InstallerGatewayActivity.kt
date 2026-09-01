package com.mekromn.apkbox

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InstallMobile
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
import com.mekromn.apkbox.data.SharedApkAnalyzer
import com.mekromn.apkbox.data.SharedApkPreview
import com.mekromn.apkbox.install.ApkInstaller
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal "Open with APKbox" gateway. This intentionally does not navigate through MainActivity or
 * expose the full vault UI: choose an archive destination, store/verify the exact APK, then hand the
 * stored record to APKbox's normal PackageInstaller path.
 */
class InstallerGatewayActivity : ComponentActivity() {
    companion object {
        private const val CREATE_NEW_PROJECT = "__create_new_project__"
    }

    private val libraryStore by lazy { ApkBoxServices.libraryStore(this) }
    private val apkInstaller by lazy { ApkInstaller(this, libraryStore) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        setContent {
            APKboxTheme {
                val projects = libraryStore.projects.collectAsStateWithLifecycle().value
                val records = libraryStore.records.collectAsStateWithLifecycle().value
                var preview by remember(uri) { mutableStateOf<SharedApkPreview?>(null) }
                var loading by remember(uri) { mutableStateOf(true) }
                var working by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var selectedProjectId by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(uri, records) {
                    if (preview != null) return@LaunchedEffect
                    loading = true
                    error = null
                    runCatching {
                        SharedApkAnalyzer.analyze(this@InstallerGatewayActivity, listOf(uri), records).single()
                    }.onSuccess { analyzed ->
                        preview = analyzed
                        selectedProjectId = projects
                            .firstOrNull { it.packageName == analyzed.packageName }
                            ?.id
                            ?: CREATE_NEW_PROJECT
                    }.onFailure { failure ->
                        error = failure.message ?: "Android could not inspect this APK."
                    }
                    loading = false
                }

                InstallerGatewayScreen(
                    preview = preview,
                    projects = projects,
                    selectedProjectId = selectedProjectId,
                    loading = loading,
                    working = working,
                    error = error,
                    onBack = { finish() },
                    onProjectSelected = { selectedProjectId = it },
                    onArchiveAndInstall = {
                        val analyzed = preview ?: return@InstallerGatewayScreen
                        val destination = selectedProjectId ?: return@InstallerGatewayScreen
                        if (working) return@InstallerGatewayScreen
                        working = true
                        error = null
                        lifecycleScope.launch {
                            runCatching {
                                val record = withContext(Dispatchers.IO) {
                                    archiveForInstall(analyzed, destination, projects, records)
                                }
                                apkInstaller.install(record)
                            }.onSuccess {
                                // PackageInstaller's callback launches Android's native confirmation UI.
                                finish()
                            }.onFailure { failure ->
                                error = failure.message ?: "APKbox could not archive and install this APK."
                                working = false
                            }
                        }
                    },
                )
            }
        }
    }

    private suspend fun archiveForInstall(
        preview: SharedApkPreview,
        destination: String,
        projects: List<ApkProject>,
        records: List<ApkRecord>,
    ): ApkRecord {
        if (destination == CREATE_NEW_PROJECT) {
            return libraryStore.importBase(preview.uri, preview.label).record
        }

        val project = projects.firstOrNull { it.id == destination }
            ?: error("That APKbox Project no longer exists.")
        require(project.packageName == preview.packageName) {
            "${preview.label} belongs to ${preview.packageName}, not ${project.packageName}."
        }

        // An already archived byte-identical APK should never create a duplicate record just because
        // the user opened it through the gateway.
        records.firstOrNull {
            it.projectId == project.id && it.sha256.equals(preview.sha256, ignoreCase = true)
        }?.let { return it }

        return libraryStore.importRevision(project.id, preview.uri).record
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallerGatewayScreen(
    preview: SharedApkPreview?,
    projects: List<ApkProject>,
    selectedProjectId: String?,
    loading: Boolean,
    working: Boolean,
    error: String?,
    onBack: () -> Unit,
    onProjectSelected: (String) -> Unit,
    onArchiveAndInstall: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack, enabled = !working) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Cancel")
                        }
                    },
                    title = { Text("Install app", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (loading || working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                if (preview != null) {
                    AppInstallHeader(preview)
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Rounded.Android, null, Modifier.size(72.dp))
                        Spacer(Modifier.size(16.dp))
                        Text(if (loading) "Checking APK…" else "Could not inspect APK")
                    }
                }
            }

            if (preview != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Archive before installing",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Choose the APKbox Project that should keep this exact APK. After SHA-256 verified archiving, Android's normal package installer opens automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item { HorizontalDivider() }

                val compatible = projects.filter { it.packageName == preview.packageName }
                if (compatible.isNotEmpty()) {
                    items(compatible, key = { it.id }) { project ->
                        val exactAlreadyStored = preview.storedMatches.any { it.projectId == project.id }
                        ProjectChoiceRow(
                            project = project,
                            selected = selectedProjectId == project.id,
                            exactAlreadyStored = exactAlreadyStored,
                            enabled = !working,
                            onClick = { onProjectSelected(project.id) },
                        )
                    }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = if (selectedProjectId == CREATE_NEW_PROJECT) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else MaterialTheme.colorScheme.surfaceContainerLow,
                        onClick = { if (!working) onProjectSelected(CREATE_NEW_PROJECT) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedProjectId == CREATE_NEW_PROJECT,
                                onClick = { onProjectSelected(CREATE_NEW_PROJECT) },
                                enabled = !working,
                            )
                            Icon(Icons.Rounded.Add, null, Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("Create new Project", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Use this APK as the new Project's base",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (error != null) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                error,
                                Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onBack, enabled = !working) { Text("Cancel") }
                        Spacer(Modifier.size(10.dp))
                        Button(
                            onClick = onArchiveAndInstall,
                            enabled = !loading && !working && selectedProjectId != null,
                        ) {
                            Icon(Icons.Rounded.InstallMobile, null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (working) "Archiving…" else "Archive & install")
                        }
                    }
                }
            } else if (error != null) {
                item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AppInstallHeader(preview: SharedApkPreview) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val bitmap = remember(preview.sha256, preview.iconPng) {
            preview.iconPng?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = preview.label, modifier = Modifier.size(82.dp))
        } else {
            Icon(Icons.Rounded.Android, contentDescription = null, modifier = Modifier.size(82.dp))
        }
        Text(
            preview.label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${preview.versionName} · code ${preview.versionCode}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${preview.packageName} · ${Formatter.formatFileSize(context, preview.sizeBytes)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProjectChoiceRow(
    project: ApkProject,
    selected: Boolean,
    exactAlreadyStored: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = { if (enabled) onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Icon(Icons.Rounded.Folder, null, Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    project.packageName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (exactAlreadyStored) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(5.dp))
                        Text(
                            "Exact APK already archived — install stored copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
