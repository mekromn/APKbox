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
import com.mekromn.apkbox.R

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
        val packageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_TARGET_LABEL).orEmpty().ifBlank { packageName }
        val version = intent.getStringExtra(EXTRA_TARGET_VERSION).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    val launched = runCatching { context.startActivity(launchIntent) }.isSuccess
                    if (!launched) notifyInstalled(context, label, version, launchIntent)
                } else {
                    notifyMessage(context, "$label installed", "The package has no launchable activity.")
                }
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Android rejected the installation."
                notifyMessage(context, "Install failed", message)
            }
        }
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
            .setContentText(message)
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
