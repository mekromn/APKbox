package com.mekromn.apkbox.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.ChunkRef
import com.mekromn.apkbox.model.IconRegenerationOutcome
import com.mekromn.apkbox.model.IconRegenerationSummary
import com.mekromn.apkbox.model.ImportResult
import com.mekromn.apkbox.model.VaultStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class LibraryStore(context: Context) {
    companion object {
        private val MANIFEST_MAGIC = "APKBOXM1".toByteArray(Charsets.US_ASCII)
        const val LEGACY_PROJECT_ID = "legacy-default-project"
    }

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, "apkbox-vault")
    private val manifestsDir = File(rootDir, "manifests")
    private val iconsDir = File(rootDir, "icons")
    private val indexFile = File(rootDir, "library.json")
    private val projectsFile = File(rootDir, "projects.json")
    private val statsFile = File(rootDir, "stats.json")
    private val chunkStore = ChunkStore(File(rootDir, "chunks"))
    private val mutex = Mutex()
    private val statsRefreshMutex = Mutex()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statsGeneration = AtomicLong(0L)

    private val _projects: MutableStateFlow<List<ApkProject>>
    val projects: StateFlow<List<ApkProject>> get() = _projects.asStateFlow()

    private val _records: MutableStateFlow<List<ApkRecord>>
    val records: StateFlow<List<ApkRecord>> get() = _records.asStateFlow()

    private val _stats: MutableStateFlow<VaultStats>
    val stats: StateFlow<VaultStats> get() = _stats.asStateFlow()

    init {
        rootDir.mkdirs()
        manifestsDir.mkdirs()
        iconsDir.mkdirs()

        // Startup is deliberately index-first. These two JSON files are tiny compared with the
        // chunk vault, so the first frame can render without reading every manifest/chunk.
        val loadedRecords = sortRecords(loadIndex())
        val loadedProjects = loadProjects()
        val fastProjects = repairProjects(loadedProjects, loadedRecords)

        _projects = MutableStateFlow(fastProjects)
        _records = MutableStateFlow(loadedRecords)
        _stats = MutableStateFlow(loadCachedStats(loadedRecords))

        maintenanceScope.launch {
            if (fastProjects != loadedProjects) runCatching { saveProjects(fastProjects) }

            // Manifest metadata repair remains authoritative, but it no longer blocks app launch.
            val snapshot = _records.value
            val repairedSnapshot = repairMetadataFromManifests(snapshot)
            if (repairedSnapshot != snapshot) {
                val repairs = repairedSnapshot.associateBy { it.id }
                mutex.withLock {
                    val live = _records.value
                    val merged = sortRecords(live.map { record ->
                        repairs[record.id]?.let { repair ->
                            if (record.sizeBytes != repair.sizeBytes || record.chunkCount != repair.chunkCount) {
                                record.copy(sizeBytes = repair.sizeBytes, chunkCount = repair.chunkCount)
                            } else record
                        } ?: record
                    })
                    if (merged != live) {
                        saveIndex(merged)
                        _records.value = merged
                    }
                }
            }
            scheduleStatsRefresh()
        }
    }

    suspend fun importBase(uri: Uri, projectName: String? = null): ImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val projectId = UUID.randomUUID().toString()
            importApkLocked(uri, projectId, isBase = true, pendingProjectName = projectName)
        }
    }

    /** Compatibility helper for a one-project vault. */
    suspend fun importRevision(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val project = _projects.value.singleOrNull()
                ?: error("Choose which APKbox project should receive this revision.")
            importApkLocked(uri, project.id, isBase = false)
        }
    }

    suspend fun importRevision(projectId: String, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(_projects.value.any { it.id == projectId }) { "That APKbox project no longer exists." }
            importApkLocked(uri, projectId, isBase = false)
        }
    }

    private fun importApkLocked(
        uri: Uri,
        projectId: String,
        isBase: Boolean,
        pendingProjectName: String? = null,
    ): ImportResult {
        val current = _records.value
        val existingProject = _projects.value.firstOrNull { it.id == projectId }
        if (!isBase) require(existingProject != null) { "Choose a project first." }

        val sourceDisplayName = documentDisplayName(uri)
        val tempFile = File(appContext.cacheDir, "apkbox-import-${UUID.randomUUID()}.apk")
        var createdRecordId: String? = null
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output, 256 * 1024) }
            } ?: error("The selected APK could not be opened.")

            val archive = ApkInspector.inspect(appContext, tempFile)
            if (!isBase && existingProject != null && archive.packageName != existingProject.packageName) {
                error(
                    "This APK belongs to ${archive.packageName}, but ${existingProject.name} contains " +
                        "${existingProject.packageName}. Choose the matching project or create a new one."
                )
            }

            val chunking = chunkStore.ingest(tempFile)
            if (current.any { it.projectId == projectId && it.sha256 == chunking.apkSha256 }) {
                error("That exact APK is already stored in this project.")
            }

            val authoritativeSize = chunking.chunks.sumOf { it.size.toLong() }
            check(authoritativeSize == tempFile.length()) {
                "Import size verification failed before the APK was stored."
            }

            val id = UUID.randomUUID().toString()
            createdRecordId = id
            var record = ApkRecord(
                id = id,
                projectId = projectId,
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
            var updatedRecords = sortRecords(current + record)
            val updatedProjects = if (isBase) {
                val name = pendingProjectName?.trim().takeUnless { it.isNullOrBlank() } ?: archive.label
                (_projects.value + ApkProject(
                    id = projectId,
                    name = name,
                    packageName = archive.packageName,
                    createdAtEpochMs = System.currentTimeMillis(),
                )).sortedBy { it.name.lowercase() }
            } else _projects.value

            saveIndex(updatedRecords)
            saveProjects(updatedProjects)
            _projects.value = updatedProjects
            _records.value = updatedRecords

            // Icon caching is deliberately non-fatal: exact APK storage remains authoritative.
            runCatching {
                val iconBytes = ApkInspector.renderApplicationIconPng(appContext, tempFile)
                if (iconBytes != null) {
                    writeIconBytes(id, iconBytes)
                    record = record.copy(iconUpdatedAtEpochMs = System.currentTimeMillis())
                    updatedRecords = sortRecords(updatedRecords.map { if (it.id == id) record else it })
                    saveIndex(updatedRecords)
                    _records.value = updatedRecords
                }
            }

            scheduleStatsRefresh()
            return ImportResult(
                record = record,
                reusedBytes = max(0L, record.sizeBytes - record.newBytesAdded),
            )
        } catch (t: Throwable) {
            createdRecordId?.takeIf { id -> _records.value.none { it.id == id } }?.let { iconFile(it).delete() }
            garbageCollectInternal(_records.value)
            scheduleStatsRefresh()
            throw t
        } finally {
            tempFile.delete()
        }
    }

    suspend fun renameProject(projectId: String, newName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = newName.trim()
            require(clean.isNotEmpty()) { "Project name cannot be empty." }
            require(_projects.value.any { it.id == projectId }) { "Project not found." }
            val updated = _projects.value.map { if (it.id == projectId) it.copy(name = clean) else it }
                .sortedBy { it.name.lowercase() }
            saveProjects(updated)
            _projects.value = updated
        }
    }

    suspend fun setStarred(recordId: String, starred: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(_records.value.any { it.id == recordId }) { "Stored APK not found." }
            val updated = sortRecords(_records.value.map {
                if (it.id == recordId) it.copy(starred = starred) else it
            })
            saveIndex(updated)
            _records.value = updated
        }
    }

    suspend fun updateRecordDetails(recordId: String, description: String, notes: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(_records.value.any { it.id == recordId }) { "Stored APK not found." }
            val updated = sortRecords(_records.value.map {
                if (it.id == recordId) it.copy(
                    description = description.trim(),
                    notes = notes.trim(),
                ) else it
            })
            saveIndex(updated)
            _records.value = updated
        }
    }

    suspend fun regenerateIcon(recordId: String): IconRegenerationOutcome = withContext(Dispatchers.IO) {
        val record = _records.value.firstOrNull { it.id == recordId }
            ?: return@withContext IconRegenerationOutcome.FAILED
        val tempFile = File(appContext.cacheDir, "apkbox-icon-${UUID.randomUUID()}.apk")
        try {
            FileOutputStream(tempFile).use { output -> streamApk(record, output) }
            val iconBytes = ApkInspector.renderApplicationIconPng(appContext, tempFile)
                ?: return@withContext IconRegenerationOutcome.FAILED
            val target = iconFile(recordId)
            val unchanged = target.isFile && runCatching { target.readBytes().contentEquals(iconBytes) }.getOrDefault(false)
            if (!unchanged) writeIconBytes(recordId, iconBytes)

            mutex.withLock {
                val updated = sortRecords(_records.value.map {
                    if (it.id == recordId) it.copy(iconUpdatedAtEpochMs = System.currentTimeMillis()) else it
                })
                saveIndex(updated)
                _records.value = updated
            }
            scheduleStatsRefresh()
            if (unchanged) IconRegenerationOutcome.UNCHANGED else IconRegenerationOutcome.UPDATED
        } catch (_: Throwable) {
            IconRegenerationOutcome.FAILED
        } finally {
            tempFile.delete()
        }
    }

    suspend fun regenerateAllIcons(): IconRegenerationSummary = withContext(Dispatchers.IO) {
        // Deliberately sequential: at most one full reconstructed APK exists at a time. Icon decode
        // and UI image loading are parallelized elsewhere without multiplying temporary disk usage.
        val ids = _records.value.map { it.id }
        var updated = 0
        var unchanged = 0
        var failed = 0
        for (id in ids) {
            when (regenerateIcon(id)) {
                IconRegenerationOutcome.UPDATED -> updated++
                IconRegenerationOutcome.UNCHANGED -> unchanged++
                IconRegenerationOutcome.FAILED -> failed++
            }
        }
        IconRegenerationSummary(updated, unchanged, failed)
    }

    suspend fun deleteRevision(recordId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = _records.value.firstOrNull { it.id == recordId } ?: return@withLock
            require(!record.isBase) { "Delete the project to remove its base APK." }

            val updated = _records.value.filterNot { it.id == recordId }
            manifestFile(recordId).delete()
            iconFile(recordId).delete()
            saveIndex(updated)
            _records.value = updated
            garbageCollectInternal(updated)
        }
        scheduleStatsRefresh()
    }

    suspend fun deleteProject(projectId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(_projects.value.any { it.id == projectId }) { "Project not found." }
            val removedRecords = _records.value.filter { it.projectId == projectId }
            val remainingRecords = _records.value.filterNot { it.projectId == projectId }
            val remainingProjects = _projects.value.filterNot { it.id == projectId }
            removedRecords.forEach {
                manifestFile(it.id).delete()
                iconFile(it.id).delete()
            }
            saveIndex(remainingRecords)
            saveProjects(remainingProjects)
            _records.value = remainingRecords
            _projects.value = remainingProjects
            garbageCollectInternal(remainingRecords)
        }
        scheduleStatsRefresh()
    }

    suspend fun clearVault() = withContext(Dispatchers.IO) {
        mutex.withLock {
            statsGeneration.incrementAndGet()
            rootDir.deleteRecursively()
            rootDir.mkdirs()
            manifestsDir.mkdirs()
            iconsDir.mkdirs()
            _projects.value = emptyList()
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
                _records.value = sortRecords(repaired)
                saveIndex(_records.value)
                scheduleStatsRefresh()
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

    private fun repairProjects(projects: List<ApkProject>, records: List<ApkRecord>): List<ApkProject> {
        if (records.isEmpty()) return projects
        val byId = projects.associateBy { it.id }.toMutableMap()
        records.groupBy { it.projectId }.forEach { (projectId, projectRecords) ->
            if (projectId !in byId) {
                val base = projectRecords.firstOrNull { it.isBase } ?: projectRecords.first()
                byId[projectId] = ApkProject(
                    id = projectId,
                    name = base.label,
                    packageName = base.packageName,
                    createdAtEpochMs = base.addedAtEpochMs,
                )
            }
        }
        val liveIds = records.mapTo(hashSetOf()) { it.projectId }
        return byId.values.filter { it.id in liveIds }.sortedBy { it.name.lowercase() }
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
        val root = JSONObject().put("schema", 3).put("records", array)
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

    private fun saveProjects(projects: List<ApkProject>) {
        val array = JSONArray()
        projects.forEach { array.put(it.toJson()) }
        val root = JSONObject().put("schema", 1).put("projects", array)
        val temp = File(rootDir, ".projects.json.tmp")
        FileOutputStream(temp).use { output ->
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        atomicReplace(temp, projectsFile)
    }

    private fun loadProjects(): List<ApkProject> {
        if (!projectsFile.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(projectsFile.readText())
            val array = root.getJSONArray("projects")
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toProject())
            }
        }.getOrDefault(emptyList())
    }

    private fun scheduleStatsRefresh() {
        val generation = statsGeneration.incrementAndGet()
        maintenanceScope.launch {
            statsRefreshMutex.withLock {
                val snapshot = _records.value
                val computed = calculateStats(snapshot)
                if (generation == statsGeneration.get()) {
                    _stats.value = computed
                    runCatching { saveCachedStats(computed) }
                }
            }
        }
    }

    private fun loadCachedStats(records: List<ApkRecord>): VaultStats {
        val logical = records.sumOf { it.sizeBytes }
        val revisions = records.count { !it.isBase }
        val cachedPhysical = runCatching {
            if (!statsFile.isFile) 0L else JSONObject(statsFile.readText()).optLong("physicalBytes", 0L)
        }.getOrDefault(0L)
        val saved = max(0L, logical - cachedPhysical)
        return VaultStats(
            logicalBytes = logical,
            physicalBytes = cachedPhysical,
            savedBytes = saved,
            savedPercent = if (logical == 0L) 0.0 else saved.toDouble() / logical.toDouble() * 100.0,
            revisionCount = revisions,
        )
    }

    private fun saveCachedStats(stats: VaultStats) {
        val temp = File(rootDir, ".stats.json.tmp")
        val json = JSONObject()
            .put("physicalBytes", stats.physicalBytes)
            .put("logicalBytes", stats.logicalBytes)
            .put("savedBytes", stats.savedBytes)
            .put("savedPercent", stats.savedPercent)
            .put("revisionCount", stats.revisionCount)
            .put("updatedAtEpochMs", System.currentTimeMillis())
        FileOutputStream(temp).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        atomicReplace(temp, statsFile)
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
    private fun iconFile(recordId: String) = File(iconsDir, "$recordId.png")

    private fun writeIconBytes(recordId: String, bytes: ByteArray) {
        iconsDir.mkdirs()
        val target = iconFile(recordId)
        val temp = File(iconsDir, ".$recordId.png.tmp")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        atomicReplace(temp, target)
    }

    private fun sortRecords(records: List<ApkRecord>): List<ApkRecord> = records.sortedWith(
        compareBy<ApkRecord> { it.projectId }
            .thenByDescending { it.isBase }
            .thenByDescending { it.starred }
            .thenByDescending { it.addedAtEpochMs }
    )

    private fun atomicReplace(temp: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun ApkProject.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("packageName", packageName)
        .put("createdAtEpochMs", createdAtEpochMs)

    private fun JSONObject.toProject(): ApkProject = ApkProject(
        id = getString("id"),
        name = getString("name"),
        packageName = getString("packageName"),
        createdAtEpochMs = getLong("createdAtEpochMs"),
    )

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
        .put("starred", starred)
        .put("description", description)
        .put("notes", notes)
        .put("iconUpdatedAtEpochMs", iconUpdatedAtEpochMs)

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
        starred = optBoolean("starred", false),
        description = optString("description", ""),
        notes = optString("notes", ""),
        iconUpdatedAtEpochMs = optLong("iconUpdatedAtEpochMs", 0L),
    )
}
