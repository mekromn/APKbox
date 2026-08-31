package com.mekromn.apkbox.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class ChunkStoreTest {
    @Test
    fun changedRevisionReusesChunksAndReconstructsExactly() {
        val root = Files.createTempDirectory("apkbox-chunk-test").toFile()
        try {
            val chunksDir = File(root, "chunks")
            val store = ChunkStore(chunksDir)

            val baseBytes = Random(42).nextBytes(8 * 1024 * 1024)
            val revisedBytes = baseBytes.copyOf()
            for (index in 3_100_000 until 3_100_512) {
                revisedBytes[index] = (revisedBytes[index].toInt() xor 0x5A).toByte()
            }

            val baseFile = File(root, "base.apk").apply { writeBytes(baseBytes) }
            val revisedFile = File(root, "revision.apk").apply { writeBytes(revisedBytes) }

            val base = store.ingest(baseFile)
            val revision = store.ingest(revisedFile)

            assertArrayEquals(baseBytes, reconstruct(store, base))
            assertArrayEquals(revisedBytes, reconstruct(store, revision))
            assertTrue(
                "A localized revision should reuse most of the original APK chunks",
                revision.uniqueBytesAdded < revisedBytes.size / 2,
            )

            val duplicateImport = store.ingest(revisedFile)
            assertEquals(revision.apkSha256, duplicateImport.apkSha256)
            assertEquals(0L, duplicateImport.uniqueBytesAdded)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun reconstruct(store: ChunkStore, result: ChunkingResult): ByteArray {
        val output = ByteArrayOutputStream()
        result.chunks.forEach { chunk ->
            val bytes = store.chunkFile(chunk.hash).readBytes()
            assertEquals(chunk.size, bytes.size)
            output.write(bytes)
        }
        return output.toByteArray()
    }
}
