package ru.liturgyguide.service.book

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.MemoryCommit
import java.time.LocalDate

/** Contract tests for the published occurrence, separate from book content and source selection. */
class BookJourneyTest {
    private val parish = Parish("temple", "Храм святителя Николая", "Никольский", "Москва", "", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val title = "Вечерня. Утреня"
    private val vespers = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "34")
    private val matins = vespers.copy(worship = WorshipKind.MATINS)
    private val journey = BookJourneyContext(parish.id, "2026-11-12", "17:00", title, listOf(vespers, matins))
    private val position = BookReadingPosition(vespers, "a".repeat(64), parish.id, "long-a", "chapter-a",
        fraction = .37f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 61, showAltar = true,
        lastTextMode = BookReadingMode.RUSSIAN, journeyContext = journey)

    @Test fun explicitEveningNamesCreateExactlyTwoOrderedBooksOnOneNextDate() {
        for (name in listOf(title, "Вечерня и утреня", "ВЕЧЕРНЯ, УТРЕНЯ", "Вечерня; утреня")) {
            val route = requireNotNull(resolve(name))
            assertEquals(name, vespers, route.activeKey)
            assertEquals(listOf(vespers, matins), route.journeyContext?.parts)
            assertEquals(name, route.journeyContext?.title)
            assertEquals("2026-11-12", route.journeyContext?.civilDate)
            assertEquals("17:00", route.journeyContext?.time)
            assertNull(route.lastSuccessfulKey)
            assertFalse(route.journeyContext!!.parts.any { it.worship == WorshipKind.VIGIL || it.date == "2026-11-14" })
        }
    }

    @Test fun dependentReferencesAndExplicitOtherServicesKeepTheirSingleBook() {
        val cases = listOf(
            "Вечерня после утрени" to WorshipKind.VESPERS,
            "Вечерня перед утреней" to WorshipKind.VESPERS,
            "Вечерняя утреня" to WorshipKind.VESPERS,
            "Утреня в вечернее время" to WorshipKind.VESPERS,
            "Вечерня с утренними молитвами" to WorshipKind.VESPERS,
            "Утреня по окончании вечерни" to WorshipKind.MATINS,
            "Утреня после окончания вечерни" to WorshipKind.MATINS,
            "Всенощное бдение. Вечерня и утреня" to WorshipKind.VIGIL,
            "Вечерня. Утреня. Литургия" to WorshipKind.LITURGY,
            "Вечерня" to WorshipKind.VESPERS,
            "Полиелейная утреня" to WorshipKind.MATINS,
            "Литургия" to WorshipKind.LITURGY)
        for ((name, kind) in cases) {
            val route = requireNotNull(resolve(name))
            assertEquals(name, kind, route.activeKey.worship)
            assertNull(name, route.journeyContext)
            assertEquals(name, if (kind == WorshipKind.LITURGY) "2026-11-12" else "2026-11-13", route.activeKey.date)
        }
        assertNull(resolve("Молебен после литургии"))
        assertNull(ServiceBookSelection.resolve(ParishService("2026-11-12", "17:00", title, "service"), null)?.journeyContext)
    }

    @Test fun combinedContextRequiresAValidEveningTimeAndMorningRetainsExistingFallback() {
        for (time in listOf("12:00", "17:00", "23:59")) {
            assertNotNull(time, resolve(title, time)?.journeyContext)
        }
        for (time in listOf("00:00", "09:00", "11:59", "24:00", "17:60", "17", "7:00", "", "17:00:00")) {
            val route = requireNotNull(resolve(title, time))
            assertEquals(time, vespers, route.activeKey)
            assertNull(time, route.journeyContext)
            assertNull(time, journey.copy(time = time).validatedFor(vespers, parish.id))
        }
        assertEquals("2026-11-12", resolve("Утреня", "09:00")?.activeKey?.date)
        assertEquals("2026-11-13", resolve("Утреня", "17:00")?.activeKey?.date)
        assertEquals(ServiceBookKey("2026-11-12", WorshipKind.MATINS, "34"),
            ServiceBookSelection.forCivilDate(LocalDate.parse("2026-11-12"), WorshipKind.MATINS, parish))
    }

    @Test fun monthLeapYearAndYearBoundariesShiftOnceAndUnrepresentableBooksAreRejected() {
        for ((civil, bookDate) in listOf("2026-11-30" to "2026-12-01", "2026-12-31" to "2027-01-01",
            "2028-02-28" to "2028-02-29", "2028-02-29" to "2028-03-01", "2200-12-30" to "2200-12-31")) {
            val route = requireNotNull(resolve(title, date = civil))
            assertEquals(listOf(bookDate, bookDate), route.journeyContext?.parts?.map { it.date })
            val switched = requireNotNull(route.selectPart(route.journeyContext!!.parts[1], savedCurrentPart = true))
            assertEquals(bookDate, BookRouteCodec.decode(BookRouteCodec.encode(switched))?.activeKey?.date)
        }
        for (date in listOf("2026-02-29", "2026-13-01", "2026-11-31", "2026-1-2", "2200-12-31", "1899-12-30")) {
            assertNull(date, resolve(title, date = date))
        }
        // A publication's civil date is already local to the selected parish, not the device clock.
        val farEast = parish.copy(timezone = "Pacific/Kamchatka")
        assertEquals(journey.parts, ServiceBookSelection.resolve(ParishService("2026-11-12", "17:00", title, "service"), farEast)?.journeyContext?.parts)
    }

    @Test fun contextRequiresExactOwnerDateKindOrderAndVariantMembership() {
        val invalid = listOf(journey.copy(parishId = "other"), journey.copy(civilDate = "2026-11-13"),
            journey.copy(title = "Всенощное бдение"), journey.copy(title = "Вечерня после утрени"),
            journey.copy(parts = journey.parts.reversed()), journey.copy(parts = listOf(vespers, vespers)),
            journey.copy(parts = listOf(vespers)), journey.copy(parts = journey.parts + matins),
            journey.copy(parts = listOf(vespers, matins.copy(date = "2026-11-14"))),
            journey.copy(parts = listOf(vespers, matins.copy(churchType = "30"))),
            journey.copy(parts = listOf(vespers, matins.copy(mainEvent = "wrong"))))
        invalid.forEach { assertNull(it.toString(), it.validatedFor(vespers, parish.id)) }
        for (foreign in listOf(vespers.copy(date = "2026-11-14"), vespers.copy(churchType = "30"),
            vespers.copy(mainEvent = "123"), vespers.copy(secondEvent = "456"), vespers.copy(worship = WorshipKind.VIGIL))) {
            assertNull(foreign.toString(), journey.validatedFor(foreign, parish.id))
        }
        assertNull(journey.validatedFor(vespers, null))
        assertEquals(journey, journey.validatedFor(matins, parish.id))
    }

    @Test fun aVariantChangesOnlyItsOwnPartAndInvalidPreviousPointersAreDiscarded() {
        val variant = vespers.copy(mainEvent = "123", secondEvent = "456")
        val changed = requireNotNull(BookRoute(vespers, parish.id, journey).withVariant(variant, savedCurrentPart = true))
        assertEquals(listOf(variant, matins), changed.journeyContext?.parts)
        val switched = requireNotNull(changed.selectPart(matins, savedCurrentPart = true))
        assertEquals(variant, switched.lastSuccessfulKey)
        assertEquals(matins, switched.activeKey)
        assertEquals(changed.journeyContext, switched.journeyContext)
        assertNull(changed.selectPart(vespers, savedCurrentPart = true))
        assertNull(changed.selectPart(matins.copy(mainEvent = "999"), savedCurrentPart = true))
        assertNull(BookRoute(vespers, parish.id, journey, matins.copy(date = "2026-11-14")).validated()?.lastSuccessfulKey)
        assertNull(BookRoute(vespers, parish.id, journey).withVariant(vespers.copy(date = "2026-11-14"), savedCurrentPart = true)?.journeyContext)
        assertNull(BookRoute(vespers, parish.id).selectPart(matins, savedCurrentPart = true))
    }

    @Test fun anExplicitlySavedPreviousVariantSurvivesCodecAndReturnsThroughItsRecordedKeyOnly() {
        val variant = vespers.copy(mainEvent = "200", secondEvent = "456")
        val previous = BookRoute(vespers, parish.id, journey)
        val requested = requireNotNull(previous.withVariant(variant, savedCurrentPart = true))
        assertEquals(vespers, requested.lastSuccessfulKey)
        assertEquals(listOf(variant, matins), requested.journeyContext?.parts)
        assertFalse(requireNotNull(requested.journeyContext).parts.contains(vespers))
        val decoded = requireNotNull(BookRouteCodec.decode(BookRouteCodec.encode(requested)))
        assertEquals(requested, decoded)
        val returned = requireNotNull(decoded.returnToLastSuccessful())
        assertEquals(vespers, returned.activeKey)
        assertEquals(journey, returned.journeyContext)
        assertEquals(parish.id, returned.parishId)
        assertNull(decoded.selectPart(vespers.copy(mainEvent = "999"), savedCurrentPart = true))

        val unsaved = requireNotNull(previous.withVariant(variant, savedCurrentPart = false))
        assertNull(unsaved.lastSuccessfulKey)
        assertNull(unsaved.returnToLastSuccessful())
        val priorAcceptedMatins = requireNotNull(previous.copy(lastSuccessfulKey = matins)
            .withVariant(variant, savedCurrentPart = false))
        assertEquals(matins, priorAcceptedMatins.lastSuccessfulKey)
        for (invalid in listOf(vespers.copy(date = "2026-11-14"), vespers.copy(churchType = "30"),
            vespers.copy(worship = WorshipKind.LITURGY), vespers.copy(mainEvent = "invalid"))) {
            val rejected = requireNotNull(requested.copy(lastSuccessfulKey = invalid).validated())
            assertNull(rejected.lastSuccessfulKey)
            assertNull(rejected.returnToLastSuccessful())
        }
        val foreignOwner = requireNotNull(requested.copy(parishId = "foreign").validated())
        assertNull(foreignOwner.journeyContext)
        assertNull(foreignOwner.lastSuccessfulKey)
    }

    @Test fun currentCodecsRoundTripAndOldPositionsWithoutContextRemainReadable() {
        assertEquals(position, BookReadingCodec.decode(BookReadingCodec.encode(position)))
        val route = BookRoute(matins, parish.id, journey, vespers)
        assertEquals(route, BookRouteCodec.decode(BookRouteCodec.encode(route)))
        val oldPosition = JsonParser.parseString(BookReadingCodec.encode(position)).asJsonObject.apply { remove("journeyContext") }
        assertEquals(position.copy(journeyContext = null), BookReadingCodec.decode(oldPosition.toString()))
        val oldRoute = JsonParser.parseString(BookRouteCodec.encode(route)).asJsonObject.apply {
            remove("journeyContext"); remove("lastSuccessfulKey")
        }
        assertEquals(BookRoute(matins, parish.id), BookRouteCodec.decode(oldRoute.toString()))
    }

    @Test fun malformedOrUnknownContextCannotDestroyTheValidCursorOrRoute() {
        val base = JsonParser.parseString(BookReadingCodec.encode(position)).asJsonObject
        val context = requireNotNull(base.get("journeyContext"))
        val badContexts = mutableListOf("null", "false", "17", "\"wrong\"", "[]", "{}", "{\"parts\":{}}", "{\"parts\":[null,null]}")
            .map { JsonParser.parseString(it) }.toMutableList()
        fun changed(field: String, value: String): JsonElement = context.deepCopy().asJsonObject.apply { add(field, JsonParser.parseString(value)) }
        badContexts += listOf(changed("parishId", "\"foreign\""), changed("civilDate", "\"2026-11-14\""),
            changed("title", "null"), changed("parts", "[\"bad\",12]"), changed("time", "[]"),
            changed("schemaVersion", "999"), changed("schemaVersion", "\"unknown\""))
        for (bad in badContexts) {
            val record = base.deepCopy().apply { add("journeyContext", bad) }
            assertEquals(bad.toString(), position.copy(journeyContext = null), BookReadingCodec.decode(record.toString()))
            val route = JsonParser.parseString(BookRouteCodec.encode(BookRoute(vespers, parish.id, journey, matins))).asJsonObject.apply {
                add("journeyContext", bad)
            }
            assertEquals(bad.toString(), BookRoute(vespers, parish.id), BookRouteCodec.decode(route.toString()))
        }
    }

    @Test fun boundedContextAndBrokenBaseRecordsHaveDifferentFailureSemantics() {
        val maxTitle = title.padEnd(500, ' ')
        val maximum = journey.copy(parishId = "p".repeat(160), title = maxTitle)
        assertEquals(maximum, maximum.validatedFor(vespers, maximum.parishId))
        for (bad in listOf(journey.copy(title = title.padEnd(501, ' ')), journey.copy(title = ""),
            journey.copy(parishId = "p".repeat(161)), journey.copy(parishId = ""))) {
            val record = JsonParser.parseString(BookReadingCodec.encode(position)).asJsonObject.apply {
                add("journeyContext", com.google.gson.Gson().toJsonTree(bad))
            }
            assertEquals(position.copy(journeyContext = null), BookReadingCodec.decode(record.toString()))
        }
        val positionJson = BookReadingCodec.encode(position)
        val routeJson = BookRouteCodec.encode(BookRoute(vespers, parish.id, journey))
        assertEquals(position, BookReadingCodec.decode(positionJson.padEnd(8192, ' ')))
        assertNotNull(BookRouteCodec.decode(routeJson.padEnd(8192, ' ')))
        assertNull(BookReadingCodec.decode(positionJson.padEnd(8193, ' ')))
        assertNull(BookRouteCodec.decode(routeJson.padEnd(8193, ' ')))
        for ((field, value) in listOf("contentHash" to "\"broken\"", "fraction" to "1.5", "blockId" to "\"\"")) {
            val record = JsonParser.parseString(BookReadingCodec.encode(position)).asJsonObject.apply { add(field, JsonParser.parseString(value)) }
            assertNull(field, BookReadingCodec.decode(record.toString()))
        }
    }

    @Test fun failedPartAcceptanceRollsBackPositionAndLastIncludingContextAsOneGroup() {
        for (failure in listOf(MemoryCommit.FALSE_AFTER_MEMORY, MemoryCommit.THROW_AFTER_MEMORY)) {
            val preferences = AcceptanceMemoryPreferences()
            val store = BookReadingStore(preferences)
            assertTrue(store.save(position))
            preferences.values["unrelated"] = "before"
            val before = preferences.values.toMap()
            val failedIndex = preferences.commitCount
            val next = position.copy(key = matins, contentHash = "b".repeat(64), blockId = "long-b", chapterId = "chapter-b",
                mode = BookReadingMode.ORIGINAL, lastTextMode = BookReadingMode.ORIGINAL)
            preferences.outcomes += failure
            preferences.afterMemory = { count -> if (count == failedIndex + 1) preferences.values["unrelated"] = "during" }
            assertFalse(store.save(next))
            assertEquals(before + ("unrelated" to "during"), preferences.values)
            assertEquals(position, store.last(parish.id))
            assertEquals(position, store.positionFor(vespers, parish.id))
            assertNull(store.positionFor(matins, parish.id))
            assertEquals(2, preferences.committedKeys[failedIndex].size)
            assertEquals(preferences.committedKeys[failedIndex], preferences.committedKeys[failedIndex + 1])
            val transient = preferences.memoryAfterCommit[failedIndex].values.filterIsInstance<String>().mapNotNull(BookReadingCodec::decode)
            assertEquals(2, transient.count { it == next })
        }
    }

    @Test fun aLaterAcceptedPartCannotBeOverwrittenByRollbackOfAnOlderContext() {
        val preferences = AcceptanceMemoryPreferences()
        val store = BookReadingStore(preferences)
        assertTrue(store.save(position))
        val failedIndex = preferences.commitCount
        val newer = position.copy(fraction = .72f, journeyContext = journey.copy(time = "18:00"))
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.afterMemory = { count -> if (count == failedIndex + 1) {
            preferences.committedKeys.last().forEach { preferences.values[it] = BookReadingCodec.encode(newer) }
        } }
        assertFalse(store.save(position.copy(fraction = .5f)))
        assertEquals(newer, store.last(parish.id))
        assertEquals(newer, store.positionFor(vespers, parish.id))
        assertEquals(failedIndex + 1, preferences.commitCount)
    }

    @Test fun partsParishesAndVariantsKeepIndependentCursorsAndStandaloneExplicitlyClearsContext() {
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val otherParish = position.copy(parishId = "other", fraction = .62f, journeyContext = null)
        val otherPart = position.copy(key = matins, blockId = "long-b", fraction = .24f, mode = BookReadingMode.MEANING)
        val variant = vespers.copy(mainEvent = "123")
        val otherVariant = position.copy(key = variant, journeyContext = journey.replacePart(vespers, variant))
        listOf(position, otherParish, otherPart, otherVariant).forEach { assertTrue(store.save(it)) }
        assertEquals(position, store.positionFor(vespers, parish.id))
        assertEquals(otherParish, store.last("other"))
        assertEquals(otherPart, store.positionFor(matins, parish.id))
        assertEquals(otherVariant, store.last(parish.id))
        assertTrue(store.save(position.copy(journeyContext = null)))
        assertNull(store.positionFor(vespers, parish.id)?.journeyContext)
        assertEquals(journey, store.positionFor(matins, parish.id)?.journeyContext)
    }

    private fun resolve(name: String, time: String = "17:00", date: String = "2026-11-12") =
        ServiceBookSelection.resolve(ParishService(date, time, name, "service"), parish)
}
