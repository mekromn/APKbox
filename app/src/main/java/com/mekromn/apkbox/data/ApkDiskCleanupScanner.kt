package com.mekromn.apkbox.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mekromn.apkbox.model.ApkRecord
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Finds APK files in shared storage and identifies exact byte-for-byte copies already stored in
 * APKbox. Matching is always SHA-256 based; filename, package metadata, version code, and file size
 * are only discovery/prefilter hints and are never proof that a disk copy is safely recoverable.
 *
 * On Android 10+ the cleanup path prefers MediaStore content streams for indexed APKs, while still
 * merging direct-filesystem discovery so unindexed files are not lost. This avoids making the whole
 * feature depend on one flaky direct FUSE path.
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

    private data class Source(
        val key: String,
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val modifiedAtEpochMs: Long,
        val openStream: () -> InputStream?,
    )

    private data class Discovery(
        val sources: List<Source>,
        val directoriesVisited: Int,
        val unreadableDirectories: Int,
    )

    fun scan(context: Context, root: File, records: List<ApkRecord>): ScanResult {
        val direct = discoverDirect(root)
        val merged = LinkedHashMap<String, Source>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            discoverMediaStore(context).forEach { source -> merged[source.key] = source }
        }

        direct.sources.forEach { source -> merged.putIfAbsent(source.key, source) }

        return matchSources(
            sources = merged.values.toList(),
            records = records,
            directoriesVisited = direct.directoriesVisited,
            unreadableDirectories = direct.unreadableDirectories,
        )
    }

    fun scan(root: File, records: List<ApkRecord>): ScanResult {
        val direct = discoverDirect(root)
        return matchSources(
            sources = direct.sources,
            records = records,
            directoriesVisited = direct.directoriesVisited,
            unreadableDirectories = direct.unreadableDirectories,
        )
    }

    private fun discoverDirect(root: File): Discovery {
        if (!runCatching { root.isDirectory }.getOrDefault(false)) {
            return Discovery(emptyList(), 0, 1)
        }

        val sources = ArrayList<Source>()
        val stack = ArrayDeque<File>()
        val seenDirectories = HashSet<String>()
        stack.add(root)
        var visited = 0
        var unreadableDirectories = 0

        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            val canonical = try {
                directory.canonicalPath
            } catch (_: Throwable) {
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
                        file.isFile && file.extension.equals("apk", ignoreCase = true) -> {
                            val path = file.absolutePath
                            val key = path.lowercase()
                            sources += Source(
                                key = key,
                                path = path,
                                name = file.name,
                                sizeBytes = file.length(),
                                modifiedAtEpochMs = file.lastModified(),
                                openStream = { FileInputStream(file) },
                            )
                        }
                    }
                }
            }
        }

        return Discovery(sources, visited, unreadableDirectories)
    }

    private fun discoverMediaStore(context: Context): List<Source> {
        val resolver = context.applicationContext.contentResolver
        val primaryRoot = Environment.getExternalStorageDirectory()
        val result = LinkedHashMap<String, Source>()
        val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }
            .getOrDefault(setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY))

        volumes.forEach { volume ->
            val table = MediaStore.Files.getContentUri(volume)
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
            val cursor = runCatching {
                resolver.query(
                    table,
                    projection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("%.apk"),
                    null,
                )
            }.getOrNull() ?: return@forEach

            cursor.use { rows ->
                val idColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val relativeColumn = rows.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                while (rows.moveToNext()) {
                    val id = rows.getLong(idColumn)
                    val name = rows.getString(nameColumn) ?: continue
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val size = rows.getLong(sizeColumn).coerceAtLeast(0L)
                    val modified = rows.getLong(modifiedColumn).coerceAtLeast(0L) * 1000L
                    val contentUri = ContentUris.withAppendedId(table, id)
                    val relative = if (relativeColumn >= 0 && !rows.isNull(relativeColumn)) {
                        rows.getString(relativeColumn).orEmpty()
                    } else ""
                    val path = if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                        File(primaryRoot, relative + name).absolutePath
                    } else {
                        contentUri.toString()
                    }
                    val key = path.lowercase()
                    result[key] = Source(
                        key = key,
                        path = path,
                        name = name,
                        sizeBytes = size,
                        modifiedAtEpochMs = modified,
                        openStream = { resolver.openInputStream(contentUri) },
                    )
                }
            }
        }
        return result.values.toList()
    }

    private fun matchSources(
        sources: List<Source>,
        records: List<ApkRecord>,
        directoriesVisited: Int,
        unreadableDirectories: Int,
    ): ScanResult {
        if (sources.isEmpty()) {
            return ScanResult(emptyList(), directoriesVisited, unreadableDirectories, 0)
        }

        val recordsByHash = records.associateBy { it.sha256.lowercase() }
        val recordsBySize = records.groupBy { it.sizeBytes }
        val recordsByName = records.groupBy { it.displayName.lowercase() }
        val matches = HashMap<String, ApkRecord>()
        val unreadable = LinkedHashSet<String>()
        val hashes = HashMap<String, String>()

        sources.forEach { source ->
            val worthHashing = recordsBySize.containsKey(source.sizeBytes) ||
                recordsByName.containsKey(source.name.lowercase())
            if (!worthHashing) return@forEach
            val hash = sha256OrNull(source.openStream)
            if (hash == null) {
                unreadable += source.key
                return@forEach
            }
            hashes[source.key] = hash
            recordsByHash[hash]?.let { matches[source.key] = it }
        }

        if (matches.isEmpty() && recordsByHash.isNotEmpty()) {
            sources.forEach { source ->
                if (source.key in hashes || source.key in unreadable) return@forEach
                val hash = sha256OrNull(source.openStream)
                if (hash == null) {
                    unreadable += source.key
                    return@forEach
                }
                hashes[source.key] = hash
                recordsByHash[hash]?.let { matches[source.key] = it }
            }
        }

        val candidates = sources
            .map { source ->
                Candidate(
                    path = source.path,
                    name = source.name,
                    sizeBytes = source.sizeBytes,
                    modifiedAtEpochMs = source.modifiedAtEpochMs,
                    storedRecord = matches[source.key],
                    hashReadFailed = source.key in unreadable,
                )
            }
            .distinctBy { it.path.lowercase() }
            .sortedWith(
                compareByDescending<Candidate> { it.isSafelyStored }
                    .thenBy { it.hashReadFailed }
                    .thenByDescending { it.sizeBytes }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.path.lowercase() }
            )

        return ScanResult(
            candidates = candidates,
            directoriesVisited = directoriesVisited,
            unreadableDirectories = unreadableDirectories,
            unreadableFiles = unreadable.size,
        )
    }

    private fun sha256OrNull(openStream: () -> InputStream?): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = runCatching { openStream() }.getOrNull() ?: return null
        return try {
            val buffer = ByteArray(512 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            digest.digest().toHex()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { input.close() }
        }
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
