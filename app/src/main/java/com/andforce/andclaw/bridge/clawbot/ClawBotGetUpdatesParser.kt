package com.andforce.andclaw.bridge.clawbot

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 解析 `ilink/bot/getupdates` 响应，并从单条消息 JSON 抽取可下发给 Agent 的文本（对齐 weclaw-proxy [weixin.ExtractTextFromMessage]）。
 */
object ClawBotGetUpdatesParser {

    const val SESSION_EXPIRED_ERRCODE = -14

    data class GetUpdatesEnvelope(
        val ret: Int,
        val errCode: Int,
        val errMsg: String?,
        val getUpdatesBuf: String?,
        val longPollingTimeoutMs: Long?,
        val msgs: List<JsonObject>
    )

    fun parseEnvelope(json: String): GetUpdatesEnvelope {
        val o = JsonParser.parseString(json).asJsonObject
        val ret = o.intOrZero("ret")
        val errCode = o.intOrZero("errcode")
        val errMsg = o.stringOrNull("errmsg")
        val buf = o.stringOrNull("get_updates_buf")
        val lp = o.get("longpolling_timeout_ms")?.takeIf { !it.isJsonNull }?.asLong
        val msgs = ArrayList<JsonObject>()
        val arr = o.getAsJsonArray("msgs") ?: JsonArray()
        for (el in arr) {
            if (el.isJsonObject) msgs.add(el.asJsonObject)
        }
        return GetUpdatesEnvelope(ret, errCode, errMsg, buf, lp, msgs)
    }

    /**
     * 从单条 `msgs[]` 元素抽取文本；无文本时返回 null（调用方可跳过）。
     */
    fun extractTextForAgent(msg: JsonObject): String? {
        val text = extractTextFromWeixinMessage(msg).trim()
        return text.takeIf { it.isNotEmpty() }
    }

    private fun extractTextFromWeixinMessage(msg: JsonObject): String {
        val items = msg.getAsJsonArray("item_list") ?: return ""
        for (el in items) {
            if (!el.isJsonObject) continue
            val item = el.asJsonObject
            val type = item.intOrZero("type")
            if (type == 1) {
                val ti = item.getAsJsonObject("text_item") ?: continue
                val text = ti.stringOrEmpty("text")
                val ref = item.getAsJsonObject("ref_msg")
                if (ref != null) {
                    val refItem = ref.getAsJsonObject("message_item")
                    if (refItem != null && isMediaItem(refItem)) {
                        return text
                    }
                    val parts = ArrayList<String>()
                    val title = ref.stringOrNull("title")
                    if (!title.isNullOrBlank()) parts.add(title)
                    if (refItem != null) {
                        val refText = extractTextFromItem(refItem)
                        if (refText.isNotEmpty()) parts.add(refText)
                    }
                    if (parts.isNotEmpty()) {
                        return "[引用: ${parts.joinToString(" | ")}]\n$text"
                    }
                }
                return text
            }
            if (type == 3) {
                val vi = item.getAsJsonObject("voice_item") ?: continue
                val vt = vi.stringOrNull("text")
                if (!vt.isNullOrBlank()) return vt
            }
        }
        return ""
    }

    private fun isMediaItem(item: JsonObject): Boolean {
        val t = item.intOrZero("type")
        return t == 2 || t == 3 || t == 4 || t == 5
    }

    private fun extractTextFromItem(item: JsonObject): String {
        if (item.intOrZero("type") != 1) return ""
        return item.getAsJsonObject("text_item")?.stringOrEmpty("text") ?: ""
    }

    fun parseTypingTicketFromGetConfig(json: String): String? {
        val o = JsonParser.parseString(json).asJsonObject
        return o.stringOrNull("typing_ticket")
    }

    private fun JsonObject.intOrZero(name: String): Int =
        get(name)?.takeIf { !it.isJsonNull }?.asInt ?: 0

    private fun JsonObject.stringOrEmpty(name: String): String =
        get(name)?.takeIf { !it.isJsonNull }?.asString ?: ""

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString
}
