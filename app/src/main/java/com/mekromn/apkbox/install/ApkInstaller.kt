package com.mekromn.apkbox.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.StatFs
import com.mekromn.apkbox.bridge.AdbBridgeManager
import com.mekromn.apkbox.bridge.AdbInstallResult
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.data.TempStorageManager
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File

data class InstallProgress(
    val bytesWritten: Long,
    val totalBytes: Long,
    val directPreparedSource: Boolean,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesWritten.toDouble() / totalBytes)
            .coerceIn(0.0, 1.0).toFloat()
}

class ApkInstaller(
    context: Context,
    @Suppress("UNUSED_PARAMETER") libraryStore: LibraryStore,
) {
    companion object {
        private const val STALE_SESSION_MS = 10L * 60L * 1000L
        private const val SAFETY_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val MIB = 1024L * 1024L
        private const val INSTALL_WRITE_BUFFER_BYTES = 4 * 1024 * 1024
        private val PACKAGE_NAME_REGEX = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val PACKAGE_PATH_REGEX = Regex("[/A-Za-z0-9._=:+-]+")
    }

    private val appContext = context.applicationContext
    private val fastStager = FastApkStager(appContext)
    private val installer: PackageInstaller
        get() = appContext.packageManager.packageInstaller

    fun pendingSessionCount(): Int = runCatching { installer.mySessions.size }.getOrDefault(0)

    fun cleanupStaleSessions(maxAgeMillis: Long = STALE_SESSION_MS): Int {
        val now = System.currentTimeMillis()
        var abandoned = 0
        runCatching { installer.mySessions }.getOrDefault(emptyList()).forEach { info ->
            if (now - info.createdMillis >= maxAgeMillis) {
                if (runCatching { installer.abandonSession(info.sessionId) }.isSuccess) abandoned++
            }
        }
        return abandoned
    }

    fun abandonAllSessions(): Int {
        var abandoned = 0
        runCatching { installer.mySessions }.getOrDefault(emptyList()).forEach { info ->
            if (runCatching { installer.abandonSession(info.sessionId) }.isSuccess) abandoned++
        }
        return abandoned
    }

    /**
     * Stages an APK without changing a single byte.
     *
     * If [preparedSource] is supplied (the standalone Open-with-APKbox gateway), the already-read
     * source file is streamed directly into PackageInstaller and SHA-256 verified during that exact
     * write. Otherwise APKbox reconstructs from the vault with bounded parallel chunk prefetch,
     * still emitting bytes strictly in manifest order and verifying the whole outgoing SHA-256.
     */
    suspend fun install(
        record: ApkRecord,
        preparedSource: File? = null,
        onProgress: ((InstallProgress) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        TempStorageManager.cleanupRoutine(appContext)
        cleanupStaleSessions()
        val existingSessions = runCatching { installer.mySessions }.getOrDefault(emptyList())
        check(existingSessions.isEmpty()) {
            "Another APKbox install is already staged. Finish or cancel that install first, or use ‘Free temporary install space’. APKbox will not stage multiple full APK copies at once."
        }

        val plan = fastStager.plan(record)
        val exactSize = plan.exactSize
        requireFreeInstallSpace(record, exactSize)

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(record.packageName)
            setSize(exactSize)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
        }

        val sessionId = installer.createSession(params)
        var session: PackageInstaller.Session? = null
        try {
            session = installer.openSession(sessionId)
            session.openWrite("base.apk", 0, exactSize).use { rawOutput ->
                val output = BufferedOutputStream(rawOutput, INSTALL_WRITE_BUFFER_BYTES)
                stageExact(record, plan, preparedSource, output, onProgress)
                output.flush()
                session.fsync(rawOutput)
            }

            val callbackIntent = Intent(appContext, InstallResultReceiver::class.java).apply {
                putExtra(InstallResultReceiver.EXTRA_TARGET_PACKAGE, record.packageName)
                putExtra(InstallResultReceiver.EXTRA_TARGET_LABEL, record.label)
                putExtra(InstallResultReceiver.EXTRA_TARGET_VERSION, record.versionName)
            }
            val callback = PendingIntent.getBroadcast(
                appContext,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        } finally {
            runCatching { session?.close() }
        }
    }

    /**
     * User-selected silent install through APKbox's paired/self-healing Wireless ADB connection.
     * The APK is never materialized as an APKbox cache file. Instead FastApkStager reconstructs or
     * reads the prepared source straight into an ADB install session. Android is not allowed to
     * commit the session until FastApkStager has verified the complete outgoing SHA-256.
     */
    suspend fun installUnattended(
        record: ApkRecord,
        adb: AdbBridgeManager,
        preparedSource: File? = null,
        allowDowngrade: Boolean = false,
        onProgress: ((InstallProgress) -> Unit)? = null,
    ): AdbInstallResult = withContext(Dispatchers.IO) {
        require(PACKAGE_NAME_REGEX.matches(record.packageName)) { "Stored APK has an invalid package name." }
        TempStorageManager.cleanupRoutine(appContext)
        cleanupStaleSessions()
        val existingSessions = runCatching { installer.mySessions }.getOrDefault(emptyList())
        check(existingSessions.isEmpty()) {
            "A normal APKbox install is still staged. Finish or cancel it before starting an unattended install."
        }

        val plan = fastStager.plan(record)
        val exactSize = plan.exactSize
        requireFreeInstallSpace(record, exactSize)

        val result = adb.installVerifiedStream(
            totalBytes = exactSize,
            allowDowngrade = allowDowngrade,
        ) { rawOutput ->
            val output = BufferedOutputStream(rawOutput, INSTALL_WRITE_BUFFER_BYTES)
            stageExact(record, plan, preparedSource, output, onProgress)
            output.flush()
        }

        check(result.success) {
            "Android package manager rejected unattended install: ${result.output.take(2_000)}"
        }

        val installedSha = installedBaseApkSha256(adb, record.packageName)
        check(installedSha.equals(record.sha256, ignoreCase = true)) {
            "Unattended install reported success, but installed base.apk SHA-256 '$installedSha' does not match ${record.sha256}."
        }
        result
    }

    private suspend fun stageExact(
        record: ApkRecord,
        plan: FastApkStager.Plan,
        preparedSource: File?,
        output: BufferedOutputStream,
        onProgress: ((InstallProgress) -> Unit)?,
    ) {
        val progressBridge: (FastApkStager.Progress) -> Unit = { progress ->
            onProgress?.invoke(
                InstallProgress(
                    bytesWritten = progress.bytesWritten,
                    totalBytes = progress.totalBytes,
                    directPreparedSource = progress.source == FastApkStager.Source.PREPARED_FILE,
                )
            )
        }

        if (preparedSource?.isFile == true) {
            fastStager.stagePreparedFile(
                record = record,
                plan = plan,
                sourceFile = preparedSource,
                output = output,
                onProgress = progressBridge,
            )
        } else {
            fastStager.stageVault(
                record = record,
                plan = plan,
                output = output,
                onProgress = progressBridge,
            )
        }
    }

    private fun requireFreeInstallSpace(record: ApkRecord, exactSize: Long) {
        val availableBytes = runCatching { StatFs(appContext.filesDir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        val requiredBytes = exactSize * 2L + SAFETY_RESERVE_BYTES
        check(availableBytes >= requiredBytes) {
            "Not enough free space to safely stage ${record.displayName}. APKbox needs about ${toMiB(requiredBytes)} MiB free for this ${toMiB(exactSize)} MiB APK, but only ${toMiB(availableBytes)} MiB is available. Free space first; nothing was staged."
        }
    }

    private suspend fun installedBaseApkSha256(adb: AdbBridgeManager, packageName: String): String {
        val paths = adb.execute("pm path $packageName", 10)
        check(!paths.timedOut && (paths.exitCode == null || paths.exitCode == 0)) {
            "Could not verify the installed APK path."
        }
        val basePath = paths.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.endsWith("/base.apk") }
            ?.removePrefix("package:")
            .orEmpty()
        check(basePath.isNotBlank() && PACKAGE_PATH_REGEX.matches(basePath)) {
            "Android did not return a verifiable installed base.apk path."
        }

        val hash = adb.execute("sha256sum $basePath", 20)
        check(!hash.timedOut && (hash.exitCode == null || hash.exitCode == 0)) {
            "Could not verify installed base.apk SHA-256."
        }
        return Regex("(?i)^[0-9a-f]{64}").find(hash.output.trim())?.value?.lowercase().orEmpty()
    }

    private fun toMiB(bytes: Long): Long =
        if (bytes <= 0L) 0L else (bytes + MIB - 1L) / MIB
}
