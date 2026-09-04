package com.mekromn.apkbox.jobs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class DurableJobType {
    REMOTE_APK_INSTALL,
    BUILD_RUNNER,
    ARTIFACT_INGEST,
    GENERIC,
}

enum class DurableJobState {
    CREATED,
    RUNNING,
    PAUSED,
    CANCEL_REQUESTED,
    CANCELLED,
    INTERRUPTED,
    SUCCEEDED,
    FAILED,
}

data class DurableJob(
    val id: String,
    val type: DurableJobType,
    val state: DurableJobState,
    val stage: String = "CREATED",
    val detail: String = "",
    val requestId: String = "",
    val packageName: String = "",
    val projectId: String = "",
    val artifactSha256: String = "",
    val artifactPath: String = "",
    val progressBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val attempt: Int = 1,
    val resumable: Boolean = false,
    val cancellable: Boolean = true,
    val cancelRequested: Boolean = false,
    val payloadJson: String = "",
    val resultJson: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val startedAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val finishedAtEpochMs: Long = 0L,
) {
    val terminal: Boolean
        get() = state in setOf(DurableJobState.CANCELLED, DurableJobState.SUCCEEDED, DurableJobState.FAILED)

    fun toJson(): JSONObject = JSONObject()
        .put("schema", 1)
        .put("id", id)
        .put("type", type.name)
        .put("state", state.name)
        .put("stage", stage)
        .put("detail", detail)
        .put("requestId", requestId)
        .put("packageName", packageName)
        .put("projectId", projectId)
        .put("artifactSha256", artifactSha256)
        .put("artifactPath", artifactPath)
        .put("progressBytes", progressBytes)
        .put("totalBytes", totalBytes)
        .put("attempt", attempt)
        .put("resumable", resumable)
        .put("cancellable", cancellable)
        .put("cancelRequested", cancelRequested)
        .put("payloadJson", payloadJson)
        .put("resultJson", resultJson)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("startedAtEpochMs", startedAtEpochMs)
        .put("updatedAtEpochMs", updatedAtEpochMs)
        .put("finishedAtEpochMs", finishedAtEpochMs)

    companion object {
        private val idRegex = Regex("[A-Za-z0-9._-]{1,96}")

        fun requireValidId(value: String): String = value.trim().also {
            require(idRegex.matches(it)) { "Invalid job ID." }
        }

        fun fromJson(json: JSONObject): DurableJob = DurableJob(
            id = requireValidId(json.getString("id")),
            type = DurableJobType.valueOf(json.getString("type")),
            state = DurableJobState.valueOf(json.getString("state")),
            stage = json.optString("stage", "CREATED").take(128),
            detail = json.optString("detail").take(8_192),
            requestId = json.optString("requestId").take(96),
            packageName = json.optString("packageName").take(512),
            projectId = json.optString("projectId").take(128),
            artifactSha256 = json.optString("artifactSha256").take(64),
            artifactPath = json.optString("artifactPath").take(2_048),
            progressBytes = json.optLong("progressBytes", 0L).coerceAtLeast(0L),
            totalBytes = json.optLong("totalBytes", -1L),
            attempt = json.optInt("attempt", 1).coerceAtLeast(1),
            resumable = json.optBoolean("resumable", false),
            cancellable = json.optBoolean("cancellable", true),
            cancelRequested = json.optBoolean("cancelRequested", false),
            payloadJson = json.optString("payloadJson").take(128 * 1024),
            resultJson = json.optString("resultJson").take(128 * 1024),
            createdAtEpochMs = json.optLong("createdAtEpochMs", System.currentTimeMillis()),
            startedAtEpochMs = json.optLong("startedAtEpochMs", 0L),
            updatedAtEpochMs = json.optLong("updatedAtEpochMs", System.currentTimeMillis()),
            finishedAtEpochMs = json.optLong("finishedAtEpochMs", 0L),
        )
    }
}

class DurableJobStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "apkbox-jobs").apply { mkdirs() }
    private val lock = Any()

    fun load(jobId: String): DurableJob? = synchronized(lock) {
        val id = DurableJob.requireValidId(jobId)
        val file = jobFile(id)
        if (!file.isFile) return@synchronized null
        runCatching { DurableJob.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun list(): List<DurableJob> = synchronized(lock) {
        root.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { runCatching { DurableJob.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    fun save(job: DurableJob): DurableJob = synchronized(lock) {
        DurableJob.requireValidId(job.id)
        val target = jobFile(job.id)
        val temp = File(root, ".${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(job.toJson().toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) error("Could not replace durable job record.")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        job
    }

    fun update(jobId: String, block: (DurableJob) -> DurableJob): DurableJob = synchronized(lock) {
        val current = load(jobId) ?: error("Job '$jobId' does not exist.")
        save(block(current))
    }

    fun delete(jobId: String) = synchronized(lock) {
        jobFile(DurableJob.requireValidId(jobId)).delete()
    }

    private fun jobFile(id: String) = File(root, "$id.json")
}

/**
 * Shared durable lifecycle for long APKbox work. Subsystems own the actual operation, while this
 * engine owns crash-safe state/progress/cancel/resume semantics and one consistent remote view.
 */
class DurableJobEngine(context: Context) {
    private val store = DurableJobStore(context.applicationContext)

    init {
        // A process restart proves that an earlier RUNNING/CANCEL_REQUESTED owner vanished. Mark it
        // INTERRUPTED instead of pretending it is still active or replaying a mutation blindly.
        store.list().filter { it.state in setOf(DurableJobState.RUNNING, DurableJobState.CANCEL_REQUESTED) }
            .forEach { stale ->
                store.save(
                    stale.copy(
                        state = DurableJobState.INTERRUPTED,
                        stage = "INTERRUPTED",
                        detail = "APKbox process restarted while this job was active. Inspect status and explicitly resume when supported; APKbox will not replay it automatically.",
                        cancellable = false,
                        cancelRequested = false,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }
    }

    fun begin(
        jobId: String,
        type: DurableJobType,
        requestId: String = "",
        packageName: String = "",
        projectId: String = "",
        payloadJson: String = "",
        resumable: Boolean = true,
    ): DurableJob {
        val id = DurableJob.requireValidId(jobId)
        val now = System.currentTimeMillis()
        val existing = store.load(id)
        if (existing != null) return existing
        return store.save(
            DurableJob(
                id = id,
                type = type,
                state = DurableJobState.CREATED,
                stage = "CREATED",
                requestId = requestId.take(96),
                packageName = packageName.take(512),
                projectId = projectId.take(128),
                payloadJson = payloadJson.take(128 * 1024),
                resumable = resumable,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
        )
    }

    fun start(jobId: String, detail: String = "Job started."): DurableJob = store.update(jobId) { current ->
        val now = System.currentTimeMillis()
        current.copy(
            state = DurableJobState.RUNNING,
            stage = if (current.stage == "CREATED" || current.stage == "INTERRUPTED") "STARTING" else current.stage,
            detail = detail.take(8_192),
            startedAtEpochMs = current.startedAtEpochMs.takeIf { it > 0L } ?: now,
            updatedAtEpochMs = now,
            finishedAtEpochMs = 0L,
            cancelRequested = false,
        )
    }

    fun stage(
        jobId: String,
        stage: String,
        detail: String,
        cancellable: Boolean,
        resumable: Boolean? = null,
        packageName: String? = null,
        projectId: String? = null,
        artifactSha256: String? = null,
        artifactPath: String? = null,
    ): DurableJob = store.update(jobId) { current ->
        current.copy(
            state = if (current.state == DurableJobState.CANCEL_REQUESTED) DurableJobState.CANCEL_REQUESTED else DurableJobState.RUNNING,
            stage = stage.trim().uppercase().take(128),
            detail = detail.take(8_192),
            cancellable = cancellable,
            resumable = resumable ?: current.resumable,
            packageName = packageName?.take(512) ?: current.packageName,
            projectId = projectId?.take(128) ?: current.projectId,
            artifactSha256 = artifactSha256?.take(64) ?: current.artifactSha256,
            artifactPath = artifactPath?.take(2_048) ?: current.artifactPath,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    fun progress(jobId: String, bytes: Long, total: Long, detail: String? = null): DurableJob = store.update(jobId) { current ->
        current.copy(
            progressBytes = bytes.coerceAtLeast(0L),
            totalBytes = total,
            detail = detail?.take(8_192) ?: current.detail,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    fun requestCancel(jobId: String): DurableJob = store.update(jobId) { current ->
        when {
            current.terminal -> current
            !current.cancellable -> current.copy(
                detail = "Cancellation is not safe during stage ${current.stage}; request was not applied.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            else -> current.copy(
                state = DurableJobState.CANCEL_REQUESTED,
                cancelRequested = true,
                detail = "Cancellation requested; APKbox will stop at the next safe boundary.",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
    }

    fun isCancelRequested(jobId: String): Boolean = store.load(jobId)?.cancelRequested == true

    fun cancelled(jobId: String, detail: String = "Job cancelled at a safe boundary."): DurableJob = store.update(jobId) { current ->
        val now = System.currentTimeMillis()
        current.copy(
            state = DurableJobState.CANCELLED,
            stage = "CANCELLED",
            detail = detail.take(8_192),
            cancellable = false,
            cancelRequested = false,
            updatedAtEpochMs = now,
            finishedAtEpochMs = now,
        )
    }

    fun succeed(jobId: String, detail: String, resultJson: String = ""): DurableJob = store.update(jobId) { current ->
        val now = System.currentTimeMillis()
        current.copy(
            state = DurableJobState.SUCCEEDED,
            stage = "COMPLETE",
            detail = detail.take(8_192),
            resultJson = resultJson.take(128 * 1024),
            cancellable = false,
            cancelRequested = false,
            resumable = false,
            updatedAtEpochMs = now,
            finishedAtEpochMs = now,
        )
    }

    fun fail(jobId: String, detail: String, resumable: Boolean): DurableJob = store.update(jobId) { current ->
        val now = System.currentTimeMillis()
        current.copy(
            state = DurableJobState.FAILED,
            stage = "FAILED",
            detail = detail.take(8_192),
            cancellable = false,
            cancelRequested = false,
            resumable = resumable,
            updatedAtEpochMs = now,
            finishedAtEpochMs = now,
        )
    }

    fun prepareResume(jobId: String): DurableJob = store.update(jobId) { current ->
        require(current.resumable) { "Job '${current.id}' is not resumable." }
        require(current.state in setOf(DurableJobState.INTERRUPTED, DurableJobState.FAILED, DurableJobState.PAUSED)) {
            "Job '${current.id}' cannot resume from ${current.state}."
        }
        current.copy(
            state = DurableJobState.RUNNING,
            stage = "RESUMING",
            detail = "Explicit resume accepted.",
            attempt = current.attempt + 1,
            cancellable = true,
            cancelRequested = false,
            updatedAtEpochMs = System.currentTimeMillis(),
            finishedAtEpochMs = 0L,
        )
    }

    fun get(jobId: String): DurableJob? = store.load(jobId)

    fun list(limit: Int = 100): List<DurableJob> = store.list().take(limit.coerceIn(1, 500))

    fun statusJson(jobId: String): JSONObject = get(jobId)?.toJson() ?: error("Job '$jobId' was not found.")

    fun listJson(limit: Int = 100): JSONObject = JSONObject()
        .put("schema", 1)
        .put("jobs", JSONArray().apply { list(limit).forEach { put(it.toJson()) } })
}
