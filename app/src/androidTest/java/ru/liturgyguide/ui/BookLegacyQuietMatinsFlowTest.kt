package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.UUID
import java.io.File
import kotlin.math.abs

/** Actual PRAYER-label archive and real reader. No claim about a parish's performed order. */
@RunWith(AndroidJUnit4::class)
class BookLegacyQuietMatinsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "actual-quiet-matins-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(prefs)
    private val mounted = mutableStateOf(true)
    private val dark = mutableStateOf(false)
    private val testFontScale = mutableStateOf<Float?>(null)
    private val parish = "actual-quiet-matins-reader"
    private val directory = File(context.cacheDir, name)
    private val cache = FileBookCache(directory)
    private lateinit var cacheBefore: Map<String, String>
    private var contentSet = false
    private val bytes = instrumentation.context.assets.open("polyeleos-journey/ranked-matins-2026-11-13-church34.json").use { it.readBytes() }
    private val book = Gson().fromJson(bytes.toString(Charsets.UTF_8), ServiceBook::class.java)
    private val scope = BookVisibilityScope.of(book)
    private val group = requireNotNull(scope.quietGroup)
    private val chapter = book.chapters.single { it.id == group.chapterId }
    // The first legacy PRAYER is a label. Select source offsets, not first(kind == PRAYER).
    private val sourceGroup = chapter.blocks.subList(18, 54)
    private val privateLabel = sourceGroup[0]
    private val privatePrayer = sourceGroup[1]
    private val privateConclusion = sourceGroup[2]

    @After fun clean() {
        if (contentSet) {
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { mounted.value = false }
            compose.mainClock.advanceTimeBy(64)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun originalPublicPlaybackContinuesIntoPsalm87WithoutTheParallelPrayers() =
        publicPlayback(BookReadingMode.ORIGINAL)

    @Test fun russianPublicPlaybackContinuesIntoPsalm87WithoutTheParallelPrayers() {
        dark.value = true
        publicPlayback(BookReadingMode.RUSSIAN)
    }

    private fun publicPlayback(mode: BookReadingMode) {
        val introduction = chapter.blocks.indexOfFirst { it.id == group.introductionId }
        val before = chapter.blocks.take(introduction).last { BookBlockPresentation.isSpoken(book.key, it) }
        val psalm87 = chapter.blocks.indexOfFirst { it.original == "Псалом 87" }
        val psalm102 = chapter.blocks.indexOfFirst { it.original == "Псалом 102" }
        val expected = chapter.blocks.subList(psalm87, psalm102).map { it.id }.toSet()
        launch(BookReadingPosition(book.key, book.contentHash, parish, before.id, chapter.id,
            fraction = .93f, wordsPerMinute = BookAutoScroll.MAX_RATE, mode = mode))
        assertFalse(saved().showAltar)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("book_play").performClick()
        // Reading positions are deliberately debounced until the viewport settles. Observe
        // the committed cursor after pausing, rather than polling a stale cursor mid-playback.
        // Eight seconds covers the remaining doxology and the short unspoken instructions;
        // it cannot cover the 1,278 words of parallel priestly prayers in the old public flow.
        repeat(16) {
            advance(500)
            assertFalse("No observed committed public cursor may enter quiet text", saved().blockId in group.blockIds)
            group.blockIds.forEach { compose.onNodeWithTag("book_block_$it").assertDoesNotExist() }
        }
        compose.onNodeWithTag("book_play").performClick()
        advance(650)
        assertTrue("Public reading must continue into the source's Psalm 87; actual=${saved().blockId}",
            saved().blockId in expected)
        assertFalse("A quiet prayer must never become the accepted public playback cursor", saved().blockId in group.blockIds)
        assertFalse(saved().showAltar)
        group.blockIds.forEach { compose.onNodeWithTag("book_block_$it").assertDoesNotExist() }
        capture("public-${mode.name.lowercase()}")
        unchanged()
    }

    @Test fun anOldMainBodyCursorReopensExactlyPausedAcrossLanguagesMeaningAndRemount() = restorePrivate(privatePrayer)
    @Test fun anOldConclusionCursorReopensExactlyPausedAcrossLanguagesMeaningAndRemount() = restorePrivate(privateConclusion)
    @Test fun anOldPrayerLabelCursorReopensExactlyPausedAcrossLanguagesMeaningAndRemount() = restorePrivate(privateLabel)

    private fun restorePrivate(target: BookBlock) {
        val initial = BookReadingPosition(book.key, book.contentHash, parish, target.id, chapter.id,
            fraction = .34f, showAltar = false)
        launch(initial)
        recordLabelGeometry(target, "initial")
        assertTrue("A saved private cursor is an explicit reason to reveal its group", saved().showAltar)
        compose.onNodeWithTag("book_text_${target.id}").assertIsDisplayed()
        assertEquals(target.id, saved().blockId)
        assertEquals(initial.fraction, saved().fraction, .015f)
        paused()
        for (mode in listOf(BookReadingMode.RUSSIAN, BookReadingMode.MEANING, BookReadingMode.ORIGINAL)) {
            compose.onNodeWithTag("book_mode_${mode.name.lowercase()}").performClick()
            settle()
            val expectedLanguage = if (mode == BookReadingMode.MEANING) BookReadingMode.RUSSIAN else mode
            recordLabelGeometry(target, "mode-${mode.name.lowercase()}")
            assertEquals(expectedLanguage, saved().mode)
            if (mode == BookReadingMode.MEANING) {
                compose.onNodeWithText("О тихой молитве").assertIsDisplayed()
                compose.onNodeWithTag("book_meaning_cue").assertDoesNotExist()
            }
            assertEquals(target.id, saved().blockId)
            assertEquals(initial.fraction, saved().fraction, .015f)
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnIdle { mounted.value = true }
            settle()
            recordLabelGeometry(target, "remount-${mode.name.lowercase()}")
            assertEquals(expectedLanguage, saved().mode)
            assertEquals(target.id, saved().blockId)
            assertEquals(initial.fraction, saved().fraction, .015f)
            compose.onNodeWithTag("book_meaning_sheet").assertDoesNotExist()
            compose.onNodeWithTag("book_text_${target.id}").assertIsDisplayed()
            paused()
        }
        if (target.id == privateLabel.id) {
            repeat(6) {
                for (mode in listOf(BookReadingMode.RUSSIAN, BookReadingMode.ORIGINAL)) {
                    compose.onNodeWithTag("book_mode_${mode.name.lowercase()}").performClick()
                    settle()
                    assertEquals(target.id, saved().blockId)
                    assertEquals(initial.fraction, saved().fraction, .015f)
                    assertEquals(mode, saved().mode)
                    paused()
                }
            }
            recordLabelGeometry(target, "after-six-cycles")
        }
        assertEquals(book, FileBookCache(directory).readVersion(book.key, book.contentHash))
        // The reader composition and cache object are new. This is not a physical process restart.
        unchanged()
    }

    @Test fun manualSearchMarksAndRevealsTheQuietPrayerAndCollapseReturnsToItsPublicExplanation() {
        val public = chapter.blocks.first { scope.isVisible(it) && it.kind == BookBlockKind.PRAYER }
        launch(BookReadingPosition(book.key, book.contentHash, parish, public.id, chapter.id, mode = BookReadingMode.MEANING))
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextInput(privatePrayer.original.split(' ').take(7).joinToString(" "))
        closeSoftKeyboard()
        val hit = compose.scrollToBookHit(privatePrayer.id)
        compose.onNodeWithTag("book_hit_private_${privatePrayer.id}", useUnmergedTree = true).assertIsDisplayed()
        hit.performClick()
        settle()
        assertEquals(privatePrayer.id, saved().blockId)
        assertTrue(saved().showAltar)
        compose.onNodeWithTag("book_text_${privatePrayer.id}").assertIsDisplayed()
        paused()
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithText("Тихие молитвы и алтарные тексты").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick()
        settle()
        assertEquals(group.introductionId, saved().blockId)
        assertEquals(0f, saved().fraction, .001f)
        assertFalse(saved().showAltar)
        androidx.test.espresso.Espresso.pressBack()
        settle()
        compose.onNodeWithTag("book_quiet_toggle").assertIsDisplayed()
        compose.onNodeWithText("Показать тихие и алтарные тексты").assertIsDisplayed()
        compose.onNodeWithTag("book_block_${privatePrayer.id}").assertDoesNotExist()
        paused()
        assertEquals("Псалом 87", BookSearch.find(book, "Псалом 87")
            .single { it.excerpt.startsWith("Псалом 87") }.excerpt)
        // A short repeated conclusion has two private occurrences plus a public exclamation.
        val query = "яко ты еси бог наш"
        val all = BookCorpusSearch.find(listOf(book), query, BookReadingMode.ORIGINAL)
        assertTrue(all.first().hit.blockId !in group.blockIds)
        val exact = all.filter { hit ->
            val block = book.blocks.single { it.id == hit.hit.blockId }
            val source = if (hit.hit.mode == BookReadingMode.RUSSIAN) requireNotNull(block.russian) else block.original
            BookText.normalized(source.substring(hit.hit.matchStart, hit.hit.matchEndExclusive)) == query
        }
        assertEquals(2, exact.count { it.hit.blockId in group.blockIds })
        all.forEach { assertEquals(it, it.validatedFor(book)) }
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextReplacement(query)
        closeSoftKeyboard()
        val conclusion = sourceGroup[14]
        val duplicateHit = compose.scrollToBookHit(conclusion.id)
        compose.onNodeWithTag("book_hit_private_${conclusion.id}", useUnmergedTree = true).assertIsDisplayed()
        duplicateHit.performClick(); settle()
        assertEquals(conclusion.id, saved().blockId)
        assertTrue(saved().showAltar)
        paused()
        unchanged()
    }

    @Test fun explicitCardRevealAndSettingsCollapseAlsoKeepTheLegacyLabelAvailableAndPaused() {
        launch(BookReadingPosition(book.key, book.contentHash, parish, group.introductionId, chapter.id))
        compose.onNodeWithTag("book_quiet_toggle").performScrollTo().performClick(); settle()
        assertTrue(saved().showAltar)
        compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_text_${privateLabel.id}"))
        compose.onNodeWithTag("book_text_${privateLabel.id}").performScrollTo().assertIsDisplayed()
        assertEquals(BookBlockKind.PRAYER, privateLabel.kind)
        // A normal persisted old label cursor is also a valid restoration target.
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        assertTrue(store.save(BookReadingPosition(book.key, book.contentHash, parish, privateLabel.id, chapter.id,
            fraction = .2f, showAltar = true)))
        compose.runOnIdle { mounted.value = true }; settle()
        assertEquals(privateLabel.id, saved().blockId)
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithText("Тихие молитвы и алтарные тексты").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick(); settle()
        assertEquals(group.introductionId, saved().blockId)
        assertEquals(0f, saved().fraction, .001f)
        assertFalse(saved().showAltar)
        androidx.test.espresso.Espresso.pressBack(); settle()
        compose.onNodeWithTag("book_block_${privateLabel.id}").assertDoesNotExist()
        compose.onNodeWithTag("book_quiet_toggle").assertIsDisplayed()
        paused()
        unchanged()
    }

    @Test fun explainingTheRealVirtualQuietCardKeepsItsExactBoundsCollapsedAndExpanded() {
        launch(BookReadingPosition(book.key, book.contentHash, parish, group.introductionId, chapter.id,
            anchorKind = BookAnchorKind.BLOCK, showAltar = false))
        for (expanded in listOf(false, true)) {
            if (expanded) {
                compose.onNodeWithTag("book_content").performScrollToNode(hasTestTag("book_quiet_group"))
                compose.onNodeWithTag("book_quiet_toggle").performScrollTo().performClick()
                settle()
            }
            alignQuietCard()
            val beforeCard = compose.onNodeWithTag("book_quiet_group").getUnclippedBoundsInRoot()
            val beforeText = compose.onNodeWithText(group.explanation).getUnclippedBoundsInRoot()
            val before = saved()
            assertEquals(expanded, before.showAltar)
            assertEquals(group.introductionId, before.blockId)
            repeat(3) {
                compose.onNodeWithTag("book_mode_meaning").performClick(); settle()
                compose.onNodeWithTag("book_meaning_title").assertTextEquals(group.title)
                compose.onNodeWithTag("book_meaning_detail").performScrollTo().assertTextEquals(group.explanation)
                compose.onNodeWithTag("book_meaning_what").assertDoesNotExist()
                compose.onNodeWithTag("book_meaning_cue").assertDoesNotExist()
                compose.onNodeWithTag("book_meaning_close").performClick(); settle()
                assertEquals("Closing help must return to the virtual card, not its preceding rubric",
                    beforeCard, compose.onNodeWithTag("book_quiet_group").getUnclippedBoundsInRoot())
                assertEquals("The same visible explanation line must keep its exact bounds",
                    beforeText, compose.onNodeWithText(group.explanation).getUnclippedBoundsInRoot())
                assertEquals(before, saved())
                paused()
            }
        }
        unchanged()
    }

    @Test fun aQuietCardReflowUnderItsHelpRestoresTheSameCardFractionRatherThanTheIntroduction() {
        testFontScale.value = 1f
        launch(BookReadingPosition(book.key, book.contentHash, parish, group.introductionId, chapter.id,
            anchorKind = BookAnchorKind.BLOCK, showAltar = false))
        alignQuietCard()
        val beforeCard = compose.onNodeWithTag("book_quiet_group").getUnclippedBoundsInRoot()
        val beforeViewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val fraction = (beforeViewport.top - beforeCard.top).value / (beforeCard.bottom - beforeCard.top).value
        val before = saved()
        compose.onNodeWithTag("book_mode_meaning").performClick(); settle()
        compose.runOnIdle { testFontScale.value = 1.5f }; settle()
        compose.onNodeWithTag("book_meaning_close").performClick(); settle()
        val afterCard = compose.onNodeWithTag("book_quiet_group").getUnclippedBoundsInRoot()
        val afterViewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        assertNotEquals("The regression must actually reflow the rendered card", (beforeCard.bottom - beforeCard.top), (afterCard.bottom - afterCard.top))
        val expectedTop = afterViewport.top.value - (afterCard.bottom - afterCard.top).value * fraction
        assertTrue("Reflow must preserve the temporary card fraction, not seek its source rubric",
            abs(afterCard.top.value - expectedTop) <= 1f / compose.density.density)
        assertEquals(before, saved())
        assertFalse(saved().showAltar)
        paused()
        unchanged()
    }

    private fun alignQuietCard() {
        val content = compose.onNodeWithTag("book_content")
        content.performScrollToNode(hasTestTag("book_quiet_group")); settle()
        val card = compose.onNodeWithTag("book_quiet_group").getUnclippedBoundsInRoot()
        val viewport = content.getBoundsInRoot()
        val distance = card.top - viewport.top + (card.bottom - card.top) * .28f
        content.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            assertTrue(scroll(0f, with(compose.density) { distance.toPx() }))
        }
        settle()
        val aligned = compose.onNodeWithTag("book_quiet_group").getUnclippedBoundsInRoot()
        assertTrue("The virtual card itself must be partly above the viewport", aligned.top < content.getBoundsInRoot().top)
        assertTrue("The card must still cross the viewport start", aligned.bottom > content.getBoundsInRoot().top)
    }

    private fun launch(position: BookReadingPosition) {
        assertEquals("440b36d4b7f190d3da575d12f0f6ff0a1e3c272e40be87ea32ea5cf162bfc79d", BookText.sha256(bytes.toString(Charsets.UTF_8)))
        assertEquals("4b9b2e7278f3d670ee813dd2afd341fcb7b2d33b0348a0a4754c0f36ad2f8b79", book.contentHash)
        assertTrue(ServiceBookIntegrity.valid(book))
        assertEquals(36, sourceGroup.size)
        assertEquals(group.blockIds, sourceGroup.map { it.id }.toSet())
        assertTrue(sourceGroup.all { it.kind == BookBlockKind.PRAYER && !it.isAltarText })
        assertEquals(listOf(3, 37, 62, 87, 102, 142).map { "псалом $it" },
            chapter.blocks.filter { scope.isVisible(it) }.map { BookText.normalized(it.original) }
                .filter { Regex("псалом [0-9]+").matches(it) })
        assertTrue(cache.write(book))
        cacheBefore = cacheSnapshot()
        assertTrue(store.save(position))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides (testFontScale.value?.let { Density(density.density, it) } ?: density)) {
                GuideTheme(dark.value) {
                    if (mounted.value) BookReaderScreen(book, parish, store, 0, onBack = {}, onOpenUrl = {},
                        onChooseVersion = {}, onSettings = {})
                }
            }
        }
        contentSet = true
        settle()
    }
    private fun saved() = requireNotNull(store.positionFor(book.key, parish))
    private fun recordLabelGeometry(target: BookBlock, phase: String) {
        if (target.id != privateLabel.id) return
        val bounds = compose.onNodeWithTag("book_block_${target.id}").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val height = bounds.bottom - bounds.top
        val out = File(context.getExternalFilesDir("actual-quiet-qa"), name).apply { mkdirs() }
        File(out, "label-geometry.ndjson").appendText(Gson().toJson(mapOf(
            "phase" to phase, "position" to saved(), "block" to bounds.toString(), "viewport" to viewport.toString(),
            "blockHeightPx" to with(compose.density) { height.toPx() },
            "viewportFraction" to ((viewport.top - bounds.top).value / height.value),
            "density" to compose.density.density, "fontScale" to compose.density.fontScale)) + "\n")
        compose.waitForIdle()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(out, "label-$phase.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }
    private fun paused() = compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Прокручивать текст")
    private fun advance(millis: Long) { compose.mainClock.advanceTimeBy(millis); compose.waitForIdle() }
    private fun settle() { compose.waitForIdle(); advance(750) }
    private fun unchanged() {
        assertArrayEquals(bytes, instrumentation.context.assets.open("polyeleos-journey/ranked-matins-2026-11-13-church34.json").use { it.readBytes() })
        assertEquals(book.contentHash, Gson().fromJson(bytes.toString(Charsets.UTF_8), ServiceBook::class.java).contentHash)
        assertEquals(cacheBefore, cacheSnapshot())
        assertEquals(book, FileBookCache(directory).readVersion(book.key, book.contentHash))
        assertTrue(sourceGroup.all { it.kind == BookBlockKind.PRAYER && !it.isAltarText })
    }
    private fun cacheSnapshot() = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).path to BookText.sha256(it.readText()) }
    private fun capture(label: String) {
        val out = File(context.getExternalFilesDir("actual-quiet-qa"), name).apply { mkdirs() }
        File(out, "$label.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
