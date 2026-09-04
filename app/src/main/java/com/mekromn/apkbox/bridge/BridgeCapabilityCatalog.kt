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
    const val PROTOCOL_VERSION = 6
    const val CAPABILITY_SCHEMA = 5
    const val SKILL_REVISION = "2026-09-04.3"
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
            .put("requestSchema", 7)
            .put("resultSchema", 7)
            .put("skillRevision", SKILL_REVISION)
            .put("operatorSkill", JSONObject()
                .put("repository", SKILL_REPOSITORY)
                .put("path", SKILL_PATH)
                .put("protocolPath", PROTOCOL_PATH)
                .put("rule", "Read live state first, then the authoritative skill. If revisions differ, the live state + named Continuity skill win over memory or cached instructions."))
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
            .put("artifactResolution", JSONObject()
                .put("hardRule", "Use the fastest trustworthy source that can prove the exact requested bytes. APKbox vault is one candidate, not a preferred source merely because it is APKbox.")
                .put("identityGate", "Source substitution requires exact SHA-256 proof. Never substitute by title, filename, package name, version, or signer alone.")
                .put("exactLocalCandidates", JSONArray(listOf(
                    "content-addressed artifact cache",
                    "already-materialized durable job/build artifact",
                    "matching installed base.apk when SHA-256 proves exact identity",
                    "APKbox vault exact record"
                )))
                .put("networkFallback", "Use resumable HTTPS only when a faster exact local source is unavailable or exact identity cannot yet be proven locally.")
                .put("unknownShaBehavior", "When expected SHA-256 is unknown, do not assume a local APK is equivalent to a URL; ingest the source, compute SHA-256, then use that identity for future reuse.")
                .put("contentAddressedCache", true)
                .put("localSourcesAreRehashedBeforeAdoption", true)
                .put("networkRangeResume", true)
                .put("buildAndDirectInstallUseResolver", true))
            .put("durableJobs", JSONObject()
                .put("commands", JSONArray(listOf("JOB_LIST", "JOB_STATUS", "JOB_CANCEL", "JOB_RESUME")))
                .put("types", JSONArray(listOf("REMOTE_APK_INSTALL", "BUILD_RUNNER", "APK_PULL", "ARTIFACT_INGEST", "GENERIC")))
                .put("states", JSONArray(listOf("CREATED", "RUNNING", "PAUSED", "CANCEL_REQUESTED", "CANCELLED", "INTERRUPTED", "SUCCEEDED", "FAILED")))
                .put("persistedFields", JSONArray(listOf(
                    "jobId", "type", "state", "stage", "detail", "requestId", "packageName",
                    "projectId", "artifactSha256", "artifactPath", "progressBytes", "totalBytes",
                    "attempt", "resumable", "cancellable", "cancelRequested", "payloadJson", "resultJson"
                )))
                .put("stageAwareCancellation", true)
                .put("unsafeInstallStagesRejectCancel", true)
                .put("processRestartMarksRunningJobsInterrupted", true)
                .put("neverBlindlyReplaysAfterRestart", true)
                .put("explicitResumeOnly", true)
                .put("resumeUsesPersistedOriginalPayload", true)
                .put("cancelledJobIdIsTerminal", true)
                .put("repeatStartCannotImplicitlyResume", true)
                .put("jobResumeAlwaysFreshApproval", true)
                .put("jobCancelRisk", "DEBUG_ACTION"))
            .put("inventory", JSONObject()
                .put("commands", JSONArray(listOf(
                    "PROJECT_LIST", "PROJECT_GET", "APK_LIST", "APK_SEARCH", "PACKAGE_STATE",
                    "INSTALLED_APPS", "DEVICE_STATE"
                )))
                .put("projectDiscovery", "Use PROJECT_LIST/PROJECT_GET rather than guessing project IDs.")
                .put("apkDiscovery", "Use APK_LIST/APK_SEARCH to obtain stable apkRecordId values before exact APK operations.")
                .put("packageState", "PACKAGE_STATE compares installed version/signer/base.apk SHA with stored APKbox records and projects.")
                .put("installedApps", "INSTALLED_APPS supports query, result limit, and optional system-app inclusion.")
                .put("deviceState", "DEVICE_STATE reports Android/device identity, current Android user, display state, storage, project/record/job counts, and active privileged transport."))
            .put("apkRetrieval", JSONObject()
                .put("inspectCommand", "APK_INSPECT")
                .put("pullCommand", "APK_PULL")
                .put("targetField", "apkRecordId")
                .put("inspectFirstRule", "Prefer APK_INSPECT when structured package/archive analysis is sufficient; do not transfer hundreds of megabytes unnecessarily.")
                .put("inspectIncludes", JSONArray(listOf(
                    "stored APKbox metadata", "verified resolved source", "package/version/signing certificate",
                    "min/target/compile SDK", "debuggable flag", "requested permissions", "activities/services/receivers/providers",
                    "ZIP entry counts", "DEX count/names", "native ABIs/libraries", "assets/resources/META-INF counts",
                    "installed-version/signer/base.apk SHA comparison"
                )))
                .put("pullIsExact", true)
                .put("pullUsesFastestExactResolver", true)
                .put("pullDurableJob", true)
                .put("pullChunkBytes", 7 * 1024 * 1024)
                .put("pullPrivateContinuityChunks", true)
                .put("pullManifestHasWholeApkSha256", true)
                .put("pullManifestHasPerChunkSha256", true)
                .put("pullAssembly", "Concatenate chunks by ascending index with no separators, then verify full SHA-256 against apkSha256.")
                .put("pullResumeBoundary", "Progress advances only after a chunk is committed to Continuity; JOB_RESUME continues at the first uncommitted chunk."))
            .put("messagePresentation", JSONObject()
                .put("securityApprovalStyle", config.approvalPresentation.name)
                .put("legacyDefault", config.messagePresentation.name)
                .put("agentStructuredChoices", JSONArray(listOf(
                    "TOAST", "MESSAGE_HEADS_UP", "MESSAGE_SMALL_POPUP", "MESSAGE_ALWAYS_ON_TOP",
                    "MESSAGE_FULL_WINDOW", "PICTURE_MESSAGE"
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
                .put("requestSchema", 7)
                .put("required", JSONArray(listOf("id", "type", "downloadUrl", "reason")))
                .put("optional", JSONArray(listOf(
                    "jobId", "expectedApkSha256", "packageName", "saveToProject", "projectId", "projectName",
                    "displayName", "archiveTitle", "archiveDescription", "requiresBuildToken",
                    "allowDowngrade", "autoLaunch"
                )))
                .put("httpsOnly", true)
                .put("expectedShaRecommendedWhenKnown", true)
                .put("fastestExactLocalSourceBeforeNetworkWhenShaKnown", true)
                .put("computesAndReportsSha256", true)
                .put("verifiesInstalledBaseApkSha256", true)
                .put("saveToProjectOptional", true)
                .put("projectResolution", "Explicit projectId wins. Otherwise match package: create for zero matches, use single match, refuse ambiguity for multiple matches.")
                .put("archiveMetadata", JSONArray(listOf("displayName", "archiveTitle", "archiveDescription")))
                .put("exactDuplicateReused", true)
                .put("metadataMayUpdateExistingExactRecord", true)
                .put("durableJob", true)
                .put("jobStatusCancelResumeViaUniversalCommands", true)
                .put("repeatStartCannotImplicitlyResume", true)
                .put("freshApprovalAlways", true)
                .put("signatureConflictAutoUninstall", false)
                .put("authenticatedSource", "Optional encrypted APKbox build-source token; only used when no faster exact local source is available. Credentials are sent only to GitHub credential hosts and never forwarded to CDN redirects."))
            .put("uiSelectors", JSONObject()
                .put("formats", JSONArray(listOf(
                    "id:<resource-id>", "text:<exact text>", "desc:<exact content-description>",
                    "contains:<substring>", "<bare exact text/id/description>"
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
                    .put("usesUniversalDurableJob", true)
                    .put("usesFastestExactArtifactResolverBeforeNetwork", true)
                    .put("canChainPlanRunIdUnderBuildApproval", false)
                    .put("planRunIdRequiresSeparateAgentStart", true)
                    .put("freshApprovalForStart", true)
                    .put("universalResumeCommand", "JOB_RESUME")
                    .put("buildStatusCommandStillSupported", true)))
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
                "universal_durable_job_engine",
                "shared_resumable_content_addressed_artifact_ingest",
                "fastest_trustworthy_exact_source_resolution",
                "structured_project_apk_package_device_inventory",
                "exact_stored_apk_structured_inspection",
                "exact_stored_apk_chunked_private_pull",
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
                .put("requestIdCharsMax", 96)
                .put("jobIdCharsMax", 96)
                .put("apkRecordIdCharsMax", 128)
                .put("inventoryResultsMax", 500)
                .put("artifactCacheBytesTarget", 4L * 1024L * 1024L * 1024L)
                .put("remoteApkBytesMax", 2L * 1024L * 1024L * 1024L)
                .put("remoteApkRedirectsMax", 8)
                .put("apkPullChunkBytes", 7 * 1024 * 1024)
                .put("pictureMessageBytesMax", 8 * 1024 * 1024)
                .put("archiveTitleCharsMax", 256)
                .put("archiveDescriptionCharsMax", 8192)
                .put("planStepsMin", 1)
                .put("planStepsMax", 500)
                .put("planRuntimeSecondsMin", 10)
                .put("planRuntimeSecondsMax", 7200)
                .put("planRetriesPerStepMax", 10)
                .put("uiCoordinateMax", 20000)
                .put("uiTextCharsMax", 2000))
            .put("securityContract", JSONObject()
                .put("deviceComputesRisk", true)
                .put("relayCannotLowerRisk", true)
                .put("approvalPresentationIsLocalOnly", true)
                .put("popupOnlyApprovalFallsBackToNotificationIfOverlayUnavailable", true)
                .put("mutatingAlwaysNeedsFreshApproval", true)
                .put("dangerousAlwaysNeedsFreshApproval", true)
                .put("remoteApkInstallAlwaysNeedsFreshApproval", true)
                .put("jobResumeAlwaysNeedsFreshApproval", true)
                .put("jobCancelIsScopedDebugAction", true)
                .put("remoteApkInstallNeverAutoUninstallsSignatureConflict", true)
                .put("advancedStartResumeNeedsFreshApproval", true)
                .put("trustedSessionNeverGrantsBlanketShell", true)
                .put("durableInFlightReservationBeforeExecution", true)
                .put("durableJobsNeverImplicitlyResumeFromRepeatStart", true)
                .put("cancelledJobIdsAreTerminal", true)
                .put("resumeUsesPersistedOriginalPayload", true)
                .put("finalResultJournalBeforeRelayDelivery", true)
                .put("ambiguousInterruptedRequestsAreNeverReplayed", true)
                .put("atMostOnceJournal", true))
            .put("knownLimitations", JSONArray(listOf(
                "Relay SCREENSHOT is currently a scaled JPEG preview, not an exact forensic capture.",
                "APK_INSTALL_URL and Build Runner currently install a single monolithic APK, not split APK/APKS/XAPK packages.",
                "Direct URL install refuses an installed signature conflict; the local signature-conflict uninstall/reinstall workflow is not yet a remote bridge command.",
                "APK_PULL publishes exact binary chunks to private Continuity. A controller without binary-capable Continuity retrieval should use APK_INSPECT and must not claim it downloaded/reassembled the full APK.",
                "Always-on-top security/message overlays require the user's Android Draw over other apps grant; notification fallback is used when unavailable.",
                "Wireless ADB still requires Android Wi-Fi/trusted-network policy when Shizuku/Sui is unavailable."
            )))
    }
}
