package com.mekromn.apkbox.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class ActionReservationStatus {
    ACCEPTED,
    NOT_SCOPED,
    STALE_SEQUENCE,
    ALREADY_RESERVED,
    INVALID_SCOPE,
}

data class ActionReservation(
    val status: ActionReservationStatus,
    val detail: String,
) {
    val mayExecute: Boolean get() = status == ActionReservationStatus.ACCEPTED || status == ActionReservationStatus.NOT_SCOPED
}

/**
 * Persistent run/sequence gate for interactive Screen Agent actions. The reservation is written
 * before input injection. If APKbox dies after the reservation but before it can journal the result,
 * the same request is refused after restart rather than risking a second tap/swipe/type action.
 */
class AgentActionLedger(context: Context) {
    companion object {
        private const val MAX_RESERVATIONS = 2_000
        private val runRegex = Regex("[A-Za-z0-9._-]{1,96}")
    }

    private val root = File(context.applicationContext.filesDir, "apkbox-agent/action-ledger").apply { mkdirs() }
    private val ledgerFile = File(root, "ledger.json")

    @Synchronized
    fun reserve(
        requestId: String,
        runId: String,
        sequenceNumber: Long,
    ): ActionReservation {
        if (runId.isBlank() && sequenceNumber == 0L) {
            return ActionReservation(ActionReservationStatus.NOT_SCOPED, "One-off manually approved action is not run-scoped.")
        }
        if (!runRegex.matches(runId) || sequenceNumber <= 0L ||
            !requestId.matches(Regex("[A-Za-z0-9._-]{1,96}"))
        ) {
            return ActionReservation(ActionReservationStatus.INVALID_SCOPE, "Invalid run ID, request ID, or sequence number.")
        }

        val rootJson = loadRoot()
        val runs = rootJson.optJSONObject("runs") ?: JSONObject().also { rootJson.put("runs", it) }
        val reservations = rootJson.optJSONObject("reservations") ?: JSONObject().also { rootJson.put("reservations", it) }

        if (reservations.has(requestId)) {
            val prior = reservations.optJSONObject(requestId)
            return ActionReservation(
                ActionReservationStatus.ALREADY_RESERVED,
                "Request $requestId was already reserved at sequence ${prior?.optLong("sequenceNumber") ?: sequenceNumber}; refusing replay.",
            )
        }

        val run = runs.optJSONObject(runId) ?: JSONObject()
        val lastSequence = run.optLong("lastSequence", 0L)
        if (sequenceNumber <= lastSequence) {
            return ActionReservation(
                ActionReservationStatus.STALE_SEQUENCE,
                "Stale Screen Agent sequence $sequenceNumber for $runId; latest reserved sequence is $lastSequence.",
            )
        }

        val now = System.currentTimeMillis()
        runs.put(
            runId,
            JSONObject()
                .put("lastSequence", sequenceNumber)
                .put("lastRequestId", requestId)
                .put("updatedAtEpochMs", now),
        )
        reservations.put(
            requestId,
            JSONObject()
                .put("runId", runId)
                .put("sequenceNumber", sequenceNumber)
                .put("reservedAtEpochMs", now),
        )
        trimReservations(reservations)
        atomicWrite(rootJson.toString())
        return ActionReservation(
            ActionReservationStatus.ACCEPTED,
            "Reserved $runId sequence $sequenceNumber for at-most-once execution.",
        )
    }

    @Synchronized
    fun lastSequence(runId: String): Long =
        loadRoot().optJSONObject("runs")?.optJSONObject(runId)?.optLong("lastSequence", 0L) ?: 0L

    @Synchronized
    fun clearRun(runId: String) {
        val rootJson = loadRoot()
        rootJson.optJSONObject("runs")?.remove(runId)
        val reservations = rootJson.optJSONObject("reservations")
        if (reservations != null) {
            val toRemove = reservations.keys().asSequence().filter { key ->
                reservations.optJSONObject(key)?.optString("runId") == runId
            }.toList()
            toRemove.forEach(reservations::remove)
        }
        atomicWrite(rootJson.toString())
    }

    private fun loadRoot(): JSONObject = runCatching {
        if (!ledgerFile.isFile) JSONObject() else JSONObject(ledgerFile.readText(Charsets.UTF_8))
    }.getOrElse { JSONObject() }

    private fun trimReservations(reservations: JSONObject) {
        val items = reservations.keys().asSequence().mapNotNull { key ->
            reservations.optJSONObject(key)?.let { key to it.optLong("reservedAtEpochMs") }
        }.sortedByDescending { it.second }.toList()
        items.drop(MAX_RESERVATIONS).forEach { reservations.remove(it.first) }
    }

    private fun atomicWrite(text: String) {
        val temp = File(root, ".ledger.json.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (ledgerFile.exists() && !ledgerFile.delete()) {
            temp.delete()
            error("Could not replace Screen Agent action ledger.")
        }
        if (!temp.renameTo(ledgerFile)) {
            temp.copyTo(ledgerFile, overwrite = true)
            temp.delete()
        }
    }
}
