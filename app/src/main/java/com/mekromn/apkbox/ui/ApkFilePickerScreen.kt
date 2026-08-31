package com.mekromn.apkbox.ui

import android.os.Environment
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ApkPickerMode { BASE, REVISIONS }

private enum class FileSort(val label: String) {
    NAME("Name"),
    NEWEST("Newest"),
    LARGEST("Largest"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkFilePickerScreen(
    mode: ApkPickerMode,
    initialDirectory: File,
    hasDirectFileAccess: Boolean,
    onRequestFileAccess: () -> Unit,
    onDismiss: () -> Unit,
    onPicked: (List<File>) -> Unit,
    onUseSystemPicker: () -> Unit,
) {
    val context = LocalContext.current
    val storageRoot = remember {
        Environment.getExternalStorageDirectory().takeIf { it.isDirectory }
            ?: File("/storage/emulated/0")
    }
    var currentDirectory by remember {
        mutableStateOf(initialDirectory.takeIf { it.isDirectory } ?: storageRoot)
    }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(FileSort.NAME) }
    var showHidden by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var directoryEntries by remember { mutableStateOf<List<File>>(emptyList()) }
    var unreadable by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    fun goTo(directory: File) {
        if (!directory.isDirectory) return
        currentDirectory = directory
        query = ""
        unreadable = false
    }

    fun goUp() {
        val parent = currentDirectory.parentFile
        if (parent != null && currentDirectory.absolutePath != storageRoot.parentFile?.absolutePath) {
            goTo(parent)
        } else {
            onDismiss()
        }
    }

    BackHandler { goUp() }

    LaunchedEffect(currentDirectory.absolutePath, showHidden, sort, refreshKey, hasDirectFileAccess) {
        if (!hasDirectFileAccess) {
            directoryEntries = emptyList()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                currentDirectory.listFiles()
                    ?.asSequence()
                    ?.filter { showHidden || !it.name.startsWith('.') }
                    ?.filter { it.isDirectory || (it.isFile && it.extension.equals("apk", ignoreCase = true)) }
                    ?.sortedWith(
                        when (sort) {
                            FileSort.NAME -> compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })
                            FileSort.NEWEST -> compareBy<File>({ !it.isDirectory }).thenByDescending { it.lastModified() }
                            FileSort.LARGEST -> compareBy<File>({ !it.isDirectory }).thenByDescending { it.length() }
                        }
                    )
                    ?.toList()
                    ?: emptyList()
            }
        }
        result.onSuccess {
            directoryEntries = it
            unreadable = false
        }.onFailure {
            directoryEntries = emptyList()
            unreadable = true
        }
    }

    val visibleEntries = remember(directoryEntries, query) {
        if (query.isBlank()) directoryEntries
        else directoryEntries.filter { it.name.contains(query, ignoreCase = true) }
    }
    val apkEntries = remember(directoryEntries) { directoryEntries.filter { it.isFile } }
    val selectedFiles = remember(selected, directoryEntries) {
        selected.map(::File).filter { it.isFile }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::goUp) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            if (mode == ApkPickerMode.BASE) "Choose base APK" else "Add revisions",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            currentDirectory.absolutePath.removePrefix(storageRoot.absolutePath).ifBlank { "/" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Picker options")
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            FileSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("Sort: ${option.label}") },
                                    trailingIcon = if (sort == option) {
                                        { Icon(Icons.Rounded.Check, null) }
                                    } else null,
                                    onClick = {
                                        sort = option
                                        overflowOpen = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
                                onClick = {
                                    showHidden = !showHidden
                                    overflowOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Use Android system picker") },
                                leadingIcon = { Icon(Icons.Rounded.Description, null) },
                                onClick = {
                                    overflowOpen = false
                                    onUseSystemPicker()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            if (hasDirectFileAccess && selectedFiles.isNotEmpty()) {
                FloatingActionButton(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = { onPicked(selectedFiles) },
                ) {
                    if (mode == ApkPickerMode.BASE) {
                        Icon(Icons.Rounded.Check, contentDescription = "Choose APK")
                    } else {
                        Text(selectedFiles.size.toString(), fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
    ) { padding ->
        if (!hasDirectFileAccess) {
            AccessGate(
                modifier = Modifier.fillMaxSize().padding(padding),
                onRequestFileAccess = onRequestFileAccess,
                onUseSystemPicker = onUseSystemPicker,
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                QuickLocations(
                    storageRoot = storageRoot,
                    currentDirectory = currentDirectory,
                    onOpen = ::goTo,
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(22.dp),
                    placeholder = { Text("Search this folder") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                )
            }
            if (mode == ApkPickerMode.REVISIONS && apkEntries.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${apkEntries.size} APK${if (apkEntries.size == 1) "" else "s"}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            val allPaths = apkEntries.mapTo(linkedSetOf()) { it.absolutePath }
                            selected = if (selected.containsAll(allPaths)) emptySet() else allPaths
                        }) {
                            Icon(
                                if (selected.containsAll(apkEntries.map { it.absolutePath })) Icons.Rounded.ClearAll
                                else Icons.Rounded.CheckBox,
                                null,
                                Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(if (selected.containsAll(apkEntries.map { it.absolutePath })) "Clear" else "Select all")
                        }
                    }
                }
            }

            when {
                unreadable -> item {
                    EmptyFolderMessage(
                        title = "Folder unavailable",
                        body = "APKbox could not read this folder. Try another location or use the system picker fallback.",
                    )
                }
                visibleEntries.isEmpty() -> item {
                    EmptyFolderMessage(
                        title = if (query.isBlank()) "No APKs here" else "No matches",
                        body = if (query.isBlank()) "This folder has no subfolders or .apk files."
                        else "No folders or APK files match your search.",
                    )
                }
                else -> items(visibleEntries, key = { it.absolutePath }) { file ->
                    val isSelected = file.absolutePath in selected
                    FileRow(
                        file = file,
                        selected = isSelected,
                        multiSelect = mode == ApkPickerMode.REVISIONS,
                        onClick = {
                            if (file.isDirectory) {
                                goTo(file)
                            } else {
                                selected = if (mode == ApkPickerMode.BASE) {
                                    setOf(file.absolutePath)
                                } else if (isSelected) {
                                    selected - file.absolutePath
                                } else {
                                    selected + file.absolutePath
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessGate(
    modifier: Modifier,
    onRequestFileAccess: () -> Unit,
    onUseSystemPicker: () -> Unit,
) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(24.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)) {
                    Icon(Icons.Rounded.Storage, null, Modifier.padding(16.dp).size(34.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text("Use APKbox's file browser", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Grant All files access once so APKbox can browse APK folders directly. After that, choosing builds stays inside APKbox instead of opening Android's stock picker.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRequestFileAccess) {
                    Icon(Icons.Rounded.Settings, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Enable APKbox file access")
                }
                TextButton(onClick = onUseSystemPicker) { Text("Use system picker this time") }
            }
        }
    }
}

@Composable
private fun QuickLocations(
    storageRoot: File,
    currentDirectory: File,
    onOpen: (File) -> Unit,
) {
    val downloads = remember(storageRoot) { File(storageRoot, "Download") }
    val documents = remember(storageRoot) { File(storageRoot, "Documents") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = currentDirectory.absolutePath == storageRoot.absolutePath,
            onClick = { onOpen(storageRoot) },
            label = { Text("Storage") },
            leadingIcon = { Icon(Icons.Rounded.Home, null, Modifier.size(18.dp)) },
        )
        if (downloads.isDirectory) {
            FilterChip(
                selected = currentDirectory.absolutePath == downloads.absolutePath,
                onClick = { onOpen(downloads) },
                label = { Text("Download") },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)) },
            )
        }
        if (documents.isDirectory) {
            FilterChip(
                selected = currentDirectory.absolutePath == documents.absolutePath,
                onClick = { onOpen(documents) },
                label = { Text("Documents") },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun FileRow(
    file: File,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (file.isDirectory) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Icon(
                    if (file.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Android,
                    null,
                    Modifier.padding(10.dp).size(26.dp),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(file.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (file.isFile) {
                    Text(
                        "${Formatter.formatFileSize(context, file.length())} · ${DateUtils.getRelativeTimeSpanString(file.lastModified())}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("Folder", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (file.isFile) {
                Icon(
                    if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!multiSelect && selected) {
                    Spacer(Modifier.size(2.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyFolderMessage(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Folder, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
