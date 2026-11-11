package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

class ScheduleDocumentParserTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val nicholas = Parish("nicholas", "Никольский храм", "Никольский храм", "Москва", "Адрес", "", "https://example.org/",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private fun inspect(html: String, parish: Parish? = null) = ScheduleDocumentParser.inspect(
        HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, "https://example.org/schedule/")), today, parish)
    private fun parse(html: String, parish: Parish? = null) = inspect(html, parish).events
    private fun scanned(vararg lines: String) = ScheduleDocument("https://example.org/a.pdf", "pdf",
        listOf(ScheduleDocumentPage(lines.map { ScheduleDocumentLine(it) })))

    @Test fun `ordinary table expands rowspans and preserves the title before its time`() {
        val result = parse("""<main><h1>Расписание на ноябрь 2026 года</h1><table>
            <tr><td rowspan="2">14 ноября, суббота</td><td>Литургия</td><td>09:00</td></tr>
            <tr><td>Всенощное бдение</td><td>18:00</td></tr>
            <tr><td>15 ноября, воскресенье</td><td>09:30 Исповедь и Литургия</td></tr>
            </table></main>""")
        assertEquals(listOf("2026-11-14", "2026-11-14", "2026-11-15"), result.map { it.date })
        assertEquals(listOf("09:00", "18:00", "09:30"), result.map { it.time })
        assertEquals(listOf("liturgy", "service_block", "service_block"), result.map { it.kind })
    }

    @Test fun `separate date headings and several clocks produce individual services`() {
        val result = parse("""<h1>Расписание ноябрь 2026</h1><div><h2>15 ноября</h2>
            <p>08:30 Часы<br>09:00 Божественная литургия<br>17:00 Вечерня</p></div>""")
        assertEquals(listOf("08:30", "09:00", "17:00"), result.map { it.time })
        assertTrue(result.all { it.date == "2026-11-15" })
    }

    @Test fun `scanned continuation text is attached to the same dated event`() {
        val result = ScheduleDocumentParser.parse(scanned("Расписание Богослужений в Никольском Храме",
            "Ноябрь 2026 ГОДА.", "14 Сб. 18.00 Воскресная всенощная", "15 Вс. 9.30 Неделя 24-я по Пятидесятнице. Исповедь и",
            "Литургия.", "Адрес храма: Tuonelankuja 3"), today, nicholas)
        assertEquals(2, result.size)
        assertEquals("2026-11-14", result[0].date)
        assertEquals("18:00", result[0].time)
        assertEquals("09:30", result[1].time)
        assertTrue(result[1].title.endsWith("Исповедь и Литургия"))
        assertFalse(result[1].title.contains("Адрес"))
    }

    @Test fun `missing year invalid weekday and month boundaries are not guessed`() {
        assertTrue(parse("<h1>Ноябрь</h1><p>14 Сб. 18.00 Всенощная</p><footer>2026</footer>").isEmpty())
        assertTrue(ScheduleDocumentParser.parse(scanned("Ноябрь 2026", "14 Вс. 18.00 Всенощная"), today).isEmpty())
        val document = ScheduleDocument("https://example.org/a.pdf", "pdf", listOf(
            ScheduleDocumentPage(listOf(ScheduleDocumentLine("Ноябрь 2026"), ScheduleDocumentLine("14 Сб. 18.00 Всенощная"))),
            ScheduleDocumentPage(listOf(ScheduleDocumentLine("15 Вс. 9.30 Литургия")))))
        assertEquals(1, ScheduleDocumentParser.parse(document, today).size)
    }

    @Test fun `unreliable temporal OCR cancellation and another building do not produce a service`() {
        val document = scanned("Ноябрь 2026", "14 Сб. 18.00 Всенощная отменена", "15 Вс. 9.30 Литургия в Казанском храме")
        assertTrue(ScheduleDocumentParser.parse(document, today).isEmpty())
        val lowConfidence = document.copy(pages = listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine("Ноябрь 2026"),
            ScheduleDocumentLine("15 Вс. 09.30 Литургия", temporalConfidence = 20)))))
        assertTrue(ScheduleDocumentParser.parse(lowConfidence, today).isEmpty())
        assertTrue(parse("<h1>Ноябрь 2026</h1><p><del>15 Вс. 9:30 Литургия</del></p>").isEmpty())
    }

    @Test fun `full numeric dates work without unrelated copyright year`() {
        val result = parse("<main><p>15.11.2026 | 9:00 | Литургия</p><p>2026-11-14 18:00 Всенощное бдение</p></main>")
        assertEquals(listOf("2026-11-14", "2026-11-15"), result.map { it.date })
        assertTrue(parse("<p>14-15 ноября 2026 | 9:00 | Литургия</p>").isEmpty())
    }

    @Test fun `parallel multiline cells keep every clock with its own service`() {
        val result = parse("""<h1>Ноябрь 2026</h1><table><tr><td>15 ноября</td>
            <td>08:00<br>09:00</td><td>Часы<br>Литургия</td></tr></table>""")
        assertEquals(listOf("08:00" to "Часы", "09:00" to "Литургия"), result.map { it.time to it.title })
        assertTrue(result.all { it.date == "2026-11-15" })
    }

    @Test fun `Kruticy numeric date cells inherit the printed year and preserve wrapped weekdays and rowspans`() {
        // Shape taken from https://kruticy.moscow/bogosluzheniya/ on 2026-11-11.
        val table = """<h2>Расписание богослужений на ноябрь 2026 года</h2><table><tbody>
            <tr><td rowspan="2"><strong>14.11</strong><br><strong>сб.</strong></td>
            <td rowspan="2">Бессребреников и чудотворцев Космы и Дамиана Аззийских</td><td>Утреня, Литургия</td><td>8:00</td></tr>
            <tr><td>Всенощное бдение</td><td>17:00</td></tr>
            <tr><td>15.11<br>вс.</td><td>Неделя 24-я по Пятидесятнице<br>Мчч. Акиндина и Пигасия</td>
            <td>Литургия</td><td>9:00</td></tr></tbody></table>"""
        val result = parse(table)
        assertEquals(listOf("2026-11-14" to "08:00", "2026-11-14" to "17:00", "2026-11-15" to "09:00"),
            result.map { it.date to it.time })
        assertTrue(parse(table.replace("2026 года", "")).isEmpty())
    }

    @Test fun `numeric date support does not infer a missing year or mistake a dot clock for a day`() {
        assertTrue(parse("<table><tr><td>15.11 вс.</td><td>Литургия</td><td>09:00</td></tr></table>").isEmpty())
        assertTrue(parse("<h1>Ноябрь 2026</h1><p>15.11.25 | 09:00 Литургия</p>").isEmpty())
        val clock = parse("<h1>Ноябрь 2026</h1><h2>15 ноября</h2><p>09.10 Литургия</p>")
        assertEquals(listOf("2026-11-15" to "09:10"), clock.map { it.date to it.time })
        val foreign = parse("""<h1>Ноябрь 2026</h1><table><tr><td>15.11 вс.</td>
            <td>09:00</td><td>Литургия</td><td>Казанский храм</td></tr></table>""", nicholas)
        assertTrue(foreign.isEmpty())
    }

    @Test fun `a shared title is broadcast to parallel clocks but incompatible subrows are not guessed`() {
        val shared = parse("""<h1>Ноябрь 2026</h1><table><tr><td>15 ноября</td>
            <td>07:00<br>10:00</td><td>Литургия</td></tr></table>""")
        assertEquals(listOf("07:00" to "Литургия", "10:00" to "Литургия"), shared.map { it.time to it.title })
        val ambiguous = parse("""<h1>Ноябрь 2026</h1><table><tr><td>15 ноября</td>
            <td>07:00<br>10:00</td><td>Часы<br>Литургия<br>Молебен</td></tr></table>""")
        assertTrue(ambiguous.isEmpty())
    }

    @Test fun `a building column is matched to the selected church instead of becoming service text`() {
        val result = parse("""<h1>Ноябрь 2026</h1><table>
            <tr><td>15 ноября</td><td>09:00</td><td>Литургия</td><td>Казанский храм</td></tr>
            <tr><td>15 ноября</td><td>10:00</td><td>Литургия</td><td>Никольский храм</td></tr></table>""", nicholas)
        assertEquals(listOf("10:00" to "Литургия"), result.map { it.time to it.title })
    }

    @Test fun `OCR inline building names survive lost table borders and match every named building`() {
        fun read(title: String) = ScheduleDocumentParser.parse(scanned("Ноябрь 2026", "15 Вс. 09:30 $title"), today, nicholas)
        assertEquals(listOf("09:30"), read("Литургия Никольский храм").map { it.time })
        assertEquals(listOf("09:30"), read("Литургия храм Святителя Николая").map { it.time })
        assertEquals(listOf("09:30"), read("Литургия в Никольском храме").map { it.time })
        assertTrue(read("Литургия Казанский храм").isEmpty())
        assertTrue(read("Литургия храм Казанской иконы Божией Матери").isEmpty())
        assertTrue(read("Литургия в Казанском храме").isEmpty())
        assertTrue(read("Литургия Никольский храм и Казанский храм").isEmpty())
        val peterOnly = nicholas.copy(name = "Храм апостола Петра", shortName = "Храм апостола Петра")
        assertTrue(ScheduleDocumentParser.parse(scanned("Ноябрь 2026",
            "15 Вс. 09:30 Литургия храм апостолов Петра и Павла"), today, peterOnly).isEmpty())
    }

    @Test fun `Kruticy calendar feast named Sobor is not mistaken for a cathedral venue`() {
        val feast = "Собор архистратига Михаила и прочих Небесных Сил бесплотных"
        val result = parse("""<h2>Расписание богослужений на ноябрь 2026 года</h2><table>
            <tr><td rowspan="2">21.11<br>сб.</td><td rowspan="2">$feast</td><td>Литургия</td><td>9:00</td></tr>
            <tr><td>Всенощное бдение</td><td>17:00</td></tr></table>""", nicholas)
        assertEquals(listOf("09:00", "17:00"), result.map { it.time })
        val scannedFeast = ScheduleDocumentParser.parse(scanned("Ноябрь 2026", "21 Сб. 09:00 $feast. Литургия"), today, nicholas)
        assertEquals(1, scannedFeast.size)
        // Keep actual named cathedrals and explicit cathedral locations subject to identity checks.
        val cathedral = parse("""<h1>Ноябрь 2026</h1><table><tr><td>21.11 сб.</td><td>09:00</td>
            <td>Литургия</td><td>Успенский собор</td></tr></table>""", nicholas)
        assertTrue(cathedral.isEmpty())
        assertTrue(ScheduleDocumentParser.parse(scanned("Ноябрь 2026",
            "21 Сб. 09:00 Литургия в соборе Архистратига Михаила и прочих Небесных Сил бесплотных"), today, nicholas).isEmpty())
    }

    @Test fun `Dnepr two digit year requires the same explicit printed four digit year`() {
        val table = """<h3>Ноябрь 2026 года</h3><table><tr><td>15.11.26,<br>вс</td>
            <td>08:00 - Божественная Литургия. Молебен</td></tr></table>"""
        val result = parse(table)
        assertEquals(listOf("2026-11-15" to "08:00"), result.map { it.date to it.time })
        assertTrue(parse(table.replace("15.11.26", "15.11.25")).isEmpty())
        assertTrue(parse(table.replace("2026 года", "")).isEmpty())
        assertTrue(parse(table.replace("<h3>Ноябрь 2026 года</h3>", "") + "<footer>2026</footer>").isEmpty())
        assertTrue(parse(table.replace("15.11.26", "31.11.26")).isEmpty())
        assertTrue(parse(table.replace("15.11.26", "14.11.26")).isEmpty()) // printed weekday disagrees
    }

    @Test fun `building and cancellation headings retain their scope across nested date headings`() {
        val result = parse("""<h1>Ноябрь 2026</h1>
            <h2>Казанский храм - все службы отменены</h2><h3>15 ноября</h3><p>09:00 Литургия</p>
            <h2>Никольский храм</h2><h3>15 ноября</h3><p>10:00 Литургия</p>""", nicholas)
        assertEquals(listOf("10:00"), result.map { it.time })
    }

    @Test fun `a named building under a date inherits the date but never another building`() {
        val result = parse("""<h1>Ноябрь 2026</h1><h2>15 ноября</h2>
            <h3>Казанский храм</h3><p>09:00 Литургия</p>
            <h3>Никольский храм</h3><p>10:00 Литургия</p>""", nicholas)
        assertEquals(listOf("2026-11-15" to "10:00"), result.map { it.date to it.time })
    }

    @Test fun `dated cancellation does not cancel the next day or escape through a nested heading`() {
        val result = inspect("""<h1>Ноябрь 2026</h1>
            <h2>15 ноября - все службы отменены</h2><h3>Утро</h3><p>09:00 Литургия</p>
            <h2>16 ноября</h2><p>08:00 Литургия</p>""")
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertEquals(listOf("2026-11-16"), result.events.map { it.date })
        val laterNotice = inspect("""<h1>Ноябрь 2026</h1><h2>15 ноября</h2>
            <p>09:00 Литургия</p><p>Все службы отменены</p><h2>16 ноября</h2><p>08:00 Литургия</p>""")
        assertEquals(listOf("2026-11-16"), laterNotice.events.map { it.date })
    }

    @Test fun `explicit withdrawal is distinguished from an unsupported document`() {
        val rejected = inspect("""<article class="hentry"><h1 class="entry-title">Ноябрь 2026</h1>
            <div class="entry-content"><h2>Все службы отменены</h2><p>15 Вс. 09:00 Литургия</p></div></article>""")
        assertEquals(ScheduleParseDisposition.REJECTED, rejected.disposition)
        assertTrue(rejected.events.isEmpty())
        assertEquals(ScheduleParseDisposition.UNSUPPORTED, inspect("<p>Добро пожаловать</p>").disposition)
    }

    @Test fun `geometric OCR joins visual rows even when recognizer lists columns separately`() {
        val words = listOf(
            ScheduleWord("14", 10, 100, 30, 120), ScheduleWord("15", 10, 150, 30, 170),
            ScheduleWord("Сб.", 40, 100, 60, 120), ScheduleWord("Вс.", 40, 150, 60, 170),
            ScheduleWord("18.00", 80, 100, 120, 120), ScheduleWord("9.30", 80, 150, 120, 170),
            ScheduleWord("Всенощная", 140, 98, 260, 123), ScheduleWord("Литургия", 140, 148, 260, 173))
        assertEquals(listOf("14 Сб. 18.00 Всенощная", "15 Вс. 9.30 Литургия"), ScheduleOcrLayout.lines(words).map { it.text })
    }

    @Test fun `linked scanned documents and content images are discovered but navigation images are excluded`() {
        val page = Jsoup.parse("""<header><img src="/logo.png"></header><h1>Расписание богослужений</h1>
            <a href="/schedule.pdf">Скачать PDF</a><a href="https://cdn.example.org/schedule.pdf">Расписание</a>
            <a href="https://other.org/raspisanie/">Расписание</a><article><img src="/month.jpg" width="900"></article>""", "https://example.org/")
        val links = ScheduleDocumentLinks.find(page)
        assertEquals(listOf("https://example.org/schedule.pdf", "https://cdn.example.org/schedule.pdf", "https://example.org/month.jpg"), links.map { it.url })
        assertTrue(links.all { it.document && it.linkedFrom == "https://example.org/" })
    }
}
