package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.security.MessageDigest
import java.time.LocalDate

class WeekdayCalendarScheduleTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val parish = Parish("osm:way:367166563", "Церковь Николая Чудотворца", "Церковь Николая Чудотворца",
        "", "", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val base = "https://calendar.example/schedule/"
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/weekday-calendar-case099/$name")).use { it.readBytes() }
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun document(html: String) = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, base))
    private fun inspect(html: String, date: LocalDate = today) = ScheduleDocumentParser.inspect(document(html), date, parish)
    private fun row(day: Int = 21, weekday: String = "Сб.", service: String = "Литургия", clock: String = "9-00", feast: String = "Память святого") =
        "<tr><td>$weekday</td><td>$day</td><td>$feast</td><td>$clock</td><td>$service</td></tr>"
    private fun calendar(rows: String, heading: String = "Расписание богослужений в ноябре 2026 года", owner: String = "article") =
        "<main><$owner><h2 class='entry-title'>$heading</h2><div class='entry-content'><table><tbody>$rows</tbody></table></div></$owner></main>"
    private fun expected() = JsonParser.parseString(bytes("EXPECTED-099-CALENDAR-MEMORY-V2.json").toString(Charsets.UTF_8)).asJsonObject
        .getAsJsonArray("expectedEvents").map { it.asJsonObject.let { e ->
            InternetService(e["date"].asString, e["time"].asString, e["title"].asString, e["kind"].asString)
        } }

    @Test fun exactFrozenSourceAndRootPredeclaredOracleAreBytePinned() {
        assertEquals("1e87ee032275e9654be3d4185dc284c28b1d332c3ac866e00214bd7f97bd9a38", sha(bytes("source.html")))
        assertEquals("adf3676ea2c5edae2de86a3fc7359772d509e1aea66aff6afc7db341d7d486f4", sha(bytes("EXPECTED-099.json")))
        assertEquals("c30fccd89f40794709cb9dfe2712b573c36d711bca5444569b92cfb608eff38d", sha(bytes("EXPECTED-099-CALENDAR-MEMORY-V2.json")))
        val historical = JsonParser.parseString(bytes("EXPECTED-099.json").toString(Charsets.UTF_8)).asJsonObject
            .getAsJsonArray("expectedEvents").map { it.asJsonObject.let { e ->
                InternetService(e["date"].asString, e["time"].asString, e["title"].asString, e["kind"].asString)
            } }
        assertEquals(5, historical.size)
        val additional = InternetService("2026-11-18", "09:00", "Молебен", "service_block")
        assertEquals((historical + additional).sortedBy { "${it.date}T${it.time}" }, expected())
        val derived = JsonParser.parseString(bytes("EXPECTED-099-CALENDAR-MEMORY-V2.json").toString(Charsets.UTF_8)).asJsonObject
        val proof = derived.getAsJsonObject("additionalSourceProof")
        assertEquals(11, proof["physicalRow"].asInt)
        val inventory = JsonParser.parseString(bytes("source-rows.json").toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("rows")
        val originalRow11 = inventory.single { it.asJsonObject["physicalRow"].asInt == 11 }.asJsonObject
        assertEquals(originalRow11["literalCells"], proof["literalCells"])
        assertEquals(originalRow11["sourceCellOriginPhysicalRows"], proof["sourceCellOriginPhysicalRows"])
        assertTrue(bytes("source.html").toString(Charsets.UTF_8).contains(proof["exactRawRow"].asString))
        val receipt = JsonParser.parseString(bytes("source-receipt.json").toString(Charsets.UTF_8)).asJsonObject
        assertEquals(receipt["originalSha256"].asString, receipt["writtenSha256"].asString)
        assertEquals(sha(bytes("source.html")), receipt["originalSha256"].asString)
    }

    @Test fun actualSourceReturnsHistoricalFivePlusIndependentlyProvenMolebenAndRetainsOtherOmissions() {
        val html = bytes("source.html").toString(Charsets.UTF_8)
        for (result in listOf(inspect(html), ScheduleHtmlParser.inspect(Jsoup.parse(html, base), today, parish))) {
            assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
            assertEquals(expected(), result.events)
            assertTrue(result.knownPartial)
            assertFalse(result.yearInferred)
            assertFalse(result.safetyConflict)
            assertFalse(result.withdrawsAll)
            assertTrue(result.withdrawnDates.isEmpty())
            assertTrue(result.withdrawnStarts.isEmpty())
            assertFalse(result.events.any { it.date in listOf("2026-11-08", "2026-11-09", "2026-11-22", "2026-11-23", "2026-11-28") })
        }
    }

    @Test fun actualRowValuesAndLocalRowspanOriginsAgreeWithIndependentSourceInventory() {
        val records = document(bytes("source.html").toString(Charsets.UTF_8)).pages.single().lines.mapNotNull { it.htmlCalendarRow }
        val inventory = JsonParser.parseString(bytes("source-rows.json").toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("rows")
        assertEquals(18, records.size)
        for ((index, actual) in records.withIndex()) {
            val expected = inventory[index].asJsonObject
            fun normalize(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
            assertEquals(index + 1, actual.rowId)
            assertEquals(expected.getAsJsonArray("sourceCellOriginPhysicalRows")[0].asInt, actual.groupId)
            assertEquals(expected.getAsJsonArray("literalCells").map { normalize(it.asString) }, actual.cells)
        }
    }

    @Test fun genericVisibleOwnerNeedsNoHostNameOrExactWordPressPath() {
        for (owner in listOf("article", "div class='hentry'")) {
            val html = if (owner == "article") calendar(row()) else calendar(row()).replace("<article>", "<div class='hentry'>").replace("</article>", "</div>")
            val result = inspect(html)
            assertEquals(listOf(InternetService("2026-11-21", "09:00", "Литургия", "liturgy")), result.events)
            assertFalse(result.knownPartial)
        }
    }

    @Test fun missingForeignMultipleAndSiblingYearsNeverSupplyAuthority() {
        val noYear = calendar(row(), "Расписание богослужений в ноябре")
        val foreign = "<main><article><h2>Расписание богослужений в ноябре 2026 года</h2></article>" +
            "<article><h2>Другая статья</h2><table>${row()}</table></article></main>"
        val duplicate = calendar(row()).replace("<div class='entry-content'>", "<h3>Декабрь 2026</h3><div class='entry-content'>")
        val second = calendar(row()).removeSuffix("</main>") + calendar(row(26, "Чт.")).removePrefix("<main>")
        val plainConflict = calendar(row()).replace("<table>", "<p>Декабрь 2026</p><table>")
        val staleArticle = calendar(row()).replace("<table>", "<p>Архив 2019 года</p><table>")
        for (html in listOf(noYear, foreign, duplicate, second, plainConflict, staleArticle)) {
            assertTrue(document(html).pages.single().lines.none { it.htmlCalendarRow?.permitted == true })
            assertTrue(inspect(html).events.isEmpty())
        }
    }

    @Test fun quotedAndHiddenOwnersNeverCreateNewCalendarPermission() {
        val ordinary = calendar(row())
        for (html in listOf("<blockquote>$ordinary</blockquote>", "<q>$ordinary</q>",
            ordinary.replace("<article>", "<article aria-hidden='true'>"),
            ordinary.replace("<article>", "<article style='display: none'>"),
            ordinary.replace("<table>", "<table style='visibility:hidden'>"),
            ordinary.replace("2026 года", "<span aria-hidden='true'>2026</span> года"),
            ordinary.replace("<td>Сб.</td>", "<td><q>Сб.</q></td>"))) {
            assertTrue(document(html).pages.single().lines.none { it.htmlCalendarRow?.permitted == true })
            assertTrue(inspect(html).events.isEmpty())
        }
    }

    @Test fun rowspanMustShareDateOriginAndStayWithinExactFiveColumnGrid() {
        val aligned = "<tr><td rowspan='2'>Сб.</td><td rowspan='2'>21</td><td rowspan='2'>Память святого</td><td>9-00</td><td>Литургия</td></tr>" +
            "<tr><td>16-00</td><td>Вечерня</td></tr>"
        assertEquals(listOf("09:00", "16:00"), inspect(calendar(aligned)).events.map { it.time })
        for (bad in listOf(aligned.replace("<td rowspan='2'>21", "<td>21"),
            aligned.replace("rowspan='2'", "rowspan='3'"),
            aligned.replace("<td>9-00", "<td colspan='2'>9-00"),
            aligned.replace("<td>9-00", "<td><table><tr><td>extra</td></tr></table>9-00"))) {
            assertTrue(document(calendar(bad)).pages.single().lines.none { it.htmlCalendarRow != null })
            assertTrue(inspect(calendar(bad)).events.isEmpty())
        }
    }

    @Test fun wrongWeekdayClosesItsWholeRowspanGroupAndNextDateRecovers() {
        val bad = "<tr><td rowspan='2'>Ср.</td><td rowspan='2'>28</td><td rowspan='2'>Память святого</td><td>9-00</td><td>Литургия</td></tr>" +
            "<tr><td>16-00</td><td>Вечерня</td></tr>"
        val result = inspect(calendar(bad + row(29, "Вс.")))
        assertEquals(listOf("2026-11-29"), result.events.map { it.date })
        assertTrue(result.knownPartial)
    }

    @Test fun unknownServiceAndVenueRejectedRowCannotHideBehindSameDateAndClock() {
        val sameClock = inspect(calendar(row() + row(service = "Бдение")))
        assertEquals(1, sameClock.events.size)
        assertTrue(sameClock.knownPartial)
        val foreign = inspect(calendar(row() + row(service = "Литургия в Сергиевском храме")))
        assertEquals(1, foreign.events.size)
        assertTrue(foreign.knownPartial)
        assertFalse(inspect(calendar(row() + row())).knownPartial)
    }

    @Test fun pastOrOutOfWindowFailuresDoNotEstablishCurrentPartial() {
        val past = inspect(calendar(row(9, "Вс.", "Бдение") + row()))
        assertFalse(past.knownPartial)
        val future = inspect(calendar(row(service = "Бдение")), LocalDate.of(2026, 9, 1))
        assertTrue(future.events.isEmpty())
        assertFalse(future.knownPartial)
    }

    @Test fun secondClockAndRangesNeverBecomeTwoUnprovenServices() {
        for (broken in listOf(row(clock = "9-00 - 10-00"), row(feast = "Память святого 10:00"), row(service = "Литургия 10:00"))) {
            val result = inspect(calendar(broken + row(26, "Чт.")))
            assertEquals(listOf("2026-11-26"), result.events.map { it.date })
            assertTrue(result.knownPartial)
        }
    }

    @Test fun rawCancellationInEveryColumnIsNeverLostByTheCleanServiceTitle() {
        for (column in 0..4) {
            val cells = mutableListOf("Сб.", "21", "Память святого", "9-00", "Литургия")
            cells[column] += " - все богослужения отменены"
            val broken = "<tr>" + cells.joinToString("") { "<td>$it</td>" } + "</tr>"
            val result = inspect(calendar(broken + row(26, "Чт.")))
            assertFalse(result.events.any { it.date == "2026-11-21" })
            assertTrue(result.events.any { it.date == "2026-11-26" } || result.safetyConflict)
            assertTrue(result.withdrawnDates.isNotEmpty() || result.withdrawnStarts.isNotEmpty() || result.safetyConflict)
        }
        val outside = calendar(row()).replace("<div class='entry-content'>", "<h3>Все богослужения отменены</h3><div class='entry-content'>")
        assertTrue(inspect(outside).events.isEmpty())
    }

    @Test fun cancelledOrStruckRowsAreNotMisreportedAsParserLoss() {
        val cancelled = inspect(calendar(row(service = "Литургия отменена") + row(26, "Чт.")))
        assertEquals(listOf("2026-11-26"), cancelled.events.map { it.date })
        assertFalse(cancelled.knownPartial)
        val struck = calendar(row(service = "<s>Бдение</s>") + row(26, "Чт."))
        assertFalse(inspect(struck).knownPartial)
    }

    @Test fun wholeVisibleScopeAndRawRowNegationsNeverBecomePositiveVenuePermission() {
        for (scope in listOf("<h3>Все службы не в Никольском храме</h3>", "<p>Литургии не в Никольском храме</p>", "<p>Литургии н<strong>е</strong> в Никольском храме</p>")) {
            val html = calendar(row()).replace("<table>", "$scope<table>")
            assertTrue(inspect(html).events.isEmpty())
        }
        assertTrue(inspect(calendar(row(service = "Литургия не в Никольском храме"))).events.isEmpty())
    }

    @Test fun exactReviewedScopesNeverAuthorizeAnEventAndGeneralCancellationKeepsOnlyListedStarts() {
        val examples = JsonParser.parseString(bytes("scope-counterexamples.json").toString(Charsets.UTF_8)).asJsonObject
        for (name in listOf("brCancellation", "captionCancellation", "captionExclusiveVenue", "captionConflictingYear")) {
            val parsed = inspect(examples[name].asString)
            assertTrue(name, parsed.events.isEmpty())
            assertFalse(name, parsed.withdrawsAll)
            assertTrue(name, parsed.withdrawnDates.isEmpty())
            if (name in listOf("brCancellation", "captionCancellation")) {
                assertEquals(name, setOf("2026-11-21T09:00"), parsed.withdrawnStarts)
                assertFalse(name, parsed.unresolvedWithdrawal)
            } else assertTrue(name, parsed.withdrawnStarts.isEmpty())
        }
        for (caption in listOf("<strong>Все богослужения</strong> не<br>состоятся", "Богослужения только<br>в <b>Сергиевском</b> храме",
            "Расписание на ноябрь <b>2025</b> года")) {
            val parsed = inspect(calendar(row()).replace("<table>", "<table><caption>$caption</caption>"))
            assertTrue(caption, parsed.events.isEmpty())
        }
    }

    @Test fun qualifiedCaptionCancellationNeverExpandsToEveryCalendarRow() {
        for (caption in listOf("25 ноября службы отменены", "Все службы отменены после 16:00",
            "Ноябрь 2025. Все богослужения отменены")) {
            val parsed = inspect(calendar(row() + row(26, "Чт.")).replace("<table>", "<table><caption>$caption</caption>"))
            assertTrue(caption, parsed.events.isEmpty())
            assertTrue(caption, parsed.withdrawnStarts.isEmpty())
            assertTrue(caption, parsed.withdrawnDates.isEmpty())
            assertFalse(caption, parsed.withdrawsAll)
            assertTrue(caption, parsed.unresolvedWithdrawal && parsed.safetyConflict)
        }
    }

    @Test fun localCaptionCancellationDoesNotWithdrawAnotherArticlesSameDayService() {
        val cancelled = calendar(row()).replace("<table>", "<table><caption>Все богослужения отменены</caption>")
        val combined = cancelled.removeSuffix("</main>") + "<article><h2>Другой раздел</h2><p>2026-11-21 10:00 Литургия</p></article></main>"
        val parsed = inspect(combined)
        assertEquals(listOf(InternetService("2026-11-21", "10:00", "Литургия", "liturgy")), parsed.events)
        assertEquals(setOf("2026-11-21T09:00"), parsed.withdrawnStarts)
        assertTrue(parsed.withdrawnDates.isEmpty())
        assertFalse(parsed.withdrawsAll || parsed.unresolvedWithdrawal)
    }

    @Test fun isolatedActualNovember18MolebenAndKnownNovember21AreBothCompleteRows() {
        val inventory = JsonParser.parseString(bytes("source-rows.json").toString(Charsets.UTF_8)).asJsonObject.getAsJsonArray("rows")
        val cells = inventory.single { it.asJsonObject["physicalRow"].asInt == 11 }.asJsonObject.getAsJsonArray("literalCells").map { it.asString }
        val actualMoleben = "<tr>" + cells.joinToString("") { "<td>$it</td>" } + "</tr>"
        val html = calendar(actualMoleben + row())
        val parsed = inspect(html)
        val both = listOf(InternetService("2026-11-18", "09:00", "Молебен", "service_block"),
            InternetService("2026-11-21", "09:00", "Литургия", "liturgy"))
        assertEquals(both, parsed.events)
        assertFalse(parsed.knownPartial)
        val followingDay = inspect(html, LocalDate.of(2026, 11, 19))
        assertEquals(both, followingDay.events)
        assertFalse(followingDay.knownPartial)
    }

    @Test fun aThirtyOneDayMonthWithFourServicesPerDayFitsTheExistingSixHundredRowBound() {
        val weekdays = listOf("Пн.", "Вт.", "Ср.", "Чт.", "Пт.", "Сб.", "Вс.")
        fun rows(perDay: Int) = (1..31).joinToString("") { day ->
            val date = LocalDate.of(2026, 12, day)
            (0 until perDay).joinToString("") { hour -> row(day, weekdays[date.dayOfWeek.value - 1], clock = "%02d:00".format(8 + hour)) }
        }
        val title = "Расписание богослужений в декабре 2026 года"
        val complete = inspect(calendar(rows(4), title), LocalDate.of(2026, 12, 1))
        assertEquals(124, complete.events.size)
        assertFalse(complete.knownPartial)
        val bounded = inspect(calendar(rows(7), title), LocalDate.of(2026, 12, 1))
        assertEquals(200, bounded.events.size)
        assertTrue(bounded.knownPartial)
    }

    @Test fun footerAndNextArticleCannotBorrowTheLastCalendarDate() {
        val html = calendar(row()).replace("</article>", "<p>10:00 Литургия</p></article><article><p>11:00 Литургия</p></article>")
        assertEquals(listOf("09:00"), inspect(html).events.map { it.time })
    }
}
