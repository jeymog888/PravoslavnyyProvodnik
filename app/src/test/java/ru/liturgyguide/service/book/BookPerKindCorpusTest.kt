package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files

class BookPerKindCorpusTest {
    private val directory = Files.createTempDirectory("per-kind-corpus-").toFile()
    private val cache = FileBookCache(directory)
    @After fun clean() { directory.deleteRecursively() }
    private fun urls(kind: WorshipKind) = BookTranslationIndex.requiredSources(kind).map { it.url }.toSet()
    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(), "text/html;charset=utf-8")
    private fun pair(original: String = "Сохраненная молитва", russian: String = "Сохраненный русский текст") =
        "<table><tr><td>${Entities.escape(original)}</td><td>${Entities.escape(russian)}</td></tr></table>"
    private fun initialIndex() = BookTranslationIndex.fromParallelPage(Jsoup.parse(pair()))
    private fun sourcePair(url: String, original: String, russian: String) =
        if (url == BookTranslationIndex.PSALTER_SOURCE.url) PsalterFixtures.page() else pair(original, russian)
    private fun basePage(url: String): String? = when {
        "worship=liturgy" in url -> BookFixtures.html()
        "worship=vespers" in url -> BookFixtures.html("vespers-2026-11-12")
        "worship=matins" in url -> BookFixtures.html("matins-2026-11-12")
        "worship=chas1" in url -> BookFixtures.html("hour1-2026-11-12")
        "/biblia/days/" in url -> "<html></html>"
        url.startsWith("https://azbyka.ru/biblia/?") -> "<html></html>"
        url.startsWith("https://azbyka.ru/days/") -> CalendarFixtures.page(url.substringAfterLast('/'))
        else -> null
    }

    @Test fun liturgyKeepsItsThreeSourcesAndEachOrderHasABoundedRequiredSet() {
        assertEquals(BookTranslationIndex.PARALLEL_SOURCES.map { it.url }.toSet(), urls(WorshipKind.LITURGY))
        assertEquals(3, urls(WorshipKind.LITURGY).size)
        assertFalse(urls(WorshipKind.LITURGY).contains(BookTranslationIndex.VIGIL_SOURCE.url))
        assertEquals(setOf(BookTranslationIndex.VESPERS_SOURCE.url, BookTranslationIndex.VIGIL_SOURCE.url,
            BookTranslationIndex.HOUR3_SOURCE.url, BookTranslationIndex.PSALTER_SOURCE.url), urls(WorshipKind.VESPERS))
        assertEquals(3, urls(WorshipKind.MATINS).size)
        assertEquals(3, urls(WorshipKind.HOUR3).size)
        for (kind in listOf(WorshipKind.HOUR1, WorshipKind.HOUR6)) assertEquals(2, urls(kind).size)
        assertEquals(3, urls(WorshipKind.HOUR9).size)
        assertEquals(urls(WorshipKind.VESPERS) + urls(WorshipKind.MATINS) + urls(WorshipKind.HOUR1), urls(WorshipKind.VIGIL))
    }

    @Test fun partialEveningFailurePreservesLiturgyCompletionAndItsCachedPairs() = runBlocking {
        assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(initialIndex(), urls(WorshipKind.LITURGY)).encode()))
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            requests += url
            if (url == BookTranslationIndex.VESPERS_SOURCE.url) error("Evening page unavailable")
            response(url, sourcePair(url, "Новая молитва $url", "Новый тестовый текст"))
        })
        val evening = repository.load(ServiceBookKey("2026-11-12", WorshipKind.VESPERS)) as BookLoadResult.Ready
        assertEquals(urls(WorshipKind.VESPERS), requests.toSet())
        assertEquals(4, requests.size)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, evening.book.enrichment.russian)
        val saved = BookTranslationCorpus.decode(cache.readTranslationCorpus())
        assertTrue(saved.completeFor(WorshipKind.LITURGY))
        assertFalse(saved.completeFor(WorshipKind.VESPERS))
        assertEquals("Сохраненный русский текст", saved.index.find("Сохраненная молитва")?.russian)
        requests.clear()
        val liturgy = repository.load(ServiceBookKey("2026-11-11")) as BookLoadResult.Ready
        assertEquals(BookSupplementStatus.AVAILABLE, liturgy.book.enrichment.russian)
        assertTrue("Liturgy must not retry an unrelated evening source", requests.isEmpty())
    }

    @Test fun forcedHourRefreshDoesNotInvalidateUnrelatedSources() = runBlocking {
        val all = BookTranslationIndex.ALL_PARALLEL_SOURCES.map { it.url }.toSet()
        assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(initialIndex(), all).encode()))
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            requests += url
            if (url == BookTranslationIndex.HOUR1_SOURCE.url) error("First-hour page unavailable")
            response(url, sourcePair(url, "Обновленная общая молитва", "Новый тестовый текст"))
        })
        val result = repository.load(ServiceBookKey("2026-11-12", WorshipKind.HOUR1), force = true) as BookLoadResult.Ready
        assertEquals(urls(WorshipKind.HOUR1), requests.toSet())
        assertEquals(2, requests.size)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, result.book.enrichment.russian)
        val saved = BookTranslationCorpus.decode(cache.readTranslationCorpus())
        assertEquals(all - BookTranslationIndex.HOUR1_SOURCE.url, saved.completedSources)
        assertTrue(saved.completeFor(WorshipKind.LITURGY))
        assertTrue(saved.completeFor(WorshipKind.HOUR6))
        assertFalse(saved.completeFor(WorshipKind.HOUR1))
        assertNotNull(saved.index.find("Сохраненная молитва"))
    }

    @Test fun sharedVigilSourceIsAttemptedOncePerLoadEvenWhenItFails() = runBlocking {
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            requests += url
            if (url == BookTranslationIndex.VIGIL_SOURCE.url) error("Shared source unavailable")
            response(url, sourcePair(url, "Молитва $url", "Тестовый русский текст"))
        })
        val result = repository.load(ServiceBookKey("2026-11-12", WorshipKind.VIGIL), force = true) as BookLoadResult.Ready
        assertEquals(urls(WorshipKind.VIGIL), requests.toSet())
        assertEquals(requests.size, requests.toSet().size)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, result.book.enrichment.russian)
        assertTrue(ServiceBookIntegrity.valid(result.book))
    }

    @Test fun aFailedCorpusWriteDoesNotLoseSharedTranslationsBetweenVigilParts() = runBlocking {
        var corpusWrites = 0
        val bookOnlyCache = object : BookCache by cache {
            override fun writeTranslationCorpus(json: String): Boolean { corpusWrites++; return false }
        }
        val targets = cases("evening-prayer-originals").filter { it.text("kind") in setOf("MATINS", "HOUR1") }
        assertEquals(2, targets.size)
        fun marker(value: JsonObject) = "Тестовый перевод из общего источника ${value.text("kind")}"
        val sharedPage = targets.joinToString("") { pair(it.text("sourceOriginal"), marker(it)) }
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(bookOnlyCache, ScheduleTransport { url ->
            basePage(url)?.let { return@ScheduleTransport response(url, it) }
            requests += url
            response(url, if (url == BookTranslationIndex.VIGIL_SOURCE.url) sharedPage
                else sourcePair(url, "Другая молитва $url", "Другой тестовый перевод"))
        })
        val key = ServiceBookKey("2026-11-12", WorshipKind.VIGIL)
        val result = repository.load(key) as BookLoadResult.Ready
        assertTrue(result.saved)
        assertTrue(corpusWrites > 0)
        assertNull(cache.readTranslationCorpus())
        assertEquals(urls(WorshipKind.VIGIL), requests.toSet())
        assertEquals("A shared source still has only one attempt", 1,
            requests.count { it == BookTranslationIndex.VIGIL_SOURCE.url })
        assertEquals(BookSupplementStatus.AVAILABLE, result.book.enrichment.russian)
        for (target in targets) {
            val block = result.book.blocks.single {
                BookText.normalized(it.original) == BookText.normalized(target.text("bookOriginal"))
            }
            assertEquals(target.text("kind"), marker(target), block.russian)
            assertEquals(BookTranslationIndex.VIGIL_SOURCE.url, block.translationSource?.url)
        }
        val offline = ServiceBookRepository(bookOnlyCache, ScheduleTransport { error("No network allowed") })
        assertEquals(result.book, (offline.load(key) as BookLoadResult.Ready).book)
    }

    private fun cases(name: String): List<JsonObject> = requireNotNull(javaClass.getResourceAsStream("/book/$name.json"))
        .bufferedReader().use { JsonParser.parseReader(it).asJsonArray.map { value -> value.asJsonObject } }
    private fun JsonObject.text(key: String) = get(key).asString

    @Test fun tenRealWholePsalmsMatchOnlyAfterPairedDomHeadingsAreSeparated() {
        val cases = cases("evening-psalm-originals")
        assertEquals(10, cases.size)
        for (case in cases) {
            val marker = "Тестовый перевод целого псалма ${case.text("id")}" // Synthetic, not a republished modern translation.
            val html = "<table><tr><td><h3>${case.text("sourceHeading")}</h3><p>${Entities.escape(case.text("sourceOriginal"))}</p></td>" +
                "<td><h3>${case.text("russianHeading")}</h3><p>$marker</p></td></tr></table>"
            val index = BookTranslationIndex.fromParallelPage(Jsoup.parse(html, case.text("sourceUrl")))
            assertEquals(case.text("id"), marker, index.find(case.text("bookOriginal"))?.russian)
            assertEquals(case.text("sourceUrl"), index.find(case.text("bookOriginal"))?.source?.url)
            assertNotNull(index.find("${case.text("sourceHeading")} ${case.text("sourceOriginal")}"))
            assertNull("A whole Psalm cannot be given for a shorter refrain", index.find(case.text("bookOriginal").substringBefore('.')))
            val book = ServiceBookParser().parse(Jsoup.parse(BookFixtures.html(case.text("fixture"))),
                ServiceBookKey(case.text("date"), WorshipKind.valueOf(case.text("kind"))), 12345, index)
            assertTrue(case.text("id"), book.blocks.any { it.kind == BookBlockKind.PRAYER && it.russian == marker &&
                BookText.normalized(it.original) == BookText.normalized(case.text("bookOriginal")) })
        }
    }

    @Test fun sixPrimaryPagesSupplyExactWholePrayerMatchesFromRealBooks() {
        val cases = cases("evening-prayer-originals")
        assertEquals(6, cases.size)
        for (case in cases) {
            val marker = "Тестовый перевод ${case.text("id")}" // The source text is preserved; this marker tests alignment only.
            val index = BookTranslationIndex.fromParallelPage(Jsoup.parse(pair(case.text("sourceOriginal"), marker), case.text("sourceUrl")))
            assertEquals(case.text("id"), marker, index.find(case.text("bookOriginal"))?.russian)
            val book = ServiceBookParser().parse(Jsoup.parse(BookFixtures.html(case.text("fixture"))),
                ServiceBookKey(case.text("date"), WorshipKind.valueOf(case.text("kind"))), 12345, index)
            assertTrue(case.text("id"), book.blocks.any { it.kind == BookBlockKind.PRAYER && it.russian == marker })
            assertNull(index.find("не ${case.text("bookOriginal")}"))
        }
    }

    @Test fun differentPsalmNumbersAndUnverifiedHeadingsCannotAuthorizeBodyOnlyMatches() {
        val body = "Глаголы моя внуши, Господи, разумей звание мое. Вонми гласу моления моего."
        fun index(left: String, right: String) = BookTranslationIndex.fromParallelPage(Jsoup.parse(
            "<table><tr><td>$left</td><td>$right</td></tr></table>"))
        assertNull(index("<h3>Псалом 5:</h3>$body", "<h3>Псалом 6</h3>Тестовый перевод").find(body))
        assertNull(index("<h3>Неизвестная ремарка</h3>$body", "<h3>Неизвестная ремарка</h3>Тестовый перевод").find(body))
        assertNull(index("Примечание<h3>Псалом 5:</h3>$body", "<h3>Псалом 5</h3>Тестовый перевод").find(body))
        assertNull(index("<h3>Псалом 5:</h3>$body", "Тестовый перевод без подтвержденного заголовка").find(body))
        val valid = index("<h3>Псалом 5:</h3>$body", "<h3>Псалом 5</h3>Тестовый перевод")
        assertNull(valid.find(body.replace("внуши", "не внуши")))
    }
}
