package ru.liturgyguide.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.DayReading
import ru.liturgyguide.model.PrayerLine
import ru.liturgyguide.model.ReadingDay
import ru.liturgyguide.model.StageText
import java.time.LocalDate

class ServicePlaybackStoreTest {
    private val date = LocalDate.of(2026, 11, 8)
    private val context = ReadingContext("church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)
    private val prayer = StageText("Молитва", "Оригинал", "Перевод", "Смысл", lines = listOf(
        PrayerLine("Первая строка", "Первый смысл"), PrayerLine("Вторая строка", "Второй смысл")))
    private val stages = listOf(
        LiturgyStage("first", 1, "Первый", "Описание", 1, listOf(prayer)),
        LiturgyStage("second", 2, "Второй", "Описание", 1, listOf(prayer.copy(type = "Другая молитва"))))
    private val plan = ServicePlaybackPlan.build(stages, date, null)
    private val engine = ServicePlaybackEngine(plan)
    private val snapshot = engine.snapshot(engine.tick(engine.play(engine.start(speed = PlaybackSpeed.SLOW), 0), 1_001), 1_001)
    private val stored = StoredServicePlayback(context, snapshot)
    private val encoded = StoredServicePlaybackCodec.encode(stored)

    @Test fun `codec roundtrip keeps exact quarters context and speed but no runtime clock`() {
        assertEquals(stored, StoredServicePlaybackCodec.decode(encoded))
        assertEquals(3_003L, requireNotNull(StoredServicePlaybackCodec.decode(encoded)).snapshot.elapsedQuarterMillis)
        val fields = JsonParser.parseString(encoded).asJsonObject.keySet()
        assertFalse("anchorMonotonicMillis" in fields)
        assertFalse("playing" in fields)
        val future = stored.copy(context = context.copy(serviceDate = "2026-11-09"))
        assertEquals(future, StoredServicePlaybackCodec.decode(StoredServicePlaybackCodec.encode(future)))
    }

    @Test fun `codec rejects missing unknown duplicated or extra top level data`() {
        val missing = mutate { remove("speed") }
        val unknown = mutate { addProperty("playing", true) }
        val duplicate = encoded.replaceFirst("{", "{\"version\":1,")
        for (json in listOf(missing, unknown, duplicate, "$encoded {}", "[$encoded]", encoded.dropLast(1) + ",}"))
            assertNull(json, StoredServicePlaybackCodec.decode(json))
    }

    @Test fun `numeric strings fractions exponent notation overflow and nonstandard numbers are not coerced`() {
        val strings = listOf("version", "contentVersion", "segmentIndex", "elapsedQuarterMillis").map { field ->
            mutate { addProperty(field, get(field).asString) }
        }
        val invalidNumbers = listOf("1.0", "1e0", "+1", "01", "9223372036854775808", "NaN")
            .map { encoded.replace("\"version\":1", "\"version\":$it") }
        for (json in strings + invalidNumbers) assertNull(json, StoredServicePlaybackCodec.decode(json))
        assertNull(StoredServicePlaybackCodec.decode(mutate { addProperty("segmentIndex", Int.MAX_VALUE.toLong() + 1) }))
        assertNull(StoredServicePlaybackCodec.decode(mutate { addProperty("elapsedQuarterMillis", -1) }))
        assertNull(StoredServicePlaybackCodec.decode(mutate { addProperty("contentVersion", 0) }))
    }

    @Test fun `strings enums nested values and non JSON escapes are strict`() {
        for (json in listOf(
            mutate { addProperty("speed", "normal") },
            mutate { addProperty("speed", 1) },
            mutate { add("speed", null) },
            mutate { add("context", JsonObject()) },
            mutate { addProperty("context", "{}") },
            mutate { addProperty("planFingerprint", "X".repeat(64)) },
            encoded.replace("\"SLOW\"", "TRUE"),
            encoded.replace("\"SLOW\"", "NULL"),
            encoded.replace("\"SLOW\"", "\"SLO\\'W\""),
            encoded.replace("\"SLOW\"", "\"SLO\nW\"")
        )) assertNull(json, StoredServicePlaybackCodec.decode(json))
    }

    @Test fun `bounded format rejects oversized and invalid encoded input`() {
        assertNull(StoredServicePlaybackCodec.decode(" ".repeat(StoredServicePlaybackCodec.MAX_JSON_CHARS + 1)))
        assertNull(StoredServicePlaybackCodec.decode(encoded + " ".repeat(StoredServicePlaybackCodec.MAX_JSON_CHARS)))
        assertThrows(IllegalArgumentException::class.java) {
            StoredServicePlaybackCodec.encode(stored.copy(context = context.copy(serviceDate = "bad")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            StoredServicePlaybackCodec.encode(stored.copy(snapshot = snapshot.copy(planFingerprint = "bad")))
        }
    }

    @Test fun `restore distinguishes absent from every kind of present invalid data`() {
        val preferences = AcceptanceMemoryPreferences()
        val store = ServicePlaybackStore(preferences)
        assertEquals(PlaybackRestoreResult.Absent, store.restore(plan, context))
        for (value in listOf(null, "", "{}", 1, true, setOf("old"))) {
            preferences.values[ServicePlaybackStore.PLAYBACK_KEY] = value
            assertEquals(PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.MALFORMED), store.restore(plan, context))
            assertNull(LegacyPlaybackMigration.resolve(plan, "first", preferences.values))
        }
    }

    @Test fun `valid restoration is paused with exact position and no monotonic anchor`() {
        val preferences = seeded()
        val result = ServicePlaybackStore(preferences).restore(plan, context) as PlaybackRestoreResult.Valid
        assertEquals(stored, result.stored)
        assertEquals(snapshot.elapsedQuarterMillis, result.state.elapsedQuarterMillis)
        assertEquals(PlaybackSpeed.SLOW, result.state.speed)
        assertFalse(result.state.isPlaying)
        assertNull(result.state.anchorMonotonicMillis)
        assertEquals(result.state, engine.tick(result.state, Long.MAX_VALUE))
        assertEquals(0, preferences.editCount)
    }

    @Test fun `equal content cannot restore a different parish date zone or source`() {
        val store = ServicePlaybackStore(seeded())
        val changed = listOf(context.copy(parishId = "other"), context.copy(serviceDate = "2026-11-09"),
            context.copy(timezone = "UTC"),
            context.copy(source = ReadingContextSource.USER, startEpochMillis = java.time.Instant.parse("2026-11-08T06:00:00Z").toEpochMilli()))
        changed.forEach { expected ->
            assertEquals(PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.CONTEXT_MISMATCH), store.restore(plan, expected))
        }
    }

    @Test fun `changed content version date and impossible offsets require explicit recovery`() {
        val preferences = seeded()
        val store = ServicePlaybackStore(preferences)
        val changedPlan = ServicePlaybackPlan.build(stages.map { it.copy(durationMin = 2) }, date, null)
        assertEquals(PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.PLAN_MISMATCH), store.restore(changedPlan, context))
        val futurePlan = ServicePlaybackPlan.build(stages, date.plusDays(1), null)
        assertEquals(PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.PLAN_MISMATCH), store.restore(futurePlan, context))
        for (bad in listOf(snapshot.copy(contentVersion = snapshot.contentVersion + 1),
            snapshot.copy(segmentIndex = plan.segments.size), snapshot.copy(elapsedQuarterMillis = 16_000))) {
            preferences.values[ServicePlaybackStore.PLAYBACK_KEY] = StoredServicePlaybackCodec.encode(stored.copy(snapshot = bad))
            assertEquals(PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.PLAN_MISMATCH), store.restore(plan, context))
        }
    }

    @Test fun `unreadable preferences are invalid rather than a migration opportunity`() {
        val preferences = AcceptanceMemoryPreferences().apply { throwOnRead = true }
        assertEquals(PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.UNREADABLE),
            ServicePlaybackStore(preferences).restore(plan, context))
    }

    @Test fun `migration preserves stable phrase but resets old tempo and elapsed without modifying input`() {
        val track = plan.tracksByStage.getValue("second").single()
        val values = mutableMapOf<String, Any>("lyric_track_second" to track.id,
            "lyric_phrase_${track.id}" to track.lines[1].id, "lyric_line_${track.id}" to 0,
            "lyric_tempo" to 8, "lyric_saved_tempo_${track.id}" to 16, "lyric_offset_${track.id}" to 12_345L,
            "dark" to true, "lyric_original" to true)
        val before = values.toMap()
        val state = requireNotNull(LegacyPlaybackMigration.resolve(plan, "second", values))
        assertEquals(plan.indexOfLine(track.id, track.lines[1].id), state.segmentIndex)
        assertEquals(0L, state.elapsedQuarterMillis)
        assertEquals(PlaybackSpeed.NORMAL, state.speed)
        assertFalse(state.isPlaying)
        assertEquals(before, values)
    }

    @Test fun `present missing or malformed phrase never falls back to the old numeric index`() {
        val track = plan.tracksByStage.getValue("first").single()
        for (phrase in listOf("removed-phrase", "", null, 42)) {
            val values = mapOf("lyric_track_first" to track.id, "lyric_phrase_${track.id}" to phrase, "lyric_line_${track.id}" to 1)
            val state = requireNotNull(LegacyPlaybackMigration.resolve(plan, "first", values))
            assertEquals(plan.indexOfLine(track.id, track.lines.first().id), state.segmentIndex)
        }
    }

    @Test fun `only an absent phrase ID enables the old index and invalid indices use the beginning`() {
        val track = plan.tracksByStage.getValue("first").single()
        val values = mutableMapOf<String, Any>("lyric_track_first" to track.id, "lyric_line_${track.id}" to 1)
        assertEquals(plan.indexOfLine(track.id, track.lines[1].id),
            requireNotNull(LegacyPlaybackMigration.resolve(plan, "first", values)).segmentIndex)
        for (invalid in listOf(-1, 99, 1L, "1")) {
            values["lyric_line_${track.id}"] = invalid
            assertEquals(plan.indexOfStage("first"), requireNotNull(LegacyPlaybackMigration.resolve(plan, "first", values)).segmentIndex)
        }
    }

    @Test fun `migration never borrows a track from a different stage and unknown stage is rejected`() {
        val other = plan.tracksByStage.getValue("second").single()
        val values = mapOf("lyric_track_first" to other.id, "lyric_phrase_${other.id}" to other.lines[1].id)
        assertEquals(engine.start("first"), LegacyPlaybackMigration.resolve(plan, "first", values))
        assertNull(LegacyPlaybackMigration.resolve(plan, "removed-stage", values))
        assertEquals(engine.start("first"), LegacyPlaybackMigration.resolve(plan, "first", mapOf("lyric_track_first" to 1)))
    }

    @Test fun `empty stage and missing reading migrate to their own paused first segment`() {
        val special = ServicePlaybackPlan.build(listOf(stages[0].copy(id = "preparation", texts = emptyList()),
            stages[1].copy(id = "epistle", texts = emptyList())), date, null)
        val preparation = requireNotNull(LegacyPlaybackMigration.resolve(special, "preparation", emptyMap<String, Any>()))
        val missing = requireNotNull(LegacyPlaybackMigration.resolve(special, "epistle", emptyMap<String, Any>()))
        assertTrue(special.segments[preparation.segmentIndex] is PlaybackSegment.Wait)
        assertTrue(special.segments[missing.segmentIndex] is PlaybackSegment.MissingReading)
        assertNull(PlaybackBookmark.at(special, preparation))
        assertNull(PlaybackBookmark.at(special, missing))
    }

    @Test fun `legacy Scripture address from another date cannot select an old reading`() {
        val epistle = stages[0].copy(id = "epistle")
        val day = ReadingDay(date.toString(), "https://example.org", "Чтения", "Примечание", listOf(
            DayReading("epistle", "Послание", "Текст на выбранную дату", "Объяснение")))
        val today = ServicePlaybackPlan.build(listOf(epistle), date, day)
        val oldTrack = today.tracksByStage.getValue("epistle").first { it.isScripture }
        val values = mapOf("lyric_track_epistle" to oldTrack.id, "lyric_phrase_${oldTrack.id}" to oldTrack.lines[0].id)
        val tomorrow = ServicePlaybackPlan.build(listOf(epistle), date.plusDays(1), day)
        val migrated = requireNotNull(LegacyPlaybackMigration.resolve(tomorrow, "epistle", values))
        assertEquals(0, migrated.segmentIndex)
        assertTrue(tomorrow.tracksByStage.getValue("epistle").none { it.isScripture })
        assertTrue(tomorrow.segments.any { it is PlaybackSegment.MissingReading })
    }

    @Test fun `manual bookmark lookup still works after migration while migration itself stays blocked`() {
        val track = plan.tracksByStage.getValue("first").single()
        val values = mapOf(ServicePlaybackStore.PLAYBACK_KEY to encoded, "lyric_phrase_${track.id}" to track.lines[1].id)
        assertNull(LegacyPlaybackMigration.resolve(plan, "first", values))
        assertEquals(PlaybackBookmark("first", track.id, track.lines[1].id), PlaybackBookmarks.forTrack(plan, track.id, values))
        assertNull(PlaybackBookmarks.forTrack(plan, "unknown", values))
    }

    private fun seeded() = AcceptanceMemoryPreferences().apply { values[ServicePlaybackStore.PLAYBACK_KEY] = encoded }
    private fun mutate(block: JsonObject.() -> Unit): String = JsonParser.parseString(encoded).asJsonObject.apply(block).toString()
}
