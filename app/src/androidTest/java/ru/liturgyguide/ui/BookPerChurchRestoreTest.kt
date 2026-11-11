package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

/** Exercises the real BOOK route's retained saveable state, not just removing a bare reader. */
@RunWith(AndroidJUnit4::class)
class BookPerChurchRestoreTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "book-per-church-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val mounted = mutableStateOf(true)
    private val parishId = mutableStateOf("parish-a")
    private val book = fixture()

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun sameBookSavedRouteRestoresEachChurchsLanguageControlsAndPositionAcrossAtoBtoA() {
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, "parish-a", "block-a", "chapter-a",
            fraction = .35f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 61, showAltar = true,
            lastTextMode = BookReadingMode.RUSSIAN)))
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, "parish-b", "block-b", "chapter-b",
            fraction = .22f, mode = BookReadingMode.ORIGINAL, wordsPerMinute = 113, showAltar = false,
            lastTextMode = BookReadingMode.ORIGINAL)))
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey) = book.takeIf { it.key == key }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit) =
                BookLoadResult.Ready(book, fromCache = true)
        }
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            GuideTheme(false) {
                if (mounted.value) holder.SaveableStateProvider("BOOK") {
                    ServiceBookScreen(book.key, parishId.value, books, store, 0,
                        onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
                }
            }
        }
        settleReader()
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        val savedA = requireNotNull(store.last("parish-a"))
        assertEquals("block-a", savedA.blockId)
        assertEquals(61, savedA.wordsPerMinute)
        assertTrue(savedA.showAltar)

        reopenFor("parish-b")
        compose.onNodeWithTag("book_mode_original").assertIsSelected()
        val savedB = requireNotNull(store.last("parish-b"))
        assertEquals(BookReadingMode.ORIGINAL, savedB.mode)
        assertEquals(BookReadingMode.ORIGINAL, savedB.lastTextMode)
        assertEquals("block-b", savedB.blockId)
        assertEquals("chapter-b", savedB.chapterId)
        assertEquals(.22f, savedB.fraction, .001f)
        assertEquals(113, savedB.wordsPerMinute)
        assertFalse(savedB.showAltar)
        assertEquals(savedA, store.last("parish-a"))

        reopenFor("parish-a")
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        val restoredA = requireNotNull(store.last("parish-a"))
        assertEquals(BookReadingMode.RUSSIAN, restoredA.lastTextMode)
        assertEquals(savedA.blockId, restoredA.blockId)
        assertEquals(savedA.chapterId, restoredA.chapterId)
        assertEquals(savedA.fraction, restoredA.fraction, .001f)
        assertEquals(savedA.wordsPerMinute, restoredA.wordsPerMinute)
        assertEquals(savedA.showAltar, restoredA.showAltar)
        assertEquals(savedB, store.last("parish-b"))
    }

    private fun reopenFor(id: String) {
        // MainActivity unmounts BOOK before the user picks another church, preserving the route's state
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { parishId.value = id; mounted.value = true }
        settleReader()
    }

    private fun settleReader() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }

    private fun fixture(): ServiceBook {
        val key = ServiceBookKey("2026-11-11")
        val chapters = listOf("a", "b").map { id -> BookChapter("chapter-$id", "Раздел $id", "Молимся вместе",
            listOf(BookBlock("block-$id", BookBlockKind.PRAYER, "Хор", "Господи, помилуй нас и помоги жить в мире. ".repeat(160),
                russian = "Просим Бога сохранить мир в наших сердцах. ".repeat(160)))) }
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Тестовый источник",
            fetchedAtMillis = 1, selectedEventTitle = null, chapters = chapters, contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
}
