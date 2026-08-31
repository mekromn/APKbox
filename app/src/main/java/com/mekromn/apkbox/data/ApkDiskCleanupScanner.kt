package com.mekromn.apkbox.data

import android.content.Context
import android.media.MediaScannerConnection
import com.mekromn.apkbox.model.ApkRecord
import java.io.File

/**
 * Finds APK files in shared storage and identifies exact byte-for-byte copies already stored in
 * APKbox. Matching is SHA-256 based; filename, package metadata, and version code are never used as
 * proof that a disk copy is safely recoverable from the vault.
 */
object ApkDiskCleanupScanner {
    data class Candidate(
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val modifiedAtEpochMs: Long,
        val storedRecord: ApkRecord?,
    ) {
        val isSafelyStored: Boolean get() = storedRecord != null
    }

    data class ScanResult(
        val candidates: List<Candidate>,
        val directoriesVisited: Int,
        val unreadableDirectories: Int,
    )

    data class DeleteResult(
        val deletedPaths: Set<String>,
        val failedPaths: Set<String>,
        val bytesReclaimed: Long,
    )

    fun scan(root: File, records: List<ApkRecord>): ScanResult {
        if (!root.isDirectory) return ScanResult(emptyList(), 0, 1)

        val apkFiles = ArrayList<File>()
        val stack = ArrayDeque<File>()
        val seenDirectories = HashSet<String>()
        stack.add(root)
        var visited = 0
        var unreadable = 0

        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            val canonical = runCatching { directory.canonicalPath }.getOrNull() ?: continue
            if (!seenDirectories.add(canonical)) continue
            visited++

            val children = runCatching { directory.listFiles() }.getOrNull()
            if (children == null) {
                unreadable++
                continue
            }

            children.forEach { file ->
                when {
                    file.isDirectory -> stack.add(file)
                    file.isFile && file.extension.equals("apk", ignoreCase = true) -> apkFiles += file
                }
            }
        }

        // StoredApkMatcher hashes only files whose size could match a vault record, so scanning a
        // folder full of unrelated APKs does not needlessly hash every file.
        val exactMatches = StoredApkMatcher.findMatches(apkFiles, records)
        val candidates = apkFiles
            .asSequence()
            .map { file ->
                Candidate(
                    path = file.absolutePath,
                    name = file.name,
                    sizeBytes = file.length(),
                    modifiedAtEpochMs = file.lastModified(),
                    storedRecord = exactMatches[file.absolutePath],
                )
            }
            .sortedWith(
                compareByDescending<Candidate> { it.isSafelyStored }
                    .thenByDescending { it.sizeBytes }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.path.lowercase() }
            )
            .toList()

        return ScanResult(candidates, visited, unreadable)
    }

    fun delete(context: Context, paths: Collection<String>): DeleteResult {
        val deleted = LinkedHashSet<String>()
        val failed = LinkedHashSet<String>()
        var bytesReclaimed = 0L

        paths.distinct().forEach { path ->
            val file = File(path)
            if (!file.isFile || !file.extension.equals("apk", ignoreCase = true)) {
                failed += path
                return@forEach
            }
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                deleted += path
                bytesReclaimed += size
            } else {
                failed += path
            }
        }

        // Ask MediaStore to discard stale metadata/thumbnails for paths that were physically removed.
        if (deleted.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    deleted.toTypedArray(),
                    null,
                    null,
                )
            }
        }

        return DeleteResult(
            deletedPaths = deleted,
            failedPaths = failed,
            bytesReclaimed = bytesReclaimed,
        )
    }
}
