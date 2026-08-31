package com.mekromn.apkbox.model

data class ChunkRef(
    val hash: String,
    val size: Int,
)

data class ApkProject(
    val id: String,
    val name: String,
    val packageName: String,
    val createdAtEpochMs: Long,
)

data class ApkRecord(
    val id: String,
    val projectId: String,
    val displayName: String,
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val sha256: String,
    val signingCertSha256: String?,
    val addedAtEpochMs: Long,
    val isBase: Boolean,
    val chunkCount: Int,
    val newBytesAdded: Long,
    val starred: Boolean = false,
    val description: String = "",
    val notes: String = "",
    val iconUpdatedAtEpochMs: Long = 0L,
)

data class VaultStats(
    val logicalBytes: Long = 0,
    val physicalBytes: Long = 0,
    val savedBytes: Long = 0,
    val savedPercent: Double = 0.0,
    val revisionCount: Int = 0,
)

data class ImportResult(
    val record: ApkRecord,
    val reusedBytes: Long,
)

enum class BatchImportStatus {
    ADDED,
    ALREADY_STORED,
    WRONG_PROJECT,
    FAILED,
}

data class BatchImportItem(
    val displayName: String,
    val status: BatchImportStatus,
    val detail: String,
    val recordId: String? = null,
)

data class BatchImportReport(
    val projectId: String,
    val items: List<BatchImportItem>,
) {
    val addedCount: Int get() = items.count { it.status == BatchImportStatus.ADDED }
    val alreadyStoredCount: Int get() = items.count { it.status == BatchImportStatus.ALREADY_STORED }
    val wrongProjectCount: Int get() = items.count { it.status == BatchImportStatus.WRONG_PROJECT }
    val failedCount: Int get() = items.count { it.status == BatchImportStatus.FAILED }
    val skippedCount: Int get() = items.size - addedCount
}

enum class IconRegenerationOutcome {
    UPDATED,
    UNCHANGED,
    FAILED,
}

data class IconRegenerationSummary(
    val updated: Int,
    val unchanged: Int,
    val failed: Int,
)

enum class ReplaceReason {
    DOWNGRADE,
    SIGNATURE_MISMATCH,
}

data class ReplaceRequest(
    val record: ApkRecord,
    val installedVersionName: String?,
    val installedVersionCode: Long,
    val reason: ReplaceReason,
)
