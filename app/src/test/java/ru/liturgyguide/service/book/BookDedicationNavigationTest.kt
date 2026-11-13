package ru.liturgyguide.service.book

import com.google.gson.JsonParser
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind

/** Source changes preserve the whole occurrence, not a daily variant under a different dedication. */
class BookDedicationNavigationTest {
    private val parish = "source-navigation-temple"
    private val evening = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "34", mainEvent = "0", secondEvent = "1")
    private val morning = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34", topLevel = "3")
    private val journey = BookJourneyContext(parish, "2026-11-12", "18:00", "Вечерня. Полиелейная утреня",
        listOf(evening, morning))
    private val previousVariant = evening.copy(mainEvent = "2", secondEvent = "")
    private val route = BookRoute(evening, parish, journey, previousVariant)
    private val saved = BookReadingPosition(evening, "a".repeat(64), parish, "block", "chapter", fraction = .42f,
        mode = BookReadingMode.RUSSIAN, wordsPerMinute = 152, showAltar = true,
        lastTextMode = BookReadingMode.RUSSIAN, journeyContext = journey)

    @Test fun oldStandaloneRouteJsonRetainsItsExactSerializedForm() {
        val legacy = """{"activeKey":{"date":"2026-11-13","worship":"LITURGY","churchType":"30"},"parishId":"temple"}"""
        val decoded = requireNotNull(BookRouteCodec.decode(legacy))
        assertEquals(legacy, BookRouteCodec.encode(decoded))
        assertNull(decoded.pendingDedication)
        assertNull(decoded.sourceReturn)
    }

    @Test fun aSourceChangeUpdatesBothPartsAndRetainsThePublishedRankIntent() {
        val proof = PatronalFixtures.proof("presentation-mary")
        val expectedProfile = PatronalFixtures.proof("nicholas-dec")
        val changed = requireNotNull(route.withDedication(proof, saved, expectedProfile))
        assertEquals("20", changed.activeKey.churchType)
        assertEquals(proof.id, changed.activeKey.patronalId)
        assertEquals(listOf(WorshipKind.VESPERS, WorshipKind.MATINS), changed.journeyContext?.parts?.map { it.worship })
        for (part in requireNotNull(changed.journeyContext).parts) {
            assertEquals("2026-11-13", part.date)
            assertEquals("20", part.churchType)
            assertEquals(proof.id, part.patronalId)
            assertNull(part.mainEvent)
            assertNull(part.secondEvent)
        }
        assertEquals("3", changed.journeyContext?.parts?.last()?.topLevel)
        assertNull(changed.lastSuccessfulKey)
        assertNull(changed.pendingSearchTarget)
        assertEquals(BookDedicationRequest(proof, expectedProfile), changed.pendingDedication)
        assertEquals(BookSourceReturn(saved, previousVariant), changed.sourceReturn)
        assertEquals(route, changed.returnToPreviousSource())
        assertEquals(changed, BookRouteCodec.decode(BookRouteCodec.encode(changed)))
    }

    @Test fun selectingTheSameSourcePreservesItsDailyVariantsAndStillCapturesAProfileExpectation() {
        val proof = PatronalFixtures.proof()
        val sourceJourney = journey.copy(parts = journey.parts.map { it.copy(patronalId = proof.id) })
        val current = route.copy(activeKey = sourceJourney.parts.first(), journeyContext = sourceJourney,
            lastSuccessfulKey = previousVariant.copy(patronalId = proof.id))
        val position = saved.copy(key = current.activeKey, journeyContext = sourceJourney)
        val changed = requireNotNull(current.withDedication(proof, position))
        assertEquals(current.activeKey, changed.activeKey)
        assertEquals(sourceJourney, changed.journeyContext)
        assertEquals(proof, changed.pendingDedication?.selection)
        assertNull(changed.pendingDedication?.expectedProfile)
        assertEquals(current, changed.returnToPreviousSource())
    }

    @Test fun partAndDailyVariantNavigationKeepOneSourceReturnWithoutGrowingAChain() {
        val changed = requireNotNull(route.withDedication(PatronalFixtures.proof(), saved))
        val switched = requireNotNull(changed.selectPart(requireNotNull(changed.journeyContext).parts.last(), true))
        assertEquals(changed.pendingDedication, switched.pendingDedication)
        assertEquals(changed.sourceReturn, switched.sourceReturn)
        assertEquals(route, switched.returnToPreviousSource())
        val finished = switched.clearPendingDedication()
        assertNull(finished.pendingDedication)
        assertEquals(changed.sourceReturn, finished.sourceReturn)
        val nextPosition = saved.copy(key = switched.activeKey, contentHash = "b".repeat(64),
            journeyContext = switched.journeyContext)
        val next = requireNotNull(finished.withDedication(PatronalFixtures.proof("nicholas-dec"), nextPosition,
            PatronalFixtures.proof()))
        val returned = requireNotNull(next.returnToPreviousSource())
        assertEquals(finished.activeKey, returned.activeKey)
        assertEquals(finished.journeyContext, returned.journeyContext)
        assertEquals(finished.lastSuccessfulKey, returned.lastSuccessfulKey)
        assertNull(returned.sourceReturn)
        assertNull(returned.pendingDedication)
        assertEquals(next, BookRouteCodec.decode(BookRouteCodec.encode(next)))
    }

    @Test fun aStandaloneSourceReturnKeepsTheExactCursorWithoutInventingAnOccurrence() {
        val current = BookRoute(evening, parish)
        val position = saved.copy(journeyContext = null)
        val changed = requireNotNull(current.withDedication(PatronalFixtures.proof(), position))
        assertNull(changed.journeyContext)
        assertEquals(position, changed.sourceReturn?.position)
        assertEquals(current, changed.returnToPreviousSource())
        assertEquals(changed, BookRouteCodec.decode(BookRouteCodec.encode(changed)))
    }

    @Test fun foreignOrInvalidSavedPositionsCannotAuthorizeASourceChange() {
        for (position in listOf(saved.copy(parishId = "other"), saved.copy(key = morning),
            saved.copy(contentHash = "broken"), saved.copy(fraction = 1.2f), saved.copy(journeyContext = null),
            saved.copy(journeyContext = journey.copy(time = "19:00"))))
            assertNull(position.toString(), route.withDedication(PatronalFixtures.proof(), position))
        assertNull(route.withDedication(PatronalFixtures.proof().copy(id = "01"), saved))
        assertNull(route.withDedication(PatronalFixtures.proof(), saved,
            PatronalFixtures.proof().copy(sourceUrl = "https://example.test")))
    }

    @Test fun aNewSourceCodecRejectsForeignOwnersBadTypesInvalidContextsAndReturnChains() {
        val encoded = BookRouteCodec.encode(requireNotNull(route.withDedication(PatronalFixtures.proof(), saved)))
        fun mutated(change: (com.google.gson.JsonObject) -> Unit): String =
            JsonParser.parseString(encoded).asJsonObject.also(change).toString()
        val invalid = listOf(
            mutated { it.getAsJsonObject("sourceReturn").getAsJsonObject("position").addProperty("parishId", "other") },
            mutated { it.getAsJsonObject("sourceReturn").add("sourceReturn", JsonParser.parseString("{}")) },
            mutated { it.getAsJsonObject("sourceReturn").getAsJsonObject("position").getAsJsonObject("journeyContext")
                .addProperty("time", "07:00") },
            mutated { it.getAsJsonObject("journeyContext").addProperty("time", "07:00") },
            mutated { it.getAsJsonObject("pendingDedication").getAsJsonObject("selection").addProperty("id", 111300) },
            mutated { it.getAsJsonObject("pendingDedication").getAsJsonObject("selection").addProperty("id", "129540") },
            mutated { it.getAsJsonObject("pendingDedication").add("expectedProfile", JsonParser.parseString("false")) },
            mutated { it.getAsJsonObject("sourceReturn").getAsJsonObject("position").getAsJsonObject("key")
                .addProperty("churchType", 34) },
            mutated { it.getAsJsonObject("activeKey").addProperty("patronalId", 111300) },
            mutated { it.getAsJsonObject("sourceReturn").getAsJsonObject("position").addProperty("showAltar", "true") },
            mutated { it.getAsJsonObject("sourceReturn").getAsJsonObject("position").addProperty("lastTextMode", "unknown") },
            mutated { it.getAsJsonObject("sourceReturn").getAsJsonObject("position").getAsJsonObject("journeyContext")
                .getAsJsonArray("parts")[0].asJsonObject.addProperty("churchType", 34) },
            mutated { it.getAsJsonObject("sourceReturn").getAsJsonObject("position").addProperty("pendingDedication", "chain") },
            mutated { it.remove("sourceReturn") }
        )
        invalid.forEach { assertNull(it, BookRouteCodec.decode(it)) }
        assertNotNull(BookRouteCodec.decode(encoded.padEnd(8192, ' ')))
        assertNull(BookRouteCodec.decode(encoded.padEnd(8193, ' ')))
    }

    @Test fun repeatedFieldNamesCannotReplaceANewSourceTransactionOrItsProfileExpectation() {
        val encoded = BookRouteCodec.encode(requireNotNull(route.withDedication(PatronalFixtures.proof(), saved)))
        for (bad in listOf(
            encoded.replace("\"pendingDedication\":", "\"pendingDedication\":null,\"pendingDedication\":"),
            encoded.replace("\"id\":\"111300\"", "\"id\":\"129540\",\"id\":\"111300\""),
            encoded.dropLast(1) + ",\"pendingDedication\":null}"
        )) assertNull(BookRouteCodec.decode(bad))
    }

    @Test fun aStandaloneReturnCannotBelongToAnotherDateWorshipOrRank() {
        val plain = BookRoute(evening, parish)
        val cursor = saved.copy(journeyContext = null)
        val switched = requireNotNull(plain.withDedication(PatronalFixtures.proof(), cursor))
        val atMatins = cursor.copy(key = morning.copy(topLevel = null))
        val matinsSwitch = requireNotNull(BookRoute(atMatins.key, parish).withDedication(PatronalFixtures.proof(), atMatins))
        for ((current, foreign) in listOf(
            switched to cursor.copy(key = evening.copy(date = "2026-11-14")),
            switched to cursor.copy(key = evening.copy(worship = WorshipKind.MATINS)),
            matinsSwitch to atMatins.copy(key = morning)
        )) {
            val previous = BookSourceReturn(foreign)
            assertNotNull("The foreign return is independently valid, rather than malformed", previous.routeFor(parish))
            val poisoned = current.copy(sourceReturn = previous)
            assertNull(BookRouteCodec.decode(Gson().toJson(poisoned)))
            assertNull(poisoned.validated())
            assertNull(poisoned.returnToPreviousSource())
        }
        assertNull(switched.withVariant(switched.activeKey.copy(date = "2026-11-14"), false))
    }

    @Test fun anIndependentlyValidReturnFromAnotherPublicationCannotBeGraftedIntoThisJourney() {
        val current = requireNotNull(route.withDedication(PatronalFixtures.proof(), saved))
        val anotherDate = journey.copy(civilDate = "2026-11-13", parts = journey.parts.map { it.copy(date = "2026-11-14") })
        val eveningTexts = journey.copy(title = "Вечернее богослужение", partSet = BookPartSetKind.EVENING_TEXTS,
            parts = listOf(evening, morning.copy(topLevel = null)))
        for (foreign in listOf(
            saved.copy(journeyContext = journey.copy(time = "19:00")),
            saved.copy(key = evening.copy(date = "2026-11-14"), journeyContext = anotherDate),
            saved.copy(journeyContext = journey.copy(title = "Вечерня и полиелейная утреня")),
            saved.copy(journeyContext = journey.copy(parts = listOf(evening, morning.copy(topLevel = null)))),
            saved.copy(journeyContext = eveningTexts),
            saved.copy(journeyContext = null)
        )) {
            val previous = BookSourceReturn(foreign,
                previousVariant.copy(date = foreign.key.date).takeIf { foreign.journeyContext != null })
            assertNotNull("The publication is valid on its own", previous.routeFor(parish))
            val poisoned = current.copy(sourceReturn = previous)
            assertNull(BookRouteCodec.decode(Gson().toJson(poisoned)))
            assertNull(poisoned.validated())
            assertNull(poisoned.returnToPreviousSource())
        }
    }

    @Test fun aConcreteDailyVariantCannotChangeChurchCategoryButNullLegacyRecoveryRemainsAvailable() {
        val concrete = BookRoute(evening.copy(patronalId = PatronalFixtures.proof().id), parish)
        for (savedCurrent in listOf(true, false)) assertNull(
            concrete.withVariant(concrete.activeKey.copy(churchType = "30"), savedCurrent))
        val legacy = BookRoute(ServiceBookKey("2026-11-11", churchType = "10"), parish)
        val recoveredKey = legacy.activeKey.copy(churchType = "30")
        assertEquals("Existing null-ID category recovery must still open its recorded old key",
            recoveredKey, legacy.withVariant(recoveredKey, false)?.activeKey)
    }
}
