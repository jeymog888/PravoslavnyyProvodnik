package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Genuine accepted Matins + reviewed bundled pairs, real local publication, explicit reader acceptance and disk remount. */
@RunWith(AndroidJUnit4::class)
class BookReviewedRussianFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "reviewed-russian-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = mutableStateOf(BookReadingStore(preferences))
    private val directory = File(context.cacheDir, name)
    private val cache = FileBookCache(directory)
    private val parish = "reviewed-russian-test-parish"
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val networkCalls = AtomicInteger()
    private val release = CompletableDeferred<Unit>()
    private val owners = mutableListOf<LocalBooks>()
    private val books = mutableStateOf(newBooks(release))
    private val archive = instrumentation.context.assets.open(ARCHIVE).use { it.readBytes() }
    private val old = Gson().fromJson(archive.toString(Charsets.UTF_8), ServiceBook::class.java)
    private val target = old.blocks.single { it.id == TARGET }

    @After fun clean() {
        if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        release.complete(Unit)
        if (contentSet) compose.waitUntil(5_000) { owners.all { it.inFlight.get() == 0 } }
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun reviewedRefrainsReachRussianReadingOnlyAfterAcceptanceAndReopenAsTheExactSavedEdition() {
        assertEquals("c1a622cb4fad548172bf44179d0c34433cff89a6e7b377fb8a8d14b3adbe7ca1", sha256(archive))
        assertEquals("bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", old.contentHash)
        assertEquals(ServiceBookKey("2026-11-13", WorshipKind.MATINS, "20"), old.key)
        assertTrue(ServiceBookIntegrity.valid(old))
        assertFalse(target.isAltarText)
        assertTrue(BookVisibilityScope.of(old).isVisible(target))
        assertEquals(APPROVED[1].first, target.original)
        assertNull(target.russian)
        assertTrue(cache.write(old))
        val oldFile = File(directory, "${BookText.sha256(old.key.canonical()).take(32)}-${old.contentHash}.json")
        val oldDiskBytes = oldFile.readBytes()
        val chapter = old.chapters.single { target in it.blocks }
        assertTrue(store.value.save(BookReadingPosition(old.key, old.contentHash, parish, target.id, chapter.id,
            fraction = .15f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)))
        compose.setContent { GuideTheme(false) {
            if (mounted.value) ServiceBookScreen(old.key, parish, books.value, store.value, 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
        } }
        contentSet = true
        awaitReader(old.contentHash)
        compose.waitUntil(5_000) { books.value.started.get() == 1 }
        val before = saved()
        assertEquals(BookReadingMode.RUSSIAN, before.mode)
        assertEquals(target.id, before.blockId)
        assertTrue("The accepted Russian fallback starts inside its real public refrain", before.fraction > 0f)
        val originalText = compose.onNodeWithTag("book_text_$TARGET", useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals(target.original)
        val originalBounds = originalText.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        assertTrue("The complete original fallback must be on screen", originalBounds.top >= viewport.top && originalBounds.bottom <= viewport.bottom)
        compose.onNodeWithTag("book_missing_russian_$TARGET").assertExists()
        compose.onNodeWithTag("book_feoria_translation_$TARGET").assertDoesNotExist()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        val beforePreferences = preferences.all.toMap()

        release.complete(Unit)
        compose.waitUntil(10_000) { books.value.candidate.get() != null && exists("book_accept_update") }
        settle()
        val candidate = requireNotNull(books.value.candidate.get())
        assertTrue(candidate.saved)
        assertTrue(candidate.fromCache)
        assertNotEquals(old.contentHash, candidate.book.contentHash)
        assertTrue(ServiceBookIntegrity.valid(candidate.book))
        assertReviewed48(candidate.book)
        assertEquals(candidate.book, FileBookCache(directory).readVersion(old.key, candidate.book.contentHash))
        assertEquals(candidate.book, cache.read(old.key))
        assertEquals("An offered latest edition is not permission to accept it", before, saved())
        assertEquals(beforePreferences, preferences.all)
        originalText.assertIsDisplayed().assertTextEquals(target.original)
        assertEquals(old, cache.readVersion(old.key, old.contentHash))
        assertArrayEquals(oldDiskBytes, oldFile.readBytes())

        compose.onNodeWithTag("book_accept_update").assertIsDisplayed().performClick()
        awaitReader(candidate.book.contentHash)
        val accepted = saved()
        assertEquals(before.copy(contentHash = candidate.book.contentHash, fraction = accepted.fraction,
            followingGapFraction = accepted.followingGapFraction), accepted)
        assertEquals("Changing the visible Russian fallback resets only its text offset", 0f, accepted.fraction, .003f)
        assertEquals(0f, accepted.followingGapFraction, .003f)
        assertReviewedTextVisible()
        assertEquals(0, books.value.loads.get())
        assertEquals(0, networkCalls.get())

        // Same-process remount: new real repository/cache wrapper and reading store, without a supplied candidate.
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        val reopenedBooks = newBooks()
        compose.runOnIdle { books.value = reopenedBooks; store.value = BookReadingStore(preferences); mounted.value = true }
        awaitReader(candidate.book.contentHash)
        compose.waitUntil(5_000) { reopenedBooks.started.get() >= 1 && reopenedBooks.inFlight.get() == 0 }
        assertEquals(accepted, saved())
        assertReviewedTextVisible()
        compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
        assertEquals(0, reopenedBooks.loads.get())
        assertEquals(0, networkCalls.get())
        assertEquals(candidate.book, FileBookCache(directory).readVersion(old.key, saved().contentHash))
        assertEquals(old, FileBookCache(directory).readVersion(old.key, old.contentHash))
        assertArrayEquals(oldDiskBytes, oldFile.readBytes())
        assertArrayEquals(archive, instrumentation.context.assets.open(ARCHIVE).use { it.readBytes() })
    }

    private fun assertReviewed48(next: ServiceBook) {
        assertEquals(old.blocks.map { it.id }, next.blocks.map { it.id })
        assertEquals(48, next.blocks.zip(old.blocks).count { (after, before) -> after != before })
        for ((original, russian) in APPROVED) {
            val before = old.blocks.filter { it.original == original }
            val translated = next.blocks.filter { it.original == original }
            assertEquals(16, before.size)
            assertEquals(16, translated.size)
            assertTrue(before.all { it.russian == null && it.kind == BookBlockKind.PRAYER && !it.isAltarText })
            for (block in translated) {
                assertEquals(russian, block.russian)
                assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, block.translationSource)
                assertEquals("Перевод Феории", block.translationSource?.title)
                assertEquals(old.blocks.single { it.id == block.id }.copy(russian = russian,
                    translationSource = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE), block)
            }
        }
        next.blocks.filter { after -> APPROVED.none { it.first == after.original } }.forEach { after ->
            assertEquals("Other accepted prayers and earlier translations must stay unchanged", old.blocks.single { it.id == after.id }, after)
        }
    }
    private fun assertReviewedTextVisible() {
        compose.onNodeWithTag("book_text_$TARGET", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(APPROVED[1].second)
        compose.onNodeWithTag("book_feoria_translation_$TARGET").assertIsDisplayed().assertTextEquals("Перевод Феории")
        compose.onNodeWithTag("book_missing_russian_$TARGET").assertDoesNotExist()
        assertEquals(BookReadingMode.RUSSIAN, saved().mode)
        assertEquals(TARGET, saved().blockId)
        assertEquals(103, saved().wordsPerMinute)
        assertFalse(saved().showAltar)
    }
    private fun newBooks(gate: CompletableDeferred<Unit>? = null) = LocalBooks(ServiceBookRepository(FileBookCache(directory),
        ScheduleTransport { networkCalls.incrementAndGet(); error("This local reviewed-pair scenario forbids network") },
        translations = BookTranslationIndex.fromFeoriaCatalog()), gate).also { owners += it }
    private fun saved() = requireNotNull(store.value.positionFor(old.key, parish))
    private fun awaitReader(hash: String) {
        compose.waitUntil(10_000) { exists("book_content") && store.value.last(parish)?.contentHash == hash }
        settle()
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(750); compose.waitForIdle() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private class LocalBooks(private val repository: ServiceBookRepository, private val gate: CompletableDeferred<Unit>?) : ServiceBooks by repository {
        val started = AtomicInteger()
        val inFlight = AtomicInteger()
        val loads = AtomicInteger()
        val candidate = AtomicReference<BookLoadResult.Ready?>()
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            loads.incrementAndGet()
            return repository.load(key, force, onProgress)
        }
        override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? {
            started.incrementAndGet(); inFlight.incrementAndGet()
            try {
                gate?.await()
                return repository.enrichSaved(accepted, allowNetwork = false).also { if (it != null) candidate.set(it) }
            } finally { inFlight.decrementAndGet() }
        }
    }
    companion object {
        private const val ARCHIVE = "quiet-matins/matins-accepted-unchanged.json"
        private const val TARGET = "matins-47b61ec862a4-1:8a745a35d11139c5:1"
        // Independent oracle transcribed from Astra-approved test/resources/russian-coverage-11/feoria-refrains-draft.json.
        private val APPROVED = listOf(
            "Припев: Сла́ва, Го́споди, Кресту́ Твоему́ честно́му." to "Припев: Слава, Господи, Твоему Кресту, достойному почитания.",
            "Припев: Святи́и му́ченицы, моли́те Бо́га о нас." to "Припев: Святые мученики, молитесь Богу о нас.",
            "Припев: Святы́й му́чениче Епимáше, моли́ Бо́га о на́с." to "Припев: Святой мученик Епимах, молись Богу о нас.")
    }
}
