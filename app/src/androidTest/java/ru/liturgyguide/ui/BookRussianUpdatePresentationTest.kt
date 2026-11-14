package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID

/** Synthetic UI policy cases. Durable historical production flow has a separate test. */
@RunWith(Parameterized::class)
class BookRussianUpdatePresentationTest(private val dark: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "russian-presentation-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(prefix, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val old = fixture()
    private val translatedA = translated(setOf("a"))
    private val translatedB = translated(setOf("b"))
    private val token = mutableStateOf<Any>(Any())
    private val proof = mutableStateOf<BookRussianUpdateDescriptor?>(null)
    private val available = mutableStateOf(true)
    private val mounted = mutableStateOf(true)
    private var accepts = 0
    private var contentSet = false
    private fun descriptor(book: ServiceBook, identity: String = "occurrence-a") =
        BookRussianUpdateDescriptor.create(old, BookLoadResult.Ready(book, true), true, token.value, identity)

    @After fun clean() {
        if (contentSet) {
            // Playback schedules frames continuously. Dispose under a controlled
            // test clock even if an assertion failed while it was still running.
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { mounted.value = false }
            compose.mainClock.advanceTimeBy(64)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(prefix)
    }

    @Test fun genericTopOfferDoesNotGrowForLateProofAndContextWithdrawalRetainsBounds() {
        mount()
        waitReader()
        compose.onNodeWithTag("book_reader_settings").performClick()
        compose.onNodeWithTag("book_accept_update").assertExists()
        val generic = optionsBounds()
        compose.runOnIdle { proof.value = descriptor(translatedA) }; settle()
        compose.onNodeWithTag("book_update_russian_context").assertDoesNotExist()
        sameBounds(generic, optionsBounds())
        compose.dismissBookUpdateOptions()
        waitFor("book_russian_update_notice")
        compose.onNodeWithTag("book_russian_notice_action").performClick()
        compose.onNodeWithTag("book_update_russian_context").assertIsDisplayed()
        val contextual = optionsBounds()
        compose.runOnIdle { proof.value = descriptor(translatedB) }; settle()
        compose.onNodeWithTag("book_update_russian_context").assertDoesNotExist()
        sameBounds(contextual, optionsBounds())
        compose.runOnIdle { proof.value = null; available.value = false }; settle()
        compose.onNodeWithTag("book_update_unavailable").assertIsNotEnabled()
        sameBounds(contextual, optionsBounds(available = false))
        compose.runOnIdle { assertEquals(0, accepts) }
    }

    @Test fun changingTheMeasuredReadingTargetRemovesTheClaimWithoutMovingOptions() {
        proof.value = descriptor(translatedA)
        mount(); waitReader(); waitFor("book_russian_update_notice")
        compose.onNodeWithTag("book_russian_notice_action").performClick()
        compose.onNodeWithTag("book_update_russian_context").assertIsDisplayed()
        val before = optionsBounds()
        // Simulate a late programmatic target change while the real dialog is open.
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_block_b"))
        compose.onNodeWithTag("book_block_b").performScrollTo()
        settle()
        compose.onNodeWithTag("book_update_russian_context").assertDoesNotExist()
        sameBounds(before, optionsBounds())
    }

    @Test fun otherBlockGenericAndMissingMetadataDoNotPromiseThisFragment() {
        proof.value = descriptor(translatedB)
        mount(); waitReader()
        assertNoNotice()
        compose.runOnIdle { proof.value = null }; settle()
        assertNoNotice()
        compose.runOnIdle { proof.value = descriptor(translatedA) }
        waitFor("book_russian_update_notice")
        compose.onNodeWithText("Есть русский перевод").assertIsDisplayed()
        assertEquals(old.contentHash, requireNotNull(store.last("parish")).contentHash)
    }

    @Test fun shownPairSurvivesSavedStateRestoreAndDoesNotRepeatOnLanguageToggles() {
        proof.value = descriptor(translatedA)
        val restoration = StateRestorationTester(compose)
        mount(restoration = restoration); waitReader(); waitFor("book_russian_update_notice")
        compose.onNodeWithTag("book_russian_notice_dismiss").performClick(); settle()
        selectMode("original"); selectMode("russian"); settle()
        assertNoNotice()
        restoration.emulateSavedInstanceStateRestore(); settle()
        assertNoNotice()
        compose.onNodeWithTag("book_reader_settings").performClick()
        compose.onNodeWithTag("book_update_russian_context").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, accepts) }
    }

    @Test fun oldNoticeActionCannotOpenOptionsAfterWithdrawalOwnerReplacementOrUnmount() {
        proof.value = descriptor(translatedA)
        mount(); waitReader(); waitFor("book_russian_update_notice")
        val held = action("book_russian_notice_action")
        compose.runOnIdle { proof.value = null; available.value = false }; settle()
        compose.runOnIdle { held() }; settle()
        compose.onNodeWithTag("book_options").assertDoesNotExist()
        compose.runOnIdle {
            token.value = Any(); available.value = true
            proof.value = descriptor(translatedA, "occurrence-b")
        }
        waitFor("book_russian_update_notice")
        compose.runOnIdle { held() }; settle()
        compose.onNodeWithTag("book_options").assertDoesNotExist()
        val current = action("book_russian_notice_action")
        compose.onNodeWithTag("book_russian_notice_action").performClick()
        compose.onNodeWithTag("book_update_russian_context").assertIsDisplayed()
        compose.runOnIdle { mounted.value = false }; settle()
        compose.runOnIdle { current() }; settle()
        compose.onNodeWithTag("book_options").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, accepts) }
    }

    @Test fun shortReaderDefersInformationAndKeepsExistingSettingsAndExplicitOffer() {
        proof.value = descriptor(translatedA)
        mount(height = 360); waitReader()
        assertNoNotice()
        listOf("book_find", "book_play", "book_microphone", "book_reader_settings").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        // A semantic click bypasses hit testing. Use actual touch here to catch
        // an invisible deferred overlay intercepting the existing controls.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("book_play").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Остановить прокрутку")
        compose.onNodeWithTag("book_play").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("book_reader_settings").performTouchInput { click(center) }
        compose.onNodeWithTag("book_accept_update").performScrollTo().assertIsDisplayed()
    }

    @Test fun playbackAndPausedActivityDeferAnUnconsumedOfferUntilSafeReadingReturns() {
        mount(); waitReader()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("book_play").performClick()
        compose.runOnUiThread { proof.value = descriptor(translatedA) }; settle()
        assertNoNotice()
        compose.onNodeWithTag("book_play").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
        waitFor("book_russian_update_notice")
        compose.onNodeWithTag("book_russian_notice_dismiss").performClick(); settle()
        val changed = translatedA.copy(selectedEventTitle = "Новая fixture metadata").let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.onActivity { proof.value = descriptor(changed) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("book_russian_update_notice")
        compose.onNodeWithText("Есть русский перевод").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, accepts) }
    }

    @Test fun errorPreemptsAnActuallyVisibleInformationNoticeAndInvalidatesItsHeldAction() {
        val owner = Any()
        val descriptor = requireNotNull(BookRussianUpdateDescriptor.create(old, BookLoadResult.Ready(translatedA, true), true, owner, "priority"))
        var revision by mutableIntStateOf(0)
        var reviews = 0
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, 2f)) {
                GuideTheme(dark) {
                    var viewport by remember { mutableStateOf<Rect?>(null) }
                    var textBounds by remember { mutableStateOf<Rect?>(null) }
                    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val target = if (textBounds != null && layout != null) BookRussianNoticeTarget("a", Rect(
                        textBounds!!.left, textBounds!!.top + layout!!.getLineTop(0), textBounds!!.right,
                        textBounds!!.top + layout!!.getLineBottom(0))) else null
                    if (mounted.value) Box(Modifier.size(320.dp, 640.dp).onGloballyPositioned { viewport = it.boundsInRoot() }) {
                        Text(old.blocks.first().original, Modifier.padding(24.dp).onGloballyPositioned {
                            val point = it.positionInRoot()
                            textBounds = Rect(point.x, point.y, point.x + it.size.width, point.y + it.size.height)
                        }, onTextLayout = { layout = it })
                        Box(Modifier.align(Alignment.BottomCenter)) {
                            BookUpdateNoticeHost(owner, old.key, old.contentHash, owner, descriptor, target, viewport,
                                visible = true, allowInformation = true, updateAvailable = true,
                                errorRevision = revision, errorMessage = "Ошибка принятия обновления",
                                onReview = { reviews++ })
                        }
                    }
                }
            }
        }
        contentSet = true
        waitFor("book_russian_update_notice")
        val held = action("book_russian_notice_action")
        compose.runOnIdle { revision = 1 }
        // An indefinite/FIFO wait behind information would violate this short error deadline.
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Ошибка принятия обновления").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ошибка принятия обновления").assertIsDisplayed()
        compose.onNodeWithTag("book_russian_update_notice").assertDoesNotExist()
        compose.runOnIdle { held(); assertEquals(0, reviews) }
    }

    private fun mount(height: Int = 640, restoration: StateRestorationTester? = null) {
        val chapter = old.chapters.first()
        assertTrue(store.save(BookReadingPosition(old.key, old.contentHash, "parish", "a", chapter.id,
            fraction = .1f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)))
        val content: @Composable () -> Unit = {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, 2f)) {
                GuideTheme(dark) { if (mounted.value) Box(Modifier.size(320.dp, height.dp)) {
                    BookReaderScreen(old, "parish", store, 0, onBack = {}, onOpenUrl = {}, onChooseVersion = {}, onSettings = {},
                        onAcceptUpdate = if (available.value) ({ _: BookReadingPosition -> accepts++; false }) else null,
                        russianUpdate = proof.value, russianUpdateOwner = token.value)
                } }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        contentSet = true
    }
    private fun selectMode(name: String) {
        compose.onNodeWithTag("book_mode_menu").performClick()
        compose.onNodeWithTag("book_mode_${name}").performClick(); settle()
    }
    private fun optionsBounds(available: Boolean = true): Map<String, DpRect> {
        compose.onNodeWithTag("book_refresh").performScrollTo()
        val button = if (available) "book_accept_update" else "book_update_unavailable"
        return mapOf("offline" to compose.onNodeWithTag("book_options_offline").getUnclippedBoundsInRoot(),
            "refresh" to compose.onNodeWithTag("book_refresh").getUnclippedBoundsInRoot(),
            "offer" to compose.onNodeWithTag("book_update_offer").getUnclippedBoundsInRoot(),
            "accept" to compose.onNodeWithTag(button).getUnclippedBoundsInRoot(),
            "viewport" to compose.onNodeWithTag("book_options_viewport").getUnclippedBoundsInRoot())
    }
    private fun sameBounds(expected: Map<String, DpRect>, actual: Map<String, DpRect>) {
        expected.forEach { (name, value) ->
            val next = actual.getValue(name)
            assertEquals(name, value.left.value, next.left.value, .5f); assertEquals(name, value.top.value, next.top.value, .5f)
            assertEquals(name, value.right.value, next.right.value, .5f); assertEquals(name, value.bottom.value, next.bottom.value, .5f)
        }
    }
    private fun action(tag: String) = requireNotNull(compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitReader() { waitFor("book_content"); settle() }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(650); compose.waitForIdle() }
    private fun assertNoNotice() {
        repeat(5) {
            compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
            compose.onNodeWithTag("book_russian_update_notice").assertDoesNotExist()
        }
    }
    private fun fixture(): ServiceBook {
        val key = ServiceBookKey("2026-11-13")
        val a = BookBlock("a", BookBlockKind.PRAYER, null, "Исходный текст первого проверяемого фрагмента. ".repeat(100))
        val b = BookBlock("b", BookBlockKind.PRAYER, null, "Исходный текст второго проверяемого фрагмента. ".repeat(100))
        val hidden = List(14) { index -> BookBlock("private-${index}", BookBlockKind.PRAYER, null,
            "Синтетическая алтарная строка для проверки ${index}.", isAltarText = true) }
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "UI fixture",
            fetchedAtMillis = 1, selectedEventTitle = null, chapters = listOf(BookChapter("chapter", "Проверка", "", listOf(a, b) + hidden)),
            contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }
    private fun translated(ids: Set<String>): ServiceBook {
        val source = BookSource("Fixture", "https://example.org/translation")
        val book = old.copy(chapters = old.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map {
            if (it.id in ids) it.copy(russian = "Русский перевод проверяемого фрагмента. ".repeat(100), translationSource = source) else it
        }) }, sources = listOf(source))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}")
        fun themes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}
