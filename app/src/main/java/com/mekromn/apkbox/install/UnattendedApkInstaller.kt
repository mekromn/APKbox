package com.mekromn.apkbox.install

import android.content.Context
import android.os.StatFs
import com.mekromn.apkbox.bridge.AdbBridgeManager
import com.mekromn.apkbox.bridge.AdbInstallResult
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File

/**
 * User-facing unattended install path.
 *
 * Unlike PackageInstaller's normal confirmation flow, this path uses APKbox's already-paired,
 * self-healing same-device Wireless ADB connection. The archived APK is never materialized as a
 * second APKbox cache file: FastApkStager streams either the verified gateway source or the parallel
 * vault reconstruction directly into an ADB PackageInstaller session. Android is allowed to commit
 * that session only after the complete outgoing SHA-256 has matched the archived record. After
 * Android reports success, APKbox hashes the installed base.apk and requires that SHA-256 to match
 * too.
 *
 * Signature mismatch is intentionally handled by callers before this class is invoked. This class
 * never performs an implicit uninstall, clear-data, or destructive fallback.
 */
class UnattendedApkInstaller(
    context: Context,
    private val adb: AdbBridgeManager,
) {
    companion object {
        private const val SAFETY_RESERVE_BYTES = 256L * 1024L * 1024L
        private const val WRITE_BUFFER_BYTES = 4 * 1024 * 1024
        private const val MIB = 1024L * 1024L
        private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val installedPathRegex = Regex("[/A-Za-z0-9._=:+-]+")
    }

    private val appContext = context.applicationContext
    private val fastStager = FastApkStager(appContext)
    private val mutex = Mutex()

    suspend fun install(
        record: ApkRecord,
        preparedSource: File? = null,
        allowDowngrade: Boolean = false,
        onProgress: ((InstallProgress) -> Unit)? = null,
    ): AdbInstallResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(packageRegex.matches(record.packageName)) { "Stored APK has an invalid package name." }

            val plan = fastStager.plan(record)
            val exactSize = plan.exactSize
            check(exactSize > 0L) { "Stored APK manifest has an invalid size." }

            // Android may briefly need one full staged copy plus the destination package copy. The
            // old implementation needed a third full copy for APKbox's own verified temp file; the
            // pre-commit streaming session eliminates that copy entirely.
            checkAdditionalSpace(exactSize, fullCopiesNeeded = 2)

            val result = adb.installVerifiedStream(
                totalBytes = exactSize,
                allowDowngrade = allowDowngrade,
            ) { rawOutput ->
                val output = BufferedOutputStream(rawOutput, WRITE_BUFFER_BYTES)
                val progressBridge: (FastApkStager.Progress) -> Unit = { progress ->
                    onProgress?.invoke(
                        InstallProgress(
                            bytesWritten = progress.bytesWritten,
                            totalBytes = progress.totalBytes,
                            directPreparedSource = progress.source == FastApkStager.Source.PREPARED_FILE,
                        )
                    )
                }

                if (preparedSource?.isFile == true) {
                    fastStager.stagePreparedFile(
                        record = record,
                        plan = plan,
                        sourceFile = preparedSource,
                        output = output,
                        onProgress = progressBridge,
                    )
                } else {
                    fastStager.stageVault(
                        record = record,
                        plan = plan,
                        output = output,
                        onProgress = progressBridge,
                    )
                }
                output.flush()
            }

            check(result.success) {
                val detail = result.output.trim().ifBlank { "Android package manager rejected the APK." }
                "Unattended install failed: ${detail.take(2_000)}"
            }

            verifyInstalledBaseSha(record)
            result
        }
    }

    private suspend fun verifyInstalledBaseSha(record: ApkRecord) {
        val pathResult = adb.execute("pm path ${record.packageName}", 12)
        check(!pathResult.timedOut && (pathResult.exitCode == null || pathResult.exitCode == 0)) {
            "Android reported install success, but APKbox could not locate the installed package."
        }
        val basePath = pathResult.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("package:") && it.removePrefix("package:").endsWith("/base.apk") }
            ?.removePrefix("package:")
            ?: error("Android reported install success, but no installed base.apk was returned.")
        check(installedPathRegex.matches(basePath)) {
            "Installed APK path contained unexpected characters; verification stopped."
        }

        val hashResult = adb.execute("sha256sum $basePath", 30)
        check(!hashResult.timedOut && (hashResult.exitCode == null || hashResult.exitCode == 0)) {
            "Android reported install success, but installed APK SHA-256 could not be read."
        }
        val installedSha = Regex("(?i)^[0-9a-f]{64}")
            .find(hashResult.output.trim())
            ?.value
            ?.lowercase()
            ?: error("Installed APK SHA-256 output was not recognized.")
        check(installedSha.equals(record.sha256, ignoreCase = true)) {
            "Unattended install verification failed: installed base.apk SHA-256 $installedSha does not match archived ${record.sha256}."
        }
    }

    private fun checkAdditionalSpace(exactSize: Long, fullCopiesNeeded: Int) {
        val copies = fullCopiesNeeded.coerceAtLeast(1)
        val available = runCatching { StatFs(appContext.filesDir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
        val maxSafeSize = (Long.MAX_VALUE - SAFETY_RESERVE_BYTES) / copies
        val required = if (exactSize > maxSafeSize) Long.MAX_VALUE
        else exactSize * copies + SAFETY_RESERVE_BYTES
        check(available >= required) {
            "Not enough free space for a verified unattended install. APKbox needs about ${toMiB(required)} MiB free, but only ${toMiB(available)} MiB is available."
        }
    }

    private fun toMiB(bytes: Long): Long = when {
        bytes <= 0L -> 0L
        bytes == Long.MAX_VALUE -> Long.MAX_VALUE / MIB
        else -> bytes / MIB + if (bytes % MIB == 0L) 0L else 1L
    }
}
