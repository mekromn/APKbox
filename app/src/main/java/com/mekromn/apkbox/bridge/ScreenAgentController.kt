package com.mekromn.apkbox.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

data class ScreenActionResult(
    val detail: String,
    val output: String = "",
    val foregroundPackage: String = "",
    val uiFingerprint: String = "",
    val localArtifact: LocalScreenArtifact? = null,
)

data class LocalScreenArtifact(
    val file: File,
    val mimeType: String,
    val sha256: String,
    val width: Int,
    val height: Int,
)

private data class UiNode(
    val text: String,
    val resourceId: String,
    val contentDescription: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

class ScreenAgentController(
    context: Context,
    private val adb: AdbBridgeManager,
) {
    companion object {
        const val LOCAL_ARTIFACT_PREFIX = "__APKBOX_LOCAL_ARTIFACT__:"
        private const val MAX_SCREENSHOT_EDGE = 1600
        private const val JPEG_QUALITY = 84
        private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val boundsRegex = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
        private val nodeRegex = Regex("<node\\s+([^>]*?)(?:/?>)")
        private val attributeRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    }

    private val appContext = context.applicationContext
    private val artifactDir = File(appContext.filesDir, "apkbox-bridge/artifacts").apply { mkdirs() }

    suspend fun foregroundPackage(): String {
        val output = adb.execute("dumpsys activity activities", 8).output
        val patterns = listOf(
            Regex("mResumedActivity:.*? ([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/"),
            Regex("topResumedActivity=.*? ([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/"),
            Regex("ResumedActivity:.*? ([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/"),
        )
        return patterns.firstNotNullOfOrNull { it.find(output)?.groupValues?.getOrNull(1) }.orEmpty()
    }

    suspend fun snapshot(requestId: String): ScreenActionResult {
        val safeId = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val remote = "/data/local/tmp/apkbox-ui-$safeId.xml"
        val shell = adb.execute(
            "uiautomator dump --compressed $remote >/dev/null 2>&1; cat $remote; rm -f $remote",
            15,
        )
        check(shell.exitCode == null || shell.exitCode == 0) { "UIAutomator dump failed with code ${shell.exitCode}." }
        val xml = shell.output.trim()
        check(xml.contains("<hierarchy")) { "UIAutomator did not return a hierarchy." }
        return ScreenActionResult(
            detail = "UI hierarchy captured.",
            output = xml,
            foregroundPackage = foregroundPackage(),
            uiFingerprint = sha256(xml.toByteArray(Charsets.UTF_8)),
        )
    }

    suspend fun screenshot(requestId: String): ScreenActionResult {
        val raw = adb.executeRaw("screencap -p", timeoutSeconds = 15)
        check(!raw.timedOut) { "Screenshot capture timed out." }
        check(!raw.truncated) { "Screenshot exceeded the raw capture safety limit." }
        check(raw.bytes.size > 128) { "Screenshot capture returned no image." }

        val decoded = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size)
            ?: error("Android screenshot was not a decodable image.")
        val scaled = scaleForRelay(decoded)
        if (scaled !== decoded) decoded.recycle()

        val output = ByteArrayOutputStream()
        try {
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Could not encode screenshot for the relay."
            }
        } finally {
            scaled.recycle()
        }
        val bytes = output.toByteArray()
        val safeId = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(artifactDir, "$safeId.jpg")
        val temp = File(artifactDir, ".$safeId.jpg.tmp")
        temp.writeBytes(bytes)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        val dimensions = BitmapFactory.Options().also { options ->
            options.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        val artifact = LocalScreenArtifact(
            file = file,
            mimeType = "image/jpeg",
            sha256 = sha256(bytes),
            width = dimensions.outWidth,
            height = dimensions.outHeight,
        )
        return ScreenActionResult(
            detail = "Screenshot captured and journaled for relay upload.",
            output = LOCAL_ARTIFACT_PREFIX + file.absolutePath,
            foregroundPackage = foregroundPackage(),
            localArtifact = artifact,
        )
    }

    suspend fun tap(packageName: String, x: Int, y: Int): ScreenActionResult {
        requireCoordinate(x, y)
        requireForeground(packageName)
        val before = snapshot("tap-before-${System.nanoTime()}").uiFingerprint
        val shell = adb.execute("input tap $x $y", 8)
        check(shell.exitCode == null || shell.exitCode == 0) { "Tap failed with code ${shell.exitCode}." }
        delay(180)
        val afterPackage = foregroundPackage()
        check(afterPackage == packageName) {
            "Foreground package changed from $packageName to ${afterPackage.ifBlank { "unknown" }} after tap; automation paused."
        }
        val after = snapshot("tap-after-${System.nanoTime()}")
        return ScreenActionResult(
            detail = if (before != after.uiFingerprint) "Tap completed; UI changed." else "Tap completed; UI fingerprint is unchanged.",
            foregroundPackage = afterPackage,
            uiFingerprint = after.uiFingerprint,
        )
    }

    suspend fun findAndTap(packageName: String, selector: String): ScreenActionResult {
        requireForeground(packageName)
        val before = snapshot("find-tap-before-${System.nanoTime()}")
        val node = findNode(before.output, selector)
            ?: error("No enabled UI node matched selector: $selector")
        check(node.right > node.left && node.bottom > node.top) { "Matched UI node has invalid bounds." }
        val shell = adb.execute("input tap ${node.centerX} ${node.centerY}", 8)
        check(shell.exitCode == null || shell.exitCode == 0) { "Semantic tap failed with code ${shell.exitCode}." }
        delay(180)
        val afterPackage = foregroundPackage()
        check(afterPackage == packageName) {
            "Foreground package changed from $packageName to ${afterPackage.ifBlank { "unknown" }} after semantic tap; automation paused."
        }
        val after = snapshot("find-tap-after-${System.nanoTime()}")
        return ScreenActionResult(
            detail = "Tapped selector '$selector' at ${node.centerX},${node.centerY}." +
                if (before.uiFingerprint == after.uiFingerprint) " UI fingerprint is unchanged." else " UI changed.",
            foregroundPackage = afterPackage,
            uiFingerprint = after.uiFingerprint,
        )
    }

    suspend fun swipe(
        packageName: String,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int,
    ): ScreenActionResult {
        requireCoordinate(startX, startY)
        requireCoordinate(endX, endY)
        requireForeground(packageName)
        val shell = adb.execute(
            "input swipe $startX $startY $endX $endY ${durationMs.coerceIn(1, 10_000)}",
            12,
        )
        check(shell.exitCode == null || shell.exitCode == 0) { "Swipe failed with code ${shell.exitCode}." }
        delay(220)
        val afterPackage = foregroundPackage()
        check(afterPackage == packageName) {
            "Foreground package changed from $packageName to ${afterPackage.ifBlank { "unknown" }} after swipe; automation paused."
        }
        val after = snapshot("swipe-after-${System.nanoTime()}")
        return ScreenActionResult(
            detail = "Swipe completed.",
            foregroundPackage = afterPackage,
            uiFingerprint = after.uiFingerprint,
        )
    }

    suspend fun typeText(packageName: String, value: String): ScreenActionResult {
        requireForeground(packageName)
        require(value.length <= 2_000) { "UI text is too long." }
        val encoded = value.replace("%", "%25").replace(" ", "%s")
        val shell = adb.execute("input text ${shellQuote(encoded)}", 12)
        check(shell.exitCode == null || shell.exitCode == 0) { "Text input failed with code ${shell.exitCode}." }
        delay(120)
        val afterPackage = foregroundPackage()
        check(afterPackage == packageName) {
            "Foreground package changed from $packageName to ${afterPackage.ifBlank { "unknown" }} after text input; automation paused."
        }
        val after = snapshot("text-after-${System.nanoTime()}")
        return ScreenActionResult(
            detail = "Text input completed.",
            foregroundPackage = afterPackage,
            uiFingerprint = after.uiFingerprint,
        )
    }

    suspend fun key(packageName: String, keyCode: Int): ScreenActionResult {
        require(keyCode in 0..400) { "Invalid Android key code." }
        requireForeground(packageName)
        val shell = adb.execute("input keyevent $keyCode", 8)
        check(shell.exitCode == null || shell.exitCode == 0) { "Key event failed with code ${shell.exitCode}." }
        delay(160)
        val afterPackage = foregroundPackage()
        return ScreenActionResult(
            detail = "Key event $keyCode completed.",
            foregroundPackage = afterPackage,
            uiFingerprint = if (afterPackage == packageName) snapshot("key-after-${System.nanoTime()}").uiFingerprint else "",
        )
    }

    suspend fun waitFor(packageName: String, selector: String, timeoutSeconds: Int): ScreenActionResult {
        require(packageRegex.matches(packageName)) { "Invalid package name." }
        val deadline = System.currentTimeMillis() + timeoutSeconds.coerceIn(1, 120) * 1_000L
        var lastFingerprint = ""
        while (System.currentTimeMillis() < deadline) {
            val foreground = foregroundPackage()
            check(foreground == packageName) {
                "Expected $packageName while waiting, but foreground is ${foreground.ifBlank { "unknown" }}."
            }
            val snapshot = snapshot("wait-${System.nanoTime()}")
            lastFingerprint = snapshot.uiFingerprint
            if (findNode(snapshot.output, selector) != null) {
                return ScreenActionResult(
                    detail = "Selector appeared: $selector",
                    output = snapshot.output,
                    foregroundPackage = foreground,
                    uiFingerprint = snapshot.uiFingerprint,
                )
            }
            delay(250)
        }
        error("Timed out waiting for selector '$selector'. Last UI fingerprint: $lastFingerprint")
    }

    fun deleteLocalArtifact(path: String) {
        val file = File(path)
        if (file.parentFile?.canonicalFile == artifactDir.canonicalFile) runCatching { file.delete() }
    }

    private suspend fun requireForeground(packageName: String) {
        require(packageRegex.matches(packageName)) { "Invalid target package name." }
        val foreground = foregroundPackage()
        check(foreground == packageName) {
            "Refusing UI action: expected foreground $packageName but found ${foreground.ifBlank { "unknown" }}."
        }
    }

    private fun findNode(xml: String, selector: String): UiNode? {
        val trimmed = selector.trim()
        require(trimmed.isNotBlank()) { "Selector is empty." }
        val mode = trimmed.substringBefore(':', "any").lowercase()
        val expected = if (':' in trimmed) trimmed.substringAfter(':') else trimmed
        val candidates = nodeRegex.findAll(xml).mapNotNull { match ->
            val attributes = attributeRegex.findAll(match.groupValues[1]).associate {
                it.groupValues[1] to xmlUnescape(it.groupValues[2])
            }
            val bounds = boundsRegex.find(attributes["bounds"].orEmpty()) ?: return@mapNotNull null
            UiNode(
                text = attributes["text"].orEmpty(),
                resourceId = attributes["resource-id"].orEmpty(),
                contentDescription = attributes["content-desc"].orEmpty(),
                clickable = attributes["clickable"] == "true",
                enabled = attributes["enabled"] != "false",
                left = bounds.groupValues[1].toInt(),
                top = bounds.groupValues[2].toInt(),
                right = bounds.groupValues[3].toInt(),
                bottom = bounds.groupValues[4].toInt(),
            )
        }.filter { it.enabled }.toList()

        fun matches(node: UiNode): Boolean = when (mode) {
            "id" -> node.resourceId == expected || node.resourceId.endsWith("/$expected")
            "text" -> node.text.equals(expected, ignoreCase = true)
            "desc", "description" -> node.contentDescription.equals(expected, ignoreCase = true)
            "contains" -> listOf(node.text, node.resourceId, node.contentDescription)
                .any { it.contains(expected, ignoreCase = true) }
            else -> listOf(node.text, node.resourceId, node.contentDescription)
                .any { it.equals(expected, ignoreCase = true) }
        }
        return candidates.filter(::matches).sortedWith(
            compareByDescending<UiNode> { it.clickable }
                .thenBy { max(1, it.right - it.left) * max(1, it.bottom - it.top) }
        ).firstOrNull()
    }

    private fun scaleForRelay(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= MAX_SCREENSHOT_EDGE) return source
        val scale = MAX_SCREENSHOT_EDGE.toDouble() / longest.toDouble()
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun requireCoordinate(x: Int, y: Int) {
        require(x in 0..20_000 && y in 0..20_000) { "Invalid screen coordinate." }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun xmlUnescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
