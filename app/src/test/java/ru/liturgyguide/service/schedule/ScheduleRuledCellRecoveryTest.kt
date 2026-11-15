package ru.liturgyguide.service.schedule

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

/** Controlled raster/capture evidence, never a native OCR or live publication claim. */
class ScheduleRuledCellRecoveryTest {
    private class Grid(private val count: Int = 4) : ScheduleOcrRaster {
        override val width = 400
        override val height = count * 75 + 100
        val pixels = IntArray(width * height) { -1 }
        val printed = mutableListOf<ScheduleWord>()
        val original = mutableListOf<ScheduleWord>()
        init {
            for (x in listOf(20, 90, 300, 380)) for (y in 50..50 + count * 75) pixels[y * width + x] = 0xff000000.toInt()
            for (y in (0..count).map { 50 + it * 75 }) for (x in 20..380) pixels[y * width + x] = 0xff000000.toInt()
            word("НОЯБРЬ", 120, 10, 180); word("2026", 185, 10, 215)
            repeat(count) { band ->
                val top = 50 + band * 75
                val (day, weekday) = listOf(14 to "суббота", 15 to "воскресенье", 20 to "пятница", 21 to "суббота")[band % 4]
                word(day.toString(), 25, top + 15, 37); word("ноября", 40, top + 15, 80)
                word(weekday, 25, top + 29, 80)
                val y = top + if (band % 4 == 3) 40 else 50
                word(if (band % 4 in listOf(1, 3)) "Литургия" else "Всенощное", 115, y, 205)
                word(if (band % 4 in listOf(1, 3)) "8:45" else "17:00", 320, y, 365)
                if (band % 4 == 3) {
                    word("Всенощное", 115, top + 62, 205); word("17:00", 320, top + 62, 365)
                    for (x in 90..380) pixels[(top + 50) * width + x] = 0xff000000.toInt()
                }
            }
        }
        fun word(text: String, left: Int, top: Int, right: Int, confidence: Int = 95, wordHeight: Int = 9) {
            val value = ScheduleWord(text, left, top, right, top + wordHeight, confidence)
            printed += value; original += value
            for (y in top until top + wordHeight) for (x in left until right) pixels[y * width + x] = 0xff000000.toInt()
        }
        fun removeLastPartial() {
            for (x in 91 until 380) if (x != 300) pixels[325 * width + x] = -1
        }
        fun dotted(top: Int, height: Int = 2, left: Int = 94, right: Int = 297) {
            for (y in top until top + height) for (x in left until right)
                pixels[y * width + x] = if ((x - left) % 4 < 2) 0xff000000.toInt() else -1
        }
        override fun readArgbRow(y: Int, row: IntArray) { pixels.copyInto(row, 0, y * width, (y + 1) * width) }
        fun native(request: ScheduleRuledDateCells.CellRequest,
            edit: (List<ScheduleWord>) -> List<ScheduleWord> = { it }): ScheduleRuledDateCells.CellCapture {
            val crop = request.crop
            val words = printed.filter { it.left >= crop.left && it.right <= crop.right && it.top >= crop.top && it.bottom <= crop.bottom }
            val local = edit(words).map { it.copy(left = it.left - crop.left, right = it.right - crop.left,
                top = it.top - crop.top, bottom = it.bottom - crop.top) }
            return request.capture(crop.width, crop.height, hocr(local))
        }
        fun page(reader: (ScheduleRuledDateCells.CellRequest) -> ScheduleRuledDateCells.CellCapture? = { native(it) }) =
            requireNotNull(ScheduleRuledDateCells.inspect(this, hocr(original), reader))
        fun singleBlock(confidence: Int = 95, titleAfter: Boolean = false) {
            original.removeAll { it.left in 91..299 && it.top in 126..199 }
            word("Акафист", 115, if (titleAfter) 185 else 145, 180, confidence)
            if (!titleAfter) word("Народное пение", 115, 185, 210)
        }
        companion object {
            fun hocr(words: List<ScheduleWord>) = words.joinToString("", "<html><body>", "</body></html>") {
                "<span class='ocrx_word' title='bbox ${it.left} ${it.top} ${it.right} ${it.bottom}; x_wconf ${it.confidence}'>${it.text}</span>"
            }
        }
    }
    private val parish = Parish("selected", "Храм Николая Чудотворца", "Никольский храм", "Москва", "Первая улица,1",
        "", "https://example.org/", "Europe/Moscow", "https://example.org/", "", "", "", emptyList(), "", "")
    private fun parse(page: ScheduleDocumentPage) = ScheduleDocumentParser.inspect(
        ScheduleDocument("https://example.org/source.png", "image", listOf(page)), LocalDate.parse("2026-11-14"), parish)
    private fun refused(page: ScheduleDocumentPage) {
        assertEquals(ScheduleOcrFailure.CONFLICTING_EVIDENCE, page.ocrFailure)
        assertTrue(page.lines.isEmpty()); assertTrue(parse(page).events.isEmpty())
        assertTrue(parse(page).safetyConflict); assertFalse(parse(page).withdrawsAll)
    }

    @Test fun ownedCellsRecoverLostDatesAndKeepExactSeparateServiceTitlesWithParish() {
        val grid = Grid(); grid.original.removeAll { it.left < 90 }
        val page = grid.page()
        assertNull(page.ocrFailure)
        assertEquals(9, page.rawOcrEvidence!!.size)
        assertEquals(listOf("2026-11-14/17:00/Всенощное", "2026-11-15/08:45/Литургия", "2026-11-20/17:00/Всенощное",
            "2026-11-21/08:45/Литургия", "2026-11-21/17:00/Всенощное"), parse(page).events.map { "${it.date}/${it.time}/${it.title}" })
        assertEquals(4, page.lines.count { it.startsDayContainer }); assertEquals(4, page.lines.count { it.endsDayContainer })
    }

    @Test fun foreignMissingRepeatedWrongSizeAndLateCapturesNeverGrantAReplacement() {
        var saved: ScheduleRuledDateCells.CellCapture? = null
        var request: ScheduleRuledDateCells.CellRequest? = null
        val first = Grid(); first.page { req -> first.native(req).also { if (saved == null) { saved = it; request = req } } }
        try { request!!.capture(request!!.crop.width, request!!.crop.height, ""); fail("Closed request") } catch (_: IllegalStateException) { }
        for (variant in listOf("foreign", "missing", "fake", "duplicate", "dimensions", "bbox")) {
            val grid = Grid()
            refused(grid.page { req -> when (variant) {
                "foreign" -> saved
                "missing" -> null
                "fake" -> object : ScheduleRuledDateCells.CellCapture { }
                "duplicate" -> { grid.native(req); grid.native(req) }
                "dimensions" -> req.capture(req.crop.width + 1, req.crop.height, "")
                else -> req.capture(req.crop.width, req.crop.height,
                    Grid.hocr(listOf(ScheduleWord("14", 0, 0, req.crop.width + 1, 9))))
            } })
        }
    }

    @Test fun emptyInvalidOrConflictingDateEvidenceIsNeverVotedAway() {
        for (variant in listOf("empty", "weekday", "date", "year")) {
            val grid = Grid()
            refused(grid.page { req -> grid.native(req) { words ->
                if (req.band != 0 || req.kind != ScheduleRuledDateCells.CellKind.DATE) words
                else when (variant) {
                    "empty" -> emptyList()
                    "weekday" -> words.map { if (it.text == "суббота") it.copy(text = "вторник") else it }
                    "date" -> words.map { when (it.text) { "14" -> it.copy(text = "15"); "суббота" -> it.copy(text = "воскресенье"); else -> it } }
                    else -> words.map { when (it.text) {
                        "14" -> it.copy(right = 35)
                        "ноября" -> it.copy(left = 37, right = 62)
                        "суббота" -> it.copy(text = "воскресенье")
                        else -> it
                    } } + ScheduleWord("2027", 65, 65, 85, 74, 95)
                }
            } })
        }
        // y246 is inside this date-cell but outside both date rows and all word boxes.
        val grid = Grid(); grid.pixels[246 * grid.width + 45] = 0xffff0000.toInt()
        refused(grid.page())
    }

    @Test fun dateBorderTouchIsNarrowAndNeverExtendsIntoANeighborOrServiceArea() {
        val allowed = Grid(); val index = allowed.original.indexOfFirst { it.text == "пятница" }
        allowed.original[index] = allowed.original[index].copy(bottom = 276)
        assertNull(allowed.page().ocrFailure)
        allowed.original[index] = allowed.original[index].copy(bottom = 277)
        refused(allowed.page())
        val service = Grid(); val row = service.original.indexOfFirst { it.left == 115 && it.top == 250 }
        service.original[row] = service.original[row].copy(bottom = 276)
        refused(service.page())
    }

    @Test fun sameLineFragmentsUseGeometryAndMinimumConfidenceWithRawDotPreserved() {
        val grid = Grid(); grid.original.removeAll { it.left == 320 && it.top == 175 }
        grid.original += ScheduleWord(":45", 330, 175, 350, 184, 89)
        grid.original += ScheduleWord("8", 320, 175, 328, 184, 95)
        val page = grid.page { req -> grid.native(req) { words ->
            if (req.kind == ScheduleRuledDateCells.CellKind.TIME && req.band == 1) emptyList()
            else words.map { if (it.text == "17:00") it.copy(text = "17:00.") else it }
        } }
        assertNull(page.ocrFailure)
        assertEquals(5, parse(page).events.size)
        assertTrue(page.lines.any { "08:45" in it.text && it.temporalConfidence == 89 })
        assertTrue(page.rawOcrEvidence!!.any { "17:00." in it })
    }

    @Test fun splitAmbiguityGapDifferentBaselineAndSamePlaceDisagreementRefuse() {
        for (variant in listOf("word", "gap", "baseline", "conflict")) {
            val grid = Grid()
            if (variant != "conflict") {
                grid.original.removeAll { it.left == 320 && it.top == 175 }
                grid.original += ScheduleWord("8", 320, 175, 328, 184, 95)
                grid.original += ScheduleWord(":45", if (variant == "gap") 340 else 330,
                    if (variant == "baseline") 188 else 175, 365, if (variant == "baseline") 197 else 184, 95)
                if (variant == "word") grid.original += ScheduleWord("и", 329, 175, 330, 184, 95)
            }
            refused(grid.page { req -> grid.native(req) { words ->
                if (req.band != 1 || req.kind != ScheduleRuledDateCells.CellKind.TIME) words
                else if (variant == "conflict") words.map { it.copy(text = "9:45") } else emptyList()
            } })
        }
    }

    @Test fun completeCalendarPhraseOnlyInOneServiceCellKeepsVenueChecks() {
        fun calendar(grid: Grid, complete: Boolean) {
            grid.word("Собор", 105, 281, 130); grid.word("Архистратига", 135, 281, 200); grid.word("Михаила", 205, 281, 250)
            if (complete) {
                grid.word("и", 105, 294, 110); grid.word("прочих", 115, 294, 145); grid.word("Небесных", 150, 294, 190)
                grid.word("Сил", 195, 294, 210); grid.word("бесплотных.", 215, 294, 275)
            }
        }
        val full = Grid(); calendar(full, true)
        val page = full.page(); assertNull(page.ocrFailure)
        assertEquals(2, parse(page).events.count { it.date == "2026-11-21" })
        assertTrue(page.rawOcrEvidence!!.first().contains("Собор Архистратига Михаила"))
        val partial = Grid(); calendar(partial, false)
        partial.word("и прочих Небесных Сил бесплотных.", 115, 215, 290)
        assertFalse(parse(partial.page()).events.any { it.date == "2026-11-21" })
    }

    @Test fun singleClockOwnBlockKeepsPrecedingAndFollowingTextButNotNeighborTitles() {
        val grid = Grid(); grid.singleBlock()
        grid.word("Панихида", 115, 263, 190)
        val page = grid.page(); assertNull(page.ocrFailure)
        assertEquals("Акафист Народное пение", parse(page).events.single { it.date == "2026-11-15" }.title)
        assertFalse(parse(page).events.single { it.date == "2026-11-15" }.title.contains("Панихида"))
    }

    @Test fun lowConfidenceTitleBeforeOrAfterClockCannotBeBorrowedIntoAnAcceptedEvent() {
        for (after in listOf(false, true)) {
            val grid = Grid(); grid.singleBlock(44, after)
            refused(grid.page())
        }
    }

    @Test fun singleBlockCannotCrossWeeklyFooterDateVenueOrAnotherClock() {
        for (boundary in listOf("ежедневно", "телефон", "22 ноября", "Храм Георгия", "09:00", "Акафист" + "а".repeat(500))) {
            val grid = Grid(); grid.singleBlock(titleAfter = true)
            for (words in listOf(grid.printed, grid.original)) {
                val index = words.indexOfFirst { it.left == 320 && it.top == 175 }
                words[index] = words[index].copy(top = 155, bottom = 164)
            }
            grid.word(boundary, 115, 170, 260)
            refused(grid.page())
        }
        val complete = Grid()
        complete.word("Память святителя Николая", 115, 327, 290)
        val exact = complete.page()
        assertNull(exact.ocrFailure)
        assertEquals(listOf("08:45/Литургия", "17:00/Всенощное"), parse(exact).events
            .filter { it.date == "2026-11-21" }.map { "${it.time}/${it.title}" })
        assertTrue(exact.rawOcrEvidence!!.first().contains("Память святителя Николая"))
        for (variant in listOf("missingLower", "bothUpper", "crossing")) {
            val grid = Grid()
            fun changed(words: List<ScheduleWord>) = words.mapNotNull { word -> when {
                word.left == 320 && word.top == 337 && variant == "missingLower" -> null
                word.left == 320 && word.top == 337 && variant == "bothUpper" -> word.copy(top = 300, bottom = 309)
                word.left == 320 && word.top == 315 && variant == "crossing" -> word.copy(bottom = 330)
                else -> word
            } }
            val original = changed(grid.original.toList())
            grid.original.clear(); grid.original += original
            assertTrue(grid.original.any { it.left == 115 && it.top == 315 && it.text == "Литургия" })
            assertTrue(grid.original.any { it.left == 115 && it.top == 337 && it.text == "Всенощное" })
            refused(grid.page { request -> grid.native(request) { words ->
                if (request.kind == ScheduleRuledDateCells.CellKind.TIME && request.band == 3) changed(words) else words
            } })
        }
    }

    @Test fun originalAndCaptureCancellationOrReplacedColumnVenueNeverDisappearIntoAuditOnlyData() {
        for (y in listOf(163, 190)) {
            val original = Grid(); original.singleBlock(); original.word("отменена", 115, y, 190, 0)
            var calls = 0
            refused(original.page { calls++; original.native(it) }); assertEquals(0, calls)
        }
        for (text in listOf("отменена", "Храм Георгия")) {
            val grid = Grid()
            refused(grid.page { req -> req.capture(req.crop.width, req.crop.height,
                Grid.hocr(listOf(ScheduleWord(text, 1, 1, 30, 9, 0)))) })
        }
        val header = Grid(); header.word("Храм Георгия", 100, 25, 240)
        assertTrue(parse(header.page()).events.isEmpty())
    }

    @Test fun aggregateLimitsAndCancellationNeverReturnAPartialPage() {
        val huge = Grid()
        val truncated = huge.page { it.capture(it.crop.width, it.crop.height, "x".repeat(4_000_001)) }
        refused(truncated); assertTrue(truncated.sourceTruncated)
        val words = Grid.hocr(List(20_001) { ScheduleWord("x", 1, 1, 2, 2, 95) })
        assertTrue(words.length < 4_000_000)
        val overflow = Grid().page { it.capture(it.crop.width, it.crop.height, words) }
        refused(overflow); assertTrue(overflow.sourceTruncated)
        val cancelled = Grid(); var calls = 0
        try {
            cancelled.page { req -> if (++calls == 3) throw CancellationException("Stop between cells") else cancelled.native(req) }
            fail("Cancellation must escape")
        } catch (_: CancellationException) { assertEquals(3, calls) }
        val maximum = Grid(79); var captures = 0
        assertNull(maximum.page { captures++; maximum.native(it) }.ocrFailure)
        assertEquals(158, captures)
        val tooMany = Grid(80); var never = 0
        refused(tooMany.page { never++; tooMany.native(it) }); assertEquals(0, never)
    }

    @Test fun isolatedZeroConfidenceLetterWithoutGraphicProofStillRefuses() {
        val grid = Grid(); grid.removeLastPartial()
        grid.word("о", 115, 305, 121, 0)
        refused(grid.page())
    }

    @Test fun fullColumnGraphicProofKeepsRawFragmentsAndBothOwnClockRows() {
        val grid = Grid(); grid.removeLastPartial()
        grid.word("о", 115, 305, 121, 0, wordHeight = 2)
        grid.dotted(305)
        val page = grid.page()
        assertNull(page.ocrFailure)
        assertEquals(listOf("08:45/Литургия", "17:00/Всенощное"), parse(page).events
            .filter { it.date == "2026-11-21" }.map { it.time + "/" + it.title })
        assertTrue(page.lines.any { it.text == "о" && it.confidence == 0 })
        assertTrue(page.rawOcrEvidence!!.first().contains("о"))
        assertEquals(4, page.lines.count { it.startsDayContainer })
        assertEquals(4, page.lines.count { it.endsDayContainer })
    }

    @Test fun twoDotsOnProvenGraphicRuleNeverBecomeATitleOrAnotherServiceBoundary() {
        val grid = Grid()
        grid.word(".", 115, 165, 117, 0, wordHeight = 2)
        grid.word(".", 203, 165, 205, 61, wordHeight = 2)
        grid.dotted(165)
        val page = grid.page()
        assertNull(page.ocrFailure)
        assertEquals(5, parse(page).events.size)
        assertEquals("Литургия", parse(page).events.single { it.date == "2026-11-15" }.title)
        assertTrue(page.lines.any { it.text == ". ." && it.confidence == 30 })
        assertTrue(page.rawOcrEvidence!!.first().contains(". ."))
    }

    @Test fun punctuationShortBrokenThickOrTextCrossedStripesDoNotReceiveGraphicPermission() {
        for (variant in listOf("none", "short", "gap", "thick", "above", "below", "irregular", "emptyBox")) {
            val grid = Grid()
            grid.word(".", 115, 165, 117, 0, wordHeight = 2)
            when (variant) {
                "none" -> Unit
                "short" -> grid.dotted(165, right = 220)
                "thick" -> grid.dotted(165, height = 4)
                else -> {
                    grid.dotted(165)
                    when (variant) {
                        "gap" -> for (y in 165..166) for (x in 200..215) grid.pixels[y * grid.width + x] = -1
                        "above" -> grid.pixels[164 * grid.width + 200] = 0xff000000.toInt()
                        "below" -> grid.pixels[167 * grid.width + 200] = 0xff000000.toInt()
                        "irregular" -> for (y in 165..166) for (x in 212..213) grid.pixels[y * grid.width + x] = 0xff000000.toInt()
                        "emptyBox" -> for (y in 165..166) for (x in 115..116) grid.pixels[y * grid.width + x] = -1
                    }
                }
            }
            refused(grid.page())
        }
    }

    @Test fun graphicPermissionCannotBorrowAFollowingTitleOrDropAnUntimedRowBetweenClocks() {
        for (multi in listOf(false, true)) {
            val grid = Grid()
            val y = if (multi) 330 else 190
            if (multi) grid.removeLastPartial()
            grid.word(".", 115, y, 117, 0, wordHeight = 2)
            grid.dotted(y)
            refused(grid.page())
        }
    }

    @Test fun nonletterAndLowConfidenceWordsNeverReceiveTheUntimedNoiseException() {
        for (text in listOf("1", "*", "×", "†", "аа", "отменена", "Храм Георгия", "21 ноября")) {
            val grid = Grid(); grid.removeLastPartial(); grid.word(text, 115, 305, 180, 0)
            refused(grid.page())
        }
    }

    @Test fun unruledMultiClockRowsCannotLoseAnUntimedServiceBeforeBetweenOrAfterTheirClocks() {
        for ((text, top, height) in listOf(Triple("Панихида", 305, 9),
            Triple("Продолжение молитвы", 327, 9), Triple("Продолжение молитвы", 347, 3))) {
            val grid = Grid(); grid.removeLastPartial(); grid.word(text, 115, top, 250, wordHeight = height)
            refused(grid.page())
        }
    }

    @Test fun aZeroConfidenceLetterInsideATimedServiceRowIsNotUntimedNoise() {
        val grid = Grid(); grid.removeLastPartial()
        for (words in listOf(grid.original, grid.printed)) {
            val index = words.indexOfFirst { it.left == 115 && it.top == 315 }
            words[index] = words[index].copy(text = "о", confidence = 0)
        }
        refused(grid.page())
    }

    @Test fun multiClockSourceScopeBoundariesRemainARefusalBeforeDateRestoration() {
        for (text in listOf("ежедневно", "телефон", "Храм Георгия", "22 ноября")) {
            val grid = Grid(); grid.removeLastPartial(); grid.word(text, 115, 305, 250)
            refused(grid.page())
        }
    }
}
