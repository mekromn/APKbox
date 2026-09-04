from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(rel: str, old: str, new: str) -> None:
    path = ROOT / rel
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{rel}: expected one match, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


# Build Runner: only JOB_RESUME may continue a failed/interrupted/paused run ID.
build = "app/src/main/java/com/mekromn/apkbox/agent/BuildRunner.kt"
replace_once(
    build,
    "    suspend fun run(candidate: BuildCandidate): BuildRunCheckpoint = mutex.withLock {",
    "    suspend fun run(candidate: BuildCandidate): BuildRunCheckpoint = runInternal(candidate, explicitResume = false)\n\n    private suspend fun runInternal(candidate: BuildCandidate, explicitResume: Boolean): BuildRunCheckpoint = mutex.withLock {",
)
replace_once(
    build,
    "        when {\n            durable.state in setOf(DurableJobState.INTERRUPTED, DurableJobState.FAILED, DurableJobState.PAUSED) && durable.resumable ->\n                jobs.prepareResume(candidate.runId)\n            durable.state == DurableJobState.SUCCEEDED -> Unit\n            else -> jobs.start(candidate.runId, \"Build Runner started candidate ${candidate.buildId}.\")\n        }",
    '''        when (durable.state) {
            DurableJobState.INTERRUPTED,
            DurableJobState.FAILED,
            DurableJobState.PAUSED -> {
                require(explicitResume) {
                    "Build job '${candidate.runId}' already exists in ${durable.state}. Use JOB_RESUME instead of repeating BUILD_START."
                }
                require(durable.resumable) { "Build job '${candidate.runId}' is not resumable; use a new runId." }
                jobs.prepareResume(candidate.runId)
            }
            DurableJobState.CANCELLED -> error("Build job '${candidate.runId}' was cancelled and is terminal; use a new runId.")
            DurableJobState.RUNNING,
            DurableJobState.CANCEL_REQUESTED -> error("Build job '${candidate.runId}' is already active in ${durable.state}.")
            DurableJobState.SUCCEEDED -> Unit
            DurableJobState.CREATED -> {
                require(!explicitResume) { "Build job '${candidate.runId}' has not started and cannot be resumed." }
                jobs.start(candidate.runId, "Build Runner started candidate ${candidate.buildId}.")
            }
        }''',
)
replace_once(
    build,
    "        return run(candidate)\n    }",
    "        return runInternal(candidate, explicitResume = true)\n    }",
)

# Direct URL deploy: same job identity rule.
remote = "app/src/main/java/com/mekromn/apkbox/bridge/RemoteApkInstallCoordinator.kt"
replace_once(
    remote,
    '''        if (existing.state == DurableJobState.SUCCEEDED && existing.resultJson.isNotBlank()) {
            return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.SUCCESS,
                risk = risk,
                detail = "Remote APK job '$jobId' had already completed successfully.",
                output = existing.resultJson,
            )
        }
        if (existing.state == DurableJobState.RUNNING && existing.requestId != request.id) {
            return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Job '$jobId' is already running under request ${existing.requestId}.",
            )
        }
        jobs.start(jobId, "Remote APK deployment started.")
        return runTransaction(request, request.id, risk, jobId)''',
    '''        when (existing.state) {
            DurableJobState.SUCCEEDED -> return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.SUCCESS,
                risk = risk,
                detail = "Remote APK job '$jobId' had already completed successfully.",
                output = existing.resultJson,
            )
            DurableJobState.INTERRUPTED,
            DurableJobState.FAILED,
            DurableJobState.PAUSED -> return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Remote APK job '$jobId' already exists in ${existing.state}. Use JOB_RESUME when resumable; do not repeat APK_INSTALL_URL.",
            )
            DurableJobState.CANCELLED -> return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Remote APK job '$jobId' was cancelled and is terminal. Use a new jobId.",
            )
            DurableJobState.RUNNING,
            DurableJobState.CANCEL_REQUESTED -> return BridgeResult(
                requestId = request.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Remote APK job '$jobId' is already active in ${existing.state}.",
            )
            DurableJobState.CREATED -> jobs.start(jobId, "Remote APK deployment started.")
        }
        return runTransaction(request, request.id, risk, jobId)''',
)
replace_once(
    remote,
    '''        if (job.state == DurableJobState.SUCCEEDED) {
            return BridgeResult(
                requestId = controllerRequest.id,
                status = BridgeResultStatus.SUCCESS,
                risk = risk,
                detail = "Job '$jobId' is already complete.",
                output = job.resultJson,
            )
        }
        val original = runCatching { BridgeRequest.fromJson(JSONObject(job.payloadJson)) }.getOrElse { failure ->''',
    '''        if (job.state == DurableJobState.SUCCEEDED) {
            return BridgeResult(
                requestId = controllerRequest.id,
                status = BridgeResultStatus.SUCCESS,
                risk = risk,
                detail = "Job '$jobId' is already complete.",
                output = job.resultJson,
            )
        }
        if (job.state == DurableJobState.CANCELLED) {
            return BridgeResult(
                requestId = controllerRequest.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Job '$jobId' was cancelled and is terminal; use a new jobId.",
            )
        }
        if (job.state !in setOf(DurableJobState.INTERRUPTED, DurableJobState.FAILED, DurableJobState.PAUSED) || !job.resumable) {
            return BridgeResult(
                requestId = controllerRequest.id,
                status = BridgeResultStatus.INVALID,
                risk = risk,
                detail = "Job '$jobId' cannot resume from ${job.state} (resumable=${job.resumable}).",
            )
        }
        val original = runCatching { BridgeRequest.fromJson(JSONObject(job.payloadJson)) }.getOrElse { failure ->''',
)

# APK pull: distinguish first start from explicit JOB_RESUME and preserve terminal identity.
retrieval = "app/src/main/java/com/mekromn/apkbox/bridge/ApkRetrievalCoordinator.kt"
replace_once(
    retrieval,
    "        token = token,\n    )\n\n    suspend fun resumePull(",
    "        token = token,\n        explicitResume = false,\n    )\n\n    suspend fun resumePull(",
)
replace_once(
    retrieval,
    "        return runPull(request.id, request.jobId, recordId, risk, config, token)\n    }",
    "        return runPull(request.id, request.jobId, recordId, risk, config, token, explicitResume = true)\n    }",
)
replace_once(
    retrieval,
    "        token: String,\n    ): BridgeResult {",
    "        token: String,\n        explicitResume: Boolean,\n    ): BridgeResult {",
)
replace_once(
    retrieval,
    '''            if (initial.state == DurableJobState.SUCCEEDED && initial.resultJson.isNotBlank()) {
                return@runCatching BridgeResult(
                    requestId = resultRequestId,
                    status = BridgeResultStatus.SUCCESS,
                    risk = risk,
                    detail = "APK pull job '$jobId' is already complete; returning its verified transfer manifest.",
                    output = initial.resultJson,
                    durationMs = System.currentTimeMillis() - started,
                )
            }
            if (initial.state in setOf(DurableJobState.INTERRUPTED, DurableJobState.FAILED, DurableJobState.PAUSED)) {
                jobs.prepareResume(jobId)
            } else {
                jobs.start(jobId, "Resolving fastest exact source for ${record.displayName}.")
            }''',
    '''            when (initial.state) {
                DurableJobState.SUCCEEDED -> return@runCatching BridgeResult(
                    requestId = resultRequestId,
                    status = BridgeResultStatus.SUCCESS,
                    risk = risk,
                    detail = "APK pull job '$jobId' is already complete; returning its verified transfer manifest.",
                    output = initial.resultJson,
                    durationMs = System.currentTimeMillis() - started,
                )
                DurableJobState.INTERRUPTED,
                DurableJobState.FAILED,
                DurableJobState.PAUSED -> {
                    require(explicitResume) { "APK pull job '$jobId' already exists in ${initial.state}. Use JOB_RESUME." }
                    require(initial.resumable) { "APK pull job '$jobId' is not resumable; use a new jobId." }
                    jobs.prepareResume(jobId)
                }
                DurableJobState.CANCELLED -> error("APK pull job '$jobId' was cancelled and is terminal; use a new jobId.")
                DurableJobState.RUNNING,
                DurableJobState.CANCEL_REQUESTED -> error("APK pull job '$jobId' is already active in ${initial.state}.")
                DurableJobState.CREATED -> {
                    require(!explicitResume) { "APK pull job '$jobId' has not started and cannot be resumed." }
                    jobs.start(jobId, "Resolving fastest exact source for ${record.displayName}.")
                }
            }''',
)
replace_once(
    retrieval,
    "        var compressedBytes = 0L\n        var uncompressedBytes = 0L\n        val nativeLibraries = mutableListOf<String>()",
    "        var compressedBytes = 0L\n        var uncompressedBytes = 0L\n        var nativeLibraryCount = 0\n        val nativeLibraries = mutableListOf<String>()",
)
replace_once(
    retrieval,
    "                if (libMatch != null) {\n                    abis += libMatch.groupValues[1]\n                    if (nativeLibraries.size < limit) nativeLibraries += name\n                }",
    "                if (libMatch != null) {\n                    nativeLibraryCount++\n                    abis += libMatch.groupValues[1]\n                    if (nativeLibraries.size < limit) nativeLibraries += name\n                }",
)
replace_once(
    retrieval,
    ".put(\"nativeLibraryCount\", nativeLibraries.size)\n",
    ".put(\"nativeLibraryCount\", nativeLibraryCount)\n",
)

# BridgeModels tests: ensure new universal fields and required identities cannot drift.
models_test = "app/src/test/java/com/mekromn/apkbox/bridge/BridgeModelsTest.kt"
replace_once(
    models_test,
    "    @Test\n    fun invalidAdvancedIdsAndImageTraversalAreRejectedDuringParsing() {",
    '''    @Test
    fun universalJobInventoryAndApkRetrievalFieldsRoundTrip() {
        val original = BridgeRequest(
            id = "apk-pull-001",
            type = BridgeCommandType.APK_PULL,
            jobId = "pull-job-42",
            apkRecordId = "record-42",
            projectId = "project-42",
            packageName = "com.example.app",
            query = "candidate",
            limit = 321,
            includeSystemApps = true,
            reason = "Pull exact stored APK",
            createdAtEpochMs = 1000L,
            expiresAtEpochMs = 2000L,
        )
        assertEquals(original, BridgeRequest.fromJson(original.toJson()))
    }

    @Test
    fun jobAndExactApkCommandsRequireStableIds() {
        listOf("JOB_STATUS", "JOB_CANCEL", "JOB_RESUME").forEach { type ->
            val json = JSONObject().put("id", "job-required-$type").put("type", type)
            assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(json) }
        }
        listOf("APK_INSPECT", "APK_PULL").forEach { type ->
            val json = JSONObject().put("id", "record-required-$type").put("type", type)
            assertThrows(IllegalArgumentException::class.java) { BridgeRequest.fromJson(json) }
        }
    }

    @Test
    fun invalidAdvancedIdsAndImageTraversalAreRejectedDuringParsing() {''',
)

# BridgePolicy tests: pin risk model for the new platform surface.
policy_test = "app/src/test/java/com/mekromn/apkbox/bridge/BridgePolicyTest.kt"
replace_once(
    policy_test,
    "    @Test\n    fun obviousReadOnlyShellMayUseTrustedSession() {",
    '''    @Test
    fun universalInventoryAndApkRetrievalAreReadOnly() {
        val types = listOf(
            BridgeCommandType.JOB_LIST,
            BridgeCommandType.JOB_STATUS,
            BridgeCommandType.PROJECT_LIST,
            BridgeCommandType.PROJECT_GET,
            BridgeCommandType.APK_LIST,
            BridgeCommandType.APK_SEARCH,
            BridgeCommandType.APK_INSPECT,
            BridgeCommandType.APK_PULL,
            BridgeCommandType.PACKAGE_STATE,
            BridgeCommandType.INSTALLED_APPS,
            BridgeCommandType.DEVICE_STATE,
        )
        types.forEach { type -> assertEquals("$type must stay read-only", BridgeRisk.READ_ONLY, BridgePolicy.classify(request(type))) }
    }

    @Test
    fun jobCancelIsScopedDebugButResumeIsAlwaysMutating() {
        val cancel = request(BridgeCommandType.JOB_CANCEL, jobId = "job-42")
        assertEquals(BridgeRisk.DEBUG_ACTION, BridgePolicy.classify(cancel))
        assertTrue(BridgePolicy.trustedSessionEligible(cancel))
        assertTrue(BridgePolicy.mayAutoExecute(cancel, trustedUntil, true, true, now))

        val resume = request(BridgeCommandType.JOB_RESUME, jobId = "job-42")
        assertEquals(BridgeRisk.MUTATING, BridgePolicy.classify(resume))
        assertFalse(BridgePolicy.trustedSessionEligible(resume))
        assertFalse(BridgePolicy.mayAutoExecute(resume, trustedUntil, true, true, now))
    }

    @Test
    fun obviousReadOnlyShellMayUseTrustedSession() {''',
)
replace_once(
    policy_test,
    "        buildId: String = \"\",\n        sequenceNumber: Long = 0L,\n",
    "        buildId: String = \"\",\n        jobId: String = \"\",\n        sequenceNumber: Long = 0L,\n",
)
replace_once(
    policy_test,
    "        buildId = buildId,\n        sequenceNumber = sequenceNumber,\n",
    "        buildId = buildId,\n        jobId = jobId,\n        sequenceNumber = sequenceNumber,\n",
)

print("Platform hardening patch applied successfully.")
