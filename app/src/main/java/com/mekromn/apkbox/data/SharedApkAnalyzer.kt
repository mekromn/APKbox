package com.mekromn.apkbox.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class SharedApkPreview(
    val uri: Uri,
    val displayName: String,
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val sha256: String,
    val storedMatches: List<ApkRecord>,
)

internal object SharedApkAnalyzer {
    suspend fun analyze(
        context: Context,
        uris: List<Uri>,
        records: List<ApkRecord>,
    ): List<SharedApkPreview> = withContext(Dispatchers.IO) {
        uris.distinct().map { uri -> analyzeOne(context, uri, records) }
    }

    private fun analyzeOne(
        context: Context,
        uri: Uri,
        records: List<ApkRecord>,
    ): SharedApkPreview {
        val temp = File(context.cacheDir, "apkbox-shared-${UUID.randomUUID()}.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        size += count
                    }
                    output.fd.sync()
                }
            } ?: error("A shared APK could not be opened.")

            require(size > 0L) { "A shared APK is empty." }
            val archive = ApkInspector.inspect(context, temp)
            val sha = digest.digest().toHex()
            return SharedApkPreview(
                uri = uri,
                displayName = displayName(context, uri) ?: archive.label,
                packageName = archive.packageName,
                label = archive.label,
                versionName = archive.versionName,
                versionCode = archive.versionCode,
                sizeBytes = size,
                sha256 = sha,
                storedMatches = records.filter { it.sha256 == sha },
            )
        } finally {
            temp.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
