package com.andforce.andclaw.bridge

import com.base.services.IRemoteBridgeService
import com.base.services.RemoteChannel
import com.base.services.RemoteSession

/**
 * 统一远程出站：通过 [IRemoteBridgeService]。
 * Telegram 走 [RemoteBridgeManager] 内 [TelegramBridge]；ClawBot 文本走 [com.andforce.andclaw.bridge.clawbot.ClawBotBridge]，
 * 图片/音视频无协议实现时由 [RemoteBridgeManager] 发降级说明文字。
 */
object RemoteOutboundHelper {

    fun telegramChatIdOrNull(session: RemoteSession): Long? {
        if (session.channel != RemoteChannel.TELEGRAM) return null
        return session.sessionKey.toLongOrNull()
    }

    fun shouldAttemptRemoteEcho(role: String, session: RemoteSession?): Boolean =
        role != "user" && session != null

    suspend fun sendTyping(
        bridge: IRemoteBridgeService,
        session: RemoteSession?,
    ) {
        if (session == null) return
        bridge.sendTyping(session)
    }

    suspend fun sendText(
        bridge: IRemoteBridgeService,
        session: RemoteSession?,
        text: String,
        replyToMessageId: Long? = null
    ) {
        if (session == null) return
        val hint = replyToMessageId?.toString()
        bridge.sendText(session, text, hint)
    }

    suspend fun sendPhoto(
        bridge: IRemoteBridgeService,
        session: RemoteSession?,
        photoBytes: ByteArray,
        caption: String?,
        fileName: String
    ) {
        if (session == null) return
        bridge.sendPhoto(session, photoBytes, caption, fileName)
    }

    suspend fun sendVideo(
        bridge: IRemoteBridgeService,
        session: RemoteSession?,
        videoBytes: ByteArray,
        caption: String?,
        fileName: String
    ) {
        if (session == null) return
        bridge.sendVideo(session, videoBytes, caption, fileName)
    }

    suspend fun sendAudio(
        bridge: IRemoteBridgeService,
        session: RemoteSession?,
        audioBytes: ByteArray,
        caption: String?,
        fileName: String
    ) {
        if (session == null) return
        bridge.sendAudio(session, audioBytes, caption, fileName)
    }
}
