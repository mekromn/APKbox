package com.mekromn.apkbox.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mekromn.apkbox.ApkBoxServices
import com.mekromn.apkbox.agent.AgentActionLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BridgeExecutor(
    context: Context,
    private val privileged: PrivilegedBridgeManager,
    private val stateStore: BridgeStateStore,
) {
    companion object {
        /** Legacy channel retained for already-installed builds; new messages use mode-specific channels. */
        const val INFO_CHANNEL_ID = "apkbox-bridge-info"
        private const val INFO_STANDARD_CHANNEL_ID = "apkbox-bridge-info-standard-v1"
        private const val INFO_HEADS_UP_CHANNEL_ID = "apkbox-bridge-info-headsup-v1"
        private const val INFO_NOTIFICATION_BASE = 74_000
        private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val serviceRegex = Regex("[A-Za-z0-9_.:-]{1,128}")
        private val safeExtraRegex = Regex("[A-Za-z0-9_.*:/@=,+ -]{0,512}")
    }

    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val screenAgent = ScreenAgentController(appContext, privileged)
    private val actionLedger = AgentActionLedger(appContext)
    private val prefs by lazy { ApkBoxServices.bridgePreferences(appContext) }
    private val relay by lazy { ApkBoxServices.relayClient() }
    private val advanced by lazy { AdvancedBridgeCoordinator(appContext, relay) }
    private val remoteApkInstaller by lazy {
        RemoteApkInstallCoordinator(
            context = appContext,
            library = ApkBoxServices.libraryStore(appContext),
            privileged = privileged,
        )
    }

    init {
        createChannels()
    }

    suspend fun execute(request: BridgeRequest, risk: BridgeRisk): BridgeResult {
        val started = System.currentTimeMillis()
        if (needsActionReservation(request.type)) {
            val reservation = actionLedger.reserve(
                requestId = request.id,
                runId = request.runId,
                sequenceNumber = request.sequenceNumber,
            )
            if (!reservation.mayExecute) {
                return BridgeResult(
                    requestId = request.id,
                    status = BridgeResultStatus.INVALID,
                    risk = risk,
                    detail = reservation.detail,
                    durationMs = System.currentTimeMillis() - started,
                    foregroundPackage = runCatching { screenAgent.foregroundPackage() }.getOrDefault(""),
                )
            }
        }

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

                // Backward-compatible generic message verbs use the user's configured default.
                BridgeCommandType.NOTIFICATION,
                BridgeCommandType.POPUP -> {
                    val detail = deliverConfiguredMessage(request)
                    success(request, risk, detail, started)
                }

                // New explicit presentation verbs let the agent choose the best information surface.
                BridgeCommandType.MESSAGE_SMALL_POPUP,
                BridgeCommandType.MESSAGE_ALWAYS_ON_TOP,
                BridgeCommandType.MESSAGE_FULL_WINDOW,
                BridgeCommandType.MESSAGE_HEADS_UP -> {
                    val detail = deliverExplicitMessage(request)
                    success(request, risk, detail, started)
                }

                BridgeCommandType.PICTURE_MESSAGE -> {
                    val detail = deliverPictureMessage(request)
                    success(request, risk, detail, started)
                }

                BridgeCommandType.APK_INSTALL_URL -> remoteApkInstaller.execute(request, risk)

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
                BridgeCommandType.AGENT_START,
                BridgeCommandType.AGENT_RESUME,
                BridgeCommandType.AGENT_STATUS,
                BridgeCommandType.BUILD_START,
                BridgeCommandType.BUILD_STATUS -> executeAdvanced(request, risk)
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

    private suspend fun executeAdvanced(request: BridgeRequest, risk: BridgeRisk): BridgeResult {
        check(AdvancedBridgeCoordinator.handles(request.type)) { "Unsupported advanced bridge request ${request.type}." }
        val config = prefs.state.value
        check(config.enabled) { "Remote Debug Bridge is not enabled." }
        val token = prefs.relayToken()
        check(token.isNotBlank()) { "Continuity relay token is not configured." }
        return advanced.execute(request, risk, config, token)
    }

    private fun needsActionReservation(type: BridgeCommandType): Boolean = when (type) {
        BridgeCommandType.LAUNCH,
        BridgeCommandType.UI_TAP,
        BridgeCommandType.UI_FIND_TAP,
        BridgeCommandType.UI_SWIPE,
        BridgeCommandType.UI_TEXT,
        BridgeCommandType.UI_KEY,
        BridgeCommandType.UI_WAIT -> true
        else -> false
    }

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

    /** Legacy NOTIFICATION/POPUP follow the local default presentation preference. */
    private suspend fun deliverConfiguredMessage(request: BridgeRequest): String {
        val popup = popupFrom(request)
        stateStore.savePopup(popup)
        val config = prefs.state.value

        return when (config.messagePresentation) {
            BridgeMessagePresentation.STANDARD_NOTIFICATION -> {
                postInformationNotification(request, headsUp = false)
                "Bridge message delivered as a standard notification."
            }
            BridgeMessagePresentation.HEADS_UP -> {
                postInformationNotification(request, headsUp = true)
                "Bridge message delivered as a heads-up notification."
            }
            BridgeMessagePresentation.POPUP_ACTIVITY -> {
                if (config.keepNotificationCopy) postInformationNotification(request, headsUp = false)
                if (launchPopupActivity()) {
                    "Bridge message delivered as the APKbox popup activity${if (config.keepNotificationCopy) " with a notification copy" else ""}."
                } else {
                    postInformationNotification(request, headsUp = true)
                    "Android did not surface the popup activity; APKbox used a heads-up notification fallback."
                }
            }
            BridgeMessagePresentation.ALWAYS_ON_TOP -> {
                val shown = BridgeOverlayController.show(appContext, popup, stateStore)
                if (shown) {
                    if (config.keepNotificationCopy) postInformationNotification(request, headsUp = false)
                    "Bridge message delivered as a persistent always-on-top overlay${if (config.keepNotificationCopy) " with a notification copy" else ""}."
                } else {
                    postInformationNotification(request, headsUp = true)
                    "Always-on-top permission is unavailable; APKbox used a heads-up notification fallback."
                }
            }
        }
    }

    /** New structured verbs bypass the legacy default so the agent can intentionally choose format. */
    private suspend fun deliverExplicitMessage(request: BridgeRequest): String {
        val popup = popupFrom(request)
        stateStore.savePopup(popup)
        return when (request.type) {
            BridgeCommandType.MESSAGE_SMALL_POPUP -> {
                val duration = request.durationMs.takeIf { it >= 1_500 } ?: 4_500
                if (BridgeSmallPopupController.show(appContext, popup, duration)) {
                    "Compact floating bridge popup shown for ${duration.coerceIn(1_500, 10_000)} ms."
                } else {
                    postInformationNotification(request, headsUp = true)
                    "Small-popup overlay permission is unavailable; APKbox used a heads-up notification fallback."
                }
            }
            BridgeCommandType.MESSAGE_ALWAYS_ON_TOP -> {
                if (BridgeOverlayController.show(appContext, popup, stateStore)) {
                    "Persistent always-on-top bridge message shown until dismissed."
                } else {
                    postInformationNotification(request, headsUp = true)
                    "Always-on-top permission is unavailable; APKbox used a heads-up notification fallback."
                }
            }
            BridgeCommandType.MESSAGE_FULL_WINDOW -> {
                if (launchPopupActivity()) {
                    "Full-window APKbox bridge message opened."
                } else {
                    postInformationNotification(request, headsUp = true)
                    "Android blocked the full-window message; APKbox used a heads-up notification fallback."
                }
            }
            BridgeCommandType.MESSAGE_HEADS_UP -> {
                postInformationNotification(request, headsUp = true)
                "Expandable heads-up bridge message delivered."
            }
            else -> error("${request.type} is not an explicit text-message presentation.")
        }
    }

    private suspend fun deliverPictureMessage(request: BridgeRequest): String {
        require(request.imagePath.isNotBlank()) { "PICTURE_MESSAGE requires imagePath." }
        val config = prefs.state.value
        val token = prefs.relayToken()
        check(token.isNotBlank()) { "Continuity relay token is not configured." }
        val bytes = relay.fetchMessageImage(config, token, request.imagePath)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("PICTURE_MESSAGE imagePath did not contain a decodable Android image.")
        check(decoded.width > 0 && decoded.height > 0) { "PICTURE_MESSAGE image has invalid dimensions." }
        decoded.recycle()

        val localImagePath = stateStore.saveMessageImage(request.id, bytes)
        val popup = popupFrom(request).copy(imageFilePath = localImagePath)
        stateStore.savePopup(popup)

        return if (launchPopupActivity()) {
            "Picture message opened full-window from private Continuity asset ${request.imagePath}."
        } else {
            postPictureNotification(request, bytes)
            "Android blocked the picture window; APKbox used an expandable BigPicture heads-up notification fallback."
        }
    }

    private fun popupFrom(request: BridgeRequest) = BridgePopupMessage(
        title = request.title.ifBlank { "ChatGPT via APKbox" }.take(256),
        message = request.message.ifBlank { request.reason }.take(8_192),
        requestId = request.id,
    )

    private suspend fun launchPopupActivity(): Boolean {
        if (runCatching { privileged.ensureReady() }.getOrDefault(false)) {
            val shell = runCatching {
                privileged.execute(
                    "am start -n ${appContext.packageName}/.bridge.BridgeMessageActivity --activity-new-task --activity-clear-top",
                    8,
                )
            }.getOrNull()
            if (shell != null && !shell.timedOut && (shell.exitCode == null || shell.exitCode == 0)) return true
        }

        val intent = Intent(appContext, BridgeMessageActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private suspend fun executeShell(
        request: BridgeRequest,
        risk: BridgeRisk,
        command: String,
        started: Long,
    ): BridgeResult {
        val shell = privileged.execute(command, request.timeoutSeconds)
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
                BridgeResultStatus.SUCCESS -> "Command completed through ${privileged.activeTransportLabel()}."
                BridgeResultStatus.TIMED_OUT -> "Command timed out through ${privileged.activeTransportLabel()}."
                else -> "Command exited with code ${shell.exitCode} through ${privileged.activeTransportLabel()}."
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

    private fun messageIntents(request: BridgeRequest): Triple<PendingIntent, PendingIntent, String> {
        val openMessage = Intent(appContext, BridgeMessageActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPending = PendingIntent.getActivity(
            appContext,
            request.id.hashCode(),
            openMessage,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val settingsIntent = Intent(appContext, BridgeMessageDisplaySettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val settingsPending = PendingIntent.getActivity(
            appContext,
            request.id.hashCode() xor 0x4B17,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Triple(openPending, settingsPending, request.message.ifBlank { request.reason }.take(8_000))
    }

    private fun postInformationNotification(request: BridgeRequest, headsUp: Boolean) {
        val (openPending, settingsPending, text) = messageIntents(request)
        val notification = NotificationCompat.Builder(
            appContext,
            if (headsUp) INFO_HEADS_UP_CHANNEL_ID else INFO_STANDARD_CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(request.title.ifBlank { "ChatGPT via APKbox" }.take(120))
            .setContentText(text.take(240))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (headsUp) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_preferences, "Display options", settingsPending)
            .build()
        notificationManager.notify(INFO_NOTIFICATION_BASE + (request.id.hashCode() and 0x0FFF), notification)
    }

    private fun postPictureNotification(request: BridgeRequest, bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Could not decode picture message for notification fallback.")
        val (openPending, settingsPending, text) = messageIntents(request)
        val notification = NotificationCompat.Builder(appContext, INFO_HEADS_UP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(request.title.ifBlank { "ChatGPT via APKbox" }.take(120))
            .setContentText(text.take(240))
            .setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .setSummaryText(text.take(240))
            )
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(android.R.drawable.ic_menu_preferences, "Display options", settingsPending)
            .build()
        notificationManager.notify(INFO_NOTIFICATION_BASE + (request.id.hashCode() and 0x0FFF), notification)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        notificationManager.createNotificationChannel(
            NotificationChannel(
                INFO_CHANNEL_ID,
                "APKbox ChatGPT messages (legacy)",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Legacy bridge message channel retained for compatibility"
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                INFO_STANDARD_CHANNEL_ID,
                "APKbox bridge messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Standard ChatGPT messages delivered through APKbox Remote Bridge"
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                INFO_HEADS_UP_CHANNEL_ID,
                "APKbox bridge heads-up messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Expandable heads-up ChatGPT messages delivered through APKbox Remote Bridge"
                enableVibration(true)
            }
        )
    }
}
