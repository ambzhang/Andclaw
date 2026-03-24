package com.andforce.andclaw.bridge.clawbot

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClawBotGetUpdatesParserTest {

    @Test
    fun parseEnvelope_readsRetErrBufLongPollAndMsgs() {
        val json = """
            {
              "ret": 0,
              "errcode": 0,
              "get_updates_buf": "buf-next",
              "longpolling_timeout_ms": 42000,
              "msgs": [
                {
                  "from_user_id": "from-1",
                  "context_token": "ctx-1",
                  "message_id": 99,
                  "item_list": [{"type": 1, "text_item": {"text": "hello"}}]
                }
              ]
            }
        """.trimIndent()
        val env = ClawBotGetUpdatesParser.parseEnvelope(json)
        assertEquals(0, env.ret)
        assertEquals(0, env.errCode)
        assertEquals("buf-next", env.getUpdatesBuf)
        assertEquals(42000L, env.longPollingTimeoutMs)
        assertEquals(1, env.msgs.size)
        assertEquals("hello", ClawBotGetUpdatesParser.extractTextForAgent(env.msgs[0]))
    }

    @Test
    fun extractTextForAgent_voiceAsrText() {
        val msg = JsonParser.parseString(
            """{"from_user_id":"u","context_token":"c","item_list":[{"type":3,"voice_item":{"text":"语音转写"}}]}"""
        ).asJsonObject
        assertEquals("语音转写", ClawBotGetUpdatesParser.extractTextForAgent(msg))
    }

    @Test
    fun parseTypingTicketFromGetConfig() {
        val json = """{"ret":0,"typing_ticket":"tk-abc"}"""
        assertEquals("tk-abc", ClawBotGetUpdatesParser.parseTypingTicketFromGetConfig(json))
    }

    @Test
    fun extractTextForAgent_noText_returnsNull() {
        val msg = JsonParser.parseString(
            """{"from_user_id":"u","context_token":"c","item_list":[{"type":2}]}"""
        ).asJsonObject
        assertNull(ClawBotGetUpdatesParser.extractTextForAgent(msg))
    }
}
