package com.andforce.andclaw.bridge

import com.andforce.andclaw.TgBotClient
import com.andforce.andclaw.TgMessage
import com.base.services.BridgeStatus
import com.base.services.TgInboundMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Telegram 长轮询与 [TgBotClient] 生命周期；由 [RemoteBridgeManager] 持有。
 */
internal class TelegramBridge(
    private val scope: CoroutineScope,
    private val getAllowedChatId: () -> Long,
    private val onInbound: suspend (TgInboundMessage) -> Unit,
    private val onConnectionStatus: (BridgeStatus) -> Unit,
) {
    private var client: TgBotClient? = null
    private var pollJob: Job? = null

    fun start(token: String) {
        stopInternal(setStatus = false)
        val c = TgBotClient(token)
        client = c
        scope.launch(Dispatchers.IO) {
            val connected = c.getMe()
            onConnectionStatus(if (connected) BridgeStatus.CONNECTED else BridgeStatus.DISCONNECTED)
            if (connected) {
                c.setMyCommands(
                    listOf(
                        "status" to "查看 Andclaw 运行状态",
                        "stop" to "停止当前任务"
                    )
                )
            }
        }
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val allowedChatId = getAllowedChatId()
                    val updates = client?.poll() ?: emptyList()
                    for (msg in updates) {
                        if (allowedChatId != 0L && msg.chatId != allowedChatId) continue
                        onInbound(msg.toInbound())
                    }
                } catch (_: Exception) {
                    delay(2000)
                }
            }
        }
    }

    private fun stopInternal(setStatus: Boolean) {
        pollJob?.cancel()
        pollJob = null
        client = null
        if (setStatus) {
            onConnectionStatus(BridgeStatus.STOPPED)
        }
    }

    fun stop() {
        stopInternal(setStatus = true)
    }

    suspend fun sendTyping(chatId: Long) {
        client?.sendTyping(chatId)
    }

    suspend fun sendText(chatId: Long, text: String, replyToMessageId: Long?) {
        client?.send(chatId, text, replyToMessageId)
    }

    suspend fun sendPhoto(
        chatId: Long,
        photoBytes: ByteArray,
        caption: String?,
        fileName: String
    ) {
        client?.sendPhoto(chatId, photoBytes, caption, fileName)
    }

    suspend fun sendVideo(
        chatId: Long,
        videoBytes: ByteArray,
        caption: String?,
        fileName: String
    ) {
        client?.sendVideo(chatId, videoBytes, caption, fileName)
    }

    suspend fun sendAudio(
        chatId: Long,
        audioBytes: ByteArray,
        caption: String?,
        fileName: String
    ) {
        client?.sendAudio(chatId, audioBytes, caption, fileName)
    }

    private fun TgMessage.toInbound() = TgInboundMessage(chatId, messageId, text)
}
