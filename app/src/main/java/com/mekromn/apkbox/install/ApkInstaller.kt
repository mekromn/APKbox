package com.mekromn.apkbox.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApkInstaller(
    context: Context,
    private val libraryStore: LibraryStore,
) {
    companion object {
        private const val STALE_SESSION_MS = 10L * 60L * 1000L
    }

    private val appContext = context.applicationContext
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

    suspend fun install(record: ApkRecord) = withContext(Dispatchers.IO) {
        // Do not allow multiple full APK staging copies to accumulate in PackageInstaller.
        cleanupStaleSessions()
        val existingSessions = runCatching { installer.mySessions }.getOrDefault(emptyList())
        check(existingSessions.isEmpty()) {
            "Another APKbox install is already staged. Finish or cancel that install first, or use ‘Free temporary install space’. APKbox will not stage multiple full APK copies at once."
        }

        // Never trust cached/index size metadata here. The ordered manifest is authoritative.
        val exactSize = libraryStore.authoritativeSize(record)
        require(exactSize > 0L) { "Stored APK manifest has an invalid size." }

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
                // LibraryStore validates manifest byte count + full original SHA-256 before commit.
                libraryStore.streamApk(record, output)
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
}
