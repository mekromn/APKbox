package com.mekromn.apkbox.bridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mekromn.apkbox.ApkBoxServices
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class BridgeActivity : ComponentActivity() {
    private val prefs by lazy { ApkBoxServices.bridgePreferences(applicationContext) }
    private val adb by lazy { ApkBoxServices.adbBridge(applicationContext) }
    private val privileged by lazy { ApkBoxServices.privilegedBridge(applicationContext) }
    private val relay by lazy { ApkBoxServices.relayClient() }
    private val stateStore by lazy { ApkBoxServices.bridgeStateStore(applicationContext) }

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val consoleOutput = MutableStateFlow("")
    private var enableAfterNotificationGrant = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!enableAfterNotificationGrant) return@registerForActivityResult
        enableAfterNotificationGrant = false
        if (granted) {
            enableBridgeNow()
        } else {
            prefs.setEnabled(false)
            message.value = "Remote Bridge was not enabled. Approval notifications are required so remote commands can never wait invisibly for permission."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            APKboxTheme {
                val config = prefs.state.collectAsStateWithLifecycle().value
                val adbStatus = adb.status.collectAsStateWithLifecycle().value
                val privilegedStatus = privileged.status.collectAsStateWithLifecycle().value
                val runtime = BridgeRuntime.status.collectAsStateWithLifecycle().value
                val events = stateStore.events.collectAsStateWithLifecycle().value
                val isBusy = busy.collectAsStateWithLifecycle().value
                val currentMessage = message.collectAsStateWithLifecycle().value
                val output = consoleOutput.collectAsStateWithLifecycle().value

                BridgeScreen(
                    config = config,
                    adbStatus = adbStatus,
                    privilegedStatus = privilegedStatus,
                    runtime = runtime,
                    events = events,
                    busy = isBusy,
                    message = currentMessage,
                    consoleOutput = output,
                    onBack = { finish() },
                    onEnabled = ::setBridgeEnabled,
                    onGrantShizuku = ::grantShizuku,
                    onBootstrapWireless = ::bootstrapPersistentWireless,
                    onAutoPair = ::startAutoPair,
                    onPair = ::pairAdb,
                    onConnect = ::connectAdb,
                    onOpenWirelessDebugging = ::openWirelessDebugging,
                    onSaveRelay = ::saveRelay,
                    onTestRelay = ::testRelay,
                    onClearRelayToken = {
                        prefs.setRelayToken("")
                        message.value = "Relay token cleared."
                    },
                    onPollNow = { RemoteBridgeService.pollNow(this) },
                    onAllowInformational = prefs::setAllowInformational,
                    onAllowPopups = prefs::setAllowPopups,
                    onTrustMinutes = ::startTrustedSession,
                    onEndTrust = {
                        prefs.endTrustedSession()
                        message.value = "Trusted session ended. Mutating commands always require approval regardless."
                    },
                    onRunLocal = ::runLocalCommand,
                    onClearEvents = stateStore::clearEvents,
                )
            }
        }
    }

    private fun setBridgeEnabled(enabled: Boolean) {
        if (!enabled) {
            prefs.setEnabled(false)
            RemoteBridgeService.stop(this)
            message.value = "Remote Bridge disabled."
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.setEnabled(false)
            enableAfterNotificationGrant = true
            message.value = "Allow notifications so APKbox can always surface ChatGPT command approvals."
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        enableBridgeNow()
    }

    private fun enableBridgeNow() {
        prefs.setEnabled(true)
        RemoteBridgeService.start(this)
        message.value = "Remote Bridge enabled. Approval prompts will stay visible in notifications."
    }

    private fun grantShizuku() {
        val status = privileged.shizuku.status.value
        message.value = when {
            status.usable -> "${privileged.activeTransportLabel()} is already ready."
            !status.binderAvailable -> "Start Shizuku, or enable Sui on a rooted device, then return to APKbox."
            privileged.requestShizukuPermission() -> "Approve APKbox in the Shizuku permission prompt. Once granted, Shizuku/Sui becomes the preferred privileged transport automatically."
            else -> privileged.shizuku.status.value.lastError.ifBlank { "APKbox could not request Shizuku access." }
        }
    }

    private fun bootstrapPersistentWireless() {
        runBusy {
            if (privileged.bootstrapPersistentWirelessControl()) {
                "Persistent Wireless Debugging self-start enabled. APKbox can now toggle Android's Wireless Debugging setting locally and reconnect its existing paired identity when Wi-Fi/trust policy allows."
            } else {
                "APKbox could not grant persistent Wireless Debugging control. Start/authorize Shizuku/Sui or connect Wireless ADB first, then try again."
            }
        }
    }

    private fun startAutoPair() {
        val alreadyEnabled = PairingAssistantService.request(this)
        message.value = if (alreadyEnabled) {
            "Pairing Assistant started. APKbox will navigate Developer options, read the temporary pairing code locally, discover the pairing port, pair, and return here."
        } else {
            "Enable APKbox Pairing Assistant in Accessibility. As soon as the service starts, APKbox will continue the Wireless ADB pairing flow automatically and disable the accessibility service after success."
        }
    }

    private fun pairAdb(portText: String, code: String) {
        val port = portText.toIntOrNull()
        if (port == null) {
            message.value = "Enter the pairing port shown by Android Wireless debugging."
            return
        }
        runBusy {
            val paired = adb.pair(port, code.trim())
            if (paired) {
                prefs.setPaired(true)
                val connected = adb.autoConnect()
                if (connected) {
                    runCatching { privileged.bootstrapPersistentWirelessControl() }
                    "Paired and connected to this phone's Wireless ADB. APKbox also attempted to enable persistent Wireless Debugging self-start for future reconnects."
                } else {
                    "Pairing succeeded. Tap Auto connect after returning to the main Wireless debugging screen."
                }
            } else {
                "Pairing was not accepted. Check the six-digit code and pairing port."
            }
        }
    }

    private fun connectAdb() {
        runBusy {
            if (adb.autoConnect()) {
                runCatching { privileged.bootstrapPersistentWirelessControl() }
                "Wireless ADB connected."
            } else {
                "Wireless ADB was not discovered."
            }
        }
    }

    private fun saveRelay(owner: String, repo: String, token: String, pollSeconds: Int) {
        prefs.setRepo(owner, repo)
        prefs.setPollSeconds(pollSeconds)
        if (token.isNotBlank()) prefs.setRelayToken(token)
        message.value = if (token.isBlank() && prefs.state.value.hasRelayToken) {
            "Relay settings saved. Existing encrypted token kept."
        } else {
            "Relay settings saved."
        }
    }

    private fun testRelay(owner: String, repo: String, tokenInput: String, pollSeconds: Int) {
        saveRelay(owner, repo, tokenInput, pollSeconds)
        runBusy {
            val token = prefs.relayToken()
            val result = relay.test(prefs.state.value, token)
            relay.heartbeat(prefs.state.value, token, adb.status.value)
            "$result · device registered as ${prefs.state.value.deviceId} · ${privileged.activeTransportLabel()}"
        }
    }

    private fun runLocalCommand(command: String) {
        if (command.isBlank()) return
        busy.value = true
        consoleOutput.value = "Running…"
        lifecycleScope.launch {
            try {
                val result = privileged.execute(command, 30)
                consoleOutput.value = buildString {
                    append(result.output)
                    append("\n\n[exit=")
                    append(result.exitCode ?: "?")
                    append(" · ")
                    append(result.durationMs)
                    append(" ms · ")
                    append(privileged.activeTransportLabel())
                    if (result.truncated) append(" · output truncated")
                    append(']')
                }
            } catch (failure: Throwable) {
                consoleOutput.value = failure.message ?: failure.javaClass.simpleName
            } finally {
                busy.value = false
            }
        }
    }

    private fun startTrustedSession(minutes: Int) {
        val until = System.currentTimeMillis() + minutes.coerceIn(1, 120) * 60_000L
        prefs.setTrustedUntil(until)
        message.value = "Safe read/debug actions trusted for $minutes minutes. Mutating and arbitrary dangerous shell commands still require approval."
    }

    private fun openWirelessDebugging() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { message.value = "Android could not open Developer options." }
    }

    private fun runBusy(block: suspend () -> String) {
        if (busy.value) return
        busy.value = true
        lifecycleScope.launch {
            try {
                message.value = withContext(Dispatchers.IO) { block() }
            } catch (failure: Throwable) {
                message.value = failure.message ?: failure.javaClass.simpleName
            } finally {
                busy.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeScreen(
    config: BridgeConfig,
    adbStatus: AdbBridgeStatus,
    privilegedStatus: PrivilegedBridgeStatus,
    runtime: BridgeRuntimeStatus,
    events: List<BridgeEvent>,
    busy: Boolean,
    message: String,
    consoleOutput: String,
    onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onGrantShizuku: () -> Unit,
    onBootstrapWireless: () -> Unit,
    onAutoPair: () -> Unit,
    onPair: (String, String) -> Unit,
    onConnect: () -> Unit,
    onOpenWirelessDebugging: () -> Unit,
    onSaveRelay: (String, String, String, Int) -> Unit,
    onTestRelay: (String, String, String, Int) -> Unit,
    onClearRelayToken: () -> Unit,
    onPollNow: () -> Unit,
    onAllowInformational: (Boolean) -> Unit,
    onAllowPopups: (Boolean) -> Unit,
    onTrustMinutes: (Int) -> Unit,
    onEndTrust: () -> Unit,
    onRunLocal: (String) -> Unit,
    onClearEvents: () -> Unit,
) {
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var owner by remember(config.repoOwner) { mutableStateOf(config.repoOwner) }
    var repo by remember(config.repoName) { mutableStateOf(config.repoName) }
    var token by remember { mutableStateOf("") }
    var pollSeconds by remember(config.pollSeconds) { mutableStateOf(config.pollSeconds.toString()) }
    var localCommand by remember { mutableStateOf("id") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Remote Debug Bridge", fontWeight = FontWeight.Bold)
                            Text(
                                "ChatGPT ↔ Continuity ↔ APKbox ↔ Shizuku/Sui or Wireless ADB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        TextButton(onClick = onBack) { Text("Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusHero(config, privilegedStatus, runtime, onEnabled, onPollNow)

            if (message.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }

            BridgeCard(Icons.Rounded.Bolt, "1 · Privileged transport") {
                Text(
                    "APKbox treats Shizuku/Sui and Wireless ADB as peer backends. When Shizuku or Sui is already authorized it is preferred automatically, so Screen Agent, Build Runner, unattended installs, evidence collection, recovery, and this console do not require Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Active: ${privilegedStatus.activeLabel}",
                    fontWeight = FontWeight.SemiBold,
                    color = if (privilegedStatus.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                val shizuku = privilegedStatus.shizuku
                Text(
                    when {
                        shizuku.usable && shizuku.root -> "Sui/root UserService ready · UID ${shizuku.uid}"
                        shizuku.usable -> "Shizuku UserService ready · shell UID ${shizuku.uid}"
                        shizuku.binderAvailable && !shizuku.permissionGranted -> "Shizuku is running · APKbox permission required"
                        else -> "Shizuku/Sui not currently available"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!shizuku.usable) {
                    OutlinedButton(onClick = onGrantShizuku, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (shizuku.binderAvailable) "Grant Shizuku access" else "Check Shizuku / Sui")
                    }
                }
                HorizontalDivider()
                Text(
                    if (privilegedStatus.persistentWirelessControl) {
                        "Persistent Wireless Debugging self-start: enabled"
                    } else {
                        "Persistent Wireless Debugging self-start: not bootstrapped"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Once bootstrapped through an authorized Shizuku/Sui or ADB session, APKbox can toggle Android's Wireless Debugging setting locally and reconnect its existing pairing without requiring that privileged transport to already be alive. Android's Wi-Fi/trusted-network rules still apply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!privilegedStatus.persistentWirelessControl) {
                    OutlinedButton(onClick = onBootstrapWireless, enabled = !busy && privilegedStatus.ready, modifier = Modifier.fillMaxWidth()) {
                        Text("Enable persistent Wireless Debugging self-start")
                    }
                }
            }

            BridgeCard(Icons.Rounded.Link, "2 · Pair this phone's Wireless ADB") {
                Text(
                    "Wireless ADB is the peer fallback when Shizuku/Sui is unavailable. APKbox can bootstrap pairing itself: Auto-open & pair navigates Pixel Developer options, opens the pairing-code dialog, reads the temporary six-digit code only from Android Settings, discovers the pairing port through Android's local ADB mDNS advertisement, pairs, and then disables its one-shot Accessibility helper.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    enabled = !busy,
                    onClick = onAutoPair,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.DeveloperMode, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Auto-open & pair")
                }
                Text(
                    "First use requires enabling APKbox Pairing Assistant in Android Accessibility settings. That permission is used only for com.android.settings and is automatically dropped after pairing succeeds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("Manual fallback", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onOpenWirelessDebugging, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.DeveloperMode, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Open Developer options")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pairingPort,
                        onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Pairing port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.weight(1f),
                        label = { Text("6-digit code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && pairingCode.length == 6 && pairingPort.isNotBlank(),
                        onClick = { onPair(pairingPort, pairingCode) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Pair") }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                    ) { Text("Auto connect") }
                }
                Text(
                    "ADB status: ${if (adbStatus.connected) "connected" else adbStatus.healPhase.name.lowercase().replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BridgeCard(Icons.Rounded.Cloud, "3 · Private Continuity relay") {
                DeviceIdRow(config.deviceId)
                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitHub owner") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Private relay repo") },
                    supportingText = { Text("Default: mekromn/Continuity") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim().take(512) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (config.hasRelayToken) "Fine-grained token (saved securely)" else "Fine-grained GitHub token") },
                    placeholder = { if (config.hasRelayToken) Text("Leave blank to keep saved token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Text(
                    "Use a fine-grained token limited to the private Continuity repository with Contents read/write. APKbox encrypts it with Android Keystore and never writes it into the relay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pollSeconds,
                    onValueChange = { pollSeconds = it.filter(Char::isDigit).take(3) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Poll interval · 5–300 seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSaveRelay(owner, repo, token, pollSeconds.toIntOrNull() ?: 10) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                    Button(
                        enabled = !busy && (token.isNotBlank() || config.hasRelayToken),
                        onClick = { onTestRelay(owner, repo, token, pollSeconds.toIntOrNull() ?: 10) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Test + register") }
                }
                if (config.hasRelayToken) {
                    TextButton(onClick = onClearRelayToken) { Text("Clear saved relay token") }
                }
            }

            BridgeCard(Icons.Rounded.Security, "4 · Approval policy") {
                ToggleRow(
                    "Informational messages",
                    "Allow ChatGPT notifications and toasts without shell approval.",
                    config.allowInformational,
                    onAllowInformational,
                )
                ToggleRow(
                    "Instruction popups",
                    "Allow informational ChatGPT requests to bring an APKbox instruction popup to the front when a privileged transport is ready.",
                    config.allowPopups,
                    onAllowPopups,
                )
                HorizontalDivider()
                val trusted = config.trustedUntilEpochMs > System.currentTimeMillis()
                Text(
                    if (trusted) {
                        "Trusted safe-debug session until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(config.trustedUntilEpochMs))}"
                    } else {
                        "No trusted debug session"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = if (trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "A trusted session auto-approves structured logcats/dumpsys/app launches and the strict read-only shell allowlist. Mutating, composed, unknown, and arbitrary shell commands still require a fresh approval every time regardless of whether Shizuku/Sui or ADB executes them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 30, 60).forEach { minutes ->
                        OutlinedButton(onClick = { onTrustMinutes(minutes) }, modifier = Modifier.weight(1f)) {
                            Text("${minutes}m")
                        }
                    }
                }
                if (trusted) {
                    TextButton(onClick = onEndTrust) { Text("End trusted session now") }
                }
            }

            BridgeCard(Icons.Rounded.Terminal, "Local privileged console") {
                Text(
                    "Commands you run here are explicitly initiated on the phone and do not need remote approval. APKbox selects Shizuku/Sui first when ready, otherwise Wireless ADB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "id" to "id",
                        "APKbox logs" to "logcat -d -v threadtime -t 1000 | grep -i apkbox",
                        "Device" to "getprop ro.product.model",
                    ).forEach { (label, command) ->
                        OutlinedButton(onClick = { localCommand = command }) { Text(label) }
                    }
                }
                OutlinedTextField(
                    value = localCommand,
                    onValueChange = { localCommand = it.take(16_384) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Privileged shell command") },
                    minLines = 2,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Button(
                    enabled = !busy && localCommand.isNotBlank(),
                    onClick = { onRunLocal(localCommand) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Run through ${privilegedStatus.activeLabel}")
                }
                if (consoleOutput.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            consoleOutput,
                            modifier = Modifier.padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            BridgeCard(Icons.Rounded.Memory, "Recent bridge activity") {
                if (events.isEmpty()) {
                    Text("No remote bridge activity yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    events.take(20).forEachIndexed { index, event ->
                        if (index > 0) HorizontalDivider()
                        Column(Modifier.padding(vertical = 5.dp)) {
                            Text(
                                event.title,
                                fontWeight = FontWeight.SemiBold,
                                color = if (event.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                event.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = onClearEvents) { Text("Clear activity history") }
                }
            }
        }
    }
}

@Composable
private fun StatusHero(
    config: BridgeConfig,
    privilegedStatus: PrivilegedBridgeStatus,
    runtime: BridgeRuntimeStatus,
    onEnabled: (Boolean) -> Unit,
    onPollNow: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("ChatGPT Remote Debug Bridge", fontWeight = FontWeight.Bold)
                    Text(
                        if (config.enabled) "Foreground relay active · ${privilegedStatus.activeLabel}" else "Off until you enable it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = config.enabled, onCheckedChange = onEnabled)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip("Shizuku", privilegedStatus.shizuku.usable)
                StatusChip("ADB", privilegedStatus.adb.connected)
                StatusChip("Continuity", runtime.relayReachable)
                StatusChip("Trusted", config.trustedUntilEpochMs > System.currentTimeMillis())
                if (runtime.pendingRequestId.isNotBlank()) StatusChip("Approval", true)
            }
            if (runtime.lastError.isNotBlank()) {
                Text(runtime.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onPollNow, enabled = config.enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.size(8.dp))
                Text("Poll Continuity now")
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BridgeCard(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(9.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DeviceIdRow(deviceId: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Relay device ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(deviceId, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("APKbox bridge device ID", deviceId))
            }
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy device ID")
        }
    }
}
