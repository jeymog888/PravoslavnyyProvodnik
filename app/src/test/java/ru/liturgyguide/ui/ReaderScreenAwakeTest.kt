package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PrayerLine
import ru.liturgyguide.model.StageText
import ru.liturgyguide.service.*
import java.time.LocalDate

class ReaderScreenAwakeTest {
    private val date = LocalDate.of(2026, 11, 9)
    private val prayer = StageText("Молитва", "Слова", "Смысл", "Пояснение",
        lines = listOf(PrayerLine("Первая строка", "Первая")))
    private val plan = ServicePlaybackPlan.build(listOf(
        LiturgyStage("preparation", 1, "Подготовка", "До начала", 5, emptyList()),
        LiturgyStage("beginning", 2, "Начало", "Начальная молитва", 1, listOf(prayer)),
        LiturgyStage("epistle", 3, "Апостол", "Чтение", 1, listOf(prayer)),
        LiturgyStage("dismissal", 4, "Завершение", "Последняя молитва", 1, listOf(prayer))
    ), date, null)

    @Test fun `visible resumed and top resumed are all required for accepted moving text`() {
        val paused = at("beginning", PlaybackSegment.Text::class.java)
        val moving = play(paused)
        for (visible in listOf(false, true)) for (resumed in listOf(false, true)) for (top in listOf(false, true)) {
            assertEquals("visible=$visible resumed=$resumed top=$top", visible && resumed && top,
                readerKeepsScreenOn(visible, resumed, top, moving))
        }
        assertFalse(readerKeepsScreenOn(true, true, true, paused))
        assertFalse(readerKeepsScreenOn(true, true, true, paused.copy(isApproximate = true)))
        assertFalse(readerKeepsScreenOn(true, true, true, null))
    }

    @Test fun `active waiting includes preparation but barriers and restored pauses never retain the screen`() {
        for (stage in listOf("preparation", "beginning", "dismissal")) {
            val waiting = at(stage, PlaybackSegment.Wait::class.java)
            assertFalse(readerKeepsScreenOn(true, true, true, waiting))
            assertTrue(readerKeepsScreenOn(true, true, true, play(waiting)))
            val restored = requireNotNull(waiting.engine.restore(waiting.engine.snapshot(play(waiting).state, 1_101)))
            assertFalse(readerKeepsScreenOn(true, true, true, waiting.copy(state = restored)))
        }
        for (barrier in listOf(at("epistle", PlaybackSegment.MissingReading::class.java),
            at("dismissal", PlaybackSegment.End::class.java))) {
            assertFalse(play(barrier).state.isPlaying)
            assertFalse(readerKeepsScreenOn(true, true, true, play(barrier)))
        }
    }

    @Test fun `first attachment clears an inherited flag and repeated values do not rewrite the target`() {
        var target = true
        val changes = mutableListOf<Boolean>()
        val owner = ReaderScreenAwakeOwner { target = it; changes += it }
        val binding = owner.binding { }
        binding.attach()
        assertFalse(target)
        assertEquals(listOf(false), changes)
        binding.update(false)
        binding.update(true)
        binding.update(true)
        assertTrue(target)
        assertEquals(listOf(false, true), changes)
    }

    @Test fun `pause and close release before the pause callback and closed bindings cannot reacquire`() {
        var target = false
        val events = mutableListOf<String>()
        val owner = ReaderScreenAwakeOwner { target = it; events += "flag:$it" }
        val binding = owner.binding { events += "pause:$target" }
        binding.attach()
        binding.update(true)
        events.clear()
        binding.pause()
        assertEquals(listOf("flag:false", "pause:false"), events)
        binding.update(true)
        events.clear()
        binding.close()
        assertEquals(listOf("flag:false", "pause:false"), events)
        binding.close()
        binding.pause()
        binding.attach()
        binding.update(true)
        assertFalse(target)
        assertEquals(listOf("flag:false", "pause:false"), events)
    }

    @Test fun `stale updates pause and disposal cannot clear a replacement binding`() {
        var target = false
        var oldPauses = 0
        var newPauses = 0
        val owner = ReaderScreenAwakeOwner { target = it }
        val old = owner.binding { oldPauses++ }
        old.attach()
        old.update(true)
        val current = owner.binding { newPauses++ }
        current.attach()
        assertFalse(target)
        current.update(true)
        old.update(false)
        old.pause()
        old.close()
        assertTrue(target)
        assertEquals(0, oldPauses)
        assertEquals(0, newPauses)
        current.pause()
        assertFalse(target)
        assertEquals(1, newPauses)
    }

    private fun at(stage: String, type: Class<out PlaybackSegment>): ActiveServicePlayback {
        val index = plan.segments.indexOfFirst { it.stageId == stage && type.isInstance(it) }
        check(index >= 0)
        return ActiveServicePlayback(plan, ReadingContext(null, date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL),
            ServicePlaybackEngine(plan).start(stage).copy(segmentIndex = index))
    }

    private fun play(reading: ActiveServicePlayback) = reading.copy(state = reading.engine.play(reading.state, 1_000))
}
