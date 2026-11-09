package ru.liturgyguide.service

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.DailyReadings
import java.time.LocalDate

class Published06PlaybackStoreTest {
    private fun asset(name: String) = requireNotNull(javaClass.classLoader!!.getResourceAsStream(name))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val dates = listOf(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 10))
    // These actual 0.7 additions change a 0.6 wait's meaning. Never reuse its old numeric offset.
    private val expandedStages = setOf("great_litany", "antiphons", "litanies", "great_entrance", "creed",
        "lords_prayer", "clergy_communion", "dismissal")

    private fun currentPlan(date: LocalDate): ServicePlaybackPlan {
        val day = Gson().fromJson(asset("daily_readings.json"), DailyReadings::class.java).days
            .singleOrNull { it.date == date.toString() }
        return ServicePlaybackPlan.build(StageMatcher(asset("liturgy_stages.json")).getAllStages(), date, day)
    }

    @Test fun `every real 06 segment preserves its proven address at both legal offset boundaries`() {
        val seen = mutableSetOf<String>()
        val historicalAssets = listOf("published_0_6/liturgy_stages.json", "published_0_6/daily_readings.json")
            .associateWith(::asset)
        val oldLoader = Historical06Content { historicalAssets.getValue(it) }
        for (date in dates) {
            val old = requireNotNull(oldLoader.loadPlan(date))
            val current = currentPlan(date)
            val context = ReadingContext("church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL, null)
            for ((index, segment) in old.segments.withIndex()) {
                val duration = old.durationQuarterMillis(index)
                val offsets = if (duration == null) listOf(0L) else listOf(0L, duration - 1)
                for (offset in offsets) {
                    val label = "$date $segment offset=$offset"
                    val snapshot = ServicePlaybackSnapshot(old.contentVersion, old.fingerprint, index, offset, PlaybackSpeed.SLOW)
                    val stored = StoredServicePlayback(context, snapshot)
                    val preferences = AcceptanceMemoryPreferences().apply {
                        values[ServicePlaybackStore.PLAYBACK_KEY] = StoredServicePlaybackCodec.encode(stored)
                    }
                    val before = preferences.values.toMap()
                    val result = ServicePlaybackStore(preferences, historical06Content = oldLoader)
                        .restore(current, context)
                    assertTrue(label, result is PlaybackRestoreResult.MigrationCandidate)
                    result as PlaybackRestoreResult.MigrationCandidate
                    val restored = current.segments[result.state.segmentIndex]
                    when (segment) {
                        is PlaybackSegment.Text -> {
                            seen += "Text"
                            assertEquals(label, segment, restored)
                            val restoredText = restored as PlaybackSegment.Text
                            assertEquals(label, old.line(segment).original ?: old.line(segment).russian,
                                current.line(restoredText).original ?: current.line(restoredText).russian)
                            assertEquals(label, offset, result.state.elapsedQuarterMillis)
                            assertNull(label, result.note)
                        }
                        is PlaybackSegment.Wait -> if (segment.stageId in expandedStages) {
                            seen += "ChangedWait"
                            val previous = requireNotNull(old.retainedTextAt(index))
                            assertEquals(label, previous, restored)
                            assertEquals(label, 0L, result.state.elapsedQuarterMillis)
                            assertEquals(label, PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE, result.note)
                        } else {
                            seen += "UnchangedWait"
                            assertEquals(label, segment, restored)
                            assertEquals(label, offset, result.state.elapsedQuarterMillis)
                            assertNull(label, result.note)
                        }
                        is PlaybackSegment.MissingReading, is PlaybackSegment.End -> {
                            seen += segment.javaClass.simpleName
                            assertEquals(label, segment, restored)
                            assertEquals(label, 0L, result.state.elapsedQuarterMillis)
                            assertNull(label, result.note)
                        }
                    }
                    assertEquals(label, stored, result.originalStored)
                    assertEquals(label, PlaybackSpeed.SLOW, result.state.speed)
                    assertFalse(label, result.state.isPlaying)
                    assertNull(label, result.state.anchorMonotonicMillis)
                    assertTrue(label, ServicePlaybackEngine(current).isValid(result.state))
                    assertEquals(label, before, preferences.values)
                    assertEquals(label, 0, preferences.editCount)
                }
                // A stored offset at the exclusive end is invalid, not an implicit advance.
                val outside = duration ?: 1L
                val bad = ServicePlaybackSnapshot(old.contentVersion, old.fingerprint, index, outside, PlaybackSpeed.FAST)
                assertNull("$date $segment rejects $outside", Published05PlaybackMigration.migrate(old, current, bad))
            }
        }
        assertEquals(setOf("Text", "ChangedWait", "UnchangedWait", "MissingReading", "End"), seen)
    }

    @Test fun `unchanged current snapshots bypass historical resources and retain every speed`() {
        val date = dates.first()
        val current = currentPlan(date)
        val context = ReadingContext("church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)
        for (speed in PlaybackSpeed.entries) {
            val snapshot = ServicePlaybackSnapshot(current.contentVersion, current.fingerprint,
                requireNotNull(current.indexOfStage("creed")), 123, speed)
            val preferences = AcceptanceMemoryPreferences().apply {
                values[ServicePlaybackStore.PLAYBACK_KEY] = StoredServicePlaybackCodec.encode(StoredServicePlayback(context, snapshot))
            }
            var reads = 0
            val result = ServicePlaybackStore(preferences, historical06Content = Historical06Content { reads++; error("Unexpected read") })
                .restore(current, context)
            assertTrue(result is PlaybackRestoreResult.Valid)
            assertEquals(speed, (result as PlaybackRestoreResult.Valid).state.speed)
            assertFalse(result.state.isPlaying)
            assertEquals(0, reads)
            assertEquals(0, preferences.editCount)
        }
    }

    @Test fun `changed frozen bytes cannot manufacture a 06 migration or fall back to 05`() {
        val date = dates.last()
        val old = requireNotNull(Historical06Content(::asset).loadPlan(date))
        val loader = Historical06Content { name -> asset(name) + "\n" }
        assertNull(loader.loadPlan(date))
        val context = ReadingContext("church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)
        val preferences = AcceptanceMemoryPreferences().apply {
            values[ServicePlaybackStore.PLAYBACK_KEY] = StoredServicePlaybackCodec.encode(StoredServicePlayback(context,
                ServicePlaybackSnapshot(old.contentVersion, old.fingerprint, 1, 0, PlaybackSpeed.NORMAL)))
        }
        assertEquals(PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.PLAN_MISMATCH),
            ServicePlaybackStore(preferences, Historical05Content(::asset), loader).restore(currentPlan(date), context))
        assertEquals(0, preferences.editCount)
    }
}
