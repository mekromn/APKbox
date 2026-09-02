package com.mekromn.apkbox.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mekromn.apkbox.ApkBoxServices
import com.mekromn.apkbox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val ADB_RECONNECT_INTERVAL_MS = 15_000L

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
    private val relay by lazy { ApkBoxServices.relayClient() }
    private val executor by lazy { ApkBoxServices.bridgeExecutor(applicationContext) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private var loopJob: Job? = null
    @Volatile private var forcePoll = false
    private var lastHeartbeat = 0L
    private var lastAdbReconnect = 0L

    override fun onCreate() {
        super.onCreate()
        createChannels()
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

            maybeReconnectAdb(config)
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

            if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                runCatching { relay.heartbeat(config, token, adb.status.value) }
                    .onSuccess {
                        lastHeartbeat = now
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
            scope.launch { executor.launchApprovalUi() }
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
            relay.writeResult(config, token, completed.result)
            runCatching {
                relay.deleteInbox(
                    config = config,
                    token = token,
                    path = completed.inboxPath,
                    sha = completed.inboxSha,
                    requestId = completed.request.id,
                )
            }
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

    private suspend fun maybeReconnectAdb(config: BridgeConfig) {
        if (!config.paired || adb.status.value.connected) return
        val now = System.currentTimeMillis()
        if (now - lastAdbReconnect < ADB_RECONNECT_INTERVAL_MS) return
        lastAdbReconnect = now
        runCatching { adb.autoConnect() }
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
                        pending.request.command.takeIf { it.isNotBlank() }?.let {
                            append("\n\n")
                            append(it.take(2_000))
                        }
                    }
                )
            )
            .setContentIntent(openPending)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
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
        val adbText = if (adb.status.value.connected) "ADB connected" else "ADB waiting"
        val runtime = BridgeRuntime.status.value
        val relayText = if (runtime.relayReachable) "Continuity online" else "Continuity retrying"
        val trust = prefs.state.value.trustedUntilEpochMs
        val trustText = if (trust > System.currentTimeMillis()) " · trusted session" else ""
        return "$adbText · $relayText$trustText"
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
    }

    private fun recordRelayFailure(failure: Throwable) {
        val detail = failure.message ?: failure.javaClass.simpleName
        BridgeRuntime.update { it.copy(relayReachable = false, lastError = detail) }
        stateStore.addEvent("Relay error", detail, success = false)
        updateForeground("Relay retrying · ${detail.take(80)}")
    }

    private fun stopBridge() {
        loopJob?.cancel()
        loopJob = null
        notificationManager.cancel(APPROVAL_NOTIFICATION_ID)
        BridgeRuntime.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
