package com.mekromn.apkbox.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeModelsTest {
    @Test
    fun requestRoundTripPreservesDebugIntent() {
        val original = BridgeRequest(
            id = "camera-logcat-001",
            type = BridgeCommandType.APP_LOGCAT,
            packageName = "com.example.camera",
            reason = "Capture crash after launch",
            createdAtEpochMs = 1000L,
            expiresAtEpochMs = 2000L,
            timeoutSeconds = 33,
            source = "ChatGPT via Continuity",
        )
        val parsed = BridgeRequest.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    @Test
    fun resultRoundTripPreservesLargeDebugOutputMetadata() {
        val result = BridgeResult(
            requestId = "logcat-002",
            status = BridgeResultStatus.SUCCESS,
            risk = BridgeRisk.READ_ONLY,
            detail = "Command completed.",
            output = "AndroidRuntime: FATAL EXCEPTION: main",
            exitCode = 0,
            durationMs = 314,
            completedAtEpochMs = 9000L,
            truncated = true,
        )
        val parsed = BridgeResult.fromJson(result.toJson("test-device"))
        assertEquals(result, parsed)
    }

    @Test
    fun malformedRequestIdIsRejectedBeforeRelayExecution() {
        val json = JSONObject()
            .put("id", "../../not-allowed")
            .put("type", "LOGCAT")
        assertThrows(IllegalArgumentException::class.java) {
            BridgeRequest.fromJson(json)
        }
    }

    @Test
    fun requestExpirationIsDeterministic() {
        val request = BridgeRequest(
            id = "expiry-test",
            type = BridgeCommandType.LOGCAT,
            createdAtEpochMs = 100L,
            expiresAtEpochMs = 200L,
        )
        assertFalse(request.isExpired(199L))
        assertFalse(request.isExpired(200L))
        assertTrue(request.isExpired(201L))
    }
}
