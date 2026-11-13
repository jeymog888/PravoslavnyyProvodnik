package ru.liturgyguide.service.book

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.AcceptanceMemoryPreferences

/** Pure routing/storage identities; these records do not claim to be downloaded service texts. */
class BookPatronalIdentityTest {
    private val parish = "same-temple"
    private val evening = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "34", patronalId = "111300")
    private val morning = evening.copy(worship = WorshipKind.MATINS)
    private val context = BookJourneyContext(parish, "2026-11-12", "18:00", "Вечерня. Утреня",
        listOf(evening, morning))

    @Test fun aJourneyKeepsOneConcreteDedicationAcrossBothParts() {
        assertEquals(context, context.validatedFor(evening, parish))
        assertEquals(context, context.validatedFor(morning, parish))
        for (other in listOf("129540", null)) {
            val mixed = context.copy(parts = listOf(evening, morning.copy(patronalId = other)))
            assertNull("A journey cannot mix concrete or generic temple texts", mixed.validatedFor(evening, parish))
        }
        val generic = context.copy(parts = context.parts.map { it.copy(patronalId = null) })
        assertEquals(generic, generic.validatedFor(evening.copy(patronalId = null), parish))
    }

    @Test fun aDailyVariantCannotChangeOrClearTheTempleDedication() {
        val route = BookRoute(evening, parish, context)
        val dailyVariant = evening.copy(mainEvent = "0", secondEvent = "1")
        assertEquals(listOf(dailyVariant, morning), route.withVariant(dailyVariant, true)?.journeyContext?.parts)
        for (other in listOf("129540", null)) {
            for (saved in listOf(true, false)) assertNull("Changing dedication requires a separate whole-route transaction",
                route.withVariant(evening.copy(patronalId = other), savedCurrentPart = saved))
        }
    }

    @Test fun aStandaloneVariantAlsoCannotChangeTheDedicationRegardlessOfCursorAvailability() {
        val route = BookRoute(evening, parish)
        for (saved in listOf(true, false)) {
            assertEquals(evening.copy(mainEvent = "1"), route.withVariant(evening.copy(mainEvent = "1"), saved)?.activeKey)
            for (other in listOf("129540", null)) assertNull(route.withVariant(evening.copy(patronalId = other), saved))
        }
    }

    @Test fun aPreviousPartFromAnotherDedicationCannotBecomeAReturnDestination() {
        val foreign = morning.copy(patronalId = "129540")
        val checked = requireNotNull(BookRoute(evening, parish, context, foreign).validated())
        assertNull(checked.lastSuccessfulKey)
        assertNull(checked.returnToLastSuccessful())
        val own = requireNotNull(BookRoute(evening, parish, context, morning).validated())
        assertEquals(morning, own.returnToLastSuccessful()?.activeKey)
    }

    @Test fun concreteKeysAndPendingSearchRoundTripWithoutBorrowingAnotherDedication() {
        val target = target(morning)
        val route = BookRoute(morning, parish, context, evening, target)
        assertEquals(route, BookRouteCodec.decode(BookRouteCodec.encode(route)))
        assertNotEquals(target.identity, target(morning.copy(patronalId = "129540")).identity)
        assertNull(route.copy(pendingSearchTarget = target.copy(bookKey = morning.copy(patronalId = "129540"))).validated())
    }

    @Test fun aNumericOrBooleanPendingDedicationIsNotCoercedToAString() {
        val encoded = BookRouteCodec.encode(BookRoute(morning, parish, context, evening, target(morning)))
        for (raw in listOf("111300", "true", "[]", "{}")) {
            val changed = JsonParser.parseString(encoded).asJsonObject
            changed.getAsJsonObject("pendingSearchTarget").getAsJsonObject("bookKey")
                .add("patronalId", JsonParser.parseString(raw))
            assertNull(raw, BookRouteCodec.decode(changed.toString()))
        }
    }

    @Test fun bookmarksForTwoDedicationsHaveDifferentStorageAddresses() {
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val first = BookReadingPosition(evening, "a".repeat(64), parish, "same-block", "same-chapter", fraction = .31f,
            journeyContext = context)
        val otherKey = evening.copy(patronalId = "129540")
        val otherContext = context.copy(parts = context.parts.map { it.copy(patronalId = "129540") })
        val second = first.copy(key = otherKey, contentHash = "b".repeat(64), fraction = .72f, journeyContext = otherContext)
        assertTrue(store.save(first))
        assertTrue(store.save(second))
        assertEquals(first, store.positionFor(evening, parish))
        assertEquals(second, store.positionFor(otherKey, parish))
        assertNull(store.positionFor(evening.copy(patronalId = null), parish))
    }

    @Test fun legacyChurchCategoryRecoveryCannotReassignAConcreteDedication() {
        val trinity = Parish("listy", "Храм Троицы Живоначальной в Листах", "Троицы в Листах", "Москва",
            "Сретенка", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
        val request = ServiceBookKey("2026-11-13", churchType = "10", patronalId = "101360")
        assertNull(BookVariantRecovery.legacyKey(request, trinity))
        assertNotNull("Unselected old books retain their existing recovery path",
            BookVariantRecovery.legacyKey(request.copy(patronalId = null), trinity))
    }

    private fun target(key: ServiceBookKey) = BookLocatedSearchHit(key, "a".repeat(64),
        BookSearchHit("chapter", "Молитва", "block", "Господи", BookReadingMode.ORIGINAL, 0, 7))
}
