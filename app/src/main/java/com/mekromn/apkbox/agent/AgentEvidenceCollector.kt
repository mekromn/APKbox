package com.mekromn.apkbox.agent

import android.content.Context
import com.mekromn.apkbox.bridge.PrivilegedBridgeManager
import com.mekromn.apkbox.bridge.ScreenAgentController
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Captures a failure before recovery changes the device state. Every bundle is immutable once the
 * final zip is published: collection happens in a private staging directory, then the zip is
 * atomically renamed into place and SHA-256 addressed in the returned manifest.
 */
data class EvidenceBundle(
    val runId: String,
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
    val signal: OracleSignal,
    val capturedAtEpochMs: Long,
)

class AgentEvidenceCollector(
    context: Context,
    private val privileged: PrivilegedBridgeManager,
    private val screen: ScreenAgentController,
    private val checkpointStore: AgentCheckpointStore,
) {
    private val root = File(context.applicationContext.filesDir, "apkbox-agent/evidence").apply { mkdirs() }

    suspend fun capture(
        checkpoint: AgentCheckpoint,
        decision: OracleDecision,
    ): EvidenceBundle {
        require(decision.captureEvidence) { "Oracle decision does not request evidence capture." }
        val capturedAt = System.currentTimeMillis()
        val safeRun = sanitize(checkpoint.runId)
        val stamp = capturedAt.toString()
        val staging = File(root, ".$safeRun-$stamp-staging").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            writeText(staging, "checkpoint.json", checkpoint.toJson().toString(2))
            writeText(
                staging,
                "oracle.json",
                JSONObject()
                    .put("schema", 1)
                    .put("signal", decision.signal.name)
                    .put("detail", decision.detail)
                    .put("terminalForStep", decision.terminalForStep)
                    .put("mayRetry", decision.mayRetry)
                    .put("capturedAtEpochMs", capturedAt)
                    .put("privilegedTransport", privileged.activeTransport().name)
                    .toString(2),
            )

            val foreground = runCatching { screen.foregroundPackage() }.getOrDefault("")
            writeText(staging, "foreground.txt", foreground)

            val snapshot = runCatching { screen.snapshot("evidence-$safeRun-$stamp") }.getOrNull()
            if (snapshot != null) {
                writeText(staging, "ui.xml", snapshot.output)
                writeText(staging, "ui.sha256", snapshot.uiFingerprint)
            }

            val screenshot = runCatching { screen.screenshot("evidence-$safeRun-$stamp") }.getOrNull()
            if (screenshot?.localArtifact != null) {
                screenshot.localArtifact.file.copyTo(File(staging, "screen.jpg"), overwrite = true)
                writeText(
                    staging,
                    "screen.json",
                    JSONObject()
                        .put("sha256", screenshot.localArtifact.sha256)
                        .put("width", screenshot.localArtifact.width)
                        .put("height", screenshot.localArtifact.height)
                        .put("mimeType", screenshot.localArtifact.mimeType)
                        .toString(2),
                )
                screen.deleteLocalArtifact(screenshot.localArtifact.file.absolutePath)
            }

            val pkg = checkpoint.targetPackage.trim()
            val pid = if (pkg.isNotBlank()) shellText("pidof ${shellToken(pkg)}", 8).trim() else ""
            writeText(staging, "pid.txt", pid)

            if (pkg.isNotBlank()) {
                writeText(staging, "package.txt", shellText("dumpsys package ${shellToken(pkg)}", 15))
                writeText(staging, "meminfo.txt", shellText("dumpsys meminfo ${shellToken(pkg)}", 15))
                if (pid.matches(Regex("\\d+"))) {
                    writeText(
                        staging,
                        "logcat.txt",
                        shellText("logcat --pid=$pid -d -v threadtime -t 10000", 20),
                    )
                } else {
                    writeText(
                        staging,
                        "logcat.txt",
                        shellText("logcat -d -v threadtime -t 10000", 20),
                    )
                }
            }

            collect(staging, "activity.txt", "dumpsys activity activities", 15)
            collect(staging, "window.txt", "dumpsys window windows", 15)
            collect(staging, "power.txt", "dumpsys power", 12)
            collect(staging, "battery.txt", "dumpsys battery", 10)
            collect(staging, "thermal.txt", "dumpsys thermalservice", 10)
            collect(staging, "display.txt", "dumpsys display", 12)
            collect(staging, "processes.txt", "ps -A -o USER,PID,PPID,NAME,ARGS", 12)
            collect(staging, "storage.txt", "df -h /data /storage/emulated/0", 10)
            collect(staging, "device.txt", "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.fingerprint; getprop ro.build.version.release; getprop ro.build.version.sdk", 10)
            collect(staging, "wm.txt", "wm size; wm density", 8)
            collect(staging, "uptime.txt", "uptime", 8)

            val checkpointOnDisk = checkpointStore.loadCheckpoint(checkpoint.runId)
            if (checkpointOnDisk != null) {
                writeText(staging, "checkpoint-persisted.json", checkpointOnDisk.toJson().toString(2))
            }

            val manifest = JSONObject()
                .put("schema", 2)
                .put("runId", checkpoint.runId)
                .put("targetPackage", checkpoint.targetPackage)
                .put("signal", decision.signal.name)
                .put("detail", decision.detail)
                .put("capturedAtEpochMs", capturedAt)
                .put("buildLabel", checkpoint.buildLabel)
                .put("buildSha256", checkpoint.buildSha256)
                .put("stepIndex", checkpoint.stepIndex)
                .put("stepName", checkpoint.stepName)
                .put("lastAction", checkpoint.lastAction)
                .put("lastResult", checkpoint.lastResult)
                .put("foregroundPackage", foreground)
                .put("uiFingerprint", snapshot?.uiFingerprint.orEmpty())
                .put("privilegedTransport", privileged.activeTransport().name)
            writeText(staging, "manifest.json", manifest.toString(2))

            val target = File(root, "$safeRun-$stamp.zip")
            val temp = File(root, ".$safeRun-$stamp.zip.tmp")
            zipDirectory(staging, temp)
            if (target.exists() && !target.delete()) error("Could not replace evidence bundle.")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            val sha = sha256(target)
            return EvidenceBundle(
                runId = checkpoint.runId,
                file = target,
                sha256 = sha,
                sizeBytes = target.length(),
                signal = decision.signal,
                capturedAtEpochMs = capturedAt,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun collect(directory: File, name: String, command: String, timeoutSeconds: Int) {
        writeText(directory, name, shellText(command, timeoutSeconds))
    }

    private suspend fun shellText(command: String, timeoutSeconds: Int): String = runCatching {
        val result = privileged.execute(command, timeoutSeconds)
        buildString {
            append(result.output)
            if (result.timedOut) append("\n[APKbox: command timed out]")
            if (result.truncated) append("\n[APKbox: output truncated]")
            result.exitCode?.let { append("\n[APKbox exitCode=").append(it).append(']') }
        }
    }.getOrElse { failure ->
        "[APKbox evidence collector error: ${failure.message ?: failure.javaClass.simpleName}]"
    }

    private fun writeText(directory: File, name: String, value: String) {
        File(directory, name).writeText(value, Charsets.UTF_8)
    }

    private fun zipDirectory(directory: File, target: File) {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            directory.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
                .forEach { file ->
                    val relative = file.relativeTo(directory).invariantSeparatorsPath
                    val entry = ZipEntry(relative).apply { time = 0L }
                    zip.putNextEntry(entry)
                    FileInputStream(file).use { input -> input.copyTo(zip, 128 * 1024) }
                    zip.closeEntry()
                }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shellToken(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_.:-]+"))) { "Unsafe shell token." }
        return value
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "run" }
}
