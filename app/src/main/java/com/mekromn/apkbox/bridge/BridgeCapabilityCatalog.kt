package com.mekromn.apkbox.bridge

import org.json.JSONArray
import org.json.JSONObject

/** Live self-describing contract published in every Continuity state.json heartbeat. */
object BridgeCapabilityCatalog {
    const val PROTOCOL_VERSION = 7
    const val CAPABILITY_SCHEMA = 7
    const val SKILL_REVISION = "2026-09-05.2"
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
            .put("relay", JSONObject()
                .put("repository", "$SKILL_REPOSITORY")
                .put("runtimeBranch", GitHubRelayClient.RELAY_BRANCH)
                .put("operatorBranch", "main")
                .put("runtimeRoot", "bridge/devices/<device-id>/")
                .put("runtimePaths", JSONArray(listOf(
                    "state.json", "inbox", "outbox", "artifacts", "message-assets",
                    "plans", "runs", "builds", "build-runs"
                )))
                .put("hardRule", "Current branch-aware APKbox runtime reads/writes/deletes MUST target apkbox-relay and must never rely on the repository default branch.")
                .put("requestBranchConsistency", "Inbox, result, checkpoints and artifacts for one request stay on the same runtime branch.")
                .put("legacyMainFallback", "Migration only: a fresh pre-branch APKbox state on main may be used only until a fresh state advertises relayBranch=apkbox-relay; after that, ignore legacy main runtime state for that device.")
                .put("continuityActionsIsolation", "Continuity extension CI ignores bridge/** and skills/**; normal bridge runtime traffic is not supposed to consume private Actions minutes."))
            .put("operatorSkill", JSONObject()
                .put("repository", SKILL_REPOSITORY)
                .put("branch", "main")
                .put("path", SKILL_PATH)
                .put("protocolPath", PROTOCOL_PATH)
                .put("rule", "Read live state first, then the authoritative skill from Continuity main. If revisions differ, live state + the named Continuity skill win over memory/cached instructions."))
            .put("testingPolicy", JSONObject()
                .put("hardRule", "For Android code testing and debugging, establish an Android 16 / API 36 emulator baseline first. Gather emulator debugging evidence before using the physical APKbox bridge device.")
                .put("primaryEnvironment", "Android 16 / API 36 emulator")
                .put("debugEvidenceOrder", JSONArray(listOf(
                    "API 36 emulator logcat/dumpsys/package/activity/process/UI evidence",
                    "physical APKbox bridge device only when emulator evidence is insufficient or device hardware/OEM behavior is required"
                )))
                .put("emulatorFirstAlways", true)
                .put("compileOnlyIsNotRuntimeValidation", true)
                .put("physicalDeviceIsEscalation", true)
                .put("physicalDeviceUseCases", JSONArray(listOf(
                    "camera sensors/lenses and other physical hardware",
                    "HDR/display and hardware codec behavior",
                    "Pixel/OEM-specific Android behavior",
                    "real Shizuku/Sui/Wireless ADB behavior",
                    "thermal, sustained-performance, battery, radio or storage behavior",
                    "issues the API 36 emulator cannot reproduce",
                    "explicit user request for real-device confirmation"
                )))
                .put("hardwareSpecificRule", "Even hardware-specific changes get API 36 install/launch/smoke and meaningful non-hardware testing first, then physical-device validation.")
                .put("comparisonRule", "When physical-device evidence is needed, retain emulator results as the baseline and identify the device-specific difference.")
                .put("ciGate", "APKbox public-repo CI boots API 36, installs the exact signed debug APK, launches MainActivity, verifies process/activity survival, and archives emulator logcat/dumpsys diagnostics."))
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
                .put("hardRule", "Use the fastest trustworthy source that can prove the exact requested bytes. APKbox vault is one candidate, not a privileged favorite.")
                .put("identityGate", "Source substitution requires exact SHA-256 proof; never substitute by title, filename, package, version or signer alone.")
                .put("exactLocalCandidates", JSONArray(listOf(
                    "content-addressed artifact cache",
                    "already-materialized durable job/build artifact",
                    "matching installed base.apk with exact SHA proof",
                    "APKbox vault exact record"
                )))
                .put("networkFallback", "Use resumable HTTPS when no faster exact local source exists or exact identity cannot yet be proven locally.")
                .put("unknownShaBehavior", "When expected SHA is unknown, ingest/compute SHA before future source substitution.")
                .put("contentAddressedCache", true)
                .put("localSourcesAreRehashedBeforeAdoption", true)
                .put("networkRangeResume", true)
                .put("buildAndDirectInstallUseResolver", true))
            .put("durableJobs", JSONObject()
                .put("commands", JSONArray(listOf("JOB_LIST", "JOB_STATUS", "JOB_CANCEL", "JOB_RESUME")))
                .put("types", JSONArray(listOf("REMOTE_APK_INSTALL", "BUILD_RUNNER", "APK_PULL", "ARTIFACT_INGEST", "GENERIC")))
                .put("states", JSONArray(listOf("CREATED", "RUNNING", "PAUSED", "CANCEL_REQUESTED", "CANCELLED", "INTERRUPTED", "SUCCEEDED", "FAILED")))
                .put("stageAwareCancellation", true)
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
                    "PROJECT_LIST", "PROJECT_GET", "APK_LIST", "APK_SEARCH",
                    "PACKAGE_STATE", "INSTALLED_APPS", "DEVICE_STATE"
                )))
                .put("projectDiscovery", "Use PROJECT_LIST/PROJECT_GET rather than guessing project IDs.")
                .put("apkDiscovery", "Use APK_LIST/APK_SEARCH to obtain stable apkRecordId before exact APK operations.")
                .put("packageState", "PACKAGE_STATE compares installed version/signer/base.apk SHA with stored records/projects."))
            .put("apkRetrieval", JSONObject()
                .put("inspectCommand", "APK_INSPECT")
                .put("pullCommand", "APK_PULL")
                .put("targetField", "apkRecordId")
                .put("inspectFirstRule", "Prefer APK_INSPECT when structured analysis is sufficient; do not transfer hundreds of megabytes unnecessarily.")
                .put("pullIsExact", true)
                .put("pullUsesFastestExactResolver", true)
                .put("pullDurableJob", true)
                .put("pullChunkBytes", 7 * 1024 * 1024)
                .put("pullPrivateContinuityChunks", true)
                .put("pullManifestHasWholeApkSha256", true)
                .put("pullManifestHasPerChunkSha256", true)
                .put("pullAssembly", "Concatenate chunks by ascending index with no separators, then verify full SHA-256 against apkSha256."))
            .put("messagePresentation", JSONObject()
                .put("securityApprovalStyle", config.approvalPresentation.name)
                .put("legacyDefault", config.messagePresentation.name)
                .put("agentStructuredChoices", JSONArray(listOf(
                    "TOAST", "MESSAGE_HEADS_UP", "MESSAGE_SMALL_POPUP",
                    "MESSAGE_ALWAYS_ON_TOP", "MESSAGE_FULL_WINDOW", "PICTURE_MESSAGE"
                )))
                .put("intrusiveChoicesRequireAllowPopups", true)
                .put("overlayPermissionRequiredForFloatingChoices", true)
                .put("pictureMaxBytes", 8 * 1024 * 1024))
            .put("remoteApkInstall", JSONObject()
                .put("command", "APK_INSTALL_URL")
                .put("requestSchema", 7)
                .put("required", JSONArray(listOf("id", "type", "downloadUrl", "reason")))
                .put("optional", JSONArray(listOf(
                    "jobId", "expectedApkSha256", "packageName", "saveToProject", "projectId",
                    "projectName", "displayName", "archiveTitle", "archiveDescription",
                    "requiresBuildToken", "allowDowngrade", "autoLaunch"
                )))
                .put("httpsOnly", true)
                .put("fastestExactLocalSourceBeforeNetworkWhenShaKnown", true)
                .put("computesAndReportsSha256", true)
                .put("verifiesInstalledBaseApkSha256", true)
                .put("saveToProjectOptional", true)
                .put("durableJob", true)
                .put("jobStatusCancelResumeViaUniversalCommands", true)
                .put("freshApprovalAlways", true)
                .put("signatureConflictAutoUninstall", false))
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
                    .put("freshApprovalForStartOrResume", true))
                .put("buildRunner", JSONObject()
                    .put("commands", JSONArray(listOf("BUILD_START", "BUILD_STATUS")))
                    .put("candidatePath", "bridge/devices/<device-id>/builds/<buildId>.json")
                    .put("checkpointPath", "bridge/devices/<device-id>/build-runs/<runId>/checkpoint.json")
                    .put("usesUniversalDurableJob", true)
                    .put("usesFastestExactArtifactResolverBeforeNetwork", true)
                    .put("planRunIdRequiresSeparateAgentStart", true)
                    .put("freshApprovalForStart", true)
                    .put("universalResumeCommand", "JOB_RESUME")))
            .put("deviceCapabilities", JSONArray(listOf(
                "android_16_emulator_first_testing_policy",
                "dedicated_zero_actions_runtime_relay_branch",
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
                .put("planStepsMax", 500)
                .put("planRuntimeSecondsMax", 7200))
            .put("securityContract", JSONObject()
                .put("deviceComputesRisk", true)
                .put("relayCannotLowerRisk", true)
                .put("approvalPresentationIsLocalOnly", true)
                .put("mutatingAlwaysNeedsFreshApproval", true)
                .put("dangerousAlwaysNeedsFreshApproval", true)
                .put("remoteApkInstallAlwaysNeedsFreshApproval", true)
                .put("jobResumeAlwaysNeedsFreshApproval", true)
                .put("jobCancelIsScopedDebugAction", true)
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
                "Direct URL install refuses an installed signature conflict; local signature-conflict reinstall is not yet a remote verb.",
                "APK_PULL can publish exact binary chunks, but the current controller must have binary-capable Continuity retrieval to reconstruct them.",
                "Always-on-top overlays require Android Draw over other apps permission.",
                "Wireless ADB still requires Android Wi-Fi/trusted-network policy when Shizuku/Sui is unavailable.",
                "Pre-2026-09-05.1 APKbox builds may still use Continuity main during migration; main CI ignores bridge/** so this legacy traffic does not launch extension validation."
            )))
    }
}
