package com.mekromn.apkbox.agent

import org.json.JSONObject

enum class BuildSourceFormat {
    APK,
    ZIP_APK,
}

enum class BuildRunState {
    CREATED,
    DOWNLOADING,
    VERIFYING,
    ARCHIVING,
    INSTALLING,
    LAUNCHING,
    TESTING,
    PASSED,
    FAILED,
    BLOCKED_SIGNATURE_MISMATCH,
    BLOCKED_PROJECT_AMBIGUOUS,
    BLOCKED_AUTH_REQUIRED,
}

data class BuildCandidate(
    val buildId: String,
    val runId: String,
    val targetPackage: String,
    val downloadUrl: String,
    val expectedApkSha256: String,
    val sourceFormat: BuildSourceFormat = BuildSourceFormat.APK,
    val apkEntryName: String = "",
    val projectId: String = "",
    val displayName: String = "",
    val commitSha: String = "",
    val workflowRunId: Long = 0L,
    val artifactId: Long = 0L,
    val requiresBuildToken: Boolean = false,
    val allowDowngrade: Boolean = false,
    val autoInstall: Boolean = true,
    val autoLaunch: Boolean = true,
    val planRunId: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("buildId", buildId)
        .put("runId", runId)
        .put("targetPackage", targetPackage)
        .put("downloadUrl", downloadUrl)
        .put("expectedApkSha256", expectedApkSha256)
        .put("sourceFormat", sourceFormat.name)
        .put("apkEntryName", apkEntryName)
        .put("projectId", projectId)
        .put("displayName", displayName)
        .put("commitSha", commitSha)
        .put("workflowRunId", workflowRunId)
        .put("artifactId", artifactId)
        .put("requiresBuildToken", requiresBuildToken)
        .put("allowDowngrade", allowDowngrade)
        .put("autoInstall", autoInstall)
        .put("autoLaunch", autoLaunch)
        .put("planRunId", planRunId)

    companion object {
        private val idRegex = Regex("[A-Za-z0-9._-]{1,96}")
        private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val shaRegex = Regex("[0-9a-fA-F]{64}")

        fun fromJson(json: JSONObject): BuildCandidate {
            val buildId = json.optString("buildId").trim()
            require(idRegex.matches(buildId)) { "Invalid build ID." }
            val runId = json.optString("runId", buildId).trim()
            require(idRegex.matches(runId)) { "Invalid build run ID." }
            val targetPackage = json.optString("targetPackage").trim()
            require(packageRegex.matches(targetPackage)) { "Invalid build target package." }
            val downloadUrl = json.optString("downloadUrl").trim()
            require(downloadUrl.startsWith("https://", ignoreCase = true)) { "Build URL must use HTTPS." }
            val sha = json.optString("expectedApkSha256").trim().lowercase()
            require(shaRegex.matches(sha)) { "Build manifest must contain an exact APK SHA-256." }
            val sourceFormat = runCatching {
                BuildSourceFormat.valueOf(json.optString("sourceFormat", "APK").uppercase())
            }.getOrElse { error("Unsupported build source format.") }
            val entry = json.optString("apkEntryName").trim().take(512)
            if (sourceFormat == BuildSourceFormat.ZIP_APK && entry.isNotBlank()) {
                require(!entry.startsWith('/') && !entry.contains("..")) { "Unsafe APK ZIP entry name." }
            }
            return BuildCandidate(
                buildId = buildId,
                runId = runId,
                targetPackage = targetPackage,
                downloadUrl = downloadUrl,
                expectedApkSha256 = sha,
                sourceFormat = sourceFormat,
                apkEntryName = entry,
                projectId = json.optString("projectId").trim().take(128),
                displayName = json.optString("displayName").trim().take(256),
                commitSha = json.optString("commitSha").trim().take(80),
                workflowRunId = json.optLong("workflowRunId", 0L).coerceAtLeast(0L),
                artifactId = json.optLong("artifactId", 0L).coerceAtLeast(0L),
                requiresBuildToken = json.optBoolean("requiresBuildToken", false),
                allowDowngrade = json.optBoolean("allowDowngrade", false),
                autoInstall = json.optBoolean("autoInstall", true),
                autoLaunch = json.optBoolean("autoLaunch", true),
                planRunId = json.optString("planRunId").trim().take(96),
            )
        }
    }
}

data class BuildRunCheckpoint(
    val buildId: String,
    val runId: String,
    val state: BuildRunState,
    val targetPackage: String,
    val projectId: String = "",
    val apkRecordId: String = "",
    val apkPath: String = "",
    val apkSha256: String = "",
    val downloadedBytes: Long = 0L,
    val expectedBytes: Long = 0L,
    val commitSha: String = "",
    val workflowRunId: Long = 0L,
    val artifactId: Long = 0L,
    val detail: String = "",
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("buildId", buildId)
        .put("runId", runId)
        .put("state", state.name)
        .put("targetPackage", targetPackage)
        .put("projectId", projectId)
        .put("apkRecordId", apkRecordId)
        .put("apkPath", apkPath)
        .put("apkSha256", apkSha256)
        .put("downloadedBytes", downloadedBytes)
        .put("expectedBytes", expectedBytes)
        .put("commitSha", commitSha)
        .put("workflowRunId", workflowRunId)
        .put("artifactId", artifactId)
        .put("detail", detail)
        .put("startedAtEpochMs", startedAtEpochMs)
        .put("updatedAtEpochMs", updatedAtEpochMs)

    companion object {
        fun fromJson(json: JSONObject): BuildRunCheckpoint = BuildRunCheckpoint(
            buildId = json.getString("buildId"),
            runId = json.optString("runId", json.getString("buildId")),
            state = BuildRunState.valueOf(json.getString("state")),
            targetPackage = json.getString("targetPackage"),
            projectId = json.optString("projectId"),
            apkRecordId = json.optString("apkRecordId"),
            apkPath = json.optString("apkPath"),
            apkSha256 = json.optString("apkSha256"),
            downloadedBytes = json.optLong("downloadedBytes"),
            expectedBytes = json.optLong("expectedBytes"),
            commitSha = json.optString("commitSha"),
            workflowRunId = json.optLong("workflowRunId"),
            artifactId = json.optLong("artifactId"),
            detail = json.optString("detail"),
            startedAtEpochMs = json.optLong("startedAtEpochMs", System.currentTimeMillis()),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs", System.currentTimeMillis()),
        )
    }
}
