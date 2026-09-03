package com.mekromn.apkbox.agent

import com.mekromn.apkbox.bridge.PrivilegedBridgeManager
import com.mekromn.apkbox.bridge.ScreenAgentController
import kotlinx.coroutines.delay

enum class RecoveryAction {
    NONE,
    RECONNECT_ADB,
    SOFT_RELAUNCH_TARGET,
}

data class RecoveryResult(
    val action: RecoveryAction,
    val attempted: Boolean,
    val recovered: Boolean,
    val detail: String,
    val foregroundPackage: String = "",
    val uiFingerprint: String = "",
)

/**
 * Performs only bounded, non-destructive recovery that is safe to execute after the watchdog has
 * persisted evidence. Hard restarts, force-stop, preference clearing, reinstall, and rollback are
 * intentionally not hidden inside this class; those belong to explicit autonomous-plan/build-runner
 * operations with their own policy and audit trail.
 */
class AgentRecoveryExecutor(
    private val privileged: PrivilegedBridgeManager,
    private val screen: ScreenAgentController,
) {
    suspend fun recover(
        checkpoint: AgentCheckpoint,
        decision: OracleDecision,
    ): RecoveryResult {
        if (!decision.mayRetry) {
            return RecoveryResult(
                action = RecoveryAction.NONE,
                attempted = false,
                recovered = false,
                detail = "Oracle does not permit automatic retry for ${decision.signal.name}.",
            )
        }

        return when (decision.signal) {
            // The legacy oracle name ADB_DISCONNECTED now means no privileged shell transport was
            // available. Shizuku/Sui recovery is attempted before Wireless ADB.
            OracleSignal.ADB_DISCONNECTED -> reconnectPrivileged(checkpoint)
            OracleSignal.UI_FROZEN,
            OracleSignal.BLACK_OR_BLANK_SCREEN,
            OracleSignal.DEADLINE_EXCEEDED -> softRelaunch(checkpoint)

            OracleSignal.ACTION_FAILED,
            OracleSignal.CONTROLLER_LOST,
            OracleSignal.WRONG_FOREGROUND_PACKAGE,
            OracleSignal.USER_INTERVENED,
            OracleSignal.APP_PROCESS_DIED,
            OracleSignal.ANR_OR_CRASH_DIALOG,
            OracleSignal.HEALTHY -> RecoveryResult(
                action = RecoveryAction.NONE,
                attempted = false,
                recovered = false,
                detail = "${decision.signal.name} requires controller/user/plan-runner handling rather than automatic UI recovery.",
            )
        }
    }

    private suspend fun reconnectPrivileged(checkpoint: AgentCheckpoint): RecoveryResult {
        repeat(2) { attempt ->
            val ready = runCatching { privileged.ensureReady() }.getOrDefault(false) ||
                runCatching { privileged.tryStartWirelessDebugging() }.getOrDefault(false)
            if (ready) {
                val foreground = runCatching { screen.foregroundPackage() }.getOrDefault("")
                return RecoveryResult(
                    action = RecoveryAction.RECONNECT_ADB,
                    attempted = true,
                    recovered = true,
                    detail = "Privileged transport recovered on attempt ${attempt + 1} via ${privileged.activeTransportLabel()}.",
                    foregroundPackage = foreground,
                )
            }
            delay(750L * (attempt + 1))
        }
        return RecoveryResult(
            action = RecoveryAction.RECONNECT_ADB,
            attempted = true,
            recovered = false,
            detail = "Neither Shizuku/Sui nor Wireless ADB became usable within the bounded retry budget for run ${checkpoint.runId}.",
        )
    }

    private suspend fun softRelaunch(checkpoint: AgentCheckpoint): RecoveryResult {
        val pkg = safePackage(checkpoint.targetPackage)
        if (!runCatching { privileged.ensureReady() }.getOrDefault(false)) {
            return RecoveryResult(
                action = RecoveryAction.SOFT_RELAUNCH_TARGET,
                attempted = false,
                recovered = false,
                detail = "Cannot soft-relaunch $pkg because no privileged transport is ready.",
            )
        }

        // `monkey -p ... 1` is the same structured launch mechanism used by Bridge LAUNCH. It does
        // not force-stop or clear the target and therefore cannot silently destroy the failing state.
        val launch = privileged.execute("monkey -p $pkg -c android.intent.category.LAUNCHER 1", 12)
        if (launch.timedOut || (launch.exitCode != null && launch.exitCode != 0)) {
            return RecoveryResult(
                action = RecoveryAction.SOFT_RELAUNCH_TARGET,
                attempted = true,
                recovered = false,
                detail = "Soft relaunch failed through ${privileged.activeTransportLabel()}${launch.exitCode?.let { " with exit code $it" }.orEmpty()}.",
            )
        }

        val deadline = System.currentTimeMillis() + 6_000L
        var foreground = ""
        while (System.currentTimeMillis() < deadline) {
            foreground = runCatching { screen.foregroundPackage() }.getOrDefault("")
            if (foreground == pkg) {
                val snapshot = runCatching { screen.snapshot("recovery-${sanitize(checkpoint.runId)}-${System.nanoTime()}") }.getOrNull()
                return RecoveryResult(
                    action = RecoveryAction.SOFT_RELAUNCH_TARGET,
                    attempted = true,
                    recovered = true,
                    detail = "Soft relaunch through ${privileged.activeTransportLabel()} returned $pkg to the foreground for re-observation.",
                    foregroundPackage = foreground,
                    uiFingerprint = snapshot?.uiFingerprint.orEmpty(),
                )
            }
            delay(250)
        }
        return RecoveryResult(
            action = RecoveryAction.SOFT_RELAUNCH_TARGET,
            attempted = true,
            recovered = false,
            detail = "Soft relaunch did not return $pkg to the foreground within 6 seconds; last foreground was ${foreground.ifBlank { "unknown" }}.",
            foregroundPackage = foreground,
        )
    }

    private fun safePackage(value: String): String {
        val trimmed = value.trim()
        require(trimmed.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { "Invalid target package." }
        return trimmed
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "run" }
}
