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

    @Test fun headingOffsetNeverBecomesAnOffsetInsideAPrayer() {
        val cursor = BookReadingCursor(position)
        cursor.observe(BookViewport("chapter-1", "first-block", .7f, BookAnchorKind.HEADER), BookReadingMode.ORIGINAL)
        val saved = cursor.snapshot(BookReadingMode.ORIGINAL, 75, false)
        assertEquals(BookAnchorKind.HEADER, saved.anchorKind)
        assertEquals(.7f, saved.fraction)
        assertEquals(BookAnchorKind.HEADER, BookReadingCodec.decode(BookReadingCodec.encode(saved))!!.anchorKind)
    }
}
