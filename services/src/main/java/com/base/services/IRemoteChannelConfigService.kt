package com.base.services

interface IRemoteChannelConfigService {
    val tgToken: String
    fun setTgToken(token: String)
    fun getTgChatId(): Long
    fun setTgChatId(chatId: Long)

    /** 登录流程使用的 API 根地址；空则实现方应回退 [ClawBotDefaults.DEFAULT_BASE_URL]。 */
    fun getClawBotBaseUrl(): String
    fun setClawBotBaseUrl(url: String)

    /** `get_bot_qrcode` 的 `bot_type` 参数，默认 [ClawBotDefaults.DEFAULT_BOT_TYPE]。 */
    fun getClawBotBotType(): String
    fun setClawBotBotType(botType: String)

    fun loadClawBotAuthState(): ClawBotAuthState?
    fun saveClawBotAuthState(state: ClawBotAuthState)
    fun clearClawBotAuthState()
    fun loadClawBotSyncBuf(): String
    fun saveClawBotSyncBuf(syncBuf: String)
}
