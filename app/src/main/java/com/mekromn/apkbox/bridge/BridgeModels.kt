package com.mekromn.apkbox.bridge

import org.json.JSONArray
import org.json.JSONObject

enum class BridgeCommandType {
    SHELL,
    LOGCAT,
    APP_LOGCAT,
    DUMPSYS,
    LAUNCH,

    // Informational presentation toolkit. Legacy NOTIFICATION/POPUP remain for compatibility;
    // explicit MESSAGE_* verbs let an agent choose the least intrusive useful presentation.
    TOAST,
    NOTIFICATION,
    POPUP,
    MESSAGE_SMALL_POPUP,
    MESSAGE_ALWAYS_ON_TOP,
    MESSAGE_FULL_WINDOW,
    MESSAGE_HEADS_UP,
    PICTURE_MESSAGE,

    UI_SNAPSHOT,
    SCREENSHOT,
    UI_TAP,
    UI_FIND_TAP,
    UI_SWIPE,
    UI_TEXT,
    UI_KEY,
    UI_WAIT,
    AGENT_START,
    AGENT_RESUME,
    AGENT_STATUS,
    BUILD_START,
    BUILD_STATUS,
}

enum class BridgeRisk {
    INFO,
    READ_ONLY,
    DEBUG_ACTION,
    MUTATING,
    DANGEROUS,
}

enum class BridgeResultStatus {
    SUCCESS,
    DENIED,
    EXPIRED,
    FAILED,
    TIMED_OUT,
    INVALID,
    AWAITING_APPROVAL,
}

data class BridgeRequest(
    val id: String,
    val type: BridgeCommandType,
    val command: String = "",
    val packageName: String = "",
    val service: String = "",
    val title: String = "",
    val message: String = "",
    val reason: String = "",
    val selector: String = "",
    val value: String = "",
    val imagePath: String = "",
    val x: Int = -1,
    val y: Int = -1,
    val endX: Int = -1,
    val endY: Int = -1,
    val durationMs: Int = 300,
    val keyCode: Int = -1,
    val runId: String = "",
    val buildId: String = "",
    val sequenceNumber: Long = 0L,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long = System.currentTimeMillis() + 10 * 60_000L,
    val timeoutSeconds: Int = 20,
    val source: String = "ChatGPT via Continuity",
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAtEpochMs in 1 until now

    fun toJson(): JSONObject = JSONObject()
        .put("schema", 4)
        .put("id", id)
        .put("type", type.name)
        .put("command", command)
        .put("packageName", packageName)
        .put("service", service)
        .put("title", title)
        .put("message", message)
        .put("reason", reason)
        .put("selector", selector)
        .put("value", value)
        .put("imagePath", imagePath)
        .put("x", x)
        .put("y", y)
        .put("endX", endX)
        .put("endY", endY)
        .put("durationMs", durationMs)
        .put("keyCode", keyCode)
        .put("runId", runId)
        .put("buildId", buildId)
        .put("sequenceNumber", sequenceNumber)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("expiresAtEpochMs", expiresAtEpochMs)
        .put("timeoutSeconds", timeoutSeconds)
        .put("source", source)

    companion object {
        private val idRegex = Regex("[A-Za-z0-9._-]{1,96}")
        private val relayImagePathRegex = Regex("[A-Za-z0-9._/-]{1,1024}")

        fun fromJson(json: JSONObject): BridgeRequest {
            val id = json.optString("id").trim()
            require(idRegex.matches(id)) { "Invalid bridge request id." }
            val type = runCatching { BridgeCommandType.valueOf(json.getString("type").uppercase()) }
                .getOrElse { error("Unsupported bridge command type.") }
            val runId = json.optString("runId").trim().take(96)
            val buildId = json.optString("buildId").trim().take(96)
            val imagePath = json.optString("imagePath").trim().take(1_024)
            if (runId.isNotBlank()) require(idRegex.matches(runId)) { "Invalid bridge runId." }
            if (buildId.isNotBlank()) require(idRegex.matches(buildId)) { "Invalid bridge buildId." }
            if (imagePath.isNotBlank()) {
                require(relayImagePathRegex.matches(imagePath) && !imagePath.contains("..")) {
                    "Invalid bridge imagePath."
                }
            }
            return BridgeRequest(
                id = id,
                type = type,
                command = json.optString("command").take(16_384),
                packageName = json.optString("packageName").take(512),
                service = json.optString("service").take(512),
                title = json.optString("title").take(256),
                message = json.optString("message").take(8_192),
                reason = json.optString("reason").take(2_048),
                selector = json.optString("selector").take(2_048),
                value = json.optString("value").take(8_192),
                imagePath = imagePath,
                x = json.optInt("x", -1),
                y = json.optInt("y", -1),
                endX = json.optInt("endX", -1),
                endY = json.optInt("endY", -1),
                durationMs = json.optInt("durationMs", 300).coerceIn(1, 10_000),
                keyCode = json.optInt("keyCode", -1),
                runId = runId,
                buildId = buildId,
                sequenceNumber = json.optLong("sequenceNumber", 0L).coerceAtLeast(0L),
                createdAtEpochMs = json.optLong("createdAtEpochMs", System.currentTimeMillis()),
                expiresAtEpochMs = json.optLong("expiresAtEpochMs", System.currentTimeMillis() + 10 * 60_000L),
                timeoutSeconds = json.optInt("timeoutSeconds", 20).coerceIn(1, 120),
                source = json.optString("source", "ChatGPT via Continuity").take(256),
            )
        }
    }
}

data class BridgeShellResult(
    val output: String,
    val exitCode: Int?,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

data class BridgeRawResult(
    val bytes: ByteArray,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

data class BridgeArtifact(
    val path: String,
    val mimeType: String,
    val sha256: String,
    val bytes: Long,
    val width: Int = 0,
    val height: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("mimeType", mimeType)
        .put("sha256", sha256)
        .put("bytes", bytes)
        .put("width", width)
        .put("height", height)

    companion object {
        fun fromJson(json: JSONObject): BridgeArtifact = BridgeArtifact(
            path = json.optString("path"),
            mimeType = json.optString("mimeType"),
            sha256 = json.optString("sha256"),
            bytes = json.optLong("bytes"),
            width = json.optInt("width"),
            height = json.optInt("height"),
        )
    }
}

data class BridgeResult(
    val requestId: String,
    val status: BridgeResultStatus,
    val risk: BridgeRisk,
    val detail: String,
    val output: String = "",
    val exitCode: Int? = null,
    val durationMs: Long = 0L,
    val completedAtEpochMs: Long = System.currentTimeMillis(),
    val truncated: Boolean = false,
    val foregroundPackage: String = "",
    val uiFingerprint: String = "",
    val artifacts: List<BridgeArtifact> = emptyList(),
) {
    fun toJson(deviceId: String): JSONObject = JSONObject()
        .put("schema", 4)
        .put("requestId", requestId)
        .put("deviceId", deviceId)
        .put("status", status.name)
        .put("risk", risk.name)
        .put("detail", detail)
        .put("output", output)
        .put("exitCode", exitCode ?: JSONObject.NULL)
        .put("durationMs", durationMs)
        .put("completedAtEpochMs", completedAtEpochMs)
        .put("truncated", truncated)
        .put("foregroundPackage", foregroundPackage)
        .put("uiFingerprint", uiFingerprint)
        .put("artifacts", JSONArray().apply { artifacts.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(json: JSONObject): BridgeResult {
            val artifactArray = json.optJSONArray("artifacts") ?: JSONArray()
            val artifacts = buildList {
                for (index in 0 until artifactArray.length()) {
                    artifactArray.optJSONObject(index)?.let { add(BridgeArtifact.fromJson(it)) }
                }
            }
            return BridgeResult(
                requestId = json.getString("requestId"),
                status = BridgeResultStatus.valueOf(json.getString("status")),
                risk = BridgeRisk.valueOf(json.getString("risk")),
                detail = json.optString("detail"),
                output = json.optString("output"),
                exitCode = if (json.isNull("exitCode")) null else json.optInt("exitCode"),
                durationMs = json.optLong("durationMs"),
                completedAtEpochMs = json.optLong("completedAtEpochMs", System.currentTimeMillis()),
                truncated = json.optBoolean("truncated", false),
                foregroundPackage = json.optString("foregroundPackage"),
                uiFingerprint = json.optString("uiFingerprint"),
                artifacts = artifacts,
            )
        }
    }
}

data class BridgePendingRequest(
    val request: BridgeRequest,
    val risk: BridgeRisk,
    val inboxPath: String,
    val inboxSha: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("request", request.toJson())
        .put("risk", risk.name)
        .put("inboxPath", inboxPath)
        .put("inboxSha", inboxSha)

    companion object {
        fun fromJson(json: JSONObject): BridgePendingRequest = BridgePendingRequest(
            request = BridgeRequest.fromJson(json.getJSONObject("request")),
            risk = BridgeRisk.valueOf(json.getString("risk")),
            inboxPath = json.getString("inboxPath"),
            inboxSha = json.getString("inboxSha"),
        )
    }
}

data class BridgeCompletedEnvelope(
    val request: BridgeRequest,
    val inboxPath: String,
    val inboxSha: String,
    val result: BridgeResult,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("request", request.toJson())
        .put("inboxPath", inboxPath)
        .put("inboxSha", inboxSha)
        .put("result", result.toJson("local-journal"))

    companion object {
        fun fromJson(json: JSONObject): BridgeCompletedEnvelope = BridgeCompletedEnvelope(
            request = BridgeRequest.fromJson(json.getJSONObject("request")),
            inboxPath = json.getString("inboxPath"),
            inboxSha = json.getString("inboxSha"),
            result = BridgeResult.fromJson(json.getJSONObject("result")),
        )
    }
}
