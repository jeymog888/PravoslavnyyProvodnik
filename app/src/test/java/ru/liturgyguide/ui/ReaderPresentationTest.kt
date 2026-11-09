package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PrayerLine
import ru.liturgyguide.model.StageText
import ru.liturgyguide.service.*
import java.time.LocalDate
import java.util.Locale

class ReaderPresentationTest {
    private val date = LocalDate.of(2026, 11, 9)
    private val prayer = StageText("Молитва", "Первая строка", "Перевод", "Пояснение",
        lines = listOf(PrayerLine("Первая строка", "Первая"), PrayerLine("Вторая строка", "Вторая")))
    private val plan = ServicePlaybackPlan.build(listOf(
        LiturgyStage("preparation", 1, "Подготовка", "До начала", 5, emptyList()),
        LiturgyStage("beginning", 2, "Начало", "Начальная молитва", 1, listOf(prayer)),
        LiturgyStage("epistle", 3, "Апостол", "Чтение", 1, listOf(prayer)),
        LiturgyStage("silent", 4, "Этап без текста", "Слушаем", 1, emptyList()),
        LiturgyStage("dismissal", 5, "Завершение", "Последняя молитва", 1, listOf(prayer))
    ), date, null)
    private val context = ReadingContext("selected-church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)

    @Test fun `text action describes movement and does not infer playing from a schedule estimate`() {
        for (speed in PlaybackSpeed.entries) {
            val paused = at("beginning", PlaybackSegment.Text::class.java, speed = speed)
                .let { it.copy(state = it.state.copy(elapsedQuarterMillis = 1_234), isApproximate = true) }
            assertPresentation(paused, ReaderStateKind.TEXT, "На паузе", ReaderPrimaryAction.PLAY, "Двигать текст")
            val playing = play(paused)
            assertPresentation(playing, ReaderStateKind.TEXT, "Движение по времени · примерно", ReaderPrimaryAction.PAUSE, "Пауза")
            assertEquals(1_234L, paused.state.elapsedQuarterMillis)
            assertFalse(paused.state.isPlaying)
            assertTrue(playing.state.isPlaying)
        }
    }

    @Test fun `waiting has its own action and status even while retaining the last phrase`() {
        val waiting = at("beginning", PlaybackSegment.Wait::class.java)
        assertNotNull(waiting.track)
        assertPresentation(waiting, ReaderStateKind.WAIT, "Примерный переход остановлен",
            ReaderPrimaryAction.PLAY, "Продолжить по времени")
        assertPresentation(play(waiting), ReaderStateKind.WAIT, "Примерный переход включен",
            ReaderPrimaryAction.PAUSE, "Пауза")
        val ordinaryEmpty = at("silent", PlaybackSegment.Wait::class.java)
        assertNull(ordinaryEmpty.track)
        assertPresentation(ordinaryEmpty, ReaderStateKind.WAIT, "Примерный переход остановлен",
            ReaderPrimaryAction.PLAY, "Продолжить по времени")
        val finalWait = at("dismissal", PlaybackSegment.Wait::class.java)
        assertPresentation(finalWait, ReaderStateKind.WAIT, "Примерный переход остановлен",
            ReaderPrimaryAction.PLAY, "Продолжить по времени")
        assertPresentation(play(finalWait), ReaderStateKind.WAIT, "Примерный переход включен",
            ReaderPrimaryAction.PAUSE, "Пауза")
    }

    @Test fun `an available introduction remains readable before the missing daily scripture barrier`() {
        val introduction = at("epistle", PlaybackSegment.Text::class.java)
        assertTrue(introduction.tracks.none { it.isScripture })
        assertPresentation(introduction, ReaderStateKind.TEXT, "На паузе", ReaderPrimaryAction.PLAY, "Двигать текст")
        assertPresentation(play(introduction), ReaderStateKind.TEXT, "Движение по времени · примерно", ReaderPrimaryAction.PAUSE, "Пауза")
        val barrier = at("epistle", PlaybackSegment.MissingReading::class.java)
        assertTrue(barrier.state.segmentIndex > introduction.state.segmentIndex)
        assertEquals(ReaderStateKind.MISSING_READING, readerPresentation(barrier, meaning = false).kind)
    }

    @Test fun `before start always asks for the observed beginning including an already running estimate`() {
        val before = at("preparation", PlaybackSegment.Wait::class.java)
        assertPresentation(before, ReaderStateKind.BEFORE_START, "До начала литургии",
            ReaderPrimaryAction.BEGIN_LITURGY, "Литургия началась")
        assertPresentation(play(before), ReaderStateKind.BEFORE_START, "До начала литургии · примерный переход включен",
            ReaderPrimaryAction.BEGIN_LITURGY, "Литургия началась")
    }

    @Test fun `missing reading uses the accepted service date and remains a manual barrier`() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val missing = at("epistle", PlaybackSegment.MissingReading::class.java)
            assertPresentation(missing, ReaderStateKind.MISSING_READING, "Текст на 9 ноября 2026 пока не добавлен · пауза",
                ReaderPrimaryAction.CONTINUE_AFTER_MISSING, "Чтение в храме закончилось")
            assertFalse(missing.engine.play(missing.state, 10_000).isPlaying)
            assertEquals(missing.state, missing.engine.tick(missing.state, Long.MAX_VALUE))
        } finally {
            Locale.setDefault(oldLocale)
        }
    }

    @Test fun `end describes completion of the app guide and provides an explicit finish`() {
        val end = at("dismissal", PlaybackSegment.End::class.java)
        assertPresentation(end, ReaderStateKind.END, "Порядок в приложении завершен",
            ReaderPrimaryAction.FINISH, "Закончить чтение")
        assertEquals(end.state, end.engine.play(end.state, 10_000))
    }

    @Test fun `meaning takes priority over every underlying state and chooses an honest return label`() {
        val readings = listOf(
            at("preparation", PlaybackSegment.Wait::class.java),
            at("beginning", PlaybackSegment.Text::class.java),
            at("beginning", PlaybackSegment.Wait::class.java),
            at("epistle", PlaybackSegment.MissingReading::class.java),
            at("silent", PlaybackSegment.Wait::class.java),
            at("dismissal", PlaybackSegment.End::class.java)
        )
        for (reading in readings) {
            val state = reading.state.copy()
            val presentation = readerPresentation(reading, meaning = true)
            assertEquals(ReaderStateKind.MEANING, presentation.kind)
            assertEquals("Пояснение · на паузе", presentation.status)
            assertEquals(ReaderPrimaryAction.RETURN_TO_TEXT, presentation.primaryAction)
            assertEquals(if (reading.track == null) "К ходу службы" else "К тексту", presentation.primaryLabel)
            assertEquals("Presentation must not seek or consume time", state, reading.state)
            assertEquals(context, reading.context)
        }
    }

    @Test fun `meaning does not claim the runtime is paused before the owner actually pauses it`() {
        for (stage in listOf("preparation", "beginning", "silent")) {
            val segment = if (stage == "beginning") PlaybackSegment.Text::class.java else PlaybackSegment.Wait::class.java
            val playing = play(at(stage, segment))
            val presentation = readerPresentation(playing, meaning = true)
            assertEquals(ReaderStateKind.MEANING, presentation.kind)
            assertEquals("Пояснение · движение останавливается", presentation.status)
            assertEquals(ReaderPrimaryAction.RETURN_TO_TEXT, presentation.primaryAction)
            assertTrue("The pure presenter must not mutate the playback owner", playing.state.isPlaying)
        }
    }

    private fun at(stageId: String, segmentClass: Class<out PlaybackSegment>, speed: PlaybackSpeed = PlaybackSpeed.NORMAL): ActiveServicePlayback {
        val index = plan.segments.indexOfFirst { it.stageId == stageId && segmentClass.isInstance(it) }
        check(index >= 0)
        return ActiveServicePlayback(plan, context, ServicePlaybackEngine(plan).start(stageId, speed).copy(segmentIndex = index))
    }

    private fun play(reading: ActiveServicePlayback) = reading.copy(state = reading.engine.play(reading.state, 1_000))

    private fun assertPresentation(reading: ActiveServicePlayback, kind: ReaderStateKind, status: String,
        action: ReaderPrimaryAction, label: String) {
        val result = readerPresentation(reading, meaning = false)
        assertEquals(kind, result.kind)
        assertEquals(status, result.status)
        assertEquals(action, result.primaryAction)
        assertEquals(label, result.primaryLabel)
    }
}
