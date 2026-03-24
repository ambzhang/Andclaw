package com.andforce.andclaw.bridge.clawbot

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * iLink HTTP 最小封装：header 约定对齐 `wechat-acp` / `weclaw-proxy`。
 *
 * - GET：`AuthorizationType`、`X-WECHAT-UIN`、可选 `Authorization: Bearer <botToken>`
 * - POST：同上，且 body 带 `base_info.channel_version`
 */
class ClawBotApiClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val channelVersion: String = DEFAULT_CHANNEL_VERSION
) {

    fun buildHeaders(botToken: String?): okhttp3.Headers {
        val b = okhttp3.Headers.Builder()
        b.add("Content-Type", "application/json")
        b.add("AuthorizationType", "ilink_bot_token")
        b.add("X-WECHAT-UIN", randomWechatUin())
        b.add("iLink-App-ClientVersion", "1")
        if (!botToken.isNullOrBlank()) {
            b.add("Authorization", "Bearer ${botToken.trim()}")
        }
        return b.build()
    }

    fun getBotQrcode(baseUrl: String, botType: String, botToken: String? = null): GetBotQrcodeResponse {
        val url = normalizeBaseUrl(baseUrl).newBuilder()
            .addPathSegments("ilink/bot/get_bot_qrcode")
            .addQueryParameter("bot_type", botType)
            .build()
        val req = Request.Builder()
            .url(url)
            .headers(buildHeaders(botToken))
            .get()
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw ClawBotHttpException(resp.code, body)
            }
            ClawBotJson.parseGetBotQrcodeResponse(body)
        }
    }

    fun getQrcodeStatus(baseUrl: String, qrcode: String, botToken: String? = null): GetQrcodeStatusResponse {
        val encoded = URLEncoder.encode(qrcode, StandardCharsets.UTF_8.name())
        val url = normalizeBaseUrl(baseUrl).newBuilder()
            .addPathSegments("ilink/bot/get_qrcode_status")
            .encodedQuery("qrcode=$encoded")
            .build()
        val req = Request.Builder()
            .url(url)
            .headers(buildHeaders(botToken))
            .get()
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw ClawBotHttpException(resp.code, body)
            }
            ClawBotJson.parseGetQrcodeStatusResponse(body)
        }
    }

    /**
     * POST `ilink/bot/getupdates`（长轮询）。
     * [timeoutMs] 作用于 OkHttp 的 call/read 超时，与 `wechat-acp` / `weclaw-proxy` 行为一致。
     */
    fun postGetUpdates(
        baseUrl: String,
        getUpdatesBuf: String,
        botToken: String?,
        timeoutMs: Long = 38_000L
    ): String {
        val effectiveTimeout = timeoutMs.coerceAtLeast(5_000L)
        val longPollClient = httpClient.newBuilder()
            .callTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
            .readTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
        val url = normalizeBaseUrl(baseUrl).newBuilder()
            .addPathSegments("ilink/bot/getupdates")
            .build()
        val payload = """{"get_updates_buf":${jsonString(getUpdatesBuf)},"base_info":{"channel_version":${jsonString(channelVersion)}}}"""
        val req = Request.Builder()
            .url(url)
            .headers(buildHeaders(botToken))
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return try {
            longPollClient.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    throw ClawBotHttpException(resp.code, body)
                }
                body
            }
        } catch (e: IOException) {
            if (e is SocketTimeoutException || e is InterruptedIOException) {
                ClawBotJson.emptyGetUpdatesJson(getUpdatesBuf)
            } else {
                throw e
            }
        }
    }

    /** 普通 POST（非长轮询），超时短于长轮询。 */
    /**
     * 文本消息；图片/视频/音频等媒体发送在本项目中**未接协议**（见 [com.andforce.andclaw.bridge.RemoteBridgeManager] 对 ClawBot 的文本降级）。
     */
    fun postSendMessage(
        baseUrl: String,
        botToken: String?,
        toUserId: String,
        text: String,
        contextToken: String
    ) {
        val clientId = "andclaw-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val payload = buildString {
            append("""{"msg":{""")
            append(""""from_user_id":"","to_user_id":${jsonString(toUserId)},""")
            append(""""client_id":${jsonString(clientId)},"message_type":2,"message_state":2,""")
            append(""""context_token":${jsonString(contextToken)},""")
            append(""""item_list":[{"type":1,"text_item":{"text":${jsonString(text)}}}]""")
            append("""},"base_info":{"channel_version":${jsonString(channelVersion)}}}""")
        }
        postShort(baseUrl, "ilink/bot/sendmessage", payload, botToken)
    }

    fun postGetConfig(baseUrl: String, botToken: String?, ilinkUserId: String, contextToken: String?): String {
        val ctxPart = if (contextToken.isNullOrBlank()) {
            ""
        } else {
            ""","context_token":${jsonString(contextToken)}"""
        }
        val payload = """{"ilink_user_id":${jsonString(ilinkUserId)}$ctxPart,"base_info":{"channel_version":${jsonString(channelVersion)}}}"""
        return postShortWithBody(baseUrl, "ilink/bot/getconfig", payload, botToken)
    }

    /**
     * [typingStatus]：1=typing，2=cancel（对齐 [weixin.TypingStatus]）。
     */
    fun postSendTyping(
        baseUrl: String,
        botToken: String?,
        ilinkUserId: String,
        typingTicket: String,
        typingStatus: Int = 1
    ) {
        val payload = """{"ilink_user_id":${jsonString(ilinkUserId)},"typing_ticket":${jsonString(typingTicket)},"status":$typingStatus,"base_info":{"channel_version":${jsonString(channelVersion)}}}"""
        postShort(baseUrl, "ilink/bot/sendtyping", payload, botToken)
    }

    private fun postShort(baseUrl: String, pathSegments: String, jsonBody: String, botToken: String?) {
        postShortWithBody(baseUrl, pathSegments, jsonBody, botToken)
    }

    private fun postShortWithBody(
        baseUrl: String,
        pathSegments: String,
        jsonBody: String,
        botToken: String?
    ): String {
        val url = normalizeBaseUrl(baseUrl).newBuilder()
            .addPathSegments(pathSegments)
            .build()
        val shortClient = httpClient.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .headers(buildHeaders(botToken))
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        return shortClient.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw ClawBotHttpException(resp.code, body)
            }
            body
        }
    }

    private companion object {
        const val DEFAULT_CHANNEL_VERSION = "1.0.2"

        fun normalizeBaseUrl(baseUrl: String): HttpUrl {
            val trimmed = baseUrl.trim().trimEnd('/')
            return try {
                trimmed.toHttpUrl()
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("invalid baseUrl: $baseUrl")
            }
        }

        fun jsonString(s: String): String =
            buildString {
                append('"')
                for (c in s) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(c)
                    }
                }
                append('"')
            }

        fun randomWechatUin(): String {
            val buf = ByteArray(4)
            SecureRandom().nextBytes(buf)
            val u = ((buf[0].toInt() and 0xff) shl 24) or
                ((buf[1].toInt() and 0xff) shl 16) or
                ((buf[2].toInt() and 0xff) shl 8) or
                (buf[3].toInt() and 0xff)
            val uLong = u.toLong() and 0xffffffffL
            return Base64.getEncoder().encodeToString(uLong.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }
}

class ClawBotHttpException(val code: Int, val body: String) : RuntimeException("HTTP $code: $body")
