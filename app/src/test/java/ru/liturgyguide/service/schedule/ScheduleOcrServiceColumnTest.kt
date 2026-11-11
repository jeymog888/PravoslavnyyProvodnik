package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test

class ScheduleOcrServiceColumnTest {
    @Test fun realColumnsRemoveCommemorationAndCelebrantsOnlyFromDisplayTitles() {
        val document = requireNotNull(ImageScheduleFixture.joined())
        val events = ImageScheduleFixture.events(document)
        assertEquals("Литургия", events.single { it.date == "2026-11-13" && it.time == "08:00" }.title)
        assertTrue(events.none { Regex("(?iu)(форников|иер\\.|прот\\.|Василий|Павел)").containsMatchIn(it.title) })
        val rows = document.pages.single().lines
        val split = rows.first { it.text.contains("Собор Архистратига Михаила и") }
        assertTrue(split.text.contains("прот. Василий"))
        assertTrue(requireNotNull(split.serviceText).contains("Вечерняя служба"))
        assertFalse(requireNotNull(split.venueEvidence).contains("Собор Архистратига"))
        assertTrue(split.venueEvidence!!.contains("прот. Василий"))
        assertTrue(rows.any { it.text.contains("прочих Небесных сил бесплотных") && it.venueEvidence != null })
    }

    @Test fun rawOcrEvidenceRemainsByteForByteInEachRow() {
        for (page in 1..3) {
            val raw = ImageScheduleFixture.document(page, false).pages.single().lines
            val geometry = ImageScheduleFixture.document(page).pages.single().lines
            assertEquals(raw.map { it.text }, geometry.map { it.text })
            assertEquals(raw.map { it.temporalConfidence }, geometry.map { it.temporalConfidence })
        }
    }

    @Test fun incompleteCalendarNameOrAnotherTempleInCalendarColumnIsStillRejected() {
        val raw = ImageScheduleFixture.text("opalikha-2.hocr")
        fun changed(hocr: String) = ImageScheduleFixture.document(2).copy(pages = listOf(ScheduleDocumentPage(ScheduleOcrLayout.fromHocr(hocr))))
        val incomplete = changed(raw.replace("бесплотных.", "нечитаемо."))
        assertTrue(incomplete.pages.single().lines.first { it.text.contains("Собор Архистратига") }.venueEvidence == null)
        val rejected = ImageScheduleFixture.events(requireNotNull(ImageScheduleFixture.joined(next = incomplete)))
        assertFalse(rejected.any { it.date == "2026-11-20" && it.time == "17:00" })
        val foreign = changed(raw.replace("бесплотных.", "бесплотных. Казанский храм"))
        val result = ImageScheduleFixture.events(requireNotNull(ImageScheduleFixture.joined(next = foreign)))
        assertFalse(result.any { it.date == "2026-11-21" && it.time == "08:00" })
    }

    @Test fun completeCalendarExceptionCannotHideAnExplicitForeignTempleInTheNextColumn() {
        val hocr = ImageScheduleFixture.text("opalikha-2.hocr").replace("Василий", "Казанский храм")
        val document = ImageScheduleFixture.document(2).copy(pages = listOf(ScheduleDocumentPage(ScheduleOcrLayout.fromHocr(hocr))))
        val row = document.pages.single().lines.first { it.text.contains("Собор Архистратига Михаила и") }
        assertNotNull(row.venueEvidence)
        assertFalse(row.venueEvidence!!.contains("Собор Архистратига"))
        assertTrue(row.venueEvidence!!.contains("Казанский храм"))
        val events = ImageScheduleFixture.events(requireNotNull(ImageScheduleFixture.joined(next = document)))
        assertFalse(events.any { it.date == "2026-11-20" && it.time == "17:00" })
    }

    @Test fun sparseSingleColumnOrParallelTimeColumnsNeverCreateDisplayOrVenueHints() {
        val rows = (1..6).map { index -> listOf(
            ScheduleWord("$index", 0, index * 30, 8, index * 30 + 12),
            ScheduleWord("воскресенье", 12, index * 30, 95, index * 30 + 12),
            ScheduleWord("08:00", 100, index * 30, 130, index * 30 + 12),
            ScheduleWord("Литургия", 140, index * 30, 200, index * 30 + 12),
            ScheduleWord("Память", 260, index * 30, 310, index * 30 + 12)) }
        assertEquals(6, ScheduleOcrServiceColumn.texts(rows).size)
        assertTrue(ScheduleOcrServiceColumn.texts(rows.take(5)).isEmpty())
        assertTrue(ScheduleOcrServiceColumn.texts(rows.map { it.dropLast(1) }).isEmpty())
        val parallel = rows.map { it + ScheduleWord("09:00", 400, it.first().top, 430, it.first().bottom) }
        assertTrue(ScheduleOcrServiceColumn.texts(parallel).isEmpty())
    }
}
