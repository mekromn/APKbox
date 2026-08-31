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
 */
internal object StoredApkMatcher {
    fun findMatches(files: List<File>, records: List<ApkRecord>): Map<String, ApkRecord> {
        if (files.isEmpty() || records.isEmpty()) return emptyMap()

        val recordsBySize = records.groupBy { it.sizeBytes }
        val matches = HashMap<String, ApkRecord>()
        val buffer = ByteArray(512 * 1024)

        files.asSequence()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .forEach { file ->
                val candidates = recordsBySize[file.length()] ?: return@forEach
                val candidateHashes = candidates.associateBy { it.sha256 }
                val hash = sha256(file, buffer)
                candidateHashes[hash]?.let { matches[file.absolutePath] = it }
            }

        return matches
    }

    private fun sha256(file: File, buffer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }
}
