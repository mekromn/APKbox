package com.mekromn.apkbox.artifacts

import android.content.Context
import com.mekromn.apkbox.bridge.PrivilegedBridgeManager
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.jobs.DurableJobEngine
import com.mekromn.apkbox.model.ApkRecord
import java.io.File
import java.io.FileOutputStream

enum class ArtifactSourceKind {
    CONTENT_CACHE,
    DURABLE_JOB_FILE,
    INSTALLED_BASE_APK,
    APKBOX_VAULT,
}

data class ResolvedArtifact(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
    val sourceKind: ArtifactSourceKind,
    val sourceDetail: String,
    val apkRecordId: String = "",
)

/**
 * Resolves an exact APK by SHA from the fastest trustworthy source already available on-device.
 *
 * The vault is deliberately not privileged: a verified cache object, already-materialized build,
 * or matching installed base.apk wins when it is cheaper. Every substitution is SHA-proven before
 * the caller receives it. Network is not handled here; callers use ArtifactIngestor only after all
 * faster exact local candidates have been exhausted.
 */
class ArtifactSourceResolver(
    context: Context,
    private val artifacts: ArtifactIngestor,
    private val library: LibraryStore,
    private val jobs: DurableJobEngine,
    private val privileged: PrivilegedBridgeManager,
) {
    companion object {
        private val SHA_REGEX = Regex("[0-9a-fA-F]{64}")
        private val PACKAGE_REGEX = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val SAFE_REMOTE_PATH = Regex("[/A-Za-z0-9._=:+-]+")
    }

    private val appContext = context.applicationContext
    private val scratch = File(appContext.cacheDir, "artifact-source-resolver").apply { mkdirs() }

    suspend fun resolveExact(
        expectedSha256: String,
        packageName: String = "",
        preferredRecordId: String = "",
    ): ResolvedArtifact? {
        val sha = expectedSha256.trim().lowercase()
        require(SHA_REGEX.matches(sha)) { "Exact source resolution requires a 64-hex SHA-256." }
        val pkg = packageName.trim()
        if (pkg.isNotBlank()) require(PACKAGE_REGEX.matches(pkg)) { "Invalid packageName for exact source resolution." }

        artifacts.objectForSha(sha)?.let { cached ->
            return ResolvedArtifact(
                file = cached,
                sha256 = sha,
                sizeBytes = cached.length(),
                sourceKind = ArtifactSourceKind.CONTENT_CACHE,
                sourceDetail = "Verified content-addressed artifact cache hit.",
            )
        }

        jobs.list(500).firstOrNull { job ->
            job.artifactSha256.equals(sha, ignoreCase = true) && job.artifactPath.isNotBlank()
        }?.let { job ->
            val file = File(job.artifactPath)
            if (file.isFile && file.canRead() && runCatching { artifacts.sha256(file) }.getOrNull() == sha) {
                return ResolvedArtifact(
                    file = file,
                    sha256 = sha,
                    sizeBytes = file.length(),
                    sourceKind = ArtifactSourceKind.DURABLE_JOB_FILE,
                    sourceDetail = "Reused exact materialized artifact from durable job ${job.id}.",
                )
            }
        }

        if (pkg.isNotBlank()) {
            materializeInstalledExact(pkg, sha)?.let { return it }
        }

        val record = preferredRecordId.takeIf { it.isNotBlank() }
            ?.let { id -> library.records.value.firstOrNull { it.id == id } }
            ?.also { require(it.sha256.equals(sha, true)) { "Requested APKbox record does not match expected SHA-256." } }
            ?: library.records.value.firstOrNull { it.sha256.equals(sha, true) }
        if (record != null) return materializeVaultExact(record, sha)

        return null
    }

    private suspend fun materializeInstalledExact(packageName: String, sha: String): ResolvedArtifact? {
        if (!runCatching { privileged.ensureReady() }.getOrDefault(false)) return null
        val pathResult = runCatching { privileged.execute("pm path $packageName", 10) }.getOrNull() ?: return null
        val path = pathResult.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.endsWith("/base.apk") }
            ?.removePrefix("package:") ?: return null
        if (!SAFE_REMOTE_PATH.matches(path)) return null

        val remoteShaResult = runCatching { privileged.execute("sha256sum $path", 30) }.getOrNull() ?: return null
        val remoteSha = Regex("(?i)^[0-9a-f]{64}").find(remoteShaResult.output.trim())?.value?.lowercase() ?: return null
        if (remoteSha != sha) return null

        val external = appContext.externalCacheDir ?: return null
        val temp = File(external, "exact-installed-$sha.apk")
        temp.delete()
        val copy = runCatching {
            privileged.execute(
                "cp ${shellQuote(path)} ${shellQuote(temp.absolutePath)} && chmod 0644 ${shellQuote(temp.absolutePath)}",
                120,
            )
        }.getOrNull() ?: return null
        if (copy.timedOut || (copy.exitCode != null && copy.exitCode != 0) || !temp.isFile) {
            temp.delete()
            return null
        }
        return try {
            val cached = artifacts.adoptLocalFile(temp, expectedSha256 = sha, deleteSource = true)
            ResolvedArtifact(
                file = cached.file,
                sha256 = sha,
                sizeBytes = cached.sizeBytes,
                sourceKind = ArtifactSourceKind.INSTALLED_BASE_APK,
                sourceDetail = "Copied already-installed exact base.apk through ${privileged.activeTransportLabel()} instead of reconstructing/downloading it.",
            )
        } catch (_: Throwable) {
            temp.delete()
            null
        }
    }

    private suspend fun materializeVaultExact(record: ApkRecord, sha: String): ResolvedArtifact {
        val temp = File(scratch, "$sha.apk")
        temp.delete()
        try {
            FileOutputStream(temp).use { output -> library.streamApk(record, output) }
            val cached = artifacts.adoptLocalFile(temp, expectedSha256 = sha, deleteSource = true)
            return ResolvedArtifact(
                file = cached.file,
                sha256 = sha,
                sizeBytes = cached.sizeBytes,
                sourceKind = ArtifactSourceKind.APKBOX_VAULT,
                sourceDetail = "Reconstructed exact APKbox record ${record.id}; faster exact local sources were unavailable.",
                apkRecordId = record.id,
            )
        } finally {
            temp.delete()
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
