package com.base.services

/** iLink 登录前默认入口（与参考实现一致，可被用户配置覆盖）。 */
object ClawBotDefaults {
    const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
    const val DEFAULT_BOT_TYPE = "3"
}

data class ClawBotAuthState(
    val botToken: String = "",
    val baseUrl: String = "",
    val accountId: String = "",
    val userId: String = "",
    val botType: String = "",
    val savedAt: Long = 0L
)

/**
 * [ilink/bot/get_qrcode_status] 返回的 `status` 字段语义（小写匹配）。
 */
enum class ClawBotQrPollPhase {
    WAIT,
    SCANED,
    CONFIRMED,
    EXPIRED,
    UNKNOWN;

    companion object {
        fun fromApi(raw: String?): ClawBotQrPollPhase {
            return when (raw?.lowercase()) {
                "wait" -> WAIT
                "scaned" -> SCANED
                "confirmed" -> CONFIRMED
                "expired" -> EXPIRED
                else -> UNKNOWN
            }
        }
    }
}

enum class ClawBotLoginStatus {
    NOT_CONFIGURED,
    LOGIN_REQUIRED,
    QR_READY,
    WAITING_CONFIRM,
    CONNECTED,
    DISCONNECTED,
    STOPPED
}

data class ClawBotQrCodeResult(
    val qrcode: String,
    val qrcodeImgContent: String
)

data class ClawBotQrPollResult(
    val phase: ClawBotQrPollPhase,
    val authState: ClawBotAuthState?
)
