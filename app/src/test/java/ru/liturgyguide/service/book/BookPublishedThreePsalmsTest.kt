package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/** Full source rows and actual whole originals are independent of the new production constants. */
class BookPublishedThreePsalmsTest {
    private val gson = Gson()
    private val directory = Files.createTempDirectory("three-published-psalms-").toFile()
    private val source = BookTranslationIndex.PSALTER_SOURCE
    private fun text(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }
    private fun asset(path: String) = listOf(File("src/androidTest/assets/$path"), File("app/src/androidTest/assets/$path"))
        .first { it.isFile }
    private val evidence by lazy {
        val raw = text("/book/published-three-psalms/evidence.json")
        assertEquals("f366b671122954fe4db8ac7d39bad7413461e9e148dc18628d7356b21683df3b", BookText.sha256(raw))
        JsonParser.parseString(raw).asJsonObject
    }
    private val rows get() = evidence["items"].asJsonArray.map { it.asJsonObject }
    private fun oldBundled() = BookTranslationIndex.fromFeoriaCatalog().fillingMissing(BookTranslationIndex.fromBundledPsalm19())
    private fun psalter() = GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/book/published-psalm19/psalter.html.gz")))
        .bufferedReader().use { it.readText() }
    private fun corpusText() = GZIPInputStream(asset("published-psalm19/phone-corpus-v12.json.gz").inputStream())
        .bufferedReader().use { it.readText() }
    private fun oldBook(): ServiceBook {
        val raw = asset(evidence["book"].asJsonObject["asset"].asString).readText()
        assertEquals(evidence["book"].asJsonObject["rawSha256"].asString, BookText.sha256(raw))
        val immutableAndroid = gson.fromJson(raw, ServiceBook::class.java)
        assertEquals(evidence["book"].asJsonObject["storedContentHash"].asString, immutableAndroid.contentHash)
        // A JVM-only cache copy uses its serializer's hash. The Android fixture is never rewritten.
        return immutableAndroid.copy(contentHash = ServiceBookIntegrity.contentHash(immutableAndroid))
    }
    private fun encoded(original: String, russian: String, attribution: BookSource = source) = JsonObject().apply {
        addProperty("original", original); addProperty("russian", russian)
        addProperty("source", attribution.title); addProperty("url", attribution.url)
    }
    private fun index(row: JsonObject) = BookTranslationIndex.decode(JsonArray().apply { add(row) }.toString())
    private fun body(cell: Element): String = cell.clone().also { it.select("span.letter").remove() }.let { BookText.clean(it.text()) }
    @After fun clean() { directory.deleteRecursively() }

    @Test fun allThreePrimaryRowsAndStoredCorpusProveCompleteBodiesAndTheirSpecificDifferences() {
        val raw = psalter()
        assertEquals(evidence["sourceDecompressedSha256"].asString, BookText.sha256(raw))
        val page = Jsoup.parse(raw, source.url)
        assertEquals(source.url, page.select("link[rel=canonical]").single().attr("href"))
        assertEquals("Псалтирь по кафизмам с переводом и молитвами", page.select("h1").single().text())
        val corpusRaw = corpusText()
        assertEquals(evidence["corpus"].asJsonObject["rawSha256"].asString, BookText.sha256(corpusRaw))
        assertEquals(12, JsonParser.parseString(corpusRaw).asJsonObject["version"].asInt)
        val corpus = BookTranslationCorpus.decode(corpusRaw).index
        val parsed = BookTranslationIndex.fromParallelPage(page, source)
        val book = oldBook()
        assertEquals(listOf(20, 134, 135), rows.map { it["psalm"].asInt })
        for (row in rows) {
            val original = row["original"].asString; val published = row["publishedOriginal"].asString
            val russian = row["russian"].asString
            val block = book.blocks.single { it.id == row["blockId"].asString }
            assertEquals(original, block.original); assertNull(block.russian)
            assertEquals(BookBlockKind.PRAYER, block.kind); assertTrue(block.scriptureReferences.isEmpty())
            assertEquals(row["originalSha256"].asString, BookText.sha256(original))
            assertEquals(row["normalizedKeySha256"].asString, BookText.sha256(BookTranslationIndex.normal(original)))
            assertEquals(row["publishedOriginalSha256"].asString, BookText.sha256(published))
            assertEquals(row["russianSha256"].asString, BookText.sha256(russian))
            assertEquals(row["rowHtmlSha256"].asString, BookText.sha256(row["rowHtml"].asString))
            val full = page.select("tr").single { candidate ->
                val cells = candidate.children().filter { it.tagName() == "td" }
                cells.size == 2 && body(cells[0]) == published
            }
            val heading = generateSequence(full.previousElementSibling()) { it.previousElementSibling() }
                .first { previous -> previous.children().any { it.tagName() == "td" && it.attr("colspan") == "2" } }
            assertEquals("Псалом ${row["psalm"].asInt}", heading.text())
            assertTrue(full.select("a").isEmpty())
            val cells = full.children()
            for (cell in cells) {
                val markers = cell.select("span.letter")
                assertEquals(row["verseMarkers"].asJsonArray.map { it.asString }, markers.map { it.select("sup").single().text() })
                assertTrue(markers.all { it.parent() === cell })
            }
            assertEquals(published, body(cells[0])); assertEquals(russian, body(cells[1]))
            assertEquals(BookTranslation(russian, source), parsed.find(published))
            assertEquals(BookTranslation(russian, source), corpus.find(published))
            assertNull(parsed.find(original)); assertNull(corpus.find(original))
        }
        val twenty = rows.single { it["psalm"].asInt == 20 }
        val target20 = BookText.normalized(twenty["original"].asString)
        val source20 = BookText.normalized(twenty["publishedOriginal"].asString)
        assertTrue(target20.contains("ненавидящия")); assertTrue(source20.contains("ненавидящыя"))
        assertTrue(target20.contains("снест ")); assertTrue(source20.contains("снесть "))
        val one34 = rows.single { it["psalm"].asInt == 134 }
        assertTrue(BookText.normalized(one34["original"].asString).contains("сиона царя аморрейска"))
        assertTrue(BookText.normalized(one34["publishedOriginal"].asString).contains("сигона царя аморрейска"))
        val russian134 = BookText.normalized(one34["russian"].asString)
        assertTrue(russian134.contains("сигона")); assertTrue(russian134.split(' ').any { it.startsWith("сион") })
        val russian135 = BookText.normalized(rows.single { it["psalm"].asInt == 135 }["russian"].asString)
        assertEquals(26, Regex("вовеки милость его").findAll(russian135).count())
    }

    @Test fun closedThreePairSetRejectsMalformedDuplicateExtraAndAlteredInputsWithoutRemovingTheOld194() {
        val valid = JsonParser.parseString(BookPublishedThreePsalms.JSON).asJsonArray
        fun changed(edit: (JsonArray) -> Unit) = valid.deepCopy().also(edit).toString()
        val invalid = mutableListOf<String?>(null, "", "null", "[]", "{}", "[{}]", "[", " ".repeat(32_769),
            changed { it.remove(0) }, changed { it.add(valid[0].deepCopy()) },
            changed { val first = it.remove(0); it.add(first) },
            BookPublishedThreePsalms.JSON.replaceFirst("{", "{\"original\":\"untrusted\","))
        for (at in 0..2) {
            invalid += changed { it[at].asJsonObject.addProperty("original", rows[at]["original"].asString + "!") }
            invalid += changed { it[at].asJsonObject.addProperty("russian", rows[at]["russian"].asString + " ") }
            invalid += changed { it[at].asJsonObject.addProperty("source", "Другое издание") }
            invalid += changed { it[at].asJsonObject.addProperty("url", source.url + "?copy=1") }
            invalid += changed { it[at].asJsonObject.remove("original") }
            invalid += changed { it[at].asJsonObject.addProperty("original", 42) }
            invalid += changed { it[at].asJsonObject.addProperty("meaning", "Незапрошенное объяснение") }
        }
        invalid += changed { it[1].asJsonObject.addProperty("original", rows[0]["original"].asString) }
        val old = oldBundled()
        for (json in invalid) {
            val rejected = BookTranslationIndex.fromBundledThreePsalms(json)
            assertEquals(0, rejected.size)
            assertEquals(old.encode(), old.fillingMissing(rejected).encode())
        }
        assertEquals(3, BookTranslationIndex.fromBundledThreePsalms().size)
    }

    @Test fun currentProductionHasExactlyTheOld193AndFourPublishedBodiesAndRetainsFillOnlyAcrossCodecsAndSources() {
        val own = BookTranslationIndex.fromFeoriaCatalog(); val old = oldBundled()
        val current = BookTranslationIndex.fromBundledRussian()
        assertEquals(193, own.size); assertEquals(8, FeoriaTranslationCatalog.REVISION)
        assertEquals(194, old.size); assertEquals(197, current.size); assertEquals(14, BookTranslationCorpus.VERSION)
        val old19 = JsonParser.parseString(asset("published-psalm19/evidence.json").readText()).asJsonObject
        val target19 = old19["book"].asJsonObject["original"].asString
        assertEquals(old.find(target19), current.find(target19))
        for (entry in FeoriaTranslationCatalog.entries) assertEquals(own.find(entry.original), current.find(entry.original))
        val oldKeys = JsonParser.parseString(old.encode()).asJsonArray.map { it.asJsonObject["original"].asString }.toSet()
        val newKeys = JsonParser.parseString(current.encode()).asJsonArray.map { it.asJsonObject["original"].asString }.toSet()
        assertEquals(rows.map { it["normalizedKey"].asString }.toSet(), newKeys - oldKeys)
        assertTrue(newKeys.containsAll(oldKeys))
        val restored = BookTranslationIndex.decode(current.encode())
        assertEquals(current.encode(), restored.encode())
        for (row in rows) {
            val target = row["original"].asString; val pair = BookTranslation(row["russian"].asString, source)
            assertNull(old.find(target)); assertEquals(pair, current.find(target)); assertEquals(pair, restored.find(target))
            assertEquals(pair, restored.fromSources(listOf(source)).find(target))
            val existing = index(encoded(target, "Прежний точный опубликованный перевод", BookSource("Fixture", "https://example.org/previous")))
            assertEquals(existing.find(target), restored.plus(existing).find(target))
            assertEquals(existing.find(target), existing.plus(restored).find(target))
            assertEquals(existing.find(target), existing.fillingMissing(restored).find(target))
        }
    }

    @Test fun noWholeApprovalIsGrantedToPartialReorderedMorphologyNameOrAnotherLatinVariant() {
        val only = BookTranslationIndex.fromBundledThreePsalms()
        for (row in rows) {
            val original = row["original"].asString; val normal = BookTranslationIndex.normal(original)
            val changedE = normal.replaceRange(normal.indexOf('е'), normal.indexOf('е') + 1, "e")
            val misses = listOf("Не $original", original + " добавлено", original.substringAfter(' '),
                original.substringBefore('.'), original.split(' ').reversed().joinToString(" "), changedE,
                row["publishedOriginal"].asString)
            for (miss in misses) assertNull(miss, only.find(miss))
            assertEquals(only.find(original), only.find(normal))
            assertEquals(only.find(original), only.find("Чтец: $original"))
            assertEquals(only.find(original), only.find(original + "!"))
        }
        val book = oldBook()
        val excluded144 = evidence["excluded144"].asJsonObject["target"].asJsonObject["occurrences"].asJsonArray[0]
            .asJsonObject["original"].asString
        assertNull(only.find(excluded144)); assertNull(BookTranslationIndex.fromBundledRussian().find(excluded144))
        val last = book.chapters.single { BookText.normalized(it.title) == "кафизма 20" }.blocks
        val label145 = last.indexOfFirst { BookText.normalized(it.original) == "псалом 145" }
        assertTrue(label145 >= 0)
        assertNull(only.find(last[label145 + 1].original))
    }

    @Test fun previouslyPublishedPriorityAndInvalidOwnPairsRemainProtectedInBothMergeOrders() {
        val only = BookTranslationIndex.fromBundledThreePsalms()
        for (row in rows) {
            val target = row["original"].asString
            val existing = index(encoded(target, "Прежнее точное издание", BookSource("Другое издание", "https://example.org/whole")))
            assertEquals(existing.find(target), existing.plus(only).find(target))
            assertEquals(existing.find(target), only.plus(existing).find(target))
            val invalidOwn = index(encoded(target, "Неодобренный собственный перевод", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE))
            assertNull(invalidOwn.find(target)); assertNull(invalidOwn.plus(only).find(target)); assertNull(only.plus(invalidOwn).find(target))
            val publishedKey = row["publishedOriginal"].asString
            val real = encoded(publishedKey, row["russian"].asString)
            val wrong = encoded(publishedKey, "Измененное runtime издание", BookSource("Runtime", "https://example.org/runtime"))
            val corpora = listOf(BookTranslationIndex.empty(), index(real), index(wrong),
                BookTranslationIndex.decode(JsonArray().apply { add(real); add(wrong) }.toString()),
                BookTranslationIndex.decode(JsonArray().apply { add(wrong); add(real) }.toString()))
            for (runtime in corpora) {
                assertNull(runtime.find(target))
                assertEquals(only.find(target), runtime.plus(only).find(target))
                assertEquals(only.find(target), only.plus(runtime).find(target))
            }
        }
    }

    @Test fun oldPagePairsVerseConflictsAndCloudRodQuarantineAreUnchanged() {
        val page = BookTranslationIndex.fromParallelPage(Jsoup.parse(psalter(), source.url), source)
        val only = BookTranslationIndex.fromBundledThreePsalms()
        val appended = page.plus(only); val prepended = only.plus(page)
        for (entry in JsonParser.parseString(page.encode()).asJsonArray) {
            val key = entry.asJsonObject["original"].asString
            assertEquals(page.find(key), appended.find(key)); assertEquals(page.find(key), prepended.find(key))
        }
        val negatives = JsonParser.parseString(text("/book/published-psalm19/preserved-negatives.json")).asJsonObject
        assertEquals(12, negatives["verseConflicts"].asJsonArray.size())
        for (entry in negatives["verseConflicts"].asJsonArray) {
            assertNull(only.find(entry.asString))
            assertEquals(page.find(entry.asString), appended.find(entry.asString))
            assertEquals(page.find(entry.asString), prepended.find(entry.asString))
        }
        for (entry in negatives["cloudRodOriginals"].asJsonArray) {
            assertNull(only.find(entry.asString)); assertNull(BookTranslationIndex.fromBundledRussian().find(entry.asString))
        }
    }

    @Test fun actualSavedBookGainsExactlyThreeWholeTargetsAnd623WordsBeyondTheOld194() {
        val old = oldBook(); val serialized = gson.toJson(old)
        val corpus = BookTranslationCorpus.decode(corpusText())
        val base = requireNotNull(SavedBookEnrichment.apply(old, corpus.index.plus(oldBundled())))
        val current = requireNotNull(SavedBookEnrichment.apply(old, corpus.index.plus(BookTranslationIndex.fromBundledRussian())))
        val delta = base.blocks.zip(current.blocks).filter { (before, after) -> before != after }
        assertEquals(rows.map { it["blockId"].asString }, delta.map { it.first.id })
        assertEquals(623, delta.sumOf { BookTranslationIndex.normal(it.first.original).split(' ').size })
        for ((before, after) in delta) {
            val row = rows.single { it["blockId"].asString == before.id }
            assertEquals(before.copy(russian = row["russian"].asString, translationSource = source), after)
        }
        assertEquals(base, current.copy(chapters = base.chapters, sources = base.sources, contentHash = base.contentHash))
        assertEquals(old.blocks.map { it.id to it.original }, current.blocks.map { it.id to it.original })
        assertEquals(old.chapters.map { it.id to it.meaning }, current.chapters.map { it.id to it.meaning })
        assertEquals(serialized, gson.toJson(old))
    }

    @Test fun offlineRepositoryPreservesAcceptedBytesCorpusAndCursorUntilExplicitAdoptionAndReopensTheCandidate() = runBlocking {
        val old = oldBook(); val original = gson.toJson(old); val corpusRaw = corpusText()
        val expected = requireNotNull(SavedBookEnrichment.apply(old,
            BookTranslationCorpus.decode(corpusRaw).index.plus(BookTranslationIndex.fromBundledRussian())))
        val first = rows.first(); val target = old.blocks.single { it.id == first["blockId"].asString }
        val chapter = old.chapters.single { target in it.blocks }
        val cache = FileBookCache(directory); assertTrue(cache.write(old)); assertTrue(cache.writeTranslationCorpus(corpusRaw))
        val file = directory.listFiles()!!.single { it.name.endsWith("-${old.contentHash}.json") }; val bytes = file.readBytes()
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val position = BookReadingPosition(old.key, old.contentHash, "three-published", target.id, chapter.id,
            fraction = .31f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position)); var calls = 0
        fun repository() = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { calls++; error("Offline") },
            translations = BookTranslationIndex.fromBundledRussian(), downloadRussian = false)
        val ready = requireNotNull(repository().enrichSaved(old, allowNetwork = false))
        assertEquals(expected, ready.book); assertTrue(ready.saved); assertTrue(ready.fromCache)
        assertEquals(position, store.last("three-published")); assertArrayEquals(bytes, file.readBytes())
        assertEquals(corpusRaw, cache.readTranslationCorpus()); assertEquals(original, gson.toJson(old))
        assertEquals(old, cache.readVersion(old.key, old.contentHash))
        assertTrue(store.acceptVersion(old, ready.book, "three-published", position))
        val accepted = position.copy(contentHash = ready.book.contentHash, fraction = 0f)
        assertEquals(accepted, store.last("three-published"))
        assertEquals(expected, (repository().load(old.key) as BookLoadResult.Ready).book)
        assertEquals(accepted, store.restore(expected, "three-published")); assertEquals(0, calls)
        for (row in rows) assertEquals(row["russian"].asString, cache.read(old.key)?.blocks?.single { it.id == row["blockId"].asString }?.russian)
        assertArrayEquals(bytes, file.readBytes()); assertEquals(corpusRaw, cache.readTranslationCorpus())
    }
}
