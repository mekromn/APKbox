package com.mekromn.apkbox.agent

import android.content.Context
import com.mekromn.apkbox.bridge.PrivilegedBridgeManager
import com.mekromn.apkbox.bridge.BridgeCommandType
import com.mekromn.apkbox.bridge.BridgeExecutor
import com.mekromn.apkbox.bridge.BridgePolicy
import com.mekromn.apkbox.bridge.BridgeRequest
import com.mekromn.apkbox.bridge.BridgeResult
import com.mekromn.apkbox.bridge.BridgeResultStatus
import com.mekromn.apkbox.bridge.ScreenAgentController
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Executes a pre-approved, bounded autonomous plan until it reaches a checkpoint or a safety
 * boundary. The runner deliberately uses BridgeExecutor for Android interaction so package checks,
 * request journaling semantics and the persistent run/sequence gate are identical to remote actions.
 */
class AutonomousPlanRunner(
    context: Context,
    private val privileged: PrivilegedBridgeManager,
    private val executor: BridgeExecutor,
) {
    private val appContext = context.applicationContext
    private val store = AgentCheckpointStore(appContext)
    private val screen = ScreenAgentController(appContext, privileged)
    private val observationCollector = AgentObservationCollector(privileged, screen)
    private val evidenceCollector = AgentEvidenceCollector(appContext, privileged, screen, store)
    private val watchdogStateStore = AgentWatchdogStateStore(appContext)
    private val watchdog = AgentWatchdog(observationCollector, evidenceCollector, store, watchdogStateStore)
    private val recovery = AgentRecoveryExecutor(privileged, screen)
    private val actionLedger = AgentActionLedger(appContext)
    private val runMutex = Mutex()

    suspend fun start(
        plan: AutonomousPlan,
        controllerLeaseUntilEpochMs: Long = 0L,
        buildLabel: String = "",
        buildSha256: String = "",
    ): AgentCheckpoint = runMutex.withLock {
        store.savePlan(plan)
        watchdog.clear(plan.runId)
        actionLedger.clearRun(plan.runId)

        val now = System.currentTimeMillis()
        val firstStep = plan.steps.first()
        val checkpoint = AgentCheckpoint(
            runId = plan.runId,
            targetPackage = plan.targetPackage,
            state = if (plan.controllerlessUntilCheckpoint) {
                AgentRunState.AUTONOMOUS_TO_CHECKPOINT
            } else {
                AgentRunState.RUNNING
            },
            stepIndex = 0,
            stepName = firstStep.name,
            nextGoal = firstStep.name,
            buildLabel = buildLabel.take(256),
            buildSha256 = buildSha256.lowercase().take(64),
            controllerLeaseUntilEpochMs = if (plan.controllerlessUntilCheckpoint) 0L else controllerLeaseUntilEpochMs,
            retryBudgetRemaining = plan.maxRetriesPerStep,
            startedAtEpochMs = now,
            missionDeadlineEpochMs = now + plan.maxRuntimeSeconds * 1_000L,
            sessionSegmentIndex = plan.sessionSegments.firstOrNull()?.index ?: 0,
            sessionSegmentName = plan.sessionSegments.firstOrNull()?.name.orEmpty(),
            updatedAtEpochMs = now,
        )
        store.saveCheckpoint(checkpoint)
        executeLoop(plan, checkpoint)
    }

    suspend fun resume(runId: String): AgentCheckpoint = runMutex.withLock {
        val plan = store.loadPlan(runId) ?: error("Autonomous plan is missing for run $runId.")
        val checkpoint = store.loadCheckpoint(runId) ?: error("Autonomous checkpoint is missing for run $runId.")
        if (checkpoint.state in TERMINAL_OR_WAITING_STATES) return@withLock checkpoint
        executeLoop(plan, normalizeDeadline(plan, checkpoint))
    }

    fun checkpoint(runId: String): AgentCheckpoint? = store.loadCheckpoint(runId)

    fun plan(runId: String): AutonomousPlan? = store.loadPlan(runId)

    fun recoverableRuns(): List<AgentCheckpoint> = store.listRecoverableRuns()

    fun pause(runId: String, reason: String): AgentCheckpoint? {
        val current = store.loadCheckpoint(runId) ?: return null
        val paused = current.copy(
            state = AgentRunState.PAUSED_SAFETY_BOUNDARY,
            lastResult = reason.take(4_096),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.saveCheckpoint(paused)
        return paused
    }

    private suspend fun executeLoop(
        plan: AutonomousPlan,
        initialCheckpoint: AgentCheckpoint,
    ): AgentCheckpoint {
        var checkpoint = initialCheckpoint
        var index = checkpoint.stepIndex.coerceIn(0, plan.steps.lastIndex)

        while (index < plan.steps.size) {
            val now = System.currentTimeMillis()
            if (checkpoint.missionDeadlineEpochMs > 0L && now >= checkpoint.missionDeadlineEpochMs) {
                val failed = watchdog.recordFailure(
                    checkpoint = checkpoint,
                    detail = "Autonomous mission deadline exceeded before step ${index + 1}.",
                    mayRetry = false,
                ).checkpoint.copy(state = AgentRunState.FAILED)
                store.saveCheckpoint(failed)
                return failed
            }

            val step = plan.steps[index]
            if (step.actionType == AutonomousAction.CHECKPOINT) {
                return reachCheckpoint(plan, checkpoint, index, step)
            }

            val continuingSameStep = checkpoint.stepIndex == index && checkpoint.stepName == step.name
            if (!continuingSameStep || checkpoint.retryBudgetRemaining !in 0..plan.maxRetriesPerStep) {
                checkpoint = checkpoint.copy(retryBudgetRemaining = plan.maxRetriesPerStep)
            }

            checkpoint = checkpoint.copy(
                state = if (plan.controllerlessUntilCheckpoint) {
                    AgentRunState.AUTONOMOUS_TO_CHECKPOINT
                } else {
                    AgentRunState.RUNNING
                },
                stepIndex = index,
                stepName = step.name,
                nextGoal = step.name,
                lastAction = step.actionType.name,
                updatedAtEpochMs = now,
            )
            store.saveCheckpoint(checkpoint)

            val beforeFingerprint = checkpoint.uiFingerprint
            if (beforeFingerprint.isNotBlank()) watchdog.markProgress(plan.runId, beforeFingerprint)

            val execution = runCatching { executeStep(plan, index, step) }
            if (execution.isFailure) {
                val failure = execution.exceptionOrNull()
                val outcome = watchdog.recordFailure(
                    checkpoint = checkpoint,
                    detail = "${step.name}: ${failure?.message ?: failure?.javaClass?.simpleName ?: "action failed"}",
                    mayRetry = true,
                )
                val retry = handleRetryableFailure(plan, outcome, step)
                checkpoint = retry.checkpoint
                if (!retry.retry) return checkpoint
                continue
            }

            val result = execution.getOrThrow()
            val succeeded = result.status == BridgeResultStatus.SUCCESS
            if (!succeeded) {
                val outcome = watchdog.recordFailure(
                    checkpoint = checkpoint.copy(
                        foregroundPackage = result.foregroundPackage,
                        uiFingerprint = result.uiFingerprint.ifBlank { checkpoint.uiFingerprint },
                        lastResult = result.detail,
                    ),
                    detail = "${step.name}: ${result.detail}",
                    mayRetry = result.status !in setOf(BridgeResultStatus.INVALID, BridgeResultStatus.DENIED),
                )
                val retry = handleRetryableFailure(plan, outcome, step)
                checkpoint = retry.checkpoint
                if (!retry.retry) return checkpoint
                continue
            }

            checkpoint = applySuccessfulResult(checkpoint, step, result)
            store.saveCheckpoint(checkpoint)

            if (step.actionType == AutonomousAction.SLEEP) {
                // SLEEP has no BridgeResult-derived foreground state, so re-observation below is
                // particularly important.
            } else {
                delay(120)
            }

            val health = verifyAfterStep(plan, checkpoint, step, beforeFingerprint)
            checkpoint = health.checkpoint
            if (health.decision.terminalForStep) {
                val retry = handleRetryableFailure(plan, health, step)
                checkpoint = retry.checkpoint
                if (!retry.retry) return checkpoint
                continue
            }

            watchdog.markProgress(plan.runId, checkpoint.uiFingerprint)
            index++
            if (index >= plan.steps.size) break
            checkpoint = checkpoint.copy(
                stepIndex = index,
                stepName = plan.steps[index].name,
                nextGoal = plan.steps[index].name,
                retryBudgetRemaining = plan.maxRetriesPerStep,
                lastResult = "Completed ${step.name}.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            store.saveCheckpoint(checkpoint)
        }

        val finished = checkpoint.copy(
            state = AgentRunState.CHECKPOINT_REACHED,
            stepIndex = plan.steps.size,
            stepName = plan.checkpointName.ifBlank { "plan-complete" },
            nextGoal = "Await controller at ${plan.checkpointName.ifBlank { "plan-complete" }}.",
            lastResult = "Autonomous plan completed all bounded steps.",
            retryBudgetRemaining = 0,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.saveCheckpoint(finished)
        return finished
    }

    private suspend fun executeStep(
        plan: AutonomousPlan,
        index: Int,
        step: AutonomousStep,
    ): BridgeResult {
        if (step.actionType == AutonomousAction.SLEEP) {
            delay(step.durationMs.toLong())
            return BridgeResult(
                requestId = requestId(plan.runId, index, 0L, "sleep"),
                status = BridgeResultStatus.SUCCESS,
                risk = com.mekromn.apkbox.bridge.BridgeRisk.DEBUG_ACTION,
                detail = "Slept ${step.durationMs} ms.",
                durationMs = step.durationMs.toLong(),
            )
        }

        val sequence = if (stepNeedsSequence(step.actionType)) {
            actionLedger.lastSequence(plan.runId) + 1L
        } else {
            0L
        }
        val request = BridgeRequest(
            id = requestId(plan.runId, index, sequence, step.actionType.name.lowercase()),
            type = bridgeType(step.actionType),
            packageName = plan.targetPackage,
            selector = step.selector,
            value = step.value,
            x = step.x,
            y = step.y,
            endX = step.endX,
            endY = step.endY,
            durationMs = step.durationMs,
            keyCode = step.keyCode,
            runId = plan.runId,
            sequenceNumber = sequence,
            reason = "Autonomous run ${plan.runId}: ${step.name}",
            timeoutSeconds = step.timeoutSeconds,
            source = "APKbox autonomous plan",
        )
        return executor.execute(request, BridgePolicy.classify(request))
    }

    private suspend fun verifyAfterStep(
        plan: AutonomousPlan,
        checkpoint: AgentCheckpoint,
        step: AutonomousStep,
        beforeFingerprint: String,
    ): WatchdogOutcome {
        val stepDeadline = System.currentTimeMillis() + step.timeoutSeconds * 1_000L
        var current = checkpoint

        // Establish the pre-action fingerprint as the watchdog's reference when the step promised a
        // UI change. Three bounded observations distinguish slow UI from an actual frozen state.
        if (step.requireUiChange && beforeFingerprint.isNotBlank()) {
            watchdog.markProgress(plan.runId, beforeFingerprint)
        }

        val samples = if (step.requireUiChange) 3 else 1
        var last: WatchdogOutcome? = null
        repeat(samples) { sample ->
            if (sample > 0) delay(250)
            val outcome = watchdog.check(
                checkpoint = current,
                config = WatchdogCheckConfig(
                    missionDeadlineEpochMs = current.missionDeadlineEpochMs,
                    stepDeadlineEpochMs = stepDeadline,
                    requiredUiChange = step.requireUiChange,
                    includeVisualStats = true,
                ),
            )
            last = outcome
            current = outcome.checkpoint
            if (outcome.decision.terminalForStep) return outcome

            val observedFingerprint = outcome.observation?.observation?.uiFingerprint.orEmpty()
            if (step.requireUiChange && beforeFingerprint.isNotBlank() &&
                observedFingerprint.isNotBlank() && observedFingerprint != beforeFingerprint
            ) {
                watchdog.markProgress(plan.runId, observedFingerprint)
                return outcome
            }
        }
        return last ?: error("Watchdog produced no observation.")
    }

    private suspend fun handleRetryableFailure(
        plan: AutonomousPlan,
        outcome: WatchdogOutcome,
        step: AutonomousStep,
    ): RetryDecision {
        var checkpoint = outcome.checkpoint
        if (!outcome.recoveryRecommended) return RetryDecision(false, checkpoint)

        // Selector/action failures can simply be re-observed and retried; other retryable stalls use
        // the bounded recovery executor after evidence has already been persisted by AgentWatchdog.
        if (outcome.decision.signal == OracleSignal.ACTION_FAILED) {
            delay(300)
            checkpoint = checkpoint.copy(
                state = if (plan.controllerlessUntilCheckpoint) AgentRunState.AUTONOMOUS_TO_CHECKPOINT else AgentRunState.RUNNING,
                lastResult = "Retrying ${step.name} after an observed action failure.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            store.saveCheckpoint(checkpoint)
            return RetryDecision(true, checkpoint)
        }

        val recovered = recovery.recover(checkpoint, outcome.decision)
        if (!recovered.recovered) {
            val stopped = checkpoint.copy(
                lastResult = checkpoint.lastResult + " Recovery: ${recovered.detail}",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            store.saveCheckpoint(stopped)
            return RetryDecision(false, stopped)
        }

        val resumed = checkpoint.copy(
            state = if (plan.controllerlessUntilCheckpoint) AgentRunState.AUTONOMOUS_TO_CHECKPOINT else AgentRunState.RUNNING,
            foregroundPackage = recovered.foregroundPackage.ifBlank { checkpoint.foregroundPackage },
            uiFingerprint = recovered.uiFingerprint.ifBlank { checkpoint.uiFingerprint },
            lastResult = "Recovered for retry: ${recovered.detail}",
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.saveCheckpoint(resumed)
        if (recovered.uiFingerprint.isNotBlank()) watchdog.markProgress(plan.runId, recovered.uiFingerprint)
        return RetryDecision(true, resumed)
    }

    private fun applySuccessfulResult(
        checkpoint: AgentCheckpoint,
        step: AutonomousStep,
        result: BridgeResult,
    ): AgentCheckpoint {
        val localScreenshot = if (
            step.actionType == AutonomousAction.SCREENSHOT &&
            result.output.startsWith(ScreenAgentController.LOCAL_ARTIFACT_PREFIX)
        ) {
            result.output.removePrefix(ScreenAgentController.LOCAL_ARTIFACT_PREFIX)
        } else {
            checkpoint.screenshotArtifactPath
        }
        return checkpoint.copy(
            foregroundPackage = result.foregroundPackage.ifBlank { checkpoint.foregroundPackage },
            uiFingerprint = result.uiFingerprint.ifBlank { checkpoint.uiFingerprint },
            screenshotArtifactPath = localScreenshot,
            lastResult = result.detail,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun reachCheckpoint(
        plan: AutonomousPlan,
        checkpoint: AgentCheckpoint,
        index: Int,
        step: AutonomousStep,
    ): AgentCheckpoint {
        val segment = plan.sessionSegments.firstOrNull { segment ->
            segment.checkpointName.equals(step.name, ignoreCase = true) ||
                segment.checkpointName.equals(plan.checkpointName, ignoreCase = true)
        }
        val nextSegment = segment?.let { current ->
            plan.sessionSegments.firstOrNull { it.index > current.index }
        }
        val handoff = segment?.handoffMode == SessionHandoffMode.AFTER_CHECKPOINT
        val reached = checkpoint.copy(
            state = if (handoff) AgentRunState.HANDOFF_PENDING else AgentRunState.CHECKPOINT_REACHED,
            stepIndex = index,
            stepName = step.name,
            nextGoal = nextSegment?.goal ?: "Await ChatGPT controller at checkpoint ${step.name}.",
            retryBudgetRemaining = 0,
            sessionSegmentIndex = segment?.index ?: checkpoint.sessionSegmentIndex,
            sessionSegmentName = segment?.name ?: checkpoint.sessionSegmentName,
            nextSessionGoal = nextSegment?.goal.orEmpty(),
            proactiveHandoffRequested = handoff,
            lastResult = "Reached verified checkpoint ${step.name}.",
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.saveCheckpoint(reached)
        return reached
    }

    private fun normalizeDeadline(plan: AutonomousPlan, checkpoint: AgentCheckpoint): AgentCheckpoint {
        if (checkpoint.startedAtEpochMs > 0L && checkpoint.missionDeadlineEpochMs > 0L) return checkpoint
        val start = checkpoint.startedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val normalized = checkpoint.copy(
            startedAtEpochMs = start,
            missionDeadlineEpochMs = start + plan.maxRuntimeSeconds * 1_000L,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.saveCheckpoint(normalized)
        return normalized
    }

    private fun bridgeType(action: AutonomousAction): BridgeCommandType = when (action) {
        AutonomousAction.LAUNCH -> BridgeCommandType.LAUNCH
        AutonomousAction.TAP -> BridgeCommandType.UI_TAP
        AutonomousAction.FIND_TAP -> BridgeCommandType.UI_FIND_TAP
        AutonomousAction.SWIPE -> BridgeCommandType.UI_SWIPE
        AutonomousAction.TEXT -> BridgeCommandType.UI_TEXT
        AutonomousAction.KEY -> BridgeCommandType.UI_KEY
        AutonomousAction.WAIT -> BridgeCommandType.UI_WAIT
        AutonomousAction.SNAPSHOT -> BridgeCommandType.UI_SNAPSHOT
        AutonomousAction.SCREENSHOT -> BridgeCommandType.SCREENSHOT
        AutonomousAction.SLEEP,
        AutonomousAction.CHECKPOINT -> error("$action does not use BridgeExecutor.")
    }

    private fun stepNeedsSequence(action: AutonomousAction): Boolean = action in setOf(
        AutonomousAction.LAUNCH,
        AutonomousAction.TAP,
        AutonomousAction.FIND_TAP,
        AutonomousAction.SWIPE,
        AutonomousAction.TEXT,
        AutonomousAction.KEY,
        AutonomousAction.WAIT,
    )

    private fun requestId(runId: String, index: Int, sequence: Long, suffix: String): String {
        val safeRun = runId.take(56)
        return "$safeRun-s${index + 1}-q$sequence-${suffix.take(16)}".take(96)
    }

    private data class RetryDecision(val retry: Boolean, val checkpoint: AgentCheckpoint)

    companion object {
        private val TERMINAL_OR_WAITING_STATES = setOf(
            AgentRunState.CHECKPOINT_REACHED,
            AgentRunState.HANDOFF_PENDING,
            AgentRunState.PAUSED_CONTROLLER_LOST,
            AgentRunState.PAUSED_UNEXPECTED_SCREEN,
            AgentRunState.PAUSED_SAFETY_BOUNDARY,
            AgentRunState.FAILED,
            AgentRunState.COMPLETED,
        )
    }
}
