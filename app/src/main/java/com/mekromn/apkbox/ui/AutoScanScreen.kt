package com.mekromn.apkbox.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mekromn.apkbox.data.AutoScanManager
import com.mekromn.apkbox.model.ApkProject
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoScanScreen(
    manager: AutoScanManager,
    projects: List<ApkProject>,
    hasDirectFileAccess: Boolean,
    onRequestFileAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val enabled = manager.enabled.collectAsStateWithLifecycle().value
    val rules = manager.rules.collectAsStateWithLifecycle().value
    val events = manager.recentEvents.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()

    var addRuleOpen by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanSummary by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
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
                            Text("Auto Scanner", fontWeight = FontWeight.Bold)
                            Text(
                                "Downloads → verified APKbox archive",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        Switch(
                            checked = enabled,
                            enabled = hasDirectFileAccess && rules.isNotEmpty(),
                            onCheckedChange = manager::setEnabled,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SafetyCard()
            }

            if (!hasDirectFileAccess) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "File access required",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "Auto Scanner needs APKbox's All files access so it can read and permanently remove verified originals from Downloads.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Button(onClick = onRequestFileAccess) { Text("Enable file access") }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("RULES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(
                            "All keywords in a rule must appear in the APK filename.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = projects.isNotEmpty(),
                        onClick = { addRuleOpen = true },
                    ) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.size(4.dp))
                        Text("Add rule")
                    }
                }
            }

            if (rules.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Text(
                            if (projects.isEmpty()) "Create a Project first, then add an Auto Scanner rule."
                            else "No rules yet. Add keywords and choose the Project that matching APKs belong to.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(rules, key = { it.id }) { rule ->
                    val project = projects.firstOrNull { it.id == rule.projectId }
                    RuleRow(
                        rule = rule,
                        project = project,
                        onEnabledChange = { manager.updateRuleEnabled(rule.id, it) },
                        onDeleteOriginalChange = { manager.updateRuleDeleteOriginal(rule.id, it) },
                        onDelete = { manager.deleteRule(rule.id) },
                    )
                }
            }

            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled && hasDirectFileAccess && !scanning,
                    onClick = {
                        scope.launch {
                            scanning = true
                            scanSummary = runCatching { manager.scanNow("manual Scan Now") }
                                .fold(
                                    onSuccess = { summary ->
                                        "${summary.examinedMatches} matched · ${summary.imported} imported · ${summary.deleted} originals deleted · ${summary.failed} need attention"
                                    },
                                    onFailure = { it.message ?: "Scan failed" },
                                )
                            scanning = false
                        }
                    },
                ) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.size(7.dp))
                    Text("Scan Downloads now")
                }
            }

            scanSummary?.let { summary ->
                item {
                    Text(
                        summary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (events.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "RECENT ACTIVITY",
                            modifier = Modifier.weight(1f).padding(start = 4.dp, top = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = manager::clearRecentEvents) { Text("Clear") }
                    }
                }
                items(events.take(30), key = { "${it.atEpochMs}:${it.fileName}:${it.status}" }) { event ->
                    EventRow(event, projects)
                }
            }
        }
    }

    if (addRuleOpen) {
        AddRuleDialog(
            projects = projects,
            onDismiss = { addRuleOpen = false },
            onAdd = { projectId, keywords, deleteOriginal ->
                manager.addRule(projectId, keywords, deleteOriginal)
                addRuleOpen = false
            },
        )
    }
}

@Composable
private fun SafetyCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Security, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 10.dp)) {
                Text("Verified-delete protection", fontWeight = FontWeight.Bold)
                Text(
                    "APKbox never deletes a matching download until its vault copy reconstructs to the exact stored SHA-256. Wrong-package, incomplete, ambiguous, or failed imports remain on disk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: AutoScanManager.Rule,
    project: ApkProject?,
    onEnabledChange: (Boolean) -> Unit,
    onDeleteOriginalChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                    Text(
                        project?.name ?: "Missing Project",
                        fontWeight = FontWeight.SemiBold,
                        color = if (project == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        rule.keywords.joinToString(" + "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onEnabledChange)
                IconButton(onClick = onDelete, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete rule")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rule.deleteOriginal, onCheckedChange = onDeleteOriginalChange)
                Text(
                    "Delete original after verified archive",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: AutoScanManager.Event, projects: List<ApkProject>) {
    val success = event.status in setOf(
        AutoScanManager.EventStatus.IMPORTED_AND_DELETED,
        AutoScanManager.EventStatus.IMPORTED_KEPT,
        AutoScanManager.EventStatus.ALREADY_STORED_AND_DELETED,
        AutoScanManager.EventStatus.ALREADY_STORED_KEPT,
    )
    val projectName = event.projectId?.let { id -> projects.firstOrNull { it.id == id }?.name }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                null,
                Modifier.size(20.dp),
                tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column(Modifier.padding(start = 9.dp)) {
                Text(event.fileName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        if (!projectName.isNullOrBlank()) append("$projectName · ")
                        append(DateUtils.getRelativeTimeSpanString(event.atEpochMs))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddRuleDialog(
    projects: List<ApkProject>,
    onDismiss: () -> Unit,
    onAdd: (String, List<String>, Boolean) -> Unit,
) {
    var keywordsText by remember { mutableStateOf("") }
    var selectedProjectId by remember(projects) { mutableStateOf(projects.firstOrNull()?.id) }
    var deleteOriginal by remember { mutableStateOf(true) }
    val parsedKeywords = keywordsText.split(',').map { it.trim() }.filter { it.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Auto Scanner rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = keywordsText,
                    onValueChange = { keywordsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filename keywords") },
                    placeholder = { Text("PixelCamera, P9PXL") },
                    supportingText = { Text("Comma-separated. Every keyword must appear in the filename.") },
                )
                Text("TARGET PROJECT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                projects.forEach { project ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedProjectId == project.id,
                            onClick = { selectedProjectId = project.id },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(project.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                project.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteOriginal, onCheckedChange = { deleteOriginal = it })
                    Text("Delete original after verified archive")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedProjectId != null && parsedKeywords.isNotEmpty(),
                onClick = { onAdd(selectedProjectId!!, parsedKeywords, deleteOriginal) },
            ) { Text("Add rule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
