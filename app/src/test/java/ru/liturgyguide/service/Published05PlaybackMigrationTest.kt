package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.DayReading
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.model.ReadingDay
import java.time.LocalDate

class Published05PlaybackMigrationTest {
    @Test fun `all 232 published text positions keep their semantic address exact quarter offset and speed after neighbor insertion`() {
        var texts = 0
        var cases = 0
        Published05Fixtures.dates.forEach { date ->
            val old = Published05Fixtures.oldPlan(date)
            val expanded = Published05Fixtures.expandedPlan(date)
            assertNotEquals(old.fingerprint, expanded.fingerprint)
            old.tracksByStage.forEach { (stageId, tracks) ->
                val newTracks = expanded.tracksByStage.getValue(stageId)
                tracks.forEach { track ->
                    val preserved = newTracks.single { it.id == track.id }
                    assertEquals(track.lines.map { it.id }, preserved.lines.map { it.id })
                }
            }
            old.segments.forEachIndexed { index, segment ->
                if (segment !is PlaybackSegment.Text) return@forEachIndexed
                texts++
                val newIndex = requireNotNull(expanded.indexOfLine(segment.trackId, segment.lineId))
                assertTrue("$date old text $index must actually move", newIndex > index)
                assertEquals(segment, expanded.segments[newIndex])
                assertEquals(canonical(old, segment), canonical(expanded, expanded.segments[newIndex] as PlaybackSegment.Text))
                offsets(segment.durationBaseMillis).forEach { offset ->
                    PlaybackSpeed.entries.forEach { speed ->
                        val snapshot = captured(old, index, offset, speed)
                        val migration = requireNotNull(Published05PlaybackMigration.migrate(old, expanded, snapshot))
                        assertPosition(expanded, migration, newIndex, offset, speed)
                        assertNull(migration.note)
                        cases++
                    }
                }
            }
        }
        assertEquals(232, texts)
        assertEquals(2_088, cases)
    }

    @Test fun `all 34 unchanged stage waits preserve exact offsets after global indexes shift`() {
        var waits = 0
        var cases = 0
        Published05Fixtures.dates.forEach { date ->
            val old = Published05Fixtures.oldPlan(date)
            val prefixed = Published05Fixtures.prefixedPlan(date)
            old.segments.forEachIndexed { index, segment ->
                if (segment !is PlaybackSegment.Wait) return@forEachIndexed
                waits++
                val newIndex = prefixed.segments.indexOf(segment)
                assertTrue(newIndex > index)
                assertEquals(old.segments.filter { it.stageId == segment.stageId }, prefixed.segments.filter { it.stageId == segment.stageId })
                offsets(segment.durationBaseMillis).forEach { offset ->
                    PlaybackSpeed.entries.forEach { speed ->
                        val migration = requireNotNull(Published05PlaybackMigration.migrate(old, prefixed, captured(old, index, offset, speed)))
                        assertPosition(prefixed, migration, newIndex, offset, speed)
                        assertNull(migration.note)
                        cases++
                    }
                }
            }
        }
        assertEquals(34, waits)
        assertEquals(306, cases)
    }

    @Test fun `all changed waits return to the last old phrase and removed waits use the same conservative rule`() {
        var waits = 0
        var safeReturns = 0
        var noOldPhrase = 0
        Published05Fixtures.dates.forEach { date ->
            val old = Published05Fixtures.oldPlan(date)
            old.segments.forEachIndexed { index, segment ->
                if (segment !is PlaybackSegment.Wait) return@forEachIndexed
                waits++
                val lastOldText = old.retainedTextAt(index)
                listOf(false, true).forEach { removeWait ->
                    val newStages = old.stages.map { stage ->
                        if (stage.id != segment.stageId) stage else {
                            val words = if (removeWait) List(stage.durationMin * 120 + 20) { "дополнение" }.joinToString(" ")
                                else "Дополненная последняя фраза"
                            stage.copy(texts = stage.texts + Published05Fixtures.extraText("suffix-${stage.id}", words))
                        }
                    }
                    val expanded = ServicePlaybackPlan.build(newStages, date, Published05Fixtures.reading(date))
                    val newWait = expanded.segments.filterIsInstance<PlaybackSegment.Wait>().singleOrNull { it.stageId == segment.stageId }
                    if (removeWait) assertNull(newWait) else assertNotEquals(segment, newWait)
                    offsets(segment.durationBaseMillis).forEach { offset ->
                        PlaybackSpeed.entries.forEach { speed ->
                            val migration = Published05PlaybackMigration.migrate(old, expanded, captured(old, index, offset, speed))
                            if (lastOldText == null) {
                                assertNull("No old phrase in ${segment.stageId}", migration)
                                noOldPhrase++
                            } else {
                                val target = requireNotNull(expanded.indexOfLine(lastOldText.trackId, lastOldText.lineId))
                                assertPosition(expanded, requireNotNull(migration), target, 0, speed)
                                assertEquals(PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE, migration.note)
                                safeReturns++
                            }
                        }
                    }
                }
            }
        }
        assertEquals(34, waits)
        assertEquals(576, safeReturns)
        assertEquals(36, noOldPhrase)
    }

    @Test fun `equal wait duration and segment count do not prove that the whole stage is unchanged`() {
        Published05Fixtures.dates.forEach { date ->
            val old = Published05Fixtures.oldPlan(date)
            val index = old.segments.indexOfFirst { it is PlaybackSegment.Wait && it.stageId == "great_litany" }
            val oldWait = old.segments[index] as PlaybackSegment.Wait
            val stages = old.stages.map { stage ->
                if (stage.id != oldWait.stageId) stage else stage.copy(texts = stage.texts.mapIndexed { textIndex, text ->
                    if (textIndex == 0) text.copy(original = "Другая исходная короткая фраза", lines = emptyList()) else text
                })
            }
            val changed = ServicePlaybackPlan.build(stages, date, Published05Fixtures.reading(date))
            assertEquals(oldWait, changed.segments.filterIsInstance<PlaybackSegment.Wait>().single { it.stageId == oldWait.stageId })
            assertEquals(old.segments.size, changed.segments.size)
            val oldPhrase = requireNotNull(old.retainedTextAt(index))
            val migration = requireNotNull(Published05PlaybackMigration.migrate(old, changed, captured(old, index, 5_003, PlaybackSpeed.FAST)))
            assertPosition(changed, migration, requireNotNull(changed.indexOfLine(oldPhrase.trackId, oldPhrase.lineId)), 0, PlaybackSpeed.FAST)
            assertEquals(PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE, migration.note)
        }
    }

    @Test fun `changed wait with no reliable old phrase never borrows a new phrase or the preceding stage`() {
        val date = Published05Fixtures.dates.first()
        val old = Published05Fixtures.oldPlan(date)
        val index = old.segments.indexOfFirst { it is PlaybackSegment.Wait && it.stageId == "great_litany" }
        val oldPhrase = requireNotNull(old.retainedTextAt(index))
        val removed = ServicePlaybackPlan.build(old.stages.map { stage ->
            if (stage.id == oldPhrase.stageId) stage.copy(texts = emptyList()) else stage
        }, date, Published05Fixtures.reading(date))
        assertNull(Published05PlaybackMigration.migrate(old, removed, captured(old, index, 7)))

        // This empty stage follows real text, so borrowing from the previous stage would be observable.
        val middle = LiturgyStage("empty_middle", 2, "Пустой этап", "Описание", 1, emptyList())
        val first = old.stages.first { it.texts.isNotEmpty() }.copy(order = 1)
        val minimalOld = ServicePlaybackPlan.build(listOf(first, middle), date, null)
        val emptyWaitIndex = requireNotNull(minimalOld.indexOfStage(middle.id))
        val populated = ServicePlaybackPlan.build(listOf(first, middle.copy(texts = listOf(Published05Fixtures.extraText("new-only")))), date, null)
        assertNull(minimalOld.retainedTextAt(emptyWaitIndex))
        assertNull(Published05PlaybackMigration.migrate(minimalOld, populated, captured(minimalOld, emptyWaitIndex, 9)))
    }

    @Test fun `both missing reading barriers and both ends preserve their exact stage and date`() {
        var missing = 0
        var ends = 0
        Published05Fixtures.dates.forEach { date ->
            val old = Published05Fixtures.oldPlan(date)
            val expanded = Published05Fixtures.expandedPlan(date)
            old.segments.forEachIndexed { index, segment ->
                if (segment !is PlaybackSegment.MissingReading && segment !is PlaybackSegment.End) return@forEachIndexed
                if (segment is PlaybackSegment.MissingReading) missing++ else ends++
                PlaybackSpeed.entries.forEach { speed ->
                    val migration = requireNotNull(Published05PlaybackMigration.migrate(old, expanded, captured(old, index, 0, speed)))
                    assertPosition(expanded, migration, expanded.segments.indexOf(segment), 0, speed)
                    assertEquals(segment, expanded.segments[migration.state.segmentIndex])
                    assertNull(migration.note)
                }
                assertNull(Published05PlaybackMigration.migrate(old, expanded, Published05Fixtures.snapshot(old, index, 1)))
            }
        }
        assertEquals(2, missing)
        assertEquals(2, ends)
    }

    @Test fun `newly available readings do not bypass an old missing barrier and a new terminal stage rejects old End`() {
        val date = Published05Fixtures.dates.last()
        val old = Published05Fixtures.oldPlan(date)
        val day = ReadingDay(date.toString(), "https://example.org", "Чтения", "Описание", listOf(
            DayReading("epistle", "Апостол", "Текст чтения", "Пояснение"),
            DayReading("gospel", "Евангелие", "Другой текст", "Пояснение")
        ))
        val available = ServicePlaybackPlan.build(old.stages, date, day)
        assertTrue(available.segments.none { it is PlaybackSegment.MissingReading })
        old.segments.forEachIndexed { index, segment ->
            if (segment is PlaybackSegment.MissingReading) {
                assertNull(Published05PlaybackMigration.migrate(old, available, captured(old, index)))
                val noStage = ServicePlaybackPlan.build(old.stages.filter { it.id != segment.stageId }, date, null)
                assertNull(Published05PlaybackMigration.migrate(old, noStage, captured(old, index)))
                val wrongBarrier = forged(available, segments = available.segments + PlaybackSegment.MissingReading(segment.stageId, date.plusDays(1)))
                assertNull(Published05PlaybackMigration.migrate(old, wrongBarrier, captured(old, index)))
            }
        }
        val newTerminal = LiturgyStage("new_terminal", 100, "Новый конец", "Описание", 1, emptyList())
        val appended = ServicePlaybackPlan.build(old.stages + newTerminal, date, null)
        assertNull(Published05PlaybackMigration.migrate(old, appended, captured(old, old.segments.lastIndex)))
    }

    @Test fun `old engine rejects unknown versions hashes indexes and every offset outside its own segment`() {
        Published05Fixtures.dates.forEach { date ->
            val old = Published05Fixtures.oldPlan(date)
            val expanded = Published05Fixtures.expandedPlan(date)
            val index = old.segments.indexOfFirst { it is PlaybackSegment.Text }
            val valid = captured(old, index, 3)
            val invalid = listOf(valid.copy(contentVersion = 2), valid.copy(contentVersion = 0),
                valid.copy(planFingerprint = "f".repeat(64)), valid.copy(segmentIndex = -1),
                valid.copy(segmentIndex = old.segments.size), valid.copy(segmentIndex = Int.MAX_VALUE),
                valid.copy(elapsedQuarterMillis = -1), valid.copy(elapsedQuarterMillis = Long.MAX_VALUE))
            invalid.forEach { snapshot ->
                assertNull(ServicePlaybackEngine(old).restore(snapshot))
                assertNull(Published05PlaybackMigration.migrate(old, expanded, snapshot))
            }
            old.segments.forEachIndexed { segmentIndex, segment ->
                val duration = when (segment) {
                    is PlaybackSegment.Text -> segment.durationBaseMillis * 4
                    is PlaybackSegment.Wait -> segment.durationBaseMillis * 4
                    else -> return@forEachIndexed
                }
                listOf(duration, duration + 1).forEach { outside ->
                    val snapshot = Published05Fixtures.snapshot(old, segmentIndex, outside)
                    assertNull(ServicePlaybackEngine(old).restore(snapshot))
                    assertNull(Published05PlaybackMigration.migrate(old, expanded, snapshot))
                }
            }
            val anotherDate = Published05Fixtures.expandedPlan(date.plusDays(1))
            assertNull(Published05PlaybackMigration.migrate(old, anotherDate, valid))
        }
    }

    @Test fun `text matching verifies stage canonical content and duration even when IDs have been retained incorrectly`() {
        val old = Published05Fixtures.oldPlan(Published05Fixtures.dates.first())
        val expanded = Published05Fixtures.expandedPlan(old.serviceDate)
        val samples = listOf(
            old.segments.indexOfFirst { it is PlaybackSegment.Text && old.line(it).original != null },
            old.segments.indexOfFirst { it is PlaybackSegment.Text && old.line(it).original == null }
        )
        samples.forEach { index ->
            val oldText = old.segments[index] as PlaybackSegment.Text
            val newIndex = requireNotNull(expanded.indexOfLine(oldText.trackId, oldText.lineId))
            val snapshot = captured(old, index, 1, PlaybackSpeed.SLOW)
            val changedCanonical = expanded.tracksByStage.mapValues { (_, tracks) -> tracks.map { track ->
                if (track.id != oldText.trackId) track else track.copy(lines = track.lines.map { line ->
                    if (line.id != oldText.lineId) line else if (line.original == null) line.copy(russian = line.russian + " изменено")
                        else line.copy(original = line.original + " изменено")
                })
            } }
            assertNull(Published05PlaybackMigration.migrate(old, forged(expanded, tracks = changedCanonical), snapshot))
            val wrongDuration = expanded.segments.toMutableList().apply { this[newIndex] = oldText.copy(durationBaseMillis = oldText.durationBaseMillis + 1) }
            assertNull(Published05PlaybackMigration.migrate(old, forged(expanded, segments = wrongDuration), snapshot))
            val wrongStage = expanded.segments.toMutableList().apply { this[newIndex] = oldText.copy(stageId = "other_stage") }
            assertNull(Published05PlaybackMigration.migrate(old, forged(expanded, segments = wrongStage), snapshot))
            val missingAddress = expanded.segments.filterIndexed { candidateIndex, _ -> candidateIndex != newIndex }
            assertNull(Published05PlaybackMigration.migrate(old, forged(expanded, segments = missingAddress), snapshot))
        }
    }

    @Test fun `changed canonical content with retained IDs also prevents exact wait restoration`() {
        val old = Published05Fixtures.oldPlan(Published05Fixtures.dates.first())
        val index = old.segments.indexOfFirst { it is PlaybackSegment.Wait && it.stageId == "great_litany" }
        val retained = requireNotNull(old.retainedTextAt(index))
        val first = old.segments.filterIsInstance<PlaybackSegment.Text>().first { it.stageId == retained.stageId }
        val tracks = old.tracksByStage.mapValues { (_, values) -> values.map { track ->
            if (track.id != first.trackId) track else track.copy(lines = track.lines.map { line ->
                if (line.id != first.lineId) line else line.copy(original = "Иной исходный текст")
            })
        } }
        val altered = forged(old, tracks = tracks)
        assertEquals(old.segments, altered.segments)
        val migration = requireNotNull(Published05PlaybackMigration.migrate(old, altered, captured(old, index, 4_001)))
        assertPosition(altered, migration, requireNotNull(altered.indexOfLine(retained.trackId, retained.lineId)), 0, PlaybackSpeed.NORMAL)
        assertEquals(PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE, migration.note)
        val changedLastPhrase = tracks.mapValues { (_, values) -> values.map { track ->
            if (track.id != retained.trackId) track else track.copy(lines = track.lines.map { line ->
                if (line.id != retained.lineId) line else line.copy(original = line.original + " изменено")
            })
        } }
        assertNull(Published05PlaybackMigration.migrate(old, forged(old, tracks = changedLastPhrase), captured(old, index, 4_001)))
    }

    private fun offsets(baseMillis: Long): List<Long> = listOf(0, baseMillis * 2 + 1, baseMillis * 4 - 1)

    private fun captured(plan: ServicePlaybackPlan, index: Int, offset: Long = 0, speed: PlaybackSpeed = PlaybackSpeed.NORMAL): ServicePlaybackSnapshot {
        val engine = ServicePlaybackEngine(plan)
        val state = ServicePlaybackState(plan.fingerprint, index, offset, speed)
        val snapshot = engine.snapshot(state, 0)
        assertEquals(state, engine.restore(snapshot))
        return snapshot
    }

    private fun assertPosition(plan: ServicePlaybackPlan, migration: MigratedPlaybackPosition, index: Int, offset: Long, speed: PlaybackSpeed) {
        assertEquals(ServicePlaybackState(plan.fingerprint, index, offset, speed), migration.state)
        assertFalse(migration.state.isPlaying)
        assertNull(migration.state.anchorMonotonicMillis)
        assertTrue(ServicePlaybackEngine(plan).isValid(migration.state))
    }

    private fun canonical(plan: ServicePlaybackPlan, segment: PlaybackSegment.Text): String =
        plan.line(segment).let { it.original?.takeIf(String::isNotBlank) ?: it.russian }

    /** Isolated fault injection proves that the migrator does not trust an ID on its own. */
    private fun forged(plan: ServicePlaybackPlan, segments: List<PlaybackSegment> = plan.segments,
        tracks: Map<String, List<PrayerTrack>> = plan.tracksByStage): ServicePlaybackPlan {
        val constructor = ServicePlaybackPlan::class.java.getDeclaredConstructor(LocalDate::class.java,
            List::class.java, Map::class.java, List::class.java, String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(plan.serviceDate, plan.stages, tracks, segments, "e".repeat(64))
    }
}
