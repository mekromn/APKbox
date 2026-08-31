package com.mekromn.apkbox.ui

import android.net.Uri
import android.text.format.Formatter
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
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.mekromn.apkbox.data.SharedApkAnalyzer
import com.mekromn.apkbox.data.SharedApkPreview
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedApkScreen(
    uris: List<Uri>,
    projects: List<ApkProject>,
    records: List<ApkRecord>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAddToProject: (ApkProject, List<Uri>) -> Unit,
    onCreateProject: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    var previews by remember(uris, records) { mutableStateOf<List<SharedApkPreview>>(emptyList()) }
    var loading by remember(uris, records) { mutableStateOf(true) }
    var failure by remember(uris, records) { mutableStateOf<String?>(null) }

    LaunchedEffect(uris, records) {
        loading = true
        failure = null
        runCatching { SharedApkAnalyzer.analyze(context, uris, records) }
            .onSuccess { previews = it }
            .onFailure { failure = it.message ?: "APKbox could not inspect the shared APK." }
        loading = false
    }

    val packageNames = previews.mapTo(linkedSetOf()) { it.packageName }
    val mixedPackages = previews.isNotEmpty() && packageNames.size > 1

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Close shared APK")
                        }
                    },
                    title = {
                        Column {
                            Text("Add shared APK", fontWeight = FontWeight.Bold)
                            Text(
                                "${uris.size} shared file${if (uris.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                loading -> item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text("Reading package metadata and exact SHA-256…")
                        }
                    }
                }
                failure != null -> item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            failure ?: "Could not inspect shared APK.",
                            Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                else -> {
                    item { SharedFilesCard(previews, projects) }

                    if (mixedPackages) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            ) {
                                Text(
                                    "These files contain different package names. Share builds from the same app together so APKbox can put them in one project safely.",
                                    Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    } else {
                        item {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && previews.isNotEmpty(),
                                onClick = { onCreateProject(previews.map { it.uri }) },
                            ) {
                                Icon(Icons.Rounded.CreateNewFolder, null)
                                Spacer(Modifier.size(8.dp))
                                Text(if (previews.size == 1) "Create new project from APK" else "Create project from shared builds")
                            }
                        }

                        if (projects.isNotEmpty()) {
                            item {
                                Text(
                                    "ADD TO PROJECT",
                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            items(projects, key = { it.id }) { project ->
                                val compatible = previews.all { it.packageName == project.packageName }
                                val alreadyHere = previews.isNotEmpty() && previews.all { preview ->
                                    preview.storedMatches.any { it.projectId == project.id }
                                }
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                        ) {
                                            Icon(Icons.Rounded.Folder, null, Modifier.padding(11.dp).size(26.dp))
                                        }
                                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                            Text(project.name, fontWeight = FontWeight.Bold)
                                            Text(
                                                project.packageName,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                when {
                                                    !compatible -> "Different package — cannot add here"
                                                    alreadyHere -> "Every shared APK is already in this project"
                                                    else -> "Compatible project"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (compatible) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Button(
                                            enabled = !busy && compatible && !alreadyHere,
                                            onClick = { onAddToProject(project, previews.map { it.uri }) },
                                        ) {
                                            Text(if (alreadyHere) "Stored" else "Add")
                                        }
                                    }
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
private fun SharedFilesCard(
    previews: List<SharedApkPreview>,
    projects: List<ApkProject>,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            previews.forEach { preview ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(Icons.Rounded.Android, null, Modifier.padding(10.dp).size(25.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(preview.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${preview.label} · ${preview.versionName} · code ${preview.versionCode}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${Formatter.formatFileSize(context, preview.sizeBytes)} · ${preview.sha256.take(16)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (preview.storedMatches.isNotEmpty()) {
                            val names = preview.storedMatches.mapNotNull { match ->
                                projects.firstOrNull { it.id == match.projectId }?.name
                            }.distinct().joinToString()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    if (names.isBlank()) "Already stored in APKbox" else "Already stored in $names",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
