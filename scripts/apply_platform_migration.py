from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(rel: str, old: str, new: str) -> None:
    path = ROOT / rel
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{rel}: expected exactly one match, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1))


# Remote URL installer: fastest exact local source before credentials/network.
remote = "app/src/main/java/com/mekromn/apkbox/bridge/RemoteApkInstallCoordinator.kt"
replace_once(
    remote,
    "import com.mekromn.apkbox.artifacts.ArtifactIngestor\nimport com.mekromn.apkbox.artifacts.ArtifactSpec\n",
    "import com.mekromn.apkbox.artifacts.ArtifactIngestor\nimport com.mekromn.apkbox.artifacts.ArtifactSourceResolver\nimport com.mekromn.apkbox.artifacts.ArtifactSpec\n",
)
replace_once(
    remote,
    "    private val jobs: DurableJobEngine,\n    private val artifacts: ArtifactIngestor,\n) {",
    "    private val jobs: DurableJobEngine,\n    private val artifacts: ArtifactIngestor,\n    private val resolver: ArtifactSourceResolver,\n) {",
)
replace_once(
    remote,
    "        if (request.requiresBuildToken && !artifacts.hasBuildToken()) {\n            return fail(\n                responseRequestId,\n                risk,\n                started,\n                jobId,\n                \"This APK URL requires the encrypted APKbox build-source token, but none is configured.\",\n                resumable = true,\n            )\n        }\n\n        val artifact = try {",
    "        val fastestLocal = if (expectedSha.isNotBlank()) {\n            runCatching { resolver.resolveExact(expectedSha, expectedPackage) }.getOrNull()\n        } else null\n        if (fastestLocal == null && request.requiresBuildToken && !artifacts.hasBuildToken()) {\n            return fail(\n                responseRequestId,\n                risk,\n                started,\n                jobId,\n                \"This APK URL requires the encrypted APKbox build-source token, but no faster exact local source was found and no token is configured.\",\n                resumable = true,\n            )\n        }\n\n        val artifact = try {",
)
replace_once(
    remote,
    "            val prior = jobs.get(jobId)\n            prior?.artifactSha256",
    "            val prior = jobs.get(jobId)\n            fastestLocal?.let { resolved ->\n                jobs.progress(jobId, resolved.sizeBytes, resolved.sizeBytes, \"Reused exact ${resolved.sourceKind.name.lowercase().replace('_', ' ')} source; network skipped.\")\n                IngestedArtifact(\n                    sha256 = resolved.sha256,\n                    sizeBytes = resolved.sizeBytes,\n                    file = resolved.file,\n                    sourceUrl = \"local://${resolved.sourceKind.name.lowercase()}\",\n                    resumedBytes = resolved.sizeBytes,\n                    cacheHit = true,\n                )\n            } ?: prior?.artifactSha256",
)
replace_once(
    remote,
    "            .put(\"artifactCacheHit\", artifact.cacheHit)\n",
    "            .put(\"artifactCacheHit\", artifact.cacheHit)\n            .put(\"artifactSource\", artifact.sourceUrl)\n",
)

# Build Runner: same fastest-source rule; only authenticate/download if local exact SHA is absent.
build = "app/src/main/java/com/mekromn/apkbox/agent/BuildRunner.kt"
replace_once(
    build,
    "import com.mekromn.apkbox.artifacts.ArtifactIngestor\n",
    "import com.mekromn.apkbox.artifacts.ArtifactIngestor\nimport com.mekromn.apkbox.artifacts.ArtifactSourceResolver\n",
)
replace_once(
    build,
    "    private val jobs: DurableJobEngine,\n    artifacts: ArtifactIngestor,\n) {",
    "    private val jobs: DurableJobEngine,\n    artifacts: ArtifactIngestor,\n    private val resolver: ArtifactSourceResolver,\n) {",
)
replace_once(
    build,
    "        if (candidate.requiresBuildToken && !credentials.hasToken()) {\n            return@withLock fail(\n                checkpoint,\n                BuildRunState.BLOCKED_AUTH_REQUIRED,\n                \"Private build source requires the encrypted read-only build-source token.\",\n                resumable = false,\n            )\n        }\n\n        val token = if (candidate.requiresBuildToken) credentials.readToken() else null\n",
    "        val fastestLocal = runCatching {\n            resolver.resolveExact(candidate.expectedApkSha256, candidate.targetPackage)\n        }.getOrNull()\n        if (fastestLocal == null && candidate.requiresBuildToken && !credentials.hasToken()) {\n            return@withLock fail(\n                checkpoint,\n                BuildRunState.BLOCKED_AUTH_REQUIRED,\n                \"Private build source requires the encrypted read-only build-source token because no exact local source is available.\",\n                resumable = false,\n            )\n        }\n\n        val token = if (fastestLocal == null && candidate.requiresBuildToken) credentials.readToken() else null\n",
)
replace_once(
    build,
    "        val apkFile = try {\n            downloader.obtainApk(\n",
    "        val apkFile = try {\n            if (fastestLocal != null) {\n                jobs.stage(\n                    candidate.runId,\n                    \"LOCAL_EXACT_SOURCE\",\n                    \"Reusing exact ${fastestLocal.sourceKind.name.lowercase().replace('_', ' ')} source; network download skipped.\",\n                    cancellable = true,\n                    resumable = true,\n                    artifactSha256 = fastestLocal.sha256,\n                    artifactPath = fastestLocal.file.absolutePath,\n                )\n                fastestLocal.file\n            } else {\n                downloader.obtainApk(\n",
)
replace_once(
    build,
    "                isCancelled = { jobs.isCancelRequested(candidate.runId) },\n            )\n        } catch (cancelled: ArtifactCancelledException) {",
    "                    isCancelled = { jobs.isCancelRequested(candidate.runId) },\n                )\n            }\n        } catch (cancelled: ArtifactCancelledException) {",
)

# Bridge executor: route universal jobs/inventory/APK retrieval through shared services.
executor = "app/src/main/java/com/mekromn/apkbox/bridge/BridgeExecutor.kt"
replace_once(
    executor,
    "import com.mekromn.apkbox.agent.AgentActionLedger\n",
    "import com.mekromn.apkbox.agent.AgentActionLedger\nimport com.mekromn.apkbox.jobs.DurableJobType\n",
)
replace_once(
    executor,
    "    private val remoteApkInstaller by lazy {\n        RemoteApkInstallCoordinator(\n            context = appContext,\n            library = ApkBoxServices.libraryStore(appContext),\n            privileged = privileged,\n        )\n    }\n",
    "    private val remoteApkInstaller by lazy { ApkBoxServices.remoteApkInstaller(appContext) }\n    private val inventory by lazy { ApkBoxServices.inventoryCoordinator(appContext) }\n    private val retrieval by lazy { ApkBoxServices.apkRetrievalCoordinator(appContext) }\n    private val jobs by lazy { ApkBoxServices.durableJobs(appContext) }\n",
)
replace_once(
    executor,
    "                BridgeCommandType.APK_INSTALL_URL -> remoteApkInstaller.execute(request, risk)\n\n                BridgeCommandType.LOGCAT -> executeShell(",
    "                BridgeCommandType.APK_INSTALL_URL -> remoteApkInstaller.execute(request, risk)\n                BridgeCommandType.PROJECT_LIST,\n                BridgeCommandType.PROJECT_GET,\n                BridgeCommandType.APK_LIST,\n                BridgeCommandType.APK_SEARCH,\n                BridgeCommandType.PACKAGE_STATE,\n                BridgeCommandType.INSTALLED_APPS,\n                BridgeCommandType.DEVICE_STATE,\n                BridgeCommandType.JOB_LIST,\n                BridgeCommandType.JOB_STATUS -> inventory.execute(request, risk)\n                BridgeCommandType.APK_INSPECT -> retrieval.inspect(request, risk)\n                BridgeCommandType.APK_PULL -> executeApkPull(request, risk)\n                BridgeCommandType.JOB_CANCEL,\n                BridgeCommandType.JOB_RESUME -> executeJobControl(request, risk)\n\n                BridgeCommandType.LOGCAT -> executeShell(",
)
replace_once(
    executor,
    "    private suspend fun executeAdvanced(request: BridgeRequest, risk: BridgeRisk): BridgeResult {",
    '''    private suspend fun executeApkPull(request: BridgeRequest, risk: BridgeRisk): BridgeResult {
        val config = prefs.state.value
        check(config.enabled) { "Remote Debug Bridge is not enabled." }
        val token = prefs.relayToken()
        check(token.isNotBlank()) { "Continuity relay token is not configured." }
        return retrieval.pull(request, risk, config, token)
    }

    private suspend fun executeJobControl(request: BridgeRequest, risk: BridgeRisk): BridgeResult {
        val started = System.currentTimeMillis()
        val job = jobs.get(request.jobId) ?: return BridgeResult(
            requestId = request.id,
            status = BridgeResultStatus.INVALID,
            risk = risk,
            detail = "Job '${request.jobId}' was not found.",
            durationMs = System.currentTimeMillis() - started,
        )
        return when (request.type) {
            BridgeCommandType.JOB_CANCEL -> {
                val updated = jobs.requestCancel(request.jobId)
                BridgeResult(
                    requestId = request.id,
                    status = BridgeResultStatus.SUCCESS,
                    risk = risk,
                    detail = updated.detail,
                    output = updated.toJson().toString(2),
                    durationMs = System.currentTimeMillis() - started,
                )
            }
            BridgeCommandType.JOB_RESUME -> when (job.type) {
                DurableJobType.BUILD_RUNNER -> {
                    val checkpoint = ApkBoxServices.buildRunner(appContext).resumeJob(request.jobId)
                    BridgeResult(
                        requestId = request.id,
                        status = BridgeResultStatus.SUCCESS,
                        risk = risk,
                        detail = checkpoint.detail,
                        output = checkpoint.toJson().toString(2),
                        durationMs = System.currentTimeMillis() - started,
                    )
                }
                DurableJobType.REMOTE_APK_INSTALL -> remoteApkInstaller.resume(request.jobId, request, risk)
                DurableJobType.APK_PULL -> {
                    val config = prefs.state.value
                    check(config.enabled) { "Remote Debug Bridge is not enabled." }
                    val token = prefs.relayToken()
                    check(token.isNotBlank()) { "Continuity relay token is not configured." }
                    retrieval.resumePull(request, risk, config, token)
                }
                DurableJobType.ARTIFACT_INGEST,
                DurableJobType.GENERIC -> BridgeResult(
                    requestId = request.id,
                    status = BridgeResultStatus.INVALID,
                    risk = risk,
                    detail = "Job '${request.jobId}' of type ${job.type} has no executable resume adapter.",
                    durationMs = System.currentTimeMillis() - started,
                )
            }
            else -> error("${request.type} is not a job-control command.")
        }
    }

    private suspend fun executeAdvanced(request: BridgeRequest, risk: BridgeRisk): BridgeResult {''',
)

# Approval Activity: show exact IDs and complete summaries.
approval = "app/src/main/java/com/mekromn/apkbox/bridge/BridgeApprovalActivity.kt"
replace_once(
    approval,
    "            if (request.type in setOf(BridgeCommandType.BUILD_START, BridgeCommandType.BUILD_STATUS)) {\n                InfoSection(\"Build ID\", request.buildId.ifBlank { \"Not supplied\" })\n                InfoSection(\"Run ID\", request.runId.ifBlank { \"Not supplied\" })\n            }\n",
    "            if (request.type in setOf(BridgeCommandType.BUILD_START, BridgeCommandType.BUILD_STATUS)) {\n                InfoSection(\"Build ID\", request.buildId.ifBlank { \"Not supplied\" })\n                InfoSection(\"Run ID\", request.runId.ifBlank { \"Not supplied\" })\n            }\n            if (request.jobId.isNotBlank()) InfoSection(\"Job ID\", request.jobId)\n            if (request.apkRecordId.isNotBlank()) InfoSection(\"APKbox record ID\", request.apkRecordId)\n",
)
replace_once(
    approval,
    "                    request.type == BridgeCommandType.APK_INSTALL_URL ->\n                        \"This downloads the complete APK before installation, computes SHA-256, verifies package/SHA constraints, optionally archives the exact bytes, unattended-installs through Shizuku/Sui or Wireless ADB, and verifies installed base.apk SHA-256. It always requires a fresh approval. Signature-conflicting installed apps are never silently removed.\"\n",
    "                    request.type == BridgeCommandType.APK_INSTALL_URL ->\n                        \"This resolves the fastest exact local source first when an expected SHA is known, otherwise downloads the complete APK, verifies it, optionally archives it, unattended-installs it, and verifies installed base.apk SHA-256. It always requires fresh approval.\"\n                    request.type == BridgeCommandType.JOB_RESUME ->\n                        \"Resuming a durable job may continue a package mutation, so it always requires fresh approval and uses the persisted original job payload.\"\n",
)
replace_once(
    approval,
    "    BridgeCommandType.APK_INSTALL_URL -> \"Install APK\"\n",
    "    BridgeCommandType.APK_INSTALL_URL -> \"Install APK\"\n    BridgeCommandType.JOB_CANCEL -> \"Cancel job\"\n    BridgeCommandType.JOB_RESUME -> \"Resume job\"\n",
)
replace_once(
    approval,
    "    BridgeCommandType.APK_INSTALL_URL -> \"Download and unattended-install an APK directly from an HTTPS URL${if (request.saveToProject) \", saving the exact verified APK to an APKbox project\" else \"\"}\"\n",
    "    BridgeCommandType.APK_INSTALL_URL -> \"Resolve/download and unattended-install an exact APK${if (request.saveToProject) \", saving it to an APKbox project\" else \"\"}\"\n    BridgeCommandType.JOB_LIST -> \"List APKbox durable jobs\"\n    BridgeCommandType.JOB_STATUS -> \"Read durable job '${request.jobId}'\"\n    BridgeCommandType.JOB_CANCEL -> \"Cancel durable job '${request.jobId}' at its next safe boundary\"\n    BridgeCommandType.JOB_RESUME -> \"Resume durable job '${request.jobId}' from its persisted operation\"\n    BridgeCommandType.PROJECT_LIST -> \"List APKbox projects\"\n    BridgeCommandType.PROJECT_GET -> \"Read APKbox project '${request.projectId}' and its records\"\n    BridgeCommandType.APK_LIST -> \"List stored APK records\"\n    BridgeCommandType.APK_SEARCH -> \"Search stored APKs for '${request.query}'\"\n    BridgeCommandType.APK_INSPECT -> \"Inspect exact APKbox record '${request.apkRecordId}' without pulling the full file\"\n    BridgeCommandType.APK_PULL -> \"Pull exact APKbox record '${request.apkRecordId}' into verified private Continuity chunks\"\n    BridgeCommandType.PACKAGE_STATE -> \"Inspect installed/stored state for ${request.packageName}\"\n    BridgeCommandType.INSTALLED_APPS -> \"List installed Android apps\"\n    BridgeCommandType.DEVICE_STATE -> \"Read structured APKbox/device/transport state\"\n",
)

# Approval overlay: include IDs and complete summaries/buttons.
overlay = "app/src/main/java/com/mekromn/apkbox/bridge/BridgeApprovalOverlayController.kt"
replace_once(
    overlay,
    "            pending.request.buildId.takeIf { it.isNotBlank() }?.let { append(\"\\n\\nBuild ID\\n\").append(it) }\n",
    "            pending.request.buildId.takeIf { it.isNotBlank() }?.let { append(\"\\n\\nBuild ID\\n\").append(it) }\n            pending.request.jobId.takeIf { it.isNotBlank() }?.let { append(\"\\n\\nJob ID\\n\").append(it) }\n            pending.request.apkRecordId.takeIf { it.isNotBlank() }?.let { append(\"\\n\\nAPKbox record ID\\n\").append(it) }\n",
)
replace_once(
    overlay,
    "                    BridgeCommandType.APK_INSTALL_URL -> \"Install APK\"\n                    else -> \"Allow once\"\n",
    "                    BridgeCommandType.APK_INSTALL_URL -> \"Install APK\"\n                    BridgeCommandType.JOB_CANCEL -> \"Cancel job\"\n                    BridgeCommandType.JOB_RESUME -> \"Resume job\"\n                    else -> \"Allow once\"\n",
)
replace_once(
    overlay,
    "        pending.request.type == BridgeCommandType.APK_INSTALL_URL ->\n            \"Direct URL installation always requires a fresh approval. APKbox downloads and hashes the full APK before unattended install, verifies installed bytes afterward, and never silently removes a signature-conflicting app.\"\n",
    "        pending.request.type == BridgeCommandType.APK_INSTALL_URL ->\n            \"Direct APK installation always requires fresh approval; APKbox prefers a proven faster exact local source before network retrieval.\"\n        pending.request.type == BridgeCommandType.JOB_RESUME ->\n            \"Durable job resume always requires fresh approval because the persisted job may continue a device mutation.\"\n",
)
replace_once(
    overlay,
    "        BridgeCommandType.APK_INSTALL_URL -> \"Download and unattended-install an APK from HTTPS${if (request.saveToProject) \", saving the exact APK to APKbox\" else \"\"}\"\n",
    "        BridgeCommandType.APK_INSTALL_URL -> \"Resolve/download and unattended-install an exact APK${if (request.saveToProject) \", saving it to APKbox\" else \"\"}\"\n        BridgeCommandType.JOB_LIST -> \"List durable jobs\"\n        BridgeCommandType.JOB_STATUS -> \"Read durable job '${request.jobId}'\"\n        BridgeCommandType.JOB_CANCEL -> \"Cancel durable job '${request.jobId}' at a safe boundary\"\n        BridgeCommandType.JOB_RESUME -> \"Resume durable job '${request.jobId}'\"\n        BridgeCommandType.PROJECT_LIST -> \"List APKbox projects\"\n        BridgeCommandType.PROJECT_GET -> \"Read APKbox project '${request.projectId}'\"\n        BridgeCommandType.APK_LIST -> \"List stored APKs\"\n        BridgeCommandType.APK_SEARCH -> \"Search APKbox for '${request.query}'\"\n        BridgeCommandType.APK_INSPECT -> \"Inspect exact APKbox record '${request.apkRecordId}'\"\n        BridgeCommandType.APK_PULL -> \"Pull exact APKbox record '${request.apkRecordId}' to private Continuity chunks\"\n        BridgeCommandType.PACKAGE_STATE -> \"Inspect package state for ${request.packageName}\"\n        BridgeCommandType.INSTALLED_APPS -> \"List installed Android apps\"\n        BridgeCommandType.DEVICE_STATE -> \"Read structured device state\"\n",
)

# RemoteBridgeService: readable notifications and better interruption recovery hints.
service = "app/src/main/java/com/mekromn/apkbox/bridge/RemoteBridgeService.kt"
replace_once(
    service,
    "                        pending.request.buildId.takeIf { it.isNotBlank() }?.let {\n                            append(\"\\nBuild ID: \").append(it)\n                        }\n",
    "                        pending.request.buildId.takeIf { it.isNotBlank() }?.let {\n                            append(\"\\nBuild ID: \").append(it)\n                        }\n                        pending.request.jobId.takeIf { it.isNotBlank() }?.let { append(\"\\nJob ID: \").append(it) }\n                        pending.request.apkRecordId.takeIf { it.isNotBlank() }?.let { append(\"\\nAPK record: \").append(it) }\n",
)
replace_once(
    service,
    "            .addAction(0, if (pending.request.type == BridgeCommandType.APK_INSTALL_URL) \"Install APK\" else \"Allow once\", allowOnce)\n",
    "            .addAction(0, when (pending.request.type) {\n                BridgeCommandType.APK_INSTALL_URL -> \"Install APK\"\n                BridgeCommandType.JOB_CANCEL -> \"Cancel job\"\n                BridgeCommandType.JOB_RESUME -> \"Resume job\"\n                else -> \"Allow once\"\n            }, allowOnce)\n",
)
replace_once(
    service,
    "        BridgeCommandType.APK_INSTALL_URL -> \"Download and unattended-install APK from ${request.downloadUrl.take(150)}${if (request.saveToProject) \" and save it to APKbox\" else \"\"}\"\n",
    "        BridgeCommandType.APK_INSTALL_URL -> \"Resolve/download and unattended-install APK from ${request.downloadUrl.take(150)}${if (request.saveToProject) \" and save it to APKbox\" else \"\"}\"\n        BridgeCommandType.JOB_LIST -> \"List durable APKbox jobs\"\n        BridgeCommandType.JOB_STATUS -> \"Read job ${request.jobId}\"\n        BridgeCommandType.JOB_CANCEL -> \"Cancel job ${request.jobId} at a safe boundary\"\n        BridgeCommandType.JOB_RESUME -> \"Resume job ${request.jobId}\"\n        BridgeCommandType.PROJECT_LIST -> \"List APKbox projects\"\n        BridgeCommandType.PROJECT_GET -> \"Read project ${request.projectId}\"\n        BridgeCommandType.APK_LIST -> \"List stored APK records\"\n        BridgeCommandType.APK_SEARCH -> \"Search APKbox for '${request.query}'\"\n        BridgeCommandType.APK_INSPECT -> \"Inspect exact APK record ${request.apkRecordId}\"\n        BridgeCommandType.APK_PULL -> \"Pull exact APK record ${request.apkRecordId}\"\n        BridgeCommandType.PACKAGE_STATE -> \"Inspect package state for ${request.packageName}\"\n        BridgeCommandType.INSTALLED_APPS -> \"List installed apps\"\n        BridgeCommandType.DEVICE_STATE -> \"Read structured device state\"\n",
)
replace_once(
    service,
    "                BridgeCommandType.APK_INSTALL_URL ->\n                    \"A direct APK install may have downloaded, archived, or installed before interruption. Inspect the installed package/project state and never blindly replay this request ID. A new request should use a new ID after state is confirmed.\"\n",
    "                BridgeCommandType.APK_INSTALL_URL -> {\n                    val job = inFlight.request.jobId.ifBlank { inFlight.request.id }\n                    \"Use JOB_STATUS for '$job'; if the durable job is resumable, use a fresh JOB_RESUME request instead of repeating APK_INSTALL_URL.\"\n                }\n                BridgeCommandType.APK_PULL -> {\n                    val job = inFlight.request.jobId.ifBlank { inFlight.request.id }\n                    \"Use JOB_STATUS for '$job'; APK pull progress is chunk-boundary durable and can resume with JOB_RESUME.\"\n                }\n                BridgeCommandType.JOB_RESUME ->\n                    \"Use JOB_STATUS for '${inFlight.request.jobId}' before deciding whether another resume is needed.\"\n",
)

print("Platform migration patch applied successfully.")
