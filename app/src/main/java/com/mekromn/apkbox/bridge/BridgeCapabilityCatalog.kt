package com.mekromn.apkbox.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Live bridge contract published in every Continuity state.json heartbeat.
 *
 * Agents must prefer this catalog over remembered capabilities. It intentionally separates
 * remotely invokable commands from device-local features so a future agent never invents a remote
 * verb merely because APKbox has a local UI for the same capability.
 */
object BridgeCapabilityCatalog {
    const val PROTOCOL_VERSION = 5
    const val CAPABILITY_SCHEMA = 4
    const val SKILL_REVISION = "2026-09-04.2"
    const val SKILL_REPOSITORY = "mekromn/Continuity"
    const val SKILL_PATH = "skills/apkbox-remote-bridge/SKILL.md"
    const val PROTOCOL_PATH = "bridge/README.md"

    fun enrich(
        state: JSONObject,
        config: BridgeConfig,
        privileged: PrivilegedBridgeStatus,
    ): JSONObject {
        val shizuku = privileged.shizuku
        val adb = privileged.adb

        return state
            .put("bridgeProtocolVersion", PROTOCOL_VERSION)
            .put("capabilitySchema", CAPABILITY_SCHEMA)
            .put("skillRevision", SKILL_REVISION)
            .put("operatorSkill", JSONObject()
                .put("repository", SKILL_REPOSITORY)
                .put("path", SKILL_PATH)
                .put("protocolPath", PROTOCOL_PATH)
                .put("rule", "Read the live state first, then read this skill before issuing bridge requests. If the skill revision differs from live state, fetch the authoritative Continuity skill before acting."))
            .put("privilegedTransport", JSONObject()
                .put("ready", privileged.ready)
                .put("active", privileged.activeLabel)
                .put("activeKind", privileged.activeTransport.name)
                .put("persistentWirelessControl", privileged.persistentWirelessControl)
                .put("shizukuBinderAvailable", shizuku.binderAvailable)
                .put("shizukuPermissionGranted", shizuku.permissionGranted)
                .put("shizukuServiceReady", shizuku.serviceReady)
                .put("shizukuMode", shizuku.mode.name)
                .put("shizukuUid", shizuku.uid)
                .put("wirelessAdbPaired", config.paired)
                .put("wirelessAdbConnected", adb.connected)
                .put("wirelessAdbHealPhase", adb.healPhase.name)
                .put("wirelessAdbWifiAvailable", adb.wifiAvailable)
                .put("wirelessAdbUserActionRequired", adb.userActionRequired))
            .put("remoteCommandTypes", JSONArray().apply {
                BridgeCommandType.values().forEach { put(it.name) }
            })
            .put("messagePresentation", JSONObject()
                .put("securityApprovalStyle", config.approvalPresentation.name)
                .put("legacyDefault", config.messagePresentation.name)
                .put("agentStructuredChoices", JSONArray(listOf(
                    "TOAST",
                    "MESSAGE_HEADS_UP",
                    "MESSAGE_SMALL_POPUP",
                    "MESSAGE_ALWAYS_ON_TOP",
                    "MESSAGE_FULL_WINDOW",
                    "PICTURE_MESSAGE"
                )))
                .put("guidance", JSONObject()
                    .put("TOAST", "Tiny acknowledgement or transient success/failure confirmation.")
                    .put("MESSAGE_HEADS_UP", "Non-blocking status/update that benefits from expandable detail.")
                    .put("MESSAGE_SMALL_POPUP", "Short actionable prompt that should be noticed without taking over the screen.")
                    .put("MESSAGE_ALWAYS_ON_TOP", "Must-see instruction that should remain visible over the current app until dismissed.")
                    .put("MESSAGE_FULL_WINDOW", "Detailed multi-step instruction or important message that deserves the whole screen.")
                    .put("PICTURE_MESSAGE", "Use when an image materially improves understanding; imagePath must be in this device's private Continuity artifacts/message-assets subtree."))
                .put("intrusiveChoicesRequireAllowPopups", true)
                .put("overlayPermissionRequiredForFloatingChoices", true)
                .put("pictureMaxBytes", 8 * 1024 * 1024))
            .put("remoteApkInstall", JSONObject()
                .put("command", "APK_INSTALL_URL")
                .put("requestSchema", 5)
                .put("required", JSONArray(listOf("id", "type", "downloadUrl", "reason")))
                .put("optional", JSONArray(listOf(
                    "expectedApkSha256", "packageName", "saveToProject", "projectId", "projectName",
                    "displayName", "archiveTitle", "archiveDescription", "requiresBuildToken",
                    "allowDowngrade", "autoLaunch"
                )))
                .put("httpsOnly", true)
                .put("expectedShaRecommendedWhenKnown", true)
                .put("downloadsCompleteBeforeInstall", true)
                .put("computesAndReportsSha256", true)
                .put("verifiesInstalledBaseApkSha256", true)
                .put("temporaryApkDeletedAfterTransaction", true)
                .put("saveToProjectOptional", true)
                .put("projectResolution", "Explicit projectId wins. Otherwise match package: create a project for zero matches, use the single match, refuse ambiguity for multiple matches.")
                .put("archiveMetadata", JSONArray(listOf("displayName", "archiveTitle", "archiveDescription")))
                .put("exactDuplicateReused", true)
                .put("metadataMayUpdateExistingExactRecord", true)
                .put("freshApprovalAlways", true)
                .put("signatureConflictAutoUninstall", false)
                .put("authenticatedSource", "Optional encrypted APKbox build-source token; credentials are sent only to GitHub credential hosts and never forwarded to CDN redirects."))
            .put("uiSelectors", JSONObject()
                .put("formats", JSONArray(listOf(
                    "id:<resource-id>",
                    "text:<exact text>",
                    "desc:<exact content-description>",
                    "contains:<substring>",
                    "<bare exact text/id/description>"
                )))
                .put("rule", "Prefer semantic selectors. Coordinate actions are package-foreground guarded."))
            .put("advancedWorkflows", JSONObject()
                .put("autonomousPlan", JSONObject()
                    .put("commands", JSONArray(listOf("AGENT_START", "AGENT_RESUME", "AGENT_STATUS")))
                    .put("planPath", "bridge/devices/<device-id>/plans/<runId>.json")
                    .put("checkpointPath", "bridge/devices/<device-id>/runs/<runId>/checkpoint.json")
                    .put("actions", JSONArray(listOf(
                        "LAUNCH", "TAP", "FIND_TAP", "SWIPE", "TEXT", "KEY", "WAIT",
                        "SNAPSHOT", "SCREENSHOT", "SLEEP", "CHECKPOINT"
                    )))
                    .put("freshApprovalForStartOrResume", true)
                    .put("interruptionRecoveryCommand", "AGENT_STATUS"))
                .put("buildRunner", JSONObject()
                    .put("commands", JSONArray(listOf("BUILD_START", "BUILD_STATUS")))
                    .put("candidatePath", "bridge/devices/<device-id>/builds/<buildId>.json")
                    .put("checkpointPath", "bridge/devices/<device-id>/build-runs/<runId>/checkpoint.json")
                    .put("canChainPlanRunIdUnderBuildApproval", false)
                    .put("planRunIdRequiresSeparateAgentStart", true)
                    .put("freshApprovalForStart", true)
                    .put("interruptionRecoveryCommand", "BUILD_STATUS")))
            .put("deviceCapabilities", JSONArray(listOf(
                "privileged_shell_shizuku_sui_or_wireless_adb",
                "wireless_adb_auto_heal",
                "wireless_adb_persistent_self_start",
                "wireless_adb_pairing_assistant",
                "ui_automation_package_guarded",
                "relay_screenshot_preview",
                "agent_selected_rich_message_presentations",
                "always_on_top_security_approval_overlay",
                "private_picture_messages",
                "remote_apk_url_download_verify_unattended_install",
                "optional_remote_apk_project_archive_with_title_description",
                "autonomous_plan_runner",
                "oracle_watchdog_and_evidence",
                "build_download_verify_archive_install_launch",
                "unattended_verified_apk_install",
                "installed_base_apk_sha256_verification",
                "durable_bridge_inflight_reservations",
                "local_signature_conflict_reinstall"
            )))
            .put("limits", JSONObject()
                .put("requestTimeoutSecondsMax", 120)
                .put("planStepsMin", 1)
                .put("planStepsMax", 500)
                .put("planRuntimeSecondsMin", 10)
                .put("planRuntimeSecondsMax", 7200)
                .put("planRetriesPerStepMax", 10)
                .put("uiCoordinateMax", 20000)
                .put("uiTextCharsMax", 2000)
                .put("requestIdCharsMax", 96)
                .put("pictureMessageBytesMax", 8 * 1024 * 1024)
                .put("remoteApkBytesMax", 2L * 1024L * 1024L * 1024L)
                .put("remoteApkRedirectsMax", 8)
                .put("archiveTitleCharsMax", 256)
                .put("archiveDescriptionCharsMax", 8192))
            .put("securityContract", JSONObject()
                .put("deviceComputesRisk", true)
                .put("relayCannotLowerRisk", true)
                .put("approvalPresentationIsLocalOnly", true)
                .put("popupOnlyApprovalFallsBackToNotificationIfOverlayUnavailable", true)
                .put("mutatingAlwaysNeedsFreshApproval", true)
                .put("dangerousAlwaysNeedsFreshApproval", true)
                .put("remoteApkInstallAlwaysNeedsFreshApproval", true)
                .put("remoteApkInstallNeverAutoUninstallsSignatureConflict", true)
                .put("advancedStartResumeNeedsFreshApproval", true)
                .put("trustedSessionNeverGrantsBlanketShell", true)
                .put("durableInFlightReservationBeforeExecution", true)
                .put("finalResultJournalBeforeRelayDelivery", true)
                .put("ambiguousInterruptedRequestsAreNeverReplayed", true)
                .put("advancedInterruptionRecoveryUsesStatusCommands", true)
                .put("atMostOnceJournal", true))
            .put("knownLimitations", JSONArray(listOf(
                "Relay SCREENSHOT is currently a scaled JPEG preview, not an exact forensic capture.",
                "APK_INSTALL_URL has no dedicated status/cancel/resume verb yet; interruption is closed without blind replay and requires state inspection before a new request ID.",
                "APK_INSTALL_URL currently installs a single monolithic APK, not split APK/APKS/XAPK packages.",
                "Direct URL install refuses an installed signature conflict; the local signature-conflict uninstall/reinstall workflow is not yet a remote bridge command.",
                "Always-on-top security/message overlays require the user's Android Draw over other apps grant; notification fallback is used when unavailable.",
                "Wireless ADB still requires Android Wi-Fi/trusted-network policy when Shizuku/Sui is unavailable."
            )))
    }
}
