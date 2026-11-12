package ru.liturgyguide.service.book

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind

class EveningServiceSelectionTest {
    private val parish = Parish("sretensky_old", "Сретенский монастырь. Храм Сретения Владимирской иконы Божией Матери",
        "Сретенский монастырь - старый храм", "Москва", "Большая Лубянка, 19", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
    // The published old-building occurrence reported by the user, without inferred parts.
    private val service = ParishService("2026-11-12", "18:00", "Вечернее богослужение", "service_block")
    private val vespers = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "20")
    private val matins = vespers.copy(worship = WorshipKind.MATINS)

    @Test fun reportedOccurrenceNeedsAChoiceAndBothChosenBooksUseTheSameNextDayOnce() {
        assertNull(ServiceBookSelection.kindFor(service.title))
        assertNull(ServiceBookSelection.resolve(service, parish))
        for ((kind, expected) in listOf(WorshipKind.VESPERS to vespers, WorshipKind.MATINS to matins)) {
            val route = requireNotNull(ServiceBookSelection.eveningTexts(service, parish, kind))
            assertEquals(expected, route.activeKey)
            val context = requireNotNull(route.journeyContext)
            assertTrue(context.isEveningTexts)
            assertEquals(listOf(vespers, matins), context.parts)
            assertEquals(service.date, context.civilDate)
            assertEquals(service.time, context.time)
            assertEquals(service.title, context.title)
            assertEquals(parish.id, context.parishId)
            assertEquals(route, BookRouteCodec.decode(BookRouteCodec.encode(route)))
            val other = context.parts.single { it != expected }
            val switched = requireNotNull(route.selectPart(other, savedCurrentPart = true))
            assertEquals(other, switched.activeKey)
            assertEquals(expected, switched.lastSuccessfulKey)
            assertEquals(route, switched.returnToLastSuccessful()?.copy(lastSuccessfulKey = null))
        }
    }

    @Test fun genericTextsCannotMasqueradeAsAnExplicitPublishedOrderOrBorrowAnotherOwner() {
        val route = requireNotNull(ServiceBookSelection.eveningTexts(service, parish, WorshipKind.MATINS))
        val context = requireNotNull(route.journeyContext)
        assertNull(context.copy(partSet = null).validatedFor(matins, parish.id))
        assertNull(context.copy(partSet = BookPartSetKind.PUBLISHED_ORDER).validatedFor(matins, parish.id))
        assertNull(context.copy(title = "Вечерня. Утреня").validatedFor(matins, parish.id))
        assertNull(context.validatedFor(matins, "other"))
        assertNull(context.validatedFor(matins.copy(date = "2026-11-14"), parish.id))
        assertNull(context.copy(parts = context.parts.reversed()).validatedFor(matins, parish.id))
        assertNull(context.copy(parts = listOf(vespers, matins.copy(topLevel = "3"))).validatedFor(matins.copy(topLevel = "3"), parish.id))
        val malformed = JsonParser.parseString(BookRouteCodec.encode(route)).asJsonObject
        malformed.getAsJsonObject("journeyContext").addProperty("partSet", "UNKNOWN")
        assertNull(BookRouteCodec.decode(malformed.toString())?.journeyContext)
    }

    @Test fun namedServicesKeepTheirKindDespiteTimeAdjectivesAndDependentReferences() {
        for (title in listOf("Утреня в вечернее время", "Вечерняя утреня", "Полиелейная утреня")) {
            val evening = requireNotNull(ServiceBookSelection.resolve(service.copy(title = title), parish))
            assertEquals(WorshipKind.MATINS, evening.activeKey.worship)
            assertEquals("2026-11-13", evening.activeKey.date)
            val morning = requireNotNull(ServiceBookSelection.resolve(service.copy(time = "09:00", title = title), parish))
            assertEquals("2026-11-12", morning.activeKey.date)
        }
        assertEquals(WorshipKind.VESPERS, ServiceBookSelection.kindFor("Вечерня с утренними молитвами"))
        assertEquals(WorshipKind.VESPERS, ServiceBookSelection.kindFor("Вечерня после утрени"))
        assertNull(ServiceBookSelection.kindFor("Молебен после литургии"))
        assertNull(ServiceBookSelection.kindFor("Утренние молитвы"))
        assertNull(ServiceBookSelection.eveningTexts(service.copy(title = "Всенощное бдение"), parish, WorshipKind.MATINS))
    }

    @Test fun choiceRejectsInvalidTimesDatesKindsAndNeverMovesTheCivilOccurrence() {
        for (time in listOf("", "09:00", "11:59", "24:00", "18:60", "18:00:00"))
            assertNull(time, ServiceBookSelection.eveningTexts(service.copy(time = time), parish, WorshipKind.MATINS))
        for (kind in listOf(WorshipKind.LITURGY, WorshipKind.VIGIL, WorshipKind.HOUR1))
            assertNull(ServiceBookSelection.eveningTexts(service, parish, kind))
        assertNull(ServiceBookSelection.eveningTexts(service, null, WorshipKind.MATINS))
        assertNull(ServiceBookSelection.eveningTexts(service.copy(date = "2026-11-31"), parish, WorshipKind.MATINS))
        for ((date, next) in listOf("2026-12-31" to "2027-01-01", "2028-02-28" to "2028-02-29")) {
            val route = requireNotNull(ServiceBookSelection.eveningTexts(service.copy(date = date), parish, WorshipKind.MATINS))
            assertEquals(next, route.activeKey.date)
            assertEquals(date, route.journeyContext?.civilDate)
            assertTrue(route.journeyContext!!.parts.all { it.date == next })
        }
    }

    @Test fun oldExplicitContextsRemainPublishedAndCannotAcquireGenericSemantics() {
        val route = requireNotNull(ServiceBookSelection.resolve(service.copy(title = "Вечерня. Утреня"), parish))
        assertEquals(BookPartSetKind.PUBLISHED_ORDER, route.journeyContext?.effectivePartSet)
        val json = JsonParser.parseString(BookRouteCodec.encode(route)).asJsonObject
        json.getAsJsonObject("journeyContext").remove("partSet")
        assertEquals(route, BookRouteCodec.decode(json.toString()))
        json.getAsJsonObject("journeyContext").addProperty("partSet", "UNKNOWN")
        assertNull(BookRouteCodec.decode(json.toString())?.journeyContext)
        assertNull(route.journeyContext!!.copy(partSet = BookPartSetKind.EVENING_TEXTS).validatedFor(vespers, parish.id))
        val position = BookReadingPosition(matins, "a".repeat(64), parish.id, "block", "chapter",
            journeyContext = requireNotNull(ServiceBookSelection.eveningTexts(service, parish, WorshipKind.MATINS)).journeyContext)
        assertEquals(position, BookReadingCodec.decode(BookReadingCodec.encode(position)))
    }
}
