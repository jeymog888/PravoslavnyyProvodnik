package ru.liturgyguide.ui

import android.Manifest
import android.content.Context
import androidx.activity.ComponentActivity
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
import ru.liturgyguide.service.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Injected recognition verifies navigation/acceptance, not real acoustics or Android permission UI. */
@RunWith(AndroidJUnit4::class)
class ListeningNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val day = LocalDate.of(2026, 11, 8)
    private val clock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC)
    private val transcript = "Отче наш Иже еси на небесех"

    @Test fun openingFromThePlayerPausesWithoutRequestingPermissionOrStartingCapture() =
        withCiFailureDiagnostics("listening.entryWithoutCapture") {
            seed()
            val speech = launch()
            resume()
            compose.onNodeWithTag("play_lyrics").performClick().assertContentDescriptionEquals("Пауза")
            val before = saved()
            val permission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            compose.onNodeWithTag("reader_microphone").assertIsDisplayed().performClick()
            compose.onNodeWithTag("listening_screen").assertIsDisplayed()
            listening("start_listening").assertIsDisplayed()
            compose.onNodeWithTag("stop_listening").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(0, speech.backend.creates)
                assertEquals(0, speech.backend.permissionChecks)
                assertEquals(SpeechStatus.IDLE, requireNotNull(speech.guide).state.status)
            }
            assertEquals(permission, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            assertEquals(before, saved())
            back()
            compose.onNodeWithTag("play_lyrics").assertContentDescriptionEquals("Двигать текст")
            assertEquals(before, saved())
        }

    @Test fun listeningFromANonemptySearchAndBackPreserveItsQueryAndVisibleResult() =
        withCiFailureDiagnostics("listening.searchBack") {
            seed()
            val speech = launch()
            val before = preferences.all.toMap()
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("find_stage"))
            compose.onNodeWithTag("find_stage").performScrollTo().performClick()
            compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_search"))
            compose.onNodeWithTag("manual_search").performClick()
            compose.onNodeWithTag("stage_search").performTextReplacement("верую")
            closeSoftKeyboardWithCiDiagnostics()
            val bounds = compose.onNodeWithTag("stage_creed").assertIsDisplayed().getBoundsInRoot()
            compose.onNodeWithTag("open_listening").assertIsDisplayed().performClick()
            compose.onNodeWithTag("listening_screen").assertIsDisplayed()
            compose.onNodeWithTag("stage_search").assertDoesNotExist()
            back()
            compose.onNodeWithTag("stage_search").assertTextContains("верую")
            assertEquals(bounds, compose.onNodeWithTag("stage_creed").assertIsDisplayed().getBoundsInRoot())
            compose.onNodeWithTag("open_listening").assertIsDisplayed()
            assertEquals(before, preferences.all)
            compose.runOnIdle { assertEquals(0, speech.backend.creates) }
        }

    @Test fun confirmingAnInjectedPhrasePreservesMeaningAcceptedDateSpeedAndOtherBookmarks() =
        withCiFailureDiagnostics("listening.correctionContext") {
            val original = seed()
            val speech = launch()
            resume()
            compose.selectReaderMode("meaning")
            val before = preferences.all.toMap()
            val oldBookmarks = preferences.all.filterKeys(ReadingAcceptanceStore::isBookmarkKey)
            compose.onNodeWithTag("reader_microphone").performClick()
            compose.onNodeWithTag("listening_screen").assertIsDisplayed()
            compose.runOnIdle { speech.inject(transcript) }
            listening("accept_listening_result").assertIsDisplayed()
            assertEquals("A candidate is not an accepted reading", before, preferences.all)
            compose.onNodeWithTag("accept_listening_result").performClick()
            compose.onNodeWithTag("listening_screen").assertDoesNotExist()
            compose.assertReaderMode("meaning", selected = true)
            val accepted = saved()
            assertEquals(original.context, accepted.context)
            assertEquals("2026-11-08", accepted.context.serviceDate)
            assertEquals(PlaybackSpeed.FAST, accepted.snapshot.speed)
            assertEquals(0L, accepted.snapshot.elapsedQuarterMillis)
            val target = original.plan.tracksByStage.getValue("lords_prayer")[0]
            assertEquals(original.plan.indexOfLine(target.id, target.lines[0].id), accepted.snapshot.segmentIndex)
            oldBookmarks.forEach { (key, value) -> assertEquals("Unrelated bookmark $key", value, preferences.all[key]) }
            assertTrue(preferences.getBoolean("lyric_original", false))
            compose.onNodeWithTag("return_to_text").performClick()
            compose.assertReaderMode("original", selected = true)
            compose.onFeedLine(target, 0).assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("play_lyrics").assertContentDescriptionEquals("Двигать текст")
            compose.runOnIdle { assertEquals(1, speech.backend.starts); assertEquals(1, speech.backend.releases) }
        }

    @Test fun rejectedAcceptanceKeepsTheTranscriptAndCandidateForAnExplicitRetry() =
        withCiFailureDiagnostics("listening.failedAcceptance") {
            val speech = FakeSpeech()
            val interrupt = ListeningInterrupt()
            var writable = false
            val choices = mutableListOf<Pair<String, String?>>()
            compose.setContent {
                GuideTheme(false) {
                    ListeningScreen(container.getStageMatcher().getAllStages(),
                        onSelect = { stage, phrase -> choices += stage to phrase; writable },
                        onManual = {}, onBack = {}, isCurrent = { true }, isForeground = { true },
                        interrupt = interrupt, speechFactory = speech.factory)
                }
            }
            compose.onNodeWithTag("listening_screen").assertIsDisplayed()
            compose.runOnIdle { speech.inject(transcript) }
            listening("accept_listening_result").performClick()
            val error = "Не удалось сохранить место. Можно повторить, найденная фраза осталась здесь"
            compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("listening_transcript").assertTextEquals("Распознано: \"$transcript\"")
            compose.runOnIdle {
                assertEquals(1, choices.size)
                assertEquals("lords_prayer", choices.single().first)
                writable = true
            }
            listening("accept_listening_result").performClick()
            compose.onNodeWithText(error).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(2, choices.size)
                assertEquals(choices[0], choices[1])
                assertEquals(1, speech.backend.creates)
                assertEquals(1, speech.backend.starts)
            }
            val oldCandidate = requireNotNull(listening("accept_listening_result").fetchSemanticsNode()
                .config[SemanticsActions.OnClick].action)
            compose.runOnIdle {
                requireNotNull(speech.guide).stop()
                // Identical words still belong to a different recognition attempt.
                speech.inject(transcript)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                oldCandidate()
                assertEquals("A callback from the previous result must remain stale after recomposition", 2, choices.size)
            }
            listening("accept_listening_result").performClick()
            compose.runOnIdle {
                assertEquals(3, choices.size)
                assertEquals(choices[0], choices.last())
                assertEquals(2, speech.backend.starts)
            }
        }

    private fun launch(): FakeSpeech {
        val speech = FakeSpeech()
        compose.setContent { AppNavigation(container, clock, monotonicMillis = { 1_000L }, speechFactory = speech.factory) }
        return speech
    }

    private fun seed(): ActiveServicePlayback {
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), day,
            container.readings?.days?.single { it.date == day.toString() })
        val engine = ServicePlaybackEngine(plan)
        val track = plan.tracksByStage.getValue("creed")[1]
        val state = engine.seekLine(engine.start("creed", PlaybackSpeed.FAST), track.id, track.lines[12].id)
            .copy(elapsedQuarterMillis = 1_234)
        val reading = ActiveServicePlayback(plan,
            ReadingContext("elisavetinsky_opalikha", day.toString(), "Europe/Moscow", ReadingContextSource.MANUAL), state)
        check(preferences.edit().clear().putString("church_id", reading.context.parishId).putBoolean("dark", false)
            .putBoolean("lyric_original", true).putInt("text_size", 0).commit())
        check(ReadingAcceptanceStore(preferences).acceptPlayback(plan, reading.context, engine.snapshot(state, 1_000),
            ServiceSession("creed", Instant.parse("2026-11-08T12:00:00Z").toEpochMilli()), true, PlaybackBookmark.at(plan, state)))
        val other = plan.tracksByStage.getValue("communion")[1]
        check(preferences.edit().putString("lyric_track_communion", other.id)
            .putString("lyric_phrase_${other.id}", other.lines[4].id).putInt("lyric_line_${other.id}", 4)
            .putLong("lyric_offset_${other.id}", 321).commit())
        return reading
    }

    private fun resume() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performScrollTo().performClick()
    }
    private fun back() { compose.onNodeWithContentDescription("Назад").performClick() }
    private fun listening(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("listening_screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun saved() = requireNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))

    private class FakeSpeech {
        val backend = FakeBackend()
        var guide: SpeechGuide? = null
        val factory: SpeechGuideFactory = { _, changed ->
            SpeechGuide(backend, SpeechScheduler { _, _ -> SpeechCancellation { } }, changed).also { guide = it }
        }
        fun inject(text: String) {
            // Explicit test stimulus through the real controller. No Android permission or microphone mutation.
            requireNotNull(guide).start()
            requireNotNull(backend.events).result(text)
        }
    }

    private class FakeBackend : SpeechBackend {
        override val apiLevel = 35
        var permissionChecks = 0
        var creates = 0
        var starts = 0
        var releases = 0
        var events: SpeechEvents? = null
        override fun isAvailable() = true
        override fun hasPermission(): Boolean { permissionChecks++; return true }
        override fun create(events: SpeechEvents): SpeechAttempt {
            creates++
            this.events = events
            return object : SpeechAttempt {
                override fun checkSupport(result: (SpeechSupport) -> Unit, error: (Int) -> Unit) =
                    result(SpeechSupport(listOf("ru-RU"), emptyList(), emptyList()))
                override fun start() { starts++; events.ready() }
                override fun finish() = Unit
                override fun release() { releases++ }
            }
        }
    }
}
