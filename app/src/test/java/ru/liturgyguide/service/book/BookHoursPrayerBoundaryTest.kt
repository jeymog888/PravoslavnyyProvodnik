package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files

/** The full captured prayer, not a nearby phrase or a shortened translation. */
class BookHoursPrayerBoundaryTest {
    private val directory = Files.createTempDirectory("hours-prayer-boundary-").toFile()
    private val oracle = JsonParser.parseString(text("oracle.json")).asJsonObject
    private val original = oracle["original"].asString
    private val russian = oracle["russian"].asString
    private val editions = listOf(BookTranslationIndex.HOUR1_SOURCE, BookTranslationIndex.HOUR3_SOURCE,
        BookTranslationIndex.HOUR6_SOURCE, BookTranslationIndex.HOUR9_SOURCE)
    @After fun cleanup() { directory.deleteRecursively() }
    private fun text(name: String) = requireNotNull(javaClass.getResourceAsStream("/book/hour-prayer/$name"))
        .bufferedReader().use { it.readText() }
    private fun page() = Jsoup.parse(text("paired-row.html"), BookTranslationIndex.HOUR3_SOURCE.url)
    private fun oldIndex() = BookTranslationIndex.decode(JsonArray().apply { add(oracle["oldCombinedPair"]) }.toString())

    @Test fun fourInspectedHoursExposeTheWholeBarePrayerAndRetainTheCombinedPublishedRow() {
        assertEquals(oracle["originalSha256"].asString, BookText.sha256(original))
        assertEquals(oracle["russianSha256"].asString, BookText.sha256(russian))
        assertEquals(oracle["normalizedOriginalSha256"].asString, BookText.sha256(BookTranslationIndex.normal(original)))
        assertEquals(oracle["normalizedRussianSha256"].asString, BookText.sha256(BookTranslationIndex.normal(russian)))
        for (source in editions) {
            val document = page()
            val unchanged = document.outerHtml()
            val index = BookTranslationIndex.fromParallelPage(document, source)
            assertEquals(2, index.size)
            val pair = requireNotNull(index.find(original))
            assertEquals(russian, pair.russian); assertEquals(source, pair.source); assertNull(pair.meaning)
            val combined = oracle["oldCombinedPair"].asJsonObject
            assertEquals(combined["russian"].asString, index.find(combined["original"].asString)?.russian)
            assertNull(index.find("Не $original")); assertNull(index.find("$original Иная молитва"))
            assertNull(index.find(original.substringBeforeLast(' ')))
            assertEquals(unchanged, document.outerHtml())
        }
    }

    @Test fun mismatchedCountersExtraParagraphsAndChangedWholeBodiesNeverCreateTheBarePair() {
        fun left(d: Document) = d.select("td")[0]
        fun right(d: Document) = d.select("td")[1]
        val changes = listOf<Pair<String, (Document) -> Unit>>(
            "left count41" to { d -> left(d).select("span.k").single().text("41.") },
            "right count3" to { d -> right(d).select("span.k").single().text("(3)") },
            "missing left count" to { d -> left(d).select("span.k").remove() },
            "extra count" to { d -> left(d).prependElement("span").attr("class", "k").text("40.") },
            "extra preceding prayer" to { d -> left(d).prependText("Аминь. ") },
            "extra left paragraph" to { d -> left(d).appendElement("p").text("Иная молитва") },
            "extra right paragraph" to { d -> right(d).appendElement("p").text("Иная молитва") },
            "missing left paragraph boundary" to { d -> left(d).select("p").last()!!.unwrap() },
            "missing right paragraph boundary" to { d -> right(d).select("p").last()!!.unwrap() },
            "changed original word" to { d ->
                val node = left(d).select("p").last()!!.childNodes().filterIsInstance<TextNode>().last()
                node.text(node.text().replace("будущих благ", "нынешних благ"))
            },
            "truncated Russian ending" to { d ->
                val node = right(d).select("p").last()!!.childNodes().filterIsInstance<TextNode>().last()
                node.text(node.text().substringBeforeLast("Аминь."))
            },
            "extra Russian prayer" to { d -> right(d).select("p").last()!!.appendText(" Иная молитва") }
        )
        for ((label, change) in changes) {
            val document = page(); change(document)
            val index = BookTranslationIndex.fromParallelPage(document, BookTranslationIndex.HOUR3_SOURCE)
            assertNull(label, index.find(original))
            document.select("td").first()!!.select("p").last()?.let { body ->
                assertNull("$label must not translate its changed bare body", index.find(BookText.clean(body.text())))
            }
        }
    }

    @Test fun onlyTheFourRegisteredHttpsPagesCanExposeThisNewBoundaryAndOlderExactPairsWin() {
        val source = BookTranslationIndex.HOUR3_SOURCE
        val invalid = listOf(source.copy(url = source.url.replace("https:", "http:")),
            source.copy(url = source.url.replace("azbyka.ru", "other.example")),
            source.copy(url = source.url.replace("azbyka.ru", "sub.azbyka.ru")),
            source.copy(url = source.url.replace("azbyka.ru", "reader@azbyka.ru")),
            source.copy(url = source.url.replace("azbyka.ru", "azbyka.ru:444")),
            source.copy(url = source.url.removeSuffix("/")),
            source.copy(url = source.url + "another/"),
            source.copy(url = source.url.replace("chas-", "%63has-")),
            source.copy(url = BookTranslationIndex.VIGIL_SOURCE.url), source.copy(title = "Иной источник"))
        invalid.forEach { assertNull(it.toString(), BookTranslationIndex.fromParallelPage(page(), it).find(original)) }
        val document = page()
        document.selectFirst("table")!!.prependElement("tr").apply {
            appendElement("td").text(original)
            appendElement("td").text("Ранее опубликованный полный точный перевод QA")
        }
        val index = BookTranslationIndex.fromParallelPage(document, source)
        assertEquals("Ранее опубликованный полный точный перевод QA", index.find(original)?.russian)
        val derivedOnly = BookTranslationIndex.fromParallelPage(page(), source)
        assertEquals(index.find(original), derivedOnly.plus(index).find(original))
        assertEquals(index.find(original), BookTranslationIndex.decode(derivedOnly.encode()).plus(index).find(original))
    }

    @Test fun corpusThirteenKeepsAllOldPairsAndReopensOnlyTheFourChangedHourPages() {
        val all = BookTranslationIndex.ALL_PARALLEL_SOURCES.map { it.url }.toSet()
        val previous = oldIndex()
        assertNull(previous.find(original))
        val encoded = BookTranslationCorpus(previous, all, version = 13).encode()
        val migrated = BookTranslationCorpus.decode(encoded)
        assertEquals(14, migrated.version); assertTrue(migrated.current)
        assertEquals(previous.encode(), migrated.index.encode())
        assertEquals(all - editions.map { it.url }.toSet(), migrated.completedSources)
        assertTrue(migrated.completeFor(WorshipKind.LITURGY)); assertTrue(migrated.completeFor(WorshipKind.MATINS))
        listOf(WorshipKind.HOUR1, WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.HOUR9).forEach {
            assertFalse(it.name, migrated.completeFor(it))
        }
        val twelve = BookTranslationCorpus.decode(BookTranslationCorpus(previous, all, version = 12).encode())
        assertEquals(all - BookTranslationCorpus.REEXTRACTED_SOURCES - editions.map { it.url }.toSet(), twelve.completedSources)
        assertEquals(previous.encode(), twelve.index.encode())
        assertEquals(migrated.encode(), BookTranslationCorpus.decode(migrated.encode()).encode())
        assertEquals(encoded, BookTranslationCorpus(previous, all, version = 13).encode())
    }

    @Test fun theCapturedThirdHourGainsOnlyItsMissingPrayerAndBothHostEditionsReadBackOffline() = runBlocking {
        val raw = text("actual-hour3-2026-11-15.json")
        assertEquals(oracle["bookSha256"].asString, BookText.sha256(raw))
        val captured = Gson().fromJson(raw, ServiceBook::class.java)
        val wire = JsonParser.parseString(raw).asJsonObject.apply {
            addProperty("fetchedAtMillis", 0L); addProperty("contentHash", "")
        }
        assertEquals("63d4c2ee5cb5a38c41b2609e2b28832238e0da10cc8df87b0164b78c849346c6", captured.contentHash)
        assertEquals(captured.contentHash, BookText.sha256(Gson().toJson(wire)))
        // Android and JVM reflect data-class fields in different orders. Preserve
        // the captured wire checksum, then materialize only its hash for this JVM cache.
        val before = captured.copy(contentHash = ServiceBookIntegrity.contentHash(captured))
        assertEquals(captured.copy(contentHash = ""), before.copy(contentHash = ""))
        assertTrue(ServiceBookIntegrity.valid(before))
        val target = before.blocks.single { it.id == oracle["blockId"].asString }
        assertEquals(original, target.original); assertNull(target.russian); assertFalse(target.isAltarText)
        assertEquals(BookBlockKind.PRAYER, target.kind)
        val disk = FileBookCache(directory)
        assertTrue(disk.write(before))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
        val oldBytes = oldFile.readBytes()
        val index = BookTranslationIndex.fromParallelPage(page(), BookTranslationIndex.HOUR3_SOURCE)
        assertTrue(disk.writeTranslationCorpus(BookTranslationCorpus(index).encode()))
        var httpCalls = 0
        val repository = ServiceBookRepository(disk, ScheduleTransport { httpCalls++; error("No network in this scenario") })
        val after = requireNotNull(repository.enrichSaved(before, allowNetwork = false)).book
        val changed = before.blocks.zip(after.blocks).filter { it.first != it.second }
        assertEquals(1, changed.size); assertEquals(target, changed.single().first)
        assertEquals(target.copy(russian = russian, translationSource = BookTranslationIndex.HOUR3_SOURCE), changed.single().second)
        assertEquals(before.chapters.map { it.id }, after.chapters.map { it.id })
        assertEquals(before.key, after.key); assertEquals(before.sourceUrl, after.sourceUrl)
        assertEquals(before.notices, after.notices); assertEquals(before.calendarDay, after.calendarDay)
        assertEquals(before.enrichment, after.enrichment)
        assertEquals(38, before.blocks.count { it.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE) && it.russian != null })
        assertEquals(39, after.blocks.count { it.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE) && it.russian != null })
        assertEquals(0, httpCalls)
        assertEquals(before, disk.readVersion(before.key, before.contentHash)); assertArrayEquals(oldBytes, oldFile.readBytes())
        val cold = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { httpCalls++; error("Still offline") })
        assertEquals(after, (cold.load(before.key) as BookLoadResult.Ready).book)
        assertEquals(before, cold.cachedVersion(before.key, before.contentHash)); assertEquals(0, httpCalls)
        assertNull(cold.enrichSaved(after, allowNetwork = false))
    }
}
