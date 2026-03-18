package com.base.services

interface IAiConfigService {
    val provider: String
    val apiUrl: String
    val apiKey: String
    val model: String
    val defaultApiKey: String
    val tgToken: String
    fun updateConfig(provider: String, apiUrl: String, apiKey: String, model: String)
    fun getTgChatId(): Long
    fun setTgChatId(chatId: Long)
    fun setTgToken(token: String)
    fun saveProviderKey(provider: String, key: String)
    fun loadProviderKey(provider: String): String
}
