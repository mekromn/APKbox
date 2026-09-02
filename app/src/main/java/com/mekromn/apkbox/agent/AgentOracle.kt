package com.mekromn.apkbox.agent

/**
 * Deterministic failure/stall classifier for the autonomous test runner. Android-specific collectors
 * feed observations into this class; the decision logic itself stays platform-free and unit-testable.
 */
enum class OracleSignal {
    HEALTHY,
    CONTROLLER_LOST,
    DEADLINE_EXCEEDED,
    ACTION_FAILED,
    APP_PROCESS_DIED,
    WRONG_FOREGROUND_PACKAGE,
    UI_FROZEN,
    BLACK_OR_BLANK_SCREEN,
    ANR_OR_CRASH_DIALOG,
    ADB_DISCONNECTED,
    USER_INTERVENED,
}

data class OracleObservation(
    val nowEpochMs: Long,
    val missionDeadlineEpochMs: Long,
    val stepDeadlineEpochMs: Long,
    val controllerLeaseUntilEpochMs: Long,
    val expectedPackage: String,
    val foregroundPackage: String,
    val targetProcessAlive: Boolean,
    val adbConnected: Boolean,
    val uiFingerprint: String,
    val previousUiFingerprint: String = "",
    val identicalUiSamples: Int = 0,
    val requiredUiChange: Boolean = false,
    val screenMeanLuma: Double? = null,
    val screenLumaStdDev: Double? = null,
    val systemDialogText: String = "",
    val userIntervened: Boolean = false,
)

data class OracleDecision(
    val signal: OracleSignal,
    val detail: String,
    val terminalForStep: Boolean,
    val captureEvidence: Boolean,
    val mayRetry: Boolean,
)

object AgentOracle {
    private const val FROZEN_SAMPLE_THRESHOLD = 3
    private const val BLACK_MEAN_LUMA_THRESHOLD = 3.0
    private const val BLANK_STDDEV_THRESHOLD = 1.25

    fun evaluate(observation: OracleObservation): OracleDecision {
        if (observation.userIntervened) {
            return decision(
                OracleSignal.USER_INTERVENED,
                "User input detected; autonomous control must yield.",
                terminal = true,
                evidence = false,
                retry = true,
            )
        }
        if (!observation.adbConnected) {
            return decision(
                OracleSignal.ADB_DISCONNECTED,
                "Wireless ADB connection is unavailable.",
                terminal = true,
                evidence = false,
                retry = true,
            )
        }
        if (observation.missionDeadlineEpochMs > 0 && observation.nowEpochMs >= observation.missionDeadlineEpochMs) {
            return decision(
                OracleSignal.DEADLINE_EXCEEDED,
                "Mission deadline exceeded.",
                terminal = true,
                evidence = true,
                retry = false,
            )
        }
        if (observation.stepDeadlineEpochMs > 0 && observation.nowEpochMs >= observation.stepDeadlineEpochMs) {
            return decision(
                OracleSignal.DEADLINE_EXCEEDED,
                "Step deadline exceeded.",
                terminal = true,
                evidence = true,
                retry = true,
            )
        }
        if (observation.controllerLeaseUntilEpochMs > 0 && observation.nowEpochMs >= observation.controllerLeaseUntilEpochMs) {
            return decision(
                OracleSignal.CONTROLLER_LOST,
                "ChatGPT controller lease expired.",
                terminal = true,
                evidence = false,
                retry = true,
            )
        }
        if (!observation.targetProcessAlive) {
            return decision(
                OracleSignal.APP_PROCESS_DIED,
                "Target app process is no longer alive.",
                terminal = true,
                evidence = true,
                retry = false,
            )
        }
        if (observation.expectedPackage.isNotBlank() &&
            observation.foregroundPackage.isNotBlank() &&
            observation.foregroundPackage != observation.expectedPackage
        ) {
            return decision(
                OracleSignal.WRONG_FOREGROUND_PACKAGE,
                "Unexpected foreground package: ${observation.foregroundPackage}; expected ${observation.expectedPackage}.",
                terminal = true,
                evidence = true,
                retry = false,
            )
        }

        val dialog = observation.systemDialogText.lowercase()
        if (dialog.contains("isn't responding") ||
            dialog.contains("is not responding") ||
            dialog.contains("keeps stopping") ||
            dialog.contains("has stopped")
        ) {
            return decision(
                OracleSignal.ANR_OR_CRASH_DIALOG,
                "Android crash/ANR dialog detected.",
                terminal = true,
                evidence = true,
                retry = false,
            )
        }

        val mean = observation.screenMeanLuma
        val stdDev = observation.screenLumaStdDev
        if (mean != null && stdDev != null && mean <= BLACK_MEAN_LUMA_THRESHOLD && stdDev <= BLANK_STDDEV_THRESHOLD) {
            return decision(
                OracleSignal.BLACK_OR_BLANK_SCREEN,
                "Screen is effectively black/blank (mean luma $mean, stddev $stdDev).",
                terminal = true,
                evidence = true,
                retry = true,
            )
        }

        if (observation.requiredUiChange &&
            observation.uiFingerprint.isNotBlank() &&
            observation.uiFingerprint == observation.previousUiFingerprint &&
            observation.identicalUiSamples >= FROZEN_SAMPLE_THRESHOLD
        ) {
            return decision(
                OracleSignal.UI_FROZEN,
                "UI fingerprint remained unchanged for ${observation.identicalUiSamples} watchdog samples.",
                terminal = true,
                evidence = true,
                retry = true,
            )
        }

        return decision(
            OracleSignal.HEALTHY,
            "No failure or stall signal detected.",
            terminal = false,
            evidence = false,
            retry = false,
        )
    }

    fun actionFailure(detail: String, mayRetry: Boolean): OracleDecision = OracleDecision(
        signal = OracleSignal.ACTION_FAILED,
        detail = detail,
        terminalForStep = true,
        captureEvidence = true,
        mayRetry = mayRetry,
    )

    private fun decision(
        signal: OracleSignal,
        detail: String,
        terminal: Boolean,
        evidence: Boolean,
        retry: Boolean,
    ) = OracleDecision(
        signal = signal,
        detail = detail,
        terminalForStep = terminal,
        captureEvidence = evidence,
        mayRetry = retry,
    )
}
