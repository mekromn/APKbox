package com.mekromn.apkbox.model

data class ChunkRef(
    val hash: String,
    val size: Int,
)

data class ApkRecord(
    val id: String,
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
