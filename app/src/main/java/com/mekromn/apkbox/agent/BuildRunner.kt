package com.mekromn.apkbox.agent

import android.content.Context
import android.net.Uri
import com.mekromn.apkbox.artifacts.ArtifactCancelledException
import com.mekromn.apkbox.artifacts.ArtifactIngestor
import com.mekromn.apkbox.artifacts.ArtifactSourceResolver
import com.mekromn.apkbox.bridge.PrivilegedBridgeManager
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.jobs.DurableJobEngine
import com.mekromn.apkbox.jobs.DurableJobState
import com.mekromn.apkbox.jobs.DurableJobType
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class BuildRunner(
    context: Context,
    private val library: LibraryStore,
    private val privileged: PrivilegedBridgeManager,
    private val jobs: DurableJobEngine,
    artifacts: ArtifactIngestor,
    private val resolver: ArtifactSourceResolver,
) {
    private val appContext = context.applicationContext
    private val store = BuildRunStore(appContext)
    private val credentials = BuildSourceCredentials(appContext)
    private val downloader = BuildDownloader(store, artifacts)
    private val mutex = Mutex()

    suspend fun run(candidate: BuildCandidate): BuildRunCheckpoint = mutex.withLock {
        store.saveCandidate(candidate)
        val durable = jobs.begin(
            jobId = candidate.runId,
            type = DurableJobType.BUILD_RUNNER,
            requestId = candidate.buildId,
            packageName = candidate.targetPackage,
            projectId = candidate.projectId,
            payloadJson = candidate.toJson().toString(),
            resumable = true,
        )
        require(durable.type == DurableJobType.BUILD_RUNNER) {
            "Durable job '${candidate.runId}' is already owned by ${durable.type}."
        }
        when {
            durable.state in setOf(DurableJobState.INTERRUPTED, DurableJobState.FAILED, DurableJobState.PAUSED) && durable.resumable ->
                jobs.prepareResume(candidate.runId)
            durable.state == DurableJobState.SUCCEEDED -> Unit
            else -> jobs.start(candidate.runId, "Build Runner started candidate ${candidate.buildId}.")
        }

        val prior = store.loadCheckpoint(candidate.runId)
        if (prior != null && prior.buildId == candidate.buildId && prior.apkSha256.equals(candidate.expectedApkSha256, true)) {
            when (prior.state) {
                BuildRunState.PASSED -> {
                    jobs.succeed(candidate.runId, prior.detail, prior.toJson().toString())
                    return@withLock prior
                }
                BuildRunState.TESTING -> {
                    jobs.stage(candidate.runId, "TESTING", prior.detail, cancellable = false, resumable = true)
                    return@withLock prior
                }
                BuildRunState.INSTALLING,
                BuildRunState.LAUNCHING -> {
                    val installedSha = installedPackageSha256(candidate.targetPackage)
                    if (installedSha.equals(candidate.expectedApkSha256, ignoreCase = true)) {
                        val resumed = prior.copy(
                            state = BuildRunState.LAUNCHING,
                            detail = "Recovered after interruption: installed base.apk matches the exact candidate SHA.",
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                        store.saveCheckpoint(resumed)
                        jobs.stage(
                            candidate.runId,
                            "VERIFYING_INSTALLED",
                            resumed.detail,
                            cancellable = false,
                            resumable = true,
                            artifactSha256 = candidate.expectedApkSha256,
                        )
                        return@withLock finishAfterInstalled(candidate, resumed)
                    }
                }
                else -> Unit
            }
        }

        val started = prior?.startedAtEpochMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
        var checkpoint = BuildRunCheckpoint(
            buildId = candidate.buildId,
            runId = candidate.runId,
            state = BuildRunState.CREATED,
            targetPackage = candidate.targetPackage,
            commitSha = candidate.commitSha,
            workflowRunId = candidate.workflowRunId,
            artifactId = candidate.artifactId,
            startedAtEpochMs = started,
            detail = "Build candidate accepted for verification.",
        )
        store.saveCheckpoint(checkpoint)

        val fastestLocal = runCatching {
            resolver.resolveExact(candidate.expectedApkSha256, candidate.targetPackage)
        }.getOrNull()
        if (fastestLocal == null && candidate.requiresBuildToken && !credentials.hasToken()) {
            return@withLock fail(
                checkpoint,
                BuildRunState.BLOCKED_AUTH_REQUIRED,
                "Private build source requires the encrypted read-only build-source token because no exact local source is available.",
                resumable = false,
            )
        }

        val token = if (fastestLocal == null && candidate.requiresBuildToken) credentials.readToken() else null
        checkpoint = transition(checkpoint, BuildRunState.DOWNLOADING, "Downloading build artifact through the shared resumable artifact engine.")
        var lastPersistAt = 0L
        var lastPersistBytes = -1L
        val apkFile = try {
            if (fastestLocal != null) {
                jobs.stage(
                    candidate.runId,
                    "LOCAL_EXACT_SOURCE",
                    "Reusing exact ${fastestLocal.sourceKind.name.lowercase().replace('_', ' ')} source; network download skipped.",
                    cancellable = true,
                    resumable = true,
                    artifactSha256 = fastestLocal.sha256,
                    artifactPath = fastestLocal.file.absolutePath,
                )
                fastestLocal.file
            } else {
                downloader.obtainApk(
                candidate = candidate,
                buildToken = token,
                onProgress = { downloaded, total ->
                    val now = System.currentTimeMillis()
                    jobs.progress(candidate.runId, downloaded, total, "Build artifact ingest · $downloaded${if (total >= 0L) " / $total" else ""} bytes")
                    if (downloaded == total || downloaded - lastPersistBytes >= 8L * 1024L * 1024L || now - lastPersistAt >= 2_000L) {
                        checkpoint = checkpoint.copy(
                            downloadedBytes = downloaded,
                            expectedBytes = total,
                            updatedAtEpochMs = now,
                        )
                        store.saveCheckpoint(checkpoint)
                        lastPersistAt = now
                        lastPersistBytes = downloaded
                    }
                },
                    isCancelled = { jobs.isCancelRequested(candidate.runId) },
                )
            }
        } catch (cancelled: ArtifactCancelledException) {
            return@withLock cancelled(checkpoint, "Build download cancelled; resumable partial artifact preserved.")
        } catch (failure: Throwable) {
            if (jobs.isCancelRequested(candidate.runId) || message(failure).contains("cancelled", true)) {
                return@withLock cancelled(checkpoint, "Build ingest/extraction cancelled at a safe boundary.")
            }
            return@withLock fail(checkpoint, BuildRunState.FAILED, "Build artifact ingest failed: ${message(failure)}", resumable = true)
        }

        if (jobs.isCancelRequested(candidate.runId)) return@withLock cancelled(checkpoint, "Build cancelled before verification.")

        checkpoint = transition(
            checkpoint.copy(apkPath = apkFile.absolutePath),
            BuildRunState.VERIFYING,
            "Verifying exact APK SHA-256 and package metadata.",
        )
        val actualSha = runCatching { downloader.sha256(apkFile) }.getOrElse { failure ->
            return@withLock fail(checkpoint, BuildRunState.FAILED, "Could not hash downloaded APK: ${message(failure)}", resumable = true)
        }
        checkpoint = checkpoint.copy(apkSha256 = actualSha, updatedAtEpochMs = System.currentTimeMillis())
        store.saveCheckpoint(checkpoint)
        jobs.stage(
            candidate.runId,
            "VERIFYING_APK",
            "Candidate APK SHA-256 is $actualSha.",
            cancellable = true,
            resumable = true,
            artifactSha256 = actualSha,
            artifactPath = apkFile.absolutePath,
        )
        if (!actualSha.equals(candidate.expectedApkSha256, ignoreCase = true)) {
            return@withLock fail(
                checkpoint,
                BuildRunState.FAILED,
                "APK SHA-256 mismatch. Expected ${candidate.expectedApkSha256}, got $actualSha. Nothing was archived or installed.",
                resumable = false,
            )
        }

        val archive = runCatching { ApkInspector.inspect(appContext, apkFile) }.getOrElse { failure ->
            return@withLock fail(checkpoint, BuildRunState.FAILED, "Android could not parse verified APK: ${message(failure)}", resumable = false)
        }
        if (archive.packageName != candidate.targetPackage) {
            return@withLock fail(
                checkpoint,
                BuildRunState.FAILED,
                "Verified APK package is ${archive.packageName}, expected ${candidate.targetPackage}. Nothing was installed.",
                resumable = false,
            )
        }

        val projectResolution = resolveProject(candidate, archive.label)
        if (projectResolution.ambiguous) {
            return@withLock fail(checkpoint, BuildRunState.BLOCKED_PROJECT_AMBIGUOUS, projectResolution.detail, resumable = false)
        }
        if (jobs.isCancelRequested(candidate.runId)) return@withLock cancelled(checkpoint, "Build cancelled before archive.")

        checkpoint = transition(
            checkpoint.copy(projectId = projectResolution.project?.id.orEmpty()),
            BuildRunState.ARCHIVING,
            "Archiving exact verified APK bytes into APKbox before installation.",
        )
        val record = try {
            archiveCandidate(candidate, apkFile, actualSha, projectResolution.project, archive.label)
        } catch (failure: Throwable) {
            return@withLock fail(checkpoint, BuildRunState.FAILED, "APKbox archive failed: ${message(failure)}", resumable = true)
        }
        check(record.sha256.equals(actualSha, ignoreCase = true)) {
            "APKbox archive SHA differs from the verified candidate SHA."
        }
        checkpoint = checkpoint.copy(
            projectId = record.projectId,
            apkRecordId = record.id,
            detail = "Archived ${record.displayName} exactly as ${record.sha256}.",
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.saveCheckpoint(checkpoint)
        jobs.stage(
            candidate.runId,
            "ARCHIVED",
            checkpoint.detail,
            cancellable = true,
            resumable = true,
            projectId = record.projectId,
        )

        val installed = ApkInspector.inspectInstalled(appContext, candidate.targetPackage)
        if (installed != null &&
            !installed.signingCertSha256.isNullOrBlank() &&
            !archive.signingCertSha256.isNullOrBlank() &&
            !installed.signingCertSha256.equals(archive.signingCertSha256, ignoreCase = true)
        ) {
            return@withLock fail(
                checkpoint,
                BuildRunState.BLOCKED_SIGNATURE_MISMATCH,
                "Installed ${candidate.targetPackage} has a different signing certificate. APKbox archived the candidate but will not silently uninstall/reinstall it.",
                resumable = false,
            )
        }

        if (!candidate.autoInstall) {
            return@withLock pass(checkpoint, "Verified build archived; autoInstall=false so device state was not changed.")
        }
        if (jobs.isCancelRequested(candidate.runId)) return@withLock cancelled(checkpoint, "Build cancelled before package-manager mutation.")

        val alreadyInstalledSha = installedPackageSha256(candidate.targetPackage)
        if (alreadyInstalledSha.equals(actualSha, ignoreCase = true)) {
            checkpoint = transition(
                checkpoint,
                BuildRunState.LAUNCHING,
                "Exact candidate is already installed; skipping duplicate package-manager mutation.",
            )
            return@withLock finishAfterInstalled(candidate, checkpoint)
        }

        checkpoint = transition(
            checkpoint,
            BuildRunState.INSTALLING,
            "Installing archived candidate unattended through ${privileged.activeTransportLabel()}.",
        )
        jobs.stage(
            candidate.runId,
            "INSTALLING",
            checkpoint.detail,
            cancellable = false,
            resumable = true,
        )
        lastPersistBytes = -1L
        lastPersistAt = 0L
        val install = runCatching {
            privileged.installApk(apkFile, allowDowngrade = candidate.allowDowngrade) { sent, total ->
                jobs.progress(candidate.runId, sent, total, "Unattended install via ${privileged.activeTransportLabel()} · $sent / $total bytes")
                val now = System.currentTimeMillis()
                if (sent == total || sent - lastPersistBytes >= 16L * 1024L * 1024L || now - lastPersistAt >= 2_000L) {
                    checkpoint = checkpoint.copy(
                        downloadedBytes = sent,
                        expectedBytes = total,
                        detail = "Unattended install via ${privileged.activeTransportLabel()}: $sent / $total bytes streamed.",
                        updatedAtEpochMs = now,
                    )
                    store.saveCheckpoint(checkpoint)
                    lastPersistAt = now
                    lastPersistBytes = sent
                }
            }
        }.getOrElse { failure ->
            return@withLock fail(checkpoint, BuildRunState.FAILED, "Unattended install transport failed: ${message(failure)}", resumable = true)
        }
        if (!install.success) {
            return@withLock fail(
                checkpoint,
                BuildRunState.FAILED,
                "Android package manager rejected the candidate: ${install.output.take(2_000)}",
                resumable = true,
            )
        }

        jobs.stage(candidate.runId, "VERIFYING_INSTALLED", "Verifying installed base.apk SHA-256.", cancellable = false, resumable = true)
        val installedSha = installedPackageSha256(candidate.targetPackage)
        if (!installedSha.equals(actualSha, ignoreCase = true)) {
            return@withLock fail(
                checkpoint,
                BuildRunState.FAILED,
                "Package manager reported success, but installed base.apk SHA '$installedSha' does not match verified candidate $actualSha.",
                resumable = true,
            )
        }

        checkpoint = transition(checkpoint, BuildRunState.LAUNCHING, "Exact installed base.apk SHA verified after unattended install.")
        finishAfterInstalled(candidate, checkpoint)
    }

    suspend fun resumeJob(runId: String): BuildRunCheckpoint {
        val candidate = store.loadCandidate(runId) ?: error("Build job '$runId' has no persisted candidate.")
        return run(candidate)
    }

    fun checkpoint(runId: String): BuildRunCheckpoint? = store.loadCheckpoint(runId)
    fun candidate(runId: String): BuildCandidate? = store.loadCandidate(runId)
    fun recoverableRuns(): List<BuildRunCheckpoint> = store.listRecoverable()

    fun completeTesting(runId: String, passed: Boolean, detail: String): BuildRunCheckpoint? {
        val current = store.loadCheckpoint(runId) ?: return null
        val updated = current.copy(
            state = if (passed) BuildRunState.PASSED else BuildRunState.FAILED,
            detail = detail.take(4_096),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        store.saveCheckpoint(updated)
        if (passed) jobs.succeed(runId, detail, updated.toJson().toString())
        else jobs.fail(runId, detail, resumable = false)
        return updated
    }

    private suspend fun finishAfterInstalled(
        candidate: BuildCandidate,
        initial: BuildRunCheckpoint,
    ): BuildRunCheckpoint {
        var checkpoint = initial
        if (candidate.autoLaunch) {
            checkpoint = transition(checkpoint, BuildRunState.LAUNCHING, "Launching newly installed ${candidate.targetPackage}.")
            jobs.stage(candidate.runId, "LAUNCHING", checkpoint.detail, cancellable = false, resumable = true)
            val launch = runCatching {
                privileged.execute("monkey -p ${candidate.targetPackage} -c android.intent.category.LAUNCHER 1", 15)
            }.getOrElse { failure ->
                return fail(checkpoint, BuildRunState.FAILED, "Installed build could not be launched: ${message(failure)}", resumable = true)
            }
            if (launch.timedOut || (launch.exitCode != null && launch.exitCode != 0)) {
                return fail(checkpoint, BuildRunState.FAILED, "Installed build launch failed: ${launch.output.take(2_000)}", resumable = true)
            }
        }

        if (candidate.planRunId.isNotBlank()) {
            val testing = transition(
                checkpoint,
                BuildRunState.TESTING,
                "Installed and launched successfully; awaiting autonomous test plan ${candidate.planRunId}.",
            )
            jobs.stage(candidate.runId, "TESTING", testing.detail, cancellable = false, resumable = true)
            return testing
        }
        return pass(checkpoint, "Verified, archived, unattended-installed${if (candidate.autoLaunch) ", and launched" else ""} successfully.")
    }

    private suspend fun installedPackageSha256(packageName: String): String {
        val pathResult = runCatching { privileged.execute("pm path $packageName", 10) }.getOrNull() ?: return ""
        val path = pathResult.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.endsWith("/base.apk") }
            ?.removePrefix("package:") ?: return ""
        if (!path.matches(Regex("[/A-Za-z0-9._=:+-]+"))) return ""
        val hashResult = runCatching { privileged.execute("sha256sum $path", 20) }.getOrNull() ?: return ""
        return Regex("(?i)^[0-9a-f]{64}").find(hashResult.output.trim())?.value?.lowercase().orEmpty()
    }

    private suspend fun archiveCandidate(
        candidate: BuildCandidate,
        apkFile: File,
        sha256: String,
        project: ApkProject?,
        archiveLabel: String,
    ): ApkRecord = withContext(Dispatchers.IO) {
        if (project != null) {
            library.records.value.firstOrNull {
                it.projectId == project.id && it.sha256.equals(sha256, ignoreCase = true)
            }?.let { return@withContext it }
            return@withContext library.importRevision(
                projectId = project.id,
                uri = Uri.fromFile(apkFile),
                displayNameOverride = candidate.displayName.ifBlank { apkFile.name },
            ).record
        }
        library.importBase(
            uri = Uri.fromFile(apkFile),
            projectName = archiveLabel,
            displayNameOverride = candidate.displayName.ifBlank { apkFile.name },
        ).record
    }

    private fun resolveProject(candidate: BuildCandidate, archiveLabel: String): ProjectResolution {
        val projects = library.projects.value
        if (candidate.projectId.isNotBlank()) {
            val project = projects.firstOrNull { it.id == candidate.projectId }
                ?: return ProjectResolution(null, true, "Requested APKbox project '${candidate.projectId}' does not exist.")
            if (project.packageName != candidate.targetPackage) {
                return ProjectResolution(null, true, "Requested project ${project.name} stores ${project.packageName}, not ${candidate.targetPackage}.")
            }
            return ProjectResolution(project, false, "Using explicitly selected project ${project.name}.")
        }
        val matches = projects.filter { it.packageName == candidate.targetPackage }
        return when (matches.size) {
            0 -> ProjectResolution(null, false, "No existing project for ${candidate.targetPackage}; APKbox will create '$archiveLabel'.")
            1 -> ProjectResolution(matches.single(), false, "Using ${matches.single().name}.")
            else -> ProjectResolution(
                null,
                true,
                "Multiple APKbox projects contain ${candidate.targetPackage}. Supply projectId so a build can never be archived to the wrong project.",
            )
        }
    }

    private fun transition(
        checkpoint: BuildRunCheckpoint,
        state: BuildRunState,
        detail: String,
    ): BuildRunCheckpoint = checkpoint.copy(
        state = state,
        detail = detail,
        updatedAtEpochMs = System.currentTimeMillis(),
    ).also { updated ->
        store.saveCheckpoint(updated)
        val cancellable = state !in setOf(BuildRunState.INSTALLING, BuildRunState.LAUNCHING, BuildRunState.TESTING)
        jobs.stage(updated.runId, state.name, detail, cancellable = cancellable, resumable = true)
    }

    private fun fail(
        checkpoint: BuildRunCheckpoint,
        state: BuildRunState,
        detail: String,
        resumable: Boolean,
    ): BuildRunCheckpoint = checkpoint.copy(
        state = state,
        detail = detail.take(4_096),
        updatedAtEpochMs = System.currentTimeMillis(),
    ).also { updated ->
        store.saveCheckpoint(updated)
        jobs.fail(updated.runId, detail, resumable)
    }

    private fun pass(checkpoint: BuildRunCheckpoint, detail: String): BuildRunCheckpoint = checkpoint.copy(
        state = BuildRunState.PASSED,
        detail = detail.take(4_096),
        updatedAtEpochMs = System.currentTimeMillis(),
    ).also { updated ->
        store.saveCheckpoint(updated)
        jobs.succeed(updated.runId, detail, updated.toJson().toString())
    }

    private fun cancelled(checkpoint: BuildRunCheckpoint, detail: String): BuildRunCheckpoint = checkpoint.copy(
        state = BuildRunState.FAILED,
        detail = detail.take(4_096),
        updatedAtEpochMs = System.currentTimeMillis(),
    ).also { updated ->
        store.saveCheckpoint(updated)
        jobs.cancelled(updated.runId, detail)
    }

    private fun message(failure: Throwable): String = failure.message ?: failure.javaClass.simpleName

    private data class ProjectResolution(
        val project: ApkProject?,
        val ambiguous: Boolean,
        val detail: String,
    )
}
