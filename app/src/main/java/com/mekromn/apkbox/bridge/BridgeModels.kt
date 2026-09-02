package com.mekromn.apkbox.bridge

import org.json.JSONObject

enum class BridgeCommandType {
    SHELL,
    LOGCAT,
    APP_LOGCAT,
    DUMPSYS,
    LAUNCH,
    TOAST,
    NOTIFICATION,
    POPUP,
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
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long = System.currentTimeMillis() + 10 * 60_000L,
    val timeoutSeconds: Int = 20,
    val source: String = "ChatGPT via Continuity",
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAtEpochMs in 1 until now

    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("id", id)
        .put("type", type.name)
        .put("command", command)
        .put("packageName", packageName)
        .put("service", service)
        .put("title", title)
        .put("message", message)
        .put("reason", reason)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("expiresAtEpochMs", expiresAtEpochMs)
        .put("timeoutSeconds", timeoutSeconds)
        .put("source", source)

    companion object {
        fun fromJson(json: JSONObject): BridgeRequest {
            val id = json.optString("id").trim()
            require(id.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "Invalid bridge request id." }
            val type = runCatching { BridgeCommandType.valueOf(json.getString("type").uppercase()) }
                .getOrElse { error("Unsupported bridge command type.") }
            return BridgeRequest(
                id = id,
                type = type,
                command = json.optString("command").take(16_384),
                packageName = json.optString("packageName").take(512),
                service = json.optString("service").take(512),
                title = json.optString("title").take(256),
                message = json.optString("message").take(8_192),
                reason = json.optString("reason").take(2_048),
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
) {
    fun toJson(deviceId: String): JSONObject = JSONObject()
        .put("schema", 1)
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

    companion object {
        fun fromJson(json: JSONObject): BridgeResult = BridgeResult(
            requestId = json.getString("requestId"),
            status = BridgeResultStatus.valueOf(json.getString("status")),
            risk = BridgeRisk.valueOf(json.getString("risk")),
            detail = json.optString("detail"),
            output = json.optString("output"),
            exitCode = if (json.isNull("exitCode")) null else json.optInt("exitCode"),
            durationMs = json.optLong("durationMs"),
            completedAtEpochMs = json.optLong("completedAtEpochMs", System.currentTimeMillis()),
            truncated = json.optBoolean("truncated", false),
        )
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
