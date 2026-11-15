package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Synthetic pixel/word evidence only. Does not claim native OCR or live source success. */
class ScheduleRuledDateCellsTest {
    private class Fixture : ScheduleOcrRaster {
        override val width = 400
        override val height = 400
        val pixels = IntArray(width * height) { -1 }
        val words = mutableListOf<ScheduleWord>()
        private val black = 0xff000000.toInt()
        init {
            for (x in listOf(20, 90, 300, 380)) for (y in 50..350) pixels[y * width + x] = black
            for (y in listOf(50, 125, 200, 275, 350)) for (x in 20..380) pixels[y * width + x] = black
            word("НОЯБРЬ", 120, 10, 180); word("2026", 185, 10, 215)
            date(14, "суббота", 65); date(15, "воскресенье", 140)
            date(20, "пятница", 215); date(21, "суббота", 290)
            service("Всенощное", "17:00", 100)
            service("Литургия", "8:45", 175)
            service("Всенощное", "17:00", 250)
            service("Литургия", "8:45", 315)
            service("Всенощное", "17:00", 337)
            for (x in 90..380) pixels[325 * width + x] = black
        }
        fun word(text: String, x: Int, y: Int, right: Int = x + 30, confidence: Int = 95) {
            words += ScheduleWord(text, x, y, right, y + 9, confidence)
            for (yy in y until y + 9) for (xx in x until right) pixels[yy * width + xx] = black
        }
        private fun date(day: Int, weekday: String, y: Int) {
            word(day.toString(), 25, y, 37); word("ноября", 40, y, 80)
            word(weekday, 25, y + 14, 80)
        }
        private fun service(name: String, time: String, y: Int) {
            word(name, 115, y, 205); word(time, 320, y, 365)
        }
        override fun readArgbRow(y: Int, row: IntArray) {
            pixels.copyInto(row, 0, y * width, (y + 1) * width)
        }
        fun hocr(extra: String = "") = "<html><body>" + words.joinToString("") {
            "<span class='ocrx_word' title='bbox ${it.left} ${it.top} ${it.right} ${it.bottom}; x_wconf ${it.confidence}'>${it.text}</span>"
        } + extra + "</body></html>"
        fun page() = ScheduleRuledDateCells.inspect(this, hocr())
    }

    private fun parsed(page: ScheduleDocumentPage) = ScheduleDocumentParser.inspect(
        ScheduleDocument("https://example.org/published.png", "image", listOf(page)), LocalDate.parse("2026-11-14"))
    private fun refused(fixture: Fixture, hocr: String = fixture.hocr()): ScheduleDocumentPage {
        val page = requireNotNull(ScheduleRuledDateCells.inspect(fixture, hocr))
        assertEquals(ScheduleOcrFailure.CONFLICTING_EVIDENCE, page.ocrFailure)
        assertTrue(page.lines.isEmpty())
        val result = parsed(page)
        assertEquals(ScheduleParseDisposition.REJECTED, result.disposition)
        assertTrue(result.safetyConflict); assertTrue(result.events.isEmpty()); assertFalse(result.withdrawsAll)
        return page
    }

    @Test fun existingFourColumnScheduleKeepsItsProvenServiceColumnRoute() {
        for (celebrant in listOf(false, true)) {
            val width = if (celebrant) 800 else 600; val height = 800
            val pixels = IntArray(width * height) { -1 }; val words = mutableListOf<ScheduleWord>()
            val black = 0xff000000.toInt()
            val rails = listOf(20, 150, 230, 400, 580) + if (celebrant) listOf(780) else emptyList()
            for (x in rails) for (y in 80..720) pixels[y * width + x] = black
            for (y in listOf(80, 180, 280, 380, 480, 580, 720)) for (x in 20..rails.last()) pixels[y * width + x] = black
            fun word(text: String, left: Int, top: Int, right: Int) {
                words += ScheduleWord(text, left, top, right, top + 10, 95)
                for (y in top until top + 10) for (x in left until right) pixels[y * width + x] = black
            }
            word("НОЯБРЬ", 200, 20, 290); word("2026", 300, 20, 340)
            for ((i, value) in listOf(14 to "Сб.", 15 to "Вс.", 20 to "Пт.",
                21 to "Сб.", 22 to "Вс.", 28 to "Сб.").withIndex()) {
                val y = 130 + i * 100
                word(value.first.toString(), 35, y, 50); word(value.second, 60, y, 85)
                word("08:00", 170, y, 205); word("Литургия", 245, y, 335); word("Память", 440, y, 520)
                if (celebrant) word("Иерей", 635, y, 690)
            }
            val raster = object : ScheduleOcrRaster {
                override val width = if (celebrant) 800 else 600
                override val height = 800
                override fun readArgbRow(y: Int, row: IntArray) {
                    pixels.copyInto(row, 0, y * width, (y + 1) * width)
                }
            }
            fun hocr() = words.joinToString("", "<html><body>", "</body></html>") {
                "<span class='ocrx_word' title='bbox ${it.left} ${it.top} ${it.right} ${it.bottom}; x_wconf ${it.confidence}'>${it.text}</span>"
            }
            val previous = parsed(ScheduleDocumentPage(ScheduleOcrLayout.fromHocr(hocr())))
            assertEquals(6, previous.events.size)
            assertTrue(previous.events.all { it.title == "Литургия" && it.time == "08:00" })
            assertNull(ScheduleRuledDateCells.inspect(raster, hocr()))
            assertEquals(previous, parsed(ScheduleDocumentPage(ScheduleOcrLayout.fromHocr(hocr()))))
            val completeWords = words.toList()
            words.removeAll { it.left < 150 && it.top == 130 }
            assertEquals(ScheduleOcrFailure.CONFLICTING_EVIDENCE, ScheduleRuledDateCells.inspect(raster, hocr())?.ocrFailure)
            words.clear(); words += completeWords
            words += ScheduleWord("Повреждено", 225, 140, 235, 150, 95)
            assertEquals(ScheduleOcrFailure.CONFLICTING_EVIDENCE, ScheduleRuledDateCells.inspect(raster, hocr())?.ocrFailure)
            words.clear(); words += completeWords
            for (y in 80..720) pixels[y * width + 10] = black
            assertEquals(ScheduleOcrFailure.CONFLICTING_EVIDENCE, ScheduleRuledDateCells.inspect(raster, hocr())?.ocrFailure)
            for (y in 80..720) pixels[y * width + 10] = -1
            word("отменена", 350, 230, 390)
            assertNull(ScheduleRuledDateCells.inspect(raster, hocr()))
            val cancelled = parsed(ScheduleDocumentPage(ScheduleOcrLayout.fromHocr(hocr())))
            assertTrue("2026-11-15T08:00" in cancelled.withdrawnStarts)
            assertFalse(cancelled.events.any { it.date == "2026-11-15" })
        }
    }

    @Test fun completeDateCellsKeepTwoServicesAndOriginalPlainEvidence() {
        val fixture = Fixture(); val page = requireNotNull(fixture.page())
        assertNull(page.ocrFailure)
        assertEquals(4, page.lines.count { it.startsDayContainer })
        assertEquals(4, page.lines.count { it.endsDayContainer })
        assertEquals(2, page.lines.count { it.endsTable })
        assertEquals(fixture.words.joinToString(" ") { it.text }, page.rawOcrEvidence?.single())
        assertFalse(page.rawOcrEvidence.orEmpty().any { "<span" in it })
        assertEquals(listOf("2026-11-14/17:00", "2026-11-15/08:45", "2026-11-20/17:00", "2026-11-21/08:45", "2026-11-21/17:00"),
            parsed(page).events.map { "${it.date}/${it.time}" })
    }

    @Test fun lostTwentiethDateNeverInheritsFifteenthEvenAfterAValidMorningTime() {
        val fixture = Fixture()
        fixture.words.removeAll { it.left < 90 && it.top in 201..274 }
        val page = refused(fixture)
        assertTrue(page.rawOcrEvidence!!.single().contains("15 ноября"))
        assertTrue(page.rawOcrEvidence!!.single().contains("8:45"))
        assertTrue(page.rawOcrEvidence!!.single().contains("17:00"))
    }

    @Test fun zeroOrOneReadableDateStillAuditsTheIntactGridForBothClockSpellings() {
        for (onlyFifteenth in listOf(false, true)) for (clock in listOf("8:45", "845"))
            for (name in listOf("", "Вечерня", "Утреня")) {
                val fixture = Fixture()
                fixture.words.removeAll { it.left < 90 && !(onlyFifteenth && it.top in 126..199) }
                for (index in fixture.words.indices) if (fixture.words[index].text == "8:45")
                    fixture.words[index] = fixture.words[index].copy(text = clock)
                for (index in fixture.words.indices) if (name.isNotEmpty() &&
                    fixture.words[index].text in listOf("Всенощное", "Литургия"))
                    fixture.words[index] = fixture.words[index].copy(text = name)
                val page = refused(fixture)
                val raw = page.rawOcrEvidence!!.single()
                assertEquals(onlyFifteenth, raw.contains("15 ноября"))
                assertFalse(raw.contains("20 ноября"))
                assertTrue(raw.contains(clock)); assertTrue(raw.contains("17:00"))
            }
    }

    @Test fun monthYearAndRepeatedOpeningHoursDoNotTriggerAServiceGridAudit() {
        for (readableDateCount in listOf(0, 1, 4)) for (qualifier in listOf("", "Утренние", "Вечерние")) {
            val fixture = Fixture()
            fixture.words.removeAll { it.left < 90 && when (readableDateCount) {
                0 -> true
                1 -> it.top !in 126..199
                else -> false
            } }
            for (index in fixture.words.indices) {
                val word = fixture.words[index]
                if (word.text == "Литургия" || word.text == "Всенощное")
                    fixture.words[index] = word.copy(text = "Часы", left = 160)
            }
            for (y in listOf(100, 175, 250, 315, 337)) {
                if (qualifier.isNotEmpty()) fixture.word(qualifier, 100, y, 155)
                fixture.word("работы", 210, y, 270)
            }
            assertTrue(fixture.hocr().contains("НОЯБРЬ")); assertTrue(fixture.hocr().contains("2026"))
            assertEquals(5, fixture.words.count { it.text == "Часы" })
            assertEquals(5, fixture.words.count { it.text == "работы" })
            assertNull(fixture.page())
        }
    }

    @Test fun namedServicesAndClocksWithoutARuledGridKeepTheOrdinaryNoGridOutcome() {
        val fixture = Fixture()
        fixture.pixels.fill(-1)
        for (word in fixture.words) for (y in word.top until word.bottom)
            for (x in word.left until word.right) fixture.pixels[y * fixture.width + x] = 0xff000000.toInt()
        assertNull(fixture.page())
    }

    @Test fun missingOrDamagedDateBordersCannotFallBackToFlatOcr() {
        val missing = Fixture()
        for (x in 20..380) missing.pixels[200 * 400 + x] = -1
        refused(missing)
        val damagedLeft = Fixture()
        for (x in 21 until 90) damagedLeft.pixels[200 * 400 + x] = -1
        refused(damagedLeft)
        val extraRail = Fixture()
        for (y in 50..350) extraRail.pixels[y * 400 + 10] = 0xff000000.toInt()
        refused(extraRail)
        val truncated = Fixture()
        for (x in 20..380) truncated.pixels[350 * 400 + x] = -1
        refused(truncated)
    }

    @Test fun missingWordGeometryCrossingCellsAndUnreadableDateConfidenceRefuse() {
        val malformed = Fixture()
        refused(malformed, malformed.hocr("<span class='ocrx_word'>отменены</span>"))
        val crossing = Fixture(); crossing.word("Литургия", 280, 185, 330)
        refused(crossing)
        val confidence = Fixture()
        val index = confidence.words.indexOfFirst { it.text == "20" }
        confidence.words[index] = confidence.words[index].copy(confidence = 54)
        refused(confidence)
    }

    @Test fun contradictoryWeekdayAndHiddenDateInkRefuseWithoutSynthesizingAReplacement() {
        val wrongWeekday = Fixture()
        val index = wrongWeekday.words.indexOfFirst { it.text == "пятница" }
        wrongWeekday.words[index] = wrongWeekday.words[index].copy(text = "воскресенье")
        refused(wrongWeekday)
        val missingInk = Fixture()
        missingInk.pixels[246 * 400 + 45] = 0xffff0000.toInt()
        refused(missingInk)
    }

    @Test fun OriginalOutsideRowsAreRetainedButCannotInheritTheLastDate() {
        val fixture = Fixture()
        fixture.word("Контакты", 100, 365, 170)
        fixture.word("12:34", 200, 365, 245)
        val page = requireNotNull(fixture.page())
        assertNull(page.ocrFailure)
        assertTrue(page.lines.last().text.contains("Контакты"))
        assertEquals(5, parsed(page).events.size)
        val outsideService = Fixture()
        outsideService.word("Литургия", 100, 365, 170)
        outsideService.word("12:34", 200, 365, 245)
        refused(outsideService)
    }

    @Test fun cancellationAnywhereIncludingLowConfidenceWordsBlocksWithoutAWithdrawalClaim() {
        for (y in listOf(35, 185, 365)) {
            val fixture = Fixture(); fixture.word("отменены", 120, y, 190, 0)
            val page = refused(fixture)
            assertTrue(page.rawOcrEvidence!!.single().contains("отменены"))
        }
    }

    @Test fun similarlyRuledOpeningHoursAndPricesDoNotClaimDatedServiceGridAuthority() {
        val shop = Fixture()
        shop.words.replaceAll { it.copy(text = when (it.text) {
            "НОЯБРЬ" -> "Церковная"; "2026" -> "лавка"; "ноября" -> "рублей"
            "Литургия", "Всенощное" -> "Свечи"; else -> it.text
        }) }
        assertNull(shop.page())
        val hours = Fixture()
        hours.words.removeAll { it.text == "ноября" || it.text == "НОЯБРЬ" || it.text == "2026" }
        hours.word("Часы", 115, 35, 150); hours.word("работы", 155, 35, 205)
        assertNull(hours.page())
    }
}
