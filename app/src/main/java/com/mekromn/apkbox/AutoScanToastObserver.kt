package com.mekromn.apkbox

import android.content.Context
import android.widget.Toast
import com.mekromn.apkbox.data.AutoScanManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide user feedback for Auto Scanner activity.
 *
 * AutoScanManager already persists every meaningful scan outcome in recentEvents. Observing that
 * single event stream keeps toast behavior identical for DownloadManager, FileObserver, startup /
 * resume catch-up and manual scans without duplicating notification code in every trigger.
 *
 * Existing persisted events are marked as seen on startup so APKbox never replays old toasts. New
 * events arriving close together are debounced into one summary toast to avoid flooding the user
 * when a batch of APKs lands in Downloads.
 */
object AutoScanToastObserver {
    private const val BATCH_DEBOUNCE_MS = 850L
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        val manager = ApkBoxServices.autoScanner(appContext)
        val seen = manager.recentEvents.value.mapTo(linkedSetOf(), ::eventKey)
        val pending = ArrayList<AutoScanManager.Event>()
        var toastJob: Job? = null

        scope.launch {
            manager.recentEvents.collect { events ->
                val fresh = events
                    .asReversed()
                    .filter { eventKey(it) !in seen }

                if (fresh.isEmpty()) return@collect

                fresh.forEach { event ->
                    seen += eventKey(event)
                    pending += event
                }

                // recentEvents is capped by AutoScanManager, so keep the seen set bounded too.
                val currentKeys = events.mapTo(hashSetOf(), ::eventKey)
                seen.retainAll(currentKeys)

                toastJob?.cancel()
                toastJob = launch {
                    delay(BATCH_DEBOUNCE_MS)
                    if (pending.isEmpty()) return@launch
                    val batch = pending.toList()
                    pending.clear()
                    Toast.makeText(appContext, toastText(batch), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toastText(events: List<AutoScanManager.Event>): String {
        if (events.size == 1) {
            val event = events.single()
            val name = shortName(event.fileName)
            return when (event.status) {
                AutoScanManager.EventStatus.IMPORTED_AND_DELETED ->
                    "Auto Scanner · $name archived · original deleted"
                AutoScanManager.EventStatus.IMPORTED_KEPT ->
                    "Auto Scanner · $name archived · original kept"
                AutoScanManager.EventStatus.ALREADY_STORED_AND_DELETED ->
                    "Auto Scanner · $name already stored · duplicate deleted"
                AutoScanManager.EventStatus.ALREADY_STORED_KEPT ->
                    "Auto Scanner · $name already stored · original kept"
                AutoScanManager.EventStatus.DELETE_FAILED ->
                    "Auto Scanner · $name archived, but original could not be deleted"
                AutoScanManager.EventStatus.WRONG_PROJECT ->
                    "Auto Scanner · $name not imported · wrong Project"
                AutoScanManager.EventStatus.AMBIGUOUS_RULE ->
                    "Auto Scanner · $name not imported · multiple rules matched"
                AutoScanManager.EventStatus.FAILED ->
                    "Auto Scanner · $name failed · original kept"
            }
        }

        val archived = events.count {
            it.status == AutoScanManager.EventStatus.IMPORTED_AND_DELETED ||
                it.status == AutoScanManager.EventStatus.IMPORTED_KEPT
        }
        val deleted = events.count {
            it.status == AutoScanManager.EventStatus.IMPORTED_AND_DELETED ||
                it.status == AutoScanManager.EventStatus.ALREADY_STORED_AND_DELETED
        }
        val alreadyStored = events.count {
            it.status == AutoScanManager.EventStatus.ALREADY_STORED_AND_DELETED ||
                it.status == AutoScanManager.EventStatus.ALREADY_STORED_KEPT
        }
        val attention = events.count {
            it.status == AutoScanManager.EventStatus.DELETE_FAILED ||
                it.status == AutoScanManager.EventStatus.WRONG_PROJECT ||
                it.status == AutoScanManager.EventStatus.AMBIGUOUS_RULE ||
                it.status == AutoScanManager.EventStatus.FAILED
        }

        return buildString {
            append("Auto Scanner · ${events.size} matched")
            if (archived > 0) append(" · $archived archived")
            if (alreadyStored > 0) append(" · $alreadyStored already stored")
            if (deleted > 0) append(" · $deleted originals deleted")
            if (attention > 0) append(" · $attention need attention")
        }
    }

    private fun eventKey(event: AutoScanManager.Event): String =
        "${event.atEpochMs}|${event.fileName}|${event.projectId.orEmpty()}|${event.status.name}"

    private fun shortName(name: String): String =
        if (name.length <= 42) name else name.take(39) + "…"
}
