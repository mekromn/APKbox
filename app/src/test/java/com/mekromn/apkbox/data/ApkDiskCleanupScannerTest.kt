package com.mekromn.apkbox.data

import com.mekromn.apkbox.model.ApkRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

class ApkDiskCleanupScannerTest {
    @Test
    fun scanMarksOnlyByteExactVaultCopiesAsSafelyStored() {
        val root = Files.createTempDirectory("apkbox-cleanup-scan-test").toFile()
        try {
            val storedBytes = Random(71).nextBytes(512 * 1024)
            val sameSizeDifferentBytes = storedBytes.copyOf().also {
                it[12345] = (it[12345].toInt() xor 0x5A).toByte()
            }

            val renamedExactCopy = File(root, "renamed-copy.apk").apply { writeBytes(storedBytes) }
            val differentSameSize = File(root, "same-size-different.apk").apply { writeBytes(sameSizeDifferentBytes) }
            val nested = File(root, "nested").apply { mkdirs() }
            val secondExactCopy = File(nested, "another-exact-copy.apk").apply { writeBytes(storedBytes) }
            File(root, "ignore-me.txt").writeText("not an apk")

            val record = ApkRecord(
                id = "record-1",
                projectId = "project-1",
                displayName = "Stored build.apk",
                label = "Stored build",
                packageName = "com.example.test",
                versionName = "1.0",
                versionCode = 1,
                sizeBytes = storedBytes.size.toLong(),
                sha256 = sha256(storedBytes),
                signingCertSha256 = null,
                addedAtEpochMs = 1L,
                isBase = true,
                chunkCount = 1,
                newBytesAdded = storedBytes.size.toLong(),
            )

            val result = ApkDiskCleanupScanner.scan(root, listOf(record))
            assertEquals(3, result.candidates.size)

            val renamed = result.candidates.single { it.path == renamedExactCopy.absolutePath }
            val different = result.candidates.single { it.path == differentSameSize.absolutePath }
            val nestedExact = result.candidates.single { it.path == secondExactCopy.absolutePath }

            assertTrue(renamed.isSafelyStored)
            assertNotNull(renamed.storedRecord)
            assertTrue(nestedExact.isSafelyStored)
            assertEquals(record.sha256, nestedExact.storedRecord?.sha256)

            // File size alone is never enough to make deletion safe.
            assertFalse(different.isSafelyStored)
            assertNull(different.storedRecord)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
