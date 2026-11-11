package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

class BookSpeechSearchTest {
    private val book = ServiceBook(
        key = ServiceBookKey("2026-11-11"), title = "Тестовая книга", sourceUrl = "https://example.com/book",
        sourceTitle = "Тест", fetchedAtMillis = 1, selectedEventTitle = null, contentHash = "test",
        chapters = listOf(BookChapter("chapter", "Песнопение", "Объяснение", listOf(
            BookBlock("cherubim", BookBlockKind.PRAYER, null, "И́же Херуви́мы та́йно образу́юще", "Тайно изображаем херувимов"),
            BookBlock("mercy", BookBlockKind.PRAYER, null, "Господи, помилуй")))))

    @Test fun recognizerAlternativeWithExactPublishedPhraseWinsAndCanBeOpenedAtSourceWords() {
        val query = BookSpeechSearch.query(book, listOf("ниже херувимы", "Иже Херувимы"))
        assertEquals("Иже Херувимы", query)
        val hit = BookSearch.find(book, query).single()
        assertEquals("cherubim", hit.blockId)
        assertEquals("И́же Херуви́мы", book.blocks.first().original.substring(hit.matchStart, hit.matchEndExclusive))
    }

    @Test fun specificPhraseWinsOverGenericWordAndRussianIsAlsoEligible() {
        assertEquals("Иже Херувимы", BookSpeechSearch.query(book, listOf("Господи", "Иже Херувимы")))
        assertEquals("изображаем херувимов", BookSpeechSearch.query(book, listOf("искажаем херувимов", "изображаем херувимов")))
    }

    @Test fun unmatchedOrPartialWordAlternativesNeverGetInventedOrPromoted() {
        assertEquals("непонятная фраза", BookSpeechSearch.query(book, listOf("непонятная фраза", "милуй")))
        assertEquals("исходная ошибка", BookSpeechSearch.query(book, listOf("исходная ошибка", "Херувимы Иже")))
        assertEquals("", BookSpeechSearch.query(book, listOf(" ", "")))
        val candidates = List(5) { "ошибка $it" } + "Иже Херувимы"
        assertEquals("ошибка 0", BookSpeechSearch.query(book, candidates))
    }

    @Test fun singleOrDuplicateHypothesisNeverReadsTheBook() {
        val unreadable = book.copy(chapters = object : AbstractList<BookChapter>() {
            override val size = 1
            override fun get(index: Int): BookChapter = error("A single hypothesis needs no book scan")
        })
        assertEquals("Иже Херувимы", BookSpeechSearch.query(unreadable, listOf("Иже Херувимы")))
        assertEquals("Иже Херувимы", BookSpeechSearch.query(unreadable, listOf("Иже Херувимы", " Иже Херувимы ")))
        assertEquals("", BookSpeechSearch.query(unreadable, emptyList()))
    }

    @Test fun cancelledAlternativeScanStopsBeforeReadingTheRestOfALongBook() {
        var visited = 0
        var checks = 0
        val long = book.copy(chapters = listOf(book.chapters.single().copy(blocks = object : AbstractList<BookBlock>() {
            override val size = 1000
            override fun get(index: Int): BookBlock {
                visited++
                check(index < 2) { "Cancelled scan kept traversing the book" }
                return BookBlock("block-$index", BookBlockKind.PRAYER, null, "Другой текст")
            }
        })))
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            BookSpeechSearch.query(long, listOf("Иже Херувимы", "Господи помилуй")) {
                checks++
                if (checks == 3) throw java.util.concurrent.CancellationException()
            }
        }
        assertEquals(2, visited)
    }
}
