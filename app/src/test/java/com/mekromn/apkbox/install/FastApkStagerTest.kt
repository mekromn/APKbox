package com.mekromn.apkbox.install

import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

class FastApkStagerTest {
    companion object {
        private const val INSTALL_STYLE_BUFFER = 4 * 1024 * 1024
    }

    @Test
    fun parallelVaultAndPreparedSourceStageIdenticalExactBytes() {
        runBlocking {
            val root = Files.createTempDirectory("apkbox-fast-stager").toFile()
            try {
                val recordId = "speed-test-record"
                val payloads = List(24) { index ->
                    // Vary chunk sizes while staying below APKbox's 1 MiB maximum.
                    Random(7000 + index).nextBytes(72 * 1024 + index * 11_137)
                }
                val exactBytes = payloads.fold(ByteArray(0)) { acc, next -> acc + next }
                val fullSha = sha256(exactBytes)

                val chunks = payloads.map { bytes ->
                    val hash = sha256(bytes)
                    val target = File(File(root, "chunks/${hash.take(2)}"), "$hash.chunk")
                    target.parentFile!!.mkdirs()
                    target.writeBytes(bytes)
                    hash to bytes.size
                }
                writeManifest(File(root, "manifests/$recordId.apkm"), chunks)

                val record = record(
                    id = recordId,
                    size = exactBytes.size.toLong(),
                    sha = fullSha,
                    chunkCount = chunks.size,
                )
                val stager = FastApkStager(root)
                val plan = stager.plan(record)
                assertEquals(exactBytes.size.toLong(), plan.exactSize)

                val vaultProgress = ArrayList<FastApkStager.Progress>()
                val vaultRaw = ByteArrayOutputStream(exactBytes.size)
                val vaultOutput = BufferedOutputStream(vaultRaw, INSTALL_STYLE_BUFFER)
                stager.stageVault(record, plan, vaultOutput) { vaultProgress += it }
                vaultOutput.flush()
                assertArrayEquals(exactBytes, vaultRaw.toByteArray())
                assertEquals(exactBytes.size.toLong(), vaultProgress.last().bytesWritten)
                assertEquals(FastApkStager.Source.VAULT, vaultProgress.last().source)

                val preparedFile = File(root, "prepared.apk").apply { writeBytes(exactBytes) }
                val directProgress = ArrayList<FastApkStager.Progress>()
                val directRaw = ByteArrayOutputStream(exactBytes.size)
                val directOutput = BufferedOutputStream(directRaw, INSTALL_STYLE_BUFFER)
                stager.stagePreparedFile(record, plan, preparedFile, directOutput) { directProgress += it }
                directOutput.flush()
                assertArrayEquals(exactBytes, directRaw.toByteArray())
                assertEquals(exactBytes.size.toLong(), directProgress.last().bytesWritten)
                assertEquals(FastApkStager.Source.PREPARED_FILE, directProgress.last().source)

                // Same length is not enough: a single changed byte must stop staging before commit.
                val corrupted = exactBytes.copyOf()
                corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 0x5A).toByte()
                preparedFile.writeBytes(corrupted)
                assertThrows(IllegalStateException::class.java) {
                    stager.stagePreparedFile(record, plan, preparedFile, ByteArrayOutputStream())
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun writeManifest(target: File, chunks: List<Pair<String, Int>>) {
        target.parentFile!!.mkdirs()
        DataOutputStream(BufferedOutputStream(FileOutputStream(target))).use { output ->
            output.write("APKBOXM1".toByteArray(Charsets.US_ASCII))
            output.writeInt(chunks.size)
            for ((hash, size) in chunks) {
                output.write(hexToBytes(hash))
                output.writeInt(size)
            }
        }
    }

    private fun record(id: String, size: Long, sha: String, chunkCount: Int) = ApkRecord(
        id = id,
        projectId = "speed-project",
        displayName = "speed.apk",
        label = "Speed",
        packageName = "com.example.speed",
        versionName = "1",
        versionCode = 1,
        sizeBytes = size,
        sha256 = sha,
        signingCertSha256 = null,
        addedAtEpochMs = 0L,
        isBase = false,
        chunkCount = chunkCount,
        newBytesAdded = size,
    )

    private fun sha256(bytes: ByteArray): String = fastHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun fastHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        val chars = CharArray(bytes.size * 2)
        var out = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            chars[out++] = hex[value ushr 4]
            chars[out++] = hex[value and 0x0F]
        }
        return String(chars)
    }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
