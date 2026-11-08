package ru.liturgyguide.service

import android.content.SharedPreferences
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReadingAcceptanceStoreTest {
    private val oldContext = ReadingContext("church", "2026-11-08", "Europe/Moscow", ReadingContextSource.MANUAL)
    private val newContext = oldContext.copy(serviceDate = "2026-11-09")
    private val oldSession = ServiceSession("creed", Instant.parse("2026-11-08T06:30:00Z").toEpochMilli(), elapsedInStageMillis = 1_000)
    private val newSession = ServiceSession("preparation", Instant.parse("2026-11-08T18:00:00Z").toEpochMilli())
    private val gson = Gson()

    @Test fun `successful acceptance replaces the whole reading in exactly one editor`() {
        val preferences = seeded()
        assertTrue(ReadingAcceptanceStore(preferences).accept(newContext, newSession, resetBookmarks = true))
        assertEquals(1, preferences.editCount)
        assertEquals(1, preferences.commitCount)
        assertEquals(newContext, ReadingContextStore(preferences).restore())
        assertEquals(newSession, savedSession(preferences))
        assertEquals("preparation", preferences.values["stage_id"])
        assertFalse(preferences.values.keys.any { ReadingAcceptanceStore.isBookmarkKey(it) })
        assertUnrelated(preferences)
        assertEquals(3 + BOOKMARK_KEYS.size, preferences.committedKeys.single().size)
    }

    @Test fun `a correction can retain every bookmark while changing the accepted session`() {
        val preferences = seeded()
        val bookmarks = preferences.values.filterKeys { ReadingAcceptanceStore.isBookmarkKey(it) }
        assertTrue(ReadingAcceptanceStore(preferences).accept(oldContext, oldSession.copy(elapsedInStageMillis = 2_000), resetBookmarks = false))
        assertEquals(bookmarks, preferences.values.filterKeys { ReadingAcceptanceStore.isBookmarkKey(it) })
        assertEquals(setOf(ReadingContextStore.CONTEXT_KEY, "session", "stage_id"), preferences.committedKeys.single())
        assertUnrelated(preferences)
    }

    @Test fun `commit false after mutating Android memory restores old reading and all bookmark types`() {
        val preferences = seeded()
        val before = preferences.values.toMap()
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, resetBookmarks = true))
        assertEquals(2, preferences.commitCount)
        assertEquals(before, preferences.values)
        val failedMemory = preferences.memoryAfterCommit.first()
        assertEquals(newContext, ReadingContextCodec.decode(failedMemory[ReadingContextStore.CONTEXT_KEY] as String))
        assertEquals("preparation", failedMemory["stage_id"])
        assertFalse(failedMemory.keys.any { ReadingAcceptanceStore.isBookmarkKey(it) })
        assertEquals(preferences.committedKeys[0], preferences.committedKeys[1])
    }

    @Test fun `a followup write from the old UI cannot mix a new date with the old stage after failure`() {
        val preferences = seeded()
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, resetBookmarks = true))
        // Like MainActivity.saveSession after the unchanged UI makes a later manual correction.
        val followup = oldSession.copy(anchorStageId = "anaphora", elapsedInStageMillis = 0)
        preferences.edit().putString("session", gson.toJson(followup)).putString("stage_id", followup.anchorStageId).apply()
        assertEquals(oldContext, ReadingContextStore(preferences).restore())
        assertEquals(followup, savedSession(preferences))
        assertEquals("anaphora", preferences.values["stage_id"])
        assertEquals(3, preferences.commitCount)
        assertEquals(3, preferences.editCount)
        assertUnrelated(preferences)
    }

    @Test fun `absent original keys are removed again instead of retaining newly accepted metadata`() {
        val preferences = seeded().apply {
            values.remove(ReadingContextStore.CONTEXT_KEY)
            values.remove("session")
            values.remove("stage_id")
            outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        }
        val before = preferences.values.toMap()
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, resetBookmarks = true))
        assertEquals(before, preferences.values)
        assertFalse(preferences.values.containsKey(ReadingContextStore.CONTEXT_KEY))
        assertFalse(preferences.values.containsKey("session"))
        assertFalse(preferences.values.containsKey("stage_id"))
    }

    @Test fun `an exception after memory mutation gets the same single rollback attempt`() {
        val preferences = seeded()
        val before = preferences.values.toMap()
        preferences.outcomes += MemoryCommit.THROW_AFTER_MEMORY
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, resetBookmarks = true))
        assertEquals(before, preferences.values)
        assertEquals(2, preferences.commitCount)
    }

    @Test fun `rollback false or exception after its memory write does not retry or report success`() {
        for (rollback in listOf(MemoryCommit.FALSE_AFTER_MEMORY, MemoryCommit.THROW_AFTER_MEMORY)) {
            val preferences = seeded()
            val before = preferences.values.toMap()
            preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
            preferences.outcomes += rollback
            assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, resetBookmarks = true))
            assertEquals(before, preferences.values)
            assertEquals(2, preferences.editCount)
            assertEquals(2, preferences.commitCount)
        }
    }

    @Test fun `rollback touches no unrelated setting changed during the failed commit`() {
        val preferences = seeded()
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.afterMemory = { count -> if (count == 1) {
            preferences.values["dark"] = false
            preferences.values["text_size"] = 2
            preferences.values["new_unrelated_setting"] = "new"
        } }
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, resetBookmarks = true))
        assertEquals(false, preferences.values["dark"])
        assertEquals(2, preferences.values["text_size"])
        assertEquals("new", preferences.values["new_unrelated_setting"])
        assertEquals(oldContext, ReadingContextStore(preferences).restore())
        assertEquals(oldSession, savedSession(preferences))
        assertTrue(preferences.committedKeys.all { keys -> "dark" !in keys && "text_size" !in keys && "new_unrelated_setting" !in keys })
    }

    @Test fun `without reset rollback does not overwrite bookmarks changed by another preferences writer`() {
        val preferences = seeded()
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.afterMemory = { count -> if (count == 1) preferences.values["lyric_line_creed"] = 8 }
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, resetBookmarks = false))
        assertEquals(8, preferences.values["lyric_line_creed"])
        assertEquals(oldContext, ReadingContextStore(preferences).restore())
        assertTrue(preferences.committedKeys.all { it.size == 3 })
    }

    @Test fun `the snapshot owns its string set rather than sharing the preference value`() {
        val preferences = seeded()
        val originalSet = mutableSetOf("first", "second")
        preferences.values["lyric_track_legacy_set"] = originalSet
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.afterMemory = { count -> if (count == 1) originalSet += "changed after snapshot" }
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, resetBookmarks = true))
        assertEquals(setOf("first", "second"), preferences.values["lyric_track_legacy_set"])
        assertTrue("changed after snapshot" in originalSet)
    }

    @Test fun `invalid input or unreadable snapshot returns false without opening an editor`() {
        val preferences = seeded()
        val before = preferences.values.toMap()
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext.copy(serviceDate = "invalid"), newSession, true))
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession.copy(anchorStageId = " "), true))
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession.copy(elapsedInStageMillis = -1), true))
        preferences.throwOnRead = true
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, true))
        assertEquals(0, preferences.editCount)
        assertEquals(before, preferences.values)
    }

    @Test fun `unsupported affected values are rejected before writing but unrelated values are not inspected`() {
        val preferences = seeded()
        preferences.values["unsupported_unrelated"] = Any()
        preferences.values["lyric_track_bad"] = setOf(1)
        assertFalse(ReadingAcceptanceStore(preferences).accept(newContext, newSession, true))
        assertEquals(0, preferences.editCount)
        preferences.values.remove("lyric_track_bad")
        assertTrue(ReadingAcceptanceStore(preferences).accept(newContext, newSession, true))
        assertTrue(preferences.values.containsKey("unsupported_unrelated"))
    }

    private fun seeded() = AcceptanceMemoryPreferences().apply {
        values[ReadingContextStore.CONTEXT_KEY] = ReadingContextCodec.encode(oldContext)
        values["session"] = gson.toJson(oldSession)
        values["stage_id"] = oldSession.anchorStageId
        values["lyric_track_creed"] = "track"
        values["lyric_line_creed"] = 3
        values["lyric_offset_creed"] = 700L
        values["lyric_phrase_creed"] = "phrase"
        values["lyric_saved_tempo_creed"] = 8
        values["lyric_track_legacy_float"] = 1.25f
        values["lyric_track_legacy_boolean"] = true
        values["lyric_track_legacy_set"] = setOf("first", "second")
        values["dark"] = true
        values["text_size"] = 1
        values["lyric_tempo"] = 12
        values["lyric_original"] = true
        values["church_id"] = "church"
        values["favorite_churches_v1"] = setOf("church", "other")
    }
    private fun savedSession(preferences: AcceptanceMemoryPreferences): ServiceSession =
        gson.fromJson(preferences.values["session"] as String, ServiceSession::class.java)
    private fun assertUnrelated(preferences: AcceptanceMemoryPreferences) {
        assertEquals(true, preferences.values["dark"])
        assertEquals(1, preferences.values["text_size"])
        assertEquals(12, preferences.values["lyric_tempo"])
        assertEquals(true, preferences.values["lyric_original"])
        assertEquals("church", preferences.values["church_id"])
        assertEquals(setOf("church", "other"), preferences.values["favorite_churches_v1"])
    }
    companion object {
        private val BOOKMARK_KEYS = setOf("lyric_track_creed", "lyric_line_creed", "lyric_offset_creed", "lyric_phrase_creed",
            "lyric_saved_tempo_creed", "lyric_track_legacy_float", "lyric_track_legacy_boolean", "lyric_track_legacy_set")
    }
}

private enum class MemoryCommit { SUCCESS, FALSE_AFTER_MEMORY, THROW_AFTER_MEMORY }

/** Models Android's relevant ordering: mutate the in-memory map first, then report disk outcome. */
private class AcceptanceMemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    val outcomes = java.util.ArrayDeque<MemoryCommit>()
    val committedKeys = mutableListOf<Set<String>>()
    val memoryAfterCommit = mutableListOf<Map<String, Any?>>()
    var afterMemory: ((Int) -> Unit)? = null
    var throwOnRead = false
    var editCount = 0
    var commitCount = 0
    override fun getAll(): MutableMap<String, *> {
        check(!throwOnRead)
        return values.toMutableMap()
    }
    override fun getString(key: String?, defValue: String?): String? = if (key in values) values[key] as String? else defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (key in values) (values[key] as Set<String>?)?.toMutableSet() else defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor {
        editCount++
        return object : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String?, value: String?) = apply { updates[requireNotNull(key)] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { updates[requireNotNull(key)] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { updates[requireNotNull(key)] = value }
            override fun putLong(key: String?, value: Long) = apply { updates[requireNotNull(key)] = value }
            override fun putFloat(key: String?, value: Float) = apply { updates[requireNotNull(key)] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { updates[requireNotNull(key)] = value }
            override fun remove(key: String?) = apply { updates[requireNotNull(key)] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                commitCount++
                committedKeys += updates.keys.toSet()
                if (clear) values.clear()
                updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                memoryAfterCommit += values.toMap()
                afterMemory?.invoke(commitCount)
                return when (outcomes.pollFirst() ?: MemoryCommit.SUCCESS) {
                    MemoryCommit.SUCCESS -> true
                    MemoryCommit.FALSE_AFTER_MEMORY -> false
                    MemoryCommit.THROW_AFTER_MEMORY -> error("Disk write failed after memory was changed")
                }
            }
            override fun apply() { commit() }
        }
    }
}
