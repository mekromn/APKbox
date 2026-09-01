package com.mekromn.apkbox.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
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
    val iconPng: ByteArray? = null,
)

/**
 * A single exact temporary copy owned by the standalone installer gateway.
 *
 * The copy was hashed while being written and is hashed again while being staged into Android.
 * Keeping it alive eliminates a redundant vault reconstruction without weakening verification.
 */
internal data class PreparedSharedApk(
    val preview: SharedApkPreview,
    val file: File,
) : Closeable {
    override fun close() {
        runCatching { file.delete() }
    }
}

internal object SharedApkAnalyzer {
    private const val COPY_BUFFER_BYTES = 1024 * 1024

    suspend fun analyze(
        context: Context,
        uris: List<Uri>,
        records: List<ApkRecord>,
    ): List<SharedApkPreview> = withContext(Dispatchers.IO) {
        uris.distinct().map { uri ->
            prepareOne(context, uri, records, prefix = "apkbox-shared-").use { prepared ->
                prepared.preview
            }
        }
    }

    /**
     * Used only by OpenApkInstallerActivity. Caller owns [PreparedSharedApk] and must close it.
     */
    suspend fun prepareForInstall(
        context: Context,
        uri: Uri,
        records: List<ApkRecord>,
    ): PreparedSharedApk = withContext(Dispatchers.IO) {
        prepareOne(context, uri, records, prefix = "apkbox-gateway-")
    }

    private fun prepareOne(
        context: Context,
        uri: Uri,
        records: List<ApkRecord>,
        prefix: String,
    ): PreparedSharedApk {
        val temp = File(context.cacheDir, "$prefix${UUID.randomUUID()}.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        size += count
                    }
                    // This file is immediate scratch, not durable vault state. Closing the stream is
                    // sufficient for coherent subsequent reads; avoiding fd.sync() removes a full
                    // redundant flash flush from the open-APK critical path.
                }
            } ?: error("The APK could not be opened.")

            require(size > 0L) { "The APK is empty." }
            val archive = ApkInspector.inspect(context, temp)
            val iconPng = ApkInspector.renderApplicationIconPng(context, temp)
            val sha = digest.digest().toHex()
            return PreparedSharedApk(
                preview = SharedApkPreview(
                    uri = uri,
                    displayName = displayName(context, uri) ?: archive.label,
                    packageName = archive.packageName,
                    label = archive.label,
                    versionName = archive.versionName,
                    versionCode = archive.versionCode,
                    sizeBytes = size,
                    sha256 = sha,
                    storedMatches = records.filter { it.sha256 == sha },
                    iconPng = iconPng,
                ),
                file = temp,
            )
        } catch (t: Throwable) {
            temp.delete()
            throw t
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
