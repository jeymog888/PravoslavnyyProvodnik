package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
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
}
