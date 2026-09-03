package com.mekromn.apkbox.install

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mekromn.apkbox.ApkBoxServices
import com.mekromn.apkbox.R
import com.mekromn.apkbox.data.TempStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_TARGET_PACKAGE = "targetPackage"
        const val EXTRA_TARGET_LABEL = "targetLabel"
        const val EXTRA_TARGET_VERSION = "targetVersion"
        private const val CHANNEL_ID = "apkbox-installs"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val packageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_TARGET_LABEL).orEmpty().ifBlank { packageName }
        val version = intent.getStringExtra(EXTRA_TARGET_VERSION).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                InstallFlowRuntime.update(
                    stage = InstallFlowStage.WAITING_PACKAGE_INSTALLER_CONFIRMATION,
                    title = "Waiting for Android Package Installer",
                    detail = "The exact verified APK is staged. Confirm Install in Android's Package Installer. APKbox is staying open underneath so cancellation/failure can be reported instead of disappearing.",
                    packageName = packageName,
                )
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation == null) {
                    InstallFlowRuntime.update(
                        InstallFlowStage.FAILED,
                        "Package Installer confirmation missing",
                        "Android requested user confirmation but did not provide a confirmation Intent.",
                        packageName = packageName,
                    )
                    notifyMessage(
                        context,
                        "Install could not continue",
                        "Android did not provide the Package Installer confirmation screen.",
                    )
                } else {
                    runCatching { context.startActivity(confirmation) }
                        .onFailure { failure ->
                            val detail = failure.message ?: failure.javaClass.simpleName
                            InstallFlowRuntime.update(
                                InstallFlowStage.FAILED,
                                "Could not open Package Installer",
                                detail,
                                packageName = packageName,
                            )
                            notifyConfirmationRequired(context, label, confirmation, detail)
                        }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                cleanupTerminalSession(context, sessionId)
                InstallFlowRuntime.update(
                    InstallFlowStage.INSTALLING,
                    "Android installed the APK",
                    "PackageInstaller reported success. APKbox is verifying any pending data-restore requirement before first launch.",
                    packageName = packageName,
                )
                val coordinator = ReinstallCoordinator(
                    context,
                    ApkBoxServices.privilegedBridge(context.applicationContext),
                )
                if (packageName.isNotBlank() && coordinator.hasPendingRestore(packageName)) {
                    restoreBeforeFirstLaunch(context, coordinator, packageName, label, version)
                } else {
                    finishSuccessfulInstall(context, packageName, label, version)
                }
            }

            else -> {
                cleanupTerminalSession(context, sessionId)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Android rejected the installation."
                val coordinator = ReinstallCoordinator(
                    context,
                    ApkBoxServices.privilegedBridge(context.applicationContext),
                )
                val backupNote = if (packageName.isNotBlank() && coordinator.hasPendingRestore(packageName)) {
                    " Preserved root backup was retained for a later retry."
                } else {
                    ""
                }
                val cancelled = status == PackageInstaller.STATUS_FAILURE_ABORTED
                InstallFlowRuntime.update(
                    stage = if (cancelled) InstallFlowStage.CANCELLED else InstallFlowStage.FAILED,
                    title = if (cancelled) "Install cancelled in Package Installer" else "Package Installer failed",
                    detail = message + backupNote,
                    packageName = packageName,
                )
                notifyMessage(
                    context,
                    if (cancelled) "Install cancelled" else "Install failed",
                    message + backupNote,
                )
            }
        }
    }

    private fun restoreBeforeFirstLaunch(
        context: Context,
        coordinator: ReinstallCoordinator,
        packageName: String,
        label: String,
        version: String,
    ) {
        InstallFlowRuntime.update(
            InstallFlowStage.RESTORING_DATA,
            "Restoring preserved app data",
            "The replacement APK is installed. APKbox is restoring the root/Sui backup before allowing the new signer to launch.",
            packageName = packageName,
        )
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val outcome = runCatching { coordinator.restorePending(packageName) }.getOrElse { failure ->
                    ReinstallRestoreOutcome(
                        hadPendingRestore = true,
                        restored = false,
                        detail = failure.message ?: failure.javaClass.simpleName,
                    )
                }
                if (outcome.restored) {
                    InstallFlowRuntime.update(
                        InstallFlowStage.COMPLETE,
                        "$label installed · data restored",
                        outcome.detail,
                        packageName = packageName,
                    )
                    notifyMessage(
                        context,
                        "$label installed · data restored",
                        outcome.detail,
                    )
                    finishSuccessfulInstall(context, packageName, label, version, updateRuntime = false)
                } else {
                    InstallFlowRuntime.update(
                        InstallFlowStage.COMPLETE_WITH_WARNING,
                        "$label installed · data restore incomplete",
                        outcome.detail + " APKbox did not launch the app; the saved backup was kept when possible.",
                        packageName = packageName,
                    )
                    // Do not auto-launch when restoration failed. Opening the replacement can create
                    // fresh state over files that the user still expects APKbox to recover.
                    notifyMessage(
                        context,
                        "$label installed · data restore incomplete",
                        outcome.detail + " APKbox did not launch the app; the saved backup was kept when possible.",
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun finishSuccessfulInstall(
        context: Context,
        packageName: String,
        label: String,
        version: String,
        updateRuntime: Boolean = true,
    ) {
        if (updateRuntime) {
            InstallFlowRuntime.update(
                InstallFlowStage.COMPLETE,
                "$label installed successfully",
                if (version.isBlank()) "Android completed the verified install." else "Android completed the verified install of version $version.",
                packageName = packageName,
            )
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val launched = runCatching { context.startActivity(launchIntent) }.isSuccess
            if (!launched) notifyInstalled(context, label, version, launchIntent)
        } else {
            notifyMessage(context, "$label installed", "The package has no launchable activity.")
        }
    }

    private fun cleanupTerminalSession(context: Context, sessionId: Int) {
        if (sessionId >= 0) {
            // Finished sessions are normally removed by Android immediately. If one still exists,
            // explicitly abandon it so staged APK bytes are not retained longer than necessary.
            runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
        }
        runCatching { TempStorageManager.cleanupRoutine(context) }
    }

    private fun notifyConfirmationRequired(
        context: Context,
        label: String,
        confirmation: Intent,
        failureDetail: String,
    ) {
        ensureChannel(context)
        if (!canNotify(context)) return
        val pending = PendingIntent.getActivity(
            context,
            (label + "confirm").hashCode(),
            confirmation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle("Confirm $label installation")
            .setContentText("Tap to open Android Package Installer")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "APKbox staged and verified the APK, but Android did not open Package Installer automatically: $failureDetail. Tap here to continue."
                )
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify((label + "confirmation").hashCode(), notification)
    }

    private fun notifyInstalled(context: Context, label: String, version: String, launchIntent: Intent) {
        ensureChannel(context)
        if (!canNotify(context)) return
        val pending = PendingIntent.getActivity(
            context,
            label.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle("$label installed")
            .setContentText(if (version.isBlank()) "Tap to open" else "Version $version · Tap to open")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify(label.hashCode(), notification)
    }

    private fun notifyMessage(context: Context, title: String, message: String) {
        ensureChannel(context)
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle(title)
            .setContentText(message.take(240))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(4_000)))
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify((title + message).hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "APK installations",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
