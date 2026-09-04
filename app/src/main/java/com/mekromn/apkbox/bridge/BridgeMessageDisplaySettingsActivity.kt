package com.mekromn.apkbox.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mekromn.apkbox.ApkBoxServices
import com.mekromn.apkbox.ui.theme.APKboxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class BridgeMessageDisplaySettingsActivity : ComponentActivity() {
    private val prefs by lazy { ApkBoxServices.bridgePreferences(applicationContext) }
    private val executor by lazy { ApkBoxServices.bridgeExecutor(applicationContext) }
    private val overlayGranted = MutableStateFlow(false)
    private val message = MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshOverlayPermission()
        setContent {
            APKboxTheme {
                val config by prefs.state.collectAsState()
                val canOverlay by overlayGranted.collectAsState()
                val status by message.collectAsState()
                BridgeMessageDisplaySettingsScreen(
                    config = config,
                    overlayGranted = canOverlay,
                    status = status,
                    onBack = { finish() },
                    onApprovalMode = prefs::setApprovalPresentation,
                    onLegacyMode = prefs::setMessagePresentation,
                    onKeepNotificationCopy = prefs::setKeepNotificationCopy,
                    onGrantOverlay = ::openOverlayPermission,
                    onPreview = ::previewCurrentMode,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermission()
    }

    private fun refreshOverlayPermission() {
        overlayGranted.value = Settings.canDrawOverlays(this)
    }

    private fun openOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            message.value = "Android could not open the Draw over other apps permission screen."
        }
    }

    private fun previewCurrentMode() {
        lifecycleScope.launch {
            val result = executor.execute(
                BridgeRequest(
                    id = "local-display-preview-${System.currentTimeMillis()}",
                    type = BridgeCommandType.NOTIFICATION,
                    title = "APKbox bridge preview",
                    message = "This is how legacy ChatGPT NOTIFICATION/POPUP messages will appear with your current default setting.",
                    reason = "Local bridge message presentation preview",
                    source = "APKbox local settings",
                ),
                BridgeRisk.INFO,
            )
            message.value = result.detail
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeMessageDisplaySettingsScreen(
    config: BridgeConfig,
    overlayGranted: Boolean,
    status: String,
    onBack: () -> Unit,
    onApprovalMode: (BridgeApprovalPresentation) -> Unit,
    onLegacyMode: (BridgeMessagePresentation) -> Unit,
    onKeepNotificationCopy: (Boolean) -> Unit,
    onGrantOverlay: () -> Unit,
    onPreview: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bridge presentation", fontWeight = FontWeight.Bold)
                        Text(
                            "Security approvals and agent-selected messages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Button(
                    onClick = onPreview,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("Preview legacy message default")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Security approval prompts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "This controls the prompts that ask you to Deny, Allow once, or Allow + trust for ChatGPT bridge actions. The always-on-top option replaces the separate approval notification whenever Android's overlay permission is available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BridgeApprovalPresentation.entries.forEach { mode ->
                ChoiceCard(
                    title = mode.displayName,
                    description = mode.description,
                    selected = config.approvalPresentation == mode,
                    onClick = { onApprovalMode(mode) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text("Overlay permission", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (overlayGranted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (overlayGranted) "Draw over other apps: ready" else "Draw over other apps: permission required",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (overlayGranted) {
                            "APKbox can show security approvals, compact floating messages, and persistent always-on-top messages above the current app."
                        } else {
                            "Until this one-time Android permission is granted, security popup mode and agent overlay messages automatically fall back to notifications so nothing becomes invisible."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!overlayGranted) {
                        OutlinedButton(onClick = onGrantOverlay, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow draw over other apps")
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text("Agent-selected message toolkit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "When Informational messages are allowed, agents can choose the least intrusive useful structured presentation: TOAST, MESSAGE_HEADS_UP, MESSAGE_SMALL_POPUP, MESSAGE_ALWAYS_ON_TOP, MESSAGE_FULL_WINDOW, or PICTURE_MESSAGE. Intrusive popup/window/picture formats also honor the separate Instruction popups permission. Picture images are fetched only from this device's private Continuity artifacts/message-assets paths.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Agent can choose", fontWeight = FontWeight.SemiBold)
                    Text("• Toast · tiny transient acknowledgement", style = MaterialTheme.typography.bodySmall)
                    Text("• Heads-up · expandable notification banner", style = MaterialTheme.typography.bodySmall)
                    Text("• Small popup · compact auto-dismiss floating card", style = MaterialTheme.typography.bodySmall)
                    Text("• Always on top · persistent floating card", style = MaterialTheme.typography.bodySmall)
                    Text("• Full window · detailed instruction/message screen", style = MaterialTheme.typography.bodySmall)
                    Text("• Picture · private image + caption in full-window viewer", style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Text("Legacy NOTIFICATION / POPUP default", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Older agents/scripts that still send generic NOTIFICATION or POPUP use this local default. New agents should prefer the explicit presentation verbs above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BridgeMessagePresentation.entries.forEach { mode ->
                ChoiceCard(
                    title = mode.displayName,
                    description = mode.description,
                    selected = config.messagePresentation == mode,
                    onClick = { onLegacyMode(mode) },
                )
            }

            if (config.messagePresentation in setOf(
                    BridgeMessagePresentation.POPUP_ACTIVITY,
                    BridgeMessagePresentation.ALWAYS_ON_TOP,
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Keep notification copy", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Also leave legacy popup messages in the notification shade.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.keepNotificationCopy,
                        onCheckedChange = onKeepNotificationCopy,
                    )
                }
            }

            if (status.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(status, Modifier.padding(14.dp))
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
