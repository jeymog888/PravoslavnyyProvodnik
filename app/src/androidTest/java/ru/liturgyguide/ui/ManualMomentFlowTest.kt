package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ru.liturgyguide.ui.closeSoftKeyboardWithCiDiagnostics as closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID
import ru.liturgyguide.service.PrayerTrackFactory
import ru.liturgyguide.service.ServiceSession
import ru.liturgyguide.service.ServicePlaybackStore
import ru.liturgyguide.service.StoredServicePlayback
import ru.liturgyguide.service.StoredServicePlaybackCodec
import java.util.concurrent.atomic.AtomicLong
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ManualMomentFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val books = NavigationMigrationBooks()
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules,
        booksOverride = books)
    private val bookPreferenceName = "manual-moment-books-${UUID.randomUUID()}"
    private val bookPreferences = context.getSharedPreferences(bookPreferenceName, Context.MODE_PRIVATE)
    private val localContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "service_book_reading") bookPreferences else super.getSharedPreferences(name, mode)
    }
    private val mounted = mutableStateOf(true)
    private val legacyParishId get() = container.parishes.getOrThrow().selected(null).id
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val instant = Instant.parse("2026-12-01T12:00:00Z") // Outside the bundled schedule.
    private val creed = tracks("creed").single { it.title == "Символ веры" }
    private val other = tracks("communion").single { it.title == "Верую, Господи, и исповедую" }
    private val monotonic = AtomicLong(1_000L)
    private val bookKey = ServiceBookKey("2026-12-01")
    private val landmarks = listOf(
        Triple("little_entrance", "Премудрость", "Премудрость, прости"),
        Triple("cherubic_hymn", "Иже Херувимы", "Херувимская: до Великого входа"),
        Triple("creed", "Верую во единаго Бога", "Символ веры"),
        Triple("lords_prayer", "Отче наш", "Отче наш"),
        Triple("communion", "Со страхом Божиим", "Со страхом Божиим и верою")
    )

    @After fun cleanBooks() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(bookPreferenceName)
    }

    @Test
    fun allFiveKnownPhrasesWithoutAScheduleOpenTheirExactPlaceInTheDatedFullBook() {
        check(preferences.edit().clear().putBoolean("dark", false).commit())
        books.publish(searchFixture(bookKey))
        val before = preferences.all.toMap()
        val restoration = launch()
        home("today_choose_without_church").performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 1 декабря 2026")
        compose.onNodeWithTag("service_book_chooser").performScrollToNode(hasTestTag("service_book_choose_liturgy"))
        compose.onNodeWithTag("service_book_choose_liturgy").performClick()
        awaitBook()
        assertNull(bookPosition(null).parishId)
        landmarks.forEach { (stageId, query, _) ->
            if (stageId == "creed") {
                restoration.emulateSavedInstanceStateRestore()
                awaitBook()
                assertEquals(bookKey, bookPosition(null).key)
            }
            compose.onNodeWithTag("book_find").performClick()
            typeBookSearch(query)
            val stage = requireNotNull(container.getStageMatcher().getStageById(stageId))
            compose.scrollToBookHit("target-$stageId").assertIsDisplayed()
                .assert(hasText(stage.name)).performClick()
            settleBook()
            compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(stage.name)
            compose.onNodeWithTag("book_text_target-$stageId", useUnmergedTree = true)
                .assertIsDisplayed().assertTextEquals(requireNotNull(books.cached(bookKey)).blocks.single { it.id == "target-$stageId" }.original)
            val saved = bookPosition(null)
            assertEquals(bookKey, saved.key)
            assertEquals("target-$stageId", saved.blockId)
            assertEquals("chapter-$stageId", saved.chapterId)
            assertEquals(BookAnchorKind.BLOCK, saved.anchorKind)
            compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
            assertEquals("Searching a full book must not create or erase legacy bookmarks", before, preferences.all)
        }
        assertEquals(listOf(bookKey), books.requests.toList())
    }

    @Test
    fun changingTheSearchPhrasePreviewsAndOpensTheNewMatchOnACompactScreen() {
        var beforeFindClick = "Not reached"
        withCiFailureDiagnostics("ManualMomentFlowTest.changingBookQuery", additionalEvidence = { directory, deadline, phase ->
            if (phase == CiEvidencePhase.AFTER_SCREENSHOT) {
                writeCiFailureEvidence(directory, "before-find-click.txt", deadline) { beforeFindClick }
                writeCiFailureEvidence(directory, "compose-roots.txt", deadline) {
                    compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.joinToString("\n\n") { index ->
                        compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString(maxDepth = 100)
                    }
                }
            }
        }) {
            seed()
            books.publish(searchFixture(bookKey))
            val before = preferences.all.toMap()
            launch(compact = true)
            // Scroll the button itself into view: its containing card is taller than this viewport.
            val openBook = home("start_guide").performScrollTo().assertIsDisplayed()
            val visible = openBook.getBoundsInRoot()
            val full = openBook.getUnclippedBoundsInRoot()
            val pixel = with(compose.density) { 1f.toDp() }
            assertTrue("The actual book button must be fully visible before a pointer click: visible=$visible, full=$full",
                visible.left <= full.left + pixel && visible.top <= full.top + pixel &&
                    visible.right >= full.right - pixel && visible.bottom >= full.bottom - pixel)
            openBook.performClick()
            awaitBook()
            val old = bookPosition()
            compose.onNodeWithTag("book_find").assertIsDisplayed()
            beforeFindClick = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.joinToString("\n\n") { index ->
                compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString(maxDepth = 100)
            }
            compose.onNodeWithTag("book_find").performClick()
            typeBookSearch("Премудрость")
            compose.scrollToBookHit("target-little_entrance").assertIsDisplayed()
            assertEquals("A search preview is not an accepted reading position", old, bookPosition())
            dismissBookSearch()
            assertEquals(old, bookPosition())
            compose.onNodeWithTag("book_find").performClick()
            typeBookSearch("Со страхом Божиим")
            compose.onNodeWithTag("book_hit_target-little_entrance").assertDoesNotExist()
            compose.scrollToBookHit("target-communion").assertIsDisplayed()
                .assert(hasText("Причащение верующих")).performClick()
            settleBook()
            compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals("Причащение верующих")
            val readingArea = compose.onNodeWithTag("book_content").getBoundsInRoot()
            assertTrue("The compact reader must keep a useful reading area: $readingArea",
                readingArea.bottom - readingArea.top >= 80.dp)
            compose.onNodeWithTag("book_text_target-communion", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(bookKey, bookPosition().key)
            assertEquals("target-communion", bookPosition().blockId)
            assertEquals(before, preferences.all)
            assertOtherBookmark()
        }
    }

    @Test
    fun correctionCancelRestoresMeaningAndANonInitialPhraseAcrossAllThreeScreens() {
        seed()
        val restoration = launch()
        resumeMeaning()
        val before = preferences.all.toMap()
        clarify()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("manual_content").assertExists()
        landmark("communion")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("arrival_stage").assertTextEquals("Причащение верующих")
        searchFromArrival()
        typeSearch("Отче наш")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("stage_search").assertTextContains("Отче наш")
        back()
        compose.onNodeWithTag("arrival_stage").assertTextEquals("Причащение верующих")
        back()
        compose.onNodeWithTag("manual_content").assertExists()
        back()
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        assertEquals(before, preferences.all)
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onFeedLine(creed, 12).assertIsSelected().assertIsDisplayed()
        assertOtherBookmark()
    }

    @Test
    fun acceptingACorrectionPreservesMeaningAndOpensTheLandmarkWithoutLosingOtherBookmarks() {
        seed()
        launch()
        resumeMeaning()
        clarify()
        landmark("creed")
        accept()
        compose.assertReaderMode("meaning", selected = true)
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_track").assertTextEquals("Символ веры")
        compose.closeReaderDetails()
        compose.selectReaderMode("text")
        compose.onFeedLine(creed, 0).assertIsSelected().assertIsDisplayed()
        assertEquals(creed.id, preferences.getString("lyric_track_creed", null))
        assertEquals(creed.lines[0].id, preferences.getString("lyric_phrase_${creed.id}", null))
        assertEquals(0L, preferences.getLong("lyric_offset_${creed.id}", -1L))
        assertManualSession("creed")
        assertOtherBookmark()
    }

    @Test
    fun correctionSearchAcceptsTheMatchingPhraseAndPreservesOtherBookmarks() {
        seed()
        launch()
        resumeMeaning()
        clarify()
        searchFromManual()
        typeSearch("духа святого животворящаго")
        compose.onNodeWithTag("stage_creed").assertIsNotSelected().performClick()
        compose.assertReaderMode("meaning", selected = true)
        compose.selectReaderMode("text")
        compose.onFeedLine(creed, 12).assertIsSelected().assertIsDisplayed()
        assertEquals(creed.lines[12].id, preferences.getString("lyric_phrase_${creed.id}", null))
        assertEquals(0L, preferences.getLong("lyric_offset_${creed.id}", -1L))
        assertManualSession("creed")
        assertOtherBookmark()
    }

    @Test
    fun activePlayerPausesBeforeThePhraseBoundaryAndCorrectionNeverConsumesBackgroundTime() {
        seed()
        val restoration = launch()
        resumeMeaning()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("play_lyrics").performClick()
        // The canonical phrase has six words: its four-second minimum ends at 5000 ms.
        compose.onNodeWithTag("clarify_stage").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { click ->
                monotonic.set(4_999L)
                click()
            }
        val paused = savedPlayback()
        assertEquals("creed", savedSession().anchorStageId)
        assertEquals(15_996L, paused.snapshot.elapsedQuarterMillis)
        assertFalse(savedSession().autoAdvance)
        compose.onNodeWithText("Автоматические переходы остановлены").assertIsDisplayed()
        compose.runOnIdle { monotonic.addAndGet(600_000L) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Автоматические переходы остановлены").assertIsDisplayed()
        landmark("communion")
        searchFromArrival()
        back()
        back()
        back()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onFeedLine(creed, 12).assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
        assertEquals(paused, savedPlayback())
        assertOtherBookmark()
    }

    @Test
    fun correctionStopsPhraseScrollingAndCancelKeepsThePausedPhrase() {
        seed()
        val restoration = launch()
        resumeMeaning()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.onNodeWithTag("play_lyrics").assert(hasText("Пауза") or hasContentDescription("Пауза")).assertIsDisplayed()
        compose.runOnIdle { monotonic.addAndGet(1_234L) }
        compose.onNodeWithTag("clarify_stage").performClick()
        compose.onNodeWithTag("manual_content").assertIsDisplayed()
        val session = savedSession()
        val stoppedLine = preferences.getInt("lyric_line_${creed.id}", -1)
        val stopped = savedPlayback()
        assertEquals(4_936L, stopped.snapshot.elapsedQuarterMillis)
        restoration.emulateSavedInstanceStateRestore()
        landmark("communion")
        back()
        back()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onFeedLine(creed, stoppedLine).assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
        assertEquals(stopped, savedPlayback())
        assertEquals(session, savedSession())
        assertOtherBookmark()
    }

    @Test
    fun savedReadingNoticeUsesCorrectionAndKeepsTheAcceptedPausedSnapshot() {
        val old = ServiceSession("creed", instant.minusSeconds(8 * 3_600L).toEpochMilli(),
            autoAdvance = true, elapsedInStageMillis = 2_000L, anchorIsApproximate = true)
        seed(session = old)
        launch()
        resumeMeaning()
        val accepted = savedPlayback()
        val normalized = savedSession()
        assertFalse(normalized.autoAdvance)
        assertEquals("creed", normalized.anchorStageId)
        compose.openReaderDetails()
        compose.onNodeWithText("Сохранённое чтение. Место в храме можно уточнить по словам или микрофону")
            .performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        compose.closeReaderDetails()
        compose.onNodeWithTag("clarify_stage").performClick()
        compose.onNodeWithTag("manual_content").assertExists()
        compose.onNodeWithText("Автоматические переходы остановлены").assertDoesNotExist()
        assertEquals(normalized, savedSession())
        assertEquals(accepted, savedPlayback())
        searchFromManual()
        back()
        compose.onNodeWithTag("manual_content").assertExists()
        back()
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onFeedLine(creed, 12).assertIsSelected()
        assertEquals(normalized, savedSession())
        assertEquals(accepted, savedPlayback())
        assertOtherBookmark()
    }

    @Test
    fun cancellingBookSearchKeepsBothReadingsAndANewDateSearchesOnlyItsOwnText() {
        seed()
        books.publish(searchFixture(bookKey))
        val tomorrow = bookKey.copy(date = "2026-12-02")
        books.publish(searchFixture(tomorrow))
        val before = preferences.all.toMap()
        launch()
        home("start_guide").performClick()
        awaitBook()
        val accepted = bookPosition()
        compose.onNodeWithTag("book_find").performClick()
        typeBookSearch("второго декабря")
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasText("Совпадений в этой книге нет. Исправьте услышанные слова или попробуйте другую короткую фразу"))
        compose.onNodeWithText("Совпадений в этой книге нет. Исправьте услышанные слова или попробуйте другую короткую фразу").assertIsDisplayed()
        dismissBookSearch()
        assertEquals(accepted, bookPosition())
        assertEquals(before, preferences.all)
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("book_listen").performScrollTo().assertIsDisplayed()
            .assert(hasText("Послушать службу") or hasText("Проверить микрофон"))
        compose.onNodeWithTag("book_listening_status")
            .assert(hasText("Найти по микрофону") or hasText("Микрофон недоступен"))
        dismissBookSearch()
        assertEquals(accepted, bookPosition())
        compose.onNodeWithTag("book_back").performClick()
        home("today_day_tomorrow").performClick()
        home("start_guide").performClick()
        awaitBook()
        assertEquals(tomorrow, bookPosition().key)
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        typeBookSearch("первого декабря")
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasText("Совпадений в этой книге нет. Исправьте услышанные слова или попробуйте другую короткую фразу"))
        compose.onNodeWithText("Совпадений в этой книге нет. Исправьте услышанные слова или попробуйте другую короткую фразу").assertIsDisplayed()
        typeBookSearch("второго декабря")
        compose.scrollToBookHit("day-marker").assertIsDisplayed().performClick()
        settleBook()
        assertEquals(tomorrow, bookPosition().key)
        assertEquals("day-marker", bookPosition().blockId)
        assertEquals(accepted, BookReadingStore(bookPreferences).restore(requireNotNull(books.cached(bookKey)), legacyParishId))
        assertEquals(listOf(bookKey, tomorrow), books.requests.toList())
        assertEquals(before, preferences.all)
        assertOtherBookmark()
    }

    private fun launch(clock: Clock = Clock.fixed(instant, ZoneOffset.UTC), compact: Boolean = false) = StateRestorationTester(compose).also {
        it.setContent { CompositionLocalProvider(LocalContext provides localContext,
            LocalActivityResultRegistryOwner provides compose.activity) {
            if (mounted.value) {
                if (compact) Box(Modifier.size(width = 320.dp, height = 320.dp)) { AppNavigation(container, clock, monotonic::get) }
                else AppNavigation(container, clock, monotonic::get)
            }
        } }
    }

    private fun home(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun bookPosition(parishId: String? = legacyParishId) = requireNotNull(BookReadingStore(bookPreferences).last(parishId))
    private fun awaitBook() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settleBook()
    }
    private fun settleBook() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }
    private fun typeBookSearch(query: String) {
        // A preceding result list may have scrolled the field off screen.
        compose.onNodeWithTag("book_search_query").performScrollTo().performTextReplacement(query)
        closeSoftKeyboard()
        compose.awaitBookSearch()
    }
    private fun dismissBookSearch() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { dismiss -> assertTrue(dismiss()) }
        compose.waitForIdle()
        compose.onNodeWithTag("book_search_query").assertDoesNotExist()
    }

    /** Exact original prayer phrases are reused only in test fixtures; the day marker is explicitly synthetic. */
    private fun searchFixture(key: ServiceBookKey): ServiceBook {
        val chapters = landmarks.mapIndexed { index, (stageId, _, title) ->
            val stage = requireNotNull(container.getStageMatcher().getStageById(stageId))
            val text = tracks(stageId).single { it.title == title }.lines.joinToString("\n") { requireNotNull(it.original) }
            val day = if (key.date == "2026-12-02") "второго декабря" else "первого декабря"
            BookChapter("chapter-$stageId", stage.name, "Пояснение тестового раздела", listOf(
                BookBlock(if (index == 0) "day-marker" else "before-$stageId", BookBlockKind.RUBRIC, null,
                    "Тестовый фрагмент $day. ".repeat(30)),
                BookBlock("target-$stageId", BookBlockKind.PRAYER, null, text),
                BookBlock("after-$stageId", BookBlockKind.RUBRIC, null, "Продолжение тестового раздела. ".repeat(30))))
        }
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Источник теста",
            fetchedAtMillis = 1, selectedEventTitle = null, chapters = chapters, contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }

    private fun seed(session: ServiceSession = ServiceSession("creed", instant.toEpochMilli())) {
        check(preferences.edit().clear().putBoolean("dark", false).putInt("text_size", 0)
            .putBoolean("lyric_original", false).putInt("lyric_tempo", 8).putBoolean("lyric_intro_seen", true)
            .putString("stage_id", "creed").putString("session", Gson().toJson(session))
            .putString("lyric_track_creed", creed.id)
            .putInt("lyric_line_${creed.id}", 12).putString("lyric_phrase_${creed.id}", creed.lines[12].id)
            .putInt("lyric_saved_tempo_${creed.id}", 8).putLong("lyric_offset_${creed.id}", 2_000L)
            .putString("lyric_track_communion", other.id)
            .putInt("lyric_line_${other.id}", 4).putString("lyric_phrase_${other.id}", other.lines[4].id)
            .putInt("lyric_saved_tempo_${other.id}", 16).putLong("lyric_offset_${other.id}", 1_234L).commit())
    }

    private fun resumeMeaning() {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithTag("resume_guide").performClick()
        compose.onFeedLine(creed, 12).assertIsSelected()
        compose.onNodeWithTag("player_meaning_tab").performClick()
    }

    private fun clarify() { compose.onNodeWithTag("clarify_stage").performClick() }
    private fun back() { compose.onNodeWithContentDescription("Назад").performClick() }
    private fun landmark(id: String) {
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_$id"))
        compose.onNodeWithTag("manual_$id").performScrollTo().assertIsDisplayed().performClick()
    }
    private fun searchFromManual() {
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_search"))
        compose.onNodeWithTag("manual_search").performClick()
    }
    private fun searchFromArrival() {
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("arrival_search"))
        compose.onNodeWithTag("arrival_search").performClick()
    }
    private fun accept() {
        compose.onNodeWithTag("arrival_content").performScrollToNode(hasTestTag("accept_arrival"))
        compose.onNodeWithTag("accept_arrival").performClick()
    }
    private fun typeSearch(query: String) {
        compose.onNodeWithTag("stage_search").performTextInput(query)
        closeSoftKeyboard()
    }
    private fun tracks(id: String) = PrayerTrackFactory.build(requireNotNull(container.getStageMatcher().getStageById(id)), null)
    private fun savedSession(): ServiceSession = Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
    private fun savedPlayback(): StoredServicePlayback = requireNotNull(
        StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun assertOtherBookmark() {
        assertEquals(other.id, preferences.getString("lyric_track_communion", null))
        assertEquals(4, preferences.getInt("lyric_line_${other.id}", -1))
        assertEquals(other.lines[4].id, preferences.getString("lyric_phrase_${other.id}", null))
        assertEquals(16, preferences.getInt("lyric_saved_tempo_${other.id}", -1))
        assertEquals(1_234L, preferences.getLong("lyric_offset_${other.id}", -1L))
    }
    private fun assertManualSession(stageId: String) {
        val session = savedSession()
        assertEquals(stageId, session.anchorStageId)
        assertEquals(instant.toEpochMilli(), session.anchorEpochMillis)
        assertEquals(0L, session.elapsedInStageMillis)
        assertFalse(session.autoAdvance)
        assertFalse(session.anchorIsApproximate)
        compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
    }
    private class AdjustableClock(var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
    }
}
