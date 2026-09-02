package com.mekromn.apkbox.agent

import android.content.Context
import android.net.Uri
import com.mekromn.apkbox.bridge.AdbBridgeManager
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.LibraryStore
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
    private val adb: AdbBridgeManager,
) {
    private val appContext = context.applicationContext
    private val store = BuildRunStore(appContext)
    private val credentials = BuildSourceCredentials(appContext)
    private val downloader = BuildDownloader(store)
    private val mutex = Mutex()

    suspend fun run(candidate: BuildCandidate): BuildRunCheckpoint = mutex.withLock {
        store.saveCandidate(candidate)
        val prior = store.loadCheckpoint(candidate.runId)
        if (prior != null && prior.buildId == candidate.buildId && prior.apkSha256.equals(candidate.expectedApkSha256, true)) {
            when (prior.state) {
                BuildRunState.PASSED,
                BuildRunState.TESTING -> return@withLock prior
                BuildRunState.INSTALLING,
                BuildRunState.LAUNCHING -> {
                    val installedSha = installedPackageSha256(candidate.targetPackage)
                    if (installedSha.equals(candidate.expectedApkSha256, ignoreCase = true)) {
                        var resumed = prior.copy(
                            state = BuildRunState.LAUNCHING,
                            detail = "Recovered after process interruption: installed base.apk matches the exact candidate SHA.",
                            updatedAtEpochMs = System.currentTimeMillis(),
                        )
                        store.saveCheckpoint(resumed)
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

        if (candidate.requiresBuildToken && !credentials.hasToken()) {
            return@withLock fail(
                checkpoint,
                BuildRunState.BLOCKED_AUTH_REQUIRED,
                "Private build source requires the separately encrypted read-only build-source token.",
            )
        }

        val token = if (candidate.requiresBuildToken) credentials.readToken() else null
        checkpoint = transition(checkpoint, BuildRunState.DOWNLOADING, "Downloading build artifact.")
        var lastPersistAt = 0L
        var lastPersistBytes = -1L
        val apkFile = try {
            downloader.obtainApk(candidate, token) { downloaded, total ->
                val now = System.currentTimeMillis()
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
            }
        } catch (failure: Throwable) {
            return@withLock fail(checkpoint, BuildRunState.FAILED, "Build download failed: ${message(failure)}")
        }

        checkpoint = transition(
            checkpoint.copy(apkPath = apkFile.absolutePath),
            BuildRunState.VERIFYING,
            "Verifying exact APK SHA-256 and package metadata.",
        )
        val actualSha = runCatching { downloader.sha256(apkFile) }.getOrElse { failure ->
            return@withLock fail(checkpoint, BuildRunState.FAILED, "Could not hash downloaded APK: ${message(failure)}")
        }
        checkpoint = checkpoint.copy(apkSha256 = actualSha, updatedAtEpochMs = System.currentTimeMillis())
        store.saveCheckpoint(checkpoint)
        if (!actualSha.equals(candidate.expectedApkSha256, ignoreCase = true)) {
            return@withLock fail(
                checkpoint,
                BuildRunState.FAILED,
                "APK SHA-256 mismatch. Expected ${candidate.expectedApkSha256}, got $actualSha. Nothing was archived or installed.",
            )
        }

        val archive = runCatching { ApkInspector.inspect(appContext, apkFile) }.getOrElse { failure ->
            return@withLock fail(checkpoint, BuildRunState.FAILED, "Android could not parse verified APK: ${message(failure)}")
        }
        if (archive.packageName != candidate.targetPackage) {
            return@withLock fail(
                checkpoint,
                BuildRunState.FAILED,
                "Verified APK package is ${archive.packageName}, expected ${candidate.targetPackage}. Nothing was installed.",
            )
        }

        val projectResolution = resolveProject(candidate, archive.label)
        if (projectResolution.ambiguous) {
            return@withLock fail(checkpoint, BuildRunState.BLOCKED_PROJECT_AMBIGUOUS, projectResolution.detail)
        }

        checkpoint = transition(
            checkpoint.copy(projectId = projectResolution.project?.id.orEmpty()),
            BuildRunState.ARCHIVING,
            "Archiving exact verified APK bytes into APKbox before installation.",
        )
        val record = try {
            archiveCandidate(candidate, apkFile, actualSha, projectResolution.project, archive.label)
        } catch (failure: Throwable) {
            return@withLock fail(checkpoint, BuildRunState.FAILED, "APKbox archive failed: ${message(failure)}")
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
            )
        }

        if (!candidate.autoInstall) {
            return@withLock pass(checkpoint, "Verified build archived; autoInstall=false so device state was not changed.")
        }

        val alreadyInstalledSha = installedPackageSha256(candidate.targetPackage)
        if (alreadyInstalledSha.equals(actualSha, ignoreCase = true)) {
            checkpoint = transition(
                checkpoint,
                BuildRunState.LAUNCHING,
                "Exact candidate is already installed; skipping duplicate package-manager mutation.",
            )
            return@withLock finishAfterInstalled(candidate, checkpoint)
        }

        checkpoint = transition(checkpoint, BuildRunState.INSTALLING, "Installing archived candidate unattended through paired Wireless ADB.")
        lastPersistBytes = -1L
        lastPersistAt = 0L
        val install = runCatching {
            adb.installApk(apkFile, allowDowngrade = candidate.allowDowngrade) { sent, total ->
                val now = System.currentTimeMillis()
                if (sent == total || sent - lastPersistBytes >= 16L * 1024L * 1024L || now - lastPersistAt >= 2_000L) {
                    checkpoint = checkpoint.copy(
                        downloadedBytes = sent,
                        expectedBytes = total,
                        detail = "Unattended install: $sent / $total bytes streamed.",
                        updatedAtEpochMs = now,
                    )
                    store.saveCheckpoint(checkpoint)
                    lastPersistAt = now
                    lastPersistBytes = sent
                }
            }
        }.getOrElse { failure ->
            return@withLock fail(checkpoint, BuildRunState.FAILED, "Unattended install transport failed: ${message(failure)}")
        }
        if (!install.success) {
            return@withLock fail(
                checkpoint,
                BuildRunState.FAILED,
                "Android package manager rejected the candidate: ${install.output.take(2_000)}",
            )
        }

        val installedSha = installedPackageSha256(candidate.targetPackage)
        if (!installedSha.equals(actualSha, ignoreCase = true)) {
            return@withLock fail(
                checkpoint,
                BuildRunState.FAILED,
                "Package manager reported success, but installed base.apk SHA '$installedSha' does not match verified candidate $actualSha.",
            )
        }

        checkpoint = transition(checkpoint, BuildRunState.LAUNCHING, "Exact installed base.apk SHA verified after unattended install.")
        finishAfterInstalled(candidate, checkpoint)
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
        return updated
    }

    private suspend fun finishAfterInstalled(
        candidate: BuildCandidate,
        initial: BuildRunCheckpoint,
    ): BuildRunCheckpoint {
        var checkpoint = initial
        if (candidate.autoLaunch) {
            checkpoint = transition(checkpoint, BuildRunState.LAUNCHING, "Launching newly installed ${candidate.targetPackage}.")
            val launch = runCatching {
                adb.execute("monkey -p ${candidate.targetPackage} -c android.intent.category.LAUNCHER 1", 15)
            }.getOrElse { failure ->
                return fail(checkpoint, BuildRunState.FAILED, "Installed build could not be launched: ${message(failure)}")
            }
            if (launch.timedOut || (launch.exitCode != null && launch.exitCode != 0)) {
                return fail(checkpoint, BuildRunState.FAILED, "Installed build launch failed: ${launch.output.take(2_000)}")
            }
        }

        if (candidate.planRunId.isNotBlank()) {
            return transition(
                checkpoint,
                BuildRunState.TESTING,
                "Installed and launched successfully; awaiting autonomous test plan ${candidate.planRunId}.",
            )
        }
        return pass(checkpoint, "Verified, archived, unattended-installed${if (candidate.autoLaunch) ", and launched" else ""} successfully.")
    }

    private suspend fun installedPackageSha256(packageName: String): String {
        val pathResult = runCatching { adb.execute("pm path $packageName", 10) }.getOrNull() ?: return ""
        val path = pathResult.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.endsWith("/base.apk") }
            ?.removePrefix("package:")
            ?: return ""
        if (!path.matches(Regex("[/A-Za-z0-9._=:+-]+"))) return ""
        val hashResult = runCatching { adb.execute("sha256sum $path", 20) }.getOrNull() ?: return ""
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
    ).also(store::saveCheckpoint)

    private fun fail(
        checkpoint: BuildRunCheckpoint,
        state: BuildRunState,
        detail: String,
    ): BuildRunCheckpoint = checkpoint.copy(
        state = state,
        detail = detail.take(4_096),
        updatedAtEpochMs = System.currentTimeMillis(),
    ).also(store::saveCheckpoint)

    private fun pass(checkpoint: BuildRunCheckpoint, detail: String): BuildRunCheckpoint = checkpoint.copy(
        state = BuildRunState.PASSED,
        detail = detail.take(4_096),
        updatedAtEpochMs = System.currentTimeMillis(),
    ).also(store::saveCheckpoint)

    private fun message(failure: Throwable): String = failure.message ?: failure.javaClass.simpleName

    private data class ProjectResolution(
        val project: ApkProject?,
        val ambiguous: Boolean,
        val detail: String,
    )
}
