package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Two independent reader owners share only an isolated, durable preference file. */
@RunWith(AndroidJUnit4::class)
class BookReaderOwnershipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "book-reader-ownership-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val parishId = "synthetic-reader-ownership-parish"
    private val key = ServiceBookKey("2026-11-13", WorshipKind.LITURGY, "30")

    @After fun clean() {
        if (contentSet) {
            // Dispose before deleting the file: the reader can save from onDispose.
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun changingTheOldReadersLanguageCannotReplaceAnotherOwnersAcceptedEdition() {
        val accepted = fixture("Редакция A")
        openSavedReader(accepted)
        val replacement = acceptFromAnotherOwner(accepted)

        chooseRussian()
        settle() // Includes the reader's 400 ms debounced save.

        assertPreserved(replacement)
    }

    @Test fun disposingTheOldReaderCannotReplaceAnotherOwnersAcceptedEdition() {
        val accepted = fixture("Редакция A")
        openSavedReader(accepted)
        val replacement = acceptFromAnotherOwner(accepted)

        compose.runOnIdle { mounted.value = false }
        settle()

        compose.onNodeWithTag("book_content").assertDoesNotExist()
        assertPreserved(replacement)
    }

    @Test fun aFirstVisitCanSaveThenKeepSavingMovementAndLanguageWithinTheSameEdition() {
        val book = fixture("Первое чтение")
        assertEquals(BookReadingLookup.Absent, store.lookup(key, parishId))
        assertNull(store.last(parishId))
        assertTrue(preferences.all.isEmpty())

        launch(book)
        settle()
        val first = saved()
        assertEquals(book.contentHash, first.contentHash)
        assertEquals(BookReadingMode.ORIGINAL, first.mode)
        assertEquals(first, store.last(parishId))

        // Index 0 is the chapter heading; index 8 is the eighth prayer.
        compose.onNodeWithTag("book_content").performScrollToIndex(8)
        settle()
        val moved = saved()
        assertEquals("prayer-7", moved.blockId)
        assertEquals(BookAnchorKind.BLOCK, moved.anchorKind)
        assertNotEquals(first.blockId, moved.blockId)
        assertEquals(book.contentHash, moved.contentHash)

        chooseRussian()
        settle()
        val translated = saved()
        assertEquals(book.contentHash, translated.contentHash)
        assertEquals(moved.blockId, translated.blockId)
        assertEquals(BookReadingMode.RUSSIAN, translated.mode)
        assertEquals(BookReadingMode.RUSSIAN, translated.lastTextMode)
        assertEquals(translated, store.last(parishId))
    }

    private fun openSavedReader(book: ServiceBook) {
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parishId,
            "prayer-3", "chapter", fraction = .24f)))
        launch(book)
        settle()
        compose.onNodeWithTag("book_block_prayer-3").assertIsDisplayed()
        assertEquals(book.contentHash, saved().contentHash)
        assertEquals(BookReadingMode.ORIGINAL, saved().mode)
        assertEquals(saved(), store.last(parishId))
    }

    private data class AcceptedReading(val position: BookReadingPosition, val preferences: Map<String, *>)

    private fun acceptFromAnotherOwner(previous: ServiceBook): AcceptedReading {
        val next = fixture("Редакция B")
        assertEquals(previous.key, next.key)
        assertNotEquals(previous.contentHash, next.contentHash)
        assertEquals(previous.blocks.map { it.id }, next.blocks.map { it.id })
        val otherStore = BookReadingStore(context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
        val otherCursor = saved().copy(blockId = "prayer-9", fraction = .61f,
            mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN, wordsPerMinute = 113)
        assertTrue(otherStore.acceptVersion(previous, next, parishId, otherCursor))
        val position = requireNotNull(otherStore.positionFor(key, parishId))
        assertEquals(next.contentHash, position.contentHash)
        assertEquals("prayer-9", position.blockId)
        assertEquals(position, otherStore.last(parishId))
        return AcceptedReading(position, preferences.all.toMap())
    }

    private fun assertPreserved(expected: AcceptedReading) {
        assertEquals("The old reader must retain the other owner's per-book position", expected.position, saved())
        assertEquals("The old reader must retain the other owner's last reading", expected.position, store.last(parishId))
        assertEquals("No preference may change after the edition owner changed", expected.preferences, preferences.all)
    }

    private fun launch(book: ServiceBook) {
        compose.setContent {
            GuideTheme(false) {
                if (mounted.value) BookReaderScreen(book, parishId, store, 0,
                    onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {})
            }
        }
        contentSet = true
        compose.onNodeWithTag("book_content").assertExists()
    }

    private fun chooseRussian() {
        if (compose.onAllNodesWithTag("book_mode_menu").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("book_mode_menu").performClick()
        compose.onNodeWithTag("book_mode_russian").performClick()
    }

    private fun saved() = requireNotNull(store.positionFor(key, parishId))
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }

    private fun fixture(edition: String): ServiceBook {
        val blocks = (0..15).map { index -> BookBlock("prayer-$index", BookBlockKind.PRAYER, "Хор",
            "$edition. Синтетический исходный фрагмент $index. ".repeat(12),
            russian = "$edition. Синтетический русский фрагмент $index. ".repeat(12)) }
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Тестовый источник",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", "Тестовый раздел", "Тестовое объяснение", blocks)), contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }
}
