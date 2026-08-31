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
