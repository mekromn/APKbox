package com.mekromn.apkbox.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BridgeExecutor(
    context: Context,
    private val adb: AdbBridgeManager,
    private val stateStore: BridgeStateStore,
) {
    companion object {
        const val INFO_CHANNEL_ID = "apkbox-bridge-info"
        private const val INFO_NOTIFICATION_BASE = 74_000
        private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val serviceRegex = Regex("[A-Za-z0-9_.:-]{1,128}")
        private val safeExtraRegex = Regex("[A-Za-z0-9_.*:/@=,+ -]{0,512}")
    }

    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val screenAgent = ScreenAgentController(appContext, adb)

    init {
        createChannels()
    }

    suspend fun execute(request: BridgeRequest, risk: BridgeRisk): BridgeResult {
        val started = System.currentTimeMillis()
        return runCatching {
            when (request.type) {
                BridgeCommandType.TOAST -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            request.message.ifBlank { request.title }.take(2_000),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    success(request, risk, "Toast delivered.", started)
                }
                BridgeCommandType.NOTIFICATION -> {
                    postInformationNotification(request)
                    success(request, risk, "Notification delivered.", started)
                }
                BridgeCommandType.POPUP -> {
                    deliverPopup(request)
                    success(request, risk, "Popup/instruction delivered.", started)
                }
                BridgeCommandType.LOGCAT -> executeShell(
                    request,
                    risk,
                    buildLogcatCommand(request.command),
                    started,
                )
                BridgeCommandType.APP_LOGCAT -> executeShell(
                    request,
                    risk,
                    buildAppLogcatCommand(request.packageName),
                    started,
                )
                BridgeCommandType.DUMPSYS -> executeShell(
                    request,
                    risk,
                    buildDumpsysCommand(request.service, request.command),
                    started,
                )
                BridgeCommandType.LAUNCH -> executeShell(
                    request,
                    risk,
                    buildLaunchCommand(request.packageName),
                    started,
                )
                BridgeCommandType.SHELL -> executeShell(request, risk, request.command, started)
                BridgeCommandType.UI_SNAPSHOT -> fromScreenResult(
                    request,
                    risk,
                    screenAgent.snapshot(request.id),
                    started,
                )
                BridgeCommandType.SCREENSHOT -> fromScreenResult(
                    request,
                    risk,
                    screenAgent.screenshot(request.id),
                    started,
                )
                BridgeCommandType.UI_TAP -> fromScreenResult(
                    request,
                    risk,
                    screenAgent.tap(request.packageName, request.x, request.y),
                    started,
                )
                BridgeCommandType.UI_FIND_TAP -> fromScreenResult(
                    request,
                    risk,
                    screenAgent.findAndTap(request.packageName, request.selector),
                    started,
                )
                BridgeCommandType.UI_SWIPE -> fromScreenResult(
                    request,
                    risk,
                    screenAgent.swipe(
                        request.packageName,
                        request.x,
                        request.y,
                        request.endX,
                        request.endY,
                        request.durationMs,
                    ),
                    started,
                )
                BridgeCommandType.UI_TEXT -> fromScreenResult(
                    request,
                    risk,
                    screenAgent.typeText(request.packageName, request.value),
                    started,
                )
                BridgeCommandType.UI_KEY -> fromScreenResult(
                    request,
                    risk,
                    screenAgent.key(request.packageName, request.keyCode),
                    started,
                )
                BridgeCommandType.UI_WAIT -> fromScreenResult(
                    request,
                    risk,
                    screenAgent.waitFor(request.packageName, request.selector, request.timeoutSeconds),
                    started,
                )
            }
        }.getOrElse { failure ->
            BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.FAILED,
                risk = risk,
                detail = failure.message ?: failure.javaClass.simpleName,
                durationMs = System.currentTimeMillis() - started,
                foregroundPackage = runCatching { screenAgent.foregroundPackage() }.getOrDefault(""),
            )
        }
    }

    fun deleteLocalArtifact(path: String) = screenAgent.deleteLocalArtifact(path)

    private fun fromScreenResult(
        request: BridgeRequest,
        risk: BridgeRisk,
        result: ScreenActionResult,
        started: Long,
    ): BridgeResult = BridgeResult(
        requestId = request.id,
        status = BridgeResultStatus.SUCCESS,
        risk = risk,
        detail = result.detail,
        output = result.output,
        durationMs = System.currentTimeMillis() - started,
        foregroundPackage = result.foregroundPackage,
        uiFingerprint = result.uiFingerprint,
    )

    private suspend fun deliverPopup(request: BridgeRequest) {
        val popup = BridgePopupMessage(
            title = request.title.ifBlank { "ChatGPT instruction" }.take(256),
            message = request.message.take(8_192),
            requestId = request.id,
        )
        stateStore.savePopup(popup)
        postInformationNotification(request)
        if (adb.ensureConnected()) {
            runCatching {
                adb.execute(
                    "am start -n ${appContext.packageName}/.bridge.BridgeMessageActivity --activity-new-task",
                    8,
                )
            }
        }
    }

    private suspend fun executeShell(
        request: BridgeRequest,
        risk: BridgeRisk,
        command: String,
        started: Long,
    ): BridgeResult {
        val shell = adb.execute(command, request.timeoutSeconds)
        val status = when {
            shell.timedOut -> BridgeResultStatus.TIMED_OUT
            shell.exitCode == null || shell.exitCode == 0 -> BridgeResultStatus.SUCCESS
            else -> BridgeResultStatus.FAILED
        }
        return BridgeResult(
            requestId = request.id,
            status = status,
            risk = risk,
            detail = when (status) {
                BridgeResultStatus.SUCCESS -> "Command completed."
                BridgeResultStatus.TIMED_OUT -> "Command timed out."
                else -> "Command exited with code ${shell.exitCode}."
            },
            output = shell.output,
            exitCode = shell.exitCode,
            durationMs = shell.durationMs.takeIf { it > 0 } ?: (System.currentTimeMillis() - started),
            truncated = shell.truncated,
        )
    }

    private fun buildLogcatCommand(filter: String): String {
        val clean = filter.trim()
        require(clean.isEmpty() || safeExtraRegex.matches(clean)) { "Unsafe logcat filter." }
        return buildString {
            append("logcat -d -v threadtime -t 5000")
            if (clean.isNotEmpty()) append(' ').append(clean)
        }
    }

    private fun buildAppLogcatCommand(packageName: String): String {
        val pkg = packageName.trim()
        require(packageRegex.matches(pkg)) { "Invalid package name." }
        return "pid=\$(pidof $pkg); if [ -n \"\$pid\" ]; then logcat --pid=\$pid -d -v threadtime -t 5000; else echo 'Package is not running: $pkg'; fi"
    }

    private fun buildDumpsysCommand(service: String, extra: String): String {
        val target = service.trim()
        require(serviceRegex.matches(target)) { "Invalid dumpsys service." }
        val cleanExtra = extra.trim()
        require(cleanExtra.isEmpty() || safeExtraRegex.matches(cleanExtra)) { "Unsafe dumpsys arguments." }
        return buildString {
            append("dumpsys ").append(target)
            if (cleanExtra.isNotEmpty()) append(' ').append(cleanExtra)
        }
    }

    private fun buildLaunchCommand(packageName: String): String {
        val pkg = packageName.trim()
        require(packageRegex.matches(pkg)) { "Invalid package name." }
        return "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
    }

    private fun success(request: BridgeRequest, risk: BridgeRisk, detail: String, started: Long) = BridgeResult(
        requestId = request.id,
        status = BridgeResultStatus.SUCCESS,
        risk = risk,
        detail = detail,
        durationMs = System.currentTimeMillis() - started,
    )

    private fun postInformationNotification(request: BridgeRequest) {
        val intent = Intent(appContext, BridgeMessageActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (request.type != BridgeCommandType.POPUP) {
            stateStore.savePopup(
                BridgePopupMessage(
                    title = request.title.ifBlank { "ChatGPT via APKbox" },
                    message = request.message.ifBlank { request.reason },
                    requestId = request.id,
                )
            )
        }
        val pending = PendingIntent.getActivity(
            appContext,
            request.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, INFO_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(request.title.ifBlank { "ChatGPT via APKbox" }.take(120))
            .setContentText(request.message.ifBlank { request.reason }.take(240))
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.message.ifBlank { request.reason }.take(8_000)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(INFO_NOTIFICATION_BASE + (request.id.hashCode() and 0x0FFF), notification)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    INFO_CHANNEL_ID,
                    "APKbox ChatGPT messages",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Messages and debugging instructions delivered through APKbox Remote Bridge"
                }
            )
        }
    }
}
