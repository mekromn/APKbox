package com.mekromn.apkbox.bridge

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
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
            val file = getFile(config, token, path) ?: continue
            runCatching { BridgeRequest.fromJson(JSONObject(file.text)) }
                .onSuccess { request -> items += RelayInboxItem(path, sha.ifBlank { file.sha }, request) }
        }
        items.sortedBy { it.request.createdAtEpochMs }
    }

    suspend fun writeResult(config: BridgeConfig, token: String, result: BridgeResult) = withContext(Dispatchers.IO) {
        val path = "bridge/devices/${config.deviceId}/outbox/${result.requestId}.json"
        putJson(config, token, path, result.toJson(config.deviceId), "APKbox bridge result ${result.requestId}")
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
        adbStatus: AdbBridgeStatus,
    ) = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("schema", 1)
            .put("deviceId", config.deviceId)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("bridgeEnabled", config.enabled)
            .put("adbPaired", config.paired)
            .put("adbConnected", adbStatus.connected)
            .put("trustedUntilEpochMs", config.trustedUntilEpochMs)
            .put("allowInformational", config.allowInformational)
            .put("allowPopups", config.allowPopups)
            .put("lastSeenEpochMs", System.currentTimeMillis())
            .put("capabilities", JSONArray(listOf(
                "shell", "logcat", "app_logcat", "dumpsys", "launch", "toast", "notification", "popup"
            )))
        val path = "bridge/devices/${config.deviceId}/state.json"
        putJson(config, token, path, json, "APKbox bridge heartbeat")
    }

    suspend fun deleteInbox(config: BridgeConfig, token: String, item: RelayInboxItem) =
        deleteInbox(config, token, item.path, item.sha, item.request.id)

    suspend fun deleteInbox(
        config: BridgeConfig,
        token: String,
        path: String,
        sha: String,
        requestId: String,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("message", "APKbox consumed bridge request $requestId")
            .put("sha", sha)
            .toString()
        // 404 is success here: another successful retry may already have removed the inbox file.
        requestOrNull(
            token = token,
            method = "DELETE",
            path = "/repos/${encode(config.repoOwner)}/${encode(config.repoName)}/contents/${encodePath(path)}",
            body = body,
        )
    }

    private data class FilePayload(val sha: String, val text: String)

    private fun getFile(config: BridgeConfig, token: String, path: String): FilePayload? {
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

    private fun putJson(
        config: BridgeConfig,
        token: String,
        path: String,
        json: JSONObject,
        commitMessage: String,
    ) {
        val existing = getFile(config, token, path)
        val body = JSONObject()
            .put("message", commitMessage.take(180))
            .put("content", Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (existing?.sha?.isNotBlank() == true) body.put("sha", existing.sha)
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

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }
}

private class RelayHttpException(val code: Int, message: String) : java.io.IOException(message)
