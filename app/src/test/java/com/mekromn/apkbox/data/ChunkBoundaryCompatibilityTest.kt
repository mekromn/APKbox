package com.mekromn.apkbox.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class ChunkBoundaryCompatibilityTest {
    companion object {
        private const val MIN_CHUNK = 64 * 1024
        private const val TARGET_CHUNK = 256 * 1024
        private const val MAX_CHUNK = 1024 * 1024
        private const val BOUNDARY_MASK = TARGET_CHUNK - 1L

        // Exact legacy table-generation algorithm from the original APKbox CDC implementation.
        private val LEGACY_GEAR = LongArray(256).also { table ->
            var x = 0x1234ABCD5678EF01L
            for (i in table.indices) {
                x = x xor (x shl 13)
                x = x xor (x ushr 7)
                x = x xor (x shl 17)
                table[i] = x
            }
        }
    }

    @Test
    fun optimizedChunkerPreservesLegacyBoundarySequenceExactly() {
        val root = Files.createTempDirectory("apkbox-boundary-compat").toFile()
        try {
            val store = ChunkStore(File(root, "chunks"))
            val inputs = listOf(
                Random(77).nextBytes(11 * 1024 * 1024 + 137),
                ByteArray(5 * 1024 * 1024 + 31) { index -> (index * 17 + index / 113).toByte() },
                ByteArray(3 * 1024 * 1024 + 7),
            )

            inputs.forEachIndexed { index, bytes ->
                val file = File(root, "compat-$index.apk").apply { writeBytes(bytes) }
                val result = store.ingest(file)
                assertEquals(
                    "Optimizations must never move content-defined chunk boundaries (case $index)",
                    legacyChunkSizes(bytes),
                    result.chunks.map { it.size },
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun legacyChunkSizes(bytes: ByteArray): List<Int> {
        val sizes = ArrayList<Int>()
        var rollingHash = 0L
        var chunkSize = 0

        for (value in bytes) {
            chunkSize++
            rollingHash = (rollingHash shl 1) + LEGACY_GEAR[value.toInt() and 0xFF]
            val boundary = chunkSize >= MIN_CHUNK &&
                ((rollingHash and BOUNDARY_MASK) == 0L || chunkSize >= MAX_CHUNK)
            if (boundary) {
                sizes += chunkSize
                chunkSize = 0
                rollingHash = 0L
            }
        }
        if (chunkSize > 0) sizes += chunkSize
        return sizes
    }
}
