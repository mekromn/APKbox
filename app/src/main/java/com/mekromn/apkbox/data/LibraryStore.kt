package com.mekromn.apkbox.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.ChunkRef
import com.mekromn.apkbox.model.ImportResult
import com.mekromn.apkbox.model.VaultStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

class LibraryStore(context: Context) {
    companion object {
        private val MANIFEST_MAGIC = "APKBOXM1".toByteArray(Charsets.US_ASCII)
        const val LEGACY_PROJECT_ID = "legacy-default-project"
    }

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, "apkbox-vault")
    private val manifestsDir = File(rootDir, "manifests")
    private val indexFile = File(rootDir, "library.json")
    private val chunkStore = ChunkStore(File(rootDir, "chunks"))
    private val mutex = Mutex()

    private val _records: MutableStateFlow<List<ApkRecord>>
    val records: StateFlow<List<ApkRecord>> get() = _records.asStateFlow()

    private val _stats: MutableStateFlow<VaultStats>
    val stats: StateFlow<VaultStats> get() = _stats.asStateFlow()

    init {
        rootDir.mkdirs()
        manifestsDir.mkdirs()
        val loaded = loadIndex()
        val repaired = repairMetadataFromManifests(loaded)
        _records = MutableStateFlow(repaired)
        _stats = MutableStateFlow(calculateStats(repaired))
        if (repaired != loaded) runCatching { saveIndex(repaired) }
    }

    suspend fun importBase(uri: Uri): ImportResult = importApk(uri, isBase = true)

    suspend fun importRevision(uri: Uri): ImportResult = importApk(uri, isBase = false)

    private suspend fun importApk(uri: Uri, isBase: Boolean): ImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _records.value
            if (isBase && current.isNotEmpty()) {
                error("A base APK is already saved. Clear the vault before choosing a different base.")
            }
            if (!isBase && current.none { it.isBase }) {
                error("Choose a base APK first.")
            }

            val sourceDisplayName = documentDisplayName(uri)
            val tempFile = File(appContext.cacheDir, "apkbox-import-${UUID.randomUUID()}.apk")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output, 256 * 1024) }
                } ?: error("The selected APK could not be opened.")

                val archive = ApkInspector.inspect(appContext, tempFile)
                val base = current.firstOrNull { it.isBase }
                if (!isBase && base != null && archive.packageName != base.packageName) {
                    error(
                        "This APK belongs to ${archive.packageName}, but the saved base is ${base.packageName}. " +
                            "APKbox keeps one package family per vault."
                    )
                }

                val chunking = chunkStore.ingest(tempFile)
                if (current.any { it.sha256 == chunking.apkSha256 }) {
                    error("That exact APK is already stored in APKbox.")
                }

                val authoritativeSize = chunking.chunks.sumOf { it.size.toLong() }
                check(authoritativeSize == tempFile.length()) {
                    "Import size verification failed before the APK was stored."
                }

                val id = UUID.randomUUID().toString()
                val record = ApkRecord(
                    id = id,
                    projectId = LEGACY_PROJECT_ID,
                    displayName = sourceDisplayName ?: archive.label,
                    label = archive.label,
                    packageName = archive.packageName,
                    versionName = archive.versionName,
                    versionCode = archive.versionCode,
                    sizeBytes = authoritativeSize,
                    sha256 = chunking.apkSha256,
                    signingCertSha256 = archive.signingCertSha256,
                    addedAtEpochMs = System.currentTimeMillis(),
                    isBase = isBase,
                    chunkCount = chunking.chunks.size,
                    newBytesAdded = chunking.uniqueBytesAdded,
                )

                writeManifest(record.id, chunking.chunks)
                val updated = (current + record).sortedWith(
                    compareByDescending<ApkRecord> { it.isBase }.thenByDescending { it.addedAtEpochMs }
                )
                saveIndex(updated)
                _records.value = updated
                _stats.value = calculateStats(updated)

                ImportResult(
                    record = record,
                    reusedBytes = max(0L, record.sizeBytes - record.newBytesAdded),
                )
            } catch (t: Throwable) {
                garbageCollectInternal(_records.value)
                throw t
            } finally {
                tempFile.delete()
            }
        }
    }

    suspend fun deleteRevision(recordId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = _records.value.firstOrNull { it.id == recordId } ?: return@withLock
            require(!record.isBase) { "The base APK cannot be deleted while revisions exist. Clear the vault instead." }

            val updated = _records.value.filterNot { it.id == recordId }
            manifestFile(recordId).delete()
            saveIndex(updated)
            _records.value = updated
            garbageCollectInternal(updated)
            _stats.value = calculateStats(updated)
        }
    }

    suspend fun clearVault() = withContext(Dispatchers.IO) {
        mutex.withLock {
            rootDir.deleteRecursively()
            rootDir.mkdirs()
            manifestsDir.mkdirs()
            _records.value = emptyList()
            _stats.value = VaultStats()
        }
    }

    /** Returns the APK size derived from its ordered manifest, never cached metadata. */
    suspend fun authoritativeSize(record: ApkRecord): Long = withContext(Dispatchers.IO) {
        mutex.withLock { readManifest(record.id).sumOf { it.size.toLong() } }
    }

    /** Streams an exact stored APK and verifies manifest byte count + original full-file SHA-256. */
    suspend fun streamApk(record: ApkRecord, output: OutputStream) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val chunks = readManifest(record.id)
            val expectedSize = chunks.sumOf { it.size.toLong() }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            var written = 0L

            for (chunk in chunks) {
                val file = chunkStore.chunkFile(chunk.hash)
                check(file.isFile && file.length() == chunk.size.toLong()) {
                    "Vault chunk ${chunk.hash.take(12)} is missing or corrupt."
                }
                FileInputStream(file).use { input ->
                    var remaining = chunk.size
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        check(count > 0) { "Unexpected end of chunk ${chunk.hash.take(12)}." }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                        remaining -= count
                    }
                }
            }
            output.flush()

            check(written == expectedSize) {
                "Reconstruction size mismatch: manifest requires $expectedSize bytes, wrote $written."
            }
            val actualSha = digest.digest().toHex()
            check(actualSha == record.sha256) {
                "Reconstruction checksum mismatch. Operation cancelled to protect the stored build."
            }

            if (record.sizeBytes != expectedSize || record.chunkCount != chunks.size) {
                val repaired = _records.value.map {
                    if (it.id == record.id) it.copy(sizeBytes = expectedSize, chunkCount = chunks.size) else it
                }
                _records.value = repaired
                saveIndex(repaired)
                _stats.value = calculateStats(repaired)
            }
        }
    }

    private fun repairMetadataFromManifests(records: List<ApkRecord>): List<ApkRecord> = records.map { record ->
        runCatching {
            val chunks = readManifest(record.id)
            val authoritativeSize = chunks.sumOf { it.size.toLong() }
            if (record.sizeBytes != authoritativeSize || record.chunkCount != chunks.size) {
                record.copy(sizeBytes = authoritativeSize, chunkCount = chunks.size)
            } else record
        }.getOrDefault(record)
    }

    private fun writeManifest(recordId: String, chunks: List<ChunkRef>) {
        val target = manifestFile(recordId)
        val temp = File(target.parentFile, ".${target.name}.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { output ->
            output.write(MANIFEST_MAGIC)
            output.writeInt(chunks.size)
            for (chunk in chunks) {
                val hashBytes = chunk.hash.hexToBytes()
                require(hashBytes.size == 32) { "Expected a SHA-256 chunk hash." }
                output.write(hashBytes)
                output.writeInt(chunk.size)
            }
        }
        atomicReplace(temp, target)
    }

    private fun readManifest(recordId: String): List<ChunkRef> {
        val source = manifestFile(recordId)
        DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            val magic = ByteArray(MANIFEST_MAGIC.size)
            input.readFully(magic)
            check(magic.contentEquals(MANIFEST_MAGIC)) { "Unsupported APKbox manifest format." }
            val count = input.readInt()
            check(count >= 0) { "Invalid manifest chunk count." }
            return ArrayList<ChunkRef>(count).also { chunks ->
                repeat(count) {
                    val hash = ByteArray(32)
                    input.readFully(hash)
                    val size = input.readInt()
                    check(size > 0) { "Invalid chunk size in manifest." }
                    chunks += ChunkRef(hash.toHex(), size)
                }
            }
        }
    }

    private fun saveIndex(records: List<ApkRecord>) {
        val array = JSONArray()
        records.forEach { record -> array.put(record.toJson()) }
        val root = JSONObject().put("schema", 2).put("records", array)
        val temp = File(rootDir, ".library.json.tmp")
        FileOutputStream(temp).use { output ->
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        atomicReplace(temp, indexFile)
    }

    private fun loadIndex(): List<ApkRecord> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(indexFile.readText())
            val array = root.getJSONArray("records")
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toRecord())
            }
        }.getOrDefault(emptyList())
    }

    private fun garbageCollectInternal(records: List<ApkRecord>) {
        val referenced = HashSet<String>()
        try {
            records.forEach { record ->
                readManifest(record.id).forEach { referenced += it.hash }
            }
        } catch (_: Throwable) {
            // Never delete chunks if any surviving manifest cannot be read safely.
            return
        }
        chunkStore.garbageCollect(referenced)
    }

    private fun calculateStats(records: List<ApkRecord>): VaultStats {
        val logical = records.sumOf { it.sizeBytes }
        val physical = if (rootDir.exists()) {
            rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L
        val saved = max(0L, logical - physical)
        return VaultStats(
            logicalBytes = logical,
            physicalBytes = physical,
            savedBytes = saved,
            savedPercent = if (logical == 0L) 0.0 else saved.toDouble() / logical.toDouble() * 100.0,
            revisionCount = records.count { !it.isBase },
        )
    }

    private fun documentDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun manifestFile(recordId: String) = File(manifestsDir, "$recordId.apkm")

    private fun atomicReplace(temp: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun ApkRecord.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("projectId", projectId)
        .put("displayName", displayName)
        .put("label", label)
        .put("packageName", packageName)
        .put("versionName", versionName)
        .put("versionCode", versionCode)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("signingCertSha256", signingCertSha256 ?: JSONObject.NULL)
        .put("addedAtEpochMs", addedAtEpochMs)
        .put("isBase", isBase)
        .put("chunkCount", chunkCount)
        .put("newBytesAdded", newBytesAdded)

    private fun JSONObject.toRecord(): ApkRecord = ApkRecord(
        id = getString("id"),
        projectId = optString("projectId").takeIf { it.isNotBlank() } ?: LEGACY_PROJECT_ID,
        displayName = optString("displayName").takeIf { it.isNotBlank() } ?: getString("label"),
        label = getString("label"),
        packageName = getString("packageName"),
        versionName = getString("versionName"),
        versionCode = getLong("versionCode"),
        sizeBytes = optLong("sizeBytes", 0L),
        sha256 = getString("sha256"),
        signingCertSha256 = if (isNull("signingCertSha256")) null else getString("signingCertSha256"),
        addedAtEpochMs = getLong("addedAtEpochMs"),
        isBase = getBoolean("isBase"),
        chunkCount = optInt("chunkCount", 0),
        newBytesAdded = optLong("newBytesAdded", 0L),
    )
}
