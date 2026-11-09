package ru.liturgyguide.service

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.model.DayReading
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PrayerLine
import ru.liturgyguide.model.ReadingDay
import ru.liturgyguide.model.StageText
import java.time.LocalDate

class ServicePlaybackPlanTest {
    private val date = LocalDate.of(2026, 11, 8)
    private val prayer = StageText("Молитва", "Исходная строка", "Русская строка", "Смысл")
    private val stage = LiturgyStage("beginning", 1, "Начало", "Что происходит", 1, listOf(prayer),
        meaning = "Смысл этапа", listenFor = listOf("Ориентир"), whatToDo = "Участвовать")
    private val day = ReadingDay(date.toString(), "https://example.org", "Чтения", "Примечание", listOf(
        DayReading("epistle", "Апостол", List(20) { "да" }.joinToString(" "), "Объяснение")
    ))

    @Test fun `short text remains short and waiting retains the text and stage explanation`() {
        val plan = ServicePlaybackPlan.build(listOf(stage), date, null)
        val text = plan.segments[0] as PlaybackSegment.Text
        val wait = plan.segments[1] as PlaybackSegment.Wait
        assertEquals(4_000L, text.durationBaseMillis)
        assertEquals(56_000L, wait.durationBaseMillis)
        assertEquals(text, plan.retainedTextAt(1))
        assertEquals(text, plan.retainedTextAt(2))
        assertEquals("Смысл этапа", plan.stageAt(1).meaning)
        assertEquals(listOf("Ориентир"), plan.stageAt(1).listenFor)
        assertEquals("Участвовать", plan.stageAt(1).whatToDo)
        assertEquals("Исходная строка", plan.line(text).original)
        assertEquals(text.trackId, plan.track(text.trackId)?.id)
        assertEquals(0, plan.indexOfTrack(text.trackId))
        assertEquals(0, plan.indexOfLine(text.trackId, text.lineId))
        assertNull(plan.indexOfLine(text.trackId, "unknown"))
    }

    @Test fun `canonical original sets cadence regardless of a longer Russian translation`() {
        val original = (1..9).joinToString("\u00a0") { "Сло́во$it" } + " \u2003- !"
        val longerRussian = (1..80).joinToString(" ") { "перевод$it" }
        val a = ServicePlaybackPlan.build(listOf(stage.copy(texts = listOf(prayer.copy(original = original)))), date, null)
        val b = ServicePlaybackPlan.build(listOf(stage.copy(texts = listOf(prayer.copy(original = original, translation = longerRussian)))), date, null)
        assertEquals(4_500L, (a.segments.first() as PlaybackSegment.Text).durationBaseMillis)
        assertEquals(a.segments, b.segments)
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test fun `Scripture with no original uses Russian words without fabricating an original`() {
        val plan = ServicePlaybackPlan.build(listOf(stage.copy(id = "epistle", texts = emptyList())), date, day)
        val text = plan.segments.first() as PlaybackSegment.Text
        assertEquals(1, plan.segments.filterIsInstance<PlaybackSegment.Text>().size)
        assertEquals(10_000L, text.durationBaseMillis)
        assertNull(plan.line(text).original)
        assertEquals(50_000L, (plan.segments[1] as PlaybackSegment.Wait).durationBaseMillis)
        assertFalse(plan.segments.any { it is PlaybackSegment.MissingReading })
    }

    @Test fun `long available text is neither cut nor squeezed into the editorial stage budget`() {
        val words = (1..150).joinToString(" ") { "слово$it" }
        val plan = ServicePlaybackPlan.build(listOf(stage.copy(texts = listOf(prayer.copy(original = words)))), date, null)
        val text = plan.segments.first() as PlaybackSegment.Text
        assertEquals(75_000L, text.durationBaseMillis)
        assertEquals(words, plan.line(text).original)
        assertEquals(2, plan.segments.size)
        assertTrue(plan.segments.last() is PlaybackSegment.End)
    }

    @Test fun `preparation with no text is a useful stage wait rather than a blank track`() {
        val plan = ServicePlaybackPlan.build(listOf(stage.copy(id = "preparation", texts = emptyList()), stage.copy(order = 2)), date, null)
        assertEquals(PlaybackSegment.Wait("preparation", 60_000L), plan.segments.first())
        assertNull(plan.retainedTextAt(0))
        assertTrue(plan.tracksByStage.getValue("preparation").isEmpty())
        assertEquals(1, plan.nextStageIndex(0))
        assertEquals("beginning", plan.stageAt(1).id)
        assertNull(plan.nextStageIndex(plan.segments.lastIndex))
    }

    @Test fun `missing reading blocks after introduction and cannot borrow the neighboring date`() {
        val epistle = stage.copy(id = "epistle")
        val plan = ServicePlaybackPlan.build(listOf(epistle), date.plusDays(1), day)
        assertEquals(3, plan.segments.size)
        assertTrue(plan.segments[0] is PlaybackSegment.Text)
        assertEquals(PlaybackSegment.MissingReading("epistle", date.plusDays(1)), plan.segments[1])
        assertEquals(plan.segments[0], plan.retainedTextAt(1))
        assertFalse(plan.segments.any { it is PlaybackSegment.Wait })
        assertTrue(plan.tracksByStage.getValue("epistle").none { it.isScripture })
    }

    @Test fun `an empty matching reading is still missing and without introduction blocks immediately`() {
        val empty = day.copy(readings = listOf(day.readings.single().copy(text = " \n ")))
        val plan = ServicePlaybackPlan.build(listOf(stage.copy(id = "epistle", texts = emptyList())), date, empty)
        assertEquals(PlaybackSegment.MissingReading("epistle", date), plan.segments.first())
        assertEquals(2, plan.segments.size)
        assertNull(plan.retainedTextAt(0))
    }

    @Test fun `date canonical content duration and order affect identity but input ordering does not`() {
        val second = stage.copy(id = "second", order = 2)
        fun plan(stages: List<LiturgyStage> = listOf(stage, second), date: LocalDate = this.date) =
            ServicePlaybackPlan.build(stages, date, null)
        val original = plan()
        assertEquals(original.fingerprint, plan(listOf(second, stage)).fingerprint)
        assertNotEquals(original.fingerprint, plan(date = date.plusDays(1)).fingerprint)
        assertNotEquals(original.fingerprint, plan(listOf(stage.copy(durationMin = 2), second)).fingerprint)
        assertNotEquals(original.fingerprint, plan(listOf(stage.copy(texts = listOf(prayer.copy(original = "Другой исходник"))), second)).fingerprint)
        assertNotEquals(original.fingerprint, plan(listOf(stage.copy(order = 2), second.copy(order = 1))).fingerprint)
        assertEquals(64, original.fingerprint.length)
        assertEquals(ServicePlaybackPlan.CONTENT_VERSION, original.contentVersion)
    }

    @Test fun `repeated prayers and lines remain separately seekable`() {
        val repeated = prayer.copy(lines = List(2) { PrayerLine("Господи помилуй", "Господи помилуй") })
        val plan = ServicePlaybackPlan.build(listOf(stage.copy(texts = listOf(repeated, repeated))), date, null)
        val lines = plan.segments.filterIsInstance<PlaybackSegment.Text>()
        assertEquals(4, lines.size)
        assertEquals(4, lines.map { it.lineId }.distinct().size)
        assertEquals(2, lines.map { it.trackId }.distinct().size)
        lines.forEachIndexed { index, line -> assertEquals(index, plan.indexOfLine(line.trackId, line.lineId)) }
    }

    @Test fun `invalid catalog rejects programming errors while empty stage content remains allowed`() {
        assertThrows(IllegalArgumentException::class.java) { ServicePlaybackPlan.build(emptyList(), date, null) }
        assertThrows(IllegalArgumentException::class.java) { ServicePlaybackPlan.build(listOf(stage, stage.copy(order = 2)), date, null) }
        assertThrows(IllegalArgumentException::class.java) { ServicePlaybackPlan.build(listOf(stage, stage.copy(id = "second")), date, null) }
        assertThrows(IllegalArgumentException::class.java) { ServicePlaybackPlan.build(listOf(stage.copy(id = " ")), date, null) }
        assertThrows(IllegalArgumentException::class.java) { ServicePlaybackPlan.build(listOf(stage.copy(durationMin = 0)), date, null) }
        assertThrows(IllegalArgumentException::class.java) { ServicePlaybackPlan.build(listOf(stage), LocalDate.of(0, 1, 1), null) }
        val brokenText = prayer.copy(lines = listOf(PrayerLine(" ", " ")))
        val plan = ServicePlaybackPlan.build(listOf(stage.copy(texts = listOf(brokenText))), date, null)
        assertTrue(plan.segments.first() is PlaybackSegment.Wait)
    }

    @Test fun `largest integer minute budget stays positive without arithmetic overflow`() {
        val plan = ServicePlaybackPlan.build(listOf(stage.copy(durationMin = Int.MAX_VALUE)), date, null)
        val durations = plan.segments.sumOf { when (it) {
            is PlaybackSegment.Text -> it.durationBaseMillis
            is PlaybackSegment.Wait -> it.durationBaseMillis
            else -> 0L
        } }
        assertEquals(Int.MAX_VALUE.toLong() * 60_000L, durations)
    }

    @Test fun `packaged guide contains dated readings in published order and honest missing barriers otherwise`() {
        fun asset(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val stages = StageMatcher(asset("liturgy_stages.json")).getAllStages()
        val readingDay = Gson().fromJson(asset("daily_readings.json"), DailyReadings::class.java).days.single { it.date == date.toString() }
        val available = ServicePlaybackPlan.build(stages, date, readingDay)
        assertEquals(18, available.stages.size)
        assertTrue(available.segments.first() is PlaybackSegment.Wait)
        assertTrue(available.segments.none { it is PlaybackSegment.MissingReading })
        listOf("epistle", "gospel").forEach { kind ->
            val tracks = available.tracksByStage.getValue(kind)
            assertFalse(tracks.first().isScripture)
            assertEquals(readingDay.readings.filter { it.kind == kind }.map { it.reference }, tracks.filter { it.isScripture }.map { it.title })
            assertTrue(tracks.filter { it.isScripture }.flatMap { it.lines }.all { it.original == null })
        }
        val missing = ServicePlaybackPlan.build(stages, date.plusDays(1), readingDay)
        assertEquals(listOf("epistle", "gospel"), missing.segments.filterIsInstance<PlaybackSegment.MissingReading>().map { it.stageId })
        val anaphora = missing.segments.filter { it.stageId == "anaphora" }
        val textMillis = anaphora.filterIsInstance<PlaybackSegment.Text>().sumOf { it.durationBaseMillis }
        // The expanded audible excerpt keeps the quiet prayers outside the timed text.
        assertEquals(22, anaphora.filterIsInstance<PlaybackSegment.Text>().size)
        assertEquals(107_500L, textMillis)
        assertEquals(12 * 60_000L - textMillis, anaphora.filterIsInstance<PlaybackSegment.Wait>().single().durationBaseMillis)
    }
}
