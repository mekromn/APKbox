package com.mekromn.apkbox.bridge

import android.content.Context
import android.net.Uri
import com.mekromn.apkbox.artifacts.ArtifactCancelledException
import com.mekromn.apkbox.artifacts.ArtifactIngestor
import com.mekromn.apkbox.artifacts.ArtifactSourceResolver
import com.mekromn.apkbox.artifacts.ArtifactSpec
import com.mekromn.apkbox.artifacts.IngestedArtifact
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.jobs.DurableJobEngine
import com.mekromn.apkbox.jobs.DurableJobState
import com.mekromn.apkbox.jobs.DurableJobType
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * One-request remote APK deployment backed by APKbox's universal durable job + artifact layers.
 * BuildRunner remains the stricter reproducible candidate-manifest workflow; this path optimizes
 * "I already have an APK URL" while still preserving exact-byte verification and safe recovery.
 */
class RemoteApkInstallCoordinator(
    context: Context,
    private val library: LibraryStore,
    private val privileged: PrivilegedBridgeManager,
    private val jobs: DurableJobEngine,
    private val artifacts: ArtifactIngestor,
    private val resolver: ArtifactSourceResolver,
) {
    companion object {
        private const val MAX_APK_BYTES = 2L * 1024L * 1024L * 1024L
        private val PACKAGE_REGEX = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val SHA_REGEX = Regex("[0-9a-fA-F]{64}")
    }

    private val appContext = context.applicationContext

    suspend fun execute(request: BridgeRequest, risk: BridgeRisk): BridgeResult {
        val jobId = request.jobId.ifBlank { request.id }
        val existing = jobs.begin(
            jobId = jobId,
            type = DurableJobType.REMOTE_APK_INSTALL,
            requestId = request.id,
            packageName = request.packageName,
            projectId = request.projectId,
            payloadJson = request.toJson().toString(),
            resumable = true,
        )
        when (existing.state) {
            DurableJobState.SUCCEEDED -> return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.SUCCESS,
                risk = risk,
                detail = "Remote APK job '$jobId' had already completed successfully.",
                output = existing.resultJson,
            )
            DurableJobState.INTERRUPTED,
            DurableJobState.FAILED,
            DurableJobState.PAUSED -> return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Remote APK job '$jobId' already exists in ${existing.state}. Use JOB_RESUME when resumable; do not repeat APK_INSTALL_URL.",
            )
            DurableJobState.CANCELLED -> return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Remote APK job '$jobId' was cancelled and is terminal. Use a new jobId.",
            )
            DurableJobState.RUNNING,
            DurableJobState.CANCEL_REQUESTED -> return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Remote APK job '$jobId' is already active in ${existing.state}.",
            )
            DurableJobState.CREATED -> jobs.start(jobId, "Remote APK deployment started.")
        }
        return runTransaction(request, request.id, risk, jobId)
    }

    suspend fun resume(jobId: String, controllerRequest: BridgeRequest, risk: BridgeRisk): BridgeResult {
        val job = jobs.get(jobId) ?: return BridgeResult(
            requestId = controllerRequest.id,
            status = BridgeResultStatus.INVALID,
            risk = risk,
            detail = "Job '$jobId' was not found.",
        )
        if (job.type != DurableJobType.REMOTE_APK_INSTALL) {
            return BridgeResult(
                requestId = controllerRequest.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Job '$jobId' is ${job.type}, not a remote APK install.",
            )
        }
        if (job.state == DurableJobState.SUCCEEDED) {
            return BridgeResult(
                requestId = controllerRequest.id,
                status = BridgeResultStatus.SUCCESS,
                risk = risk,
                detail = "Job '$jobId' is already complete.",
                output = job.resultJson,
            )
        }
        if (job.state == DurableJobState.CANCELLED) {
            return BridgeResult(
                requestId = controllerRequest.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Job '$jobId' was cancelled and is terminal; use a new jobId.",
            )
        }
        if (job.state !in setOf(DurableJobState.INTERRUPTED, DurableJobState.FAILED, DurableJobState.PAUSED) || !job.resumable) {
            return BridgeResult(
                requestId = controllerRequest.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Job '$jobId' cannot resume from ${job.state} (resumable=${job.resumable}).",
            )
        }
        val original = runCatching { BridgeRequest.fromJson(JSONObject(job.payloadJson)) }.getOrElse { failure ->
            return BridgeResult(
                requestId = controllerRequest.id,
                status = BridgeResultStatus.FAILED,
                risk = risk,
                detail = "Job '$jobId' cannot resume because its original request payload is unavailable: ${message(failure)}",
            )
        }
        jobs.prepareResume(jobId)
        return runTransaction(original.copy(jobId = jobId), controllerRequest.id, risk, jobId)
    }

    private suspend fun runTransaction(
        request: BridgeRequest,
        responseRequestId: String,
        risk: BridgeRisk,
        jobId: String,
    ): BridgeResult {
        val started = System.currentTimeMillis()
        val url = request.downloadUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true)) {
            return fail(responseRequestId, risk, started, jobId, "Remote APK URL must use HTTPS.", resumable = false)
        }
        val expectedSha = request.expectedApkSha256.trim().lowercase()
        if (expectedSha.isNotBlank() && !SHA_REGEX.matches(expectedSha)) {
            return fail(responseRequestId, risk, started, jobId, "expectedApkSha256 must be blank or exactly 64 hexadecimal characters.", resumable = false)
        }
        val expectedPackage = request.packageName.trim()
        if (expectedPackage.isNotBlank() && !PACKAGE_REGEX.matches(expectedPackage)) {
            return fail(responseRequestId, risk, started, jobId, "Invalid expected package name.", resumable = false)
        }
        val fastestLocal = if (expectedSha.isNotBlank()) {
            runCatching { resolver.resolveExact(expectedSha, expectedPackage) }.getOrNull()
        } else null
        if (fastestLocal == null && request.requiresBuildToken && !artifacts.hasBuildToken()) {
            return fail(
                responseRequestId,
                risk,
                started,
                jobId,
                "This APK URL requires the encrypted APKbox build-source token, but no faster exact local source was found and no token is configured.",
                resumable = true,
            )
        }

        val artifact = try {
            jobs.stage(
                jobId,
                stage = "DOWNLOADING",
                detail = "Ingesting APK into APKbox's resumable content-addressed artifact store.",
                cancellable = true,
                resumable = true,
            )
            val prior = jobs.get(jobId)
            fastestLocal?.let { resolved ->
                jobs.progress(jobId, resolved.sizeBytes, resolved.sizeBytes, "Reused exact ${resolved.sourceKind.name.lowercase().replace('_', ' ')} source; network skipped.")
                IngestedArtifact(
                    sha256 = resolved.sha256,
                    sizeBytes = resolved.sizeBytes,
                    file = resolved.file,
                    sourceUrl = "local://${resolved.sourceKind.name.lowercase()}",
                    resumedBytes = resolved.sizeBytes,
                    cacheHit = true,
                )
            } ?: prior?.artifactSha256
                ?.takeIf { it.isNotBlank() }
                ?.let(artifacts::objectForSha)
                ?.let { file ->
                    IngestedArtifact(
                        sha256 = prior.artifactSha256,
                        sizeBytes = file.length(),
                        file = file,
                        sourceUrl = url,
                        resumedBytes = file.length(),
                        cacheHit = true,
                    )
                }
                ?: artifacts.ingest(
                    ArtifactSpec(
                        jobId = jobId,
                        sourceUrl = url,
                        expectedSha256 = expectedSha,
                        requiresBuildToken = request.requiresBuildToken,
                        maxBytes = MAX_APK_BYTES,
                        userAgent = "APKbox-Remote-APK-Install",
                        accept = "application/vnd.android.package-archive, application/octet-stream",
                    ),
                    onProgress = { downloaded, total ->
                        jobs.progress(jobId, downloaded, total, "Downloading APK · $downloaded${if (total >= 0L) " / $total" else ""} bytes")
                    },
                    isCancelled = { jobs.isCancelRequested(jobId) },
                )
        } catch (cancelled: ArtifactCancelledException) {
            jobs.cancelled(jobId, "Remote APK download cancelled; partial bytes were preserved for an explicit future resume.")
            return BridgeResult(
                requestId = responseRequestId,
                status = BridgeResultStatus.DENIED,
                risk = risk,
                detail = "Job '$jobId' cancelled at a safe download boundary.",
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (failure: Throwable) {
            return fail(
                responseRequestId,
                risk,
                started,
                jobId,
                "Artifact ingest failed: ${message(failure)}",
                resumable = true,
            )
        }

        jobs.stage(
            jobId,
            stage = "VERIFYING_APK",
            detail = "Exact artifact ${artifact.sha256} acquired; parsing APK identity.",
            cancellable = true,
            resumable = true,
            artifactSha256 = artifact.sha256,
            artifactPath = artifact.file.absolutePath,
        )
        if (jobs.isCancelRequested(jobId)) {
            jobs.cancelled(jobId)
            return cancelledResult(responseRequestId, risk, started, jobId)
        }

        val archive = runCatching { ApkInspector.inspect(appContext, artifact.file) }.getOrElse { failure ->
            return fail(responseRequestId, risk, started, jobId, "Downloaded artifact is not a parseable APK: ${message(failure)}", resumable = false)
        }
        if (expectedPackage.isNotBlank() && archive.packageName != expectedPackage) {
            return fail(
                responseRequestId,
                risk,
                started,
                jobId,
                "Downloaded APK package is ${archive.packageName}, expected $expectedPackage. Nothing was installed.",
                resumable = false,
            )
        }

        jobs.stage(
            jobId,
            stage = "PREPARING",
            detail = "Verified ${archive.packageName}; preparing project/archive/install plan.",
            cancellable = true,
            resumable = true,
            packageName = archive.packageName,
        )

        val record = if (request.saveToProject) {
            if (jobs.isCancelRequested(jobId)) {
                jobs.cancelled(jobId)
                return cancelledResult(responseRequestId, risk, started, jobId)
            }
            jobs.stage(jobId, "ARCHIVING", "Saving exact verified APK bytes into APKbox.", cancellable = true, resumable = true)
            runCatching {
                archiveToProject(
                    request = request,
                    apkFile = artifact.file,
                    sha256 = artifact.sha256,
                    packageName = archive.packageName,
                    archiveLabel = archive.label,
                )
            }.getOrElse { failure ->
                return fail(responseRequestId, risk, started, jobId, "APKbox project archive failed: ${message(failure)}", resumable = true)
            }.also { saved ->
                jobs.stage(
                    jobId,
                    "ARCHIVED",
                    "Exact APK saved as record ${saved.id} in project ${saved.projectId}.",
                    cancellable = true,
                    resumable = true,
                    projectId = saved.projectId,
                )
            }
        } else null

        val installed = ApkInspector.inspectInstalled(appContext, archive.packageName)
        if (installed != null &&
            !installed.signingCertSha256.isNullOrBlank() &&
            !archive.signingCertSha256.isNullOrBlank() &&
            !installed.signingCertSha256.equals(archive.signingCertSha256, ignoreCase = true)
        ) {
            val archiveNote = if (record != null) " The APK remains preserved in project '${record.projectId}'." else ""
            return fail(
                responseRequestId,
                risk,
                started,
                jobId,
                "Installed ${archive.packageName} has a different signing certificate. APKbox will not silently remove app data.$archiveNote Use the explicit signature-replacement flow when intended.",
                resumable = false,
            )
        }

        if (jobs.isCancelRequested(jobId)) {
            jobs.cancelled(jobId)
            return cancelledResult(responseRequestId, risk, started, jobId)
        }

        val existingSha = installedPackageSha256(archive.packageName)
        if (!existingSha.equals(artifact.sha256, ignoreCase = true)) {
            jobs.stage(
                jobId,
                stage = "INSTALLING",
                detail = "Streaming exact APK through ${privileged.activeTransportLabel()}; cancellation is locked until package-manager state is verified.",
                cancellable = false,
                resumable = true,
            )
            val install = runCatching {
                privileged.installApk(artifact.file, allowDowngrade = request.allowDowngrade) { sent, total ->
                    jobs.progress(jobId, sent, total, "Installing via ${privileged.activeTransportLabel()} · $sent / $total bytes")
                }
            }.getOrElse { failure ->
                return fail(responseRequestId, risk, started, jobId, "Unattended install transport failed: ${message(failure)}", resumable = true)
            }
            if (!install.success) {
                return fail(
                    responseRequestId,
                    risk,
                    started,
                    jobId,
                    "Android package manager rejected the APK: ${install.output.take(2_000)}",
                    resumable = true,
                )
            }
        }

        jobs.stage(
            jobId,
            stage = "VERIFYING_INSTALLED",
            detail = "Verifying installed base.apk against exact artifact SHA-256.",
            cancellable = false,
            resumable = true,
        )
        val installedSha = installedPackageSha256(archive.packageName)
        if (!installedSha.equals(artifact.sha256, ignoreCase = true)) {
            return fail(
                responseRequestId,
                risk,
                started,
                jobId,
                "Package manager completed, but installed base.apk SHA '$installedSha' does not match artifact ${artifact.sha256}.",
                resumable = true,
            )
        }

        var launchDetail = ""
        if (request.autoLaunch) {
            jobs.stage(jobId, "LAUNCHING", "Launching verified ${archive.packageName}.", cancellable = false, resumable = true)
            val launch = runCatching {
                privileged.execute("monkey -p ${archive.packageName} -c android.intent.category.LAUNCHER 1", 15)
            }.getOrElse { failure ->
                return fail(responseRequestId, risk, started, jobId, "APK installed and verified, but launch failed: ${message(failure)}", resumable = true)
            }
            if (launch.timedOut || (launch.exitCode != null && launch.exitCode != 0)) {
                return fail(responseRequestId, risk, started, jobId, "APK installed and verified, but launch failed: ${launch.output.take(2_000)}", resumable = true)
            }
            launchDetail = " and launched"
        }

        val output = JSONObject()
            .put("jobId", jobId)
            .put("packageName", archive.packageName)
            .put("label", archive.label)
            .put("apkSha256", artifact.sha256)
            .put("artifactCacheHit", artifact.cacheHit)
            .put("artifactSource", artifact.sourceUrl)
            .put("artifactResumedFromBytes", artifact.resumedBytes)
            .put("downloadedBytes", artifact.sizeBytes)
            .put("installedBaseApkSha256", installedSha)
            .put("savedToProject", record != null)
            .put("projectId", record?.projectId.orEmpty())
            .put("apkRecordId", record?.id.orEmpty())
            .put("archiveTitle", record?.title.orEmpty())
            .put("archiveDescription", record?.description.orEmpty())
            .put("transport", privileged.activeTransportLabel())
            .put("launched", request.autoLaunch)
            .toString(2)

        val detail = buildString {
            append("Durable job '$jobId' downloaded/verified and unattended-installed ")
            append(archive.packageName)
            if (record != null) append("; exact bytes saved to project ${record.projectId}")
            append(launchDetail)
            append("; installed SHA verified.")
        }
        jobs.succeed(jobId, detail, output)
        return BridgeResult(
            requestId = responseRequestId,
            status = BridgeResultStatus.SUCCESS,
            risk = risk,
            detail = detail,
            output = output,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    private suspend fun archiveToProject(
        request: BridgeRequest,
        apkFile: File,
        sha256: String,
        packageName: String,
        archiveLabel: String,
    ): ApkRecord = withContext(Dispatchers.IO) {
        val project = resolveProject(request, packageName)
        if (project != null) {
            library.records.value.firstOrNull {
                it.projectId == project.id && it.sha256.equals(sha256, ignoreCase = true)
            }?.let { existing ->
                if (request.archiveTitle.isNotBlank() || request.archiveDescription.isNotBlank()) {
                    library.updateRecordDetails(
                        recordId = existing.id,
                        title = request.archiveTitle.ifBlank { existing.title },
                        description = request.archiveDescription.ifBlank { existing.description },
                        notes = existing.notes,
                    )
                    return@withContext library.records.value.first { it.id == existing.id }
                }
                return@withContext existing
            }

            return@withContext library.importRevision(
                projectId = project.id,
                uri = Uri.fromFile(apkFile),
                displayNameOverride = request.displayName.ifBlank { apkFile.name },
                title = request.archiveTitle,
                description = request.archiveDescription,
            ).record
        }

        library.importBase(
            uri = Uri.fromFile(apkFile),
            projectName = request.projectName.ifBlank { archiveLabel.ifBlank { packageName } },
            displayNameOverride = request.displayName.ifBlank { apkFile.name },
            title = request.archiveTitle,
            description = request.archiveDescription,
        ).record
    }

    private fun resolveProject(request: BridgeRequest, packageName: String): ApkProject? {
        val projects = library.projects.value
        if (request.projectId.isNotBlank()) {
            val project = projects.firstOrNull { it.id == request.projectId }
                ?: error("Requested APKbox project '${request.projectId}' does not exist.")
            require(project.packageName == packageName) {
                "Requested project ${project.name} stores ${project.packageName}, not $packageName."
            }
            return project
        }
        val matches = projects.filter { it.packageName == packageName }
        return when (matches.size) {
            0 -> null
            1 -> matches.single()
            else -> error("Multiple APKbox projects contain $packageName. Supply projectId so the artifact cannot be archived to the wrong project.")
        }
    }

    private suspend fun installedPackageSha256(packageName: String): String {
        val pathResult = runCatching { privileged.execute("pm path $packageName", 10) }.getOrNull() ?: return ""
        val path = pathResult.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.endsWith("/base.apk") }
            ?.removePrefix("package:") ?: return ""
        if (!path.matches(Regex("[/A-Za-z0-9._=:+-]+"))) return ""
        val hashResult = runCatching { privileged.execute("sha256sum $path", 30) }.getOrNull() ?: return ""
        return Regex("(?i)^[0-9a-f]{64}").find(hashResult.output.trim())?.value?.lowercase().orEmpty()
    }

    private fun cancelledResult(requestId: String, risk: BridgeRisk, started: Long, jobId: String) = BridgeResult(
        requestId = requestId,
        status = BridgeResultStatus.DENIED,
        risk = risk,
        detail = "Job '$jobId' cancelled at a safe boundary.",
        durationMs = System.currentTimeMillis() - started,
    )

    private fun fail(
        requestId: String,
        risk: BridgeRisk,
        started: Long,
        jobId: String,
        detail: String,
        resumable: Boolean,
    ): BridgeResult {
        runCatching { jobs.fail(jobId, detail, resumable) }
        return BridgeResult(
            requestId = requestId,
            status = BridgeResultStatus.FAILED,
            risk = risk,
            detail = detail.take(4_096),
            durationMs = System.currentTimeMillis() - started,
        )
    }

    private fun message(failure: Throwable): String = failure.message ?: failure.javaClass.simpleName
}
