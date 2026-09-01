package com.mekromn.apkbox.install

import android.content.Context
import com.mekromn.apkbox.data.VaultChunkClaims
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * High-throughput exact APK staging.
 *
 * Fidelity invariant: concurrency is used only to prefetch chunk bytes. Bytes are emitted strictly
 * in manifest order and a single SHA-256 digest is computed over the exact outgoing stream before
 * PackageInstaller is allowed to commit it.
 */
internal class FastApkStager internal constructor(private val vaultRoot: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "apkbox-vault"))

    companion object {
        private val MANIFEST_MAGIC = "APKBOXM1".toByteArray(Charsets.US_ASCII)
        private const val SOURCE_BUFFER_BYTES = 1024 * 1024
        private const val MAX_CHUNK_BYTES = 1024 * 1024
        private val HEX = "0123456789abcdef".toCharArray()

        // Bounded by chunk size: even on a many-core phone the vault pipeline holds at most ~8 MiB
        // of prefetched APK data. Lower-core devices still get enough read-ahead to hide I/O waits.
        private val PREFETCH_SLOTS = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
    }

    enum class Source {
        PREPARED_FILE,
        VAULT,
    }

    data class Progress(
        val bytesWritten: Long,
        val totalBytes: Long,
        val source: Source,
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (bytesWritten.toDouble() / totalBytes)
                .coerceIn(0.0, 1.0).toFloat()
    }

    data class Plan(
        val chunks: List<Chunk>,
        val exactSize: Long,
    )

    data class Chunk(
        val hash: String,
        val size: Int,
    )

    private val manifestsDir = File(vaultRoot, "manifests")
    private val chunksDir = File(vaultRoot, "chunks")

    fun plan(record: ApkRecord): Plan {
        val chunks = readManifest(record.id)
        val exactSize = chunks.sumOf { it.size.toLong() }
        require(exactSize > 0L) { "Stored APK manifest has an invalid size." }
        return Plan(chunks = chunks, exactSize = exactSize)
    }

    suspend fun stageVault(
        record: ApkRecord,
        plan: Plan,
        output: OutputStream,
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        // Keep every manifest hash alive for the duration of staging. This preserves the old
        // serialized reader's safety while allowing read-ahead workers to run concurrently.
        val claim = VaultChunkClaims.claim(vaultRoot, plan.chunks.map { it.hash })
        try {
            coroutineScope {
                val digest = MessageDigest.getInstance("SHA-256")
                val queue = ArrayDeque<Deferred<Pair<Chunk, ByteArray>>>()
                var nextIndex = 0
                var written = 0L

                fun fillPrefetch() {
                    while (queue.size < PREFETCH_SLOTS && nextIndex < plan.chunks.size) {
                        val chunk = plan.chunks[nextIndex++]
                        queue.addLast(
                            async(Dispatchers.IO) {
                                chunk to readChunk(chunk)
                            }
                        )
                    }
                }

                fillPrefetch()
                for (expectedChunk in plan.chunks) {
                    val (actualChunk, bytes) = queue.removeFirst().await()
                    check(actualChunk == expectedChunk) { "Internal APKbox staging order mismatch." }

                    // One write per content-defined chunk. This cuts FileBridge/OutputStream call
                    // overhead while preserving the exact manifest order byte-for-byte.
                    output.write(bytes)
                    digest.update(bytes)
                    written += bytes.size
                    onProgress?.invoke(Progress(written, plan.exactSize, Source.VAULT))
                    fillPrefetch()
                }
                output.flush()

                check(written == plan.exactSize) {
                    "Reconstruction size mismatch: manifest requires ${plan.exactSize} bytes, wrote $written."
                }
                val actualSha = fastHex(digest.digest())
                check(actualSha == record.sha256) {
                    "Reconstruction checksum mismatch. Operation cancelled to protect the stored build."
                }
            }
        } finally {
            claim.close()
        }
    }

    fun stagePreparedFile(
        record: ApkRecord,
        plan: Plan,
        sourceFile: File,
        output: OutputStream,
        onProgress: ((Progress) -> Unit)? = null,
    ) {
        check(sourceFile.isFile) { "Prepared APK is no longer available." }
        check(sourceFile.length() == plan.exactSize) {
            "Prepared APK size changed before installation."
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SOURCE_BUFFER_BYTES)
        var written = 0L

        FileInputStream(sourceFile).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                written += count
                onProgress?.invoke(Progress(written, plan.exactSize, Source.PREPARED_FILE))
            }
        }
        output.flush()

        check(written == plan.exactSize) {
            "Prepared APK size mismatch: expected ${plan.exactSize} bytes, staged $written."
        }
        val actualSha = fastHex(digest.digest())
        check(actualSha == record.sha256) {
            "Prepared APK checksum changed. Installation cancelled before commit."
        }
    }

    private fun readManifest(recordId: String): List<Chunk> {
        val source = File(manifestsDir, "$recordId.apkm")
        check(source.isFile) { "Stored APK manifest is missing." }

        DataInputStream(BufferedInputStream(FileInputStream(source), 64 * 1024)).use { input ->
            val magic = ByteArray(MANIFEST_MAGIC.size)
            input.readFully(magic)
            check(magic.contentEquals(MANIFEST_MAGIC)) { "Unsupported APKbox manifest format." }

            val count = input.readInt()
            check(count > 0) { "Invalid manifest chunk count." }
            return ArrayList<Chunk>(count).also { chunks ->
                repeat(count) {
                    val hashBytes = ByteArray(32)
                    input.readFully(hashBytes)
                    val size = input.readInt()
                    check(size in 1..MAX_CHUNK_BYTES) { "Invalid manifest chunk size." }
                    chunks += Chunk(hash = fastHex(hashBytes), size = size)
                }
            }
        }
    }

    private fun readChunk(chunk: Chunk): ByteArray {
        val file = File(File(chunksDir, chunk.hash.take(2)), "${chunk.hash}.chunk")
        check(file.isFile && file.length() == chunk.size.toLong()) {
            "Vault chunk ${chunk.hash.take(12)} is missing or corrupt."
        }

        val bytes = ByteArray(chunk.size)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0) { "Unexpected end of chunk ${chunk.hash.take(12)}." }
                offset += count
            }
        }
        return bytes
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
