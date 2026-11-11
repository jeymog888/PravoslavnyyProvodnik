package ru.liturgyguide.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.*
import ru.liturgyguide.service.book.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Compose layout/controller with explicit synthetic speech callbacks, not real microphone quality. */
@RunWith(AndroidJUnit4::class)
class BookReadingUsabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun allReadingModeNamesFitAt320And360DpAcrossSystemFontSizes() {
        val width = mutableStateOf(320)
        val scale = mutableStateOf(1f)
        val selected = mutableStateOf(BookReadingMode.ORIGINAL)
        var menuOpens = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                GuideTheme(false) {
                    Box(Modifier.width(width.value.dp)) {
                        BookModePicker(selected.value, { selected.value = it }, { menuOpens++ })
                    }
                }
            }
        }
        for (dp in listOf(320, 360)) for (font in listOf(1f, 1.3f, 1.5f, 2f)) {
            compose.runOnIdle { width.value = dp; scale.value = font; selected.value = BookReadingMode.ORIGINAL }
            compose.waitForIdle()
            val menuShown = compose.onAllNodesWithTag("book_mode_menu").fetchSemanticsNodes().isNotEmpty()
            if (font == 1f) assertFalse("All three names must fit normally at $dp dp", menuShown)
            if (menuShown) {
                assertSingleUnclippedLine("book_mode_menu_label", "Оригинал")
                val before = menuOpens
                compose.onNodeWithTag("book_mode_menu").assertIsDisplayed().performClick()
                compose.runOnIdle { assertEquals(before + 1, menuOpens) }
                for (mode in BookReadingMode.entries) {
                    assertSingleUnclippedLine("book_mode_menu_label_${mode.name.lowercase()}", mode.title)
                }
                compose.onNodeWithTag("book_mode_russian").performClick()
                compose.runOnIdle { assertEquals(BookReadingMode.RUSSIAN, selected.value) }
                assertSingleUnclippedLine("book_mode_menu_label", "Русский")
            } else {
                val bounds = BookReadingMode.entries.map { mode ->
                    assertSingleUnclippedLine("book_mode_label_${mode.name.lowercase()}", mode.title)
                    compose.onNodeWithTag("book_mode_${mode.name.lowercase()}").assertIsDisplayed().getBoundsInRoot()
                }
                bounds.forEach { assertTrue(it.right - it.left >= 48.dp); assertTrue(it.bottom - it.top >= 48.dp) }
                assertTrue(bounds[1].left - bounds[0].right >= 12.dp)
                assertTrue(bounds[2].left - bounds[1].right >= 12.dp)
                compose.onNodeWithTag("book_mode_russian").performClick().assertIsSelected()
                compose.runOnIdle { assertEquals(BookReadingMode.RUSSIAN, selected.value) }
            }
        }
    }

    @Test fun newBookShowsPartialWhileListeningAndDonePreservesFinalWithoutAutomaticJump() {
        val fake = FakeSpeech()
        var selected: BookSearchHit? = null
        launchSheet(fake) { selected = it }
        compose.onNodeWithTag("book_search_query").performTextInput("мой запрос")
        closeSoftKeyboard()
        compose.onNodeWithTag("book_listen").performScrollTo().assertTextEquals("Послушать службу").performClick()
        compose.runOnIdle { assertEquals(1, fake.backend.starts) }
        compose.onNodeWithTag("book_listening_status").assertTextEquals("Включаю микрофон")
        compose.onNodeWithTag("book_microphone_level").assertDoesNotExist()
        compose.runOnIdle {
            fake.backend.events!!.ready()
            fake.backend.events!!.beginning()
            fake.backend.events!!.level(5f)
            fake.backend.events!!.partial("Иже")
        }
        compose.onNodeWithTag("book_listening_status").assertTextEquals("Слушаю службу")
        compose.onNodeWithTag("book_listening_partial").performScrollTo().assertTextEquals("Иже")
        compose.onNodeWithTag("book_search_query").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("мой запрос")))
        compose.onNodeWithTag("book_listen").performScrollTo().assertTextEquals("Готово").performClick()
        compose.runOnIdle {
            assertEquals(1, fake.backend.finishes)
            assertEquals(0, fake.backend.releases)
            assertEquals(SpeechStatus.PROCESSING, fake.guide!!.state.status)
            assertNull(selected)
        }
        compose.onNodeWithTag("book_listening_status").performScrollTo().assertTextEquals("Распознаю фразу")
        compose.onNodeWithTag("book_listening_partial").performScrollTo().assertTextEquals("Иже")
        compose.onNodeWithTag("book_microphone_level").assertDoesNotExist()
        compose.runOnIdle { fake.backend.events!!.alternatives(listOf("ниже херувимы", "Иже Херувимы")) }
        awaitQuery("Иже Херувимы")
        compose.onNodeWithTag("book_search_query").performScrollTo().assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Иже Херувимы")))
        compose.runOnIdle { assertNull(selected); assertEquals(1, fake.backend.releases) }
        // Use the actual restart action. Calling guide.start() directly would miss a stuck RESULT state.
        compose.onNodeWithTag("book_listen").performScrollTo().assertTextEquals("Послушать еще раз").performClick()
        compose.runOnIdle { assertEquals(2, fake.backend.starts); assertEquals(SpeechStatus.STARTING, fake.guide!!.state.status) }
        compose.onNodeWithTag("book_listening_partial").assertDoesNotExist()
        compose.runOnIdle { fake.backend.events!!.ready(); fake.backend.events!!.result("Иже Херувимы") }
        awaitQuery("Иже Херувимы")
        awaitSearch()
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_hit_cherubim"))
        compose.onNodeWithTag("book_hit_cherubim").performClick()
        compose.runOnIdle { assertEquals("cherubim", selected?.blockId) }
    }

    @Test fun cancelledAndBackgroundSpeechCallbacksCannotReplaceManualQueryOrOpenTheBook() {
        val fake = FakeSpeech()
        var selections = 0
        launchSheet(fake) { selections++ }
        compose.onNodeWithTag("book_search_query").performTextInput("мой запрос")
        closeSoftKeyboard()
        compose.onNodeWithTag("book_listen").performScrollTo().performClick()
        compose.runOnIdle { fake.backend.events!!.ready(); fake.backend.events!!.partial("Иже") }
        val old = fake.backend.events!!
        compose.onNodeWithTag("book_listening_cancel").performScrollTo().performClick()
        compose.runOnIdle { old.result("Иже Херувимы"); old.level(9f); old.beginning() }
        compose.onNodeWithTag("book_search_query").performScrollTo().assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("мой запрос")))
        compose.runOnIdle { assertEquals(SpeechStatus.IDLE, fake.guide!!.state.status); assertEquals(0, selections) }
        compose.onNodeWithTag("book_listen").performScrollTo().performClick()
        compose.runOnIdle { fake.backend.events!!.ready() }
        val background = fake.backend.events!!
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { background.result("Иже Херувимы"); background.level(8f) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag("book_search_query").performScrollTo().assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("мой запрос")))
        compose.runOnIdle { assertEquals(0, selections); assertEquals(2, fake.backend.releases) }
    }

    @Test fun typingDuringSlowAlternativeLookupKeepsManualWordsWhenOldLookupFinishes() {
        val fake = FakeSpeech()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var selections = 0
        launchSheet(fake, speechQuerySelector = { _, _ -> withContext(NonCancellable) {
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                "Иже Херувимы"
            } finally { returned.countDown() }
        } }) { selections++ }
        try {
            compose.onNodeWithTag("book_listen").performScrollTo().performClick()
            compose.runOnIdle { fake.backend.events!!.ready(); fake.backend.events!!.alternatives(listOf("ошибка", "Иже Херувимы")) }
            assertTrue("The background lookup must actually be in flight", entered.await(5, TimeUnit.SECONDS))
            compose.onNodeWithTag("book_search_query").performScrollTo().assertIsEnabled().performTextReplacement("мой ручной запрос")
            closeSoftKeyboard()
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            awaitSearch()
            compose.onNodeWithTag("book_search_query").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("мой ручной запрос")))
            compose.runOnIdle { assertEquals(0, selections) }
        } finally { release.countDown() }
    }

    private fun awaitQuery(expected: String) {
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("book_search_query").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == expected
        }
    }

    private fun awaitSearch() {
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("book_search_sheet").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "Поиск готов"
        }
    }

    private fun assertSingleUnclippedLine(tag: String, expected: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().assertTextEquals(expected)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        try {
            assertTextFitsLayout(layout, expected, singleLine = true)
        } catch (failure: AssertionError) {
            val target = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "v09-reading-overflow.png")
            target.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            throw failure
        }
    }

    private fun launchSheet(fake: FakeSpeech, speechQuerySelector: (suspend (ServiceBook, List<String>) -> String)? = null,
        onSelect: (BookSearchHit) -> Unit) {
        val key = ServiceBookKey("2026-11-11")
        val book = ServiceBook(key = key, title = "Тестовая книга", sourceUrl = key.sourceUrl(), sourceTitle = "Тест",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", "Херувимская песнь", "Тестовое объяснение", listOf(
                BookBlock("cherubim", BookBlockKind.PRAYER, "Хор", "И́же Херуви́мы та́йно образу́юще")))), contentHash = "fixture")
        compose.setContent {
            GuideTheme(false) {
                Box(Modifier.width(320.dp).heightIn(max = 720.dp)) {
                    BookSearchSheet(book, false, BookReadingMode.ORIGINAL, onSelect, fake.factory,
                        hasMicrophonePermission = { true }, speechQuerySelector = speechQuerySelector)
                }
            }
        }
        compose.waitForIdle()
    }

    private class FakeSpeech {
        val backend = FakeBackend()
        var guide: SpeechGuide? = null
        val factory: SpeechGuideFactory = { _, changed ->
            SpeechGuide(backend, SpeechScheduler { _, _ -> SpeechCancellation { } }, changed).also { guide = it }
        }
    }

    private class FakeBackend : SpeechBackend {
        override val apiLevel = 31
        var events: SpeechEvents? = null
        var finishes = 0
        var releases = 0
        var starts = 0
        override fun isAvailable() = true
        override fun hasPermission() = true
        override fun create(events: SpeechEvents): SpeechAttempt {
            this.events = events
            return object : SpeechAttempt {
                override fun checkSupport(result: (SpeechSupport) -> Unit, error: (Int) -> Unit) = Unit
                override fun start() { starts++ }
                override fun finish() { finishes++ }
                override fun release() { releases++ }
            }
        }
    }
}
