package com.mekromn.apkbox.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable, exact backup/restore for the entire APKbox vault.
 *
 * Restore is deliberately transactional: a backup is extracted into a separate candidate vault,
 * every stored APK is re-hashed from its ordered manifest/chunks, and only a fully valid candidate
 * is allowed to replace the live vault. The old vault is kept until the new one is in place.
 */
class VaultBackupManager(context: Context) {
    companion object {
        private const val BACKUP_FORMAT = "APKBOX_BACKUP_V1"
        private val MANIFEST_MAGIC = "APKBOXM1".toByteArray(Charsets.US_ASCII)
    }

    data class RestoreSummary(
        val projects: Int,
        val records: Int,
        val logicalBytes: Long,
    )

    private val appContext = context.applicationContext
    private val liveRoot = File(appContext.filesDir, "apkbox-vault")

    fun writeBackup(output: OutputStream) {
        require(File(liveRoot, "library.json").isFile) { "APKbox vault has no library index to back up." }

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val marker = JSONObject()
                .put("format", BACKUP_FORMAT)
                .put("createdAtEpochMs", System.currentTimeMillis())
                .toString()
                .toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(marker)
            zip.closeEntry()

            liveRoot.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        !file.name.startsWith(".") &&
                        !file.name.endsWith(".tmp", ignoreCase = true)
                }
                .forEach { file ->
                    val relative = file.relativeTo(liveRoot).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("vault/$relative"))
                    FileInputStream(file).use { input -> input.copyTo(zip, 256 * 1024) }
                    zip.closeEntry()
                }
        }
    }

    fun restoreBackup(uri: Uri): RestoreSummary {
        val candidate = File(appContext.filesDir, "apkbox-restore-${UUID.randomUUID()}")
        val previous = File(appContext.filesDir, "apkbox-previous-${UUID.randomUUID()}")
        candidate.mkdirs()

        try {
            extractBackup(uri, candidate)
            val summary = validateCandidate(candidate)

            if (liveRoot.exists()) {
                check(liveRoot.renameTo(previous)) {
                    "Could not stage the current APKbox vault for replacement. Nothing was changed."
                }
            }

            if (!candidate.renameTo(liveRoot)) {
                if (previous.exists()) previous.renameTo(liveRoot)
                error("Could not activate the restored vault. The previous vault was restored.")
            }

            previous.deleteRecursively()
            return summary
        } catch (t: Throwable) {
            candidate.deleteRecursively()
            if (!liveRoot.exists() && previous.exists()) {
                previous.renameTo(liveRoot)
            }
            throw t
        } finally {
            candidate.deleteRecursively()
            if (previous.exists() && liveRoot.exists()) previous.deleteRecursively()
        }
    }

    private fun extractBackup(uri: Uri, candidate: File) {
        var sawMarker = false
        appContext.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == "backup.json" -> {
                            val json = zip.readBytes().toString(Charsets.UTF_8)
                            require(JSONObject(json).optString("format") == BACKUP_FORMAT) {
                                "Unsupported or damaged APKbox backup format."
                            }
                            sawMarker = true
                        }
                        entry.name.startsWith("vault/") && !entry.isDirectory -> {
                            val relative = entry.name.removePrefix("vault/")
                            require(relative.isNotBlank() && !relative.split('/').contains("..")) {
                                "Backup contains an unsafe path."
                            }
                            val target = File(candidate, relative).canonicalFile
                            val candidatePrefix = candidate.canonicalPath + File.separator
                            require(target.path.startsWith(candidatePrefix)) {
                                "Backup contains a path outside the APKbox vault."
                            }
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> zip.copyTo(output, 256 * 1024) }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("The selected backup could not be opened.")

        require(sawMarker) { "This file is not an APKbox master backup." }
    }

    private fun validateCandidate(candidate: File): RestoreSummary {
        val indexFile = File(candidate, "library.json")
        require(indexFile.isFile) { "Backup is missing library.json." }
        val root = JSONObject(indexFile.readText())
        val records = root.optJSONArray("records") ?: error("Backup library index has no records array.")
        val manifestsDir = File(candidate, "manifests")
        val chunksDir = File(candidate, "chunks")
        val buffer = ByteArray(256 * 1024)
        var logicalBytes = 0L

        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val id = record.getString("id")
            val displayName = record.optString("displayName", record.optString("label", id))
            val expectedApkHash = record.getString("sha256")
            require(expectedApkHash.length == 64) { "Invalid APK hash for $displayName." }

            val manifest = readManifest(File(manifestsDir, "$id.apkm"))
            require(manifest.isNotEmpty()) { "Backup manifest for $displayName is empty." }
            val apkDigest = MessageDigest.getInstance("SHA-256")
            var recordBytes = 0L

            manifest.forEach { chunk ->
                val chunkFile = File(File(chunksDir, chunk.hash.take(2)), "${chunk.hash}.chunk")
                require(chunkFile.isFile && chunkFile.length() == chunk.size.toLong()) {
                    "Backup is missing/corrupt chunk ${chunk.hash.take(12)} for $displayName."
                }

                // Validate both the chunk's content-address and the reconstructed APK stream.
                val chunkDigest = MessageDigest.getInstance("SHA-256")
                FileInputStream(chunkFile).use { input ->
                    var remaining = chunk.size
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        require(count > 0) { "Chunk ${chunk.hash.take(12)} ended unexpectedly." }
                        chunkDigest.update(buffer, 0, count)
                        apkDigest.update(buffer, 0, count)
                        remaining -= count
                        recordBytes += count
                    }
                }
                require(chunkDigest.digest().toHex() == chunk.hash) {
                    "Chunk ${chunk.hash.take(12)} failed SHA-256 validation."
                }
            }

            require(apkDigest.digest().toHex() == expectedApkHash) {
                "Reconstructed SHA-256 failed for $displayName. The live vault was not changed."
            }
            logicalBytes += recordBytes
        }

        val projects = File(candidate, "projects.json").takeIf { it.isFile }?.let { file ->
            runCatching { JSONObject(file.readText()).optJSONArray("projects")?.length() ?: 0 }.getOrDefault(0)
        } ?: if (records.length() > 0) 1 else 0

        return RestoreSummary(
            projects = projects,
            records = records.length(),
            logicalBytes = logicalBytes,
        )
    }

    private data class BackupChunk(val hash: String, val size: Int)

    private fun readManifest(file: File): List<BackupChunk> {
        require(file.isFile) { "Backup is missing manifest ${file.name}." }
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = ByteArray(MANIFEST_MAGIC.size)
            input.readFully(magic)
            require(magic.contentEquals(MANIFEST_MAGIC)) { "Unsupported APKbox manifest ${file.name}." }
            val count = input.readInt()
            require(count >= 0 && count < 1_000_000) { "Invalid manifest chunk count in ${file.name}." }
            return List(count) {
                val hash = ByteArray(32)
                input.readFully(hash)
                val size = input.readInt()
                require(size > 0) { "Invalid chunk size in ${file.name}." }
                BackupChunk(hash.toHex(), size)
            }
        }
    }
}
