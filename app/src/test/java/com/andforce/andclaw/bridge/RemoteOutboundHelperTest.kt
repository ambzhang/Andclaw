package com.andforce.andclaw.bridge

import com.base.services.RemoteChannel
import com.base.services.RemoteSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteOutboundHelperTest {

    @Test
    fun telegramChatIdOrNull_parsesTelegramSessionKey() {
        val s = RemoteSession(RemoteChannel.TELEGRAM, sessionKey = "12345")
        assertEquals(12345L, RemoteOutboundHelper.telegramChatIdOrNull(s))
    }

    @Test
    fun telegramChatIdOrNull_invalidKey_returnsNull() {
        val s = RemoteSession(RemoteChannel.TELEGRAM, sessionKey = "abc")
        assertNull(RemoteOutboundHelper.telegramChatIdOrNull(s))
    }

    @Test
    fun telegramChatIdOrNull_nonTelegram_returnsNull() {
        val s = RemoteSession(RemoteChannel.CLAWBOT, sessionKey = "123")
        assertNull(RemoteOutboundHelper.telegramChatIdOrNull(s))
    }

    @Test
    fun shouldAttemptRemoteEcho_user_false() {
        val s = RemoteSession(RemoteChannel.TELEGRAM, sessionKey = "1")
        assertFalse(RemoteOutboundHelper.shouldAttemptRemoteEcho("user", s))
    }

    @Test
    fun shouldAttemptRemoteEcho_nonUser_withSession_true() {
        val s = RemoteSession(RemoteChannel.TELEGRAM, sessionKey = "1")
        assertTrue(RemoteOutboundHelper.shouldAttemptRemoteEcho("ai", s))
        assertTrue(RemoteOutboundHelper.shouldAttemptRemoteEcho("system", s))
    }

    @Test
    fun shouldAttemptRemoteEcho_noSession_false() {
        assertFalse(RemoteOutboundHelper.shouldAttemptRemoteEcho("ai", null))
    }
}
