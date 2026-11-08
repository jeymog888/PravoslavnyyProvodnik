package ru.liturgyguide.service

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Uses the existing Android-ordering fault fixture: memory changes before commit reports failure. */
class PersonalStoresRollbackTest {
    private val church = SavedChurchRecord("custom:00000000-0000-0000-0000-000000000001", "Храм Николая", "Казань", "", "Europe/Moscow")
    private val another = church.copy(id = "custom:00000000-0000-0000-0000-000000000002", name = "Другой храм")
    private val start = UserServiceStart(church.id, "2026-11-08", "09:30")
    private val available = setOf(church.id, another.id)
    private val failures = listOf(MemoryCommit.FALSE_AFTER_MEMORY, MemoryCommit.THROW_AFTER_MEMORY)

    @Test fun `first personal save replacement and removal restore the exact old memory on false or throw`() {
        for (failure in failures) for (operation in listOf("first", "replace", "remove")) {
            val preferences = AcceptanceMemoryPreferences()
            val previous = if (operation == "first") emptyList() else listOf(start, start.copy(date = "2026-11-09"))
            if (previous.isNotEmpty()) preferences.values[UserServiceStartStore.RECORDS_KEY] =
                " \n" + UserServiceStartCodec.encode(previous) + "\n"
            preferences.values["dark"] = true
            val before = preferences.values.toMap()
            preferences.outcomes += failure
            val store = UserServiceStartStore(preferences)
            val accepted = when (operation) {
                "remove" -> store.remove(church.id, LocalDate.parse(start.date))
                "replace" -> store.save(start.copy(time = "10:00"), church.asParish())
                else -> store.save(start, church.asParish())
            }
            assertFalse("$operation / $failure", accepted)
            assertNotEquals(before[UserServiceStartStore.RECORDS_KEY], preferences.memoryAfterCommit.first()[UserServiceStartStore.RECORDS_KEY])
            assertEquals(before, preferences.values)
            assertEquals(previous, store.records())
            assertEquals(previous, UserServiceStartStore(preferences).records())
            assertOnlyTwoCommits(preferences, UserServiceStartStore.RECORDS_KEY)
        }
    }

    @Test fun `first directory save and replacement restore absence or the original encoded value`() {
        for (failure in failures) for (first in listOf(true, false)) {
            val preferences = AcceptanceMemoryPreferences()
            if (!first) preferences.values[ChurchDirectoryStore.RECORDS_KEY] = " \n" + SavedChurchCodec.encode(listOf(church, another)) + "\n"
            val before = preferences.values.toMap()
            preferences.outcomes += failure
            val store = ChurchDirectoryStore(preferences)
            assertFalse(store.save(church.copy(name = "Исправленное название")))
            assertNotEquals(before[ChurchDirectoryStore.RECORDS_KEY], preferences.memoryAfterCommit.first()[ChurchDirectoryStore.RECORDS_KEY])
            assertEquals(before, preferences.values)
            assertEquals(if (first) emptyList<SavedChurchRecord>() else listOf(church, another), store.records())
            assertEquals(store.records(), ChurchDirectoryStore(preferences).records())
            assertOnlyTwoCommits(preferences, ChurchDirectoryStore.RECORDS_KEY)
        }
    }

    @Test fun `failed favorite add removal and first toggle restore raw favorites including unavailable IDs`() {
        for (failure in failures) for (id in listOf(church.id, another.id, "first")) {
            val preferences = AcceptanceMemoryPreferences()
            if (id != "first") preferences.values[ChurchDirectoryStore.FAVORITES_KEY] = setOf(church.id, "not_available")
            val before = preferences.values.toMap()
            val store = ChurchDirectoryStore(preferences)
            val visibleBefore = store.favorites().intersect(available)
            preferences.outcomes += failure
            assertEquals(visibleBefore, store.toggleFavorite(if (id == "first") church.id else id, available))
            assertNotEquals(before[ChurchDirectoryStore.FAVORITES_KEY], preferences.memoryAfterCommit.first()[ChurchDirectoryStore.FAVORITES_KEY])
            assertEquals(before, preferences.values)
            assertEquals(if (id == "first") emptySet<String>() else setOf(church.id, "not_available"), store.favorites())
            assertOnlyTwoCommits(preferences, ChurchDirectoryStore.FAVORITES_KEY)
        }
    }

    @Test fun `all supported old preference types are restored without coercing damaged storage`() {
        val values: List<Any> = listOf("damaged JSON", 42, 42L, 1.25f, true, setOf("old", "values"))
        for (target in Target.entries) for (old in values) {
            val preferences = AcceptanceMemoryPreferences()
            preferences.values[target.key] = old
            preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
            assertRejected(target, preferences)
            val restored = preferences.values[target.key]
            assertEquals("${target.key} / ${old.javaClass}", old, restored)
            if (old !is Set<*>) assertEquals(old.javaClass, restored!!.javaClass)
            assertOnlyTwoCommits(preferences, target.key)
        }
    }

    @Test fun `rollback false or throw after memory restoration never becomes success or another retry`() {
        for (target in Target.entries) for (rollback in failures) {
            val preferences = seeded()
            val before = preferences.values.toMap()
            preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
            preferences.outcomes += rollback
            assertRejected(target, preferences)
            assertEquals(before, preferences.values)
            assertOnlyTwoCommits(preferences, target.key)
        }
    }

    @Test fun `an unavailable rollback editor is best effort and never reports the attempted write as accepted`() {
        for (target in Target.entries) {
            val delegate = seeded()
            val before = delegate.values[target.key]
            delegate.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
            var attempts = 0
            val preferences = object : SharedPreferences by delegate {
                override fun edit(): SharedPreferences.Editor {
                    attempts++
                    check(attempts != 2) { "Rollback editor unavailable" }
                    return delegate.edit()
                }
            }
            assertRejected(target, preferences)
            assertEquals(2, attempts)
            assertEquals(1, delegate.commitCount)
            assertNotEquals("No restoration can be promised when the rollback editor is unavailable", before, delegate.values[target.key])
        }
    }

    @Test fun `rollback touches only its key and preserves unrelated callback writes`() {
        for (target in Target.entries) {
            val preferences = seeded()
            val oldTarget = preferences.values[target.key]
            preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
            preferences.afterMemory = { count -> if (count == 1) {
                Target.entries.filter { it != target }.forEach { preferences.values[it.key] = "changed during commit" }
                preferences.values["dark"] = false
                preferences.values["new_unrelated"] = 7L
            } }
            assertRejected(target, preferences)
            assertEquals(oldTarget, preferences.values[target.key])
            Target.entries.filter { it != target }.forEach { assertEquals("changed during commit", preferences.values[it.key]) }
            assertEquals(false, preferences.values["dark"])
            assertEquals(7L, preferences.values["new_unrelated"])
            assertOnlyTwoCommits(preferences, target.key)
        }
    }

    @Test fun `favorite rollback owns a copy of the old set`() {
        val preferences = AcceptanceMemoryPreferences()
        val original = mutableSetOf(church.id, "not_available")
        preferences.values[ChurchDirectoryStore.FAVORITES_KEY] = original
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.afterMemory = { count -> if (count == 1) original += "mutated after snapshot" }
        assertEquals(setOf(church.id), ChurchDirectoryStore(preferences).toggleFavorite(another.id, available))
        assertTrue("mutated after snapshot" in original)
        assertEquals(setOf(church.id, "not_available"), preferences.values[ChurchDirectoryStore.FAVORITES_KEY])
        assertOnlyTwoCommits(preferences, ChurchDirectoryStore.FAVORITES_KEY)
    }

    @Test fun `successful writes use one commit and publish the intended records or favorites`() {
        val personal = AcceptanceMemoryPreferences()
        assertTrue(UserServiceStartStore(personal).save(start, church.asParish()))
        assertEquals(listOf(start), UserServiceStartStore(personal).records())
        val directory = AcceptanceMemoryPreferences()
        assertTrue(ChurchDirectoryStore(directory).save(church))
        assertEquals(listOf(church), ChurchDirectoryStore(directory).records())
        val favorites = AcceptanceMemoryPreferences()
        assertEquals(setOf(church.id), ChurchDirectoryStore(favorites).toggleFavorite(church.id, available))
        assertEquals(setOf(church.id), ChurchDirectoryStore(favorites).favorites())
        listOf(personal, directory, favorites).forEach { assertEquals(1, it.editCount); assertEquals(1, it.commitCount) }
    }

    @Test fun `unreadable snapshots do not open an editor`() {
        for (target in Target.entries) {
            val preferences = seeded()
            val before = preferences.values.toMap()
            preferences.throwOnRead = true
            assertRejected(target, preferences)
            assertEquals(before, preferences.values)
            assertEquals(0, preferences.editCount)
        }
    }

    private fun assertRejected(target: Target, preferences: SharedPreferences) {
        when (target) {
            Target.PERSONAL -> assertFalse(UserServiceStartStore(preferences).save(start.copy(time = "10:00"), church.asParish()))
            Target.DIRECTORY -> assertFalse(ChurchDirectoryStore(preferences).save(church.copy(name = "Исправленное название")))
            Target.FAVORITES -> {
                val store = ChurchDirectoryStore(preferences)
                val current = store.favorites().intersect(available)
                assertEquals(current, store.toggleFavorite(another.id, available))
            }
        }
    }

    private fun seeded() = AcceptanceMemoryPreferences().apply {
        values[UserServiceStartStore.RECORDS_KEY] = UserServiceStartCodec.encode(listOf(start))
        values[ChurchDirectoryStore.RECORDS_KEY] = SavedChurchCodec.encode(listOf(church))
        values[ChurchDirectoryStore.FAVORITES_KEY] = setOf(church.id, "not_available")
        values["dark"] = true
    }

    private fun assertOnlyTwoCommits(preferences: AcceptanceMemoryPreferences, key: String) {
        assertEquals(2, preferences.editCount)
        assertEquals(2, preferences.commitCount)
        assertEquals(listOf(setOf(key), setOf(key)), preferences.committedKeys)
    }

    private enum class Target(val key: String) {
        PERSONAL(UserServiceStartStore.RECORDS_KEY), DIRECTORY(ChurchDirectoryStore.RECORDS_KEY), FAVORITES(ChurchDirectoryStore.FAVORITES_KEY)
    }
}
