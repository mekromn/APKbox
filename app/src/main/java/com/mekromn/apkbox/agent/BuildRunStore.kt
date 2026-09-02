package com.mekromn.apkbox.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

class BuildRunStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "apkbox-agent/build-runs").apply { mkdirs() }

    @Synchronized
    fun saveCandidate(candidate: BuildCandidate) {
        atomicWrite(file(candidate.runId, "candidate.json"), candidate.toJson().toString())
    }

    @Synchronized
    fun loadCandidate(runId: String): BuildCandidate? = runCatching {
        val target = file(runId, "candidate.json")
        if (!target.isFile) null else BuildCandidate.fromJson(JSONObject(target.readText(Charsets.UTF_8)))
    }.getOrNull()

    @Synchronized
    fun saveCheckpoint(checkpoint: BuildRunCheckpoint) {
        atomicWrite(file(checkpoint.runId, "checkpoint.json"), checkpoint.toJson().toString())
    }

    @Synchronized
    fun loadCheckpoint(runId: String): BuildRunCheckpoint? = runCatching {
        val target = file(runId, "checkpoint.json")
        if (!target.isFile) null else BuildRunCheckpoint.fromJson(JSONObject(target.readText(Charsets.UTF_8)))
    }.getOrNull()

    fun sourcePartFile(runId: String): File = file(runId, "source.download.part")
    fun sourceFile(runId: String, format: BuildSourceFormat): File =
        file(runId, if (format == BuildSourceFormat.APK) "candidate.apk" else "artifact.zip")
    fun extractedApkFile(runId: String): File = file(runId, "candidate.apk")

    @Synchronized
    fun clearWorkspace(runId: String, keepCheckpoint: Boolean = true) {
        val dir = runDirectory(runId)
        if (!dir.isDirectory) return
        dir.listFiles().orEmpty().forEach { child ->
            if (keepCheckpoint && child.name in setOf("candidate.json", "checkpoint.json")) return@forEach
            if (child.isDirectory) child.deleteRecursively() else child.delete()
        }
    }

    fun listRecoverable(): List<BuildRunCheckpoint> = root.listFiles().orEmpty()
        .asSequence()
        .filter { it.isDirectory }
        .mapNotNull { dir ->
            runCatching {
                val target = File(dir, "checkpoint.json")
                if (!target.isFile) null else BuildRunCheckpoint.fromJson(JSONObject(target.readText(Charsets.UTF_8)))
            }.getOrNull()
        }
        .filter { it.state !in setOf(BuildRunState.PASSED, BuildRunState.FAILED) }
        .sortedByDescending { it.updatedAtEpochMs }
        .toList()

    private fun file(runId: String, name: String): File = File(runDirectory(runId), name)

    private fun runDirectory(runId: String): File = File(root, sanitize(runId)).apply { mkdirs() }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "run" }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace ${target.name}.")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}
