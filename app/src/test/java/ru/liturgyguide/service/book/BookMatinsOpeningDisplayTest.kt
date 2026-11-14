package ru.liturgyguide.service.book

import com.google.gson.Gson
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import java.io.File

/** Immutable genuine books and a real fresh parse, never a changed-kind substitute for the parser. */
class BookMatinsOpeningDisplayTest {
    private val gson = Gson()
    private fun text(name: String) = requireNotNull(javaClass.classLoader?.getResource(name)).readText()
    private fun source(path: String) = listOf(File(path), File("../$path")).first { it.isFile }.readText()
    private fun books(): List<ServiceBook> = listOf(
        Triple("ranked-matins-2026-11-13-church34.json", "440b36d4b7f190d3da575d12f0f6ff0a1e3c272e40be87ea32ea5cf162bfc79d", "4b9b2e7278f3d670ee813dd2afd341fcb7b2d33b0348a0a4754c0f36ad2f8b79"),
        Triple("legacy-daily-2026-11-13-church30.json", "c7a8ea6f9915b4c60e33a6d709e5df45c19e22c813fcec500277c5f1648dff98", "46b462096ae0f952529eba8a3395a060684dd91af6a6a34187519d2d7d3e0689"),
        Triple("legacy-ranked-2026-11-18-church34.json", "427e8a5898de734a11ccb025628d010050b5bb8cad787abd561d111d5be125fe", "91d7a5d38eff1409649f83d2d6522856e9e2b76f20dfa0492ecc6d62c0082a5d")
    ).map { (name, sha, hash) ->
        val raw = text("quiet-matins/$name")
        assertEquals(sha, BookText.sha256(raw))
        gson.fromJson(raw, ServiceBook::class.java).also { assertEquals(hash, it.contentHash) }
    }
    private val book by lazy { books().first() }
    private fun changePrefix(edit: (MutableList<BookBlock>) -> Unit): ServiceBook = book.copy(chapters =
        listOf(book.chapters.first().copy(blocks = book.chapters.first().blocks.toMutableList().also(edit))) + book.chapters.drop(1))
    private fun reject(candidate: ServiceBook) {
        val chapter = candidate.chapters.first()
        val before = gson.toJson(candidate)
        val display = BookMatinsOpeningDisplay.of(candidate)
        assertEquals(chapter.title, display.title(chapter)); assertNull(display.meaning(chapter))
        assertEquals(chapter.meaning, BookMeaningCatalog.displayMeaning(candidate, chapter))
        assertEquals(before, gson.toJson(candidate))
    }

    @Test fun threeGenuineAcceptedBooksReceiveOnlyAReadOnlyOpeningTitleAndHelp() {
        for (input in books()) {
            val before = gson.toJson(input); val first = input.chapters.first()
            val display = BookMatinsOpeningDisplay.of(input)
            assertEquals("Перед началом", first.title)
            assertEquals(TITLE, display.title(first)); assertEquals(DETAIL, display.meaning(first))
            assertEquals(DETAIL, BookMeaningCatalog.displayMeaning(input, first))
            for (chapter in input.chapters.drop(1)) {
                assertEquals(chapter.title, display.title(chapter)); assertNull(display.meaning(chapter))
            }
            for (mode in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN)) {
                val position = BookReadingPosition(input.key, input.contentHash, "parish", first.blocks[8].id, first.id,
                    fraction = .24f, mode = mode, lastTextMode = mode)
                val content = requireNotNull(BookMeaningContent.capture(input, position))
                assertEquals(TITLE, content.chapterTitle); assertEquals(DETAIL, content.detail)
                assertEquals(position, content.position); assertNull(content.brief); assertFalse(content.privateText)
            }
            assertEquals(before, gson.toJson(input))
            // Android alone validates the accepted serializer hash; no native input is rehashed here.
        }
    }

    @Test fun theCurrentParserRecognizesTheWholePublishedOpeningWithoutAnyFixtureKindPatch() {
        val html = source("app/src/androidTest/assets/polyeleos-journey/daily-matins-2026-11-13-church34.html")
        assertEquals("4f68b106e867eadf1087dcc1cc38f991b20c76a4fed7a37451b5f3b753258f8f", BookText.sha256(html))
        val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34")
        val parsed = ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 1)
        assertTrue(ServiceBookIntegrity.valid(parsed))
        assertEquals(BookBlockKind.RUBRIC, parsed.chapters[0].blocks[0].kind)
        assertEquals(BookBlockKind.RUBRIC, parsed.chapters[2].blocks[0].kind)
        assertEquals(book.chapters.first().blocks.map { it.original }, parsed.chapters.first().blocks.map { it.original })
        assertEquals(TITLE, BookMatinsOpeningDisplay.of(parsed).title(parsed.chapters.first()))
    }

    @Test fun enrichmentMissingTranslationAndFetchMetadataCannotDefineOrRewriteTheRole() {
        val enriched = book.copy(chapters = book.chapters.map { c -> c.copy(meaning = "Сохраненное пояснение",
            blocks = c.blocks.map { it.copy(russian = "Сохраненный перевод", translationSource =
                BookSource("Перевод", "https://example.org/translation"), meaning = "Сохраненный смысл") }) },
            contentHash = "b".repeat(64), fetchedAtMillis = 999)
        val before = gson.toJson(enriched)
        assertEquals(TITLE, BookMatinsOpeningDisplay.of(enriched).title(enriched.chapters.first()))
        assertEquals(DETAIL, BookMeaningCatalog.displayMeaning(enriched, enriched.chapters.first()))
        assertEquals(before, gson.toJson(enriched))
        val unrelatedDate = book.copy(key = book.key.copy(date = "2027-05-06"), sourceUrl = "https://example.org/editorial-source")
        assertEquals(TITLE, BookMatinsOpeningDisplay.of(unrelatedDate).title(unrelatedDate.chapters.first()))
        // This is a display contract case, not authorization to accept a changed source/key.
    }

    @Test fun otherWorshipOtherTitlesDetachedOrLateChaptersKeepTheirSourceLabel() {
        for (kind in listOf(WorshipKind.LITURGY, WorshipKind.VESPERS, WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.VIGIL))
            reject(book.copy(key = book.key.copy(worship = kind)))
        for (title in listOf("Подготовка", "Перед началом службы", "Утреня · Перед началом", "Начальные молитвы"))
            reject(book.copy(chapters = listOf(book.chapters.first().copy(title = title)) + book.chapters.drop(1)))
        val display = BookMatinsOpeningDisplay.of(book)
        val detached = book.chapters.first().copy(meaning = "Чужое содержание отдельной главы")
        assertEquals(detached.title, display.title(detached)); assertNull(display.meaning(detached))
        val equalReopened = gson.fromJson(gson.toJson(book), ServiceBook::class.java)
        assertEquals(TITLE, display.title(equalReopened.chapters.first()))
        val unrelatedOwner = book.copy(key = book.key.copy(worship = WorshipKind.VESPERS))
        assertEquals(unrelatedOwner.chapters.first().meaning,
            BookMeaningCatalog.displayMeaning(unrelatedOwner, unrelatedOwner.chapters.first(), display))
        val foreign = book.chapters.first().copy(id = "foreign")
        assertEquals(foreign.title, display.title(foreign))
        val late = book.copy(chapters = listOf(book.chapters[1], book.chapters[0]) + book.chapters.drop(2))
        assertEquals(late.chapters[1].title, BookMatinsOpeningDisplay.of(late).title(late.chapters[1]))
    }

    @Test fun everyMandatoryPrefixBodyAndItsFullShapeIsRequiredInItsSourceOrder() {
        for (i in 0..10) {
            reject(changePrefix { it.removeAt(i) })
            if (i < 10) reject(changePrefix { it.add(i, it[i].copy(id = "inserted-$i")) })
            reject(changePrefix { it[i] = it[i].copy(original = it[i].original + " изменено") })
            reject(changePrefix { it[i] = it[i].copy(speaker = "Другой") })
            reject(changePrefix { it[i] = it[i].copy(scriptureReferences = listOf("Ps.19")) })
            reject(changePrefix { it[i] = it[i].copy(isAltarText = true) })
            for (kind in BookBlockKind.values()) if (kind != book.chapters.first().blocks[i].kind &&
                !(i == 0 && kind == BookBlockKind.RUBRIC)) reject(changePrefix { it[i] = it[i].copy(kind = kind) })
        }
        reject(changePrefix { val first = it[3]; it[3] = it[4]; it[4] = first })
        reject(changePrefix { val first = it[7]; it[7] = it[9]; it[9] = first })
        for (i in listOf(8, 10)) reject(changePrefix { it[i] = it[i].copy(original = it[i].original.take(100)) })
    }

    @Test fun prefixLengthDoesNotFreezeAnUnrelatedTailButAHiddenTailCannotBeRelabelledPublic() {
        val minimal = changePrefix { while (it.size > 11) it.removeAt(it.lastIndex) }
        assertEquals(TITLE, BookMatinsOpeningDisplay.of(minimal).title(minimal.chapters.first()))
        // A duplicate of the final witness still leaves the complete eleven-block prefix intact.
        val duplicatedBoundary = changePrefix { it.add(10, it[10].copy(id = "inserted-10")) }
        assertEquals(TITLE, BookMatinsOpeningDisplay.of(duplicatedBoundary).title(duplicatedBoundary.chapters.first()))
        val longer = changePrefix { it += it.last().copy(id = "another-public-tail") }
        assertEquals(TITLE, BookMatinsOpeningDisplay.of(longer).title(longer.chapters.first()))
        reject(changePrefix { it[it.lastIndex] = it.last().copy(isAltarText = true) })
        reject(changePrefix { it.clear(); it += book.chapters.first().blocks.first() })
    }

    @Test fun exactAdjacentContextAndItsKnownLegacyCurrentInstructionAreRequired() {
        for (index in listOf(1, 2)) {
            fun changed(edit: (BookChapter) -> BookChapter) = book.copy(chapters = book.chapters.mapIndexed { i, c -> if (i == index) edit(c) else c })
            reject(changed { it.copy(title = "Другая глава") })
            reject(changed { it.copy(blocks = emptyList()) })
            reject(changed { it.copy(blocks = listOf(it.blocks.first().copy(original = "Другое начало")) + it.blocks.drop(1)) })
            reject(changed { it.copy(blocks = listOf(it.blocks.first().copy(speaker = "Другой")) + it.blocks.drop(1)) })
            reject(changed { it.copy(blocks = listOf(it.blocks.first().copy(scriptureReferences = listOf("Ps.3"))) + it.blocks.drop(1)) })
            reject(changed { it.copy(blocks = listOf(it.blocks.first().copy(isAltarText = true)) + it.blocks.drop(1)) })
            for (kind in BookBlockKind.values()) if (kind != BookBlockKind.PRAYER &&
                !(index == 2 && kind == BookBlockKind.RUBRIC))
                reject(changed { it.copy(blocks = listOf(it.blocks.first().copy(kind = kind)) + it.blocks.drop(1)) })
        }
        reject(book.copy(chapters = listOf(book.chapters[0], book.chapters[2], book.chapters[1]) + book.chapters.drop(3)))
        reject(book.copy(chapters = book.chapters.take(2)))
        val currentWitness = book.copy(chapters = book.chapters.mapIndexed { i, c -> if (i == 2)
            c.copy(blocks = listOf(c.blocks.first().copy(kind = BookBlockKind.RUBRIC)) + c.blocks.drop(1)) else c })
        assertEquals(TITLE, BookMatinsOpeningDisplay.of(currentWitness).title(currentWitness.chapters.first()))
    }

    @Test fun everyChapterAndBlockOwnerMustBeUnambiguous() {
        reject(book.copy(chapters = book.chapters + book.chapters.last().copy(id = book.chapters.first().id)))
        reject(book.copy(chapters = book.chapters + book.chapters.last().copy(id = "another-chapter",
            blocks = listOf(book.chapters.first().blocks[8]))))
        reject(changePrefix { it[10] = it[10].copy(id = it[8].id) })
        reject(changePrefix { it[0] = it[0].copy(id = "") })
        reject(book.copy(chapters = listOf(book.chapters.first().copy(id = "")) + book.chapters.drop(1)))
    }

    @Test fun localAndCrossBookLabelsRequireTheirExactEditionAndKeepTheStoredSearchProof() {
        val raw = BookSearch.find(book, "Услышит тя Господь", BookReadingMode.ORIGINAL).first { it.blockId == book.chapters.first().blocks[8].id }
        val located = BookLocatedSearchHit(book.key, book.contentHash, raw)
        val before = gson.toJson(located)
        assertEquals("Перед началом", raw.chapterTitle); assertEquals(located, located.validatedFor(book))
        assertEquals(TITLE, BookMatinsOpeningDisplay.of(book).title(located))
        val owners = (books().drop(1) + book).associate { (it.key to it.contentHash) to BookMatinsOpeningDisplay.of(it) }
        assertEquals(TITLE, owners[located.bookKey to located.contentHash]?.title(located))
        val otherEdition = book.copy(contentHash = "b".repeat(64))
        assertEquals(raw.chapterTitle, BookMatinsOpeningDisplay.of(otherEdition).title(located))
        val absent = owners - (located.bookKey to located.contentHash)
        assertEquals(raw.chapterTitle, absent[located.bookKey to located.contentHash]?.title(located) ?: raw.chapterTitle)
        val corrupted = located.copy(hit = raw.copy(chapterTitle = "Поврежденная подпись"))
        assertNull(corrupted.validatedFor(book))
        assertEquals(corrupted.hit.chapterTitle, BookMatinsOpeningDisplay.of(book).title(corrupted))
        val wrongOffset = located.copy(hit = raw.copy(matchEndExclusive = Int.MAX_VALUE))
        assertEquals(raw.chapterTitle, BookMatinsOpeningDisplay.of(book).title(wrongOffset))
        assertEquals(before, gson.toJson(located)); assertEquals(located, located.validatedFor(book))
    }

    @Test fun vespersPsalmAndQuietMeaningsKeepTheirExistingPriority() {
        val vespers = ServiceBookParser().parse(Jsoup.parse(text("book/vespers-2026-11-12.html")),
            ServiceBookKey("2026-11-12", WorshipKind.VESPERS), 1)
        val first = vespers.chapters.first()
        val psalm = first.blocks.single { BookText.normalized(it.original).startsWith("благослови душе моя господа господи боже мой") }
        val before = BookMeaningBriefCatalog.forPlace(vespers, first, psalm.id)
        assertEquals("vespers-psalm-103", requireNotNull(before).topicId)
        val position = BookReadingPosition(vespers.key, vespers.contentHash, "parish", psalm.id, first.id)
        assertEquals("Псалом 103", requireNotNull(BookMeaningContent.capture(vespers, position)).chapterTitle)
        val quiet = requireNotNull(BookVisibilityScope.of(book).quietGroup)
        val c = book.chapters.single { it.id == quiet.chapterId }
        val b = c.blocks.first { it.id in quiet.blockIds && it.original.length > 100 }
        val content = requireNotNull(BookMeaningContent.capture(book, BookReadingPosition(book.key, book.contentHash, "parish", b.id, c.id, showAltar = true)))
        assertTrue(content.privateText); assertNull(content.brief); assertEquals(quiet.explanation, content.detail)
        assertNotEquals(TITLE, content.chapterTitle)
    }

    private companion object {
        const val TITLE = "Начало утрени"
        const val DETAIL = "Это начало утрени: после благословения и приглашения к молитве читают псалмы 19 и 20, затем начальные молитвы и тропари. В тексте этого начала указано каждение храма. Далее следуют сугубая ектения и шестопсалмие"
    }
}
