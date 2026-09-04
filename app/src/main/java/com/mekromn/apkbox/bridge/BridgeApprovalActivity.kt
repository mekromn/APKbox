package com.mekromn.apkbox.bridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mekromn.apkbox.ApkBoxServices
import com.mekromn.apkbox.ui.theme.APKboxTheme
import java.text.DateFormat
import java.util.Date

class BridgeApprovalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = ApkBoxServices.bridgeStateStore(applicationContext)
        setContent {
            APKboxTheme {
                val pending = store.loadPending()
                if (pending == null) {
                    EmptyApprovalScreen { finish() }
                } else {
                    ApprovalScreen(
                        pending = pending,
                        onDeny = { respond(RemoteBridgeService.ACTION_DENY) },
                        onAllowOnce = { respond(RemoteBridgeService.ACTION_APPROVE_ONCE) },
                        onTrust = { respond(RemoteBridgeService.ACTION_APPROVE_TRUST) },
                    )
                }
            }
        }
    }

    private fun respond(action: String) {
        startService(Intent(this, RemoteBridgeService::class.java).setAction(action))
        finishAndRemoveTask()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalScreen(
    pending: BridgePendingRequest,
    onDeny: () -> Unit,
    onAllowOnce: () -> Unit,
    onTrust: () -> Unit,
) {
    val request = pending.request
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Remote debugging request", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (BridgePolicy.trustedSessionEligible(request)) {
                        OutlinedButton(
                            onClick = onTrust,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Allow + trust safe debugging for 10 min")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Deny") }
                        Button(onClick = onAllowOnce, modifier = Modifier.weight(1f)) {
                            Text(confirmLabel(request.type))
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
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Rounded.Security, null, Modifier.size(38.dp), tint = riskTint(pending.risk))
                    Column {
                        Text(
                            pending.risk.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleLarge,
                            color = riskTint(pending.risk),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(request.source, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            InfoSection("Why ChatGPT wants this", request.reason.ifBlank { "No reason was supplied." })
            InfoSection("Action", requestSummaryForApproval(request))
            if (request.type in setOf(
                    BridgeCommandType.AGENT_START,
                    BridgeCommandType.AGENT_RESUME,
                    BridgeCommandType.AGENT_STATUS,
                )
            ) {
                InfoSection("Run ID", request.runId.ifBlank { "Missing" })
                if (request.packageName.isNotBlank()) InfoSection("Target app", request.packageName)
            }
            if (request.type in setOf(BridgeCommandType.BUILD_START, BridgeCommandType.BUILD_STATUS)) {
                InfoSection("Build ID", request.buildId.ifBlank { "Not supplied" })
                InfoSection("Run ID", request.runId.ifBlank { "Not supplied" })
            }
            if (request.jobId.isNotBlank()) InfoSection("Job ID", request.jobId)
            if (request.apkRecordId.isNotBlank()) InfoSection("APKbox record ID", request.apkRecordId)
            if (request.type == BridgeCommandType.APK_INSTALL_URL) {
                InfoSection("APK source", request.downloadUrl)
                if (request.packageName.isNotBlank()) InfoSection("Expected package", request.packageName)
                InfoSection(
                    "Expected SHA-256",
                    request.expectedApkSha256.ifBlank { "Not supplied · APKbox will compute and report the downloaded APK SHA-256" },
                )
                InfoSection("Authenticated build source", if (request.requiresBuildToken) "Yes · use encrypted APKbox build-source token" else "No")
                InfoSection("Save to APKbox project", if (request.saveToProject) "Yes" else "No · temporary APK is deleted after verified install")
                if (request.saveToProject) {
                    InfoSection("Project", request.projectId.ifBlank { "Auto-resolve by package; create a project if none exists" })
                    if (request.projectName.isNotBlank()) InfoSection("New project name", request.projectName)
                    if (request.displayName.isNotBlank()) InfoSection("APK display/file name", request.displayName)
                    if (request.archiveTitle.isNotBlank()) InfoSection("APK title", request.archiveTitle)
                    if (request.archiveDescription.isNotBlank()) InfoSection("APK description", request.archiveDescription)
                }
                InfoSection("Downgrade", if (request.allowDowngrade) "Allowed" else "Not allowed")
                InfoSection("Launch after install", if (request.autoLaunch) "Yes" else "No")
            }
            if (request.imagePath.isNotBlank()) {
                InfoSection("Private Continuity image", request.imagePath)
            }

            if (request.command.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        request.command,
                        modifier = Modifier.padding(14.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()
            Text(
                "Expires ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(request.expiresAtEpochMs))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    request.type in setOf(BridgeCommandType.AGENT_START, BridgeCommandType.AGENT_RESUME) ->
                        "This approves one bounded autonomous execution for the named run. Plan start/resume never inherit an earlier trusted session."
                    request.type == BridgeCommandType.BUILD_START ->
                        "This may download, archive, install, downgrade, launch, and optionally test the exact build candidate described in Continuity. APKbox always requires a fresh on-device approval for BUILD_START."
                    request.type == BridgeCommandType.APK_INSTALL_URL ->
                        "This resolves the fastest exact local source first when an expected SHA is known, otherwise downloads the complete APK, verifies it, optionally archives it, unattended-installs it, and verifies installed base.apk SHA-256. It always requires fresh approval."
                    request.type == BridgeCommandType.JOB_RESUME ->
                        "Resuming a durable job may continue a package mutation, so it always requires fresh approval and uses the persisted original job payload."
                    pending.risk == BridgeRisk.READ_ONLY ->
                        "Read-only debugging can be covered by a temporary trusted session."
                    pending.risk == BridgeRisk.DEBUG_ACTION ->
                        "Package-scoped debug actions can be covered by a temporary trusted session when the action is eligible."
                    pending.risk == BridgeRisk.INFO ->
                        "This is informational only and does not receive shell privileges. Intrusive message surfaces still obey your local Informational messages / Instruction popups policy."
                    pending.risk == BridgeRisk.MUTATING ->
                        "This can change device or app state. APKbox never auto-approves it."
                    else ->
                        "This is arbitrary or high-risk shell access. APKbox never auto-approves it."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun confirmLabel(type: BridgeCommandType): String = when (type) {
    BridgeCommandType.AGENT_START -> "Start plan"
    BridgeCommandType.AGENT_RESUME -> "Resume plan"
    BridgeCommandType.BUILD_START -> "Start build"
    BridgeCommandType.APK_INSTALL_URL -> "Install APK"
    BridgeCommandType.JOB_CANCEL -> "Cancel job"
    BridgeCommandType.JOB_RESUME -> "Resume job"
    else -> "Allow once"
}

@Composable
private fun InfoSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EmptyApprovalScreen(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No remote request is waiting for approval.")
        Spacer(Modifier.size(14.dp))
        Button(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun riskTint(risk: BridgeRisk) = when (risk) {
    BridgeRisk.INFO -> MaterialTheme.colorScheme.primary
    BridgeRisk.READ_ONLY -> MaterialTheme.colorScheme.primary
    BridgeRisk.DEBUG_ACTION -> MaterialTheme.colorScheme.tertiary
    BridgeRisk.MUTATING -> MaterialTheme.colorScheme.error
    BridgeRisk.DANGEROUS -> MaterialTheme.colorScheme.error
}

private fun requestSummaryForApproval(request: BridgeRequest): String = when (request.type) {
    BridgeCommandType.SHELL -> "Run a shell command through APKbox's active privileged transport"
    BridgeCommandType.LOGCAT -> "Capture a system logcat snapshot"
    BridgeCommandType.APP_LOGCAT -> "Capture logcat for ${request.packageName}"
    BridgeCommandType.DUMPSYS -> "Capture dumpsys ${request.service}"
    BridgeCommandType.LAUNCH -> "Launch ${request.packageName}"
    BridgeCommandType.TOAST -> "Show a short Android toast message"
    BridgeCommandType.NOTIFICATION -> "Show a bridge message using the phone's legacy/default presentation"
    BridgeCommandType.POPUP -> "Show a bridge popup using the phone's legacy/default presentation"
    BridgeCommandType.MESSAGE_SMALL_POPUP -> "Show a compact auto-dismissing floating popup"
    BridgeCommandType.MESSAGE_ALWAYS_ON_TOP -> "Show a persistent always-on-top floating message"
    BridgeCommandType.MESSAGE_FULL_WINDOW -> "Open a full-window APKbox message"
    BridgeCommandType.MESSAGE_HEADS_UP -> "Show an expandable heads-up notification"
    BridgeCommandType.PICTURE_MESSAGE -> "Fetch and show a private Continuity image with title/caption"
    BridgeCommandType.APK_INSTALL_URL -> "Resolve/download and unattended-install an exact APK${if (request.saveToProject) ", saving it to an APKbox project" else ""}"
    BridgeCommandType.JOB_LIST -> "List APKbox durable jobs"
    BridgeCommandType.JOB_STATUS -> "Read durable job '${request.jobId}'"
    BridgeCommandType.JOB_CANCEL -> "Cancel durable job '${request.jobId}' at its next safe boundary"
    BridgeCommandType.JOB_RESUME -> "Resume durable job '${request.jobId}' from its persisted operation"
    BridgeCommandType.PROJECT_LIST -> "List APKbox projects"
    BridgeCommandType.PROJECT_GET -> "Read APKbox project '${request.projectId}' and its records"
    BridgeCommandType.APK_LIST -> "List stored APK records"
    BridgeCommandType.APK_SEARCH -> "Search stored APKs for '${request.query}'"
    BridgeCommandType.APK_INSPECT -> "Inspect exact APKbox record '${request.apkRecordId}' without pulling the full file"
    BridgeCommandType.APK_PULL -> "Pull exact APKbox record '${request.apkRecordId}' into verified private Continuity chunks"
    BridgeCommandType.PACKAGE_STATE -> "Inspect installed/stored state for ${request.packageName}"
    BridgeCommandType.INSTALLED_APPS -> "List installed Android apps"
    BridgeCommandType.DEVICE_STATE -> "Read structured APKbox/device/transport state"
    BridgeCommandType.UI_SNAPSHOT -> "Inspect the current UI hierarchy${request.packageName.takeIf { it.isNotBlank() }?.let { " for $it" }.orEmpty()}"
    BridgeCommandType.SCREENSHOT -> "Capture the current screen as a private Continuity artifact"
    BridgeCommandType.UI_TAP -> "Tap (${request.x}, ${request.y}) inside ${request.packageName}"
    BridgeCommandType.UI_FIND_TAP -> "Find and tap '${request.selector}' inside ${request.packageName}"
    BridgeCommandType.UI_SWIPE -> "Swipe inside ${request.packageName} from (${request.x}, ${request.y}) to (${request.endX}, ${request.endY})"
    BridgeCommandType.UI_TEXT -> "Type text into the foreground ${request.packageName} UI"
    BridgeCommandType.UI_KEY -> "Send Android key code ${request.keyCode} while ${request.packageName} is foreground"
    BridgeCommandType.UI_WAIT -> "Wait for '${request.selector}' in ${request.packageName}"
    BridgeCommandType.AGENT_START -> "Fetch, validate, and execute bounded autonomous plan '${request.runId}'"
    BridgeCommandType.AGENT_RESUME -> "Resume previously persisted autonomous run '${request.runId}'"
    BridgeCommandType.AGENT_STATUS -> "Read and republish autonomous checkpoint '${request.runId}'"
    BridgeCommandType.BUILD_START -> "Fetch, verify, archive, optionally install/launch, and optionally test build '${request.buildId.ifBlank { request.runId }}'"
    BridgeCommandType.BUILD_STATUS -> "Read and republish build checkpoint '${request.runId.ifBlank { request.buildId }}'"
}
