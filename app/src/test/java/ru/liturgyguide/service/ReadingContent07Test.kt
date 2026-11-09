package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** The shipped reader must retain old bookmarks while filling audible gaps. */
class ReadingContent07Test {
    private fun stages(asset: String = "liturgy_stages.json") =
        StageMatcher(Published05Fixtures.asset(asset)).getAllStages()

    @Test fun `all released phrases retain their addresses and relative order`() {
        val old = stages("published_0_6/liturgy_stages.json")
        val current = stages().associateBy { it.id }
        old.forEach { stage ->
            val changed = current.getValue(stage.id)
            assertEquals(stage.copy(texts = emptyList()), changed.copy(texts = emptyList()))
            val before = PrayerTrackFactory.build(stage, null)
            val after = PrayerTrackFactory.build(changed, null)
            assertEquals(before, after.filter { candidate -> before.any { it.id == candidate.id } })
        }
        assertArrayEquals(Published05Fixtures.bytes("published_0_6/daily_readings.json"),
            Published05Fixtures.bytes("daily_readings.json"))
    }

    @Test fun `the new familiar phrases are findable in both languages`() {
        val cases = listOf(
            Triple("antiphons", "не надейтеся на князи", "Не возлагайте последнюю надежду"),
            Triple("antiphons", "блажени миротворцы", "те, кто приносит мир"),
            Triple("great_entrance", "ангела мирна", "ангела мира"),
            Triple("dismissal", "Благословляяй благословящия", "Ты благословляешь прославляющих")
        )
        val content = stages().associateBy { it.id }
        cases.forEach { (stage, original, russian) ->
            val tracks = PrayerTrackFactory.build(content.getValue(stage), null)
            val first = requireNotNull(PrayerTextSearch.find(tracks, original))
            val second = requireNotNull(PrayerTextSearch.find(tracks, russian))
            assertEquals("Language switch must preserve a phrase: $stage", first, second)
        }
    }

    @Test fun `an introduction cannot steal the Our Father title search`() {
        val tracks = PrayerTrackFactory.build(stages().single { it.id == "lords_prayer" }, null)
        val match = requireNotNull(PrayerTextSearch.find(tracks, "Отче наш"))
        assertEquals("Отче наш", tracks[match.trackIndex].title)
        assertTrue(tracks[match.trackIndex].lines.first().original.orEmpty().startsWith("Отче наш"))
    }

    @Test fun `new text fits stage budgets and never fills unknown daily readings`() {
        val plan = ServicePlaybackPlan.build(stages(), LocalDate.of(2026, 11, 10), null)
        plan.stages.forEach { stage ->
            val speech = plan.segments.filterIsInstance<PlaybackSegment.Text>()
                .filter { it.stageId == stage.id }.sumOf { it.durationBaseMillis }
            assertTrue("Added text silently overran ${stage.id}", speech <= stage.durationMin * 60_000L)
        }
        assertEquals(setOf("epistle", "gospel"), plan.segments.filterIsInstance<PlaybackSegment.MissingReading>()
            .map { it.stageId }.toSet())
        assertTrue(plan.tracksByStage.values.flatten().none { it.isScripture })
    }
}
