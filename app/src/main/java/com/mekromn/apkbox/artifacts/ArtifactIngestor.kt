package com.mekromn.apkbox.artifacts

import android.content.Context
import com.mekromn.apkbox.agent.BuildSourceCredentials
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ArtifactCancelledException(message: String = "Artifact ingest cancelled.") : RuntimeException(message)

data class ArtifactSpec(
    val jobId: String,
    val sourceUrl: String,
    val expectedSha256: String = "",
    val requiresBuildToken: Boolean = false,
    val maxBytes: Long = 2L * 1024L * 1024L * 1024L,
    val userAgent: String = "APKbox-Artifact-Ingest",
    val accept: String = "application/octet-stream",
)

data class IngestedArtifact(
    val sha256: String,
    val sizeBytes: Long,
    val file: File,
    val sourceUrl: String,
    val resumedBytes: Long,
    val cacheHit: Boolean,
)

/**
 * Shared exact-byte ingest layer for APKbox. Network consumers should use this instead of growing
 * their own downloader. Artifacts are resumed into a per-job .part file, then hashed and promoted
 * into a SHA-256 content-addressed store. A known expected SHA can reuse an existing exact object.
 * Local exact sources can be adopted into the same store after SHA proof, so all consumers share
 * one cache regardless of whether the fastest source was network, installed app, build output, or
 * APKbox's vault.
 */
class ArtifactIngestor(context: Context) {
    companion object {
        private const val BUFFER_BYTES = 1024 * 1024
        private const val MAX_REDIRECTS = 8
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val DEFAULT_CACHE_LIMIT_BYTES = 4L * 1024L * 1024L * 1024L
        private val SHA_REGEX = Regex("[0-9a-fA-F]{64}")
        private val JOB_ID_REGEX = Regex("[A-Za-z0-9._-]{1,96}")
    }

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "apkbox-artifacts").apply { mkdirs() }
    private val objects = File(root, "objects").apply { mkdirs() }
    private val work = File(root, "work").apply { mkdirs() }
    private val credentials = BuildSourceCredentials(appContext)

    fun hasBuildToken(): Boolean = credentials.hasToken()

    fun objectForSha(sha256: String): File? {
        val sha = sha256.trim().lowercase()
        if (!SHA_REGEX.matches(sha)) return null
        val file = objectFile(sha)
        if (!file.isFile || file.length() <= 0L) return null
        val actual = runCatching { sha256(file) }.getOrNull() ?: return null
        if (actual != sha) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /**
     * Promote any already-local exact source into the shared SHA-addressed cache. This is the
     * bridge between APKbox vault reconstruction, installed base.apk, durable-job output, and the
     * network ingest path. No local source is trusted by location/name: bytes are hashed first.
     */
    fun adoptLocalFile(
        source: File,
        expectedSha256: String = "",
        deleteSource: Boolean = false,
    ): IngestedArtifact {
        require(source.isFile && source.canRead() && source.length() > 0L) {
            "Local artifact source is missing, unreadable, or empty."
        }
        val expected = expectedSha256.trim().lowercase()
        if (expected.isNotBlank()) {
            require(SHA_REGEX.matches(expected)) {
                "Expected artifact SHA-256 must be exactly 64 hexadecimal characters."
            }
        }

        val actual = sha256(source)
        if (expected.isNotBlank()) {
            check(actual == expected) {
                "Local artifact SHA-256 mismatch. Expected $expected, got $actual."
            }
        }

        val target = objectFile(actual)
        target.parentFile?.mkdirs()
        var cacheHit = false
        if (target.isFile) {
            val cachedSha = sha256(target)
            check(cachedSha == actual) {
                "Content-addressed artifact cache collision/corruption detected."
            }
            cacheHit = true
            if (deleteSource && source.absolutePath != target.absolutePath) source.delete()
        } else if (source.absolutePath == target.absolutePath) {
            cacheHit = true
        } else {
            val sameFilesystemMove = deleteSource && runCatching { source.renameTo(target) }.getOrDefault(false)
            if (!sameFilesystemMove) {
                val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
                temp.delete()
                source.inputStream().buffered(BUFFER_BYTES).use { input ->
                    FileOutputStream(temp).buffered(BUFFER_BYTES).use { output ->
                        input.copyTo(output, BUFFER_BYTES)
                        output.flush()
                    }
                }
                check(temp.length() == source.length() && sha256(temp) == actual) {
                    temp.delete()
                    "Local artifact copy verification failed."
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = false)
                    temp.delete()
                }
                check(sha256(target) == actual) { "Promoted artifact failed final SHA-256 verification." }
                if (deleteSource) source.delete()
            }
        }

        target.setLastModified(System.currentTimeMillis())
        pruneCache(DEFAULT_CACHE_LIMIT_BYTES, preserve = setOf(target.absolutePath))
        return IngestedArtifact(
            sha256 = actual,
            sizeBytes = target.length(),
            file = target,
            sourceUrl = "local://${source.name}",
            resumedBytes = 0L,
            cacheHit = cacheHit,
        )
    }

    fun ingest(
        spec: ArtifactSpec,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): IngestedArtifact {
        val jobId = spec.jobId.trim()
        require(JOB_ID_REGEX.matches(jobId)) { "Invalid artifact job ID." }
        val initialUrl = spec.sourceUrl.trim()
        require(initialUrl.startsWith("https://", ignoreCase = true)) { "Artifact URL must use HTTPS." }
        val expected = spec.expectedSha256.trim().lowercase()
        if (expected.isNotBlank()) require(SHA_REGEX.matches(expected)) { "Expected artifact SHA-256 must be exactly 64 hexadecimal characters." }
        require(spec.maxBytes in 1L..(8L * 1024L * 1024L * 1024L)) { "Artifact maxBytes is outside APKbox safety bounds." }
        if (spec.requiresBuildToken) {
            require(credentials.hasToken()) { "This artifact requires the encrypted APKbox build-source token." }
        }

        if (expected.isNotBlank()) {
            objectForSha(expected)?.let { cached ->
                onProgress(cached.length(), cached.length())
                return IngestedArtifact(
                    sha256 = expected,
                    sizeBytes = cached.length(),
                    file = cached,
                    sourceUrl = initialUrl,
                    resumedBytes = cached.length(),
                    cacheHit = true,
                )
            }
        }

        val part = File(work, "$jobId.part")
        var restartBudget = 1
        val token = if (spec.requiresBuildToken) credentials.readToken() else null
        while (true) {
            if (isCancelled()) throw ArtifactCancelledException()
            val offset = part.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
            val response = openFollowingRedirects(
                initialUrl = initialUrl,
                offset = offset,
                requiresToken = spec.requiresBuildToken,
                token = token,
                userAgent = spec.userAgent,
                accept = spec.accept,
            )
            try {
                if (response.responseCode == HTTP_RANGE_NOT_SATISFIABLE && restartBudget-- > 0) {
                    part.delete()
                    continue
                }
                check(response.responseCode == HttpURLConnection.HTTP_OK || response.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    "Artifact download HTTP ${response.responseCode}."
                }

                val append = response.responseCode == HttpURLConnection.HTTP_PARTIAL && offset > 0L
                if (!append && part.exists()) part.delete()
                val base = if (append) offset else 0L
                val contentLength = response.contentLengthLong.takeIf { it >= 0L } ?: -1L
                val total = if (contentLength >= 0L) base + contentLength else -1L
                if (total > spec.maxBytes) error("Artifact exceeds APKbox's ${spec.maxBytes}-byte limit.")

                BufferedInputStream(response.inputStream, BUFFER_BYTES).use { input ->
                    BufferedOutputStream(FileOutputStream(part, append), BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = base
                        onProgress(downloaded, total)
                        while (true) {
                            if (isCancelled()) throw ArtifactCancelledException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            downloaded += count
                            check(downloaded <= spec.maxBytes) { "Artifact download exceeded APKbox's safety limit." }
                            output.write(buffer, 0, count)
                            onProgress(downloaded, total)
                        }
                        output.flush()
                    }
                }

                check(part.isFile && part.length() > 0L) { "Artifact download produced an empty file." }
                if (total >= 0L) check(part.length() == total) {
                    "Artifact download ended at ${part.length()} of $total bytes."
                }

                val actualSha = sha256(part)
                if (expected.isNotBlank() && actualSha != expected) {
                    part.delete()
                    error("Artifact SHA-256 mismatch. Expected $expected, got $actualSha.")
                }
                if (isCancelled()) throw ArtifactCancelledException()

                val objectFile = objectFile(actualSha)
                objectFile.parentFile?.mkdirs()
                if (!objectFile.isFile) {
                    if (!part.renameTo(objectFile)) {
                        part.copyTo(objectFile, overwrite = false)
                        part.delete()
                    }
                } else {
                    val existingSha = sha256(objectFile)
                    check(existingSha == actualSha) { "Content-addressed artifact cache collision/corruption detected." }
                    part.delete()
                }
                objectFile.setLastModified(System.currentTimeMillis())
                pruneCache(DEFAULT_CACHE_LIMIT_BYTES, preserve = setOf(objectFile.absolutePath))
                return IngestedArtifact(
                    sha256 = actualSha,
                    sizeBytes = objectFile.length(),
                    file = objectFile,
                    sourceUrl = initialUrl,
                    resumedBytes = offset,
                    cacheHit = false,
                )
            } catch (cancelled: ArtifactCancelledException) {
                // Preserve .part so an explicit resume can continue from the exact byte boundary.
                throw cancelled
            } finally {
                response.disconnect()
            }
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun discardWork(jobId: String) {
        val id = jobId.trim()
        if (JOB_ID_REGEX.matches(id)) File(work, "$id.part").delete()
    }

    fun pruneCache(maxBytes: Long = DEFAULT_CACHE_LIMIT_BYTES, preserve: Set<String> = emptySet()) {
        val files = objects.walkTopDown()
            .filter { it.isFile && SHA_REGEX.matches(it.name) }
            .sortedBy { it.lastModified() }
            .toList()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            if (file.absolutePath in preserve) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun objectFile(sha: String): File = File(File(objects, sha.take(2)), sha)

    private fun openFollowingRedirects(
        initialUrl: String,
        offset: Long,
        requiresToken: Boolean,
        token: String?,
        userAgent: String,
        accept: String,
    ): HttpURLConnection {
        var current = URL(initialUrl)
        if (requiresToken) {
            require(!token.isNullOrBlank()) { "Build-source token is missing." }
            require(isGitHubCredentialHost(current.host)) {
                "Authenticated artifact sources must begin on a GitHub credential host."
            }
        }

        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            check(current.protocol.equals("https", ignoreCase = true)) { "Artifact redirects must stay on HTTPS." }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", userAgent.take(128))
            connection.setRequestProperty("Accept", accept.take(512))
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
            if (requiresToken && !token.isNullOrBlank() && isGitHubCredentialHost(current.host)) {
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
                ?: run {
                    connection.disconnect()
                    error("Artifact redirect did not contain a Location header.")
                }
            connection.disconnect()
            check(redirectIndex < MAX_REDIRECTS) { "Too many artifact redirects." }
            current = URL(current, location)
        }
        error("Too many artifact redirects.")
    }

    private fun isGitHubCredentialHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "github.com" || lower == "api.github.com" || lower.endsWith(".github.com")
    }
}
