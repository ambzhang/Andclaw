package com.base.services

import kotlinx.coroutines.flow.StateFlow

enum class BridgeStatus {
    NOT_CONFIGURED,
    STOPPED,
    CONNECTED,
    DISCONNECTED
}

interface ITgBridgeService {
    val bridgeStatus: StateFlow<BridgeStatus>
    fun startBridge()
    fun stopBridge()
}
