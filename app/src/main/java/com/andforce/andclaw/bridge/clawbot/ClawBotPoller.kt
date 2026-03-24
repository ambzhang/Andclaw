package com.andforce.andclaw.bridge.clawbot

import android.util.Log
import com.base.services.ClawBotAuthState
import com.base.services.RemoteChannel
import com.base.services.RemoteIncomingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import com.google.gson.JsonObject

private const val MAX_CONSECUTIVE_FAILURES = 3
private const val BACKOFF_DELAY_MS = 30_000L
private const val RETRY_DELAY_MS = 2_000L
private const val SESSION_PAUSE_MS = 300_000L
private const val DEFAULT_LONG_POLL_MS = 38_000L

private const val TAG = "ClawBotPoller"

/**
 * ClawBot `getupdates` 长轮询循环（纯逻辑 + [ClawBotApiClient]），由 [ClawBotBridge] 在 IO 协程中驱动。
 */
class ClawBotPoller(
    private val api: ClawBotApiClient,
    private val getAuth: () -> ClawBotAuthState?,
    private val loadSyncBuf: () -> String,
    private val saveSyncBuf: (String) -> Unit,
) {

    suspend fun runLoop(
        onInbound: suspend (RemoteIncomingMessage) -> Unit,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
    ) {
        var nextTimeoutMs = DEFAULT_LONG_POLL_MS
        var consecutiveFailures = 0
        var getUpdatesBuf = loadSyncBuf()

        while (coroutineContext.isActive) {
            val auth = getAuth()
            if (auth == null || !auth.isCompleteForBridge()) {
                onDisconnected()
                delay(RETRY_DELAY_MS)
                continue
            }
            val baseUrl = auth.baseUrl.trim()
            val token = auth.botToken.trim()
            val accountId = auth.accountId.trim()

            try {
                val raw = api.postGetUpdates(baseUrl, getUpdatesBuf, token, nextTimeoutMs)
                val env = ClawBotGetUpdatesParser.parseEnvelope(raw)

                if (env.longPollingTimeoutMs != null && env.longPollingTimeoutMs > 0) {
                    nextTimeoutMs = env.longPollingTimeoutMs
                }

                val isApiError = (env.ret != 0) || (env.errCode != 0)
                if (isApiError) {
                    val sessionExpired = env.errCode == ClawBotGetUpdatesParser.SESSION_EXPIRED_ERRCODE ||
                        env.ret == ClawBotGetUpdatesParser.SESSION_EXPIRED_ERRCODE
                    if (sessionExpired) {
                        consecutiveFailures = 0
                        onDisconnected()
                        delay(SESSION_PAUSE_MS)
                        continue
                    }
                    consecutiveFailures++
                    onDisconnected()
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        consecutiveFailures = 0
                        delay(BACKOFF_DELAY_MS)
                    } else {
                        delay(RETRY_DELAY_MS)
                    }
                    continue
                }

                consecutiveFailures = 0
                onConnected()

                if (!env.getUpdatesBuf.isNullOrEmpty()) {
                    saveSyncBuf(env.getUpdatesBuf)
                    getUpdatesBuf = env.getUpdatesBuf
                }

                for (msg in env.msgs) {
                    val incoming = mapToIncoming(accountId, msg) ?: continue
                    onInbound(incoming)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                consecutiveFailures++
                onDisconnected()
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    consecutiveFailures = 0
                    delay(BACKOFF_DELAY_MS)
                } else {
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun mapToIncoming(accountId: String, msg: JsonObject): RemoteIncomingMessage? {
        val from = msg.stringOrNull("from_user_id")?.trim().orEmpty()
        if (from.isEmpty()) return null
        val text = ClawBotGetUpdatesParser.extractTextForAgent(msg) ?: return null
        val mid = when {
            msg.has("message_id") && !msg.get("message_id").isJsonNull ->
                msg.get("message_id").asInt.toString()
            else -> msg.stringOrNull("client_id") ?: msg.intOrZero("seq").toString()
        }
        val ctx = msg.stringOrNull("context_token")?.trim().orEmpty()
        if (ctx.isEmpty()) {
            Log.d(TAG, "drop inbound: missing context_token (from=$from, message_id=$mid)")
            return null
        }
        val sessionKey = "wx:$accountId:$from"
        val now = System.currentTimeMillis()
        return RemoteIncomingMessage(
            channel = RemoteChannel.CLAWBOT,
            sessionKey = sessionKey,
            replyToken = ctx,
            userId = from,
            displayName = null,
            messageId = mid,
            accountId = accountId,
            lastIncomingAt = now,
            text = text,
            senderId = from,
            senderDisplayName = null,
            receivedAtMs = now
        )
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.intOrZero(name: String): Int =
        get(name)?.takeIf { !it.isJsonNull }?.asInt ?: 0
}
