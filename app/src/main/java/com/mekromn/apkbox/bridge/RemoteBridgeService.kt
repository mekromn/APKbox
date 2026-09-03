package com.mekromn.apkbox.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mekromn.apkbox.ApkBoxServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

class RemoteBridgeService : Service() {
    companion object {
        const val ACTION_START = "com.mekromn.apkbox.bridge.START"
        const val ACTION_STOP = "com.mekromn.apkbox.bridge.STOP"
        const val ACTION_POLL_NOW = "com.mekromn.apkbox.bridge.POLL_NOW"
        const val ACTION_APPROVE_ONCE = "com.mekromn.apkbox.bridge.APPROVE_ONCE"
        const val ACTION_APPROVE_TRUST = "com.mekromn.apkbox.bridge.APPROVE_TRUST"
        const val ACTION_DENY = "com.mekromn.apkbox.bridge.DENY"

        private const val SERVICE_CHANNEL = "apkbox-remote-bridge-service"
        private const val APPROVAL_CHANNEL = "apkbox-remote-bridge-approval"
        private const val SERVICE_NOTIFICATION_ID = 73_001
        private const val APPROVAL_NOTIFICATION_ID = 73_002
        private const val HEARTBEAT_KEEPALIVE_MS = 6L * 60L * 60L * 1_000L
        private const val TRANSPORT_RECHECK_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, RemoteBridgeService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RemoteBridgeService::class.java).setAction(ACTION_STOP))
        }

        fun pollNow(context: Context) {
            val intent = Intent(context, RemoteBridgeService::class.java).setAction(ACTION_POLL_NOW)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { ApkBoxServices.bridgePreferences(applicationContext) }
    private val stateStore by lazy { ApkBoxServices.bridgeStateStore(applicationContext) }
    private val adb by lazy { ApkBoxServices.adbBridge(applicationContext) }
    private val privileged by lazy { ApkBoxServices.privilegedBridge(applicationContext) }
    private val relay by lazy { ApkBoxServices.relayClient() }
    private val executor by lazy { ApkBoxServices.bridgeExecutor(applicationContext) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private var loopJob: Job? = null
    @Volatile private var forcePoll = false
    private var lastHeartbeat = 0L
    private var lastHeartbeatFingerprint = ""
    private var lastTransportRecheck = 0L

    override fun onCreate() {
        super.onCreate()
        createChannels()
        privileged.refreshStatus()
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification("Starting…"))
        BridgeRuntime.update { it.copy(running = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                prefs.setEnabled(false)
                stopBridge()
                return START_NOT_STICKY
            }
            ACTION_APPROVE_ONCE -> scope.launch { resolvePending(allow = true, trust = false) }
            ACTION_APPROVE_TRUST -> scope.launch { resolvePending(allow = true, trust = true) }
            ACTION_DENY -> scope.launch { resolvePending(allow = false, trust = false) }
            ACTION_POLL_NOW -> forcePoll = true
        }
        ensureLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        notificationManager.cancel(APPROVAL_NOTIFICATION_ID)
        BridgeRuntime.reset()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { bridgeLoop() }
    }

    private suspend fun bridgeLoop() {
        while (scope.isActive) {
            val config = prefs.state.value
            if (!config.enabled) {
                updateForeground("Bridge disabled")
                delay(2_000)
                continue
            }

            val token = prefs.relayToken()
            if (token.isBlank()) {
                val error = "Continuity relay token is not configured."
                BridgeRuntime.update { it.copy(running = true, relayReachable = false, lastError = error) }
                updateForeground("Relay token needed")
                delay(5_000)
                continue
            }

            maybePreparePrivilegedTransport(config)
            val now = System.currentTimeMillis()

            runCatching { flushCompleted(config, token) }
                .onFailure { recordRelayFailure(it) }

            val pending = stateStore.loadPending()
            if (pending != null) {
                BridgeRuntime.update { it.copy(pendingRequestId = pending.request.id) }
                showApproval(pending)
                updateForeground("Waiting for approval · ${pending.risk.name.lowercase().replace('_', ' ')}")
                delay(config.pollSeconds * 1_000L)
                continue
            } else {
                BridgeRuntime.update { it.copy(pendingRequestId = "") }
            }

            val heartbeatFingerprint = heartbeatFingerprint(config)
            val heartbeatDue = lastHeartbeat == 0L ||
                heartbeatFingerprint != lastHeartbeatFingerprint ||
                now - lastHeartbeat >= HEARTBEAT_KEEPALIVE_MS
            if (heartbeatDue) {
                runCatching { relay.heartbeat(config, token, privileged.status.value) }
                    .onSuccess {
                        lastHeartbeat = now
                        lastHeartbeatFingerprint = heartbeatFingerprint
                        BridgeRuntime.update { status ->
                            status.copy(relayReachable = true, lastHeartbeatEpochMs = now, lastError = "")
                        }
                    }
                    .onFailure { recordRelayFailure(it) }
            }

            runCatching { pollInbox(config, token) }
                .onSuccess {
                    BridgeRuntime.update { status ->
                        status.copy(relayReachable = true, lastPollEpochMs = System.currentTimeMillis(), lastError = "")
                    }
                }
                .onFailure { recordRelayFailure(it) }

            updateForeground(statusLine())
            val delayMs = if (forcePoll) 250L else config.pollSeconds * 1_000L
            forcePoll = false
            delay(delayMs)
        }
    }

    private suspend fun pollInbox(config: BridgeConfig, token: String) {
        val items = relay.fetchInbox(config, token)
        for (item in items) {
            if (stateStore.hasCompleted(item.request.id)) continue
            val request = item.request
            val risk = BridgePolicy.classify(request)

            if (request.isExpired()) {
                journalResult(
                    item,
                    BridgeResult(
                        requestId = request.id,
                        status = BridgeResultStatus.EXPIRED,
                        risk = risk,
                        detail = "Request expired before APKbox executed it.",
                    ),
                )
                flushCompleted(config, token)
                continue
            }

            if (BridgePolicy.mayAutoExecute(
                    request = request,
                    trustedUntilEpochMs = config.trustedUntilEpochMs,
                    allowInformational = config.allowInformational,
                    allowPopups = config.allowPopups,
                )
            ) {
                executeAndJournal(item, risk)
                flushCompleted(config, token)
                continue
            }

            val pending = BridgePendingRequest(
                request = request,
                risk = risk,
                inboxPath = item.path,
                inboxSha = item.sha,
            )
            stateStore.savePending(pending)
            runCatching { relay.writeAwaitingApproval(config, token, request, risk) }
            stateStore.addEvent(
                "Approval requested",
                "${risk.name}: ${request.reason.ifBlank { requestSummary(request) }}",
                success = true,
            )
            BridgeRuntime.update { it.copy(pendingRequestId = request.id) }
            showApproval(pending)
            break
        }
    }

    private suspend fun executeAndJournal(item: RelayInboxItem, risk: BridgeRisk) {
        val result = executor.execute(item.request, risk)
        journalResult(item, result)
    }

    private fun journalResult(item: RelayInboxItem, result: BridgeResult) {
        stateStore.saveCompleted(
            BridgeCompletedEnvelope(
                request = item.request,
                inboxPath = item.path,
                inboxSha = item.sha,
                result = result,
            )
        )
        stateStore.addEvent(
            title = "${item.request.type.name.lowercase().replace('_', ' ')} · ${result.status.name.lowercase().replace('_', ' ')}",
            detail = item.request.reason.ifBlank { result.detail },
            success = result.status == BridgeResultStatus.SUCCESS,
        )
    }

    private suspend fun flushCompleted(config: BridgeConfig, token: String) {
        for (completed in stateStore.loadCompleted()) {
            var deliverable = completed.result
            var localArtifactPath: String? = null

            if (completed.request.type == BridgeCommandType.SCREENSHOT &&
                completed.result.output.startsWith(ScreenAgentController.LOCAL_ARTIFACT_PREFIX)
            ) {
                val path = completed.result.output.removePrefix(ScreenAgentController.LOCAL_ARTIFACT_PREFIX)
                val file = File(path)
                check(file.isFile) { "Journaled screenshot artifact is missing: $path" }
                val bytes = file.readBytes()
                val relayPath = relay.writeArtifact(
                    config = config,
                    token = token,
                    requestId = completed.request.id,
                    extension = "jpg",
                    bytes = bytes,
                )
                val options = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                deliverable = completed.result.copy(
                    output = "",
                    artifacts = listOf(
                        BridgeArtifact(
                            path = relayPath,
                            mimeType = "image/jpeg",
                            sha256 = sha256(bytes),
                            bytes = bytes.size.toLong(),
                            width = options.outWidth,
                            height = options.outHeight,
                        )
                    ),
                )
                localArtifactPath = path
            }

            // The local completion journal survives until artifact delivery (if any), result write,
            // and inbox deletion have all succeeded. A retry uploads the exact same bytes.
            relay.writeResult(config, token, deliverable)
            relay.deleteInbox(
                config = config,
                token = token,
                path = completed.inboxPath,
                sha = completed.inboxSha,
                requestId = completed.request.id,
            )
            localArtifactPath?.let(executor::deleteLocalArtifact)
            stateStore.clearCompleted(completed.request.id)
        }
    }

    private suspend fun resolvePending(allow: Boolean, trust: Boolean) {
        val pending = stateStore.loadPending() ?: return
        notificationManager.cancel(APPROVAL_NOTIFICATION_ID)
        stateStore.clearPending()
        BridgeRuntime.update { it.copy(pendingRequestId = "") }

        val item = RelayInboxItem(pending.inboxPath, pending.inboxSha, pending.request)
        val result = when {
            pending.request.isExpired() -> BridgeResult(
                requestId = pending.request.id,
                status = BridgeResultStatus.EXPIRED,
                risk = pending.risk,
                detail = "Request expired while waiting for approval.",
            )
            !allow -> BridgeResult(
                requestId = pending.request.id,
                status = BridgeResultStatus.DENIED,
                risk = pending.risk,
                detail = "Denied on device.",
            )
            else -> {
                if (trust && BridgePolicy.trustedSessionEligible(pending.request)) {
                    prefs.setTrustedUntil(System.currentTimeMillis() + 10 * 60_000L)
                }
                executor.execute(pending.request, pending.risk)
            }
        }
        journalResult(item, result)

        val config = prefs.state.value
        val token = prefs.relayToken()
        if (config.enabled && token.isNotBlank()) {
            runCatching { flushCompleted(config, token) }.onFailure { recordRelayFailure(it) }
        }
        forcePoll = true
    }

    /**
     * Shizuku/Sui is a peer transport, not an ADB convenience wrapper. If its UserService is ready,
     * the bridge does no periodic ADB rediscovery at all. This keeps the service useful off Wi-Fi and
     * avoids radio/mDNS work that cannot improve command execution. Only when Shizuku/Sui is absent
     * do we maintain the paired Wireless ADB fallback.
     */
    private suspend fun maybePreparePrivilegedTransport(config: BridgeConfig) {
        val now = System.currentTimeMillis()
        if (now - lastTransportRecheck < TRANSPORT_RECHECK_INTERVAL_MS) return
        lastTransportRecheck = now

        privileged.shizuku.refreshStatus()
        if (runCatching { privileged.shizuku.ensureReady() }.getOrDefault(false)) return
        if (!config.paired) return

        if (privileged.hasPersistentWirelessControl()) {
            runCatching { privileged.tryStartWirelessDebugging() }
        } else {
            // Background healing must honor ADB backoff and USER_ACTION_REQUIRED. Only an explicit
            // user reconnect operation is allowed to force rediscovery/pairing attention.
            runCatching { adb.autoHeal(force = false) }
        }
    }

    private fun heartbeatFingerprint(config: BridgeConfig): String = buildString {
        val status = privileged.status.value
        val adbState = status.adb
        val shizuku = status.shizuku
        append(BridgeCapabilityCatalog.SKILL_REVISION).append('|')
        append(BridgeCapabilityCatalog.CAPABILITY_SCHEMA).append('|')
        append(config.enabled).append('|')
        append(config.deviceId).append('|')
        append(config.repoOwner).append('/').append(config.repoName).append('|')
        append(config.paired).append('|')
        append(status.activeTransport.name).append('|')
        append(status.persistentWirelessControl).append('|')
        append(shizuku.binderAvailable).append('|')
        append(shizuku.permissionGranted).append('|')
        append(shizuku.serviceReady).append('|')
        append(shizuku.mode.name).append('|')
        append(shizuku.uid).append('|')
        append(adbState.connected).append('|')
        append(adbState.healPhase.name).append('|')
        append(adbState.consecutiveFailures).append('|')
        append(adbState.userActionRequired).append('|')
        append(adbState.wifiAvailable).append('|')
        append(config.trustedUntilEpochMs).append('|')
        append(config.allowInformational).append('|')
        append(config.allowPopups)
    }

    private fun showApproval(pending: BridgePendingRequest) {
        val openIntent = Intent(this, BridgeApprovalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val allowOnce = PendingIntent.getService(
            this,
            1,
            Intent(this, RemoteBridgeService::class.java).setAction(ACTION_APPROVE_ONCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deny = PendingIntent.getService(
            this,
            2,
            Intent(this, RemoteBridgeService::class.java).setAction(ACTION_DENY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, APPROVAL_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("ChatGPT requests ${pending.risk.name.lowercase().replace('_', ' ')} access")
            .setContentText(pending.request.reason.ifBlank { requestSummary(pending.request) }.take(240))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(pending.request.source)
                        append("\n\n")
                        append(pending.request.reason.ifBlank { requestSummary(pending.request) })
                        pending.request.runId.takeIf { it.isNotBlank() }?.let {
                            append("\n\nRun ID: ").append(it)
                        }
                        pending.request.buildId.takeIf { it.isNotBlank() }?.let {
                            append("\nBuild ID: ").append(it)
                        }
                        pending.request.command.takeIf { it.isNotBlank() }?.let {
                            append("\n\n")
                            append(it.take(2_000))
                        }
                        pending.request.selector.takeIf { it.isNotBlank() }?.let {
                            append("\n\nSelector: ")
                            append(it.take(500))
                        }
                    }
                )
            )
            .setContentIntent(openPending)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, "Deny", deny)
            .addAction(0, "Allow once", allowOnce)

        if (BridgePolicy.trustedSessionEligible(pending.request)) {
            val trust = PendingIntent.getService(
                this,
                3,
                Intent(this, RemoteBridgeService::class.java).setAction(ACTION_APPROVE_TRUST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Allow + trust 10 min", trust)
        }
        notificationManager.notify(APPROVAL_NOTIFICATION_ID, builder.build())
    }

    private fun statusLine(): String {
        val status = privileged.status.value
        val transportText = when {
            status.shizuku.usable && status.shizuku.root -> "Sui/root ready"
            status.shizuku.usable -> "Shizuku ready"
            status.adb.healPhase == AdbHealPhase.HEALTHY -> "Wireless ADB healthy"
            status.adb.healPhase == AdbHealPhase.VERIFYING -> "Wireless ADB verifying"
            status.adb.healPhase == AdbHealPhase.REDISCOVERING -> "Wireless ADB rediscovering"
            status.adb.healPhase == AdbHealPhase.WAITING_FOR_WIFI -> "No privileged transport · ADB waiting for Wi-Fi"
            status.adb.healPhase == AdbHealPhase.BACKOFF -> "No privileged transport · ADB retry backoff"
            status.adb.healPhase == AdbHealPhase.USER_ACTION_REQUIRED -> "No privileged transport · ADB needs attention"
            else -> "No privileged transport"
        }
        val runtime = BridgeRuntime.status.value
        val relayText = if (runtime.relayReachable) "Continuity online" else "Continuity retrying"
        val trust = prefs.state.value.trustedUntilEpochMs
        val trustText = if (trust > System.currentTimeMillis()) " · trusted session" else ""
        return "$transportText · $relayText$trustText"
    }

    private fun updateForeground(text: String) {
        notificationManager.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(text))
    }

    private fun buildServiceNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            9,
            Intent(this, BridgeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val poll = PendingIntent.getService(
            this,
            10,
            Intent(this, RemoteBridgeService::class.java).setAction(ACTION_POLL_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, RemoteBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("APKbox Remote Debug Bridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Poll now", poll)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL,
                "APKbox Remote Bridge",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent status while the ChatGPT remote debugging bridge is enabled"
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                APPROVAL_CHANNEL,
                "APKbox Bridge approvals",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Approval prompts for remote debugging commands"
                enableVibration(true)
            }
        )
    }

    private fun requestSummary(request: BridgeRequest): String = when (request.type) {
        BridgeCommandType.SHELL -> request.command.take(220)
        BridgeCommandType.LOGCAT -> "Capture system logcat"
        BridgeCommandType.APP_LOGCAT -> "Capture logcat for ${request.packageName}"
        BridgeCommandType.DUMPSYS -> "Capture dumpsys ${request.service}"
        BridgeCommandType.LAUNCH -> "Launch ${request.packageName}"
        BridgeCommandType.TOAST -> request.message.take(220)
        BridgeCommandType.NOTIFICATION -> request.message.take(220)
        BridgeCommandType.POPUP -> request.message.take(220)
        BridgeCommandType.UI_SNAPSHOT -> "Read current UI hierarchy"
        BridgeCommandType.SCREENSHOT -> "Capture current screen"
        BridgeCommandType.UI_TAP -> "Tap ${request.x},${request.y} in ${request.packageName}"
        BridgeCommandType.UI_FIND_TAP -> "Tap '${request.selector}' in ${request.packageName}"
        BridgeCommandType.UI_SWIPE -> "Swipe in ${request.packageName}"
        BridgeCommandType.UI_TEXT -> "Type text in ${request.packageName}"
        BridgeCommandType.UI_KEY -> "Send key ${request.keyCode} in ${request.packageName}"
        BridgeCommandType.UI_WAIT -> "Wait for '${request.selector}' in ${request.packageName}"
        BridgeCommandType.AGENT_START -> "Start autonomous run ${request.runId}"
        BridgeCommandType.AGENT_RESUME -> "Resume autonomous run ${request.runId}"
        BridgeCommandType.AGENT_STATUS -> "Read autonomous run ${request.runId} status"
        BridgeCommandType.BUILD_START -> "Start build ${request.buildId.ifBlank { request.runId }}"
        BridgeCommandType.BUILD_STATUS -> "Read build ${request.runId.ifBlank { request.buildId }} status"
    }

    private fun recordRelayFailure(failure: Throwable) {
        val detail = failure.message ?: failure.javaClass.simpleName
        BridgeRuntime.update { it.copy(relayReachable = false, lastError = detail) }
        stateStore.addEvent("Relay error", detail, success = false)
        updateForeground("Relay retrying · ${detail.take(80)}")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun stopBridge() {
        loopJob?.cancel()
        loopJob = null
        notificationManager.cancel(APPROVAL_NOTIFICATION_ID)
        BridgeRuntime.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
