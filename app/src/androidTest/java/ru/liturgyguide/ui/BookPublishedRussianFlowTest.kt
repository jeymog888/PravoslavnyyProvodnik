package ru.liturgyguide.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
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
import java.util.concurrent.atomic.AtomicInteger

/** Real Android reader + production repository, downloaded HTML, explicit acceptance and exact offline remount. */
@RunWith(AndroidJUnit4::class)
class BookPublishedRussianFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "published-russian-${UUID.randomUUID()}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, name)
    private val cache = FileBookCache(directory)
    private val books = mutableStateOf<ServiceBooks?>(null)
    private val reading = mutableStateOf(BookReadingStore(prefs))
    private val mounted = mutableStateOf(true)
    private val gate = CompletableDeferred<Unit>()
    private val requested = CopyOnWriteArrayList<String>()
    private val offlineRequests = AtomicInteger()
    private var contentSet = false
    private val parish = "published-russian-test"
    private fun asset(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun published(name: String) = asset("published-russian/$name")

    @After fun clean() {
        gate.complete(Unit)
        if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun directSundayMatinsOffersThePublishedRussianAndAcceptsItAtTheSameHymnOffline() {
        val key = ServiceBookKey("2026-11-15", WorshipKind.MATINS)
        val raw = ServiceBookParser().parse(Jsoup.parse(published("matins-2026-11-15.html"), key.sourceUrl()), key, 12345)
        val calendar = BookCalendarDay(key.date, "Неделя 24-я по Пятидесятнице", 7, emptyList(), "https://azbyka.ru/days/${key.date}")
        val before = raw.copy(calendarDay = calendar).let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        assertTrue(ServiceBookIntegrity.valid(before)); assertTrue(cache.write(before))
        val oldCorpus = BookTranslationCorpus.decode(published("corpus11-3694.json"))
        val completed = BookTranslationIndex.requiredSources(WorshipKind.MATINS).map { it.url }.toSet() + oldCorpus.completedSources
        assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(oldCorpus.index, completed).encode()))
        val oracle = JsonParser.parseString(published("sunday-coverage-oracle.json")).asJsonArray[0].asJsonObject
        val expected = oracle.getAsJsonArray("rows")[0].asJsonObject
        val target = before.blocks.single { it.id == expected.get("id").asString }
        val chapter = before.chapters.single { target in it.blocks }
        assertNull(target.russian)
        val position = BookReadingPosition(key, before.contentHash, parish, target.id, chapter.id,
            mode = BookReadingMode.RUSSIAN, wordsPerMinute = 107, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(reading.value.save(position))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
        val immutable = oldFile.readBytes()
        books.value = ServiceBookRepository(cache, ScheduleTransport { url ->
            requested += url
            assertEquals(BookTranslationIndex.OCTOECHOS7_SOURCE.url, url)
            gate.await()
            ScheduleResponse(url, published("sunday-7.html").toByteArray(Charsets.UTF_8), "text/html;charset=utf-8")
        })
        compose.setContent { GuideTheme(false) {
            if (mounted.value) ServiceBookScreen(key, parish, requireNotNull(books.value), reading.value, 0,
                onBack = {}, onSettings = {}, onOpenUrl = {}, onOpenCommonLiturgy = {})
        } }
        contentSet = true
        compose.waitUntil(10_000) { requested.isNotEmpty() }
        compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(target.original)
        compose.onNodeWithTag("book_missing_russian_${target.id}").assertExists()
        assertEquals(before.contentHash, reading.value.positionFor(key, parish)?.contentHash)
        gate.complete(Unit)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("book_accept_update").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(BookTranslationIndex.OCTOECHOS7_SOURCE.url), requested.toList())
        assertEquals(before.contentHash, reading.value.positionFor(key, parish)?.contentHash)
        compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(target.original)
        compose.onNodeWithTag("book_accept_update").performClick()
        compose.waitUntil(10_000) { reading.value.positionFor(key, parish)?.contentHash != before.contentHash }
        compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(expected.get("russian").asString)
        compose.onNodeWithTag("book_missing_russian_${target.id}").assertDoesNotExist()
        val acceptedPosition = requireNotNull(reading.value.positionFor(key, parish))
        assertEquals(target.id, acceptedPosition.blockId)
        assertEquals(BookReadingMode.RUSSIAN, acceptedPosition.mode)
        assertEquals(107, acceptedPosition.wordsPerMinute)
        val accepted = requireNotNull(cache.readVersion(key, acceptedPosition.contentHash))
        assertEquals(before.blocks.map { it.original }, accepted.blocks.map { it.original })
        assertEquals(before.calendarDay, accepted.calendarDay)
        assertEquals(BookTranslationIndex.OCTOECHOS7_SOURCE, accepted.blocks.single { it.id == target.id }.translationSource)
        assertArrayEquals(immutable, oldFile.readBytes())
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { offlineRequests.incrementAndGet(); error("Offline transport") })
        compose.runOnIdle { books.value = offline; reading.value = BookReadingStore(prefs); mounted.value = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_text_${target.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(expected.get("russian").asString)
        val reopenedPosition = requireNotNull(reading.value.positionFor(key, parish))
        assertEquals(acceptedPosition.copy(fraction = reopenedPosition.fraction), reopenedPosition)
        assertEquals(acceptedPosition.fraction, reopenedPosition.fraction, .015f)
        assertEquals(accepted, offline.cachedVersion(key, accepted.contentHash))
        assertEquals(before, offline.cachedVersion(key, before.contentHash))
        assertEquals(0, offlineRequests.get())
        assertArrayEquals(immutable, oldFile.readBytes())

        // An explicitly pinned old edition must remain old even when latest is newer.
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        assertTrue(BookReadingStore(prefs).save(position))
        compose.runOnIdle {
            reading.value = BookReadingStore(prefs)
            books.value = ServiceBookRepository(FileBookCache(directory), ScheduleTransport {
                offlineRequests.incrementAndGet(); error("Offline old edition")
            })
            mounted.value = true
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_text_${target.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book_text_${target.id}").assertTextEquals(target.original)
        compose.onNodeWithTag("book_missing_russian_${target.id}").assertExists()
        assertEquals(before.contentHash, reading.value.positionFor(key, parish)?.contentHash)
        assertEquals(target.id, reading.value.positionFor(key, parish)?.blockId)
        assertEquals(BookReadingMode.RUSSIAN, reading.value.positionFor(key, parish)?.mode)
        assertEquals(107, reading.value.positionFor(key, parish)?.wordsPerMinute)
        assertEquals(accepted.contentHash, FileBookCache(directory).read(key)?.contentHash)
        assertEquals(0, offlineRequests.get())
        assertArrayEquals(immutable, oldFile.readBytes())
    }

    @Test fun realArchivedFridayGainsTheEightReviewedIrmosAndKeepsItsImmutableEdition() = runBlocking {
        val bytes = asset("quiet-matins/matins-accepted-unchanged.json")
        val before = Gson().fromJson(bytes, ServiceBook::class.java)
        assertEquals("bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", before.contentHash)
        assertTrue("Do not repair or rehash the actual accepted Android edition", ServiceBookIntegrity.valid(before))
        assertTrue(cache.write(before))
        val required = BookTranslationIndex.requiredSources(WorshipKind.MATINS).map { it.url }.toSet()
        // A nonempty unrelated exact pair keeps completed core flags meaningful, without pre-filling the archive.
        val core = BookTranslationIndex.PARALLEL_SOURCES[0]
        val unrelated = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table><tr><td>Иная отдельная молитва</td><td>Иной отдельный перевод</td></tr></table>", core.url), core)
        assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(unrelated, required).encode()))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
        val immutable = oldFile.readBytes()
        val calls = CopyOnWriteArrayList<String>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            calls += url
            val file = when (url) {
                BookPublishedIrmos6.SATURDAY.url -> "great-saturday.html"
                BookPublishedIrmos6.FRIDAY.url -> "great-friday.html"
                else -> error("No other source belongs to the eight reviewed irmoi")
            }
            ScheduleResponse(url, published(file).toByteArray(Charsets.UTF_8), "text/html;charset=utf-8")
        }, translations = BookTranslationIndex.empty())
        val candidate = requireNotNull(repository.enrichSaved(before)).book
        assertEquals(BookPublishedIrmos6.SOURCES.map { it.url }, calls.toList())
        val cases = JsonParser.parseString(published("irmos6-cases.json")).asJsonArray.map { it.asJsonObject }
        val gained = before.blocks.zip(candidate.blocks).filter { (a, b) -> a.russian == null && b.russian != null }
        // This source-isolated case tests the eight published irmoi. The independent
        // BookReviewedRussianFlowTest covers delivery of the 48 local refrains.
        assertEquals(8, gained.size)
        assertEquals(cases.map { it.get("blockId").asString }.toSet(), gained.map { it.first.id }.toSet())
        cases.forEach { expected ->
            val block = candidate.blocks.single { it.id == expected.get("blockId").asString }
            assertEquals(expected.get("proposedRussian").asString, block.russian)
            assertEquals(expected.get("sourceUrl").asString, block.translationSource?.url)
        }
        assertEquals(before.blocks.map { it.original }, candidate.blocks.map { it.original })
        val target = gained.first().first
        val chapter = before.chapters.single { target in it.blocks }
        val position = BookReadingPosition(before.key, before.contentHash, parish, target.id, chapter.id,
            mode = BookReadingMode.RUSSIAN, wordsPerMinute = 111, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(reading.value.save(position))
        assertEquals(before.contentHash, reading.value.positionFor(before.key, parish)?.contentHash)
        assertTrue(reading.value.acceptVersion(before, candidate, parish, position))
        val acceptedPosition = requireNotNull(reading.value.positionFor(before.key, parish))
        assertEquals(target.id, acceptedPosition.blockId); assertEquals(candidate.contentHash, acceptedPosition.contentHash)
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { offlineRequests.incrementAndGet(); error("Offline") })
        assertEquals(candidate, offline.cachedVersion(before.key, candidate.contentHash))
        assertEquals(before, offline.cachedVersion(before.key, before.contentHash))
        assertEquals(acceptedPosition, BookReadingStore(prefs).positionFor(before.key, parish))
        assertNull(offline.enrichSaved(candidate, allowNetwork = false))
        assertEquals(0, offlineRequests.get())
        assertArrayEquals(immutable, oldFile.readBytes())
    }
}
