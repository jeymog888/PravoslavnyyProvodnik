package ru.liturgyguide.service

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.model.StageText
import java.time.Instant
import java.time.LocalDate

class Published05PlaybackStoreTest {
    private val date = LocalDate.of(2026, 11, 8)
    private val context = ReadingContext("church", date.toString(), "Europe/Moscow",
        ReadingContextSource.PUBLISHED, Instant.parse("2026-11-08T06:00:00Z").toEpochMilli())
    private val historical = Historical05Content(::asset)
    private val oldPlan = requireNotNull(historical.loadPlan(date))
    private val newPlan = expanded(oldPlan)
    private val oldIndex = oldPlan.segments.indexOfFirst { it is PlaybackSegment.Text && it.stageId == "anaphora" }
    private val snapshot = ServicePlaybackEngine(oldPlan).snapshot(
        ServicePlaybackState(oldPlan.fingerprint, oldIndex, 12_345, PlaybackSpeed.FAST), 0)
    private val stored = StoredServicePlayback(context, snapshot)

    @Test fun `existing store constructor keeps changed plan invalid until historical reader is supplied`() {
        val preferences = seeded()
        assertEquals(invalidPlan(), ServicePlaybackStore(preferences).restore(newPlan, context))
        assertTrue(ServicePlaybackStore(preferences, historical).restore(newPlan, context) is PlaybackRestoreResult.MigrationCandidate)
        assertEquals(0, preferences.editCount)
    }

    @Test fun `current valid snapshot wins without opening even unavailable historical resources`() {
        val preferences = seeded()
        var reads = 0
        val store = ServicePlaybackStore(preferences, Historical05Content { reads++; error("Unavailable resource") })
        assertTrue(store.restore(oldPlan, context) is PlaybackRestoreResult.Valid)
        val currentState = ServicePlaybackEngine(newPlan).start("anaphora", PlaybackSpeed.SLOW)
        val current = StoredServicePlayback(context, ServicePlaybackEngine(newPlan).snapshot(currentState, 0))
        preferences.values[ServicePlaybackStore.PLAYBACK_KEY] = StoredServicePlaybackCodec.encode(current)
        val result = store.restore(newPlan, context) as PlaybackRestoreResult.Valid
        assertEquals(current, result.stored)
        assertEquals(currentState, result.state)
        assertEquals(0, reads)
        assertEquals(0, preferences.editCount)
    }

    @Test fun `candidate separates original snapshot from shifted new state and never writes any preference`() {
        val preferences = seeded().apply {
            values[ReadingAcceptanceStore.SESSION_KEY] = "invalid old marker"
            values[ReadingAcceptanceStore.STAGE_KEY] = "unrelated"
            values["lyric_phrase_unrelated-track"] = "unrelated-line"
            values["lyric_original"] = true
            values["lyric_tempo"] = 16
            values["dark"] = true
        }
        val before = preferences.values.toMap()
        val opened = mutableListOf<String>()
        val store = ServicePlaybackStore(preferences, Historical05Content { name -> opened += name; asset(name) })
        val result = store.restore(newPlan, context) as PlaybackRestoreResult.MigrationCandidate
        val oldText = oldPlan.segments[oldIndex] as PlaybackSegment.Text
        assertEquals(stored, result.originalStored)
        assertEquals(context, result.originalStored.context)
        assertEquals(newPlan.indexOfLine(oldText.trackId, oldText.lineId), result.state.segmentIndex)
        assertNotEquals(oldIndex, result.state.segmentIndex)
        assertEquals(oldText, newPlan.segments[result.state.segmentIndex])
        assertEquals(newPlan.fingerprint, result.state.planFingerprint)
        assertNotEquals(result.originalStored.snapshot.planFingerprint, result.state.planFingerprint)
        assertEquals(12_345L, result.state.elapsedQuarterMillis)
        assertEquals(PlaybackSpeed.FAST, result.state.speed)
        assertTrue(ServicePlaybackEngine(newPlan).isValid(result.state))
        assertFalse(result.state.isPlaying)
        assertNull(result.note)
        assertEquals(listOf("published_0_5/liturgy_stages.json", "published_0_5/daily_readings.json"), opened)
        assertEquals(before, preferences.values)
        assertEquals(0, preferences.editCount)
        assertEquals(0, preferences.commitCount)
        assertNull(LegacyPlaybackMigration.resolve(newPlan, "anaphora", preferences.values))
        assertEquals(result, store.restore(newPlan, context))
    }

    @Test fun `absent malformed and unreadable snapshots never open the historical reader`() {
        val preferences = AcceptanceMemoryPreferences()
        var reads = 0
        val store = ServicePlaybackStore(preferences, Historical05Content { reads++; asset(it) })
        assertEquals(PlaybackRestoreResult.Absent, store.restore(newPlan, context))
        val encoded = StoredServicePlaybackCodec.encode(stored)
        val malformed = listOf(null, "", "{}", 7, true,
            encoded.replaceFirst("{", "{\"version\":1,"),
            encoded.replace("\"segmentIndex\":$oldIndex", "\"segmentIndex\":-1"))
        malformed.forEach { value ->
            preferences.values[ServicePlaybackStore.PLAYBACK_KEY] = value
            assertEquals(PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.MALFORMED), store.restore(newPlan, context))
            assertNull(LegacyPlaybackMigration.resolve(newPlan, "anaphora", preferences.values))
        }
        preferences.throwOnRead = true
        assertEquals(PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.UNREADABLE), store.restore(newPlan, context))
        assertEquals(0, reads)
        assertEquals(0, preferences.editCount)
    }

    @Test fun `every context field is checked before historical IO including source and accepted start`() {
        val preferences = seeded()
        var reads = 0
        val store = ServicePlaybackStore(preferences, Historical05Content { reads++; asset(it) })
        val contexts = listOf(
            context.copy(parishId = "another-church"),
            context.copy(serviceDate = "2026-11-09", startEpochMillis = context.startEpochMillis!! + 86_400_000),
            context.copy(timezone = "UTC"),
            context.copy(source = ReadingContextSource.USER),
            context.copy(source = ReadingContextSource.MANUAL, startEpochMillis = null),
            context.copy(startEpochMillis = context.startEpochMillis!! + 60_000),
            context.copy(timezone = "not-a-zone")
        )
        contexts.forEach { expected ->
            assertEquals(expected.toString(), PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.CONTEXT_MISMATCH),
                store.restore(newPlan, expected))
        }
        assertEquals(0, reads)
        assertEquals(0, preferences.editCount)
    }

    @Test fun `plan date mismatch is rejected before historical IO even with matching stored context`() {
        val preferences = seeded()
        var reads = 0
        val tomorrow = requireNotNull(historical.loadPlan(date.plusDays(1)))
        val result = ServicePlaybackStore(preferences, Historical05Content { reads++; asset(it) }).restore(expanded(tomorrow), context)
        assertEquals(invalidPlan(), result)
        assertEquals(0, reads)
        assertEquals(0, preferences.editCount)
    }

    @Test fun `unknown fingerprints versions indexes and old boundary offsets cannot become candidates`() {
        val preferences = seeded()
        val store = ServicePlaybackStore(preferences, historical)
        val duration = requireNotNull(oldPlan.durationQuarterMillis(oldIndex))
        val end = oldPlan.segments.lastIndex
        val badSnapshots = listOf(
            snapshot.copy(planFingerprint = "0".repeat(64)),
            snapshot.copy(planFingerprint = newPlan.fingerprint, segmentIndex = newPlan.segments.size),
            snapshot.copy(contentVersion = 2),
            snapshot.copy(segmentIndex = oldPlan.segments.size),
            snapshot.copy(segmentIndex = Int.MAX_VALUE),
            snapshot.copy(elapsedQuarterMillis = duration),
            snapshot.copy(elapsedQuarterMillis = duration + 1),
            snapshot.copy(elapsedQuarterMillis = Long.MAX_VALUE),
            snapshot.copy(segmentIndex = end, elapsedQuarterMillis = 1)
        )
        badSnapshots.forEach { bad ->
            preferences.values[ServicePlaybackStore.PLAYBACK_KEY] = StoredServicePlaybackCodec.encode(stored.copy(snapshot = bad))
            val before = preferences.values.toMap()
            assertEquals(bad.toString(), invalidPlan(), store.restore(newPlan, context))
            assertEquals(before, preferences.values)
            assertNull(LegacyPlaybackMigration.resolve(newPlan, "anaphora", preferences.values))
        }
        assertEquals(0, preferences.editCount)
    }

    @Test fun `missing or wrong historical bytes fail closed for each resource without removing stored key`() {
        for (unavailable in listOf("published_0_5/liturgy_stages.json", "published_0_5/daily_readings.json")) {
            for (missing in listOf(true, false)) {
                val preferences = seeded()
                val before = preferences.values.toMap()
                val loader = Historical05Content { name ->
                    if (name != unavailable) asset(name)
                    else if (missing) error("Unavailable published asset") else asset(name) + "\n"
                }
                assertEquals(invalidPlan(), ServicePlaybackStore(preferences, loader).restore(newPlan, context))
                assertEquals(before, preferences.values)
                assertEquals(0, preferences.editCount)
                assertNull(LegacyPlaybackMigration.resolve(newPlan, "anaphora", preferences.values))
            }
        }
    }

    @Test fun `changed wait uses the old last phrase with a distinct explanation and keeps old snapshot untouched`() {
        val waitIndex = oldPlan.segments.indexOfFirst { it is PlaybackSegment.Wait && it.stageId == "great_litany" }
        val oldWait = snapshot.copy(segmentIndex = waitIndex, elapsedQuarterMillis = requireNotNull(oldPlan.durationQuarterMillis(waitIndex)) - 1)
        val original = stored.copy(snapshot = oldWait)
        val preferences = seeded(original)
        val result = ServicePlaybackStore(preferences, historical).restore(newPlan, context) as PlaybackRestoreResult.MigrationCandidate
        val oldText = requireNotNull(oldPlan.retainedTextAt(waitIndex))
        assertEquals(newPlan.indexOfLine(oldText.trackId, oldText.lineId), result.state.segmentIndex)
        assertEquals(0L, result.state.elapsedQuarterMillis)
        assertEquals(PlaybackSpeed.FAST, result.state.speed)
        assertEquals(PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE, result.note)
        assertEquals(original, result.originalStored)
        assertEquals(original, StoredServicePlaybackCodec.decode(preferences.values[ServicePlaybackStore.PLAYBACK_KEY] as String))
        assertEquals(0, preferences.editCount)
    }

    @Test fun `saved day without readings stays on its own barrier and nonzero barrier offset is rejected`() {
        val missingDate = date.plusDays(1)
        val oldMissing = requireNotNull(historical.loadPlan(missingDate))
        val newMissing = expanded(oldMissing)
        val savedContext = context.copy(serviceDate = missingDate.toString(), startEpochMillis = context.startEpochMillis!! + 86_400_000)
        oldMissing.segments.withIndex().filter { it.value is PlaybackSegment.MissingReading }.forEach { (index, segment) ->
            val original = StoredServicePlayback(savedContext, snapshot.copy(planFingerprint = oldMissing.fingerprint,
                segmentIndex = index, elapsedQuarterMillis = 0))
            val preferences = seeded(original)
            val store = ServicePlaybackStore(preferences, historical)
            val result = store.restore(newMissing, savedContext) as PlaybackRestoreResult.MigrationCandidate
            assertEquals(segment, newMissing.segments[result.state.segmentIndex])
            assertEquals(missingDate.toString(), result.originalStored.context.serviceDate)
            assertFalse(result.state.isPlaying)
            val invalid = original.copy(snapshot = original.snapshot.copy(elapsedQuarterMillis = 1))
            preferences.values[ServicePlaybackStore.PLAYBACK_KEY] = StoredServicePlaybackCodec.encode(invalid)
            assertEquals(invalidPlan(), store.restore(newMissing, savedContext))
            assertEquals(0, preferences.editCount)
        }
    }

    private fun expanded(plan: ServicePlaybackPlan): ServicePlaybackPlan {
        val newText = StageText("Дополнение для проверки", "Соседний новый текст", "Новый текст", "Проверка")
        val stages = plan.stages.map { if (it.id == "great_litany") it.copy(texts = listOf(newText) + it.texts + newText.copy(type = "Конец дополнения")) else it }
        val day = Gson().fromJson(asset("published_0_5/daily_readings.json"), DailyReadings::class.java)
            .days.singleOrNull { it.date == plan.serviceDate.toString() }
        return ServicePlaybackPlan.build(stages, plan.serviceDate, day)
    }

    private fun seeded(value: StoredServicePlayback = stored) = AcceptanceMemoryPreferences().apply {
        values[ServicePlaybackStore.PLAYBACK_KEY] = StoredServicePlaybackCodec.encode(value)
    }
    private fun invalidPlan() = PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.PLAN_MISMATCH)
    private fun asset(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
