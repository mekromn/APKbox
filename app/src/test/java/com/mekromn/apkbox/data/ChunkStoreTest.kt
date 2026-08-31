package com.mekromn.apkbox.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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

    @Test
    fun fiftyRevisionVaultRoundTripPreservesEveryFileAndHash() {
        val root = Files.createTempDirectory("apkbox-fifty-file-torture-test").toFile()
        try {
            val store = ChunkStore(File(root, "chunks"))
            val base = Random(9001).nextBytes(2 * 1024 * 1024)
            val originals = ArrayList<ByteArray>(50)
            val stored = ArrayList<ChunkingResult>(50)

            // Generate a realistic family: most bytes stay identical, each build has localized
            // edits, and groups of builds share feature payloads that never existed in the base.
            val sharedFeatures = List(5) { featureIndex ->
                Random(10_000 + featureIndex).nextBytes(192 * 1024)
            }

            repeat(50) { revisionIndex ->
                val bytes = base.copyOf()

                // A group feature is shared by ten revisions and gives the vault opportunities to
                // reuse data introduced by revisions rather than by the original base.
                val feature = sharedFeatures[revisionIndex / 10]
                val featureStart = 320 * 1024 + (revisionIndex / 10) * 256 * 1024
                feature.copyInto(bytes, destinationOffset = featureStart)

                // Each revision also carries a unique localized change.
                val changeStart = 1_650_000 + revisionIndex * 2_048
                repeat(1_024) { offset ->
                    val index = changeStart + offset
                    bytes[index] = (bytes[index].toInt() xor (revisionIndex + offset + 1)).toByte()
                }

                // Vary a small header-like region so no two test files are accidentally identical.
                repeat(32) { offset ->
                    bytes[4_096 + offset] = (revisionIndex * 31 + offset).toByte()
                }

                val file = File(root, "revision-${revisionIndex.toString().padStart(2, '0')}.apk")
                    .apply { writeBytes(bytes) }
                val result = store.ingest(file)

                originals += bytes
                stored += result
                assertEquals("Ingest SHA must equal original SHA", sha256(bytes), result.apkSha256)
            }

            assertEquals(50, originals.size)
            assertEquals(50, stored.size)

            stored.forEachIndexed { index, result ->
                val reconstructed = reconstruct(store, result)
                val original = originals[index]

                assertEquals("Revision $index reconstructed size", original.size, reconstructed.size)
                assertArrayEquals("Revision $index must reconstruct byte-for-byte", original, reconstructed)
                assertEquals("Revision $index original SHA", result.apkSha256, sha256(original))
                assertEquals("Revision $index reconstructed SHA", result.apkSha256, sha256(reconstructed))
            }

            val logicalBytes = originals.sumOf { it.size.toLong() }
            val physicalChunkBytes = File(root, "chunks").walkTopDown()
                .filter { it.isFile && it.extension == "chunk" }
                .sumOf { it.length() }
            assertTrue(
                "Fifty related revisions should occupy substantially less space than fifty full copies",
                physicalChunkBytes < logicalBytes / 2,
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
