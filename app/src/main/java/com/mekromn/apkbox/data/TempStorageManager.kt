package com.mekromn.apkbox.data

import android.content.Context
import java.io.File

/** Cleans full-size scratch APKs that must never become part of APKbox's persistent vault. */
object TempStorageManager {
    private const val SHARE_MAX_AGE_MS = 5L * 60L * 1000L

    data class CleanupResult(
        val filesDeleted: Int,
        val bytesDeleted: Long,
    )

    fun cleanupStartup(context: Context): CleanupResult = cleanup(
        context = context,
        deleteAllImports = true,
        deleteAllShares = true,
    )

    fun cleanupRoutine(context: Context): CleanupResult = cleanup(
        context = context,
        deleteAllImports = false,
        deleteAllShares = false,
    )

    fun cleanupAll(context: Context): CleanupResult = cleanup(
        context = context,
        deleteAllImports = true,
        deleteAllShares = true,
    )

    private fun cleanup(
        context: Context,
        deleteAllImports: Boolean,
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
            if (file.isFile && file.name.startsWith("apkbox-import-") && file.name.endsWith(".apk")) {
                // Normal imports delete these in finally. Any file still present at process startup
                // or old enough during routine cleanup is an orphan from interruption/crash.
                if (deleteAllImports || now - file.lastModified() >= SHARE_MAX_AGE_MS) delete(file)
            }
        }

        val shareDir = File(cache, "share")
        shareDir.listFiles()?.forEach { file ->
            if (file.isFile && (deleteAllShares || now - file.lastModified() >= SHARE_MAX_AGE_MS)) {
                delete(file)
            }
        }
        if (shareDir.isDirectory && shareDir.listFiles().isNullOrEmpty()) shareDir.delete()

        return CleanupResult(filesDeleted, bytesDeleted)
    }
}
