package com.mekromn.apkbox.data

import com.mekromn.apkbox.model.ChunkRef
import java.io.ByteArrayOutputStream
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
 * A compact content-defined chunk store. Boundaries are based on a Gear-style rolling hash,
 * so inserting bytes in one part of an APK does not shift every subsequent chunk boundary.
 */
internal class ChunkStore(private val chunksDir: File) {
    companion object {
        private const val MIN_CHUNK = 64 * 1024
        private const val TARGET_CHUNK = 256 * 1024
        private const val MAX_CHUNK = 1024 * 1024
        private const val BOUNDARY_MASK = TARGET_CHUNK - 1L

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
        var uniqueBytesAdded = 0L
        var rollingHash = 0L
        var chunkSize = 0
        val chunkBuffer = ByteArrayOutputStream(MAX_CHUNK)

        fun commitChunk() {
            if (chunkSize == 0) return
            val bytes = chunkBuffer.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
            val target = chunkFile(hash)
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
                FileOutputStream(temp).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    if (!target.exists()) {
                        temp.copyTo(target, overwrite = false)
                    }
                    temp.delete()
                }
                if (target.exists()) uniqueBytesAdded += bytes.size
            }
            chunkRefs += ChunkRef(hash = hash, size = bytes.size)
            chunkBuffer.reset()
            chunkSize = 0
            rollingHash = 0L
        }

        FileInputStream(file).use { input ->
            val readBuffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(readBuffer)
                if (count < 0) break
                if (count == 0) continue

                wholeApkDigest.update(readBuffer, 0, count)
                for (index in 0 until count) {
                    val value = readBuffer[index]
                    chunkBuffer.write(value.toInt())
                    chunkSize++
                    rollingHash = (rollingHash shl 1) + GEAR[value.toInt() and 0xFF]

                    val boundary = chunkSize >= MIN_CHUNK &&
                        ((rollingHash and BOUNDARY_MASK) == 0L || chunkSize >= MAX_CHUNK)
                    if (boundary) commitChunk()
                }
            }
        }
        commitChunk()

        return ChunkingResult(
            chunks = chunkRefs,
            apkSha256 = wholeApkDigest.digest().toHex(),
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
}
