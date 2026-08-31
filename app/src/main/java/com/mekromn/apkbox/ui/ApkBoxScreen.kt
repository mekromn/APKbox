package com.mekromn.apkbox.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.VaultStats
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkBoxScreen(
    records: List<ApkRecord>,
    stats: VaultStats,
    busy: Boolean,
    message: String?,
    onMessageShown: () -> Unit,
    onChooseBase: () -> Unit,
    onAddRevision: () -> Unit,
    onInstall: (ApkRecord) -> Unit,
    onDelete: (ApkRecord) -> Unit,
    onClearVault: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var overflowOpen by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<ApkRecord?>(null) }
    var clearRequested by remember { mutableStateOf(false) }

    val base = records.firstOrNull { it.isBase }
    val visibleRevisions = records.filter { !it.isBase }.filter { record ->
        query.isBlank() || listOf(
            record.label,
            record.versionName,
            record.versionCode.toString(),
            record.sha256,
        ).any { it.contains(query, ignoreCase = true) }
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
                    title = {
                        Column {
                            Text("APKbox", fontWeight = FontWeight.Bold)
                            if (base != null) {
                                Text(
                                    base.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        if (records.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { overflowOpen = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                                }
                                DropdownMenu(
                                    expanded = overflowOpen,
                                    onDismissRequest = { overflowOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Clear entire vault") },
                                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                        onClick = {
                                            overflowOpen = false
                                            clearRequested = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                AnimatedVisibility(busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            if (base != null) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = onAddRevision,
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Add revision") },
                )
            }
        },
    ) { scaffoldPadding ->
        if (base == null) {
            EmptyVault(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
                busy = busy,
                onChooseBase = onChooseBase,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = scaffoldPadding.calculateTopPadding() + 12.dp,
                    bottom = scaffoldPadding.calculateBottomPadding() + 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { StorageHero(stats) }
                item { SectionLabel("BASE") }
                item {
                    RevisionCard(
                        record = base,
                        base = base,
                        busy = busy,
                        onInstall = { onInstall(base) },
                        onDelete = null,
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) { SectionLabel("REVISIONS") }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                stats.revisionCount.toString(),
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
                        placeholder = { Text("Search version, build code, or hash") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    )
                }

                if (visibleRevisions.isEmpty()) {
                    item { EmptyRevisions(query.isNotBlank()) }
                } else {
                    items(visibleRevisions, key = { it.id }) { record ->
                        RevisionCard(
                            record = record,
                            base = base,
                            busy = busy,
                            onInstall = { onInstall(record) },
                            onDelete = { deleteCandidate = record },
                        )
                    }
                }
            }
        }
    }

    deleteCandidate?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete ${record.versionName}?") },
            text = { Text("Unused chunks will be removed automatically; shared chunks stay available to other revisions.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    onDelete(record)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
    }

    if (clearRequested) {
        AlertDialog(
            onDismissRequest = { clearRequested = false },
            title = { Text("Clear APKbox?") },
            text = { Text("This removes the saved base, all revision manifests, and every chunk in this vault.") },
            confirmButton = {
                TextButton(onClick = {
                    clearRequested = false
                    onClearVault()
                }) { Text("Clear vault") }
            },
            dismissButton = {
                TextButton(onClick = { clearRequested = false }) { Text("Cancel") }
            },
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
private fun EmptyVault(modifier: Modifier, busy: Boolean, onChooseBase: () -> Unit) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(34.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer,
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Inventory2,
                    null,
                    modifier = Modifier.size(58.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "One APK. Every revision.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Choose the APK your development builds are based on. APKbox remembers it and stores only unique chunk data from later revisions.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Button(enabled = !busy, onClick = onChooseBase) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.FolderZip, null)
                Spacer(Modifier.size(10.dp))
                Text("Choose base APK")
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(6.dp))
                Text(
                    "Exact bytes preserved · original signatures stay intact",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StorageHero(stats: VaultStats) {
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
                    Text("Space saved", style = MaterialTheme.typography.labelLarge)
                    Text(
                        Formatter.formatFileSize(context, stats.savedBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)) {
                    Text("$percent%", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Modifier.weight(1f), "Vault", Formatter.formatFileSize(context, stats.physicalBytes))
                StatTile(Modifier.weight(1f), "Full copies", Formatter.formatFileSize(context, stats.logicalBytes))
                StatTile(Modifier.weight(1f), "Builds", (stats.revisionCount + 1).toString())
            }
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, label: String, value: String) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyRevisions(searching: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.FolderZip, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(if (searching) "No matching revisions" else "No revisions yet", fontWeight = FontWeight.SemiBold)
            if (!searching) {
                Text(
                    "Add a build and APKbox will keep only chunks it hasn't already stored.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RevisionCard(
    record: ApkRecord,
    base: ApkRecord,
    busy: Boolean,
    onInstall: () -> Unit,
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
                    Icon(Icons.Rounded.Android, null, Modifier.padding(12.dp).size(28.dp))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(record.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${record.versionName} · code ${record.versionCode}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onDelete != null) {
                    IconButton(enabled = !busy, onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete revision") }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (record.isBase) "${Formatter.formatFileSize(context, record.sizeBytes)} base APK"
                        else "${Formatter.formatFileSize(context, record.newBytesAdded)} new · $percent% reused",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "${record.chunkCount} chunks · ${DateUtils.getRelativeTimeSpanString(record.addedAtEpochMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(record.sha256.take(16), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(enabled = !busy, onClick = onInstall) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Install")
                }
            }

            if (signingMismatch) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Different signing certificate — Android may require uninstalling the currently installed copy first.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            } else if (!record.isBase) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(6.dp))
                    Text("Byte-exact revision stored", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
