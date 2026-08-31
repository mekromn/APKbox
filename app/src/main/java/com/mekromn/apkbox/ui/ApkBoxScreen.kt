package com.mekromn.apkbox.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.ReplaceReason
import com.mekromn.apkbox.model.ReplaceRequest
import com.mekromn.apkbox.model.VaultStats
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkBoxScreen(
    project: ApkProject,
    records: List<ApkRecord>,
    globalStats: VaultStats,
    busy: Boolean,
    message: String?,
    replaceRequest: ReplaceRequest?,
    onMessageShown: () -> Unit,
    onBackToProjects: () -> Unit,
    onAddRevision: () -> Unit,
    onInstall: (ApkRecord) -> Unit,
    onExport: (ApkRecord) -> Unit,
    onShare: (ApkRecord) -> Unit,
    onDelete: (ApkRecord) -> Unit,
    onDeleteProject: () -> Unit,
    onRenameProject: (String) -> Unit,
    onToggleStar: (ApkRecord) -> Unit,
    onUpdateDetails: (ApkRecord, String, String) -> Unit,
    onRegenerateIcon: (ApkRecord) -> Unit,
    onConfirmReplace: () -> Unit,
    onCancelReplace: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var starredOnly by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<ApkRecord?>(null) }
    var editCandidate by remember { mutableStateOf<ApkRecord?>(null) }
    var deleteProjectRequested by remember { mutableStateOf(false) }
    var renameRequested by remember { mutableStateOf(false) }

    val base = records.firstOrNull { it.isBase }
    val revisions = records.filterNot { it.isBase }
    val visibleRevisions = revisions.filter { record ->
        (!starredOnly || record.starred) && (
            query.isBlank() || listOf(
                record.displayName,
                record.label,
                record.versionName,
                record.versionCode.toString(),
                record.sha256,
                record.description,
                record.notes,
            ).any { it.contains(query, ignoreCase = true) }
        )
    }

    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBackToProjects) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Projects")
                        }
                    },
                    title = {
                        Column {
                            Text(project.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                project.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Project options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename project") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { menuOpen = false; renameRequested = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete project") },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                    onClick = { menuOpen = false; deleteProjectRequested = true },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                AnimatedVisibility(busy) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        },
        floatingActionButton = {
            if (base != null) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = onAddRevision,
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Add revisions") },
                )
            }
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
            item { ProjectStorageCard(records, globalStats) }
            if (base != null) {
                item { SectionLabel("BASE") }
                item {
                    RevisionCard(
                        record = base,
                        base = base,
                        busy = busy,
                        onInstall = { onInstall(base) },
                        onExport = { onExport(base) },
                        onShare = { onShare(base) },
                        onToggleStar = { onToggleStar(base) },
                        onEdit = { editCandidate = base },
                        onDelete = null,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionLabel("REVISIONS") }
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            revisions.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    placeholder = { Text("Search filename, version, notes, description, or hash") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                )
            }
            item {
                FilterChip(
                    selected = starredOnly,
                    onClick = { starredOnly = !starredOnly },
                    label = { Text("Starred only") },
                    leadingIcon = {
                        Icon(
                            if (starredOnly) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            null,
                            Modifier.size(18.dp),
                        )
                    },
                )
            }
            if (visibleRevisions.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.FolderZip, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                when {
                                    starredOnly -> "No starred revisions"
                                    query.isBlank() -> "No revisions yet"
                                    else -> "No matching revisions"
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else {
                items(visibleRevisions, key = { it.id }) { record ->
                    RevisionCard(
                        record = record,
                        base = base ?: record,
                        busy = busy,
                        onInstall = { onInstall(record) },
                        onExport = { onExport(record) },
                        onShare = { onShare(record) },
                        onToggleStar = { onToggleStar(record) },
                        onEdit = { editCandidate = record },
                        onDelete = { deleteCandidate = record },
                    )
                }
            }
        }
    }

    deleteCandidate?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete ${record.displayName}?") },
            text = { Text("Unused chunks will be removed; chunks shared by other revisions or projects stay in the global vault.") },
            confirmButton = {
                TextButton(onClick = { deleteCandidate = null; onDelete(record) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
        )
    }

    editCandidate?.let { record ->
        var description by remember(record.id, record.description) { mutableStateOf(record.description) }
        var notes by remember(record.id, record.notes) { mutableStateOf(record.notes) }
        AlertDialog(
            onDismissRequest = { editCandidate = null },
            title = { Text("APK details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(record.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Description") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Notes") },
                        minLines = 3,
                        maxLines = 7,
                    )
                    TextButton(enabled = !busy, onClick = { onRegenerateIcon(record) }) {
                        Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.size(6.dp))
                        Text("Regenerate this icon")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        editCandidate = null
                        onUpdateDetails(record, description, notes)
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editCandidate = null }) { Text("Cancel") } },
        )
    }

    if (renameRequested) {
        var name by remember(project.id, project.name) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renameRequested = false },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Project name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !busy,
                    onClick = {
                        renameRequested = false
                        onRenameProject(name.trim())
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameRequested = false }) { Text("Cancel") } },
        )
    }

    if (deleteProjectRequested) {
        AlertDialog(
            onDismissRequest = { deleteProjectRequested = false },
            title = { Text("Delete ${project.name}?") },
            text = { Text("This removes the project's base and revisions. Chunks still used by another project remain stored.") },
            confirmButton = {
                TextButton(onClick = { deleteProjectRequested = false; onDeleteProject() }) { Text("Delete project") }
            },
            dismissButton = { TextButton(onClick = { deleteProjectRequested = false }) { Text("Cancel") } },
        )
    }

    replaceRequest?.takeIf { it.record.projectId == project.id }?.let { request ->
        val installed = request.installedVersionName
            ?.let { "$it (code ${request.installedVersionCode})" }
            ?: "code ${request.installedVersionCode}"
        val reason = when (request.reason) {
            ReplaceReason.DOWNGRADE -> "Android will not install this older revision over the currently installed $installed."
            ReplaceReason.SIGNATURE_MISMATCH -> "The selected revision is signed with a different certificate than the currently installed $installed."
        }
        AlertDialog(
            onDismissRequest = onCancelReplace,
            title = { Text("Replace installed app?") },
            text = { Text("$reason\n\nAPKbox must open Android's uninstall confirmation first. Uninstalling removes that app's local data; APKbox's stored revision remains intact.") },
            confirmButton = { TextButton(onClick = onConfirmReplace) { Text("Uninstall & install") } },
            dismissButton = { TextButton(onClick = onCancelReplace) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ProjectStorageCard(records: List<ApkRecord>, globalStats: VaultStats) {
    val context = LocalContext.current
    val logical = records.sumOf { it.sizeBytes }
    val projectNew = records.sumOf { it.newBytesAdded }
    val reused = (logical - projectNew).coerceAtLeast(0L)
    val reusedPercent = if (logical == 0L) 0.0 else reused.toDouble() / logical * 100.0
    val percent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(reusedPercent)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Project reuse", style = MaterialTheme.typography.labelLarge)
            Text("$percent%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "${Formatter.formatFileSize(context, logical)} as full copies · ${records.size} build${if (records.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Global vault: ${Formatter.formatFileSize(context, globalStats.physicalBytes)} physical",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun RevisionCard(
    record: ApkRecord,
    base: ApkRecord,
    busy: Boolean,
    onInstall: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onToggleStar: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val reused = (record.sizeBytes - record.newBytesAdded).coerceAtLeast(0L)
    val reusedPercent = if (record.sizeBytes == 0L) 0.0 else reused.toDouble() / record.sizeBytes * 100.0
    val percent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(reusedPercent)
    val signingMismatch = !record.isBase && record.signingCertSha256 != null &&
        base.signingCertSha256 != null && record.signingCertSha256 != base.signingCertSha256

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (record.isBase) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    StoredApkIcon(record, Modifier.padding(9.dp).size(38.dp), record.label)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        record.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${record.label} · ${record.versionName} · code ${record.versionCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (record.description.isNotBlank()) {
                        Text(
                            record.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (record.notes.isNotBlank()) {
                        Text(
                            "Notes: ${record.notes}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(enabled = !busy, onClick = onToggleStar) {
                    Icon(
                        if (record.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (record.starred) "Unstar APK" else "Star APK",
                    )
                }
                IconButton(enabled = !busy, onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit APK details")
                }
                if (onDelete != null) {
                    IconButton(enabled = !busy, onClick = onDelete) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete revision")
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            HorizontalDivider()
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (record.isBase) "${Formatter.formatFileSize(context, record.sizeBytes)} base APK"
                        else "${Formatter.formatFileSize(context, record.newBytesAdded)} new · $percent% vault-wide reused",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${record.chunkCount} chunks · ${DateUtils.getRelativeTimeSpanString(record.addedAtEpochMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        record.sha256.take(16),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(enabled = !busy, onClick = onExport) {
                    Icon(Icons.Rounded.Download, contentDescription = "Export APK")
                }
                IconButton(enabled = !busy, onClick = onShare) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share APK")
                }
                FilledTonalButton(enabled = !busy, onClick = onInstall) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.size(5.dp))
                    Text("Install")
                }
            }

            if (signingMismatch) {
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Different signing certificate from base",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
