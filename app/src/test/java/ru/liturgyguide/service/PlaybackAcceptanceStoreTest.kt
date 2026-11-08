package ru.liturgyguide.service

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PrayerLine
import ru.liturgyguide.model.StageText
import java.time.Instant
import java.time.LocalDate

class PlaybackAcceptanceStoreTest {
    private val date = LocalDate.of(2026, 11, 8)
    private val context = ReadingContext("church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)
    private val prayer = StageText("Молитва", "Оригинал", "Перевод", "Смысл", lines = listOf(
        PrayerLine("Первая строка", "Первый смысл"), PrayerLine("Вторая строка", "Второй смысл")))
    private val stages = listOf(
        LiturgyStage("first", 1, "Первый", "Описание", 1, listOf(prayer)),
        LiturgyStage("second", 2, "Второй", "Описание", 1, listOf(prayer.copy(type = "Другая молитва"))))
    private val plan = ServicePlaybackPlan.build(stages, date, null)
    private val engine = ServicePlaybackEngine(plan)
    private val track = plan.tracksByStage.getValue("second").single()
    private val state = engine.seekLine(engine.start(), track.id, track.lines[1].id)
    private val snapshot = engine.snapshot(state, 0)
    private val bookmark = requireNotNull(PlaybackBookmark.at(plan, state))
    private val marker = ServiceSession("second", Instant.parse("2026-11-08T06:00:00Z").toEpochMilli(), anchorIsApproximate = true)
    private val gson = Gson()

    @Test fun `acceptance stores snapshot context paused marker and current bookmark in one editor`() {
        val preferences = seeded()
        assertTrue(accept(preferences))
        assertEquals(1, preferences.editCount)
        assertEquals(1, preferences.commitCount)
        val restored = ServicePlaybackStore(preferences).restore(plan, context) as PlaybackRestoreResult.Valid
        assertEquals(state, restored.state)
        assertEquals(marker, savedMarker(preferences))
        assertEquals("second", preferences.values["stage_id"])
        assertEquals(context, ReadingContextStore(preferences).restore())
        assertEquals(track.id, preferences.values["lyric_track_second"])
        assertEquals(track.lines[1].id, preferences.values["lyric_phrase_${track.id}"])
        assertEquals(1, preferences.values["lyric_line_${track.id}"])
        assertEquals(0L, preferences.values["lyric_offset_${track.id}"])
        assertFalse(preferences.values.containsKey("lyric_saved_tempo_${track.id}"))
        assertEquals("other-phrase", preferences.values["lyric_phrase_other"])
        assertGlobals(preferences)
    }

    @Test fun `reset removes old bookmarks but keeps the newly accepted current bookmark and snapshot`() {
        val preferences = seeded()
        assertTrue(accept(preferences, reset = true))
        assertFalse(preferences.values.containsKey("lyric_phrase_other"))
        assertFalse(preferences.values.containsKey("lyric_line_other"))
        assertEquals(bookmark, PlaybackBookmarks.forTrack(plan, track.id, preferences.values))
        assertTrue(ServicePlaybackStore(preferences).restore(plan, context) is PlaybackRestoreResult.Valid)
        assertEquals(setOf("lyric_track_second", "lyric_line_${track.id}", "lyric_phrase_${track.id}", "lyric_offset_${track.id}"),
            preferences.values.keys.filter(ReadingAcceptanceStore::isBookmarkKey).toSet())
        assertGlobals(preferences)
    }

    @Test fun `commit false rolls back existing new snapshot and touched bookmarks after Android memory changed`() {
        val preferences = seeded()
        val before = preferences.values.toMap()
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        assertFalse(accept(preferences, reset = true))
        assertEquals(before, preferences.values)
        assertEquals(2, preferences.commitCount)
        assertEquals(preferences.committedKeys[0], preferences.committedKeys[1])
        val failed = preferences.memoryAfterCommit.first()
        assertEquals(snapshot, StoredServicePlaybackCodec.decode(failed[ServicePlaybackStore.PLAYBACK_KEY] as String)?.snapshot)
        assertEquals(track.lines[1].id, failed["lyric_phrase_${track.id}"])
        assertFalse(failed.containsKey("lyric_phrase_other"))
    }

    @Test fun `failed first migration removes the newly created key and preserves every legacy value`() {
        val preferences = seeded().apply {
            values.remove(ServicePlaybackStore.PLAYBACK_KEY)
            values.remove("lyric_phrase_${track.id}")
            outcomes += MemoryCommit.THROW_AFTER_MEMORY
        }
        val before = preferences.values.toMap()
        assertFalse(accept(preferences))
        assertEquals(before, preferences.values)
        assertFalse(preferences.values.containsKey(ServicePlaybackStore.PLAYBACK_KEY))
        assertEquals(2, preferences.commitCount)
    }

    @Test fun `double write failure stays failed with one rollback and preserves unrelated concurrent writes`() {
        val preferences = seeded()
        val previousSnapshot = preferences.values[ServicePlaybackStore.PLAYBACK_KEY]
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.afterMemory = { count -> if (count == 1) {
            preferences.values["dark"] = false
            preferences.values["lyric_phrase_other"] = "concurrently-changed"
        } }
        assertFalse(accept(preferences))
        assertEquals(2, preferences.commitCount)
        assertEquals(previousSnapshot, preferences.values[ServicePlaybackStore.PLAYBACK_KEY])
        assertEquals(false, preferences.values["dark"])
        assertEquals("concurrently-changed", preferences.values["lyric_phrase_other"])
        assertTrue(preferences.committedKeys.all { "dark" !in it && "lyric_phrase_other" !in it })
    }

    @Test fun `wrong stage running marker and old stage offset cannot be accepted`() {
        val preferences = seeded()
        val store = ReadingAcceptanceStore(preferences)
        listOf(marker.copy(anchorStageId = "first"), marker.copy(autoAdvance = true), marker.copy(elapsedInStageMillis = 1)).forEach { bad ->
            assertFalse(store.acceptPlayback(plan, context, snapshot, bad, false, bookmark))
        }
        assertEquals(0, preferences.editCount)
    }

    @Test fun `wrong plan date invalid snapshot and foreign bookmark fail before opening editor`() {
        val preferences = seeded()
        val store = ReadingAcceptanceStore(preferences)
        assertFalse(store.acceptPlayback(plan, context.copy(serviceDate = "2026-11-09"), snapshot, marker, false, bookmark))
        assertFalse(store.acceptPlayback(plan, context, snapshot.copy(segmentIndex = -1), marker, false, bookmark))
        assertFalse(store.acceptPlayback(plan, context, snapshot.copy(planFingerprint = "0".repeat(64)), marker, false, bookmark))
        assertFalse(store.acceptPlayback(plan, context, snapshot, marker, false, bookmark.copy(lineId = track.lines[0].id)))
        assertFalse(store.acceptPlayback(plan, context, snapshot, marker, false, bookmark.copy(stageId = "first")))
        assertEquals(0, preferences.editCount)
    }

    @Test fun `Wait and End retain the last text bookmark without pretending to be a running marker`() {
        val preferences = seeded()
        for (delta in listOf(8_000L, 60_000L)) {
            val current = engine.tick(engine.play(engine.start("second"), 0), delta)
            val captured = engine.snapshot(current, delta)
            val retained = requireNotNull(PlaybackBookmark.at(plan, current))
            assertEquals(track.lines.last().id, retained.lineId)
            assertTrue(ReadingAcceptanceStore(preferences).acceptPlayback(plan, context, captured, marker, false, retained))
            val restored = ServicePlaybackStore(preferences).restore(plan, context) as PlaybackRestoreResult.Valid
            assertFalse(restored.state.isPlaying)
            assertEquals(captured.segmentIndex, restored.state.segmentIndex)
        }
    }

    @Test fun `legacy acceptance removes an existing new snapshot in the same transaction`() {
        val preferences = seeded()
        assertTrue(ReadingAcceptanceStore(preferences).accept(context, marker, resetBookmarks = false))
        assertFalse(preferences.values.containsKey(ServicePlaybackStore.PLAYBACK_KEY))
        assertEquals(1, preferences.commitCount)
        assertTrue(ServicePlaybackStore.PLAYBACK_KEY in preferences.committedKeys.single())
        assertEquals(marker, savedMarker(preferences))
        assertEquals("other-phrase", preferences.values["lyric_phrase_other"])
    }

    @Test fun `failed legacy acceptance restores the new snapshot together with the old marker`() {
        val preferences = seeded()
        val before = preferences.values.toMap()
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        assertFalse(ReadingAcceptanceStore(preferences).accept(context, marker, resetBookmarks = true))
        assertEquals(before, preferences.values)
        assertEquals(2, preferences.commitCount)
    }

    @Test fun `rollback preserves a malformed old new-format key exactly instead of enabling remigration`() {
        for (bad in listOf<Any>(19, setOf("not-json"), "broken-json")) {
            val preferences = seeded().apply {
                values[ServicePlaybackStore.PLAYBACK_KEY] = bad
                outcomes += MemoryCommit.FALSE_AFTER_MEMORY
            }
            assertFalse(accept(preferences))
            assertEquals(bad, preferences.values[ServicePlaybackStore.PLAYBACK_KEY])
            assertNull(LegacyPlaybackMigration.resolve(plan, "first", preferences.values))
        }
    }

    private fun accept(preferences: AcceptanceMemoryPreferences, reset: Boolean = false): Boolean =
        ReadingAcceptanceStore(preferences).acceptPlayback(plan, context, snapshot, marker, reset, bookmark)

    private fun seeded() = AcceptanceMemoryPreferences().apply {
        val old = ServiceSession("first", marker.anchorEpochMillis)
        values[ReadingContextStore.CONTEXT_KEY] = ReadingContextCodec.encode(context)
        values[ServicePlaybackStore.PLAYBACK_KEY] = StoredServicePlaybackCodec.encode(
            StoredServicePlayback(context, engine.snapshot(engine.start(), 0)))
        values["session"] = gson.toJson(old)
        values["stage_id"] = "first"
        values["lyric_track_second"] = track.id
        values["lyric_line_${track.id}"] = 0
        values["lyric_phrase_${track.id}"] = track.lines[0].id
        values["lyric_offset_${track.id}"] = 7_777L
        values["lyric_saved_tempo_${track.id}"] = 16
        values["lyric_phrase_other"] = "other-phrase"
        values["lyric_line_other"] = 9
        values["lyric_original"] = true
        values["lyric_tempo"] = 8
        values["dark"] = true
        values["text_size"] = 2
    }

    private fun savedMarker(preferences: AcceptanceMemoryPreferences): ServiceSession =
        gson.fromJson(preferences.values["session"] as String, ServiceSession::class.java)

    private fun assertGlobals(preferences: AcceptanceMemoryPreferences) {
        assertEquals(true, preferences.values["lyric_original"])
        assertEquals(8, preferences.values["lyric_tempo"])
        assertEquals(true, preferences.values["dark"])
        assertEquals(2, preferences.values["text_size"])
    }
}
