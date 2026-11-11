package ru.liturgyguide.service.book

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleTransport

/** Real weekday, Sunday, combined vigil and month-boundary books, including a new offline repository. */
class LiveServiceBookMatrixTest {
    @Test fun datedBooksRetainTheirWholeOriginalAndExactTranslationsOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("feoriaLiveNetwork") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.cacheDir.resolve("live-book-matrix-${System.nanoTime()}")
        val report = mutableListOf<Map<String, Any?>>()
        val unavailable = mutableListOf<String>()
        val output = context.filesDir.resolve("live-book-matrix-08.json")
        try {
            val online = ServiceBookRepository(FileBookCache(directory))
            val keys = listOf(ServiceBookKey("2026-11-11"), ServiceBookKey("2026-11-15"),
                ServiceBookKey("2026-11-15", WorshipKind.VIGIL), ServiceBookKey("2026-12-01"))
            for (key in keys) {
                val started = System.nanoTime()
                val result = online.load(key)
                val ready = result as? BookLoadResult.Ready
                val book = ready?.book
                report += mapOf("key" to key, "result" to result.javaClass.simpleName,
                    "saved" to ready?.saved, "fromCache" to ready?.fromCache, "warning" to ready?.warning,
                    "chapters" to book?.chapters?.map { it.title }, "blocks" to book?.blocks?.size,
                    "prayers" to book?.prayerCount, "translatedPrayers" to book?.translatedPrayerCount,
                    "scripture" to book?.blocks?.filter { it.kind == BookBlockKind.SCRIPTURE }?.map {
                        mapOf("references" to it.scriptureReferences, "russian" to (it.russian != null)) },
                    "hash" to book?.contentHash, "sources" to book?.sources,
                    "elapsedMillis" to (System.nanoTime() - started) / 1_000_000)
                output.writeText(Gson().toJson(report))
                if (ready == null) {
                    unavailable += "$key: $result"
                    continue
                }
                assertTrue("$key must be persisted", ready.saved)
                assertFalse("Each selected book must be downloaded in this run", ready.fromCache)
                assertEquals(key, ready.book.key)
                assertTrue("Only published translations belong to the complete book", ready.book.blocks.filter {
                    it.russian != null && it.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE) }
                    .all { it.translationSource != null && it.translationSource != BookTranslationIndex.EDITORIAL_SOURCE })
                assertTrue("$key: complete validated order", ServiceBookIntegrity.valid(ready.book))
                assertTrue("$key: actual source text", ready.book.blocks.size > 200)
                assertTrue("$key: final dismissal", ready.book.chapters.any { BookText.normalized(it.title).contains("отпуст") })
                if (key.worship == WorshipKind.LITURGY) {
                    val readings = ready.book.blocks.filter { it.kind == BookBlockKind.SCRIPTURE }
                    assertTrue("$key: dated Scripture in Russian", readings.isNotEmpty() && readings.all { it.russian != null })
                }
                val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { error("Network forbidden in offline check") })
                val restored = offline.load(key) as BookLoadResult.Ready
                assertTrue(restored.fromCache)
                assertEquals(ready.book, restored.book)
                assertEquals(ready.book, offline.cachedVersion(key, ready.book.contentHash))
            }
            assertTrue("Every requested book must load: $unavailable", unavailable.isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
