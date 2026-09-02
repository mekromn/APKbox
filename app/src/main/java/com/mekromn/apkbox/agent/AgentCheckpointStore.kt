package com.mekromn.apkbox.agent

import android.content.Context
import org.json.JSONObject
import java.io.File

class AgentCheckpointStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "apkbox-agent").apply { mkdirs() }
    private val runsDir = File(root, "runs").apply { mkdirs() }
    private val transcriptsDir = File(root, "transcripts").apply { mkdirs() }

    @Synchronized
    fun saveCheckpoint(checkpoint: AgentCheckpoint) {
        atomicWrite(runFile(checkpoint.runId, "checkpoint.json"), checkpoint.toJson().toString())
    }

    @Synchronized
    fun loadCheckpoint(runId: String): AgentCheckpoint? = runCatching {
        val file = runFile(runId, "checkpoint.json")
        if (!file.isFile) null else AgentCheckpoint.fromJson(JSONObject(file.readText()))
    }.getOrNull()

    @Synchronized
    fun savePlan(plan: AutonomousPlan) {
        atomicWrite(runFile(plan.runId, "plan.json"), plan.toJson().toString())
    }

    @Synchronized
    fun loadPlan(runId: String): AutonomousPlan? = runCatching {
        val file = runFile(runId, "plan.json")
        if (!file.isFile) null else AutonomousPlan.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
    }.getOrNull()

    @Synchronized
    fun saveHandoff(checkpoint: ChatHandoffCheckpoint) {
        atomicWrite(runFile(checkpoint.runId, "handoff.json"), checkpoint.toJson().toString())
    }

    @Synchronized
    fun loadHandoff(runId: String): ChatHandoffCheckpoint? = runCatching {
        val file = runFile(runId, "handoff.json")
        if (!file.isFile) null else ChatHandoffCheckpoint.fromJson(JSONObject(file.readText()))
    }.getOrNull()

    @Synchronized
    fun transcriptFile(runId: String, requestedName: String): File {
        val safeRun = sanitize(runId)
        val safeName = requestedName
            .ifBlank { "ChatGPT-$safeRun.md" }
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .take(160)
            .let { if (it.endsWith(".md", true)) it else "$it.md" }
        return File(transcriptsDir, "$safeRun-$safeName")
    }

    @Synchronized
    fun writeTranscript(runId: String, requestedName: String, markdown: String): File {
        require(markdown.isNotBlank()) { "Refusing to save an empty ChatGPT transcript." }
        val target = transcriptFile(runId, requestedName)
        atomicWrite(target, markdown)
        return target
    }

    @Synchronized
    fun clearRun(runId: String, keepTranscript: Boolean = true) {
        val dir = File(runsDir, sanitize(runId))
        dir.deleteRecursively()
        if (!keepTranscript) {
            val prefix = sanitize(runId) + "-"
            transcriptsDir.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.forEach { it.delete() }
        }
    }

    fun listRecoverableRuns(): List<AgentCheckpoint> = runsDir.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isDirectory }
        .mapNotNull { dir ->
            runCatching {
                val file = File(dir, "checkpoint.json")
                if (!file.isFile) null else AgentCheckpoint.fromJson(JSONObject(file.readText()))
            }.getOrNull()
        }
        .filter { checkpoint ->
            checkpoint.state !in setOf(AgentRunState.COMPLETED, AgentRunState.FAILED)
        }
        .sortedByDescending { it.updatedAtEpochMs }
        .toList()

    private fun runFile(runId: String, name: String): File {
        val dir = File(runsDir, sanitize(runId)).apply { mkdirs() }
        return File(dir, name)
    }

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
