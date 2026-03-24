package com.andforce.andclaw.bridge.clawbot

import com.base.services.ClawBotAuthState

/** Task 7：长轮询与出站所需的最小登录态（与 sessionKey `wx:<accountId>:…` 一致）。 */
internal fun ClawBotAuthState?.isCompleteForBridge(): Boolean {
    if (this == null) return false
    return botToken.trim().isNotEmpty() &&
        baseUrl.trim().isNotEmpty() &&
        accountId.trim().isNotEmpty()
}
