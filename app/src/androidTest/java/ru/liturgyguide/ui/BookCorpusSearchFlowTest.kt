package ru.liturgyguide.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Synthetic UI fixtures, not captured or reconstructed church services
 * The real ServiceBookScreen, serialized route and isolated durable reading store own the flow
 */
@RunWith(AndroidJUnit4::class)
class BookCorpusSearchFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "book-corpus-search-${UUID.randomUUID()}"
    private val rawPreferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val preferences = RejectingPreferences(rawPreferences)
    private val store = BookReadingStore(preferences)
    private val books = ControlledBooks()
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val parishId = "synthetic-corpus-search-parish"
    private val vespers = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "34")
    private val matins = vespers.copy(worship = WorshipKind.MATINS)
    private val journey = BookJourneyContext(parishId, "2026-11-12", "17:00", "Вечерня. Утреня", listOf(vespers, matins))
    private val currentPhrase = "вечерняя звезда над тихой рекой"
    private val originalPhrase = "утренняя тишина хранит надежду"
    private val russianPhrase = "янтарный маяк согревает сердце"
    private var routeSnapshot = BookRoute(vespers, parishId, journey)
    private var replaceRoute: ((BookRoute) -> Unit)? = null

    @After fun clean() {
        if (contentSet) {
            compose.runOnIdle {
                preferences.rejectWrites = false
                mounted.value = false
                books.releaseForCleanup()
            }
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun matinsOnlyPhraseOpensItsMeasuredDeepOriginalLineAndKeepsThePublishedOccurrence() {
        val evening = fixture(vespers)
        val morning = fixture(matins)
        seed(morning, .22f, BookReadingMode.RUSSIAN)
        seed(evening, .16f, BookReadingMode.RUSSIAN)
        launch()
        waitForReader(vespers)
        val beforeEvening = position(vespers)
        val beforeMorning = position(matins)
        val beforeSearch = rawPreferences.all.toMap()
        val target = hit(morning, originalPhrase)
        assertEquals(BookReadingMode.ORIGINAL, target.hit.mode)
        assertTrue("The synthetic books deliberately reuse paragraph IDs", evening.blocks.first().id == target.hit.blockId)
        assertTrue("Only matins may contain this phrase", BookCorpusSearch.find(listOf(evening), originalPhrase).isEmpty())

        openSearch(originalPhrase)
        crossHit(target).assertIsDisplayed()
        assertEquals("Searching must not accept either edition or move either cursor", beforeSearch, rawPreferences.all)
        assertEquals(beforeEvening, position(vespers))
        assertEquals(beforeMorning, position(matins))
        crossHit(target).performClick()

        waitForReader(matins)
        assertDeepLine(morning, target)
        val opened = position(matins)
        assertEquals(morning.key, opened.key)
        assertEquals(morning.contentHash, opened.contentHash)
        assertEquals(parishId, opened.parishId)
        assertEquals(BookReadingMode.ORIGINAL, opened.mode)
        assertEquals(BookReadingMode.ORIGINAL, opened.lastTextMode)
        assertEquals("2026-11-13", opened.key.date)
        assertEquals(journey, opened.journeyContext)
        assertEquals("2026-11-12", opened.journeyContext?.civilDate)
        assertEquals(listOf(vespers, matins), opened.journeyContext?.parts)
        assertEquals(beforeEvening, position(vespers))
        assertEquals(opened, store.last(parishId))
        assertNull(currentRoute().pendingSearchTarget)
        compose.onNodeWithTag("book_mode_original").assertIsSelected()
        assertTrue("Both exact fixture editions were cached", books.loads.isEmpty())
    }

    @Test fun anotherPartsAcceptedOldEditionWinsOverLatestAndOpensItsDeepRussianLine() {
        val evening = fixture(vespers)
        val accepted = fixture(matins, edition = "Принятая старая редакция", russianTarget = russianPhrase)
        val latest = fixture(matins, edition = "Новая редакция", russianTarget = "зеленые ветви склонились к озеру")
        seed(accepted, .19f, BookReadingMode.ORIGINAL)
        seed(evening, .14f, BookReadingMode.ORIGINAL)
        books.publish(latest)
        assertNotEquals(accepted.contentHash, latest.contentHash)
        launch()
        waitForReader(vespers)
        val beforeSearch = rawPreferences.all.toMap()
        val target = hit(accepted, russianPhrase)
        assertEquals(BookReadingMode.RUSSIAN, target.hit.mode)

        openSearch(russianPhrase)
        crossHit(target).assertIsDisplayed()
        assertEquals(beforeSearch, rawPreferences.all)
        crossHit(target).performClick()

        waitForReader(matins)
        assertDeepLine(accepted, target)
        assertEquals(accepted.contentHash, position(matins).contentHash)
        assertNotEquals(latest.contentHash, position(matins).contentHash)
        assertEquals(BookReadingMode.RUSSIAN, position(matins).mode)
        assertEquals(journey, position(matins).journeyContext)
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        assertTrue(books.loads.isEmpty())
    }

    @Test fun missingAcceptedOtherEditionReportsPartialSearchAndNeverFallsBackToLatest() {
        val evening = fixture(vespers)
        val accepted = fixture(matins, edition = "Утраченная принятая редакция")
        seed(accepted, .23f, BookReadingMode.ORIGINAL)
        seed(evening, .13f, BookReadingMode.ORIGINAL)
        books.publish(fixture(matins, edition = "Доступная новая редакция"))
        books.removeVersion(accepted)
        launch()
        waitForReader(vespers)
        books.cachedCalls.clear()
        val beforeSearch = rawPreferences.all.toMap()

        openSearch(currentPhrase)
        sheetNode("book_search_corpus_error").assertTextContains("Сохраненная редакция", substring = true)
            .assertTextContains("не проверена", substring = true)
        sheetNode("book_hit_long").assertIsDisplayed()
        assertEquals(beforeSearch, rawPreferences.all)
        assertTrue("A missing accepted version must not ask for the latest cached matins", matins !in books.cachedCalls)
        assertTrue("A missing accepted version must not download matins", books.loads.none { it.key == matins })
        assertTrue(books.versionCalls.any { it == Edition(accepted.key, accepted.contentHash) })

        replaceQuery(originalPhrase)
        compose.awaitBookSearch()
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasText("Совпадений в открытой книге нет", substring = true))
        compose.onNodeWithText("Совпадений в открытой книге нет", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Совпадений в обеих книгах нет", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("book_cross_hit_${hit(accepted, originalPhrase).identity}").assertDoesNotExist()
        assertEquals(beforeSearch, rawPreferences.all)
        assertEquals(vespers, currentRoute().activeKey)
    }

    @Test fun failedCurrentPositionCommitLeavesSearchOpenAndBothDurableCursorsUntouched() {
        val evening = fixture(vespers)
        val morning = fixture(matins)
        seed(morning, .27f, BookReadingMode.RUSSIAN)
        seed(evening, .15f, BookReadingMode.ORIGINAL)
        launch()
        waitForReader(vespers)
        val target = hit(morning, originalPhrase)
        openSearch(originalPhrase)
        crossHit(target).assertIsDisplayed()
        val before = rawPreferences.all.toMap()
        val routeBefore = currentRoute()
        compose.runOnIdle { preferences.rejectWrites = true }

        crossHit(target).performClick()

        sheetNode("book_search_selection_error").assertTextContains("Не удалось сохранить место", substring = true)
        compose.onNodeWithTag("book_search_sheet").assertIsDisplayed()
        assertEquals(routeBefore, currentRoute())
        assertEquals(before, rawPreferences.all)
        assertTrue("The failure must occur in a real preference commit", preferences.rejectedCommits > 0)
        assertEquals(evening.contentHash, store.last(parishId)?.contentHash)
        assertEquals(morning.contentHash, position(matins).contentHash)
        assertTrue(books.loads.isEmpty())
    }

    @Test fun failedDestinationCommitKeepsTheMeasuredTargetPendingUntilAnExplicitSuccessfulRetry() {
        val evening = fixture(vespers)
        val morning = fixture(matins)
        seed(morning, .24f, BookReadingMode.RUSSIAN)
        seed(evening, .17f, BookReadingMode.ORIGINAL)
        val target = hit(morning, originalPhrase)
        var committedSource: BookReadingPosition? = null
        var beforeDestinationWrite: Map<String, *>? = null
        val restoration = launch { next ->
            if (next.pendingSearchTarget != null) {
                // Selection has already committed the source; reject only the destination write
                committedSource = position(vespers)
                beforeDestinationWrite = rawPreferences.all.toMap()
                preferences.rejectWrites = true
            }
        }
        waitForReader(vespers)
        val oldDestination = position(matins)
        openSearch(originalPhrase)
        crossHit(target).performClick()
        compose.waitUntil(5_000) { preferences.rejectedCommits > 0 }

        // Merely displaying the right line must not consume the pending durable transaction
        assertEquals(target, currentRoute().pendingSearchTarget)
        assertEquals(matins, currentRoute().activeKey)
        waitFor("book_search_jump_error")
        compose.onNodeWithTag("book_search_jump_error").assertIsDisplayed()
        compose.onNodeWithTag("book_search_jump_retry").assertIsDisplayed().assertIsEnabled()
        assertEquals(oldDestination, position(matins))
        assertEquals(requireNotNull(committedSource), position(vespers))
        assertEquals(committedSource, store.last(parishId))
        assertEquals(requireNotNull(beforeDestinationWrite), rawPreferences.all)
        val text = compose.onNodeWithTag("book_text_long", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(morning.blocks.single().original, layout.layoutInput.text.text)
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val textTop = text.getUnclippedBoundsInRoot().top
        val density = compose.activity.resources.displayMetrics.density
        val lineTop = textTop + (layout.getLineTop(layout.getLineForOffset(target.hit.matchStart)) / density).dp
        val lineBottom = textTop + (layout.getLineBottom(layout.getLineForOffset(target.hit.matchEndExclusive - 1)) / density).dp
        assertTrue("The failed write still follows a measured, visible target line", lineTop >= viewport.top && lineBottom <= viewport.bottom)

        restoration.emulateSavedInstanceStateRestore()

        waitFor("book_search_jump_error")
        assertEquals(target, currentRoute().pendingSearchTarget)
        assertEquals(oldDestination, position(matins))
        assertEquals(committedSource, position(vespers))
        assertEquals(requireNotNull(beforeDestinationWrite), rawPreferences.all)
        compose.runOnIdle { preferences.rejectWrites = false }
        compose.onNodeWithTag("book_search_jump_retry").assertIsDisplayed().performClick()

        waitForReader(matins)
        assertDeepLine(morning, target)
        assertEquals(morning.contentHash, position(matins).contentHash)
        assertEquals(BookReadingMode.ORIGINAL, position(matins).mode)
        assertEquals(journey, position(matins).journeyContext)
        assertEquals(committedSource, position(vespers))
        assertEquals(position(matins), store.last(parishId))
        assertNull(currentRoute().pendingSearchTarget)
        compose.onNodeWithTag("book_search_jump_error").assertDoesNotExist()
    }

    @Test fun pendingTargetSurvivesRecreationAndMissingExactDestinationCanReturnWithoutAutosaving() {
        val evening = fixture(vespers)
        val morning = fixture(matins)
        seed(evening, .18f, BookReadingMode.RUSSIAN)
        books.publish(morning)
        val target = hit(morning, originalPhrase)
        val restoration = launch { next ->
            if (next.pendingSearchTarget != null) books.removeVersion(morning)
        }
        waitForReader(vespers)
        openSearch(originalPhrase)
        crossHit(target).performClick()
        waitFor("book_load_error")
        val pending = currentRoute()
        assertEquals(target, pending.pendingSearchTarget)
        assertEquals(vespers, pending.lastSuccessfulKey)
        assertEquals(matins, pending.activeKey)
        val beforeRestore = rawPreferences.all.toMap()
        assertNull(store.positionFor(matins, parishId))
        assertTrue("The exact pending target must be included in the serialized route",
            BookRouteCodec.decode(BookRouteCodec.encode(pending))?.pendingSearchTarget == target)

        restoration.emulateSavedInstanceStateRestore()

        waitFor("book_load_error")
        compose.onNodeWithTag("book_load_error").assertTextContains("редакц", substring = true)
        assertEquals(pending, currentRoute())
        assertEquals(beforeRestore, rawPreferences.all)
        assertNull("A failed pending jump must never publish a destination cursor", store.positionFor(matins, parishId))
        assertTrue("Pending exact destinations never fall back to a load of the latest book", books.loads.none { it.key == matins })
        waitFor("book_journey_return")
        compose.onNodeWithTag("book_journey_return").performClick()
        waitForReader(vespers)
        assertEquals(vespers, currentRoute().activeKey)
        assertEquals(journey, currentRoute().journeyContext)
        assertNull(currentRoute().pendingSearchTarget)
        assertNull(store.positionFor(matins, parishId))
        assertEquals(evening.contentHash, store.last(parishId)?.contentHash)
    }

    @Test fun cancellingALoadingSearchRejectsItsLateBookAndKeepsManualQueryInTheNextContext() {
        val evening = fixture(vespers)
        val lateMorning = fixture(matins, originalTarget = "новая вечерняя заря согревает город")
        seed(evening, .12f, BookReadingMode.ORIGINAL)
        books.hold(matins)
        launch()
        waitForReader(vespers)
        openSearch(currentPhrase)
        compose.waitUntil(5_000) { books.isPending(matins) }
        sheetNode("book_search_corpus_loading").assertIsDisplayed()
        sheetNode("book_hit_long").assertIsDisplayed()
        assertQuery(currentPhrase)
        val beforeCancel = rawPreferences.all.toMap()
        pressBack()
        waitFor("book_content")
        assertEquals(beforeCancel, rawPreferences.all)

        val nextVespers = vespers.copy(date = "2026-11-14")
        val nextMatins = matins.copy(date = "2026-11-14")
        val nextJourney = journey.copy(civilDate = "2026-11-13", parts = listOf(nextVespers, nextMatins))
        val nextEvening = fixture(nextVespers, originalTarget = "новая вечерняя заря согревает город")
        books.publish(nextEvening)
        books.publish(fixture(nextMatins))
        compose.runOnIdle { requireNotNull(replaceRoute)(BookRoute(nextVespers, parishId, nextJourney)) }
        waitForReader(nextVespers)
        val manualQuery = "новая вечерняя заря"
        openSearch(manualQuery)
        sheetNode("book_hit_long").assertIsDisplayed()
        val beforeLateReply = rawPreferences.all.toMap()

        compose.runOnIdle { books.release(matins, BookLoadResult.Ready(lateMorning, fromCache = false)) }
        compose.waitUntil(5_000) { matins in books.delivered }
        settle()

        assertQuery(manualQuery)
        sheetNode("book_hit_long").assertIsDisplayed()
        compose.onNodeWithTag("book_cross_hit_${hit(lateMorning, manualQuery).identity}").assertDoesNotExist()
        assertEquals(nextVespers, currentRoute().activeKey)
        assertEquals(nextJourney, currentRoute().journeyContext)
        assertEquals(beforeLateReply, rawPreferences.all)
        assertNull(store.positionFor(matins, parishId))
    }

    @Test fun anUnsavedCurrentBookCannotOpenThePersistentOtherEditionFromASearchHit() {
        val evening = fixture(vespers)
        val morning = fixture(matins)
        seed(morning, .25f, BookReadingMode.RUSSIAN)
        books.responses[vespers] = BookLoadResult.Ready(evening, fromCache = false, saved = false)
        launch()
        waitFor("book_content")
        settle()
        assertNull(store.positionFor(vespers, parishId))
        val before = rawPreferences.all.toMap()
        val target = hit(morning, originalPhrase)
        openSearch(originalPhrase)
        crossHit(target).performClick()

        sheetNode("book_search_selection_error").assertTextContains("пока не сохранен", substring = true)
        compose.onNodeWithTag("book_search_sheet").assertIsDisplayed()
        assertEquals(vespers, currentRoute().activeKey)
        assertNull(currentRoute().pendingSearchTarget)
        assertEquals(before, rawPreferences.all)
        assertNull(store.positionFor(vespers, parishId))
        assertEquals(morning.contentHash, store.last(parishId)?.contentHash)
        assertEquals(listOf(vespers), books.loads.map { it.key })
    }

    private fun launch(beforeRouteChange: (BookRoute) -> Unit = {}): StateRestorationTester =
        StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                var encodedRoute by rememberSaveable { mutableStateOf(BookRouteCodec.encode(BookRoute(vespers, parishId, journey))) }
                val route = requireNotNull(BookRouteCodec.decode(encodedRoute))
                SideEffect {
                    routeSnapshot = route
                    replaceRoute = { encodedRoute = BookRouteCodec.encode(it) }
                }
                GuideTheme(false) {
                    if (mounted.value) Box(Modifier.size(320.dp, 640.dp)) {
                        ServiceBookScreen(vespers, parishId, books, store, 0,
                            onBack = {}, onSettings = {}, onOpenUrl = {}, route = route,
                            onRouteChanged = { next ->
                                beforeRouteChange(next)
                                encodedRoute = BookRouteCodec.encode(next)
                                true
                            }, onOpenCommonLiturgy = {})
                    }
                }
            }
            contentSet = true
        }

    private fun seed(book: ServiceBook, fraction: Float, mode: BookReadingMode): BookReadingPosition {
        books.publish(book)
        return BookReadingPosition(book.key, book.contentHash, parishId, "long", "opening", fraction = fraction,
            mode = mode, lastTextMode = mode, wordsPerMinute = 113, journeyContext = journey).also { assertTrue(store.save(it)) }
    }

    private fun position(key: ServiceBookKey) = requireNotNull(store.positionFor(key, parishId))

    private fun currentRoute(): BookRoute {
        var result = routeSnapshot
        compose.runOnIdle { result = routeSnapshot }
        return result
    }

    private fun hit(book: ServiceBook, query: String): BookLocatedSearchHit =
        BookCorpusSearch.find(listOf(book), query).single().also { assertNotNull(it.validatedFor(book)) }

    private fun openSearch(query: String) {
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextInput(query)
        closeSoftKeyboard()
        compose.awaitBookSearch()
    }

    private fun replaceQuery(query: String) {
        compose.onNodeWithTag("book_search_sheet").performScrollToIndex(0)
        compose.onNodeWithTag("book_search_query").performTextReplacement(query)
        closeSoftKeyboard()
    }

    private fun assertQuery(query: String) {
        compose.onNodeWithTag("book_search_sheet").performScrollToIndex(0)
        compose.onNodeWithTag("book_search_query").assertTextContains(query, substring = true)
    }

    private fun crossHit(target: BookLocatedSearchHit) = sheetNode("book_cross_hit_${target.identity}")

    private fun sheetNode(tag: String): SemanticsNodeInteraction {
        // The neighbour loads on IO and its row may be below the lazy microphone panel
        compose.waitUntil(5_000) {
            runCatching {
                compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag(tag))
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        return compose.onNodeWithTag(tag)
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForReader(key: ServiceBookKey) {
        waitFor("book_content")
        compose.waitUntil(5_000) { store.last(parishId)?.key == key }
        settle()
    }

    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }

    private fun assertDeepLine(book: ServiceBook, target: BookLocatedSearchHit) {
        val block = book.blocks.single { it.id == target.hit.blockId }
        val source = if (target.hit.mode == BookReadingMode.RUSSIAN) requireNotNull(block.russian) else block.original
        assertTrue("The synthetic target must exercise a deep paragraph jump", target.hit.matchStart.toFloat() / source.length > .7f)
        val text = compose.onNodeWithTag("book_text_${block.id}", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(source, layout.layoutInput.text.text)
        val firstLine = layout.getLineForOffset(target.hit.matchStart)
        val finalLine = layout.getLineForOffset(target.hit.matchEndExclusive - 1)
        val top = text.getUnclippedBoundsInRoot().top
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        val lineTop = top + (layout.getLineTop(firstLine) / density).dp
        val lineBottom = top + (layout.getLineBottom(finalLine) / density).dp
        assertTrue("The actual first matched line must be inside the reader: $lineTop vs ${viewport.top}", lineTop >= viewport.top)
        assertTrue("The complete matched phrase must fit above the controls: $lineBottom vs ${viewport.bottom}", lineBottom <= viewport.bottom)
        assertEquals(block.id, position(book.key).blockId)
        assertTrue("The accepted destination cursor must reflect the deep jump", position(book.key).fraction > .6f)
    }

    private data class Edition(val key: ServiceBookKey, val hash: String)
    private data class Request(val key: ServiceBookKey, val force: Boolean)

    private class ControlledBooks : ServiceBooks {
        val loads = CopyOnWriteArrayList<Request>()
        val cachedCalls = CopyOnWriteArrayList<ServiceBookKey>()
        val versionCalls = CopyOnWriteArrayList<Edition>()
        val delivered = CopyOnWriteArrayList<ServiceBookKey>()
        val responses = ConcurrentHashMap<ServiceBookKey, BookLoadResult>()
        private val latest = ConcurrentHashMap<ServiceBookKey, ServiceBook>()
        private val versions = ConcurrentHashMap<Edition, ServiceBook>()
        private val held = ConcurrentHashMap.newKeySet<ServiceBookKey>()
        private val callbacks = ConcurrentHashMap<ServiceBookKey, Continuation<BookLoadResult>>()

        fun publish(book: ServiceBook) { latest[book.key] = book; versions[Edition(book.key, book.contentHash)] = book }
        fun removeVersion(book: ServiceBook) { versions.remove(Edition(book.key, book.contentHash)) }
        fun hold(key: ServiceBookKey) { held += key }
        fun isPending(key: ServiceBookKey) = callbacks.containsKey(key)
        override fun cached(key: ServiceBookKey): ServiceBook? { cachedCalls += key; return latest[key] }
        override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? {
            val edition = Edition(key, contentHash)
            versionCalls += edition
            return versions[edition]
        }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            loads += Request(key, force)
            responses[key]?.let { return it }
            if (key in held) {
                // Deliberately non-cancellable: a provider may deliver after this sheet is gone
                val result = suspendCoroutine<BookLoadResult> { callbacks[key] = it }
                delivered += key
                return result
            }
            return latest[key]?.let { BookLoadResult.Ready(it, fromCache = true) }
                ?: BookLoadResult.Failed(BookFailure.NETWORK, "Синтетический текст для этого ключа не задан")
        }
        fun release(key: ServiceBookKey, result: BookLoadResult) {
            if (result is BookLoadResult.Ready && result.saved) publish(result.book)
            requireNotNull(callbacks.remove(key)).resume(result)
        }
        fun releaseForCleanup() {
            callbacks.keys.toList().forEach { key ->
                callbacks.remove(key)?.resume(BookLoadResult.Failed(BookFailure.NETWORK, "Синтетический тест завершен"))
            }
        }
    }

    private class RejectingPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        @Volatile var rejectWrites = false
        var rejectedCommits = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                override fun commit(): Boolean {
                    val committed = editor.commit()
                    if (!rejectWrites) return committed
                    rejectedCommits++
                    return false // Exercise the real atomic writer restoring its changed in-memory pointers
                }
            }
        }
    }

    /** A long, deliberately repetitive synthetic renderer fixture with reused IDs across both parts */
    private fun fixture(key: ServiceBookKey, edition: String = "Базовая редакция",
        originalTarget: String = if (key.worship == WorshipKind.VESPERS) currentPhrase else originalPhrase,
        russianTarget: String = if (key.worship == WorshipKind.MATINS) russianPhrase else "вечерний свет помогает слушать"):
        ServiceBook {
        val original = "Господи, помоги нам внимательно слушать молитву и сохранять мир в наших сердцах. ".repeat(125) +
            originalTarget + ". " + "Помоги сохранить мир в сердце. ".repeat(10) + edition
        val russian = "Просим Бога помочь нам внимательно слушать молитву и хранить мир в сердцах. ".repeat(130) +
            russianTarget + ". " + "Просим помощи для каждого человека. ".repeat(10) + edition
        val book = ServiceBook(key = key, title = "Синтетическая ${key.worship.title}: $edition", sourceUrl = key.sourceUrl(),
            sourceTitle = "Синтетический источник UI-тестов", fetchedAtMillis = 12345, selectedEventTitle = null,
            chapters = listOf(BookChapter("opening", "Синтетический общий раздел", "Синтетическое пояснение для UI-теста",
                listOf(BookBlock("long", BookBlockKind.PRAYER, "Хор", original, russian)))), contentHash = "",
            enrichment = BookEnrichment(BookSupplementStatus.AVAILABLE, BookSupplementStatus.NOT_NEEDED))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
}
