package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test

class ChurchSelectionGuardTest {
    @Test fun `empty first choice and repeat choice do not ask for confirmation`() {
        for (selected in listOf(null, OLD)) {
            val scenario = Scenario(selectedId = selected, seedReading = false)
            val target = selected ?: NEXT

            assertEquals(ChurchSelectionResult.SELECTED, scenario.request(target))
            assertNull(scenario.guard.pending)
            assertEquals(target, scenario.preferences.values["church_id"])
            assertEquals(1, scenario.selectedCalls)
            assertEquals(listOf(target to (selected == target)), scenario.commitCalls)
        }
    }

    @Test fun `another church without reading is selected directly`() {
        val scenario = Scenario(seedReading = false)

        assertEquals(ChurchSelectionResult.SELECTED, scenario.request(NEXT))
        assertNull(scenario.guard.pending)
        assertEquals(NEXT, scenario.preferences.values["church_id"])
        assertEquals(listOf(NEXT to false), scenario.commitCalls)
    }

    @Test fun `same recovered church keeps all reading and only confirms its explicit selection`() {
        val scenario = Scenario()
        scenario.preferences.values.remove("church_id")
        val before = scenario.savedValues()

        assertEquals(ChurchSelectionResult.SELECTED, scenario.request(OLD))
        assertEquals(before + ("church_id" to OLD), scenario.savedValues())
        assertEquals(listOf(setOf("church_id")), scenario.preferences.committedKeys)
        assertNull(scenario.guard.pending)
    }

    @Test fun `every affected reading key protects even malformed or incomplete saved data`() {
        val samples = listOf(
            ReadingContextStore.CONTEXT_KEY to "{malformed context",
            ServicePlaybackStore.PLAYBACK_KEY to "{malformed snapshot",
            ReadingAcceptanceStore.SESSION_KEY to "legacy session",
            ReadingAcceptanceStore.STAGE_KEY to "unknown removed stage",
            "lyric_track_creed" to "track",
            "lyric_line_creed" to 3,
            "lyric_phrase_creed" to "phrase four",
            "lyric_offset_creed" to 700L,
            "lyric_saved_tempo_creed" to 8,
            ReadingContextStore.CONTEXT_KEY to 42,
            ServicePlaybackStore.PLAYBACK_KEY to false,
            ReadingAcceptanceStore.STAGE_KEY to ""
        )
        for ((key, value) in samples) {
            for (selected in listOf(OLD, null)) {
                val scenario = Scenario(selectedId = selected, seedReading = false)
                scenario.preferences.values[key] = value
                val before = scenario.savedValues()

                assertEquals("$key with selection $selected", ChurchSelectionResult.PENDING, scenario.request(NEXT))
                assertEquals(NEXT, scenario.guard.pending?.targetId)
                assertEquals(before, scenario.savedValues())
                assertEquals(0, scenario.preferences.editCount)
                assertEquals(0, scenario.selectedCalls)
            }
        }
    }

    @Test fun `accepted reading remains protected when its saved keys are missing`() {
        val scenario = Scenario(selectedId = null, seedReading = false)
        scenario.hasActiveReading = true

        assertEquals(ChurchSelectionResult.PENDING, scenario.request(NEXT))
        assertEquals(0, scenario.preferences.editCount)
        assertEquals(0, scenario.selectedCalls)
    }

    @Test fun `cancellation preserves the full preferences snapshot and invalidates its confirm action`() {
        val scenario = Scenario()
        val before = scenario.savedValues()
        assertEquals(ChurchSelectionResult.PENDING, scenario.request(NEXT))
        val pending = requireNotNull(scenario.guard.pending)

        scenario.guard.cancel()

        assertEquals(before, scenario.savedValues())
        assertNull(scenario.guard.pending)
        assertEquals(ChurchSelectionResult.STALE, scenario.confirm(pending))
        assertEquals(before, scenario.savedValues())
        assertEquals(0, scenario.preferences.editCount)
        assertEquals(0, scenario.selectedCalls)
    }

    @Test fun `confirmed change uses one atomic clear and retains unrelated preferences`() {
        val scenario = Scenario()
        val before = scenario.savedValues()
        assertEquals(ChurchSelectionResult.PENDING, scenario.request(NEXT))
        assertEquals(0, scenario.preferences.editCount)
        val pending = requireNotNull(scenario.guard.pending)

        assertEquals(ChurchSelectionResult.SELECTED, scenario.confirm(pending))

        assertEquals(before.filterKeys { it !in READING_KEYS } + ("church_id" to NEXT), scenario.savedValues())
        assertEquals(listOf(READING_KEYS + "church_id"), scenario.preferences.committedKeys)
        assertEquals(listOf(NEXT to false), scenario.commitCalls)
        assertEquals(1, scenario.selectedCalls)
        assertEquals(NEXT, scenario.selectedId)
        assertNull(scenario.guard.pending)
    }

    @Test fun `failed confirmed change rolls back the whole reading and can retry the same pending choice`() {
        val scenario = Scenario()
        val before = scenario.savedValues()
        scenario.request(NEXT)
        val pending = requireNotNull(scenario.guard.pending)
        scenario.preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY

        assertEquals(ChurchSelectionResult.FAILED, scenario.confirm(pending))
        assertEquals(before, scenario.savedValues())
        assertEquals(OLD, scenario.selectedId)
        assertEquals(0, scenario.selectedCalls)
        assertSame(pending, scenario.guard.pending)
        assertEquals(2, scenario.preferences.commitCount)

        assertEquals(ChurchSelectionResult.SELECTED, scenario.confirm(pending))
        assertEquals(NEXT, scenario.selectedId)
        assertEquals(NEXT, scenario.preferences.values["church_id"])
        assertEquals(1, scenario.selectedCalls)
        assertEquals(3, scenario.preferences.commitCount)
        assertNull(scenario.guard.pending)
    }

    @Test fun `failed direct selection is an error without a selected callback or pending choice`() {
        val scenario = Scenario(selectedId = null, seedReading = false)
        val before = scenario.savedValues()
        scenario.preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY

        assertEquals(ChurchSelectionResult.FAILED, scenario.request(NEXT))
        assertEquals(before, scenario.savedValues())
        assertNull(scenario.selectedId)
        assertNull(scenario.guard.pending)
        assertEquals(0, scenario.selectedCalls)
    }

    @Test fun `duplicate request keeps one pending action and repeated confirm cannot reset twice`() {
        val scenario = Scenario()
        scenario.request(NEXT)
        val pending = requireNotNull(scenario.guard.pending)
        var replacementCallbackCalls = 0

        assertEquals(ChurchSelectionResult.PENDING, scenario.guard.request(NEXT, scenario.snapshot(), AVAILABLE,
            commit = { _, _ -> fail("A duplicate request replaced the pending commit"); false },
            onSelected = { replacementCallbackCalls++ }))
        assertSame(pending, scenario.guard.pending)
        assertEquals(ChurchSelectionResult.SELECTED, scenario.confirm(pending))
        assertEquals(ChurchSelectionResult.STALE, scenario.confirm(pending))
        assertEquals(1, scenario.preferences.commitCount)
        assertEquals(1, scenario.selectedCalls)
        assertEquals(0, replacementCallbackCalls)
    }

    @Test fun `changing the requested target invalidates the previous action without choosing either early`() {
        val scenario = Scenario()
        val before = scenario.savedValues()
        scenario.request(NEXT)
        val oldPending = requireNotNull(scenario.guard.pending)

        assertEquals(ChurchSelectionResult.PENDING, scenario.request(THIRD))
        val newPending = requireNotNull(scenario.guard.pending)
        assertNotSame(oldPending, newPending)
        assertEquals(ChurchSelectionResult.STALE, scenario.confirm(oldPending))
        assertSame(newPending, scenario.guard.pending)
        assertEquals(before, scenario.savedValues())
        assertEquals(ChurchSelectionResult.SELECTED, scenario.confirm(newPending))
        assertEquals(THIRD, scenario.selectedId)
        assertEquals(listOf(THIRD to false), scenario.commitCalls)
    }

    @Test fun `a removed target cannot be selected by either a new or pending action`() {
        val scenario = Scenario()
        val before = scenario.savedValues()
        scenario.request(NEXT)
        val pending = requireNotNull(scenario.guard.pending)

        assertEquals(ChurchSelectionResult.STALE,
            scenario.guard.confirm(pending, scenario.snapshot(), AVAILABLE - NEXT))
        assertNull(scenario.guard.pending)
        assertEquals(ChurchSelectionResult.STALE,
            scenario.guard.request(NEXT, scenario.snapshot(), AVAILABLE - NEXT, scenario::commit))
        assertEquals(before, scenario.savedValues())
        assertEquals(0, scenario.preferences.editCount)
    }

    @Test fun `changed raw reading or parish values invalidate pending without clearing the replacement`() {
        val changes = listOf<Pair<String, Any?>>(
            ReadingContextStore.CONTEXT_KEY to "replacement context with another date",
            ServicePlaybackStore.PLAYBACK_KEY to "replacement snapshot with another offset and speed",
            ReadingAcceptanceStore.SESSION_KEY to "replacement session",
            ReadingAcceptanceStore.STAGE_KEY to "anaphora",
            "lyric_phrase_creed" to "replacement phrase",
            "lyric_offset_creed" to 1_400L,
            "church_id" to THIRD
        )
        for ((key, value) in changes) {
            val scenario = Scenario()
            scenario.request(NEXT)
            val pending = requireNotNull(scenario.guard.pending)
            scenario.preferences.values[key] = value
            val replacement = scenario.savedValues()

            assertEquals(key, ChurchSelectionResult.STALE, scenario.confirm(pending))
            assertEquals(replacement, scenario.savedValues())
            assertEquals(0, scenario.preferences.editCount)
            assertEquals(0, scenario.selectedCalls)
            assertNull(scenario.guard.pending)
        }
    }

    @Test fun `new reading generation rejects an old action even when saved values are identical`() {
        val scenario = Scenario()
        scenario.request(NEXT)
        val pending = requireNotNull(scenario.guard.pending)
        val before = scenario.savedValues()
        scenario.readingGeneration++

        assertEquals(ChurchSelectionResult.STALE, scenario.confirm(pending))
        assertEquals(before, scenario.savedValues())
        assertEquals(0, scenario.preferences.editCount)
    }

    @Test fun `a changed resolved church invalidates pending even before an explicit selection is stored`() {
        val scenario = Scenario()
        scenario.preferences.values.remove("church_id")
        scenario.request(NEXT)
        val pending = requireNotNull(scenario.guard.pending)
        val before = scenario.savedValues()
        scenario.selectedId = THIRD

        assertEquals(ChurchSelectionResult.STALE, scenario.confirm(pending))
        assertEquals(before, scenario.savedValues())
        assertEquals(0, scenario.preferences.editCount)
    }

    @Test fun `unrelated settings favorites and a newly saved church do not invalidate a pending choice`() {
        val scenario = Scenario()
        scenario.request(NEXT)
        val pending = requireNotNull(scenario.guard.pending)
        scenario.preferences.values["dark"] = false
        scenario.preferences.values["text_size"] = 2
        scenario.preferences.values[ChurchDirectoryStore.FAVORITES_KEY] = setOf(THIRD)
        scenario.preferences.values[ChurchDirectoryStore.RECORDS_KEY] = "newly saved directory"
        val updated = scenario.savedValues()

        assertEquals(ChurchSelectionResult.SELECTED, scenario.confirm(pending))
        assertEquals(updated.filterKeys { it !in READING_KEYS } + ("church_id" to NEXT), scenario.savedValues())
    }

    @Test fun `recreated owner cannot confirm an old owners action and disposal disables captured callbacks`() {
        val scenario = Scenario()
        scenario.request(NEXT)
        val oldPending = requireNotNull(scenario.guard.pending)
        val before = scenario.savedValues()
        scenario.guard.close()
        val recreated = ChurchSelectionGuard()

        assertEquals(ChurchSelectionResult.STALE, scenario.confirm(oldPending))
        assertEquals(ChurchSelectionResult.STALE, scenario.request(NEXT))
        assertEquals(ChurchSelectionResult.STALE, recreated.confirm(oldPending, scenario.snapshot(), AVAILABLE))
        assertEquals(before, scenario.savedValues())
        assertEquals(0, scenario.preferences.editCount)

        assertEquals(ChurchSelectionResult.PENDING, recreated.request(NEXT, scenario.snapshot(), AVAILABLE, scenario::commit))
        val newPending = requireNotNull(recreated.pending)
        assertEquals(ChurchSelectionResult.STALE, recreated.confirm(oldPending, scenario.snapshot(), AVAILABLE))
        assertSame(newPending, recreated.pending)
        assertEquals(ChurchSelectionResult.SELECTED, recreated.confirm(newPending, scenario.snapshot(), AVAILABLE))
        assertEquals(NEXT, scenario.selectedId)
    }

    @Test fun `snapshot owns mutable set values so an in place legacy bookmark change is detected`() {
        val scenario = Scenario()
        val oldSet = mutableSetOf("legacy first", "legacy second")
        scenario.preferences.values["lyric_track_legacy_set"] = oldSet
        scenario.request(NEXT)
        val pending = requireNotNull(scenario.guard.pending)
        oldSet += "new reading"
        val changed = scenario.savedValues()

        assertEquals(ChurchSelectionResult.STALE, scenario.confirm(pending))
        assertEquals(changed, scenario.savedValues())
        assertEquals(0, scenario.preferences.editCount)
    }

    private class Scenario(var selectedId: String? = OLD, seedReading: Boolean = true) {
        val preferences = AcceptanceMemoryPreferences().apply {
            if (selectedId != null) values["church_id"] = selectedId
            if (seedReading) {
                values[ReadingContextStore.CONTEXT_KEY] = ReadingContextCodec.encode(
                    ReadingContext(OLD, "2026-11-09", "Europe/Moscow", ReadingContextSource.MANUAL))
                values[ServicePlaybackStore.PLAYBACK_KEY] = "preserved snapshot, offset and speed"
                values[ReadingAcceptanceStore.SESSION_KEY] = "preserved legacy session"
                values[ReadingAcceptanceStore.STAGE_KEY] = "creed"
                values["lyric_track_creed"] = "creed-track"
                values["lyric_line_creed"] = 3
                values["lyric_phrase_creed"] = "creed-phrase-four"
                values["lyric_offset_creed"] = 700L
                values["lyric_saved_tempo_creed"] = 8
                values["lyric_track_legacy_set"] = setOf("legacy first", "legacy second")
            }
            values["dark"] = true
            values["text_size"] = 1
            values["lyric_original"] = true
            values["lyric_tempo"] = 12
            values[ChurchDirectoryStore.FAVORITES_KEY] = setOf(OLD, NEXT)
            values[ChurchDirectoryStore.RECORDS_KEY] = "saved church directory"
            values[UserServiceStartStore.RECORDS_KEY] = "personal service times"
        }
        val guard = ChurchSelectionGuard()
        var readingGeneration = 4
        var hasActiveReading = false
        var selectedCalls = 0
        val commitCalls = mutableListOf<Pair<String, Boolean>>()

        fun snapshot() = ChurchSelectionSnapshot.capture(selectedId, readingGeneration, hasActiveReading, preferences.all)
        fun request(targetId: String) = guard.request(targetId, snapshot(), AVAILABLE, ::commit) { selectedCalls++ }
        fun confirm(pending: ChurchSelectionGuard.Pending) = guard.confirm(pending, snapshot(), AVAILABLE)
        fun commit(targetId: String, preserveReading: Boolean): Boolean {
            commitCalls += targetId to preserveReading
            val store = ReadingAcceptanceStore(preferences)
            val saved = if (preserveReading) store.confirmChurch(targetId) else store.clearReading(targetId)
            if (saved) selectedId = targetId
            return saved
        }
        fun savedValues(): Map<String, Any?> = preferences.values.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
    }

    companion object {
        private const val OLD = "old-parish"
        private const val NEXT = "next-parish"
        private const val THIRD = "third-parish"
        private val AVAILABLE = setOf(OLD, NEXT, THIRD)
        private val READING_KEYS = setOf(
            ReadingContextStore.CONTEXT_KEY, ServicePlaybackStore.PLAYBACK_KEY,
            ReadingAcceptanceStore.SESSION_KEY, ReadingAcceptanceStore.STAGE_KEY,
            "lyric_track_creed", "lyric_line_creed", "lyric_phrase_creed", "lyric_offset_creed",
            "lyric_saved_tempo_creed", "lyric_track_legacy_set"
        )
    }
}
