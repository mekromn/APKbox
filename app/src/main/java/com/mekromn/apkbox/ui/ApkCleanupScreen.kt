package com.mekromn.apkbox.ui

import android.os.Environment
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mekromn.apkbox.data.ApkDiskCleanupScanner
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CleanupFilter(val label: String) {
    ALL("All"),
    STORED("Stored"),
    NOT_STORED("Not stored"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkCleanupScreen(
    projects: List<ApkProject>,
    records: List<ApkRecord>,
    hasDirectFileAccess: Boolean,
    onRequestFileAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val root = remember { Environment.getExternalStorageDirectory() }
    val projectsById = remember(projects) { projects.associateBy { it.id } }

    var candidates by remember { mutableStateOf<List<ApkDiskCleanupScanner.Candidate>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filter by remember { mutableStateOf(CleanupFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var unreadableDirectories by remember { mutableIntStateOf(0) }
    var unreadableFiles by remember { mutableIntStateOf(0) }
    var directoriesVisited by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(hasDirectFileAccess, refreshKey, records) {
        if (!hasDirectFileAccess) {
            candidates = emptyList()
            selected = emptySet()
            return@LaunchedEffect
        }
        scanning = true
        scanError = null
        try {
            val result = withContext(Dispatchers.IO) {
                ApkDiskCleanupScanner.scan(root, records)
            }
            candidates = result.candidates
            directoriesVisited = result.directoriesVisited
            unreadableDirectories = result.unreadableDirectories
            unreadableFiles = result.unreadableFiles
            selected = selected.intersect(result.candidates.mapTo(hashSetOf()) { it.path })
        } catch (t: Throwable) {
            scanError = t.message ?: "APK scan failed."
        } finally {
            scanning = false
        }
    }

    val storedCandidates = remember(candidates) { candidates.filter { it.isSafelyStored } }
    val safeBytes = remember(storedCandidates) { storedCandidates.sumOf { it.sizeBytes } }
    val selectedCandidates = remember(candidates, selected) { candidates.filter { it.path in selected } }
    val selectedBytes = remember(selectedCandidates) { selectedCandidates.sumOf { it.sizeBytes } }
    val selectedUnsafeCount = remember(selectedCandidates) { selectedCandidates.count { !it.isSafelyStored } }

    val visible = remember(candidates, filter, query) {
        candidates.filter { candidate ->
            val filterMatch = when (filter) {
                CleanupFilter.ALL -> true
                CleanupFilter.STORED -> candidate.isSafelyStored
                CleanupFilter.NOT_STORED -> !candidate.isSafelyStored
            }
            filterMatch && (query.isBlank() || candidate.name.contains(query, ignoreCase = true) ||
                candidate.path.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            Text("APK cleanup", fontWeight = FontWeight.Bold)
                            Text(
                                "Delete original APK files already archived in APKbox",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = !scanning && !deleting && hasDirectFileAccess,
                            onClick = { refreshKey++ },
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (scanning || deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            if (selectedCandidates.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    tonalElevation = 4.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${selectedCandidates.size} selected",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                Formatter.formatFileSize(context, selectedBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            enabled = !deleting,
                            onClick = { confirmDelete = true },
                        ) {
                            Icon(Icons.Rounded.DeleteForever, null)
                            Spacer(Modifier.size(8.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            !hasDirectFileAccess -> {
                CleanupAccessGate(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onRequestFileAccess = onRequestFileAccess,
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        top = padding.calculateTopPadding() + 10.dp,
                        bottom = padding.calculateBottomPadding() + if (selectedCandidates.isEmpty()) 24.dp else 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        CleanupSummaryCard(
                            totalCount = candidates.size,
                            storedCount = storedCandidates.size,
                            safeBytes = safeBytes,
                            directoriesVisited = directoriesVisited,
                            unreadableDirectories = unreadableDirectories,
                            unreadableFiles = unreadableFiles,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(22.dp),
                            placeholder = { Text("Search filename or path") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CleanupFilter.entries.forEach { option ->
                                FilterChip(
                                    selected = filter == option,
                                    onClick = { filter = option },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    }
                    if (storedCandidates.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Exact SHA-256 matches can always be reconstructed from the vault.",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        val safePaths = storedCandidates.mapTo(linkedSetOf()) { it.path }
                                        selected = if (selected.containsAll(safePaths)) selected - safePaths else selected + safePaths
                                    },
                                ) {
                                    Icon(Icons.Rounded.ClearAll, null, Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text(if (selected.containsAll(storedCandidates.map { it.path })) "Clear stored" else "Select stored")
                                }
                            }
                        }
                    }

                    scanError?.let { error ->
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            ) {
                                Text(
                                    error,
                                    Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }

                    when {
                        scanning && candidates.isEmpty() -> item {
                            CleanupEmptyMessage("Scanning shared storage…", "APKbox is finding .apk files and checking exact vault matches.")
                        }
                        !scanning && visible.isEmpty() -> item {
                            CleanupEmptyMessage(
                                if (candidates.isEmpty()) "No APK files found" else "No matching APKs",
                                if (candidates.isEmpty()) {
                                    if (unreadableDirectories > 0 || unreadableFiles > 0) {
                                        "No readable .apk files were found. Some storage entries could not be inspected."
                                    } else {
                                        "No .apk files were found in accessible shared storage."
                                    }
                                } else "Try another filter or search.",
                            )
                        }
                        else -> items(visible, key = { it.path }) { candidate ->
                            val stored = candidate.storedRecord
                            val projectName = stored?.let { projectsById[it.projectId]?.name }
                            CleanupCandidateRow(
                                candidate = candidate,
                                projectName = projectName,
                                selected = candidate.path in selected,
                                enabled = !deleting,
                                onToggle = {
                                    selected = if (candidate.path in selected) selected - candidate.path
                                    else selected + candidate.path
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && selectedCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("Permanently delete ${selectedCandidates.size} APK file${if (selectedCandidates.size == 1) "" else "s"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This will reclaim approximately ${Formatter.formatFileSize(context, selectedBytes)} from shared storage. The original files are deleted directly, not moved to a recycle bin.")
                    if (selectedUnsafeCount == 0) {
                        Text(
                            "Every selected APK is an exact SHA-256 match for a build already stored in APKbox, so APKbox can reconstruct it later.",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Text(
                            "$selectedUnsafeCount selected APK${if (selectedUnsafeCount == 1) " is" else "s are"} NOT verified as stored in APKbox. APKbox cannot promise those files are reconstructable after deletion.",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        confirmDelete = false
                        deleting = true
                        val paths = selectedCandidates.map { it.path }
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                ApkDiskCleanupScanner.delete(context, paths)
                            }
                            candidates = candidates.filterNot { it.path in result.deletedPaths }
                            selected = selected - result.deletedPaths
                            deleting = false
                            val summary = buildString {
                                append("Deleted ${result.deletedPaths.size} APK")
                                if (result.deletedPaths.size != 1) append('s')
                                append(" · reclaimed ${Formatter.formatFileSize(context, result.bytesReclaimed)}")
                                if (result.failedPaths.isNotEmpty()) append(" · ${result.failedPaths.size} failed")
                            }
                            snackbar.showSnackbar(summary)
                        }
                    },
                ) { Text(if (selectedUnsafeCount == 0) "Delete stored copies" else "Delete anyway") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CleanupAccessGate(modifier: Modifier, onRequestFileAccess: () -> Unit) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(24.dp)) {
                Icon(Icons.Rounded.Storage, null, Modifier.size(38.dp))
                Spacer(Modifier.size(14.dp))
                Text("Shared-storage access required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                Text(
                    "APK cleanup needs the same All files access used by APKbox's custom file browser so it can find and delete original APK files you choose.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.size(18.dp))
                Button(onClick = onRequestFileAccess) {
                    Icon(Icons.Rounded.Settings, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Enable file access")
                }
            }
        }
    }
}

@Composable
private fun CleanupSummaryCard(
    totalCount: Int,
    storedCount: Int,
    safeBytes: Long,
    directoriesVisited: Int,
    unreadableDirectories: Int,
    unreadableFiles: Int,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Safe reclaimable copies", style = MaterialTheme.typography.labelLarge)
                    Text(
                        Formatter.formatFileSize(context, safeBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)) {
                    Text(
                        "$storedCount stored",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(
                buildString {
                    append("$totalCount APK file${if (totalCount == 1) "" else "s"} found · $directoriesVisited folders scanned")
                    val unreadableFolderOnly = (unreadableDirectories - unreadableFiles).coerceAtLeast(0)
                    if (unreadableFolderOnly > 0) append(" · $unreadableFolderOnly inaccessible folder${if (unreadableFolderOnly == 1) "" else "s"}")
                    if (unreadableFiles > 0) append(" · $unreadableFiles APK read issue${if (unreadableFiles == 1) "" else "s"}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun CleanupCandidateRow(
    candidate: ApkDiskCleanupScanner.Candidate,
    projectName: String?,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val stored = candidate.storedRecord
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        candidate.isSafelyStored -> MaterialTheme.colorScheme.primaryContainer
                        candidate.hashReadFailed -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
                ) {
                    Icon(
                        Icons.Rounded.Android,
                        null,
                        Modifier.padding(10.dp).size(26.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(candidate.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Formatter.formatFileSize(context, candidate.sizeBytes)} · ${DateUtils.getRelativeTimeSpanString(candidate.modifiedAtEpochMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = enabled)
            }
            Spacer(Modifier.size(10.dp))
            HorizontalDivider()
            Spacer(Modifier.size(10.dp))
            Text(
                candidate.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(7.dp))
            when {
                stored != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            buildString {
                                append("Stored safely")
                                if (!projectName.isNullOrBlank()) append(" · $projectName")
                                append(" · ${stored.displayName}")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                candidate.hashReadFailed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "SHA-256 read issue · vault match unknown · not marked safe",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "Not in APKbox · deleting this file is not recoverable from the vault",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanupEmptyMessage(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Storage, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
