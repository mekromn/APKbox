package com.mekromn.apkbox.bridge

import com.mekromn.apkbox.ApkBoxServices

/**
 * Compatibility overload for UI code that still passes only the legacy ADB status object.
 * Prefer the already-created unified manager's full status so a manual Test + register action never
 * overwrites Continuity state.json with a fake ADB-only view while Shizuku/Sui is actually active.
 */
suspend fun GitHubRelayClient.heartbeat(
    config: BridgeConfig,
    token: String,
    adbStatus: AdbBridgeStatus,
) {
    val privileged = ApkBoxServices.existingPrivilegedBridge()?.status?.value
    val merged = if (privileged != null) privileged.copy(adb = adbStatus)
    else PrivilegedBridgeStatus(adb = adbStatus)
    heartbeat(config, token, merged)
}
