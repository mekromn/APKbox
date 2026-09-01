package com.mekromn.apkbox.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class VaultChunkClaimsTest {
    @Test
    fun garbageCollectorPreservesClaimedChunkUntilReaderReleasesIt() {
        val root = Files.createTempDirectory("apkbox-claims").toFile()
        try {
            val chunksDir = File(root, "chunks")
            val store = ChunkStore(chunksDir)
            val source = File(root, "source.apk").apply {
                writeBytes(Random(9081).nextBytes(512 * 1024))
            }
            val ingest = store.ingest(source)
            val protected = ingest.chunks.first()
            val protectedFile = store.chunkFile(protected.hash)
            assertTrue(protectedFile.isFile)

            val claim = VaultChunkClaims.claim(root, listOf(protected.hash))
            try {
                // Nothing is logically referenced, but an active exact reader still owns this hash.
                store.garbageCollect(emptySet())
                assertTrue("GC deleted a chunk still owned by an active reader", protectedFile.isFile)
            } finally {
                claim.close()
            }

            store.garbageCollect(emptySet())
            assertFalse("Released unreferenced chunk should now be collectible", protectedFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
