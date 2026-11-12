package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A real old native book and local repository. No captured response is called a live result. */
@RunWith(AndroidJUnit4::class)
class FeoriaTranslationAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefix = "feoria-translation-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(prefix, Context.MODE_PRIVATE)
    private val store = BookReadingStore(preferences)
    private val directory = File(context.cacheDir, prefix)
    private val output = File(context.getExternalFilesDir("feoria-translation-qa"), prefix).apply { mkdirs() }
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val calls = CopyOnWriteArrayList<String>()
    private val openedUrls = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<Map<String, Any?>>()
    private val start = SystemClock.elapsedRealtime()
    private val release = CompletableDeferred<Unit>()
    private val firstFinished = CountDownLatch(1)
    private val firstClaimed = AtomicBoolean(false)
    private val firstReady = AtomicReference<BookLoadResult.Ready?>(null)
    private val firstFailure = AtomicReference<Throwable?>(null)
    private val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34", topLevel = "3")
    private val parishId = "feoria-translation-fixture"
    private val ownSource = BookSource("Перевод Феории", "https://github.com/jeymog888/PravoslavnyyProvodnik")
    private val original = "Прииди́те, поклони́мся и припаде́м Самому́ Христу́, Царе́ви и Бо́гу на́шему."
    private val expected = "Придите, поклонимся и падем ниц перед Самим Христом, нашим Царем и Богом."
    private val receipt = linkedMapOf<String, Any?>("status" to "RUNNING", "scope" to "local catalog with actual old native book/repository/reader; optional network disabled")

    @After fun clean() {
        if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        context.deleteSharedPreferences(prefix)
        directory.deleteRecursively()
    }

    @Test fun oldBookOffersReviewedLocalTranslationThenKeepsAcceptedRussianAndAttributionOffline() {
        try {
            val old = instrumentation.context.assets.open("polyeleos-journey/ranked-matins-2026-11-13-church34.json")
                .bufferedReader().use { Gson().fromJson(it, ServiceBook::class.java) }
            assertEquals("4b9b2e7278f3d670ee813dd2afd341fcb7b2d33b0348a0a4754c0f36ad2f8b79", old.contentHash)
            assertTrue(ServiceBookIntegrity.valid(old)); assertEquals(key, old.key)
            val target = old.blocks.single { it.original == original }
            assertNull(target.russian)
            val chapter = old.chapters.single { target in it.blocks }
            val cache = FileBookCache(directory)
            assertTrue(cache.write(old))
            val oldFile = File(directory, "${BookText.sha256(key.canonical()).take(32)}-${old.contentHash}.json")
            val oldBytes = oldFile.readBytes()
            val initial = BookReadingPosition(key, old.contentHash, parishId, target.id, chapter.id,
                fraction = .24f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)
            assertTrue(store.save(initial))
            val owner = mutableStateOf<ServiceBooks>(observed(repository()))
            compose.setContent { GuideTheme(false) {
                if (mounted.value) ServiceBookScreen(key, parishId, owner.value, store, 0,
                    onBack = {}, onSettings = {}, onOpenUrl = { openedUrls += it }, onOpenCommonLiturgy = {})
            } }
            contentSet = true
            waitFor("book_content"); settle()
            assertPosition(initial, saved())
            compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(original)
            compose.onNodeWithTag("book_feoria_translation_${target.id}").assertDoesNotExist()
            compose.onNodeWithTag("book_accept_update").assertDoesNotExist()
            assertTrue(calls.isEmpty())
            compose.runOnIdle { release.complete(Unit) }
            // The local book transformation owns its bounded completion; UI has its own 5s deadline.
            assertTrue("Local enrichment must finish within 20 seconds", firstFinished.await(20, TimeUnit.SECONDS))
            assertNull(firstFailure.get())
            val ready = requireNotNull(firstReady.get())
            assertTrue(ready.saved); assertTrue(ready.fromCache)
            waitFor("book_accept_update"); settle()
            val newer = ready.book
            assertEquals(newer, cache.read(key)); assertTrue(ServiceBookIntegrity.valid(newer))
            assertEquals(104, newer.blocks.count { it.translationSource == ownSource })
            assertEquals(477, newer.blocks.count { it.russian != null })
            old.blocks.zip(newer.blocks).forEach { (before, after) ->
                if (after.translationSource == ownSource) {
                    assertNull(before.russian)
                    assertEquals(before, after.copy(russian = null, translationSource = before.translationSource))
                } else assertEquals(before, after)
            }
            assertEquals(old, newer.copy(chapters = old.chapters, sources = old.sources, contentHash = old.contentHash))
            assertPosition(initial, saved()); assertArrayEquals(oldBytes, oldFile.readBytes()); assertTrue(calls.isEmpty())
            compose.onNodeWithTag("book_accept_update").assertIsDisplayed().performClick()
            settle()
            val accepted = saved()
            assertPosition(initial.copy(contentHash = newer.contentHash, fraction = 0f), accepted)
            compose.onNodeWithTag("book_text_${target.id}").assertIsDisplayed().assertTextEquals(expected)
            compose.onNodeWithTag("book_feoria_translation_${target.id}").assertIsDisplayed().assertTextEquals("Перевод Феории")
            assertEquals(ownSource, newer.blocks.single { it.id == target.id }.translationSource)
            capture("accepted-local-russian")
            chooseMode(BookReadingMode.ORIGINAL)
            compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(original)
            compose.onNodeWithTag("book_feoria_translation_${target.id}").assertDoesNotExist()
            chooseMode(BookReadingMode.RUSSIAN)
            assertPosition(accepted, saved())
            compose.onNodeWithContentDescription("Настроить чтение").performClick()
            compose.onNodeWithText("Источники и вариант службы").performScrollTo().performClick()
            val sourceList = compose.onNode(hasScrollToIndexAction() and !hasTestTag("book_content"))
            sourceList.performScrollToNode(hasTestTag("book_feoria_translation_origin"))
            compose.onAllNodesWithTag("book_feoria_translation_origin").assertCountEquals(1)
            compose.onNodeWithTag("book_feoria_translation_origin").assertIsDisplayed().assertHasNoClickAction()
                .assertTextContains("переведены нами с церковнославянского оригинала", substring = true)
            compose.onAllNodes(hasText("Перевод Феории") and hasClickAction()).assertCountEquals(0)
            assertTrue(openedUrls.isEmpty())
            capture("local-origin-explanation")
            dismissSheet()
            assertPosition(accepted, saved())
            compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
            val offline = repository()
            compose.runOnIdle { owner.value = offline; mounted.value = true }
            waitFor("book_content"); settle()
            assertPosition(accepted, saved())
            compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(expected)
            compose.onNodeWithTag("book_feoria_translation_${target.id}").assertTextEquals("Перевод Феории")
            assertEquals(newer, offline.cachedVersion(key, accepted.contentHash))
            assertTrue(calls.isEmpty()); assertArrayEquals(oldBytes, oldFile.readBytes())
            receipt.putAll(mapOf("status" to "PASS", "oldHash" to old.contentHash, "newHash" to newer.contentHash,
                "addedOwnBlocks" to 104, "russianBlocks" to 477, "networkRequests" to calls.size,
                "oldBytesPreserved" to true, "acceptedPosition" to accepted, "source" to ownSource))
        } catch (failure: Throwable) {
            receipt["status"] = "FAIL"; receipt["error"] = failure.toString()
            runCatching { capture("failure") }
            runCatching { File(output, "semantics.txt").writeText(compose.onRoot().printToString()) }
            runCatching { File(output, "isolated-state.txt").writeText("preferences=${preferences.all}\nrequests=$calls\nopenedUrls=$openedUrls") }
            throw failure
        } finally {
            receipt["events"] = events.toList()
            receipt["cacheCurrentHash"] = runCatching { FileBookCache(directory).read(key)?.contentHash }.getOrNull()
            File(output, "receipt.json").writeText(Gson().toJson(receipt))
            android.util.Log.i("FeoriaTranslationQA", "receipt=${File(output, "receipt.json").absolutePath}")
        }
    }

    private fun repository() = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
        calls += url; error("The local catalog and accepted offline book require no transport")
    }, translations = BookTranslationIndex.fromFeoriaCatalog(), downloadRussian = false)
    private fun observed(repository: ServiceBookRepository): ServiceBooks = object : ServiceBooks by repository {
        override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? {
            val first = firstClaimed.compareAndSet(false, true)
            return try {
                release.await()
                record("enrichment-start", mapOf("acceptedHash" to accepted.contentHash, "allowNetwork" to allowNetwork))
                repository.enrichSaved(accepted, allowNetwork).also { result ->
                    if (first) firstReady.set(result)
                    record("enrichment-result", mapOf("kind" to if (result == null) "null" else "Ready",
                        "saved" to result?.saved, "hash" to result?.book?.contentHash,
                        "cacheCurrentHash" to repository.cached(key)?.contentHash))
                }
            } catch (failure: Throwable) {
                if (first) firstFailure.set(failure)
                record("enrichment-threw", mapOf("error" to failure.toString()))
                throw failure
            } finally { if (first) firstFinished.countDown() }
        }
    }
    private fun record(event: String, fields: Map<String, Any?>) {
        synchronized(events) {
            events += linkedMapOf<String, Any?>("elapsedMillis" to SystemClock.elapsedRealtime() - start,
                "event" to event).apply { putAll(fields) }
            runCatching { File(output, "enrichment-events.json").writeText(Gson().toJson(events.toList())) }
        }
    }
    private fun chooseMode(mode: BookReadingMode) {
        val tag = "book_mode_${mode.name.lowercase()}"
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("book_mode_menu").performClick()
        compose.onNodeWithTag(tag).performClick(); settle()
    }
    private fun dismissSheet() {
        val dismiss = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        dismiss.assertCountEquals(1)
        dismiss[0].performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        compose.waitForIdle()
    }
    private fun waitFor(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }
    private fun saved() = requireNotNull(store.last(parishId))
    private fun assertPosition(expected: BookReadingPosition, actual: BookReadingPosition) {
        assertEquals(expected.copy(fraction = actual.fraction), actual)
        assertEquals(expected.fraction, actual.fraction, .003f)
    }
    private fun capture(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
