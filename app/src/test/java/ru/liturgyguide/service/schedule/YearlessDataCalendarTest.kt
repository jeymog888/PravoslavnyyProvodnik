package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.io.File
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.ServiceBookSelection
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth

class YearlessDataCalendarTest {
    private val today = LocalDate.of(2026, 11, 16)
    private val url = "https://calendar.example/schedule/"
    private val parish = Parish("custom:calendar", "Никольский храм", "Никольский храм", "", "", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
    private val weekdays = listOf("ПОНЕДЕЛЬНИК", "ВТОРНИК", "СРЕДА", "ЧЕТВЕРГ", "ПЯТНИЦА", "СУББОТА", "ВОСКРЕСЕНЬЕ")
    private val monthNames = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/yearless-data-calendar-case043/$name")).use { it.readBytes() }
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun inspect(html: String, date: LocalDate = today, source: String = url) = ScheduleHtmlParser.inspect(Jsoup.parse(html, source), date, parish)
    private fun data(month: YearMonth = YearMonth.from(today), entries: (Int) -> String = { "09:00 — Литургия" }): String =
        (1..month.lengthOfMonth()).joinToString("\n") { day ->
            val date = month.atDay(day)
            "${weekdays[date.dayOfWeek.value - 1]},$day ${monthNames[month.monthValue - 1]}\nПамять святого\n${entries(day)}"
        }
    private fun html(data: String = data(), before: String = "", after: String = "", owner: String = "div") =
        "<html><head><title>Расписание</title></head><body><$owner>$before<script type='text/plain' id='unrelated-id'>$data</script>$after</$owner></body></html>"

    @Test fun realSourceAndIndependentOracleStayByteExact() {
        assertEquals("1ae15d6124694ef7a4c7bd86af4586bc6f63fc2a7e398e8ff4954e5dd6092526", sha(bytes("source.html")))
        assertEquals("aac84fe247033d75ec19f568f5943a197add1bd6d77c0750ec42522d91fe389f", sha(bytes("SOURCE-ORACLE.json")))
        val receipt = JsonParser.parseString(bytes("source-receipt.json").toString(Charsets.UTF_8)).asJsonObject
        assertEquals(receipt["savedSanitizedSourceSha256"], receipt["writtenSha256"])
    }

    @Test fun fullActual043UsesOnlyPublishedRowsAndIndependentDatesWithoutAQuotaOnAdmission() {
        val html = bytes("source.html").toString(Charsets.UTF_8)
        val inventory = JsonParser.parseString(bytes("EXPECTED-043.json").toString(Charsets.UTF_8)).asJsonObject["allRows"].asJsonArray
        assertEquals(41, inventory.size())
        for ((date, sourceCount) in listOf(LocalDate.of(2026, 11, 15) to 23, today to 21)) {
            val extracted = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, url), date)
            val rows = extracted.pages.flatMap { it.lines }.mapNotNull { it.htmlYearlessCalendar }
                .filter { it.kind == ScheduleYearlessCalendarLine.Kind.ROW }
            assertEquals(sourceCount, rows.size)
            assertTrue(extracted.pages.flatMap { it.lines }.none { it.text.contains("2026-11-") })
            val result = inspect(html, date)
            assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
            assertTrue(result.yearInferred)
            assertFalse(result.safetyConflict || result.unresolvedWithdrawal || result.withdrawsAll)
            val expected = inventory.map { it.asJsonObject }.filter {
                LocalDate.parse(it["dateIfQualifiedCurrentMonth"].asString) in date.minusDays(1)..date.plusDays(45)
            }.map { InternetService(it["dateIfQualifiedCurrentMonth"].asString, it["time"].asString,
                it["expectedTitle"].asString, it["kind"].asString) }
            assertTrue("Accepted events must be a subset of exact source rows", result.events.all { it in expected })
            assertEquals(result.events.distinct(), result.events)
            val omittedCurrent = expected.filter { LocalDate.parse(it.date) >= date }.any { it !in result.events }
            assertEquals("Actual lawful omissions must be reported, never repaired by changing source", omittedCurrent, result.knownPartial)
            assertEquals(setOf("2026-11-16", "2026-11-17", "2026-11-23", "2026-11-24", "2026-11-30"), result.withdrawnDates)
            assertTrue(result.withdrawnStarts.isEmpty())
            val direct = ScheduleDocumentParser.inspect(extracted, date, parish)
            assertEquals(result, direct)
            System.getenv("FEORIA_043_OUTPUT")?.takeIf { it.isNotBlank() }?.let { path ->
                val output = File(path).canonicalFile
                check(output.isDirectory || output.mkdirs())
                val report = JsonObject().apply {
                    addProperty("status", "ACTUAL_JVM_REPLAY_NOT_ANDROID_OR_LIVE")
                    addProperty("date", date.toString()); addProperty("sourceTimedRowsAllMonth", inventory.size())
                    addProperty("accepted", result.events.size); addProperty("yearInferred", result.yearInferred)
                    addProperty("knownPartial", result.knownPartial)
                    add("rows", JsonArray().also { array -> inventory.map { it.asJsonObject }.forEach { original ->
                        val row = original.deepCopy()
                        val day = LocalDate.parse(row["dateIfQualifiedCurrentMonth"].asString)
                        val event = InternetService(day.toString(), row["time"].asString, row["expectedTitle"].asString, row["kind"].asString)
                        val status = when {
                            day !in date.minusDays(1)..date.plusDays(45) -> "OUTSIDE_IMPORT_WINDOW"
                            event in result.events -> "ACCEPTED_EXACT_SOURCE_ROW"
                            else -> "OMITTED_REQUIRES_ROOT_GUARD_REASON"
                        }
                        row.addProperty("actualParserResult", status)
                        if (status == "OMITTED_REQUIRES_ROOT_GUARD_REASON") row.addProperty("releaseAcceptance", "NOT_ACCEPTED_UNTIL_CONCRETE_REASON")
                        array.add(row)
                    } })
                }
                val file = File(output, "case043-$date.json")
                check(!file.exists()) { "Use a fresh output, preserving previous actual results" }
                file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
            }
        }
    }

    @Test fun supportedActualHoursOccurrenceKeepsItsDateFullTitleAndAllThreeExistingKeys() {
        val result = inspect(bytes("source.html").toString(Charsets.UTF_8))
        val event = result.events.single { it.date == "2026-11-22" && it.time == "06:40" }
        assertEquals("Часы. Литургия. Водосвятный молебен. Заупокойная лития", event.title)
        assertEquals("service_block", event.kind)
        val route = requireNotNull(ServiceBookSelection.resolve(ParishService(event.date, event.time, event.title, event.kind), parish))
        val journey = requireNotNull(route.journeyContext)
        assertEquals(listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY), journey.parts.map { it.worship })
        assertTrue(journey.parts.all { it.date == event.date })
        assertEquals(event.title, journey.title)
        assertNull(ServiceBookSelection.kindFor("Утреня. Литургия. Молебен. Заупокойная лития"))
        assertNull(ServiceBookSelection.kindFor("Молебен с акафистом прп. Сергию Радонежскому"))
    }

    @Test fun genericHostIdAndPageTitleWorkWithoutArticleOrSemanticHeading() {
        val first = inspect(html())
        val renamed = inspect(html().replace("unrelated-id", "random-data"), source = "https://other-parish.example/calendar/")
        assertEquals(first, renamed)
        assertEquals(16, first.events.size)
        assertTrue(first.yearInferred)
        assertFalse(first.knownPartial)
        assertEquals("2026-11-15", first.events.first().date)
        assertEquals("2026-11-30", first.events.last().date)
    }

    @Test fun everyDayAndWeekdayIsRequiredBeforeApplyingTheImportWindow() {
        val full = data()
        val faults = listOf(
            full.substringAfter("ПОНЕДЕЛЬНИК,2 ноября"),
            full.replace("ПОНЕДЕЛЬНИК,2 ноября", "ВОСКРЕСЕНЬЕ,2 ноября"),
            full.replace("ПОНЕДЕЛЬНИК,2 ноября", "ПОНЕДЕЛЬНИК,1 ноября"),
            full.replace("ПОНЕДЕЛЬНИК,30 ноября\nПамять святого\n09:00 — Литургия", ""),
            full.replace("ПОНЕДЕЛЬНИК,2 ноября", "ПОНЕДЕЛЬНИК,2 декабря"))
        for (broken in faults) {
            val result = inspect(html(broken))
            assertTrue(result.events.isEmpty())
            assertTrue(result.withdrawnDates.isEmpty())
            assertFalse(result.yearInferred)
        }
    }

    @Test fun leapMonthAndCivilDayAreTakenFromThePassedParishDateOnly() {
        val month = YearMonth.of(2028, 2)
        val date = LocalDate.of(2028, 2, 15)
        val accepted = inspect(html(data(month)), date)
        assertEquals(16, accepted.events.size)
        assertEquals("2028-02-29", accepted.events.last().date)
        assertTrue(accepted.yearInferred)
        assertTrue(inspect(html(data(month).replace("ВТОРНИК,29 февраля\nПамять святого\n09:00 — Литургия", "")), date).events.isEmpty())
        assertTrue(inspect(html(data(month)), LocalDate.of(2026, 11, 16)).events.isEmpty())
    }

    @Test fun explicitArchiveYearsAndOtherMonthsNeverCreateCurrentNegativeAuthority() {
        val negative = data(entries = { "СЛУЖБ НЕТ" })
        for ((content, source) in listOf(
            html(negative) to "https://calendar.example/archive/2025/",
            html(negative, before = "<h2>Расписание 2025</h2>") to url,
            html(negative.replace("Память святого", "Память святого 2025")) to url,
            html(negative).replace("<title>Расписание</title>", "<title>Расписание на декабрь 2026</title>") to url,
            html(negative.replace("ноября", "декабря")) to url)) {
            val result = inspect(content, source = source)
            assertTrue(result.events.isEmpty())
            assertTrue(result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty())
            assertFalse(result.withdrawsAll || result.unresolvedWithdrawal || result.yearInferred)
        }
    }

    @Test fun exactHistoricalCouncilSpanIsLocalToMemoryAndCannotHideAnotherYear() {
        val memory = "Отцов Поместного Собора Церкви Русской 1917-1918 годов"
        val accepted = inspect(html(data().replace("Память святого", memory)))
        assertTrue(accepted.events.isNotEmpty() && accepted.yearInferred)
        for (foreignYear in listOf("$memory 2025", "Память 1917-1918 годов", memory.replace("1917-1918", "1917–1918"))) {
            val rejected = inspect(html(data().replace("Память святого", foreignYear)))
            assertTrue(rejected.events.isEmpty())
            assertTrue(rejected.withdrawnDates.isEmpty())
        }
        assertTrue(inspect(html(data(entries = { "09:00 — Литургия || $memory" }))).events.isEmpty())
    }

    @Test fun competingDataQuotedHiddenAndNavigationCannotAcquireDates() {
        val normal = html()
        val variants = listOf(
            normal.replace("</body>", "<script type='text/plain'>${data()}</script></body>"),
            normal.replace("<div>", "<div hidden>"), normal.replace("<div>", "<div aria-hidden='true'>"),
            normal.replace("<div>", "<div style='display:none'>"), normal.replace("<div>", "<div style='visibility:hidden'>"),
            normal.replace("<div>", "<div role='navigation'>"), html(owner = "blockquote"), html(owner = "q"),
            html(owner = "nav"), normal.replace("<div>", "<div class='comments'>"))
        for (variant in variants) {
            val result = inspect(variant)
            assertTrue(result.events.isEmpty())
            assertTrue(result.withdrawnDates.isEmpty())
        }
    }

    @Test fun unsafeDataAndExecutableJavaScriptNeverEnterTheReader() {
        for (broken in listOf(data().replace("Память святого", "Память\u202E святого"),
            data().replace("09:00 — Литургия", "<b>09:00 — Литургия</b>"))) assertTrue(inspect(html(broken)).events.isEmpty())
        assertTrue(inspect(html().replace("type='text/plain'", "type='text/javascript'")).events.isEmpty())
        assertTrue(inspect(html().replace("type='text/plain'", "src='https://unknown.example/calendar.js'")).events.isEmpty())
    }

    @Test fun fullRawDetailsKeepForeignVenueNegationAndCancellationAuthority() {
        for (detail in listOf("в Сергиевском храме", "не в Никольском храме", "только в Никольском храме", "в Никольском храме или в другом месте")) {
            val result = inspect(html(data(entries = { day -> "09:00 — Литургия" + if (day == 21) " || $detail" else "" })))
            assertFalse(result.events.any { it.date == "2026-11-21" })
            assertTrue(result.knownPartial)
            assertTrue(result.events.any { it.date == "2026-11-22" })
        }
        val cancelled = inspect(html(data(entries = { day -> "09:00 — Литургия" + if (day == 21) " || богослужение отменено" else "" })))
        assertFalse(cancelled.events.any { it.date == "2026-11-21" })
        assertTrue("2026-11-21T09:00" in cancelled.withdrawnStarts)
        assertFalse(cancelled.knownPartial)
    }

    @Test fun wrongOrAdditionalClockIsAnOmissionNotTwoGuessedEvents() {
        for (row in listOf("25:00 — Литургия", "09:00 — Литургия || начало в 10:00", "09:00 — 10:00 Литургия", "09:00 — Литургия 22 ноября", "09:00 — Литургия 21 декабря")) {
            val result = inspect(html(data(entries = { if (it == 21) row else "09:00 — Литургия" })))
            assertFalse(result.events.any { it.date == "2026-11-21" })
            assertTrue(result.knownPartial)
        }
    }

    @Test fun everyPhysicalRowOwnsItsDiagnosticEvenAtTheSameClockAndPastLossDoesNotWarn() {
        val partial = inspect(html(data(entries = { if (it == 21) "09:00 — Литургия\n09:00 — Бдение" else "09:00 — Литургия" })))
        assertTrue(partial.events.any { it.date == "2026-11-21" })
        assertTrue(partial.knownPartial)
        val past = inspect(html(data(entries = { if (it == 15) "09:00 — Бдение" else "09:00 — Литургия" })))
        assertFalse(past.knownPartial)
    }

    @Test fun exactDayNegativesCannotWithdrawTheMonthOrNeighbouringSourceRows() {
        val result = inspect(html(data(entries = { if (it in setOf(16, 17, 23)) "СЛУЖБ НЕТ" else "09:00 — Литургия" }),
            after = "<p>22 ноября 2026 воскресенье 12:00 Литургия</p>"))
        assertEquals(setOf("2026-11-16", "2026-11-17", "2026-11-23"), result.withdrawnDates)
        assertFalse(result.withdrawsAll || result.unresolvedWithdrawal || result.safetyConflict)
        assertTrue(result.events.any { it.date == "2026-11-22" && it.time == "12:00" })
    }

    @Test fun contradictionConditionAndUnknownNegativeVenueStayUnresolvedWithoutInventedDates() {
        for (marker in listOf("СЛУЖБ НЕТ\n09:00 — Литургия", "СЛУЖБ НЕТ, если храм закрыт", "СЛУЖБ НЕТ в храме во имя")) {
            val result = inspect(html(data(entries = { if (it == 16) marker else "09:00 — Литургия" })))
            assertTrue(result.events.isEmpty())
            assertTrue(result.safetyConflict && result.unresolvedWithdrawal)
            assertTrue(result.withdrawnDates.isEmpty())
            assertFalse(result.withdrawsAll)
        }
    }

    @Test fun positiveForeignNegativeCannotWithdrawTheSelectedParishHistory() {
        val result = inspect(html(data(entries = { "СЛУЖБ НЕТ" }), before = "<h2>В Сергиевском храме</h2>"))
        assertTrue(result.events.isEmpty())
        assertTrue(result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty())
        assertFalse(result.safetyConflict || result.unresolvedWithdrawal || result.withdrawsAll)
    }

    @Test fun damagedCurrentMonthNoticeIsNotLostAsAnOrdinaryUnsupportedFormat() {
        val broken = data(entries = { if (it == 16) "СЛУЖБ НЕТ" else "09:00 — Литургия" })
            .replace("ПОНЕДЕЛЬНИК,2 ноября", "ВОСКРЕСЕНЬЕ,2 ноября")
        val result = inspect(html(broken))
        assertTrue(result.events.isEmpty())
        assertTrue(result.safetyConflict && result.unresolvedWithdrawal)
        assertTrue(result.withdrawnDates.isEmpty())
    }

    @Test fun blockBoundariesAndOuterCancellationKeepTheirOriginalScope() {
        val outside = inspect(html(after = "<p>12:00 Литургия</p>"))
        assertFalse(outside.events.any { it.time == "12:00" })
        val cancelled = inspect(html(before = "<h2>Все богослужения отменены</h2>"))
        assertTrue(cancelled.events.isEmpty())
        assertTrue(cancelled.withdrawsAll || cancelled.withdrawnDates.isNotEmpty() || cancelled.safetyConflict)
        val foreign = inspect(html(before = "<h2>В Сергиевском храме</h2>"))
        assertTrue(foreign.events.isEmpty())
    }
    @Test fun anOuterDatedCancellationKeepsItsDateInsteadOfAcquiringTheWholeDataMonth() {
        val result = inspect(html(before = "<h2>25 ноября 2026 все богослужения отменены</h2>"))
        assertTrue(result.events.isEmpty())
        assertEquals(setOf("2026-11-25"), result.withdrawnDates)
        assertTrue(result.withdrawnStarts.isEmpty())
        assertFalse(result.withdrawsAll || result.yearInferred)
    }

    @Test fun anUnscopedNegativeInTheOwnerCannotDisappearBehindTheDataReader() {
        val result = inspect(html(before = "<p>СЛУЖБ НЕТ</p>"))
        assertTrue(result.events.isEmpty())
        assertTrue(result.safetyConflict && result.unresolvedWithdrawal)
        assertTrue(result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty())
        assertFalse(result.withdrawsAll)
    }

    @Test fun typedNegativeRetainsItsOwnVenueWithoutBecomingTheFollowingDaysVenue() {
        val result = inspect(html(data(entries = { if (it == 16) "СЛУЖБ НЕТ в Сергиевском храме" else "09:00 — Литургия" })))
        assertTrue(result.withdrawnDates.isEmpty())
        assertFalse(result.unresolvedWithdrawal || result.safetyConflict || result.withdrawsAll)
        assertTrue(result.events.any { it.date == "2026-11-17" })
        for (heading in listOf("<h2>В Сергиевском храме</h2>", "<h2>  В  Сергиевском   храме  </h2>")) {
            val foreign = inspect(html(data(entries = { "СЛУЖБ НЕТ" }), before = heading))
            assertTrue(foreign.withdrawnDates.isEmpty())
            assertFalse(foreign.unresolvedWithdrawal || foreign.safetyConflict)
        }
    }

    @Test fun activeOuterArchiveAndOldYearHeadingsQualifyTheNestedDataOwner() {
        for (heading in listOf("Архив богослужений", "Расписание 2025", "Расписание на декабрь 2026")) {
            val document = "<title>Расписание</title><main><h1>$heading</h1><article>" +
                "<script type='text/plain'>${data(entries = { "СЛУЖБ НЕТ" })}</script></article></main>"
            val result = inspect(document)
            assertTrue(result.events.isEmpty())
            assertTrue(result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty())
            assertFalse(result.yearInferred || result.withdrawsAll || result.unresolvedWithdrawal)
        }
        val external = "<title>Расписание</title><h1>Расписание 2025</h1><main><article>" +
            "<script type='text/plain'>${data()}</script></article></main>"
        assertTrue(inspect(external).events.isEmpty())
        val copyright = inspect(html(after = "<div>© 2025 Publisher</div>"))
        assertTrue("An unrelated copyright is not the source publication year", copyright.events.isNotEmpty() && copyright.yearInferred)
    }

    @Test fun aSingleRiteCancellationInMemoryDoesNotBecomeAnAllDayWithdrawal() {
        val narrow = data(entries = { if (it == 21) "09:00 — Молебен" else "09:00 — Литургия" })
            .replace("СУББОТА,21 ноября\nПамять святого", "СУББОТА,21 ноября\nЛитургия отменена")
        val unresolved = inspect(html(narrow))
        assertTrue(unresolved.events.isEmpty())
        assertTrue(unresolved.unresolvedWithdrawal && unresolved.safetyConflict)
        assertTrue(unresolved.withdrawnDates.isEmpty() && unresolved.withdrawnStarts.isEmpty())
        val fullDay = inspect(html(narrow.replace("Литургия отменена", "Все богослужения отменены")))
        assertEquals(setOf("2026-11-21"), fullDay.withdrawnDates)
        assertFalse(fullDay.unresolvedWithdrawal || fullDay.withdrawsAll)
        assertTrue(fullDay.events.any { it.date == "2026-11-22" })
    }

    private fun menu(label: String, qualified: String = "", semantic: Boolean = true): String =
        "<div data-menu='yes'><ul><li><div>$qualified<a href='/other'>$label</a></div></li>" +
            "<li><ul${if (semantic) " role='menu'" else ""}><li><a${if (semantic) " role='menuitem'" else ""} href='/about'>О приходе</a></li></ul></li></ul></div>"

    @Test fun aProvenNeutralMenuKeepsItsWordsWithoutCreatingInheritedVenueAuthority() {
        for (label in listOf("История храма", "Помочь храму", "Поддержать церковь")) {
            val document = Jsoup.parse(html(before = menu(label)), url)
            val extracted = HtmlScheduleDocumentExtractor.fromHtml(document, today)
            assertTrue(extracted.pages.flatMap { it.lines }.any { it.text.contains(label) && it.htmlNavigationLabel == it.text })
            val result = inspect(document.outerHtml())
            assertEquals(16, result.events.size)
            assertTrue(result.yearInferred)
            assertFalse(result.safetyConflict || result.unresolvedWithdrawal || result.knownPartial)
        }
    }

    @Test fun menuOriginNeverHidesTimedCancellationOrQualifiedAuthorityAndAFlagAloneIsInsufficient() {
        for (label in listOf("В Сергиевском храме", "не в Никольском храме", "Все богослужения отменены", "21 ноября 2026 09:00 Литургия")) {
            val extracted = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html(before = menu(label)), url), today)
            assertTrue(extracted.pages.flatMap { it.lines }.any { it.text.contains(label) && it.htmlNavigationLabel == null })
        }
        val outsideAnchor = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html(before = menu("Никольский храм", qualified = "только ")), url), today)
        assertTrue(outsideAnchor.pages.flatMap { it.lines }.any { it.text.contains("только") && it.htmlNavigationLabel == null })
        val flagOnly = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html(before = menu("История храма", semantic = false)), url), today)
        assertTrue(flagOnly.pages.flatMap { it.lines }.any { it.text.contains("История храма") && it.htmlNavigationLabel == null })
        val insideMenu = html().replace("<div>", "<div data-menu='yes'><a href='/one'>О приходе</a><a role='menuitem' href='/two'>Контакты</a>")
        assertTrue("A complete data script inside a proven navigation container is not a publication", inspect(insideMenu).events.isEmpty())
    }

    @Test fun aDamagedFirstHeaderAfterLongMemoryCannotHideALaterReadableNegative() {
        val valid = data(entries = { if (it == 16) "СЛУЖБ НЕТ" else "09:00 — Литургия" })
        val broken = valid.replaceFirst("ВОСКРЕСЕНЬЕ,1 ноября", "Поврежденный заголовок")
            .replaceFirst("Память святого", (1..10).joinToString("\n") { "Память святого" })
        val result = inspect(html(broken))
        assertTrue(result.events.isEmpty())
        assertTrue(result.unresolvedWithdrawal && result.safetyConflict)
        assertTrue(result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty() && !result.withdrawsAll)
        val old = inspect(html(broken + "\n2025"))
        assertTrue(old.events.isEmpty() && old.withdrawnDates.isEmpty())
        assertFalse(old.unresolvedWithdrawal || old.safetyConflict)
    }

    @Test fun typedCalendarPluralGeneralCancellationsStayScopedAndBrokenOnesStayUnresolved() {
        for (notice in listOf("Все службы не состоятся", "Все богослужения не будут совершаться")) {
            val source = data(entries = { if (it == 21) "09:00 — Молебен" else "09:00 — Литургия" })
                .replace("СУББОТА,21 ноября\nПамять святого", "СУББОТА,21 ноября\n" + notice)
            val result = inspect(html(source))
            assertEquals(setOf("2026-11-21"), result.withdrawnDates)
            assertFalse(result.unresolvedWithdrawal || result.withdrawsAll)
            assertTrue(result.events.any { it.date == "2026-11-22" })
            assertTrue(result.events.none { it.date == "2026-11-21" })
            val broken = inspect(html(source.replace("СУББОТА,21 ноября", "НЕВЕРНЫЙ ДЕНЬ,21 ноября")))
            assertTrue(broken.events.isEmpty())
            assertTrue(broken.unresolvedWithdrawal && broken.safetyConflict)
            assertTrue(broken.withdrawnDates.isEmpty() && !broken.withdrawsAll)
        }
    }

}
