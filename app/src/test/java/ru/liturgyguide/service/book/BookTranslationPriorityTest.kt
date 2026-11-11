package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import ru.liturgyguide.model.BookSource
import org.junit.Test

class BookTranslationPriorityTest {
    @Test fun existingWholeCellEditionWinsOverAnEarlierDerivedPetition() {
        val original = "О избавитися нам от всякия скорби, гнева и нужды, Господу помолимся."
        val derived = """
            <tr><td>Лик: Господи, помилуй.</p><p>$original</td>
            <td>Хор: Господи, помилуй.</p><p>Другой тестовый перевод.</td></tr>
        """
        val whole = "<tr><td>$original</td><td>Ранее доступный тестовый перевод.</td></tr>"
        for (rows in listOf(derived + whole, whole + derived)) {
            val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>$rows</table>"))
            assertEquals("Ранее доступный тестовый перевод.", index.find(original)?.russian)
        }
    }

    @Test fun partialRefreshCannotReplaceAnExactFullEditionPairWithTheShortEdition() {
        val original = "Да исполнятся уста наша хваления Твоего, Господи."
        fun pair(source: BookSource, text: String) = BookTranslationIndex.fromParallelPage(
            Jsoup.parse("<table><tr><td>$original</td><td>$text</td></tr></table>"), source)
        val full = pair(BookTranslationIndex.TIMROT_FULL_SOURCE, "Полный тестовый перевод.")
        val short = pair(BookTranslationIndex.TIMROT_SOURCE, "Краткий тестовый перевод.")
        for (cachedFull in listOf(full, BookTranslationIndex.decode(full.encode()))) {
            // Both normal source order and an incomplete refresh retain the full edition.
            assertEquals(full.find(original), cachedFull.plus(short).find(original))
            assertEquals(full.find(original), short.plus(cachedFull).find(original))
            assertEquals(full.find(original), BookTranslationIndex.decode(short.plus(cachedFull).encode()).find(original))
        }
        // This is a preference between exactly two editions, not a general ban on refreshing a pair.
        val revisedFull = pair(BookTranslationIndex.TIMROT_FULL_SOURCE, "Обновленный полный тестовый перевод.")
        val revisedShort = pair(BookTranslationIndex.TIMROT_SOURCE, "Обновленный краткий тестовый перевод.")
        assertEquals(revisedFull.find(original), revisedFull.plus(full).find(original))
        assertEquals(revisedShort.find(original), revisedShort.plus(short).find(original))
        val other = pair(BookTranslationIndex.TIMROT_SOURCE.copy(url = "https://azbyka.ru/days/2026-11-12"), "Датированный тестовый текст.")
        assertEquals(other.find(original), other.plus(full).find(original))
        val differentOriginal = BookTranslationIndex.fromParallelPage(Jsoup.parse(
            "<table><tr><td>Да не исполнятся уста наша хваления Твоего, Господи.</td><td>Отдельный тестовый текст.</td></tr></table>"), BookTranslationIndex.TIMROT_SOURCE)
        assertEquals(full.find(original), differentOriginal.plus(full).find(original))
        assertEquals(2, differentOriginal.plus(full).size)
    }

}
