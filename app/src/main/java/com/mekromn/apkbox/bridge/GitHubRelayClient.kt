package com.mekromn.apkbox.bridge

import android.os.Build
import android.util.Base64
import com.mekromn.apkbox.agent.AgentCheckpoint
import com.mekromn.apkbox.agent.AutonomousPlan
import com.mekromn.apkbox.agent.BuildCandidate
import com.mekromn.apkbox.agent.BuildRunCheckpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RelayInboxItem(
    val path: String,
    val sha: String,
    val request: BridgeRequest,
)

class GitHubRelayClient {
    companion object {
        private const val API_ROOT = "https://api.github.com"
        private const val API_VERSION = "2022-11-28"
        private const val MAX_RESPONSE_CHARS = 3_500_000
        private const val MAX_ARTIFACT_BYTES = 8 * 1024 * 1024
        private val runIdRegex = Regex("[A-Za-z0-9._-]{1,96}")
    }

    suspend fun test(config: BridgeConfig, token: String): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Enter a fine-grained GitHub token for the private Continuity relay." }
        val response = request(
            token = token,
            method = "GET",
            path = "/repos/${encode(config.repoOwner)}/${encode(config.repoName)}",
        )
        val json = JSONObject(response.body)
        "Connected to ${json.optString("full_name", "${config.repoOwner}/${config.repoName}")}"
    }

    suspend fun fetchInbox(config: BridgeConfig, token: String): List<RelayInboxItem> = withContext(Dispatchers.IO) {
        val base = "bridge/devices/${config.deviceId}/inbox"
        val listing = requestOrNull(
            token,
            "GET",
            "/repos/${encode(config.repoOwner)}/${encode(config.repoName)}/contents/${encodePath(base)}",
        ) ?: return@withContext emptyList()
        val array = runCatching { JSONArray(listing.body) }.getOrElse { return@withContext emptyList() }
        val items = ArrayList<RelayInboxItem>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("type") != "file") continue
            val path = item.optString("path")
            if (!path.endsWith(".json", ignoreCase = true)) continue
            val sha = item.optString("sha")
            val file = getTextFile(config, token, path) ?: continue
            runCatching { BridgeRequest.fromJson(JSONObject(file.text)) }
                .onSuccess { request -> items += RelayInboxItem(path, sha.ifBlank { file.sha }, request) }
        }
        items.sortedBy { it.request.createdAtEpochMs }
    }

    suspend fun fetchAgentPlan(
        config: BridgeConfig,
        token: String,
        runId: String,
    ): AutonomousPlan = withContext(Dispatchers.IO) {
        val safeRun = runId.trim()
        require(runIdRegex.matches(safeRun)) { "Invalid autonomous run ID." }
        val path = "bridge/devices/${config.deviceId}/plans/$safeRun.json"
        val file = getTextFile(config, token, path) ?: error("Autonomous plan is missing: $path")
        val plan = AutonomousPlan.fromJson(JSONObject(file.text))
        require(plan.runId == safeRun) { "Plan run ID does not match AGENT_START request." }
        plan
    }

    suspend fun fetchBuildCandidate(
        config: BridgeConfig,
        token: String,
        buildId: String,
    ): BuildCandidate = withContext(Dispatchers.IO) {
        val safeBuild = buildId.trim()
        require(runIdRegex.matches(safeBuild)) { "Invalid build candidate ID." }
        val path = "bridge/devices/${config.deviceId}/builds/$safeBuild.json"
        val file = getTextFile(config, token, path) ?: error("Build candidate is missing: $path")
        val candidate = BuildCandidate.fromJson(JSONObject(file.text))
        require(candidate.buildId == safeBuild) { "Build manifest ID does not match the requested build." }
        candidate
    }

    /** Fetch a picture only from this device's private Continuity relay subtree. */
    suspend fun fetchMessageImage(
        config: BridgeConfig,
        token: String,
        repositoryPath: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val safePath = repositoryPath.trim()
        val allowedPrefixes = listOf(
            "bridge/devices/${config.deviceId}/artifacts/",
            "bridge/devices/${config.deviceId}/message-assets/",
        )
        require(safePath.isNotBlank() && !safePath.contains("..") && allowedPrefixes.any(safePath::startsWith)) {
            "Picture imagePath must be inside this device's Continuity artifacts or message-assets directory."
        }
        requestBytes(
            token = token,
            path = "/repos/${encode(config.repoOwner)}/${encode(config.repoName)}/contents/${encodePath(safePath)}",
            maxBytes = MAX_ARTIFACT_BYTES,
        )
    }

    suspend fun writeAgentCheckpoint(
        config: BridgeConfig,
        token: String,
        checkpoint: AgentCheckpoint,
    ): String = withContext(Dispatchers.IO) {
        require(runIdRegex.matches(checkpoint.runId)) { "Invalid autonomous checkpoint run ID." }
        val path = "bridge/devices/${config.deviceId}/runs/${checkpoint.runId}/checkpoint.json"
        val json = checkpoint.toJson()
            .put("deviceId", config.deviceId)
            .put("publishedAtEpochMs", System.currentTimeMillis())
        putJson(config, token, path, json, "APKbox agent checkpoint ${checkpoint.runId}")
        path
    }

    suspend fun writeBuildCheckpoint(
        config: BridgeConfig,
        token: String,
        checkpoint: BuildRunCheckpoint,
    ): String = withContext(Dispatchers.IO) {
        require(runIdRegex.matches(checkpoint.runId)) { "Invalid build checkpoint run ID." }
        val path = "bridge/devices/${config.deviceId}/build-runs/${checkpoint.runId}/checkpoint.json"
        val json = checkpoint.toJson()
            .put("deviceId", config.deviceId)
            .put("publishedAtEpochMs", System.currentTimeMillis())
        putJson(config, token, path, json, "APKbox build checkpoint ${checkpoint.runId}")
        path
    }

    suspend fun writeResult(config: BridgeConfig, token: String, result: BridgeResult) = withContext(Dispatchers.IO) {
        val path = "bridge/devices/${config.deviceId}/outbox/${result.requestId}.json"
        putJson(config, token, path, result.toJson(config.deviceId), "APKbox bridge result ${result.requestId}")
    }

    suspend fun writeArtifact(
        config: BridgeConfig,
        token: String,
        requestId: String,
        extension: String,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "Artifact is empty." }
        require(bytes.size <= MAX_ARTIFACT_BYTES) { "Artifact exceeds the $MAX_ARTIFACT_BYTES-byte relay limit." }
        val safeId = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeExtension = extension.lowercase().replace(Regex("[^a-z0-9]"), "").take(8).ifBlank { "bin" }
        val path = "bridge/devices/${config.deviceId}/artifacts/$safeId.$safeExtension"
        putBytes(config, token, path, bytes, "APKbox bridge artifact $safeId")
        path
    }

    suspend fun writeAwaitingApproval(
        config: BridgeConfig,
        token: String,
        request: BridgeRequest,
        risk: BridgeRisk,
    ) = withContext(Dispatchers.IO) {
        val path = "bridge/devices/${config.deviceId}/outbox/${request.id}.json"
        val json = BridgeResult(
            requestId = request.id,
            status = BridgeResultStatus.AWAITING_APPROVAL,
            risk = risk,
            detail = "Waiting for approval on the device.",
        ).toJson(config.deviceId)
        putJson(config, token, path, json, "APKbox bridge awaiting approval ${request.id}")
    }

    suspend fun heartbeat(
        config: BridgeConfig,
        token: String,
        privilegedStatus: PrivilegedBridgeStatus,
    ) = withContext(Dispatchers.IO) {
        val adbStatus = privilegedStatus.adb
        val json = JSONObject()
            .put("schema", 7)
            .put("deviceId", config.deviceId)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("bridgeEnabled", config.enabled)
            .put("adbPaired", config.paired)
            .put("adbConnected", adbStatus.connected)
            .put("adbHealPhase", adbStatus.healPhase.name)
            .put("adbLastConnectedAtEpochMs", adbStatus.lastConnectedAtEpochMs)
            .put("adbLastVerifiedAtEpochMs", adbStatus.lastVerifiedAtEpochMs)
            .put("adbConsecutiveFailures", adbStatus.consecutiveFailures)
            .put("adbNextRetryAtEpochMs", adbStatus.nextRetryAtEpochMs)
            .put("adbWifiAvailable", adbStatus.wifiAvailable)
            .put("adbUserActionRequired", adbStatus.userActionRequired)
            .put("adbFailureKind", adbStatus.lastFailureKind.name)
            .put("adbLastError", adbStatus.lastError.take(500))
            .put("trustedUntilEpochMs", config.trustedUntilEpochMs)
            .put("allowInformational", config.allowInformational)
            .put("allowPopups", config.allowPopups)
            .put("messagePresentation", config.messagePresentation.name)
            .put("approvalPresentation", config.approvalPresentation.name)
            .put("lastSeenEpochMs", System.currentTimeMillis())
            // Backward-compatible flat names. Current agents should prefer BridgeCapabilityCatalog.
            .put("capabilities", JSONArray(listOf(
                "shell", "logcat", "app_logcat", "dumpsys", "launch", "toast", "notification", "popup",
                "message_small_popup", "message_always_on_top", "message_full_window", "message_heads_up", "picture_message",
                "apk_install_url", "job_list", "job_status", "job_cancel", "job_resume",
                "project_list", "project_get", "apk_list", "apk_search", "apk_inspect", "apk_pull",
                "package_state", "installed_apps", "device_state",
                "ui_snapshot", "screenshot", "ui_tap", "ui_find_tap", "ui_swipe", "ui_text", "ui_key", "ui_wait",
                "agent_start", "agent_resume", "agent_status", "build_start", "build_status",
                "agent_checkpoint", "agent_plan", "build_candidate", "build_checkpoint",
                "universal_durable_jobs", "content_addressed_artifact_cache", "fastest_exact_source_resolution",
                "exact_apk_inventory_inspection", "exact_apk_chunked_pull",
                "unattended_verified_install", "privileged_transport_shizuku_sui_or_wireless_adb",
                "wireless_adb_auto_heal", "wireless_adb_persistent_self_start"
            )))
        BridgeCapabilityCatalog.enrich(json, config, privilegedStatus)
        val path = "bridge/devices/${config.deviceId}/state.json"
        putJson(config, token, path, json, "APKbox bridge heartbeat")
    }

    suspend fun deleteInbox(config: BridgeConfig, token: String, item: RelayInboxItem): Unit =
        deleteInbox(config, token, item.path, item.sha, item.request.id)

    suspend fun deleteInbox(
        config: BridgeConfig,
        token: String,
        path: String,
        sha: String,
        requestId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("message", "APKbox consumed bridge request $requestId")
            .put("sha", sha)
            .toString()
        requestOrNull(
            token = token,
            method = "DELETE",
            path = "/repos/${encode(config.repoOwner)}/${encode(config.repoName)}/contents/${encodePath(path)}",
            body = body,
        )
        Unit
    }

    private data class FilePayload(val sha: String, val text: String)

    private fun getTextFile(config: BridgeConfig, token: String, path: String): FilePayload? {
        val response = requestOrNull(
            token,
            "GET",
            "/repos/${encode(config.repoOwner)}/${encode(config.repoName)}/contents/${encodePath(path)}",
        ) ?: return null
        val json = JSONObject(response.body)
        val encoded = json.optString("content").replace("\n", "")
        if (encoded.isBlank()) return null
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        return FilePayload(json.optString("sha"), decoded)
    }

    private fun getFileSha(config: BridgeConfig, token: String, path: String): String? {
        val response = requestOrNull(
            token,
            "GET",
            "/repos/${encode(config.repoOwner)}/${encode(config.repoName)}/contents/${encodePath(path)}",
        ) ?: return null
        return JSONObject(response.body).optString("sha").takeIf { it.isNotBlank() }
    }

    private fun putJson(
        config: BridgeConfig,
        token: String,
        path: String,
        json: JSONObject,
        commitMessage: String,
    ) = putBytes(
        config = config,
        token = token,
        path = path,
        bytes = json.toString().toByteArray(Charsets.UTF_8),
        commitMessage = commitMessage,
    )

    private fun putBytes(
        config: BridgeConfig,
        token: String,
        path: String,
        bytes: ByteArray,
        commitMessage: String,
    ) {
        val existingSha = getFileSha(config, token, path)
        val body = JSONObject()
            .put("message", commitMessage.take(180))
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (!existingSha.isNullOrBlank()) body.put("sha", existingSha)
        request(
            token = token,
            method = "PUT",
            path = "/repos/${encode(config.repoOwner)}/${encode(config.repoName)}/contents/${encodePath(path)}",
            body = body.toString(),
        )
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun requestOrNull(token: String, method: String, path: String, body: String? = null): HttpResponse? =
        try {
            request(token, method, path, body)
        } catch (failure: RelayHttpException) {
            if (failure.code == HttpURLConnection.HTTP_NOT_FOUND) null else throw failure
        }

    private fun request(token: String, method: String, path: String, body: String? = null): HttpResponse {
        require(token.isNotBlank()) { "Relay token is missing." }
        val connection = URL(API_ROOT + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            connection.setRequestProperty("User-Agent", "APKbox-Remote-Bridge")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    val builder = StringBuilder()
                    val buffer = CharArray(16 * 1024)
                    while (builder.length < MAX_RESPONSE_CHARS) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        builder.append(buffer, 0, minOf(count, MAX_RESPONSE_CHARS - builder.length))
                    }
                    builder.toString()
                }
            }.orEmpty()
            if (code !in 200..299) {
                val apiMessage = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw RelayHttpException(code, apiMessage?.takeIf { it.isNotBlank() } ?: "GitHub relay HTTP $code")
            }
            return HttpResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBytes(token: String, path: String, maxBytes: Int): ByteArray {
        require(token.isNotBlank()) { "Relay token is missing." }
        val connection = URL(API_ROOT + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github.raw+json")
            connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            connection.setRequestProperty("User-Agent", "APKbox-Remote-Bridge")
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(8_000) }.orEmpty()
                val apiMessage = runCatching { JSONObject(error).optString("message") }.getOrNull()
                throw RelayHttpException(code, apiMessage?.takeIf { it.isNotBlank() } ?: "GitHub relay HTTP $code")
            }
            val declared = connection.contentLengthLong
            require(declared < 0L || declared <= maxBytes.toLong()) { "Picture message exceeds the $maxBytes-byte relay limit." }
            val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
            connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "Picture message exceeds the $maxBytes-byte relay limit." }
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            require(bytes.isNotEmpty()) { "Picture message image is empty." }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }
}

private class RelayHttpException(val code: Int, message: String) : java.io.IOException(message)
