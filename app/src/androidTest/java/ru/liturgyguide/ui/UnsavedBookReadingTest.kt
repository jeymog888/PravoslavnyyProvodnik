package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** A failed book write must not replace a recoverable offline reading pointer. */
class UnsavedBookReadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "unsaved-book-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val parish = "unsaved-book-parish"
    private val mounted = mutableStateOf(true)
    private val book = fixture()

    @After fun clean() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(name)
    }

    @Test fun failedFirstSaveDoesNotWriteAPointerDuringReadingPauseOrExit() {
        val prior = book.key.copy(date = "2026-11-10")
        assertTrue(store.save(BookReadingPosition(prior, "a".repeat(64), parish, "first", "chapter")))
        val before = preferences.all.toMap()
        mount(Books(book))
        readSecondPrayer()
        assertEquals(before, preferences.all)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(before, preferences.all)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        settle()
        compose.runOnIdle { mounted.value = false }
        settle()
        assertEquals(before, preferences.all)
        assertNull(store.positionFor(book.key, parish))
    }

    @Test fun savingTheSameTextEnablesBookmarksWithoutReopeningTheReader() {
        val books = Books(book)
        mount(books)
        readSecondPrayer()
        assertNull(store.last(parish))
        refresh()
        compose.waitUntil(5_000) { books.calls.get() == 2 && store.last(parish)?.contentHash == book.contentHash }
        compose.onNodeWithTag("book_options").assertExists()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        val saved = requireNotNull(store.last(parish))
        assertEquals("second", saved.blockId)
        assertEquals(BookReadingMode.RUSSIAN, saved.mode)
        remount()
        assertEquals(2, books.calls.get())
        assertEquals(saved.blockId, store.last(parish)?.blockId)
        assertEquals(saved.mode, store.last(parish)?.mode)
    }

    @Test fun acceptingASavedSuccessorPublishesTheCurrentUnsavedCursorDirectly() {
        val next = book.copy(title = "Проверенная новая редакция").let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it))
        }
        val books = Books(next)
        mount(books)
        readSecondPrayer()
        assertTrue(preferences.all.isEmpty())
        refresh()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_options_accept_update").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("An unsaved hash must never be published before acceptance", preferences.all.isEmpty())
        compose.onNodeWithTag("book_options_accept_update").performScrollTo().performClick()
        settle()
        val accepted = requireNotNull(store.last(parish))
        assertEquals(next.contentHash, accepted.contentHash)
        assertEquals("second", accepted.blockId)
        assertEquals(BookReadingMode.RUSSIAN, accepted.mode)
        assertFalse(preferences.all.values.any { it.toString().contains(book.contentHash) })
        remount()
        assertEquals(2, books.calls.get())
        assertEquals(accepted.blockId, store.last(parish)?.blockId)
        assertEquals(accepted.contentHash, store.last(parish)?.contentHash)
    }

    @Test fun acceptanceRejectsACursorFromAnotherChurchDateOrEditionWithoutWriting() {
        val next = book.copy(title = "Новая редакция").let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val cursor = BookReadingPosition(book.key, book.contentHash, parish, "second", "chapter")
        listOf(cursor.copy(parishId = "another"), cursor.copy(key = book.key.copy(date = "2026-11-12")),
            cursor.copy(contentHash = "b".repeat(64))).forEach {
            assertFalse(store.acceptVersion(book, next, parish, it))
            assertTrue(preferences.all.isEmpty())
        }
        assertTrue(store.acceptVersion(book, next, parish, cursor))
        assertEquals(next.contentHash, store.last(parish)?.contentHash)
        assertEquals("second", store.last(parish)?.blockId)
    }

    private inner class Books(private val savedResult: ServiceBook) : ServiceBooks {
        val calls = AtomicInteger()
        private var stored: ServiceBook? = null
        override fun cached(key: ServiceBookKey) = stored?.takeIf { it.key == key }
        override fun cachedVersion(key: ServiceBookKey, contentHash: String) =
            cached(key)?.takeIf { it.contentHash == contentHash }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            val attempt = calls.incrementAndGet()
            if (attempt == 1) return BookLoadResult.Ready(book, fromCache = false, saved = false,
                warning = "Не удалось сохранить книгу")
            assertTrue(force)
            stored = savedResult
            return BookLoadResult.Ready(savedResult, fromCache = false, saved = true)
        }
    }

    private fun mount(books: ServiceBooks) {
        compose.setContent { GuideTheme(false) {
            if (mounted.value) ServiceBookScreen(book.key, parish, books, store, 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
        } }
        awaitReader()
        compose.onNodeWithTag("book_offline_summary", useUnmergedTree = true).assertTextEquals("Пока не сохранен")
    }

    private fun readSecondPrayer() {
        compose.onNodeWithTag("book_mode_russian").performClick()
        settle()
        compose.onNodeWithTag("book_content").performScrollToIndex(2)
        settle()
        compose.onNodeWithTag("book_block_second").assertIsDisplayed()
    }

    private fun refresh() {
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithTag("book_refresh").performScrollTo().performClick()
    }

    private fun remount() {
        compose.runOnIdle { mounted.value = false }
        settle()
        compose.runOnIdle { mounted.value = true }
        awaitReader()
    }

    private fun awaitReader() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }

    private fun fixture(): ServiceBook {
        val key = ServiceBookKey("2026-11-11")
        val value = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(),
            sourceTitle = "Тестовый источник", fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", "Общая молитва", "Тестовое объяснение", listOf(
                BookBlock("first", BookBlockKind.PRAYER, null, "Первая строка для проверки чтения. ".repeat(90),
                    "Первый русский текст для проверки чтения. ".repeat(90)),
                BookBlock("second", BookBlockKind.PRAYER, null, "Вторая строка для проверки чтения. ".repeat(90),
                    "Второй русский текст для проверки чтения. ".repeat(90))) + (0..12).map { index ->
                BookBlock("quiet-altar-$index", BookBlockKind.PRAYER, null,
                    "Синтетический алтарный хвост для проверки целостности книги. ".repeat(3), isAltarText = true)
            })), contentHash = "",
            enrichment = BookEnrichment(BookSupplementStatus.AVAILABLE, BookSupplementStatus.NOT_NEEDED))
        return value.copy(contentHash = ServiceBookIntegrity.contentHash(value)).also {
            assertTrue("The fixture must satisfy the same saved-book contract as the reader", ServiceBookIntegrity.valid(it))
        }
    }
}
