package com.mekromn.apkbox.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onBackupVault: () -> Unit,
    onRestoreVault: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

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
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Vault options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("APK cleanup") },
                                    onClick = { menuOpen = false; onCleanupApks() },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                modifier = Modifier.navigationBarsPadding(),
                onClick = onNewProject,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New project") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GlobalVaultCard(stats) }
            if (projects.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.FolderZip, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(10.dp))
                            Text("No projects yet", fontWeight = FontWeight.Bold)
                            Text(
                                "Create a project by choosing its base APK. Each project gets its own revisions while all projects share APKbox's global deduplication pool.",
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenProject(project) },
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Icon(Icons.Rounded.Android, null, Modifier.padding(13.dp).size(30.dp))
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    project.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${projectRecords.size} build${if (projectRecords.size == 1) "" else "s"} · ${Formatter.formatFileSize(context, logical)} full-copy size",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

@Composable
private fun GlobalVaultCard(stats: VaultStats) {
    val context = LocalContext.current
    val percent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(stats.savedPercent)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Global space saved", style = MaterialTheme.typography.labelLarge)
                    Text(
                        Formatter.formatFileSize(context, stats.savedBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)) {
                    Text("$percent%", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.size(14.dp))
            Text(
                "Vault ${Formatter.formatFileSize(context, stats.physicalBytes)} · ${Formatter.formatFileSize(context, stats.logicalBytes)} as full APK copies",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
