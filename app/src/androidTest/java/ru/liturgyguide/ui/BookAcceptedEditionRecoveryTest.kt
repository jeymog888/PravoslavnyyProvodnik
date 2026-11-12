package ru.liturgyguide.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Synthetic editions exercise the real screen and durable reading store, not a local service reconstruction. */
@RunWith(AndroidJUnit4::class)
class BookAcceptedEditionRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "accepted-edition-recovery-${UUID.randomUUID()}"
    private val rawPreferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val preferences = ControlledPreferences(rawPreferences)
    private val store = BookReadingStore(preferences)
    private val books = ControlledBooks()
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val parishId = "synthetic-accepted-edition-parish"
    private val key = ServiceBookKey("2026-11-13", WorshipKind.LITURGY, "30")

    @After fun clean() {
        if (contentSet) {
            compose.runOnIdle { preferences.rejectWrites = false; preferences.failReads = false; mounted.value = false; books.releaseForCleanup() }
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun anUnavailableAcceptedEditionNeverOpensLatestOrChangesTheSavedReading() {
        val accepted = fixture("Принятая редакция")
        val latest = fixture("Другая доступная редакция")
        val position = save(accepted)
        books.publish(latest)
        assertNotEquals(accepted.contentHash, latest.contentHash)
        val before = rawPreferences.all.toMap()

        launch()
        awaitOpeningOutcome()
        // Keep this first: on the old screen the latest edition is already mounted as the reader.
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        compose.onNodeWithTag("book_accepted_edition_missing").assertIsDisplayed()
        assertTrue("A lost accepted edition must not trigger a download", books.loads.isEmpty())
        assertTrue("A lost accepted edition must not request latest", books.cachedCalls.isEmpty())
        assertTrue("A lost accepted edition must not start optional enrichment", books.enrichments.isEmpty())
        assertEquals(before, rawPreferences.all)
        assertEquals(position, store.positionFor(key, parishId))
        assertEquals(position, store.last(parishId))

        books.publishVersion(accepted.copy(title = "Поврежденная сохраненная редакция"))
        tap("book_check_accepted_edition")
        awaitOpeningOutcome()
        assertBlocked()
        assertEquals(before, rawPreferences.all)

        books.throwVersionReads = true
        tap("book_check_accepted_edition")
        awaitOpeningOutcome()
        assertBlocked()
        assertEquals(before, rawPreferences.all)
        assertTrue(books.loads.isEmpty())
        assertTrue(books.cachedCalls.isEmpty())
        assertTrue(books.enrichments.isEmpty())
    }

    @Test fun checkingThePhoneAgainRestoresOnlyTheExactAcceptedEditionAndItsReadingPlace() {
        val accepted = fixture("Принятая редакция")
        val position = save(accepted)
        books.publish(fixture("Другая доступная редакция"))
        launch()
        awaitOpeningOutcome()
        assertBlocked()
        books.publishVersion(accepted)

        tap("book_check_accepted_edition")
        awaitReader(accepted)
        assertEquals(position.copy(fraction = saved().fraction), saved())
        assertEquals(position.fraction, saved().fraction, .01f)
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        assertTrue("A local check must not download a text", books.loads.isEmpty())
    }

    @Test fun initialCheckDoesNotMountAnEditionWhoseOwnerChangedDuringItsLastCacheRead() {
        val accepted = fixture("Принятая редакция A")
        val other = fixture("Принятая другим владельцем редакция B")
        val changed = save(accepted).copy(contentHash = other.contentHash, blockId = "prayer-9", fraction = .61f)
        books.publish(accepted)
        books.publish(other)
        val changes = AtomicInteger()
        val expectedPreferences = AtomicReference<Map<String, *>>()
        books.beforeVersionReturn = { book ->
            if (book?.contentHash == accepted.contentHash) {
                books.beforeVersionReturn = null
                check(store.save(changed))
                expectedPreferences.set(rawPreferences.all.toMap())
                changes.incrementAndGet()
            }
        }

        launch()
        awaitOpeningOutcome()
        // On the previous screen A was mounted here and its autosave replaced B's cursor.
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        assertEquals("The test must change ownership inside the actual exact-cache read", 1, changes.get())
        compose.onNodeWithTag("book_accepted_recovery_warning")
            .assertTextContains("Сохраненное место изменилось", substring = true)
        assertEquals(changed, saved())
        assertEquals(changed, store.last(parishId))
        assertEquals(expectedPreferences.get(), rawPreferences.all)
        assertTrue(books.loads.isEmpty())
    }

    @Test fun exactDownloadDoesNotMountAnEditionWhoseOwnerChangedDuringDurabilityVerification() {
        val accepted = fixture("Принятая редакция A")
        val other = fixture("Принятая другим владельцем редакция B")
        val changed = save(accepted).copy(contentHash = other.contentHash, blockId = "prayer-9", fraction = .61f)
        books.publish(other)
        books.response = BookLoadResult.Ready(accepted, fromCache = false)
        val changes = AtomicInteger()
        val expectedPreferences = AtomicReference<Map<String, *>>()
        books.afterSavedLoad = { loaded ->
            if (loaded.contentHash == accepted.contentHash) books.beforeVersionReturn = { book ->
                if (book?.contentHash == accepted.contentHash) {
                    books.beforeVersionReturn = null
                    check(store.save(changed))
                    expectedPreferences.set(rawPreferences.all.toMap())
                    changes.incrementAndGet()
                }
            }
        }
        launch()
        awaitOpeningOutcome()
        assertBlocked()

        tap("book_download_edition")
        compose.waitUntil(5_000) { changes.get() == 1 }
        awaitOpeningOutcome()
        // The initial missing-version check passed; B is accepted only in the final durability read.
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        compose.onNodeWithTag("book_accepted_recovery_warning")
            .assertTextContains("Сохраненное место изменилось", substring = true)
        assertEquals(changed, saved())
        assertEquals(changed, store.last(parishId))
        assertEquals(expectedPreferences.get(), rawPreferences.all)
        assertEquals(listOf(Request(key, true)), books.loads)
    }

    @Test fun aValidLastReadingStillPinsItsEditionWhenTheBoundedPerBookPointerWasEvicted() {
        val accepted = fixture("Принятая редакция")
        val position = save(accepted)
        val positionKey = rawPreferences.all.keys.single { it.startsWith("position:") }
        assertTrue(rawPreferences.edit().remove(positionKey).commit())
        val before = rawPreferences.all.toMap()
        assertEquals(BookReadingLookup.Found(position), store.lookup(key, parishId))
        assertEquals(position, store.positionFor(key, parishId))
        assertEquals("A last reading must not pin another service date", BookReadingLookup.Absent,
            store.lookup(key.copy(date = "2026-11-14"), parishId))
        books.publish(fixture("Другая доступная редакция"))

        launch()
        awaitOpeningOutcome()
        assertBlocked()
        assertEquals(before, rawPreferences.all)
        assertTrue(books.loads.isEmpty())
        assertTrue(books.cachedCalls.isEmpty())

        books.publishVersion(accepted)
        tap("book_check_accepted_edition")
        awaitReader(accepted)
        assertEquals(position.copy(fraction = saved().fraction), saved())
        assertEquals(position.fraction, saved().fraction, .01f)
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        assertTrue("The ordinary reader may now persist its recovered exact place", rawPreferences.contains(positionKey))
        assertTrue(books.loads.isEmpty())
    }

    @Test fun anExplicitDownloadCanRecoverTheSameDurableContentWithoutChoosingAnotherEdition() {
        val accepted = fixture("Принятая редакция")
        val position = save(accepted)
        val before = rawPreferences.all.toMap()
        books.response = BookLoadResult.Ready(accepted, fromCache = false)
        launch()
        awaitOpeningOutcome()
        assertBlocked()
        assertEquals(before, rawPreferences.all)

        tap("book_download_edition")
        awaitReader(accepted)
        assertEquals(listOf(Request(key, true)), books.loads)
        assertEquals(position.copy(fraction = saved().fraction), saved())
        assertEquals(position.fraction, saved().fraction, .01f)
        compose.onNodeWithTag("book_open_new_edition").assertDoesNotExist()
    }

    @Test fun aDifferentDownloadedEditionSurvivesRecreationAsAnOfferAndOpensOnlyAfterExplicitAcceptance() {
        val accepted = fixture("Принятая редакция")
        val latest = fixture("Новая загруженная редакция")
        val position = save(accepted)
        val before = rawPreferences.all.toMap()
        books.response = BookLoadResult.Ready(latest, fromCache = false)
        val restoration = StateRestorationTester(compose)
        launch(restoration)
        awaitOpeningOutcome()
        tap("book_download_edition")
        await("book_open_new_edition")
        assertBlocked()
        assertEquals(before, rawPreferences.all)
        assertEquals(position, saved())

        restoration.emulateSavedInstanceStateRestore()
        await("book_open_new_edition")
        assertBlocked()
        assertEquals("Recreation rechecks the saved candidate, never downloads automatically", 1, books.loads.size)
        assertEquals(before, rawPreferences.all)
        assertEquals(position, saved())

        tap("book_open_new_edition")
        awaitReader(latest)
        assertEquals(BookAnchorKind.HEADER, saved().anchorKind)
        assertEquals("prayer-0", saved().blockId)
        assertEquals(0f, saved().fraction, .001f)
        assertEquals(BookReadingMode.RUSSIAN, saved().mode)
        compose.onNodeWithTag("book_mode_russian").assertIsSelected()
        assertNotEquals(position, saved())
    }

    @Test fun malformedWrongTypeAndUnreadableStoredPointersNeverBecomeANewVisit() {
        save(fixture("Принятая редакция"))
        books.publish(fixture("Другая доступная редакция"))
        val positionKey = rawPreferences.all.keys.single { it.startsWith("position:") }
        assertTrue(rawPreferences.edit().putString(positionKey, "{broken-json").commit())
        var before = rawPreferences.all.toMap()
        assertEquals(BookReadingLookup.Unreadable, store.lookup(key, parishId))
        launch()
        awaitOpeningOutcome()
        assertBlocked()
        assertEquals(before, rawPreferences.all)

        assertTrue(rawPreferences.edit().putInt(positionKey, 17).commit())
        before = rawPreferences.all.toMap()
        tap("book_check_accepted_edition")
        awaitOpeningOutcome()
        assertBlocked()
        assertEquals(BookReadingLookup.Unreadable, store.lookup(key, parishId))
        assertEquals(before, rawPreferences.all)

        preferences.failReads = true
        tap("book_check_accepted_edition")
        awaitOpeningOutcome()
        assertBlocked()
        assertEquals(BookReadingLookup.Unreadable, store.lookup(key, parishId))
        assertEquals(before, rawPreferences.all)
        preferences.failReads = false
        assertTrue(books.loads.isEmpty())
        assertTrue(books.cachedCalls.isEmpty())
        assertTrue(books.enrichments.isEmpty())
    }

    @Test fun failedOrUnsavedDownloadsKeepTheOldPointersAndCanBeRetried() {
        save(fixture("Принятая редакция"))
        val latest = fixture("Новая редакция")
        val before = rawPreferences.all.toMap()
        books.response = BookLoadResult.Failed(BookFailure.NETWORK, "Синтетическая ошибка сети")
        launch()
        awaitOpeningOutcome()
        downloadAndAwait("book_accepted_recovery_warning")
        assertBlocked()
        assertEquals(before, rawPreferences.all)

        books.response = BookLoadResult.Ready(latest, fromCache = false, saved = false)
        downloadAndAwait("book_accepted_recovery_warning")
        assertBlocked()
        compose.onNodeWithTag("book_open_new_edition").assertDoesNotExist()
        assertEquals(before, rawPreferences.all)

        books.persistLoaded = false
        books.response = BookLoadResult.Ready(latest, fromCache = false)
        downloadAndAwait("book_accepted_recovery_warning")
        assertBlocked()
        compose.onNodeWithTag("book_open_new_edition").assertDoesNotExist()
        assertEquals(before, rawPreferences.all)

        books.persistLoaded = true
        downloadAndAwait("book_open_new_edition")
        assertBlocked()
        assertEquals(before, rawPreferences.all)
    }

    @Test fun aRejectedAcceptanceCommitKeepsTheOldReadingAndRetryCanAcceptTheSavedCandidate() {
        save(fixture("Принятая редакция"))
        val latest = fixture("Новая редакция")
        val before = rawPreferences.all.toMap()
        books.response = BookLoadResult.Ready(latest, fromCache = false)
        launch()
        awaitOpeningOutcome()
        tap("book_download_edition")
        await("book_open_new_edition")
        preferences.rejectWrites = true
        tap("book_open_new_edition")
        await("book_accepted_recovery_warning")
        assertBlocked()
        assertEquals(before, rawPreferences.all)
        assertTrue(preferences.rejectedCommits > 0)

        preferences.rejectWrites = false
        tap("book_open_new_edition")
        awaitReader(latest)
        assertEquals(1, books.loads.size)
    }

    @Test fun aLateDownloadFromBeforeRecreationCannotOpenOrAcceptItsEdition() {
        save(fixture("Принятая редакция"))
        val latest = fixture("Поздняя новая редакция")
        val before = rawPreferences.all.toMap()
        books.hold = true
        val restoration = StateRestorationTester(compose)
        launch(restoration)
        awaitOpeningOutcome()
        tap("book_download_edition")
        compose.waitUntil(5_000) { books.pending != null }

        restoration.emulateSavedInstanceStateRestore()
        awaitOpeningOutcome()
        books.release(BookLoadResult.Ready(latest, fromCache = false))
        compose.waitUntil(5_000) { books.delivered == 1 }
        settle()
        assertBlocked()
        compose.onNodeWithTag("book_open_new_edition").assertDoesNotExist()
        assertEquals(before, rawPreferences.all)
        assertEquals(1, books.loads.size)
    }

    private fun launch(restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            GuideTheme(false) {
                if (mounted.value) ServiceBookScreen(key, parishId, books, store, 0,
                    onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        contentSet = true
    }

    private fun awaitOpeningOutcome() {
        compose.waitUntil(5_000) {
            exists("book_content") || exists("book_accepted_edition_missing") || exists("book_load_error")
        }
        awaitRecoveryIdle()
        settle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }
    private fun await(tag: String) {
        compose.waitUntil(5_000) { exists(tag) }
        awaitRecoveryIdle()
        settle()
    }
    private fun awaitRecoveryIdle() {
        compose.waitUntil(5_000) { !exists("book_accepted_recovery_progress") }
    }
    private fun tap(tag: String) {
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag(tag).assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun downloadAndAwait(tag: String) {
        val before = books.loads.size
        tap("book_download_edition")
        compose.waitUntil(5_000) { books.loads.size == before + 1 }
        await(tag)
    }
    private fun assertBlocked() {
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        compose.onNodeWithTag("book_accepted_edition_missing").assertExists()
    }
    private fun saved() = requireNotNull(store.positionFor(key, parishId))
    private fun awaitReader(book: ServiceBook) {
        await("book_content")
        compose.waitUntil(5_000) { store.last(parishId)?.contentHash == book.contentHash }
        settle()
    }

    private fun save(book: ServiceBook) = BookReadingPosition(book.key, book.contentHash, parishId,
        "prayer-7", "chapter", fraction = .32f, mode = BookReadingMode.RUSSIAN,
        lastTextMode = BookReadingMode.RUSSIAN, wordsPerMinute = 113).also { assertTrue(store.save(it)) }

    private data class Edition(val key: ServiceBookKey, val hash: String)
    private data class Request(val key: ServiceBookKey, val force: Boolean)

    private class ControlledBooks : ServiceBooks {
        val loads = CopyOnWriteArrayList<Request>()
        val cachedCalls = CopyOnWriteArrayList<ServiceBookKey>()
        val enrichments = CopyOnWriteArrayList<ServiceBook>()
        @Volatile var response: BookLoadResult? = null
        @Volatile var persistLoaded = true
        @Volatile var throwVersionReads = false
        @Volatile var hold = false
        @Volatile var pending: Continuation<BookLoadResult>? = null
        @Volatile var delivered = 0
        @Volatile var beforeVersionReturn: ((ServiceBook?) -> Unit)? = null
        @Volatile var afterSavedLoad: ((ServiceBook) -> Unit)? = null
        private val latest = ConcurrentHashMap<ServiceBookKey, ServiceBook>()
        private val versions = ConcurrentHashMap<Edition, ServiceBook>()
        fun publish(book: ServiceBook) { latest[book.key] = book; versions[Edition(book.key, book.contentHash)] = book }
        fun publishVersion(book: ServiceBook) { versions[Edition(book.key, book.contentHash)] = book }
        override fun cached(key: ServiceBookKey): ServiceBook? { cachedCalls += key; return latest[key] }
        override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? {
            if (throwVersionReads) error("Synthetic exact-version read failure")
            val book = versions[Edition(key, contentHash)]
            beforeVersionReturn?.invoke(book)
            return book
        }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            loads += Request(key, force)
            val result = if (hold) suspendCoroutine { pending = it } else response
                ?: latest[key]?.let { BookLoadResult.Ready(it, fromCache = true) }
                ?: BookLoadResult.Failed(BookFailure.NETWORK, "Синтетический источник недоступен")
            if (result is BookLoadResult.Ready && result.saved && persistLoaded) {
                publish(result.book)
                afterSavedLoad?.invoke(result.book)
            }
            return result
        }
        override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? {
            enrichments += accepted
            return null
        }
        fun release(result: BookLoadResult) {
            hold = false
            requireNotNull(pending).also { pending = null }.resume(result)
            delivered++
        }
        fun releaseForCleanup() {
            pending?.also { pending = null }?.resume(BookLoadResult.Failed(BookFailure.NETWORK, "Тест завершен"))
        }
    }

    private class ControlledPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        @Volatile var rejectWrites = false
        @Volatile var failReads = false
        var rejectedCommits = 0
        override fun getAll(): MutableMap<String, *> {
            if (failReads) error("Synthetic preference read failure")
            return backing.all
        }
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                override fun commit(): Boolean {
                    val result = editor.commit()
                    if (!rejectWrites) return result
                    rejectedCommits++
                    return false
                }
            }
        }
    }

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
