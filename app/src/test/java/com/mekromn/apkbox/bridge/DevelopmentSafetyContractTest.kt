package com.mekromn.apkbox.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevelopmentSafetyContractTest {
    private val now = 1_788_336_000_000L
    private val trusted = now + 10 * 60_000L

    @Test
    fun relayAndLiveContractCannotDriftBackToContinuityMain() {
        assertEquals("apkbox-relay", GitHubRelayClient.RELAY_BRANCH)
        assertTrue(BridgeCapabilityCatalog.PROTOCOL_VERSION >= 7)
        assertTrue(BridgeCapabilityCatalog.CAPABILITY_SCHEMA >= 7)
        assertTrue(BridgeCapabilityCatalog.SKILL_REVISION.startsWith("2026-09-05"))
    }

    @Test
    fun deploymentAndResumeNeverInheritTrustedSession() {
        val types = listOf(
            BridgeCommandType.APK_INSTALL_URL,
            BridgeCommandType.BUILD_START,
            BridgeCommandType.JOB_RESUME,
        )
        types.forEach { type ->
            val request = BridgeRequest(
                id = "contract-${type.name.lowercase()}",
                type = type,
                downloadUrl = if (type == BridgeCommandType.APK_INSTALL_URL) "https://example.com/app.apk" else "",
                jobId = if (type == BridgeCommandType.JOB_RESUME) "job-1" else "",
                buildId = if (type == BridgeCommandType.BUILD_START) "build-1" else "",
                runId = if (type == BridgeCommandType.BUILD_START) "run-1" else "",
                createdAtEpochMs = now,
                expiresAtEpochMs = trusted,
            )
            assertEquals("$type must remain mutating", BridgeRisk.MUTATING, BridgePolicy.classify(request))
            assertFalse("$type must never inherit trust", BridgePolicy.trustedSessionEligible(request))
            assertFalse(
                "$type must not auto-execute in an active trusted window",
                BridgePolicy.mayAutoExecute(request, trusted, true, true, now),
            )
        }
    }

    @Test
    fun exactApkInspectionAndInventoryStayReadOnly() {
        val requests = listOf(
            BridgeRequest(id = "inspect", type = BridgeCommandType.APK_INSPECT, apkRecordId = "record-1"),
            BridgeRequest(id = "state", type = BridgeCommandType.DEVICE_STATE),
            BridgeRequest(id = "jobs", type = BridgeCommandType.JOB_STATUS, jobId = "job-1"),
        )
        requests.forEach { request ->
            assertEquals(BridgeRisk.READ_ONLY, BridgePolicy.classify(request))
            assertTrue(BridgePolicy.trustedSessionEligible(request))
        }
    }
}
