package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleVenueStatementTest {
    private val stock = "Богослужения в храме совершаются согласно расписанию"

    @Test fun unqualifiedStatementsDoNotInventAnotherBuilding() {
        for (text in listOf(stock, "Огласительные беседы проводятся в храме в дни, когда совершается Богослужение")) {
            assertFalse(ScheduleVenue.isLabel(text))
            assertNull(ScheduleVenue.inlineLabel(text))
        }
    }

    @Test fun namedRelativeAndExplicitCalendarBuildingsRemainEvidence() {
        for (text in listOf("В Никольском храме совершаются согласно расписанию", "$stock в малом храме",
            "Собор Церкви Русской", "Литургия в храме Отцов Поместного Собора Церкви Русской 1917-1918 годов")) {
            assertNotNull(text, ScheduleVenue.inlineLabel(text))
            assertFalse(text, ScheduleVenue.matches(ScheduleVenue.inlineLabel(text), null))
        }
    }

    @Test fun cancellationInTheOriginalUnqualifiedStatementStillWithdrawsTheService() {
        fun inspect(heading: String) = ScheduleDocumentParser.inspect(ScheduleDocument("https://church.example/schedule", "html",
            listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine(heading, headingLevel = 1),
                ScheduleDocumentLine("ноябрь 2026"), ScheduleDocumentLine("2026-11-13 17:00 Вечерня"))))), LocalDate.of(2026, 11, 12))
        assertEquals(1, inspect(stock).events.size)
        assertTrue(inspect("$stock. Все богослужения отменены").events.isEmpty())
    }
}
