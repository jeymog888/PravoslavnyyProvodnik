package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class MyPrihodScheduleTest {
    private val source = "https://hram-sv-filippa.myprihod.ru/raspisanie-bogosluzhenij"
    private val today = LocalDate.of(2026, 11, 12)
    private val now = Instant.parse("2026-11-12T03:00:00Z")
    private val html = requireNotNull(javaClass.getResourceAsStream("/schedule/myprihod/filipp-november-2026.html"))
        .bufferedReader().use { it.readText() }
    private val parish = Parish("filipp", "Храм святителя Филиппа, митрополита Московского", "Храм святителя Филиппа",
        "Москва", "Гиляровского, 35", "", source, "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private fun page() = Jsoup.parse(html, source)
    private fun inspect(document: Document, selected: Parish = parish) = ScheduleHtmlParser.inspect(document, today, selected)
    private fun row(document: Document, day: Int) = document.select(".schedule tr").single {
        it.children().firstOrNull()?.text()?.startsWith("%02d ноября".format(day)) == true ||
            it.children().firstOrNull()?.text()?.startsWith("$day ноября") == true
    }
    private fun entry(document: Document, day: Int, index: Int): Element = row(document, day).children()[1].select("li")[index]

    @Test fun tablePageDoesNotTurnUnlabelledPicturesIntoSchedulesButKeepsPublishedDocuments() {
        assertTrue(ScheduleDocumentLinks.find(page()).none { it.document })
        val document = page().apply { body().append("""
            <a href="/updates.pdf" title="Расписание на ноябрь">Скачать</a>
            <img src="/calendar.png" alt="Расписание богослужений">
            <a href="/poster.jpg"><img src="/preview.jpg" alt="Расписание"></a>
        """) }
        val files = ScheduleDocumentLinks.find(document).filter { it.document }.map { it.url }
        for (name in listOf("updates.pdf", "calendar.png", "poster.jpg")) assertTrue(name, files.any { it.endsWith("/$name") })
        assertTrue(files.none { it.contains("abac699787f4386e0633df1c054351db") })
    }

    @Test fun printedClocksUseTheSameSupportedNotationAsTheScheduleParser() {
        for (clock in listOf("9:00", "9.00", "9-00")) {
            val document = page().apply { entry(this, 13, 0).select("span.time").forEach { it.text(clock) } }
            val result = inspect(document)
            assertEquals(clock, ScheduleParseDisposition.PARSED, result.disposition)
            assertEquals(listOf("09:00", "17:00"), result.events.filter { it.date == "2026-11-13" }.map { it.time })
        }
    }

    @Test fun realMonthUsesOnlySelectedYearAndEachServiceInsteadOfClergyColumnsOrRecurringProse() {
        val result = inspect(page())
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertEquals(32, result.events.size)
        assertFalse(result.yearInferred)
        fun clocks(day: Int) = result.events.filter { it.date == "2026-11-%02d".format(day) }.map { it.time }
        assertEquals(listOf("09:00"), clocks(12))
        assertEquals(listOf("09:00", "17:00"), clocks(13))
        // The undated prose says 10:00 Sundays; the dated table actually prints 09:40.
        assertEquals(listOf("09:00", "09:40", "17:00"), clocks(15))
        assertTrue(result.events.none { it.title.contains("иерей", ignoreCase = true) || it.title.contains("диакон", ignoreCase = true) })
        assertTrue(result.events.none { it.time == "10:00" || it.time == "12:30" || it.time == "13:00" })
        val modifiedArchive = page().apply { select("option:not([selected])").forEach { it.text("январь 2099") } }
        assertEquals(result.events, inspect(modifiedArchive).events)
        assertEquals(result.events, ScheduleDocumentParser.inspect(HtmlScheduleDocumentExtractor.fromHtml(page()), today, parish).events)
    }

    @Test fun selectedMonthPrintedDateWeekdayAndOriginMustAgreeWithoutADeviceYearGuess() {
        val mutations: List<Pair<String, (Document) -> Unit>> = listOf(
            "year disagreement" to { it.select("option[selected]").single().text("ноябрь 2025") },
            "duplicate selected" to { val selected = it.select("option[selected]").single(); selected.after(selected.clone()) },
            "no selected" to { it.select("option[selected]").removeAttr("selected") },
            "foreign month URL" to { it.select("option[selected]").attr("value", source.replace("hram-sv-filippa", "other") + "?current_date=2026-11") },
            "duplicate month parameter" to { it.select("option[selected]").attr("value", "$source?current_date=2026-11&current_date=2026-10") },
            "wrong row month" to { row(it, 12).children()[0].text("12 декабря четверг") },
            "wrong weekday" to { row(it, 12).children()[0].text("12 ноября понедельник") },
            "impossible day" to { row(it, 12).children()[0].text("31 ноября четверг") },
            "duplicate date" to { val day = row(it, 12); day.after(day.clone()) },
            "ambiguous clock" to { val clock = entry(it, 13, 1).select("span.time").single(); clock.after(clock.clone()) }
        )
        for ((label, mutate) in mutations) {
            val result = inspect(page().apply(mutate))
            assertEquals(label, ScheduleParseDisposition.REJECTED, result.disposition)
            assertTrue(label, result.events.isEmpty())
            assertTrue(label, result.safetyConflict)
        }
        assertTrue(inspect(Jsoup.parse(html, "$source?current_date=2026-10")).events.isEmpty())
        assertFalse(MyPrihodScheduleHtml.appliesTo(Jsoup.parse(html, "https://foreign.example/schedule")))
        val stale = page().apply {
            select("option[selected]").single().text("ноябрь 2025").attr("value", "$source?current_date=2025-11")
        }
        assertTrue(inspect(stale).events.isEmpty())
    }

    @Test fun foreignBuildingsAndServiceWithdrawalsRemainScopedWhileAReplacementCelebrantDoesNotCancel() {
        val foreign = page().apply { row(this, 13).children()[3].html("<li></li><li>В Никольском храме</li>") }
        assertEquals(listOf("09:00"), inspect(foreign).events.filter { it.date == "2026-11-13" }.map { it.time })
        val cancelled = page().apply { entry(this, 13, 1).append(" Богослужение отменено") }
        val cancelledResult = inspect(cancelled)
        assertEquals(listOf("09:00"), cancelledResult.events.filter { it.date == "2026-11-13" }.map { it.time })
        assertTrue("A cancelled start must not be revived from an old cache", "2026-11-13T17:00" in cancelledResult.withdrawnStarts)
        val struck = page().apply { val service = entry(this, 13, 1); service.html("<s>${service.html()}</s>") }
        assertTrue("2026-11-13T17:00" in inspect(struck).withdrawnStarts)
        val replaced = page().apply { row(this, 13).children()[2].html("<ul><li><s>иерей Игорь</s></li><li>иерей Игорь не служит</li></ul>") }
        assertEquals(inspect(page()).events, inspect(replaced).events)
        val clergyNotice = page().apply { row(this, 13).children()[2].html("<ul><li>Литургия отменена</li><li>иерей Игорь</li></ul>") }
        assertTrue("2026-11-13T09:00" in inspect(clergyNotice).withdrawnStarts)
        val global = page().apply { body().prepend("<h1>Все богослужения отменены</h1>") }
        assertTrue(inspect(global).withdrawsAll)
        assertTrue(inspect(global).events.isEmpty())
    }

    @Test fun repositoryImportsThePublishedMonthReopensOfflineAndNeverRestoresARejectedOlderMonth() = runBlocking {
        val values = mutableMapOf<String, String>()
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = values[parishId]
            override fun write(parishId: String, value: String) { values[parishId] = value }
        }
        var current = html
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            if (url != source) throw IOException("Only the exact configured platform publication is available")
            ScheduleResponse(url, current.toByteArray(), "text/html; charset=UTF-8")
        }, cache)
        val ready = repository.refresh(parish, now, force = true)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(source, ready.publication?.sourceUrl)
        assertEquals(32, ready.publication?.events?.size)
        assertEquals(listOf("09:00", "17:00"), ready.publication!!.forDate(today.plusDays(1)).map { it.time })
        val reopened = InternetScheduleRepository(ScheduleTransport { throw IOException("Offline") }, cache)
        assertEquals(ready, reopened.cached(parish))
        assertTrue(calls.all { it == source })
        current = page().apply { select("option[selected]").single().text("ноябрь 2025") }.outerHtml()
        val rejected = repository.refresh(parish, now.plusSeconds(60), force = true)
        assertNull(rejected.publication)
        assertTrue(rejected.publicationRejected)
        assertNull(reopened.cached(parish).publication)
    }

    @Test fun scopedNotesWithdrawTheirOwnStartWhileUnmappedNotesAndUnparsedTextAreConflicts() {
        for (note in listOf("Отменено", "17:00 отменено", "17:00отменено")) {
            val document = page().apply { row(this, 13).children()[3].html("<li></li><li>$note</li>") }
            val result = inspect(document)
            assertEquals(note, listOf("09:00"), result.events.filter { it.date == "2026-11-13" }.map { it.time })
            assertTrue(note, "2026-11-13T17:00" in result.withdrawnStarts)
            assertFalse(note, "2026-11-13T09:00" in result.withdrawnStarts)
        }
        val conflicts: List<Pair<String, (Document) -> Unit>> = listOf(
            "unmapped cancellation note" to { row(it, 13).children()[3].html("<li>Отменено</li>") },
            "service-column global cancellation" to { row(it, 13).children()[1].prepend("<p>Все богослужения отменены</p>") },
            "notes-column foreign venue" to { row(it, 13).children()[3].prepend("<p>В Никольском храме</p>") },
            "clergy-column unparsed service withdrawal" to { row(it, 13).children()[2].prepend("<p>Богослужения отменены</p>") }
        )
        for ((label, mutate) in conflicts) {
            val result = inspect(page().also(mutate))
            assertEquals(label, ScheduleParseDisposition.REJECTED, result.disposition)
            assertTrue(label, result.events.isEmpty())
            assertTrue(label, result.safetyConflict)
        }
    }
}
