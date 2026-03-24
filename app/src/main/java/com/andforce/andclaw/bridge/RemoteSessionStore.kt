package com.andforce.andclaw.bridge

import android.content.Context
import android.content.SharedPreferences
import com.base.services.RemoteSession
import com.google.gson.Gson

/**
 * 远程桥接会话的持久化与内存缓存（通用命名，不限于单一渠道）。
 *
 * 使用 [SharedPreferences] 持久化 JSON，并用内存表加速读取；同一进程内多实例共享同一 preference 文件时，
 * 仍建议通过单一 [RemoteSessionStore] 访问以避免缓存不一致。
 *
 * 从磁盘读取时会校验 JSON 与 [RemoteSession.sessionKey] 是否与存储键一致；损坏或不一致的条目会被移除。
 */
class RemoteSessionStore private constructor(
    private val prefs: SharedPreferences,
    private val gson: Gson,
) {

    constructor(prefs: SharedPreferences) : this(prefs, Gson())

    constructor(context: Context) : this(
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE),
        Gson(),
    )

    private val cache = mutableMapOf<String, RemoteSession>()
    private val lock = Any()

    fun upsert(session: RemoteSession) {
        synchronized(lock) {
            val json = gson.toJson(session)
            cache[session.sessionKey] = session
            prefs.edit().putString(prefKey(session.sessionKey), json).apply()
        }
    }

    fun get(sessionKey: String): RemoteSession? {
        synchronized(lock) {
            cache[sessionKey]?.let { return it }
            val storageKey = prefKey(sessionKey)
            val json = prefs.getString(storageKey, null) ?: return null
            val session = resolveEntryOrRemove(storageKey, json, sessionKey) ?: return null
            cache[sessionKey] = session
            return session
        }
    }

    fun remove(sessionKey: String) {
        synchronized(lock) {
            cache.remove(sessionKey)
            prefs.edit().remove(prefKey(sessionKey)).apply()
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            prefs.edit().clear().apply()
        }
    }

    fun all(): List<RemoteSession> {
        synchronized(lock) {
            val result = ArrayList<RemoteSession>()
            for ((key, value) in prefs.all.entries.toList()) {
                if (!key.startsWith(KEY_PREFIX)) continue
                val expectedSessionKey = key.removePrefix(KEY_PREFIX)
                val json = value as? String ?: run {
                    removeCorruptEntry(key, expectedSessionKey)
                    continue
                }
                val session = resolveEntryOrRemove(key, json, expectedSessionKey) ?: continue
                cache[session.sessionKey] = session
                result.add(session)
            }
            result.sortBy { it.sessionKey }
            return result
        }
    }

    /**
     * 解析 JSON 并校验 [RemoteSession.sessionKey] 与 [expectedSessionKey] 一致且非空。
     * 失败时从 [prefs] 与 [cache] 中移除对应条目（与 [get] / [all] 行为一致）。
     */
    private fun resolveEntryOrRemove(
        storageKey: String,
        json: String,
        expectedSessionKey: String,
    ): RemoteSession? {
        val session = runCatching { gson.fromJson(json, RemoteSession::class.java) }.getOrNull()
        if (session == null || session.sessionKey.isBlank() || session.sessionKey != expectedSessionKey) {
            prefs.edit().remove(storageKey).apply()
            cache.remove(expectedSessionKey)
            return null
        }
        return session
    }

    private fun removeCorruptEntry(storageKey: String, logicalKey: String) {
        prefs.edit().remove(storageKey).apply()
        cache.remove(logicalKey)
    }

    private fun prefKey(sessionKey: String): String = KEY_PREFIX + sessionKey

    companion object {
        private const val PREF_NAME = "remote_session_store"
        private const val KEY_PREFIX = "remote_session.entry."
    }
}
