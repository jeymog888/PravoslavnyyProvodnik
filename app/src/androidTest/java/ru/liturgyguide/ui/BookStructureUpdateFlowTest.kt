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

/** Synthetic passages exercise actual reader, immutable files and bookmarks, without network. */
@RunWith(AndroidJUnit4::class)
class BookStructureUpdateFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "book-structure-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = context.cacheDir.resolve(name)
    private val cache = FileBookCache(directory)
    private val mounted = mutableStateOf(true)
    private val parish = "structure-test-parish"
    private var contentSet = false

    @After fun clean() {
        if (contentSet) {
            compose.runOnUiThread { mounted.value = false }
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun aReclassifiedDirectionKeepsItsPassageAfterAcceptanceAndOfflineReentry() = checkUpdate(split = false)

    @Test fun aSeparatedPrayerRestartsAtItsOwnDirectionAndReopensThereOffline() = checkUpdate(split = true)

    private fun checkUpdate(split: Boolean) {
        val instruction = "Священник же молится:"
        val body = "Проверочный фрагмент текста для проверки положения при обновлении. ".repeat(70).trim()
        val original = if (split) "$instruction $body" else body
        val oldBlock = BookBlock("old-target", if (split) BookBlockKind.RUBRIC else BookBlockKind.PRAYER,
            null, original, russian = original)
        val nextBlocks = if (split) listOf(
            BookBlock("new-target", BookBlockKind.RUBRIC, null, instruction, russian = instruction),
            BookBlock("new-prayer", BookBlockKind.PRAYER, null, body, russian = body)
        ) else listOf(oldBlock.copy(id = "new-target", kind = BookBlockKind.RUBRIC))
        val old = book(listOf(oldBlock))
        val next = book(nextBlocks)
        assertTrue(cache.write(old))
        assertTrue(cache.write(next))
        assertTrue(store.save(BookReadingPosition(old.key, old.contentHash, parish, oldBlock.id, "chapter",
            fraction = .37f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)))
        // A new repository instance reads only the disk cache; no load is permitted.
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey) = FileBookCache(directory).read(key)
            override fun cachedVersion(key: ServiceBookKey, contentHash: String) = FileBookCache(directory).readVersion(key, contentHash)
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult =
                error("Both editions must remain readable offline")
        }
        compose.setContent { GuideTheme(false) {
            if (mounted.value) ServiceBookScreen(old.key, parish, books, store, 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
        } }
        contentSet = true
        awaitReader()
        compose.onNodeWithTag("book_block_old-target").assertIsDisplayed()
        val before = requireNotNull(store.last(parish))
        assertEquals(old.contentHash, before.contentHash)
        compose.onNodeWithTag("book_accept_update").performClick()
        settle()
        assertAccepted(next, if (split) 0f else before.fraction)
        compose.runOnIdle { mounted.value = false }; settle()
        compose.runOnIdle { mounted.value = true }; awaitReader()
        assertAccepted(next, if (split) 0f else before.fraction)
        assertEquals("The older immutable edition remains available", old, FileBookCache(directory).readVersion(old.key, old.contentHash))
    }

    private fun assertAccepted(next: ServiceBook, fraction: Float) {
        val accepted = requireNotNull(store.last(parish))
        assertEquals(next.contentHash, accepted.contentHash)
        assertEquals("new-target", accepted.blockId)
        assertEquals(BookAnchorKind.BLOCK, accepted.anchorKind)
        assertEquals(BookReadingMode.RUSSIAN, accepted.mode)
        assertEquals(fraction, accepted.fraction, .004f)
        compose.onNodeWithTag("book_block_new-target").assertIsDisplayed()
        compose.onNodeWithTag("book_block_intro").assertIsNotDisplayed()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
    }

    private fun book(target: List<BookBlock>): ServiceBook {
        val key = ServiceBookKey("2026-11-12")
        val intro = BookBlock("intro", BookBlockKind.PRAYER, null, "Начало раздела. ".repeat(100))
        val end = BookBlock("end", BookBlockKind.PRAYER, null, "Продолжение раздела. ".repeat(100))
        val rest = (1..12).map { index -> BookBlock("rest-$index", BookBlockKind.PRAYER, null,
            "Дальнейший проверочный фрагмент $index. ".repeat(5)) }
        val book = ServiceBook(key = key, title = "Проверка обновления", sourceUrl = key.sourceUrl(),
            sourceTitle = "Синтетический тест", fetchedAtMillis = 1, selectedEventTitle = null, contentHash = "",
            chapters = listOf(BookChapter("chapter", "Раздел", "Пояснение", listOf(intro) + target + rest + end)))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }

    private fun awaitReader() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }
}
