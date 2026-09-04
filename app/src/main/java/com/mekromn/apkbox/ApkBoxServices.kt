package com.mekromn.apkbox

import android.content.Context
import com.mekromn.apkbox.agent.AutonomousPlanRunner
import com.mekromn.apkbox.agent.BuildRunner
import com.mekromn.apkbox.artifacts.ArtifactIngestor
import com.mekromn.apkbox.artifacts.ArtifactSourceResolver
import com.mekromn.apkbox.bridge.AdbBridgeManager
import com.mekromn.apkbox.bridge.ApkRetrievalCoordinator
import com.mekromn.apkbox.bridge.BridgeExecutor
import com.mekromn.apkbox.bridge.BridgeInventoryCoordinator
import com.mekromn.apkbox.bridge.BridgePreferences
import com.mekromn.apkbox.bridge.BridgeStateStore
import com.mekromn.apkbox.bridge.GitHubRelayClient
import com.mekromn.apkbox.bridge.PrivilegedBridgeManager
import com.mekromn.apkbox.bridge.RemoteApkInstallCoordinator
import com.mekromn.apkbox.bridge.ShizukuBridgeManager
import com.mekromn.apkbox.data.AutoScanManager
import com.mekromn.apkbox.data.LibraryStore
import com.mekromn.apkbox.jobs.DurableJobEngine

/**
 * Process-local service graph so UI, broadcast receivers, background scanners, build automation,
 * and the remote bridge share one writer/connection and one set of StateFlows. Master restore only
 * resets services that retain vault references; Shizuku/Sui authorization, bridge identity, durable
 * jobs/artifacts and ADB pairing deliberately live independently of the APK vault.
 */
object ApkBoxServices {
    private val lock = Any()

    @Volatile private var libraryStoreInstance: LibraryStore? = null
    @Volatile private var autoScanManagerInstance: AutoScanManager? = null
    @Volatile private var bridgePreferencesInstance: BridgePreferences? = null
    @Volatile private var bridgeStateStoreInstance: BridgeStateStore? = null
    @Volatile private var adbBridgeManagerInstance: AdbBridgeManager? = null
    @Volatile private var shizukuBridgeManagerInstance: ShizukuBridgeManager? = null
    @Volatile private var privilegedBridgeManagerInstance: PrivilegedBridgeManager? = null
    @Volatile private var relayClientInstance: GitHubRelayClient? = null
    @Volatile private var bridgeExecutorInstance: BridgeExecutor? = null
    @Volatile private var autonomousPlanRunnerInstance: AutonomousPlanRunner? = null
    @Volatile private var buildRunnerInstance: BuildRunner? = null
    @Volatile private var durableJobEngineInstance: DurableJobEngine? = null
    @Volatile private var artifactIngestorInstance: ArtifactIngestor? = null
    @Volatile private var artifactSourceResolverInstance: ArtifactSourceResolver? = null
    @Volatile private var remoteApkInstallInstance: RemoteApkInstallCoordinator? = null
    @Volatile private var inventoryCoordinatorInstance: BridgeInventoryCoordinator? = null
    @Volatile private var apkRetrievalCoordinatorInstance: ApkRetrievalCoordinator? = null

    fun libraryStore(context: Context): LibraryStore =
        libraryStoreInstance ?: synchronized(lock) {
            libraryStoreInstance ?: LibraryStore(context.applicationContext).also {
                libraryStoreInstance = it
            }
        }

    fun autoScanner(context: Context): AutoScanManager =
        autoScanManagerInstance ?: synchronized(lock) {
            autoScanManagerInstance ?: AutoScanManager(
                context = context.applicationContext,
                libraryStore = libraryStore(context.applicationContext),
            ).also {
                autoScanManagerInstance = it
            }
        }

    fun bridgePreferences(context: Context): BridgePreferences =
        bridgePreferencesInstance ?: synchronized(lock) {
            bridgePreferencesInstance ?: BridgePreferences(context.applicationContext).also {
                bridgePreferencesInstance = it
            }
        }

    fun bridgeStateStore(context: Context): BridgeStateStore =
        bridgeStateStoreInstance ?: synchronized(lock) {
            bridgeStateStoreInstance ?: BridgeStateStore(context.applicationContext).also {
                bridgeStateStoreInstance = it
            }
        }

    /** ADB-specific surface retained for pairing and Wireless ADB self-heal. */
    fun adbBridge(context: Context): AdbBridgeManager =
        adbBridgeManagerInstance ?: synchronized(lock) {
            adbBridgeManagerInstance ?: AdbBridgeManager(context.applicationContext).also {
                adbBridgeManagerInstance = it
            }
        }

    fun shizukuBridge(context: Context): ShizukuBridgeManager =
        shizukuBridgeManagerInstance ?: synchronized(lock) {
            shizukuBridgeManagerInstance ?: ShizukuBridgeManager(context.applicationContext).also {
                shizukuBridgeManagerInstance = it
            }
        }

    /** Preferred feature surface: Shizuku/Sui first, Wireless ADB peer fallback. */
    fun privilegedBridge(context: Context): PrivilegedBridgeManager =
        privilegedBridgeManagerInstance ?: synchronized(lock) {
            privilegedBridgeManagerInstance ?: PrivilegedBridgeManager(
                context = context.applicationContext,
                adb = adbBridge(context.applicationContext),
                shizuku = shizukuBridge(context.applicationContext),
            ).also { privilegedBridgeManagerInstance = it }
        }

    fun existingPrivilegedBridge(): PrivilegedBridgeManager? = privilegedBridgeManagerInstance

    fun durableJobs(context: Context): DurableJobEngine =
        durableJobEngineInstance ?: synchronized(lock) {
            durableJobEngineInstance ?: DurableJobEngine(context.applicationContext).also {
                durableJobEngineInstance = it
            }
        }

    fun artifacts(context: Context): ArtifactIngestor =
        artifactIngestorInstance ?: synchronized(lock) {
            artifactIngestorInstance ?: ArtifactIngestor(context.applicationContext).also {
                artifactIngestorInstance = it
            }
        }

    fun artifactSourceResolver(context: Context): ArtifactSourceResolver =
        artifactSourceResolverInstance ?: synchronized(lock) {
            artifactSourceResolverInstance ?: ArtifactSourceResolver(
                context = context.applicationContext,
                artifacts = artifacts(context.applicationContext),
                library = libraryStore(context.applicationContext),
                jobs = durableJobs(context.applicationContext),
                privileged = privilegedBridge(context.applicationContext),
            ).also { artifactSourceResolverInstance = it }
        }

    fun relayClient(): GitHubRelayClient =
        relayClientInstance ?: synchronized(lock) {
            relayClientInstance ?: GitHubRelayClient().also { relayClientInstance = it }
        }

    fun remoteApkInstaller(context: Context): RemoteApkInstallCoordinator =
        remoteApkInstallInstance ?: synchronized(lock) {
            remoteApkInstallInstance ?: RemoteApkInstallCoordinator(
                context = context.applicationContext,
                library = libraryStore(context.applicationContext),
                privileged = privilegedBridge(context.applicationContext),
                jobs = durableJobs(context.applicationContext),
                artifacts = artifacts(context.applicationContext),
                resolver = artifactSourceResolver(context.applicationContext),
            ).also { remoteApkInstallInstance = it }
        }

    fun inventoryCoordinator(context: Context): BridgeInventoryCoordinator =
        inventoryCoordinatorInstance ?: synchronized(lock) {
            inventoryCoordinatorInstance ?: BridgeInventoryCoordinator(
                context = context.applicationContext,
                library = libraryStore(context.applicationContext),
                privileged = privilegedBridge(context.applicationContext),
                jobs = durableJobs(context.applicationContext),
            ).also { inventoryCoordinatorInstance = it }
        }

    fun apkRetrievalCoordinator(context: Context): ApkRetrievalCoordinator =
        apkRetrievalCoordinatorInstance ?: synchronized(lock) {
            apkRetrievalCoordinatorInstance ?: ApkRetrievalCoordinator(
                context = context.applicationContext,
                library = libraryStore(context.applicationContext),
                privileged = privilegedBridge(context.applicationContext),
                jobs = durableJobs(context.applicationContext),
                resolver = artifactSourceResolver(context.applicationContext),
                relay = relayClient(),
            ).also { apkRetrievalCoordinatorInstance = it }
        }

    fun bridgeExecutor(context: Context): BridgeExecutor =
        bridgeExecutorInstance ?: synchronized(lock) {
            bridgeExecutorInstance ?: BridgeExecutor(
                context = context.applicationContext,
                privileged = privilegedBridge(context.applicationContext),
                stateStore = bridgeStateStore(context.applicationContext),
            ).also { bridgeExecutorInstance = it }
        }

    fun autonomousPlanRunner(context: Context): AutonomousPlanRunner =
        autonomousPlanRunnerInstance ?: synchronized(lock) {
            autonomousPlanRunnerInstance ?: AutonomousPlanRunner(
                context = context.applicationContext,
                privileged = privilegedBridge(context.applicationContext),
                executor = bridgeExecutor(context.applicationContext),
            ).also { autonomousPlanRunnerInstance = it }
        }

    fun buildRunner(context: Context): BuildRunner =
        buildRunnerInstance ?: synchronized(lock) {
            buildRunnerInstance ?: BuildRunner(
                context = context.applicationContext,
                library = libraryStore(context.applicationContext),
                privileged = privilegedBridge(context.applicationContext),
                jobs = durableJobs(context.applicationContext),
                artifacts = artifacts(context.applicationContext),
                resolver = artifactSourceResolver(context.applicationContext),
            ).also { buildRunnerInstance = it }
        }

    fun resetVaultServices() {
        synchronized(lock) {
            autoScanManagerInstance?.shutdown()
            autoScanManagerInstance = null
            buildRunnerInstance = null
            remoteApkInstallInstance = null
            inventoryCoordinatorInstance = null
            apkRetrievalCoordinatorInstance = null
            artifactSourceResolverInstance = null
            libraryStoreInstance = null
        }
    }
}
