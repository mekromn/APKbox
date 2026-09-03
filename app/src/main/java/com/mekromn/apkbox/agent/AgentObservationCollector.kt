package com.mekromn.apkbox.agent

import android.graphics.BitmapFactory
import android.graphics.Color
import com.mekromn.apkbox.bridge.PrivilegedBridgeManager
import com.mekromn.apkbox.bridge.ScreenAgentController
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Collects the smallest live Android state needed by AgentOracle. This intentionally does not make
 * recovery decisions; it only observes. Visual sampling is performed directly from `screencap -p`
 * without creating a relay artifact, so routine watchdog samples do not churn storage or GitHub.
 *
 * The legacy OracleObservation.adbConnected field now means "a privileged shell transport is
 * available" for schema compatibility; either Shizuku/Sui or Wireless ADB satisfies it.
 */
data class CollectedAgentObservation(
    val observation: OracleObservation,
    val uiXml: String,
    val processId: String,
    val visualWidth: Int,
    val visualHeight: Int,
)

class AgentObservationCollector(
    private val privileged: PrivilegedBridgeManager,
    private val screen: ScreenAgentController,
) {
    suspend fun collect(
        checkpoint: AgentCheckpoint,
        missionDeadlineEpochMs: Long,
        stepDeadlineEpochMs: Long,
        previousUiFingerprint: String,
        identicalUiSamples: Int,
        requiredUiChange: Boolean,
        userIntervened: Boolean = false,
        includeVisualStats: Boolean = true,
    ): CollectedAgentObservation {
        val now = System.currentTimeMillis()
        val connected = runCatching { privileged.ensureReady() }.getOrDefault(false)
        if (!connected) {
            return CollectedAgentObservation(
                observation = OracleObservation(
                    nowEpochMs = now,
                    missionDeadlineEpochMs = missionDeadlineEpochMs,
                    stepDeadlineEpochMs = stepDeadlineEpochMs,
                    controllerLeaseUntilEpochMs = checkpoint.controllerLeaseUntilEpochMs,
                    expectedPackage = checkpoint.targetPackage,
                    foregroundPackage = "",
                    targetProcessAlive = true,
                    adbConnected = false,
                    uiFingerprint = "",
                    previousUiFingerprint = previousUiFingerprint,
                    identicalUiSamples = identicalUiSamples,
                    requiredUiChange = requiredUiChange,
                    userIntervened = userIntervened,
                ),
                uiXml = "",
                processId = "",
                visualWidth = 0,
                visualHeight = 0,
            )
        }

        val target = safePackage(checkpoint.targetPackage)
        val pid = runCatching { privileged.execute("pidof $target", 6).output.trim() }.getOrDefault("")
        val processAlive = pid.split(Regex("\\s+")).any { it.matches(Regex("\\d+")) }
        val foreground = runCatching { screen.foregroundPackage() }.getOrDefault("")
        val snapshot = runCatching { screen.snapshot("watchdog-${sanitize(checkpoint.runId)}-${now}") }.getOrNull()
        val xml = snapshot?.output.orEmpty()
        val fingerprint = snapshot?.uiFingerprint.orEmpty()
        val dialogText = extractVisibleText(xml)
        val visual = if (includeVisualStats) collectVisualStats() else VisualStats(null, null, 0, 0)

        return CollectedAgentObservation(
            observation = OracleObservation(
                nowEpochMs = now,
                missionDeadlineEpochMs = missionDeadlineEpochMs,
                stepDeadlineEpochMs = stepDeadlineEpochMs,
                controllerLeaseUntilEpochMs = checkpoint.controllerLeaseUntilEpochMs,
                expectedPackage = checkpoint.targetPackage,
                foregroundPackage = foreground,
                targetProcessAlive = processAlive,
                adbConnected = true,
                uiFingerprint = fingerprint,
                previousUiFingerprint = previousUiFingerprint,
                identicalUiSamples = identicalUiSamples,
                requiredUiChange = requiredUiChange,
                screenMeanLuma = visual.meanLuma,
                screenLumaStdDev = visual.stdDev,
                systemDialogText = dialogText,
                userIntervened = userIntervened,
            ),
            uiXml = xml,
            processId = pid,
            visualWidth = visual.width,
            visualHeight = visual.height,
        )
    }

    private suspend fun collectVisualStats(): VisualStats = runCatching {
        val raw = privileged.executeRaw("screencap -p", timeoutSeconds = 10)
        if (raw.timedOut || raw.truncated || raw.bytes.size < 128) return@runCatching VisualStats(null, null, 0, 0)
        val bitmap = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size)
            ?: return@runCatching VisualStats(null, null, 0, 0)
        try {
            // Ignore the outside 15% where status/nav bars and camera chrome dominate. Sampling the
            // central 70% makes a black-viewfinder signal materially more useful without assuming a
            // particular camera UI layout.
            val left = (bitmap.width * 0.15).toInt().coerceIn(0, max(0, bitmap.width - 1))
            val right = (bitmap.width * 0.85).toInt().coerceIn(left + 1, bitmap.width)
            val top = (bitmap.height * 0.15).toInt().coerceIn(0, max(0, bitmap.height - 1))
            val bottom = (bitmap.height * 0.85).toInt().coerceIn(top + 1, bitmap.height)
            val area = max(1L, (right - left).toLong() * (bottom - top).toLong())
            val step = max(1, sqrt(area.toDouble() / 12_000.0).toInt())

            var count = 0L
            var sum = 0.0
            var sumSquares = 0.0
            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    val pixel = bitmap.getPixel(x, y)
                    val luma = 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
                    sum += luma
                    sumSquares += luma * luma
                    count++
                    x += step
                }
                y += step
            }
            if (count == 0L) return@runCatching VisualStats(null, null, bitmap.width, bitmap.height)
            val mean = sum / count
            val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
            VisualStats(mean, sqrt(variance), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }.getOrDefault(VisualStats(null, null, 0, 0))

    private fun extractVisibleText(xml: String): String {
        if (xml.isBlank()) return ""
        val attribute = Regex("(?:text|content-desc)=\"([^\"]+)\"")
        return attribute.findAll(xml)
            .map { decodeXml(it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(200)
            .joinToString("\n")
            .take(32_000)
    }

    private fun decodeXml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun safePackage(value: String): String {
        val trimmed = value.trim()
        require(trimmed.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { "Invalid target package." }
        return trimmed
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "run" }

    private data class VisualStats(
        val meanLuma: Double?,
        val stdDev: Double?,
        val width: Int,
        val height: Int,
    )
}
