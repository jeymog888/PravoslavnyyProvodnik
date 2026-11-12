package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SavedBookEnrichmentFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "saved-enrichment-flow-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val mounted = mutableStateOf(true)
    private val parish = mutableStateOf("first-parish")

    @After fun clean() {
        compose.runOnUiThread { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(name)
    }

    @Test fun pinnedTextOpensDuringPendingEnrichmentAndOnlyExplicitAcceptanceChangesEdition() {
        val old = fixture()
        val next = translated(old, "second")
        val ready = CompletableDeferred<BookLoadResult.Ready?>()
        val calls = AtomicInteger()
        val books = fake(old, next) { calls.incrementAndGet(); ready.await() }
        savePosition(old)
        mount(old, books)
        awaitReader()
        compose.waitUntil(5_000) { calls.get() > 0 }
        compose.onNodeWithTag("book_loading").assertDoesNotExist()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        val before = requireNotNull(store.last(parish.value))
        compose.runOnIdle { ready.complete(BookLoadResult.Ready(next, fromCache = true)) }
        awaitUpdate()
        assertEquals(before, store.last(parish.value))
        compose.onNodeWithTag("book_accept_update").performClick()
        settle()
        val accepted = requireNotNull(store.last(parish.value))
        assertEquals(next.contentHash, accepted.contentHash)
        assertEquals(before.blockId, accepted.blockId)
        assertEquals(before.mode, accepted.mode)
        assertEquals(before.fraction, accepted.fraction, .003f)
        assertEquals(before.wordsPerMinute, accepted.wordsPerMinute)
        assertEquals(before.showAltar, accepted.showAltar)
        // Reopening pins the accepted immutable file and requires no load or network.
        compose.runOnIdle { mounted.value = false }; settle()
        compose.runOnIdle { mounted.value = true }; awaitReader()
        assertEquals(next.contentHash, store.last(parish.value)!!.contentHash)
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
    }

    @Test fun newlyTranslatedCurrentRussianFallbackKeepsBlockAndResetsOnlyItsFraction() {
        val old = fixture(currentRussian = null)
        val next = translated(old, "first")
        val ready = CompletableDeferred<BookLoadResult.Ready?>()
        savePosition(old)
        mount(old, fake(old, next) { ready.await() })
        awaitReader()
        val before = requireNotNull(store.last(parish.value))
        assertTrue(before.fraction > .2f)
        compose.runOnIdle { ready.complete(BookLoadResult.Ready(next, fromCache = true)) }
        awaitUpdate()
        assertEquals(old.contentHash, store.last(parish.value)!!.contentHash)
        compose.onNodeWithTag("book_accept_update").performClick()
        settle()
        val after = requireNotNull(store.last(parish.value))
        assertEquals(next.contentHash, after.contentHash)
        assertEquals(before.blockId, after.blockId)
        assertEquals(BookReadingMode.RUSSIAN, after.mode)
        assertEquals(0f, after.fraction, .003f)
        assertEquals(before.wordsPerMinute, after.wordsPerMinute)
        assertEquals(before.showAltar, after.showAltar)
    }

    @Test fun unsavedManualRefreshCannotBeAcceptedAndCanBeRetried() = checkUnsavedManualRefresh(delayed = true)

    @Test fun immediateSavedRetryKeepsOptionsOpenUntilExplicitAcceptance() = checkUnsavedManualRefresh(delayed = false)

    private fun checkUnsavedManualRefresh(delayed: Boolean) {
        val old = fixture()
        val next = translated(old, "second")
        var saved = false
        val loads = AtomicInteger()
        val thirdResponse = CompletableDeferred<Unit>()
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey): ServiceBook = if (saved) next else old
            override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? =
                listOfNotNull(old, next.takeIf { saved }).firstOrNull { it.key == key && it.contentHash == contentHash }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
                assertTrue(force)
                val attempt = loads.incrementAndGet()
                if (attempt == 3 && delayed) thirdResponse.await()
                saved = attempt > 2
                // A reported success without a readable immutable file must also be rejected.
                return BookLoadResult.Ready(next, fromCache = false, saved = attempt > 1)
            }
        }
        savePosition(old)
        mount(old, books)
        awaitReader()
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithTag("book_refresh").performScrollTo().performClick()
        compose.waitUntil(5_000) { loads.get() == 1 }
        settle()
        compose.onNodeWithTag("book_options_accept_update").assertDoesNotExist()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        compose.onNodeWithText("Обновленный текст не удалось сохранить на телефоне. Открытая книга осталась на месте. Попробуйте обновить еще раз")
            .performScrollTo().assertIsDisplayed()
        assertEquals(old.contentHash, store.last(parish.value)!!.contentHash)
        compose.onNodeWithTag("book_refresh").performScrollTo().performClick()
        compose.waitUntil(5_000) { loads.get() == 2 }
        settle()
        compose.onNodeWithTag("book_options_accept_update").assertDoesNotExist()
        assertEquals(old.contentHash, store.last(parish.value)!!.contentHash)
        compose.onNodeWithTag("book_refresh").performScrollTo().performClick()
        compose.waitUntil(5_000) { loads.get() == 3 }
        settle()
        compose.onNodeWithTag("book_options").assertExists()
        compose.runOnIdle { thirdResponse.complete(Unit) }
        settle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_options_accept_update").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book_options").assertExists()
        assertEquals(old.contentHash, store.last(parish.value)!!.contentHash)
        compose.onNodeWithTag("book_options_accept_update").performScrollTo().performClick()
        settle()
        assertEquals(next.contentHash, store.last(parish.value)!!.contentHash)
    }

    @Test fun lateNonCooperativeResultFromPreviousParishCannotOfferUpdateInNewRoute() {
        val old = fixture()
        val next = translated(old, "second")
        val ready = CompletableDeferred<BookLoadResult.Ready?>()
        val calls = AtomicInteger()
        val books = fake(old, next) {
            if (calls.incrementAndGet() == 1) withContext(NonCancellable) { ready.await() } else null
        }
        savePosition(old)
        savePosition(old, "second-parish")
        mount(old, books)
        awaitReader()
        compose.waitUntil(5_000) { calls.get() == 1 }
        compose.runOnIdle { parish.value = "second-parish" }
        compose.waitUntil(5_000) { calls.get() > 1 }
        compose.runOnIdle { ready.complete(BookLoadResult.Ready(next, fromCache = true)) }
        settle()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        assertEquals(old.contentHash, store.last("first-parish")!!.contentHash)
        assertEquals(old.contentHash, store.last("second-parish")!!.contentHash)
    }

    @Test fun lateResultForPreviousDateCannotOfferUpdateInAnotherService() {
        val old = fixture()
        val next = translated(old, "second")
        val otherKey = old.key.copy(date = "2026-11-12")
        val other = old.copy(key = otherKey, sourceUrl = otherKey.sourceUrl()).let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it))
        }
        val route = mutableStateOf(old.key)
        val ready = CompletableDeferred<BookLoadResult.Ready?>()
        val calls = AtomicInteger()
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey): ServiceBook? = listOf(old, other).firstOrNull { it.key == key }
            override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? =
                listOf(old, next, other).firstOrNull { it.key == key && it.contentHash == contentHash }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult =
                error("Both dates are already saved")
            override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? {
                calls.incrementAndGet()
                return if (accepted.key == old.key) withContext(NonCancellable) { ready.await() } else null
            }
        }
        savePosition(old)
        savePosition(other)
        compose.setContent { GuideTheme(false) {
            if (mounted.value) ServiceBookScreen(route.value, parish.value, books, store, 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
        } }
        awaitReader()
        compose.waitUntil(5_000) { calls.get() == 1 }
        compose.runOnIdle { route.value = otherKey }
        compose.waitUntil(5_000) { calls.get() > 1 }
        compose.runOnIdle { ready.complete(BookLoadResult.Ready(next, fromCache = true)) }
        settle()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        assertEquals(otherKey, store.last(parish.value)!!.key)
        assertEquals(other.contentHash, store.last(parish.value)!!.contentHash)
        assertEquals(old.contentHash, store.positionFor(old.key, parish.value)!!.contentHash)
    }

    private fun fake(old: ServiceBook, next: ServiceBook, enrich: suspend () -> BookLoadResult.Ready?) = object : ServiceBooks {
        private var latest = old
        override fun cached(key: ServiceBookKey): ServiceBook? = latest.takeIf { it.key == key }
        override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? =
            listOf(old, next).firstOrNull { it.key == key && it.contentHash == contentHash }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult =
            error("Saved editions must open offline without load")
        override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? =
            enrich().also { if (it?.saved == true) latest = it.book }
    }

    private fun mount(old: ServiceBook, books: ServiceBooks) {
        compose.setContent { GuideTheme(false) {
            if (mounted.value) ServiceBookScreen(old.key, parish.value, books, store, 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
        } }
    }
    private fun savePosition(book: ServiceBook, parishId: String = parish.value) {
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parishId, "first", "chapter",
            fraction = .37f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 73, showAltar = true,
            lastTextMode = BookReadingMode.RUSSIAN)))
    }
    private fun awaitReader() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun awaitUpdate() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_accept_update").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }

    private fun fixture(currentRussian: String? = "Господь, помоги нам жить в мире. ".repeat(100)): ServiceBook {
        val key = ServiceBookKey("2026-11-11")
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Тестовый источник",
            fetchedAtMillis = 1, selectedEventTitle = null, contentHash = "",
            chapters = listOf(BookChapter("chapter", "Общая молитва", "Объяснение общей молитвы", listOf(
                BookBlock("first", BookBlockKind.PRAYER, null, "Господи, помоги нам жить в мире. ".repeat(100), currentRussian),
                BookBlock("second", BookBlockKind.PRAYER, null, "Господи, благослови наши дела. ".repeat(100))))),
            enrichment = BookEnrichment(BookSupplementStatus.AVAILABLE, BookSupplementStatus.NOT_NEEDED))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
    private fun translated(book: ServiceBook, id: String): ServiceBook = book.copy(chapters = book.chapters.map { chapter ->
        chapter.copy(blocks = chapter.blocks.map { block -> if (block.id == id) block.copy(
            russian = "Господь, помоги нам любить и понимать друг друга. ".repeat(100),
            translationSource = BookTranslationIndex.TIMROT_FULL_SOURCE) else block })
    }).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
}
