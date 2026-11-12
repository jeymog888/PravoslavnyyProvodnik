package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import java.time.LocalDate

/** New publication resolution is deliberately separate from decoding a historical reading. */
class PolyeleosJourneyTest {
    private val parish = Parish("osm:way:424243", "Храм святителя Николая", "Никольский", "Москва", "", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val title = "Вечерня. Полиелейная утреня"
    private val vespers = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "34")
    private val daily = vespers.copy(worship = WorshipKind.MATINS)
    private val ranked = daily.copy(topLevel = "3")
    private val legacy = BookJourneyContext(parish.id, "2026-11-12", "17:00", title, listOf(vespers, daily))
    private val journey = legacy.copy(parts = listOf(vespers, ranked))

    @Test fun freshPublishedPolyeleosSelectsTheRankOnlyForMatinsAndShiftsItsDateOnce() {
        for (name in listOf("Полиелейная утреня", "ПОЛИЕЛЕЙНАЯ УТРЕНЯ")) for (time in listOf("09:00", "11:59", "12:00", "17:00")) {
            val route = requireNotNull(resolve(name, time))
            assertEquals(ranked.copy(date = if (time < "12:00") "2026-11-12" else "2026-11-13"), route.activeKey)
            assertNull(route.journeyContext)
            assertEquals(route, BookRouteCodec.decode(BookRouteCodec.encode(route)))
        }
        for (name in listOf(title, "ВЕЧЕРНЯ, ПОЛИЕЛЕЙНАЯ УТРЕНЯ")) {
            val route = requireNotNull(resolve(name))
            assertEquals(vespers, route.activeKey)
            assertEquals(listOf(vespers, ranked), route.journeyContext?.parts)
            val selected = requireNotNull(route.selectPart(ranked, true))
            assertEquals(ranked, BookRouteCodec.decode(BookRouteCodec.encode(selected))?.activeKey)
        }
        assertEquals(daily.copy(date = "2026-11-12"), ServiceBookSelection.forCivilDate(LocalDate.of(2026, 11, 12), WorshipKind.MATINS, parish))
    }

    @Test fun dependentPolyeleosWordsAndOtherServicesDoNotCreateARankedMatins() {
        for (name in listOf("Молебен после полиелейной утрени", "Молебен после окончания полиелейной утрени",
            "Молебен по завершении полиелейной утрени")) assertNull(name, resolve(name))
        for ((name, kind) in listOf("Вечерня перед полиелейной утреней" to WorshipKind.VESPERS,
            "Утреня после полиелейной утрени" to WorshipKind.MATINS,
            "Утреня без полиелея" to WorshipKind.MATINS, "Утреня" to WorshipKind.MATINS,
            "Всенощное бдение. Полиелейная утреня" to WorshipKind.VIGIL,
            "Литургия. Полиелейная утреня" to WorshipKind.LITURGY)) {
            val route = requireNotNull(resolve(name))
            assertEquals(name, kind, route.activeKey.worship)
            assertNull(name, route.activeKey.topLevel); assertNull(name, route.journeyContext)
        }
        assertEquals(listOf(vespers, daily), resolve("Вечерня. Утреня")?.journeyContext?.parts)
    }

    @Test fun fixedLegacyJsonWithTheSamePolyeleosTitleIsResumedWithoutPromotion() {
        val raw = requireNotNull(javaClass.classLoader?.getResource("polyeleos-journey/legacy-position.json")).readText()
        assertFalse(raw.contains("topLevel"))
        val position = requireNotNull(BookReadingCodec.decode(raw))
        assertEquals(daily, position.key); assertEquals(legacy, position.journeyContext)
        assertEquals("a".repeat(64), position.contentHash)
        assertEquals("legacy-block", position.blockId); assertEquals("legacy-chapter", position.chapterId)
        assertEquals(.37f, position.fraction, 0f); assertEquals(BookAnchorKind.BLOCK, position.anchorKind)
        assertEquals(BookReadingMode.RUSSIAN, position.mode); assertEquals(BookReadingMode.RUSSIAN, position.lastTextMode)
        assertEquals(61, position.wordsPerMinute); assertTrue(position.showAltar)
        val resumed = BookRouteCodec.decode(BookRouteCodec.encode(BookRoute(position.key, position.parishId, position.journeyContext)))
        assertEquals(daily, resumed?.activeKey); assertEquals(legacy, resumed?.journeyContext)
        assertFalse(BookReadingCodec.encode(position).contains("topLevel"))
        assertEquals(listOf(vespers, ranked), resolve(title)?.journeyContext?.parts)
        assertEquals(listOf(vespers, daily), position.journeyContext?.parts)
    }

    @Test fun contextAllowsHistoricalNullRankButNeverChangesRankThroughPartReplacement() {
        assertEquals(legacy, legacy.validatedFor(daily, parish.id))
        assertEquals(journey, journey.validatedFor(ranked, parish.id))
        assertNull(journey.copy(title = "Вечерня. Утреня").validatedFor(ranked, parish.id))
        assertNull(journey.copy(title = "Вечерня перед полиелейной утреней").validatedFor(ranked, parish.id))
        assertNull(legacy.replacePart(daily, ranked)); assertNull(journey.replacePart(ranked, daily))
        assertNull(BookRoute(daily, parish.id, legacy).withVariant(ranked, true))
        val ordinaryVariant = daily.copy(mainEvent = "273")
        assertEquals(listOf(vespers, ordinaryVariant), legacy.replacePart(daily, ordinaryVariant)?.parts)
        assertEquals(listOf(vespers.copy(mainEvent = "273"), ranked), journey.replacePart(vespers, vespers.copy(mainEvent = "273"))?.parts)
    }

    @Test fun previousPartIdentityIncludesRankButStillAllowsReturningToAcceptedVespers() {
        for (route in listOf(BookRoute(ranked, parish.id, journey, daily), BookRoute(daily, parish.id, legacy, ranked))) {
            val restored = requireNotNull(BookRouteCodec.decode(BookRouteCodec.encode(route)))
            assertNull(restored.lastSuccessfulKey); assertNull(restored.returnToLastSuccessful())
        }
        val back = requireNotNull(BookRoute(ranked, parish.id, journey, vespers).returnToLastSuccessful())
        assertEquals(vespers, back.activeKey); assertEquals(journey, back.journeyContext)
        val ordinaryVariant = vespers.copy(mainEvent = "273")
        val explicit = requireNotNull(BookRoute(ranked, parish.id, journey, ordinaryVariant).returnToLastSuccessful())
        assertEquals(ordinaryVariant, explicit.activeKey)
        assertEquals(listOf(ordinaryVariant, ranked), explicit.journeyContext?.parts)
    }

    @Test fun recoveryPreservesRankAndRequiresItsOwnAcceptedBookInsteadOfADailySubstitute() {
        val ambiguous = parish.copy(name = "Храм Николая Чудотворца и Святой Троицы")
        val request = ranked.copy(churchType = "30")
        assertEquals(ranked, BookVariantRecovery.legacyKey(request, ambiguous))
        val book = MatinsRankFixtures.parse()
        val position = BookReadingPosition(ranked, book.contentHash, parish.id, book.blocks.first().id, book.chapters.first().id)
        val accepted = BookVariantRecovery.candidate(request, parish.id, ambiguous, { key, _ ->
            assertEquals(ranked, key); position
        }, { key, hash -> assertEquals(ranked, key); assertEquals(book.contentHash, hash); book })
        assertEquals(book, accepted)
        var readCache = false
        assertNull(BookVariantRecovery.candidate(request, parish.id, ambiguous,
            { _, _ -> position.copy(key = daily) }, { _, _ -> readCache = true; book }))
        assertFalse(readCache)
        assertNull(BookVariantRecovery.candidate(request, parish.id, ambiguous,
            { _, _ -> position }, { _, _ -> ServiceBookParser().parse(MatinsRankFixtures.document(), daily, 12345) }))
    }

    private fun resolve(name: String, time: String = "17:00") =
        ServiceBookSelection.resolve(ParishService("2026-11-12", time, name, "service"), parish)
}
