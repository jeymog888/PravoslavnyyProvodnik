package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class StructuredCalendarScheduleTest {
    private val website = "https://monastery.ru/bogosluzhenie/raspisanie-bogosluzheniy/"
    private val dated = website + "?day=09.11.2026"
    private val today = LocalDate.of(2026, 11, 11)
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val fixture = requireNotNull(javaClass.getResourceAsStream("/schedule/sretensky-week-2026-11-09.html"))
        .bufferedReader().use { it.readText() }
    private val old = parish("sretensky_old", "Сретенский монастырь. Храм Сретения Владимирской иконы Божией Матери")
    private val newer = parish("sretensky_new", "Сретенский монастырь. Храм Воскресения Христова и Новомучеников и Исповедников Церкви Русской")
    private fun parish(id: String, name: String) = Parish(id, name, name, "Москва", "Большая Лубянка, 19", "",
        website, "Europe/Moscow", dated, "Прежнее расписание", "2026-11-09", "2026-11-15",
        listOf(ParishService("2026-11-11", "05:00", "Старая запись", "liturgy")), "", "")
    private fun inspect(html: String, selected: Parish = old, source: String = website) =
        ScheduleHtmlParser.inspect(Jsoup.parse(html, source), today, selected)
    private fun dayCard() = requireNotNull(Jsoup.parse(fixture, website).getElementById("11112026")).outerHtml()
    private fun page(card: String) = "<main><h1>Расписание богослужений</h1>$card</main>"

    @Test fun `live weekly cards keep civil dates and separate both named buildings`() {
        for (source in listOf(website, dated)) {
            val oldResult = inspect(fixture, old, source)
            val newResult = inspect(fixture, newer, source)
            assertEquals(ScheduleParseDisposition.PARSED, oldResult.disposition)
            assertEquals(ScheduleParseDisposition.PARSED, newResult.disposition)
            assertEquals(11, oldResult.events.size)
            assertEquals(6, newResult.events.size)
            assertEquals(listOf("06:45", "08:00", "18:00"), oldResult.events.filter { it.date == today.toString() }.map { it.time })
            assertTrue(newResult.events.none { it.date == today.toString() })
            assertEquals(listOf("07:00"), oldResult.events.filter { it.date == "2026-11-15" }.map { it.time })
            assertEquals(listOf("10:00", "13:00", "18:00"), newResult.events.filter { it.date == "2026-11-15" }.map { it.time })
            assertFalse(oldResult.yearInferred)
            assertFalse(newResult.yearInferred)
        }
    }

    @Test fun `card id calendar link civil day and weekday must all agree`() {
        val card = dayCard()
        val invalid = listOf(
            card.replace("id=\"11112026\"", "id=\"12112026\""),
            card.replace("?day=11.11.2026", "?day=12.11.2026"),
            card.replace("Среда,", "Четверг,"),
            card.replace("/calendar/?day=11.11.2026", "https://other.example/calendar/?day=11.11.2026"),
            card.replace("?day=11.11.2026", "?day=11.11.2026&day=12.11.2026")
        )
        for (html in invalid) assertTrue(inspect(page(html)).events.isEmpty())
        val oldStyle = card.replace("29 октября ст. ст.", "15 ноября 2026 ст. ст.")
        assertEquals(setOf("2026-11-11"), inspect(page(oldStyle)).events.map { it.date }.toSet())
        assertEquals(3, inspect(page(oldStyle)).events.size)
    }

    @Test fun `stale archive foreign venue and explicit cancellations remain rejected`() {
        // 11 Nov 2015 was also Wednesday: a matching weekday must never replace its full year.
        assertTrue(inspect(page(dayCard().replace("2026", "2015")), source = website + "?day=09.11.2015").events.isEmpty())
        val otherBuilding = inspect(page(dayCard()), newer)
        assertEquals(ScheduleParseDisposition.REJECTED, otherBuilding.disposition)
        assertTrue(otherBuilding.events.isEmpty())
        val cancelled = inspect(page(dayCard().replace("Вечернее богослужение", "Вечернее богослужение отменено")))
        assertEquals(listOf("06:45", "08:00"), cancelled.events.map { it.time })
        assertEquals(setOf("2026-11-11T18:00"), cancelled.withdrawnStarts)
        val cardNotice = Jsoup.parse(page(dayCard()), website)
        requireNotNull(cardNotice.selectFirst(".holiday a")).text("Все богослужения отменены")
        val withdrawnDay = inspect(cardNotice.outerHtml())
        assertTrue(withdrawnDay.events.isEmpty())
        assertEquals(setOf("2026-11-11"), withdrawnDay.withdrawnDates)
        val global = inspect("<main><h1>Все богослужения отменены</h1>${dayCard()}</main>")
        assertTrue(global.events.isEmpty())
        assertTrue(global.withdrawsAll)
    }

    @Test fun `a replacement celebrant does not cancel the service but an explicit service notice does`() {
        fun withClergyNotice(notice: String) = Jsoup.parse(page(dayCard()), website).apply {
            val row = select(".sch-day-row").single { it.selectFirst(".time")?.text() == "08:00" }
            requireNotNull(row.selectFirst(".name")).html(notice)
        }.outerHtml()
        for (notice in listOf("<s>Иеромонах Никон</s>", "Иеромонах Никон не служит")) {
            val result = inspect(withClergyNotice(notice))
            assertEquals(listOf("06:45", "08:00", "18:00"), result.events.map { it.time })
            assertTrue(result.withdrawnStarts.isEmpty())
        }
        val cancelled = inspect(withClergyNotice("Литургия отменена"))
        assertEquals(listOf("06:45", "18:00"), cancelled.events.map { it.time })
        assertEquals(setOf("2026-11-11T08:00"), cancelled.withdrawnStarts)
    }

    @Test fun `struck services withdraw exact starts and cannot revive an older publication`() = runBlocking {
        val cancelledPage = Jsoup.parse(page(dayCard()), website).apply {
            select(".sch-day-row .time").forEach { it.html("<s>${it.text()}</s>") }
        }.outerHtml()
        val parsed = inspect(cancelledPage)
        assertEquals(ScheduleParseDisposition.REJECTED, parsed.disposition)
        assertTrue(parsed.events.isEmpty())
        assertEquals(setOf("2026-11-11T06:45", "2026-11-11T08:00", "2026-11-11T18:00"), parsed.withdrawnStarts)
        val values = mutableMapOf<String, String>()
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = values[parishId]
            override fun write(parishId: String, value: String) { values[parishId] = value }
        }
        var html = page(dayCard())
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            if (url != website) throw IOException("Only selected source is available")
            ScheduleResponse(url, html.toByteArray(), "text/html; charset=UTF-8")
        }, cache)
        assertEquals(3, repository.refresh(old, now, force = true).publication!!.events.size)
        html = cancelledPage
        val withdrawn = repository.refresh(old, now.plusSeconds(60), force = true)
        assertTrue(withdrawn.publicationRejected)
        assertNull(withdrawn.publication)
        assertTrue(old.withInternetSchedule(withdrawn, now.plusSeconds(60)).schedule.isEmpty())
        assertTrue(old.withInternetSchedule(repository.cached(old), now.plusSeconds(60)).schedule.isEmpty())
    }

    @Test fun `fresh import repairs previous rejection replaces stale bundle and survives cache reopen`() = runBlocking {
        for (selected in listOf(old, newer)) for (source in listOf(website, dated)) {
            val values = mutableMapOf(selected.id to ScheduleCacheCodec.encode(InternetScheduleState(
                selected.id, InternetScheduleStatus.UNSUPPORTED, website, dated, now.minusSeconds(30).toString(),
                configuredWebsite = website, publicationRejected = true)))
            val cache = object : ScheduleCacheStore {
                override fun read(parishId: String) = values[parishId]
                override fun write(parishId: String, value: String) { values[parishId] = value }
            }
            val repository = InternetScheduleRepository(ScheduleTransport { url ->
                if (url != source) throw IOException("Only the exact fetched fixture is available")
                ScheduleResponse(url, fixture.toByteArray(), "text/html; charset=UTF-8")
            }, cache)
            val state = repository.refresh(selected, now, force = true,
                sourceOverride = source.takeIf { it != website })
            assertEquals(InternetScheduleStatus.READY, state.status)
            assertFalse(state.publicationRejected)
            val displayed = selected.withInternetSchedule(state, now)
            assertTrue(displayed.schedule.isNotEmpty())
            assertTrue(displayed.schedule.none { it.time == "05:00" })
            assertEquals(source, displayed.sourceUrl)
            if (selected.id == old.id) assertTrue(displayed.schedule.any { it.date == "2026-11-11" && it.time == "18:00" })
            else assertEquals(listOf("10:00", "13:00", "18:00"), displayed.schedule.filter { it.date == "2026-11-15" }.map { it.time })
            val reopened = InternetScheduleRepository(ScheduleTransport { throw IOException("Offline") }, cache)
            assertEquals(state, reopened.cached(selected))
        }
    }
}
