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
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * User-facing unattended install path.
 *
 * Unlike PackageInstaller's normal confirmation flow, this path uses APKbox's already-paired
 * same-device Wireless ADB shell. APK fidelity stays non-negotiable: vault bytes are reconstructed
 * and full-file SHA-256 verified before package-manager mutation begins. A prepared gateway source
 * is re-verified against the stored record before it is streamed. After Android reports success,
 * APKbox hashes the installed base.apk through ADB and requires it to match the archived APK.
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
        private const val TEMP_WRITE_BUFFER_BYTES = 4 * 1024 * 1024
        private const val MIB = 1024L * 1024L
        private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val installedPathRegex = Regex("[/A-Za-z0-9._=:+-]+")
    }

    private val appContext = context.applicationContext
    private val fastStager = FastApkStager(appContext)
    private val tempDir = File(appContext.cacheDir, "apkbox-unattended-install").apply { mkdirs() }
    private val mutex = Mutex()

    suspend fun install(
        record: ApkRecord,
        preparedSource: File? = null,
        allowDowngrade: Boolean = false,
        onProgress: ((InstallProgress) -> Unit)? = null,
    ): AdbInstallResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            cleanupTemps()
            require(packageRegex.matches(record.packageName)) { "Stored APK has an invalid package name." }

            val plan = fastStager.plan(record)
            val exactSize = plan.exactSize
            check(exactSize > 0L) { "Stored APK manifest has an invalid size." }

            val verifiedSource = if (preparedSource?.isFile == true) {
                // The gateway scratch file was already hashed while being prepared/imported, but
                // verify it again immediately before handing bytes to the privileged ADB installer.
                fastStager.stagePreparedFile(
                    record = record,
                    plan = plan,
                    sourceFile = preparedSource,
                    output = NullOutputStream,
                )
                checkAdditionalSpace(exactSize, fullCopiesNeeded = 2)
                preparedSource
            } else {
                // ADB's one-shot `pm install -S` commits as soon as it receives the full stream, so
                // do not discover a vault checksum problem after bytes have already reached package
                // manager. Reconstruct and verify one private temporary APK first, then stream it.
                checkAdditionalSpace(exactSize, fullCopiesNeeded = 3)
                val target = File(tempDir, "${sanitize(record.id)}-${System.nanoTime()}.apk")
                try {
                    FileOutputStream(target).use { fileOutput ->
                        BufferedOutputStream(fileOutput, TEMP_WRITE_BUFFER_BYTES).use { output ->
                            fastStager.stageVault(
                                record = record,
                                plan = plan,
                                output = output,
                            )
                        }
                    }
                    check(target.isFile && target.length() == exactSize) {
                        "Verified unattended-install staging file has the wrong size."
                    }
                    target
                } catch (failure: Throwable) {
                    target.delete()
                    throw failure
                }
            }

            try {
                val result = adb.installApk(
                    apkFile = verifiedSource,
                    allowDowngrade = allowDowngrade,
                    onProgress = { sent, total ->
                        onProgress?.invoke(
                            InstallProgress(
                                bytesWritten = sent,
                                totalBytes = total,
                                directPreparedSource = preparedSource?.isFile == true,
                            )
                        )
                    },
                )
                check(result.success) {
                    val detail = result.output.trim().ifBlank { "Android package manager rejected the APK." }
                    "Unattended install failed: ${detail.take(2_000)}"
                }

                verifyInstalledBaseSha(record)
                result
            } finally {
                if (runCatching { verifiedSource.parentFile?.canonicalFile == tempDir.canonicalFile }.getOrDefault(false)) {
                    verifiedSource.delete()
                }
                cleanupTemps()
            }
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

    private fun cleanupTemps() {
        tempDir.mkdirs()
        tempDir.listFiles().orEmpty().forEach { runCatching { it.delete() } }
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "apk" }

    private fun toMiB(bytes: Long): Long = when {
        bytes <= 0L -> 0L
        bytes == Long.MAX_VALUE -> Long.MAX_VALUE / MIB
        else -> bytes / MIB + if (bytes % MIB == 0L) 0L else 1L
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}
