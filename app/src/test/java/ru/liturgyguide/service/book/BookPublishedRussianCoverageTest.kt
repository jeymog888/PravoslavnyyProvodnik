package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files

/** Downloaded publisher fixtures and finite reviewed whole-body oracles, not invented translations. */
class BookPublishedRussianCoverageTest {
    private fun text(name: String) = requireNotNull(javaClass.getResourceAsStream("/book/published-russian/$name"))
        .bufferedReader().use { it.readText() }
    private fun index(tone: Int) = BookTranslationIndex.OCTOECHOS_SOURCES[tone - 1].let {
        BookTranslationIndex.fromParallelPage(Jsoup.parse(text("sunday-$tone.html"), it.url), it)
    }
    private fun irmoi() = BookPublishedIrmos6.SOURCES.fold(BookTranslationIndex.empty()) { index, source ->
        val file = if (source == BookPublishedIrmos6.SATURDAY) "great-saturday.html" else "great-friday.html"
        index.fillingMissing(BookTranslationIndex.fromParallelPage(Jsoup.parse(text(file), source.url), source))
    }
    private val cases get() = JsonParser.parseString(text("irmos6-cases.json")).asJsonArray.map { it.asJsonObject }
    private fun matins() = ServiceBookParser().parse(Jsoup.parse(BookFixtures.html("matins-2026-11-15")),
        ServiceBookKey("2026-11-15", WorshipKind.MATINS), 12345)
    private fun day(date: String, tone: Int?) = BookCalendarDay(date, "Память дня", tone, emptyList(), "https://azbyka.ru/days/$date")
    private fun ServiceBook.rehash() = copy(contentHash = ServiceBookIntegrity.contentHash(this))

    @Test fun eightRealSundayPagesPassStrictStructureAndRetainTheirPublishedAttribution() {
        assertEquals(8, BookTranslationIndex.OCTOECHOS_SOURCES.size)
        assertEquals(BookTranslationIndex.OCTOECHOS_SOURCES[6], BookTranslationIndex.OCTOECHOS7_SOURCE)
        (1..8).forEach { tone ->
            val source = BookTranslationIndex.OCTOECHOS_SOURCES[tone - 1]
            val parsed = index(tone)
            assertTrue("Actual tone $tone is usable", parsed.size > 100)
            val pairs = JsonParser.parseString(parsed.encode()).asJsonArray
            assertTrue(pairs.all { it.asJsonObject.get("url").asString == source.url })
            assertTrue(BookTranslationIndex.ALL_PARALLEL_SOURCES.contains(source))
            val doc = Jsoup.parse(text("sunday-$tone.html"), source.url)
            val original = doc.outerHtml()
            val right = doc.select(".article-single-content table tr").mapNotNull { it.children().getOrNull(1)?.outerHtml() }
            val corrected = requireNotNull(BookSundayRussianPage.prepare(doc, source))
            assertEquals("The caller's source DOM remains immutable", original, doc.outerHtml())
            assertEquals(right, corrected.select(".article-single-content table tr").mapNotNull { it.children().getOrNull(1)?.outerHtml() })
            assertEquals(source.url, corrected.location())
        }
        assertTrue(BookTranslationIndex.requiredSources(WorshipKind.LITURGY).none { it in BookTranslationIndex.OCTOECHOS_SOURCES })
        assertEquals(3, BookTranslationIndex.requiredSources(WorshipKind.LITURGY).size)
    }

    @Test fun foreignUrlRedirectWrongTitleWrongToneAndBrokenColumnsCannotActivateCorrection() {
        val source = BookTranslationIndex.OCTOECHOS_SOURCES[6]
        fun rejected(change: (org.jsoup.nodes.Document) -> Unit) {
            val doc = Jsoup.parse(text("sunday-7.html"), source.url); change(doc)
            assertEquals(0, BookTranslationIndex.fromParallelPage(doc, source).size)
        }
        assertEquals(0, BookTranslationIndex.fromParallelPage(Jsoup.parse(text("sunday-7.html"), "https://example.org/copy/"), source).size)
        rejected { it.select("h1").single().text("Октоих воскресный. Глас 6") }
        rejected { it.select("h1").single().text("Другая книга") }
        rejected { it.select(".article-single-content table tr").first()!!.appendElement("td").text("Третья колонка") }
        rejected { it.select(".article-single-content table td").first()!!.attr("rowspan", "2") }
        rejected { it.select(".article-single-content").removeClass("main-page-content") }
        assertEquals(0, BookTranslationIndex.fromParallelPage(Jsoup.parse(text("sunday-7.html"), source.url),
            BookTranslationIndex.OCTOECHOS_SOURCES[5]).size)
    }

    @Test fun pGlyphCorrectionInspectsTheWholeRawTokenBeforeTheGeneralNormalizer() {
        assertEquals("Престол, престо́л, РАБ, раб", BookSundayRussianPage.correctText("Пpестол, пpесто́л, PАБ, pаб"))
        val forbidden = listOf("p", "P", "Peter", "PETER", "αpестол", "пpіiстол", "пpиiстол", "пp12естол", "πpестол", "p\u0483", "P\u0483")
        forbidden.forEach { assertEquals(it, it, BookSundayRussianPage.correctText(it)) }
        val source = BookTranslationIndex.OCTOECHOS_SOURCES[6]
        val doc = Jsoup.parse(text("sunday-7.html"), source.url)
        val row = doc.select(".article-single-content table tr").first()!!.parent()!!.appendElement("tr")
        row.appendElement("td").attr("data-proof", "Пpестол").text("Пpестол")
        row.appendElement("td").text("Пpестол в переводе")
        val copy = requireNotNull(BookSundayRussianPage.prepare(doc, source))
        val last = copy.select("td[data-proof]").single()
        assertEquals("Престол", last.text()); assertEquals("Пpестол", last.attr("data-proof"))
        assertEquals("Пpестол в переводе", last.nextElementSibling()!!.text())
        assertEquals("Пpестол", row.children()[0].text())
    }

    @Test fun rawTokenGuardsSpanInlineMarkupBeforeAnyConcordanceNormalization() {
        val source = BookTranslationIndex.OCTOECHOS7_SOURCE
        val doc = Jsoup.parse(text("sunday-7.html"), source.url)
        val table = doc.select(".article-single-content table").first()!!
        val html = linkedMapOf(
            "latin" to "Пp<span>i</span>стол",
            "greek" to "Пp<span>ι</span>стол",
            "mark" to "p<span>\u0483</span>",
            "eligible" to "П<span>p</span>естол"
        )
        html.forEach { (id, body) ->
            val row = table.appendElement("tr")
            row.appendElement("td").attr("id", id).html(body)
            row.appendElement("td").text("Неизменный русский перевод $id")
        }
        val original = doc.outerHtml()
        val prepared = requireNotNull(BookSundayRussianPage.prepare(doc, source))
        assertEquals("Пpiстол", prepared.getElementById("latin")!!.wholeText())
        assertEquals("Пpιстол", prepared.getElementById("greek")!!.wholeText())
        assertEquals("p\u0483", prepared.getElementById("mark")!!.wholeText())
        assertEquals("Престол", prepared.getElementById("eligible")!!.wholeText())
        assertEquals(1, prepared.getElementById("eligible")!!.select("span").size)
        html.keys.forEach { id ->
            assertEquals("Неизменный русский перевод $id", prepared.getElementById(id)!!.nextElementSibling()!!.text())
        }
        assertEquals(original, doc.outerHtml())
        val contaminated = Jsoup.parse(text("sunday-7.html"), source.url)
        contaminated.select(".article-single-content table").first()!!.appendElement("tr").apply {
            appendElement("td").html("Пp<span>i</span>стол")
            appendElement("td").text("Нельзя сопоставить по исправленному кириллическому слову")
        }
        assertNull(BookTranslationIndex.fromParallelPage(contaminated, source).find("Пристол"))
    }

    @Test fun allEightReviewedIrmosPairsUseTheirActualRussianAndExactOriginalBodies() {
        val index = irmoi()
        assertEquals("Three exact bodies and five finite whole-hymn aliases", 13, index.size)
        assertEquals(8, cases.size)
        cases.forEach { c ->
            val original = c.get("archivedOriginal").asString
            val pair = requireNotNull(index.find(original)) { original }
            assertEquals(c.get("proposedRussian").asString, pair.russian)
            assertEquals(c.get("sourceUrl").asString, pair.source.url)
            assertEquals(BookTranslationIndex.TIMROT_SOURCE.title, pair.source.title)
            assertEquals(c.get("publishedRussianSha256").asString, BookText.sha256(index.find(c.get("publishedOriginal").asString)!!.russian))
            assertNull(index.find(original.replace("глас 6:", "глас 5:")))
            assertNull(index.find("Ирмос, глас 6: $original"))
            assertNull(index.find("$original (Дважды)"))
            assertNull(index.find(original.substringBeforeLast(' ')))
            assertNull(index.find(original + " Иное слово"))
            assertNull(index.find(original.replaceFirst(": ", ": Не ")))
        }
        val fifth = cases.single { it.get("chapter").asString == "Песнь 5" }.get("archivedOriginal").asString
        assertNull("The initial К belongs to the hymn", index.find(fifth.replace("К Тебе́", "Тебе́")))
        val sixth = cases.single { it.get("chapter").asString == "Песнь 6" }.get("archivedOriginal").asString
        assertNull(index.find(sixth.replace("неудержа́н", "удержа́н")))
        assertNull(index.find(sixth.replace("Ио́на", "Иоа́нн")))
        assertNull(index.find(sixth.replace("Я́т бы́сть", "Взя́т бы́сть")))
    }

    @Test fun seasonalIrmosPairsCannotBypassTheirStrictContractThroughAGenericTonePrefix() {
        val index = irmoi()
        cases.forEach { c ->
            val body = c.get("publishedOriginal").asString
            (1..8).forEach { voice -> assertNull(index.find("Глас $voice: $body")) }
            val changed = JsonParser.parseString(index.encode()).asJsonArray
            changed.forEach { raw -> raw.asJsonObject.addProperty("russian", "Измененный непроверенный перевод") }
            val decoded = BookTranslationIndex.decode(changed.toString())
            assertNull(decoded.find(body))
            assertNull(decoded.find(c.get("archivedOriginal").asString))
            assertNull(decoded.find("Глас 5: $body"))
            assertNull(decoded.find("Глас 6: $body"))
        }
    }

    @Test fun knownPublicationAliasesCannotEscapeTheStrictAdaptersOrTheDecodedIrmosContract() {
        val aliases = listOf("?q=1", "#fragment", "?q=1#fragment")
        for (source in BookTranslationIndex.OCTOECHOS_SOURCES + BookPublishedIrmos6.SOURCES) {
            for (alias in aliases) {
                val changed = source.copy(url = source.url + alias)
                val doc = Jsoup.parse("<table><tr><td>Молитва исходная</td><td>Русский перевод</td></tr></table>", changed.url)
                assertEquals("Alias must not enter generic parser: ${changed.url}", 0,
                    BookTranslationIndex.fromParallelPage(doc, changed).size)
            }
        }
        val index = irmoi()
        for (alias in aliases) {
            val encoded = JsonParser.parseString(index.encode()).asJsonArray
            encoded.forEach { raw -> raw.asJsonObject.let {
                it.addProperty("url", it.get("url").asString + alias)
                it.addProperty("russian", "Непроверенный измененный перевод")
            } }
            val decoded = BookTranslationIndex.decode(encoded.toString())
            cases.forEach { c ->
                val body = c.get("publishedOriginal").asString
                assertNull(decoded.find(body))
                assertNull(decoded.find(c.get("archivedOriginal").asString))
                assertNull(decoded.find("Глас 6: $body"))
            }
        }
    }

    @Test fun anAlteredPublishedPairOrStructuralLabelDoesNotActivateTheReviewedAlias() {
        val c = cases.first()
        val source = BookPublishedIrmos6.SATURDAY
        val body = c.get("archivedOriginal").asString
        val original = c.get("publishedOriginal").asString
        val russian = c.get("publishedRussian").asString
        fun changed(from: String, to: String): BookTranslationIndex = BookTranslationIndex.fromParallelPage(
            Jsoup.parse(text("great-saturday.html").replace(from, to), source.url), source)
        assertNull(changed("Волною морскою", "Волною земною").find(body))
        assertNull(changed("Покрывшего в древности", "Покрывшего вчера").find(body))
        assertNull(BookTranslationIndex.fromParallelPage(Jsoup.parse(text("great-saturday.html"), "https://example.org/copy"), source).find(body))
        val doc = Jsoup.parse(text("great-saturday.html"), source.url)
        val row = doc.select("table tr").first { it.children().size == 2 && it.children()[0].text().contains(original) }
        row.parent()!!.appendChild(row.clone())
        assertNull("An unexpected third whole pair cannot pass the two inspected occurrences", BookTranslationIndex.fromParallelPage(doc, source).find(body))
        val fri = BookPublishedIrmos6.FRIDAY
        val fifth = cases.single { it.get("chapter").asString == "Песнь 5" }.get("archivedOriginal").asString
        val friday = Jsoup.parse(text("great-friday.html"), fri.url)
        friday.select("span.k").filter { it.text().contains("{Π}") && it.text().contains("Ирмос") }.forEach { it.text("Ирмос:") }
        assertNull(BookTranslationIndex.fromParallelPage(friday, fri).find(fifth))
        assertTrue(russian.isNotBlank())
    }

    @Test fun optionalToneSelectionUsesPublishedSameDateSundayOrThePreviouslySupportedCompleteRubric() {
        val book = matins()
        fun withoutOctoechos(b: ServiceBook) = b.copy(chapters = b.chapters.map { c -> c.copy(blocks = c.blocks.filterNot {
            it.kind == BookBlockKind.RUBRIC && BookText.normalized(it.original).startsWith("октоих")
        }) })
        val noRubric = withoutOctoechos(book)
        assertTrue(BookSharedRussianSources.forBook(noRubric).isEmpty())
        (1..8).forEach { tone ->
            val source = BookTranslationIndex.OCTOECHOS_SOURCES[tone - 1]
            val chosen = noRubric.copy(calendarDay = day(book.key.date, tone))
            assertEquals(listOf(source), BookSharedRussianSources.forBook(chosen))
            assertEquals(listOf(source), BookSharedRussianSources.forBook(chosen.copy(key = chosen.key.copy(worship = WorshipKind.VESPERS))))
        }
        assertTrue(BookSharedRussianSources.forBook(book.copy(calendarDay = day(book.key.date, 6))).isEmpty())
        assertTrue(BookSharedRussianSources.forBook(book.copy(calendarDay = day("2026-11-22", 7))).isEmpty())
        assertTrue(BookSharedRussianSources.forBook(noRubric.copy(calendarDay = day("2026-11-13", 6), key = book.key.copy(date = "2026-11-13"))).isEmpty())
        val arbitraryCanon = noRubric.copy(chapters = listOf(BookChapter("c", "Канон", "", listOf(BookBlock("b", BookBlockKind.RUBRIC, null, "Глас 6:")))))
        assertTrue(BookSharedRussianSources.forBook(arbitraryCanon).isEmpty())
        val contradictory = book.copy(chapters = book.chapters.map { c -> c.copy(blocks = c.blocks.map {
            if (it.kind == BookBlockKind.RUBRIC && BookText.normalized(it.original).startsWith("октоих")) it.copy(original = "Октоих, глас 7, Неделя на 8, неизвестная приписка") else it
        }) }, calendarDay = day(book.key.date, 7))
        assertTrue(BookSharedRussianSources.forBook(contradictory).isEmpty())
    }

    @Test fun regressionCoversActualBaselineToCandidateDeltaOnSixRealSundayBooks() {
        val oracle = JsonParser.parseString(text("sunday-coverage-oracle.json")).asJsonArray
        assertEquals(listOf(74, 8, 0, 52, 6, 4), oracle.map { it.asJsonObject.get("gains").asInt })
        val corpus = BookTranslationCorpus.decode(text("corpus11-3694.json"))
        assertEquals(3694, corpus.index.size)
        val common = corpus.index.plus(BookTranslationIndex.fromFeoriaCatalog())
        val calendar15 = requireNotNull(PublishedCalendarSupplements.decode(
            requireNotNull(javaClass.getResourceAsStream("/calendar/2026-11-15.json")).bufferedReader().use { it.readText() }, "2026-11-15"))
        val calendar22 = requireNotNull(BookTranslationIndex.fromCalendarPage(Jsoup.parse(
            text("calendar-2026-11-22.html"), "https://azbyka.ru/days/2026-11-22"), "2026-11-22"))
        var total = 0
        oracle.forEach { raw ->
            val c = raw.asJsonObject
            val fields = c.get("key").asString.split('|')
            val key = ServiceBookKey(fields[0], WorshipKind.valueOf(fields[1]), churchType = fields[2])
            val optional = index(c.get("tone").asInt)
            var baseline = common.plus(if (key.date == "2026-11-15") calendar15.translations else calendar22)
            // The prior release already selected Sunday7 for Liturgy. It did not select Sunday8,
            // nor a direct Matins/Vespers page. Preserve that real pre-change baseline.
            if (key.date == "2026-11-15" && key.worship == WorshipKind.LITURGY) baseline = baseline.plus(index(7))
            val html = if (key.date == "2026-11-15") BookFixtures.html("${key.worship.queryValue}-${key.date}")
                else text("${key.worship.queryValue}-${key.date}.html")
            val parsed = ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 12345, translations = baseline)
            val before = parsed.copy(calendarDay = if (key.date == "2026-11-15") calendar15.day else day(key.date, 8)).rehash()
            val visibility = BookVisibilityScope.of(before)
            val missingBefore = before.blocks.filter { it.kind == BookBlockKind.PRAYER && it.russian == null && !visibility.isPrivate(it) }
            assertEquals("Actual baseline missing: ${key.canonical()}", c.get("missingBefore").asInt, missingBefore.size)
            assertTrue(BookSharedRussianSources.forBook(before).contains(BookTranslationIndex.OCTOECHOS_SOURCES[c.get("tone").asInt - 1]))
            val after = SavedBookEnrichment.apply(before, optional) ?: before
            val gains = before.blocks.zip(after.blocks).filter { (a, b) ->
                a.kind == BookBlockKind.PRAYER && a.russian == null && b.russian != null && !visibility.isPrivate(a)
            }
            val expected = c.getAsJsonArray("rows").map { it.asJsonObject }
            assertEquals("Actual candidate gains: ${key.canonical()}", c.get("gains").asInt, gains.size)
            assertEquals(expected.map { it.get("id").asString }, gains.map { it.first.id })
            expected.zip(gains).forEach { (e, pair) ->
                val (old, new) = pair
                assertEquals(e.get("original").asString, old.original)
                assertEquals(e.get("russian").asString, new.russian)
                assertEquals(e.get("sourceUrl").asString, new.translationSource?.url)
            }
            assertEquals(before.key, after.key); assertEquals(before.calendarDay, after.calendarDay)
            assertEquals(before.blocks.map { it.original }, after.blocks.map { it.original })
            assertEquals(before.blocks.map { it.meaning }, after.blocks.map { it.meaning })
            before.blocks.zip(after.blocks).filter { (a, _) ->
                a.russian != null && a.translationSource != BookTranslationIndex.FEORIA_TRANSLATION_SOURCE
            }.forEach { (a, b) -> assertEquals("An accepted published pair is immutable", a, b) }
            total += gains.size
        }
        assertEquals("Fragments include repeated hymns, not144 unique translations", 144, total)
    }

    @Test fun old3694PairCorpusStaysUsefulButCannotMarkTheChangedExtractionComplete() {
        val old = BookTranslationCorpus.decode(text("corpus11-3694.json"))
        assertEquals(3694, old.index.size); assertEquals(11, old.version); assertFalse(old.current)
        val obsoleteWithSundayFlag = old.copy(completedSources = old.completedSources + BookTranslationIndex.OCTOECHOS7_SOURCE.url)
        assertFalse(obsoleteWithSundayFlag.completeFor(listOf(BookTranslationIndex.OCTOECHOS7_SOURCE)))
        val current = BookTranslationCorpus(old.index.fillingMissing(index(7)), obsoleteWithSundayFlag.completedSources)
        assertTrue(current.current)
        val decoded = BookTranslationCorpus.decode(current.encode())
        assertEquals(current.index.size, decoded.index.size)
        assertTrue(decoded.completeFor(listOf(BookTranslationIndex.OCTOECHOS7_SOURCE)))
        val pair = JsonParser.parseString(old.index.encode()).asJsonArray.first().asJsonObject
        assertEquals(pair.get("russian").asString, decoded.index.find(pair.get("original").asString)?.russian)
    }

    @Test fun savedIrmosCandidateKeepsTheAcceptedOriginalEditionAndPublishedPairsUnchanged() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/legacy-presentation/matins-accepted-unchanged.json"))
            .bufferedReader().use { it.readText() }
        val accepted = Gson().fromJson(raw, ServiceBook::class.java)
        val candidate = requireNotNull(SavedBookEnrichment.apply(accepted, irmoi()))
        assertEquals(accepted.key, candidate.key); assertEquals(accepted.calendarDay, candidate.calendarDay)
        assertEquals(accepted.blocks.map { it.original }, candidate.blocks.map { it.original })
        assertEquals(accepted.blocks.map { it.meaning }, candidate.blocks.map { it.meaning })
        assertNotEquals(accepted.contentHash, candidate.contentHash)
        val ids = cases.map { it.get("blockId").asString }.toSet()
        val gains = accepted.blocks.zip(candidate.blocks).filter { (a, b) -> a.russian == null && b.russian != null }
        assertEquals(ids, gains.map { it.first.id }.toSet())
        gains.forEach { (a, b) -> assertNull(a.russian); assertTrue(b.translationSource in BookPublishedIrmos6.SOURCES) }
        accepted.blocks.zip(candidate.blocks).filter { it.first.russian != null }.forEach { (a, b) -> assertEquals(a, b) }
        assertNull(SavedBookEnrichment.apply(candidate, irmoi()))
        assertEquals(BookPublishedIrmos6.SOURCES.toSet(), BookSharedRussianSources.forBook(accepted).toSet())
    }

    @Test fun directMatinsLoadsOnlyTheSelectedToneAndTheCompleteCacheMakesNoFurtherRequests() = runBlocking {
        val dir = Files.createTempDirectory("sunday-direct-").toFile()
        try {
            val cache = FileBookCache(dir)
            val accepted = matins().copy(calendarDay = day("2026-11-15", 7)).rehash()
            assertTrue(cache.write(accepted))
            val old = BookTranslationCorpus.decode(text("corpus11-3694.json"))
            val required = BookTranslationIndex.requiredSources(WorshipKind.MATINS).map { it.url }.toSet()
            // Current rules with the selected Sunday deliberately incomplete, simulating direct Matins opening.
            assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(old.index,
                required + old.completedSources - BookTranslationIndex.OCTOECHOS7_SOURCE.url).encode()))
            val requested = mutableListOf<String>()
            val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
                requested += url
                assertEquals(BookTranslationIndex.OCTOECHOS7_SOURCE.url, url)
                ScheduleResponse(url, text("sunday-7.html").toByteArray(), "text/html;charset=utf-8")
            })
            val candidate = requireNotNull(repository.enrichSaved(accepted)).book
            assertEquals(listOf(BookTranslationIndex.OCTOECHOS7_SOURCE.url), requested)
            assertEquals(accepted, cache.readVersion(accepted.key, accepted.contentHash))
            assertTrue(candidate.translatedPrayerCount > accepted.translatedPrayerCount)
            requested.clear()
            repository.enrichSaved(candidate)
            assertTrue(requested.isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
