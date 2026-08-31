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
    private val appContext = context.applicationContext

    suspend fun install(record: ApkRecord) = withContext(Dispatchers.IO) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(record.packageName)
            setSize(record.sizeBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("base.apk", 0, record.sizeBytes).use { output ->
                // LibraryStore validates byte count + full original SHA-256 before commit.
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
            runCatching { session.abandon() }
            throw t
        } finally {
            session.close()
        }
    }
}
