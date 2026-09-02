package com.mekromn.apkbox.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BridgeRuntimeStatus(
    val running: Boolean = false,
    val relayReachable: Boolean = false,
    val lastPollEpochMs: Long = 0L,
    val lastHeartbeatEpochMs: Long = 0L,
    val pendingRequestId: String = "",
    val lastError: String = "",
)

object BridgeRuntime {
    private val _status = MutableStateFlow(BridgeRuntimeStatus())
    val status: StateFlow<BridgeRuntimeStatus> = _status.asStateFlow()

    fun update(block: (BridgeRuntimeStatus) -> BridgeRuntimeStatus) {
        _status.value = block(_status.value)
    }

    fun reset() {
        _status.value = BridgeRuntimeStatus()
    }
}
