package com.andforce.andclaw.bridge.clawbot

/**
 * 微信二维码登录：组合 [ClawBotApiClient] 的「取码」与「单次轮询状态」。
 * 外层循环与超时策略由 UI / Bridge 层决定（本任务不实现）。
 */
class ClawBotAuthClient(
    private val api: ClawBotApiClient
) {
    fun requestQrCode(baseUrl: String, botType: String): GetBotQrcodeResponse {
        return api.getBotQrcode(baseUrl, botType, botToken = null)
    }

    /** 单次查询扫码状态；外层按间隔重复调用即构成轮询。 */
    fun pollQrStatus(baseUrl: String, qrcode: String): GetQrcodeStatusResponse {
        return api.getQrcodeStatus(baseUrl, qrcode, botToken = null)
    }
}
