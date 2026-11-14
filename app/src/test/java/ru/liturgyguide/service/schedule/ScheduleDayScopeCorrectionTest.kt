package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

class ScheduleDayScopeCorrectionTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val parish = Parish("scope-test", "Никольский храм", "Никольский храм", "Москва", "Адрес", "",
        "https://example.org/", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private fun inspect(html: String, selected: Parish? = null) = ScheduleDocumentParser.inspect(
        HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, "https://example.org/schedule/")), today, selected)
    private fun rows(vararg lines: ScheduleDocumentLine) = ScheduleDocumentParser.inspect(
        ScheduleDocument("https://example.org/schedule.png", "image", listOf(ScheduleDocumentPage(lines.toList()))), today)

    @Test fun actualAttributedWeeklySectionKeepsEveryAuditedRowOnItsPrintedDayWithoutHeaderSuffixes() {
        val html = requireNotNull(javaClass.getResourceAsStream("/schedule/day-scope/blagovest72-weekly-section-2026-11.html"))
            .bufferedReader().use { it.readText() }
        val result = inspect(html)
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertFalse(result.yearInferred)
        fun event(day: Int, time: String, title: String) = InternetService("2026-11-$day", time, "— $title", "service_block")
        val liturgy = "Часы. Божественная Литургия"
        val panikhida = "$liturgy. Панихида"
        val evening = "Вечернее богослужение. Исповедь"
        val chalice = "Акафист Пресвятой Богородице пред иконой «Неупиваемая чаша»"
        // The independent primary-source audit's 21 rows are a required subset, not an output cap.
        val audited = listOf(
            event(14, "08:00", panikhida), event(14, "16:00", evening),
            event(15, "07:00", liturgy), event(15, "08:30", liturgy), event(15, "11:00", chalice),
            event(16, "09:00", "Молебен всем Святым"), event(16, "17:00", "Акафист Святителю Иоанну Митрополиту Тобольскому"),
            event(17, "09:00", "Молебен о болящих"), event(17, "16:00", evening),
            event(18, "08:00", liturgy), event(18, "17:00", "Молебен с акафистом иконы Божией Матери «Всецарица»"),
            event(19, "09:00", "Молебен о воинах"), event(19, "13:00", "Акафист мученику Евгению Боткину"),
            event(19, "17:00", "Акафист Святителю Николаю Чудотворцу"),
            event(20, "09:00", "Благодарственный молебен"), event(20, "16:00", evening),
            event(21, "08:00", panikhida), event(21, "16:00", evening),
            event(22, "07:00", liturgy), event(22, "08:30", liturgy), event(22, "11:00", chalice))
        audited.forEach { expected -> assertTrue("Missing correct source row $expected", expected in result.events) }
        val printedTimes = mapOf(14 to setOf("08:00", "11:00", "12:00", "16:00"),
            15 to setOf("07:00", "08:30", "11:00"), 16 to setOf("09:00", "12:00", "17:00"),
            17 to setOf("09:00", "12:00", "16:00"), 18 to setOf("08:00", "12:00", "17:00"),
            19 to setOf("09:00", "12:00", "13:00", "17:00"), 20 to setOf("09:00", "12:00", "16:00"),
            21 to setOf("08:00", "11:00", "12:00", "16:00"), 22 to setOf("07:00", "08:30", "11:00"))
        result.events.forEach { event ->
            val day = LocalDate.parse(event.date)
            assertEquals("2026-11", event.date.take(7))
            assertTrue(event.time in printedTimes.getValue(day.dayOfMonth))
            assertFalse(Regex("\\d{1,2}, (?:суббота|воскресенье|понедельник|вторник|среда|четверг|пятница)").containsMatchIn(event.title))
        }
    }

    @Test fun commaSpaceAndPipeKeepWeekdayValidationAndContinuation() {
        val result = inspect("""<h1>Ноябрь 2026</h1><p>14, суббота<br>18:00 Вечерня</p>
            <p>15 воскресенье<br>09:00 Божественная<br>Литургия</p><p>16 | понедельник<br>08:00 Молебен</p>""")
        assertEquals(listOf("2026-11-14" to "18:00", "2026-11-15" to "09:00", "2026-11-16" to "08:00"),
            result.events.map { it.date to it.time })
        assertEquals("Божественная Литургия", result.events[1].title)
    }

    @Test fun malformedOrDisagreeingDayFlushesOldServiceAndCannotReviveAnOuterHeadingDate() {
        for (invalid in listOf("31, суббота", "16, вторник", "32, неизвестный", "31.11.2026")) {
            val result = inspect("""<h1>Ноябрь 2026</h1><h2>15 ноября</h2><p>09:00 Литургия</p>
                <h3>$invalid</h3><table><tr><td>10:00 Молебен</td></tr></table>
                <h3>Утро</h3><p>11:00 Часы</p><h2>16, понедельник</h2><p>08:00 Молебен</p>""")
            assertEquals(listOf("2026-11-15" to "09:00", "2026-11-16" to "08:00"), result.events.map { it.date to it.time })
            assertEquals("Литургия", result.events.first().title)
        }
    }

    @Test fun consecutiveRangesSetMonthAndYearButNeverEndpointOrInheritedDay() {
        val result = inspect("""<h1>14 ноября 2026</h1><p>08:00 Литургия</p>
            <h2>С 9 по 15 ноября 2026 года</h2><p>10:00 Молебен</p><h3>Утро</h3><p>11:00 Часы</p>
            <h3>15, воскресенье</h3><p>09:00 Литургия</p>
            <h2>С 16 по 22 ноября 2026 года</h2><table><tr><td>12:00 Панихида</td></tr></table>
            <h3>16, понедельник</h3><p>08:00 Молебен</p>""")
        assertEquals(listOf("2026-11-14" to "08:00", "2026-11-15" to "09:00", "2026-11-16" to "08:00"),
            result.events.map { it.date to it.time })
        for (range in listOf("14-15 ноября 2026", "с 14 по 15 ноября 2026", "14.11.2026-15.11.2026")) {
            assertTrue(inspect("<h1>$range</h1><p>09:00 Литургия</p>").events.isEmpty())
        }
        assertTrue(inspect("<h1>С 14 по 15 ноября</h1><p>15, воскресенье</p><p>09:00 Литургия</p>").events.isEmpty())
    }

    @Test fun anniversaryAndDotClockAreNotDateBoundaries() {
        val result = inspect("""<h1>Ноябрь 2026</h1><h2>15, воскресенье</h2><p>09.00 Литургия</p>
            <p>20-летие освящения</p><p>10:00 Молебен</p>""")
        assertEquals(listOf("09:00", "10:00"), result.events.map { it.time })
        assertTrue(result.events.all { it.date == "2026-11-15" })
        assertTrue(result.events.first().title.contains("20-летие освящения"))
    }

    @Test fun rangeCancellationWithdrawsOnlyTheValidatedRangeInlineOrOnAFollowingHeading() {
        for (notice in listOf("<h2>С 14 по 15 ноября 2026 - все службы отменены</h2>",
            "<h2>С 14 по 15 ноября 2026</h2><h3>Все службы отменены</h3>")) {
            val result = inspect("""<h1>Ноябрь 2026</h1><p>14, суббота 08:00 Литургия</p>
                <p>15, воскресенье 09:00 Литургия</p>$notice<h2>16, понедельник</h2><p>10:00 Молебен</p>""")
            assertEquals(listOf("2026-11-16"), result.events.map { it.date })
            assertEquals(setOf("2026-11-14", "2026-11-15"), result.withdrawnDates)
            assertFalse(result.withdrawsAll)
            assertFalse(result.safetyConflict)
        }
        val newDatedNotice = inspect("""<h1>Ноябрь 2026</h1><h2>С 14 по 15 ноября 2026</h2>
            <h2>16 ноября 2026. Литургия отменена</h2><p>09:00 Литургия</p>
            <h2>17, вторник</h2><p>10:00 Молебен</p>""")
        assertEquals(listOf("2026-11-17"), newDatedNotice.events.map { it.date })
        assertTrue("2026-11-16T09:00" in newDatedNotice.withdrawnStarts)
    }

    @Test fun ambiguousRangeCancellationCannotBecomeMonthWideOrSilentlyDisappear() {
        for (notice in listOf("С 15 по 14 ноября 2026 - все службы отменены", "С 30 ноября по 2 декабря 2026 - все службы отменены",
            "С 14 по 15 ноября 2026 09:00 Литургия отменена", "С 14 по 15 ноября 2026<br>Литургия отменена",
            "С 14 по 15 ноября 2026<br>31, суббота<br>Все службы отменены",
            "С 14 по 15 ноября 2026<br>16, вторник<br>Все службы отменены")) {
            val result = inspect("<h1>Ноябрь 2026</h1><p>16, понедельник 10:00 Молебен</p><p>$notice</p>")
            assertEquals(ScheduleParseDisposition.REJECTED, result.disposition)
            assertTrue(result.safetyConflict)
            assertTrue(result.events.isEmpty())
            assertTrue(result.withdrawnDates.isEmpty())
            assertFalse(result.withdrawsAll)
        }
        val multiple = inspect("""<h1>Ноябрь 2026</h1><p>20, пятница 09:00 Литургия</p>
            <h2>С 14 по 15 ноября 2026 и с 20 по 21 ноября 2026 все службы отменены</h2>""")
        assertEquals(ScheduleParseDisposition.REJECTED, multiple.disposition)
        assertTrue(multiple.safetyConflict)
        assertTrue(multiple.events.isEmpty())
        assertTrue(multiple.withdrawnDates.isEmpty())
    }

    @Test fun commaAndRangeDoNotRelaxVenueOrTemporalConfidence() {
        assertTrue(inspect("<h1>С 14 по 15 ноября 2026</h1><p>15, воскресенье 09:00 Литургия в Казанском храме</p>", parish).events.isEmpty())
        val low = rows(ScheduleDocumentLine("Ноябрь 2026"), ScheduleDocumentLine("15, воскресенье 09:00 Литургия", temporalConfidence = 20))
        assertTrue(low.events.isEmpty())
        val unreliableYear = rows(ScheduleDocumentLine("С 14 по 15 ноября 2026", confidence = 20),
            ScheduleDocumentLine("15, воскресенье 09:00 Литургия"))
        assertTrue(unreliableYear.events.isEmpty())
        val lostRange = rows(ScheduleDocumentLine("Ноябрь 2026", headingLevel = 1),
            ScheduleDocumentLine("15 ноября", headingLevel = 2), ScheduleDocumentLine("09:00 Литургия"),
            ScheduleDocumentLine("С 16 по 22 ноября 2026", confidence = 20), ScheduleDocumentLine("Утро", headingLevel = 3),
            ScheduleDocumentLine("10:00 Молебен"))
        assertEquals(listOf("09:00"), lostRange.events.map { it.time })
    }

    @Test fun actualExtractorMultilineCellAppliesEveryRangeBoundaryInSourceOrder() {
        fun document(afterRange: String) = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse("""<h1>Ноябрь 2026</h1>
            <table><tr><td>15, воскресенье<br>09:00 Литургия<br>
            С 16 по 22 ноября 2026<br>10:00 Молебен$afterRange</td></tr></table>""", "https://example.org/schedule/"))
        val extracted = document("")
        assertTrue(extracted.pages.single().lines.any { "09:00" in it.text && "С 16 по 22" in it.text && '\n' in it.text })
        val onlyBefore = ScheduleDocumentParser.inspect(extracted, today)
        assertEquals(listOf("2026-11-15" to "09:00"), onlyBefore.events.map { it.date to it.time })
        assertEquals("Литургия", onlyBefore.events.single().title)
        val withNewDay = ScheduleDocumentParser.inspect(document("<br>16, понедельник<br>11:00 Панихида"), today)
        assertEquals(listOf("2026-11-15" to "09:00", "2026-11-16" to "11:00"), withNewDay.events.map { it.date to it.time })
    }

    @Test fun multilineCellRangeWithdrawalUsesTheNewRangeAndReportsUnknownScopeWithoutInventedMarkers() {
        val definite = inspect("""<h1>Ноябрь 2026</h1><table><tr><td>15, воскресенье<br>09:00 Литургия<br>
            С 16 по 22 ноября 2026<br>Все службы отменены</td></tr></table>""")
        assertEquals(listOf("2026-11-15" to "09:00"), definite.events.map { it.date to it.time })
        assertEquals((16..22).map { "2026-11-$it" }.toSet(), definite.withdrawnDates)
        assertFalse(definite.withdrawsAll)
        assertFalse(definite.safetyConflict)
        assertFalse(definite.unresolvedWithdrawal)
        val uncertain = inspect("""<h1>Ноябрь 2026</h1><table><tr><td>20, пятница<br>09:00 Литургия<br>
            С 14 по 15 ноября 2026 и с 20 по 21 ноября 2026<br>Все службы отменены</td></tr></table>""")
        assertEquals(ScheduleParseDisposition.REJECTED, uncertain.disposition)
        assertTrue(uncertain.safetyConflict)
        assertTrue(uncertain.unresolvedWithdrawal)
        assertTrue(uncertain.events.isEmpty())
        assertTrue(uncertain.withdrawnDates.isEmpty())
        assertTrue(uncertain.withdrawnStarts.isEmpty())
        assertFalse(uncertain.withdrawsAll)
    }

    @Test fun explicitMonthClearsAnOuterHeadingDayButKeepsPriorServiceAndAcceptsANewPrintedDay() {
        assertTrue(inspect("<h1>14 ноября 2026</h1><h2>Декабрь 2026</h2><p>10:00 Литургия</p>").events.isEmpty())
        val result = inspect("""<h1>14 ноября 2026</h1><p>09:00 Литургия</p>
            <h2>Декабрь 2026</h2><p>10:00 Молебен</p><h3>1, вторник</h3><p>11:00 Панихида</p>""")
        assertEquals(listOf("2026-11-14" to "09:00", "2026-12-01" to "11:00"), result.events.map { it.date to it.time })
        assertEquals(listOf("Литургия", "Панихида"), result.events.map { it.title })
        val multiline = inspect("""<h1>Ноябрь 2026</h1><table><tr><td>14, суббота<br>09:00 Литургия<br>
            Декабрь 2026<br>10:00 Молебен<br>1, вторник<br>11:00 Панихида</td></tr></table>""")
        assertEquals(result.events, multiline.events)
    }

    @Test fun explicitMonthKeepsLegitimateMonthlyWithdrawalInHeadingsAndPhysicalTableLines() {
        val content = listOf(
            """<h1>14 ноября 2026</h1><p>09:00 Литургия</p><h2>Декабрь 2026</h2>
                <h3>1, вторник</h3><p>11:00 Панихида</p><h2>Декабрь 2026</h2><p>Все службы отменены</p>""",
            """<h1>Ноябрь 2026</h1><table><tr><td>14, суббота<br>09:00 Литургия<br>Декабрь 2026<br>
                1, вторник<br>11:00 Панихида<br>Декабрь 2026<br>Все службы отменены</td></tr></table>""")
        for (html in content) {
            val result = inspect(html)
            assertEquals(listOf("2026-11-14" to "09:00"), result.events.map { it.date to it.time })
            assertEquals((1..30).map { "2026-12-%02d".format(it) }.toSet(), result.withdrawnDates)
            assertFalse(result.safetyConflict)
            assertFalse(result.unresolvedWithdrawal)
            assertFalse(result.withdrawsAll)
        }
    }

}
