package ru.liturgyguide.ui

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.service.*
import java.time.LocalDate

class ReaderVisibleStatusTest {
    private val date = LocalDate.of(2026, 11, 8)
    private val plan = ServicePlaybackPlan.build(StageMatcher(Published05Fixtures.asset("liturgy_stages.json")).getAllStages(), date,
        Gson().fromJson(Published05Fixtures.asset("daily_readings.json"), DailyReadings::class.java).days.single { it.date == date.toString() })
    private val context = ReadingContext("accepted-church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)

    @Test fun `manual and approximate text keep both pause and the basis of the place`() {
        val manual = reading("creed", PlaybackSegment.Text::class.java)
        assertEquals("На паузе", status(manual))
        assertEquals("На паузе · примерное место", status(manual.copy(isApproximate = true)))
        assertEquals("На паузе · сохраненное чтение", status(manual, restored = true))
        assertEquals("На паузе · сохраненное чтение", status(manual.copy(isApproximate = true), restored = true))
    }

    @Test fun `playing and waiting never duplicate the approximate qualifier`() {
        val text = reading("creed", PlaybackSegment.Text::class.java).copy(isApproximate = true)
        val playing = text.copy(state = text.engine.play(text.state, 1_000))
        assertEquals("Движение по времени · примерно", status(playing))
        assertEquals("Движение по времени · примерно · сохраненное чтение", status(playing, restored = true))
        val waiting = reading("anaphora", PlaybackSegment.Wait::class.java).copy(isApproximate = true)
        assertEquals("Примерный переход остановлен", status(waiting))
        assertEquals("Примерный переход включен", status(waiting.copy(state = waiting.engine.play(waiting.state, 1_000))))
    }

    @Test fun `before start and meaning retain their real state with visible context`() {
        val before = reading("preparation", PlaybackSegment.Wait::class.java).copy(isApproximate = true)
        assertEquals("До начала литургии · примерное место", status(before))
        assertEquals("До начала литургии · примерный переход включен", status(before.copy(state = before.engine.play(before.state, 1_000))))
        val text = reading("creed", PlaybackSegment.Text::class.java).copy(isApproximate = true)
        assertEquals("Пояснение · на паузе · примерное место", status(text, meaning = true))
        assertEquals("Пояснение · движение останавливается · примерное место",
            status(text.copy(state = text.engine.play(text.state, 1_000)), meaning = true))
    }

    @Test fun `mixed scripture states its actual language only while showing its text`() {
        val scriptures = plan.tracksByStage.getValue("gospel").filter { it.isScripture }
        assertEquals(3, scriptures.size)
        val scripture = scriptures.first()
        val initial = reading("gospel", PlaybackSegment.Text::class.java)
        val accepted = initial.copy(state = initial.engine.seekLine(initial.state, scripture.id, scripture.lines[3].id))
        assertEquals("На паузе · по-русски", status(accepted, original = true))
        assertEquals("На паузе", status(accepted, original = false))
        assertEquals("На паузе · сохраненное чтение · по-русски", status(accepted, restored = true, original = true))
        assertEquals("Движение по времени · примерно · по-русски",
            status(accepted.copy(state = accepted.engine.play(accepted.state, 1_000), isApproximate = true), original = true))
        assertEquals("Пояснение · на паузе", status(accepted, original = true, meaning = true))
        assertEquals("Примерный переход остановлен", status(reading("gospel", PlaybackSegment.Wait::class.java), original = true))
        assertEquals("На паузе", status(initial, original = true))
    }

    @Test fun `missing and end keep their date or app boundary while exposing saved context`() {
        val missingPlan = ServicePlaybackPlan.build(plan.stages, date.plusDays(1), null)
        val index = missingPlan.segments.indexOfFirst { it.stageId == "epistle" && it is PlaybackSegment.MissingReading }
        val missing = ActiveServicePlayback(missingPlan, context.copy(serviceDate = "2026-11-09"),
            ServicePlaybackEngine(missingPlan).start("epistle").copy(segmentIndex = index), isApproximate = true)
        assertEquals("Текст на 9 ноября 2026 пока не добавлен · пауза · примерное место", status(missing))
        assertEquals("Текст на 9 ноября 2026 пока не добавлен · пауза · сохраненное чтение", status(missing, restored = true))
        assertEquals("Порядок в приложении завершен · сохраненное чтение",
            status(reading("dismissal", PlaybackSegment.End::class.java), restored = true, original = true))
    }

    private fun reading(stage: String, type: Class<out PlaybackSegment>): ActiveServicePlayback {
        val index = plan.segments.indexOfFirst { it.stageId == stage && type.isInstance(it) }
        check(index >= 0)
        return ActiveServicePlayback(plan, context, ServicePlaybackEngine(plan).start(stage).copy(segmentIndex = index))
    }
    private fun status(reading: ActiveServicePlayback, restored: Boolean = false, original: Boolean = false, meaning: Boolean = false) =
        readerVisibleStatus(reading, readerPresentation(reading, meaning), restored, original)
}
