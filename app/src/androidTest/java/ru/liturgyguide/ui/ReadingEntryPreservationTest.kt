package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.*
import ru.liturgyguide.service.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.OfflineInternetSchedules
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** New dated books and accepted legacy readings have separate stores and explicit entry points. */
@RunWith(AndroidJUnit4::class)
class ReadingEntryPreservationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "reading-entry-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences("$prefix-guide", Context.MODE_PRIVATE)
    private val bookPreferences = context.getSharedPreferences("$prefix-books", Context.MODE_PRIVATE)
    private val localContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
            "guide_preferences" -> preferences
            "service_book_reading" -> bookPreferences
            else -> super.getSharedPreferences(name, mode)
        }
    }
    private val mounted = mutableStateOf(true)
    private val books = NavigationMigrationBooks()
    private val container = AppContainer(context, scheduleOverride = OfflineInternetSchedules, booksOverride = books)
    private val parish get() = container.parishes.getOrThrow().selected(null)
    // Outside the bundled schedule: real AppNavigation must still open the exact requested date.
    private val now = Instant.parse("2026-12-05T09:00:00Z")
    private val date = LocalDate.of(2026, 12, 5)
    private val key get() = ServiceBookKey(date.toString())

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences("$prefix-guide")
        context.deleteSharedPreferences("$prefix-books")
    }

    @Test fun openingWithoutAScheduleLoadsTheExactDayAndStartsPaused() {
        clear()
        launch()
        home("start_guide").assertTextEquals("Открыть литургию").performClick()
        awaitBook()
        compose.onNodeWithTag("book_saved").assertTextContains("5 декабря 2026", substring = true)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        assertEquals(listOf(key), books.requests.toList())
        assertEquals(key, requireNotNull(BookReadingStore(bookPreferences).last(parish.id)).key)
        // Opening a downloaded book must not create a guessed stage in the former player.
        assertNull(preferences.getString("stage_id", null))
        assertFalse(preferences.contains(ServicePlaybackStore.PLAYBACK_KEY))
    }

    @Test fun openingTheSameDayFromChurchPreservesTheLegacyPhraseAndItsSeparateResume() {
        clear()
        seed(ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), date, null))
        val before = preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)
        launch()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_read_liturgy"))
        compose.onNodeWithTag("church_read_liturgy").performClick()
        awaitBook()
        assertEquals(key, books.requests.single())
        assertEquals("creed", preferences.getString("stage_id", null))
        assertEquals(before, preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))
        compose.onNodeWithTag("book_back").performClick()
        compose.onNodeWithTag("main_content").performScrollToNode(hasText("Прежнее сохраненное чтение"))
        compose.onNodeWithText("Прежнее сохраненное чтение").performClick()
        compose.onNodeWithTag("service_book").assertIsDisplayed()
        val accepted = requireNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
        compose.onFeedLine(container.recordedFeedTrack(accepted), 3).assertIsSelected().assertIsDisplayed()
        assertEquals(before, preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))
    }

    @Test fun aHistoricalPreviewIsNotAcceptedByVisitingChurchOrOpeningANewBook() {
        clear()
        val old = requireNotNull(Historical06Content { name ->
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.loadPlan(date))
        seed(old)
        val before = preferences.all.toMap()
        launch()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_read_liturgy"))
        compose.runOnIdle { assertEquals(before, preferences.all) }
        compose.onNodeWithTag("church_read_liturgy").performClick()
        awaitBook()
        compose.runOnIdle { assertEquals(before, preferences.all) }
        compose.onNodeWithTag("book_back").performClick()
        compose.onNodeWithTag("main_content").performScrollToNode(hasText("Прежнее сохраненное чтение"))
        compose.onNodeWithText("Прежнее сохраненное чтение").assertIsDisplayed()
        assertEquals(before, preferences.all)
        assertEquals(key, requireNotNull(BookReadingStore(bookPreferences).last(parish.id)).key)
    }

    private fun clear() {
        check(preferences.edit().clear().putBoolean("dark", false).putString("church_id", parish.id).commit())
    }
    private fun seed(plan: ServicePlaybackPlan) {
        val track = plan.tracksByStage.getValue("creed").single { it.title == "Символ веры" }
        val engine = ServicePlaybackEngine(plan)
        val state = engine.seekLine(engine.start("creed"), track.id, track.lines[3].id)
        val reading = requireNotNull(ReadingContextFactory.manualOnDate(parish, date))
        check(ReadingAcceptanceStore(preferences).acceptPlayback(plan, reading, engine.snapshot(state, 0L),
            ServiceSession("creed", now.toEpochMilli()), resetBookmarks = false, bookmark = PlaybackBookmark.at(plan, state)))
    }
    private fun launch() { compose.setContent {
        CompositionLocalProvider(LocalContext provides localContext) {
            if (mounted.value) AppNavigation(container, Clock.fixed(now, ZoneOffset.UTC), monotonicMillis = { 1_000L })
        }
    } }
    private fun home(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun awaitBook() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(650)
        compose.waitForIdle()
    }
}
