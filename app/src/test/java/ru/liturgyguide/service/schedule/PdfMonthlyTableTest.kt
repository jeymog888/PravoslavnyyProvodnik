package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

class PdfMonthlyTableTest {
    private val parish = Parish("test", "Храм Нечаянная Радость", "Нечаянная Радость", "Москва", "", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private data class Page(val glyphs: List<PdfScheduleGlyph>, val rules: List<PdfTableRect>)
    private fun page(first: Int = 12, header: Boolean = true, openingDay: Int? = null,
        memory: String = "Память святого"): Page {
        val glyphs = mutableListOf<PdfScheduleGlyph>()
        fun word(text: String, x: Float, y: Float, width: Float) { glyphs += PdfScheduleGlyph(text, x, y, width, 10f) }
        if (header) {
            word("Расписание на ноябрь 2026", 80f, 25f, 280f)
            word("Храм Нечаянная Радость", 80f, 45f, 250f)
        }
        val columns = listOf(20f, 60f, 300f, 520f, 580f)
        val rules = mutableListOf<PdfTableRect>()
        columns.forEach { x -> rules += PdfTableRect(x - .5f, 60f, x + .5f, 180f) }
        repeat(4) { i -> rules += PdfTableRect(20f, 59.5f + i * 40, 580f, 60.5f + i * 40) }
        repeat(3) { i ->
            val day = first + i
            val top = 60f + i * 40
            rules += PdfTableRect(300f, top + 19.5f, 580f, top + 20.5f)
            word(day.toString(), 30f, top + 15, 14f)
            word(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[LocalDate.of(2026, 11, day).dayOfWeek.value - 1],
                30f, top + 30, 14f)
            word(memory, 95f, top + 22, 175f)
            word(if (day == openingDay) "храм открыт" else "Божественная Литургия", 330f, top + 14, 155f)
            word("8-00", 540f, top + 14, 25f)
            word("Вечерня. Утреня", 330f, top + 34, 155f)
            word("17-00", 540f, top + 34, 25f)
        }
        return Page(glyphs, rules)
    }
    private fun parsed(vararg pages: ScheduleDocumentPage) = ScheduleDocumentParser.inspect(
        ScheduleDocument("https://parish.example/schedule.pdf", "pdf", pages.toList()), LocalDate.of(2026, 11, 12), parish)

    @Test fun ruledCellsKeepTheirOwnDatesClocksAndServiceNames() {
        val input = page()
        val output = requireNotNull(PdfMonthlyTable.read(input.glyphs, input.rules))
        val result = parsed(output.page)
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        for (date in listOf("2026-11-12", "2026-11-13", "2026-11-14")) {
            assertEquals(listOf("08:00", "17:00"), result.events.filter { it.date == date }.map { it.time })
        }
        assertTrue(result.events.none { "Память" in it.title })
        assertTrue(output.page.lines.any { "Память святого" in it.text })
        assertFalse(result.yearInferred)
    }

    @Test fun onlyAnAdjacentCompatiblePageInheritsPublishedMonthAndVenue() {
        val first = page()
        val a = requireNotNull(PdfMonthlyTable.read(first.glyphs, first.rules))
        val next = page(15, header = false, openingDay = 16)
        assertNull(PdfMonthlyTable.read(next.glyphs, next.rules))
        val b = requireNotNull(PdfMonthlyTable.read(next.glyphs, next.rules, a.continuation))
        assertEquals("Храм Нечаянная Радость", b.continuation.venue)
        val result = parsed(a.page, b.page)
        assertEquals(listOf("17:00"), result.events.filter { it.date == "2026-11-16" }.map { it.time })
        assertTrue(result.events.none { "храм открыт" in it.title })
        val gap = page(16, header = false)
        assertNull(PdfMonthlyTable.read(gap.glyphs, gap.rules, a.continuation))
        val moved = next.rules.map { it.copy(left = it.left + 5, right = it.right + 5) }
        assertNull(PdfMonthlyTable.read(next.glyphs, moved, a.continuation))
    }

    @Test fun missingGridAmbiguousClocksWrongWeekdayAndExtraTextFailClosed() {
        val source = page()
        assertNull(PdfMonthlyTable.read(source.glyphs, emptyList()))
        assertNull(PdfMonthlyTable.read(source.glyphs, source.rules.filterNot { it.left == 300f && it.top == 79.5f }))
        for (changed in listOf(
            source.glyphs.map { if (it.text == "Чт") it.copy(text = "Пт") else it },
            source.glyphs.map { if (it.text == "8-00") it.copy(text = "8-60") else it },
            source.glyphs.map { if (it.text == "8-00") it.copy(text = "8-00 9-00") else it },
            source.glyphs + PdfScheduleGlyph("Богослужения отменены", 80f, 210f, 200f, 10f),
            source.glyphs.map { if (it.text == "Божественная Литургия") it.copy(text = "Литу\uFFFDргия") else it }
        )) assertNull(PdfMonthlyTable.read(changed, source.rules))
        val strike = source.rules + PdfTableRect(330f, 69f, 485f, 70f)
        assertNull(PdfMonthlyTable.read(source.glyphs, strike))
    }

    @Test fun cancellationAndForeignVenueInTheMemoryColumnRemainSafetyEvidence() {
        for (memory in listOf("Богослужения отменены", "В Казанском храме")) {
            val input = page(memory = memory)
            val output = requireNotNull(PdfMonthlyTable.read(input.glyphs, input.rules))
            assertTrue(output.page.lines.any { memory in it.text })
            assertEquals(ScheduleParseDisposition.REJECTED, parsed(output.page).disposition)
            assertTrue(parsed(output.page).events.isEmpty())
        }
    }
}
