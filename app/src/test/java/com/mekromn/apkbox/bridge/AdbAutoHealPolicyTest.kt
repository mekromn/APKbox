package com.mekromn.apkbox.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbAutoHealPolicyTest {
    @Test
    fun authorizationFailuresRequireUserAction() {
        val messages = listOf(
            "Device unauthorized",
            "authentication failed",
            "Pairing was not accepted.",
            "certificate rejected by adbd",
        )
        messages.forEach { message ->
            val kind = AdbAutoHealPolicy.failureKind(message)
            assertEquals(message, AdbHealFailureKind.AUTHORIZATION, kind)
            assertTrue(message, AdbAutoHealPolicy.requiresUserAction(kind))
        }
    }

    @Test
    fun discoveryAndNetworkFailuresRemainSelfHealable() {
        val cases = mapOf(
            "Wireless ADB was not discovered." to AdbHealFailureKind.DISCOVERY,
            "mDNS service not found" to AdbHealFailureKind.DISCOVERY,
            "network is unreachable" to AdbHealFailureKind.NETWORK,
            "Wi-Fi network unavailable" to AdbHealFailureKind.NETWORK,
            "connection reset" to AdbHealFailureKind.CONNECTION,
        )
        cases.forEach { (message, expected) ->
            val kind = AdbAutoHealPolicy.failureKind(message)
            assertEquals(message, expected, kind)
            assertFalse(message, AdbAutoHealPolicy.requiresUserAction(kind))
        }
    }

    @Test
    fun reconnectBackoffIsFastThenBounded() {
        assertEquals(1_000L, AdbAutoHealPolicy.backoffMs(1))
        assertEquals(2_000L, AdbAutoHealPolicy.backoffMs(2))
        assertEquals(4_000L, AdbAutoHealPolicy.backoffMs(3))
        assertEquals(8_000L, AdbAutoHealPolicy.backoffMs(4))
        assertEquals(15_000L, AdbAutoHealPolicy.backoffMs(5))
        assertEquals(30_000L, AdbAutoHealPolicy.backoffMs(6))
        assertEquals(60_000L, AdbAutoHealPolicy.backoffMs(7))
        assertEquals(60_000L, AdbAutoHealPolicy.backoffMs(200))
    }

    @Test
    fun activeTrafficCanSuppressIdleHealthProbes() {
        val now = 1_788_500_000_000L
        assertTrue(AdbAutoHealPolicy.shouldProbe(0L, now))
        assertFalse(AdbAutoHealPolicy.shouldProbe(now - 29_999L, now))
        assertTrue(AdbAutoHealPolicy.shouldProbe(now - 30_000L, now))
    }
}
