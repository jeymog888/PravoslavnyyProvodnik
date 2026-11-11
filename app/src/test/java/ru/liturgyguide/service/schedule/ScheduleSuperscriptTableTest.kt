package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

class ScheduleSuperscriptTableTest {
    private val original = fixture("tomsk-auto.hocr")
    private val supplemental = fixture("tomsk-sparse-enlarged.hocr")
    private val sparse = fixture("tomsk-sparse.hocr")
    private val parish = Parish("tomsk", "Петропавловский собор", "Петропавловский собор", "Томск", "Алтайская, 47", "", "",
        "Asia/Tomsk", "", "", "", "", emptyList(), "", "")
    private val date = LocalDate.of(2026, 11, 12)
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/schedule/images/superscript/$name")).readText()
    private fun recover(a: String = original, b: String = supplemental, c: String = sparse) = ScheduleSuperscriptTable.recover(a, c, b, 1530.0/900, 2165.0/1274)
    private fun inspect(a: String = original, b: String = supplemental, c: String = sparse) = ScheduleSuperscriptTable.inspect(a, c, b, 1530.0/900, 2165.0/1274)
    private fun events(page: ScheduleDocumentPage, selected: Parish = parish) =
        ScheduleDocumentParser.inspect(ScheduleDocument("https://example.org/schedule.jpg", "image", listOf(page)), date, selected)

    @Test fun actualAndroidPassesRecoverPrintedClockGeometryAndTheDateBelowEachFirstService() {
        assertTrue(ScheduleSuperscriptTable.needsSupplement(original))
        val page = requireNotNull(recover())
        val evidence = requireNotNull(page.rawOcrEvidence)
        assertEquals(3, evidence.size)
        assertTrue(evidence[0].contains("12.11.2026"))
        assertTrue(evidence[2].contains("Псповедь.")) // Preserve the imperfect second pass too.
        val result = events(page)
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertFalse(result.yearInferred)
        for (day in listOf("2026-11-12", "2026-11-13")) {
            val current = result.events.filter { it.date == day }
            assertEquals(listOf("08:30", "16:45", "17:00"), current.map { it.time })
            assertTrue(current.first { it.time == "16:45" }.title.contains("Исповедь", ignoreCase = true))
            assertFalse(current.any { it.title.contains("Зиновии") || it.title.contains("Бессребреников") })
        }
        assertEquals(1, result.events.count { it.date == "2026-11-12" && it.time == "08:30" })
        assertEquals(1, result.events.count { it.date == "2026-11-13" && it.time == "08:30" })
    }

    @Test fun missingMinuteDigitIsNeverCopiedFromThePreviousDay() {
        val changed = mutate(supplemental) { document ->
            val clock = document.select(".ocrx_word").single { text(it) == "1700" && box(it)[1] in 1400..1450 }
            val characters = clock.children().filter { it.hasClass("ocrx_cinfo") }
            characters.last().remove() // 170 is not evidence of 17:00.
        }
        val result = events(requireNotNull(recover(b = changed)))
        assertEquals(listOf("08:30", "16:45"), result.events.filter { it.date == "2026-11-13" }.map { it.time })
        assertFalse(result.events.filter { it.date == "2026-11-13" }.any { it.title.contains("Вечернее") })
        assertFalse(result.events.any { it.time == "01:70" })
    }

    @Test fun ordinaryNumbersPhonesAndFootnotesCannotCreateSuperscriptClocks() {
        val changed = mutate(supplemental) { document ->
            listOf("2026", "830", "1700", "84951234567").forEachIndexed { index, value ->
                document.body().appendChild(number(value, 1200, 1200 + index*35, superscript = false))
            }
        }
        val result = events(requireNotNull(recover(b = changed)))
        assertEquals(events(requireNotNull(recover())).events, result.events)
        assertFalse(result.events.any { it.time == "20:26" })
    }

    @Test fun aSecondSuperscriptClockColumnIsAmbiguous() {
        val changed = mutate(supplemental) { document ->
            document.body().appendChild(number("830", 1100, 1055, superscript = true))
        }
        assertNull(recover(b = changed))
    }

    @Test fun weekdayMismatchOrAnUnpairedLastDayCannotInheritThePreviousDate() {
        val wrongWeekday = mutate(original) { document ->
            document.select(".ocrx_word").single { it.text() == "Пятница" && box(it)[0] < 150 }.text("Понедельник")
        }
        assertFalse(ScheduleSuperscriptTable.needsSupplement(wrongWeekday))
        assertNull(recover(a = wrongWeekday))
        val missingDate = mutate(original) { document -> document.select(".ocrx_word").single { it.text() == "15.11.2026" }.text("32.11.2026") }
        assertNull(recover(a = missingDate))
    }

    @Test fun aDifferentValidDateOrClockInTheSameRegionIsAConflict() {
        val wrongDate = mutate(supplemental) { document ->
            document.select(".ocrx_word").single { text(it) == "13.11.2026" }.empty().text("14.11.2026")
        }
        assertNull(recover(b = wrongDate))
        val wrongClock = mutate(original) { document ->
            document.select(".ocrx_word").first { it.text() == "830" }.text("850")
        }
        assertNull(recover(a = wrongClock))
        val conflictingProof = mutate(sparse) { document ->
            val clock = document.select(".ocrx_word").first { text(it) == "830" }
            clock.children().filter { it.hasClass("ocrx_cinfo") }.last().text("5")
        }
        assertNull(recover(c = conflictingProof))
    }

    @Test fun cancellationInEitherColumnOrEitherPassVetoesTheRepair() {
        for (inOriginal in listOf(true, false)) {
            val changed = mutate(if (inOriginal) original else supplemental) { document ->
                document.body().appendChild(word("Все службы отменены", if (inOriginal) 550 else 1000,
                    if (inOriginal) 600 else 1020, 97))
            }
            assertNull(if (inOriginal) recover(a = changed) else recover(b = changed))
        }
        val recurring = mutate(supplemental) { it.body().appendChild(word("Ежедневно", 400, 2000, 95)) }
        assertNull(recover(b = recurring))
        val missingGeometry = mutate(supplemental) { it.body().appendChild(Element("span").addClass("ocrx_word").text("Службы отменены")) }
        assertNull(recover(b = missingGeometry))
    }

    @Test fun anotherNamedBuildingInTheBodyIsNeverLostWithTheCalendarColumn() {
        val changed = mutate(supplemental) { document ->
            document.body().appendChild(word("Казанский храм", 1000, 1050, 95))
        }
        assertNull(recover(b = changed))
    }

    @Test fun recoveredHeaderStillMustBelongToTheSelectedBuilding() {
        val page = requireNotNull(recover())
        val other = parish.copy(name = "Никольский храм", shortName = "Никольский храм")
        assertEquals(ScheduleParseDisposition.REJECTED, events(page, other).disposition)
        assertTrue(events(page, other).events.isEmpty())
    }

    @Test fun uncertainDatesAndAbsentCharacterGeometryCannotBePromotedToEvidence() {
        val lowDate = mutate(original) { document ->
            val date = document.select(".ocrx_word").single { it.text() == "12.11.2026" }
            date.attr("title", date.attr("title").replace("x_wconf 96", "x_wconf 54"))
        }
        assertNull(recover(a = lowDate))
        val noCharacters = mutate(supplemental) { document ->
            document.select(".ocrx_word").forEach { val text = text(it); it.empty().text(text) }
        }
        val noSparseCharacters = mutate(sparse) { document ->
            document.select(".ocrx_word").forEach { val text = text(it); it.empty().text(text) }
        }
        assertNull(recover(b = noCharacters, c = noSparseCharacters))
        assertNull(recover(b = ""))
        assertNull(ScheduleSuperscriptTable.recover(original, sparse, supplemental, Double.NaN, 1.7))
    }

    @Test fun pairedApostleDedicationIsSymmetricAndCannotComeFromAnAddress() {
        assertTrue(ScheduleVenue.matches("Собор святых апостолов Петра и Павла", parish))
        assertTrue(ScheduleVenue.matches("Петропавловский собор", parish.copy(name = "Собор святых апостолов Петра и Павла", shortName = "")))
        assertFalse(ScheduleVenue.matches("Храм апостола Петра", parish))
        assertFalse(ScheduleVenue.matches("Собор святых апостолов Петра и Павла", parish.copy(
            name = "Никольский храм", shortName = "", address = "Петропавловская улица, 1")))
        assertFalse(ScheduleVenue.matches("Собор святых апостолов Петра и Павла", parish.copy(
            name = "Храм на Петропавловской улице", shortName = "", address = "Петропавловская улица, 1")))
    }

    @Test fun aSupplementaryCancellationRejectsPrimaryEventsEvenWhenTheNextPassFails() {
        val auto = simpleTable()
        assertTrue(ScheduleSuperscriptTable.needsSupplement(auto))
        assertEquals(ScheduleParseDisposition.PARSED,
            events(ScheduleDocumentPage(ScheduleOcrLayout.fromHocr(auto))).disposition)
        val withCancellation = mutate(auto) { it.body().appendChild(word("Все службы отменены", 400, 500, 96)) }
        for (nextPass in listOf("", "<html></html>")) {
            val page = inspect(a = auto, c = withCancellation, b = nextPass)
            assertEquals(ScheduleOcrFailure.CONFLICTING_EVIDENCE, page.ocrFailure)
            assertTrue(requireNotNull(page.rawOcrEvidence).any { it.contains("Все службы отменены") })
            val result = events(page)
            assertEquals(ScheduleParseDisposition.REJECTED, result.disposition)
            assertTrue(result.events.isEmpty())
            assertTrue(result.withdrawnDates.isEmpty())
            assertTrue(result.withdrawnStarts.isEmpty())
            assertFalse(result.withdrawsAll) // The unreadable scope is not an invented global cancellation.
        }
    }

    @Test fun unsupportedSupplementDoesNotSilentlyRestoreThePrimaryPass() {
        val auto = simpleTable()
        val page = inspect(a = auto, c = "", b = "")
        assertEquals(ScheduleOcrFailure.UNSUPPORTED_LAYOUT, page.ocrFailure)
        assertEquals(ScheduleParseDisposition.UNSUPPORTED, events(page).disposition)
        assertTrue(events(page).events.isEmpty())
    }

    @Test fun explicitOcrConflictIsCheckedBeforeAnyPageEventsWithoutInventingWithdrawalDates() {
        val readable = ScheduleDocumentPage(listOf(ScheduleDocumentLine("12.11.2026 08:30 Литургия")))
        val conflicted = readable.copy(ocrFailure = ScheduleOcrFailure.CONFLICTING_EVIDENCE)
        val result = ScheduleDocumentParser.inspect(ScheduleDocument("https://example.org/a.pdf", "pdf",
            listOf(readable, conflicted)), date, parish)
        assertEquals(ScheduleParseDisposition.REJECTED, result.disposition)
        assertTrue(result.events.isEmpty())
        assertFalse(result.withdrawsAll)
        assertTrue(result.withdrawnStarts.isEmpty() && result.withdrawnDates.isEmpty())
        assertEquals(ScheduleParseDisposition.UNSUPPORTED,
            events(readable.copy(ocrFailure = ScheduleOcrFailure.UNSUPPORTED_LAYOUT)).disposition)
    }

    @Test fun aCompletelyUnreadableInteriorDateCannotMoveItsClocksToThePreviousDay() {
        val page = inspect(a = removeLeftColumn(original, 550.0..620.0),
            c = removeLeftColumn(sparse, 550.0..620.0),
            b = removeLeftColumn(supplemental, 550.0..620.0, 1.7))
        assertEquals(ScheduleOcrFailure.CONFLICTING_EVIDENCE, page.ocrFailure)
        assertEquals(ScheduleParseDisposition.REJECTED, events(page).disposition)
        assertTrue(events(page).events.isEmpty())
    }

    @Test fun lastBandWithoutFollowingPrintedDayBoundaryIsNotImportedOrAssignedToAnotherDay() {
        val normal = events(requireNotNull(recover()))
        assertTrue(normal.events.none { it.date == "2026-11-15" })
        val missing = inspect(a = removeLeftColumn(original, 1000.0..1080.0),
            c = removeLeftColumn(sparse, 1000.0..1080.0),
            b = removeLeftColumn(supplemental, 1000.0..1080.0, 1.7))
        assertNull(missing.ocrFailure)
        val result = events(missing)
        assertTrue(result.events.none { it.date >= "2026-11-14" })
        for (day in listOf("2026-11-12", "2026-11-13"))
            assertEquals(listOf("08:30", "16:45", "17:00"), result.events.filter { it.date == day }.map { it.time })
    }

    @Test fun anOrdinaryClockConflictInASupplementaryPassCannotBeIgnored() {
        val changed = mutate(sparse) { document ->
            document.select(".ocrx_word").first { text(it) == "830" }.empty().text("08:50")
        }
        val page = inspect(c = changed)
        assertEquals(ScheduleOcrFailure.CONFLICTING_EVIDENCE, page.ocrFailure)
        assertEquals(ScheduleParseDisposition.REJECTED, events(page).disposition)
        assertTrue(events(page).events.isEmpty())
    }

    @Test fun aPrintedRelicTranslationCommemorationDoesNotCancelTheActualTimetable() {
        val withCommemoration = mutate(supplemental) { document ->
            document.body().appendChild(word("Перенесение мощей святителя Николая", 1000, 1050, 96))
        }
        val page = inspect(b = withCommemoration)
        assertNull(page.ocrFailure)
        assertEquals(events(requireNotNull(recover())).events, events(page).events)
        assertTrue(requireNotNull(page.rawOcrEvidence).any { it.contains("Перенесение мощей святителя Николая") })
        val withActualRescheduling = mutate(withCommemoration) { document ->
            document.body().appendChild(word("Литургия перенесена на 10:00", 1000, 1120, 96))
        }
        val refused = inspect(b = withActualRescheduling)
        assertEquals(ScheduleOcrFailure.CONFLICTING_EVIDENCE, refused.ocrFailure)
        assertTrue(events(refused).events.isEmpty())
    }

    private fun removeLeftColumn(source: String, vertical: ClosedFloatingPointRange<Double>, scale: Double = 1.0) =
        mutate(source) { document -> document.select(".ocrx_word").filter {
            val bounds = box(it)
            bounds[0] / scale < 160 && bounds[1] / scale in vertical
        }.forEach(Element::remove) }

    private fun simpleTable(): String = Jsoup.parse("<html><body></body></html>").also { document ->
        document.body().appendChild(word("Собор святых апостолов Петра и Павла", 50, 40, 98))
        listOf("Четверг" to "12.11.2026", "Пятница" to "13.11.2026", "Суббота" to "14.11.2026")
            .forEachIndexed { index, (weekday, date) ->
                document.body().appendChild(word(weekday, 50, 100 + index*150, 98))
                document.body().appendChild(word(date, 50, 130 + index*150, 98))
            }
        document.body().appendChild(word("08:30", 200, 160, 98))
        document.body().appendChild(word("Литургия", 260, 160, 98))
    }.outerHtml()

    private fun mutate(source: String, change: (org.jsoup.nodes.Document) -> Unit): String = Jsoup.parse(source).also(change).outerHtml()
    private fun text(word: Element): String = word.children().filter { it.hasClass("ocrx_cinfo") && it.attr("title").contains("x_bboxes") }
        .takeIf { it.isNotEmpty() }?.joinToString("") { it.text() } ?: word.text()
    private fun box(word: Element) = Regex("bbox (\\d+) (\\d+) (\\d+) (\\d+)").find(word.attr("title"))!!.groupValues.drop(1).map(String::toInt)
    private fun word(text: String, x: Int, y: Int, confidence: Int) = Element("span").addClass("ocrx_word")
        .attr("title", "bbox $x $y ${x+text.length*9} ${y+24}; x_wconf $confidence").text(text)
    private fun number(value: String, x: Int, y: Int, superscript: Boolean): Element {
        val result = word("", x, y, 95).attr("title", "bbox $x $y ${x+value.length*16} ${y+30}; x_wconf 95")
        value.forEachIndexed { index, digit ->
            val smaller = superscript && index >= value.length-2
            val left = x+index*16
            result.appendChild(Element("span").addClass("ocrx_cinfo").attr("title",
                "x_bboxes $left $y ${left+14} ${y+if (smaller) 18 else 30}; x_conf 98.0").text(digit.toString()))
        }
        return result
    }
}
