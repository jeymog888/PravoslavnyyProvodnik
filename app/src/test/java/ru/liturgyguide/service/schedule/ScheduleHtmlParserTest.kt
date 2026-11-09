package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleHtmlParserTest {
    private val today = LocalDate.of(2026, 11, 8)
    private fun parse(html: String) = ScheduleHtmlParser.parse(html, "https://example.org/schedule/", today)
    private fun iso(title: String, date: String = "2026-11-10") =
        """<article data-day="$date"><ul><li><time>09:00</time><span>$title</span></li></ul></article>"""

    @Test fun `ISO source keeps the actual liturgy start and excludes empty clocks and old months`() {
        // Sanitized real structure: https://hramkresta.ru/schedule/
        val html = """<article data-day="2026-11-08"><div data-date="2026-11-08">8 ноября</div><ul>
          <li><time>08:00</time><span>Исповедь</span></li>
          <li><time>08:30</time><span>Часы</span></li>
          <li><time>09:00</time><span>Литургия</span><span class="who">Священник</span></li>
          <li><time></time><span>Заупокойная лития</span></li></ul></article>""" + iso("Литургия", "2026-10-08")
        val result = parse(html)
        assertEquals(3, result.size)
        assertEquals("09:00", result.single { it.kind == "liturgy" }.time)
        assertEquals("Литургия", result.last().title)
    }

    @Test fun `WordPress calendar uses civil date and distinguishes a combined service block`() {
        // Sanitized real structure: https://hram-petr-fevronia.ru/parish-life/schedule/
        val html = """<div class="bg_ortcal_schedule"><table>
          <tr><td class="bg_ortcal_day"><span class="bg_ortcal_date">Воскресенье, 8 ноября 2026 г.</span>
          <span class="bg_ortcal_old">26 октября ст.ст.</span></td></tr>
          <tr><td class="bg_ortcal_time">9:00</td><td class="bg_ortcal_event">Литургия и Молебен</td></tr>
          <tr><td class="bg_ortcal_day"><span class="bg_ortcal_date">Вторник, 10 ноября 2026 г.</span></td></tr>
          <tr><td class="bg_ortcal_time">8:30</td><td class="bg_ortcal_event">Полиелейная Утреня и Литургия</td></tr>
          <tr><td class="bg_ortcal_day">Дата не определена</td></tr>
          <tr><td class="bg_ortcal_time">10:00</td><td class="bg_ortcal_event">Литургия</td></tr></table></div>"""
        val result = parse(html)
        assertEquals(listOf("2026-11-08", "2026-11-10"), result.map { it.date })
        assertEquals(listOf("liturgy", "service_block"), result.map { it.kind })
        assertTrue(parse(html + html).isEmpty()) // Two calendars can describe different buildings.
    }

    @Test fun `explicit monthly article splits br and rejects invalid weekday or missing year`() {
        // Sanitized real structure: https://www.svt-nikolai.org/ru/?page_id=6846
        val html = """<div class="page hentry"><h1 class="entry-title">Ноябрь 2026 ГОДА.</h1><div class="entry-content">
          <p>14 Сб. 18.00 Воскресная всенощная<br>15 Вс. 9.30 Неделя 24-я по Пятидесятнице. Исповедь и Литургия.</p>
          <p>23 Пк. 9.00 Св. Мученика Ореста Врача. Литургия.</p></div></div>"""
        val result = parse(html)
        assertEquals(listOf("2026-11-14", "2026-11-15"), result.map { it.date })
        assertTrue(result.all { it.kind == "service_block" })
        assertTrue(parse(html.replace("Ноябрь 2026 ГОДА.", "Ноябрь") + "<footer>©2026</footer>").isEmpty())
        assertTrue(parse(html.replace("14 Сб.", "14 Вс.")).none { it.date == "2026-11-14" })
    }

    @Test fun `ambiguous table venues and unanchored year cannot become published events`() {
        // Real shape and text: https://dyakovo.moseparh.ru/raspisanie/noyabr-2026/
        val dyakovo = """<h1>Ноябрь 2026</h1><table><tr><td>28 ноября<br>суббота</td><td>
          <p>09:00 Часы, Литургия в храме св. Георгия.</p>
          <p>18:00 Всенощное бдение в храме Усекновения</p></td></tr></table>"""
        assertTrue(parse(dyakovo).isEmpty())
        // elohov.ru/raspisanie/ has rowspans and month/day without a scoped explicit year.
        assertTrue(parse("<table><tr><td rowspan='2'>8 ноября воскресенье</td><td>Литургия</td><td>09:00</td></tr></table><footer>2026</footer>").isEmpty())
    }

    @Test fun `cancellation other rites venues and struck text cannot create false clock anchors`() {
        listOf("Литургия отменена", "Литургия не будет", "Литургия перенесена на 10:00",
            "Литургия в приделе", "Литургия (нижний храм)", "Литургия в Казанском храме").forEach {
            assertTrue(it, parse(iso(it)).isEmpty())
        }
        assertTrue(parse(iso("<del>Литургия</del>")).isEmpty())
        listOf("Литургия Преждеосвященных Даров", "Литургия Василия Великого", "Литургия апостола Иакова", "Часы, Литургия").forEach {
            assertEquals(it, "service_block", parse(iso(it)).single().kind)
        }
    }

    @Test fun `links remain same origin bounded and never scrape image or PDF`() {
        val document = Jsoup.parse("""<a href='/schedule/'>Расписание</a><a href='https://other.org/schedule'>Расписание</a>
          <a href='/raspisanie.pdf'>Расписание</a><a href='/raspisanie.jpg'>Расписание</a>
          <a href='/raspisanie-month/'>Расписание</a><a href='/schedule-extra/'>Расписание</a>""", "https://example.org/")
        assertEquals(listOf("https://example.org/schedule/", "https://example.org/raspisanie-month/"), ScheduleHtmlParser.scheduleLinks(document))
        assertEquals("https://example.org/?q=a%26b", ScheduleUrl.normalized("https://example.org/?q=a%26b#fragment"))
        assertNull(ScheduleUrl.normalized("https://127.0.0.1/schedule"))
        assertNull(ScheduleUrl.normalized("https://user:password@example.org/"))
    }
}
