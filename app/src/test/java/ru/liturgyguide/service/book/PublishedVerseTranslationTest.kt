package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.model.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Committed DOM contains synthetic Russian. Real published pages are explicit local opt-in inputs. */
class PublishedVerseTranslationTest {
    private val directory = Files.createTempDirectory("published-verses-").toFile()
    private val psalter = BookTranslationIndex.PSALTER_SOURCE
    private val vigil = BookTranslationIndex.VIGIL_SOURCE
    @After fun clean() { directory.deleteRecursively() }

    @Test fun directVerseBoundariesPreserveInlineWordsAndTheOldWholeRowWithoutJoiningVerses() {
        val good = row("${m(1)}Первое <em>слово</em>. ${m(2)}Второе <span>слово</span>.",
            "${m(1)}Первый <em>перевод</em>. ${m(2)}Второй <a>перевод</a>.")
        val document = Jsoup.parse(PsalterFixtures.page(good))
        val originalDom = document.outerHtml()
        val parsed = parse(document)
        assertEquals(BookTranslation("Первый перевод.", psalter), parsed.find("Первое слово."))
        assertEquals(BookTranslation("Второй перевод.", psalter), parsed.find("Второе слово."))
        assertEquals("Первый перевод. Второй перевод.", parsed.find("Первое слово. Второе слово.")?.russian)
        assertEquals(originalDom, document.outerHtml())
        val mutations: List<Pair<String, (Document) -> Unit>> = listOf(
            "nested marker" to { d -> d.select("td span.letter")[1].wrap("<span></span>") },
            "missing marker" to { d -> d.select("td span.letter")[1].remove() },
            "repeated number" to { d -> d.select("td span.letter sup")[1].text("1") },
            "different number" to { d -> d.select("td span.letter sup")[3].text("3") },
            "extra sup" to { d -> d.select("tr").last()!!.select("td")[1].append("<sup>3</sup>") },
            "nonnumeric marker" to { d -> d.select("td span.letter sup")[1].text("а") },
            "footnote" to { d -> d.select("tr").last()!!.select("td")[1].append("<a href='#_ftna2'>2</a>") },
            "text before first marker" to { d -> d.select("tr").last()!!.select("td")[0].prependText("Лишний текст ") },
            "nested structure" to { d -> d.select("td span.letter")[0].after("<h2>Вложенный заголовок</h2>") },
            "empty first original" to { d -> d.select("tr").last()!!.select("td")[0].html("${m(1)} ${m(2)}Второе слово.") },
            "empty first Russian" to { d -> d.select("tr").last()!!.select("td")[1].html("${m(1)} ${m(2)}Второй перевод.") }
        )
        for ((label, change) in mutations) {
            val changed = Jsoup.parse(PsalterFixtures.page(good)); change(changed)
            val index = parse(changed)
            assertNull(label, index.find("Первое слово."))
            if (label == "empty first original") {
                // This key is now the unchanged legacy whole-row key, not an accepted single verse.
                assertEquals("Первый перевод. Второй перевод.", index.find("Второе слово.")?.russian)
            } else assertNull(label, index.find("Второе слово."))
            if (label == "nested marker") assertEquals("Old whole-row acceptance remains unchanged",
                "Первый перевод. Второй перевод.", index.find("Первое слово. Второе слово.")?.russian)
        }
    }

    @Test fun pageWideConflictsCannotReappearAndCannotOverwriteAnExistingWholeKey() {
        val first = row("${m(1)}Общие слова. ${m(2)}Первое окончание.", "${m(1)}Русский текст. ${m(2)}Первый конец.")
        for (different in listOf("Другой текст.", "русский текст.", "Русский текст;")) {
            val second = row("${m(1)}Общие слова. ${m(2)}Второе окончание.", "${m(1)}$different ${m(2)}Второй конец.")
            for (rows in listOf(first + second + first, second + first + second)) {
                val result = parse(Jsoup.parse(PsalterFixtures.page(rows)))
                assertNull(result.find("Общие слова."))
                assertEquals("Первый конец.", result.find("Первое окончание.")?.russian)
                assertEquals("Второй конец.", result.find("Второе окончание.")?.russian)
            }
            val whole = row("${m(1)}Общие слова.", "${m(1)}Сохраненная целая пара.")
            assertEquals("Сохраненная целая пара.", parse(Jsoup.parse(PsalterFixtures.page(first + whole + second))).find("Общие слова.")?.russian)
        }
        assertEquals("Русский текст.", parse(Jsoup.parse(PsalterFixtures.page(first + first))).find("Общие слова.")?.russian)
    }

    @Test fun oneRefrainLabelUsesOnlyTheWholeVerifiedVigilBodyAndNeverBecomesAPersistedAlias() {
        val body = "Слова молитвы."
        val index = pair(body, "Полный опубликованный текст QA.", vigil)
        val encodedBefore = index.encode()
        assertEquals(BookTranslation("Припев: Полный опубликованный текст QA.", vigil), index.find("  припев:  $body "))
        assertEquals(encodedBefore, index.encode()); assertEquals(1, index.size)
        val exact = pair("Припев: $body", "Точное полное совпадение QA.", psalter).plus(index)
        assertEquals(BookTranslation("Точное полное совпадение QA.", psalter), exact.find("Припев: $body"))
        for (bad in listOf("Припев:", "Припев: Припев: $body", "Внутри Припев: $body", "Припев: Священник: $body",
            "Припев: Не $body", "Припев: $body трижды", "Припев: $body (трижды)", "Припев: Слова")) assertNull(bad, index.find(bad))
        for (source in listOf(psalter, BookTranslationIndex.TIMROT_SOURCE, BookTranslationIndex.EDITORIAL_SOURCE,
            BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, vigil.copy(title = "Другой автор"),
            vigil.copy(url = vigil.url.replace("https:", "http:")), vigil.copy(url = vigil.url.replace("azbyka.ru", "azbyka.ru.example.org")),
            vigil.copy(url = vigil.url.replace("azbyka.ru", "user@azbyka.ru")), vigil.copy(url = vigil.url.replace("azbyka.ru", "azbyka.ru:444")),
            vigil.copy(url = vigil.url + "other/"))) assertNull(source.toString(), pair(body, "Текст QA.", source).find("Припев: $body"))
        val alias = vigil.copy(url = vigil.url + "?edition=1#prayer")
        assertEquals(alias, pair(body, "Текст QA.", alias).find("Припев: $body")?.source)
        assertNull(pair(body, "Припев: Уже с пометой.", vigil).find("Припев: $body"))
        assertNull(pair("Аллилуия", "Хвалите Господа QA.", vigil).find("Припев: Аллилуия (трижды)"))
        val own = BookTranslationIndex.fromFeoriaCatalog()
        val ownFull = "Припев: Аллилу́ия (трижды)."
        val merged = own.plus(pair("Аллилу́ия (трижды).", "Иной опубликованный текст QA.", vigil))
        assertEquals(own.find(ownFull), merged.find(ownFull))
    }

    @Test fun versionTenRemainsReadableOfflineButCannotClaimCompletenessUnderVersionTwelve() {
        assertEquals(12, BookTranslationCorpus.VERSION); assertEquals(4_000_000, BookTranslationCorpus.MAX_BYTES)
        val index = pair("Старая молитва", "Старый опубликованный перевод QA", vigil)
        val all = BookTranslationIndex.ALL_PARALLEL_SOURCES.map { it.url }.toSet()
        val old = BookTranslationCorpus(index, all, version = 10).encode()
        val cache = FileBookCache(directory)
        assertTrue(cache.writeTranslationCorpus(old)); assertEquals(old, cache.readTranslationCorpus())
        val decoded = BookTranslationCorpus.decode(cache.readTranslationCorpus())
        assertFalse(decoded.current); assertFalse(decoded.completeFor(BookTranslationIndex.ALL_PARALLEL_SOURCES))
        assertEquals(index.find("Старая молитва"), decoded.index.find("Старая молитва"))
        val next = BookTranslationCorpus(decoded.index, all).encode()
        assertTrue(cache.writeTranslationCorpus(next))
        val restored = BookTranslationCorpus.decode(cache.readTranslationCorpus())
        assertTrue(restored.current); assertTrue(restored.completeFor(BookTranslationIndex.ALL_PARALLEL_SOURCES))
        assertEquals(index.encode(), restored.index.encode())
    }

    @Test fun optInRealSourcesPreserve206WholePairsReject12ConflictsGain40And38AndPersistHistoricalTwelveSourceCorpus() {
        val path = System.getProperty("feoria36bInputs") ?: System.getenv("FEORIA_36B_INPUTS")
        assumeTrue("Requires explicit local SHA-verified published inputs", !path.isNullOrBlank())
        val inputs = File(requireNotNull(path)).canonicalFile
        val manifest = JsonParser.parseString(File(inputs, "manifest.json").readText()).asJsonObject
        assertEquals("269e6fb", manifest["baseline"].asString)
        val sourceHashes = manifest.getAsJsonArray("files").associate { raw ->
            val entry = raw.asJsonObject; val name = entry["file"].asString
            require(name.matches(Regex("[a-z0-9.-]+")))
            val file = File(inputs, name); assertEquals(entry["bytes"].asLong, file.length())
            assertEquals(entry["sha256"].asString, sha(file.readBytes())); name to sha(file.readBytes())
        }
        val output = File(System.getProperty("feoria36bOutput") ?: System.getenv("FEORIA_36B_OUTPUT")
            ?: Files.createTempDirectory("published-verse-receipt-").toString()).apply { mkdirs() }
        val receipt = linkedMapOf<String, Any?>("status" to "RUNNING", "scope" to "actual Kotlin parser/encode/cache with readonly local public sources")
        try {
            fun read(name: String) = File(inputs, name).readText()
            val oldWhole = BookTranslationIndex.decode(read("index-psalter.json"))
            assertEquals(206, oldWhole.size)
            val oldEntries = entries(oldWhole)
            val actualDocument = Jsoup.parse(read("psalter.html"), psalter.url)
            val actualPsalter = parse(actualDocument)
            oldEntries.forEach { (original, translation) -> assertEquals(original, translation, actualPsalter.find(original)) }
            val expected = JsonParser.parseString(read("expectations.json")).asJsonObject
            val conflictKeys = expected.getAsJsonArray("conflicts").map { it.asString }
            assertEquals(12, conflictKeys.size)
            for (key in conflictKeys) assertEquals(key, oldWhole.find(key), actualPsalter.find(key))
            val reversed = parse(reversePsalmGroups(actualDocument))
            assertEquals(entries(actualPsalter).filterKeys { it !in oldEntries }, entries(reversed).filterKeys { it !in oldEntries })
            val actualVigil = BookTranslationIndex.fromParallelPage(Jsoup.parse(read("vigil.html"), vigil.url), vigil)
            val vigilRows = Jsoup.parse(read("vigil.html")).select("table tr")
            expected.getAsJsonArray("primaryRefrainPairs").forEach { value ->
                val proof = value.asJsonObject
                val cells = vigilRows[proof["rowZeroBased"].asInt].children().filter { it.tagName() == "td" }
                assertEquals(proof["original"].asString, BookText.clean(cells[0].text()))
                assertEquals(proof["russian"].asString, BookText.clean(cells[1].text()))
                assertEquals(BookText.withoutSpeaker(proof["russian"].asString).second,
                    actualVigil.find(BookText.withoutSpeaker(proof["original"].asString).second)?.russian)
            }
            val perSource = manifest.getAsJsonArray("sources").associate { value ->
                val item = value.asJsonObject
                val source = BookTranslationIndex.ALL_PARALLEL_SOURCES.single { it.url == item["url"].asString }
                val index = when (source) {
                    psalter -> actualPsalter
                    vigil -> actualVigil
                    else -> {
                        // Two older probe exports use abbreviated author labels; restore the exact published source identity.
                        val raw = JsonParser.parseString(read(item["file"].asString)).asJsonArray
                        raw.forEach { it.asJsonObject.addProperty("source", source.title); it.asJsonObject.addProperty("url", source.url) }
                        BookTranslationIndex.decode(raw.toString())
                    }
                }
                source.url to index
            }
            assertEquals(12, perSource.size)
            // Coverage was measured on this exact Matins set. The full twelve-source corpus
            // also contains older optional matches, so its size/roundtrip is a separate contract.
            val matinsSources = BookTranslationIndex.requiredSources(WorshipKind.MATINS)
            assertEquals(listOf(vigil, BookTranslationIndex.TIMROT_SOURCE, psalter), matinsSources)
            var matins = BookTranslationIndex.empty()
            matinsSources.forEach { matins = matins.fillingMissing(requireNotNull(perSource[it.url])) }
            val frozenSources = BookTranslationIndex.ALL_PARALLEL_SOURCES.filter { it.url in perSource }
            val sourceOrder = (matinsSources + frozenSources).distinctBy { it.url }
            var full = BookTranslationIndex.empty()
            sourceOrder.forEach { full = full.fillingMissing(requireNotNull(perSource[it.url])) }
            // These frozen twelve exports predate the Sunday extraction change.
            // Keep their pairs readable without declaring the expanded corpus complete.
            val corpus = BookTranslationCorpus(full, perSource.keys, version = 11)
            val encoded = corpus.encode(); val bytes = encoded.toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size > 2_000_000); assertTrue(bytes.size <= 4_000_000)
            val disk = FileBookCache(directory)
            assertTrue(disk.writeTranslationCorpus(encoded)); assertEquals(encoded, disk.readTranslationCorpus())
            val restored = BookTranslationCorpus.decode(disk.readTranslationCorpus())
            assertEquals(full.encode(), restored.index.encode()); assertEquals(11, restored.version)
            assertFalse(restored.current)
            assertFalse(restored.completeFor(BookTranslationIndex.ALL_PARALLEL_SOURCES))
            assertTrue(entries(full).values.none { it.source == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE || it.source == BookTranslationIndex.EDITORIAL_SOURCE })
            val rawBook = Gson().fromJson(read("old-book.json"), ServiceBook::class.java)
            // Native and JVM serializers may hash differently. Only its hash is adapted for this JVM cache.
            val old = rawBook.copy(contentHash = ServiceBookIntegrity.contentHash(rawBook))
            assertTrue(ServiceBookIntegrity.valid(old))
            val before = requireNotNull(SavedBookEnrichment.apply(old, BookTranslationIndex.fromFeoriaCatalog()))
            val ownBefore = before.blocks.filter { it.translationSource == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE }
            assertEquals(104, ownBefore.size)
            receipt["additionalFullCorpusMatches"] = before.blocks.filter { block ->
                block.russian == null && full.find(block.original) != null && matins.find(block.original) == null
            }.map { block -> mapOf("id" to block.id, "source" to full.find(block.original)?.source) }
            val after = requireNotNull(SavedBookEnrichment.apply(before, matins.plus(BookTranslationIndex.fromFeoriaCatalog())))
            val gained = before.blocks.zip(after.blocks).filter { (a, b) -> a.russian == null && b.russian != null }
            val targets = expected.getAsJsonArray("expected").map { it.asJsonObject }
            assertEquals(targets.map { it["id"].asString }.toSet(), gained.map { it.second.id }.toSet())
            assertEquals(40, gained.count { it.second.translationSource == psalter })
            assertEquals(38, gained.count { it.second.translationSource == vigil })
            targets.forEach { proof ->
                val block = after.blocks.single { it.id == proof["id"].asString }
                assertEquals(proof["original"].asString, block.original)
                assertEquals(proof["russian"].asString, block.russian)
                assertEquals(proof["sourceUrl"].asString, block.translationSource?.url)
            }
            assertEquals(ownBefore, after.blocks.filter { it.translationSource == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE })
            before.blocks.zip(after.blocks).forEach { (a, b) ->
                if (a.russian == null && b.russian != null) assertEquals(a, b.copy(russian = null, translationSource = a.translationSource))
                else assertEquals(a, b)
            }
            assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
            assertTrue(ServiceBookIntegrity.valid(after))
            File(output, "full-corpus11.json").writeText(encoded)
            receipt.putAll(mapOf("status" to "PASS", "wholePreserved" to 206, "conflicts" to 12,
                "psalmGains" to 40, "refrainGains" to 38, "ownUnchanged" to 104, "corpusVersion" to 11,
                "corpusEntries" to full.size, "corpusUtf8Bytes" to bytes.size, "corpusSha256" to sha(bytes),
                "sourceOrder" to sourceOrder.map { it.url }, "coverageSourceOrder" to matinsSources.map { it.url },
                "coverageIndexEntries" to matins.size, "inputs" to sourceHashes))
        } catch (failure: Throwable) {
            receipt["status"] = "FAIL"; receipt["error"] = failure.toString(); throw failure
        } finally {
            assertTrue("Readonly source inputs changed", sourceHashes.all { (name, hash) -> sha(File(inputs, name).readBytes()) == hash })
            File(output, "receipt.json").writeText(Gson().toJson(receipt))
            println("PublishedVerseQA receipt=${File(output, "receipt.json").absolutePath}")
        }
    }

    private fun m(number: Int) = PsalterFixtures.marker(number)
    private fun row(original: String, russian: String) = "<tr><td>$original</td><td>$russian</td></tr>"
    private fun parse(document: Document) = BookTranslationIndex.fromParallelPage(document, psalter)
    private fun pair(original: String, russian: String, source: BookSource) = BookTranslationIndex.decode(JsonArray().apply {
        add(JsonObject().apply { addProperty("original", original); addProperty("russian", russian); addProperty("source", source.title); addProperty("url", source.url) })
    }.toString())
    private fun entries(index: BookTranslationIndex) = JsonParser.parseString(index.encode()).asJsonArray.associate { raw ->
        val row = raw.asJsonObject
        row["original"].asString to BookTranslation(row["russian"].asString, BookSource(row["source"].asString, row["url"].asString), row["meaning"]?.asString)
    }
    private fun reversePsalmGroups(original: Document): Document {
        val groups = mutableListOf<List<Element>>()
        for (table in original.select("table.adaptive")) {
            var group = mutableListOf<Element>()
            for (row in table.select("tr")) {
                if (row.closest("table") !== table) continue
                val cells = row.children().filter { it.tagName() == "td" }
                if (cells.size == 1 && cells[0].attr("colspan") == "2") {
                    if (group.isNotEmpty()) groups += group.toList()
                    group = mutableListOf()
                }
                group += row.clone()
            }
            if (group.isNotEmpty()) groups += group.toList()
        }
        val result = Jsoup.parse("<html><head><link rel='canonical' href='${psalter.url}'></head><body><h1>Псалтирь по кафизмам с переводом и молитвами</h1></body></html>")
        for (group in groups.asReversed()) {
            val table = result.body().appendElement("table").addClass("adaptive")
            group.forEach { table.appendChild(it) }
        }
        return result
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
