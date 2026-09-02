package com.mekromn.apkbox.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

class BuildDownloader(
    private val store: BuildRunStore,
) {
    companion object {
        private const val BUFFER_BYTES = 1024 * 1024
        private const val MAX_REDIRECTS = 8
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_BUILD_BYTES = 2L * 1024L * 1024L * 1024L
    }

    suspend fun obtainApk(
        candidate: BuildCandidate,
        buildToken: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val finalSource = store.sourceFile(candidate.runId, candidate.sourceFormat)
        if (!finalSource.isFile || finalSource.length() == 0L) {
            download(candidate, buildToken, finalSource, onProgress)
        }

        when (candidate.sourceFormat) {
            BuildSourceFormat.APK -> finalSource
            BuildSourceFormat.ZIP_APK -> extractApk(candidate, finalSource)
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

    private fun download(
        candidate: BuildCandidate,
        token: String?,
        finalSource: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        if (candidate.requiresBuildToken) {
            require(!token.isNullOrBlank()) {
                "This private build requires the separately encrypted read-only build-source token."
            }
            require(isGitHubHost(URL(candidate.downloadUrl).host)) {
                "APKbox never sends a GitHub build token to a non-GitHub host."
            }
        }

        val part = store.sourcePartFile(candidate.runId)
        part.parentFile?.mkdirs()
        var restartBudget = 1
        while (true) {
            val offset = part.takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
            val response = openFollowingRedirects(candidate.downloadUrl, offset, candidate.requiresBuildToken, token)
            try {
                if (response.responseCode == HttpURLConnection.HTTP_REQUESTED_RANGE_NOT_SATISFIABLE && restartBudget-- > 0) {
                    part.delete()
                    continue
                }
                check(response.responseCode == HttpURLConnection.HTTP_OK || response.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    "Build download HTTP ${response.responseCode}."
                }

                val append = response.responseCode == HttpURLConnection.HTTP_PARTIAL && offset > 0L
                if (!append && part.exists()) part.delete()
                val base = if (append) offset else 0L
                val contentLength = response.contentLengthLong.takeIf { it >= 0L } ?: -1L
                val total = if (contentLength >= 0L) base + contentLength else -1L
                if (total > MAX_BUILD_BYTES) error("Build download exceeds APKbox's ${MAX_BUILD_BYTES}-byte safety limit.")

                BufferedInputStream(response.inputStream, BUFFER_BYTES).use { input ->
                    BufferedOutputStream(FileOutputStream(part, append), BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = base
                        onProgress(downloaded, total)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            downloaded += count
                            check(downloaded <= MAX_BUILD_BYTES) { "Build download exceeded APKbox's safety limit." }
                            output.write(buffer, 0, count)
                            onProgress(downloaded, total)
                        }
                        output.flush()
                    }
                }

                check(part.isFile && part.length() > 0L) { "Build download produced an empty file." }
                if (total >= 0L) check(part.length() == total) {
                    "Build download ended at ${part.length()} of $total bytes."
                }
                if (finalSource.exists() && !finalSource.delete()) error("Could not replace previous build source.")
                if (!part.renameTo(finalSource)) {
                    part.copyTo(finalSource, overwrite = true)
                    part.delete()
                }
                return
            } finally {
                response.disconnect()
            }
        }
    }

    private fun extractApk(candidate: BuildCandidate, archive: File): File {
        val target = store.extractedApkFile(candidate.runId)
        if (target.isFile && target.length() > 0L) return target

        ZipFile(archive).use { zip ->
            val apkEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .toList()
            val entry = if (candidate.apkEntryName.isNotBlank()) {
                apkEntries.firstOrNull { it.name == candidate.apkEntryName }
                    ?: error("APK entry '${candidate.apkEntryName}' was not found in the build artifact.")
            } else {
                require(apkEntries.size == 1) {
                    "Build artifact contains ${apkEntries.size} APK files; specify apkEntryName explicitly."
                }
                apkEntries.single()
            }
            val declared = entry.size
            if (declared > MAX_BUILD_BYTES) error("APK inside build artifact is too large.")

            val temp = File(target.parentFile, ".${target.name}.extracting")
            temp.delete()
            var written = 0L
            zip.getInputStream(entry).use { raw ->
                BufferedInputStream(raw, BUFFER_BYTES).use { input ->
                    BufferedOutputStream(FileOutputStream(temp), BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            written += count
                            check(written <= MAX_BUILD_BYTES) { "Extracted APK exceeded APKbox's safety limit." }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
            }
            if (declared >= 0L) check(written == declared) { "Extracted APK size verification failed." }
            check(temp.length() == written && written > 0L) { "Extracted APK is incomplete." }
            if (target.exists() && !target.delete()) error("Could not replace previous extracted APK.")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        return target
    }

    private fun openFollowingRedirects(
        initialUrl: String,
        offset: Long,
        requiresToken: Boolean,
        token: String?,
    ): HttpURLConnection {
        var current = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            check(current.protocol.equals("https", ignoreCase = true)) { "Build redirects must stay on HTTPS." }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "APKbox-Build-Runner")
            connection.setRequestProperty("Accept", "application/octet-stream")
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
            if (requiresToken && !token.isNullOrBlank() && isGitHubHost(current.host)) {
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
                ?: run {
                    connection.disconnect()
                    error("Build redirect did not contain a Location header.")
                }
            connection.disconnect()
            check(redirectIndex < MAX_REDIRECTS) { "Too many build download redirects." }
            current = URL(current, location)
        }
        error("Too many build download redirects.")
    }

    private fun isGitHubHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "github.com" || lower == "api.github.com" || lower.endsWith(".github.com")
    }
}
