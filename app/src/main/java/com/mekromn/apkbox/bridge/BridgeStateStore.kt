package com.mekromn.apkbox.bridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BridgeEvent(
    val atEpochMs: Long,
    val title: String,
    val detail: String,
    val success: Boolean,
)

data class BridgePopupMessage(
    val title: String,
    val message: String,
    val requestId: String,
)

data class BridgeInFlightEnvelope(
    val request: BridgeRequest,
    val risk: BridgeRisk,
    val inboxPath: String,
    val inboxSha: String,
    val startedAtEpochMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("request", request.toJson())
        .put("risk", risk.name)
        .put("inboxPath", inboxPath)
        .put("inboxSha", inboxSha)
        .put("startedAtEpochMs", startedAtEpochMs)

    fun completed(result: BridgeResult): BridgeCompletedEnvelope = BridgeCompletedEnvelope(
        request = request,
        inboxPath = inboxPath,
        inboxSha = inboxSha,
        result = result,
    )

    companion object {
        fun fromJson(json: JSONObject): BridgeInFlightEnvelope = BridgeInFlightEnvelope(
            request = BridgeRequest.fromJson(json.getJSONObject("request")),
            risk = BridgeRisk.valueOf(json.getString("risk")),
            inboxPath = json.getString("inboxPath"),
            inboxSha = json.getString("inboxSha"),
            startedAtEpochMs = json.optLong("startedAtEpochMs"),
        )
    }
}

class BridgeStateStore(context: Context) {
    companion object {
        private const val MAX_EVENTS = 100
    }

    private val root = File(context.applicationContext.filesDir, "apkbox-bridge").apply { mkdirs() }
    private val pendingFile = File(root, "pending.json")
    private val popupFile = File(root, "popup.json")
    private val eventsFile = File(root, "events.json")
    private val inFlightDir = File(root, "inflight").apply { mkdirs() }
    private val completedDir = File(root, "completed").apply { mkdirs() }

    private val _events = MutableStateFlow(loadEvents())
    val events: StateFlow<List<BridgeEvent>> = _events.asStateFlow()

    @Synchronized
    fun savePending(pending: BridgePendingRequest) {
        atomicWrite(pendingFile, pending.toJson().toString())
    }

    @Synchronized
    fun loadPending(): BridgePendingRequest? = runCatching {
        if (!pendingFile.isFile) null else BridgePendingRequest.fromJson(JSONObject(pendingFile.readText()))
    }.getOrNull()

    @Synchronized
    fun clearPending() {
        pendingFile.delete()
    }

    /**
     * Persist before executing an approved/auto-approved request. This closes the gap between
     * clearing a pending approval and journaling the final result: the inbox poller can now prove
     * that the same request is already executing and will never launch a duplicate copy.
     */
    @Synchronized
    fun saveInFlight(pending: BridgePendingRequest, startedAtEpochMs: Long = System.currentTimeMillis()) {
        val envelope = BridgeInFlightEnvelope(
            request = pending.request,
            risk = pending.risk,
            inboxPath = pending.inboxPath,
            inboxSha = pending.inboxSha,
            startedAtEpochMs = startedAtEpochMs,
        )
        atomicWrite(inFlightFile(pending.request.id), envelope.toJson().toString())
    }

    @Synchronized
    fun loadInFlight(): List<BridgeInFlightEnvelope> = inFlightDir.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension == "json" }
        .sortedBy { it.lastModified() }
        .mapNotNull { file ->
            runCatching { BridgeInFlightEnvelope.fromJson(JSONObject(file.readText())) }.getOrNull()
        }
        .toList()

    @Synchronized
    fun hasInFlight(requestId: String): Boolean = inFlightFile(requestId).isFile

    @Synchronized
    fun clearInFlight(requestId: String) {
        inFlightFile(requestId).delete()
    }

    @Synchronized
    fun saveCompleted(completed: BridgeCompletedEnvelope) {
        atomicWrite(completedFile(completed.request.id), completed.toJson().toString())
    }

    @Synchronized
    fun loadCompleted(): List<BridgeCompletedEnvelope> = completedDir.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension == "json" }
        .sortedBy { it.lastModified() }
        .mapNotNull { file ->
            runCatching { BridgeCompletedEnvelope.fromJson(JSONObject(file.readText())) }.getOrNull()
        }
        .toList()

    @Synchronized
    fun hasCompleted(requestId: String): Boolean = completedFile(requestId).isFile

    @Synchronized
    fun clearCompleted(requestId: String) {
        completedFile(requestId).delete()
    }

    @Synchronized
    fun savePopup(popup: BridgePopupMessage) {
        atomicWrite(
            popupFile,
            JSONObject()
                .put("title", popup.title)
                .put("message", popup.message)
                .put("requestId", popup.requestId)
                .toString(),
        )
    }

    @Synchronized
    fun loadPopup(): BridgePopupMessage? = runCatching {
        if (!popupFile.isFile) null else JSONObject(popupFile.readText()).let {
            BridgePopupMessage(
                title = it.optString("title", "APKbox Bridge"),
                message = it.optString("message"),
                requestId = it.optString("requestId"),
            )
        }
    }.getOrNull()

    @Synchronized
    fun clearPopup() {
        popupFile.delete()
    }

    @Synchronized
    fun addEvent(title: String, detail: String, success: Boolean) {
        val updated = (
            listOf(BridgeEvent(System.currentTimeMillis(), title.take(256), detail.take(4096), success)) + _events.value
        ).take(MAX_EVENTS)
        _events.value = updated
        saveEvents(updated)
    }

    @Synchronized
    fun clearEvents() {
        _events.value = emptyList()
        eventsFile.delete()
    }

    private fun loadEvents(): List<BridgeEvent> = runCatching {
        if (!eventsFile.isFile) return@runCatching emptyList()
        val array = JSONArray(eventsFile.readText())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    BridgeEvent(
                        atEpochMs = item.optLong("atEpochMs"),
                        title = item.optString("title"),
                        detail = item.optString("detail"),
                        success = item.optBoolean("success"),
                    )
                )
            }
        }.take(MAX_EVENTS)
    }.getOrDefault(emptyList())

    private fun saveEvents(events: List<BridgeEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("atEpochMs", event.atEpochMs)
                    .put("title", event.title)
                    .put("detail", event.detail)
                    .put("success", event.success)
            )
        }
        atomicWrite(eventsFile, array.toString())
    }

    private fun safeName(requestId: String): String =
        requestId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"

    private fun inFlightFile(requestId: String) = File(inFlightDir, safeName(requestId))

    private fun completedFile(requestId: String) = File(completedDir, safeName(requestId))

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}
