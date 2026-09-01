package com.mekromn.apkbox.data

import android.content.Context
import java.io.File

/** Cleans full-size scratch APKs and APKbox-owned PackageInstaller staging sessions. */
object TempStorageManager {
    private const val SCRATCH_MAX_AGE_MS = 5L * 60L * 1000L
    private const val INSTALL_SESSION_MAX_AGE_MS = 10L * 60L * 1000L

    data class CleanupResult(
        val filesDeleted: Int,
        val bytesDeleted: Long,
        val installerSessionsAbandoned: Int,
    )

    fun cleanupStartup(context: Context): CleanupResult {
        val files = cleanupFiles(context, deleteAllScratch = true, deleteAllShares = true)
        return files.copy(
            installerSessionsAbandoned = abandonInstallerSessions(
                context = context,
                olderThanMillis = INSTALL_SESSION_MAX_AGE_MS,
            )
        )
    }

    fun cleanupRoutine(context: Context): CleanupResult {
        val files = cleanupFiles(context, deleteAllScratch = false, deleteAllShares = false)
        return files.copy(
            installerSessionsAbandoned = abandonInstallerSessions(
                context = context,
                olderThanMillis = INSTALL_SESSION_MAX_AGE_MS,
            )
        )
    }

    /** Explicit user-requested emergency cleanup. Cancels every still-pending APKbox install. */
    fun cleanupAll(context: Context): CleanupResult {
        val files = cleanupFiles(context, deleteAllScratch = true, deleteAllShares = true)
        return files.copy(
            installerSessionsAbandoned = abandonInstallerSessions(
                context = context,
                olderThanMillis = 0L,
            )
        )
    }

    private fun abandonInstallerSessions(context: Context, olderThanMillis: Long): Int {
        val installer = context.applicationContext.packageManager.packageInstaller
        val now = System.currentTimeMillis()
        var abandoned = 0
        runCatching { installer.mySessions }.getOrDefault(emptyList()).forEach { info ->
            if (olderThanMillis == 0L || now - info.createdMillis >= olderThanMillis) {
                if (runCatching { installer.abandonSession(info.sessionId) }.isSuccess) abandoned++
            }
        }
        return abandoned
    }

    private fun cleanupFiles(
        context: Context,
        deleteAllScratch: Boolean,
        deleteAllShares: Boolean,
    ): CleanupResult {
        val cache = context.applicationContext.cacheDir
        val now = System.currentTimeMillis()
        var filesDeleted = 0
        var bytesDeleted = 0L

        fun delete(file: File) {
            if (!file.isFile) return
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                filesDeleted++
                bytesDeleted += size
            }
        }

        cache.listFiles()?.forEach { file ->
            val apkboxScratch = file.isFile && file.name.endsWith(".apk") && (
                file.name.startsWith("apkbox-import-") ||
                    file.name.startsWith("apkbox-icon-") ||
                    file.name.startsWith("apkbox-gateway-") ||
                    file.name.startsWith("apkbox-shared-")
                )
            if (apkboxScratch && (deleteAllScratch || now - file.lastModified() >= SCRATCH_MAX_AGE_MS)) {
                delete(file)
            }
        }

        val shareDir = File(cache, "share")
        shareDir.listFiles()?.forEach { file ->
            if (file.isFile && (deleteAllShares || now - file.lastModified() >= SCRATCH_MAX_AGE_MS)) {
                delete(file)
            }
        }
        if (shareDir.isDirectory && shareDir.listFiles().isNullOrEmpty()) shareDir.delete()

        return CleanupResult(
            filesDeleted = filesDeleted,
            bytesDeleted = bytesDeleted,
            installerSessionsAbandoned = 0,
        )
    }
}
