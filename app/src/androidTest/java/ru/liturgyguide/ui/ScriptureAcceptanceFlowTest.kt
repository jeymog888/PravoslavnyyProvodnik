package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual repository/parser/cache/reader with captured public responses, not a live-network claim. */
@RunWith(AndroidJUnit4::class)
class ScriptureAcceptanceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefix = "scripture-acceptance-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(prefix, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, prefix)
    private val output = File(context.getExternalFilesDir("scripture-acceptance-qa"), prefix).apply { mkdirs() }
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val calls = CopyOnWriteArrayList<String>()
    private val offlineCalls = CopyOnWriteArrayList<String>()
    private val loadEvents = CopyOnWriteArrayList<Map<String, Any?>>()
    private val loadFinished = CountDownLatch(1)
    private val startedAt = SystemClock.elapsedRealtime()
    private val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34", topLevel = "3")
    private val parishId = "scripture-acceptance-fixture"
    private val directUrl = "https://azbyka.ru/biblia/?Lk.10:1-21&c~r"
    private val receipt = linkedMapOf<String, Any?>("status" to "RUNNING", "scope" to "actual reader with captured public HTTP fixtures")

    @After fun clean() {
        if (contentSet) {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
        context.deleteSharedPreferences(prefix)
        directory.deleteRecursively()
    }

    @Test fun realMissingGospelBecomesReadableOnlyAfterExplicitAcceptanceAndSurvivesOfflineReopening() {
        try {
            val old = Gson().fromJson(asset("polyeleos-journey/ranked-matins-2026-11-13-church34.json"), ServiceBook::class.java)
            assertEquals("4b9b2e7278f3d670ee813dd2afd341fcb7b2d33b0348a0a4754c0f36ad2f8b79", old.contentHash)
            assertTrue(ServiceBookIntegrity.valid(old)); assertEquals(key, old.key)
            val gospel = old.blocks.single { it.kind == BookBlockKind.SCRIPTURE }
            assertEquals(listOf("Lk.10:1-21"), gospel.scriptureReferences); assertNull(gospel.russian)
            val chapter = old.chapters.single { gospel in it.blocks }
            val cache = FileBookCache(directory)
            assertTrue(cache.write(old))
            val oldFile = File(directory, "${BookText.sha256(key.canonical()).take(32)}-${old.contentHash}.json")
            val oldBytes = oldFile.readBytes()
            val initial = BookReadingPosition(key, old.contentHash, parishId, gospel.id, chapter.id,
                fraction = .24f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)
            assertTrue(store.save(initial))
            val transport = ScheduleTransport { url ->
                calls += url
                val text = when (url) {
                    key.copy(topLevel = null).sourceUrl() -> asset("polyeleos-journey/daily-matins-2026-11-13-church34.html")
                    old.sourceUrl -> asset("scripture-fallback/rank3-2026-11-13.html")
                    "https://azbyka.ru/biblia/days/2026-11-13" -> asset("scripture-fallback/daily-2026-11-13.html")
                    directUrl -> asset("scripture-fallback/direct-2026-11-13.html")
                    else -> error("Unexpected refresh request: $url")
                }
                ScheduleResponse(url, text.toByteArray(), "text/html; charset=UTF-8")
            }
            val owner = mutableStateOf<ServiceBooks>(observed(ServiceBookRepository(cache, transport, downloadRussian = false)))
            compose.setContent { GuideTheme(false) {
                if (mounted.value) ServiceBookScreen(key, parishId, owner.value, store, 0,
                    onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
            } }
            contentSet = true
            waitFor("book_content"); settle(); assertPosition(initial, saved())
            compose.onNodeWithTag("book_text_${gospel.id}").assertTextEquals(gospel.original)
            assertTrue(calls.isEmpty())
            compose.onNodeWithContentDescription("Настроить чтение").performClick()
            recordLoad("refresh-click")
            compose.onNodeWithTag("book_refresh").performScrollTo().performClick()
            dismissSheetIfPresent()
            // Parsing a complete captured book is asynchronous. Measure its bounded completion
            // separately from the existing five-second deadline for presenting the UI action.
            assertTrue("Captured book load must finish within 20 seconds", loadFinished.await(20, TimeUnit.SECONDS))
            val completedLoad = loadEvents.last { it["event"] in setOf("load-result", "load-threw") }
            assertEquals("load-result", completedLoad["event"])
            assertEquals("Ready", completedLoad["kind"])
            assertEquals(true, completedLoad["saved"])
            waitFor("book_accept_update")
            settle()
            val newer = requireNotNull(cache.read(key))
            val translated = newer.blocks.single { it.id == gospel.id }
            val expected = JsonParser.parseString(asset("scripture-fallback/expected-2026-11-13.json")).asJsonObject
                .getAsJsonArray("verses").joinToString("\n\n") { it.asJsonObject["text"].asString }
            assertEquals(expected, translated.russian)
            assertEquals(BookSource("Библия, Синодальный перевод", directUrl), translated.translationSource)
            assertEquals(BookSupplementStatus.AVAILABLE, newer.enrichment.scripture)
            assertEquals(old.blocks.map { it.original }, newer.blocks.map { it.original })
            assertNotEquals(old.contentHash, newer.contentHash)
            assertPosition(initial, saved())
            assertArrayEquals(oldBytes, oldFile.readBytes())
            assertEquals(listOf(key.copy(topLevel = null).sourceUrl(), old.sourceUrl,
                "https://azbyka.ru/biblia/days/2026-11-13", directUrl), calls.toList())
            compose.onNodeWithTag("book_accept_update").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(newer.contentHash, saved().contentHash) }
            settle()
            val accepted = saved()
            // A newly available translation changes visible prose: retain the exact fragment
            // but restart that fragment, as BookPositionUpdate already specifies and tests.
            assertPosition(initial.copy(contentHash = newer.contentHash, fraction = 0f), accepted)
            compose.onNodeWithTag("book_text_${gospel.id}").assertIsDisplayed().assertTextEquals(expected)
            assertEquals(newer, cache.readVersion(key, accepted.contentHash))
            capture("accepted-russian-gospel")
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
                offlineCalls += url; error("Offline reader must use the accepted stored version")
            }, downloadRussian = false)
            compose.runOnIdle { owner.value = offline; mounted.value = true }
            waitFor("book_content"); settle()
            assertPosition(accepted, saved())
            compose.onNodeWithTag("book_text_${gospel.id}").assertTextEquals(expected)
            assertEquals(newer, offline.cachedVersion(key, accepted.contentHash))
            assertTrue(offlineCalls.isEmpty())
            assertArrayEquals(oldBytes, oldFile.readBytes())
            receipt.putAll(mapOf("status" to "PASS", "oldHash" to old.contentHash, "acceptedHash" to accepted.contentHash,
                "sourceRequests" to calls.filter { "/worships/" in it }, "directRequests" to calls.filter { "/biblia/?" in it },
                "gospelRussianSha256" to BookText.sha256(expected), "gospelSourceUrl" to translated.translationSource?.url,
                "offlineRequests" to offlineCalls.size, "oldBytesPreserved" to true))
        } catch (failure: Throwable) {
            receipt["status"] = "FAIL"; receipt["error"] = failure.toString()
            recordLoad("assertion-failed", mapOf("error" to failure.toString(),
                "cacheCurrent" to runCatching { bookSummary(FileBookCache(directory).read(key)) }.getOrElse { mapOf("readError" to it.toString()) }))
            runCatching { File(output, "isolated-state.txt").writeText("preferences=${preferences.all}\nrequests=$calls\noffline=$offlineCalls") }
            runCatching { capture("failure") }
            runCatching { File(output, "semantics.txt").writeText(compose.onRoot().printToString()) }
            throw failure
        } finally {
            receipt["loadEvents"] = loadEvents.toList()
            receipt["cacheCurrent"] = runCatching { bookSummary(FileBookCache(directory).read(key)) }
                .getOrElse { mapOf("readError" to it.toString()) }
            File(output, "receipt.json").writeText(Gson().toJson(receipt))
            android.util.Log.i("ScriptureAcceptanceQA", "receipt=${File(output, "receipt.json").absolutePath}")
        }
    }

    /** Observation only: all repository outcomes and callbacks reach the actual screen unchanged. */
    private fun observed(repository: ServiceBookRepository): ServiceBooks = object : ServiceBooks by repository {
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            recordLoad("load-start", mapOf("key" to key.canonical(), "force" to force))
            return try {
                val result = repository.load(key, force) { progress ->
                    recordLoad("load-progress", mapOf("progress" to progress.name))
                    onProgress(progress)
                }
                val resultFields = when (result) {
                    is BookLoadResult.Ready -> mapOf("kind" to "Ready", "saved" to result.saved,
                        "fromCache" to result.fromCache, "warning" to result.warning, "book" to bookSummary(result.book))
                    is BookLoadResult.Failed -> mapOf("kind" to "Failed", "reason" to result.reason.name,
                        "message" to result.message, "cachedBook" to bookSummary(result.cachedBook))
                }
                recordLoad("load-result", resultFields)
                loadFinished.countDown()
                result
            } catch (failure: Throwable) {
                recordLoad("load-threw", mapOf("error" to failure.toString()))
                loadFinished.countDown()
                throw failure
            }
        }
    }
    private fun bookSummary(book: ServiceBook?): Map<String, Any?>? = book?.let {
        mapOf("hash" to it.contentHash, "sourceUrl" to it.sourceUrl, "fetchedAtMillis" to it.fetchedAtMillis,
            "enrichment" to it.enrichment, "blocks" to it.blocks.size,
            "scripture" to it.blocks.filter { block -> block.kind == BookBlockKind.SCRIPTURE }.map { block ->
                mapOf("id" to block.id, "references" to block.scriptureReferences,
                    "russianLength" to block.russian?.length, "russianSha256" to block.russian?.let(BookText::sha256),
                    "source" to block.translationSource)
            })
    }
    private fun recordLoad(event: String, fields: Map<String, Any?> = emptyMap()) {
        synchronized(loadEvents) {
            loadEvents += linkedMapOf<String, Any?>("elapsedMillis" to SystemClock.elapsedRealtime() - startedAt,
                "event" to event).apply { putAll(fields) }
            runCatching { File(output, "load-diagnostics.json").writeText(Gson().toJson(loadEvents.toList())) }
        }
    }

    private fun dismissSheetIfPresent() {
        val dismiss = compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss))
        if (dismiss.fetchSemanticsNodes().isNotEmpty()) {
            dismiss.assertCountEquals(1)
            dismiss[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { assertTrue(it()) }
            compose.waitForIdle()
        }
    }
    private fun asset(path: String) = instrumentation.context.assets.open(path).bufferedReader().use { it.readText() }
    private fun saved() = requireNotNull(store.last(parishId))
    private fun waitFor(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }
    private fun assertPosition(expected: BookReadingPosition, actual: BookReadingPosition) {
        assertEquals(expected.copy(fraction = actual.fraction), actual)
        assertEquals(expected.fraction, actual.fraction, .001f)
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
