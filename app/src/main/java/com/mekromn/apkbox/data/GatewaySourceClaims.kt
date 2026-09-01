package com.mekromn.apkbox.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File

/**
 * Process-local claim for an APK currently being handled by the standalone installer gateway.
 * Matching is intentionally cheap (display filename + size) because its purpose is only to defer
 * Auto Scanner work for a few seconds. A false-positive merely postpones one scan; it can never
 * archive/delete the wrong file.
 */
internal object GatewaySourceClaims {
    private data class Key(val normalizedName: String, val sizeBytes: Long)

    private val lock = Any()
    private val counts = HashMap<Key, Int>()

    fun claimUri(context: Context, uri: Uri): Closeable? {
        val key = resolveKey(context.applicationContext, uri) ?: return null
        synchronized(lock) {
            counts[key] = (counts[key] ?: 0) + 1
        }
        var closed = false
        return Closeable {
            synchronized(lock) {
                if (closed) return@Closeable
                closed = true
                val remaining = (counts[key] ?: 1) - 1
                if (remaining <= 0) counts.remove(key) else counts[key] = remaining
            }
        }
    }

    fun isClaimed(file: File): Boolean {
        val name = file.name.lowercase()
        val size = runCatching { file.length() }.getOrDefault(-1L)
        synchronized(lock) {
            return counts.keys.any { claim ->
                claim.normalizedName == name &&
                    (claim.sizeBytes <= 0L || size <= 0L || claim.sizeBytes == size)
            }
        }
    }

    private fun resolveKey(context: Context, uri: Uri): Key? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let(::File) ?: return null
            return Key(file.name.lowercase(), runCatching { file.length() }.getOrDefault(-1L))
        }

        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn < 0) return@use null
                val name = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: return@use null
                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else -1L
                Key(name.lowercase(), size)
            }
        }.getOrNull()
    }
}
