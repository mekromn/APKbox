package com.mekromn.apkbox.data

import com.mekromn.apkbox.model.ChunkRef
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class ChunkingResult(
    val chunks: List<ChunkRef>,
    val apkSha256: String,
    val uniqueBytesAdded: Long,
)

/**
 * Content-defined, vault-wide chunk store.
 *
 * Boundaries are based only on file content (Gear-style rolling hash), so insertions or edits in
 * one part of an APK do not permanently shift later boundaries. Chunks are addressed solely by
 * SHA-256 under one shared chunks directory. Therefore every ingest checks against the entire
 * existing vault: the base APK and every revision imported before it. A chunk introduced by
 * revision A can be reused directly by revisions B, C, or any later build even when that chunk
 * never existed in the base APK.
 */
internal class ChunkStore(private val chunksDir: File) {
    companion object {
        private const val MIN_CHUNK = 64 * 1024
        private const val TARGET_CHUNK = 256 * 1024
        private const val MAX_CHUNK = 1024 * 1024
        private const val READ_BUFFER = 1024 * 1024
        private const val BOUNDARY_MASK = TARGET_CHUNK - 1L

        private val HEX = "0123456789abcdef".toCharArray()

        private val GEAR = LongArray(256).also { table ->
            var x = 0x1234ABCD5678EF01L
            for (i in table.indices) {
                x = x xor (x shl 13)
                x = x xor (x ushr 7)
                x = x xor (x shl 17)
                table[i] = x
            }
        }
    }

    init {
        chunksDir.mkdirs()
    }

    fun ingest(file: File): ChunkingResult {
        val chunkRefs = ArrayList<ChunkRef>()
        val wholeApkDigest = MessageDigest.getInstance("SHA-256")
        val chunkDigest = MessageDigest.getInstance("SHA-256")
        var uniqueBytesAdded = 0L
        var rollingHash = 0L
        var chunkSize = 0

        // A fixed backing array removes ByteArrayOutputStream.write(int) on every source byte and
        // avoids the old toByteArray() copy at every boundary. MAX_CHUNK already caps the size.
        val chunkBytes = ByteArray(MAX_CHUNK)

        fun commitChunk() {
            if (chunkSize == 0) return

            chunkDigest.reset()
            chunkDigest.update(chunkBytes, 0, chunkSize)
            val hash = fastHex(chunkDigest.digest())
            val target = chunkFile(hash)

            // This is deliberately global, not base-relative. Any existing hash came from some
            // previously stored APK in this vault and is immediately reusable by this revision.
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
                var installed = false
                try {
                    FileOutputStream(temp).use { output ->
                        output.write(chunkBytes, 0, chunkSize)
                        // Durability is unchanged: a newly introduced chunk is synced before it is
                        // published into the content-addressed vault.
                        output.fd.sync()
                    }
                    installed = temp.renameTo(target)
                    if (!installed && !target.exists()) {
                        runCatching {
                            FileInputStream(temp).use { input ->
                                FileOutputStream(target, false).use { output ->
                                    input.copyTo(output, 256 * 1024)
                                    output.fd.sync()
                                }
                            }
                            installed = true
                        }
                    }
                } finally {
                    temp.delete()
                }
                if (installed) uniqueBytesAdded += chunkSize.toLong()
            }

            chunkRefs += ChunkRef(hash = hash, size = chunkSize)
            chunkSize = 0
            rollingHash = 0L
        }

        FileInputStream(file).use { input ->
            val readBuffer = ByteArray(READ_BUFFER)
            while (true) {
                val count = input.read(readBuffer)
                if (count < 0) break
                if (count == 0) continue

                wholeApkDigest.update(readBuffer, 0, count)
                for (index in 0 until count) {
                    val value = readBuffer[index]
                    chunkBytes[chunkSize] = value
                    chunkSize++
                    rollingHash = (rollingHash shl 1) + GEAR[value.toInt() and 0xFF]

                    // This boundary expression is intentionally byte-for-byte equivalent to the
                    // original implementation. Performance changes must never alter CDC layout.
                    val boundary = chunkSize >= MIN_CHUNK &&
                        ((rollingHash and BOUNDARY_MASK) == 0L || chunkSize >= MAX_CHUNK)
                    if (boundary) commitChunk()
                }
            }
        }
        commitChunk()

        return ChunkingResult(
            chunks = chunkRefs,
            apkSha256 = fastHex(wholeApkDigest.digest()),
            uniqueBytesAdded = uniqueBytesAdded,
        )
    }

    fun chunkFile(hash: String): File =
        File(File(chunksDir, hash.take(2)), "$hash.chunk")

    fun garbageCollect(referencedHashes: Set<String>) {
        if (!chunksDir.exists()) return
        chunksDir.walkBottomUp().forEach { file ->
            when {
                file.isFile && file.extension == "chunk" -> {
                    if (file.nameWithoutExtension !in referencedHashes) file.delete()
                }
                file.isDirectory && file != chunksDir && file.listFiles().isNullOrEmpty() -> file.delete()
            }
        }
    }

    private fun fastHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        var out = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            chars[out++] = HEX[value ushr 4]
            chars[out++] = HEX[value and 0x0F]
        }
        return String(chars)
    }
}
