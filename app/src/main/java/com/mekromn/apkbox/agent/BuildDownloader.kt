package com.mekromn.apkbox.agent

import com.mekromn.apkbox.artifacts.ArtifactIngestor
import com.mekromn.apkbox.artifacts.ArtifactSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** Build-specific APK/ZIP extraction layered on APKbox's universal artifact ingest engine. */
class BuildDownloader(
    private val store: BuildRunStore,
    private val artifacts: ArtifactIngestor,
) {
    companion object {
        private const val BUFFER_BYTES = 1024 * 1024
        private const val MAX_BUILD_BYTES = 2L * 1024L * 1024L * 1024L
    }

    suspend fun obtainApk(
        candidate: BuildCandidate,
        buildToken: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): File = withContext(Dispatchers.IO) {
        if (candidate.requiresBuildToken) {
            require(!buildToken.isNullOrBlank()) {
                "This private build requires the separately encrypted read-only build-source token."
            }
        }

        // For a raw APK, the candidate's expected SHA is also the network artifact SHA and can
        // produce a content-addressed cache hit. ZIP_APK candidates define the SHA of the extracted
        // APK, so the ZIP itself is downloaded without pretending it has that digest.
        val artifact = artifacts.ingest(
            ArtifactSpec(
                jobId = candidate.runId,
                sourceUrl = candidate.downloadUrl,
                expectedSha256 = if (candidate.sourceFormat == BuildSourceFormat.APK) candidate.expectedApkSha256 else "",
                requiresBuildToken = candidate.requiresBuildToken,
                maxBytes = MAX_BUILD_BYTES,
                userAgent = "APKbox-Build-Runner",
                accept = "application/octet-stream",
            ),
            onProgress = onProgress,
            isCancelled = isCancelled,
        )

        when (candidate.sourceFormat) {
            BuildSourceFormat.APK -> artifact.file
            BuildSourceFormat.ZIP_APK -> extractApk(candidate, artifact.file, isCancelled)
        }
    }

    fun sha256(file: File): String = artifacts.sha256(file)

    private fun extractApk(candidate: BuildCandidate, archive: File, isCancelled: () -> Boolean): File {
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
                            if (isCancelled()) error("Build extraction cancelled at a safe boundary.")
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
}
