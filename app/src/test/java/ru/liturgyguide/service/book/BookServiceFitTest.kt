package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import ru.liturgyguide.model.*

class BookServiceFitTest {
    private val parishId = "sretensky_old"
    private val key = ServiceBookKey("2026-11-10", WorshipKind.MATINS, "20")
    private val title = "Вечернее богослужение. Полиелей"
    private fun journey(bookKey: ServiceBookKey = key, publication: String = title) = BookJourneyContext(
        parishId, "2026-11-09", "18:00", publication,
        listOf(bookKey.copy(worship = WorshipKind.VESPERS, mainEvent = null), bookKey), partSet = BookPartSetKind.EVENING_TEXTS)

    @Test fun thePublishedGenericEveningNoteIsRetainedAsAnExpectationWithoutPromotingItsKey() {
        val html = requireNotNull(javaClass.getResourceAsStream("/schedule/sretensky-week-2026-11-09.html"))
            .bufferedReader().use { it.readText() }
        assertTrue("The regression title is present in the captured official schedule", html.contains(title))
        for (publication in listOf(title, "ВЕЧЕРНЕЕ БОГОСЛУЖЕНИЕ. ПОЛИЕЛЕЙ", "Вечернее богослужение с полиелеем")) {
            assertTrue(publication, ServiceBookSelection.hasPublishedEveningPolyeleos(publication))
        }
        // No saint is guessed and no top_level is imposed just because the publication names polyeleos.
        val route = BookRoute(key, parishId, journey())
        assertNull(route.activeKey.topLevel)
        val next = requireNotNull(route.withVariant(key.copy(mainEvent = "5"), savedCurrentPart = false))
        assertEquals("5", next.activeKey.mainEvent)
        assertNull(next.activeKey.topLevel)
        assertEquals("2026-11-09", next.journeyContext?.civilDate)
        assertEquals("18:00", next.journeyContext?.time)
        assertEquals(title, next.journeyContext?.title)
        assertEquals(next.activeKey, next.journeyContext?.parts?.last())
        assertEquals(next, BookRouteCodec.decode(BookRouteCodec.encode(next)))
    }

    @Test fun absentCancelledAndDependentPolyeleosReferencesDoNotCreateAnExpectation() {
        for (publication in listOf("Вечернее богослужение", "Вечернее богослужение без полиелея",
            "Вечернее богослужение после полиелея", "Вечернее богослужение перед полиелеем",
            "Вечернее богослужение до начала полиелея", "Вечернее богослужение по окончании полиелея",
            "Вечернее богослужение после полиелейной утрени", "Вечернее богослужение. Полиелей отменен",
            "Вечернее богослужение. Отмена полиелея", "Вечернее богослужение. Полиелей не предусмотрен",
            "Вечернее богослужение. Не будет полиелея", "Молебен после полиелейной утрени",
            "Утреня", "Вечерня. Полиелейная утреня")) {
            assertFalse(publication, ServiceBookSelection.hasPublishedEveningPolyeleos(publication))
        }
        assertTrue("The existing named rank-request contract stays unchanged",
            ServiceBookSelection.isPolyeleosMatins("Вечерня. Полиелейная утреня"))
    }

    @Test fun theActualBodyMustConfirmThePolyeleosOrderNotItsHeading() {
        val ordinary = ordinary()
        assertTrue(BookServiceFit.needsPolyeleosChoice(ordinary, journey(), parishId))
        assertTrue(BookServiceFit.needsPolyeleosChoice(ordinary.copy(title = "Полиелейная утреня"), journey(), parishId))
        val polyeleos = ServiceBookParser().parse(document("dimitry"), key.copy(mainEvent = "5"), 12345)
        assertTrue(ServiceBookIntegrity.valid(polyeleos))
        assertTrue(polyeleos.selectedEventTitle.orEmpty().contains("Дими"))
        assertNull("This natural source variant does not impose top_level", polyeleos.key.topLevel)
        assertFalse(BookServiceFit.needsPolyeleosChoice(polyeleos, journey(polyeleos.key), parishId))
        val withoutGospel = polyeleos.copy(chapters = polyeleos.chapters.filterNot {
            BookText.normalized(it.title) == "чтение евангелия"
        })
        assertTrue(BookServiceFit.needsPolyeleosChoice(withoutGospel, journey(withoutGospel.key), parishId))
        val headingsOnly = polyeleos.copy(chapters = polyeleos.chapters.map { chapter ->
            chapter.copy(blocks = chapter.blocks.map { it.copy(kind = BookBlockKind.RUBRIC) })
        })
        assertTrue(BookServiceFit.needsPolyeleosChoice(headingsOnly, journey(headingsOnly.key), parishId))
    }

    @Test fun theGuardAppliesOnlyToThisValidatedMatinsOccurrence() {
        val book = ordinary()
        assertFalse(BookServiceFit.needsPolyeleosChoice(book, null, parishId))
        assertFalse(BookServiceFit.needsPolyeleosChoice(book, journey(), "another-parish"))
        assertFalse(BookServiceFit.needsPolyeleosChoice(book, journey(publication = "Вечернее богослужение"), parishId))
        assertFalse(BookServiceFit.needsPolyeleosChoice(book, journey().copy(civilDate = "2026-11-08"), parishId))
        val vespers = book.copy(key = key.copy(worship = WorshipKind.VESPERS))
        assertFalse(BookServiceFit.needsPolyeleosChoice(vespers, journey(), parishId))
    }

    @Test fun anAcknowledgementIsBoundToThisEditionAndPublishedOccurrence() {
        val book = ordinary()
        val identity = requireNotNull(BookServiceFit.polyeleosMismatchIdentity(book, journey(), parishId))
        assertEquals(identity, BookServiceFit.polyeleosMismatchIdentity(book, journey(), parishId))
        assertNotEquals(identity, BookServiceFit.polyeleosMismatchIdentity(book.copy(contentHash = "b".repeat(64)), journey(), parishId))
        assertNotEquals(identity, BookServiceFit.polyeleosMismatchIdentity(book, journey().copy(time = "17:00"), parishId))
        assertNotEquals(identity, BookServiceFit.polyeleosMismatchIdentity(book, journey().copy(title = "$title. Вечером"), parishId))
        val otherParish = "another-parish"
        assertNotEquals(identity, BookServiceFit.polyeleosMismatchIdentity(book, journey().copy(parishId = otherParish), otherParish))
        assertNull(BookServiceFit.polyeleosMismatchIdentity(book, null, parishId))
    }

    @Test fun forcingTheDefaultRankDoesNotSelectTheParishMemory() {
        val rankedKey = key.copy(topLevel = "3")
        val request = MatinsRankSource.resolve(document("base"), rankedKey)
        assertEquals("0", request.mainEvent)
        val forced = ServiceBookParser().parse(document("forced-default"), rankedKey, 12345, rankRequest = request)
        val chosen = ServiceBookParser().parse(document("dimitry"), key.copy(mainEvent = "5"), 12345)
        assertTrue(ServiceBookIntegrity.valid(forced))
        assertTrue(ServiceBookIntegrity.valid(chosen))
        assertEquals("0", forced.rankSelection?.mainEvent)
        assertTrue(forced.selectedEventTitle.orEmpty().contains("Параске"))
        assertTrue(chosen.selectedEventTitle.orEmpty().contains("Дими"))
        assertNotEquals(forced.selectedEventTitle, chosen.selectedEventTitle)
        assertTrue(MatinsRankSource.hasPolyeleosOrder(forced))
        assertTrue(MatinsRankSource.hasPolyeleosOrder(chosen))
    }

    private fun document(name: String) = Jsoup.parse(requireNotNull(
        javaClass.getResourceAsStream("/book-service-fit/$name-2026-11-10.html")).bufferedReader().use { it.readText() })
    private fun ordinary() = ServiceBookParser().parse(document("base"), key, 12345)
}
