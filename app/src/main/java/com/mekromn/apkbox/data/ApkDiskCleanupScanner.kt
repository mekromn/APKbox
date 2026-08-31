package com.mekromn.apkbox.data

import android.content.Context
import android.media.MediaScannerConnection
import com.mekromn.apkbox.model.ApkRecord
import java.io.File

/**
 * Finds APK files in shared storage and identifies exact byte-for-byte copies already stored in
 * APKbox. Matching is SHA-256 based; filename, package metadata, and version code are never used as
 * proof that a disk copy is safely recoverable from the vault.
 *
 * Shared-storage faults are isolated to the affected directory/file. One flaky FUSE/provider node
 * must never collapse the complete cleanup scan into a false zero-result state.
 */
object ApkDiskCleanupScanner {
    data class Candidate(
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val modifiedAtEpochMs: Long,
        val storedRecord: ApkRecord?,
        val hashReadFailed: Boolean = false,
    ) {
        val isSafelyStored: Boolean get() = storedRecord != null
    }

    data class ScanResult(
        val candidates: List<Candidate>,
        val directoriesVisited: Int,
        val unreadableDirectories: Int,
        val unreadableFiles: Int = 0,
    )

    data class DeleteResult(
        val deletedPaths: Set<String>,
        val failedPaths: Set<String>,
        val bytesReclaimed: Long,
    )

    fun scan(root: File, records: List<ApkRecord>): ScanResult {
        if (!runCatching { root.isDirectory }.getOrDefault(false)) {
            return ScanResult(emptyList(), 0, 1, 0)
        }

        val apkFiles = ArrayList<File>()
        val stack = ArrayDeque<File>()
        val seenDirectories = HashSet<String>()
        stack.add(root)
        var visited = 0
        var unreadableDirectories = 0

        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            val canonical = runCatching { directory.canonicalPath }.getOrElse {
                unreadableDirectories++
                continue
            }
            if (!seenDirectories.add(canonical)) continue
            visited++

            val children = runCatching { directory.listFiles() }.getOrNull()
            if (children == null) {
                unreadableDirectories++
                continue
            }

            children.forEach { file ->
                runCatching {
                    when {
                        file.isDirectory -> stack.add(file)
                        file.isFile && file.extension.equals("apk", ignoreCase = true) -> apkFiles += file
                    }
                }.onFailure {
                    // A single broken directory entry is treated like an inaccessible child, not a
                    // fatal scan error. Directory-level failures are already counted above.
                }
            }
        }

        // StoredApkMatcher hashes only files whose size could match a vault record, so scanning a
        // folder full of unrelated APKs does not needlessly hash every file. Hash/read failures are
        // returned per-file and never abort the rest of the match pass.
        val matchResult = StoredApkMatcher.findMatchesDetailed(apkFiles, records)
        val candidates = apkFiles
            .asSequence()
            .mapNotNull { file ->
                runCatching {
                    Candidate(
                        path = file.absolutePath,
                        name = file.name,
                        sizeBytes = file.length(),
                        modifiedAtEpochMs = file.lastModified(),
                        storedRecord = matchResult.matches[file.absolutePath],
                        hashReadFailed = file.absolutePath in matchResult.unreadablePaths,
                    )
                }.getOrNull()
            }
            .sortedWith(
                compareByDescending<Candidate> { it.isSafelyStored }
                    .thenBy { it.hashReadFailed }
                    .thenByDescending { it.sizeBytes }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.path.lowercase() }
            )
            .toList()

        return ScanResult(
            candidates = candidates,
            directoriesVisited = visited,
            unreadableDirectories = unreadableDirectories,
            unreadableFiles = matchResult.unreadablePaths.size,
        )
    }

    fun delete(context: Context, paths: Collection<String>): DeleteResult {
        val deleted = LinkedHashSet<String>()
        val failed = LinkedHashSet<String>()
        var bytesReclaimed = 0L

        paths.distinct().forEach { path ->
            val file = File(path)
            if (!runCatching { file.isFile && file.extension.equals("apk", ignoreCase = true) }.getOrDefault(false)) {
                failed += path
                return@forEach
            }
            val size = runCatching { file.length() }.getOrDefault(0L)
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
