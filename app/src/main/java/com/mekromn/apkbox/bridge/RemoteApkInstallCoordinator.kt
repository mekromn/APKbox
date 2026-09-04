package com.mekromn.apkbox.bridge

import android.content.Context
import android.net.Uri
import com.mekromn.apkbox.agent.BuildSourceCredentials
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.model.ApkProject
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * One-request remote APK installer for agents that already have a direct APK URL.
 *
 * This intentionally does not weaken BuildRunner's stricter manifest + exact SHA contract. Direct
 * installs are still downloaded completely, hashed, parsed, optionally archived, unattended-
 * installed through APKbox's privileged transport selector, and verified against installed
 * base.apk before success is reported.
 */
class RemoteApkInstallCoordinator(
    context: Context,
    private val library: LibraryStore,
    private val privileged: PrivilegedBridgeManager,
) {
    companion object {
        private const val BUFFER_BYTES = 1024 * 1024
        private const val MAX_REDIRECTS = 8
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_APK_BYTES = 2L * 1024L * 1024L * 1024L
        private val PACKAGE_REGEX = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val SHA_REGEX = Regex("[0-9a-fA-F]{64}")
    }

    private val appContext = context.applicationContext
    private val credentials = BuildSourceCredentials(appContext)

    suspend fun execute(request: BridgeRequest, risk: BridgeRisk): BridgeResult {
        val started = System.currentTimeMillis()
        val url = request.downloadUrl.trim()
        if (!url.startsWith("https://", ignoreCase = true)) {
            return failed(request, risk, started, "Remote APK URL must use HTTPS.")
        }
        val expectedSha = request.expectedApkSha256.trim().lowercase()
        if (expectedSha.isNotBlank() && !SHA_REGEX.matches(expectedSha)) {
            return failed(request, risk, started, "expectedApkSha256 must be blank or exactly 64 hexadecimal characters.")
        }
        val expectedPackage = request.packageName.trim()
        if (expectedPackage.isNotBlank() && !PACKAGE_REGEX.matches(expectedPackage)) {
            return failed(request, risk, started, "Invalid expected package name.")
        }
        if (request.requiresBuildToken && !credentials.hasToken()) {
            return failed(request, risk, started, "This APK URL requires the separately encrypted read-only build-source token, but APKbox does not have one configured.")
        }

        val scratchDir = File(appContext.cacheDir, "remote-apk-install").apply { mkdirs() }
        val apkFile = File(scratchDir, "${request.id}.apk")
        apkFile.delete()

        return try {
            val token = if (request.requiresBuildToken) credentials.readToken() else null
            val downloadedBytes = download(url, request.requiresBuildToken, token, apkFile)
            val actualSha = sha256(apkFile)
            if (expectedSha.isNotBlank() && !actualSha.equals(expectedSha, ignoreCase = true)) {
                return failed(
                    request,
                    risk,
                    started,
                    "Downloaded APK SHA-256 mismatch. Expected $expectedSha, got $actualSha. Nothing was installed.",
                )
            }

            val archive = runCatching { ApkInspector.inspect(appContext, apkFile) }.getOrElse { failure ->
                return failed(request, risk, started, "Downloaded file is not a parseable APK: ${message(failure)}")
            }
            if (expectedPackage.isNotBlank() && archive.packageName != expectedPackage) {
                return failed(
                    request,
                    risk,
                    started,
                    "Downloaded APK package is ${archive.packageName}, expected $expectedPackage. Nothing was installed.",
                )
            }

            val record = if (request.saveToProject) {
                runCatching {
                    archiveToProject(
                        request = request,
                        apkFile = apkFile,
                        sha256 = actualSha,
                        packageName = archive.packageName,
                        archiveLabel = archive.label,
                    )
                }.getOrElse { failure ->
                    return failed(request, risk, started, "APKbox project archive failed: ${message(failure)}")
                }
            } else null

            val installed = ApkInspector.inspectInstalled(appContext, archive.packageName)
            if (installed != null &&
                !installed.signingCertSha256.isNullOrBlank() &&
                !archive.signingCertSha256.isNullOrBlank() &&
                !installed.signingCertSha256.equals(archive.signingCertSha256, ignoreCase = true)
            ) {
                val archiveNote = if (record != null) " The APK was preserved in project '${record.projectId}'." else ""
                return failed(
                    request,
                    risk,
                    started,
                    "Installed ${archive.packageName} has a different signing certificate. APKbox will not silently remove app data during a direct remote install.$archiveNote Use the explicit uninstall/reinstall flow when signature replacement is intended.",
                )
            }

            val existingSha = installedPackageSha256(archive.packageName)
            if (!existingSha.equals(actualSha, ignoreCase = true)) {
                val install = runCatching {
                    privileged.installApk(apkFile, allowDowngrade = request.allowDowngrade)
                }.getOrElse { failure ->
                    return failed(request, risk, started, "Unattended install transport failed: ${message(failure)}")
                }
                if (!install.success) {
                    return failed(
                        request,
                        risk,
                        started,
                        "Android package manager rejected the APK: ${install.output.take(2_000)}",
                    )
                }
            }

            val installedSha = installedPackageSha256(archive.packageName)
            if (!installedSha.equals(actualSha, ignoreCase = true)) {
                return failed(
                    request,
                    risk,
                    started,
                    "Package manager completed, but installed base.apk SHA '$installedSha' does not match downloaded APK $actualSha.",
                )
            }

            var launchDetail = ""
            if (request.autoLaunch) {
                val launch = runCatching {
                    privileged.execute("monkey -p ${archive.packageName} -c android.intent.category.LAUNCHER 1", 15)
                }.getOrElse { failure ->
                    return failed(request, risk, started, "APK installed and verified, but launch failed: ${message(failure)}")
                }
                if (launch.timedOut || (launch.exitCode != null && launch.exitCode != 0)) {
                    return failed(request, risk, started, "APK installed and verified, but launch failed: ${launch.output.take(2_000)}")
                }
                launchDetail = " and launched"
            }

            val output = JSONObject()
                .put("packageName", archive.packageName)
                .put("label", archive.label)
                .put("apkSha256", actualSha)
                .put("downloadedBytes", downloadedBytes)
                .put("installedBaseApkSha256", installedSha)
                .put("savedToProject", record != null)
                .put("projectId", record?.projectId.orEmpty())
                .put("apkRecordId", record?.id.orEmpty())
                .put("transport", privileged.activeTransportLabel())
                .put("launched", request.autoLaunch)
                .toString(2)

            BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.SUCCESS,
                risk = risk,
                detail = buildString {
                    append("Downloaded, SHA-256 verified, unattended-installed, and post-install verified ")
                    append(archive.packageName)
                    if (record != null) append("; exact APK bytes saved to APKbox project ${record.projectId}")
                    append(launchDetail)
                    append('.')
                },
                output = output,
                durationMs = System.currentTimeMillis() - started,
            )
        } finally {
            apkFile.delete()
        }
    }

    private suspend fun archiveToProject(
        request: BridgeRequest,
        apkFile: File,
        sha256: String,
        packageName: String,
        archiveLabel: String,
    ): ApkRecord = withContext(Dispatchers.IO) {
        val project = resolveProject(request, packageName)
        if (project != null) {
            library.records.value.firstOrNull {
                it.projectId == project.id && it.sha256.equals(sha256, ignoreCase = true)
            }?.let { return@withContext it }

            return@withContext library.importRevision(
                projectId = project.id,
                uri = Uri.fromFile(apkFile),
                displayNameOverride = request.displayName.ifBlank { apkFile.name },
            ).record
        }

        library.importBase(
            uri = Uri.fromFile(apkFile),
            projectName = request.projectName.ifBlank { archiveLabel.ifBlank { packageName } },
            displayNameOverride = request.displayName.ifBlank { apkFile.name },
        ).record
    }

    private fun resolveProject(request: BridgeRequest, packageName: String): ApkProject? {
        val projects = library.projects.value
        if (request.projectId.isNotBlank()) {
            val project = projects.firstOrNull { it.id == request.projectId }
                ?: error("Requested APKbox project '${request.projectId}' does not exist.")
            require(project.packageName == packageName) {
                "Requested project ${project.name} stores ${project.packageName}, not $packageName."
            }
            return project
        }

        val matches = projects.filter { it.packageName == packageName }
        return when (matches.size) {
            0 -> null
            1 -> matches.single()
            else -> error("Multiple APKbox projects contain $packageName. Supply projectId so the downloaded APK cannot be archived to the wrong project.")
        }
    }

    private fun download(
        initialUrl: String,
        requiresToken: Boolean,
        token: String?,
        target: File,
    ): Long {
        var current = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            check(current.protocol.equals("https", ignoreCase = true)) { "APK redirects must stay on HTTPS." }
            if (requiresToken) {
                require(!token.isNullOrBlank()) { "Build-source token is missing." }
                require(isGitHubHost(current.host)) { "APKbox never sends the build-source token to a non-GitHub host." }
            }

            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "APKbox-Remote-APK-Install")
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
            if (requiresToken && !token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: run {
                        connection.disconnect()
                        error("APK download redirect did not contain a Location header.")
                    }
                connection.disconnect()
                check(redirectIndex < MAX_REDIRECTS) { "Too many APK download redirects." }
                val next = URL(current, location)
                if (requiresToken && !isGitHubHost(next.host)) {
                    error("Authenticated APK download attempted to redirect outside GitHub; token was not sent.")
                }
                current = next
                return@repeat
            }

            try {
                check(code in 200..299) { "APK download HTTP $code." }
                val declared = connection.contentLengthLong
                if (declared > MAX_APK_BYTES) error("APK download exceeds APKbox's ${MAX_APK_BYTES}-byte safety limit.")

                target.parentFile?.mkdirs()
                var written = 0L
                BufferedInputStream(connection.inputStream, BUFFER_BYTES).use { input ->
                    BufferedOutputStream(FileOutputStream(target), BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            written += count
                            check(written <= MAX_APK_BYTES) { "APK download exceeded APKbox's safety limit." }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
                check(written > 0L && target.isFile && target.length() == written) { "APK download produced an incomplete file." }
                if (declared >= 0L) check(written == declared) { "APK download ended at $written of $declared bytes." }
                return written
            } finally {
                connection.disconnect()
            }
        }
        error("Too many APK download redirects.")
    }

    private suspend fun installedPackageSha256(packageName: String): String {
        val pathResult = runCatching { privileged.execute("pm path $packageName", 10) }.getOrNull() ?: return ""
        val path = pathResult.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.endsWith("/base.apk") }
            ?.removePrefix("package:")
            ?: return ""
        if (!path.matches(Regex("[/A-Za-z0-9._=:+-]+"))) return ""
        val hashResult = runCatching { privileged.execute("sha256sum $path", 30) }.getOrNull() ?: return ""
        return Regex("(?i)^[0-9a-f]{64}").find(hashResult.output.trim())?.value?.lowercase().orEmpty()
    }

    private fun sha256(file: File): String {
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

    private fun isGitHubHost(host: String): Boolean {
        val lower = host.lowercase()
        return lower == "github.com" || lower == "api.github.com" || lower.endsWith(".github.com") || lower.endsWith("githubusercontent.com")
    }

    private fun failed(
        request: BridgeRequest,
        risk: BridgeRisk,
        started: Long,
        detail: String,
    ) = BridgeResult(
        requestId = request.id,
        status = BridgeResultStatus.FAILED,
        risk = risk,
        detail = detail.take(4_096),
        durationMs = System.currentTimeMillis() - started,
    )

    private fun message(failure: Throwable): String = failure.message ?: failure.javaClass.simpleName
}
