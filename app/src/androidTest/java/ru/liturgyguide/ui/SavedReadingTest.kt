package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import ru.liturgyguide.ui.pressBackWithCiDiagnostics as pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.book.*

/** Cold Activity launches exercise disk-backed migration and restore, not only rememberSaveable. */
@RunWith(AndroidJUnit4::class)
class SavedReadingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val tracks = PrayerTrackFactory.build(requireNotNull(AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules).getStageMatcher().getStageById("creed")), null)

    private val bookPreferenceName = "saved-reading-books-${UUID.randomUUID()}"
    private val bookPreferences = context.getSharedPreferences(bookPreferenceName, Context.MODE_PRIVATE)
    private val books = NavigationMigrationBooks()
    private val bookClock = Clock.fixed(Instant.parse("2026-12-05T09:00:00Z"), ZoneOffset.UTC)
    private val localContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "service_book_reading") bookPreferences else super.getSharedPreferences(name, mode)
    }

    @After fun cleanBooks() { context.deleteSharedPreferences(bookPreferenceName) }

    private val creed get() = tracks.single { it.title == "Символ веры" }

    @Test
    fun legacyTrackBookmarksKeepTheirPhraseButDiscardTheOldTimerOffsets() {
        seed(line = 1, offset = 12_000L, selectedTitle = "Символ веры")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            resumeReading()
            compose.onFeedLine(creed, 1).assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
            assertEquals(PlaybackSpeed.NORMAL, savedPlayback().snapshot.speed)
            assertEquals(creed.lines[1].id, preferences.getString("lyric_phrase_${creed.id}", null))
            chooseCreedLine(2)
            scenario.recreate()
            compose.onFeedLine(creed, 2).assertIsSelected().assertIsDisplayed()
            assertEquals(creed.lines[2].id, preferences.getString("lyric_phrase_${creed.id}", null))
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
        }
    }

    @Test
    fun aLegacyFinishedTextRestoresItsLastPhrasePausedWithoutInventingNewElapsedTime() {
        seed(line = creed.lines.lastIndex, offset = 8_000L, selectedTitle = "Символ веры", savedTempo = 8, globalTempo = 16)
        lateinit var saved: StoredServicePlayback
        ActivityScenario.launch(MainActivity::class.java).use {
            resumeReading()
            compose.onFeedLine(creed, creed.lines.lastIndex).assertIsSelected()
            compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
            assertEquals(0L, savedPlayback().snapshot.elapsedQuarterMillis)
            assertEquals(PlaybackSpeed.NORMAL, savedPlayback().snapshot.speed)
            compose.onFeedLine(creed, creed.lines.lastIndex).assertIsSelected()
            saved = savedPlayback()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            resumeReading()
            compose.onFeedLine(creed, creed.lines.lastIndex).assertIsSelected()
            compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
            assertEquals(saved, savedPlayback())
        }
    }

    @Test
    fun anOldSavedReadingStaysPausedWhenAnotherDateOpensAsAFullBook() {
        seed(line = 3, offset = 0L, selectedTitle = "Символ веры", stale = true)
        launchBookNavigation().use {
            resumeReading()
            assertSavedReadingNotice()
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
            compose.onFeedLine(creed, 3).assertIsSelected()
            compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
            val old = savedPlayback()
            assertEquals(0L, old.snapshot.elapsedQuarterMillis)
            assertFalse(Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java).autoAdvance)
            pressBack()
            home("today_day_tomorrow").performClick()
            home("start_guide").performClick()
            awaitBook()
            val opened = requireNotNull(BookReadingStore(bookPreferences).last(old.context.parishId))
            assertEquals(ServiceBookKey("2026-12-06"), opened.key)
            assertEquals(listOf(opened.key), books.requests.toList())
            assertEquals(BookAnchorKind.HEADER, opened.anchorKind)
            assertEquals(0f, opened.fraction, 0f)
            compose.onNodeWithTag("book_saved").assertTextContains("6 декабря 2026", substring = true)
            compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
            assertEquals("The complete book has its own cursor; the old phrase is still recoverable", old, savedPlayback())
            assertEquals(creed.lines[3].id, preferences.getString("lyric_phrase_${creed.id}", null))
            compose.onNodeWithTag("book_back").performClick()
            resumeReading()
            compose.onFeedLine(creed, 3).assertIsSelected()
            assertEquals(old, savedPlayback())
        }
    }

    @Test
    fun aCorruptLegacySnapshotCannotSupplyOrBeClearedByANewDatedBook() {
        seed(line = 3, offset = 2_000L, selectedTitle = "Символ веры")
        check(preferences.edit().putString(ServicePlaybackStore.PLAYBACK_KEY, "broken").commit())
        val before = preferences.all.toMap()
        launchBookNavigation().use {
            home("reading_restore_notice").assertIsDisplayed()
            compose.onNodeWithTag("resume_guide").assertDoesNotExist()
            assertEquals("broken", preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))
            assertEquals(creed.lines[3].id, preferences.getString("lyric_phrase_${creed.id}", null))
            home("today_choose_without_church").performClick()
            compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 5 декабря 2026")
            compose.onNodeWithTag("service_book_chooser").performScrollToNode(hasTestTag("service_book_choose_liturgy"))
            compose.onNodeWithTag("service_book_choose_liturgy").performClick()
            awaitBook()
            val opened = requireNotNull(BookReadingStore(bookPreferences).last(null))
            assertNull("A corrupt legacy snapshot must not supply a guessed parish", opened.parishId)
            assertEquals(ServiceBookKey("2026-12-05"), opened.key)
            assertEquals(BookAnchorKind.HEADER, opened.anchorKind)
            assertEquals(0f, opened.fraction, 0f)
            assertEquals(listOf(opened.key), books.requests.toList())
            assertEquals("Opening BOOK is not permission to erase or reimport broken legacy data", before, preferences.all)
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("book_back").performClick()
            home("reading_restore_notice").assertIsDisplayed()
            compose.onNodeWithTag("resume_guide").assertDoesNotExist()
            home("resume_book").performClick()
            awaitBook()
            assertEquals(opened.key, requireNotNull(BookReadingStore(bookPreferences).last(null)).key)
            assertEquals(before, preferences.all)
        }
    }

    /** Only these BOOK cases need a controlled network; the two cold legacy tests still launch MainActivity. */
    private fun launchBookNavigation(): ActivityScenario<ComponentActivity> {
        val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules,
            booksOverride = books)
        return ActivityScenario.launch(ComponentActivity::class.java).also { scenario -> scenario.onActivity { activity ->
            activity.setContent { CompositionLocalProvider(LocalContext provides localContext,
                LocalActivityResultRegistryOwner provides activity) {
                AppNavigation(container, bookClock, monotonicMillis = { 1_000L })
            } }
        } }
    }

    private fun home(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun awaitBook() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(650)
        compose.waitForIdle()
    }

    private fun seed(line: Int, offset: Long, selectedTitle: String, stale: Boolean = false, savedTempo: Int = 16, globalTempo: Int = 8) {
        val anchor = Instant.now().minusSeconds(if (stale) 48 * 3600L else 0L)
        val track = creed
        check(preferences.edit().clear().putBoolean("dark", false)
            .putString("stage_id", "creed")
            .putString("session", Gson().toJson(ServiceSession("creed", anchor.toEpochMilli(), autoAdvance = stale)))
            .putString("lyric_track_creed", tracks.single { it.title == selectedTitle }.id)
            .putInt("lyric_tempo", globalTempo).putInt("lyric_saved_tempo_${track.id}", savedTempo)
            .putInt("lyric_line_${track.id}", line).putString("lyric_phrase_${track.id}", track.lines[line].id)
            .putLong("lyric_offset_${track.id}", offset).commit())
    }

    private fun savedPlayback(): StoredServicePlayback = requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))

    private fun resumeReading() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
    }

    private fun chooseCreedLine(index: Int) {
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag(feedLineTag(creed.lines[index])))
        compose.onFeedLine(creed, index).performScrollTo().assertIsDisplayed().performClick()
    }

    private fun assertSavedReadingNotice() {
        compose.openReaderDetails()
        compose.onNodeWithText("Сохранённое чтение. Место в храме можно уточнить по словам или микрофону")
            .performScrollTo().assertIsDisplayed()
        compose.closeReaderDetails()
    }
}
