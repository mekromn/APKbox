package com.mekromn.apkbox.data

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID

/**
 * Rule-based importer for APKs arriving in public Downloads.
 *
 * Safety invariant: a source file is never deleted until APKbox has an exact stored record and a
 * full reconstruction/SHA-256 verification of that stored record succeeds.
 */
class AutoScanManager(
    context: Context,
    private val libraryStore: LibraryStore,
) {
    companion object {
        private const val RULES_SCHEMA = 1
        private const val STABLE_FILE_AGE_MS = 2_000L
        private const val MAX_RECENT_EVENTS = 60
    }

    data class Rule(
        val id: String,
        val projectId: String,
        val keywords: List<String>,
        val enabled: Boolean = true,
        val deleteOriginal: Boolean = true,
    ) {
        fun normalizedKeywords(): List<String> = keywords
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()

        fun matches(fileName: String): Boolean {
            val normalizedName = fileName.lowercase()
            val words = normalizedKeywords()
            return words.isNotEmpty() && words.all { it in normalizedName }
        }
    }

    enum class EventStatus {
        IMPORTED_AND_DELETED,
        IMPORTED_KEPT,
        ALREADY_STORED_AND_DELETED,
        ALREADY_STORED_KEPT,
        WRONG_PROJECT,
        AMBIGUOUS_RULE,
        DELETE_FAILED,
        FAILED,
    }

    data class Event(
        val fileName: String,
        val projectId: String?,
        val status: EventStatus,
        val detail: String,
        val atEpochMs: Long = System.currentTimeMillis(),
    )

    data class ScanSummary(
        val examinedMatches: Int,
        val imported: Int,
        val deleted: Int,
        val failed: Int,
    )

    private val appContext = context.applicationContext
    private val vaultRoot = File(appContext.filesDir, "apkbox-vault")
    private val rulesFile = File(vaultRoot, "auto-scan.json")
    private val downloadsRoot = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
    )
    private val apkboxDownloads = File(downloadsRoot, "APKbox")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val scanMutex = Mutex()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> get() = _enabled.asStateFlow()

    private val _rules = MutableStateFlow<List<Rule>>(emptyList())
    val rules: StateFlow<List<Rule>> get() = _rules.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<Event>>(emptyList())
    val recentEvents: StateFlow<List<Event>> get() = _recentEvents.asStateFlow()

    @Volatile
    private var observer: FileObserver? = null

    init {
        reloadFromDisk()
        updateObserver()
    }

    fun setEnabled(value: Boolean) {
        scope.launch {
            stateMutex.withLock {
                _enabled.value = value
                saveStateLocked()
                updateObserver()
            }
            if (value) scanNow("enabled")
        }
    }

    fun addRule(
        projectId: String,
        keywords: List<String>,
        deleteOriginal: Boolean = true,
    ) {
        val normalized = keywords.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(normalized.isNotEmpty()) { "Enter at least one filename keyword." }
        scope.launch {
            stateMutex.withLock {
                val rule = Rule(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    keywords = normalized,
                    deleteOriginal = deleteOriginal,
                )
                _rules.value = _rules.value + rule
                saveStateLocked()
                updateObserver()
            }
            if (_enabled.value) scanNow("rule added")
        }
    }

    fun updateRuleEnabled(ruleId: String, enabled: Boolean) {
        scope.launch {
            stateMutex.withLock {
                _rules.value = _rules.value.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
                saveStateLocked()
                updateObserver()
            }
        }
    }

    fun updateRuleDeleteOriginal(ruleId: String, deleteOriginal: Boolean) {
        scope.launch {
            stateMutex.withLock {
                _rules.value = _rules.value.map {
                    if (it.id == ruleId) it.copy(deleteOriginal = deleteOriginal) else it
                }
                saveStateLocked()
            }
        }
    }

    fun deleteRule(ruleId: String) {
        scope.launch {
            stateMutex.withLock {
                _rules.value = _rules.value.filterNot { it.id == ruleId }
                saveStateLocked()
                updateObserver()
            }
        }
    }

    fun clearRecentEvents() {
        scope.launch {
            stateMutex.withLock {
                _recentEvents.value = emptyList()
                saveStateLocked()
            }
        }
    }

    fun reloadFromDisk() {
        val state = runCatching { readState() }.getOrNull()
        _enabled.value = state?.first ?: false
        _rules.value = state?.second ?: emptyList()
        _recentEvents.value = state?.third ?: emptyList()
        updateObserver()
    }

    fun scanAsync(reason: String = "background") {
        if (!_enabled.value) return
        scope.launch { runCatching { scanNow(reason) } }
    }

    suspend fun scanNow(reason: String = "manual"): ScanSummary = scanMutex.withLock {
        if (!_enabled.value) return ScanSummary(0, 0, 0, 0)
        val activeRules = _rules.value.filter { it.enabled && it.normalizedKeywords().isNotEmpty() }
        if (activeRules.isEmpty() || !downloadsRoot.isDirectory) return ScanSummary(0, 0, 0, 0)

        val now = System.currentTimeMillis()
        val candidates = downloadsRoot.walkTopDown()
            .onEnter { directory -> directory.canonicalPath != apkboxDownloads.canonicalPath }
            .filter { file ->
                file.isFile &&
                    file.extension.equals("apk", ignoreCase = true) &&
                    file.length() > 0L &&
                    now - file.lastModified() >= STABLE_FILE_AGE_MS &&
                    !GatewaySourceClaims.isClaimed(file)
            }
            .toList()
            .sortedBy { it.lastModified() }

        var examined = 0
        var imported = 0
        var deleted = 0
        var failed = 0

        for (file in candidates) {
            // Re-check at the last possible cheap point in case the gateway claimed the APK after
            // the directory inventory was built. The next catch-up scan will handle it later.
            if (GatewaySourceClaims.isClaimed(file)) continue

            val matchingRules = activeRules.filter { it.matches(file.name) }
            if (matchingRules.isEmpty()) continue
            examined++

            val bestRules = mostSpecificRules(matchingRules)
            if (bestRules.size != 1) {
                recordEvent(
                    Event(
                        fileName = file.name,
                        projectId = null,
                        status = EventStatus.AMBIGUOUS_RULE,
                        detail = "Matched multiple equally specific Auto Scanner rules. Original kept.",
                    )
                )
                failed++
                continue
            }

            val rule = bestRules.single()
            val project = libraryStore.projects.value.firstOrNull { it.id == rule.projectId }
            if (project == null) {
                recordEvent(
                    Event(
                        fileName = file.name,
                        projectId = rule.projectId,
                        status = EventStatus.FAILED,
                        detail = "Target project no longer exists. Original kept.",
                    )
                )
                failed++
                continue
            }

            val result = processFile(file, project, rule, reason)
            when (result.status) {
                EventStatus.IMPORTED_AND_DELETED -> { imported++; deleted++ }
                EventStatus.IMPORTED_KEPT -> imported++
                EventStatus.ALREADY_STORED_AND_DELETED -> deleted++
                EventStatus.ALREADY_STORED_KEPT -> Unit
                EventStatus.DELETE_FAILED,
                EventStatus.WRONG_PROJECT,
                EventStatus.AMBIGUOUS_RULE,
                EventStatus.FAILED -> failed++
            }
            recordEvent(result)
        }

        ScanSummary(examined, imported, deleted, failed)
    }

    private suspend fun processFile(
        file: File,
        project: ApkProject,
        rule: Rule,
        reason: String,
    ): Event {
        if (!file.isFile) {
            return Event(file.name, project.id, EventStatus.FAILED, "File disappeared before import.")
        }
        if (GatewaySourceClaims.isClaimed(file)) {
            return Event(
                file.name,
                project.id,
                EventStatus.ALREADY_STORED_KEPT,
                "Installer gateway is using this APK; Auto Scanner deferred it without reading or deleting it.",
            )
        }

        val projectRecords = libraryStore.records.value.filter { it.projectId == project.id }
        val alreadyStored = StoredApkMatcher.findMatches(listOf(file), projectRecords)[file.absolutePath]
        if (alreadyStored != null) {
            return runCatching {
                verifyStoredRecord(alreadyStored)
                if (rule.deleteOriginal) {
                    if (deleteSource(file)) {
                        Event(
                            file.name,
                            project.id,
                            EventStatus.ALREADY_STORED_AND_DELETED,
                            "Exact APK was already stored in ${project.name}; verified vault copy and deleted original.",
                        )
                    } else {
                        Event(
                            file.name,
                            project.id,
                            EventStatus.DELETE_FAILED,
                            "Exact APK is stored and verified, but Android could not delete the original download.",
                        )
                    }
                } else {
                    Event(
                        file.name,
                        project.id,
                        EventStatus.ALREADY_STORED_KEPT,
                        "Exact APK was already stored in ${project.name}; original kept by rule.",
                    )
                }
            }.getOrElse { failure ->
                Event(
                    file.name,
                    project.id,
                    EventStatus.FAILED,
                    failure.message ?: "Stored copy verification failed. Original kept.",
                )
            }
        }

        return runCatching {
            val result = libraryStore.importRevision(project.id, Uri.fromFile(file))
            verifyStoredRecord(result.record)
            if (rule.deleteOriginal) {
                if (deleteSource(file)) {
                    Event(
                        file.name,
                        project.id,
                        EventStatus.IMPORTED_AND_DELETED,
                        "Auto-imported into ${project.name} ($reason), verified exact reconstruction, then deleted original.",
                    )
                } else {
                    Event(
                        file.name,
                        project.id,
                        EventStatus.DELETE_FAILED,
                        "Imported and verified in ${project.name}, but Android could not delete the original download.",
                    )
                }
            } else {
                Event(
                    file.name,
                    project.id,
                    EventStatus.IMPORTED_KEPT,
                    "Auto-imported and verified in ${project.name}; original kept by rule.",
                )
            }
        }.getOrElse { failure ->
            val detail = failure.message?.takeIf { it.isNotBlank() }
                ?: "Auto-import failed: ${failure::class.java.simpleName}"
            val status = if (
                detail.contains("belongs to", ignoreCase = true) &&
                detail.contains("matching project", ignoreCase = true)
            ) EventStatus.WRONG_PROJECT else EventStatus.FAILED
            Event(file.name, project.id, status, "$detail Original kept.")
        }
    }

    private suspend fun verifyStoredRecord(record: ApkRecord) {
        libraryStore.streamApk(record, NullOutputStream)
    }

    private fun deleteSource(file: File): Boolean {
        val path = file.absolutePath
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        if (deleted) {
            MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
        }
        return deleted
    }

    private fun mostSpecificRules(matches: List<Rule>): List<Rule> {
        if (matches.size <= 1) return matches
        val score = matches.maxOf { rule ->
            val words = rule.normalizedKeywords()
            words.size * 10_000 + words.sumOf { it.length }
        }
        return matches.filter { rule ->
            val words = rule.normalizedKeywords()
            words.size * 10_000 + words.sumOf { it.length } == score
        }
    }

    private suspend fun recordEvent(event: Event) {
        stateMutex.withLock {
            _recentEvents.value = (listOf(event) + _recentEvents.value).take(MAX_RECENT_EVENTS)
            saveStateLocked()
        }
    }

    private fun updateObserver() {
        val shouldRun = _enabled.value && _rules.value.any { it.enabled } && downloadsRoot.isDirectory
        if (!shouldRun) {
            observer?.stopWatching()
            observer = null
            return
        }
        if (observer != null) return

        @Suppress("DEPRECATION")
        observer = object : FileObserver(
            downloadsRoot.absolutePath,
            FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE,
        ) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: return
                if (!name.endsWith(".apk", ignoreCase = true)) return
                if (File(downloadsRoot, name).canonicalPath.startsWith(apkboxDownloads.canonicalPath)) return
                scope.launch {
                    delay(STABLE_FILE_AGE_MS)
                    runCatching { scanNow("Downloads watcher") }
                }
            }
        }.also { it.startWatching() }
    }

    fun shutdown() {
        observer?.stopWatching()
        observer = null
    }

    private fun saveStateLocked() {
        vaultRoot.mkdirs()
        val rules = JSONArray()
        _rules.value.forEach { rule ->
            rules.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("projectId", rule.projectId)
                    .put("keywords", JSONArray(rule.keywords))
                    .put("enabled", rule.enabled)
                    .put("deleteOriginal", rule.deleteOriginal)
            )
        }
        val events = JSONArray()
        _recentEvents.value.forEach { event ->
            events.put(
                JSONObject()
                    .put("fileName", event.fileName)
                    .put("projectId", event.projectId ?: JSONObject.NULL)
                    .put("status", event.status.name)
                    .put("detail", event.detail)
                    .put("atEpochMs", event.atEpochMs)
            )
        }
        val root = JSONObject()
            .put("schema", RULES_SCHEMA)
            .put("enabled", _enabled.value)
            .put("rules", rules)
            .put("recentEvents", events)
        val temp = File(vaultRoot, ".auto-scan.json.tmp")
        temp.writeText(root.toString())
        if (rulesFile.exists()) rulesFile.delete()
        if (!temp.renameTo(rulesFile)) {
            temp.copyTo(rulesFile, overwrite = true)
            temp.delete()
        }
    }

    private fun readState(): Triple<Boolean, List<Rule>, List<Event>> {
        if (!rulesFile.isFile) return Triple(false, emptyList(), emptyList())
        val root = JSONObject(rulesFile.readText())
        val enabled = root.optBoolean("enabled", false)
        val rulesArray = root.optJSONArray("rules") ?: JSONArray()
        val rules = buildList {
            for (index in 0 until rulesArray.length()) {
                val item = rulesArray.getJSONObject(index)
                val keywordsJson = item.optJSONArray("keywords") ?: JSONArray()
                val keywords = buildList {
                    for (keywordIndex in 0 until keywordsJson.length()) {
                        keywordsJson.optString(keywordIndex).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                if (keywords.isNotEmpty()) {
                    add(
                        Rule(
                            id = item.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                            projectId = item.optString("projectId"),
                            keywords = keywords,
                            enabled = item.optBoolean("enabled", true),
                            deleteOriginal = item.optBoolean("deleteOriginal", true),
                        )
                    )
                }
            }
        }
        val eventsArray = root.optJSONArray("recentEvents") ?: JSONArray()
        val events = buildList {
            for (index in 0 until eventsArray.length()) {
                val item = eventsArray.getJSONObject(index)
                val status = runCatching { EventStatus.valueOf(item.optString("status")) }.getOrNull()
                if (status != null) {
                    add(
                        Event(
                            fileName = item.optString("fileName", "APK"),
                            projectId = if (item.isNull("projectId")) null else item.optString("projectId"),
                            status = status,
                            detail = item.optString("detail"),
                            atEpochMs = item.optLong("atEpochMs", 0L),
                        )
                    )
                }
            }
        }.take(MAX_RECENT_EVENTS)
        return Triple(enabled, rules, events)
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}
