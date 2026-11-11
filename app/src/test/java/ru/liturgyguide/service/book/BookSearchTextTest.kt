package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

class BookSearchTextTest {
    @Test fun accentedLatePhraseMapsToOriginalUtf16Offsets() {
        val prefix = "Вступление и молитва. ".repeat(200)
        val phrase = "И́же Херуви́мы"
        val text = prefix + phrase + " та́йно образу́юще"
        val match = requireNotNull(BookSearchText.find(text, "Иже херувимы"))
        assertEquals(prefix.length, match.start)
        assertEquals(phrase, text.substring(match.start, match.endExclusive))
    }

    @Test fun decomposedLettersSlavonicCharactersAndPunctuationKeepSourceOffsets() {
        val text = "🙏 Славословие. Е\u0308лка, вѣ́ра: Iису́с"
        val match = requireNotNull(BookSearchText.find(text, "елка вера иисус"))
        assertEquals(text.indexOf('Е'), match.start)
        assertEquals("Е\u0308лка, вѣ́ра: Iису́с", text.substring(match.start, match.endExclusive))
    }

    @Test fun wordsInAnotherOrderAnchorToFirstActualWordWithoutCrossingTheWholeParagraph() {
        val text = "Начало молитвы. Изображаем тихо и смиренно херувимов. Завершение"
        val match = requireNotNull(BookSearchText.find(text, "херувимов изображаем"))
        assertEquals("Изображаем", text.substring(match.start, match.endExclusive))
        assertNull(BookSearchText.find(text, "херувимов несуществующее"))
    }

    @Test fun cheapCandidateFilterKeepsPhraseAndUnorderedWordMatchesAcrossPunctuation() {
        val text = "До начала... (И́же),\nХеруви́мы — та́йно. Завершение"
        val phrase = requireNotNull(BookSearchText.find(text, "Иже: Херувимы, тайно"))
        assertEquals("И́же),\nХеруви́мы — та́йно", text.substring(phrase.start, phrase.endExclusive))
        val words = requireNotNull(BookSearchText.find(text, "тайно / иже"))
        assertEquals("И́же", text.substring(words.start, words.endExclusive))
        assertNull(BookSearchText.find(text, "херувимы отсутствует"))
        assertNull(BookSearchText.find(text, " : ; "))
    }

    @Test fun searchStopsReadingTheBookAfterThirtyMatches() {
        val visited = mutableListOf<Int>()
        val blocks = object : AbstractList<BookBlock>() {
            override val size = 300
            override fun get(index: Int): BookBlock {
                check(index < 30) { "The search must not read beyond the visible result limit" }
                visited += index
                return BookBlock("block-$index", BookBlockKind.PRAYER, null, "Господи, помилуй")
            }
        }
        val key = ServiceBookKey("2026-11-11")
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Источник",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", "Молитвы", "Объяснение", blocks)), contentHash = "a".repeat(64))
        val hits = BookSearch.find(book, "Господи")
        assertEquals(30, hits.size)
        assertEquals((0 until 30).toList(), visited)
        assertEquals("block-29", hits.last().blockId)
    }

    @Test fun preferredLanguageWinsWhenBothContainTheWordsAndActualMatchLanguageWinsOtherwise() {
        val block = BookBlock("block", BookBlockKind.PRAYER, null, "Начало. Отче наш. И́же Херуви́мы",
            "Перевод. Отче наш. Изображаем херувимов")
        val key = ServiceBookKey("2026-11-11")
        val book = ServiceBook(key = key, title = "Литургия", sourceUrl = key.sourceUrl(), sourceTitle = "Источник",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", "Молитва", "Объяснение", listOf(block))), contentHash = "a".repeat(64))
        assertEquals(BookReadingMode.ORIGINAL, BookSearch.find(book, "Отче наш", BookReadingMode.ORIGINAL).single().mode)
        assertEquals(BookReadingMode.RUSSIAN, BookSearch.find(book, "Отче наш", BookReadingMode.RUSSIAN).single().mode)
        val original = BookSearch.find(book, "Иже Херувимы", BookReadingMode.RUSSIAN).single()
        assertEquals(BookReadingMode.ORIGINAL, original.mode)
        assertEquals("И́же Херуви́мы", block.original.substring(original.matchStart, original.matchEndExclusive))
        val russian = BookSearch.find(book, "изображаем херувимов", BookReadingMode.ORIGINAL).single()
        assertEquals(BookReadingMode.RUSSIAN, russian.mode)
        assertEquals("Изображаем херувимов", block.russian!!.substring(russian.matchStart, russian.matchEndExclusive))
    }

    @Test fun oldPositionsRemainReadableAndMeaningCanRememberTheLastTextLanguage() {
        val position = BookReadingPosition(ServiceBookKey("2026-11-11"), "a".repeat(64), null,
            "block", "chapter", mode = BookReadingMode.MEANING, lastTextMode = BookReadingMode.RUSSIAN)
        assertEquals(position, BookReadingCodec.decode(BookReadingCodec.encode(position)))
        val old = BookReadingCodec.encode(position).replace(",\"lastTextMode\":\"RUSSIAN\"", "")
        assertNull(BookReadingCodec.decode(old)!!.lastTextMode)
        assertNull(BookReadingCodec.decode(BookReadingCodec.encode(position).replace("\"lastTextMode\":\"RUSSIAN\"", "\"lastTextMode\":\"MEANING\"")))
    }
}
