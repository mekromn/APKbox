package com.mekromn.apkbox.bridge

enum class AdbHealPhase {
    HEALTHY,
    VERIFYING,
    REDISCOVERING,
    WAITING_FOR_WIFI,
    BACKOFF,
    USER_ACTION_REQUIRED,
    DISCONNECTED,
}

enum class AdbHealFailureKind {
    NONE,
    NETWORK,
    DISCOVERY,
    AUTHORIZATION,
    CONNECTION,
}

object AdbAutoHealPolicy {
    private val authorizationHints = listOf(
        "unauthorised",
        "unauthorized",
        "not authorised",
        "not authorized",
        "authentication failed",
        "pairing was not accepted",
        "pairing rejected",
        "certificate rejected",
    )

    private val networkHints = listOf(
        "network is unreachable",
        "no route to host",
        "wifi",
        "wi-fi",
        "network unavailable",
    )

    private val discoveryHints = listOf(
        "not discovered",
        "discovery",
        "mdns",
        "service not found",
        "timed out",
        "timeout",
    )

    fun failureKind(message: String): AdbHealFailureKind {
        val lower = message.lowercase()
        return when {
            authorizationHints.any { it in lower } -> AdbHealFailureKind.AUTHORIZATION
            networkHints.any { it in lower } -> AdbHealFailureKind.NETWORK
            discoveryHints.any { it in lower } -> AdbHealFailureKind.DISCOVERY
            lower.isBlank() -> AdbHealFailureKind.NONE
            else -> AdbHealFailureKind.CONNECTION
        }
    }

    fun requiresUserAction(kind: AdbHealFailureKind): Boolean =
        kind == AdbHealFailureKind.AUTHORIZATION

    fun backoffMs(consecutiveFailures: Int): Long = when (consecutiveFailures.coerceAtLeast(1)) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        4 -> 8_000L
        5 -> 15_000L
        6 -> 30_000L
        else -> 60_000L
    }

    fun shouldProbe(lastVerifiedAtEpochMs: Long, nowEpochMs: Long, intervalMs: Long = 30_000L): Boolean =
        lastVerifiedAtEpochMs <= 0L || nowEpochMs - lastVerifiedAtEpochMs >= intervalMs
}
