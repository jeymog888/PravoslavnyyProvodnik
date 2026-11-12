package ru.liturgyguide.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in real-state smoke on the owned emulator, using MainActivity and its production container.
 * Root disables/verifies radios outside instrumentation, then separately cold-launches the app.
 * No preferences, book files, containers or providers are seeded, substituted or cleared here.
 * The deliberate UI selection remains saved for that independent offline resume check.
 */
@RunWith(AndroidJUnit4::class)
class LiveOfflineBookSearchTest {
    // This rule does not launch an Activity. A normal test run skips before accessing app data.
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realMainSearchOpensTheExactCachedVespersLineAndRetainsTheEveningOccurrence() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit real-state offline search opt-in",
            arguments.getString("liveOfflineBookSearch") == "true")
        val run = requireNotNull(arguments.getString("offlineBookRun")) { "Supply a unique offlineBookRun" }
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Use a safe evidence run ID" }
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("/sdk_gphone")) {
            "Real-state reading changes are restricted to the owned Android emulator"
        }
        check(Build.VERSION.SDK_INT >= 29) { "Frame-commit evidence requires Android 10 or newer" }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
        val readingPreferences = context.getSharedPreferences("service_book_reading", Context.MODE_PRIVATE)
        val readingStore = BookReadingStore(readingPreferences)
        val output = File(requireNotNull(context.getExternalFilesDir("live-offline-book-search")), run)
        check(!output.exists() && output.mkdirs()) { "Use a new offlineBookRun to preserve earlier evidence" }
        val captures = mutableListOf<Map<String, Any>>()
        val receipt = linkedMapOf<String, Any?>(
            "passed" to false,
            "run" to run,
            "phrase" to PHRASE,
            "captures" to captures,
            "statePolicy" to "Current production app state; no seed, override, replacement or cleanup; UI selection retained",
            "scope" to "Published text offline UI, not real temple synchronization",
            "offlineVerification" to mapOf(
                "verifiedByInstrumentation" to false,
                "responsibility" to "External assertion by root",
                "requiredExternalEvidence" to "Radios disabled and dumpsys connectivity has no active network before and after"
            ),
            "caveats" to listOf(
                "Published text offline UI, not real temple synchronization",
                "Radios are verified externally; an opt-in argument is not connectivity evidence",
                "A separate cold launch after instrumentation must verify offline resume"
            )
        )
        var scenario: ActivityScenario<MainActivity>? = null
        var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        var currentKey: ServiceBookKey? = null
        var otherKey: ServiceBookKey? = null
        try {
            receipt["apkSha256"] = sha256(File(context.applicationInfo.sourceDir))
            assertEquals("Keep the actual selected parish as a precondition", PARISH_ID,
                preferences.getString("church_id", null))
            val saved = requireNotNull(readingStore.last(PARISH_ID)) { "No saved reading in the selected parish" }
            currentKey = saved.key
            receipt["beforeLaunch"] = positionSummary(saved)
            assertEquals("The preserved reading must be Matins", WorshipKind.MATINS, saved.key.worship)
            assertEquals("2026-11-13", saved.key.date)
            assertEquals("20", saved.key.churchType)
            val journey = requireNotNull(saved.journeyContext?.validatedFor(saved.key, PARISH_ID)) {
                "The preserved reading must own a valid evening-text occurrence"
            }
            assertEquals(BookPartSetKind.EVENING_TEXTS, journey.effectivePartSet)
            assertEquals("2026-11-12", journey.civilDate)
            assertEquals("18:00", journey.time)
            otherKey = journey.parts.single { it != saved.key }
            assertEquals(WorshipKind.VESPERS, otherKey.worship)
            assertEquals("2026-11-13", otherKey.date)
            assertEquals("20", otherKey.churchType)
            assertEquals(saved, readingStore.positionFor(saved.key, PARISH_ID))

            val books = (context.applicationContext as LiturgyGuideApplication).container.serviceBooks
            assertTrue("Use the production repository", books is ServiceBookRepository)
            val current = requireNotNull(books.cachedVersion(saved.key, saved.contentHash)) {
                "The current accepted Matins edition must already be saved on the phone"
            }
            assertEquals(saved.key, current.key)
            assertEquals(saved.contentHash, current.contentHash)
            assertTrue("The current exact book must pass production integrity checks", ServiceBookIntegrity.valid(current))
            val previousOther = readingStore.positionFor(otherKey, PARISH_ID)
            val other = if (previousOther != null) {
                requireNotNull(books.cachedVersion(otherKey, previousOther.contentHash)) {
                    "The accepted Vespers edition is missing; latest is not a substitute"
                }.also { assertEquals(previousOther.contentHash, it.contentHash) }
            } else {
                val cached = requireNotNull(books.cached(otherKey)) {
                    "Vespers must already be cached; this smoke never downloads or seeds it"
                }
                requireNotNull(books.cachedVersion(otherKey, cached.contentHash)) {
                    "The cached Vespers edition must have a durable exact-version copy"
                }.also { assertEquals(cached, it) }
            }
            assertEquals(otherKey, other.key)
            assertTrue("The other exact book must pass production integrity checks", ServiceBookIntegrity.valid(other))
            receipt["currentEdition"] = bookSummary(current)
            receipt["targetEdition"] = bookSummary(other)
            receipt["otherEditionPolicy"] = if (previousOther == null) "Verified cached exact edition" else "Accepted exact edition"
            receipt["beforePublicPositions"] = publicPositions(readingStore, saved.key, other.key)

            val currentHits = BookSearch.find(current, PHRASE, BookReadingMode.ORIGINAL)
            val otherHits = BookCorpusSearch.find(listOf(other), PHRASE, BookReadingMode.ORIGINAL)
            receipt["currentHitCount"] = currentHits.size
            receipt["otherHitCount"] = otherHits.size
            assertTrue("The real Matins edition must not contain the chosen heard phrase", currentHits.isEmpty())
            assertTrue("The real Vespers edition must contain the chosen heard phrase", otherHits.isNotEmpty())
            assertEquals("The full exact corpus must return only Vespers hits", otherHits,
                BookCorpusSearch.find(listOf(current, other), PHRASE, BookReadingMode.ORIGINAL))
            val target = otherHits.first()
            assertEquals(BookReadingMode.ORIGINAL, target.hit.mode)
            assertNotNull(target.validatedFor(other))
            receipt["target"] = mapOf(
                "key" to target.bookKey.canonical(), "contentHash" to target.contentHash,
                "identity" to target.identity, "chapterId" to target.hit.chapterId,
                "blockId" to target.hit.blockId, "mode" to target.hit.mode.name,
                "matchStart" to target.hit.matchStart, "matchEndExclusive" to target.hit.matchEndExclusive
            )

            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitTag("main_content")
            compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_book"))
            compose.onNodeWithTag("resume_book").assertIsDisplayed().performClick()
            awaitTag("book_content")
            waitForPosition(readingStore, current, journey)
            selectOriginalIfNeeded(readingStore)
            waitForPosition(readingStore, current, journey, BookReadingMode.ORIGINAL)
            settle()
            capture("01-current-matins", requireNotNull(scenario), output, captures)

            // Start after the reader's initial restoration/mode change has settled. Only read
            // public positions into the receipt; raw preferences stay in memory for equality.
            val beforeSearch = readingPreferences.all.toMap()
            val beforeCurrent = requireNotNull(readingStore.positionFor(current.key, PARISH_ID))
            val beforeOther = readingStore.positionFor(other.key, PARISH_ID)
            val beforeLast = readingStore.last(PARISH_ID)
            receipt["beforeSearchPublicPositions"] = publicPositions(readingStore, current.key, other.key)
            val mutatedDuringSearch = AtomicBoolean(false)
            listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                // This namespace contains reading pointers only. Record the callback itself:
                // a write followed by rollback can restore the map before callback delivery.
                mutatedDuringSearch.set(true)
            }
            readingPreferences.registerOnSharedPreferenceChangeListener(listener)
            fun assertSearchHasNotMovedReading() {
                assertFalse("Search must never transiently change an accepted reading position", mutatedDuringSearch.get())
                assertTrue("Search must leave every reading preference unchanged", beforeSearch == readingPreferences.all)
                assertEquals(beforeCurrent, readingStore.positionFor(current.key, PARISH_ID))
                assertEquals(beforeOther, readingStore.positionFor(other.key, PARISH_ID))
                assertEquals(beforeLast, readingStore.last(PARISH_ID))
            }

            compose.onNodeWithTag("book_find").assertIsDisplayed().performClick()
            awaitTag("book_search_query")
            assertSearchHasNotMovedReading()
            // The actual UI receives Unicode text, matching the typed heard-words flow.
            compose.onNodeWithTag("book_search_query").performTextInput(PHRASE)
            closeSoftKeyboardWithCiDiagnostics()
            compose.onNodeWithTag("book_search_query").assertTextContains(PHRASE, substring = true)
            assertSearchHasNotMovedReading()
            val targetTag = "book_cross_hit_${target.identity}"
            compose.waitUntil(15_000) {
                assertSearchHasNotMovedReading()
                runCatching {
                    compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag(targetTag))
                    compose.onNodeWithTag("book_search_sheet").fetchSemanticsNode()
                        .config[SemanticsProperties.StateDescription] == "Поиск готов" && exists(targetTag)
                }.getOrDefault(false)
            }
            compose.onNodeWithTag("book_search_corpus_error").assertDoesNotExist()
            compose.onNodeWithTag("book_search_selection_error").assertDoesNotExist()
            compose.onNodeWithTag(targetTag).assertIsDisplayed()
                .assertTextContains("Вечерня", substring = true)
                .assertTextContains(target.hit.chapterTitle, substring = true)
                .assertTextContains(target.hit.excerpt, substring = true)
            val anyCurrentHit = SemanticsMatcher("a current-book search result") { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("book_hit_")
            }
            compose.onAllNodes(anyCurrentHit).assertCountEquals(0)
            capture("02-vespers-only-result", requireNotNull(scenario), output, captures)
            assertSearchHasNotMovedReading()
            receipt["searchKeptAllReadingPreferencesUnchanged"] = true
            readingPreferences.unregisterOnSharedPreferenceChangeListener(listener)
            listener = null
            compose.onNodeWithTag(targetTag).performClick()

            awaitTag("book_content")
            compose.waitUntil(15_000) {
                readingStore.last(PARISH_ID)?.let { position ->
                    position.key == target.bookKey && position.contentHash == target.contentHash &&
                        position.blockId == target.hit.blockId && position.chapterId == target.hit.chapterId &&
                        position.mode == BookReadingMode.ORIGINAL && position.journeyContext == journey
                } == true
            }
            settle()
            compose.onNodeWithTag("book_search_sheet").assertDoesNotExist()
            compose.onNodeWithTag("book_search_jump_error").assertDoesNotExist()
            compose.onNodeWithTag("book_save_error").assertDoesNotExist()
            val opened = requireNotNull(readingStore.positionFor(other.key, PARISH_ID))
            assertDestination(opened, target, journey)
            assertEquals(beforeCurrent, readingStore.positionFor(current.key, PARISH_ID))
            assertEquals(opened, readingStore.last(PARISH_ID))
            receipt["measuredMatch"] = assertActualMatchedLine(other, target, context)
            receipt["afterSelectionPublicPositions"] = publicPositions(readingStore, current.key, other.key)
            receipt["pendingCompletionEvidence"] = "Exact measured destination committed as last reading; no jump error"
            capture("03-exact-vespers-line", requireNotNull(scenario), output, captures)

            // Closing uses the real reader lifecycle save. The root-owned cold launch follows
            // outside instrumentation and is intentionally not replaced by a synthetic route.
            scenario.close()
            scenario = null
            val durable = requireNotNull(readingStore.last(PARISH_ID))
            assertDestination(durable, target, journey)
            assertEquals(durable, readingStore.positionFor(other.key, PARISH_ID))
            assertEquals(beforeCurrent, readingStore.positionFor(current.key, PARISH_ID))
            assertEquals(PARISH_ID, preferences.getString("church_id", null))
            receipt["afterClosePublicPositions"] = publicPositions(readingStore, current.key, other.key)
            receipt["realActivityClosedWithDurableDestination"] = true
            receipt["passed"] = true
        } catch (failure: Throwable) {
            // Do not serialize exception messages or preference maps: precondition failures
            // can mention app state. Safe public-key summaries are recorded separately.
            receipt["failureType"] = failure.javaClass.name
            scenario?.let { active ->
                runCatching { capture("failure", active, output, captures) }
                    .onFailure { receipt["failureCaptureType"] = it.javaClass.name }
            }
            throw failure
        } finally {
            listener?.let(readingPreferences::unregisterOnSharedPreferenceChangeListener)
            runCatching { scenario?.close() }.onFailure {
                receipt["passed"] = false
                receipt["closeFailureType"] = it.javaClass.name
            }
            receipt["selectedParishAtEnd"] = preferences.getString("church_id", null)
            if (currentKey != null && otherKey != null) {
                receipt["finalPublicPositions"] = publicPositions(readingStore, currentKey, otherKey)
            }
            File(output, "receipt.json").writeText(
                GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(receipt), Charsets.UTF_8)
        }
    }

    private fun selectOriginalIfNeeded(store: BookReadingStore) {
        if (store.last(PARISH_ID)?.let {
                it.mode == BookReadingMode.ORIGINAL && it.lastTextMode == BookReadingMode.ORIGINAL
            } == true) return
        if (!exists("book_mode_original")) compose.onNodeWithTag("book_mode_menu").performClick()
        compose.onNodeWithTag("book_mode_original").assertIsDisplayed().performClick()
    }

    private fun waitForPosition(store: BookReadingStore, book: ServiceBook, journey: BookJourneyContext,
        mode: BookReadingMode? = null) {
        compose.waitUntil(15_000) {
            store.last(PARISH_ID)?.let {
                it.key == book.key && it.contentHash == book.contentHash && it.journeyContext == journey &&
                    (mode == null || it.mode == mode && it.lastTextMode == mode)
            } == true
        }
        settle()
    }

    private fun assertDestination(position: BookReadingPosition, target: BookLocatedSearchHit,
        journey: BookJourneyContext) {
        assertEquals(target.bookKey, position.key)
        assertEquals(target.contentHash, position.contentHash)
        assertEquals(target.hit.chapterId, position.chapterId)
        assertEquals(target.hit.blockId, position.blockId)
        assertEquals(BookAnchorKind.BLOCK, position.anchorKind)
        assertEquals(BookReadingMode.ORIGINAL, position.mode)
        assertEquals(BookReadingMode.ORIGINAL, position.lastTextMode)
        assertEquals(PARISH_ID, position.parishId)
        assertEquals(journey, position.journeyContext)
        assertEquals(BookPartSetKind.EVENING_TEXTS, position.journeyContext?.effectivePartSet)
        assertEquals("2026-11-12", position.journeyContext?.civilDate)
        assertEquals("18:00", position.journeyContext?.time)
        assertEquals("2026-11-13", position.key.date)
    }

    private fun assertActualMatchedLine(book: ServiceBook, target: BookLocatedSearchHit,
        context: Context): Map<String, Any> {
        val block = book.blocks.single { it.id == target.hit.blockId }
        val text = compose.onNodeWithTag("book_text_${block.id}", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals("The actual reader must show the exact cached original", block.original, layout.layoutInput.text.text)
        val firstLine = layout.getLineForOffset(target.hit.matchStart)
        val lastLine = layout.getLineForOffset(target.hit.matchEndExclusive - 1)
        val textTop = text.getUnclippedBoundsInRoot().top
        val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
        val density = context.resources.displayMetrics.density
        val lineTop = textTop + (layout.getLineTop(firstLine) / density).dp
        val lineBottom = textTop + (layout.getLineBottom(lastLine) / density).dp
        assertTrue("The first matched line must be inside the actual reader viewport", lineTop >= viewport.top)
        assertTrue("The complete matched phrase must be above the reader controls", lineBottom <= viewport.bottom)
        return mapOf("sourceTextEqualsCachedOriginal" to true, "firstLine" to firstLine,
            "lastLine" to lastLine, "lineTopDp" to lineTop.value, "lineBottomDp" to lineBottom.value,
            "viewportTopDp" to viewport.top.value, "viewportBottomDp" to viewport.bottom.value)
    }

    private fun publicPositions(store: BookReadingStore, current: ServiceBookKey, other: ServiceBookKey) = mapOf(
        "current" to positionSummary(store.positionFor(current, PARISH_ID)),
        "other" to positionSummary(store.positionFor(other, PARISH_ID)),
        "last" to positionSummary(store.last(PARISH_ID))
    )

    private fun positionSummary(position: BookReadingPosition?): Map<String, Any?>? = position?.let {
        mapOf("key" to it.key.canonical(), "contentHash" to it.contentHash, "parishId" to it.parishId,
            "chapterId" to it.chapterId, "blockId" to it.blockId, "fraction" to it.fraction,
            "followingGapFraction" to it.followingGapFraction, "anchorKind" to it.anchorKind.name,
            "mode" to it.mode.name, "lastTextMode" to it.lastTextMode?.name,
            "journeyCivilDate" to it.journeyContext?.civilDate, "journeyTime" to it.journeyContext?.time,
            "partSet" to it.journeyContext?.effectivePartSet?.name,
            "journeyParts" to it.journeyContext?.parts?.map(ServiceBookKey::canonical))
    }

    private fun bookSummary(book: ServiceBook) = mapOf(
        "key" to book.key.canonical(), "contentHash" to book.contentHash,
        "chapterCount" to book.chapters.size, "blockCount" to book.blocks.size)

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { exists(tag) }
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
    }

    private fun capture(label: String, scenario: ActivityScenario<MainActivity>, output: File,
        captures: MutableList<Map<String, Any>>) {
        compose.waitForIdle()
        val committed = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            activity.window.decorView.invalidate()
        }
        assertTrue("A real screen frame must be committed", committed.await(5, TimeUnit.SECONDS))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var stable = false
        try {
            for (attempt in 0 until 8) {
                SystemClock.sleep(120)
                val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                stable = bitmap.sameAs(next)
                bitmap.recycle()
                bitmap = next
                if (stable) break
            }
            val file = File(output, "$label.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            captures += mapOf("file" to file.name, "sha256" to sha256(file),
                "twoIdenticalFrames" to stable, "widthPx" to bitmap.width, "heightPx" to bitmap.height)
        } finally { bitmap.recycle() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PARISH_ID = "sretensky_old"
        const val PHRASE = "Свете тихий"
    }
}
