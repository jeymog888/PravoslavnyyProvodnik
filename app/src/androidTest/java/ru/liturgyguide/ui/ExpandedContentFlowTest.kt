package ru.liturgyguide.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.service.*
import java.time.LocalDate

/** Packaged content through the real reader; only time and persistence are controlled here. */
@RunWith(AndroidJUnit4::class)
class ExpandedContentFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
    private val date = LocalDate.of(2026, 11, 8)
    private val accepted = ReadingContext("selected-test-church", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL)
    private val plan by lazy { ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), date,
        container.readings?.days?.singleOrNull { it.date == date.toString() }) }

    @Test fun localOmissionsRemainAvailableInBothLanguagesWithoutBecomingReadingPositions() =
        withCiFailureDiagnostics("content.localOmissionsRemainAvailableInBothLanguages") {
            val h = launch("great_litany", "О стране")
            for ((title, omission) in listOf("О стране" to "Имена и титулы зависят от места служения",
                    "О нуждах людей" to "Местный вариант этого прошения")) {
                selectTrack(h, title)
                val before = requireNotNull(h.player.reading)
                val writes = h.writes
                for (original in listOf(true, false)) {
                    mode(original)
                    val note = lyrics(feedContextTag(requireNotNull(before.track)))
                    note.assertTextContains(omission, substring = true)
                        .assertTextContains("пропущены", substring = true).assert(noClick())
                    assertCurrentLine(before, original)
                    unchanged(h, before, writes)
                }
            }
        }

    @Test fun anaphoraSuppliesTheMissingSubjectAndExplanationsBesideBothLanguagePresentations() =
        withCiFailureDiagnostics("content.anaphoraSuppliesSubjectAndExplanations") {
            val h = launch("anaphora", "Приготовимся к благодарению")
            val notes = listOf(
                "Приготовимся к благодарению" to "Христос примиряет нас с Богом",
                "Свят Господь" to "Возглас продолжает молитву об ангелах, которые славят Бога",
                "Приношение даров" to "Все, что мы приносим Богу, уже получено от Него")
            for ((title, explanation) in notes) {
                selectTrack(h, title)
                val before = requireNotNull(h.player.reading)
                val writes = h.writes
                for (original in listOf(true, false)) {
                    mode(original)
                    lyrics(feedContextTag(requireNotNull(before.track))).assertTextContains(explanation, substring = true).assert(noClick())
                    assertCurrentLine(before, original)
                    unchanged(h, before, writes)
                }
            }
            val beforeMeaning = requireNotNull(h.player.reading)
            val writes = h.writes
            compose.onNodeWithTag("player_meaning_tab").performClick()
            scroll("service_content", "prayer_context_note")
                .assertTextContains("Все, что мы приносим Богу, уже получено от Него", substring = true).assert(noClick())
            compose.onNodeWithTag("reading_note_toggle").assertDoesNotExist()
            unchanged(h, beforeMeaning, writes)
        }

    @Test fun thePublishedMixedDialogueHasTwoVoicesWhileOnlyTappingAPhraseChangesItsAddress() =
        withCiFailureDiagnostics("content.mixedDialogueVoicesAndExplicitPhraseChoice") {
            val h = launch("anaphora", "Горе имеим сердца", lineIndex = 1)
            val before = requireNotNull(h.player.reading)
            val writes = h.writes
            for (original in listOf(true, false)) {
                mode(original)
                lyrics(feedSpeakerTag(requireNotNull(before.track).lines[0])).assertTextEquals("Священник").assert(noClick())
                lyrics(feedSpeakerTag(requireNotNull(before.track).lines[1])).assertTextEquals("Хор").assert(noClick())
                assertCurrentLine(before, original)
                unchanged(h, before, writes)
            }
            lyrics(feedLineTag(requireNotNull(before.track).lines[0])).performClick()
            compose.runOnIdle {
                val after = requireNotNull(h.player.reading)
                assertEquals(before.context, after.context)
                assertEquals(before.plan.fingerprint, after.plan.fingerprint)
                assertEquals(before.state.speed, after.state.speed)
                assertEquals(requireNotNull(before.track).lines[0].id, after.text?.lineId)
                assertEquals(0L, after.state.elapsedQuarterMillis)
                assertFalse(after.state.isPlaying)
                assertEquals(writes + 1, h.writes)
            }
        }

    @Test fun scriptureRetainsItsSeparateDayExplanationAndDoesNotAcquireAPrayerSpeaker() =
        withCiFailureDiagnostics("content.scriptureRetainsDayExplanation") {
            val scripture = plan.tracksByStage.getValue("gospel").first { it.isScripture }
            val h = launch("gospel", scripture.title)
            val before = requireNotNull(h.player.reading)
            val writes = h.writes
            lyrics(feedLanguageTag(scripture)).assertIsDisplayed()
                .assertTextEquals("${scripture.subtitle}. Церковнославянский текст пока не добавлен")
            compose.onNodeWithTag(feedSpeakerTag(scripture.lines[0])).assertDoesNotExist()
            compose.onNodeWithTag(feedContextTag(scripture)).assertDoesNotExist()
            compose.onNodeWithTag("player_meaning_tab").performClick()
            scroll("service_content", "reading_note_toggle").assertTextContains("О чтениях этого дня").performClick()
            scroll("service_content", "reading_day_note").assertTextEquals(requireNotNull(scripture.contextNote))
            compose.onNodeWithTag("prayer_context_note").assertDoesNotExist()
            unchanged(h, before, writes)
        }

    private class Harness(val player: ServicePlayerState, val count: () -> Int) {
        var original by mutableStateOf(true)
        val writes get() = count()
    }

    private fun launch(stageId: String, title: String, lineIndex: Int = 0): Harness {
        val track = plan.tracksByStage.getValue(stageId).single { it.title == title }
        val engine = ServicePlaybackEngine(plan)
        val state = engine.seekLine(engine.start(stageId, PlaybackSpeed.SLOW), track.id, track.lines[lineIndex].id)
            .copy(elapsedQuarterMillis = 1_234L)
        var writes = 0
        val player = ServicePlayerState(ActiveServicePlayback(plan, accepted, state), { 1_000L },
            ServicePlaybackWriter { _, _, _ -> writes++; true })
        val h = Harness(player) { writes }
        compose.setContent {
            GuideTheme(false) {
                ServiceScreen(player, onStageChange = { player.seekStage(it) }, onBack = {}, onSelectStage = {},
                    onFindStage = {}, onSettings = {}, dark = false, onDarkChange = {}, onOpenSource = {},
                    onSoundSettings = {}, onFinish = {}, preferredOriginal = h.original,
                    onOriginalChange = { h.original = it })
            }
        }
        return h
    }

    private fun selectTrack(h: Harness, title: String) {
        if (h.player.reading?.track?.title == title) return
        val tracks = requireNotNull(h.player.reading).tracks
        val index = tracks.indexOfFirst { it.title == title }
        check(index >= 0)
        compose.openReaderTracks()
        val expandable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        expandable.assertCountEquals(1)
        expandable[0].assertIsDisplayed().performSemanticsAction(SemanticsActions.Expand) { action ->
            assertTrue(action())
        }
        compose.waitForIdle()
        scroll("track_list", "track_$index").performClick()
        compose.waitForIdle()
        assertEquals(title, h.player.reading?.track?.title)
    }

    private fun mode(original: Boolean) = compose.onNodeWithTag(if (original) "player_original_tab" else "player_text_tab")
        .performClick().assertIsSelected()
    private fun scroll(parent: String, tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(parent).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun lyrics(tag: String) = scroll("lyrics_list", tag)
    private fun noClick() = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
    private fun assertCurrentLine(reading: ActiveServicePlayback, original: Boolean) {
        val track: PrayerTrack = requireNotNull(reading.track)
        val line = track.lines[reading.lineIndex]
        lyrics(feedLineTag(track.lines[reading.lineIndex])).assertIsSelected()
            .assertTextEquals(if (original) requireNotNull(line.original) else line.russian)
    }
    private fun unchanged(h: Harness, before: ActiveServicePlayback, writes: Int) = compose.runOnIdle {
        assertEquals(before, h.player.reading)
        assertEquals(writes, h.writes)
        assertEquals(accepted, h.player.reading?.context)
        assertFalse(requireNotNull(h.player.reading).state.isPlaying)
    }
}
