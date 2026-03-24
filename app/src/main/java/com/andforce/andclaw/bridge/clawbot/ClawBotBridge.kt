package com.andforce.andclaw.bridge.clawbot

import com.base.services.BridgeStatus
import com.base.services.IRemoteChannelConfigService
import com.base.services.RemoteIncomingMessage
import com.base.services.RemoteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ClawBot 长轮询桥：持有 [ClawBotPoller]，与 [TelegramBridge] 一样由 [com.andforce.andclaw.bridge.RemoteBridgeManager] 管理。
 * 出站仅文本 [sendText] 与 [sendTyping]；图片/音视频由 [com.andforce.andclaw.bridge.RemoteBridgeManager] 调用本类 [sendText] 做媒体降级说明。
 */
internal class ClawBotBridge(
    private val scope: CoroutineScope,
    private val channelConfig: IRemoteChannelConfigService,
    private val apiClient: ClawBotApiClient,
    private val onInbound: suspend (RemoteIncomingMessage) -> Unit,
    private val onConnectionStatus: (BridgeStatus) -> Unit,
) {
    private var pollJob: Job? = null

    private val poller: ClawBotPoller by lazy {
        ClawBotPoller(
            api = apiClient,
            getAuth = { channelConfig.loadClawBotAuthState() },
            loadSyncBuf = { channelConfig.loadClawBotSyncBuf() },
            saveSyncBuf = { buf -> channelConfig.saveClawBotSyncBuf(buf) }
        )
    }

    fun start() {
        stopInternal(updateStatus = false)
        val auth = channelConfig.loadClawBotAuthState()
        if (!auth.isCompleteForBridge()) {
            onConnectionStatus(BridgeStatus.NOT_CONFIGURED)
            return
        }
        pollJob = scope.launch(Dispatchers.IO) {
            poller.runLoop(
                onInbound = onInbound,
                onConnected = { onConnectionStatus(BridgeStatus.CONNECTED) },
                onDisconnected = { onConnectionStatus(BridgeStatus.DISCONNECTED) }
            )
        }
    }

    fun stop(updateStatus: Boolean = true) {
        stopInternal(updateStatus = updateStatus)
    }

    private fun stopInternal(updateStatus: Boolean) {
        pollJob?.cancel()
        pollJob = null
        if (updateStatus) {
            onConnectionStatus(BridgeStatus.STOPPED)
        }
    }

    fun isRunning(): Boolean = pollJob?.isActive == true

    suspend fun sendText(session: RemoteSession, text: String) {
        withContext(Dispatchers.IO) {
            val auth = channelConfig.loadClawBotAuthState()
            if (auth == null || !auth.isCompleteForBridge()) return@withContext
            val base = auth.baseUrl.trim()
            val token = auth.botToken.trim()
            val to = session.userId?.trim().orEmpty()
            val ctx = session.replyToken?.trim().orEmpty()
            if (to.isEmpty() || ctx.isEmpty()) return@withContext
            apiClient.postSendMessage(base, token, to, text, ctx)
        }
    }

    /**
     * 先 [postGetConfig] 取 `typing_ticket`，再 [postSendTyping]；失败则静默跳过。
     */
    suspend fun sendTyping(session: RemoteSession) {
        withContext(Dispatchers.IO) {
            val auth = channelConfig.loadClawBotAuthState()
            if (auth == null || !auth.isCompleteForBridge()) return@withContext
            val base = auth.baseUrl.trim()
            val token = auth.botToken.trim()
            val uid = session.userId?.trim().orEmpty()
            val ctx = session.replyToken?.trim().orEmpty()
            if (uid.isEmpty() || ctx.isEmpty()) return@withContext
            val cfgJson = try {
                apiClient.postGetConfig(base, token, uid, ctx)
            } catch (_: Exception) {
                return@withContext
            }
            val ticket = ClawBotGetUpdatesParser.parseTypingTicketFromGetConfig(cfgJson)?.trim().orEmpty()
            if (ticket.isEmpty()) return@withContext
            try {
                apiClient.postSendTyping(base, token, uid, ticket, typingStatus = 1)
            } catch (_: Exception) {
            }
        }
    }
}
