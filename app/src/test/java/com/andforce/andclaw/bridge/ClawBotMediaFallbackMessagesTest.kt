package com.andforce.andclaw.bridge

import org.junit.Assert.assertTrue
import org.junit.Test

class ClawBotMediaFallbackMessagesTest {

    @Test
    fun image_containsKindAndFileNameAndDisclaimer() {
        val s = ClawBotMediaFallbackMessages.image(caption = "cap", fileName = "a.png")
        assertTrue(s.contains("图片"))
        assertTrue(s.contains("a.png"))
        assertTrue(s.contains("说明：cap"))
        assertTrue(s.contains("未实现媒体远程发送"))
    }

    @Test
    fun video_omitsCaptionWhenBlank() {
        val s = ClawBotMediaFallbackMessages.video(caption = "   ", fileName = "v.mp4")
        assertTrue(s.contains("视频"))
        assertTrue(s.contains("v.mp4"))
        assertTrue(!s.contains("说明："))
    }
}
