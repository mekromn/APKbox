package com.mekromn.apkbox

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mekromn.apkbox.data.ApkInspector
import com.mekromn.apkbox.data.GatewaySourceClaims
import com.mekromn.apkbox.data.PreparedSharedApk
import com.mekromn.apkbox.data.SharedApkAnalyzer
import com.mekromn.apkbox.data.SharedApkPreview
import com.mekromn.apkbox.install.ApkInstaller
import com.mekromn.apkbox.install.InstallFlowRuntime
import com.mekromn.apkbox.install.InstallFlowStage
import com.mekromn.apkbox.install.InstallFlowStatus
import com.mekromn.apkbox.install.InstallProgress
import com.mekromn.apkbox.install.ReinstallAssessment
import com.mekromn.apkbox.install.ReinstallCoordinator
import com.mekromn.apkbox.install.ReinstallPreservationMode
import com.mekromn.apkbox.install.ReinstallRemovalMethod
import com.mekromn.apkbox.install.ReinstallSignatureRelationship
import com.mekromn.apkbox.model.ApkRecord
import com.mekromn.apkbox.model.ReplaceReason
import com.mekromn.apkbox.model.ReplaceRequest
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

private const val OPEN_INSTALLER_NEW_PROJECT = "__new_project__"
private const val PACKAGE_REMOVAL_TIMEOUT_MS = 10_000L
private const val PACKAGE_REMOVAL_POLL_MS = 125L

private enum class OpenInstallMode {
    NORMAL,
    UNATTENDED,
    REINSTALL,
}

private enum class PermissionResumeAction {
    NORMAL_INSTALL,
    REINSTALL,
}

class OpenApkInstallerActivity : ComponentActivity() {
    private val libraryStore by lazy { ApkBoxServices.libraryStore(applicationContext) }
    private val apkInstaller by lazy { ApkInstaller(applicationContext, libraryStore) }
    private val privileged by lazy { ApkBoxServices.privilegedBridge(applicationContext) }
    private val reinstallCoordinator by lazy { ReinstallCoordinator(applicationContext, privileged) }

    private val preview = MutableStateFlow<SharedApkPreview?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val replaceRequest = MutableStateFlow<ReplaceRequest?>(null)
    private val reinstallAssessment = MutableStateFlow<ReinstallAssessment?>(null)
    private val installProgress = MutableStateFlow<InstallProgress?>(null)

    private var sourceUri: Uri? = null
    private var sourceClaim: Closeable? = null
    private var preparedApk: PreparedSharedApk? = null
    private var installWaitingForPermission: ApkRecord? = null
    private var permissionResumeAction = PermissionResumeAction.NORMAL_INSTALL
    private var installWaitingForRemoval: ApkRecord? = null
    private var forcedInstallMode: OpenInstallMode? = null

    private val uninstallLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val record = installWaitingForRemoval ?: return@registerForActivityResult
        installWaitingForRemoval = null
        lifecycleScope.launch {
            busy.value = true
            InstallFlowRuntime.update(
                InstallFlowStage.VERIFYING_REMOVAL,
                "Verifying the old package is actually gone",
                "Android returned from its uninstall UI. APKbox will not stage the replacement until PackageManager proves ${record.packageName} is absent.",
                packageName = record.packageName,
            )

            // Android can return RESULT_OK before PackageManager's package view has caught up. That
            // used to send the flow straight back through normal signature-conflict detection and
            // could loop forever. The absence barrier below is now mandatory.
            val removed = awaitPackageRemoved(
                record.packageName,
                if (result.resultCode == Activity.RESULT_OK) PACKAGE_REMOVAL_TIMEOUT_MS else 2_000L,
            )
            if (removed) {
                stageVerifiedReplacement(
                    record,
                    "Android uninstall completed and PackageManager now reports the old package absent.",
                )
            } else {
                val stillInstalled = inspectInstalled(record.packageName) != null
                if (result.resultCode != Activity.RESULT_OK && stillInstalled) {
                    InstallFlowRuntime.update(
                        InstallFlowStage.CANCELLED,
                        "Uninstall cancelled",
                        "The installed app is still present. APKbox did not stage or modify the replacement APK.",
                        packageName = record.packageName,
                    )
                    message.value = "Uninstall cancelled · installed app left unchanged"
                } else {
                    InstallFlowRuntime.update(
                        InstallFlowStage.FAILED,
                        "Uninstall did not complete",
                        "Android returned from uninstall, but ${record.packageName} is still installed after the bounded verification window. APKbox stopped before staging the replacement.",
                        packageName = record.packageName,
                    )
                    message.value = "Android returned from uninstall, but the old package is still installed. Replacement was not staged."
                }
                busy.value = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sourceUri = intent?.data
        forcedInstallMode = when (intent?.getStringExtra(EXTRA_OPEN_APK_INSTALL_MODE)) {
            OPEN_APK_INSTALL_MODE_NORMAL -> OpenInstallMode.NORMAL
            OPEN_APK_INSTALL_MODE_UNATTENDED -> OpenInstallMode.UNATTENDED
            OPEN_APK_INSTALL_MODE_REINSTALL -> OpenInstallMode.REINSTALL
            else -> null
        }
        InstallFlowRuntime.reset(mode = forcedInstallMode?.flowLabel().orEmpty())

        // Claim immediately, before startup/resume Auto Scanner can inventory Downloads. Resolving
        // this key is metadata-only (name + size); it never hashes or copies the APK.
        sourceClaim?.close()
        sourceClaim = sourceUri?.let { GatewaySourceClaims.claimUri(this, it) }

        setContent {
            APKboxTheme {
                val projects = libraryStore.projects.collectAsStateWithLifecycle().value
                val records = libraryStore.records.collectAsStateWithLifecycle().value
                val currentPreview = preview.collectAsStateWithLifecycle().value
                val isBusy = busy.collectAsStateWithLifecycle().value
                val currentMessage = message.collectAsStateWithLifecycle().value
                val currentReplace = replaceRequest.collectAsStateWithLifecycle().value
                val currentReinstall = reinstallAssessment.collectAsStateWithLifecycle().value
                val currentProgress = installProgress.collectAsStateWithLifecycle().value
                val currentFlow = InstallFlowRuntime.status.collectAsStateWithLifecycle().value

                LaunchedEffect(currentFlow.stage) {
                    if (currentFlow.stage.terminal) busy.value = false
                }

                OpenApkInstallerScreen(
                    preview = currentPreview,
                    projects = projects.filter { project ->
                        currentPreview == null || project.packageName == currentPreview.packageName
                    },
                    records = records,
                    busy = isBusy,
                    message = currentMessage,
                    replaceRequest = currentReplace,
                    reinstallAssessment = currentReinstall,
                    installProgress = currentProgress,
                    installFlow = currentFlow,
                    forcedMode = forcedInstallMode,
                    onCancel = { finish() },
                    onArchiveAndInstall = ::archiveAndInstall,
                    onConfirmReplace = ::confirmReplacement,
                    onCancelReplace = { replaceRequest.value = null },
                    onConfirmReinstall = ::confirmReinstall,
                    onCancelReinstall = { reinstallAssessment.value = null },
                )
            }
        }

        val uri = sourceUri
        if (uri == null) {
            InstallFlowRuntime.update(
                InstallFlowStage.FAILED,
                "No APK supplied",
                "The open-with intent did not include an APK URI.",
            )
            message.value = "No APK was supplied."
        } else {
            lifecycleScope.launch {
                busy.value = true
                InstallFlowRuntime.update(
                    InstallFlowStage.READING_APK,
                    "Reading incoming APK",
                    "APKbox is inspecting the exact incoming file before any archive or install action.",
                )
                try {
                    preparedApk?.close()
                    preparedApk = SharedApkAnalyzer.prepareForInstall(
                        this@OpenApkInstallerActivity,
                        uri,
                        libraryStore.records.value,
                    )
                    val prepared = preparedApk
                    preview.value = prepared?.preview
                    if (prepared != null) {
                        InstallFlowRuntime.update(
                            InstallFlowStage.READY,
                            "APK ready",
                            "Incoming SHA-256 ${prepared.preview.sha256}. Choose an archive target and install mode.",
                            packageName = prepared.preview.packageName,
                            mode = forcedInstallMode?.flowLabel(),
                        )
                    }
                } catch (t: Throwable) {
                    val detail = t.message ?: "Android could not inspect this APK."
                    InstallFlowRuntime.update(
                        InstallFlowStage.FAILED,
                        "Could not read APK",
                        detail,
                    )
                    message.value = detail
                } finally {
                    busy.value = false
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
            installWaitingForPermission?.let { record ->
                installWaitingForPermission = null
                val action = permissionResumeAction
                permissionResumeAction = PermissionResumeAction.NORMAL_INSTALL
                when (action) {
                    PermissionResumeAction.NORMAL_INSTALL -> requestInstall(record)
                    PermissionResumeAction.REINSTALL -> prepareReinstall(record)
                }
            }
        }
    }

    override fun onDestroy() {
        preparedApk?.close()
        preparedApk = null
        sourceClaim?.close()
        sourceClaim = null
        super.onDestroy()
    }

    private fun archiveAndInstall(projectChoice: String, mode: OpenInstallMode) {
        val prepared = preparedApk ?: return
        val incoming = prepared.preview
        if (busy.value) return

        lifecycleScope.launch {
            busy.value = true
            installProgress.value = null
            replaceRequest.value = null
            reinstallAssessment.value = null
            message.value = null
            InstallFlowRuntime.reset(incoming.packageName, mode.flowLabel())
            InstallFlowRuntime.update(
                InstallFlowStage.ARCHIVING_VERIFYING,
                "Archiving and verifying exact APK",
                "APKbox is storing the incoming APK in the selected project and will require the archived SHA-256 to remain ${incoming.sha256} before continuing.",
                packageName = incoming.packageName,
                mode = mode.flowLabel(),
            )
            try {
                val preparedUri = Uri.fromFile(prepared.file)
                val record = withContext(Dispatchers.IO) {
                    if (projectChoice == OPEN_INSTALLER_NEW_PROJECT) {
                        libraryStore.importBase(
                            uri = preparedUri,
                            projectName = incoming.label,
                            displayNameOverride = incoming.displayName,
                        ).record
                    } else {
                        val alreadyHere = libraryStore.records.value.firstOrNull {
                            it.projectId == projectChoice && it.sha256 == incoming.sha256
                        }
                        alreadyHere ?: libraryStore.importRevision(
                            projectId = projectChoice,
                            uri = preparedUri,
                            displayNameOverride = incoming.displayName,
                        ).record
                    }
                }
                check(record.sha256 == incoming.sha256) {
                    "Archived APK identity changed unexpectedly. Installation cancelled."
                }
                busy.value = false
                when (mode) {
                    OpenInstallMode.UNATTENDED -> {
                        startActivity(UnattendedInstallActivity.intent(this@OpenApkInstallerActivity, record.id))
                        finish()
                    }
                    OpenInstallMode.REINSTALL -> prepareReinstall(record)
                    OpenInstallMode.NORMAL -> requestInstall(record)
                }
            } catch (t: Throwable) {
                val detail = t.message ?: "APKbox could not archive this APK."
                InstallFlowRuntime.update(
                    InstallFlowStage.FAILED,
                    "Archive/verification failed",
                    detail,
                    packageName = incoming.packageName,
                )
                message.value = detail
                busy.value = false
            }
        }
    }

    private fun requestInstall(record: ApkRecord) {
        if (busy.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            installWaitingForPermission = record
            permissionResumeAction = PermissionResumeAction.NORMAL_INSTALL
            installProgress.value = null
            message.value = "Allow APKbox to install unknown apps, then return here."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        val installed = ApkInspector.inspectInstalled(this, record.packageName)
        if (installed != null) {
            val signingMismatch = installed.signingCertSha256 != null &&
                record.signingCertSha256 != null && installed.signingCertSha256 != record.signingCertSha256
            val reason = when {
                signingMismatch -> ReplaceReason.SIGNATURE_MISMATCH
                installed.versionCode > record.versionCode -> ReplaceReason.DOWNGRADE
                else -> null
            }
            if (reason != null) {
                replaceRequest.value = ReplaceRequest(
                    record = record,
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    reason = reason,
                )
                return
            }
        }
        installRecord(record, flowMode = "Install")
    }

    private fun prepareReinstall(record: ApkRecord) {
        if (busy.value) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            installWaitingForPermission = record
            permissionResumeAction = PermissionResumeAction.REINSTALL
            installProgress.value = null
            message.value = "Allow APKbox to use Android Package Installer first. APKbox will not uninstall the current app until this prerequisite is satisfied."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }

        lifecycleScope.launch {
            busy.value = true
            InstallFlowRuntime.update(
                InstallFlowStage.ASSESSING_REINSTALL,
                "Assessing reinstall",
                "Checking installed signer/version, Shizuku/Sui, Wireless ADB, fallback uninstall UI, and the strongest data-preservation method that can actually be proven available.",
                packageName = record.packageName,
                mode = "Uninstall & reinstall",
            )
            message.value = "Checking signatures, data-preservation options, Shizuku/Sui, and Wireless ADB…"
            try {
                val assessment = reinstallCoordinator.assess(record)
                if (!assessment.installed) {
                    message.value = "No installed copy exists; uninstall is unnecessary. Staging the verified APK directly."
                    busy.value = false
                    installRecord(
                        record,
                        flowMode = "Uninstall & reinstall",
                        requirePackageAbsent = true,
                    )
                    return@launch
                }
                reinstallAssessment.value = assessment
                InstallFlowRuntime.update(
                    InstallFlowStage.WAITING_REINSTALL_CONFIRMATION,
                    "Reinstall plan ready · confirm before removal",
                    assessment.warning,
                    packageName = record.packageName,
                    mode = "Uninstall & reinstall",
                    removalMethod = assessment.removalMethod.displayLabel(),
                    dataPreservation = assessment.preservationMode.displayLabel(),
                )
                message.value = null
            } catch (failure: Throwable) {
                val detail = failure.message ?: failure.javaClass.simpleName
                InstallFlowRuntime.update(
                    InstallFlowStage.FAILED,
                    "Could not assess reinstall",
                    detail,
                    packageName = record.packageName,
                )
                message.value = detail
            } finally {
                busy.value = false
            }
        }
    }

    private fun confirmReinstall() {
        val assessment = reinstallAssessment.value ?: return
        reinstallAssessment.value = null
        if (busy.value) return

        if (assessment.removalMethod == ReinstallRemovalMethod.ANDROID_UNINSTALL_UI) {
            installWaitingForRemoval = assessment.record
            InstallFlowRuntime.update(
                InstallFlowStage.WAITING_UNINSTALL_CONFIRMATION,
                "Waiting for Android uninstall confirmation",
                "No silent privileged removal transport is ready. Confirm uninstall in Android. APKbox will then wait until PackageManager proves the old package is gone before staging the replacement.",
                packageName = assessment.record.packageName,
                mode = "Uninstall & reinstall",
                removalMethod = assessment.removalMethod.displayLabel(),
                dataPreservation = assessment.preservationMode.displayLabel(),
            )
            message.value = "Using Android's uninstall confirmation because no privileged uninstall transport is ready. Replacement staging is blocked until removal is verified."
            @Suppress("DEPRECATION")
            val uninstallIntent = Intent(
                Intent.ACTION_UNINSTALL_PACKAGE,
                Uri.parse("package:${assessment.record.packageName}"),
            ).putExtra(Intent.EXTRA_RETURN_RESULT, true)
            runCatching { uninstallLauncher.launch(uninstallIntent) }
                .onFailure { failure ->
                    installWaitingForRemoval = null
                    val detail = failure.message ?: "Android could not open uninstall confirmation."
                    InstallFlowRuntime.update(
                        InstallFlowStage.FAILED,
                        "Could not open uninstall confirmation",
                        detail,
                        packageName = assessment.record.packageName,
                    )
                    message.value = detail
                }
            return
        }

        lifecycleScope.launch {
            busy.value = true
            val stage = if (assessment.preservationMode == ReinstallPreservationMode.ROOT_BEST_EFFORT) {
                InstallFlowStage.BACKING_UP_DATA
            } else {
                InstallFlowStage.REQUESTING_UNINSTALL
            }
            val stageTitle = when (assessment.preservationMode) {
                ReinstallPreservationMode.ROOT_BEST_EFFORT -> "Backing up private data before uninstall"
                ReinstallPreservationMode.ANDROID_KEEP_DATA -> "Uninstalling package registration · keeping Android app data"
                else -> "Uninstalling currently installed package"
            }
            InstallFlowRuntime.update(
                stage,
                stageTitle,
                "Removal transport: ${assessment.transportLabel}. ${assessment.warning}",
                packageName = assessment.record.packageName,
                mode = "Uninstall & reinstall",
                removalMethod = assessment.removalMethod.displayLabel(),
                dataPreservation = assessment.preservationMode.displayLabel(),
            )
            message.value = when (assessment.preservationMode) {
                ReinstallPreservationMode.ROOT_BEST_EFFORT ->
                    "Creating root-only private-data backup before removing the conflicting signer…"
                ReinstallPreservationMode.ANDROID_KEEP_DATA ->
                    "Removing the package registration while asking Android to retain its app data…"
                else -> "Removing the currently installed package through ${assessment.transportLabel}…"
            }
            try {
                val removed = reinstallCoordinator.removeInstalled(assessment)
                check(removed.removed) { removed.detail }
                stageVerifiedReplacement(assessment.record, removed.detail)
            } catch (failure: Throwable) {
                val detail = failure.message ?: failure.javaClass.simpleName
                InstallFlowRuntime.update(
                    InstallFlowStage.FAILED,
                    "Uninstall/reinstall stopped before replacement staging",
                    detail,
                    packageName = assessment.record.packageName,
                )
                message.value = detail
                busy.value = false
            }
        }
    }

    private fun confirmReplacement() {
        val request = replaceRequest.value ?: return
        replaceRequest.value = null
        installWaitingForRemoval = request.record
        InstallFlowRuntime.update(
            InstallFlowStage.WAITING_UNINSTALL_CONFIRMATION,
            "Waiting for Android uninstall confirmation",
            "Normal install cannot replace the installed signature/version in place. After uninstall, APKbox will verify package absence and stage the already-verified APK directly without re-running conflict detection.",
            packageName = request.record.packageName,
            mode = "Install",
            removalMethod = "Android uninstall confirmation",
            dataPreservation = "Not guaranteed by normal replacement path",
        )
        @Suppress("DEPRECATION")
        val uninstallIntent = Intent(
            Intent.ACTION_UNINSTALL_PACKAGE,
            Uri.parse("package:${request.record.packageName}"),
        ).putExtra(Intent.EXTRA_RETURN_RESULT, true)
        runCatching { uninstallLauncher.launch(uninstallIntent) }
            .onFailure { failure ->
                installWaitingForRemoval = null
                val detail = failure.message ?: "Android could not open uninstall confirmation."
                InstallFlowRuntime.update(
                    InstallFlowStage.FAILED,
                    "Could not open uninstall confirmation",
                    detail,
                    packageName = request.record.packageName,
                )
                message.value = detail
            }
    }

    /**
     * Hard post-uninstall barrier. This path deliberately never calls requestInstall(), because
     * requestInstall() is a pre-removal decision function. Re-entering it was the source of the
     * signature-conflict loop when PackageManager had not yet converged after Android uninstall UI.
     */
    private suspend fun stageVerifiedReplacement(record: ApkRecord, removalDetail: String) {
        InstallFlowRuntime.update(
            InstallFlowStage.VERIFYING_REMOVAL,
            "Verifying removal barrier",
            "$removalDetail APKbox is independently checking PackageManager before any replacement session can be created.",
            packageName = record.packageName,
        )
        val absent = awaitPackageRemoved(record.packageName, PACKAGE_REMOVAL_TIMEOUT_MS)
        if (!absent) {
            InstallFlowRuntime.update(
                InstallFlowStage.FAILED,
                "Old package is still installed",
                "The uninstall operation reported success, but ${record.packageName} remains installed. APKbox refused to stage the replacement so it cannot loop back into signature-conflict handling.",
                packageName = record.packageName,
            )
            message.value = "Old package still appears installed. Replacement was not staged."
            busy.value = false
            return
        }

        busy.value = false
        installRecord(
            record = record,
            flowMode = "Uninstall & reinstall",
            requirePackageAbsent = true,
            precedingDetail = removalDetail,
        )
    }

    private fun installRecord(
        record: ApkRecord,
        flowMode: String,
        requirePackageAbsent: Boolean = false,
        precedingDetail: String = "",
    ) {
        if (busy.value) return
        lifecycleScope.launch {
            busy.value = true
            try {
                if (requirePackageAbsent) {
                    InstallFlowRuntime.update(
                        InstallFlowStage.VERIFYING_REMOVAL,
                        "Final package-absence check",
                        "The replacement session cannot be created until ${record.packageName} is absent.",
                        packageName = record.packageName,
                        mode = flowMode,
                    )
                    check(awaitPackageRemoved(record.packageName, 2_500L)) {
                        "Replacement staging blocked because ${record.packageName} still appears installed."
                    }
                }

                InstallFlowRuntime.update(
                    InstallFlowStage.STAGING_PACKAGE_INSTALLER,
                    "Staging exact APK into Android Package Installer",
                    buildString {
                        if (precedingDetail.isNotBlank()) append(precedingDetail).append(' ')
                        append("APKbox is streaming the archived exact bytes into one PackageInstaller session. Full outgoing SHA-256 verification must finish before commit.")
                    },
                    packageName = record.packageName,
                    mode = flowMode,
                )
                installProgress.value = InstallProgress(0L, record.sizeBytes, directPreparedSource = true)
                apkInstaller.install(
                    record = record,
                    preparedSource = preparedApk?.file,
                    onProgress = { progress -> installProgress.value = progress },
                )

                // Do not finish this Activity. Keeping APKbox foreground underneath the Android
                // confirmation makes STATUS_PENDING_USER_ACTION launch reliable and gives the user a
                // deterministic place to return for cancelled/failed status.
                if (InstallFlowRuntime.status.value.stage == InstallFlowStage.STAGING_PACKAGE_INSTALLER) {
                    InstallFlowRuntime.update(
                        InstallFlowStage.WAITING_PACKAGE_INSTALLER_CONFIRMATION,
                        "Waiting for Android Package Installer",
                        "The exact verified APK session is committed. Android Package Installer should now be in front of APKbox. Confirm Install there; if you cancel or Android rejects it, return here for the exact result.",
                        packageName = record.packageName,
                        mode = flowMode,
                    )
                }
                message.value = "Verified APK staged · waiting for Android Package Installer confirmation"
                if (InstallFlowRuntime.status.value.stage.terminal) busy.value = false
            } catch (t: Throwable) {
                val detail = t.message ?: "Installation could not be staged."
                InstallFlowRuntime.update(
                    InstallFlowStage.FAILED,
                    "Replacement staging failed",
                    detail,
                    packageName = record.packageName,
                    mode = flowMode,
                )
                message.value = detail
                busy.value = false
            } finally {
                installProgress.value = null
            }
        }
    }

    private suspend fun awaitPackageRemoved(packageName: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        do {
            if (inspectInstalled(packageName) == null) return true
            if (System.currentTimeMillis() >= deadline) break
            delay(PACKAGE_REMOVAL_POLL_MS)
        } while (true)
        return inspectInstalled(packageName) == null
    }

    private suspend fun inspectInstalled(packageName: String) = withContext(Dispatchers.IO) {
        ApkInspector.inspectInstalled(this@OpenApkInstallerActivity, packageName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenApkInstallerScreen(
    preview: SharedApkPreview?,
    projects: List<com.mekromn.apkbox.model.ApkProject>,
    records: List<ApkRecord>,
    busy: Boolean,
    message: String?,
    replaceRequest: ReplaceRequest?,
    reinstallAssessment: ReinstallAssessment?,
    installProgress: InstallProgress?,
    installFlow: InstallFlowStatus,
    forcedMode: OpenInstallMode?,
    onCancel: () -> Unit,
    onArchiveAndInstall: (String, OpenInstallMode) -> Unit,
    onConfirmReplace: () -> Unit,
    onCancelReplace: () -> Unit,
    onConfirmReinstall: () -> Unit,
    onCancelReinstall: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember(preview?.packageName, projects) {
        mutableStateOf(projects.firstOrNull()?.id ?: OPEN_INSTALLER_NEW_PROJECT)
    }

    LaunchedEffect(preview?.packageName, projects.map { it.id }) {
        if (selected != OPEN_INSTALLER_NEW_PROJECT && projects.none { it.id == selected }) {
            selected = projects.firstOrNull()?.id ?: OPEN_INSTALLER_NEW_PROJECT
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Cancel")
                        }
                    },
                    title = {
                        Text(
                            when (forcedMode) {
                                OpenInstallMode.NORMAL -> "APKbox · Install"
                                OpenInstallMode.UNATTENDED -> "APKbox · Unattended"
                                OpenInstallMode.REINSTALL -> "APKbox · Uninstall & reinstall"
                                null -> "APKbox Installer"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                when {
                    installProgress != null -> LinearProgressIndicator(
                        progress = { installProgress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            if (preview != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (forcedMode == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
                                Spacer(Modifier.weight(1f))
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = { onArchiveAndInstall(selected, OpenInstallMode.NORMAL) },
                                ) { Text("Install") }
                                Button(
                                    enabled = !busy,
                                    onClick = { onArchiveAndInstall(selected, OpenInstallMode.UNATTENDED) },
                                ) { Text("Unattended") }
                            }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { onArchiveAndInstall(selected, OpenInstallMode.REINSTALL) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Uninstall & reinstall")
                                    Text(
                                        "Fix signature conflicts · Android Package Installer",
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
                                Button(
                                    enabled = !busy,
                                    onClick = { onArchiveAndInstall(selected, forcedMode) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            when (forcedMode) {
                                                OpenInstallMode.NORMAL -> "Archive & install"
                                                OpenInstallMode.UNATTENDED -> "Archive & install unattended"
                                                OpenInstallMode.REINSTALL -> "Uninstall & reinstall"
                                            }
                                        )
                                        if (forcedMode == OpenInstallMode.REINSTALL) {
                                            Text(
                                                "Fix signature conflicts · Android Package Installer",
                                                style = MaterialTheme.typography.labelSmall,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                preview == null && message == null -> {
                    Spacer(Modifier.size(72.dp))
                    Text("Reading APK…", style = MaterialTheme.typography.titleLarge)
                }
                preview != null -> {
                    IncomingApkIcon(preview)
                    Spacer(Modifier.size(16.dp))
                    Text(
                        preview.label,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        preview.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${preview.versionName} · code ${preview.versionCode} · ${Formatter.formatFileSize(context, preview.sizeBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (installFlow.visible) {
                        Spacer(Modifier.size(14.dp))
                        InstallFlowStatusCard(installFlow)
                    }

                    if (installProgress != null) {
                        Spacer(Modifier.size(12.dp))
                        val percent = (installProgress.fraction * 100f).toInt().coerceIn(0, 100)
                        Text(
                            "Preparing install · ${Formatter.formatFileSize(context, installProgress.bytesWritten)} / ${Formatter.formatFileSize(context, installProgress.totalBytes)} · $percent%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (installProgress.directPreparedSource) "Direct verified source stream"
                            else "Parallel vault prefetch · ordered exact stream",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.size(24.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                "Choose where to archive this APK",
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            projects.forEach { project ->
                                val alreadyStored = records.any {
                                    it.projectId == project.id && it.sha256 == preview.sha256
                                }
                                InstallerProjectRow(
                                    title = project.name,
                                    subtitle = if (alreadyStored) "Exact APK already archived here" else project.packageName,
                                    selected = selected == project.id,
                                    icon = if (alreadyStored) Icons.Rounded.CheckCircle else Icons.Rounded.Folder,
                                    onClick = { selected = project.id },
                                )
                                HorizontalDivider()
                            }
                            InstallerProjectRow(
                                title = "Create new Project",
                                subtitle = "Use ${preview.label} as the new base APK",
                                selected = selected == OPEN_INSTALLER_NEW_PROJECT,
                                icon = Icons.Rounded.CreateNewFolder,
                                onClick = { selected = OPEN_INSTALLER_NEW_PROJECT },
                            )
                        }
                    }

                    Spacer(Modifier.size(16.dp))
                    Text(
                        when (forcedMode) {
                            null -> "All install types archive and verify the exact incoming APK first. Install uses Android's normal confirmation. Unattended uses Shizuku/Sui or paired self-healing Wireless ADB. Uninstall & reinstall is the explicit signature-conflict repair path: it selects the best available removal method, reports whether app data can be preserved, verifies the old package is truly absent, then stages the verified APK through Android Package Installer."
                            OpenInstallMode.NORMAL -> "Direct resolver mode: Install. APKbox will archive and verify the exact incoming APK, then use Android's normal Package Installer confirmation."
                            OpenInstallMode.UNATTENDED -> "Direct resolver mode: Unattended. APKbox will archive and verify the exact incoming APK, then use Shizuku/Sui or paired self-healing Wireless ADB for the unattended install."
                            OpenInstallMode.REINSTALL -> "Direct resolver mode: Uninstall & reinstall. APKbox will archive and verify the exact incoming APK, probe all removal/data-preservation methods, report the result before removal, enforce a verified package-absence barrier, then install through Android Package Installer."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (preview == null && installFlow.visible) {
                Spacer(Modifier.size(18.dp))
                InstallFlowStatusCard(installFlow)
            }

            if (!message.isNullOrBlank()) {
                Spacer(Modifier.size(18.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }

    replaceRequest?.let { request ->
        val installed = request.installedVersionName
            ?.let { "$it (code ${request.installedVersionCode})" }
            ?: "code ${request.installedVersionCode}"
        val reason = when (request.reason) {
            ReplaceReason.DOWNGRADE -> "This archived APK is older than the currently installed $installed."
            ReplaceReason.SIGNATURE_MISMATCH -> "This archived APK is signed differently from the currently installed $installed."
        }
        AlertDialog(
            onDismissRequest = onCancelReplace,
            title = { Text("Replace installed app?") },
            text = {
                Text(
                    "$reason\n\nAndroid requires the installed app to be removed first. This normal replacement path can remove that app's local data. Cancel and use Uninstall & reinstall if you want APKbox to probe Shizuku/Sui, Wireless ADB, and available data-preservation methods first."
                )
            },
            confirmButton = { TextButton(onClick = onConfirmReplace) { Text("Uninstall & install") } },
            dismissButton = { TextButton(onClick = onCancelReplace) { Text("Cancel") } },
        )
    }

    reinstallAssessment?.let { assessment ->
        val installed = assessment.installedVersionName
            ?.let { "$it (code ${assessment.installedVersionCode})" }
            ?: "code ${assessment.installedVersionCode}"
        val signature = when (assessment.signatureRelationship) {
            ReinstallSignatureRelationship.SAME -> "Signing certificate: matches"
            ReinstallSignatureRelationship.DIFFERENT -> "Signing certificate: CONFLICT"
            ReinstallSignatureRelationship.UNKNOWN -> "Signing certificate: could not be proven equal"
        }
        val preservation = assessment.preservationMode.displayLabel()
        val removal = assessment.removalMethod.displayLabel()
        val confirmLabel = when (assessment.preservationMode) {
            ReinstallPreservationMode.ROOT_BEST_EFFORT -> "Back up data & reinstall"
            ReinstallPreservationMode.ANDROID_KEEP_DATA -> "Keep data & reinstall"
            ReinstallPreservationMode.NONE -> "Uninstall anyway"
            ReinstallPreservationMode.NOT_NEEDED -> "Install"
        }

        AlertDialog(
            onDismissRequest = onCancelReinstall,
            title = { Text("Uninstall & reinstall?") },
            text = {
                Text(
                    "Installed: $installed\n$signature\nRemoval: $removal\nPrivate data: $preservation\n\n${assessment.warning}\n\nAfter removal, APKbox will wait until PackageManager proves the old package is absent. Only then will it stage the exact archived APK into Android Package Installer."
                )
            },
            confirmButton = { TextButton(onClick = onConfirmReinstall) { Text(confirmLabel) } },
            dismissButton = { TextButton(onClick = onCancelReinstall) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InstallFlowStatusCard(status: InstallFlowStatus) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val stepText = if (status.stage.step > 0) {
                "Step ${status.stage.step}/${status.stage.stepCount}"
            } else {
                "Install status"
            }
            Text(stepText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(status.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (status.detail.isNotBlank()) {
                Text(status.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status.mode.isNotBlank()) {
                Text("Mode: ${status.mode}", style = MaterialTheme.typography.labelMedium)
            }
            if (status.removalMethod.isNotBlank()) {
                Text("Removal: ${status.removalMethod}", style = MaterialTheme.typography.labelMedium)
            }
            if (status.dataPreservation.isNotBlank()) {
                Text("Data: ${status.dataPreservation}", style = MaterialTheme.typography.labelMedium)
            }
            if (status.packageName.isNotBlank()) {
                Text(status.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun IncomingApkIcon(preview: SharedApkPreview) {
    val bitmap = remember(preview.sha256, preview.iconPng) {
        preview.iconPng?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = preview.label, modifier = Modifier.size(88.dp))
    } else {
        Icon(
            Icons.Rounded.Android,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstallerProjectRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

private fun OpenInstallMode.flowLabel(): String = when (this) {
    OpenInstallMode.NORMAL -> "Install"
    OpenInstallMode.UNATTENDED -> "Unattended"
    OpenInstallMode.REINSTALL -> "Uninstall & reinstall"
}

private fun ReinstallRemovalMethod.displayLabel(): String = when (this) {
    ReinstallRemovalMethod.SHIZUKU_ROOT -> "Shizuku/Sui root"
    ReinstallRemovalMethod.SHIZUKU_SHELL -> "Shizuku shell"
    ReinstallRemovalMethod.WIRELESS_ADB -> "Wireless ADB"
    ReinstallRemovalMethod.ANDROID_UNINSTALL_UI -> "Android uninstall confirmation · no Wi-Fi required"
    ReinstallRemovalMethod.NONE -> "Not needed"
}

private fun ReinstallPreservationMode.displayLabel(): String = when (this) {
    ReinstallPreservationMode.ANDROID_KEEP_DATA -> "Android keep-data available"
    ReinstallPreservationMode.ROOT_BEST_EFFORT -> "Root/Sui best-effort backup + restore"
    ReinstallPreservationMode.NONE -> "Cannot be preserved with the currently available method"
    ReinstallPreservationMode.NOT_NEEDED -> "No installed package"
}
