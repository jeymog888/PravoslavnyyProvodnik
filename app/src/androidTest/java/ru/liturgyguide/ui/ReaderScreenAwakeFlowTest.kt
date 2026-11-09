package ru.liturgyguide.ui

import android.content.Context
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
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
import java.util.concurrent.atomic.AtomicLong

/** Tests the actual Activity window; device timeout/power behavior remains a separate native check. */
@RunWith(AndroidJUnit4::class)
class ReaderScreenAwakeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val instant = Instant.parse("2026-11-08T12:00:00Z")

    @Test fun realNavigationRetainsOnlyMovingReadingAndTempoWhileMeaningAndSettingsReleaseIt() =
        withCiFailureDiagnostics("readerAwake.appNavigation") {
            val now = AtomicLong(1_000L)
            val initial = seedAcceptedReading()
            val initialBookmarks = bookmarks()
            val foreground = ActivityForegroundState()
            lateinit var owner: ReaderScreenAwakeOwner
            compose.runOnUiThread {
                foreground.onTopResumedActivityChanged(true)
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                owner = readerScreenAwakeOwner(compose.activity.window)
            }
            compose.setContent {
                AppNavigation(container, Clock.fixed(instant, ZoneOffset.UTC), now::get, foreground, owner)
            }
            assertWindow(false)
            resumeReading()
            assertWindow(false)
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Автопрокрутка").performClick()
            assertWindow(true)
            compose.runOnIdle { now.addAndGet(101) }
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Пауза").performClick()
            assertWindow(false)
            assertEquals(1_638L, saved().snapshot.elapsedQuarterMillis)

            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(true)
            compose.onNodeWithTag("player_menu").performClick()
            compose.onNodeWithTag("scroll_options").performClick()
            compose.onNodeWithTag("tempo_done").assertIsDisplayed()
            assertWindow(true)
            compose.runOnIdle { now.addAndGet(100) }
            compose.onNodeWithTag("tempo_slow").performScrollTo().performClick().assertIsSelected()
            assertEquals(2_038L, saved().snapshot.elapsedQuarterMillis)
            assertEquals(PlaybackSpeed.SLOW, saved().snapshot.speed)
            assertWindow(true)
            compose.onNodeWithText("Готово").performClick()
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Пауза")
            assertWindow(true)
            compose.runOnIdle { now.addAndGet(100) }
            compose.selectReaderMode("meaning")
            compose.onNodeWithTag("return_to_text").assertIsDisplayed()
            assertWindow(false)
            val explained = saved()
            assertEquals(2_338L, explained.snapshot.elapsedQuarterMillis)
            assertEquals(initial.context, explained.context)
            assertEquals(initial.state.segmentIndex, explained.snapshot.segmentIndex)
            assertEquals(initialBookmarks, bookmarks())
            compose.runOnIdle { now.addAndGet(86_400_000) }
            compose.onNodeWithTag("return_to_text").performClick()
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Автопрокрутка")
            compose.assertReaderMode("original", selected = true)
            assertEquals(explained, saved())
            assertWindow(false)

            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(true)
            compose.onNodeWithTag("player_menu").performClick()
            compose.onNodeWithText("Размер текста").performClick()
            compose.onNodeWithTag("settings_content").assertIsDisplayed()
            assertWindow(false)
            val stopped = saved()
            compose.runOnIdle { now.addAndGet(86_400_000) }
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Автопрокрутка")
            assertEquals(stopped, saved())
            assertTrue(preferences.getBoolean("lyric_original", false))
            assertEquals(initialBookmarks, bookmarks())
            assertWindow(false)
        }

    @Test fun topLossAndGainInOneCallbackReleaseBeforeSavingAndNeverAutomaticallyResume() =
        withCiFailureDiagnostics("readerAwake.topLoss") {
            val h = launch(reading("creed", PlaybackSegment.Text::class.java))
            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(true)
            lateinit var stopped: ActiveServicePlayback
            compose.runOnUiThread {
                assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
                h.now += 1_234
                h.foreground.onTopResumedActivityChanged(false)
                assertFalse(windowFlag())
                stopped = h.current
                assertFalse(stopped.state.isPlaying)
                assertEquals(4_936L, stopped.state.elapsedQuarterMillis)
                val pause = h.writes.last()
                assertFalse(pause.playing)
                assertFalse("The writer must already observe the released window", pause.flag)
                h.now += 86_400_000
                h.foreground.onTopResumedActivityChanged(true)
                assertFalse(windowFlag())
                assertEquals(stopped, h.current)
            }
            assertWindow(false)
            compose.runOnIdle { assertEquals(stopped, h.current) }
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Автопрокрутка").performClick()
            assertWindow(true)
            compose.runOnIdle { h.now++ }
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.runOnIdle { assertEquals(4_940L, h.current.state.elapsedQuarterMillis) }
            assertWindow(false)
        }

    @Test fun activityPauseReleasesBeforeTheWriterAndReturnPreservesTheMaterializedPlace() =
        withCiFailureDiagnostics("readerAwake.lifecycle") {
            val h = launch(reading("creed", PlaybackSegment.Text::class.java))
            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(true)
            compose.runOnIdle { h.now += 101 }
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            lateinit var stopped: ActiveServicePlayback
            compose.activityRule.scenario.onActivity {
                assertFalse(windowFlag())
                assertFalse(h.resumed)
                stopped = h.current
                assertFalse(stopped.state.isPlaying)
                assertEquals(404L, stopped.state.elapsedQuarterMillis)
                assertFalse(h.writes.last().flag)
                assertFalse(h.writes.last().playing)
                h.now += 86_400_000
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(h.resumed)
                assertEquals(stopped, h.current)
            }
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Автопрокрутка")
            assertWindow(false)
        }

    @Test fun preparationAndOrdinaryWaitRetainOnlyWhileMovingAndMissingAndEndRelease() =
        withCiFailureDiagnostics("readerAwake.waitAndBarriers") {
            val h = launch(reading("preparation", PlaybackSegment.Wait::class.java))
            assertWindow(false)
            // Preparation is still an actual Wait; the continuous reader exposes its standard play/pause action.
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Автопрокрутка").performClick()
            compose.runOnIdle { assertTrue(h.current.state.isPlaying); assertTrue(h.current.segment is PlaybackSegment.Wait) }
            assertWindow(true)
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Пауза").performClick()
            assertWindow(false)
            compose.runOnIdle { assertTrue(h.player.accept(reading("great_litany", PlaybackSegment.Wait::class.java), false)) }
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Автопрокрутка").performClick()
            assertWindow(true)
            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(false)

            compose.runOnIdle { assertTrue(h.player.accept(reading("epistle", PlaybackSegment.Text::class.java), false)) }
            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(true)
            compose.runOnIdle { h.now += 86_400_000; h.player.tick(h.player.generation) }
            compose.onNodeWithTag("continue_missing_reading").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(h.current.segment is PlaybackSegment.MissingReading)
                assertFalse(h.current.state.isPlaying)
                assertFalse(h.player.play())
            }
            assertWindow(false)

            compose.runOnIdle { assertTrue(h.player.accept(reading("dismissal", PlaybackSegment.Wait::class.java), false)) }
            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(true)
            compose.runOnIdle { h.now += 86_400_000; h.player.tick(h.player.generation) }
            compose.onNodeWithTag("finish_reading").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(h.current.segment is PlaybackSegment.End)
                assertFalse(h.current.state.isPlaying)
                assertFalse(h.player.play())
            }
            assertWindow(false)
        }

    @Test fun rejectedPlayNeverAcquiresAndFailedMandatoryPauseReleasesBeforeTheFailedWrite() =
        withCiFailureDiagnostics("readerAwake.failedWrites") {
            val h = launch(reading("creed", PlaybackSegment.Text::class.java))
            val initial = compose.runOnIdle { h.current.also { h.writable = false } }
            compose.onNodeWithTag("play_lyrics").performClick()
            compose.runOnIdle {
                assertEquals(initial, h.current)
                assertEquals(1, h.writes.size)
                assertFalse(h.writes.single().flag)
                assertFalse(h.writes.single().accepted)
                h.writable = true
            }
            assertWindow(false)
            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(true)
            lateinit var stopped: ActiveServicePlayback
            compose.runOnUiThread {
                h.writable = false
                h.now += 101
                h.foreground.onTopResumedActivityChanged(false)
                assertFalse(windowFlag())
                stopped = h.current
                assertFalse(stopped.state.isPlaying)
                assertEquals(404L, stopped.state.elapsedQuarterMillis)
                assertEquals(3, h.writes.size)
                assertFalse(h.writes.last().flag)
                assertFalse(h.writes.last().accepted)
                assertFalse(h.writes.last().playing)
                h.now += 86_400_000
                h.foreground.onTopResumedActivityChanged(true)
                h.player.tick(h.player.generation)
                assertEquals(stopped, h.current)
                assertEquals(3, h.writes.size)
            }
            compose.onNodeWithTag("play_lyrics").assertTextEquals("Автопрокрутка")
            assertWindow(false)
        }

    @Test fun replacedBindingsCannotClearTheNewWindowAndDisposalReleasesBeforeSaving() =
        withCiFailureDiagnostics("readerAwake.replacementAndDisposal") {
            val h = launch(reading("creed", PlaybackSegment.Text::class.java))
            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(true)
            val oldBinding = compose.runOnIdle { requireNotNull(h.binding) }
            val oldPlayer = compose.runOnIdle { h.player }
            compose.runOnIdle {
                h.now += 101
                h.player = h.newPlayer(reading("creed", PlaybackSegment.Text::class.java, LocalDate.of(2026, 11, 10)), "new")
            }
            assertWindow(false)
            compose.runOnIdle {
                assertFalse(requireNotNull(oldPlayer.reading).state.isPlaying)
                assertEquals(404L, requireNotNull(oldPlayer.reading).state.elapsedQuarterMillis)
                assertEquals("old", h.writes.last().owner)
                assertFalse(h.writes.last().flag)
                assertNotSame(oldBinding, h.binding)
            }
            compose.onNodeWithTag("play_lyrics").performClick()
            assertWindow(true)
            compose.runOnUiThread {
                val accepted = h.current
                val writes = h.writes.size
                oldBinding.update(false)
                oldBinding.pause()
                oldBinding.close()
                assertTrue(windowFlag())
                assertEquals(accepted, h.current)
                assertTrue(h.current.state.isPlaying)
                assertEquals(writes, h.writes.size)
                h.now += 101
                h.mounted = false
            }
            compose.onNodeWithTag("reader_surface").assertDoesNotExist()
            assertWindow(false)
            compose.runOnIdle {
                assertEquals("new", h.writes.last().owner)
                assertFalse(h.writes.last().flag)
                assertFalse(h.writes.last().playing)
                assertFalse(h.current.state.isPlaying)
                assertEquals(404L, h.current.state.elapsedQuarterMillis)
            }
        }

    private data class Write(val owner: String, val playing: Boolean, val flag: Boolean, val accepted: Boolean)

    private class Harness(initial: ActiveServicePlayback, val window: Window) {
        var now = 1_000L
        var writable = true
        var mounted by mutableStateOf(true)
        var original by mutableStateOf(true)
        var resumed by mutableStateOf(false)
        val foreground = ActivityForegroundState()
        val owner = readerScreenAwakeOwner(window)
        val writes = mutableListOf<Write>()
        var binding: ReaderScreenAwakeOwner.Binding? = null
        fun newPlayer(initial: ActiveServicePlayback, label: String) = ServicePlayerState(initial, { now },
            ServicePlaybackWriter { value, _, _ ->
                // Record rather than assert here: ServicePlayerState deliberately catches writer failures.
                writes += Write(label, value.state.isPlaying,
                    window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0, writable)
                writable
            })
        var player by mutableStateOf(newPlayer(initial, "old"))
        val current get() = requireNotNull(player.reading)
    }

    private fun launch(initial: ActiveServicePlayback): Harness {
        lateinit var h: Harness
        compose.runOnUiThread {
            h = Harness(initial, compose.activity.window)
            h.foreground.onTopResumedActivityChanged(true)
        }
        compose.setContent {
            if (h.mounted) {
                val binding = rememberReaderScreenAwakeBinding(h.player, true, LocalLifecycleOwner.current.lifecycle,
                    h.foreground, h.owner) { h.resumed = it }
                SideEffect { h.binding = binding }
                GuideTheme(false) {
                    ServiceScreen(h.player, onStageChange = { h.player.seekStage(it) }, onBack = {}, onSelectStage = {},
                        onFindStage = {}, onSettings = {}, dark = false, onDarkChange = {}, onOpenSource = {},
                        onSoundSettings = {}, onFinish = { h.player.forgetAccepted() },
                        preferredOriginal = h.original, onOriginalChange = { h.original = it })
                }
            }
        }
        compose.onNodeWithTag("reader_surface").assertIsDisplayed()
        assertWindow(false)
        return h
    }

    private fun reading(stage: String, type: Class<out PlaybackSegment>, date: LocalDate = LocalDate.of(2026, 11, 9)): ActiveServicePlayback {
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), date,
            container.readings?.days?.singleOrNull { it.date == date.toString() })
        val index = plan.segments.indexOfFirst { it.stageId == stage && type.isInstance(it) }
        check(index >= 0)
        return ActiveServicePlayback(plan, ReadingContext("elisavetinsky_opalikha", date.toString(), "Europe/Moscow", ReadingContextSource.MANUAL),
            ServicePlaybackEngine(plan).start(stage, PlaybackSpeed.NORMAL).copy(segmentIndex = index))
    }

    private fun seedAcceptedReading(): ActiveServicePlayback {
        val base = reading("creed", PlaybackSegment.Text::class.java, LocalDate.of(2026, 11, 8))
        val track = base.tracks.single { it.title == "Символ веры" }
        val state = base.engine.seekLine(base.state, track.id, track.lines[12].id).copy(elapsedQuarterMillis = 1_234)
        val initial = base.copy(state = state)
        check(preferences.edit().clear().putString("church_id", initial.context.parishId)
            .putBoolean("dark", false).putBoolean("lyric_original", true).putInt("text_size", 0).commit())
        check(ReadingAcceptanceStore(preferences).acceptPlayback(initial.plan, initial.context, initial.engine.snapshot(state, 1_000),
            ServiceSession("creed", instant.toEpochMilli()), true, PlaybackBookmark.at(initial.plan, state)))
        return initial
    }

    private fun resumeReading() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performScrollTo().performClick()
    }

    private fun saved() = requireNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun bookmarks(): Map<String, *> = preferences.all.filterKeys(ReadingAcceptanceStore::isBookmarkKey)
    private fun windowFlag() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    private fun assertWindow(expected: Boolean) {
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Actual Activity FLAG_KEEP_SCREEN_ON", expected, windowFlag()) }
    }
}
