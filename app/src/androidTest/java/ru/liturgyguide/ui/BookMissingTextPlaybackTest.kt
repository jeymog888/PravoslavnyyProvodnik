package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/**
 * Native reader regressions using a byte-for-byte copy of the actual JVM parser fixture
 * app/src/test/resources/book/liturgy-2026-11-11.html
 * SHA-256: ed04798ea75a895830737946b82cf54a7d44309c4ea58eb76fe3e564a3aad5b1
 * Its controls select 2026-11-11, full liturgy, church 30, main event 0, second event 1
 * Only the two explicitly labelled rearrangement fixtures change block order, never source prose
 * The clock stays controlled during playback, including the first assertion against the old UI
 */
@RunWith(AndroidJUnit4::class)
class BookMissingTextPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferenceName = "book-missing-text-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val mounted = mutableStateOf(true)
    private val parishId = "missing-text-test-parish"
    private val source by lazy { actualSource() }

    @After fun clean() {
        // Dispose the reader before deleting its UUID store, even after a failed playback assertion
        compose.mainClock.autoAdvance = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { mounted.value = false }
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun realLiturgyStopsBeforeItsFirstMissingHymnAndWaitsForAnExplicitContinuation() {
        val public = source.blocks.filterNot { it.isAltarText }
        val missingIndex = public.indexOfFirst { it.kind == BookBlockKind.LOCAL_VARIATION }
        assertTrue("The complete source must contain a public missing hymn after known text", missingIndex > 0)
        val missing = public[missingIndex]
        val before = public[missingIndex - 1]
        val nextPrayer = public.drop(missingIndex + 1).first {
            it.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE)
        }
        launch(source, before)
        val gapHeightBefore = compose.onNodeWithTag("book_block_${missing.id}").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        startPlay()
        advance(8_000)

        // Keep this FIRST after playback: the old reader is still playing in the following hymn
        compose.onAllNodesWithContentDescription(PAUSE).assertCountEquals(0)
        assertContinueControl()
        compose.onNodeWithTag(reasonTag(missing)).assertIsDisplayed()
        val gapHeightAfter = compose.onNodeWithTag("book_block_${missing.id}").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        assertTrue("The missing-text explanation must not resize its item when playback pauses",
            abs(gapHeightAfter - gapHeightBefore) <= 1f)
        val stoppedIndex = public.indexOfFirst { it.id == saved().blockId }
        assertTrue("A stop can retain the known predecessor's following space or the missing hymn",
            stoppedIndex in (missingIndex - 1)..missingIndex)
        assertTrue("Autoplay must not reach the prayer following the missing hymn",
            stoppedIndex < public.indexOf(nextPrayer))
        assertStaysStill(reasonTag(missing), CONTINUE)

        val next = nextVisibleAnchor(source, missing)
        compose.onNodeWithTag("book_play").performClick()
        advance(700)
        assertAt(next)
        assertNormalPause()
        assertStaysStill(next.displayTag, PLAY)

        // Continuing past the unknown hymn leaves the reader paused; the same footer can then play known text
        val continued = saved()
        val scrollBefore = compose.onNodeWithTag("book_content").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        startPlay()
        advance(64)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals(PAUSE)
        advance(1_500)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals(PAUSE)
        val scrollAfter = compose.onNodeWithTag("book_content").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("A separate Play action must move the visible known text", scrollAfter > scrollBefore)
        compose.onNodeWithTag("book_play").performClick()
        advance(700)
        assertNotEquals("A separate Play action must move known text normally", continued, saved())
    }

    @Test fun adjacentMissingHymnsNeedTwoActionsAndTheSecondActionLandsOnTheNextHeader() {
        val gaps = source.blocks.filter { !it.isAltarText && it.kind == BookBlockKind.LOCAL_VARIATION }
        assertEquals("The actual source contains the two temple hymn omissions", 2, gaps.size)
        val before = publicBlockBefore(gaps.first())
        val known = longestPublicPrayer()
        val used = (gaps + before + known).map { it.id }.toSet()
        val prefix = source.blocks.filter { !it.isAltarText && it.id !in used }
        val fixture = rearranged("Соседние пропуски", listOf(
            BookChapter("fixture-adjacent-before", "Тест: перед пропусками", FIXTURE_LABEL, prefix + before),
            BookChapter("fixture-adjacent-gaps", "Тест: два соседних пропуска", FIXTURE_LABEL, gaps),
            BookChapter("fixture-adjacent-after", "Тест: следующая глава", FIXTURE_LABEL, listOf(known))
        ))
        launch(fixture, before)
        startPlay()
        advance(8_000)
        compose.onAllNodesWithContentDescription(PAUSE).assertCountEquals(0)
        assertContinueControl()
        assertStaysStill(reasonTag(gaps.first()), CONTINUE)

        compose.onNodeWithTag("book_play").performClick()
        advance(700)
        assertAt(nextVisibleAnchor(fixture, gaps.first()))
        assertEquals(gaps.last().id, saved().blockId)
        assertContinueControl()
        assertStaysStill(reasonTag(gaps.last()), CONTINUE)

        compose.onNodeWithTag("book_play").performClick()
        advance(700)
        val next = nextVisibleAnchor(fixture, gaps.last())
        assertEquals(BookAnchorKind.HEADER, next.kind)
        assertAt(next)
        assertNormalPause()
        assertStaysStill(next.displayTag, PLAY)
    }

    @Test fun ordinaryHeadingsAndRubricsKeepMovingButTheFinalMissingHymnRequiresContinuationToEnd() {
        val missing = source.blocks.last { !it.isAltarText && it.kind == BookBlockKind.LOCAL_VARIATION }
        val known = longestPublicPrayer()
        val rubric = source.blocks.first { !it.isAltarText && it.kind == BookBlockKind.RUBRIC }
        val used = setOf(missing.id, known.id, rubric.id)
        val prefix = source.blocks.filter {
            !it.isAltarText && it.kind != BookBlockKind.LOCAL_VARIATION && it.id !in used
        }
        val fixture = rearranged("Последний пропуск", listOf(
            BookChapter("fixture-last-before", "Тест: перед последней главой", FIXTURE_LABEL, prefix + known),
            BookChapter("fixture-last-gap", "Тест: обычная рубрика и последний пропуск", FIXTURE_LABEL,
                listOf(rubric, missing))
        ))
        launch(fixture, known)
        startPlay()
        advance(8_000)
        compose.onAllNodesWithContentDescription(PAUSE).assertCountEquals(0)
        assertContinueControl()
        assertEquals("The ordinary heading and rubric must have been passed", "fixture-last-gap", saved().chapterId)
        assertTrue("Stopping may retain the rubric's following space, but never the earlier prayer",
            saved().blockId in setOf(rubric.id, missing.id))
        assertStaysStill(reasonTag(missing), CONTINUE)

        compose.onNodeWithTag("book_play").performClick()
        advance(700)
        val end = nextVisibleAnchor(fixture, missing)
        assertEquals(BookAnchorKind.END, end.kind)
        assertAt(end)
        assertNormalPause()
        assertStaysStill("book_end", PLAY)
    }

    private fun launch(book: ServiceBook, before: BookBlock) {
        assertNotEquals("Seed the known predecessor, never the missing hymn itself",
            BookBlockKind.LOCAL_VARIATION, before.kind)
        val chapter = book.chapters.single { before in it.blocks }
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parishId, before.id, chapter.id,
            fraction = .98f, mode = BookReadingMode.ORIGINAL, wordsPerMinute = 360, showAltar = false,
            followingGapFraction = 0f)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                GuideTheme(false) {
                    if (mounted.value) BookReaderScreen(book, parishId, store, 0, onBack = {}, onOpenUrl = {},
                        onChooseVersion = {}, onSettings = {})
                }
            }
        }
        advance(700)
        assertEquals(before.id, saved().blockId)
        assertNormalPause()
    }

    private fun startPlay() {
        assertNormalPause()
        compose.onNodeWithTag("book_play").performClick()
        // A predecessor at .98 can reach the omission in a single frame; do not require it to keep playing
    }

    private fun assertContinueControl() {
        compose.onNodeWithTag("book_play").assertIsDisplayed().assertContentDescriptionEquals(CONTINUE)
            .assertTextContains("Дальше")
        compose.onAllNodesWithContentDescription(CONTINUE).assertCountEquals(1)
    }

    private fun assertNormalPause() = compose.onNodeWithTag("book_play").assertContentDescriptionEquals(PLAY)

    private fun assertStaysStill(tag: String, description: String) {
        val stopped = saved()
        val top = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot().top.value
        advance(10_000)
        compose.onAllNodesWithContentDescription(PAUSE).assertCountEquals(0)
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals(description)
        assertEquals("Time alone must not change the saved semantic position", stopped, saved())
        val after = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot().top.value
        assertTrue("Time alone must not move the actual visible text: $top -> $after", abs(after - top) <= 1f)
    }

    private data class ExpectedAnchor(val chapter: BookChapter, val block: BookBlock?, val kind: BookAnchorKind) {
        val blockId get() = block?.id ?: chapter.blocks.first { !it.isAltarText }.id
        val displayTag get() = when (kind) {
            BookAnchorKind.END -> "book_end"
            BookAnchorKind.HEADER -> "book_block_$blockId"
            BookAnchorKind.BLOCK -> "book_block_$blockId"
        }
    }

    private fun nextVisibleAnchor(book: ServiceBook, missing: BookBlock): ExpectedAnchor {
        val visible = book.chapters.filter { chapter -> chapter.blocks.any { !it.isAltarText } }.flatMap { chapter ->
            listOf(ExpectedAnchor(chapter, null, BookAnchorKind.HEADER)) +
                chapter.blocks.filterNot { it.isAltarText }.map { ExpectedAnchor(chapter, it, BookAnchorKind.BLOCK) }
        }
        val index = visible.indexOfFirst { it.block?.id == missing.id }
        assertTrue("The missing hymn must be a visible source item", index >= 0)
        return visible.getOrNull(index + 1)
            ?: ExpectedAnchor(visible.last().chapter, visible.last().block, BookAnchorKind.END)
    }

    private fun assertAt(expected: ExpectedAnchor) {
        val actual = saved()
        assertEquals("Continue must land on exactly the next visible item", expected.kind, actual.anchorKind)
        assertEquals(expected.chapter.id, actual.chapterId)
        assertEquals(expected.blockId, actual.blockId)
        assertEquals(0f, actual.fraction, .001f)
        assertEquals(0f, actual.followingGapFraction, .001f)
        compose.onNodeWithTag(expected.displayTag).assertIsDisplayed()
        if (expected.kind == BookAnchorKind.HEADER)
            compose.onNodeWithTag("book_chapter", useUnmergedTree = true).assertTextEquals(expected.chapter.title)
    }

    private fun publicBlockBefore(block: BookBlock): BookBlock {
        val public = source.blocks.filterNot { it.isAltarText }
        return public[public.indexOf(block) - 1]
    }

    private fun longestPublicPrayer() = source.blocks.filter { !it.isAltarText && it.kind == BookBlockKind.PRAYER }
        .maxBy { BookAutoScroll.wordCount(it.original) }

    private fun rearranged(label: String, chapters: List<BookChapter>): ServiceBook {
        val fixture = source.copy(title = "$FIXTURE_LABEL: $label", sourceTitle = FIXTURE_LABEL,
            chapters = chapters, contentHash = "")
        assertTrue("Synthetic ordering must retain every chosen source block verbatim", fixture.blocks.all { block ->
            source.blocks.singleOrNull { it.id == block.id } == block
        })
        return fixture.copy(contentHash = ServiceBookIntegrity.contentHash(fixture)).also {
            assertTrue("The explicitly labelled fixture needs its own valid integrity hash", ServiceBookIntegrity.valid(it))
        }
    }

    private fun actualSource(): ServiceBook {
        // Instrumentation assets belong to the test APK; reading bookmarks belong to targetContext
        val bytes = instrumentation.context.assets.open("book/missing-text-liturgy-2026-11-11.html").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("Keep the actual HTML fixture byte-for-byte, including liturgical text", SOURCE_SHA256, hash)
        val document = Jsoup.parse(bytes.toString(Charsets.UTF_8))
        val key = ServiceBookKey("2026-11-11", WorshipKind.LITURGY, churchType = "30")
        return ServiceBookParser().parse(document, key, 1_794_348_000_000).also {
            assertTrue("Use the complete actual source, not a hand-written prayer-only book", it.blocks.size > 200)
            assertTrue(ServiceBookIntegrity.valid(it))
        }
    }

    private fun reasonTag(block: BookBlock) = "book_local_gap_${block.id}"
    private fun saved(): BookReadingPosition = requireNotNull(BookReadingStore(preferences).last(parishId))
    private fun advance(millis: Long) { compose.mainClock.advanceTimeBy(millis); compose.waitForIdle() }

    companion object {
        private const val SOURCE_SHA256 = "ed04798ea75a895830737946b82cf54a7d44309c4ea58eb76fe3e564a3aad5b1"
        private const val FIXTURE_LABEL = "Тестовая перестановка исходных фрагментов"
        private const val PLAY = "Прокручивать текст"
        private const val PAUSE = "Остановить прокрутку"
        private const val CONTINUE = "Продолжить после местного песнопения"
    }
}
