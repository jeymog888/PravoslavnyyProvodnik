package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test

class PdfScheduleTextLayoutTest {
    @Test fun realRaisedMinuteGeometryBecomesClocksBeforeRowsAreJoined() {
        // Measured in dnepr-2026-11.pdf: hour14.04pt, minutes9pt, rise5.04pt.
        val source = row("01.11.26", "8", "00", 58.56f) + row("04.11.26", "7", "30", 92.30f) +
            row("06.11.26", "14", "00", 142.10f)
        val lines = PdfScheduleTextLayout.lines(source)
        assertTrue(lines.any { "01.11.26" in it.text && "08:00" in it.text })
        assertTrue(lines.any { "04.11.26" in it.text && "07:30" in it.text })
        assertTrue(lines.any { "06.11.26" in it.text && "14:00" in it.text })
        assertTrue(PdfScheduleTextLayout.isReliable(lines))
    }

    @Test fun twoMinuteGlyphsAndASeparateDateHeadingKeepTheirAssociation() {
        val source = listOf(g("14.11.26", 28f, 342.77f, 65f, 14.04f)) +
            row(null, "7", "3", 358.85f).filterNot { it.text == "3" } +
            listOf(g("3", 212.45f, 353.81f, 4.5f, 9f), g("0", 216.95f, 353.81f, 4.5f, 9f))
        val lines = PdfScheduleTextLayout.lines(source)
        assertEquals(2, lines.size)
        assertTrue(lines[0].text.contains("14.11.26"))
        assertTrue(lines[1].text.startsWith("07:30"))
        assertFalse(PdfScheduleTextLayout.isReliable(lines)) // One date alone is insufficient.
    }

    @Test fun plainNumbersAndDegreeSymbolsAreNeverInventedMinutes() {
        for (raw in listOf("800", "730", "1400", "8°", "73°", "14®", "9%")) {
            val lines = PdfScheduleTextLayout.lines(listOf(g(raw, 205f, 90f, 24f, 14f), g("Литургия", 241f, 90f, 70f, 14f)))
            assertTrue(lines.joinToString { it.text }.contains(raw))
            assertFalse(lines.any { ':' in it.text })
        }
    }

    @Test fun normalSizeRaisedDigitsAndSubscriptsAndInvalidMinutesAreNotClocks() {
        val base = row("01.11.26", "8", "00", 90f)
        val invalid = listOf(
            base.map { if (it.text == "00") it.copy(fontSize = 14.04f) else it },
            base.map { if (it.text == "00") it.copy(baseline = 95f) else it },
            base.map { if (it.text == "00") it.copy(text = "60") else it },
            base.map { if (it.text == "00") it.copy(text = "0°") else it }
        )
        invalid.forEach { glyphs -> assertFalse(PdfScheduleTextLayout.lines(glyphs).any { ':' in it.text }) }
    }

    @Test fun raisedFootnotesWithoutAServiceAndAnUnseparatedNumberAreNotClocks() {
        val footnote = row("01.11.26", "8", "00", 90f).map { if (it.text == "Литургия") it.copy(text = "страница") else it }
        assertFalse(PdfScheduleTextLayout.lines(footnote).any { ':' in it.text })
        val attached = row("01.11.26", "8", "00", 90f) + g("ссылка", 170f, 90f, 33f, 14.04f)
        assertFalse(PdfScheduleTextLayout.lines(attached).any { ':' in it.text })
    }

    @Test fun reliabilityNeedsThreeDistinctCalendarDatesAndServicesNotThreeNumbers() {
        fun reliable(vararg text: String) = PdfScheduleTextLayout.isReliable(text.map(::ScheduleDocumentLine))
        assertTrue(reliable("01.11.2026 08:00 Литургия", "04.11.26", "07:30 Утреня", "06.11.26 14.00 Вечерня"))
        assertFalse(reliable("01.11.26 08:00 Литургия", "01.11.26 09:00 Литургия", "01.11.26 10:00 Молебен"))
        assertFalse(reliable("31.02.26 08:00 Литургия", "04.11.26 07:30 Утреня", "06.11.26 14:00 Вечерня"))
        assertFalse(reliable("01.11.26 08:00 Адрес", "04.11.26 07:30 Телефон", "06.11.26 14:00 Контакт"))
    }

    @Test fun aBrokenClockInAnOtherwiseReadableLayerStillRequiresOcr() {
        val good = listOf("01.11.26 08:00 Литургия", "04.11.26 07:30 Утреня", "06.11.26 14:00 Вечерня")
        for (broken in listOf("07.11.26 800 Литургия", "07.11.26 8° Литургия", "07.11.26 08:00 Литу\uFFFDгия")) {
            assertFalse(PdfScheduleTextLayout.isReliable((good + broken).map(::ScheduleDocumentLine)))
        }
    }

    @Test fun weeklyHeadingDoesNotSupplyAThirdDatedAgreement() {
        val lines = listOf("01.11.26 08:00 Литургия", "04.11.26 07:30 Утреня", "06.11.26", "Каждую среду:", "09:00 Молебен")
        assertFalse(PdfScheduleTextLayout.isReliable(lines.map(::ScheduleDocumentLine)))
    }

    private fun g(text: String, x: Float, y: Float, width: Float, size: Float) = PdfScheduleGlyph(text, x, y, width, size)
    private fun row(date: String?, hour: String, minutes: String, baseline: Float): List<PdfScheduleGlyph> {
        val right = 205.37f + hour.length * 7.02f
        return listOfNotNull(date?.let { g(it, 28.32f, baseline, 65f, 14.04f) },
            g(hour, 205.37f, baseline, hour.length * 7.02f, 14.04f),
            g(minutes, right + .06f, baseline - 5.04f, minutes.length * 4.5f, 9f),
            g("Литургия", 240.53f, baseline, 67f, 14.04f))
    }
}
