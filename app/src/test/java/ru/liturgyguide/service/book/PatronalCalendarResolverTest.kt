package ru.liturgyguide.service.book

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate

/** Independent calendar evidence: source event IDs never come from title or date arithmetic. */
class PatronalCalendarResolverTest {
    private val catalog by lazy { requireNotNull(PatronalCatalog.decode(resource(PatronalCatalog.ASSET_NAME).toString(Charsets.UTF_8))) }

    @Test fun minimalRealFixturesFreezeEveryEventAndBothSourceAndFixtureDigests() {
        val provenance = JsonParser.parseString(resource("patronal-calendar/provenance.json").toString(Charsets.UTF_8)).asJsonObject
        assertEquals(1, provenance["schemaVersion"].asInt)
        assertTrue(provenance.getAsJsonObject("projection")["allEventsRetained"].asBoolean)
        assertTrue(provenance.getAsJsonObject("projection")["valueTypesRetained"].asBoolean)
        val records = provenance.getAsJsonArray("fixtures")
        assertEquals(realCases.size, records.size())
        realCases.forEach { expected ->
            val record = records.single { it.asJsonObject["fixture"].asString == expected.fixture }.asJsonObject
            val bytes = fixture(expected)
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            assertEquals(setOf("events"), root.keySet())
            assertEquals(expected.rawSha256, record["rawSha256"].asString)
            assertEquals(url(expected.date), record["rawUrl"].asString)
            assertEquals(expected.rawBytes, record["rawBytes"].asInt)
            assertEquals(sha256(bytes), record["fixtureSha256"].asString)
            assertEquals(bytes.size, record["fixtureBytes"].asInt)
            assertEquals(expected.eventCount, record["eventCount"].asInt)
            assertEquals(expected.eventCount, root.getAsJsonArray("events").size())
            root.getAsJsonArray("events").forEach {
                assertEquals(setOf("id", "title", "minea_id", "church_type"), it.asJsonObject.keySet())
            }
            val event = root.getAsJsonArray("events").single { it.asJsonObject["id"].asString == expected.id }.asJsonObject
            assertEquals(expected.mineaId, event["minea_id"].asString)
            assertEquals(expected.churchType, event["church_type"].asString)
            assertEquals(expected.id, record["confirmedEventId"].asString)
            assertEquals(expected.mineaId, record["mineaId"].asString)
            assertEquals(expected.churchType, record["churchType"].asString)
            assertEquals(expected.date, record["calendarDate"].asString)
        }
        // Preserve the authentic numeric minea_id and leading-space title of a non-applicable row.
        val firstMay = fixtureRoot(realCases[0]).getAsJsonArray("events")[0].asJsonObject
        assertTrue(firstMay["minea_id"].asJsonPrimitive.isNumber)
        assertEquals(7505, firstMay["minea_id"].asInt)
        assertTrue(firstMay["title"].asString.startsWith(" "))
    }

    @Test fun sixRealCalendarDaysConfirmOnlyTheirExactMineaEvents() = runBlocking {
        realCases.forEach { expected ->
            val proofs = MemoryProofs()
            val transport = RecordingTransport { requested ->
                assertEquals(url(expected.date), requested)
                // The public endpoint is observed serving JSON under this actual MIME header.
                ScheduleResponse(requested, fixture(expected), "TEXT/HTML;charset=UTF-8")
            }
            val result = PatronalCalendarResolver(proofs, transport).resolve(entry(expected.mineaId), 2026)
            assertTrue(expected.fixture, result is PatronalResolution.Confirmed)
            val proof = (result as PatronalResolution.Confirmed).proof
            assertEquals(expected.id, proof.id)
            assertEquals(expected.mineaId, proof.mineaId)
            assertEquals(expected.churchType, proof.churchType)
            assertEquals(expected.date, proof.calendarDate)
            assertEquals(url(expected.date), proof.sourceUrl)
            assertEquals(fixtureRoot(expected).getAsJsonArray("events")
                .single { it.asJsonObject["id"].asString == expected.id }.asJsonObject["title"].asString, proof.title)
            assertTrue(proof.isValid())
            assertEquals(listOf(url(expected.date)), transport.calls)
            assertEquals(listOf(BYTE_LIMIT), transport.limits)
            assertEquals(listOf(proof), proofs.attempted)
            assertEquals(proof, proofs.find(expected.id))
        }
    }

    @Test fun normalJsonMimeTypesAndStrictIntegerStringOrNumberFieldsAreAccepted() = runBlocking {
        for (mime in listOf("application/json", "application/json; charset=utf-8", "text/json")) {
            val proofs = MemoryProofs()
            val event = event().apply {
                addProperty("id", "111300")
                addProperty("minea_id", 11183)
                addProperty("church_type", 34)
            }
            val transport = RecordingTransport { jsonResponse(it, json(event), mime) }
            val result = PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026)
            assertEquals(mime, "111300", (result as PatronalResolution.Confirmed).proof.id)
            assertEquals(listOf(url(BASE_DATE)), transport.calls)
        }
    }

    @Test fun mayAndDecemberNicholasNeverSubstituteForEachOtherByTitleOrChurchType() = runBlocking {
        for ((wanted, other) in listOf(realCases[0] to realCases[1], realCases[1] to realCases[0])) {
            val proofs = MemoryProofs()
            val transport = RecordingTransport { ScheduleResponse(it, fixture(other), "application/json") }
            assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(wanted.mineaId), 2026))
            assertWindow(transport, LocalDate.parse(wanted.date))
            assertTrue(proofs.attempted.isEmpty())
            assertNull(proofs.find(other.id))
        }
    }

    @Test fun partialMineaIdsNeverBecomeMatchesEvenWhenTitleAndChurchTypeMatch() = runBlocking {
        for (mineaId in listOf("1118", "111830")) {
            val proofs = MemoryProofs()
            val body = json(event(mineaId = mineaId))
            val transport = RecordingTransport { jsonResponse(it, body) }
            assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026))
            assertWindow(transport, LocalDate.parse(BASE_DATE))
            assertTrue(proofs.attempted.isEmpty())
        }
    }

    @Test fun twoExactEventsOnTheBaseDayRemainAmbiguousAndBothAreRemembered() = runBlocking {
        val proofs = MemoryProofs()
        val transport = RecordingTransport { jsonResponse(it, json(event(), event(id = 111301))) }
        val result = PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026)
        assertTrue(result is PatronalResolution.Ambiguous)
        val choices = (result as PatronalResolution.Ambiguous).choices
        assertEquals(setOf("111300", "111301"), choices.map { it.id }.toSet())
        assertTrue(choices.all { it.mineaId == "11183" && it.calendarDate == BASE_DATE && it.isValid() })
        assertEquals(choices.toSet(), proofs.attempted.toSet())
        assertEquals(listOf(url(BASE_DATE)), transport.calls)
    }

    @Test fun aTransferredExactEventRequiresTheCompleteBoundedNeighbourWindowBeforeRemembering() = runBlocking {
        val base = LocalDate.parse(BASE_DATE)
        val transferred = base.plusDays(2)
        val proofs = MemoryProofs()
        val transport = RecordingTransport { requested ->
            assertTrue("A partial calendar search must not write evidence", proofs.attempted.isEmpty())
            jsonResponse(requested, if (requested == url(transferred.toString())) json(event()) else EMPTY_DAY)
        }
        val result = PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026) as PatronalResolution.Confirmed
        assertEquals("111300", result.proof.id)
        assertEquals(transferred.toString(), result.proof.calendarDate)
        assertEquals(url(transferred.toString()), result.proof.sourceUrl)
        assertWindow(transport, base)
        assertEquals(listOf(result.proof), proofs.attempted)
    }

    @Test fun exactEventsOnTwoNeighbouringDaysRemainAmbiguousAfterTheWholeWindow() = runBlocking {
        val base = LocalDate.parse(BASE_DATE)
        val first = base.minusDays(2).toString()
        val second = base.plusDays(2).toString()
        val proofs = MemoryProofs()
        val transport = RecordingTransport { requested ->
            assertTrue(proofs.attempted.isEmpty())
            jsonResponse(requested, when (requested) {
                url(first) -> json(event())
                url(second) -> json(event(id = 111301))
                else -> EMPTY_DAY
            })
        }
        val result = PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026) as PatronalResolution.Ambiguous
        assertEquals(setOf("111300" to first, "111301" to second), result.choices.map { it.id to it.calendarDate }.toSet())
        assertEquals(result.choices.toSet(), proofs.attempted.toSet())
        assertWindow(transport, base)
    }

    @Test fun aLaterRequiredNetworkFailureCannotTurnAnEarlierMatchIntoAFalseUniqueChoice() = runBlocking {
        val proofs = MemoryProofs()
        lateinit var transport: RecordingTransport
        transport = RecordingTransport { requested ->
            assertTrue(proofs.attempted.isEmpty())
            when (transport.calls.size) {
                2 -> jsonResponse(requested, json(event()))
                15 -> throw IOException("The last required neighbour is unavailable")
                else -> jsonResponse(requested, EMPTY_DAY)
            }
        }
        val result = assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026))
        assertTrue(result.retryable)
        assertWindow(transport, LocalDate.parse(BASE_DATE))
        assertTrue(proofs.attempted.isEmpty())
    }

    @Test fun aBoundedTransferCanCrossTheCivilYearBoundaryAfterCheckingTheWholeWindow() = runBlocking {
        val cases = listOf(
            Triple(PatronalDateRule.FixedJulian(12, 19), LocalDate.of(2026, 1, 1), "2025-12-31"),
            Triple(PatronalDateRule.FixedJulian(12, 18), LocalDate.of(2026, 12, 31), "2027-01-01")
        )
        for ((rule, base, transferred) in cases) {
            val proofs = MemoryProofs()
            val transport = RecordingTransport {
                assertTrue(proofs.attempted.isEmpty())
                jsonResponse(it, if (it == url(transferred)) json(event()) else EMPTY_DAY)
            }
            val synthetic = entry().copy(dateRule = rule)
            val result = PatronalCalendarResolver(proofs, transport).resolve(synthetic, 2026) as PatronalResolution.Confirmed
            assertEquals(transferred, result.proof.calendarDate)
            assertEquals(url(transferred), result.proof.sourceUrl)
            assertWindow(transport, base)
            assertEquals(15, transport.calls.size)
            assertEquals(listOf(result.proof), proofs.attempted)
        }
    }

    @Test fun neighbourQueriesNeverEscapeTheSupportedCalendarYearRange() = runBlocking {
        val cases = listOf(
            PatronalDateRule.FixedJulian(12, 20) to LocalDate.of(1900, 1, 1),
            PatronalDateRule.FixedJulian(12, 16) to LocalDate.of(2200, 12, 31)
        )
        for ((rule, base) in cases) {
            val proofs = MemoryProofs()
            val transport = RecordingTransport { jsonResponse(it, EMPTY_DAY) }
            assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry().copy(dateRule = rule), base.year))
            assertWindow(transport, base)
            assertEquals(8, transport.calls.size)
            assertTrue(proofs.attempted.isEmpty())
        }
    }

    @Test fun aNonLeapJulianFebruary29ChecksBothFebruary28AndMarch1Fallbacks() = runBlocking {
        val fallbackDates = setOf("2026-03-13", "2026-03-14")
        val proofs = MemoryProofs()
        val synthetic = entry().copy(dateRule = PatronalDateRule.FixedJulian(2, 29))
        assertTrue(PatronalDates.candidates(requireNotNull(synthetic.dateRule), 2026).isEmpty())
        val transport = RecordingTransport { requested ->
            assertTrue(proofs.attempted.isEmpty())
            jsonResponse(requested, if (requested == url("2026-03-13")) json(event()) else EMPTY_DAY)
        }
        val result = PatronalCalendarResolver(proofs, transport).resolve(synthetic, 2026) as PatronalResolution.Confirmed
        assertEquals(fallbackDates.map(::url).toSet(), transport.calls.toSet())
        assertEquals(2, transport.calls.size)
        assertEquals(listOf(BYTE_LIMIT, BYTE_LIMIT), transport.limits)
        assertEquals("2026-03-13", result.proof.calendarDate)
        assertEquals(listOf(result.proof), proofs.attempted)
    }

    @Test fun theSecondFebruaryFallbackFailureCannotConfirmTheFirstFallbackMatch() = runBlocking {
        val proofs = MemoryProofs()
        val synthetic = entry().copy(dateRule = PatronalDateRule.FixedJulian(2, 29))
        val transport = RecordingTransport { requested ->
            if (requested == url("2026-03-14")) throw IOException("Second required fallback failed")
            jsonResponse(requested, json(event()))
        }
        val result = assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(synthetic, 2026))
        assertTrue(result.retryable)
        assertEquals(setOf(url("2026-03-13"), url("2026-03-14")), transport.calls.toSet())
        assertEquals(2, transport.calls.size)
        assertTrue(proofs.attempted.isEmpty())
    }

    @Test fun aNewResolutionAlwaysChecksFreshCalendarEvidenceInsteadOfReusingAnOldProof() = runBlocking {
        val proofs = MemoryProofs()
        lateinit var transport: RecordingTransport
        transport = RecordingTransport { jsonResponse(it, json(event(id = 111299 + transport.calls.size))) }
        val resolver = PatronalCalendarResolver(proofs, transport)
        val first = resolver.resolve(entry(), 2026) as PatronalResolution.Confirmed
        val second = resolver.resolve(entry(), 2026) as PatronalResolution.Confirmed
        assertEquals("111300", first.proof.id)
        assertEquals("111301", second.proof.id)
        assertEquals(listOf(url(BASE_DATE), url(BASE_DATE)), transport.calls)
        assertEquals(listOf(BYTE_LIMIT, BYTE_LIMIT), transport.limits)
        assertEquals(listOf(first.proof, second.proof), proofs.attempted)
    }

    @Test fun aStaleStoredProofCannotMaskAFailedFreshCalendarRequest() = runBlocking {
        val proofs = MemoryProofs()
        lateinit var transport: RecordingTransport
        transport = RecordingTransport { requested ->
            if (transport.calls.size == 1) jsonResponse(requested, json(event())) else throw IOException("Fresh request failed")
        }
        val resolver = PatronalCalendarResolver(proofs, transport)
        val first = resolver.resolve(entry(), 2026) as PatronalResolution.Confirmed
        assertTrue(assertUnavailable(resolver.resolve(entry(), 2026)).retryable)
        assertEquals(listOf(url(BASE_DATE), url(BASE_DATE)), transport.calls)
        assertEquals(listOf(first.proof), proofs.attempted)
    }

    @Test fun wrongResponseUrlsAndWrongMimeTypesCannotSupplyProofs() = runBlocking {
        val bodies = listOf<(String) -> ScheduleResponse>(
            { ScheduleResponse(it.replace("azbyka.ru", "azbyka.ru.evil.test"), json(event()).toByteArray(), "application/json") },
            { ScheduleResponse(it.replace("2026-05-22", "2026-05-23"), json(event()).toByteArray(), "application/json") },
            { ScheduleResponse("$it?copy=1", json(event()).toByteArray(), "application/json") },
            { ScheduleResponse("$it#copy", json(event()).toByteArray(), "application/json") },
            { ScheduleResponse(it.replace("https://", "http://"), json(event()).toByteArray(), "application/json") },
            { jsonResponse(it, json(event()), "image/png") },
            { jsonResponse(it, json(event()), "text/plain") },
            { jsonResponse(it, "<html><body>Calendar temporarily unavailable</body></html>", "text/html;charset=UTF-8") }
        )
        for (response in bodies) {
            val proofs = MemoryProofs()
            val transport = RecordingTransport { response(it) }
            assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026))
            assertEquals(listOf(url(BASE_DATE)), transport.calls)
            assertTrue(proofs.attempted.isEmpty())
        }
    }

    @Test fun invalidUtf8AndOversizedBodiesAreRejectedEvenIfTheTransportReturnsThem() = runBlocking {
        val valid = json(event())
        val marker = "Перенесение мощей святителя Николая"
        assertTrue(valid.contains(marker))
        val split = valid.split(marker)
        val invalidUtf8 = split[0].toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + split[1].toByteArray()
        val oversized = valid.toByteArray() + ByteArray(BYTE_LIMIT) { ' '.code.toByte() }
        for (bytes in listOf(invalidUtf8, oversized)) {
            val proofs = MemoryProofs()
            val transport = RecordingTransport { ScheduleResponse(it, bytes, "application/json") }
            assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026))
            assertEquals(listOf(BYTE_LIMIT), transport.limits)
            assertEquals(listOf(url(BASE_DATE)), transport.calls)
            assertTrue(proofs.attempted.isEmpty())
        }
    }

    @Test fun duplicateJsonKeysAndDuplicateEventIdsFailClosedBeforeAnyProofWrite() = runBlocking {
        val item = event().toString()
        val duplicateField = item.replace("\"id\":111300", "\"id\":111301,\"id\":111300")
        val bodies = listOf(
            "{\"events\":[],\"events\":[$item]}",
            "{\"events\":[$duplicateField]}",
            "{\"events\":[$item],\"unused\":{\"value\":0,\"value\":1}}",
            json(event(), event().apply { addProperty("minea_id", "9906") })
        )
        for (body in bodies) {
            val proofs = MemoryProofs()
            val transport = RecordingTransport { jsonResponse(it, body) }
            assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026))
            assertEquals(listOf(url(BASE_DATE)), transport.calls)
            assertTrue(proofs.attempted.isEmpty())
        }
    }

    @Test fun invalidRequiredFieldTokensAreNeverCoercedOrSilentlySkipped() = runBlocking {
        val malformed = mutableListOf<JsonObject>()
        for (field in listOf("id", "minea_id", "church_type")) {
            for (token in listOf("true", "null", "0", "-1", "1e3", "1.0", "\"01\"", "\" 11183\"", "{}", "[]")) {
                malformed += event().apply { add(field, JsonParser.parseString(token)) }
            }
        }
        for (field in listOf("id", "title", "minea_id", "church_type")) {
            malformed += event().apply { remove(field) }
        }
        for (token in listOf("true", "null", "12", "{}", "[]", "\"\"")) {
            malformed += event().apply { add("title", JsonParser.parseString(token)) }
        }
        malformed.forEach { bad ->
            val proofs = MemoryProofs()
            // A valid first row cannot hide a malformed later row.
            val transport = RecordingTransport { jsonResponse(it, json(event(id = 111301), bad)) }
            assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026))
            assertEquals("Malformed row: $bad", listOf(url(BASE_DATE)), transport.calls)
            assertTrue("Malformed row: $bad", proofs.attempted.isEmpty())
        }
    }

    @Test fun rootEventArrayDepthAndNodeLimitsRejectTheWholeResponse() = runBlocking {
        val body = json(event())
        val tooDeep = "{\"events\":[${event()}],\"unused\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}"
        val tooManyNodes = "{\"events\":[${event()}],\"unused\":[" + List(100_001) { "0" }.joinToString(",") + "]}"
        val tooManyEvents = json(*(1..101).map { event(id = it) }.toTypedArray())
        val invalid = listOf("[]", "null", "{}", "{", "$body $body", "{\"events\":null}",
            "{\"events\":{}}", "{\"events\":[null]}", "{\"events\":[[]]}", tooDeep, tooManyNodes, tooManyEvents)
        for (candidate in invalid) {
            val proofs = MemoryProofs()
            val transport = RecordingTransport { jsonResponse(it, candidate) }
            assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026))
            assertEquals(listOf(url(BASE_DATE)), transport.calls)
            assertTrue(proofs.attempted.isEmpty())
        }
    }

    @Test fun blankOrUnknownChurchTypesCannotBecomeASelectableDedication() = runBlocking {
        for (churchType in listOf("", "99")) {
            val proofs = MemoryProofs()
            val transport = RecordingTransport { jsonResponse(it, json(event(churchType = churchType))) }
            assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026))
            assertTrue(proofs.attempted.isEmpty())
        }
    }

    @Test fun unsupportedYearsOrUnusableMetadataNeverStartCalendarRequests() = runBlocking {
        val proofs = MemoryProofs()
        val transport = RecordingTransport { error("There is no bounded candidate date to request") }
        val resolver = PatronalCalendarResolver(proofs, transport)
        for (year in listOf(1899, 2201)) assertUnavailable(resolver.resolve(entry(), year))
        assertUnavailable(resolver.resolve(entry().copy(dateRule = null), 2026))
        assertUnavailable(resolver.resolve(entry().copy(candidateEligible = false), 2026))
        assertTrue(transport.calls.isEmpty())
        assertTrue(proofs.attempted.isEmpty())
    }

    @Test fun rejectedOrFailedStorageWritesCannotReportConfirmedEvidence() = runBlocking {
        for (proofs in listOf(MemoryProofs(acceptWrites = false), MemoryProofs(throwOnWrite = true))) {
            val transport = RecordingTransport { jsonResponse(it, json(event())) }
            val result = assertUnavailable(PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026))
            assertTrue(result.retryable)
            assertEquals(listOf(url(BASE_DATE)), transport.calls)
            assertEquals(1, proofs.attempted.size)
            assertNull(proofs.find("111300"))
        }
    }

    @Test fun transportCancellationIsPropagatedInsteadOfReturningUnavailable() = runBlocking {
        val cancelled = CancellationException("Calendar selection was cancelled")
        val proofs = MemoryProofs()
        val transport = RecordingTransport { throw cancelled }
        var failure: CancellationException? = null
        try {
            PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026)
        } catch (caught: CancellationException) {
            failure = caught
        }
        // Coroutine stack recovery may copy the exception while retaining its cause.
        val propagated = requireNotNull(failure) { "Cancellation must escape, not become Unavailable" }
        assertEquals(cancelled.message, propagated.message)
        assertTrue("The transport cancellation must remain in the propagated cause chain",
            generateSequence<Throwable>(propagated) { it.cause }.take(8).any { it === cancelled })
        assertEquals(listOf(url(BASE_DATE)), transport.calls)
        assertTrue(proofs.attempted.isEmpty())
    }

    @Test fun cancellingAHeldCalendarRequestWritesNoProofAndStartsNoNeighbourRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val held = CompletableDeferred<ScheduleResponse>()
        val proofs = MemoryProofs()
        val transport = RecordingTransport {
            started.complete(Unit)
            held.await()
        }
        val pending = async { PatronalCalendarResolver(proofs, transport).resolve(entry(), 2026) }
        started.await()
        pending.cancel()
        try {
            pending.await()
            fail("A cancelled selection must stay cancelled")
        } catch (_: CancellationException) {
            assertTrue(pending.isCancelled)
        }
        assertEquals(listOf(url(BASE_DATE)), transport.calls)
        assertTrue(proofs.attempted.isEmpty())
    }

    private fun entry(mineaId: String = "11183") = catalog.entries.single { it.mineaId == mineaId }
    private fun fixture(case: RealCase) = resource("patronal-calendar/${case.fixture}")
    private fun fixtureRoot(case: RealCase) = JsonParser.parseString(fixture(case).toString(Charsets.UTF_8)).asJsonObject
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun url(date: String) = "https://azbyka.ru/worships/calendar/api/$date/"
    private fun jsonResponse(url: String, body: String, mime: String = "application/json") = ScheduleResponse(url, body.toByteArray(Charsets.UTF_8), mime)
    private fun event(id: Int = 111300, mineaId: String = "11183", churchType: String = "34") = JsonObject().apply {
        addProperty("id", id)
        addProperty("title", "Перенесение мощей святителя Николая")
        addProperty("minea_id", mineaId)
        addProperty("church_type", churchType)
    }
    private fun json(vararg events: JsonElement) = JsonObject().apply {
        add("events", JsonArray().apply { events.forEach { add(it) } })
    }.toString()
    private fun assertUnavailable(result: PatronalResolution): PatronalResolution.Unavailable {
        assertTrue("Expected unavailable, got $result", result is PatronalResolution.Unavailable)
        return (result as PatronalResolution.Unavailable).also { assertTrue(it.message.isNotBlank()) }
    }
    private fun assertWindow(transport: RecordingTransport, base: LocalDate) {
        val expected = (-7L..7L).map(base::plusDays).filter { it.year in 1900..2200 }.map { url(it.toString()) }.toSet()
        assertEquals(url(base.toString()), transport.calls.first())
        assertEquals(expected, transport.calls.toSet())
        assertEquals(expected.size, transport.calls.size)
        assertTrue(transport.calls.size <= 15)
        assertEquals(List(expected.size) { BYTE_LIMIT }, transport.limits)
    }

    private class MemoryProofs(private val acceptWrites: Boolean = true, private val throwOnWrite: Boolean = false) : PatronalProofRegistry {
        private val saved = linkedMapOf<String, PatronalEventProof>()
        val attempted = mutableListOf<PatronalEventProof>()
        override fun find(id: String) = saved[id]
        override fun rememberProof(proof: PatronalEventProof): Boolean {
            attempted += proof
            if (throwOnWrite) throw IOException("Synthetic proof storage failure")
            if (!acceptWrites) return false
            saved[proof.id] = proof
            return true
        }
    }

    private class RecordingTransport(private val respond: suspend (String) -> ScheduleResponse) : ScheduleTransport {
        val calls = mutableListOf<String>()
        val limits = mutableListOf<Int>()
        override suspend fun fetch(url: String): ScheduleResponse = error("Calendar resolver must use the bounded fetch")
        override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse {
            calls += url
            limits += maximumBytes
            return respond(url)
        }
    }

    private data class RealCase(val label: String, val date: String, val id: String, val mineaId: String,
        val churchType: String, val eventCount: Int, val rawBytes: Int, val rawSha256: String) {
        val fixture get() = "calendar-$label-$date.json"
    }

    companion object {
        private const val BASE_DATE = "2026-05-22"
        private const val BYTE_LIMIT = 1_500_000
        private const val EMPTY_DAY = "{\"events\":[]}"
        private val realCases = listOf(
            RealCase("nicholas-may", "2026-05-22", "111300", "11183", "34", 9, 446663,
                "bd73fe248a5f0d018af0010f19f2aca249010d9ca6da1bc89d166b2aac90db52"),
            RealCase("nicholas-dec", "2026-12-19", "129540", "9906", "34", 1, 257945,
                "c3ce123ff076103d3b300b55c7f6626f25ace2b67cc9f233ec5c526440fb5e19"),
            RealCase("trinity", "2026-05-31", "101360", "9197", "10", 10, 142135,
                "9680489702d5abc23add3ea202a57ca828136a31c84f4996ac999c827ec79cac"),
            RealCase("presentation", "2026-02-15", "104530", "10885", "10", 3, 31048,
                "25bb2c34470d705490d210bfc308602c10e31ea9e2e0be116f9b113543cd8d73"),
            RealCase("elizabeth", "2026-07-18", "115980", "9369", "30", 7, 96559,
                "3044503cf8853275296015adae1cb870590216b7845f7d9529721ea174aae511"),
            RealCase("presentation-mary", "2026-12-04", "128390", "14581", "20", 1, 11994,
                "2f57a400b6640f43a6d5707025f4184b47d16364c3b2a4fc5c43d1f247420fbf")
        )
    }
}
