package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class BookVariantRecoveryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "book-variant-recovery-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val mounted = mutableStateOf(true)
    private val church = Parish("variant-recovery-parish", "Храм Троицы Живоначальной в Листах", "Троицы в Листах",
        "Москва", "Сретенка", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val previous = fixture(ServiceBookKey("2026-11-11", churchType = "30"))
    private val requested = previous.key.copy(churchType = "10")
    private val position = BookReadingPosition(previous.key, previous.contentHash, church.id, "block-7", "chapter",
        mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)
    private val pending = CompletableDeferred<BookLoadResult>()
    private val requests = CopyOnWriteArrayList<ServiceBookKey>()
    private var openedKey: ServiceBookKey? = null
    private val books = object : ServiceBooks {
        override fun cached(key: ServiceBookKey): ServiceBook? = previous.takeIf { it.key == key }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            requests += key
            return pending.await()
        }
    }

    @After fun clean() {
        compose.runOnIdle { mounted.value = false }
        pending.cancel()
        compose.waitForIdle()
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun failedCorrectedVariantOffersOwnSavedBookOnlyAfterFailureAndExplicitChoice() {
        assertTrue(store.save(position))
        val before = preferences.all.toMap()
        launch()
        compose.waitUntil(5_000) { requests.isNotEmpty() }
        compose.onNodeWithTag("book_open_previous_variant").assertDoesNotExist()
        assertEquals(before, preferences.all)
        fail()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_open_previous_variant").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book_previous_variant_notice").assertTextContains("прежним вариантом", substring = true)
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        assertEquals(before, preferences.all)

        compose.onNodeWithTag("book_open_previous_variant").performScrollTo().performClick()
        awaitBook()
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        compose.runOnIdle {
            assertEquals(previous.key, openedKey)
            assertEquals(listOf(requested), requests.toList())
            assertEquals(previous.key, store.last(church.id)!!.key)
            assertEquals(previous.contentHash, store.last(church.id)!!.contentHash)
            assertNull(store.positionFor(requested, church.id))
        }
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithText("Источники и вариант службы").performScrollTo().performClick()
        compose.onNodeWithTag("book_church_type").assertTextContains("Вариант источника: храм святого", substring = true)
        compose.onNodeWithTag("book_church_type").assertTextContains("По посвящению храма: Господский храм", substring = true)
    }

    @Test fun successfulCorrectedBookDoesNotOfferOrReplaceThePreviousEdition() {
        assertTrue(store.save(position))
        val corrected = fixture(requested)
        pending.complete(BookLoadResult.Ready(corrected, fromCache = false))
        launch()
        awaitBook()
        compose.onNodeWithTag("book_open_previous_variant").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(position, store.positionFor(previous.key, church.id))
            assertEquals(previous, books.cached(previous.key))
            assertEquals(requested, store.last(church.id)!!.key)
        }
    }

    @Test fun sharedCachedBookWithoutThisParishsPositionIsNotOffered() {
        assertTrue(store.save(position.copy(parishId = "other-parish")))
        launch()
        fail()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_load_error").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("book_open_previous_variant").assertDoesNotExist()
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        assertNull(store.last(church.id))
    }

    @Test fun continuingAcceptedOldKeyStillWorksOfflineWithoutReclassification() {
        assertTrue(store.save(position))
        launch(previous.key)
        awaitBook()
        assertTrue(requests.isEmpty())
        assertEquals(previous.key, store.last(church.id)!!.key)
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
    }

    private fun launch(key: ServiceBookKey = requested) {
        compose.setContent { GuideTheme(false) {
            if (mounted.value) ServiceBookScreen(key, church.id, books, store, 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, parish = church,
                onRequestChanged = { openedKey = it }, onOpenCommonLiturgy = {})
        } }
    }

    private fun fail() { pending.complete(BookLoadResult.Failed(BookFailure.NETWORK, "Не удалось загрузить службу")) }

    private fun awaitBook() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_content").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
    }

    private fun fixture(key: ServiceBookKey): ServiceBook {
        val blocks = (0..15).map { i -> BookBlock("block-$i", BookBlockKind.PRAYER, "Хор",
            "Тестовый оригинал фрагмента $i. ".repeat(12), russian = "Тестовый русский текст фрагмента $i. ".repeat(12)) }
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Тестовый источник",
            fetchedAtMillis = 1, selectedEventTitle = null, chapters = listOf(BookChapter("chapter", "Раздел", "Пояснение", blocks)), contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }
}
