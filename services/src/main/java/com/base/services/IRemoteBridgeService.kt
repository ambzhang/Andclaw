package com.base.services

import kotlinx.coroutines.flow.StateFlow

interface IRemoteBridgeService {
    val telegramStatus: StateFlow<BridgeStatus>
    val clawBotStatus: StateFlow<BridgeStatus>
    val clawBotLoginStatus: StateFlow<ClawBotLoginStatus>

    fun startEligibleBridges()
    fun stopAllBridges()
    fun startTelegramBridgeIfConfigured()
    fun stopTelegramBridge()
    fun startClawBotBridgeIfConfigured(forceRelogin: Boolean = false)

    /**
     * 由 [com.andforce.andclaw.AgentController] 在初始化时注册；收到 Telegram 文本消息后回调（IO 协程中执行）。
     */
    fun setTelegramInboundHandler(handler: suspend (TgInboundMessage) -> Unit) {}

    /**
     * 由 [com.andforce.andclaw.AgentController] 注册；收到 ClawBot 文本消息后在 IO 协程中回调。
     */
    fun setClawBotInboundHandler(handler: suspend (RemoteIncomingMessage) -> Unit) {}

    suspend fun requestClawBotQrCode(): ClawBotQrCodeResult
    suspend fun pollClawBotQrCodeStatus(qrcode: String): ClawBotQrPollResult

    suspend fun sendTyping(session: RemoteSession)
    suspend fun sendText(session: RemoteSession, text: String, replyHint: String? = null)
    suspend fun sendPhoto(
        session: RemoteSession,
        photoBytes: ByteArray,
        caption: String? = null,
        fileName: String = "photo.png"
    )
    suspend fun sendVideo(
        session: RemoteSession,
        videoBytes: ByteArray,
        caption: String? = null,
        fileName: String = "video.mp4"
    )
    suspend fun sendAudio(
        session: RemoteSession,
        audioBytes: ByteArray,
        caption: String? = null,
        fileName: String = "audio.m4a"
    )
}
