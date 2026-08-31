package com.mekromn.apkbox.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mekromn.apkbox.model.BatchImportItem
import com.mekromn.apkbox.model.BatchImportReport
import com.mekromn.apkbox.model.BatchImportStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchResultsScreen(
    report: BatchImportReport,
    projectName: String?,
    onDone: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("Batch results", fontWeight = FontWeight.Bold)
                        Text(
                            projectName ?: "APKbox project",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("${report.addedCount} added · ${report.skippedCount} skipped", fontWeight = FontWeight.Bold)
                        Text(
                            "${report.alreadyStoredCount} already stored · ${report.wrongProjectCount} wrong project · ${report.failedCount} failed",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            itemsIndexed(report.items, key = { index, item -> "$index-${item.displayName}" }) { _, item ->
                BatchResultCard(item)
            }
        }
    }
}

@Composable
private fun BatchResultCard(item: BatchImportItem) {
    val icon = when (item.status) {
        BatchImportStatus.ADDED -> Icons.Rounded.CheckCircle
        BatchImportStatus.ALREADY_STORED -> Icons.Rounded.Info
        BatchImportStatus.WRONG_PROJECT -> Icons.Rounded.WarningAmber
        BatchImportStatus.FAILED -> Icons.Rounded.ErrorOutline
    }
    val label = when (item.status) {
        BatchImportStatus.ADDED -> "Added"
        BatchImportStatus.ALREADY_STORED -> "Already stored"
        BatchImportStatus.WRONG_PROJECT -> "Wrong project"
        BatchImportStatus.FAILED -> "Failed"
    }
    val container = when (item.status) {
        BatchImportStatus.ADDED -> MaterialTheme.colorScheme.surfaceContainerLow
        BatchImportStatus.ALREADY_STORED -> MaterialTheme.colorScheme.secondaryContainer
        BatchImportStatus.WRONG_PROJECT -> MaterialTheme.colorScheme.tertiaryContainer
        BatchImportStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
