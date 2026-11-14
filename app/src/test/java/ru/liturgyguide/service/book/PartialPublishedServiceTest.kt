package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences

/** Exact publication metadata, not a fabricated complete akathist or a changed service book. */
class PartialPublishedServiceTest {
    private val parish = Parish("partial-temple", "Храм святителя Николая", "Храм", "Москва", "", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
    private val gson = Gson()
    private fun service(title: String, date: String = "2026-11-13", time: String = "17:00") = ParishService(date, time, title, "service_block")
    private fun route(title: String = "Вечерня с акафистом иконе Богородицы Всецарица", date: String = "2026-11-13") =
        requireNotNull(ServiceBookSelection.resolve(service(title, date), parish))
    private fun position(route: BookRoute = route()) = BookReadingPosition(route.activeKey, "a".repeat(64), parish.id,
        "block", "chapter", fraction = .37f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 149,
        lastTextMode = BookReadingMode.RUSSIAN, journeyContext = route.journeyContext)

    @Test fun partialMatrixKeepsTheExactPublishedOccurrenceAndOnlyTheOneAvailableBook() {
        for ((title, kind, excluded) in listOf(
            Triple("Вечерня с акафистом иконе Богородицы Всецарица", WorshipKind.VESPERS, "Акафист"),
            Triple("Литургия. Панихида", WorshipKind.LITURGY, "Панихида"),
            Triple("После вечерни - Литургия. Панихида", WorshipKind.LITURGY, "Панихида"),
            Triple("Литургия. Панихида после нее", WorshipKind.LITURGY, "Панихида"),
            Triple("Литургия и молебен с акафистом", WorshipKind.LITURGY, "Акафист"),
            Triple("Вечерня. Исповедь", WorshipKind.VESPERS, "Исповедь"))) {
            val selected = route(title); val context = requireNotNull(selected.journeyContext)
            assertEquals(title, context.title); assertEquals("17:00", context.time); assertEquals("2026-11-13", context.civilDate)
            assertEquals(BookPartSetKind.PARTIAL_PUBLISHED_TEXT, context.partSet); assertEquals(2, context.schemaVersion)
            assertEquals(listOf(selected.activeKey), context.parts); assertEquals(kind, selected.activeKey.worship)
            assertEquals(if (kind == WorshipKind.VESPERS) "2026-11-14" else "2026-11-13", selected.activeKey.date)
            assertTrue(requireNotNull(ServiceBookSelection.readingScope(title)).contains(excluded))
            assertEquals(selected, BookRouteCodec.decode(BookRouteCodec.encode(selected)))
            assertEquals(position(selected), BookReadingCodec.decode(BookReadingCodec.encode(position(selected))))
        }
        assertTrue(ServiceBookSelection.readingScope("Литургия и молебен с акафистом")!!.contains("молебен"))
        assertNull(ServiceBookSelection.resolve(service("Вечерня с акафистом"), null))
    }

    @Test fun dependentNegativeAndAmbiguousNamesNeverInventABaseServiceOrAnUnavailableExtra() {
        for (title in listOf("Панихида после литургии", "Молебен перед вечерней", "Литургия отменена. Панихида",
            "Часы. Литургия не будет", "Всенощное бдение с акафистом", "Утреня с акафистом",
            "Вечерня и утреня с акафистом", "Литургия и панихида отменены", "Литургия. Вечерня. Акафист", "Литургия. Вечерня", "Часы. Вечерня")) {
            assertNull(title, ServiceBookSelection.kindFor(title)); assertNull(title, ServiceBookSelection.resolve(service(title), parish))
            assertEquals("Выбрать доступные тексты", ServiceBookSelection.readingAction(title))
        }
        for (title in listOf("Вечерня без утрени", "Вечерня без акафиста", "Вечерня. Акафист отменен")) {
            val selected = route(title)
            assertEquals(WorshipKind.VESPERS, selected.activeKey.worship)
            assertNull(selected.journeyContext); assertNull(ServiceBookSelection.readingScope(title))
            assertFalse(ServiceBookSelection.isCombinedEvening(title))
        }
        val liturgy = route("Литургия. Панихиды не будет")
        assertEquals(WorshipKind.LITURGY, liturgy.activeKey.worship); assertNull(liturgy.journeyContext)
        assertNull(ServiceBookSelection.readingScope("Литургия. Панихиды не будет"))
        assertEquals(WorshipKind.LITURGY, route("Литургия\nПанихиды не будет").activeKey.worship)
        assertNull(ServiceBookSelection.readingScope("Литургия\nПанихиды не будет"))
    }

    @Test fun existingTwoAndThreePartKindsDatesAndRankRemainExact() {
        val hours = route("Часы. Литургия. Панихида")
        assertEquals(1, hours.journeyContext!!.schemaVersion)
        assertEquals(BookPartSetKind.HOURS_LITURGY_TEXTS, hours.journeyContext!!.partSet)
        assertEquals(listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY), hours.journeyContext!!.parts.map { it.worship })
        assertTrue(hours.journeyContext!!.parts.all { it.date == "2026-11-13" })
        assertTrue(ServiceBookSelection.hoursLiturgyScope("Часы. Литургия. Панихида").contains("не входят"))
        assertFalse(ServiceBookSelection.hoursLiturgyScope("Часы. Литургия. Панихиды не будет").contains("не входят"))
        val evening = route("Вечерня. Полиелейная утреня")
        assertEquals(2, evening.journeyContext!!.parts.size); assertEquals(1, evening.journeyContext!!.schemaVersion)
        assertEquals("3", evening.journeyContext!!.parts.last().topLevel)
        assertEquals(WorshipKind.VIGIL, route("Всенощное бдение").activeKey.worship)
        assertNull(route("Всенощное бдение").journeyContext)
        for (selected in listOf(hours, evening)) {
            val context = selected.journeyContext!!
            assertNull(context.copy(parts = context.parts.take(1)).validatedFor(selected.activeKey, parish.id))
            assertNull(context.copy(schemaVersion = 2).validatedFor(selected.activeKey, parish.id))
            assertEquals(selected, BookRouteCodec.decode(BookRouteCodec.encode(selected)))
        }
    }

    @Test fun malformedTypedPartialNeverDropsItsMandatoryScopeIncludingUnsavedVariantAndWrongOwner() {
        val selected = route(); val context = selected.journeyContext!!; val key = selected.activeKey
        for (wrong in listOf(context.copy(schemaVersion = 1), context.copy(schemaVersion = 3),
            context.copy(partSet = null), context.copy(partSet = BookPartSetKind.EVENING_TEXTS),
            context.copy(parts = emptyList()), context.copy(parts = listOf(key, key)),
            context.copy(parishId = "foreign"), context.copy(civilDate = "2026-11-14"),
            context.copy(time = "24:00"), context.copy(title = "Всенощное бдение с акафистом"))) {
            assertNull(wrong.toString(), wrong.validatedFor(key, parish.id))
            assertNull(BookRoute(key, parish.id, wrong).validated())
            assertTrue(runCatching { BookRouteCodec.encode(BookRoute(key, parish.id, wrong)) }.isFailure)
            assertTrue(runCatching { BookReadingCodec.encode(position().copy(journeyContext = wrong)) }.isFailure)
        }
        for (saved in listOf(false, true)) for (wrongKey in listOf(key.copy(date = "2026-11-15"),
            key.copy(worship = WorshipKind.LITURGY), key.copy(churchType = "20")))
            assertNull(selected.withVariant(wrongKey, saved))
        assertNull(selected.copy(parishId = "foreign").validated())
    }

    @Test fun rawContextShapeErrorsRejectWholeRoutesAndPositionsInsteadOfLegacyFallback() {
        val routeJson = BookRouteCodec.encode(route()); val positionJson = BookReadingCodec.encode(position())
        val mutations: List<(com.google.gson.JsonObject) -> Unit> = listOf(
            { it.addProperty("schemaVersion", "2") }, { it.addProperty("schemaVersion", 2.0) },
            { it.addProperty("schemaVersion", 1) }, { it.remove("schemaVersion") },
            { it.addProperty("partSet", "UNKNOWN") }, { it.remove("partSet") },
            { it.add("partSet", com.google.gson.JsonNull.INSTANCE) }, { it.addProperty("partSet", 12) },
            { it.addProperty("parts", "not-an-array") }, { it.add("parts", com.google.gson.JsonArray()) },
            { it.getAsJsonArray("parts").add(it.getAsJsonArray("parts")[0].deepCopy()) },
            { it.addProperty("extra", true) }, { it.addProperty("time", 1700) },
            { it.addProperty("parishId", "another") }, { it.addProperty("civilDate", "2026-11-12") })
        for (mutate in mutations) for ((json, decode) in listOf(
            routeJson to { raw: String -> BookRouteCodec.decode(raw) },
            positionJson to { raw: String -> BookReadingCodec.decode(raw) })) {
            val value = JsonParser.parseString(json).asJsonObject; mutate(value.getAsJsonObject("journeyContext"))
            assertNull(value.toString(), decode(value.toString()))
        }
        for (field in listOf("extra", "wordsPerMinute", "showAltar")) {
            val value = JsonParser.parseString(positionJson).asJsonObject; value.addProperty(field, "invalid")
            assertNull(BookReadingCodec.decode(value.toString()))
        }
    }

    @Test fun duplicateAndEscapedMarkersCannotBeHiddenBeforeLossyGsonTreeParsing() {
        val raw = BookRouteCodec.encode(route())
        val context = gson.toJson(route().journeyContext)
        val oldContext = gson.toJson(route("Вечерня. Утреня").journeyContext)
        val pair = "\"journeyContext\":$context"
        val escapedName = "\"journey\\u0043ontext\""
        val hiddenContexts = listOf("$pair,\"journeyContext\":null", "\"journeyContext\":null,$pair",
            "$pair,$escapedName:$oldContext", "$escapedName:$oldContext,$pair")
        for (replacement in hiddenContexts) assertNull(BookRouteCodec.decode(raw.replace(pair, replacement)))
        for ((name, replacement) in listOf("schemaVersion" to "1", "partSet" to "null", "parts" to "[]")) {
            val element = JsonParser.parseString(context).asJsonObject.get(name).toString()
            val field = "\"$name\":$element"
            for (duplicated in listOf("$field,\"$name\":$replacement", "\"$name\":$replacement,$field"))
                assertNull(BookRouteCodec.decode(raw.replace(field, duplicated)))
        }
        val escaped = raw.replace("\"partSet\"", "\"part\\u0053et\"")
        assertEquals(route(), BookRouteCodec.decode(escaped))
        assertNull(BookRouteCodec.decode(raw.replace(pair, "\"journeyContext\":[$context]")))
        val savedRaw = BookReadingCodec.encode(position())
        assertNull(BookReadingCodec.decode(savedRaw.replace(pair, "$pair,\"journeyContext\":null")))
        assertNull(BookReadingCodec.decode(savedRaw.replace(pair, "\"journeyContext\":null,$pair")))
    }

    @Test fun nestedSourceReturnHasTheSameMandatoryShapeAndDuplicateProtection() {
        val selected = route(); val saved = position(selected)
        val changed = requireNotNull(selected.withDedication(PatronalFixtures.proof("presentation-mary"), saved))
        val raw = BookRouteCodec.encode(changed)
        assertEquals(changed, BookRouteCodec.decode(raw))
        assertEquals(selected, changed.returnToPreviousSource())
        val tree = JsonParser.parseString(raw).asJsonObject
        val nested = tree.getAsJsonObject("sourceReturn").getAsJsonObject("position")
        val nestedPair = "\"journeyContext\":" + nested.get("journeyContext")
        assertTrue(raw.contains(nestedPair))
        for (replacement in listOf("$nestedPair,\"journeyContext\":null", "\"journeyContext\":null,$nestedPair"))
            assertNull(BookRouteCodec.decode(raw.replace(nestedPair, replacement)))
        nested.getAsJsonObject("journeyContext").addProperty("partSet", "UNKNOWN")
        assertNull(BookRouteCodec.decode(tree.toString()))
    }

    @Test fun lastOnlyPartialPointerRestoresOrReportsUnreadableWithoutChangingAnySavedValue() {
        val selected = route(); val position = position(selected); val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs); assertTrue(store.save(position))
        prefs.values.keys.single { it.startsWith("position:") }.let(prefs.values::remove)
        assertEquals(BookReadingLookup.Found(position), store.lookup(selected.activeKey, parish.id))
        val last = prefs.values.keys.single { it.startsWith("last:") }; val raw = prefs.values[last] as String
        for (edit in listOf<(com.google.gson.JsonObject) -> Unit>(
            { it.addProperty("schemaVersion", 1) }, { it.getAsJsonArray("parts").add(it.getAsJsonArray("parts")[0].deepCopy()) },
            { it.addProperty("civilDate", "2026-11-12") }, { it.addProperty("partSet", "UNKNOWN") })) {
            val value = JsonParser.parseString(raw).asJsonObject; edit(value.getAsJsonObject("journeyContext"))
            prefs.values[last] = value.toString(); val before = prefs.values.toMap()
            assertEquals(BookReadingLookup.Unreadable, store.lookup(selected.activeKey, parish.id))
            assertFalse(store.saveReading(position)); assertEquals(before, prefs.values)
            assertEquals(BookReadingLookup.Absent, store.lookup(selected.activeKey.copy(date = "2026-11-15"), parish.id))
        }
    }

    @Test fun variantProfileDedicationReceiptAndReturnKeepScopeAndOnlyChangeAllowedSourceKeys() {
        val selected = route(); val original = selected.journeyContext!!
        for (saved in listOf(false, true)) {
            val key = selected.activeKey.copy(mainEvent = "0", secondEvent = "1")
            val changed = requireNotNull(selected.withVariant(key, saved))
            assertEquals(original.copy(parts = listOf(key)), changed.journeyContext)
            assertEquals(changed, BookRouteCodec.decode(BookRouteCodec.encode(changed)))
            if (saved) assertEquals(selected.activeKey, changed.returnToLastSuccessful()?.activeKey)
        }
        val proof = PatronalFixtures.proof("presentation-mary")
        val profile = requireNotNull(selected.withProfileForNewOpening(proof))
        assertEquals(original.copy(parts = listOf(profile.activeKey)), profile.journeyContext)
        assertEquals(proof.churchType, profile.activeKey.churchType); assertEquals(proof.id, profile.activeKey.patronalId)
        val changed = requireNotNull(selected.withDedication(proof, position()))
        assertNotNull(changed.dedicationReceiptIdentity()); assertEquals(selected, changed.returnToPreviousSource())
        assertEquals(changed, BookRouteCodec.decode(BookRouteCodec.encode(changed)))
        assertEquals(original.copy(parts = listOf(changed.activeKey)), changed.journeyContext)
        val otherOccurrence = original.copy(time = "18:00")
        assertNull(changed.copy(journeyContext = otherOccurrence.copy(parts = listOf(changed.activeKey))).validated())
        // Words in the additional akathist never become a temple profile or daily rank.
        for (title in listOf("Вечерня с акафистом святителю Николаю Чудотворцу", "Вечерня с акафистом иконе Всецарица")) {
            val key = route(title).activeKey
            assertEquals(ServiceBookSelection.churchType(parish), key.churchType)
            assertNull(key.patronalId); assertNull(key.topLevel); assertNull(key.mainEvent); assertNull(key.secondEvent)
        }
    }

    @Test fun onePartOfflinePreparationAndSearchCannotDownloadNeighboursOrChangeTheAcceptedBook() = runBlocking {
        val selected = route("Вечерня с акафистом", "2026-11-12")
        val html = javaClass.classLoader!!.getResource("book/vespers-2026-11-12.html")!!.readText()
        // The genuine source parser is invoked for this fixture's own service date.
        val key = ServiceBookKey("2026-11-12", WorshipKind.VESPERS)
        val book = ServiceBookParser().parse(Jsoup.parse(html, key.sourceUrl()), key, 1)
        val context = selected.journeyContext!!.copy(civilDate = "2026-11-11", parts = listOf(key))
        assertEquals(context, context.validatedFor(key, parish.id))
        val accepted = BookReadingPosition(key, book.contentHash, parish.id, book.blocks.first().id, book.chapters.first().id,
            journeyContext = context)
        val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs); assertTrue(store.save(accepted))
        val before = gson.toJson(book); val pointers = prefs.values.toMap(); val loads = mutableListOf<ServiceBookKey>()
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey) = book.takeIf { it.key == key }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
                loads += key; error("One published part has no adjacent service to load")
            }
        }
        val state = BookOfflinePreparation.prepare(book, context, parish.id, books, store)
        assertTrue(state.isComplete); assertEquals(1, state.total); assertEquals(book.contentHash, state.parts.single().savedHash)
        val corpus = BookSearchCorpusLoader.load(book, context, parish.id, books, store)
        assertEquals(listOf(book), corpus.books); assertNull(corpus.error); assertTrue(corpus.otherParts.isEmpty())
        assertTrue(loads.isEmpty()); assertEquals(before, gson.toJson(book)); assertEquals(pointers, prefs.values)
    }

    @Test fun aRealDedicationRebasePersistsItsSinglePartReceiptAndReturnsToTheSamePublishedOccurrence() = runBlocking {
        val previous = PatronalFixtures.parse("nicholas-may")
        val next = PatronalFixtures.parse("presentation-mary")
        val oldContext = BookJourneyContext(parish.id, previous.key.date, "08:00", "Литургия. Панихида",
            listOf(previous.key), schemaVersion = 2, partSet = BookPartSetKind.PARTIAL_PUBLISHED_TEXT)
        val oldRoute = requireNotNull(BookRoute(previous.key, parish.id, oldContext).validated())
        val chapter = previous.chapters.first { c -> c.blocks.any { !it.isAltarText && it.kind == BookBlockKind.PRAYER } }
        val block = chapter.blocks.first { !it.isAltarText && it.kind == BookBlockKind.PRAYER }
        val oldPosition = BookReadingPosition(previous.key, previous.contentHash, parish.id, block.id, chapter.id,
            fraction = .23f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN,
            journeyContext = oldContext)
        val selected = requireNotNull(oldRoute.withDedication(PatronalFixtures.proof("presentation-mary"), oldPosition))
        val changedContext = requireNotNull(selected.journeyContext)
        val transfer = BookPositionUpdate.rebaseForDedication(oldPosition, previous, next, changedContext)
        assertEquals(changedContext, transfer.position.journeyContext)
        assertEquals(oldContext.title, changedContext.title); assertEquals(oldContext.civilDate, changedContext.civilDate)
        assertEquals(oldContext.time, changedContext.time); assertEquals(next.key, changedContext.parts.single())
        val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs); assertTrue(store.save(oldPosition))
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey): ServiceBook? = listOf(previous, next).singleOrNull { it.key == key }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult =
                error("A source return cannot fetch a different edition")
        }
        val outcome = prepareDedicationReading(selected, BookLoadResult.Ready(next, fromCache = true),
            BookReadingLookup.Absent, books, store) { true }
        assertTrue(outcome is BookDedicationPreparation.Ready)
        assertEquals(BookReadingLookup.Found(transfer.position), store.lookup(next.key, parish.id))
        assertEquals(BookReadingLookup.Found(transfer.position), store.lookupDedicationReceipt(
            requireNotNull(selected.dedicationReceiptIdentity()), next.key, parish.id))
        assertEquals(oldRoute, preparePreviousDedication(selected, books, store) { true })
        assertEquals(oldPosition, store.positionFor(previous.key, parish.id))
    }

    @Test fun singletonAloneKeepsTypedRawNestedAndLastOnlyScopeMandatoryAfterBothOtherMarkersAreDamaged() {
        val selected = route(); val context = selected.journeyContext!!; val key = selected.activeKey
        val typed = context.copy(schemaVersion = 1, partSet = null)
        assertTrue(typed.requiresPublishedScope)
        val typedRoute = selected.copy(journeyContext = typed)
        assertNull(typedRoute.validated()); assertNull(typedRoute.withVariant(key.copy(mainEvent = "0"), false))
        assertTrue(runCatching { BookRouteCodec.encode(typedRoute) }.isFailure)
        assertTrue(runCatching { BookReadingCodec.encode(position().copy(journeyContext = typed)) }.isFailure)
        val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs); val saved = position()
        assertTrue(store.save(saved)); prefs.values.keys.single { it.startsWith("position:") }.let(prefs.values::remove)
        val lastKey = prefs.values.keys.single { it.startsWith("last:") }
        val versions = listOf<String?>("1", null, "null", "3", "\"bad\"")
        val kinds = listOf<String?>(null, "null", "\"UNKNOWN\"", "\"PUBLISHED_ORDER\"")
        for (version in versions) for (kind in kinds) {
            fun damaged(raw: String): String {
                val value = JsonParser.parseString(raw).asJsonObject
                val journey = value.getAsJsonObject("journeyContext")
                if (version == null) journey.remove("schemaVersion") else journey.add("schemaVersion", JsonParser.parseString(version))
                if (kind == null) journey.remove("partSet") else journey.add("partSet", JsonParser.parseString(kind))
                return value.toString()
            }
            val rawRoute = damaged(BookRouteCodec.encode(selected))
            val rawPosition = damaged(BookReadingCodec.encode(saved))
            assertTrue(StrictBookJson.hasPartialPublishedScope(rawRoute))
            assertNull(rawRoute, BookRouteCodec.decode(rawRoute)); assertNull(rawPosition, BookReadingCodec.decode(rawPosition))
            val brokenContext = JsonParser.parseString(rawPosition).asJsonObject.get("journeyContext")
            val nestedOnly = """{"sourceReturn":{"position":{"journeyContext":$brokenContext}}}"""
            assertTrue("Nested singleton must be seen without any outer marker", StrictBookJson.hasPartialPublishedScope(nestedOnly))
            val changed = requireNotNull(selected.withDedication(PatronalFixtures.proof("presentation-mary"), saved))
            val full = JsonParser.parseString(BookRouteCodec.encode(changed)).asJsonObject
            full.getAsJsonObject("sourceReturn").getAsJsonObject("position").add("journeyContext", brokenContext.deepCopy())
            assertNull(BookRouteCodec.decode(full.toString()))
            prefs.values[lastKey] = rawPosition; val before = prefs.values.toMap()
            assertEquals(BookReadingLookup.Unreadable, store.lookup(key, parish.id))
            assertFalse(store.saveReading(saved)); assertEquals(before, prefs.values)
            assertEquals(BookReadingLookup.Absent, store.lookup(key.copy(date = "2026-11-15"), parish.id))
            val foreign = JsonParser.parseString(rawPosition).asJsonObject.apply { addProperty("parishId", "foreign") }
            prefs.values[lastKey] = foreign.toString()
            assertEquals("Another owner in this last envelope is not this reading", BookReadingLookup.Absent, store.lookup(key, parish.id))
        }
    }

    @Test fun rawSingletonSignalSurvivesDuplicatePartsContextsEscapedNamesAndWrongTypeArrays() {
        val selected = route(); val context = selected.journeyContext!!
        val damaged = gson.toJson(context.copy(schemaVersion = 1, partSet = null))
        val old = gson.toJson(route("Вечерня. Утреня").journeyContext)
        val original = gson.toJson(context)
        val singleton = "\"parts\":" + gson.toJson(context.parts)
        val legacyParts = gson.toJson(route("Вечерня. Утреня").journeyContext!!.parts)
        val rawRoute = BookRouteCodec.encode(selected).replace(original, damaged)
        val rawPosition = BookReadingCodec.encode(position()).replace(original, damaged)
        for (json in listOf(rawRoute, rawPosition)) {
            val decode: (String) -> Any? = if (json == rawRoute) BookRouteCodec::decode else BookReadingCodec::decode
            for (pair in listOf("$singleton,\"parts\":$legacyParts", "\"parts\":$legacyParts,$singleton",
                "$singleton,\"pa\\u0072ts\":$legacyParts", "\"pa\\u0072ts\":$legacyParts,$singleton")) {
                val duplicated = json.replace(singleton, pair)
                assertTrue(StrictBookJson.hasPartialPublishedScope(duplicated)); assertNull(duplicated, decode(duplicated))
            }
            val contextPair = "\"journeyContext\":$damaged"
            for (pair in listOf("$contextPair,\"journeyContext\":$old", "\"journeyContext\":$old,$contextPair",
                "$contextPair,\"journey\\u0043ontext\":$old", "\"journey\\u0043ontext\":$old,$contextPair")) {
                val duplicated = json.replace(contextPair, pair)
                assertTrue(StrictBookJson.hasPartialPublishedScope(duplicated)); assertNull(duplicated, decode(duplicated))
            }
            val escaped = json.replace("\"parts\"", "\"pa\\u0072ts\"")
            assertTrue(StrictBookJson.hasPartialPublishedScope(escaped)); assertNull(decode(escaped))
            val wrongType = json.replace(contextPair, "\"journeyContext\":[$damaged]")
            assertTrue(StrictBookJson.hasPartialPublishedScope(wrongType)); assertNull(decode(wrongType))
        }
        val legacy = JsonParser.parseString(BookRouteCodec.encode(route("Вечерня. Утреня"))).asJsonObject
        legacy.getAsJsonObject("journeyContext").addProperty("partSet", "UNKNOWN")
        assertFalse(StrictBookJson.hasPartialPublishedScope(legacy.toString()))
        assertNull(requireNotNull(BookRouteCodec.decode(legacy.toString())).journeyContext)
    }

    @Test fun dependentListsUseAChoiceAndControlCharactersCannotEnterNewPartialMetadata() {
        for ((title, kind) in listOf("Вечерня без утрени и акафиста" to WorshipKind.VESPERS,
            "Литургия без панихиды и молебна" to WorshipKind.LITURGY,
            "Панихида после литургии и вечерни" to WorshipKind.VESPERS,
            "Панихида после литургии, вечерни и утрени" to WorshipKind.VESPERS)) {
            assertNull(title, ServiceBookSelection.kindFor(title)); assertNull(ServiceBookSelection.resolve(service(title), parish))
            assertEquals("Выбрать доступные тексты", ServiceBookSelection.readingAction(title))
            assertNull(ServiceBookSelection.readingScope(title)); assertNull(ServiceBookSelection.excludedScope(title))
            val available = route(if (kind == WorshipKind.VESPERS) "Вечерня с акафистом" else "Литургия. Панихида")
            assertNull(available.journeyContext!!.copy(title = title).validatedFor(available.activeKey, parish.id))
        }
        // These independent declarations and the existing single-negation cases remain useful.
        for (title in listOf("Литургия и молебен с акафистом", "Литургия. Панихида после нее"))
            assertTrue(route(title).journeyContext!!.isPartialPublishedText)
        for (title in listOf("Вечерня без акафиста", "Вечерня. Акафист отменен", "Вечерня без утрени"))
            assertNull(route(title).journeyContext)
        val valid = route("Литургия. Панихида")
        for (control in listOf('\u0000', '\t', '\n', '\r', '\u007f', '\u0085')) {
            val title = "Литургия.$control Панихида"
            val bad = valid.journeyContext!!.copy(title = title)
            assertNull(bad.validatedFor(valid.activeKey, parish.id)); assertNull(valid.copy(journeyContext = bad).validated())
            assertNull(ServiceBookSelection.kindFor(title)); assertNull(ServiceBookSelection.resolve(service(title), parish))
            assertNull(BookRouteCodec.decode(gson.toJson(valid.copy(journeyContext = bad))))
            assertNull(BookReadingCodec.decode(gson.toJson(position(valid).copy(journeyContext = bad))))
        }
        val ordinary = "Литургия. Молебен с акафистом иконе Богородицы \"Всецарица\""
        val selected = route(ordinary)
        assertEquals(ordinary, BookRouteCodec.decode(BookRouteCodec.encode(selected))!!.journeyContext!!.title)
        assertEquals(ordinary, BookReadingCodec.decode(BookReadingCodec.encode(position(selected)))!!.journeyContext!!.title)
    }

    @Test fun legacyUnknownPartSetFallbackAndStandaloneBytesRemainUnchanged() {
        val standalone = """{"activeKey":{"date":"2026-11-13","worship":"LITURGY","churchType":"30"},"parishId":"temple"}"""
        assertEquals(standalone, BookRouteCodec.encode(requireNotNull(BookRouteCodec.decode(standalone))))
        val legacy = route("Вечерня. Утреня")
        val tree = JsonParser.parseString(BookRouteCodec.encode(legacy)).asJsonObject
        tree.getAsJsonObject("journeyContext").addProperty("partSet", "UNKNOWN")
        assertNull(BookRouteCodec.decode(tree.toString())?.journeyContext)
    }
}
