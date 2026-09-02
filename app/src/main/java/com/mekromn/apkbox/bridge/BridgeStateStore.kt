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

class BridgeStateStore(context: Context) {
    companion object {
        private const val MAX_EVENTS = 100
    }

    private val root = File(context.applicationContext.filesDir, "apkbox-bridge").apply { mkdirs() }
    private val pendingFile = File(root, "pending.json")
    private val popupFile = File(root, "popup.json")
    private val eventsFile = File(root, "events.json")
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

    private fun completedFile(requestId: String) =
        File(completedDir, requestId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

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
