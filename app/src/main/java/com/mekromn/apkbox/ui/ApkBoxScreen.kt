package com.mekromn.apkbox.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.ReplaceReason
import com.mekromn.apkbox.model.ReplaceRequest
import com.mekromn.apkbox.model.VaultStats
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var searchOpen by remember { mutableStateOf(false) }
    var starredOnly by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var actionCandidate by remember { mutableStateOf<ApkRecord?>(null) }
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
                        IconButton(
                            onClick = {
                                if (searchOpen) {
                                    searchOpen = false
                                    query = ""
                                } else {
                                    searchOpen = true
                                }
                            },
                        ) {
                            Icon(
                                if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = if (searchOpen) "Close search" else "Search revisions",
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Project options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Add revisions") },
                                    leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                    onClick = { menuOpen = false; onAddRevision() },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (starredOnly) "Show all revisions" else "Show starred only") },
                                    leadingIcon = { Icon(if (starredOnly) Icons.Rounded.Star else Icons.Rounded.StarBorder, null) },
                                    onClick = { menuOpen = false; starredOnly = !starredOnly },
                                )
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = padding.calculateTopPadding() + 6.dp,
                bottom = padding.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (searchOpen) {
                item(key = "project-search") {
                    ProjectSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClose = {
                            query = ""
                            searchOpen = false
                        },
                    )
                }
            }

            item(key = "project-stats") {
                ProjectStatsCard(records, globalStats, starredOnly)
            }

            if (base != null) {
                item { SectionLabel("BASE") }
                item {
                    CompactApkRow(
                        record = base,
                        base = base,
                        busy = busy,
                        onInstall = { onInstall(base) },
                        onActions = { actionCandidate = base },
                    )
                }
            }

            item { SectionLabel(if (starredOnly) "STARRED REVISIONS · ${visibleRevisions.size}" else "REVISIONS · ${revisions.size}") }

            if (visibleRevisions.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(
                            when {
                                starredOnly -> "No starred revisions"
                                query.isNotBlank() -> "No matching revisions"
                                else -> "No revisions yet"
                            },
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(visibleRevisions, key = { it.id }) { record ->
                    CompactApkRow(
                        record = record,
                        base = base ?: record,
                        busy = busy,
                        onInstall = { onInstall(record) },
                        onActions = { actionCandidate = record },
                    )
                }
            }
        }
    }

    actionCandidate?.let { record ->
        ModalBottomSheet(onDismissRequest = { actionCandidate = null }) {
            Column(Modifier.navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StoredApkIcon(record, Modifier.size(52.dp), record.label)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(record.displayName, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${record.versionName} · code ${record.versionCode}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ActionSheetItem(Icons.Rounded.PlayArrow, "Install") {
                    actionCandidate = null
                    onInstall(record)
                }
                ActionSheetItem(
                    if (record.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (record.starred) "Remove star" else "Star APK",
                ) {
                    actionCandidate = null
                    onToggleStar(record)
                }
                ActionSheetItem(Icons.Rounded.Edit, "Description & notes") {
                    actionCandidate = null
                    editCandidate = record
                }
                ActionSheetItem(Icons.Rounded.Download, "Export exact APK") {
                    actionCandidate = null
                    onExport(record)
                }
                ActionSheetItem(Icons.Rounded.Share, "Share APK") {
                    actionCandidate = null
                    onShare(record)
                }
                ActionSheetItem(Icons.Rounded.Refresh, "Regenerate app icon") {
                    actionCandidate = null
                    onRegenerateIcon(record)
                }
                if (!record.isBase) {
                    ActionSheetItem(Icons.Rounded.DeleteOutline, "Delete from APKbox") {
                        actionCandidate = null
                        deleteCandidate = record
                    }
                }
                Spacer(Modifier.size(12.dp))
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
private fun ProjectSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        placeholder = { Text("Search builds") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close search")
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 1.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ProjectStatsCard(records: List<ApkRecord>, globalStats: VaultStats, starredOnly: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val logical = records.sumOf { it.sizeBytes }
    val introduced = records.sumOf { it.newBytesAdded }.coerceAtLeast(0L)
    val reused = (logical - introduced).coerceAtLeast(0L)
    val reusedPercent = if (logical == 0L) 0.0 else reused.toDouble() / logical * 100.0
    val projectPercent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(reusedPercent)
    val globalPercent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(globalStats.savedPercent)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PROJECT STORAGE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        "$projectPercent% reused",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.size(7.dp))
            Text(
                "${records.size} build${if (records.size == 1) "" else "s"} · ${Formatter.formatFileSize(context, logical)} as full APK copies",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${Formatter.formatFileSize(context, introduced)} new bytes introduced · ${Formatter.formatFileSize(context, reused)} reused",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Vault ${Formatter.formatFileSize(context, globalStats.physicalBytes)} physical · ${Formatter.formatFileSize(context, globalStats.savedBytes)} saved · $globalPercent%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (starredOnly) {
                Text(
                    "Starred-only filter active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactApkRow(
    record: ApkRecord,
    base: ApkRecord,
    busy: Boolean,
    onInstall: () -> Unit,
    onActions: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val reused = (record.sizeBytes - record.newBytesAdded).coerceAtLeast(0L)
    val reusedPercent = if (record.sizeBytes == 0L) 0.0 else reused.toDouble() / record.sizeBytes * 100.0
    val percent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(reusedPercent)
    val signingMismatch = !record.isBase && record.signingCertSha256 != null &&
        base.signingCertSha256 != null && record.signingCertSha256 != base.signingCertSha256

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !busy,
                onClick = onInstall,
                onLongClick = onActions,
            ),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StoredApkIcon(record, Modifier.size(56.dp), record.label)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (record.starred) {
                        Icon(
                            Icons.Rounded.Star,
                            contentDescription = "Starred",
                            modifier = Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "${record.label} · ${record.versionName} · code ${record.versionCode}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (record.isBase) {
                        "${Formatter.formatFileSize(context, record.sizeBytes)} base · ${record.chunkCount} chunks · ${DateUtils.getRelativeTimeSpanString(record.addedAtEpochMs)}"
                    } else {
                        "${Formatter.formatFileSize(context, record.newBytesAdded)} new · $percent% reused · ${record.chunkCount} chunks · ${DateUtils.getRelativeTimeSpanString(record.addedAtEpochMs)}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    record.sha256.take(16),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (record.description.isNotBlank()) {
                    Text(
                        record.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (record.notes.isNotBlank()) {
                    Text(
                        record.notes,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (signingMismatch) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Shield, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "Different signing certificate",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
    HorizontalDivider()
}
