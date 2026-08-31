package com.mekromn.apkbox.data

import com.mekromn.apkbox.model.ApkRecord
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Finds exact APKs that already exist in the vault.
 *
 * We first compare file sizes, which is effectively free. SHA-256 is only calculated when a file's
 * size matches at least one stored APK, keeping normal folder browsing fast even when it contains
 * many unrelated APKs.
 *
 * Android shared storage/FUSE can occasionally report an I/O error while closing an otherwise fully
 * read file. A close error must never abort the whole batch. If every byte was read successfully,
 * the SHA-256 is still authoritative; genuine open/read failures are isolated to that one file.
 */
internal object StoredApkMatcher {
    data class MatchResult(
        val matches: Map<String, ApkRecord>,
        val unreadablePaths: Set<String>,
    )

    fun findMatches(files: List<File>, records: List<ApkRecord>): Map<String, ApkRecord> =
        findMatchesDetailed(files, records).matches

    fun findMatchesDetailed(files: List<File>, records: List<ApkRecord>): MatchResult {
        if (files.isEmpty() || records.isEmpty()) return MatchResult(emptyMap(), emptySet())

        val recordsBySize = records.groupBy { it.sizeBytes }
        val matches = HashMap<String, ApkRecord>()
        val unreadable = LinkedHashSet<String>()
        val buffer = ByteArray(512 * 1024)

        files.asSequence()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .forEach { file ->
                val candidates = recordsBySize[file.length()] ?: return@forEach
                val candidateHashes = candidates.associateBy { it.sha256 }
                val hash = sha256OrNull(file, buffer)
                if (hash == null) {
                    unreadable += file.absolutePath
                    return@forEach
                }
                candidateHashes[hash]?.let { matches[file.absolutePath] = it }
            }

        return MatchResult(matches, unreadable)
    }

    private fun sha256OrNull(file: File, buffer: ByteArray): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = runCatching { FileInputStream(file) }.getOrNull() ?: return null
        return try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            digest.digest().toHex()
        } catch (_: Throwable) {
            null
        } finally {
            // Some Android/FUSE paths can throw EIO only from close() after a complete successful
            // read. Swallow that close-only error; an actual read/open error above still returns null.
            runCatching { input.close() }
        }
    }
}
