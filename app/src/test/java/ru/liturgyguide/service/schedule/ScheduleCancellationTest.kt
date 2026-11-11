package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleCancellationTest {
    private val today = LocalDate.of(2026, 11, 12)
    private val commemorations = listOf(
        "Перенесение мощей святителя Николая",
        "Воспоминание перенесения честных и святых мощей",
        "Перенесение Нерукотворенного Образа Господа Иисуса Христа",
        "Перенесение из Едессы в Константинополь Нерукотворенного Образа Господа Иисуса Христа",
        "Перенесение из Едессы в Константинополь Нерукотворного Образа",
        "Перенесение частицы мощей святителя Николая",
        "Перенесение иконы Божией Матери",
        "Обретение мощей преподобного Сергия"
    )

    @Test fun commemorationHeadingsAndCalendarColumnsKeepTheirRealService() {
        for (commemoration in commemorations) {
            assertFalse(commemoration, ScheduleCancellation.containsMatchIn(commemoration))
            val document = ScheduleDocument("https://example.org/schedule.jpg", "image", listOf(
                ScheduleDocumentPage(listOf(ScheduleDocumentLine(commemoration, headingLevel = 1),
                    ScheduleDocumentLine("12.11.2026 08:30 Литургия. $commemoration")))))
            val result = ScheduleDocumentParser.inspect(document, today)
            assertEquals(commemoration, listOf("08:30"), result.events.map { it.time })
            assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
            assertTrue(result.events.single().title.contains(commemoration))
            assertFalse(result.withdrawsAll)
            val html = Jsoup.parse("<article data-day='2026-11-12'><h2>$commemoration</h2>" +
                "<li><time>08:30</time><span>Литургия</span></li></article>", "https://example.org/schedule/")
            assertEquals(commemoration, listOf("08:30"), ScheduleHtmlParser.inspect(html, today).events.map { it.time })
        }
    }

    @Test fun serviceCancellationsAndReschedulingRemainEffectiveBesideACommemoration() {
        for (notice in listOf("Литургия перенесена на 10:00", "Перенос службы на 10:00",
            "Перенесение литургии на 10:00", "Перенос на другое время", "Служба не будет",
            "Литургия отменена", "Богослужения не совершаются",
            "Перенесение литургии в праздник Нерукотворенного Образа",
            "Перенесение\tлитургии в праздник Нерукотворенного Образа",
            "Перенесение панихиды в день обретения мощей",
            "Перенесение акафиста в день обретения мощей",
            "Перенесение литии в день обретения мощей",
            "Перенесение не будет в день обретения мощей",
            "Перенесение часов в день перенесения мощей",
            "Перенесение на 10:00 в праздник иконы Божией Матери")) {
            val line = "12.11.2026 08:30 Литургия. Перенесение мощей святителя Николая. $notice"
            assertTrue(notice, ScheduleCancellation.containsMatchIn(line))
            val document = ScheduleDocument("https://example.org/schedule.jpg", "image", listOf(
                ScheduleDocumentPage(listOf(ScheduleDocumentLine(line)))))
            val result = ScheduleDocumentParser.inspect(document, today)
            assertEquals(notice, ScheduleParseDisposition.REJECTED, result.disposition)
            assertTrue(notice, result.events.isEmpty())
        }
    }
}
