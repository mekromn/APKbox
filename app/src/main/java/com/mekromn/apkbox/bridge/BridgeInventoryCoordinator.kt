package com.mekromn.apkbox.bridge

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.jobs.DurableJobEngine
import org.json.JSONArray
import org.json.JSONObject

/** Read-only structured discovery surface so agents do not guess project/package/device state. */
class BridgeInventoryCoordinator(
    context: Context,
    private val library: LibraryStore,
    private val privileged: PrivilegedBridgeManager,
    private val jobs: DurableJobEngine,
) {
    private val appContext = context.applicationContext
    private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val packageLineRegex = Regex("^package:(.+?)=([A-Za-z0-9_.]+)(?:\\s+uid:(\\d+))?.*$")

    suspend fun execute(request: BridgeRequest, risk: BridgeRisk): BridgeResult {
        val started = System.currentTimeMillis()
        val output = when (request.type) {
            BridgeCommandType.PROJECT_LIST -> projectList(request.limit)
            BridgeCommandType.PROJECT_GET -> projectGet(request.projectId)
            BridgeCommandType.APK_LIST -> apkList(request.projectId, request.packageName, request.limit)
            BridgeCommandType.APK_SEARCH -> apkSearch(request.query, request.limit)
            BridgeCommandType.PACKAGE_STATE -> packageState(request.packageName)
            BridgeCommandType.INSTALLED_APPS -> installedApps(request.query, request.includeSystemApps, request.limit)
            BridgeCommandType.DEVICE_STATE -> deviceState()
            BridgeCommandType.JOB_LIST -> jobs.listJson(request.limit)
            BridgeCommandType.JOB_STATUS -> jobs.statusJson(request.jobId)
            else -> error("${request.type} is not an inventory command.")
        }
        return BridgeResult(
            requestId = request.id,
            status = BridgeResultStatus.SUCCESS,
            risk = risk,
            detail = "Structured ${request.type.name.lowercase().replace('_', ' ')} completed.",
            output = output.toString(2),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    private fun projectList(limit: Int): JSONObject {
        val records = library.records.value
        val projects = library.projects.value.take(limit.coerceIn(1, 500))
        return JSONObject()
            .put("schema", 1)
            .put("count", projects.size)
            .put("projects", JSONArray().apply {
                projects.forEach { project ->
                    val builds = records.filter { it.projectId == project.id }
                    val base = builds.firstOrNull { it.isBase }
                    put(JSONObject()
                        .put("id", project.id)
                        .put("name", project.name)
                        .put("packageName", project.packageName)
                        .put("createdAtEpochMs", project.createdAtEpochMs)
                        .put("buildCount", builds.size)
                        .put("baseRecordId", base?.id.orEmpty())
                        .put("baseSha256", base?.sha256.orEmpty())
                        .put("latestAddedAtEpochMs", builds.maxOfOrNull { it.addedAtEpochMs } ?: 0L))
                }
            })
    }

    private fun projectGet(projectId: String): JSONObject {
        val id = projectId.trim()
        require(id.isNotBlank()) { "PROJECT_GET requires projectId." }
        val project = library.projects.value.firstOrNull { it.id == id } ?: error("APKbox project '$id' was not found.")
        val records = library.records.value.filter { it.projectId == id }
        return JSONObject()
            .put("schema", 1)
            .put("project", JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("packageName", project.packageName)
                .put("createdAtEpochMs", project.createdAtEpochMs))
            .put("records", JSONArray().apply { records.forEach { put(recordJson(it)) } })
    }

    private fun apkList(projectId: String, packageName: String, limit: Int): JSONObject {
        val id = projectId.trim()
        val pkg = packageName.trim()
        if (pkg.isNotBlank()) require(packageRegex.matches(pkg)) { "Invalid packageName." }
        val matches = library.records.value
            .asSequence()
            .filter { id.isBlank() || it.projectId == id }
            .filter { pkg.isBlank() || it.packageName == pkg }
            .take(limit.coerceIn(1, 500))
            .toList()
        return JSONObject()
            .put("schema", 1)
            .put("count", matches.size)
            .put("records", JSONArray().apply { matches.forEach { put(recordJson(it)) } })
    }

    private fun apkSearch(query: String, limit: Int): JSONObject {
        val clean = query.trim()
        require(clean.isNotBlank()) { "APK_SEARCH requires query." }
        val needle = clean.lowercase()
        val matches = library.records.value.filter { record ->
            listOf(
                record.title,
                record.displayName,
                record.label,
                record.packageName,
                record.versionName,
                record.versionCode.toString(),
                record.sha256,
                record.signingCertSha256.orEmpty(),
                record.description,
                record.notes,
            ).any { needle in it.lowercase() }
        }.take(limit.coerceIn(1, 500))
        return JSONObject()
            .put("schema", 1)
            .put("query", clean)
            .put("count", matches.size)
            .put("records", JSONArray().apply { matches.forEach { put(recordJson(it)) } })
    }

    private suspend fun packageState(packageName: String): JSONObject {
        val pkg = packageName.trim()
        require(packageRegex.matches(pkg)) { "PACKAGE_STATE requires a valid packageName." }
        val installed = ApkInspector.inspectInstalled(appContext, pkg)
        val installedSha = if (installed != null) installedBaseSha(pkg) else ""
        val matchingRecords = library.records.value.filter { it.packageName == pkg }
        val matchingProjects = library.projects.value.filter { it.packageName == pkg }
        return JSONObject()
            .put("schema", 1)
            .put("packageName", pkg)
            .put("installed", installed != null)
            .put("installedVersionName", installed?.versionName.orEmpty())
            .put("installedVersionCode", installed?.versionCode ?: 0L)
            .put("installedSigningCertSha256", installed?.signingCertSha256.orEmpty())
            .put("installedBaseApkSha256", installedSha)
            .put("exactStoredMatchRecordId", matchingRecords.firstOrNull { it.sha256.equals(installedSha, true) }?.id.orEmpty())
            .put("projectIds", JSONArray().apply { matchingProjects.forEach { put(it.id) } })
            .put("storedBuildCount", matchingRecords.size)
            .put("storedRecords", JSONArray().apply { matchingRecords.take(100).forEach { put(recordJson(it)) } })
    }

    private suspend fun installedApps(query: String, includeSystem: Boolean, limit: Int): JSONObject {
        check(privileged.ensureReady()) { "INSTALLED_APPS requires a ready Shizuku/Sui or Wireless ADB transport." }
        val command = if (includeSystem) "pm list packages -f -U" else "pm list packages -3 -f -U"
        val result = privileged.execute(command, 30)
        check(!result.timedOut && (result.exitCode == null || result.exitCode == 0)) {
            "Package inventory failed: ${result.output.take(1_000)}"
        }
        val needle = query.trim().lowercase()
        val parsed = result.output.lineSequence().mapNotNull { line ->
            val match = packageLineRegex.matchEntire(line.trim()) ?: return@mapNotNull null
            val path = match.groupValues[1]
            val pkg = match.groupValues[2]
            val uid = match.groupValues[3].toLongOrNull() ?: -1L
            if (needle.isNotBlank() && needle !in pkg.lowercase() && needle !in path.lowercase()) return@mapNotNull null
            JSONObject()
                .put("packageName", pkg)
                .put("baseOrPackagePath", path)
                .put("uid", uid)
                .put("storedInApkbox", library.records.value.any { it.packageName == pkg })
        }.take(limit.coerceIn(1, 500)).toList()
        return JSONObject()
            .put("schema", 1)
            .put("includeSystemApps", includeSystem)
            .put("query", query.trim())
            .put("count", parsed.size)
            .put("packages", JSONArray().apply { parsed.forEach(::put) })
    }

    private suspend fun deviceState(): JSONObject {
        privileged.refreshStatus()
        val status = privileged.status.value
        val storage = StatFs(appContext.filesDir.absolutePath)
        val currentUser = runCatching { privileged.execute("am get-current-user", 5).output.trim().toIntOrNull() }.getOrNull()
        val wmSize = runCatching { privileged.execute("wm size", 5).output.trim() }.getOrDefault("")
        val wmDensity = runCatching { privileged.execute("wm density", 5).output.trim() }.getOrDefault("")
        return JSONObject()
            .put("schema", 1)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("androidApi", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("buildFingerprint", Build.FINGERPRINT)
            .put("currentAndroidUserId", currentUser ?: JSONObject.NULL)
            .put("wmSize", wmSize)
            .put("wmDensity", wmDensity)
            .put("apkboxProjectCount", library.projects.value.size)
            .put("apkboxRecordCount", library.records.value.size)
            .put("durableJobCount", jobs.list(500).size)
            .put("filesFreeBytes", storage.availableBytes)
            .put("filesTotalBytes", storage.totalBytes)
            .put("privilegedTransport", JSONObject()
                .put("ready", status.ready)
                .put("active", status.activeLabel)
                .put("activeKind", status.activeTransport.name)
                .put("persistentWirelessControl", status.persistentWirelessControl)
                .put("shizukuUsable", status.shizuku.usable)
                .put("shizukuRoot", status.shizuku.root)
                .put("wirelessAdbConnected", status.adb.connected)
                .put("wirelessAdbHealPhase", status.adb.healPhase.name))
    }

    private suspend fun installedBaseSha(packageName: String): String {
        if (!runCatching { privileged.ensureReady() }.getOrDefault(false)) return ""
        val pathResult = runCatching { privileged.execute("pm path $packageName", 10) }.getOrNull() ?: return ""
        val path = pathResult.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.endsWith("/base.apk") }
            ?.removePrefix("package:") ?: return ""
        if (!path.matches(Regex("[/A-Za-z0-9._=:+-]+"))) return ""
        val hash = runCatching { privileged.execute("sha256sum $path", 20) }.getOrNull() ?: return ""
        return Regex("(?i)^[0-9a-f]{64}").find(hash.output.trim())?.value?.lowercase().orEmpty()
    }

    private fun recordJson(record: com.mekromn.apkbox.model.ApkRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("projectId", record.projectId)
        .put("title", record.title)
        .put("displayName", record.displayName)
        .put("label", record.label)
        .put("packageName", record.packageName)
        .put("versionName", record.versionName)
        .put("versionCode", record.versionCode)
        .put("sizeBytes", record.sizeBytes)
        .put("sha256", record.sha256)
        .put("signingCertSha256", record.signingCertSha256.orEmpty())
        .put("addedAtEpochMs", record.addedAtEpochMs)
        .put("isBase", record.isBase)
        .put("starred", record.starred)
        .put("description", record.description)
        .put("notes", record.notes)
}
