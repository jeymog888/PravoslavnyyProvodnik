package ru.liturgyguide.service

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.model.LiturgyStage
import java.time.LocalDate

/** Frozen released 0.6 content, compared with the byte-verified published 0.5 baseline. */
class ExpandedContentTest {
    private val changed = setOf("great_litany", "troparia_trisagion", "anaphora")
    private fun asset(name: String) = Published05Fixtures.asset(name)
    private fun currentStages() = StageMatcher(asset("published_0_6/liturgy_stages.json")).getAllStages()
    private fun currentPlan(date: LocalDate, stages: List<LiturgyStage> = currentStages()): ServicePlaybackPlan {
        val day = Gson().fromJson(asset("daily_readings.json"), DailyReadings::class.java)
            .days.singleOrNull { it.date == date.toString() }
        return ServicePlaybackPlan.build(stages, date, day)
    }

    @Test fun `only three approved stages add twelve blocks and forty two spoken lines`() {
        val old = Published05Fixtures.stages()
        val current = currentStages()
        assertEquals(old.map { it.id }, current.map { it.id })
        assertEquals(old.filterNot { it.id in changed }, current.filterNot { it.id in changed })
        changed.forEach { id ->
            assertEquals(old.single { it.id == id }.copy(texts = emptyList()),
                current.single { it.id == id }.copy(texts = emptyList()))
        }
        assertEquals(12, current.sumOf { it.texts.size } - old.sumOf { it.texts.size })
        assertEquals(42, current.sumOf { it.texts.sumOf { text -> text.lines.size } } -
            old.sumOf { it.texts.sumOf { text -> text.lines.size } })
        assertArrayEquals(Published05Fixtures.bytes(Published05Fixtures.READINGS),
            Published05Fixtures.bytes("daily_readings.json"))
    }

    @Test fun `all five old blocks retain canonical words translations and eight semantic line addresses`() {
        val old = Published05Fixtures.stages().filter { it.id in changed }
        val current = currentStages().associateBy { it.id }
        var blocks = 0
        var lines = 0
        old.forEach { stage ->
            val oldTracks = PrayerTrackFactory.build(stage, null)
            val newTracks = PrayerTrackFactory.build(current.getValue(stage.id), null)
            assertEquals(oldTracks.map { it.id }, newTracks.filter { candidate -> oldTracks.any { it.id == candidate.id } }.map { it.id })
            stage.texts.zip(oldTracks).forEach { (text, track) ->
                val index = newTracks.indexOfFirst { it.id == track.id }
                assertTrue("Published track disappeared: ${track.id}", index >= 0)
                val preserved = current.getValue(stage.id).texts[index]
                assertEquals(text.type, preserved.type)
                assertEquals(text.original, preserved.original)
                assertEquals(text.translation, preserved.translation)
                assertEquals(text.lines.map { it.original to it.translation }, preserved.lines.map { it.original to it.translation })
                assertEquals(track.lines.map { it.id }, newTracks[index].lines.map { it.id })
                blocks++
                lines += track.lines.size
            }
        }
        assertEquals(5, blocks)
        assertEquals(8, lines)
    }

    @Test fun `nullable decoder and factory carry the approved voices and notes without changing the clock`() {
        val stages = currentStages()
        val complete = stages.filter { it.id in changed }.flatMap { stage ->
            PrayerTrackFactory.build(stage, null).also { tracks ->
                stage.texts.zip(tracks).forEach { (source, track) ->
                    assertEquals(source.contextNote, track.contextNote)
                    assertEquals(source.lines.map { it.speaker }, track.lines.map { it.speaker })
                }
            }
        }
        assertTrue(complete.flatMap { it.lines }.all { it.speaker in setOf("Диакон", "Священник", "Хор") })
        val stripped = stages.map { stage -> stage.copy(texts = stage.texts.map { text ->
            text.copy(contextNote = null, lines = text.lines.map { it.copy(speaker = null) })
        }) }
        val verbose = stages.map { stage -> stage.copy(texts = stage.texts.map { text ->
            text.copy(contextNote = "Длинное пояснение без звучания ".repeat(100),
                lines = text.lines.map { it.copy(speaker = "Проверочная роль") })
        }) }
        Published05Fixtures.dates.forEach { date ->
            val normal = currentPlan(date, stages)
            listOf(stripped, verbose).forEach { variant ->
                val compared = currentPlan(date, variant)
                assertEquals(normal.fingerprint, compared.fingerprint)
                assertEquals(normal.segments, compared.segments)
                assertEquals(normal.tracksByStage.mapValues { it.value.map { track -> track.id } },
                    compared.tracksByStage.mapValues { it.value.map { track -> track.id } })
            }
        }
    }

    @Test fun `local omissions and difficult clauses have honest untimed context`() {
        val stages = currentStages().associateBy { it.id }
        val litany = stages.getValue("great_litany").texts
        assertTrue(requireNotNull(litany.first().contextNote).contains("Если диакона нет"))
        assertTrue(requireNotNull(litany.single { it.type == "Прошение о стране" }.contextNote).contains("прошение и ответ на него здесь пропущены"))
        assertTrue(requireNotNull(litany.single { it.type == "Прошения о помощи" }.contextNote).contains("прошения и ответ на него здесь пропущены"))
        val anaphora = stages.getValue("anaphora").texts
        assertTrue(requireNotNull(anaphora.single { it.type == "Ангельская песнь" }.contextNote)
            .contains("Возглас продолжает молитву об ангелах, которые славят Бога"))
        assertTrue(requireNotNull(anaphora.single { it.type == "Начальный диалог анафоры" }.contextNote)
            .contains("Христос примиряет нас с Богом"))
        assertTrue(requireNotNull(anaphora.single { it.type == "Твоя от Твоих" }.contextNote)
            .contains("Все, что мы приносим Богу, уже получено от Него"))
        val oldDialogue = anaphora.single { it.original.startsWith("Горе имеим сердца") }
        assertEquals(listOf("Священник", "Хор"), oldDialogue.lines.map { it.speaker })
        stages.values.flatMap { it.texts }.filter { it.contextNote != null }.forEach { text ->
            assertFalse(text.lines.any { it.original == text.contextNote || it.translation == text.contextNote })
        }
    }

    @Test fun `the corrected entrusting clause is aligned and ordinary trisagion repeats remain an excerpt`() {
        val stages = currentStages().associateBy { it.id }
        val text = stages.getValue("great_litany").texts.single { it.type == "Завершение прошений" }
        assertEquals("Помянув Пресвятую, Пречистую, Преблагословенную, славную Владычицу нашу Богородицу, всегда Деву Марию, и всех святых, вверим Христу Богу себя, друг друга и всю нашу жизнь",
            text.lines.first().translation)
        assertEquals(text.lines.joinToString("\n") { it.translation }, text.translation)
        val trisagion = stages.getValue("troparia_trisagion")
        assertTrue(trisagion.texts.all { it.isExcerpt })
        val original = Published05Fixtures.stages().single { it.id == trisagion.id }.texts.single().lines.single().original
        val lines = trisagion.texts.flatMap { it.lines }
        assertEquals(4, lines.count { it.original == original })
        assertEquals(listOf(original, original, original), lines.take(3).map { it.original })
        assertEquals(original, lines.last().original)
        assertTrue(requireNotNull(trisagion.texts.first().contextNote).contains("на некоторых праздниках"))
    }

    @Test fun `actual Kotlin durations add speech without inventing a different stage budget`() {
        val plan = currentPlan(Published05Fixtures.dates.first())
        val expected = mapOf("great_litany" to (117_500L to 122_500L),
            "troparia_trisagion" to (28_000L to 272_000L), "anaphora" to (107_500L to 612_500L))
        expected.forEach { (id, durations) ->
            val speech = plan.segments.filterIsInstance<PlaybackSegment.Text>().filter { it.stageId == id }
                .sumOf { it.durationBaseMillis }
            val wait = plan.segments.filterIsInstance<PlaybackSegment.Wait>().single { it.stageId == id }.durationBaseMillis
            assertEquals(durations.first, speech)
            assertEquals(durations.second, wait)
            assertEquals(plan.stages.single { it.id == id }.durationMin * 60_000L, speech + wait)
        }
        val oldSpeech = Published05Fixtures.oldPlan(plan.serviceDate).segments.filterIsInstance<PlaybackSegment.Text>()
            .filter { it.stageId in changed }.sumOf { it.durationBaseMillis }
        assertEquals(220_500L, expected.values.sumOf { it.first } - oldSpeech)
    }

    @Test fun `every published text position migrates to the real expanded plan with exact offsets and speeds`() {
        var tested = 0
        var shifted = 0
        Published05Fixtures.dates.forEach { date ->
            val old = Published05Fixtures.oldPlan(date)
            val current = currentPlan(date)
            assertNotEquals(old.fingerprint, current.fingerprint)
            old.segments.forEachIndexed { index, segment ->
                if (segment is PlaybackSegment.Text) {
                    for (offset in listOf(0L, 1_234L, segment.durationBaseMillis * 4 - 1)) {
                        PlaybackSpeed.entries.forEach { speed ->
                            val migrated = requireNotNull(Published05PlaybackMigration.migrate(old, current,
                                Published05Fixtures.snapshot(old, index, offset, speed)))
                            assertEquals(segment, current.segments[migrated.state.segmentIndex])
                            assertEquals(old.line(segment).original, current.line(segment).original)
                            assertEquals(old.line(segment).russian, current.line(segment).russian)
                            assertEquals(offset, migrated.state.elapsedQuarterMillis)
                            assertEquals(speed, migrated.state.speed)
                            assertNull(migrated.note)
                            assertFalse(migrated.state.isPlaying)
                            assertTrue(ServicePlaybackEngine(current).isValid(migrated.state))
                            if (migrated.state.segmentIndex != index) shifted++
                            tested++
                        }
                    }
                }
            }
        }
        assertEquals(2_088, tested)
        assertTrue(shifted > 0)
    }

    @Test fun `only waits in the three expanded stages return to an old phrase and missing days stay barriers`() {
        var fallback = 0
        var exact = 0
        var barriers = 0
        Published05Fixtures.dates.forEach { date ->
            val old = Published05Fixtures.oldPlan(date)
            val current = currentPlan(date)
            old.segments.forEachIndexed { index, segment ->
                if (segment is PlaybackSegment.Text) return@forEachIndexed
                val offsets = if (segment is PlaybackSegment.Wait)
                    listOf(0L, 1_234L, segment.durationBaseMillis * 4 - 1) else listOf(0L)
                offsets.forEach { offset -> PlaybackSpeed.entries.forEach { speed ->
                    val result = requireNotNull(Published05PlaybackMigration.migrate(old, current,
                        Published05Fixtures.snapshot(old, index, offset, speed)))
                    assertEquals(speed, result.state.speed)
                    assertFalse(result.state.isPlaying)
                    if (segment is PlaybackSegment.Wait && segment.stageId in changed) {
                        assertEquals(old.retainedTextAt(index), current.segments[result.state.segmentIndex])
                        assertEquals(0L, result.state.elapsedQuarterMillis)
                        assertEquals(PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE, result.note)
                        fallback++
                    } else {
                        assertEquals(segment, current.segments[result.state.segmentIndex])
                        assertEquals(offset, result.state.elapsedQuarterMillis)
                        assertNull(result.note)
                        if (segment is PlaybackSegment.Wait) exact++
                        if (segment is PlaybackSegment.MissingReading) {
                            val engine = ServicePlaybackEngine(current)
                            assertEquals(result.state, engine.play(result.state, 0L))
                            assertEquals(result.state, engine.tick(result.state, Long.MAX_VALUE))
                            barriers++
                        }
                    }
                } }
            }
        }
        assertEquals(54, fallback)
        assertEquals(252, exact)
        assertEquals(6, barriers)
    }
}
