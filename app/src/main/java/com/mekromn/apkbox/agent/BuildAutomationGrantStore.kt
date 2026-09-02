package com.mekromn.apkbox.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BuildAutomationGrant(
    val grantId: String,
    val targetPackage: String,
    val projectId: String,
    val expiresAtEpochMs: Long,
    val maxBuilds: Int,
    val remainingBuilds: Int,
    val allowDowngrade: Boolean,
    val consumedBuildIds: Set<String> = emptySet(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("grantId", grantId)
        .put("targetPackage", targetPackage)
        .put("projectId", projectId)
        .put("expiresAtEpochMs", expiresAtEpochMs)
        .put("maxBuilds", maxBuilds)
        .put("remainingBuilds", remainingBuilds)
        .put("allowDowngrade", allowDowngrade)
        .put("consumedBuildIds", JSONArray(consumedBuildIds.toList().sorted()))
        .put("createdAtEpochMs", createdAtEpochMs)

    companion object {
        fun fromJson(json: JSONObject): BuildAutomationGrant {
            val array = json.optJSONArray("consumedBuildIds") ?: JSONArray()
            val consumed = buildSet {
                for (index in 0 until array.length()) add(array.optString(index))
            }
            return BuildAutomationGrant(
                grantId = json.getString("grantId"),
                targetPackage = json.getString("targetPackage"),
                projectId = json.optString("projectId"),
                expiresAtEpochMs = json.getLong("expiresAtEpochMs"),
                maxBuilds = json.optInt("maxBuilds").coerceIn(1, 50),
                remainingBuilds = json.optInt("remainingBuilds").coerceIn(0, 50),
                allowDowngrade = json.optBoolean("allowDowngrade", false),
                consumedBuildIds = consumed,
                createdAtEpochMs = json.optLong("createdAtEpochMs"),
            )
        }
    }
}

data class BuildGrantDecision(
    val authorized: Boolean,
    val alreadyReserved: Boolean,
    val detail: String,
    val grant: BuildAutomationGrant? = null,
)

class BuildAutomationGrantStore(context: Context) {
    companion object {
        private val idRegex = Regex("[A-Za-z0-9._-]{1,96}")
        private val packageRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }

    private val root = File(context.applicationContext.filesDir, "apkbox-agent/build-automation").apply { mkdirs() }
    private val file = File(root, "grants.json")

    @Synchronized
    fun create(
        grantId: String,
        targetPackage: String,
        projectId: String,
        durationMinutes: Int,
        maxBuilds: Int,
        allowDowngrade: Boolean,
    ): BuildAutomationGrant {
        require(idRegex.matches(grantId)) { "Invalid build automation grant ID." }
        require(packageRegex.matches(targetPackage)) { "Invalid build automation package." }
        val duration = durationMinutes.coerceIn(5, 240)
        val max = maxBuilds.coerceIn(1, 50)
        val grant = BuildAutomationGrant(
            grantId = grantId,
            targetPackage = targetPackage,
            projectId = projectId.take(128),
            expiresAtEpochMs = System.currentTimeMillis() + duration * 60_000L,
            maxBuilds = max,
            remainingBuilds = max,
            allowDowngrade = allowDowngrade,
        )
        val grants = loadMutable().apply { put(grantId, grant) }
        save(grants)
        return grant
    }

    /**
     * Reserves authority before a build download/install starts. A repeated buildId is authorized
     * without decrementing the budget again, which lets a crashed BuildRunner resume the same
     * candidate but prevents a controller replay from expanding the approved build count.
     */
    @Synchronized
    fun authorizeAndReserve(grantId: String, candidate: BuildCandidate): BuildGrantDecision {
        val grants = loadMutable()
        val grant = grants[grantId]
            ?: return BuildGrantDecision(false, false, "Build automation grant '$grantId' does not exist.")
        val now = System.currentTimeMillis()
        if (now >= grant.expiresAtEpochMs) {
            grants.remove(grantId)
            save(grants)
            return BuildGrantDecision(false, false, "Build automation grant '$grantId' expired.")
        }
        if (grant.targetPackage != candidate.targetPackage) {
            return BuildGrantDecision(false, false, "Candidate package ${candidate.targetPackage} is outside this build grant.", grant)
        }
        if (grant.projectId.isNotBlank() && candidate.projectId != grant.projectId) {
            return BuildGrantDecision(false, false, "Candidate project does not match the approved APKbox project.", grant)
        }
        if (candidate.allowDowngrade && !grant.allowDowngrade) {
            return BuildGrantDecision(false, false, "Candidate requests downgrade authority that this session did not approve.", grant)
        }
        if (candidate.buildId in grant.consumedBuildIds) {
            return BuildGrantDecision(true, true, "Build ${candidate.buildId} was already reserved; resume is allowed without consuming another slot.", grant)
        }
        if (grant.remainingBuilds <= 0) {
            return BuildGrantDecision(false, false, "Build automation grant has exhausted its ${grant.maxBuilds}-build budget.", grant)
        }

        val reserved = grant.copy(
            remainingBuilds = grant.remainingBuilds - 1,
            consumedBuildIds = grant.consumedBuildIds + candidate.buildId,
        )
        grants[grantId] = reserved
        save(grants)
        return BuildGrantDecision(
            authorized = true,
            alreadyReserved = false,
            detail = "Reserved build ${candidate.buildId}; ${reserved.remainingBuilds} approved build slots remain.",
            grant = reserved,
        )
    }

    @Synchronized
    fun get(grantId: String): BuildAutomationGrant? {
        val grant = loadMutable()[grantId] ?: return null
        if (System.currentTimeMillis() >= grant.expiresAtEpochMs) {
            revoke(grantId)
            return null
        }
        return grant
    }

    @Synchronized
    fun revoke(grantId: String) {
        val grants = loadMutable()
        grants.remove(grantId)
        save(grants)
    }

    @Synchronized
    fun active(): List<BuildAutomationGrant> {
        val now = System.currentTimeMillis()
        val grants = loadMutable()
        val filtered = grants.filterValues { it.expiresAtEpochMs > now }
        if (filtered.size != grants.size) save(filtered.toMutableMap())
        return filtered.values.sortedBy { it.expiresAtEpochMs }
    }

    private fun loadMutable(): MutableMap<String, BuildAutomationGrant> = runCatching {
        if (!file.isFile) return@runCatching mutableMapOf()
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val grants = json.optJSONObject("grants") ?: JSONObject()
        grants.keys().asSequence().associateWith { key ->
            BuildAutomationGrant.fromJson(grants.getJSONObject(key))
        }.toMutableMap()
    }.getOrElse { mutableMapOf() }

    private fun save(grants: MutableMap<String, BuildAutomationGrant>) {
        val json = JSONObject().put("schema", 1).put(
            "grants",
            JSONObject().apply { grants.toSortedMap().forEach { (id, grant) -> put(id, grant.toJson()) } },
        )
        val temp = File(root, ".grants.json.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            temp.delete()
            error("Could not replace build automation grants.")
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
