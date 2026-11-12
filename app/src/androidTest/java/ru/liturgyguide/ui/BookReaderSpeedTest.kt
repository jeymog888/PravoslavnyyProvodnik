package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import kotlin.math.abs

/** Measures native text movement at the same saved paragraph/offset, not a speed helper. */
@RunWith(AndroidJUnit4::class)
class BookReaderSpeedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "book-reader-speed-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val mounted = mutableStateOf(false)
    private val parish = "reader-speed-parish"
    private val book = fixture()

    @After fun clean() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { mounted.value = false }
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        context.deleteSharedPreferences(name)
    }

    @Test fun thirtyAndOneHundredTwentyWordsPerMinuteProduceFourfoldActualMotion() {
        compose.setContent {
            val density = LocalDensity.current
            // This inline reader uses one fixed font layout in both runs. No dialog,
            // recognizer, network, system setting or alternative playback engine.
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                GuideTheme(false) {
                    if (mounted.value) Box(Modifier.fillMaxSize()) {
                        BookReaderScreen(book, parish, store, 0, onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {})
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        mountAt(30)
        val slowStart = paragraphTop()
        val bounds = compose.onNodeWithTag("book_block_long").getUnclippedBoundsInRoot()
        val height = (bounds.bottom - bounds.top).value
        val words = book.blocks.single().original.trim().split(Regex("\\s+")).size
        assertTrue("The real paragraph layout must exercise the old 5dp/s floor",
            height * 30f / (words * 60f) < 4.5f)
        val slow = measureMotion()

        // Dispose before writing the seed, so an old lifecycle save cannot overwrite it.
        mountAt(120)
        assertTrue("Both rates must start at the same actual visible line", abs(paragraphTop() - slowStart) <= 1f)
        val fast = measureMotion()
        assertTrue("30 words/min must still move the text", slow > 4f)
        assertTrue("The former 5dp/s minimum must not force a faster pace", slow < 45f)
        assertTrue("120 words/min should move four times farther in the same time: slow=$slow fast=$fast",
            fast / slow in 3.6f..4.4f)
    }

    @Test fun shortRepliesAdvanceAtTheirSpokenLengthRatherThanTenWordsEach() {
        val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS)
        val blocks = (0 until 40).map { index -> BookBlock("reply-$index", BookBlockKind.PRAYER, "Хор",
            if (index % 2 == 0) "Ами́нь." else "Го́споди, поми́луй.") }
        val source = ServiceBook(key = key, title = "Утреня", sourceUrl = key.sourceUrl(), sourceTitle = "Тест",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("litany", "Ектения", "Ответы хора", blocks)), contentHash = "")
        val replies = source.copy(contentHash = ServiceBookIntegrity.contentHash(source))
        check(store.save(BookReadingPosition(key, replies.contentHash, parish, "reply-0", "litany", wordsPerMinute = 180)))
        compose.setContent {
            GuideTheme(false) { if (mounted.value) BookReaderScreen(replies, parish, store, 0,
                onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {}) }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { mounted.value = true }
        advance(700)
        compose.onNodeWithTag("book_play").performClick()
        advance(4_000)
        compose.onNodeWithTag("book_play").performClick()
        advance(700)
        val index = requireNotNull(store.last(parish)).blockId.removePrefix("reply-").toInt()
        assertTrue("At 180 words/min several one/two-word responses must pass in four seconds: $index", index >= 5)
        assertTrue("The paragraph has not been skipped to its end", index < 20)
    }

    private fun mountAt(wordsPerMinute: Int) {
        compose.runOnUiThread { mounted.value = false }
        advance(64)
        compose.runOnUiThread {
            check(store.save(BookReadingPosition(book.key, book.contentHash, parish, "long", "prayer",
                fraction = .25f, mode = BookReadingMode.ORIGINAL, wordsPerMinute = wordsPerMinute)))
            mounted.value = true
        }
        advance(700)
        val saved = requireNotNull(store.last(parish))
        assertEquals("long", saved.blockId)
        assertEquals(.25f, saved.fraction, .001f)
        assertEquals(wordsPerMinute, saved.wordsPerMinute)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    }

    private fun measureMotion(): Float {
        compose.onNodeWithTag("book_play").performClick()
        advance(64)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Остановить прокрутку")
        val before = paragraphTop()
        advance(10_000)
        val moved = before - paragraphTop()
        compose.onNodeWithTag("book_play").performClick()
        advance(700)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
        assertEquals("long", requireNotNull(store.last(parish)).blockId)
        return moved
    }

    private fun paragraphTop() = compose.onNodeWithTag("book_block_long").assertIsDisplayed().getUnclippedBoundsInRoot().top.value
    private fun advance(millis: Long) { compose.mainClock.advanceTimeBy(millis); compose.waitForIdle() }

    private fun fixture(): ServiceBook {
        val key = ServiceBookKey("2026-11-11")
        val block = BookBlock("long", BookBlockKind.PRAYER, null,
            "И да будет мир во всем мире и в нас. ".repeat(200))
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Тестовый источник",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("prayer", "Молитва о мире", "Просим Бога о мире", listOf(block))), contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
}
