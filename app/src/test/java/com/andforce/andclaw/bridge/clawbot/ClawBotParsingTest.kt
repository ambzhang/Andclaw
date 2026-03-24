package com.andforce.andclaw.bridge.clawbot

import com.base.services.ClawBotQrPollPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClawBotParsingTest {

    @Test
    fun parseGetBotQrcodeResponse_extractsQrcodeAndImageUrl() {
        val json = """
            {
              "qrcode": "sess-abc",
              "qrcode_img_content": "https://example.com/qr.png"
            }
        """.trimIndent()
        val r = ClawBotJson.parseGetBotQrcodeResponse(json)
        assertEquals("sess-abc", r.qrcode)
        assertEquals("https://example.com/qr.png", r.qrcodeImgContent)
    }

    @Test
    fun parseGetQrcodeStatusResponse_wait() {
        val json = """{"status":"wait"}"""
        val r = ClawBotJson.parseGetQrcodeStatusResponse(json)
        assertEquals(ClawBotQrPollPhase.WAIT, r.phase)
        assertNull(r.botToken)
    }

    @Test
    fun parseGetQrcodeStatusResponse_scaned() {
        val json = """{"status":"scaned"}"""
        val r = ClawBotJson.parseGetQrcodeStatusResponse(json)
        assertEquals(ClawBotQrPollPhase.SCANED, r.phase)
    }

    @Test
    fun parseGetQrcodeStatusResponse_confirmed_buildsAuthState() {
        val json = """
            {
              "status": "confirmed",
              "bot_token": "tok-1",
              "baseurl": "https://ilink.example.com",
              "ilink_bot_id": "bot@im.bot",
              "ilink_user_id": "user-99"
            }
        """.trimIndent()
        val r = ClawBotJson.parseGetQrcodeStatusResponse(json)
        assertEquals(ClawBotQrPollPhase.CONFIRMED, r.phase)
        val savedAt = 1_700_000_000_000L
        val state = buildClawBotAuthStateFromConfirmed(
            status = r,
            fallbackBaseUrl = "https://fallback.example.com",
            botType = "3",
            savedAt = savedAt
        )
        assertNotNull(state)
        assertEquals("tok-1", state!!.botToken)
        assertEquals("https://ilink.example.com", state.baseUrl)
        assertEquals("bot@im.bot", state.accountId)
        assertEquals("user-99", state.userId)
        assertEquals("3", state.botType)
        assertEquals(savedAt, state.savedAt)
    }

    @Test
    fun buildAuthState_confirmed_usesFallbackBaseUrlWhenBaseurlMissing() {
        val r = ClawBotJson.parseGetQrcodeStatusResponse(
            """{"status":"confirmed","bot_token":"t","ilink_bot_id":"acc","ilink_user_id":"u"}"""
        )
        val state = buildClawBotAuthStateFromConfirmed(
            r,
            fallbackBaseUrl = "https://fallback.example.com",
            botType = "3",
            savedAt = 42L
        )
        assertNotNull(state)
        assertEquals("https://fallback.example.com", state!!.baseUrl)
    }

    @Test
    fun buildAuthState_returnsNullWhenNotConfirmed() {
        val r = ClawBotJson.parseGetQrcodeStatusResponse("""{"status":"wait"}""")
        assertNull(
            buildClawBotAuthStateFromConfirmed(
                r,
                fallbackBaseUrl = "https://x.com",
                botType = "3",
                savedAt = 0L
            )
        )
    }
}
