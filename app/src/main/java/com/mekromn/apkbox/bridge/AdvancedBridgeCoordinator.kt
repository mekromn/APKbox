package com.mekromn.apkbox.bridge

import android.content.Context
import com.mekromn.apkbox.ApkBoxServices
import com.mekromn.apkbox.agent.AgentRunState
import com.mekromn.apkbox.agent.BuildRunState

/**
 * Turns advanced relay verbs into complete APKbox workflows. The caller approves one bounded start
 * request; this coordinator owns the private relay fetch/publish plumbing so future agents do not
 * need to hand-stitch implementation details or bypass APKbox's local safety model.
 */
class AdvancedBridgeCoordinator(
    context: Context,
    private val relay: GitHubRelayClient,
) {
    companion object {
        private val ID_REGEX = Regex("[A-Za-z0-9._-]{1,96}")

        fun handles(type: BridgeCommandType): Boolean = type in setOf(
            BridgeCommandType.AGENT_START,
            BridgeCommandType.AGENT_RESUME,
            BridgeCommandType.AGENT_STATUS,
            BridgeCommandType.BUILD_START,
            BridgeCommandType.BUILD_STATUS,
        )
    }

    private val appContext = context.applicationContext
    private val agentRunner by lazy { ApkBoxServices.autonomousPlanRunner(appContext) }
    private val buildRunner by lazy { ApkBoxServices.buildRunner(appContext) }

    suspend fun execute(
        request: BridgeRequest,
        risk: BridgeRisk,
        config: BridgeConfig,
        token: String,
    ): BridgeResult {
        val started = System.currentTimeMillis()
        return runCatching {
            when (request.type) {
                BridgeCommandType.AGENT_START -> startAgent(request, risk, config, token, started)
                BridgeCommandType.AGENT_RESUME -> resumeAgent(request, risk, config, token, started)
                BridgeCommandType.AGENT_STATUS -> agentStatus(request, risk, config, token, started)
                BridgeCommandType.BUILD_START -> startBuild(request, risk, config, token, started)
                BridgeCommandType.BUILD_STATUS -> buildStatus(request, risk, config, token, started)
                else -> error("${request.type} is not an advanced bridge workflow.")
            }
        }.getOrElse { failure ->
            BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.FAILED,
                risk = risk,
                detail = failure.message ?: failure.javaClass.simpleName,
                durationMs = System.currentTimeMillis() - started,
            )
        }
    }

    private suspend fun startAgent(
        request: BridgeRequest,
        risk: BridgeRisk,
        config: BridgeConfig,
        token: String,
        started: Long,
    ): BridgeResult {
        val runId = requireRunId(request.runId)
        val plan = relay.fetchAgentPlan(config, token, runId)
        requireOptionalPackageMatch(request.packageName, plan.targetPackage, "autonomous plan")
        val checkpoint = agentRunner.start(plan)
        val published = relay.writeAgentCheckpoint(config, token, checkpoint)
        return BridgeResult(
            requestId = request.id,
            status = if (checkpoint.state == AgentRunState.FAILED) BridgeResultStatus.FAILED else BridgeResultStatus.SUCCESS,
            risk = risk,
            detail = "Autonomous run $runId reached ${checkpoint.state}. Checkpoint published to $published.",
            output = checkpoint.toJson().toString(2),
            durationMs = System.currentTimeMillis() - started,
            foregroundPackage = checkpoint.foregroundPackage,
            uiFingerprint = checkpoint.uiFingerprint,
        )
    }

    private suspend fun resumeAgent(
        request: BridgeRequest,
        risk: BridgeRisk,
        config: BridgeConfig,
        token: String,
        started: Long,
    ): BridgeResult {
        val runId = requireRunId(request.runId)
        val before = agentRunner.checkpoint(runId) ?: error("No autonomous checkpoint exists for run $runId.")
        requireOptionalPackageMatch(request.packageName, before.targetPackage, "persisted autonomous run")
        val checkpoint = agentRunner.resume(runId)
        val published = relay.writeAgentCheckpoint(config, token, checkpoint)
        return BridgeResult(
            requestId = request.id,
            status = if (checkpoint.state == AgentRunState.FAILED) BridgeResultStatus.FAILED else BridgeResultStatus.SUCCESS,
            risk = risk,
            detail = "Autonomous run $runId resumed to ${checkpoint.state}. Checkpoint published to $published.",
            output = checkpoint.toJson().toString(2),
            durationMs = System.currentTimeMillis() - started,
            foregroundPackage = checkpoint.foregroundPackage,
            uiFingerprint = checkpoint.uiFingerprint,
        )
    }

    private suspend fun agentStatus(
        request: BridgeRequest,
        risk: BridgeRisk,
        config: BridgeConfig,
        token: String,
        started: Long,
    ): BridgeResult {
        val runId = requireRunId(request.runId)
        val checkpoint = agentRunner.checkpoint(runId) ?: error("No autonomous checkpoint exists for run $runId.")
        requireOptionalPackageMatch(request.packageName, checkpoint.targetPackage, "persisted autonomous run")
        val published = relay.writeAgentCheckpoint(config, token, checkpoint)
        return BridgeResult(
            requestId = request.id,
            status = BridgeResultStatus.SUCCESS,
            risk = risk,
            detail = "Autonomous run $runId is ${checkpoint.state}. Checkpoint refreshed at $published.",
            output = checkpoint.toJson().toString(2),
            durationMs = System.currentTimeMillis() - started,
            foregroundPackage = checkpoint.foregroundPackage,
            uiFingerprint = checkpoint.uiFingerprint,
        )
    }

    private suspend fun startBuild(
        request: BridgeRequest,
        risk: BridgeRisk,
        config: BridgeConfig,
        token: String,
        started: Long,
    ): BridgeResult {
        val buildId = requireBuildId(request)
        val candidate = relay.fetchBuildCandidate(config, token, buildId)
        if (request.runId.isNotBlank()) {
            require(request.runId == candidate.runId) {
                "BUILD_START request runId '${request.runId}' does not match candidate runId '${candidate.runId}'."
            }
        }
        requireOptionalPackageMatch(request.packageName, candidate.targetPackage, "build candidate")

        val checkpoint = buildRunner.run(candidate)
        val buildPath = relay.writeBuildCheckpoint(config, token, checkpoint)
        val testHint = if (checkpoint.state == BuildRunState.TESTING && candidate.planRunId.isNotBlank()) {
            " Candidate names autonomous plan '${candidate.planRunId}', but APKbox did not start it under BUILD_START approval. Issue AGENT_START with that runId for a separate fresh approval."
        } else {
            ""
        }
        val failed = checkpoint.state in setOf(
            BuildRunState.FAILED,
            BuildRunState.BLOCKED_SIGNATURE_MISMATCH,
            BuildRunState.BLOCKED_PROJECT_AMBIGUOUS,
            BuildRunState.BLOCKED_AUTH_REQUIRED,
        )
        return BridgeResult(
            requestId = request.id,
            status = if (failed) BridgeResultStatus.FAILED else BridgeResultStatus.SUCCESS,
            risk = risk,
            detail = "Build ${candidate.buildId} reached ${checkpoint.state}; checkpoint $buildPath.$testHint",
            output = checkpoint.toJson().toString(2),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    private suspend fun buildStatus(
        request: BridgeRequest,
        risk: BridgeRisk,
        config: BridgeConfig,
        token: String,
        started: Long,
    ): BridgeResult {
        val runId = if (request.runId.isNotBlank()) {
            requireRunId(request.runId)
        } else {
            val buildId = request.buildId.trim()
            require(ID_REGEX.matches(buildId)) { "BUILD_STATUS requires runId or a valid buildId." }
            relay.fetchBuildCandidate(config, token, buildId).runId
        }
        val checkpoint = buildRunner.checkpoint(runId) ?: error("No build checkpoint exists for run $runId.")
        requireOptionalPackageMatch(request.packageName, checkpoint.targetPackage, "persisted build run")
        val published = relay.writeBuildCheckpoint(config, token, checkpoint)
        return BridgeResult(
            requestId = request.id,
            status = BridgeResultStatus.SUCCESS,
            risk = risk,
            detail = "Build run $runId is ${checkpoint.state}. Checkpoint refreshed at $published.",
            output = checkpoint.toJson().toString(2),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    private fun requireBuildId(request: BridgeRequest): String {
        val value = request.buildId.ifBlank { request.runId }.trim()
        require(ID_REGEX.matches(value)) { "BUILD_START requires a valid buildId (or runId fallback)." }
        return value
    }

    private fun requireRunId(value: String): String {
        val runId = value.trim()
        require(ID_REGEX.matches(runId)) { "A valid runId is required." }
        return runId
    }

    private fun requireOptionalPackageMatch(requestPackage: String, actualPackage: String, source: String) {
        if (requestPackage.isBlank()) return
        require(requestPackage == actualPackage) {
            "Request package '$requestPackage' does not match $source package '$actualPackage'."
        }
    }
}
