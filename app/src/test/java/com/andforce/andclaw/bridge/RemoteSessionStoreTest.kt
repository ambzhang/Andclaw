package com.andforce.andclaw.bridge

import android.content.SharedPreferences
import com.base.services.RemoteChannel
import com.base.services.RemoteSession
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [MemoryEditor.remove] 使用的哨兵，表示删除该 key。 */
private object PendingRemove

/**
 * 内存 [SharedPreferences]：行为足够支撑 [RemoteSessionStore] 单测，无 Robolectric 依赖。
 */
private class MemorySharedPreferences : SharedPreferences {
    private val lock = Any()
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { HashMap(values) }

    override fun getString(key: String, defValue: String?): String? = synchronized(lock) {
        if (!values.containsKey(key)) defValue else values[key] as? String
    }

    override fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = MemoryEditor(this)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) = Unit

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        throw UnsupportedOperationException()

    override fun getFloat(key: String, defValue: Float): Float =
        throw UnsupportedOperationException()

    override fun getInt(key: String, defValue: Int): Int =
        throw UnsupportedOperationException()

    override fun getLong(key: String, defValue: Long): Long =
        throw UnsupportedOperationException()

    override fun getStringSet(key: String, defValue: MutableSet<String>?): MutableSet<String>? =
        throw UnsupportedOperationException()

    fun applyBatch(clear: Boolean, mutations: Map<String, Any?>) {
        synchronized(lock) {
            if (clear) values.clear()
            for ((k, v) in mutations) {
                when (v) {
                    PendingRemove -> values.remove(k)
                    null -> values.remove(k)
                    else -> values[k] = v
                }
            }
        }
    }
}

private class MemoryEditor(
    private val prefs: MemorySharedPreferences,
) : SharedPreferences.Editor {
    private var clear = false
    private val pending = linkedMapOf<String, Any?>()

    override fun putString(key: String, value: String?): SharedPreferences.Editor {
        pending[key] = value
        return this
    }

    override fun remove(key: String): SharedPreferences.Editor {
        pending[key] = PendingRemove
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        clear = true
        return this
    }

    override fun apply() {
        commit()
    }

    override fun commit(): Boolean {
        prefs.applyBatch(clear, pending)
        pending.clear()
        clear = false
        return true
    }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
        throw UnsupportedOperationException()

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
        throw UnsupportedOperationException()

    override fun putInt(key: String, value: Int): SharedPreferences.Editor =
        throw UnsupportedOperationException()

    override fun putLong(key: String, value: Long): SharedPreferences.Editor =
        throw UnsupportedOperationException()

    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
        throw UnsupportedOperationException()
}

class RemoteSessionStoreTest {

    private lateinit var prefs: MemorySharedPreferences
    private lateinit var store: RemoteSessionStore

    /** 须与 [RemoteSessionStore] 内 `KEY_PREFIX` 保持一致。 */
    private fun entryKey(sessionKey: String) = "remote_session.entry.$sessionKey"

    @Before
    fun setUp() {
        prefs = MemorySharedPreferences()
        store = RemoteSessionStore(prefs)
    }

    @Test
    fun upsert_sameSessionKey_overwritesReplyTokenAndLastIncomingAt() {
        val key = "sess-1"
        store.upsert(
            RemoteSession(
                channel = RemoteChannel.TELEGRAM,
                sessionKey = key,
                replyToken = "old-token",
                lastIncomingAt = 100L,
            ),
        )
        store.upsert(
            RemoteSession(
                channel = RemoteChannel.TELEGRAM,
                sessionKey = key,
                replyToken = "new-token",
                lastIncomingAt = 200L,
            ),
        )

        val loaded = store.get(key)!!
        assertEquals("new-token", loaded.replyToken)
        assertEquals(200L, loaded.lastIncomingAt)
        assertEquals(RemoteChannel.TELEGRAM, loaded.channel)
    }

    @Test
    fun remove_thenGet_returnsNull() {
        val key = "sess-rm"
        store.upsert(
            RemoteSession(
                channel = RemoteChannel.CLAWBOT,
                sessionKey = key,
                replyToken = "t",
                lastIncomingAt = 1L,
            ),
        )
        assertEquals(key, store.get(key)!!.sessionKey)

        store.remove(key)

        assertNull(store.get(key))
    }

    @Test
    fun clear_thenAllSessionsEmpty() {
        store.upsert(
            RemoteSession(
                RemoteChannel.TELEGRAM,
                "a",
                replyToken = "1",
                lastIncomingAt = 1L,
            ),
        )
        store.upsert(
            RemoteSession(
                RemoteChannel.CLAWBOT,
                "b",
                replyToken = "2",
                lastIncomingAt = 2L,
            ),
        )

        store.clear()

        assertTrue(store.all().isEmpty())
        assertNull(store.get("a"))
        assertNull(store.get("b"))
    }

    @Test
    fun get_malformedJson_returnsNull_andRemovesEntry() {
        val id = "corrupt-1"
        prefs.edit().putString(entryKey(id), "not-json {{{").commit()

        val got = store.get(id)

        assertNull(got)
        assertFalse(prefs.contains(entryKey(id)))
    }

    @Test
    fun get_sessionKeyMismatch_returnsNull_andRemovesEntry() {
        val storageId = "foo"
        val json = Gson().toJson(
            RemoteSession(
                channel = RemoteChannel.TELEGRAM,
                sessionKey = "other",
                replyToken = "t",
                lastIncomingAt = 1L,
            ),
        )
        prefs.edit().putString(entryKey(storageId), json).commit()

        val got = store.get(storageId)

        assertNull(got)
        assertFalse(prefs.contains(entryKey(storageId)))
    }

    @Test
    fun all_malformedJson_skipsAndRemovesEntry() {
        prefs.edit().putString(entryKey("bad-json"), "{").commit()

        val list = store.all()

        assertTrue(list.isEmpty())
        assertFalse(prefs.contains(entryKey("bad-json")))
    }

    @Test
    fun all_sessionKeyMismatch_skipsAndRemovesEntry() {
        prefs.edit().putString(
            entryKey("slot-a"),
            Gson().toJson(
                RemoteSession(
                    RemoteChannel.CLAWBOT,
                    "slot-b",
                    replyToken = "x",
                    lastIncomingAt = 1L,
                ),
            ),
        ).commit()

        val list = store.all()

        assertTrue(list.isEmpty())
        assertFalse(prefs.contains(entryKey("slot-a")))
    }
}
