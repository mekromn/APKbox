package com.mekromn.apkbox.agent

import org.json.JSONArray
import org.json.JSONObject

enum class AgentRunState {
    CREATED,
    RUNNING,
    WAITING_FOR_CONTROLLER,
    AUTONOMOUS_TO_CHECKPOINT,
    CHECKPOINT_REACHED,
    HANDOFF_PENDING,
    PAUSED_CONTROLLER_LOST,
    PAUSED_UNEXPECTED_SCREEN,
    PAUSED_SAFETY_BOUNDARY,
    FAILED,
    COMPLETED,
}

enum class ChatHandoffState {
    NONE,
    EXPORTING_VISIBLE_CHAT,
    VERIFYING_EXPORT,
    OPENING_NEW_CHAT,
    UPLOADING_TRANSCRIPT,
    SENDING_BOOTSTRAP,
    WAITING_FOR_NEW_CONTROLLER,
    COMPLETE,
    FAILED,
}

enum class ChatHandoffReason {
    CONTROLLER_STALL,
    FORCED_NEW_CHAT,
    PROACTIVE_SESSION_SPLIT,
}

enum class SessionHandoffMode {
    NONE,
    AFTER_CHECKPOINT,
}

data class AgentCheckpoint(
    val runId: String,
    val targetPackage: String,
    val state: AgentRunState,
    val stepIndex: Int,
    val stepName: String,
    val nextGoal: String,
    val buildLabel: String = "",
    val buildSha256: String = "",
    val foregroundPackage: String = "",
    val uiFingerprint: String = "",
    val screenshotArtifactPath: String = "",
    val lastAction: String = "",
    val lastResult: String = "",
    val controllerLeaseUntilEpochMs: Long = 0L,
    val retryBudgetRemaining: Int = 0,
    val sessionSegmentIndex: Int = 0,
    val sessionSegmentName: String = "",
    val nextSessionGoal: String = "",
    val proactiveHandoffRequested: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("runId", runId)
        .put("targetPackage", targetPackage)
        .put("state", state.name)
        .put("stepIndex", stepIndex)
        .put("stepName", stepName)
        .put("nextGoal", nextGoal)
        .put("buildLabel", buildLabel)
        .put("buildSha256", buildSha256)
        .put("foregroundPackage", foregroundPackage)
        .put("uiFingerprint", uiFingerprint)
        .put("screenshotArtifactPath", screenshotArtifactPath)
        .put("lastAction", lastAction)
        .put("lastResult", lastResult)
        .put("controllerLeaseUntilEpochMs", controllerLeaseUntilEpochMs)
        .put("retryBudgetRemaining", retryBudgetRemaining)
        .put("sessionSegmentIndex", sessionSegmentIndex)
        .put("sessionSegmentName", sessionSegmentName)
        .put("nextSessionGoal", nextSessionGoal)
        .put("proactiveHandoffRequested", proactiveHandoffRequested)
        .put("updatedAtEpochMs", updatedAtEpochMs)

    companion object {
        fun fromJson(json: JSONObject): AgentCheckpoint = AgentCheckpoint(
            runId = json.getString("runId"),
            targetPackage = json.getString("targetPackage"),
            state = AgentRunState.valueOf(json.getString("state")),
            stepIndex = json.optInt("stepIndex"),
            stepName = json.optString("stepName"),
            nextGoal = json.optString("nextGoal"),
            buildLabel = json.optString("buildLabel"),
            buildSha256 = json.optString("buildSha256"),
            foregroundPackage = json.optString("foregroundPackage"),
            uiFingerprint = json.optString("uiFingerprint"),
            screenshotArtifactPath = json.optString("screenshotArtifactPath"),
            lastAction = json.optString("lastAction"),
            lastResult = json.optString("lastResult"),
            controllerLeaseUntilEpochMs = json.optLong("controllerLeaseUntilEpochMs"),
            retryBudgetRemaining = json.optInt("retryBudgetRemaining"),
            sessionSegmentIndex = json.optInt("sessionSegmentIndex"),
            sessionSegmentName = json.optString("sessionSegmentName"),
            nextSessionGoal = json.optString("nextSessionGoal"),
            proactiveHandoffRequested = json.optBoolean("proactiveHandoffRequested", false),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs"),
        )
    }
}

data class VisibleChatTurn(
    val role: String,
    val markdown: String,
    val sourceFingerprint: String,
)

data class ChatHandoffCheckpoint(
    val runId: String,
    val state: ChatHandoffState,
    val reason: ChatHandoffReason,
    val sourceConversationUrl: String,
    val transcriptFileName: String,
    val transcriptSha256: String = "",
    val transcriptBytes: Long = 0L,
    val capturedTurns: Int = 0,
    val lastCapturedFingerprint: String = "",
    val newConversationUrl: String = "",
    val controllerResumeAttempts: Int = 0,
    val targetSessionSegmentIndex: Int = 0,
    val nextSessionGoal: String = "",
    val bootstrapMessage: String = "",
    val error: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("runId", runId)
        .put("state", state.name)
        .put("reason", reason.name)
        .put("sourceConversationUrl", sourceConversationUrl)
        .put("transcriptFileName", transcriptFileName)
        .put("transcriptSha256", transcriptSha256)
        .put("transcriptBytes", transcriptBytes)
        .put("capturedTurns", capturedTurns)
        .put("lastCapturedFingerprint", lastCapturedFingerprint)
        .put("newConversationUrl", newConversationUrl)
        .put("controllerResumeAttempts", controllerResumeAttempts)
        .put("targetSessionSegmentIndex", targetSessionSegmentIndex)
        .put("nextSessionGoal", nextSessionGoal)
        .put("bootstrapMessage", bootstrapMessage)
        .put("error", error)
        .put("updatedAtEpochMs", updatedAtEpochMs)

    companion object {
        fun fromJson(json: JSONObject): ChatHandoffCheckpoint = ChatHandoffCheckpoint(
            runId = json.getString("runId"),
            state = ChatHandoffState.valueOf(json.getString("state")),
            reason = runCatching { ChatHandoffReason.valueOf(json.optString("reason")) }
                .getOrDefault(ChatHandoffReason.CONTROLLER_STALL),
            sourceConversationUrl = json.optString("sourceConversationUrl"),
            transcriptFileName = json.optString("transcriptFileName"),
            transcriptSha256 = json.optString("transcriptSha256"),
            transcriptBytes = json.optLong("transcriptBytes"),
            capturedTurns = json.optInt("capturedTurns"),
            lastCapturedFingerprint = json.optString("lastCapturedFingerprint"),
            newConversationUrl = json.optString("newConversationUrl"),
            controllerResumeAttempts = json.optInt("controllerResumeAttempts"),
            targetSessionSegmentIndex = json.optInt("targetSessionSegmentIndex"),
            nextSessionGoal = json.optString("nextSessionGoal"),
            bootstrapMessage = json.optString("bootstrapMessage"),
            error = json.optString("error"),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs"),
        )
    }
}

data class SessionSegment(
    val index: Int,
    val name: String,
    val goal: String,
    val checkpointName: String,
    val handoffMode: SessionHandoffMode = SessionHandoffMode.NONE,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("index", index)
        .put("name", name)
        .put("goal", goal)
        .put("checkpointName", checkpointName)
        .put("handoffMode", handoffMode.name)
}

data class AutonomousPlan(
    val runId: String,
    val targetPackage: String,
    val checkpointName: String,
    val maxRuntimeSeconds: Int,
    val maxRetriesPerStep: Int,
    val steps: List<AutonomousStep>,
    val sessionSegments: List<SessionSegment> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("runId", runId)
        .put("targetPackage", targetPackage)
        .put("checkpointName", checkpointName)
        .put("maxRuntimeSeconds", maxRuntimeSeconds)
        .put("maxRetriesPerStep", maxRetriesPerStep)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        .put("sessionSegments", JSONArray().apply { sessionSegments.forEach { put(it.toJson()) } })
}

data class AutonomousStep(
    val name: String,
    val action: String,
    val selector: String = "",
    val value: String = "",
    val timeoutSeconds: Int = 20,
    val requireUiChange: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("action", action)
        .put("selector", selector)
        .put("value", value)
        .put("timeoutSeconds", timeoutSeconds)
        .put("requireUiChange", requireUiChange)
}
