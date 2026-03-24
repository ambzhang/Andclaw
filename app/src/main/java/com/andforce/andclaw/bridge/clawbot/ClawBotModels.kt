package com.andforce.andclaw.bridge.clawbot

import com.base.services.ClawBotAuthState
import com.base.services.ClawBotQrPollPhase
import com.google.gson.annotations.SerializedName
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * `GET ilink/bot/get_bot_qrcode` 响应体。
 */
data class GetBotQrcodeResponse(
    @SerializedName("qrcode") val qrcode: String,
    @SerializedName("qrcode_img_content") val qrcodeImgContent: String
)

/**
 * `GET ilink/bot/get_qrcode_status` 响应体。
 */
data class GetQrcodeStatusResponse(
    @SerializedName("status") val statusRaw: String,
    @SerializedName("bot_token") val botToken: String? = null,
    @SerializedName("baseurl") val baseUrl: String? = null,
    @SerializedName("ilink_bot_id") val ilinkBotId: String? = null,
    @SerializedName("ilink_user_id") val ilinkUserId: String? = null
) {
    val phase: ClawBotQrPollPhase
        get() = ClawBotQrPollPhase.fromApi(statusRaw)
}

object ClawBotJson {
    /** 长轮询客户端超时：与空消息、保持 sync buf 不变（对齐 weclaw-proxy [Client.GetUpdates]）。 */
    fun emptyGetUpdatesJson(getUpdatesBuf: String): String =
        """{"ret":0,"get_updates_buf":${jsonStringLiteral(getUpdatesBuf)},"msgs":[]}"""

    private fun jsonStringLiteral(s: String): String =
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

    fun parseGetBotQrcodeResponse(json: String): GetBotQrcodeResponse {
        val o = JsonParser.parseString(json).asJsonObject
        return GetBotQrcodeResponse(
            qrcode = o.stringOrEmpty("qrcode"),
            qrcodeImgContent = o.stringOrEmpty("qrcode_img_content")
        )
    }

    fun parseGetQrcodeStatusResponse(json: String): GetQrcodeStatusResponse {
        val o = JsonParser.parseString(json).asJsonObject
        return GetQrcodeStatusResponse(
            statusRaw = o.stringOrEmpty("status"),
            botToken = o.stringOrNull("bot_token"),
            baseUrl = o.stringOrNull("baseurl"),
            ilinkBotId = o.stringOrNull("ilink_bot_id"),
            ilinkUserId = o.stringOrNull("ilink_user_id")
        )
    }
}

/**
 * 在 `status == confirmed` 且关键字段齐全时，构造持久化用的 [ClawBotAuthState]。
 */
fun buildClawBotAuthStateFromConfirmed(
    status: GetQrcodeStatusResponse,
    fallbackBaseUrl: String,
    botType: String,
    savedAt: Long
): ClawBotAuthState? {
    if (status.phase != ClawBotQrPollPhase.CONFIRMED) return null
    val token = status.botToken?.trim().orEmpty()
    val account = status.ilinkBotId?.trim().orEmpty()
    if (token.isEmpty() || account.isEmpty()) return null
    val resolvedBase = status.baseUrl?.trim().orEmpty().ifBlank { fallbackBaseUrl.trim() }
    if (resolvedBase.isEmpty()) return null
    return ClawBotAuthState(
        botToken = token,
        baseUrl = resolvedBase,
        accountId = account,
        userId = status.ilinkUserId?.trim().orEmpty(),
        botType = botType,
        savedAt = savedAt
    )
}

private fun JsonObject.stringOrEmpty(name: String): String =
    get(name)?.takeIf { !it.isJsonNull }?.asString ?: ""

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString
