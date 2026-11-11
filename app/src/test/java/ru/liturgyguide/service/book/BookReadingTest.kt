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

}
