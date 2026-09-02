package com.mekromn.apkbox.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOracleTest {
    private val now = 1_788_400_000_000L

    @Test
    fun healthyObservationStaysHealthy() {
        val result = AgentOracle.evaluate(base())
        assertEquals(OracleSignal.HEALTHY, result.signal)
        assertFalse(result.terminalForStep)
        assertFalse(result.captureEvidence)
    }

    @Test
    fun userInterventionAlwaysYieldsFirst() {
        val result = AgentOracle.evaluate(
            base(
                userIntervened = true,
                adbConnected = false,
                targetProcessAlive = false,
            )
        )
        assertEquals(OracleSignal.USER_INTERVENED, result.signal)
        assertTrue(result.terminalForStep)
        assertTrue(result.mayRetry)
        assertFalse(result.captureEvidence)
    }

    @Test
    fun controllerLeaseLossPausesRatherThanPretendingFailure() {
        val result = AgentOracle.evaluate(base(controllerLeaseUntilEpochMs = now))
        assertEquals(OracleSignal.CONTROLLER_LOST, result.signal)
        assertTrue(result.terminalForStep)
        assertTrue(result.mayRetry)
        assertFalse(result.captureEvidence)
    }

    @Test
    fun processDeathIsTerminalAndCapturesEvidence() {
        val result = AgentOracle.evaluate(base(targetProcessAlive = false))
        assertEquals(OracleSignal.APP_PROCESS_DIED, result.signal)
        assertTrue(result.terminalForStep)
        assertTrue(result.captureEvidence)
        assertFalse(result.mayRetry)
    }

    @Test
    fun unexpectedForegroundPackageStopsBlindAutomation() {
        val result = AgentOracle.evaluate(base(foregroundPackage = "com.android.settings"))
        assertEquals(OracleSignal.WRONG_FOREGROUND_PACKAGE, result.signal)
        assertTrue(result.captureEvidence)
        assertFalse(result.mayRetry)
    }

    @Test
    fun repeatedUiFingerprintOnlyFailsWhenChangeWasRequired() {
        val unchanged = base(
            uiFingerprint = "same",
            previousUiFingerprint = "same",
            identicalUiSamples = 4,
            requiredUiChange = false,
        )
        assertEquals(OracleSignal.HEALTHY, AgentOracle.evaluate(unchanged).signal)

        val frozen = unchanged.copy(requiredUiChange = true)
        val result = AgentOracle.evaluate(frozen)
        assertEquals(OracleSignal.UI_FROZEN, result.signal)
        assertTrue(result.captureEvidence)
        assertTrue(result.mayRetry)
    }

    @Test
    fun blackBlankScreenIsDetectedByLumaAndVariance() {
        val result = AgentOracle.evaluate(
            base(screenMeanLuma = 1.2, screenLumaStdDev = 0.4)
        )
        assertEquals(OracleSignal.BLACK_OR_BLANK_SCREEN, result.signal)
        assertTrue(result.captureEvidence)
    }

    @Test
    fun crashAndAnrDialogsAreDetected() {
        val crash = AgentOracle.evaluate(base(systemDialogText = "Camera keeps stopping"))
        assertEquals(OracleSignal.ANR_OR_CRASH_DIALOG, crash.signal)
        assertTrue(crash.captureEvidence)

        val anr = AgentOracle.evaluate(base(systemDialogText = "Camera isn't responding"))
        assertEquals(OracleSignal.ANR_OR_CRASH_DIALOG, anr.signal)
    }

    @Test
    fun missionDeadlineBeatsControllerLeaseWhenBothExpired() {
        val result = AgentOracle.evaluate(
            base(
                missionDeadlineEpochMs = now,
                controllerLeaseUntilEpochMs = now,
            )
        )
        assertEquals(OracleSignal.DEADLINE_EXCEEDED, result.signal)
        assertFalse(result.mayRetry)
        assertTrue(result.captureEvidence)
    }

    private fun base(
        missionDeadlineEpochMs: Long = now + 60_000,
        stepDeadlineEpochMs: Long = now + 20_000,
        controllerLeaseUntilEpochMs: Long = now + 30_000,
        expectedPackage: String = "com.example.camera",
        foregroundPackage: String = "com.example.camera",
        targetProcessAlive: Boolean = true,
        adbConnected: Boolean = true,
        uiFingerprint: String = "ui-a",
        previousUiFingerprint: String = "ui-b",
        identicalUiSamples: Int = 0,
        requiredUiChange: Boolean = false,
        screenMeanLuma: Double? = 55.0,
        screenLumaStdDev: Double? = 12.0,
        systemDialogText: String = "",
        userIntervened: Boolean = false,
    ) = OracleObservation(
        nowEpochMs = now,
        missionDeadlineEpochMs = missionDeadlineEpochMs,
        stepDeadlineEpochMs = stepDeadlineEpochMs,
        controllerLeaseUntilEpochMs = controllerLeaseUntilEpochMs,
        expectedPackage = expectedPackage,
        foregroundPackage = foregroundPackage,
        targetProcessAlive = targetProcessAlive,
        adbConnected = adbConnected,
        uiFingerprint = uiFingerprint,
        previousUiFingerprint = previousUiFingerprint,
        identicalUiSamples = identicalUiSamples,
        requiredUiChange = requiredUiChange,
        screenMeanLuma = screenMeanLuma,
        screenLumaStdDev = screenLumaStdDev,
        systemDialogText = systemDialogText,
        userIntervened = userIntervened,
    )
}
