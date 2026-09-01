package com.mekromn.apkbox.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.StatFs
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.data.TempStorageManager
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // Reclaim old full-size scratch APKs before asking Android for another large staging area.
        TempStorageManager.cleanupRoutine(appContext)

        // Do not allow multiple full APK staging copies to accumulate in PackageInstaller.
        cleanupStaleSessions()
        val existingSessions = runCatching { installer.mySessions }.getOrDefault(emptyList())
        check(existingSessions.isEmpty()) {
            "Another APKbox install is already staged. Finish or cancel that install first, or use ‘Free temporary install space’. APKbox will not stage multiple full APK copies at once."
        }

        // Read the compact binary manifest once. It is the authority for install size and ordering.
        val plan = fastStager.plan(record)
        val exactSize = plan.exactSize

        // Android can briefly need both a full staging copy and the destination package copy.
        // Keep an additional reserve so APKbox refuses early instead of filling /data mid-install.
        val availableBytes = runCatching { StatFs(appContext.filesDir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        val requiredBytes = exactSize * 2L + SAFETY_RESERVE_BYTES
        check(availableBytes >= requiredBytes) {
            "Not enough free space to safely stage ${record.displayName}. APKbox needs about ${toMiB(requiredBytes)} MiB free for this ${toMiB(exactSize)} MiB APK, but only ${toMiB(availableBytes)} MiB is available. Free space first; nothing was staged."
        }

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
            session.openWrite("base.apk", 0, exactSize).use { output ->
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

                // Durability semantics are unchanged: PackageInstaller is fsynced before commit.
                session.fsync(output)
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
            // Covers failures both before and after openSession; no orphan staging allocation remains.
            runCatching { installer.abandonSession(sessionId) }
            throw t
        } finally {
            runCatching { session?.close() }
        }
    }

    private fun toMiB(bytes: Long): Long =
        if (bytes <= 0L) 0L else (bytes + MIB - 1L) / MIB
}
