package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import java.util.concurrent.CancellationException

class BookCorpusSearchTest {
    @Test fun aPhraseInTheOtherBookOutranksAllWeakMatchesInTheOpenBook() {
        val vespers = book(WorshipKind.VESPERS, (0 until 35).map {
            prayer("weak-$it", "Слава Тебе в вышних, Богу нашему, и мир на земли")
        })
        val matins = book(WorshipKind.MATINS, listOf(prayer("praise", "Сла́ва в вы́шних Бо́гу и на земли́ мир.")))
        val results = BookCorpusSearch.find(listOf(vespers, matins), "Слава в вышних Богу и на земли мир", BookReadingMode.ORIGINAL)
        assertEquals(36, results.size)
        assertEquals(matins.key, results.first().bookKey)
        assertEquals("praise", results.first().hit.blockId)
        assertEquals((0 until 35).map { "weak-$it" }, results.drop(1).map { it.hit.blockId })
        assertTrue(results.drop(1).all { it.bookKey == vespers.key })
    }

    @Test fun repeatedResponsesKeepBothBooksAndAllLateOccurrencesInStableSourceOrder() {
        val responses = (0 until 42).map { prayer("response-$it", "Господи, помилуй.") }
        val vespers = book(WorshipKind.VESPERS, responses)
        val matins = book(WorshipKind.MATINS, responses)
        val results = BookCorpusSearch.find(listOf(vespers, matins), "Господи помилуй")
        assertEquals(84, results.size)
        assertEquals(List(42) { vespers.key } + List(42) { matins.key }, results.map { it.bookKey })
        assertEquals(List(2) { responses.map { it.id } }.flatten(), results.map { it.hit.blockId })
        assertEquals(84, results.map { it.identity }.distinct().size)
        assertEquals("response-41", results.last().hit.blockId)
    }

    @Test fun resultIdentityAndValidationIncludeTheExactEditionAndTheWholeBookKey() {
        val accepted = book(WorshipKind.MATINS, listOf(prayer("same-id", "Отче наш")))
        val result = BookCorpusSearch.find(listOf(accepted), "Отче наш").single()
        assertSame(result, result.validatedFor(accepted))
        val otherDate = accepted.copy(key = accepted.key.copy(date = "2026-11-14"))
        val otherVariant = accepted.copy(key = accepted.key.copy(mainEvent = "123"))
        val newer = accepted.copy(contentHash = "b".repeat(64))
        for (other in listOf(otherDate, otherVariant, newer)) {
            assertNull(result.validatedFor(other))
            val otherResult = BookCorpusSearch.find(listOf(other), "Отче наш").single()
            assertNotEquals(result.identity, otherResult.identity)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BookCorpusSearch.find(listOf(accepted, newer), "Отче наш")
        }
    }

    @Test fun validationRejectsWrongFragmentsLanguagesAndOffsetsWithoutSubstitutingOriginalText() {
        val book = book(WorshipKind.MATINS, listOf(prayer("prayer", "Отче наш", "Отец наш, Сущий на небесах")))
        val result = BookCorpusSearch.find(listOf(book), "Сущий на небесах").single()
        assertEquals(BookReadingMode.RUSSIAN, result.hit.mode)
        assertSame(result, result.validatedFor(book))
        val invalid = listOf(
            result.hit.copy(chapterId = "another"),
            result.hit.copy(chapterTitle = "Another title"),
            result.hit.copy(blockId = "another"),
            result.hit.copy(mode = BookReadingMode.MEANING),
            result.hit.copy(matchStart = -1),
            result.hit.copy(matchEndExclusive = result.hit.matchStart),
            result.hit.copy(matchEndExclusive = 10_000)
        )
        for (hit in invalid) assertNull(result.copy(hit = hit).validatedFor(book))
        val untranslated = book.copy(chapters = listOf(book.chapters.single().copy(
            blocks = listOf(book.blocks.single().copy(russian = null)))))
        assertNull("A Russian hit cannot silently navigate the original", result.validatedFor(untranslated))
    }

    @Test fun validationNeverSplitsAUtf16SurrogatePair() {
        val source = "Господи \uD83D\uDE4F помилуй"
        val book = book(WorshipKind.VESPERS, listOf(prayer("prayer", source)))
        val result = BookCorpusSearch.find(listOf(book), "помилуй").single()
        val split = source.indexOf('\uD83D') + 1
        assertSame(result, result.validatedFor(book))
        assertNull(result.copy(hit = result.hit.copy(matchStart = split)).validatedFor(book))
        assertNull(result.copy(hit = result.hit.copy(matchStart = 0, matchEndExclusive = split)).validatedFor(book))
    }

    @Test fun aCancelledCorpusSearchDoesNotReadTheRestOfThisBookOrTheNextBook() {
        val visited = mutableListOf<Int>()
        val first = book(WorshipKind.VESPERS, object : AbstractList<BookBlock>() {
            override val size = 1_000
            override fun get(index: Int): BookBlock {
                visited += index
                return prayer("paragraph-$index", "Совсем другой текст. ".repeat(20))
            }
        })
        val second = book(WorshipKind.MATINS, object : AbstractList<BookBlock>() {
            override val size = 1
            override fun get(index: Int): BookBlock = error("Cancelled search entered the next book")
        })
        var checks = 0
        val expected = CancellationException("Search closed")
        val thrown = assertThrows(CancellationException::class.java) {
            BookCorpusSearch.find(listOf(first, second), "Слава в вышних") {
                if (++checks == 12) throw expected
            }
        }
        assertSame(expected, thrown)
        assertEquals((0 until 10).toList(), visited)
    }

    @Test fun anEmptyQueryDoesNotReadEitherBook() {
        val unreadable = book(WorshipKind.MATINS, object : AbstractList<BookBlock>() {
            override val size = 1
            override fun get(index: Int): BookBlock = error("Empty query scanned a book")
        })
        assertTrue(BookCorpusSearch.find(listOf(unreadable), " ").isEmpty())
        assertTrue(BookCorpusSearch.find(emptyList(), "Господи помилуй").isEmpty())
    }

    @Test fun aRecognizerAlternativeCanBeConfirmedOnlyInTheOtherBook() {
        val vespers = book(WorshipKind.VESPERS, listOf(prayer("opening", "Господи, помилуй")))
        val matins = book(WorshipKind.MATINS, listOf(prayer("praise", "Сла́ва в вы́шних Бо́гу и на земли́ мир")))
        val candidates = listOf("Господи", "слава в вышних Богу")
        assertEquals("Господи", BookSpeechSearch.query(vespers, candidates))
        val query = BookSpeechSearch.query(listOf(vespers, matins), candidates)
        assertEquals("слава в вышних Богу", query)
        assertEquals(matins.key, BookCorpusSearch.find(listOf(vespers, matins), query).single().bookKey)
        assertEquals("ошибка распознавания", BookSpeechSearch.query(listOf(vespers, matins),
            listOf("ошибка распознавания", "Богу вышних слава")))
    }

    @Test fun singleSpeechHypothesisStillDoesNotReadAnyBook() {
        val unreadable = book(WorshipKind.MATINS, object : AbstractList<BookBlock>() {
            override val size = 1
            override fun get(index: Int): BookBlock = error("One hypothesis needs no book scan")
        })
        assertEquals("Отче наш", BookSpeechSearch.query(listOf(unreadable), listOf("Отче наш", " Отче наш ")))
        assertEquals("я", BookSpeechSearch.query(listOf(unreadable), listOf("я", "а")))
        assertEquals("!", BookSpeechSearch.query(listOf(unreadable), listOf("!", "?")))
    }

    @Test fun sourceIdSeparatorsCannotMakeDifferentLocationsShareAnIdentity() {
        val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS)
        fun located(chapter: String, block: String) = BookLocatedSearchHit(key, "a".repeat(64),
            BookSearchHit(chapter, "Молитвы", block, "Отче наш", matchEndExclusive = 8))
        assertNotEquals(located("first\u0000second", "third").identity,
            located("first", "second\u0000third").identity)
    }

    private fun prayer(id: String, original: String, russian: String? = null) =
        BookBlock(id, BookBlockKind.PRAYER, null, original, russian)

    private fun book(worship: WorshipKind, blocks: List<BookBlock>): ServiceBook {
        val key = ServiceBookKey("2026-11-13", worship)
        return ServiceBook(key = key, title = worship.title, sourceUrl = key.sourceUrl(), sourceTitle = "Тест",
            fetchedAtMillis = 1, selectedEventTitle = null, contentHash = "a".repeat(64),
            chapters = listOf(BookChapter("chapter", "Молитвы", "Объяснение", blocks)))
    }
}
