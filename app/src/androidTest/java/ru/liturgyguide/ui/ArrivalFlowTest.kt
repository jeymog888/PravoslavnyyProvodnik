package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.book.*
import java.util.UUID
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Dated book entry and explicit legacy recovery share navigation but never overwrite each other. */
@RunWith(AndroidJUnit4::class)
class ArrivalFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "arrival-route-${UUID.randomUUID()}"
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
    private val container = AppContainer(context,
        scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules, booksOverride = books)
    private val instant = Instant.parse("2026-11-08T04:35:20Z")
    private val parish get() = container.parishes.getOrThrow().selected(null)
    private val creedTracks = PrayerTrackFactory.build(requireNotNull(container.getStageMatcher().getStageById("creed")), null)

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences("$prefix-guide")
        context.deleteSharedPreferences("$prefix-books")
    }

    @Test fun openingACurrentServiceUsesItsDateWithoutGuessingTheReadersPlace() {
        seed()
        val before = preferences.all.toMap()
        val clock = AdjustableClock(instant)
        launch(clock)
        home("today_service_time").assertTextEquals("07:00")
        // No periodic tick runs between the displayed occurrence and this tap.
        compose.runOnIdle { clock.current = instant.plusSeconds(600) }
        openFeatured()
        assertEquals(ServiceBookKey("2026-11-08"), savedBook().key)
        assertEquals(BookAnchorKind.HEADER, savedBook().anchorKind)
        assertEquals(0f, savedBook().fraction, 0f)
        assertEquals(before, preferences.all)
        assertGlobals()
    }

    @Test fun aStaleDisplayedOccurrenceStillOpensItsOwnDateAndLeavesTheOldReadingUntouched() {
        seed()
        val before = preferences.all.toMap()
        val clock = AdjustableClock(instant)
        launch(clock)
        home("today_service_date").assertTextEquals("8 ноября")
        compose.runOnIdle { clock.current = instant.plusSeconds(24 * 3_600) }
        openFeatured()
        // The button opens the exact displayed book; it does not reinterpret the date as the new today.
        assertEquals(ServiceBookKey("2026-11-08"), savedBook().key)
        assertEquals(before, preferences.all)
        assertNull(ReadingContextStore(preferences).restore())
    }

    @Test fun aFutureServiceBecomingCurrentBetweenDisplayAndTapKeepsItsOwnBook() {
        seed()
        val before = preferences.all.toMap()
        val clock = AdjustableClock(Instant.parse("2026-11-08T03:59:59Z"))
        launch(clock)
        home("today_service_time").assertTextEquals("07:00")
        compose.runOnIdle { clock.current = Instant.parse("2026-11-08T04:00:01Z") }
        openFeatured()
        assertEquals(listOf(ServiceBookKey("2026-11-08")), books.requests.toList())
        assertEquals(before, preferences.all)
    }

    @Test fun tomorrowsBookKeepsItsDateAcrossMeaningMidnightAndRestoration() {
        seed()
        val before = preferences.all.toMap()
        val clock = AdjustableClock(Instant.parse("2026-11-08T12:00:00Z"))
        val restoration = launch(clock)
        home("today_day_tomorrow").performClick()
        home("today_service_date").assertTextEquals("9 ноября")
        openFeatured()
        compose.onNodeWithTag("book_mode_meaning").performClick()
        settle()
        assertEquals(BookReadingMode.MEANING, savedBook().mode)
        compose.runOnIdle { clock.current = Instant.parse("2026-11-09T21:05:00Z") }
        compose.mainClock.advanceTimeBy(6_000)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settle()
        assertEquals(ServiceBookKey("2026-11-09"), savedBook().key)
        assertEquals(BookReadingMode.MEANING, savedBook().mode)
        compose.onNodeWithTag("book_mode_original").performClick()
        compose.onNodeWithTag("book_saved").assertTextContains("9 ноября 2026", substring = true)
        assertEquals(before, preferences.all)
    }

    @Test fun choosingTodayFromTheChurchAfterAFutureBookDoesNotReuseTheFutureDate() {
        seed()
        val before = preferences.all.toMap()
        launch(AdjustableClock(Instant.parse("2026-11-08T12:00:00Z")))
        home("today_day_tomorrow").performClick()
        openFeatured()
        assertEquals("2026-11-09", savedBook().key.date)
        compose.onNodeWithTag("book_back").performClick()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_today"))
        compose.onNodeWithTag("schedule_today").assertIsSelected()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_read_liturgy"))
        compose.onNodeWithTag("church_read_liturgy").performClick()
        awaitBook()
        assertEquals("2026-11-08", savedBook().key.date)
        assertEquals(listOf(ServiceBookKey("2026-11-09"), ServiceBookKey("2026-11-08")), books.requests.toList())
        assertEquals(before, preferences.all)
    }

    @Test fun personalStartHasItsOwnEntryAndDoesNotReplacePublishedTimeOrLegacyReading() {
        seed()
        assertTrue(UserServiceStartStore(preferences).save(UserServiceStart(parish.id, "2026-11-08", "07:10"), parish))
        val before = preferences.all.toMap()
        launch(AdjustableClock(instant))
        home("today_service_time").assertTextEquals("07:00")
        home("today_personal_summary").assertTextEquals("8 ноября · 07:10 · Литургия")
        home("today_personal_source").assertTextEquals("Начало указано вами")
        home("today_open_personal_liturgy").performClick()
        awaitBook()
        assertEquals(ServiceBookKey("2026-11-08"), savedBook().key)
        assertEquals(before, preferences.all)
        assertGlobals()
    }

    @Test fun resumingALegacyBookmarkPinsItsOriginalAnchorDateWithoutClearingThePhrase() {
        seed()
        launch(AdjustableClock(Instant.parse("2026-11-10T12:00:00Z")))
        resumeLegacy()
        // The 0.4 bookmark has no context; its saved anchor was 7 November in the parish timezone.
        assertEquals("2026-11-07", savedContext().serviceDate)
        assertEquals(ReadingContextSource.MANUAL, savedContext().source)
        compose.onFeedLine(container.recordedFeedTrack(savedPlayback()), 3).assertIsSelected()
        assertGlobals()
    }

    @Test fun restorationStraightIntoLegacyServicePinsTheAnchorDateBeforeAnyCorrection() {
        seed()
        val clock = AdjustableClock(Instant.parse("2026-11-10T12:00:00Z"))
        val restoration = launch(clock)
        resumeLegacy()
        // Simulate saved SERVICE state produced by the old app, which had no date metadata.
        compose.runOnIdle {
            check(preferences.edit().remove(ReadingContextStore.CONTEXT_KEY).remove(ServicePlaybackStore.PLAYBACK_KEY)
                .putString("session", Gson().toJson(ServiceSession("creed", instant.minusSeconds(8 * 3_600).toEpochMilli())))
                .commit())
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_date").assertTextEquals("Тексты на 7 ноября 2026")
        compose.closeReaderDetails()
        compose.onNodeWithTag("clarify_stage").performClick()
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_lords_prayer"))
        compose.onNodeWithTag("manual_lords_prayer").performClick()
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("accept_arrival"))
        compose.onNodeWithTag("accept_arrival").performClick()
        assertEquals("2026-11-07", savedContext().serviceDate)
        assertEquals("lords_prayer", savedSession().anchorStageId)
    }

    @Test fun foreignOrCorruptContextRestoredInServiceRequiresANewExplicitChoice() {
        seed()
        check(preferences.edit().putString("church_id", parish.id).commit())
        val restoration = launch(AdjustableClock(instant))
        resumeLegacy()
        val wrong = ReadingContext("another-parish", "2026-11-08", "Europe/Moscow", ReadingContextSource.MANUAL)
        for (invalid in listOf(ReadingContextCodec.encode(wrong), "broken")) {
            compose.runOnIdle { check(preferences.edit().putString(ReadingContextStore.CONTEXT_KEY, invalid).commit()) }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("main_content").assertExists()
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("reading_restore_notice"))
            compose.onNodeWithTag("reading_restore_notice").assertIsDisplayed()
            compose.onNodeWithText("Прежнее сохраненное чтение").assertDoesNotExist()
            assertEquals(invalid, preferences.getString(ReadingContextStore.CONTEXT_KEY, null))
            openCommonOrder()
            assertEquals(parish.id, savedContext().parishId)
            assertEquals("2026-11-08", savedContext().serviceDate)
        }
    }

    @Test fun anInvalidLegacySessionCannotSupplyTheDateForAnOtherwiseValidBookmark() {
        seed()
        check(preferences.edit().putString("session", Gson().toJson(
            ServiceSession("creed", instant.minusSeconds(8 * 3_600).toEpochMilli(), elapsedInStageMillis = -1))).commit())
        launch(AdjustableClock(Instant.parse("2026-11-10T12:00:00Z")))
        resumeLegacy()
        assertEquals("2026-11-10", savedContext().serviceDate)
        assertEquals("creed", savedSession().anchorStageId)
        assertEquals(0L, savedSession().elapsedInStageMillis)
    }

    private fun launch(clock: Clock) = StateRestorationTester(compose).also {
        it.setContent { CompositionLocalProvider(LocalContext provides localContext) {
            if (mounted.value) AppNavigation(container, clock, monotonicMillis = { 1_000L })
        } }
    }
    private fun home(tag: String): SemanticsNodeInteraction {
        val parentTag = if (tag in setOf("today_personal_summary", "today_personal_source")) "today_open_personal_liturgy" else tag
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(parentTag))
        return compose.onNodeWithTag(tag, useUnmergedTree = parentTag != tag)
    }
    private fun openFeatured() {
        home("open_service_book").performClick()
        awaitBook()
    }
    private fun resumeLegacy() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasText("Прежнее сохраненное чтение"))
        compose.onNodeWithText("Прежнее сохраненное чтение").performClick()
        compose.onNodeWithTag("service_book").assertIsDisplayed()
    }
    private fun openCommonOrder() {
        books.fail = true
        home("today_other_services").performClick()
        compose.onNodeWithTag("service_book_chooser").performScrollToNode(hasTestTag("service_book_choose_liturgy"))
        compose.onNodeWithTag("service_book_choose_liturgy").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_load_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Открыть общий порядок литургии").performScrollTo().performClick()
        compose.onNodeWithTag("service_book").assertIsDisplayed()
    }
    private fun savedBook() = requireNotNull(BookReadingStore(bookPreferences).last(parish.id))
    private fun settle() { compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }
    private fun awaitBook() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    }
    private fun seed() {
        val track = creedTracks.single { it.title == "Символ веры" }
        check(preferences.edit().clear().putBoolean("dark", true).putInt("text_size", 0)
            .putBoolean("lyric_original", true).putInt("lyric_tempo", 8).putBoolean("lyric_intro_seen", true)
            .putString("stage_id", "creed")
            .putString("session", Gson().toJson(ServiceSession("creed", instant.minusSeconds(8 * 3_600).toEpochMilli(),
                autoAdvance = true, elapsedInStageMillis = 2_000L, anchorIsApproximate = true)))
            .putString("lyric_track_creed", track.id).putInt("lyric_line_${track.id}", 3)
            .putString("lyric_phrase_${track.id}", track.lines[3].id).putInt("lyric_saved_tempo_${track.id}", 8)
            .putLong("lyric_offset_${track.id}", 2_000L)
            .putString("lyric_track_old_stage", "old_track").putInt("lyric_line_old_track", 9)
            .putString("lyric_phrase_old_track", "old_phrase").putLong("lyric_offset_old_track", 500L)
            .putInt("lyric_saved_tempo_old_track", 16).commit())
    }
    private fun savedSession(): ServiceSession = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
    private fun savedPlayback(): StoredServicePlayback = requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun savedContext(): ReadingContext = requireNotNull(ReadingContextStore(preferences).restore())
    private fun assertGlobals() {
        assertTrue(preferences.getBoolean("dark", false))
        assertEquals(0, preferences.getInt("text_size", -1))
        assertTrue(preferences.getBoolean("lyric_original", false))
        assertEquals(8, preferences.getInt("lyric_tempo", -1))
        assertTrue(preferences.getBoolean("lyric_intro_seen", false))
    }
    private class AdjustableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
}
