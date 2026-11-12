package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

class SchedulePublishedHtmlTest {
    private val today = LocalDate.of(2026, 11, 12)
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val home = "https://prorok-iliya.cerkov.ru/"
    private val source = home + "raspisanie-bogosluzhenij/"
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/schedule/voronezh/$name")).readText()
    private fun parish(): Parish {
        val data = JsonParser.parseString(fixture("selected-parish.json")).asJsonObject
        return Parish(data["id"].asString, data["name"].asString, data["name"].asString,
            data["city"].asString, data["address"].asString, "", "http://prorok-iliya.cerkov.ru/",
            "Europe/Moscow", data["sourceUrl"].asString, "", "", "", emptyList(), "", "",
            data["center"].asJsonObject["latitude"].asDouble, data["center"].asJsonObject["longitude"].asDouble)
    }
    private fun document(html: String) = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, source))
    private fun inspect(html: String) = ScheduleDocumentParser.inspect(document(html), today, parish())
    private fun events(html: String) = inspect(html).events

    @Test fun `untouched real pages import seven services through published homepage then survive offline reopening`() = runBlocking {
        val selected = parish()
        assertEquals("osm:way:239273353", selected.id)
        assertEquals("Воронеж, улица Севастьяновский съезд, 26", selected.address)
        val provenance = JsonParser.parseString(fixture("provenance.json")).asJsonObject
        for (entry in provenance["documents"].asJsonArray) {
            val item = entry.asJsonObject
            val bytes = requireNotNull(javaClass.getResource("/schedule/voronezh/${item["fixture"].asString}")).readBytes()
            assertEquals(item["sha256"].asString, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            assertEquals(item["bytes"].asInt, bytes.size)
        }
        val raw = fixture("schedule.html")
        assertTrue(raw.contains("15 Ноября, Воскресение"))
        assertTrue(raw.contains("Храм открыт для посещения с 7.00 до 17.00 каждый день."))
        assertTrue(Jsoup.parse(raw).select("#comments .commentlist").isNotEmpty())
        var stored: String? = null
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = stored
            override fun write(parishId: String, value: String) { stored = value }
        }
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when (url) {
                selected.website -> ScheduleResponse(home, fixture("home.html").toByteArray(), "text/html; charset=UTF-8")
                source -> ScheduleResponse(source, raw.toByteArray(), "text/html; charset=UTF-8")
                else -> error("Unexpected schedule crawl: $url")
            }
        }, cache, siteResolver = ScheduleSiteResolver { error("This test starts with the published configured website") })
        val state = repository.refresh(selected, now)
        assertEquals(InternetScheduleStatus.READY, state.status)
        assertEquals(listOf(selected.website, source), calls)
        val publication = requireNotNull(state.publication)
        assertEquals(source, publication.sourceUrl)
        assertTrue(publication.yearInferred)
        assertEquals(listOf("2026-11-11T07:45", "2026-11-13T07:45", "2026-11-13T16:00",
            "2026-11-14T07:45", "2026-11-14T16:00", "2026-11-15T07:30", "2026-11-15T08:00"),
            publication.events.map { "${it.date}T${it.time}" })
        assertEquals("liturgy", publication.events.single { it.date == "2026-11-13" && it.time == "07:45" }.kind)
        val memorial = publication.events.single { it.date == "2026-11-13" && it.time == "16:00" }
        assertEquals("service_block", memorial.kind)
        assertEquals("Заупокойное Богослужение", memorial.title)
        assertTrue(publication.events.none { it.date == "2026-11-12" })
        assertEquals(state, ScheduleCacheCodec.decode(requireNotNull(stored), selected))
        val offline = InternetScheduleRepository(ScheduleTransport { throw IOException("offline") }, cache)
        assertEquals(publication, offline.cached(selected).publication)
        val unavailable = offline.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, unavailable.status)
        assertEquals(publication, unavailable.publication)
        assertEquals(publication, offline.cached(selected).publication)
    }

    @Test fun `standard discussion containers cannot contribute dates services images or crawl links`() {
        val conversation = """<p>Архив 2015</p><article data-day="2026-11-12"><li>
            <time>08:00</time><span>Литургия</span></li></article>
            <a href="/raspisanie-bogosluzhenij/?replytocom=12">Расписание</a>
            <img src="/schedule-comment.png" alt="Расписание" width="800">"""
        for (attribute in listOf("id='comments'", "class='comments'", "class='commentlist'", "class='comment-list'",
            "id='respond'", "class='comment-respond'")) {
            val html = "<article><h1>Расписание</h1><div $attribute>$conversation</div></article>"
            val page = Jsoup.parse(html, source)
            assertTrue(attribute, document(html).pages.flatMap { it.lines }.none { "2015" in it.text || "08:00" in it.text })
            assertTrue(attribute, inspect(html).events.isEmpty())
            assertTrue(attribute, ScheduleHtmlParser.parse(page, today).isEmpty())
            assertTrue(attribute, ScheduleHtmlParser.inspect(page, today, parish()).events.isEmpty())
            assertTrue(attribute, ScheduleDocumentLinks.find(page).isEmpty())
            assertTrue(attribute, ScheduleHtmlParser.scheduleLinks(page).isEmpty())
        }
    }

    @Test fun `comment cleanup keeps real article and similarly named schedule classes and ids`() {
        val html = """<article><h1>Расписание</h1><div id="schedule-comments-note" class="comments-schedule">
            <p>13 ноября 2026, пятница</p><p>16:00 Заупокойное богослужение</p>
            <a href="/schedule.pdf">Расписание PDF</a></div>
            <div id="comments"><p>2015</p><a href="?replytocom=12">Расписание</a></div></article>
            <nav><a href="/schedule/next">Расписание следующей недели</a></nav>"""
        assertEquals(listOf("16:00"), events(html).map { it.time })
        assertEquals(setOf(home + "schedule.pdf", home + "schedule/next"), ScheduleDocumentLinks.find(Jsoup.parse(html, source)).map { it.url }.toSet())
    }

    @Test fun `old year and cancellation in actual content remain authoritative after comments disappear`() {
        val schedule = """<p>11 ноября, среда</p><p>08:00 Литургия</p>
            <p>13 ноября, пятница</p><p>16:00 Заупокойное богослужение</p>
            <p>15 ноября, Воскресение</p><p>09:00 Литургия</p>"""
        val comments = "<div id='comments'><p>12 ноября 2026 10:00 Литургия</p></div>"
        assertEquals(3, events("<article>$schedule$comments</article>").size)
        assertTrue(events("<article><p>Архив 2015</p>$schedule$comments</article>").isEmpty())
        val cancelled = inspect("<article><h1>Все богослужения отменены</h1>$schedule$comments</article>")
        assertEquals(ScheduleParseDisposition.REJECTED, cancelled.disposition)
        assertTrue(cancelled.events.isEmpty())
        val oneCancelled = inspect("<article>${schedule.replace("Заупокойное богослужение", "Заупокойное богослужение отменено")}$comments</article>")
        assertEquals(listOf("2026-11-11", "2026-11-15"), oneCancelled.events.map { it.date })
        assertTrue("2026-11-13T16:00" in oneCancelled.withdrawnStarts)
    }

    @Test fun `Sunday spelling is validated after the date including separate and wrapped weekday cells`() {
        val dateCells = listOf("<td>15 ноября</td><td>Воскресение</td>", "<td>15 ноября<br>Воскресение</td>",
            "<td>15.11 | Воскресение</td>", "<td>15 Воскресение</td>")
        for (cells in dateCells) {
            val html = "<h1>Ноябрь 2026</h1><table><tr>$cells<td>08:00</td><td>Литургия</td></tr></table>"
            assertEquals(cells, listOf("2026-11-15"), events(html).map { it.date })
            assertTrue(cells, events(html.replace("15", "13")).isEmpty())
        }
        val full = fixture("schedule.html")
        assertTrue(inspect(full).yearInferred)
        assertTrue(events(full.replace("13 Ноября, Пятница", "13 Ноября, Воскресение")).isEmpty())
        assertTrue(events("<p>13 ноября 2026, Воскресение</p><p>08:00 Литургия</p>").isEmpty())
    }

    @Test fun `resurrection feast is preserved and never contributes weekday evidence`() {
        val html = "<p>13 ноября 2026, Воскресение Христово | 08:00 Литургия</p>"
        assertEquals("2026-11-13", events(html).single().date)
        assertTrue(events(html).single().title.contains("Воскресение Христово"))
        val inferred = "<p>11 ноября, среда</p><p>08:00 Литургия</p>" +
            "<p>13 ноября, Воскресение Христово</p><p>08:00 Литургия</p>" +
            "<p>15 ноября, Воскресение</p><p>08:00 Литургия</p>"
        assertTrue(events(inferred).isEmpty())
        assertNull(ScheduleWeekdays.prefix.find("Воскресение Христово"))
        assertNull(ScheduleWeekdays.prefix.find("Воскресение Господне"))
    }

    @Test fun `legacy civil calendar and monthly article also validate the positioned Sunday spelling`() {
        val calendar = """<div class="bg_ortcal_schedule"><table>
            <tr><td class="bg_ortcal_date">15 ноября 2026, Воскресение</td></tr>
            <tr><td class="bg_ortcal_time">16:00</td><td class="bg_ortcal_event">Заупокойное богослужение</td></tr>
            </table></div>"""
        assertEquals("service_block", ScheduleHtmlParser.parse(calendar, source, today).single().kind)
        assertTrue(ScheduleHtmlParser.parse(calendar.replace("15 ноября", "13 ноября"), source, today).isEmpty())
        val article = "<article><h1>Ноябрь 2026</h1><div class='entry-content'><p>15 Воскресение 16:00 Заупокойное богослужение</p></div></article>"
        assertEquals("2026-11-15", ScheduleHtmlParser.parse(article, source, today).single().date)
        assertTrue(ScheduleHtmlParser.parse(article.replace("15 Воскресение", "13 Воскресение"), source, today).isEmpty())
    }

    @Test fun `memorial term does not bypass cancellation venue time or relative descriptions`() {
        val date = "<h2>13 ноября 2026, пятница</h2>"
        val rows = listOf("16:00 Заупокойное богослужение отменено", "<s>16:00 Заупокойное богослужение</s>",
            "16:00 Заупокойное богослужение в Казанском храме", "25:00 Заупокойное богослужение",
            "16:70 Заупокойное богослужение", "После заупокойного богослужения встреча в 16:00",
            "После заупокойного богослужения", "Заупокойное богослужение после литургии", "16:00 Богослужение")
        for (row in rows) assertTrue(row, events(date + "<p>$row</p>").isEmpty())
        assertTrue(events("<p>16:00 Заупокойное богослужение</p>").isEmpty())
        assertTrue(events("<h1>Казанский храм</h1>$date<p>16:00 Заупокойное богослужение</p>").isEmpty())
        val sequence = events(date + "<p>08:00 Литургия</p><p>После литургии - заупокойное богослужение</p>")
        assertEquals(listOf("08:00"), sequence.map { it.time })
    }
}
