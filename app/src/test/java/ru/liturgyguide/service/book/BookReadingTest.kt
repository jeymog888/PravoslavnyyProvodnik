package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

class BookReadingTest {
    private val key = ServiceBookKey("2026-11-11")
    private val position = BookReadingPosition(key, "a".repeat(64), "osm:node:123", "block-2", "chapter-1",
        fraction = .45f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 75)

    @Test fun savedFragmentIncludesDateChurchLanguageAndOffset() {
        assertEquals(position, BookReadingCodec.decode(BookReadingCodec.encode(position)))
        assertNull(BookReadingCodec.decode(BookReadingCodec.encode(position).replace("2026-11-11", "2026-02-30")))
        assertNull(BookReadingCodec.decode(BookReadingCodec.encode(position).replace("RUSSIAN", "UNKNOWN")))
        assertNull(BookReadingCodec.decode(BookReadingCodec.encode(position).replace("0.45", "-0.45")))
        assertNull(BookReadingCodec.decode("{}"))
    }

    @Test fun interPrayerSpaceRoundTripsAndOlderPositionsNeedNoMigration() {
        for (gap in listOf(.25f, .5f, .99f)) {
            val inSpace = position.copy(fraction = 1f, followingGapFraction = gap)
            assertEquals(inSpace, BookReadingCodec.decode(BookReadingCodec.encode(inSpace)))
        }
        val json = com.google.gson.JsonParser.parseString(BookReadingCodec.encode(position)).asJsonObject
        assertFalse("An ordinary position retains its legacy serialized form", json.has("followingGapFraction"))
        val escapedPosition = position.copy(parishId = "temple&chapel")
        val escaped = BookReadingCodec.encode(escapedPosition)
        assertTrue("The legacy Gson escaping is retained too", escaped.contains("temple\\u0026chapel"))
        assertEquals(escapedPosition, BookReadingCodec.decode(escaped))
        json.remove("followingGapFraction")
        assertEquals(position, BookReadingCodec.decode(json.toString()))
        for (invalid in listOf("null", "\"0.5\"", "-0.1", "1.1")) {
            json.add("followingGapFraction", com.google.gson.JsonParser.parseString(invalid))
            assertNull(BookReadingCodec.decode(json.toString()))
        }
        assertThrows(IllegalArgumentException::class.java) { BookReadingCodec.encode(position.copy(followingGapFraction = .5f)) }
        assertThrows(IllegalArgumentException::class.java) {
            BookReadingCodec.encode(position.copy(fraction = 1f, anchorKind = BookAnchorKind.END, followingGapFraction = .5f))
        }
    }

    @Test fun meaningRetainsTheTextGapButAnExplicitNewLocationClearsIt() {
        val atGap = position.copy(fraction = 1f, followingGapFraction = .5f)
        val cursor = BookReadingCursor(atGap)
        cursor.observe(BookViewport(position.chapterId, position.blockId, 0f, BookAnchorKind.HEADER), BookReadingMode.MEANING)
        assertEquals(.5f, cursor.snapshot(BookReadingMode.ORIGINAL, 75, false).followingGapFraction)
        cursor.seek(position.chapterId, position.blockId)
        assertEquals(0f, cursor.value.followingGapFraction)
        cursor.observe(BookViewport(position.chapterId, position.blockId, 1f, BookAnchorKind.BLOCK, .25f), BookReadingMode.ORIGINAL)
        assertEquals(.25f, cursor.value.followingGapFraction)
        cursor.observe(BookViewport("another-chapter", "another-block", 0f, BookAnchorKind.HEADER), BookReadingMode.MEANING)
        assertEquals(0f, cursor.value.followingGapFraction)
    }

    @Test fun wordSearchFindsAccentedOriginalAndRussianInTheSameBookWithoutCrossingFragments() {
        val blocks = listOf(
            BookBlock("one", BookBlockKind.PRAYER, "Хор", "И́же Херуви́мы та́йно образу́юще", "Мы таинственно изображаем херувимов"),
            BookBlock("two", BookBlockKind.PRAYER, "Хор", "Отче наш", "Отец наш"),
            BookBlock("three", BookBlockKind.PRAYER, "Хор", "Иже еси на небесех", "Сущий на небесах")
        )
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Источник",
            fetchedAtMillis = 1, selectedEventTitle = null, chapters = listOf(BookChapter("chapter", "Херувимская", "Смысл", blocks)), contentHash = "a".repeat(64))
        assertEquals("one", BookSearch.find(book, "Иже Херувимы").single().blockId)
        assertEquals("one", BookSearch.find(book, "изображаем херувимов").single().blockId)
        assertTrue(BookSearch.find(book, "наш Иже").isEmpty())
        assertTrue(BookSearch.find(book, " ").isEmpty())
        assertTrue(BookSearch.find(book, "Несуществующие слова").isEmpty())
    }

    @Test fun meaningKeepsTheFragmentUnlessReaderChoosesAnotherChapter() {
        val cursor = BookReadingCursor(position)
        cursor.observe(BookViewport("chapter-1", "first-block", 0f, BookAnchorKind.HEADER), BookReadingMode.MEANING)
        assertEquals("block-2", cursor.value.blockId)
        assertEquals(.45f, cursor.value.fraction)
        cursor.observe(BookViewport("chapter-2", "new-first", .4f, BookAnchorKind.HEADER), BookReadingMode.MEANING)
        val saved = cursor.snapshot(BookReadingMode.RUSSIAN, 75, false)
        assertEquals("new-first", saved.blockId)
        assertEquals("chapter-2", saved.chapterId)
        assertEquals(0f, saved.fraction)
        assertEquals(BookAnchorKind.HEADER, saved.anchorKind)
        assertEquals(saved, BookReadingCodec.decode(BookReadingCodec.encode(saved)))
    }

    @Test fun searchShowsMatchingWordsDeepInRussianAndAccentedOriginalParagraphs() {
        val block = BookBlock("long", BookBlockKind.PRAYER, null,
            "Начало молитвы ".repeat(40) + "И́же Херуви́мы та́йно образу́юще" + " Продолжение".repeat(20),
            "Начало перевода ".repeat(40) + "Мы таинственно изображаем херувимов" + " Продолжение".repeat(20))
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Источник",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", "Херувимская", "Смысл", listOf(block))), contentHash = "a".repeat(64))
        val russian = BookSearch.find(book, "херувимов изображаем").single().excerpt
        assertTrue(russian.contains("изображаем херувимов"))
        assertTrue(russian.startsWith("… "))
        val original = BookSearch.find(book, "Иже Херувимы").single().excerpt
        assertTrue(original.contains("И́же Херуви́мы"))
        assertTrue(original.length <= 240)
        assertFalse(russian.contains("И́же"))
    }

    @Test fun headingOffsetNeverBecomesAnOffsetInsideAPrayer() {
        val cursor = BookReadingCursor(position)
        cursor.observe(BookViewport("chapter-1", "first-block", .7f, BookAnchorKind.HEADER), BookReadingMode.ORIGINAL)
        val saved = cursor.snapshot(BookReadingMode.ORIGINAL, 75, false)
        assertEquals(BookAnchorKind.HEADER, saved.anchorKind)
        assertEquals(.7f, saved.fraction)
        assertEquals(BookAnchorKind.HEADER, BookReadingCodec.decode(BookReadingCodec.encode(saved))!!.anchorKind)
    }

    @Test fun explicitUpdateKeepsUnchangedVisibleTextButResetsChangedTranslationOffset() {
        val block = BookBlock("block-2", BookBlockKind.PRAYER, null, "Отче наш", "Отец наш")
        val before = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Источник",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter-1", "Молитва", "Смысл", listOf(block))), contentHash = "a".repeat(64))
        val metadataUpdate = before.copy(contentHash = "b".repeat(64), notices = listOf("Новый источник"))
        val kept = BookPositionUpdate.rebase(position, before, metadataUpdate)
        assertEquals(.45f, kept.fraction)
        assertEquals(BookReadingMode.RUSSIAN, kept.mode)
        assertEquals("b".repeat(64), kept.contentHash)
        val translated = metadataUpdate.copy(chapters = listOf(before.chapters.single().copy(blocks = listOf(block.copy(russian = "Отче наш, сущий на небесах")))))
        assertEquals(0f, BookPositionUpdate.rebase(position, before, translated).fraction)
        assertEquals(.45f, BookPositionUpdate.rebase(position.copy(mode = BookReadingMode.ORIGINAL), before, translated).fraction)
        val inGap = position.copy(fraction = 1f, followingGapFraction = .5f)
        assertEquals(.5f, BookPositionUpdate.rebase(inGap, before, metadataUpdate).followingGapFraction)
        assertEquals(0f, BookPositionUpdate.rebase(inGap, before, translated).followingGapFraction)
    }

    @Test fun updateWhileInMeaningUsesTheTextLanguageWhoseOffsetWasSaved() {
        val block = BookBlock("block-2", BookBlockKind.PRAYER, null, "Отче наш", "Отец наш")
        val before = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Источник",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter-1", "Молитва", "Смысл", listOf(block))), contentHash = "a".repeat(64))
        val translated = before.copy(contentHash = "b".repeat(64), chapters = listOf(before.chapters.single().copy(
            blocks = listOf(block.copy(russian = "Отче наш, сущий на небесах")))))
        val fromRussian = position.copy(mode = BookReadingMode.MEANING, lastTextMode = BookReadingMode.RUSSIAN)
        val rebased = BookPositionUpdate.rebase(fromRussian, before, translated)
        assertEquals(0f, rebased.fraction)
        assertEquals(BookReadingMode.MEANING, rebased.mode)
        assertEquals(BookReadingMode.RUSSIAN, rebased.lastTextMode)
        assertEquals("block-2", rebased.blockId)
        // Meaning itself is temporary; an unchanged original remains at its former offset
        assertEquals(.45f, BookPositionUpdate.rebase(fromRussian.copy(lastTextMode = BookReadingMode.ORIGINAL), before, translated).fraction)
        // Positions written before lastTextMode existed retain the original-language fallback
        assertEquals(.45f, BookPositionUpdate.rebase(fromRussian.copy(lastTextMode = null), before, translated).fraction)
        assertEquals(.45f, BookPositionUpdate.rebase(fromRussian, before, before.copy(contentHash = "c".repeat(64))).fraction)
    }

    private fun edition(blocks: List<BookBlock>, hash: Char = 'a') = ServiceBook(
        key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Источник",
        fetchedAtMillis = 1, selectedEventTitle = null,
        chapters = listOf(BookChapter("chapter-1", "Молитва", "Смысл", blocks)), contentHash = hash.toString().repeat(64))

    @Test fun correctingADirectionKeepsItsPlaceEvenWhenItsDerivedIdChanges() {
        val opening = BookBlock("first", BookBlockKind.PRAYER, null, "Господи, помилуй.")
        val direction = BookBlock("block-2", BookBlockKind.PRAYER, null, "И открываются Царские врата.")
        val corrected = direction.copy(id = "new-direction", kind = BookBlockKind.RUBRIC, russian = direction.original)
        val before = edition(listOf(opening, direction))
        val next = edition(listOf(opening, corrected), 'b')
        val rebased = BookPositionUpdate.rebase(position, before, next)
        assertEquals("new-direction", rebased.blockId)
        assertEquals(BookAnchorKind.BLOCK, rebased.anchorKind)
        assertEquals(.45f, rebased.fraction)
        assertEquals(position.mode, rebased.mode)
        assertEquals(position.parishId, rebased.parishId)
        assertEquals(next.contentHash, rebased.contentHash)
    }

    @Test fun aVerbatimSplitReturnsToThatPassageInsteadOfTheStartOfTheChapter() {
        val opening = BookBlock("first", BookBlockKind.PRAYER, null, "Господи, помилуй.")
        val mixed = BookBlock("block-2", BookBlockKind.RUBRIC, null,
            "Священник же молится: Вонми, Господи Иисусе Христе", isAltarText = true)
        val rubric = BookBlock("new-direction", BookBlockKind.RUBRIC, null, "Священник же молится:", isAltarText = true)
        val prayer = BookBlock("new-prayer", BookBlockKind.PRAYER, null, "Вонми, Господи Иисусе Христе", isAltarText = true)
        val before = edition(listOf(opening, mixed))
        val next = edition(listOf(opening, rubric, prayer), 'b')
        val rebased = BookPositionUpdate.rebase(position.copy(showAltar = true), before, next)
        assertEquals("new-direction", rebased.blockId)
        assertEquals(BookAnchorKind.BLOCK, rebased.anchorKind)
        assertEquals(0f, rebased.fraction)
        assertTrue(rebased.showAltar)
        // A changed prayer, reversed fragments or a crossed altar boundary is not the same passage.
        for (replacement in listOf(listOf(rubric, prayer.copy(original = "Вонми, Господи")),
                listOf(prayer, rubric), listOf(rubric, prayer.copy(isAltarText = false)))) {
            val changed = BookPositionUpdate.rebase(position, before, edition(listOf(opening) + replacement, 'b'))
            assertEquals("first", changed.blockId)
            assertEquals(BookAnchorKind.HEADER, changed.anchorKind)
        }
    }

    @Test fun repeatedOrOtherChapterWordsNeverGuessWhichOccurrenceWasRead() {
        val opening = BookBlock("first", BookBlockKind.PRAYER, null, "Начало")
        val old = BookBlock("block-2", BookBlockKind.PRAYER, null, "И открываются Царские врата.")
        val new = old.copy(id = "new-direction", kind = BookBlockKind.RUBRIC)
        val before = edition(listOf(opening, old))
        val duplicateNext = edition(listOf(opening, new, new.copy(id = "another")), 'b')
        val duplicateBefore = edition(listOf(opening, old, old.copy(id = "earlier")))
        val next = edition(listOf(opening, new), 'b')
        val anotherChapter = edition(listOf(opening), 'b').copy(chapters = listOf(
            BookChapter("chapter-1", "Молитва", "Смысл", listOf(opening)),
            BookChapter("chapter-2", "Далее", "Смысл", listOf(new))))
        for ((previous, updated) in listOf(before to duplicateNext, duplicateBefore to next,
                before to anotherChapter, before to edition(listOf(opening, new.copy(isAltarText = true)), 'b'))) {
            val rebased = BookPositionUpdate.rebase(position, previous, updated)
            assertEquals("first", rebased.blockId)
            assertEquals(BookAnchorKind.HEADER, rebased.anchorKind)
            assertEquals(0f, rebased.fraction)
        }
    }

    @Test fun allVerifiedDirectionsKeepTheirOriginalChapterAndReadingPositionAfterClassificationFix() {
        val html = requireNotNull(javaClass.getResourceAsStream("/book/liturgy-2026-11-12.html"))
            .bufferedReader().use { it.readText() }
        val next = ServiceBookParser().parse(org.jsoup.Jsoup.parse(html), ServiceBookKey("2026-11-12"), 1)
        val proof = requireNotNull(javaClass.getResourceAsStream("/book/rubric-classification-provenance.json"))
            .bufferedReader().use { com.google.gson.JsonParser.parseReader(it).asJsonObject }
        val directions = proof.getAsJsonArray("directions").map { it.asJsonObject.get("original").asString }.toSet()
        val before = next.copy(contentHash = "a".repeat(64), chapters = next.chapters.map { chapter ->
            chapter.copy(blocks = chapter.blocks.map { block ->
                if (block.original in directions) block.copy(id = "old-${block.id}", kind = BookBlockKind.PRAYER, russian = null)
                else block
            })
        })
        assertEquals(27, before.blocks.count { it.original in directions })
        for (chapter in before.chapters) for (block in chapter.blocks.filter { it.original in directions }) {
            val oldPosition = position.copy(key = before.key, blockId = block.id, chapterId = chapter.id, showAltar = block.isAltarText)
            val rebased = BookPositionUpdate.rebase(oldPosition, before, next)
            assertEquals(block.original, next.blocks.single { it.id == rebased.blockId }.original)
            assertEquals(chapter.id, rebased.chapterId)
            assertEquals(BookAnchorKind.BLOCK, rebased.anchorKind)
            assertEquals(.45f, rebased.fraction)
        }
    }

}
