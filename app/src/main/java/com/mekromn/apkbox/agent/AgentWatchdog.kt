package com.mekromn.apkbox.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

data class AgentWatchdogState(
    val runId: String,
    val previousUiFingerprint: String = "",
    val identicalUiSamples: Int = 0,
    val recoveryAttempts: Int = 0,
    val lastProgressAtEpochMs: Long = System.currentTimeMillis(),
    val lastObservedAtEpochMs: Long = 0L,
    val lastSignal: OracleSignal = OracleSignal.HEALTHY,
    val lastEvidencePath: String = "",
    val lastEvidenceSha256: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("runId", runId)
        .put("previousUiFingerprint", previousUiFingerprint)
        .put("identicalUiSamples", identicalUiSamples)
        .put("recoveryAttempts", recoveryAttempts)
        .put("lastProgressAtEpochMs", lastProgressAtEpochMs)
        .put("lastObservedAtEpochMs", lastObservedAtEpochMs)
        .put("lastSignal", lastSignal.name)
        .put("lastEvidencePath", lastEvidencePath)
        .put("lastEvidenceSha256", lastEvidenceSha256)

    companion object {
        fun fromJson(json: JSONObject): AgentWatchdogState = AgentWatchdogState(
            runId = json.getString("runId"),
            previousUiFingerprint = json.optString("previousUiFingerprint"),
            identicalUiSamples = json.optInt("identicalUiSamples").coerceAtLeast(0),
            recoveryAttempts = json.optInt("recoveryAttempts").coerceAtLeast(0),
            lastProgressAtEpochMs = json.optLong("lastProgressAtEpochMs"),
            lastObservedAtEpochMs = json.optLong("lastObservedAtEpochMs"),
            lastSignal = runCatching { OracleSignal.valueOf(json.optString("lastSignal")) }
                .getOrDefault(OracleSignal.HEALTHY),
            lastEvidencePath = json.optString("lastEvidencePath"),
            lastEvidenceSha256 = json.optString("lastEvidenceSha256"),
        )
    }
}

data class WatchdogCheckConfig(
    val missionDeadlineEpochMs: Long,
    val stepDeadlineEpochMs: Long,
    val requiredUiChange: Boolean,
    val includeVisualStats: Boolean = true,
)

data class WatchdogOutcome(
    val decision: OracleDecision,
    val observation: CollectedAgentObservation,
    val checkpoint: AgentCheckpoint,
    val state: AgentWatchdogState,
    val evidence: EvidenceBundle? = null,
    val recoveryRecommended: Boolean = false,
)

/**
 * Persistent anti-stall coordinator. One check performs: observe -> update freeze history -> classify
 * -> capture evidence (if requested) -> persist checkpoint/watchdog state. It never executes a
 * recovery action itself, which guarantees the forensic snapshot is committed before a runner can
 * relaunch, rollback, reconnect, or otherwise change the failing state.
 */
class AgentWatchdog(
    private val observationCollector: AgentObservationCollector,
    private val evidenceCollector: AgentEvidenceCollector,
    private val checkpointStore: AgentCheckpointStore,
    private val watchdogStore: AgentWatchdogStateStore,
) {
    suspend fun check(
        checkpoint: AgentCheckpoint,
        config: WatchdogCheckConfig,
        userIntervened: Boolean = false,
    ): WatchdogOutcome {
        val prior = watchdogStore.load(checkpoint.runId) ?: AgentWatchdogState(runId = checkpoint.runId)

        val initial = observationCollector.collect(
            checkpoint = checkpoint,
            missionDeadlineEpochMs = config.missionDeadlineEpochMs,
            stepDeadlineEpochMs = config.stepDeadlineEpochMs,
            previousUiFingerprint = prior.previousUiFingerprint,
            identicalUiSamples = prior.identicalUiSamples,
            requiredUiChange = config.requiredUiChange,
            userIntervened = userIntervened,
            includeVisualStats = config.includeVisualStats,
        )

        val currentFingerprint = initial.observation.uiFingerprint
        val identical = when {
            currentFingerprint.isBlank() -> prior.identicalUiSamples
            prior.previousUiFingerprint.isBlank() -> 0
            currentFingerprint == prior.previousUiFingerprint -> prior.identicalUiSamples + 1
            else -> 0
        }
        val observation = initial.observation.copy(
            previousUiFingerprint = prior.previousUiFingerprint,
            identicalUiSamples = identical,
        )
        val collected = initial.copy(observation = observation)
        val decision = AgentOracle.evaluate(observation)

        val observedCheckpoint = checkpoint.copy(
            foregroundPackage = observation.foregroundPackage,
            uiFingerprint = currentFingerprint.ifBlank { checkpoint.uiFingerprint },
            updatedAtEpochMs = observation.nowEpochMs,
        )

        // Capture before changing the run state or consuming a recovery budget. This keeps the
        // bundle representative of the failure that triggered the watchdog.
        val evidence = if (decision.captureEvidence) {
            runCatching { evidenceCollector.capture(observedCheckpoint, decision) }.getOrNull()
        } else null

        val retryAvailable = decision.mayRetry && observedCheckpoint.retryBudgetRemaining > 0
        val nextCheckpoint = transitionCheckpoint(observedCheckpoint, decision, retryAvailable, evidence)
        checkpointStore.saveCheckpoint(nextCheckpoint)

        val madeProgress = currentFingerprint.isNotBlank() &&
            prior.previousUiFingerprint.isNotBlank() &&
            currentFingerprint != prior.previousUiFingerprint
        val nextState = prior.copy(
            previousUiFingerprint = currentFingerprint.ifBlank { prior.previousUiFingerprint },
            identicalUiSamples = identical,
            recoveryAttempts = prior.recoveryAttempts + if (retryAvailable && decision.terminalForStep) 1 else 0,
            lastProgressAtEpochMs = if (madeProgress) observation.nowEpochMs else prior.lastProgressAtEpochMs,
            lastObservedAtEpochMs = observation.nowEpochMs,
            lastSignal = decision.signal,
            lastEvidencePath = evidence?.file?.absolutePath ?: prior.lastEvidencePath,
            lastEvidenceSha256 = evidence?.sha256 ?: prior.lastEvidenceSha256,
        )
        watchdogStore.save(nextState)

        return WatchdogOutcome(
            decision = decision,
            observation = collected,
            checkpoint = nextCheckpoint,
            state = nextState,
            evidence = evidence,
            recoveryRecommended = retryAvailable && decision.terminalForStep,
        )
    }

    fun markProgress(runId: String, uiFingerprint: String = "") {
        val now = System.currentTimeMillis()
        val current = watchdogStore.load(runId) ?: AgentWatchdogState(runId = runId)
        watchdogStore.save(
            current.copy(
                previousUiFingerprint = uiFingerprint.ifBlank { current.previousUiFingerprint },
                identicalUiSamples = 0,
                lastProgressAtEpochMs = now,
                lastObservedAtEpochMs = now,
                lastSignal = OracleSignal.HEALTHY,
            )
        )
    }

    fun clear(runId: String) = watchdogStore.clear(runId)

    private fun transitionCheckpoint(
        checkpoint: AgentCheckpoint,
        decision: OracleDecision,
        retryAvailable: Boolean,
        evidence: EvidenceBundle?,
    ): AgentCheckpoint {
        if (!decision.terminalForStep) return checkpoint

        val state = when (decision.signal) {
            OracleSignal.HEALTHY -> checkpoint.state
            OracleSignal.CONTROLLER_LOST -> AgentRunState.PAUSED_CONTROLLER_LOST
            OracleSignal.WRONG_FOREGROUND_PACKAGE -> AgentRunState.PAUSED_UNEXPECTED_SCREEN
            OracleSignal.USER_INTERVENED,
            OracleSignal.ADB_DISCONNECTED -> AgentRunState.PAUSED_SAFETY_BOUNDARY
            OracleSignal.DEADLINE_EXCEEDED -> if (retryAvailable) AgentRunState.PAUSED_SAFETY_BOUNDARY else AgentRunState.FAILED
            OracleSignal.APP_PROCESS_DIED,
            OracleSignal.ANR_OR_CRASH_DIALOG -> AgentRunState.FAILED
            OracleSignal.UI_FROZEN,
            OracleSignal.BLACK_OR_BLANK_SCREEN -> if (retryAvailable) AgentRunState.PAUSED_SAFETY_BOUNDARY else AgentRunState.FAILED
        }

        val evidenceText = evidence?.let {
            " Evidence: ${it.file.name} sha256=${it.sha256}."
        }.orEmpty()
        return checkpoint.copy(
            state = state,
            lastResult = decision.detail + evidenceText,
            retryBudgetRemaining = if (retryAvailable) checkpoint.retryBudgetRemaining - 1 else checkpoint.retryBudgetRemaining,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }
}

class AgentWatchdogStateStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "apkbox-agent/watchdog").apply { mkdirs() }

    @Synchronized
    fun load(runId: String): AgentWatchdogState? = runCatching {
        val file = file(runId)
        if (!file.isFile) null else AgentWatchdogState.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
    }.getOrNull()

    @Synchronized
    fun save(state: AgentWatchdogState) {
        val target = file(state.runId)
        val temp = File(root, ".${target.name}.tmp")
        temp.writeText(state.toJson().toString(), Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace watchdog state.")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    fun clear(runId: String) {
        file(runId).delete()
    }

    private fun file(runId: String): File = File(
        root,
        runId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "run" } + ".json",
    )
}
