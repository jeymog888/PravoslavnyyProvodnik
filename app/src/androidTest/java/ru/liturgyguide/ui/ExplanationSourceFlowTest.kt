package ru.liturgyguide.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.service.*
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ExplanationSourceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val date = LocalDate.of(2026, 11, 8)
    // This accepted church differs from the site which published the explanation source.
    private val acceptedContext = ReadingContext("another-selected-church", date.toString(),
        "Europe/Moscow", ReadingContextSource.MANUAL)
    private val known = "https://ikoni.cerkov.ru/posledovanie-bozhestvennoj-liturgii/kratkij-ustav-sxema-liturgii-svyatitelya-ioanna-zlatousta/"
    private val knownTitle = "Краткий устав-схема Литургии святителя Иоанна Златоуста"
    private val oldCompositeTitle = "Последование литургии Иоанна Златоуста, храм Пророка Илии"

    @Test fun disclosureIsLocalAndOpeningThePublicationPreservesThePausedReading() {
        val h = launch()
        compose.runOnIdle { assertTrue(h.player.play()) }
        compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
        val before = requireNotNull(h.player.reading)
        val writesBefore = h.writes
        assertFalse(before.state.isPlaying)
        assertEquals(acceptedContext, before.context)
        assertEquals(PlaybackSpeed.SLOW, before.state.speed)
        assertEquals(3_210L, before.state.elapsedQuarterMillis)

        sourceToggle().assertTextContains("Источники текста и пояснения").assert(state("Свернуто"))
        compose.onNodeWithText(oldCompositeTitle).assertDoesNotExist()
        compose.onNodeWithText("Сайт храма Пророка Илии").assertDoesNotExist()
        sourceToggle().performClick()
        assertExpanded()
        compose.runOnIdle {
            assertTrue(h.opened.isEmpty())
            assertEquals(before, h.player.reading)
            assertEquals(writesBefore, h.writes)
            assertTrue(h.original)
        }
        sourceToggle().performClick().assert(state("Свернуто"))
        compose.onNodeWithTag("explanation_source_open").assertDoesNotExist()
        sourceToggle().performClick()
        sourceOpen().performClick()
        compose.runOnIdle {
            assertEquals(listOf(known), h.opened)
            assertEquals(before, h.player.reading)
            assertEquals(writesBefore, h.writes)
            assertTrue(h.original)
        }
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
    }

    @Test fun changingTheStageOrItsAddressResetsDisclosureAndMissingAddressHasNoAction() {
        val h = launch()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        sourceToggle().performClick()
        assertExpanded()
        compose.onNodeWithTag("return_to_text").performClick()
        val next = requireNotNull(h.player.reading).plan.tracksByStage.getValue("lords_prayer").single { it.title == "Отче наш" }
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag(feedLineTag(next.lines[0])))
        compose.onFeedLine(next, 0).performScrollTo().performClick()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        sourceToggle().assert(state("Свернуто"))
        compose.onNodeWithTag("explanation_source_open").assertDoesNotExist()
        sourceToggle().performClick()
        assertExpanded()

        val anotherUrl = "https://ikoni.cerkov.ru/another-page/"
        compose.runOnIdle {
            assertTrue(h.player.accept(reading(stages().map {
                if (it.id == "lords_prayer") it.copy(sourceUrl = anotherUrl, sourceTitle = "  Другая публикация  ") else it
            }, stageId = "lords_prayer"), resetBookmarks = false))
        }
        sourceToggle().assert(state("Свернуто"))
        compose.onNodeWithTag("explanation_source_open").assertDoesNotExist()
        sourceToggle().performClick()
        scrollTo("explanation_source_title").assertTextEquals("Другая публикация")
        sourceOpen().performClick()
        compose.runOnIdle { assertEquals(listOf(anotherUrl), h.opened) }

        compose.runOnIdle {
            assertTrue(h.player.accept(reading(stages().map {
                if (it.id == "lords_prayer") it.copy(sourceUrl = null, texts = it.texts.map { text -> text.copy(sourceUrl = null) }) else it
            }, stageId = "lords_prayer"), resetBookmarks = false))
        }
        // Reach the end before asserting absence, so lazy composition cannot hide a stale source.
        val list = compose.onNodeWithTag("service_content")
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        list.assert(SemanticsMatcher("The end of the explanation is visible") { node ->
            val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
            range.value() >= range.maxValue() - 1f
        })
        compose.onNodeWithTag("explanation_source_toggle").assertDoesNotExist()
        compose.onNodeWithTag("explanation_source_open").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(anotherUrl), h.opened) }
    }

    @Test fun recreationKeepsTheExpandedPublicationWithoutOpeningItOrChangingTheReading() {
        val h = launch()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        sourceToggle().performClick()
        val before = requireNotNull(h.player.reading)
        val writesBefore = h.writes
        h.restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        sourceToggle().assert(state("Развернуто"))
        assertExpanded()
        compose.runOnIdle {
            assertEquals(before, h.player.reading)
            assertEquals(writesBefore, h.writes)
            assertTrue(h.original)
            assertTrue(h.opened.isEmpty())
        }
    }

    @Test fun aDifferentStageOrAddressAtRestorationCannotInheritTheOldExpandedSource() {
        val h = launch()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        sourceToggle().performClick()
        sourceToggle().assert(state("Развернуто"))
        compose.runOnIdle {
            // A plain reference changes the next composition, not the currently saved one.
            h.player = ServicePlayerState(reading(stageId = "lords_prayer"), { 1_000L },
                // Replacement owners are only used to test saved UI identity, not persistence.
                ServicePlaybackWriter { _, _, _ -> true })
        }
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true)
            .assertTextEquals(requireNotNull(container.getStageMatcher().getStageById("creed")).name)
        h.restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true)
            .assertTextEquals(requireNotNull(container.getStageMatcher().getStageById("lords_prayer")).name)
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        sourceToggle().assert(state("Свернуто"))
        sourceToggle().performClick()
        sourceToggle().assert(state("Развернуто"))
        compose.runOnIdle {
            val changed = stages().map {
                if (it.id == "lords_prayer") it.copy(sourceUrl = "https://example.org/new", sourceTitle = "Новая публикация") else it
            }
            h.player = ServicePlayerState(reading(changed, "lords_prayer"), { 1_000L },
                ServicePlaybackWriter { _, _, _ -> true })
        }
        h.restoration.emulateSavedInstanceStateRestore()
        sourceToggle().assert(state("Свернуто"))
        compose.onNodeWithTag("explanation_source_open").assertDoesNotExist()
        sourceToggle().performClick()
        scrollTo("explanation_source_title").assertTextEquals("Новая публикация")
        compose.runOnIdle { assertTrue(h.opened.isEmpty()) }
    }

    private class Harness(var player: ServicePlayerState, val restoration: StateRestorationTester,
        val opened: MutableList<String>, val writeCount: () -> Int) {
        var original by mutableStateOf(true)
        val writes get() = writeCount()
    }

    private fun launch(): Harness {
        var writes = 0
        val player = ServicePlayerState(reading(), { 1_000L }, ServicePlaybackWriter { _, _, _ -> writes++; true })
        val h = Harness(player, StateRestorationTester(compose), mutableListOf()) { writes }
        h.restoration.setContent {
            GuideTheme(false) {
                val displayedPlayer = h.player
                ServiceScreen(displayedPlayer, onStageChange = { displayedPlayer.seekStage(it) }, onBack = {}, onSelectStage = {},
                    onFindStage = {}, onSettings = {}, dark = false, onDarkChange = {}, onOpenSource = { h.opened.add(it) },
                    onSoundSettings = {}, onFinish = {}, preferredOriginal = h.original,
                    onOriginalChange = { h.original = it })
            }
        }
        return h
    }

    private fun stages(): List<LiturgyStage> = container.getStageMatcher().getAllStages()
        .filter { it.id in setOf("creed", "lords_prayer") }
        .map { it.copy(sourceUrl = known, sourceTitle = oldCompositeTitle) }

    private fun reading(stages: List<LiturgyStage> = stages(), stageId: String = "creed"): ActiveServicePlayback {
        val plan = ServicePlaybackPlan.build(stages, date, null)
        val engine = ServicePlaybackEngine(plan)
        val title = if (stageId == "creed") "Символ веры" else "Отче наш"
        val track = plan.tracksByStage.getValue(stageId).single { it.title == title }
        val state = engine.seekLine(engine.start(stageId, PlaybackSpeed.SLOW), track.id, track.lines[3].id)
            .copy(elapsedQuarterMillis = 3_210L)
        return ActiveServicePlayback(plan, acceptedContext, state)
    }

    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun sourceToggle() = scrollTo("explanation_source_toggle")
    private fun sourceOpen() = scrollTo("explanation_source_open")
    private fun assertExpanded() {
        sourceToggle().assert(state("Развернуто"))
        val purpose = "Публикация для подготовки пояснения"
        compose.onNodeWithText(purpose).performScrollTo().assertIsDisplayed()
        scrollTo("explanation_source_title").assertTextEquals(knownTitle)
        compose.onNodeWithText(oldCompositeTitle).assertDoesNotExist()
        sourceOpen().assertTextContains("Открыть пояснение")
    }
}
