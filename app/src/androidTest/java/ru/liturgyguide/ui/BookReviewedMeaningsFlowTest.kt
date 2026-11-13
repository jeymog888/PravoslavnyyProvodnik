package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.service.book.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** A current explanation is app help; the accepted original and Russian edition stay exact. */
@RunWith(AndroidJUnit4::class)
class BookReviewedMeaningsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "reviewed-meanings-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = mutableStateOf(BookReadingStore(preferences))
    private val directory = File(context.cacheDir, name)
    private val cache = FileBookCache(directory)
    private val archive = instrumentation.context.assets.open(ARCHIVE).use { it.readBytes() }
    private val old = Gson().fromJson(archive.toString(Charsets.UTF_8), ServiceBook::class.java)
    private val book = mutableStateOf(old)
    private val mounted = mutableStateOf(true)
    private val parish = "reviewed-meanings-isolated-parish"
    private var launched = false

    @After fun clean() {
        if (launched) {
            compose.runOnUiThread { mounted.value = false }
            settle()
        }
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun currentPraisesExplanationAppearsInAnOldBookWithoutReplacingItsTextsEditionOrReadingAnchor() {
        assertEquals("c1a622cb4fad548172bf44179d0c34433cff89a6e7b377fb8a8d14b3adbe7ca1", sha(archive))
        assertEquals("bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", old.contentHash)
        assertTrue(ServiceBookIntegrity.valid(old))
        val chapter = old.chapters.single { it.title == "Хвалитны" }
        val target = chapter.blocks.single { it.id == TARGET }
        assertEquals(BookBlockKind.PRAYER, target.kind)
        assertNotNull(target.russian)
        assertTrue(chapter.meaning.contains("чередуются"))
        assertTrue(cache.write(old))
        val edition = File(directory, "${BookText.sha256(old.key.canonical()).take(32)}-${old.contentHash}.json")
        val oldDiskBytes = edition.readBytes()
        val originalJson = Gson().toJson(old)
        assertTrue(store.value.save(BookReadingPosition(old.key, old.contentHash, parish, target.id, chapter.id,
            fraction = .21f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)))
        compose.setContent { GuideTheme(false) {
            if (mounted.value) BookReaderScreen(book.value, parish, store.value, 0,
                onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {})
        } }
        launched = true
        settle()
        compose.onNodeWithTag("book_text_$TARGET").assertIsDisplayed().assertTextEquals(target.russian!!)
        val before = saved()
        compose.onNodeWithTag("book_mode_meaning").performClick()
        settle()
        compose.onAllNodesWithText("Объяснение Феории").onFirst().assertExists()
        compose.onNodeWithText(REVIEWED_PRAISES).assertIsDisplayed()
        assertAnchor(before, BookReadingMode.MEANING)
        for (mode in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN)) {
            compose.onNodeWithTag("book_mode_${mode.name.lowercase()}").performClick()
            settle()
            compose.onNodeWithTag("book_text_$TARGET").assertIsDisplayed()
                .assertTextEquals(if (mode == BookReadingMode.RUSSIAN) target.russian!! else target.original)
            assertAnchor(before, mode)
        }
        assertEquals(originalJson, Gson().toJson(old))
        assertArrayEquals(oldDiskBytes, edition.readBytes())
        assertArrayEquals(archive, instrumentation.context.assets.open(ARCHIVE).use { it.readBytes() })

        // Exact disk edition and a new store, remounted in this process; not a cold Main claim.
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        val exact = requireNotNull(FileBookCache(directory).readVersion(old.key, old.contentHash))
        assertEquals(old, exact)
        compose.runOnIdle { book.value = exact; store.value = BookReadingStore(preferences); mounted.value = true }
        settle()
        assertAnchor(before, BookReadingMode.RUSSIAN)
        compose.onNodeWithTag("book_mode_meaning").performClick()
        settle()
        compose.onNodeWithText(REVIEWED_PRAISES).assertIsDisplayed()
        assertAnchor(before, BookReadingMode.MEANING)
        assertArrayEquals(oldDiskBytes, edition.readBytes())
        assertEquals(originalJson, Gson().toJson(exact))
    }

    private fun saved() = requireNotNull(store.value.positionFor(old.key, parish))
    private fun assertAnchor(before: BookReadingPosition, mode: BookReadingMode) {
        val current = saved()
        assertEquals(old.contentHash, current.contentHash)
        assertEquals(before.chapterId, current.chapterId)
        assertEquals(before.blockId, current.blockId)
        assertEquals(before.wordsPerMinute, current.wordsPerMinute)
        assertEquals(before.fraction, current.fraction, .015f)
        assertEquals(mode, current.mode)
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(750); compose.waitForIdle() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object {
        const val ARCHIVE = "quiet-matins/matins-accepted-unchanged.json"
        const val TARGET = "matins-3fc31903f90c-1:2b8fc8ba1f184095:1"
        const val REVIEWED_PRAISES = "Звучат хвалитные псалмы с призывом \"Хвалите Господа\". Небо, земля и всё живое призываются славить Творца. В зависимости от дня к псалмам добавляются стихиры - песнопения праздника или святых"
    }
}
