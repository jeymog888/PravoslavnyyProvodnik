package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import kotlinx.coroutines.CancellationException
import ru.liturgyguide.model.*

class BookSearchRankingTest {
    @Test fun supersededSearchStopsBeforeReadingTheRemainingBook() {
        val visited = mutableListOf<Int>()
        val blocks = object : AbstractList<BookBlock>() {
            override val size = 1_000
            override fun get(index: Int): BookBlock {
                visited += index
                // A long paragraph does not need the short-response context lookup.
                return block("block-$index", "Господи, помилуй. " + "Продолжение молитвы. ".repeat(20))
            }
        }
        var checks = 0
        val cancelled = CancellationException("A newer query superseded this search")
        val thrown = assertThrows(CancellationException::class.java) {
            BookSearch.find(book(blocks), "Господи помилуй", checkActive = {
                if (++checks > 100) throw cancelled
            })
        }
        assertSame(cancelled, thrown)
        assertEquals((0 until 100).toList(), visited)
        assertEquals(101, checks)
    }

    @Test fun exactPhraseAfterThirtyWeakMatchesRemainsTheFirstAvailableResult() {
        val early = (0 until 35).map { block("early-$it", "Господи, услышь нас и помилуй") }
        val exact = block("late-exact", "Господи, помилуй.")
        val hits = BookSearch.find(book(early + exact), "Господи помилуй", BookReadingMode.ORIGINAL)
        assertEquals(36, hits.size)
        assertEquals("late-exact", hits.first().blockId)
        assertEquals((0 until 35).map { "early-$it" }, hits.drop(1).map { it.blockId })
        assertEquals("Господи, помилуй", exact.original.substring(hits.first().matchStart, hits.first().matchEndExclusive))
    }

    @Test fun theLastRepeatedResponseOfTheRealThirteenNovemberMatinsIsReachable() {
        val html = requireNotNull(javaClass.classLoader?.getResource("matins-rank/default-2026-11-13.html")).readText()
        val book = ServiceBookParser().parse(Jsoup.parse(html), ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34"), 1)
        val expected = book.blocks.filter { BookText.normalized(it.original).contains("господи помилуй") }.map { it.id }
        assertEquals("The frozen actual source has forty-two response blocks", 42, expected.size)
        val hits = BookSearch.find(book, "Господи помилуй", BookReadingMode.ORIGINAL)
        assertEquals(expected, hits.filter { it.blockId in expected }.map { it.blockId })
        assertTrue(hits.any { it.blockId == expected.last() })
        assertEquals(hits.size, hits.map { it.blockId }.distinct().size)
    }

    @Test fun exactHeardPhraseWinsOverUnorderedWordsInTheOtherLanguageWhileTiesKeepPreference() {
        val originalExact = block("prayer", "Го́споди, поми́луй.", "Господи, услышь нас и помилуй")
        val original = BookSearch.find(book(listOf(originalExact)), "Господи помилуй", BookReadingMode.RUSSIAN).single()
        assertEquals(BookReadingMode.ORIGINAL, original.mode)
        assertEquals("Го́споди, поми́луй", originalExact.original.substring(original.matchStart, original.matchEndExclusive))
        val russianExact = block("prayer", "Господи, услышь нас и помилуй", "Господи, помилуй.")
        assertEquals(BookReadingMode.RUSSIAN,
            BookSearch.find(book(listOf(russianExact)), "Господи помилуй", BookReadingMode.ORIGINAL).single().mode)
        val both = block("prayer", "Го́споди, поми́луй.", "Господи, помилуй.")
        for (mode in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN))
            assertEquals(mode, BookSearch.find(book(listOf(both)), "Господи помилуй", mode).single().mode)
    }

    @Test fun severalHeardWordsCannotMatchInsideOtherWordsOrReuseOneOccurrence() {
        assertNull(BookSearchText.find("Владимир помилуй", "мир помилуй"))
        assertNull(BookSearchText.find("Господи, помилуй нас", "Господи Господи"))
        val reordered = requireNotNull(BookSearchText.find("Господи, Боже мой, Господи, помилуй", "Господи Господи"))
        assertEquals(0, reordered.start)
        assertNotNull("Single-word partial typing stays available", BookSearchText.find("Иже Херувимы", "херув"))
    }

    @Test fun repeatedShortResponsesShowTheirDifferentPetitionsWithoutChangingJumpOffsets() {
        val first = block("response-1", "Господи, помилуй.")
        val second = block("response-2", "Господи, помилуй.")
        val book = book(listOf(block("petition-1", "О мире всего мира Господу помолимся."), first,
            block("petition-2", "О плавающих, путешествующих, недугующих Господу помолимся."), second))
        val hits = BookSearch.find(book, "Господи помилуй", BookReadingMode.ORIGINAL)
        assertEquals(listOf("response-1", "response-2"), hits.map { it.blockId })
        assertTrue(hits[0].excerpt.contains("О мире всего мира"))
        assertTrue(hits[1].excerpt.contains("О плавающих"))
        assertFalse(hits[1].excerpt.contains("О мире всего мира"))
        for (hit in hits) {
            assertEquals("Ектения", hit.chapterTitle)
            val source = book.blocks.single { it.id == hit.blockId }.original
            assertEquals("Господи, помилуй", source.substring(hit.matchStart, hit.matchEndExclusive))
        }
        val mixed = book(listOf(block("older", "Ранее просили о другом", "Ранее просили о другом"),
            block("untranslated", "Чужой непереведенный оригинал"),
            block("response", "Го́споди, поми́луй.", "Господи, помилуй.")))
        val russian = BookSearch.find(mixed, "Господи помилуй", BookReadingMode.RUSSIAN).single()
        assertEquals(BookReadingMode.RUSSIAN, russian.mode)
        assertFalse(russian.excerpt.contains("Чужой непереведенный оригинал"))
        assertFalse("An untranslated intervening petition cannot be replaced by an older one",
            russian.excerpt.contains("Ранее просили о другом"))
    }

    private fun block(id: String, original: String, russian: String? = null) =
        BookBlock(id, BookBlockKind.PRAYER, null, original, russian)

    private fun book(blocks: List<BookBlock>): ServiceBook {
        val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS)
        return ServiceBook(key = key, title = "Утреня", sourceUrl = key.sourceUrl(), sourceTitle = "Тест",
            fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("litany", "Ектения", "Просим Бога о помощи", blocks)), contentHash = "a".repeat(64))
    }
}
