package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.io.File
import java.util.UUID

/** Compact real-reader gates for two independent untouched historical source keys. */
@RunWith(Parameterized::class)
class BookLegacyQuietFamilyFlowTest(private val asset: String, private val rawSha: String,
    private val acceptedHash: String) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "legacy-quiet-family-${UUID.randomUUID()}"
    private val store = BookReadingStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    private val directory = File(context.cacheDir, name)
    private val mounted = mutableStateOf(true)
    private var contentSet = false

    @After fun clean() {
        if (contentSet) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { mounted.value = false }
            compose.mainClock.advanceTimeBy(64)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(name); directory.deleteRecursively()
    }

    @Test fun genuineLegacyDefaultRevealAndOldQuietCursorRestoreUseTheSameAcceptedBook() {
        val bytes = instrumentation.context.assets.open("quiet-matins/$asset").use { it.readBytes() }
        assertEquals(rawSha, BookText.sha256(bytes.toString(Charsets.UTF_8)))
        val book = Gson().fromJson(bytes.toString(Charsets.UTF_8), ServiceBook::class.java)
        assertEquals(acceptedHash, book.contentHash)
        assertTrue(ServiceBookIntegrity.valid(book))
        val scope = BookVisibilityScope.of(book)
        val group = requireNotNull(scope.quietGroup)
        val chapter = book.chapters.single { it.id == group.chapterId }
        val sourceGroup = chapter.blocks.subList(18, 54)
        assertEquals(sourceGroup.map { it.id }.toSet(), group.blockIds)
        assertEquals(36, sourceGroup.size)
        assertTrue(sourceGroup.all { it.kind == BookBlockKind.PRAYER && !it.isAltarText })
        val cache = FileBookCache(directory)
        assertTrue(cache.write(book))
        val before = directory.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(directory).path to BookText.sha256(it.readText()) }
        val parish = "family-fixture"
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parish, group.introductionId, chapter.id)))
        compose.setContent {
            GuideTheme(false) { if (mounted.value) BookReaderScreen(book, parish, store, 0,
                onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {}) }
        }
        contentSet = true
        settle()
        assertFalse(requireNotNull(store.positionFor(book.key, parish)).showAltar)
        group.blockIds.forEach { compose.onNodeWithTag("book_block_$it").assertDoesNotExist() }
        compose.onNodeWithTag("book_quiet_toggle").performScrollTo().assertIsDisplayed().performClick()
        settle()
        assertTrue(requireNotNull(store.positionFor(book.key, parish)).showAltar)
        val target = sourceGroup[1]
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_text_${target.id}"))
        compose.onNodeWithTag("book_text_${target.id}").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        val old = BookReadingPosition(book.key, book.contentHash, parish, target.id, chapter.id,
            fraction = .31f, showAltar = false)
        assertTrue(store.save(old))
        assertEquals(book, FileBookCache(directory).readVersion(book.key, book.contentHash))
        compose.runOnIdle { mounted.value = true }; settle()
        val restored = requireNotNull(store.positionFor(book.key, parish))
        assertEquals(target.id, restored.blockId); assertEquals(old.fraction, restored.fraction, .015f)
        assertEquals(book.contentHash, restored.contentHash); assertTrue(restored.showAltar)
        compose.onNodeWithTag("book_text_${target.id}").assertIsDisplayed()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        assertEquals(before, directory.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(directory).path to BookText.sha256(it.readText()) })
        assertArrayEquals(bytes, instrumentation.context.assets.open("quiet-matins/$asset").use { it.readBytes() })
        assertTrue(sourceGroup.all { it.kind == BookBlockKind.PRAYER && !it.isAltarText })
        // New composition/cache instances are tested; Android process restart is a separate gate.
    }

    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(750); compose.waitForIdle() }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(arrayOf("legacy-daily-2026-11-13-church30.json",
            "c7a8ea6f9915b4c60e33a6d709e5df45c19e22c813fcec500277c5f1648dff98",
            "46b462096ae0f952529eba8a3395a060684dd91af6a6a34187519d2d7d3e0689"),
            arrayOf("legacy-ranked-2026-11-18-church34.json",
                "427e8a5898de734a11ccb025628d010050b5bb8cad787abd561d111d5be125fe",
                "91d7a5d38eff1409649f83d2d6522856e9e2b76f20dfa0492ecc6d62c0082a5d"))
    }
}
