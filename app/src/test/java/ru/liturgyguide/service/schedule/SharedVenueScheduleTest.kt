package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.security.MessageDigest
import java.time.LocalDate

class SharedVenueScheduleTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val target = Parish("osm:way:171771405", "Церковь Успения Пресвятой Богородицы",
        "Церковь Успения Пресвятой Богородицы", "Обухово", "Обухово, 71", "", "https://hram-obuhovo.ru/",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val a = "Успенском храме д. Обухово"
    private val b = "Сергиевском храме д. Татищево"
    private val joint = "09:00 Часы. Литургия (в $a и $b)."
    private val foreign = "08:30 Исповедь (в $b)."
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/shared-venue-case039/$name"))
        .use { it.readBytes() }
    private fun document(html: String) = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, "https://example.org/schedule/"))
    private fun inspect(html: String, parish: Parish = target) = ScheduleDocumentParser.inspect(document(html), today, parish)
    private fun day(rows: String, heading: String = "22 ноября, воскресенье") =
        "<main><h1>Ноябрь 2026</h1><section><h3>$heading</h3><p>Неделя по Пятидесятнице</p><ul>$rows</ul></section></main>"
    private fun rows(first: String = foreign, second: String = joint) = "<li>$first</li><li>$second</li>"
    private fun events(name: String, field: String) = JsonParser.parseString(fixture(name).toString(Charsets.UTF_8))
        .asJsonObject[field].asJsonArray.map { row -> row.asJsonObject.let {
            InternetService(it["date"].asString, it["time"].asString, it["title"].asString, it["kind"].asString)
        } }

    @Test fun capturedBytesArePinnedIncludingFullRootScheduleCalendarAndPredeclaredOracle() {
        val pins = mapOf(
            "0000.body" to "966a513f330f5f0a81690eba519ba43aa23b103b26e47a3dd83734d9095a807e",
            "0001.body" to "e7cc359eed9ef7b9476db715e167c4bf0bcd981aabc2881a7731fd7460868e0b",
            "0002.body" to "c60dd78f85568eedb4142e77215238b47120f2caa1d40c64308d83feb654c785",
            "calls.json" to "e541d18fd63542b56deef32a54b0be13418e0071b5db3e28935955919887ec34",
            "baseline-source-cache.json" to "31e30e826c45cf66eeb303c1bc1101f87c896f0eee5de81a4e00ecd74f5b9150",
            "EXPECTED-039.json" to "f1dd0cde0af9b2b7c5e240f855e90d5b55ce3084cf286eedc2fda26b3b25858d")
        pins.forEach { (name, sha) ->
            assertEquals(name, sha, MessageDigest.getInstance("SHA-256").digest(fixture(name)).joinToString("") { "%02x".format(it) })
        }
    }

    @Test fun capturedPublicationEqualsAllTwelveOriginalFieldsPlusTheThreeExactJointRows() {
        val page = Jsoup.parse(fixture("0001.body").toString(Charsets.UTF_8), "https://hram-obuhovo.ru/raspisanie/")
        val parsed = ScheduleHtmlParser.inspect(page, today, target)
        val oracle = events("EXPECTED-039.json", "expectedEvents")
        assertEquals(ScheduleParseDisposition.PARSED, parsed.disposition)
        assertFalse(parsed.yearInferred)
        assertEquals(oracle, parsed.events)
        val baseline = JsonParser.parseString(fixture("baseline-source-cache.json").toString(Charsets.UTF_8))
            .asJsonObject["publication"].asJsonObject["events"]
        assertEquals(baseline, JsonParser.parseString(fixture("EXPECTED-039.json").toString(Charsets.UTF_8)).asJsonObject["baselineEvents"])
        assertEquals(12, parsed.events.filterNot { it in events("EXPECTED-039.json", "expectedAdditions") }.size)
        for (date in listOf("2026-11-15", "2026-11-22", "2026-11-29")) {
            assertFalse(parsed.events.any { it.date == date && it.time == "08:30" })
        }
        assertEquals("Акафист Архистратигу Михаилу", parsed.events.first { it.date >= "2026-11-20" }.title)
    }

    @Test fun capturedDayWitnessComesFromTheActualSiblingHeadingAndList() {
        val page = Jsoup.parse(fixture("0001.body").toString(Charsets.UTF_8), "https://hram-obuhovo.ru/raspisanie/")
        assertEquals(30, page.select(".events-list > ul > li").size)
        val lines = HtmlScheduleDocumentExtractor.fromHtml(page).pages.single().lines
        for (day in listOf("15.11", "22.11", "29.11")) {
            val heading = lines.indexOfFirst { it.text.startsWith("$day ") }
            assertTrue(day, heading >= 0)
            val id = lines[heading].htmlDayContainer
            assertNotNull(day, id)
            assertEquals(lines[heading].text, lines[heading].htmlDayHeading)
            val members = lines.filter { it.htmlDayContainer == id }
            assertTrue(day, members.any { it.text == foreign && it.htmlEventRow == foreign })
            assertTrue(day, members.any { it.text.startsWith("09:00 Часы. Литургия") && it.text.contains("$a и $b") })
        }
        // Change only the parser's query window. The original complete month bytes remain intact.
        val wholeMonth = ScheduleHtmlParser.inspect(page, LocalDate.of(2026, 11, 2), target).events
        assertEquals(23, wholeMonth.size)
        assertTrue(wholeMonth.containsAll(events("EXPECTED-039.json", "expectedEvents")))
        assertEquals(setOf("2026-11-01", "2026-11-03", "2026-11-04", "2026-11-06", "2026-11-07", "2026-11-08"),
            wholeMonth.filter { it.date < "2026-11-14" }.map { it.date }.toSet())
        assertFalse(wholeMonth.any { it.date == "2026-11-07" && it.time == "14:30" })
    }

    @Test fun targetCanBeFirstOrLastButBuildingsNeverBecomeAliases() {
        for (location in listOf("$a и $b", "$b и $a")) {
            val raw = "09:00 Часы. Литургия (в $location)."
            assertNotNull(raw, ScheduleVenue.sharedEventVenue(raw, target))
            assertFalse(ScheduleVenue.matches(ScheduleVenue.inlineLabel(raw), target))
            val parsed = inspect(day(rows(second = raw))).events.single()
            assertEquals("2026-11-22", parsed.date)
            assertEquals("09:00", parsed.time)
            assertEquals("service_block", parsed.kind)
            assertEquals(raw.removePrefix("09:00 ").removeSuffix("."), parsed.title)
        }
        val ordinary = inspect(day("<li>09:00 Литургия</li><li>10:00 Литургия (в $a).</li>")).events
        assertEquals(listOf("09:00", "10:00"), ordinary.map { it.time })
        assertTrue(inspect(day("<li>$foreign</li>")).events.isEmpty())
    }

    @Test fun restrictionsBeforeAndAfterTheCompleteCoordinationNeverAuthorizeIt() {
        for (restriction in listOf("не", "нет", "ни", "без", "только", "лишь", "кроме", "вместо", "перенесено", "отменено",
            "запись", "трансляция", "воспоминание", "ранее", "историческое упоминание", "Рассказ о")) {
            for (raw in listOf("$restriction $joint", "$joint $restriction")) {
                assertNull(raw, ScheduleVenue.sharedEventVenue(raw, target))
                assertTrue(raw, inspect(day("<li>$raw</li>")).events.isEmpty())
            }
        }
        for (raw in listOf("09:00 Литургия только в $b", "09:00 Литургия в $b вместо $a",
            "09:00 Литургия кроме $a", "09:00 Литургия перенесена из $a в $b", "09:00 Литургия отменена в $a")) {
            assertNull(raw, ScheduleVenue.sharedEventVenue(raw, target))
        }
    }

    @Test fun alternativesIncompleteMembersAssociatedPlacesAndBrokenBracketsStayRejected() {
        val malformed = listOf(
            joint.replace(" и ", " или "), joint.replace(" и ", " либо "),
            joint.replace(b, "храме д. Татищево"), joint.replace(b, "Большом храме д. Татищево"),
            joint.replace(b, "Сергиевском храме"), joint.replace(b, "Сергиевском храме д."),
            joint.replace(b, "$b рядом с Обухово"), joint.replace(b, "$b при монастыре"),
            joint.replace(" и ", " / "), joint.replace(" и ", " | "),
            joint.replace(").", ""), joint.replace("(в", "((в"), "$joint и храме", "$joint; только Обухово",
            "09:00 Литургия в $a (в $a и $b).")
        assertTrue(inspect(day("<li>$joint<p>Историческое упоминание</p></li>")).events.isEmpty())
        malformed.forEach { raw ->
            assertNull(raw, ScheduleVenue.sharedEventVenue(raw, target))
            assertTrue(raw, inspect(day("<li>$raw</li>")).events.isEmpty())
        }
    }

    @Test fun selectedClauseMustMatchLocalityBuildingTypeAndRelativeQualifiers() {
        for (parish in listOf(target.copy(city = "Татищево", address = "Татищево, 71"),
            target.copy(name = "Успенская часовня", shortName = "Успенская часовня"),
            target.copy(name = "Успенский придел", shortName = "Успенский придел"))) {
            assertNull(ScheduleVenue.sharedEventVenue(joint, parish))
            assertTrue(inspect(day(rows()), parish).events.isEmpty())
        }
        assertNull(ScheduleVenue.sharedEventVenue(joint.replace(a, "Нижнем Успенском храме д. Обухово"), target))
        assertNull(ScheduleVenue.sharedEventVenue(joint.replace(a, "Успенском храме д. Иное"), target))
        assertNull(ScheduleVenue.sharedEventVenue(joint.replace("Обухово", "Обуховка"), target))
        assertNull(ScheduleVenue.sharedEventVenue(joint, target.copy(city = "Обуховка", address = "Обухово, 71")))
        assertNull(ScheduleVenue.sharedEventVenue(joint, target.copy(city = "Обуховка", address = "Обуховка, 71")))
        val multi = target.copy(city = "Новая Деревня", address = "Новая Деревня, 71")
        assertNotNull(ScheduleVenue.sharedEventVenue(joint.replace("Обухово", "Новая Деревня"), multi))
        assertNull(ScheduleVenue.sharedEventVenue(joint.replace("Обухово", "Новая Деревняково"), multi))
        assertNull(ScheduleVenue.sharedEventVenue(joint, target.copy(city = "", address = "Обухово, 71")))
        assertNull(ScheduleVenue.sharedEventVenue(joint, target.copy(city = "", address = "улица Обухово, 71")))
    }

    @Test fun severalClocksAndRespectiveOrUnreliableOcrDoNotReceiveNewAuthority() {
        for (raw in listOf("08:30 Исповедь. $joint", "$joint 10:00 Литургия", "$joint соответственно",
            joint.replace("09:00", "09:00-10:00"))) {
            assertNull(raw, ScheduleVenue.sharedEventVenue(raw, target))
            assertTrue(raw, inspect(day("<li>$raw</li>")).events.isEmpty())
        }
        val readable = document(day("<li>$joint</li>"))
        assertTrue(ScheduleDocumentParser.inspect(readable.copy(format = "pdf"), today, target).events.isEmpty())
        val weak = readable.copy(pages = readable.pages.map { page -> page.copy(lines = page.lines.map {
            if (it.text == joint) it.copy(confidence = 60, temporalConfidence = 60) else it
        }) })
        assertTrue(ScheduleDocumentParser.inspect(weak, today, target).events.isEmpty())
    }

    @Test fun foreignSiblingCannotBorrowADateAcrossAListOrTableOrUnknownBoundary() {
        val cases = listOf(
            "<h1>Ноябрь 2026</h1><h3>22 ноября</h3><div><ul>${rows()}</ul></div>",
            "<h1>Ноябрь 2026</h1><h3>22 ноября</h3><ul><li>$foreign</li></ul><ul><li>$joint</li></ul>",
            "<h1>Ноябрь 2026</h1><h3>22 ноября</h3><table><tr><td>$foreign</td></tr></table><ul><li>$joint</li></ul>",
            day(rows(first = "23 ноября $foreign")),
            day(rows(), "31 ноября"), day(rows(), "22-23 ноября"), day(rows(), "<del>22 ноября</del>"),
            day(rows()).replace("<p>Неделя по Пятидесятнице</p>", "<p>31 ноября</p>"),
            day(rows()).replace("<p>Неделя по Пятидесятнице</p>", "<p>22-23 ноября</p>"),
            day(rows()).replace("Ноябрь 2026", "Ноябрь"))
        cases.forEach { html -> assertTrue(html, inspect(html).events.isEmpty()) }
        val detached = document(cases.first()).pages.single().lines
        assertNull(detached.first { it.text == foreign }.htmlDayContainer)
        assertNull(detached.first { it.text == joint }.htmlDayContainer)
    }

    @Test fun foreignAndExclusiveParentScopesAndAllWithdrawalNoticesRemainEffective() {
        val list = "<h3>22 ноября</h3><ul>${rows()}</ul>"
        for (parent in listOf("Сергиевский храм деревня Татищево", "Все службы только в Сергиевском храме деревня Татищево",
            "Все службы отменены")) {
            assertTrue(parent, inspect("<main><h1>Ноябрь 2026</h1><h2>$parent</h2>$list</main>").events.isEmpty())
        }
        val cancelled = inspect(day(rows()).replace("<p>Неделя по Пятидесятнице</p>", "<p>Все службы отменены</p>"))
        assertTrue(cancelled.events.isEmpty())
        assertEquals(setOf("2026-11-22"), cancelled.withdrawnDates)
        assertTrue(inspect(day("<li>${joint.replace("Литургия", "Литургия отменена")}</li>")).events.isEmpty())
        assertTrue(inspect(day("<li><del>$joint</del></li>")).events.isEmpty())
        val later = inspect(day(rows() + "<li>09:00 Литургия отменена</li>"))
        assertTrue(later.events.isEmpty())
        assertTrue(later.withdrawnStarts.contains("2026-11-22T09:00"))
    }

    @Test fun onlyAConcreteCompleteForeignLiCanPreserveTheValidatedHeadingDate() {
        val general = "08:30 Все богослужения только в $b."
        assertTrue(ScheduleVenue.isConcreteForeignEvent(foreign, target))
        assertFalse(ScheduleVenue.isConcreteForeignEvent(general, target))
        assertFalse(ScheduleVenue.isConcreteForeignEvent("08:30 Исповедь только в $b (в $b).", target))
        for (html in listOf(
            day("<li>$joint</li>").replace("<p>Неделя по Пятидесятнице</p>", "<p>$general</p>"),
            day(rows(first = general)),
            day("<li>$general</li><li>Успенский храм д. Обухово</li><li>$joint</li>"),
            "<h1>Ноябрь 2026</h1><h3>22 ноября</h3><p>$joint</p>")) {
            assertTrue(html, inspect(html).events.isEmpty())
        }
        val restored = day("<li>$general</li><li>Успенский храм д. Обухово</li>")
            .replace("</ul>", "</ul><h3>29 ноября</h3><ul><li>$joint</li></ul>")
        assertEquals(listOf("2026-11-29"), inspect(restored).events.map { it.date })
    }

    @Test fun wholeDomLiIsCheckedBeforeFragmentsCanFlushAtTheVenueLabel() {
        val restricted = day("<li>$joint<br>Только в $b</li>")
        val lines = document(restricted).pages.single().lines
        val first = lines.first { it.text == joint }
        val notice = lines.first { it.text.startsWith("Только в") }
        assertTrue(ScheduleVenue.isLabel(notice.text))
        assertTrue(requireNotNull(first.htmlEventRow).contains("\n"))
        assertTrue(requireNotNull(first.htmlEventRow).contains("Только в $b"))
        assertEquals(first.htmlEventRow, notice.htmlEventRow)
        assertTrue(inspect(restricted).events.isEmpty())
        assertTrue(inspect(day("<li>09:00 Литургия (в $a и $b).<br>Отменена</li>")).events.isEmpty())
    }

    @Test fun formatBidiAndControlCharactersCannotHideRestrictionsOrCreateDayAuthority() {
        for (unsafe in listOf("\u200b", "\u202e", "\u2066", "\u0001", "\t")) {
            for (raw in listOf(joint.replace("Литургия", "Литургия отме${unsafe}нена"),
                joint.replace("Литургия", "Литургия толь${unsafe}ко"), joint.replace("Успенском", "Успен${unsafe}ском"))) {
                assertTrue(ScheduleVenue.hasUnsafeSharedInput(raw))
                assertNull(ScheduleVenue.sharedEventVenue(raw, target))
                assertTrue(inspect(day("<li>$raw</li>")).events.isEmpty())
            }
            val unsafeForeign = foreign.replace("Исповедь", "Исповедь отме${unsafe}нена")
            assertFalse(ScheduleVenue.isConcreteForeignEvent(unsafeForeign, target))
            assertTrue(inspect(day(rows(first = unsafeForeign))).events.isEmpty())
            assertTrue(inspect(day(rows(), "22 ноя${unsafe}бря")).events.isEmpty())
        }
    }

    @Test fun fullTypedCityGrammarNeverDropsDigitsOrPunctuation() {
        for (city in listOf("Обухово-2", "Обухово2", "Обухово/2", "Обухово (2)", "Обухово, 2")) {
            val parish = target.copy(city = city)
            assertNull(city, ScheduleVenue.sharedEventVenue(joint, parish))
            assertTrue(city, inspect(day(rows()), parish).events.isEmpty())
        }
        assertNotNull(ScheduleVenue.sharedEventVenue(joint, target.copy(city = "деревня ОБУХОВО")))
        assertNotNull(ScheduleVenue.sharedEventVenue(joint, target.copy(city = "д. Обухово")))
    }

    @Test fun arbitraryDatedRowsCannotRelabelOrReviveTheBoundDomHeadingDate() {
        val dated = "<li>23 ноября 10:00 Литургия (в $a).</li>"
        val parsed = inspect(day(dated + rows())).events
        assertEquals(listOf("2026-11-23" to "10:00"), parsed.map { it.date to it.time })
        val paragraph = day(rows()).replace("<p>Неделя по Пятидесятнице</p>", "<p>23 ноября</p>")
        assertTrue(inspect(paragraph).events.isEmpty())
    }


    @Test fun originalActiveHeadingAndVenueTextCannotLoseItsNegation() {
        val sharedList = "<h3>22 ноября</h3><ul><li>$joint</li></ul>"
        for (parent in listOf("Все службы не в $a", "Все службы кроме $a", "Не<br>в $a")) {
            val html = "<main><h1>Ноябрь 2026</h1><h2>$parent</h2>$sharedList</main>"
            assertTrue(parent, inspect(html).events.isEmpty())
        }
        val paragraph = "<main><h1>Ноябрь 2026</h1><p>Не<br>в $a</p>$sharedList</main>"
        assertTrue(inspect(paragraph).events.isEmpty())
        val fragment = document(paragraph).pages.single().lines.first { it.text == "в $a" }
        assertTrue(requireNotNull(fragment.htmlScopeText).contains("Не"))
        assertTrue(ScheduleVenue.hasSharedScopeRestriction(requireNotNull(fragment.htmlScopeText)))
        val reset = "<main><h1>Ноябрь 2026</h1><h2>Все службы не в $a</h2>$sharedList" +
            "<h2>Богослужения</h2><h3>29 ноября</h3><ul><li>$joint</li></ul></main>"
        assertEquals(listOf("2026-11-29"), inspect(reset).events.map { it.date })
        val oversized = "<main><h1>Ноябрь" + " ".repeat(2_100) + "2026</h1>$sharedList</main>"
        assertTrue(document(oversized).pages.single().lines.first { it.headingLevel == 1 }.htmlScopeText == null)
        assertTrue(inspect(oversized).events.isEmpty())
    }

    @Test fun supplementaryFormatCharactersAndUnpairedSurrogatesCannotSplitRestrictions() {
        for (codePoint in listOf(0xE0001, 0xE0061)) {
            val hidden = String(Character.toChars(codePoint))
            assertTrue(ScheduleVenue.hasUnsafeSharedInput(hidden))
            for (word in listOf("отме${hidden}нена", "толь${hidden}ко")) {
                val raw = joint.replace("Литургия", "Литургия $word")
                assertNull(raw, ScheduleVenue.sharedEventVenue(raw, target))
                assertTrue(inspect(day("<li>$raw</li>")).events.isEmpty())
                val html = "<main><h1>Ноябрь 2026</h1><h2>Все службы $word в $a</h2>" +
                    "<h3>22 ноября</h3><ul><li>$joint</li></ul></main>"
                assertTrue(inspect(html).events.isEmpty())
            }
        }
        assertTrue(ScheduleVenue.hasUnsafeSharedInput(String(charArrayOf(0xDB40.toChar()))))
        assertFalse(ScheduleVenue.hasUnsafeSharedInput(String(Character.toChars(0x1F64F))))
    }
}
