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

    @Test
    fun laterRevisionReusesChunksIntroducedByEarlierRevisionNotPresentInBase() {
        val root = Files.createTempDirectory("apkbox-cross-revision-test").toFile()
        try {
            val store = ChunkStore(File(root, "chunks"))

            // Base does not contain the 3 MiB feature payload below.
            val baseBytes = Random(101).nextBytes(12 * 1024 * 1024)
            val revisionABytes = baseBytes.copyOf()
            val featureStart = 4 * 1024 * 1024
            val featureBytes = Random(202).nextBytes(3 * 1024 * 1024)
            featureBytes.copyInto(revisionABytes, destinationOffset = featureStart)

            // Revision B keeps revision A's large feature payload and changes something elsewhere.
            // A base-only deduper would have to store that feature payload again for B. A true
            // vault-wide deduper must reuse the chunks that revision A introduced.
            val revisionBBytes = revisionABytes.copyOf()
            for (index in 10_000_000 until 10_000_512) {
                revisionBBytes[index] = (revisionBBytes[index].toInt() xor 0x33).toByte()
            }

            val base = store.ingest(File(root, "base.apk").apply { writeBytes(baseBytes) })
            val revisionA = store.ingest(File(root, "revision-a.apk").apply { writeBytes(revisionABytes) })
            val revisionB = store.ingest(File(root, "revision-b.apk").apply { writeBytes(revisionBBytes) })

            assertArrayEquals(baseBytes, reconstruct(store, base))
            assertArrayEquals(revisionABytes, reconstruct(store, revisionA))
            assertArrayEquals(revisionBBytes, reconstruct(store, revisionB))

            val baseHashes = base.chunks.asSequence().map { it.hash }.toHashSet()
            val revisionBHashes = revisionB.chunks.asSequence().map { it.hash }.toHashSet()
            val bytesReusedFromRevisionAOnly = revisionA.chunks
                .asSequence()
                .filter { it.hash !in baseHashes && it.hash in revisionBHashes }
                .sumOf { it.size.toLong() }

            assertTrue(
                "Revision B must reuse substantial data that exists in revision A but not in the base",
                bytesReusedFromRevisionAOnly >= 2L * 1024 * 1024,
            )
            assertTrue(
                "Revision B should add only the localized second change, not revision A's feature payload again",
                revisionB.uniqueBytesAdded < 2L * 1024 * 1024,
            )
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
