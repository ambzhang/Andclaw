package com.andforce.andclaw.bridge

/**
 * ClawBot / iLink 在本项目中仅接好文本与 typing；媒体走明确文本降级，避免静默丢消息。
 */
internal object ClawBotMediaFallbackMessages {

    fun image(caption: String?, fileName: String): String =
        build(kindLabel = "图片", caption = caption, fileName = fileName)

    fun video(caption: String?, fileName: String): String =
        build(kindLabel = "视频", caption = caption, fileName = fileName)

    fun audio(caption: String?, fileName: String): String =
        build(kindLabel = "音频", caption = caption, fileName = fileName)

    private fun build(kindLabel: String, caption: String?, fileName: String): String {
        val cap = caption?.trim()?.takeIf { it.isNotEmpty() }?.let { "，说明：$it" } ?: ""
        return "媒体已保存到本地（$kindLabel，文件：$fileName$cap）。ClawBot 当前协议在本项目未实现媒体远程发送，请在本机查看原文件。"
    }
}
