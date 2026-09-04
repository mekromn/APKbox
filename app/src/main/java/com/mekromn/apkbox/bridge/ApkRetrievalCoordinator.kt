package com.mekromn.apkbox.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mekromn.apkbox.artifacts.ArtifactSourceResolver
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.jobs.DurableJobEngine
import com.mekromn.apkbox.jobs.DurableJobState
import com.mekromn.apkbox.jobs.DurableJobType
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Exact APK retrieval surface for agents. Inspection is metadata-first and avoids relay transfer;
 * pull is an explicit durable operation that publishes deterministic private Continuity chunks.
 * Both use ArtifactSourceResolver so APKbox vault reconstruction is only used when no faster exact
 * local source is available.
 */
class ApkRetrievalCoordinator(
    context: Context,
    private val library: LibraryStore,
    private val privileged: PrivilegedBridgeManager,
    private val jobs: DurableJobEngine,
    private val resolver: ArtifactSourceResolver,
    private val relay: GitHubRelayClient,
) {
    companion object {
        private const val PULL_CHUNK_BYTES = 7 * 1024 * 1024
        private const val MAX_COMPONENTS = 500
    }

    private val appContext = context.applicationContext

    suspend fun inspect(request: BridgeRequest, risk: BridgeRisk): BridgeResult {
        val started = System.currentTimeMillis()
        return runCatching {
            val record = requireRecord(request.apkRecordId)
            val resolved = resolver.resolveExact(
                expectedSha256 = record.sha256,
                packageName = record.packageName,
                preferredRecordId = record.id,
            ) ?: error("No exact source could be materialized for APKbox record ${record.id}.")
            val actualSha = sha256(resolved.file)
            check(actualSha.equals(record.sha256, true)) { "Resolved APK SHA differs from APKbox record." }

            val archive = ApkInspector.inspect(appContext, resolved.file)
            check(archive.packageName == record.packageName) { "Resolved APK package identity changed unexpectedly." }
            val output = inspectJson(record, resolved.file, resolved.sourceKind.name, resolved.sourceDetail, request.limit)
            BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.SUCCESS,
                risk = risk,
                detail = "Inspected exact APKbox record ${record.id} from ${resolved.sourceKind.name.lowercase().replace('_', ' ')} without exporting the full APK.",
                output = output.toString(2),
                durationMs = System.currentTimeMillis() - started,
            )
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

    suspend fun pull(
        request: BridgeRequest,
        risk: BridgeRisk,
        config: BridgeConfig,
        token: String,
    ): BridgeResult = runPull(
        resultRequestId = request.id,
        jobId = request.jobId.ifBlank { request.id },
        apkRecordId = request.apkRecordId,
        risk = risk,
        config = config,
        token = token,
        explicitResume = false,
    )

    suspend fun resumePull(
        request: BridgeRequest,
        risk: BridgeRisk,
        config: BridgeConfig,
        token: String,
    ): BridgeResult {
        val job = jobs.get(request.jobId) ?: return BridgeResult(
            requestId = request.id,
            status = BridgeResultStatus.INVALID,
            risk = risk,
            detail = "APK pull job '${request.jobId}' was not found.",
        )
        if (job.type != DurableJobType.APK_PULL) {
            return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Job '${request.jobId}' belongs to ${job.type}, not APK_PULL.",
            )
        }
        val payload = runCatching { JSONObject(job.payloadJson) }.getOrElse { JSONObject() }
        val recordId = payload.optString("apkRecordId")
        if (recordId.isBlank()) {
            return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.FAILED,
                risk = risk,
                detail = "APK pull job '${request.jobId}' is missing its persisted apkRecordId.",
            )
        }
        return runPull(request.id, request.jobId, recordId, risk, config, token, explicitResume = true)
    }

    private suspend fun runPull(
        resultRequestId: String,
        jobId: String,
        apkRecordId: String,
        risk: BridgeRisk,
        config: BridgeConfig,
        token: String,
        explicitResume: Boolean,
    ): BridgeResult {
        val started = System.currentTimeMillis()
        return runCatching {
            require(token.isNotBlank()) { "Continuity relay token is not configured." }
            val record = requireRecord(apkRecordId)
            val payload = JSONObject()
                .put("schema", 1)
                .put("apkRecordId", record.id)
                .put("apkSha256", record.sha256)
                .put("packageName", record.packageName)
                .toString()
            val initial = jobs.begin(
                jobId = jobId,
                type = DurableJobType.APK_PULL,
                requestId = resultRequestId,
                packageName = record.packageName,
                projectId = record.projectId,
                payloadJson = payload,
                resumable = true,
            )
            require(initial.type == DurableJobType.APK_PULL) { "Job '$jobId' is already owned by ${initial.type}." }
            val originalPayload = runCatching { JSONObject(initial.payloadJson) }.getOrElse { JSONObject() }
            require(originalPayload.optString("apkRecordId") == record.id) {
                "Job '$jobId' was created for a different APK record. Use a new jobId."
            }

            when (initial.state) {
                DurableJobState.SUCCEEDED -> return@runCatching BridgeResult(
                    requestId = resultRequestId,
                    status = BridgeResultStatus.SUCCESS,
                    risk = risk,
                    detail = "APK pull job '$jobId' is already complete; returning its verified transfer manifest.",
                    output = initial.resultJson,
                    durationMs = System.currentTimeMillis() - started,
                )
                DurableJobState.INTERRUPTED,
                DurableJobState.FAILED,
                DurableJobState.PAUSED -> {
                    require(explicitResume) { "APK pull job '$jobId' already exists in ${initial.state}. Use JOB_RESUME." }
                    require(initial.resumable) { "APK pull job '$jobId' is not resumable; use a new jobId." }
                    jobs.prepareResume(jobId)
                }
                DurableJobState.CANCELLED -> error("APK pull job '$jobId' was cancelled and is terminal; use a new jobId.")
                DurableJobState.RUNNING,
                DurableJobState.CANCEL_REQUESTED -> error("APK pull job '$jobId' is already active in ${initial.state}.")
                DurableJobState.CREATED -> {
                    require(!explicitResume) { "APK pull job '$jobId' has not started and cannot be resumed." }
                    jobs.start(jobId, "Resolving fastest exact source for ${record.displayName}.")
                }
            }
            jobs.stage(jobId, "RESOLVING_SOURCE", "Selecting the fastest trustworthy exact source for ${record.sha256}.", true, true)

            val resolved = resolver.resolveExact(record.sha256, record.packageName, record.id)
                ?: error("No exact source could be materialized for APKbox record ${record.id}.")
            check(sha256(resolved.file).equals(record.sha256, true)) { "Resolved source failed full-file SHA verification." }
            jobs.stage(
                jobId = jobId,
                stage = "PUBLISHING_CHUNKS",
                detail = "Publishing exact APK from ${resolved.sourceKind.name.lowercase().replace('_', ' ')}.",
                cancellable = true,
                resumable = true,
                packageName = record.packageName,
                projectId = record.projectId,
                artifactSha256 = record.sha256,
                artifactPath = resolved.file.absolutePath,
            )

            val total = resolved.file.length()
            val existing = jobs.get(jobId)
            var offset = existing?.progressBytes?.coerceIn(0L, total) ?: 0L
            offset = (offset / PULL_CHUNK_BYTES) * PULL_CHUNK_BYTES
            val parts = JSONArray()
            val totalParts = ((total + PULL_CHUNK_BYTES - 1L) / PULL_CHUNK_BYTES).toInt()

            // Deterministic paths make already-published chunks safe to reference after resume.
            repeat(totalParts) { index ->
                val partOffset = index.toLong() * PULL_CHUNK_BYTES
                val partSize = minOf(PULL_CHUNK_BYTES.toLong(), total - partOffset).toInt()
                val partId = "$jobId.apk.part${index.toString().padStart(4, '0')}"
                val path = "bridge/devices/${config.deviceId}/artifacts/$partId.bin"
                if (partOffset < offset) {
                    parts.put(JSONObject()
                        .put("index", index)
                        .put("offset", partOffset)
                        .put("bytes", partSize)
                        .put("path", path)
                        .put("sha256", JSONObject.NULL)
                        .put("reusedFromPriorAttempt", true))
                    return@repeat
                }
                if (jobs.isCancelRequested(jobId)) {
                    jobs.cancelled(jobId, "APK pull cancelled after $partOffset of $total bytes; completed relay chunks remain reusable for an explicit resume.")
                    throw PullCancelledException("APK pull cancelled at a safe chunk boundary.")
                }

                val bytes = readRange(resolved.file, partOffset, partSize)
                val partSha = sha256(bytes)
                val writtenPath = relay.writeArtifact(config, token, partId, "bin", bytes)
                parts.put(JSONObject()
                    .put("index", index)
                    .put("offset", partOffset)
                    .put("bytes", bytes.size)
                    .put("path", writtenPath)
                    .put("sha256", partSha)
                    .put("reusedFromPriorAttempt", false))
                offset = partOffset + bytes.size
                jobs.progress(jobId, offset, total, "Published APK chunk ${index + 1}/$totalParts · $offset / $total bytes")
            }

            // Recompute missing per-part SHA values for parts skipped on resume by reading local
            // bytes; no relay readback is needed because progress was only advanced after PUT success.
            for (index in 0 until parts.length()) {
                val part = parts.getJSONObject(index)
                if (part.isNull("sha256")) {
                    val partOffset = part.getLong("offset")
                    val partSize = part.getInt("bytes")
                    part.put("sha256", sha256(readRange(resolved.file, partOffset, partSize)))
                }
            }

            val manifest = JSONObject()
                .put("schema", 1)
                .put("kind", "APKBOX_EXACT_APK_PULL")
                .put("jobId", jobId)
                .put("apkRecordId", record.id)
                .put("projectId", record.projectId)
                .put("displayName", record.displayName)
                .put("title", record.title)
                .put("packageName", record.packageName)
                .put("versionName", record.versionName)
                .put("versionCode", record.versionCode)
                .put("apkSha256", record.sha256)
                .put("sizeBytes", total)
                .put("chunkBytes", PULL_CHUNK_BYTES)
                .put("sourceKind", resolved.sourceKind.name)
                .put("sourceDetail", resolved.sourceDetail)
                .put("parts", parts)
                .put("assemblyRule", "Concatenate parts by ascending index with no separators, then verify SHA-256 equals apkSha256.")
            val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
            val manifestPath = relay.writeArtifact(config, token, "$jobId.apk.manifest", "json", manifestBytes)
            manifest.put("manifestPath", manifestPath)
            manifest.put("manifestSha256", sha256(manifestBytes))
            val resultJson = manifest.toString(2)
            jobs.succeed(jobId, "Exact APK pull completed: $total bytes in $totalParts verified chunks.", resultJson)

            BridgeResult(
                requestId = resultRequestId,
                status = BridgeResultStatus.SUCCESS,
                risk = risk,
                detail = "Pulled exact APKbox record ${record.id} from ${resolved.sourceKind.name.lowercase().replace('_', ' ')} into $totalParts private Continuity chunks; full-file SHA-256 is ${record.sha256}.",
                output = resultJson,
                durationMs = System.currentTimeMillis() - started,
                artifacts = listOf(
                    BridgeArtifact(
                        path = manifestPath,
                        mimeType = "application/json",
                        sha256 = sha256(manifestBytes),
                        bytes = manifestBytes.size.toLong(),
                    )
                ),
            )
        }.getOrElse { failure ->
            if (failure is PullCancelledException) {
                BridgeResult(
                    requestId = resultRequestId,
                    status = BridgeResultStatus.FAILED,
                    risk = risk,
                    detail = failure.message ?: "APK pull cancelled.",
                    durationMs = System.currentTimeMillis() - started,
                )
            } else {
                runCatching {
                    val current = jobs.get(jobId)
                    if (current != null && !current.terminal) jobs.fail(jobId, failure.message ?: failure.javaClass.simpleName, resumable = true)
                }
                BridgeResult(
                    requestId = resultRequestId,
                    status = BridgeResultStatus.FAILED,
                    risk = risk,
                    detail = failure.message ?: failure.javaClass.simpleName,
                    durationMs = System.currentTimeMillis() - started,
                )
            }
        }
    }

    private suspend fun inspectJson(
        record: ApkRecord,
        file: File,
        sourceKind: String,
        sourceDetail: String,
        requestedLimit: Int,
    ): JSONObject = withContext(Dispatchers.IO) {
        val limit = requestedLimit.coerceIn(1, MAX_COMPONENTS)
        val archive = ApkInspector.inspect(appContext, file)
        val pm = appContext.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
            PackageManager.GET_META_DATA
        @Suppress("DEPRECATION")
        val packageInfo = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Android could not parse detailed package metadata.")
        packageInfo.applicationInfo?.apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }

        var zipEntryCount = 0
        var dexCount = 0
        var assetsCount = 0
        var resCount = 0
        var metaInfCount = 0
        var compressedBytes = 0L
        var uncompressedBytes = 0L
        var nativeLibraryCount = 0
        val nativeLibraries = mutableListOf<String>()
        val abis = linkedSetOf<String>()
        val dexFiles = mutableListOf<String>()
        var hasManifest = false
        var hasResourcesArsc = false
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                zipEntryCount++
                if (entry.compressedSize >= 0) compressedBytes += entry.compressedSize
                if (entry.size >= 0) uncompressedBytes += entry.size
                val name = entry.name
                if (Regex("^classes(?:\\d+)?\\.dex$").matches(name)) {
                    dexCount++
                    if (dexFiles.size < limit) dexFiles += name
                }
                if (name.startsWith("assets/")) assetsCount++
                if (name.startsWith("res/")) resCount++
                if (name.startsWith("META-INF/")) metaInfCount++
                if (name == "AndroidManifest.xml") hasManifest = true
                if (name == "resources.arsc") hasResourcesArsc = true
                val libMatch = Regex("^lib/([^/]+)/(.+\\.so)$").find(name)
                if (libMatch != null) {
                    nativeLibraryCount++
                    abis += libMatch.groupValues[1]
                    if (nativeLibraries.size < limit) nativeLibraries += name
                }
            }
        }

        val appInfo = packageInfo.applicationInfo
        val permissions = packageInfo.requestedPermissions?.toList().orEmpty()
        fun componentNames(values: Array<out android.content.pm.ComponentInfo>?): JSONArray = JSONArray().apply {
            values.orEmpty().take(limit).forEach { put(it.name) }
        }

        val installed = ApkInspector.inspectInstalled(appContext, record.packageName)
        val installedSha = if (installed != null) installedPackageSha256(record.packageName) else ""

        JSONObject()
            .put("schema", 1)
            .put("apkRecord", JSONObject()
                .put("id", record.id)
                .put("projectId", record.projectId)
                .put("title", record.title)
                .put("displayName", record.displayName)
                .put("description", record.description)
                .put("notes", record.notes)
                .put("packageName", record.packageName)
                .put("versionName", record.versionName)
                .put("versionCode", record.versionCode)
                .put("sha256", record.sha256)
                .put("signingCertSha256", record.signingCertSha256 ?: JSONObject.NULL)
                .put("sizeBytes", record.sizeBytes)
                .put("isBase", record.isBase))
            .put("resolvedSource", JSONObject()
                .put("kind", sourceKind)
                .put("detail", sourceDetail)
                .put("materializedBytes", file.length())
                .put("verifiedSha256", sha256(file)))
            .put("package", JSONObject()
                .put("label", archive.label)
                .put("packageName", archive.packageName)
                .put("versionName", archive.versionName)
                .put("versionCode", archive.versionCode)
                .put("signingCertSha256", archive.signingCertSha256 ?: JSONObject.NULL)
                .put("minSdk", appInfo?.minSdkVersion ?: 0)
                .put("targetSdk", appInfo?.targetSdkVersion ?: 0)
                .put("compileSdk", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) appInfo?.compileSdkVersion ?: 0 else 0)
                .put("debuggable", appInfo?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 } ?: false)
                .put("nativeLibraryDir", appInfo?.nativeLibraryDir.orEmpty()))
            .put("permissions", JSONArray().apply { permissions.take(limit).forEach(::put) })
            .put("permissionCount", permissions.size)
            .put("components", JSONObject()
                .put("activities", componentNames(packageInfo.activities))
                .put("activityCount", packageInfo.activities?.size ?: 0)
                .put("services", componentNames(packageInfo.services))
                .put("serviceCount", packageInfo.services?.size ?: 0)
                .put("receivers", componentNames(packageInfo.receivers))
                .put("receiverCount", packageInfo.receivers?.size ?: 0)
                .put("providers", componentNames(packageInfo.providers))
                .put("providerCount", packageInfo.providers?.size ?: 0))
            .put("archiveLayout", JSONObject()
                .put("zipEntryCount", zipEntryCount)
                .put("dexCount", dexCount)
                .put("dexFiles", JSONArray(dexFiles))
                .put("abis", JSONArray(abis.toList()))
                .put("nativeLibraryCount", nativeLibraryCount)
                .put("nativeLibraries", JSONArray(nativeLibraries))
                .put("assetEntryCount", assetsCount)
                .put("resourceEntryCount", resCount)
                .put("metaInfEntryCount", metaInfCount)
                .put("compressedEntryBytes", compressedBytes)
                .put("uncompressedEntryBytes", uncompressedBytes)
                .put("hasAndroidManifest", hasManifest)
                .put("hasResourcesArsc", hasResourcesArsc))
            .put("installedComparison", JSONObject()
                .put("installed", installed != null)
                .put("versionName", installed?.versionName ?: JSONObject.NULL)
                .put("versionCode", installed?.versionCode ?: JSONObject.NULL)
                .put("signingCertSha256", installed?.signingCertSha256 ?: JSONObject.NULL)
                .put("baseApkSha256", installedSha)
                .put("exactSameApk", installedSha.isNotBlank() && installedSha.equals(record.sha256, true)))
            .put("listLimit", limit)
    }

    private fun requireRecord(id: String): ApkRecord {
        val clean = id.trim()
        require(clean.isNotBlank()) { "apkRecordId is required." }
        return library.records.value.firstOrNull { it.id == clean }
            ?: error("APKbox record '$clean' was not found.")
    }

    private suspend fun installedPackageSha256(packageName: String): String {
        if (!runCatching { privileged.ensureReady() }.getOrDefault(false)) return ""
        val pathResult = runCatching { privileged.execute("pm path $packageName", 10) }.getOrNull() ?: return ""
        val path = pathResult.output.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.endsWith("/base.apk") }
            ?.removePrefix("package:") ?: return ""
        if (!path.matches(Regex("[/A-Za-z0-9._=:+-]+"))) return ""
        val hashResult = runCatching { privileged.execute("sha256sum $path", 30) }.getOrNull() ?: return ""
        return Regex("(?i)^[0-9a-f]{64}").find(hashResult.output.trim())?.value?.lowercase().orEmpty()
    }

    private fun readRange(file: File, offset: Long, length: Int): ByteArray {
        require(offset >= 0L && length > 0) { "Invalid APK pull range." }
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return bytes
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class PullCancelledException(message: String) : RuntimeException(message)
}
