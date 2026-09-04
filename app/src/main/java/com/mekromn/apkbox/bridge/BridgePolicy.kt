package com.mekromn.apkbox.bridge

object BridgePolicy {
    private val shellMetacharacters = Regex("[;&|`><\\n\\r]|\\$\\(|\\$\\{")
    private val runIdRegex = Regex("[A-Za-z0-9._-]{1,96}")

    private val readOnlyExact = setOf(
        "id",
        "whoami",
        "uname",
        "uptime",
        "date",
        "ps",
        "top",
        "df",
        "mount",
        "printenv",
    )

    private val readOnlyPrefixes = listOf(
        "getprop",
        "dumpsys",
        "logcat -d",
        "logcat --dump",
        "pidof",
        "pm list ",
        "cmd package list ",
        "cmd activity get-",
        "settings get ",
        "cat /proc/",
        "cat /sys/",
        "ls ",
        "stat ",
        "du ",
        "wc ",
    )

    private val mutatingTokens = listOf(
        " rm ", "mv ", "cp ", "chmod ", "chown ", "touch ", "mkdir ", "rmdir ",
        "pm clear ", "pm install", "pm uninstall", "pm disable", "pm enable", "pm grant ", "pm revoke ",
        "settings put ", "settings delete ", "setprop ", "svc ", "reboot", "shutdown", "poweroff",
        "am force-stop ", "am kill ", "kill ", "pkill ", "killall ", "input ", "dd ", "mkfs", "mount -o rw",
    )

    fun classify(request: BridgeRequest): BridgeRisk = when (request.type) {
        BridgeCommandType.TOAST,
        BridgeCommandType.NOTIFICATION,
        BridgeCommandType.POPUP,
        BridgeCommandType.MESSAGE_SMALL_POPUP,
        BridgeCommandType.MESSAGE_ALWAYS_ON_TOP,
        BridgeCommandType.MESSAGE_FULL_WINDOW,
        BridgeCommandType.MESSAGE_HEADS_UP,
        BridgeCommandType.PICTURE_MESSAGE -> BridgeRisk.INFO

        BridgeCommandType.LOGCAT,
        BridgeCommandType.APP_LOGCAT,
        BridgeCommandType.DUMPSYS,
        BridgeCommandType.UI_SNAPSHOT,
        BridgeCommandType.SCREENSHOT,
        BridgeCommandType.AGENT_STATUS,
        BridgeCommandType.BUILD_STATUS -> BridgeRisk.READ_ONLY

        BridgeCommandType.LAUNCH,
        BridgeCommandType.UI_TAP,
        BridgeCommandType.UI_FIND_TAP,
        BridgeCommandType.UI_SWIPE,
        BridgeCommandType.UI_TEXT,
        BridgeCommandType.UI_KEY,
        BridgeCommandType.UI_WAIT,
        BridgeCommandType.AGENT_START,
        BridgeCommandType.AGENT_RESUME -> BridgeRisk.DEBUG_ACTION

        // A build candidate may install/downgrade/launch an APK. Classify from the maximum possible
        // effect rather than trusting remote candidate flags to lower the approval requirement.
        BridgeCommandType.BUILD_START -> BridgeRisk.MUTATING

        BridgeCommandType.SHELL -> classifyShell(request.command)
    }

    fun mayAutoExecute(
        request: BridgeRequest,
        trustedUntilEpochMs: Long,
        allowInformational: Boolean,
        allowPopups: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val risk = classify(request)
        return when (risk) {
            BridgeRisk.INFO -> {
                val intrusivePresentation = request.type in setOf(
                    BridgeCommandType.POPUP,
                    BridgeCommandType.MESSAGE_SMALL_POPUP,
                    BridgeCommandType.MESSAGE_ALWAYS_ON_TOP,
                    BridgeCommandType.MESSAGE_FULL_WINDOW,
                    BridgeCommandType.PICTURE_MESSAGE,
                )
                allowInformational && (!intrusivePresentation || allowPopups)
            }
            BridgeRisk.READ_ONLY -> trustedUntilEpochMs > now
            BridgeRisk.DEBUG_ACTION -> trustedUntilEpochMs > now && debugActionIsSafelyScoped(request)
            BridgeRisk.MUTATING,
            BridgeRisk.DANGEROUS -> false
        }
    }

    fun trustedSessionEligible(request: BridgeRequest): Boolean = when (classify(request)) {
        BridgeRisk.READ_ONLY -> true
        BridgeRisk.DEBUG_ACTION -> debugActionIsSafelyScoped(request)
        else -> false
    }

    /**
     * Autonomous screen interaction needs both package scope and run/sequence scope. A one-off
     * manually approved UI action may omit run metadata, but it can never inherit trusted-session
     * auto-execution in that form. Advanced plan start/resume are intentionally excluded: approving
     * a bounded autonomous execution is always a fresh on-device decision.
     */
    private fun debugActionIsSafelyScoped(request: BridgeRequest): Boolean = when (request.type) {
        BridgeCommandType.LAUNCH -> validPackage(request.packageName)
        BridgeCommandType.UI_TAP,
        BridgeCommandType.UI_FIND_TAP,
        BridgeCommandType.UI_SWIPE,
        BridgeCommandType.UI_TEXT,
        BridgeCommandType.UI_KEY,
        BridgeCommandType.UI_WAIT -> validPackage(request.packageName) && validRunSequence(request)
        BridgeCommandType.AGENT_START,
        BridgeCommandType.AGENT_RESUME -> false
        else -> false
    }

    private fun validRunSequence(request: BridgeRequest): Boolean =
        runIdRegex.matches(request.runId) && request.sequenceNumber > 0L

    private fun validPackage(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))

    private fun classifyShell(raw: String): BridgeRisk {
        val command = raw.trim()
        if (command.isEmpty()) return BridgeRisk.DANGEROUS

        if (shellMetacharacters.containsMatchIn(command)) return BridgeRisk.DANGEROUS

        val lower = " ${command.lowercase()} "
        if (mutatingTokens.any { it in lower }) return BridgeRisk.MUTATING

        val normalized = command.lowercase().replace(Regex("\\s+"), " ").trim()
        if (normalized in readOnlyExact || readOnlyPrefixes.any { normalized.startsWith(it) }) {
            return BridgeRisk.READ_ONLY
        }

        return BridgeRisk.DANGEROUS
    }
}
