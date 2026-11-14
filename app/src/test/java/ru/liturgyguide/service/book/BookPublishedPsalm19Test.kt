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

/** One actual whole row, never an e/е normalizer or a whole-Psalter coverage claim. */
class BookPublishedPsalm19Test {
    private val gson = Gson()
    private val directory = Files.createTempDirectory("psalm19-published-").toFile()
    private val source = BookTranslationIndex.PSALTER_SOURCE
    private fun asset(name: String) = listOf(File("src/androidTest/assets/$name"), File("app/src/androidTest/assets/$name"))
        .first { it.isFile }
    private val evidence by lazy {
        val bytes = asset("published-psalm19/evidence.json").readText()
        assertEquals("d6e82505b6b152e936f222040b74bb5ded329b76f1f4d646cd2eda194160441d", BookText.sha256(bytes))
        JsonParser.parseString(bytes).asJsonObject
    }
    private val target get() = evidence["book"].asJsonObject["original"].asString
    private val published get() = evidence["published"].asJsonObject["original"].asString
    private val russian get() = evidence["published"].asJsonObject["russian"].asString
    private val blockId get() = evidence["book"].asJsonObject["blockId"].asString
    private val pair get() = BookTranslation(russian, source)
    private fun raw() = GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/book/published-psalm19/psalter.html.gz")))
        .bufferedReader().use { it.readText() }
    private fun oldCorpus() = GZIPInputStream(asset("published-psalm19/phone-corpus-v12.json.gz").inputStream())
        .bufferedReader().use { it.readText() }
    private fun negatives() = JsonParser.parseString(requireNotNull(javaClass.getResourceAsStream(
        "/book/published-psalm19/preserved-negatives.json")).bufferedReader().use { it.readText() }).asJsonObject
    private fun oldBook(): ServiceBook {
        val text = asset("polyeleos-journey/ranked-matins-2026-11-13-church34.json").readText()
        assertEquals("440b36d4b7f190d3da575d12f0f6ff0a1e3c272e40be87ea32ea5cf162bfc79d", BookText.sha256(text))
        val native = gson.fromJson(text, ServiceBook::class.java)
        // The immutable Android witness stays as supplied. Only this JVM cache copy uses its serializer's hash.
        return native.copy(contentHash = ServiceBookIntegrity.contentHash(native))
    }
    private fun encoded(original: String, value: String, attribution: BookSource = source) = JsonObject().apply {
        addProperty("original", original); addProperty("russian", value)
        addProperty("source", attribution.title); addProperty("url", attribution.url)
    }
    private fun index(row: JsonObject) = BookTranslationIndex.decode(JsonArray().apply { add(row) }.toString())
    @After fun clean() { directory.deleteRecursively() }

    @Test fun rawPublishedRowAndActualPhoneCorpusIndependentlyProveTheWholeBodyWithoutItsInscription() {
        val html = raw()
        assertEquals("cbbdcedf13f24b590609c6cbf4b389838be16975530584a9b38c3bc1fdb7b04b", BookText.sha256(html))
        val page = Jsoup.parse(html, source.url)
        assertEquals(source.url, page.select("link[rel=canonical]").single().attr("href"))
        assertEquals("Псалтирь по кафизмам с переводом и молитвами", page.select("h1").single().text())
        val heading = page.select("td[colspan=2]").single { it.text() == "Псалом 19" }.parent()!!
        val inscription = requireNotNull(heading.nextElementSibling())
        val row = requireNotNull(inscription.nextElementSibling())
        fun body(cell: Element, expectedMarkers: List<Int>): String {
            val copy = cell.clone()
            val markers = copy.select("span.letter")
            assertEquals(expectedMarkers, markers.map { it.select("sup").single().text().toInt() })
            assertTrue(markers.all { it.parent() === copy })
            markers.remove()
            return BookText.clean(copy.text())
        }
        assertTrue(body(inscription.children()[0], listOf(1)).startsWith("В коне́ц"))
        assertEquals(published, body(row.children()[0], (2..10).toList()))
        assertEquals(russian, body(row.children()[1], (2..10).toList()))
        val matchingWholeRows = page.select("tr").count { candidate ->
            val cells = candidate.children().filter { it.tagName() == "td" }
            if (cells.size != 2) false else {
                val copy = cells[0].clone(); copy.select("span.letter").remove()
                BookTranslationIndex.normal(copy.text()) == BookTranslationIndex.normal(published)
            }
        }
        assertEquals(1, matchingWholeRows)
        val a = BookTranslationIndex.normal(target); val b = BookTranslationIndex.normal(published)
        assertEquals(a.length, b.length)
        assertEquals(listOf(386), a.indices.filter { a[it] != b[it] })
        assertEquals('е', a[386]); assertEquals('e', b[386])
        assertEquals(111, a.split(' ').size); assertEquals(120, BookText.normalized(russian).split(' ').size)
        assertEquals("734070219f6fa509dcec5e030e43117116162106af8896121be07d3111f0268a", BookText.sha256(target))
        assertEquals("77f90c2d5a7b962c65eb8208ed8011567d764b2dad3c31c6074c3054dee51e45", BookText.sha256(published))
        assertEquals("d29c88e33e5f3725f0cc34105ff44c059cb6a4ce2d0fafa10fa65477d140d2ff", BookText.sha256(russian))
        val phone = oldCorpus()
        assertEquals("c04424b8ee039c560968680e42e7b87ecd829f46b843ab7098aebe21b1a927f1", BookText.sha256(phone))
        val json = JsonParser.parseString(phone).asJsonObject
        assertEquals(12, json["version"].asInt); assertEquals(3694, json["entries"].asJsonArray.size())
        assertEquals(0, json["entries"].asJsonArray.count { BookTranslationIndex.normal(it.asJsonObject["original"].asString) == a })
        val whole = json["entries"].asJsonArray.filter { BookTranslationIndex.normal(it.asJsonObject["original"].asString) == b }
        assertEquals(1, whole.size)
        assertEquals(russian, whole.single().asJsonObject["russian"].asString)
        assertEquals(source.url, whole.single().asJsonObject["url"].asString)
        assertEquals(source.title, whole.single().asJsonObject["source"].asString)
    }

    @Test fun productionFactoryAddsOnePublishedPairWithoutChangingThe193OwnTranslationsAndRoundTripsItsFillOnlyFlag() {
        val own = BookTranslationIndex.fromFeoriaCatalog()
        val bundled = BookTranslationIndex.fromBundledRussian()
        assertEquals(8, FeoriaTranslationCatalog.REVISION); assertEquals(193, own.size); assertEquals(194, bundled.size)
        assertNull(own.find(target)); assertEquals(pair, bundled.find(target))
        assertEquals(1, BookTranslationIndex.fromBundledPsalm19().size)
        assertNotEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, requireNotNull(bundled.find(target)).source)
        for (entry in FeoriaTranslationCatalog.entries) assertEquals(own.find(entry.original), bundled.find(entry.original))
        val restored = BookTranslationIndex.decode(bundled.encode())
        assertEquals(pair, restored.find(target)); assertEquals(194, restored.size)
        val existing = index(encoded(target, "Прежний точный опубликованный перевод", BookSource("Fixture", "https://example.org/old")))
        assertEquals(existing.find(target), restored.plus(existing).find(target))
        assertEquals(existing.find(target), existing.plus(restored).find(target))
        assertEquals(pair, restored.fromSources(listOf(source)).find(target))
    }

    @Test fun closedSingletonRejectsMissingDuplicateAndAlteredFixtureBeforeAnyGeneralDecodeWithoutRemovingTheBase() {
        val valid = JsonParser.parseString(BookPublishedPsalm19.JSON).asJsonArray.single().asJsonObject
        fun changed(edit: (JsonObject) -> Unit) = JsonArray().apply { add(valid.deepCopy().also(edit)) }.toString()
        val other = valid.deepCopy().apply { addProperty("russian", "Другой перевод") }
        val invalid = listOf<String?>(null, "", "null", "[]", "{}", "[{}]", "[", " ".repeat(8_193),
            JsonArray().apply { add(valid); add(valid.deepCopy()) }.toString(),
            JsonArray().apply { add(valid); add(other) }.toString(),
            JsonArray().apply { add(other); add(valid) }.toString(),
            changed { it.addProperty("original", target + "!") },
            changed { it.addProperty("original", target + " другое") },
            changed { it.addProperty("russian", russian + " ") },
            changed { it.addProperty("source", source.title + " · копия") },
            changed { it.addProperty("url", source.url + "?copy=1") },
            changed { it.remove("original") }, changed { it.addProperty("source", 42) },
            changed { it.addProperty("meaning", "Незапрошенное объяснение") },
            changed { it.addProperty("boundaryFillOnly", true) },
            changed { it.add("openingBodies", JsonArray()) },
            changed { it.addProperty("provenance", "Другой источник") },
            BookPublishedPsalm19.JSON.replaceFirst("{", "{\"original\":\"untrusted\","))
        val own = BookTranslationIndex.fromFeoriaCatalog()
        assertEquals(BookTranslationIndex.normal(target), BookTranslationIndex.normal(target + "!"))
        for (fixture in invalid) {
            val rejected = BookTranslationIndex.fromBundledPsalm19(fixture)
            assertEquals(0, rejected.size); assertNull(rejected.find(target))
            val retained = own.fillingMissing(rejected)
            assertEquals(own.encode(), retained.encode())
        }
    }

    @Test fun lookupDoesNotBecomeFuzzyOrCorrectAnotherLatinLetterOrPartialPsalm() {
        val bundle = BookTranslationIndex.fromBundledPsalm19()
        for (changed in listOf(target + " добавлено", "Добавлено " + target,
            target.replaceFirst("Услы́шит", "Услы́шит иной"), target.substringAfter(' '),
            target.replaceFirst("день", "дeнь"), published,
            target.split(' ').reversed().joinToString(" "))) assertNull(changed, bundle.find(changed))
        // Existing lexical normalization is unchanged: punctuation/stress/speaker handling is not a new alias guarantee.
        assertEquals(pair, bundle.find(BookText.normalized(target)))
        assertEquals(pair, bundle.find("Чтец: $target"))
        assertEquals(pair, bundle.find(target + "!"))
        assertNotEquals(BookTranslationIndex.normal(target), BookTranslationIndex.normal(published))
    }

    @Test fun anExistingExactTargetKeepsPriorityInBothPlusOrdersAndTheProductionFillingOrderIncludingGuardRejection() {
        val bundle = BookTranslationIndex.fromBundledPsalm19()
        val existing = index(encoded(target, "Прежний точный текст", BookSource("Другое издание", "https://example.org/prayer")))
        assertEquals(existing.find(target), existing.plus(bundle).find(target))
        assertEquals(existing.find(target), bundle.plus(existing).find(target))
        assertEquals(existing.find(target), existing.fillingMissing(bundle).find(target))
        // fillingMissing is receiver-first, not a commutative conflict resolver; production uses base.fillingMissing(bundle).
        assertEquals(pair, bundle.fillingMissing(existing).find(target))
        val invalidExact = index(encoded(target, "Неодобренный собственный перевод", BookTranslationIndex.FEORIA_TRANSLATION_SOURCE))
        assertNull(invalidExact.find(target))
        assertNull(invalidExact.plus(bundle).find(target)); assertNull(bundle.plus(invalidExact).find(target))
    }

    @Test fun absentModifiedOrAmbiguousRuntimePublishedKeysCannotGrantOrRevokeAnIndependentReviewedBundle() {
        val bundle = BookTranslationIndex.fromBundledPsalm19()
        val correct = encoded(published, russian)
        val wrong = encoded(published, "Измененный runtime перевод", BookSource("Runtime fixture", "https://example.org/runtime"))
        val corpora = listOf(BookTranslationIndex.empty(), index(correct), index(wrong),
            BookTranslationIndex.decode(JsonArray().apply { add(correct); add(wrong) }.toString()),
            BookTranslationIndex.decode(JsonArray().apply { add(wrong); add(correct) }.toString()),
            BookTranslationCorpus.decode(oldCorpus()).index)
        for (runtime in corpora) {
            assertNull(runtime.find(target))
            assertEquals(pair, runtime.plus(bundle).find(target)); assertEquals(pair, bundle.plus(runtime).find(target))
        }
    }

    @Test fun historicalVerseConflictsAndCloudRodResearchQuarantineReceiveNoNewMatch() {
        val page = BookTranslationIndex.fromParallelPage(Jsoup.parse(raw(), source.url), source)
        val bundle = BookTranslationIndex.fromBundledPsalm19()
        val negatives = negatives(); val conflicts = negatives["verseConflicts"].asJsonArray
        val appended = page.plus(bundle); val prepended = bundle.plus(page)
        assertEquals(12, conflicts.size())
        for (row in conflicts) {
            // These are quarantined derived verse keys. An older whole-row key
            // can deliberately survive; preserve that existing result literally.
            assertNull(bundle.find(row.asString))
            assertEquals(page.find(row.asString), appended.find(row.asString))
            assertEquals(page.find(row.asString), prepended.find(row.asString))
        }
        for (row in JsonParser.parseString(page.encode()).asJsonArray) {
            val key = row.asJsonObject["original"].asString
            assertEquals(page.find(key), appended.find(key))
            assertEquals(page.find(key), prepended.find(key))
        }
        for (row in negatives["cloudRodOriginals"].asJsonArray) {
            assertNull(bundle.find(row.asString)); assertNull(BookTranslationIndex.fromBundledRussian().find(row.asString))
        }
        assertEquals(pair, page.plus(bundle).find(target))
        assertEquals(BookTranslation(russian, source), page.find(published))
    }

    @Test fun actualOldCorpusAndBookGainOnlyThisSupplementalBodyBeyondThe193BaselineAndAcceptItOffline() = runBlocking {
        val old = oldBook(); val before = gson.toJson(old)
        val targetBlock = old.blocks.single { it.id == blockId }
        assertEquals(BookBlockKind.PRAYER, targetBlock.kind); assertTrue(targetBlock.scriptureReferences.isEmpty())
        assertEquals(target, targetBlock.original); assertNull(targetBlock.russian)
        val corpusText = oldCorpus(); val corpus = BookTranslationCorpus.decode(corpusText)
        val baseline = SavedBookEnrichment.apply(old, corpus.index.plus(BookTranslationIndex.fromFeoriaCatalog())) ?: old
        val expected = requireNotNull(SavedBookEnrichment.apply(old, corpus.index.plus(BookTranslationIndex.fromBundledRussian())))
        val delta = baseline.blocks.zip(expected.blocks).filter { (a, b) -> a != b }
        assertEquals(listOf(blockId), delta.map { it.first.id })
        assertEquals(delta.single().first.copy(russian = russian, translationSource = source), delta.single().second)
        assertEquals(old.blocks.map { it.id to it.original }, expected.blocks.map { it.id to it.original })
        assertEquals(old.chapters.map { it.id to it.meaning }, expected.chapters.map { it.id to it.meaning })
        assertEquals(baseline, expected.copy(chapters = baseline.chapters, sources = baseline.sources, contentHash = baseline.contentHash))
        val cache = FileBookCache(directory); assertTrue(cache.write(old)); assertTrue(cache.writeTranslationCorpus(corpusText))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${old.contentHash}.json") }; val oldBytes = oldFile.readBytes()
        val store = BookReadingStore(AcceptanceMemoryPreferences()); val chapter = old.chapters.single { targetBlock in it.blocks }
        val position = BookReadingPosition(old.key, old.contentHash, "psalm19", blockId, chapter.id,
            fraction = .31f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position)); var requests = 0
        val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Bundled exact Psalm19 is offline") },
            translations = BookTranslationIndex.fromBundledRussian())
        val result = requireNotNull(repository.enrichSaved(old, allowNetwork = false))
        assertTrue(result.saved); assertTrue(result.fromCache); assertEquals(expected, result.book)
        assertEquals(0, requests); assertEquals(position, store.last("psalm19"))
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertEquals(corpusText, cache.readTranslationCorpus())
        assertEquals(before, gson.toJson(old)); assertEquals(old, cache.readVersion(old.key, old.contentHash))
        assertTrue(store.acceptVersion(old, result.book, "psalm19", position))
        val accepted = position.copy(contentHash = result.book.contentHash, fraction = 0f)
        assertEquals(accepted, store.last("psalm19"))
        val disk = FileBookCache(directory)
        val offline = ServiceBookRepository(disk, ScheduleTransport { requests++; error("Offline reopen") },
            translations = BookTranslationIndex.fromBundledRussian())
        assertEquals(result.book, (offline.load(old.key) as BookLoadResult.Ready).book)
        assertEquals(accepted, store.restore(result.book, "psalm19")); assertEquals(old, offline.cachedVersion(old.key, old.contentHash))
        assertEquals(pair.russian, disk.read(old.key)?.blocks?.single { it.id == blockId }?.russian)
        assertEquals(0, requests)
    }
}
