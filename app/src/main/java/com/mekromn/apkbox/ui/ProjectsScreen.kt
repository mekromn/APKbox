package com.mekromn.apkbox.ui

import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mekromn.apkbox.AutoScanActivity
import com.mekromn.apkbox.bridge.BridgeActivity
import com.mekromn.apkbox.data.TempStorageManager
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.VaultStats
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    projects: List<ApkProject>,
    records: List<ApkRecord>,
    stats: VaultStats,
    busy: Boolean,
    onOpenProject: (ApkProject) -> Unit,
    onNewProject: () -> Unit,
    onCleanupApks: () -> Unit,
    onRegenerateAllIcons: () -> Unit,
    onBackupVault: () -> Unit,
    onRestoreVault: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var regenerateIconsRequested by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("APKbox", fontWeight = FontWeight.Bold)
                            Text(
                                "${projects.size} project${if (projects.size == 1) "" else "s"} · ${records.size} stored build${if (records.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(enabled = !busy, onClick = onNewProject) {
                            Icon(Icons.Rounded.Add, contentDescription = "New project")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Vault options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Remote Debug Bridge") },
                                    onClick = {
                                        menuOpen = false
                                        context.startActivity(Intent(context, BridgeActivity::class.java))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Auto Scanner") },
                                    onClick = {
                                        menuOpen = false
                                        context.startActivity(Intent(context, AutoScanActivity::class.java))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("APK cleanup") },
                                    onClick = { menuOpen = false; onCleanupApks() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Regenerate all icons") },
                                    enabled = !busy && records.isNotEmpty(),
                                    onClick = { menuOpen = false; regenerateIconsRequested = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Free temporary install space") },
                                    onClick = {
                                        menuOpen = false
                                        val result = TempStorageManager.cleanupAll(context)
                                        val detail = buildString {
                                            append("Freed ${Formatter.formatFileSize(context, result.bytesDeleted)}")
                                            if (result.filesDeleted > 0) append(" · ${result.filesDeleted} scratch file${if (result.filesDeleted == 1) "" else "s"}")
                                            if (result.installerSessionsAbandoned > 0) append(" · ${result.installerSessionsAbandoned} staged install${if (result.installerSessionsAbandoned == 1) "" else "s"} cancelled")
                                        }
                                        Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Master backup") },
                                    onClick = { menuOpen = false; onBackupVault() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Restore master backup") },
                                    onClick = { menuOpen = false; onRestoreVault() },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = padding.calculateTopPadding() + 6.dp,
                bottom = padding.calculateBottomPadding() + 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { CompactVaultSummary(stats) }
            if (projects.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.FolderZip, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(8.dp))
                            Text("No projects yet", fontWeight = FontWeight.Bold)
                            Text(
                                "Tap + to choose a base APK. Projects keep their own revisions while sharing APKbox's global deduplication pool.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    val projectRecords = records.filter { it.projectId == project.id }
                    val base = projectRecords.firstOrNull { it.isBase }
                    val logical = projectRecords.sumOf { it.sizeBytes }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenProject(project) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StoredApkIcon(base, Modifier.size(56.dp), project.name)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    project.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    project.packageName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${projectRecords.size} build${if (projectRecords.size == 1) "" else "s"} · ${Formatter.formatFileSize(context, logical)} full-copy size",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (base != null) {
                                    Text(
                                        "Base: ${base.displayName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (regenerateIconsRequested) {
        AlertDialog(
            onDismissRequest = { regenerateIconsRequested = false },
            title = { Text("Regenerate all app icons?") },
            text = {
                Text("APKbox will reconstruct each stored APK one at a time, extract Android's declared application icon, then immediately delete the temporary APK before moving to the next build.")
            },
            confirmButton = {
                TextButton(onClick = { regenerateIconsRequested = false; onRegenerateAllIcons() }) {
                    Text("Regenerate all")
                }
            },
            dismissButton = {
                TextButton(onClick = { regenerateIconsRequested = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CompactVaultSummary(stats: VaultStats) {
    val context = LocalContext.current
    val percent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(stats.savedPercent)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            "Saved ${Formatter.formatFileSize(context, stats.savedBytes)} ($percent%) · ${Formatter.formatFileSize(context, stats.physicalBytes)} vault · ${Formatter.formatFileSize(context, stats.logicalBytes)} full copies",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
