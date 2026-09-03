package com.mekromn.apkbox.install

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide status for the currently visible APKbox install transaction.
 *
 * This is intentionally presentation/status state rather than authority: PackageInstaller,
 * ReinstallCoordinator, archived SHA-256 verification, and Android itself remain the sources of
 * truth. Keeping one explicit state machine makes it obvious to the user where an install stopped
 * instead of reducing a multi-step reinstall to a transient toast/message string.
 */
enum class InstallFlowStage(val step: Int, val stepCount: Int = 8) {
    IDLE(0),
    READING_APK(1),
    READY(1),
    ARCHIVING_VERIFYING(2),
    ASSESSING_REINSTALL(3),
    WAITING_REINSTALL_CONFIRMATION(4),
    BACKING_UP_DATA(5),
    REQUESTING_UNINSTALL(5),
    WAITING_UNINSTALL_CONFIRMATION(5),
    VERIFYING_REMOVAL(6),
    STAGING_PACKAGE_INSTALLER(7),
    WAITING_PACKAGE_INSTALLER_CONFIRMATION(8),
    INSTALLING(8),
    RESTORING_DATA(8),
    COMPLETE(8),
    COMPLETE_WITH_WARNING(8),
    CANCELLED(8),
    FAILED(8),
    ;

    val terminal: Boolean
        get() = this in setOf(COMPLETE, COMPLETE_WITH_WARNING, CANCELLED, FAILED)
}

data class InstallFlowStatus(
    val stage: InstallFlowStage = InstallFlowStage.IDLE,
    val title: String = "",
    val detail: String = "",
    val packageName: String = "",
    val mode: String = "",
    val removalMethod: String = "",
    val dataPreservation: String = "",
    val updatedAtEpochMs: Long = 0L,
) {
    val visible: Boolean
        get() = stage != InstallFlowStage.IDLE && stage != InstallFlowStage.READY
}

object InstallFlowRuntime {
    private val _status = MutableStateFlow(InstallFlowStatus())
    val status: StateFlow<InstallFlowStatus> = _status.asStateFlow()

    fun update(
        stage: InstallFlowStage,
        title: String,
        detail: String = "",
        packageName: String? = null,
        mode: String? = null,
        removalMethod: String? = null,
        dataPreservation: String? = null,
    ) {
        val previous = _status.value
        _status.value = previous.copy(
            stage = stage,
            title = title.take(240),
            detail = detail.take(4_000),
            packageName = packageName?.take(240) ?: previous.packageName,
            mode = mode?.take(80) ?: previous.mode,
            removalMethod = removalMethod?.take(160) ?: previous.removalMethod,
            dataPreservation = dataPreservation?.take(240) ?: previous.dataPreservation,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    fun reset(packageName: String = "", mode: String = "") {
        _status.value = InstallFlowStatus(
            stage = InstallFlowStage.IDLE,
            packageName = packageName.take(240),
            mode = mode.take(80),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }
}
