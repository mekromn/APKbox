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

enum class AutonomousAction {
    LAUNCH,
    TAP,
    FIND_TAP,
    SWIPE,
    TEXT,
    KEY,
    WAIT,
    SNAPSHOT,
    SCREENSHOT,
    SLEEP,
    CHECKPOINT,
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
    val startedAtEpochMs: Long = 0L,
    val missionDeadlineEpochMs: Long = 0L,
    val sessionSegmentIndex: Int = 0,
    val sessionSegmentName: String = "",
    val nextSessionGoal: String = "",
    val proactiveHandoffRequested: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 2)
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
        .put("startedAtEpochMs", startedAtEpochMs)
        .put("missionDeadlineEpochMs", missionDeadlineEpochMs)
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
            startedAtEpochMs = json.optLong("startedAtEpochMs"),
            missionDeadlineEpochMs = json.optLong("missionDeadlineEpochMs"),
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

    companion object {
        fun fromJson(json: JSONObject): SessionSegment = SessionSegment(
            index = json.optInt("index").coerceAtLeast(0),
            name = json.optString("name").take(160),
            goal = json.optString("goal").take(2_048),
            checkpointName = json.optString("checkpointName").take(160),
            handoffMode = runCatching { SessionHandoffMode.valueOf(json.optString("handoffMode", "NONE").uppercase()) }
                .getOrDefault(SessionHandoffMode.NONE),
        )
    }
}

data class AutonomousPlan(
    val runId: String,
    val targetPackage: String,
    val checkpointName: String,
    val maxRuntimeSeconds: Int,
    val maxRetriesPerStep: Int,
    val steps: List<AutonomousStep>,
    val sessionSegments: List<SessionSegment> = emptyList(),
    val controllerlessUntilCheckpoint: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 2)
        .put("runId", runId)
        .put("targetPackage", targetPackage)
        .put("checkpointName", checkpointName)
        .put("maxRuntimeSeconds", maxRuntimeSeconds)
        .put("maxRetriesPerStep", maxRetriesPerStep)
        .put("controllerlessUntilCheckpoint", controllerlessUntilCheckpoint)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        .put("sessionSegments", JSONArray().apply { sessionSegments.forEach { put(it.toJson()) } })

    companion object {
        private val runRegex = Regex("[A-Za-z0-9._-]{1,96}")
        private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

        fun fromJson(json: JSONObject): AutonomousPlan {
            val runId = json.optString("runId").trim()
            require(runRegex.matches(runId)) { "Invalid autonomous run ID." }
            val targetPackage = json.optString("targetPackage").trim()
            require(packageRegex.matches(targetPackage)) { "Invalid autonomous target package." }

            val stepArray = json.optJSONArray("steps") ?: JSONArray()
            require(stepArray.length() in 1..500) { "Autonomous plan must contain 1..500 steps." }
            val steps = buildList {
                for (index in 0 until stepArray.length()) {
                    add(AutonomousStep.fromJson(stepArray.getJSONObject(index), index))
                }
            }

            val segmentArray = json.optJSONArray("sessionSegments") ?: JSONArray()
            require(segmentArray.length() <= 50) { "Too many session segments." }
            val segments = buildList {
                for (index in 0 until segmentArray.length()) {
                    add(SessionSegment.fromJson(segmentArray.getJSONObject(index)))
                }
            }

            return AutonomousPlan(
                runId = runId,
                targetPackage = targetPackage,
                checkpointName = json.optString("checkpointName", "checkpoint").take(160),
                maxRuntimeSeconds = json.optInt("maxRuntimeSeconds", 600).coerceIn(10, 7_200),
                maxRetriesPerStep = json.optInt("maxRetriesPerStep", 2).coerceIn(0, 10),
                steps = steps,
                sessionSegments = segments,
                controllerlessUntilCheckpoint = json.optBoolean("controllerlessUntilCheckpoint", true),
            )
        }
    }
}

data class AutonomousStep(
    val name: String,
    val action: String,
    val selector: String = "",
    val value: String = "",
    val x: Int = -1,
    val y: Int = -1,
    val endX: Int = -1,
    val endY: Int = -1,
    val durationMs: Int = 300,
    val keyCode: Int = -1,
    val timeoutSeconds: Int = 20,
    val requireUiChange: Boolean = false,
) {
    val actionType: AutonomousAction
        get() = AutonomousAction.valueOf(action.uppercase())

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("action", actionType.name)
        .put("selector", selector)
        .put("value", value)
        .put("x", x)
        .put("y", y)
        .put("endX", endX)
        .put("endY", endY)
        .put("durationMs", durationMs)
        .put("keyCode", keyCode)
        .put("timeoutSeconds", timeoutSeconds)
        .put("requireUiChange", requireUiChange)

    companion object {
        fun fromJson(json: JSONObject, index: Int): AutonomousStep {
            val action = runCatching { AutonomousAction.valueOf(json.getString("action").uppercase()) }
                .getOrElse { error("Unsupported autonomous action at step $index.") }
            val step = AutonomousStep(
                name = json.optString("name", "Step ${index + 1}").take(160),
                action = action.name,
                selector = json.optString("selector").take(2_048),
                value = json.optString("value").take(8_192),
                x = json.optInt("x", -1),
                y = json.optInt("y", -1),
                endX = json.optInt("endX", -1),
                endY = json.optInt("endY", -1),
                durationMs = json.optInt("durationMs", 300).coerceIn(1, 10_000),
                keyCode = json.optInt("keyCode", -1),
                timeoutSeconds = json.optInt("timeoutSeconds", 20).coerceIn(1, 120),
                requireUiChange = json.optBoolean("requireUiChange", false),
            )
            step.validate(index)
            return step
        }
    }

    private fun validate(index: Int) {
        fun coordinate(value: Int): Boolean = value in 0..20_000
        when (actionType) {
            AutonomousAction.TAP -> require(coordinate(x) && coordinate(y)) { "Invalid TAP coordinates at step $index." }
            AutonomousAction.FIND_TAP,
            AutonomousAction.WAIT -> require(selector.isNotBlank()) { "Selector required at step $index." }
            AutonomousAction.SWIPE -> require(
                coordinate(x) && coordinate(y) && coordinate(endX) && coordinate(endY)
            ) { "Invalid SWIPE coordinates at step $index." }
            AutonomousAction.TEXT -> require(value.length <= 2_000) { "TEXT is too long at step $index." }
            AutonomousAction.KEY -> require(keyCode in 0..400) { "Invalid KEY code at step $index." }
            AutonomousAction.SLEEP -> require(durationMs in 1..10_000) { "Invalid SLEEP duration at step $index." }
            AutonomousAction.LAUNCH,
            AutonomousAction.SNAPSHOT,
            AutonomousAction.SCREENSHOT,
            AutonomousAction.CHECKPOINT -> Unit
        }
    }
}
