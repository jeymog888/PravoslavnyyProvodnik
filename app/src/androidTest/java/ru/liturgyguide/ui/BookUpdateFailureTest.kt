package ru.liturgyguide.ui

import android.content.Context
import android.content.SharedPreferences
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

/** Actual screen + disk-backed preferences; only a disk failure report is injected. */
@RunWith(AndroidJUnit4::class)
class BookUpdateFailureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "book-update-failure-${UUID.randomUUID()}"
    private val backing = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val storage = FailAfterMemoryPreferences(backing)
    private val store = BookReadingStore(storage)
    private val mounted = mutableStateOf(true)
    private val parish = "book-update-failure-parish"
    private val old = fixture("Сохраненное объяснение общей молитвы")
    private val newer = fixture("Обновленное объяснение общей молитвы")
    private val books = object : ServiceBooks {
        override fun cached(key: ServiceBookKey): ServiceBook? = newer.takeIf { it.key == key }
        override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? =
            listOf(old, newer).firstOrNull { it.key == key && it.contentHash == contentHash }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult =
            error("Both versions are on the phone; this flow must remain offline")
    }

    @After fun clean() {
        storage.failHashOnce = null
        compose.runOnUiThread { mounted.value = false }
        compose.waitForIdle()
        context.deleteSharedPreferences(name)
    }

    @Test fun failedNewEditionCommitRestoresOldPointersAndAllowsTheSameExplicitRetry() {
        assertTrue(store.save(BookReadingPosition(old.key, old.contentHash, parish, "prayer", "chapter",
            fraction = .37f, mode = BookReadingMode.MEANING, lastTextMode = BookReadingMode.RUSSIAN)))
        compose.setContent { GuideTheme(false) {
            if (mounted.value) ServiceBookScreen(old.key, parish, books, store, 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
        } }
        awaitBook()
        val before = requireNotNull(store.last(parish))
        val beforeValues = backing.all.toMap()
        compose.onNodeWithText(old.chapters.single().meaning).assertIsDisplayed()
        compose.onNodeWithTag("book_accept_update").assertIsDisplayed()
        compose.runOnIdle { storage.failHashOnce = newer.contentHash }
        compose.onNodeWithTag("book_accept_update").performClick()
        // Check immediately, before lifecycle saves or a later debounce can hide
        // the bug by overwriting the prematurely accepted hash with the old one.
        compose.runOnIdle {
            assertEquals(1, storage.failures)
            assertEquals(newer.contentHash, storage.hashWrittenBeforeFailure)
            assertEquals(beforeValues, backing.all)
            assertEquals(before, BookReadingStore(backing).last(parish))
        }
        compose.onNodeWithText(old.chapters.single().meaning).assertIsDisplayed()
        compose.onNodeWithText(newer.chapters.single().meaning).assertDoesNotExist()
        compose.onNodeWithTag("book_accept_update").assertIsDisplayed()
        compose.onNodeWithText("Не удалось сохранить место. Открытый текст остался на месте").assertExists()

        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        awaitBook()
        assertEquals("Reentry must still pin the old edition", old.contentHash, store.last(parish)!!.contentHash)
        compose.onNodeWithText(old.chapters.single().meaning).assertIsDisplayed()
        compose.onNodeWithTag("book_accept_update").performClick()
        compose.runOnIdle {
            assertEquals(newer.contentHash, store.last(parish)!!.contentHash)
            assertEquals(1, storage.failures)
        }
        settle()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        compose.onNodeWithText(newer.chapters.single().meaning).assertIsDisplayed()
        val accepted = requireNotNull(store.last(parish))
        assertEquals(before.key, accepted.key)
        assertEquals(before.parishId, accepted.parishId)
        assertEquals(before.blockId, accepted.blockId)
        assertEquals(before.mode, accepted.mode)
        assertEquals(before.lastTextMode, accepted.lastTextMode)
        assertEquals(before.fraction, accepted.fraction, .001f)
    }

    private fun awaitBook() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }

    private fun fixture(meaning: String): ServiceBook {
        val key = ServiceBookKey("2026-11-11")
        val block = BookBlock("prayer", BookBlockKind.PRAYER, null,
            "Господи, помоги нам жить в мире. ".repeat(100), "Господь, помоги нам жить в мире. ".repeat(100))
        val hiddenPadding = List(14) { index -> BookBlock("integrity-altar-$index", BookBlockKind.PRAYER, null,
            "Синтетическая алтарная молитва $index для проверки полной книги.", isAltarText = true) }
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Тестовый источник",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", "Общая молитва", meaning, listOf(block) + hiddenPadding)), contentHash = "",
            enrichment = BookEnrichment(BookSupplementStatus.AVAILABLE, BookSupplementStatus.NOT_NEEDED))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }

    private class FailAfterMemoryPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        var failHashOnce: String? = null
        var hashWrittenBeforeFailure: String? = null
        var failures = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            val strings = mutableMapOf<String, String?>()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    if (key != null) strings[key] = value
                    return this
                }
                override fun commit(): Boolean {
                    val target = failHashOnce
                    val shouldFail = target != null && strings.any { (key, value) ->
                        key.startsWith("position:") && value?.contains("\"contentHash\":\"$target\"") == true
                    }
                    val committed = editor.commit() // Android's memory has changed before failure is reported.
                    if (!shouldFail) return committed
                    failHashOnce = null
                    hashWrittenBeforeFailure = target
                    failures++
                    return false
                }
            }
        }
    }
}
