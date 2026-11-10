package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A scoped manual correction must retain the chosen occurrence before and after midnight. */
@RunWith(AndroidJUnit4::class)
class ExhaustedServiceEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val parish get() = container.parishes.getOrThrow().selected(null)
    private val afterMidnight = Instant.parse("2026-11-08T21:50:00Z")
    private val expected = ReadingContext("elisavetinsky_opalikha", "2026-11-08", "Europe/Moscow",
        ReadingContextSource.USER, Instant.parse("2026-11-08T20:00:00Z").toEpochMilli())

    @Test fun aLandmarkAfterMidnightPreservesTheOccurrenceAndRetriesAnAtomicWrite() {
        seed()
        val before = preferences.all.toMap()
        val storage = FailNextCommitPreferences(preferences)
        val restoration = launch(AdjustableClock(afterMidnight), storage)
        watchCurrent()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        assertPending("23:00")
        assertUnchanged(before)
        restoration.emulateSavedInstanceStateRestore()
        assertPending("23:00")
        landmark("creed")
        restoration.emulateSavedInstanceStateRestore()
        assertPending("23:00")
        assertUnchanged(before)
        compose.runOnIdle { storage.failNextCommit = true }
        accept()
        compose.onNodeWithTag("arrival_content").assertIsDisplayed()
        assertUnchanged(before)
        assertEquals(1, storage.failedCommits)
        // The same pending occurrence remains available after the failed disk report and rollback.
        accept()
        assertAccepted(expected, "creed")
        assertFalse(preferences.contains("lyric_phrase_old_track"))
        assertTrue(preferences.getBoolean("lyric_original", false))
    }

    @Test fun aSearchAfterMidnightRestoresAndFindsTheChosenDaysScripture() {
        seed()
        val before = preferences.all.toMap()
        val restoration = launch(AdjustableClock(afterMidnight))
        watchCurrent()
        compose.onNodeWithTag("manual_search").performClick()
        typeSearch("Лазарь")
        compose.onNodeWithTag("stage_gospel").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("stage_search").assertTextContains("Лазарь")
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_gospel"))
        assertUnchanged(before)
        compose.onNodeWithTag("stage_gospel").performClick()
        assertAccepted(expected, "gospel", russianFallback = true)
        val plan = planFor(expected)
        val text = plan.segments[saved().snapshot.segmentIndex] as PlaybackSegment.Text
        assertEquals("От Луки 16:19-31", plan.track(text.trackId)?.title)
        assertTrue(plan.line(text).russian.contains("Лазарь"))
        assertTrue(preferences.getBoolean("lyric_original", false))
    }

    @Test fun midnightDuringTheDraftDoesNotRebindItsDateAndBackDoesNotAccept() {
        seed(start = "22:00")
        val before = preferences.all.toMap()
        val clock = AdjustableClock(Instant.parse("2026-11-08T20:50:00Z")) // 23:50 at the parish.
        val restoration = launch(clock)
        watchCurrent()
        assertPending("22:00")
        landmark("lords_prayer")
        assertPending("22:00")
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        assertUnchanged(before)
        watchCurrent()
        // This second draft is opened before midnight and accepted after midnight without reopening it.
        compose.runOnIdle { clock.current = Instant.parse("2026-11-08T21:10:00Z") }
        compose.mainClock.advanceTimeBy(6_000)
        restoration.emulateSavedInstanceStateRestore()
        assertPending("22:00")
        assertUnchanged(before)
        landmark("lords_prayer")
        accept()
        assertAccepted(expected.copy(startEpochMillis = Instant.parse("2026-11-08T19:00:00Z").toEpochMilli()), "lords_prayer")
    }

    @Test fun anEstimateThatBecomesExhaustedBeforeTheTapUsesTheFreshResolverResult() {
        seed()
        val before = preferences.all.toMap()
        val clock = AdjustableClock(Instant.parse("2026-11-08T21:30:00Z")) // Only 90 minutes after the start.
        launch(clock)
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("service_status_current"))
        compose.onNodeWithTag("service_status_current").assertTextEquals("Возможно, уже идет")
        compose.runOnIdle { clock.current = afterMidnight }
        watchCurrent()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        assertPending("23:00")
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertDoesNotExist()
        assertUnchanged(before)
    }

    @Test fun staleCallbacksCannotReplaceTheDraftForANewerAcceptedService() {
        seed()
        launch(AdjustableClock(afterMidnight))
        watchCurrent()
        val oldSearch = clickAction("manual_search")
        compose.onNodeWithTag("manual_search").performClick()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
        val oldSearchResult = clickAction("stage_creed")
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_creed"))
        val oldLandmark = clickAction("manual_creed")
        compose.onNodeWithTag("manual_creed").performClick()
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("accept_arrival"))
        val oldAccept = clickAction("accept_arrival")
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("watch_next"))
        compose.onNodeWithTag("watch_next").performClick()
        assertEquals("2026-11-09", saved().context.serviceDate)
        compose.onNodeWithTag("clarify_stage").performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        val acceptedNext = preferences.all.toMap()
        compose.runOnIdle { oldLandmark(); oldAccept(); oldSearch(); oldSearchResult() }
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        compose.onNodeWithTag("arrival_content").assertDoesNotExist()
        compose.onNodeWithTag("stage_search").assertDoesNotExist()
        assertUnchanged(acceptedNext)
    }

    @Test fun lostOrCorruptDraftsReturnSafelyFromManualArrivalAndBothScopedSearchOrigins() {
        val registry = mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        val mounted = mutableStateOf(false)
        val clock = AdjustableClock(afterMidnight)
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry.value) {
                if (mounted.value) AppNavigation(container, clock)
            }
        }
        val routes = listOf("manual", "arrival", "search-manual", "search-arrival")
        routes.forEachIndexed { index, route ->
            compose.runOnIdle {
                seed()
                registry.value = SaveableStateRegistry(restoredValues = null) { true }
                mounted.value = true
            }
            val before = preferences.all.toMap()
            watchCurrent()
            if (route == "arrival" || route == "search-arrival") landmark("creed")
            if (route.startsWith("search")) {
                val tag = if (route == "search-manual") "manual_search" else "arrival_search"
                if (route == "search-arrival") compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performClick()
                compose.onNodeWithTag("stage_search").assertIsDisplayed()
            }
            var savedState: Map<String, List<Any?>> = emptyMap()
            compose.runOnIdle { savedState = registry.value.performSave(); mounted.value = false }
            compose.waitForIdle()
            val corrupted = corruptOneDraft(savedState, missing = index % 2 == 0)
            compose.runOnIdle {
                registry.value = SaveableStateRegistry(restoredValues = corrupted) { true }
                mounted.value = true
            }
            compose.onNodeWithTag("main_content").assertIsDisplayed()
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("reading_restore_notice"))
            compose.onNodeWithTag("reading_restore_notice").assertTextEquals(
                "Не удалось восстановить выбранную службу. Выберите ее заново")
            compose.onNodeWithTag("manual_content").assertDoesNotExist()
            compose.onNodeWithTag("arrival_content").assertDoesNotExist()
            compose.onNodeWithTag("stage_search").assertDoesNotExist()
            assertUnchanged(before)
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
    }

    @Test fun significantPublishedSecondsAreShownOnBothManualScreens() {
        val draft = ArrivalDraft(parish.id, parish.shortName, ArrivalMode.NEW_SERVICE, ArrivalSource.MANUAL,
            stageId = "creed", pendingServiceContext = expected.copy(source = ReadingContextSource.PUBLISHED,
                startEpochMillis = Instant.parse("2026-11-08T20:00:15.500Z").toEpochMilli()))
        val showingArrival = mutableStateOf(false)
        compose.setContent { GuideTheme(false) {
            if (showingArrival.value) ArrivalScreen(draft, container.getStageMatcher().getStageById("creed"),
                null, true, {}, {}, {})
            else ManualMomentScreen(draft, {}, {}, {})
        } }
        assertPending("23:00:15.5")
        compose.runOnIdle { showingArrival.value = true }
        assertPending("23:00:15.5")
    }

    private fun seed(start: String = "23:00") {
        check(preferences.edit().clear().putString("church_id", parish.id).putBoolean("dark", false)
            .putBoolean("lyric_original", true).putInt("text_size", 0).commit())
        check(UserServiceStartStore(preferences).save(UserServiceStart(parish.id, "2026-11-08", start), parish))
        val previous = ReadingContext(parish.id, "2026-11-07", parish.timezone, ReadingContextSource.MANUAL)
        val plan = planFor(previous)
        val engine = ServicePlaybackEngine(plan)
        val state = engine.start("creed", PlaybackSpeed.SLOW).copy(elapsedQuarterMillis = 1_234L)
        check(ReadingAcceptanceStore(preferences).acceptPlayback(plan, previous, engine.snapshot(state, 0L),
            ServiceSession("creed", Instant.parse("2026-11-07T07:00:00Z").toEpochMilli()), true,
            PlaybackBookmark.at(plan, state)))
        check(preferences.edit().putString("lyric_phrase_old_track", "old_phrase").commit())
    }

    private fun planFor(value: ReadingContext): ServicePlaybackPlan = ServicePlaybackPlan.build(
        container.getStageMatcher().getAllStages(), LocalDate.parse(value.serviceDate),
        container.readings?.days?.firstOrNull { it.date == value.serviceDate })

    private fun launch(clock: Clock, storage: SharedPreferences = preferences): StateRestorationTester {
        val local = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "guide_preferences") storage else super.getSharedPreferences(name, mode)
        }
        return StateRestorationTester(compose).also { restoration -> restoration.setContent {
            CompositionLocalProvider(LocalContext provides local) { AppNavigation(container, clock) }
        } }
    }

    private fun watchCurrent() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("watch_current"))
        compose.onNodeWithTag("watch_current").performClick()
    }
    private fun landmark(id: String) {
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_$id"))
        compose.onNodeWithTag("manual_$id").performClick()
    }
    private fun accept() {
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("accept_arrival"))
        compose.onNodeWithTag("accept_arrival").performClick()
    }
    private fun clickAction(tag: String): () -> Boolean = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun assertPending(time: String) = compose.onNodeWithTag("pending_service_context")
        .assertTextEquals("Литургия 8 ноября 2026 · начало в $time")
    private fun saved(): StoredServicePlayback = requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun assertUnchanged(before: Map<String, *>) = compose.runOnIdle { assertEquals(before, preferences.all) }
    private fun assertAccepted(value: ReadingContext, stageId: String, russianFallback: Boolean = false) {
        val stored = saved()
        assertEquals(value, stored.context)
        assertEquals(value, ReadingContextStore(preferences).restore())
        assertEquals(PlaybackSpeed.NORMAL, stored.snapshot.speed)
        assertEquals(0L, stored.snapshot.elapsedQuarterMillis)
        val plan = planFor(value)
        val restored = requireNotNull(ServicePlaybackEngine(plan).restore(stored.snapshot))
        assertEquals(stageId, plan.stageAt(restored.segmentIndex).id)
        assertFalse(restored.isPlaying)
        val marker = Gson().fromJson(preferences.getString(ReadingAcceptanceStore.SESSION_KEY, null), ServiceSession::class.java)
        assertEquals(stageId, marker.anchorStageId)
        assertFalse(marker.autoAdvance)
        assertFalse(marker.anchorIsApproximate)
        compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertExists()
        if (russianFallback) {
            compose.assertReaderMode("text", selected = true)
            compose.onNodeWithText("Для этого чтения доступен русский текст").assertIsDisplayed()
        }
        compose.onNodeWithTag("manual_content").assertDoesNotExist()
        compose.onNodeWithTag("arrival_content").assertDoesNotExist()
    }

    private fun typeSearch(query: String) {
        compose.onNodeWithTag("stage_search").performTextReplacement(query)
        waitForKeyboard(true)
        closeSoftKeyboardWithCiDiagnostics()
        waitForKeyboard(false)
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
    }
    private fun waitForKeyboard(visible: Boolean) {
        compose.waitUntil(timeoutMillis = 10_000) {
            var matches = false
            compose.activityRule.scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                    (visible || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0)
            }
            matches
        }
    }

    /** Corrupt only this screen's actual saved draft; preserve screen and origin enum values. */
    private fun corruptOneDraft(saved: Map<String, List<Any?>>, missing: Boolean): Map<String, List<Any?>> {
        var replaced = 0
        fun visit(value: Any?): Any? = when (value) {
            is MutableState<*> -> {
                val original = value.value
                val transformed = visit(original)
                // Preserve unrelated specialized state (for example MutableIntState).
                if (transformed == original) value else mutableStateOf(transformed)
            }
            is List<*> -> if (value.size == 12 && value[0] == parish.id &&
                value[2] == ArrivalMode.NEW_SERVICE.name && value[3] == ArrivalSource.MANUAL.name) {
                replaced++
                if (missing) emptyList<Any>() else value.toMutableList().also { it[11] = "broken pending context" }
            } else value.map(::visit)
            is Map<*, *> -> value.mapValues { (_, nested) -> visit(nested) }
            else -> value
        }
        val result = saved.mapValues { (_, values) -> values.map(::visit) }
        assertEquals("Exactly one actual pending draft must be corrupted", 1, replaced)
        return result
    }

    private class AdjustableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }

    private class FailNextCommitPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        var failNextCommit = false
        var failedCommits = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    val committed = editor.commit()
                    if (!failNextCommit) return committed
                    failNextCommit = false
                    failedCommits++
                    return false
                }
            }
        }
    }
}
