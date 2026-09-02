package com.mekromn.apkbox.bridge

object BridgePolicy {
    private val shellMetacharacters = Regex("[;&|`><\\n\\r]|\\$\\(|\\$\\{")

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
        BridgeCommandType.POPUP -> BridgeRisk.INFO

        BridgeCommandType.LOGCAT,
        BridgeCommandType.APP_LOGCAT,
        BridgeCommandType.DUMPSYS -> BridgeRisk.READ_ONLY

        // The structured LAUNCH type is the only trusted-session stateful/debug action. APKbox
        // builds that command itself from a validated package name.
        BridgeCommandType.LAUNCH -> BridgeRisk.DEBUG_ACTION
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
            BridgeRisk.INFO -> when (request.type) {
                BridgeCommandType.POPUP -> allowInformational && allowPopups
                else -> allowInformational
            }
            BridgeRisk.READ_ONLY,
            BridgeRisk.DEBUG_ACTION -> trustedUntilEpochMs > now
            BridgeRisk.MUTATING,
            BridgeRisk.DANGEROUS -> false
        }
    }

    fun trustedSessionEligible(request: BridgeRequest): Boolean = when (classify(request)) {
        BridgeRisk.READ_ONLY,
        BridgeRisk.DEBUG_ACTION -> true
        else -> false
    }

    private fun classifyShell(raw: String): BridgeRisk {
        val command = raw.trim()
        if (command.isEmpty()) return BridgeRisk.DANGEROUS

        // Any shell composition/chaining is high risk before looking at individual tokens. This
        // prevents a command from inheriting a trusted-read classification by hiding a second
        // operation behind ;, |, command substitution, redirection, or a newline.
        if (shellMetacharacters.containsMatchIn(command)) return BridgeRisk.DANGEROUS

        val lower = " ${command.lowercase()} "
        if (mutatingTokens.any { it in lower }) return BridgeRisk.MUTATING

        val normalized = command.lowercase().replace(Regex("\\s+"), " ").trim()
        if (normalized in readOnlyExact || readOnlyPrefixes.any { normalized.startsWith(it) }) {
            return BridgeRisk.READ_ONLY
        }

        // Raw shell commands that do anything beyond the strict read-only allowlist always require
        // a fresh approval, even during a trusted session. Use structured LAUNCH/LOGCAT/DUMPSYS for
        // safe autonomous debugging operations.
        return BridgeRisk.DANGEROUS
    }
}
