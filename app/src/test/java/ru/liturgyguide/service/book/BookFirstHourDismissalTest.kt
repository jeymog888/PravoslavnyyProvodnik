package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

/** Pinned complete host sources. Legacy books are explicitly reconstructed before the test starts. */
class BookFirstHourDismissalTest {
    private val gson = Gson()

    @Test fun native86ArchiveBytesAndTheirExplicitDesktopProjectionsRetainTheSameBodyAndScope() {
        val provenance = JsonParser.parseString(resource("first-hour-dismissal/native86-provenance.json"))
            .asJsonObject["entries"].asJsonArray
        assertEquals(2, provenance.size())
        for (entry in provenance) {
            val pin = entry.asJsonObject
            val path = "first-hour-dismissal/${pin["file"].asString}"
            val raw = resource(path)
            assertEquals(pin["bytes"].asInt, raw.toByteArray(Charsets.UTF_8).size)
            assertEquals(pin["sha256"].asString, BookText.sha256(raw))
            val tree = JsonParser.parseString(raw).asJsonObject
            val archivedRepresentation = tree.deepCopy().apply {
                addProperty("fetchedAtMillis", 0)
                addProperty("contentHash", "")
            }
            assertEquals(pin["contentHash"].asString, BookText.sha256(gson.toJson(archivedRepresentation)))
            val archived = gson.fromJson(tree, ServiceBook::class.java)
            assertEquals(pin["contentHash"].asString, archived.contentHash)
            val acceptedRepresentation = gson.toJson(archived)
            val position = BookReadingPosition(archived.key, archived.contentHash, "native86-archive",
                pin["bodyId"].asString, archived.chapters.last().id, fraction = .37f,
                mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)
            val acceptedCursor = BookReadingCodec.encode(position)
            // Android/Dex field order differs from desktop Gson. This separate in-memory
            // projection has a desktop hash; the genuine archive and its cursor keep theirs.
            val desktopProjection = hashed(archived)
            assertTrue(ServiceBookIntegrity.valid(desktopProjection))
            val block = desktopProjection.blocks.single { it.id == pin["bodyId"].asString }
            val checked = BookFirstHourDismissalScope.of(desktopProjection)
            assertEquals(pin["originalSha256"].asString, BookText.sha256(block.original))
            assertTrue(checked.isLegacyPrayer(desktopProjection, block))
            assertNull(BookBlockPresentation.effectiveRussian(desktopProjection, block, checked))
            assertEquals(cases.first().meaning, checked.meaning(desktopProjection, desktopProjection.chapters.last(), block.id))
            assertEquals(acceptedRepresentation, gson.toJson(archived))
            assertEquals(acceptedCursor, BookReadingCodec.encode(position))
            assertEquals(raw, resource(path))
        }
    }

    @Test fun bothExactTerminalSourcesBecomeIndependentWholePrayersWithEmptyAndOwnIndexes() {
        for (expected in cases) for (own in listOf(false, true)) {
            val source = document(expected.date)
            val untouched = source.outerHtml()
            val book = parse(source, expected.date, if (own) BookTranslationIndex.fromFeoriaCatalog() else BookTranslationIndex.empty())
            val chapter = book.chapters.last()
            val block = chapter.blocks.first()
            assertEquals(expected.hostSha, BookText.sha256(resource("first-hour-dismissal/${expected.date}-chas1.html")))
            assertEquals("Отпуст", chapter.title)
            assertEquals(2, chapter.blocks.size)
            assertEquals(expected.original, block.original)
            assertEquals(expected.originalSha, BookText.sha256(block.original))
            assertEquals(BookBlockKind.PRAYER, block.kind)
            assertNull(block.speaker)
            assertFalse(block.isAltarText)
            assertTrue(block.scriptureReferences.isEmpty())
            assertEquals(expected.meaning, block.meaning)
            assertEquals(expected.meaning, chapter.meaning)
            if (own) {
                assertEquals(expected.russian, block.russian)
                assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, block.translationSource)
                assertTrue(book.sources.contains(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE))
            } else {
                assertNull(block.russian)
                assertNull(block.translationSource)
            }
            val scope = BookFirstHourDismissalScope.of(book)
            assertFalse(scope.isLegacyPrayer(book, block))
            assertEquals(expected.meaning, scope.meaning(book, chapter, block.id))
            assertEquals(expected.meaning, BookMeaningCatalog.displayMeaning(book, chapter))
            assertNull(scope.meaning(book, chapter, chapter.blocks.last().id))
            val bodyPosition = BookReadingPosition(book.key, book.contentHash, null, block.id, chapter.id)
            assertEquals(expected.meaning, requireNotNull(BookMeaningContent.capture(book, bodyPosition, scope)).detail)
            for (other in listOf(bodyPosition.copy(blockId = chapter.blocks.last().id),
                bodyPosition.copy(anchorKind = BookAnchorKind.HEADER), bodyPosition.copy(anchorKind = BookAnchorKind.END))) {
                assertEquals("Stored new short help is not evidence for a response or non-body anchor", OLD_HEADING_MEANING,
                    requireNotNull(BookMeaningContent.capture(book, other, scope)).detail)
            }
            val foreignOwner = hashed(book.copy(sourceUrl = book.key.copy(date =
                if (expected.date == "2026-11-21") "2026-11-22" else "2026-11-21").sourceUrl()))
            assertEquals("Copied short stored help cannot authorize an unverified source owner", OLD_HEADING_MEANING,
                BookMeaningCatalog.displayMeaning(foreignOwner, foreignOwner.chapters.last()))
            assertEquals(OLD_HEADING_MEANING, requireNotNull(BookMeaningContent.capture(foreignOwner,
                bodyPosition.copy(contentHash = foreignOwner.contentHash))).detail)
            assertEquals(untouched, source.outerHtml())
            assertTrue(ServiceBookIntegrity.valid(book))
        }
    }

    @Test fun theReviewedStringsRemainExactlyTheAcceptedUnpunctuatedContent() {
        val index = BookTranslationIndex.fromFeoriaCatalog()
        for (expected in cases) {
            assertEquals(expected.russianSha, BookText.sha256(expected.russian))
            assertEquals(expected.meaningSha, BookText.sha256(expected.meaning))
            assertEquals(BookTranslation(expected.russian, BookTranslationIndex.FEORIA_TRANSLATION_SOURCE), index.find(expected.original))
            assertFalse(expected.russian.endsWith('.'))
            assertFalse(expected.meaning.endsWith('.'))
        }
    }

    @Test fun similarTextOrUnknownTerminalMarkupCannotObtainTheNewFreshPrayerBoundary() {
        val mutations: List<Pair<String, (Document) -> Unit>> = listOf(
            "body wrapped in span" to { d -> dismissalNode(d).wrap("<span></span>") },
            "extra owner attribute" to { d -> terminalRegion(d).attr("data-unverified", "true") },
            "extra owner class" to { d -> terminalRegion(d).addClass("unverified") },
            "heading with attribute" to { d -> terminalRegion(d).selectFirst("h3")!!.attr("id", "dismissal") },
            "heading with nested markup" to { d -> terminalRegion(d).selectFirst("h3")!!.html("<em>Отпуст</em>") },
            "speaker markup changed" to { d -> terminalRegion(d).selectFirst("strong")!!.tagName("b") },
            "response count markup changed" to { d -> terminalRegion(d).selectFirst("em")!!.tagName("span") },
            "comment inside terminal owner" to { d -> terminalRegion(d).appendChild(Comment("unverified boundary")) },
            "placeholder input inside terminal owner" to { d -> terminalRegion(d).appendElement("input")
                .attr("type", "hidden").attr("value", "unfilled") },
            "placeholder button inside terminal owner" to { d -> terminalRegion(d).appendElement("button").text("Неизвестный фрагмент") },
            "terminal owner inside altar details" to { d ->
                val region = terminalRegion(d)
                val details = content(d).appendElement("details")
                details.appendElement("summary").text("В алтаре")
                details.appendChild(region)
            },
            "unknown extra element" to { d -> terminalRegion(d).appendElement("span") },
            "duplicate full body" to { d -> terminalRegion(d).prependChild(dismissalNode(d).clone()) },
            "duplicate heading" to { d -> terminalRegion(d).before("<h3>Отпуст</h3>") },
            "nonterminal owner" to { d -> content(d).appendElement("p").text("Неизвестное продолжение") },
            "changed internal words" to { d -> dismissalNode(d).let { it.text(it.text().replace("всех", "некоторых")) } },
            "changed response count" to { d -> terminalRegion(d).selectFirst("em")!!.text("(Дважды)") }
        )
        for (expected in cases) for ((label, mutate) in mutations) {
            val source = document(expected.date)
            mutate(source)
            assertNull("$label/${expected.date}", BookFirstHourDismissal.terminalBody(content(source), ServiceBookKey(expected.date, WorshipKind.HOUR1)))
            try {
                val book = parse(source, expected.date, BookTranslationIndex.fromFeoriaCatalog())
                assertFalse("$label must not create a fresh H1 prayer", book.blocks.any {
                    it.kind == BookBlockKind.PRAYER && BookText.normalized(it.original) == BookText.normalized(expected.original)
                })
            } catch (_: InvalidServiceBook) {
                // Existing whole-source validation may reject an altered ending before classification.
            }
        }
        for (kind in WorshipKind.entries.filterNot { it == WorshipKind.HOUR1 }) {
            assertNull("A bare H1 structure must not widen $kind", BookFirstHourDismissal.terminalBody(
                content(document("2026-11-21")), ServiceBookKey("2026-11-21", kind)))
        }
    }

    @Test fun verifiedLegacyHoursAndComposedVigilUseOneReadOnlyScopeForFallbackTimingAndMeaning() {
        for ((book, expected) in legacyCases()) {
            val chapter = book.chapters.last()
            val block = chapter.blocks.first()
            val position = BookReadingPosition(book.key, book.contentHash, "hour1-regression", block.id, chapter.id,
                fraction = .37f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN,
                wordsPerMinute = 130, showAltar = true)
            val json = gson.toJson(book)
            val cursor = BookReadingCodec.encode(position)
            val ids = book.chapters.map { it.id to it.blocks.map { block -> block.id } }
            val scope = BookFirstHourDismissalScope.of(book)
            assertTrue(ServiceBookIntegrity.valid(book))
            assertTrue(scope.isLegacyPrayer(book, block))
            assertFalse("A key and block alone are not whole-book H1 evidence", BookBlockPresentation.isLegacyPrayer(book.key, block))
            assertEquals(block.original, BookBlockPresentation.effectiveRussian(book.key, block))
            assertNull(BookBlockPresentation.effectiveRussian(book, block, scope))
            assertTrue(BookBlockPresentation.isSpoken(book, block, scope))
            for (mode in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN)) {
                val spoken = requireNotNull(BookBlockPresentation.spokenText(book, block, scope, mode))
                assertEquals(block.original, spoken)
                assertEquals(expected.words, BookAutoScroll.wordCount(spoken))
                assertTrue(BookAutoScroll.seconds(BookAutoScroll.wordCount(spoken), BookAutoScroll.MAX_RATE) > .8f)
            }
            assertNull(BookBlockPresentation.spokenText(book, block, scope, BookReadingMode.MEANING))
            assertEquals(expected.meaning, scope.meaning(book, chapter, block.id))
            assertEquals(expected.meaning, BookMeaningCatalog.displayMeaning(book, chapter))
            val meaning = requireNotNull(BookMeaningContent.capture(book, position, scope))
            assertEquals("Отпуст", meaning.chapterTitle)
            assertEquals(block.original, meaning.excerpt)
            assertEquals(expected.meaning, meaning.detail)
            assertNull(meaning.brief)
            assertFalse(meaning.privateText)
            for (other in listOf(position.copy(blockId = chapter.blocks.last().id),
                position.copy(anchorKind = BookAnchorKind.HEADER), position.copy(anchorKind = BookAnchorKind.END))) {
                assertEquals("Response or non-body anchor must retain its ordinary chapter help", OLD_HEADING_MEANING,
                    requireNotNull(BookMeaningContent.capture(book, other, scope)).detail)
            }
            assertEquals(json, gson.toJson(book))
            assertEquals(book.contentHash, ServiceBookIntegrity.contentHash(book))
            assertEquals(ids, book.chapters.map { it.id to it.blocks.map { block -> block.id } })
            assertEquals(cursor, BookReadingCodec.encode(position))
            assertEquals(position, BookReadingCodec.decode(cursor))
        }
    }

    @Test fun newLegacySearchHitsUseTheOriginalUtf16OffsetsAndOldRussianTargetsRemainValid() {
        for ((book, expected) in legacyCases()) {
            val body = book.chapters.last().blocks.first()
            val query = "истинный бог наш молитвами"
            val expectedMatch = requireNotNull(BookSearchText.find(expected.original, query))
            for (mode in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN)) {
                val found = BookCorpusSearch.find(listOf(book), query, mode).single { it.hit.blockId == body.id }
                assertEquals(BookReadingMode.ORIGINAL, found.hit.mode)
                assertEquals(expected.original, found.hit.excerpt)
                assertEquals(expectedMatch.start, found.hit.matchStart)
                assertEquals(expectedMatch.endExclusive, found.hit.matchEndExclusive)
                assertEquals(BookText.normalized(query), BookText.normalized(body.original.substring(found.hit.matchStart, found.hit.matchEndExclusive)))
                assertSame(found, found.validatedFor(book))
                val oldTarget = found.copy(hit = found.hit.copy(mode = BookReadingMode.RUSSIAN))
                assertSame("Raw saved Russian coordinates stay compatible", oldTarget, oldTarget.validatedFor(book))
                assertEquals(body.original.substring(expectedMatch.start, expectedMatch.endExclusive),
                    requireNotNull(body.russian).substring(oldTarget.hit.matchStart, oldTarget.hit.matchEndExclusive))
            }
        }
    }

    @Test fun precedingResponseContextUsesTheSameFullBookFallbackBoundary() {
        for ((legacy, _) in legacyCases()) {
            val book = changeTail(legacy) { tail -> tail.copy(blocks = listOf(tail.blocks.first(),
                tail.blocks.last().let { it.copy(russian = it.original) })) }
            val body = book.chapters.last().blocks.first()
            val response = book.chapters.last().blocks.last()
            val query = "господи помилуй трижды"
            val original = BookSearch.find(book, query, BookReadingMode.ORIGINAL).single { it.blockId == response.id }
            assertEquals(BookReadingMode.ORIGINAL, original.mode)
            assertTrue("Original context must recognize the immediately preceding legacy prayer",
                BookText.normalized(original.excerpt).contains(BookText.normalized(body.original.takeLast(45))))
            val russian = BookSearch.find(book, query, BookReadingMode.RUSSIAN).single { it.blockId == response.id }
            assertEquals(BookReadingMode.RUSSIAN, russian.mode)
            assertEquals("Untranslated prayer is a context boundary", response.original, russian.excerpt)
        }
    }

    @Test fun wholeBookGuardsRejectPartialAmbiguousPrivateForeignAndChangedLegacyOwners() {
        val book = legacy(cases.first())
        val tail = book.chapters.last()
        val body = tail.blocks.first()
        val response = tail.blocks.last()
        val variants = linkedMapOf<String, ServiceBook>(
            "invalid accepted hash" to book.copy(contentHash = "0".repeat(64)),
            "tail slice only" to hashed(book.copy(chapters = listOf(tail))),
            "missing choir response" to changeTail(book) { it.copy(blocks = listOf(body)) },
            "extra tail block" to changeTail(book) { it.copy(blocks = it.blocks + response.copy(id = "${it.id}:extra-tail:1")) },
            "changed choir response" to changeTail(book) { it.copy(blocks = listOf(body, response.copy(original = "Господи, помилуй (Дважды)."))) },
            "changed choir speaker" to changeTail(book) { it.copy(blocks = listOf(body, response.copy(speaker = null))) },
            "rubric choir response" to changeTail(book) { it.copy(blocks = listOf(body, response.copy(kind = BookBlockKind.RUBRIC))) },
            "private response" to changeTail(book) { it.copy(blocks = listOf(body, response.copy(isAltarText = true))) },
            "private body" to changeBody(book) { it.copy(isAltarText = true) },
            "body speaker" to changeBody(book) { it.copy(speaker = "Священник") },
            "body scripture references" to changeBody(book) { it.copy(scriptureReferences = listOf("Пс 1:1")) },
            "response scripture references" to changeTail(book) { it.copy(blocks = listOf(body, response.copy(scriptureReferences = listOf("Пс 1:1")))) },
            "scripture body" to changeBody(book) { it.copy(kind = BookBlockKind.SCRIPTURE) },
            "local body" to changeBody(book) { it.copy(kind = BookBlockKind.LOCAL_VARIATION) },
            "partial body" to changeBody(book) { it.copy(original = it.original.substringBefore("моли́твами")) },
            "changed internal body" to changeBody(book) { it.copy(original = it.original.replace("всех", "некоторых")) },
            "foreign block owner" to changeBody(book) { it.copy(id = "foreign-owner:body:1") },
            "duplicate block id" to changeTail(book) { it.copy(blocks = listOf(body, response.copy(id = body.id))) },
            "duplicate chapter id" to hashed(book.copy(chapters = book.chapters + tail)),
            "extra final chapter" to hashed(book.copy(chapters = book.chapters + tail.copy(id = tail.id.replace(Regex("-[0-9]+$"), "-99"),
                title = "Неизвестное продолжение", blocks = listOf(response.copy(id = "extra-owner:response:1"))))),
            "wrong source URL" to hashed(book.copy(sourceUrl = book.key.copy(date = "2026-11-22").sourceUrl())),
            "wrong source date" to hashed(book.copy(sources = book.sources.map { if (it.title == SOURCE_TITLE)
                it.copy(url = book.key.copy(date = "2026-11-22").sourceUrl()) else it })),
            "duplicate primary source" to hashed(book.copy(sources = book.sources + book.sources.single { it.title == SOURCE_TITLE })),
            "wrong key date" to hashed(book.copy(key = book.key.copy(date = "2026-11-22")))
        )
        variants["malformed null sources"] = gson.fromJson(gson.toJsonTree(book).asJsonObject.apply {
            add("sources", com.google.gson.JsonNull.INSTANCE)
        }, ServiceBook::class.java)
        val first = book.chapters.first()
        variants["duplicate body with distinct owned id"] = hashed(book.copy(chapters =
            listOf(first.copy(blocks = first.blocks + body.copy(id = "${first.id}:duplicate-body:1"))) + book.chapters.drop(1)))
        for ((label, candidate) in variants) assertRejected(label, candidate)
        for (kind in WorshipKind.entries.filterNot { it in setOf(WorshipKind.HOUR1, WorshipKind.VIGIL) }) {
            val key = book.key.copy(worship = kind)
            assertRejected("unsupported $kind", hashed(book.copy(key = key, sourceUrl = key.sourceUrl())))
        }
    }

    @Test fun vigilNeedsItsThreeUnambiguousSameDateComponentsAndTheirActualSourceKeys() {
        val book = vigil(legacy(cases.last()))
        val vespers = book.chapters.filter { it.id.startsWith("vespers-") }
        val matins = book.chapters.filter { it.id.startsWith("matins-") }
        val hour = book.chapters.filter { it.id.startsWith("chas1-") }
        assertTrue(BookFirstHourDismissalScope.of(book).isLegacyPrayer(book, hour.last().blocks.first()))
        val fakeComposite = hashed(book.copy(chapters = hour))
        assertTrue("Even a valid H1-sized book and all three claimed source keys are not three components", ServiceBookIntegrity.valid(fakeComposite))
        assertRejected("H1 prefix and claimed sources alone", fakeComposite)
        assertRejected("missing Matins", hashed(book.copy(chapters = vespers + hour)))
        assertRejected("wrong component order", hashed(book.copy(chapters = matins + vespers + hour)))
        assertRejected("interleaved components", hashed(book.copy(chapters = vespers.take(1) + matins.take(1) +
            vespers.drop(1) + matins.drop(1) + hour)))
        assertRejected("missing component source", hashed(book.copy(sources = book.sources.filterNot { "worship=matins" in it.url })))
        assertRejected("foreign H1 source date", hashed(book.copy(sources = book.sources.map { source ->
            if ("worship=chas1" in source.url) source.copy(url = book.key.copy(date = "2026-11-21", worship = WorshipKind.HOUR1).sourceUrl()) else source
        })))
        val renamed = hour.map { owner -> owner.copy(id = owner.id.replaceFirst("chas1-", "chas3-"),
            blocks = owner.blocks.map { it.copy(id = it.id.replaceFirst("chas1-", "chas3-")) }) }
        assertRejected("foreign component family", hashed(book.copy(chapters = vespers + matins + renamed)))
    }

    @Test fun aScopeCannotAuthorizeAnotherEditionOrAlteredTargetAndAttributedRussianIsRetained() {
        val first = legacy(cases.first())
        val second = legacy(cases.last())
        val scope = BookFirstHourDismissalScope.of(first)
        val firstBody = first.chapters.last().blocks.first()
        val secondBody = second.chapters.last().blocks.first()
        assertFalse(scope.isLegacyPrayer(second, secondBody))
        assertNull(scope.meaning(second, second.chapters.last(), secondBody.id))
        assertFalse(scope.isLegacyPrayer(first, firstBody.copy(id = firstBody.id + "-other")))
        assertFalse(scope.isLegacyPrayer(first, firstBody.copy(isAltarText = true)))
        val another = changeBody(first) { it.copy(meaning = "Другой сохраненный текст") }
        assertFalse(scope.isLegacyPrayer(another, another.chapters.last().blocks.first()))
        assertNull(scope.meaning(another, another.chapters.last()))
        val published = BookSource("Проверенный русский текст", "https://azbyka.ru/worships/chas1/")
        for (russian in listOf(cases.first().russian, firstBody.original)) {
            val attributed = changeBody(first) { it.copy(russian = russian, translationSource = published) }
            val block = attributed.chapters.last().blocks.first()
            val checked = BookFirstHourDismissalScope.of(attributed)
            assertTrue(checked.isLegacyPrayer(attributed, block))
            assertEquals(russian, BookBlockPresentation.effectiveRussian(attributed, block, checked))
            assertEquals(russian, BookBlockPresentation.spokenText(attributed, block, checked, BookReadingMode.RUSSIAN))
        }
    }

    @Test fun existingVerbatimRebasePreservesOriginalAndResetsChangedRussianWithoutATargetedClaim() {
        for (composed in listOf(false, true)) {
            val expected = cases.last()
            val old = if (composed) vigil(legacy(expected)) else legacy(expected)
            val canonical = changeBody(fresh(expected)) { it.copy(russian = expected.russian,
                translationSource = BookTranslationIndex.FEORIA_TRANSLATION_SOURCE) }
            val next = if (composed) vigil(canonical) else canonical
            val oldBody = old.chapters.last().blocks.first()
            val nextBody = next.chapters.last().blocks.first()
            assertNotEquals(oldBody.id, nextBody.id)
            assertEquals(oldBody.original, nextBody.original)
            assertEquals(BookBlockKind.RUBRIC, oldBody.kind)
            assertEquals(BookBlockKind.PRAYER, nextBody.kind)
            assertNull(BookRussianUpdateDescriptor.create(old, BookLoadResult.Ready(next, fromCache = false, saved = true),
                verifiedCandidate = true, ownerToken = Any(), ownerIdentity = "hour1-rebase"))
            val before = gson.toJson(old)
            val journey = if (composed) BookJourneyContext("hour1-rebase-parish", "2026-11-21", "17:00",
                "Всенощное бдение. Исповедь", listOf(old.key), schemaVersion = 2,
                partSet = BookPartSetKind.PARTIAL_PUBLISHED_TEXT) else null
            if (journey != null) assertEquals(journey, journey.validatedFor(old.key, "hour1-rebase-parish"))
            for ((mode, lastTextMode) in listOf(BookReadingMode.ORIGINAL to BookReadingMode.ORIGINAL,
                BookReadingMode.RUSSIAN to BookReadingMode.RUSSIAN, BookReadingMode.MEANING to BookReadingMode.ORIGINAL,
                BookReadingMode.MEANING to BookReadingMode.RUSSIAN)) for ((fraction, gap) in listOf(.37f to 0f, 1f to .31f)) {
                val position = BookReadingPosition(old.key, old.contentHash, "hour1-rebase-parish", oldBody.id,
                    old.chapters.last().id, fraction = fraction, followingGapFraction = gap, mode = mode,
                    lastTextMode = lastTextMode, wordsPerMinute = 145, showAltar = true, journeyContext = journey)
                val rawCursor = BookReadingCodec.encode(position)
                val rebased = BookPositionUpdate.rebase(position, old, next)
                val original = lastTextMode == BookReadingMode.ORIGINAL
                assertEquals(position.copy(contentHash = next.contentHash, blockId = nextBody.id,
                    fraction = if (original) fraction else 0f, followingGapFraction = if (original) gap else 0f), rebased)
                assertEquals(rawCursor, BookReadingCodec.encode(position))
            }
            assertEquals(before, gson.toJson(old))
            val ambiguous = changeTail(next) { owner -> owner.copy(blocks = listOf(nextBody,
                nextBody.copy(id = "${owner.id}:second-verbatim:1"), owner.blocks.last())) }
            val position = BookReadingPosition(old.key, old.contentHash, null, oldBody.id, old.chapters.last().id, fraction = .37f)
            val safe = BookPositionUpdate.rebase(position, old, ambiguous)
            assertEquals(BookAnchorKind.HEADER, safe.anchorKind)
            assertEquals(0f, safe.fraction)
        }
    }

    private fun assertRejected(label: String, candidate: ServiceBook) {
        val scope = BookFirstHourDismissalScope.of(candidate)
        assertTrue("$label must not grant a legacy block", candidate.blocks.none { scope.isLegacyPrayer(candidate, it) })
        assertTrue("$label must not grant a chapter explanation", candidate.chapters.all { scope.meaning(candidate, it) == null })
    }
    private fun fresh(expected: Expected) = parse(document(expected.date), expected.date)
    private fun legacy(expected: Expected): ServiceBook {
        val parsed = fresh(expected)
        return changeTail(parsed) { owner ->
            val body = owner.blocks.first()
            val id = "${owner.id}:${BookText.sha256("RUBRIC|null|false|${body.original}|").take(16)}:1"
            owner.copy(meaning = OLD_HEADING_MEANING, blocks = listOf(body.copy(id = id, kind = BookBlockKind.RUBRIC,
                russian = body.original, translationSource = null, meaning = body.original),
                owner.blocks.last().copy(meaning = OLD_HEADING_MEANING)))
        }
    }
    private fun legacyCases(): List<Pair<ServiceBook, Expected>> = cases.map { legacy(it) to it } + (vigil(legacy(cases.last())) to cases.last())
    private fun vigil(hour: ServiceBook): ServiceBook {
        require(hour.key.date == "2026-11-22")
        val parts = listOf(WorshipKind.VESPERS, WorshipKind.MATINS).map { kind ->
            ServiceBookParser().parse(Jsoup.parse(resource("published-russian/${kind.queryValue}-2026-11-22.html")),
                hour.key.copy(worship = kind), 1L)
        } + hour
        val key = hour.key.copy(worship = WorshipKind.VIGIL)
        // Same chapter composition and source ownership as loadVigil, without repository/network behavior.
        return hashed(ServiceBook(key = key, title = "Всенощное бдение", sourceUrl = key.sourceUrl(),
            sourceTitle = "Азбука веры: вечерня, утреня и первый час", fetchedAtMillis = 1L,
            selectedEventTitle = parts.first().selectedEventTitle, chapters = parts.flatMap { part ->
                part.chapters.mapIndexed { index, owner -> if (index == 0) owner.copy(title = "${part.title} · ${owner.title}") else owner }
            }, contentHash = "", notices = parts.flatMap { it.notices }.distinct(), sources = parts.flatMap { it.sources }.distinct(),
            mainEventOptions = parts.first().mainEventOptions, secondEventOptions = parts.first().secondEventOptions))
    }
    private fun changeBody(book: ServiceBook, update: (BookBlock) -> BookBlock) = changeTail(book) { owner ->
        owner.copy(blocks = listOf(update(owner.blocks.first())) + owner.blocks.drop(1))
    }
    private fun changeTail(book: ServiceBook, update: (BookChapter) -> BookChapter) =
        hashed(book.copy(chapters = book.chapters.dropLast(1) + update(book.chapters.last())))
    private fun hashed(book: ServiceBook) = book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    private fun parse(source: Document, date: String, index: BookTranslationIndex = BookTranslationIndex.empty()) =
        ServiceBookParser().parse(source, ServiceBookKey(date, WorshipKind.HOUR1), 1L, index)
    private fun document(date: String) = Jsoup.parse(resource("first-hour-dismissal/$date-chas1.html"))
    private fun content(source: Document): Element = requireNotNull(source.selectFirst(".article-single-content.main-page-content"))
    private fun terminalRegion(source: Document): Element = requireNotNull(content(source).children().last())
    private fun dismissalNode(source: Document): TextNode = terminalRegion(source).textNodes().single {
        BookText.normalized(it.text()).contains("христос истинный бог наш")
    }
    private fun resource(path: String) = requireNotNull(javaClass.classLoader?.getResource("book/$path")).readText()

    private data class Expected(val date: String, val hostSha: String, val original: String, val originalSha: String,
        val russian: String, val russianSha: String, val meaning: String, val meaningSha: String, val words: Int)
    private companion object {
        const val SOURCE_TITLE = "Азбука веры: Богослужения сегодня"
        const val OLD_HEADING_MEANING = "Священник благословляет молящихся и просит Христа помиловать и спасти их по молитвам Богородицы и святых. Это завершение службы; ее молитву предстоит продолжить делами обычной жизни"
        val cases = listOf(
            Expected("2026-11-21", "5629868ee225ad7947691088fbfe130db1c1ab728066f53c93fb16e6b71a9d17",
                "Христо́с И́стинный Бог наш, моли́твами Пречи́стыя Своея́ Ма́тере, преподо́бных и богоно́сных оте́ц на́ших и всех святы́х, поми́лует и спасе́т нас, я́ко Благ и Человеколю́бец.",
                "c3edf0e8dd4f72bde0a94eb78de08f50de45f3dfd6ef563f10c67d6dce695f02",
                "Христос, истинный Бог наш, по молитвам Пречистой Своей Матери, преподобных и богоносных отцов наших и всех святых, помилует и спасет нас, потому что Он благ и любит людей",
                "86b1cee7a0f04e9ed6df8aa3aa4e3bbb28fad0d5dbe98503755f1c6bc2277de2",
                "Эти слова выражают надежду на милость и спасение от Христа по молитвам Его Матери, преподобных отцов и всех святых",
                "a6b69fd5ba1b4b4b8ef22a88645ed35006a22d5bb95c610d953966a8b345a85e", 24),
            Expected("2026-11-22", "072e32c0cf65ac2287b1fd4096b5ca83682a4bddd605d2097b8550f32f354c8f",
                "Воскресы́й из ме́ртвых Христо́с И́стинный Бог наш, моли́твами Пречи́стыя Своея́ Ма́тере, преподо́бных и богоно́сных оте́ц на́ших и всех святы́х, поми́лует и спасе́т нас, я́ко Благ и Человеколю́бец.",
                "8fcbc7328da17a6fe7b701dea0d11c05c4956c627232af7d9c46cb7427132145",
                "Воскресший из мертвых Христос, истинный Бог наш, по молитвам Пречистой Своей Матери, преподобных и богоносных отцов наших и всех святых, помилует и спасет нас, потому что Он благ и любит людей",
                "5a61807a8c522f551d6186b9e77e5445b7285ab17ccfe4ae2def172efa190e19",
                "Эти слова выражают надежду на милость и спасение от воскресшего Христа по молитвам Его Матери, преподобных отцов и всех святых",
                "a3698ec6fb5c4f76bd5b0bc76207cbd2b9a79234269990df8b2d8557524bf6a1", 27)
        )
    }
}
