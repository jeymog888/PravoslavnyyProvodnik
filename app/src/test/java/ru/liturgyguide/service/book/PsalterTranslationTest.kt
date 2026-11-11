package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files

/** Modern published translations stay online; fixture Russian is deliberately synthetic. */
internal object PsalterFixtures {
    val source get() = BookTranslationIndex.PSALTER_SOURCE
    fun marker(number: Int) = "<span class='letter'><sup>$number</sup></span>"
    fun page(rows: String = "<tr><td>${marker(1)}Слова псалма.</td><td>${marker(1)}Тестовый перевод.</td></tr>", psalm: Int = 3): String =
        "<html><head><link rel='canonical' href='${source.url}'></head><body>" +
            "<h1>Псалтирь по кафизмам с переводом и молитвами</h1><table class='adaptive'>" +
            "<tr><td colspan='2'>Псалом $psalm</td></tr>$rows</table></body></html>"
    fun cases(): List<JsonObject> = requireNotNull(javaClass.getResourceAsStream("/book/psalter-original-rows.json"))
        .bufferedReader().use { JsonParser.parseReader(it).asJsonArray.map { value -> value.asJsonObject } }
    fun page(case: JsonObject): String {
        val markers = case.getAsJsonArray("markers").joinToString(" ") { marker(it.asInt) }
        return page("<tr><td>${case.get("originalHtml").asString}</td><td>$markers Тестовый перевод полного псалма.</td></tr>", case.get("psalm").asInt)
    }
}

class PsalterTranslationTest {
    private fun parse(html: String, source: BookSource = PsalterFixtures.source) =
        BookTranslationIndex.fromParallelPage(Jsoup.parse(html, source.url), source)

    @Test fun sixRealPsalmRowsMatchTheEntireOriginalWithoutNumberAliasesOrAnInventedTranslator() {
        val cases = PsalterFixtures.cases()
        assertEquals(6, cases.size)
        for (case in cases) {
            val original = case.get("bookOriginal").asString
            val index = parse(PsalterFixtures.page(case))
            val found = index.find(original)
            assertEquals(case.get("book").asString, "Тестовый перевод полного псалма.", found?.russian)
            assertEquals(PsalterFixtures.source, found?.source)
            assertFalse(requireNotNull(found).source.title.contains("Тимрот"))
            assertNull(index.find(original + " Несовпадающее слово"))
        }
    }

    @Test fun onlyPairedContinuousNumericVerseMarkersAreRemoved() {
        val good = PsalterFixtures.page()
        assertNotNull(parse(good).find("Слова псалма."))
        val badRows = listOf(
            "<tr><td>${PsalterFixtures.marker(1)}Слова псалма.</td><td>${PsalterFixtures.marker(2)}Тестовый перевод.</td></tr>",
            "<tr><td>${PsalterFixtures.marker(1)}Слова псалма.</td><td>Тестовый перевод.</td></tr>",
            "<tr><td>${PsalterFixtures.marker(1)}${PsalterFixtures.marker(3)}Слова псалма.</td><td>${PsalterFixtures.marker(1)}${PsalterFixtures.marker(3)}Тестовый перевод.</td></tr>",
            "<tr><td><sup>1</sup>Слова псалма.</td><td><sup>1</sup>Тестовый перевод.</td></tr>",
            "<tr><td><span class='letter'><sup>а</sup></span>Слова псалма.</td><td><span class='letter'><sup>а</sup></span>Тестовый перевод.</td></tr>",
            "<tr><td><span class='letter'>Слово<sup>2</sup></span></td><td><span class='letter'>Слово<sup>2</sup></span></td></tr>",
            "<tr><td>${PsalterFixtures.marker(1)}Слова псалма.</td><td>${PsalterFixtures.marker(1)}Тестовый перевод.<a href='#_ftna2'>2</a></td></tr>",
            "<tr><td>${PsalterFixtures.marker(1)}Слова псалма.</td><td>${PsalterFixtures.marker(1)}Тестовый перевод.</td><td>Третий столбец</td></tr>"
        )
        for (row in badRows) assertEquals(row, 0, parse(PsalterFixtures.page(row)).size)
        // The same numeric typography on another page is never silently rewritten by the generic importer.
        assertNull(parse(good, BookTranslationIndex.TIMROT_SOURCE).find("Слова псалма."))
    }

    @Test fun missingOrChangedSourceIdentityAndNonPsalmSectionsNeverBecomePsalmTranslations() {
        val html = PsalterFixtures.page()
        assertEquals(0, parse(html.replace(PsalterFixtures.source.url, "https://example.com/")).size)
        assertEquals(0, parse(html.replace("Псалтирь по кафизмам с переводом и молитвами", "Другая книга")).size)
        assertEquals(0, parse(html.replace("Псалом 3", "Молитва после кафизмы")).size)
        assertEquals(0, parse(html.replace("Псалом 3", "Псалом 999")).size)
        assertEquals(0, parse(html.replace("colspan='2'", "colspan='3'")).size)
    }

    @Test fun aNonNumericSuperscriptIsRejectedWithoutMutatingTheSourceDom() {
        val html = PsalterFixtures.page("<tr><td><span class='letter'><sup>а</sup></span>Слова.</td>" +
            "<td><span class='letter'><sup>а</sup></span>Тестовый перевод.</td></tr>")
        val document = Jsoup.parse(html, PsalterFixtures.source.url)
        val original = document.outerHtml()
        assertEquals(0, BookTranslationIndex.fromParallelPage(document, PsalterFixtures.source).size)
        assertEquals(original, document.outerHtml())
    }

    @Test fun psalterIsFetchedOnceForVigilThenReusedFromDiskForAnotherDate() = runBlocking {
        val directory = Files.createTempDirectory("psalter-corpus-").toFile()
        try {
            val requests = mutableListOf<String>()
            val case = PsalterFixtures.cases().first { it.get("book").asString == "matins-2026-11-12" }
            val transport = ScheduleTransport { url ->
                requests += url
                val html = when {
                    url == PsalterFixtures.source.url -> PsalterFixtures.page(case)
                    "worship=vespers" in url -> BookFixtures.html("vespers-2026-11-12")
                    "worship=matins" in url -> BookFixtures.html(if ("2026-11-15" in url) "matins-2026-11-15" else "matins-2026-11-12")
                    "worship=chas1" in url -> BookFixtures.html("hour1-2026-11-12")
                    "/biblia/days/" in url -> "<html></html>"
                    url.startsWith("https://azbyka.ru/days/") -> CalendarFixtures.page(url.substringAfterLast('/'))
                    else -> "<table><tr><td>Общая молитва</td><td>Тестовый перевод общей молитвы</td></tr></table>"
                }
                ScheduleResponse(url, html.toByteArray(), "text/html;charset=utf-8")
            }
            val first = ServiceBookRepository(FileBookCache(directory), transport)
                .load(ServiceBookKey("2026-11-12", WorshipKind.VIGIL)) as BookLoadResult.Ready
            assertEquals(1, requests.count { it == PsalterFixtures.source.url })
            assertTrue(first.book.blocks.any { it.translationSource == PsalterFixtures.source })
            val persisted = BookTranslationCorpus.decode(FileBookCache(directory).readTranslationCorpus())
            assertTrue(persisted.completeFor(WorshipKind.MATINS))
            assertEquals("Тестовый перевод полного псалма.", persisted.index.find(case.get("bookOriginal").asString)?.russian)
            requests.clear()
            val second = ServiceBookRepository(FileBookCache(directory), transport)
                .load(ServiceBookKey("2026-11-15", WorshipKind.MATINS)) as BookLoadResult.Ready
            assertEquals(0, requests.count { it == PsalterFixtures.source.url })
            assertTrue(second.book.blocks.any { it.translationSource == PsalterFixtures.source })
        } finally { directory.deleteRecursively() }
    }
}
