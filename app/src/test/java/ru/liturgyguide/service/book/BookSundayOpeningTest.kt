package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files

/** Expected full texts and fourteen IDs are sealed from Astra JSON, never from this parser. */
class BookSundayOpeningTest {
    private val full = BookTranslationIndex.TIMROT_FULL_SOURCE
    private fun text(path: String) = requireNotNull(javaClass.classLoader?.getResource(path)).readText()
    private val oracle get() = JsonParser.parseString(text("book/sunday-opening/oracle.json")).asJsonObject
    private val rows get() = oracle["items"].asJsonArray.map { it.asJsonObject }
    private val cases get() = oracle["cases"].asJsonArray.map { it.asJsonObject }
    private val baseline get() = JsonParser.parseString(text("book/sunday-opening/current126-baseline.json")).asJsonObject
    private fun row(rank: Int) = rows.single { it["rank"].asInt == rank }
    private fun source(row: JsonObject) = row["source"].asJsonObject.let { BookSource(it["title"].asString, it["url"].asString) }
    private fun document(row: JsonObject) = Jsoup.parse(text(row["source"].asJsonObject["fixture"].asString), source(row).url)
    private fun index(row: JsonObject) = BookTranslationIndex.fromParallelPage(document(row), source(row))
    private fun key(case: JsonObject) = case["bookKey"].asString.split('|').let {
        ServiceBookKey(it[0], WorshipKind.valueOf(it[1]), it[2])
    }
    private fun before(case: JsonObject): ServiceBook {
        val frozen = baseline["cases"].asJsonArray.map { it.asJsonObject }.single { it["bookKey"] == case["bookKey"] }
        val html = text(case["fixture"].asString)
        assertEquals(case["sourceFixtureSha256"].asString, BookText.sha256(html))
        val index = BookTranslationIndex.decode(frozen["pairs"].toString())
        assertEquals(frozen["totalDistinctPairs"].asInt, index.size)
        return ServiceBookParser().parse(Jsoup.parse(html, key(case).sourceUrl()), key(case), 1L, translations = index)
    }
    private fun published(case: JsonObject): BookTranslationIndex {
        val tone = if (key(case).date == "2026-11-15") 7 else 8
        return index(row(24)).fillingMissing(index(row(if (tone == 7) 32 else 39)))
    }
    private fun expected(case: JsonObject) = case["expected"].asJsonArray.map { it.asJsonObject }.associateBy { it["blockId"].asString }
    private fun expectedRow(occurrence: JsonObject) = rows.single { it["id"].asString == occurrence["itemId"].asString }
    private fun ServiceBook.rehash() = copy(contentHash = ServiceBookIntegrity.contentHash(this))
    private fun targetRow(doc: Document, rank: Int): Element {
        val body = row(rank)["source"].asJsonObject["sourceCsBody"].asString
        return doc.select("tr").single { tr -> tr.children().size == 2 &&
            BookText.normalized(tr.children()[0].text()).endsWith(BookText.normalized(body)) }
    }
    private fun assertGains(before: ServiceBook, after: ServiceBook, case: JsonObject) {
        val expected = expected(case)
        val changed = before.blocks.zip(after.blocks).filter { it.first != it.second }
        assertEquals(7, changed.size)
        assertEquals(expected.keys, changed.map { it.first.id }.toSet())
        for ((old, next) in changed) {
            val occurrence = expected.getValue(old.id)
            val row = expectedRow(occurrence)
            assertNull(old.russian)
            assertEquals(row["original"].asString, old.original)
            assertEquals(occurrence["originalSha256"].asString, BookText.sha256(old.original))
            assertEquals(row["russian"].asString, next.russian)
            assertEquals(occurrence["russianSha256"].asString, BookText.sha256(requireNotNull(next.russian)))
            assertEquals(source(row), next.translationSource)
            assertEquals(old.copy(russian = next.russian, translationSource = source(row)), next)
        }
        before.blocks.zip(after.blocks).filter { it.first.russian != null }.forEach { (old, next) -> assertEquals(old, next) }
        assertEquals(before.blocks.map { it.id to it.original }, after.blocks.map { it.id to it.original })
        assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
        assertTrue(ServiceBookIntegrity.valid(after))
    }

    @Test fun eightWholePairsAndCitationEqualTheIndependentAstraOracleIncludingCorpusRoundTrip() {
        assertEquals(listOf(23, 24, 25, 27, 28, 29, 32, 39), rows.map { it["rank"].asInt })
        assertEquals("5b5b5558e19041421a24b0784d5aae71b1a768f49d8b574fa995ec6bf4351f3a", oracle["review"].asJsonObject["sha256"].asString)
        assertEquals(14, rows.sumOf { it["occurrences"].asJsonArray.size() })
        for (row in rows) {
            val original = row["original"].asString
            val russian = row["russian"].asString
            val sourceProof = row["source"].asJsonObject
            val html = text(sourceProof["fixture"].asString)
            assertEquals(sourceProof["sourceFileSha256"].asString, BookText.sha256(html))
            assertEquals(row["originalSha256"].asString, BookText.sha256(original))
            assertEquals(row["russianSha256"].asString, BookText.sha256(russian))
            val index = index(row)
            assertEquals(BookTranslation(russian, source(row)), index.find(original))
            assertEquals(index.find(original), BookTranslationIndex.decode(index.encode()).find(original))
            val doc = document(row)
            val oldDom = doc.outerHtml()
            BookTranslationIndex.fromParallelPage(doc, source(row))
            assertEquals(oldDom, doc.outerHtml())
        }
        val citation = row(24)
        assertEquals("Пс 102:1-8", citation["separateSourceCitation"].asString)
        assertFalse(requireNotNull(index(citation).find(citation["original"].asString)).russian.contains("Пс"))
        assertTrue(document(citation).select("a[href=https://azbyka.ru/biblia/?Ps.102:1-8&c~r]").isNotEmpty())
    }

    @Test fun theAcceptedArchiveFilesAndExistingOwnCatalogStayAtTheirIndependentSeals() {
        val protected = CommonTranslationFixtures.oracle["protectedPrevious"].asJsonObject
        for ((fixture, seal) in listOf("legacy-presentation/matins-accepted-unchanged.json" to "archiveMatinsSha256",
            "legacy-presentation/vespers-accepted-unchanged.json" to "archiveVespersSha256")) {
            assertEquals(protected[seal].asString, BookText.sha256(text(fixture)))
        }
        assertEquals(4, FeoriaTranslationCatalog.REVISION)
        assertEquals(126, FeoriaTranslationCatalog.entries.size)
    }

    @Test fun exactWholeSourceToTwoBooksGainsOnlyTheFourteenApprovedIdsAndPreservesEveryOtherBlock() {
        var count = 0
        for (case in cases) {
            val before = before(case)
            val oldBytes = com.google.gson.Gson().toJson(before)
            val selected = source(row(if (key(case).date == "2026-11-15") 32 else 39))
            assertTrue(BookSharedRussianSources.forBook(before).contains(selected))
            val scope = BookVisibilityScope.of(before)
            val missing = before.blocks.count { it.kind == BookBlockKind.PRAYER && it.russian == null && !scope.isPrivate(it) }
            val frozen = baseline["cases"].asJsonArray.map { it.asJsonObject }.single { it["bookKey"] == case["bookKey"] }
            assertEquals(frozen["expectedPublicMissingBeforeSundayOpening"].asInt, missing)
            val after = requireNotNull(SavedBookEnrichment.apply(before, published(case)))
            assertGains(before, after, case)
            assertEquals(missing - 7, after.blocks.count { it.kind == BookBlockKind.PRAYER && it.russian == null && !scope.isPrivate(it) })
            assertEquals(oldBytes, com.google.gson.Gson().toJson(before))
            assertNull(SavedBookEnrichment.apply(after, published(case)))
            count += expected(case).size
        }
        assertEquals(14, count)
    }

    @Test fun countsRolesNumbersExtraPrefixesAndWholeBodyChangesCannotBorrowAnApprovedPair() {
        val target = row(25)
        val original = target["original"].asString
        val index = index(target)
        for (changed in listOf(original.replace("на 12:", "на 10:"), original.replace("1 Лик:", "2 Лик:"),
            original.replace("1 Лик:", "1 Чтец:"), original.replace("на 12:", "на 14:"),
            "на 12: 1 Лик: $original", "Хор: $original", "$original Еще слова", original.substringBeforeLast(' '))) {
            assertNull(changed, index.find(changed))
        }
        val bare = original.removePrefix("на 12: 1 Лик: ")
        val justBare = BookTranslationIndex.decode("""[{"original":${com.google.gson.Gson().toJson(bare)},
            "russian":"Только bare pair без доказанного count","source":${com.google.gson.Gson().toJson(full.title)},
            "url":${com.google.gson.Gson().toJson(full.url)}}]""")
        assertNull("Source 1. is not evidence of a constructor choir label", justBare.find(original))
    }

    @Test fun mismatchedLeadingMetadataAndAdditionalPrayerNodesCloseTheNewCountBoundary() {
        val mutations: List<(Element) -> Unit> = listOf(
            { it.children()[1].select("span.k").first()!!.text("на 6:") },
            { it.children()[1].select("span.n").first()!!.text("2.") },
            { it.children()[0].select("span.n").first()!!.text("1. Молитва") },
            { it.children()[0].select("span.k").first()!!.remove() },
            { it.children()[1].prepend("Неизвестная роль: ") },
            { it.children()[0].appendElement("p").text("Вторая молитва") },
            { it.children()[1].appendElement("span").addClass("k").text("Дважды") },
            { it.children()[0].appendElement("span").addClass("n").text("3") }
        )
        mutations.forEach { change ->
            val doc = document(row(23)); change(targetRow(doc, 23))
            assertNull(BookTranslationIndex.fromParallelPage(doc, full).find(row(23)["original"].asString))
        }
    }

    @Test fun citationUrlDataTitleTextAndTerminalPositionMustAgreeAndNoPrayerWordsCanHideInsideIt() {
        val mutations: List<(Element) -> Unit> = listOf(
            { it.select("a").single().attr("href", "https://example.org/biblia/?Ps.102:1-8&c~r") },
            { it.select("span.bg_bibrefs").single().attr("data-title", "?title=Ps&chapter=102:2-8&type=t_verses&lang=ru") },
            { it.select("a").single().text("Пс 102:1-7") },
            { it.select("span.bg_bibrefs").single().prependText("И еще молитвенные слова") },
            { it.appendText(" / Дополнительное прошение") },
            { it.select("span.n").last()!!.before("Дополнительная молитва ").text("Пс 102:1-8") }
        )
        mutations.forEach { change ->
            val doc = document(row(24)); change(targetRow(doc, 24).children()[1])
            assertNull(BookTranslationIndex.fromParallelPage(doc, full).find(row(24)["original"].asString))
        }
    }

    @Test fun sundayHeadingsToneCountAndOneParagraphAlignmentAreAnEntirePairedContract() {
        for (rank in listOf(32, 39)) {
            val row = row(rank)
            val mutations: List<(Element) -> Unit> = listOf(
                { it.children()[0].select("h2").single().text("На вечерне") },
                { it.children()[0].select("h3").single().text("Блаженны, глас 6:") },
                { it.children()[1].select("h3").single().text("Блаженны, на 7") },
                { it.children()[0].appendElement("p").text("Второй полный тропарь") },
                { it.children()[1].select("p").single().remove() },
                { it.children()[1].prependText("Другая роль: ") },
                { it.children()[1].select("p").single().appendElement("span").addClass("n").text("2.") }
            )
            mutations.forEach { change ->
                val doc = document(row); change(targetRow(doc, rank))
                assertNull(BookTranslationIndex.fromParallelPage(doc, source(row)).find(row["original"].asString))
            }
        }
    }

    @Test fun foreignSourceAliasesWrongTitlesAndDocumentLocationsCannotEnableTheNewBranches() {
        for (rank in listOf(23, 24, 32, 39)) {
            val row = row(rank)
            for (foreign in listOf(source(row).copy(url = "https://example.org/copied/"),
                source(row).copy(url = source(row).url + "?edition=reviewed"),
                source(row).copy(url = source(row).url + "#body"), source(row).copy(title = "Другой издатель"))) {
                assertNull(BookTranslationIndex.fromParallelPage(Jsoup.parse(text(row["source"].asJsonObject["fixture"].asString), foreign.url), foreign)
                    .find(row["original"].asString))
            }
            val wrongTitle = document(row).apply { select("h1").single().text("Другая книга") }
            assertNull(BookTranslationIndex.fromParallelPage(wrongTitle, source(row)).find(row["original"].asString))
            val wrongLocation = Jsoup.parse(text(row["source"].asJsonObject["fixture"].asString), "https://example.org/copy")
            assertNull(BookTranslationIndex.fromParallelPage(wrongLocation, source(row)).find(row["original"].asString))
        }
    }

    @Test fun allThreeAstraRejectedActualWitnessRowsStayRejectedWithoutReindexingOrRepair() {
        val rejected = oracle["rejectedWitnesses"].asJsonArray.map { it.asJsonObject }
        assertEquals(3, rejected.size)
        rejected.forEach { item ->
            val identity = item["sourceIdentity"].asJsonObject
            val source = BookSource(identity["sourceTitle"].asString, identity["sourceUrl"].asString)
            val fixture = if (source == full) "full-liturgy.html" else "short-liturgy-rejected-witnesses.html"
            val doc = Jsoup.parse(text("book/sunday-opening/$fixture"), source.url)
            val actual = doc.select("tr")[item["rowIndex"].asInt]
            val cells = actual.children()
            assertEquals(2, cells.size)
            assertNull(BookSundayOpening.fromRow(doc, source, cells[0], cells[1]))
        }
    }

    @Test fun newDerivedBodyNeverReplacesAnOlderBarePairButQualifiedBookMetadataSurvivesMergingAndDiskCodec() {
        val target = row(25)
        val bare = target["original"].asString.removePrefix("на 12: 1 Лик: ")
        val old = BookTranslationIndex.decode(com.google.gson.Gson().toJson(listOf(mapOf("original" to bare,
            "russian" to "Прежний принятый bare перевод", "source" to full.title, "url" to BookTranslationIndex.VIGIL_SOURCE.url))))
        val merged = index(target).plus(old)
        assertEquals(old.find(bare), merged.find(bare))
        assertEquals(BookTranslation(target["russian"].asString, full), merged.find(target["original"].asString))
        assertEquals(merged.find(bare), BookTranslationIndex.decode(merged.encode()).find(bare))
        assertEquals(merged.find(target["original"].asString), BookTranslationIndex.decode(merged.encode()).find(target["original"].asString))
        assertEquals(old.find(bare), BookTranslationIndex.decode(index(target).encode()).plus(old).find(bare))
        val primary = merged.fromSources(BookTranslationIndex.PARALLEL_SOURCES)
        assertEquals(merged.find(target["original"].asString), BookTranslationIndex.decode(primary.encode()).find(target["original"].asString))
    }

    @Test fun anAlreadyAcceptedPublishedTargetStaysVerbatimEvenWhenAnotherReviewedEditionIsAvailable() {
        val case = cases.first()
        val target = expected(case).keys.first()
        val before = before(case).let { book -> book.copy(chapters = book.chapters.map { chapter ->
            chapter.copy(blocks = chapter.blocks.map { block -> if (block.id == target)
                block.copy(russian = "Ранее принятый опубликованный перевод", translationSource = full) else block })
        }).rehash() }
        val after = requireNotNull(SavedBookEnrichment.apply(before, published(case)))
        val changed = before.blocks.zip(after.blocks).filter { it.first != it.second }
        assertEquals(6, changed.size)
        assertEquals(expected(case).keys - target, changed.map { it.first.id }.toSet())
        assertEquals(before.blocks.single { it.id == target }, after.blocks.single { it.id == target })
    }

    @Test fun corpusTwelveMigrationKeepsEveryOldPairOfflineAndOnlyMarksTheThreeChangedPagesIncomplete() {
        val captured = BookTranslationIndex.decode(text("book/published-russian/corpus11-3694.json").let {
            JsonParser.parseString(it).asJsonObject["entries"].toString()
        })
        assertEquals(3694, captured.size)
        val all = BookTranslationIndex.ALL_PARALLEL_SOURCES.map { it.url }.toSet()
        val old = BookTranslationCorpus(captured, all, version = 12)
        val oldEncoded = old.encode()
        val decoded = BookTranslationCorpus.decode(oldEncoded)
        assertEquals(13, decoded.version); assertTrue(decoded.current)
        assertEquals(captured.encode(), decoded.index.encode())
        assertEquals(all - BookTranslationCorpus.REEXTRACTED_SOURCES, decoded.completedSources)
        assertEquals(setOf(full.url, BookTranslationIndex.OCTOECHOS_SOURCES[6].url, BookTranslationIndex.OCTOECHOS_SOURCES[7].url),
            BookTranslationCorpus.REEXTRACTED_SOURCES)
        assertFalse(decoded.completeFor(WorshipKind.LITURGY))
        assertTrue(decoded.completeFor(WorshipKind.MATINS))
        JsonParser.parseString(captured.encode()).asJsonArray.forEach { raw ->
            val item = raw.asJsonObject
            assertEquals(captured.find(item["original"].asString), decoded.index.find(item["original"].asString))
        }
        assertEquals(decoded.index.encode(), BookTranslationCorpus.decode(decoded.encode()).index.encode())
        assertEquals(oldEncoded, old.encode())
    }

    @Test fun migratedNetworkPassRequestsOnlyFullEditionAndTheSelectedSundayAndKeepsAcceptedPublishedBlocks() = runBlocking {
        val case = cases.first()
        val before = before(case)
        val directory = Files.createTempDirectory("sunday-selected-refresh-").toFile()
        try {
            val cache = FileBookCache(directory)
            assertTrue(cache.write(before))
            val frozen = baseline["cases"].asJsonArray.first().asJsonObject
            val oldIndex = BookTranslationIndex.decode(frozen["pairs"].toString())
            assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(oldIndex,
                BookTranslationIndex.ALL_PARALLEL_SOURCES.map { it.url }.toSet(), version = 12).encode()))
            val selected = source(row(32))
            val requests = mutableListOf<String>()
            val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
                requests += url
                val fixture = when (url) {
                    full.url -> row(24)["source"].asJsonObject["fixture"].asString
                    selected.url -> row(32)["source"].asJsonObject["fixture"].asString
                    else -> error("Unchanged or unrelated source must not be fetched: $url")
                }
                ScheduleResponse(url, text(fixture).toByteArray(), "text/html;charset=utf-8")
            }, translations = BookTranslationIndex.empty())
            val candidate = requireNotNull(repository.enrichSaved(before)).book
            assertEquals(listOf(full.url, selected.url), requests)
            assertGains(before, candidate, case)
            assertEquals(before, cache.readVersion(before.key, before.contentHash))
            assertFalse(BookTranslationCorpus.decode(cache.readTranslationCorpus()).completedSources.contains(source(row(39)).url))
        } finally { directory.deleteRecursively() }
    }

    @Test fun offlineCandidateNeedsExplicitAcceptAndBothExactEditionsAndOldBytesSurviveRecreation() = runBlocking {
        for (case in cases) {
            val directory = Files.createTempDirectory("sunday-offline-offer-").toFile()
            try {
                val before = before(case)
                val cache = FileBookCache(directory)
                assertTrue(cache.write(before))
                val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${before.contentHash}.json") }
                val oldBytes = oldFile.readBytes()
                val target = expected(case).keys.first()
                val chapter = before.chapters.single { c -> c.blocks.any { it.id == target } }
                val preferences = AcceptanceMemoryPreferences()
                val store = BookReadingStore(preferences)
                val position = BookReadingPosition(before.key, before.contentHash, "sunday-opening", target, chapter.id,
                    fraction = .4f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
                assertTrue(store.save(position))
                val acceptedPreferences = preferences.values.toMap()
                assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(published(case)).encode()))
                var requests = 0
                val repository = ServiceBookRepository(cache, ScheduleTransport { requests++; error("Offline") },
                    translations = BookTranslationIndex.empty())
                val candidate = requireNotNull(repository.enrichSaved(before, allowNetwork = false)).book
                assertGains(before, candidate, case)
                assertEquals(position, store.last("sunday-opening")); assertEquals(acceptedPreferences, preferences.values)
                assertArrayEquals(oldBytes, oldFile.readBytes())
                assertTrue(store.acceptVersion(before, candidate, "sunday-opening", position))
                val accepted = requireNotNull(store.last("sunday-opening"))
                assertEquals(position.copy(contentHash = candidate.contentHash, fraction = 0f), accepted)
                val reopened = FileBookCache(directory)
                assertEquals(before, reopened.readVersion(before.key, before.contentHash))
                assertEquals(candidate, reopened.readVersion(candidate.key, candidate.contentHash))
                assertEquals(accepted, BookReadingStore(preferences).restore(candidate, "sunday-opening"))
                assertArrayEquals(oldBytes, oldFile.readBytes())
                assertEquals(0, requests)
            } finally { directory.deleteRecursively() }
        }
    }
}
