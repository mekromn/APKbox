package com.mekromn.apkbox.install

import android.content.Context
import com.mekromn.apkbox.bridge.PrivilegedBridgeManager
import com.mekromn.apkbox.bridge.PrivilegedTransportKind
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.model.ApkRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom


enum class ReinstallSignatureRelationship {
    SAME,
    DIFFERENT,
    UNKNOWN,
}

enum class ReinstallRemovalMethod {
    NONE,
    SHIZUKU_ROOT,
    SHIZUKU_SHELL,
    WIRELESS_ADB,
    ANDROID_UNINSTALL_UI,
}

enum class ReinstallPreservationMode {
    NOT_NEEDED,
    ANDROID_KEEP_DATA,
    ROOT_BEST_EFFORT,
    NONE,
}

data class ReinstallAssessment(
    val record: ApkRecord,
    val installed: Boolean,
    val installedVersionName: String? = null,
    val installedVersionCode: Long = 0L,
    val signatureRelationship: ReinstallSignatureRelationship = ReinstallSignatureRelationship.UNKNOWN,
    val removalMethod: ReinstallRemovalMethod = ReinstallRemovalMethod.NONE,
    val preservationMode: ReinstallPreservationMode = ReinstallPreservationMode.NOT_NEEDED,
    val transportLabel: String = "",
    val warning: String = "",
) {
    val signatureConflict: Boolean get() = signatureRelationship == ReinstallSignatureRelationship.DIFFERENT
    val usesPrivilegedRemoval: Boolean get() = removalMethod in setOf(
        ReinstallRemovalMethod.SHIZUKU_ROOT,
        ReinstallRemovalMethod.SHIZUKU_SHELL,
        ReinstallRemovalMethod.WIRELESS_ADB,
    )

    val privateDataPreserved: Boolean get() = preservationMode in setOf(
        ReinstallPreservationMode.ANDROID_KEEP_DATA,
        ReinstallPreservationMode.ROOT_BEST_EFFORT,
        ReinstallPreservationMode.NOT_NEEDED,
    )
}

data class ReinstallRemovalResult(
    val removed: Boolean,
    val detail: String,
    val backupPrepared: Boolean = false,
)

data class ReinstallRestoreOutcome(
    val hadPendingRestore: Boolean,
    val restored: Boolean,
    val detail: String,
)

/**
 * Persistent root-only backup descriptor. APKbox stores metadata in its private files directory;
 * the actual app sandbox tar files stay under /data/local/tmp and are mode 0600. They survive the
 * target package uninstall without making another app's private data readable by APKbox itself.
 */
private data class PendingReinstallRestore(
    val packageName: String,
    val expectedSigningCertSha256: String,
    val userId: Int,
    val ceArchivePath: String,
    val ceArchiveSha256: String,
    val deArchivePath: String,
    val deArchiveSha256: String,
    val createdAtEpochMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("packageName", packageName)
        .put("expectedSigningCertSha256", expectedSigningCertSha256)
        .put("userId", userId)
        .put("ceArchivePath", ceArchivePath)
        .put("ceArchiveSha256", ceArchiveSha256)
        .put("deArchivePath", deArchivePath)
        .put("deArchiveSha256", deArchiveSha256)
        .put("createdAtEpochMs", createdAtEpochMs)

    companion object {
        fun fromJson(json: JSONObject): PendingReinstallRestore = PendingReinstallRestore(
            packageName = json.getString("packageName"),
            expectedSigningCertSha256 = json.optString("expectedSigningCertSha256"),
            userId = json.optInt("userId"),
            ceArchivePath = json.optString("ceArchivePath"),
            ceArchiveSha256 = json.optString("ceArchiveSha256"),
            deArchivePath = json.optString("deArchivePath"),
            deArchiveSha256 = json.optString("deArchiveSha256"),
            createdAtEpochMs = json.optLong("createdAtEpochMs"),
        )
    }
}

private class ReinstallRestoreStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "apkbox-reinstall/pending").apply { mkdirs() }

    @Synchronized
    fun load(packageName: String): PendingReinstallRestore? = runCatching {
        val file = file(packageName)
        if (!file.isFile) null else PendingReinstallRestore.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
    }.getOrNull()

    @Synchronized
    fun save(pending: PendingReinstallRestore) {
        val target = file(pending.packageName)
        val temp = File(root, ".${target.name}.tmp")
        temp.writeText(pending.toJson().toString(), Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace pending reinstall restore metadata.")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    fun clear(packageName: String) {
        file(packageName).delete()
    }

    private fun file(packageName: String): File = File(
        root,
        packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180) + ".json",
    )
}

/**
 * Plans and executes the removal half of the explicit "Uninstall & reinstall" mode.
 *
 * The incoming APK is always installed later through APKbox's ordinary user-confirmed
 * PackageInstaller path. This class only chooses/removes the existing package and, when Sui/root is
 * available, preserves private app files across a conflicting-signature full uninstall.
 */
class ReinstallCoordinator(
    context: Context,
    private val privileged: PrivilegedBridgeManager,
) {
    companion object {
        private val PACKAGE_REGEX = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val SHA_REGEX = Regex("(?i)^[0-9a-f]{64}$")
        private const val ROOT_BACKUP_DIR = "/data/local/tmp/apkbox-reinstall"
        private const val SHIZUKU_PERMISSION_WAIT_MS = 8_000L
    }

    private val appContext = context.applicationContext
    private val store = ReinstallRestoreStore(appContext)
    private val random = SecureRandom()

    suspend fun assess(record: ApkRecord): ReinstallAssessment {
        require(PACKAGE_REGEX.matches(record.packageName)) { "Stored APK has an invalid package name." }
        val installed = ApkInspector.inspectInstalled(appContext, record.packageName)
            ?: return ReinstallAssessment(
                record = record,
                installed = false,
                removalMethod = ReinstallRemovalMethod.NONE,
                preservationMode = ReinstallPreservationMode.NOT_NEEDED,
                warning = "This package is not currently installed, so APKbox will skip removal and open Android Package Installer normally.",
            )

        // A user explicitly chose this destructive mode, so it is appropriate to surface Shizuku's
        // own permission prompt when Shizuku is already running but APKbox has not been authorized.
        var ready = privileged.ensureReady()
        val shizukuState = privileged.shizuku.status.value
        if (!ready && shizukuState.binderAvailable && !shizukuState.permissionGranted) {
            if (privileged.requestShizukuPermission()) {
                withTimeoutOrNull(SHIZUKU_PERMISSION_WAIT_MS) {
                    privileged.shizuku.status.first {
                        it.permissionGranted || !it.binderAvailable || it.lastError.isNotBlank()
                    }
                }
                ready = privileged.ensureReady()
            }
        }

        // If no privileged transport is ready, try every non-interactive Wireless ADB self-start
        // route (persistent secure-settings control and/or Shizuku). Failure is harmless: the
        // universal Android uninstall UI remains the final fallback and needs no Wi-Fi.
        if (!ready) ready = runCatching { privileged.tryStartWirelessDebugging() }.getOrDefault(false)

        if (ready) {
            // Bootstrap the one-time adb_wifi_enabled write grant whenever possible so later ADB
            // recovery can start itself without requiring this transport to already be alive.
            runCatching { privileged.bootstrapPersistentWirelessControl() }
        }

        val relationship = signatureRelationship(installed.signingCertSha256, record.signingCertSha256)
        val transport = if (ready) privileged.activeTransport() else PrivilegedTransportKind.NONE
        val removalMethod = when (transport) {
            PrivilegedTransportKind.SHIZUKU_ROOT -> ReinstallRemovalMethod.SHIZUKU_ROOT
            PrivilegedTransportKind.SHIZUKU_SHELL -> ReinstallRemovalMethod.SHIZUKU_SHELL
            PrivilegedTransportKind.WIRELESS_ADB -> ReinstallRemovalMethod.WIRELESS_ADB
            PrivilegedTransportKind.NONE -> ReinstallRemovalMethod.ANDROID_UNINSTALL_UI
        }
        val preservation = when {
            relationship == ReinstallSignatureRelationship.SAME && ready ->
                ReinstallPreservationMode.ANDROID_KEEP_DATA
            relationship == ReinstallSignatureRelationship.DIFFERENT && transport == PrivilegedTransportKind.SHIZUKU_ROOT ->
                ReinstallPreservationMode.ROOT_BEST_EFFORT
            else -> ReinstallPreservationMode.NONE
        }

        val warning = when (preservation) {
            ReinstallPreservationMode.ANDROID_KEEP_DATA ->
                "The signing certificate matches. APKbox can remove the package registration with Android's keep-data flag, then reinstall through Package Installer without deleting the app sandbox."
            ReinstallPreservationMode.ROOT_BEST_EFFORT ->
                "The signing certificate conflicts, but Sui/root is active. APKbox can make a best-effort root backup of the app's credential-encrypted and device-encrypted private files before the full uninstall, then restore them before first launch. Android Keystore entries, signature-bound encryption, permissions, and some system-managed state may still be lost or unusable with the new signer."
            ReinstallPreservationMode.NONE -> when (relationship) {
                ReinstallSignatureRelationship.DIFFERENT ->
                    "The signing certificate conflicts and no root-capable preservation method is active. A full uninstall is required; the old app's private data cannot be preserved by ordinary Shizuku shell, Wireless ADB, or Android's uninstall UI."
                ReinstallSignatureRelationship.UNKNOWN ->
                    "APKbox could not prove that both signing certificates match. For safety it will not claim keep-data compatibility. The selected removal method will delete the old app's private data."
                ReinstallSignatureRelationship.SAME ->
                    "The signatures match, but no privileged keep-data transport is currently available. Android's normal uninstall UI is the fallback and may delete the old app's private data."
            }
            ReinstallPreservationMode.NOT_NEEDED -> "Nothing is installed to preserve."
        }

        return ReinstallAssessment(
            record = record,
            installed = true,
            installedVersionName = installed.versionName,
            installedVersionCode = installed.versionCode,
            signatureRelationship = relationship,
            removalMethod = removalMethod,
            preservationMode = preservation,
            transportLabel = if (ready) privileged.activeTransportLabel() else "Android uninstall UI",
            warning = warning,
        )
    }

    suspend fun removeInstalled(assessment: ReinstallAssessment): ReinstallRemovalResult {
        if (!assessment.installed) {
            return ReinstallRemovalResult(true, "No installed package needed removal.")
        }
        check(assessment.usesPrivilegedRemoval) {
            "This reinstall assessment requires Android's uninstall confirmation UI."
        }

        var backupPrepared = false
        if (assessment.preservationMode == ReinstallPreservationMode.ROOT_BEST_EFFORT) {
            check(assessment.removalMethod == ReinstallRemovalMethod.SHIZUKU_ROOT) {
                "Root preservation was selected without an active root transport."
            }
            backupPrepared = prepareRootBackup(assessment.record)
        }

        val keepData = assessment.preservationMode == ReinstallPreservationMode.ANDROID_KEEP_DATA
        val command = buildString {
            append("pm uninstall ")
            if (keepData) append("-k ")
            append(assessment.record.packageName)
        }
        val result = runCatching { privileged.execute(command, 45) }.getOrElse { failure ->
            if (backupPrepared) discardPendingRootBackup(assessment.record.packageName)
            throw failure
        }
        val accepted = !result.timedOut &&
            (result.exitCode == null || result.exitCode == 0) &&
            result.output.lineSequence().any { it.trim().equals("Success", ignoreCase = true) }
        if (!accepted) {
            if (backupPrepared) discardPendingRootBackup(assessment.record.packageName)
            return ReinstallRemovalResult(
                removed = false,
                detail = "Android rejected uninstall through ${privileged.activeTransportLabel()}: ${result.output.take(2_000)}",
            )
        }

        repeat(12) {
            if (ApkInspector.inspectInstalled(appContext, assessment.record.packageName) == null) {
                return ReinstallRemovalResult(
                    removed = true,
                    detail = if (keepData) {
                        "Package removed through ${privileged.activeTransportLabel()} with Android app data retained."
                    } else if (backupPrepared) {
                        "Package fully removed through ${privileged.activeTransportLabel()}; root data backup is waiting for post-install restore."
                    } else {
                        "Package fully removed through ${privileged.activeTransportLabel()}."
                    },
                    backupPrepared = backupPrepared,
                )
            }
            delay(125)
        }

        if (backupPrepared) discardPendingRootBackup(assessment.record.packageName)
        return ReinstallRemovalResult(
            removed = false,
            detail = "Android reported uninstall success, but ${assessment.record.packageName} still appears installed. APKbox stopped before staging the replacement.",
        )
    }

    fun hasPendingRestore(packageName: String): Boolean = store.load(packageName) != null

    /** Called by InstallResultReceiver after Android reports PackageInstaller success. */
    suspend fun restorePending(packageName: String): ReinstallRestoreOutcome {
        val pending = store.load(packageName)
            ?: return ReinstallRestoreOutcome(false, true, "No reinstall data restore was pending.")
        require(PACKAGE_REGEX.matches(packageName)) { "Invalid pending restore package name." }

        val installed = ApkInspector.inspectInstalled(appContext, packageName)
            ?: return ReinstallRestoreOutcome(true, false, "Replacement package is not installed; root backup was retained for retry.")
        if (pending.expectedSigningCertSha256.isNotBlank() &&
            !pending.expectedSigningCertSha256.equals(installed.signingCertSha256, ignoreCase = true)
        ) {
            return ReinstallRestoreOutcome(
                true,
                false,
                "Installed signing certificate does not match the replacement APK expected by the saved restore. Backup was retained and not injected into the wrong app identity.",
            )
        }

        check(privileged.shizuku.ensureReady() && privileged.shizuku.status.value.root) {
            "Sui/root is no longer available. The replacement installed, but the private-data backup was retained and has not been restored."
        }

        verifyArchive(pending.ceArchivePath, pending.ceArchiveSha256)
        verifyArchive(pending.deArchivePath, pending.deArchiveSha256)

        val uidResult = privileged.execute("cmd package list packages -U $packageName", 10)
        val uid = Regex("uid:(\\d+)", RegexOption.IGNORE_CASE)
            .find(uidResult.output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: error("Could not resolve the replacement app UID. Backup was retained.")
        check(uid >= 10_000) { "Refusing to restore private data to unexpected UID $uid." }

        val ceDir = "/data/user/${pending.userId}/$packageName"
        val deDir = "/data/user_de/${pending.userId}/$packageName"
        val script = buildString {
            append("set -e\n")
            append("am force-stop ").append(packageName).append(" >/dev/null 2>&1 || true\n")
            if (pending.ceArchivePath.isNotBlank()) {
                append("mkdir -p '").append(ceDir).append("'\n")
                append("rm -rf '").append(ceDir).append("'/* '").append(ceDir).append("'/.[!.]* '").append(ceDir).append("'/..?* 2>/dev/null || true\n")
                append("/system/bin/toybox tar -C '").append(ceDir).append("' -xpf '").append(pending.ceArchivePath).append("'\n")
                append("chown -R ").append(uid).append(':').append(uid).append(" '").append(ceDir).append("'\n")
                append("restorecon -RF '").append(ceDir).append("' >/dev/null 2>&1 || true\n")
            }
            if (pending.deArchivePath.isNotBlank()) {
                append("mkdir -p '").append(deDir).append("'\n")
                append("rm -rf '").append(deDir).append("'/* '").append(deDir).append("'/.[!.]* '").append(deDir).append("'/..?* 2>/dev/null || true\n")
                append("/system/bin/toybox tar -C '").append(deDir).append("' -xpf '").append(pending.deArchivePath).append("'\n")
                append("chown -R ").append(uid).append(':').append(uid).append(" '").append(deDir).append("'\n")
                append("restorecon -RF '").append(deDir).append("' >/dev/null 2>&1 || true\n")
            }
            append("rm -f '").append(pending.ceArchivePath).append("' '").append(pending.deArchivePath).append("'\n")
            append("echo __APKBOX_RESTORE_OK__\n")
        }
        val restored = privileged.execute(script, 120)
        val success = !restored.timedOut &&
            (restored.exitCode == null || restored.exitCode == 0) &&
            restored.output.contains("__APKBOX_RESTORE_OK__")
        if (!success) {
            return ReinstallRestoreOutcome(
                true,
                false,
                "Root restore failed: ${restored.output.take(2_000)}. Backup metadata/files were retained when possible; do not assume private data was restored.",
            )
        }

        store.clear(packageName)
        return ReinstallRestoreOutcome(
            hadPendingRestore = true,
            restored = true,
            detail = "Best-effort private app files restored before first launch. Android Keystore/signature-bound secrets are not guaranteed across a signer change.",
        )
    }

    suspend fun discardPendingRootBackup(packageName: String) {
        val pending = store.load(packageName) ?: return
        if (privileged.shizuku.ensureReady() && privileged.shizuku.status.value.root) {
            val paths = listOf(pending.ceArchivePath, pending.deArchivePath)
                .filter { it.startsWith("$ROOT_BACKUP_DIR/") && it.isNotBlank() }
            if (paths.isNotEmpty()) {
                val quoted = paths.joinToString(" ") { "'${it.replace("'", "")}'" }
                runCatching { privileged.execute("rm -f $quoted", 10) }
            }
        }
        store.clear(packageName)
    }

    private suspend fun prepareRootBackup(record: ApkRecord): Boolean {
        check(privileged.shizuku.ensureReady() && privileged.shizuku.status.value.root) {
            "Sui/root is required for conflicting-signature private-data preservation. Nothing was uninstalled."
        }
        discardPendingRootBackup(record.packageName)

        val userResult = privileged.execute("am get-current-user", 8)
        val userId = Regex("\\d+").find(userResult.output)?.value?.toIntOrNull()
            ?: error("Could not resolve the current Android user. Nothing was uninstalled.")
        check(userId in 0..999) { "Unexpected Android user ID $userId." }

        val tokenBytes = ByteArray(16).also(random::nextBytes)
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        val ceDir = "/data/user/$userId/${record.packageName}"
        val deDir = "/data/user_de/$userId/${record.packageName}"
        val ceArchive = "$ROOT_BACKUP_DIR/$token-ce.tar"
        val deArchive = "$ROOT_BACKUP_DIR/$token-de.tar"

        val script = buildString {
            append("set -e\n")
            append("mkdir -p '").append(ROOT_BACKUP_DIR).append("'\n")
            append("chmod 700 '").append(ROOT_BACKUP_DIR).append("'\n")
            append("am force-stop ").append(record.packageName).append(" >/dev/null 2>&1 || true\n")
            append("if [ -d '").append(ceDir).append("' ]; then /system/bin/toybox tar -C '").append(ceDir)
                .append("' -cpf '").append(ceArchive).append("' .; chmod 600 '").append(ceArchive).append("'; sha256sum '")
                .append(ceArchive).append("'; else echo __APKBOX_CE_MISSING__; fi\n")
            append("if [ -d '").append(deDir).append("' ]; then /system/bin/toybox tar -C '").append(deDir)
                .append("' -cpf '").append(deArchive).append("' .; chmod 600 '").append(deArchive).append("'; sha256sum '")
                .append(deArchive).append("'; else echo __APKBOX_DE_MISSING__; fi\n")
        }
        val result = privileged.execute(script, 120)
        check(!result.timedOut && (result.exitCode == null || result.exitCode == 0)) {
            "Root app-data backup failed: ${result.output.take(2_000)}. Nothing was uninstalled."
        }

        val ceHash = hashForPath(result.output, ceArchive)
        val deHash = hashForPath(result.output, deArchive)
        check(ceHash.isNotBlank() || deHash.isNotBlank()) {
            "Root access was available, but Android exposed no credential- or device-encrypted private sandbox to preserve. Nothing was uninstalled so APKbox will not claim a successful data backup."
        }

        store.save(
            PendingReinstallRestore(
                packageName = record.packageName,
                expectedSigningCertSha256 = record.signingCertSha256.orEmpty(),
                userId = userId,
                ceArchivePath = if (ceHash.isBlank()) "" else ceArchive,
                ceArchiveSha256 = ceHash,
                deArchivePath = if (deHash.isBlank()) "" else deArchive,
                deArchiveSha256 = deHash,
                createdAtEpochMs = System.currentTimeMillis(),
            )
        )
        return true
    }

    private suspend fun verifyArchive(path: String, expectedSha: String) {
        if (path.isBlank()) return
        check(path.startsWith("$ROOT_BACKUP_DIR/")) { "Unsafe pending root backup path." }
        check(SHA_REGEX.matches(expectedSha)) { "Pending root backup has an invalid SHA-256." }
        val result = privileged.execute("sha256sum '$path'", 30)
        val actual = Regex("(?i)^[0-9a-f]{64}").find(result.output.trim())?.value.orEmpty()
        check(actual.equals(expectedSha, ignoreCase = true)) {
            "Pending root backup SHA-256 mismatch. Restore aborted before touching the replacement app data."
        }
    }

    private fun hashForPath(output: String, path: String): String = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.endsWith("  $path") || it.endsWith(" *$path") }
        ?.substringBefore(' ')
        ?.takeIf(SHA_REGEX::matches)
        .orEmpty()

    private fun signatureRelationship(oldSha: String?, newSha: String?): ReinstallSignatureRelationship {
        val old = oldSha?.trim().orEmpty()
        val new = newSha?.trim().orEmpty()
        if (!SHA_REGEX.matches(old) || !SHA_REGEX.matches(new)) return ReinstallSignatureRelationship.UNKNOWN
        return if (old.equals(new, ignoreCase = true)) {
            ReinstallSignatureRelationship.SAME
        } else {
            ReinstallSignatureRelationship.DIFFERENT
        }
    }
}
