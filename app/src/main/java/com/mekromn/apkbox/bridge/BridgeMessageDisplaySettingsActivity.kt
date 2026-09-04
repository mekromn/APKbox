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
                    onMode = prefs::setMessagePresentation,
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
        overlayGranted.value = BridgeOverlayController.canDraw(this)
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
                    message = "This is how ChatGPT bridge messages will appear with your current display setting.",
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
    onMode: (BridgeMessagePresentation) -> Unit,
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
                        Text("Bridge message display", fontWeight = FontWeight.Bold)
                        Text(
                            "Choose how ChatGPT gets your attention",
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
                    Text("Preview current mode")
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
            Text(
                "ChatGPT NOTIFICATION and POPUP requests use this local presentation preference. TOAST requests remain short Android toasts. Command-approval prompts always keep their independent persistent high-priority notification so an overlay can never become the only approval path.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BridgeMessagePresentation.entries.forEach { mode ->
                PresentationChoice(
                    mode = mode,
                    selected = config.messagePresentation == mode,
                    onClick = { onMode(mode) },
                )
            }

            if (config.messagePresentation in setOf(
                    BridgeMessagePresentation.POPUP_ACTIVITY,
                    BridgeMessagePresentation.ALWAYS_ON_TOP,
                )
            ) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Keep notification copy", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Also leave the message in the notification shade after showing the popup.",
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

            if (config.messagePresentation == BridgeMessagePresentation.ALWAYS_ON_TOP) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (overlayGranted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (overlayGranted) "Always-on-top permission ready" else "Draw over other apps permission required",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (overlayGranted) {
                                "Bridge messages can now appear above whatever app is currently open and remain there until dismissed."
                            } else {
                                "Android requires a one-time special permission for true always-on-top windows. Until granted, APKbox automatically falls back to a heads-up notification so messages are never silently lost."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!overlayGranted) {
                            OutlinedButton(
                                onClick = onGrantOverlay,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Allow draw over other apps")
                            }
                        }
                    }
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
private fun PresentationChoice(
    mode: BridgeMessagePresentation,
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
                Text(mode.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
