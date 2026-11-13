package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class OwnContactCandidateProviderTest {
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/contact-pool/$name")).use { it.readBytes() }
    private val oracle get() = JsonParser.parseString(bytes("oracle.json").toString(Charsets.UTF_8)).asJsonObject
    private val url get() = oracle["contact"].asJsonObject["url"].asString
    private val home get() = oracle["home"].asJsonObject["url"].asString
    private val clock = Clock.fixed(Instant.parse("2026-11-13T18:00:00Z"), ZoneOffset.UTC)
    private fun parish(): Parish {
        val source = oracle["selected"].asJsonObject
        val point = source["center"].asJsonObject
        return Parish(source["id"].asString, source["name"].asString, "", "", "", "", "", "Europe/Moscow",
            source["sourceUrl"].asString, "", "", "", emptyList(), "", "",
            point["latitude"].asDouble, point["longitude"].asDouble)
    }
    private fun origin() = Gson().fromJson(oracle["metadataOrigin"], PublisherSeedProvenance::class.java)
    private fun seed(role: PublisherSeedRole = PublisherSeedRole.CONTACT_REFERENCE, target: String = url,
        scope: String = home, metadata: PublisherSeedProvenance = origin()) = PublisherSeed(target, role, scope, metadata)
    private fun response(target: String = url, body: ByteArray = bytes("contacts.html")) = ScheduleResponse(target, body, "text/html;charset=UTF-8")
    private fun provider(transport: ScheduleTransport, seeds: List<PublisherSeed> = listOf(seed()), timeout: Long = 25_000) =
        OwnContactCandidateProvider(transport, LocalPublisherSeedPool(seeds), clock, timeout)
    private fun changed(change: (org.jsoup.nodes.Element) -> Unit): ByteArray {
        val doc = Jsoup.parse(bytes("contacts.html").toString(Charsets.UTF_8), url)
        val block = doc.select(".t195__text").single { it.text().contains("Приписные храмы") }
        change(block)
        return doc.outerHtml().toByteArray()
    }

    @Test fun realOrdinaryOsmGetsOneFreshConfirmableInsufficientCandidateWithOnePageProvenance() = runBlocking {
        val parish = parish(); val before = Gson().toJson(parish)
        val requested = mutableListOf<String>()
        val result = provider(ScheduleTransport { requested += it; response(it) }).discover(parish, emptySet())
        assertEquals(listOf(url), requested)
        val candidate = result.candidates.single()
        val expected = oracle["contact"].asJsonObject
        assertEquals("osm:way:226509018", parish.id)
        assertEquals("", parish.website); assertEquals("", parish.city); assertEquals("", parish.address); assertEquals("", parish.phone)
        assertEquals(OwnContactCandidateProvider.PROVIDER, candidate.provider)
        assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, candidate.outcome)
        assertTrue(candidate.canConfirm(parish)); assertFalse(candidate.verificationUnavailable)
        assertTrue(result.incomplete); assertFalse(result.unavailable)
        assertEquals(expected["heading"].asString, candidate.name)
        assertEquals(expected["locality"].asString, candidate.address)
        assertEquals(expected["latitude"].asDouble, candidate.latitude, 1e-10)
        assertEquals(expected["longitude"].asDouble, candidate.longitude, 1e-10)
        assertEquals(expected["sha256"].asString, ScheduleSourceCandidate.sha(bytes("contacts.html")))
        assertEquals(url, candidate.sourceUrl); assertEquals(url, candidate.evidenceUrl)
        assertEquals(expected["sha256"].asString, candidate.evidenceSha256)
        assertEquals(ScheduleGeometryEvidence(url, candidate.evidenceSha256), candidate.geometryEvidence)
        assertEquals(ScheduleEntityIdentity.fingerprint(parish), candidate.selectedIdentity)
        assertTrue(result.observedIds.contains(candidate.providerObjectId))
        assertEquals(listOf(origin()), requireNotNull(candidate.ownContact).seedOrigins)
        assertEquals(before, Gson().toJson(parish))
    }

    @Test fun directReferencesRunBeforeNearerWebsitesAndExactUrlDedupKeepsBothMetadataOwners() = runBlocking {
        val parish = parish()
        val metadata = origin().copy(recordId = "another-record", recordName = "Другой владелец metadata")
        val closer = origin().copy(recordId = "nearer-owner", latitude = requireNotNull(parish.latitude), longitude = requireNotNull(parish.longitude))
        val seeds = listOf(seed(PublisherSeedRole.WEBSITE, "https://nearby.example/", "https://nearby.example/", closer),
            seed(metadata = metadata), seed(), seed(PublisherSeedRole.WEBSITE, home))
        val calls = mutableListOf<String>()
        val result = provider(ScheduleTransport { calls += it; response(it) }, seeds).discover(parish, emptySet())
        assertEquals(listOf(url), calls)
        assertEquals(setOf(origin(), metadata), requireNotNull(result.candidates.single().ownContact).seedOrigins.toSet())
    }

    @Test fun websiteSeedFollowsOnlyThePublishedSamePublisherContactNavigation() = runBlocking {
        assertEquals(oracle["home"].asJsonObject["sha256"].asString, ScheduleSourceCandidate.sha(bytes("home.html")))
        val calls = mutableListOf<String>()
        val result = provider(ScheduleTransport {
            calls += it
            when (it) { home -> response(it, bytes("home.html")); url -> response(it); else -> error("No guessed URL: $it") }
        }, listOf(seed(PublisherSeedRole.WEBSITE, home))).discover(parish(), emptySet())
        assertEquals(listOf(home, url), calls)
        assertTrue(result.candidates.single().canConfirm(parish()))
        assertTrue(calls.none { it.endsWith("/null") || it.contains("google") })
    }

    @Test fun phoneOrNeutralMarkupUpdateChangesShaButNotCandidateIdAndEachAttemptFetchesAgain() = runBlocking {
        val updated = changed { block ->
            block.select("span").first()!!.tagName("strong")
            block.select("span").single { it.text().startsWith("Телефон") }.text("Телефон 8-915-346-47-53")
        }
        var calls = 0
        val provider = provider(ScheduleTransport { calls++; response(it, if (calls == 1) bytes("contacts.html") else updated) })
        val first = provider.discover(parish(), emptySet()).candidates.single()
        val second = provider.discover(parish(), emptySet()).candidates.single()
        assertEquals(2, calls); assertEquals(first.id, second.id); assertNotEquals(first.evidenceSha256, second.evidenceSha256)
        assertTrue(second.canConfirm(parish()))
        assertFalse(second.canConfirm(parish().copy(phone = "8-915-346-47-52")))
    }

    @Test fun siblingParentAndChapelPointsCannotBorrowTheChildNameOrCoordinates() = runBlocking {
        for (kind in listOf("parent", "chapel")) {
            val point = oracle["counterpoints"].asJsonObject[kind].asJsonObject
            val selected = parish().copy(latitude = point["latitude"].asDouble, longitude = point["longitude"].asDouble)
            val result = provider(ScheduleTransport { response(it) }).discover(selected, emptySet())
            assertTrue(result.candidates.isEmpty())
        }
        val relocated = changed { it.select("em").single { e -> e.text().startsWith("широта:") }.remove() }
        assertTrue(PublishedChildContactParser.parse(response(body = relocated)).children.isEmpty())
    }

    @Test fun qualifierFirstOrExplicitSecondHeadingBeforeTheOnlyDmsCannotDonateItsPointToTheFirstChild() = runBlocking {
        for ((tag, heading) in listOf("h3" to "Нижний храм святителя Николая", "h2" to "Крестильный храм святого Иоанна",
            "h1" to "Новый собор святителя Николая", "h4" to "Верхняя часовня святой Анны",
            "h5" to "Соседнее помещение", "h6" to "Другая часть здания", "span" to "Старый храм святителя Николая")) {
            val body = changed { block ->
                block.select("em").single { it.text().startsWith("Координаты для GPS") }
                    .before("<$tag>$heading</$tag><br>")
            }
            val parsed = PublishedChildContactParser.parse(response(body = body))
            assertTrue(heading, parsed.ambiguous); assertTrue(heading, parsed.children.isEmpty())
            assertTrue(heading, provider(ScheduleTransport { response(it, body) }).discover(parish(), emptySet())
                .candidates.none { it.canConfirm(parish()) })
        }
        val hoursAndSecondChurch = changed { block ->
            block.select("span").single { it.text().startsWith("Храм открыт ежедневно") }
                .text("Храм открыт ежедневно с 10.00 до 15.00; Нижний храм святителя Николая")
        }
        assertTrue(PublishedChildContactParser.parse(response(body = hoursAndSecondChurch)).children.isEmpty())
        val ownHeadingOnly = changed { block -> block.select("span").single { it.text().startsWith("Храм в честь") }.tagName("h3") }
        assertTrue(provider(ScheduleTransport { response(it, ownHeadingOnly) }).discover(parish(), emptySet()).candidates.single().canConfirm(parish()))
    }

    @Test fun equivalentChurchTempleAndCathedralNamesAreDuplicatesBeforeValidOrInvalidGeometryIsChosen() = runBlocking {
        val selected = parish()
        val originalName = oracle["contact"].asJsonObject["heading"].asString
        for (synonym in listOf("Церковь иконы Божией Матери Всецарица", "Собор во имя иконы Божией Матери Всецарица",
            "Храм имени иконы Божией Матери Всецарица")) {
            assertTrue(synonym, OwnContactChildIdentity.sameName(originalName, synonym))
            for (invalidSecond in listOf(false, true)) {
                val body = changed { block ->
                    val duplicate = block.clone()
                    duplicate.select("span").single { it.text().startsWith("Храм в честь") }.text(synonym)
                    if (invalidSecond) duplicate.select("em").single { it.text().startsWith("широта:") }
                        .text("широта: N55°99'0'', долгота: E37°0'0''")
                    block.after(duplicate)
                }
                val parsed = PublishedChildContactParser.parse(response(body = body))
                assertTrue("$synonym invalid=$invalidSecond", parsed.ambiguous)
                assertTrue("$synonym invalid=$invalidSecond", parsed.children.isEmpty())
                assertTrue(provider(ScheduleTransport { response(it, body) }).discover(selected, emptySet())
                    .candidates.none { it.canConfirm(selected) })
            }
        }
        for (different in listOf("Нижний храм иконы Божией Матери Всецарица", "Верхний храм иконы Божией Матери Всецарица",
            "Новый храм иконы Божией Матери Всецарица", "Старый храм иконы Божией Матери Всецарица",
            "Часовня иконы Божией Матери Всецарица")) {
            assertFalse(different, OwnContactChildIdentity.sameName(originalName, different))
        }
    }

    @Test fun genericMarianIconVariantsFailSelectedAndSerializedAdmissionButAnExplicitIconTitleStillWorks() = runBlocking {
        val ordinary = parish()
        val good = provider(ScheduleTransport { response(it) }).discover(ordinary, emptySet()).candidates.single()
        val generics = listOf("Храм в честь иконы Пресвятой Богородицы", "Церковь иконы Божьей Матери",
            "Нижний храм в честь иконы Пречистой Богоматери", "Крестильный храм иконы Пресвятой Девы Марии",
            "Храм чудотворной иконы Божией Матери", "Собор в честь образа Пресвятой Богородицы")
        for (generic in generics) {
            assertFalse(generic, OwnContactChildIdentity.fullName(generic))
            val selected = ordinary.copy(name = generic)
            var calls = 0
            assertTrue(provider(ScheduleTransport { calls++; response(it) }).discover(selected, emptySet()).candidates.isEmpty())
            assertEquals(0, calls)
            val genericBody = changed { it.select("span").single { e -> e.text().startsWith("Храм в честь") }.text(generic) }
            assertTrue(PublishedChildContactParser.parse(response(body = genericBody)).children.isEmpty())
            val blockKey = "child:" + ScheduleSourceCandidate.sha(ScheduleEntityIdentity.normalize(generic)).take(40)
            val forged = good.copy(name = generic, selectedIdentity = ScheduleEntityIdentity.fingerprint(selected),
                providerObjectId = OwnContactChildIdentity.objectId(good.evidenceUrl, blockKey, generic),
                ownContact = requireNotNull(good.ownContact).copy(blockKey = blockKey))
            assertFalse(generic, forged.canConfirm(selected))
            assertNull(ScheduleSourceCandidate.decode(JsonParser.parseString(Gson().toJson(forged)), selected))
            val named = "$generic Всецарица"
            assertTrue(named, OwnContactChildIdentity.fullName(named))
            val namedSelection = ordinary.copy(name = named)
            val namedBody = changed { it.select("span").single { e -> e.text().startsWith("Храм в честь") }.text(named) }
            assertTrue(named, provider(ScheduleTransport { response(it, namedBody) }).discover(namedSelection, emptySet())
                .candidates.single().canConfirm(namedSelection))
        }
    }

    @Test fun russianAxisLabelCaseCannotChangeOrSwapDmsCoordinates() {
        for ((latitude, longitude) in listOf("широта" to "долгота", "Широта" to "Долгота", "ШИРОТА" to "ДОЛГОТА")) {
            val point = requireNotNull(PublishedChildContactParser.dms(
                "$latitude: N55°50'26.30'', $longitude: E37°10'23.90''"))
            assertEquals(55.84063888888889, point.first, 1e-10)
            assertEquals(37.17330555555556, point.second, 1e-10)
            assertNull(PublishedChildContactParser.dms("$latitude: E55°50'26.30'', $longitude: N37°10'23.90''"))
        }
    }

    @Test fun namedIconBeforeOrAfterTheImageWordSurvivesTheGenericGuard() = runBlocking {
        for (name in listOf("Храм Казанской иконы Божией Матери", "Церковь Тихвинской иконы Пресвятой Богородицы",
            "Храм в честь Владимирской иконы Божьей Матери", "Храм в честь иконы Божией Матери Казанская")) {
            assertTrue(name, OwnContactChildIdentity.fullName(name))
            val selected = parish().copy(name = name)
            val body = changed { it.select("span").single { e -> e.text().startsWith("Храм в честь") }.text(name) }
            val result = provider(ScheduleTransport { response(it, body) }).discover(selected, emptySet())
            val candidate = result.candidates.single()
            assertTrue(name, candidate.canConfirm(selected))
            assertTrue(name, requireNotNull(ScheduleSourceCandidate.decode(JsonParser.parseString(Gson().toJson(candidate)), selected))
                .canConfirm(selected))
        }
        for (name in listOf("Кафедральный собор иконы Пресвятой Богородицы", "Крестильный храм иконы Божьей Матери",
            "Домовая церковь иконы Пречистой Богоматери", "Каменный храм иконы Пресвятой Девы Марии")) {
            assertFalse(name, OwnContactChildIdentity.fullName(name))
        }
    }

    @Test fun fullDedicationTypeAndQualifiersAreRecomputedAndTamperedOutcomeCannotEraseConflict() = runBlocking {
        val wanted = parish()
        for (name in listOf("Храм в честь иконы Божией Матери", "Храм святого", "Старый храм", "Храм в честь иконы Божией Матери Казанская",
            "Часовня в честь иконы Божией Матери Всецарица", "Нижний храм в честь иконы Божией Матери Всецарица",
            "Старый храм в честь иконы Божией Матери Всецарица")) {
            val body = changed { it.select("span").single { e -> e.text().startsWith("Храм в честь") }.text(name) }
            val result = provider(ScheduleTransport { response(it, body) }).discover(wanted, emptySet())
            assertTrue(name, result.candidates.none { it.canConfirm(wanted) })
            result.candidates.forEach { candidate ->
                assertEquals(ScheduleIdentityOutcome.CONFLICT, candidate.outcome)
                assertFalse(candidate.copy(outcome = ScheduleIdentityOutcome.INSUFFICIENT).canConfirm(wanted))
            }
        }
        val generic = wanted.copy(name = "Церковь иконы Божией Матери")
        var calls = 0
        assertTrue(provider(ScheduleTransport { calls++; response(it) }).discover(generic, emptySet()).candidates.isEmpty())
        assertEquals(0, calls)
    }

    @Test fun dmsAxesHemisphereSignAndExtremeRangesAreStrictAndAsciiDegreeFormIsSupported() {
        fun pair(lat: String, lon: String) = "широта: $lat, долгота: $lon."
        assertEquals(90.0 to 180.0, PublishedChildContactParser.dms(pair("N90°0'0''", "E180°0'0''")))
        assertEquals(-90.0 to -180.0, PublishedChildContactParser.dms(pair("S-90o0'0''", "W-180o0'0''")))
        for ((a, b) in listOf("N90°0'1''" to "E37°0'0''", "N55°60'0''" to "E37°0'0''",
            "N55°0'60''" to "E37°0'0''", "E55°0'0''" to "N37°0'0''", "N-55°0'0''" to "E37°0'0''",
            "S+55°0'0''" to "E37°0'0''", "N91°0'0''" to "E37°0'0''", "N55°0'0''" to "E181°0'0''")) {
            assertNull(pair(a, b), PublishedChildContactParser.dms(pair(a, b)))
        }
        assertNull(PublishedChildContactParser.dms(pair("N55°0'0''", "E37°0'0''") + " широта: N55°0'0''"))
        assertNull(PublishedChildContactParser.dms(pair("N55O0'0''", "E37°0'0''")))
    }

    @Test fun strict150MeterAdmissionUsesTheOriginalIndependentOsmPointAndRejectsCopiedSourceIdentity() {
        val original = parish()
        val lat = oracle["contact"].asJsonObject["latitude"].asDouble
        val lon = oracle["contact"].asJsonObject["longitude"].asDouble
        val near = original.copy(latitude = lat + Math.toDegrees(149.999 / 6_371_000), longitude = lon)
        val far = original.copy(latitude = lat + Math.toDegrees(150.001 / 6_371_000), longitude = lon)
        assertTrue(OwnContactChildIdentity.pointAgrees(near, lat, lon)); assertFalse(OwnContactChildIdentity.pointAgrees(far, lat, lon))
        assertFalse(OwnContactChildIdentity.ordinaryOsm(original.copy(id = "curated-child")))
        assertFalse(OwnContactChildIdentity.ordinaryOsm(original.copy(sourceUrl = "https://example.org/way/226509018")))
    }

    @Test fun duplicateSameNamedChildrenOrCoordinatesFailEvenWhenOneDuplicateHasInvalidGeometry() {
        for (invalidSecond in listOf(false, true)) {
            val body = changed { block ->
                val duplicate = block.clone()
                if (invalidSecond) duplicate.select("em").single { it.text().startsWith("широта:") }.text("широта: N55°99'0'', долгота: E37°0'0''")
                block.after(duplicate)
            }
            val parsed = PublishedChildContactParser.parse(response(body = body))
            assertTrue(parsed.ambiguous); assertTrue(parsed.children.isEmpty())
        }
        val secondDms = changed { it.appendElement("em").text("широта: N55°0'0'', долгота: E37°0'0''") }
        assertTrue(PublishedChildContactParser.parse(response(body = secondDms)).children.isEmpty())
        val secondHeading = changed { it.appendElement("p").text("Храм в честь другого святого") }
        assertTrue(PublishedChildContactParser.parse(response(body = secondHeading)).children.isEmpty())
    }

    @Test fun publicEndpointAndTenantBoundariesRejectForeignRedirectsAndNeverGuessAnExternalContact() = runBlocking {
        for (target in listOf("https://other.example/contacts", home + "other-contact", home + "contacts/../other")) {
            val result = provider(ScheduleTransport { response(target) }).discover(parish(), emptySet())
            assertTrue(result.candidates.isEmpty()); assertTrue(result.unavailable)
        }
        assertFalse(PublisherContactScope.contains("https://shared.example/parish-a/", "https://shared.example/parish-ab/contacts"))
        assertFalse(PublisherContactScope.contains("https://shared.example/parish-a/", "https://shared.example/parish-a/%2e%2e/other"))
        val calls = mutableListOf<String>()
        val external = "<html><body><nav><a href='https://foreign.example/contacts'>Контакты</a></nav></body></html>"
        val result = provider(ScheduleTransport { calls += it; response(it, external.toByteArray()) },
            listOf(seed(PublisherSeedRole.WEBSITE, home))).discover(parish(), emptySet())
        assertEquals(listOf(home), calls); assertTrue(result.candidates.isEmpty())
    }

    @Test fun excludedCandidateDoesNotBlanketExcludeThePublisherAndFallbackStillRuns() = runBlocking {
        val original = provider(ScheduleTransport { response(it) }).discover(parish(), emptySet()).candidates.single()
        val fallback = "https://fallback.example/"
        val requests = mutableListOf<String>()
        val provider = provider(ScheduleTransport {
            requests += it
            when (it) {
                url -> response(it)
                fallback -> response(it, "<html><body><nav><a href='/contacts'>Контакты</a></nav></body></html>".toByteArray())
                fallback + "contacts" -> response(it)
                else -> error(it)
            }
        }, listOf(seed(), seed(PublisherSeedRole.WEBSITE, fallback, fallback)))
        val result = provider.discover(parish(), emptySet(), setOf(original.id))
        assertEquals(listOf(url, fallback, fallback + "contacts"), requests)
        assertTrue(result.candidates.single().canConfirm(parish())); assertNotEquals(original.id, result.candidates.single().id)
        assertTrue(provider.discover(parish(), setOf(url, fallback)).candidates.isEmpty())
    }

    @Test fun noMoreThanThreePublishersSixDispatchesOrTwoWorkersAndFailuresDoNotReturnBudget() = runBlocking {
        val live = AtomicInteger(); val maximum = AtomicInteger(); val calls = mutableListOf<String>()
        val firstPair = CompletableDeferred<Unit>()
        val seeds = (1..5).map { seed(PublisherSeedRole.WEBSITE, "https://publisher$it.example/", "https://publisher$it.example/",
            origin().copy(recordId = "publisher-$it")) }
        val result = provider(ScheduleTransport { target ->
            calls += target
            val active = live.incrementAndGet(); maximum.updateAndGet { old -> maxOf(old, active) }
            if (active == 2) firstPair.complete(Unit)
            try {
                firstPair.await(); yield()
                if (target.endsWith("/contacts") && target.contains("publisher1")) error("Unavailable consumes its dispatch")
                val body = if (target.endsWith("/contacts")) "<html><body>Контакты без приписного блока</body></html>" else
                    "<html><body><nav><a href='/contacts'>Контакты</a></nav></body></html>"
                response(target, body.toByteArray())
            } finally { live.decrementAndGet() }
        }, seeds).discover(parish(), emptySet())
        assertEquals(6, calls.size); assertEquals(3, calls.map { java.net.URI(it).host }.distinct().size)
        assertEquals(2, maximum.get()); assertEquals(0, live.get()); assertTrue(result.unavailable); assertTrue(result.incomplete)
    }

    @Test fun oneDeadlineIncludesWaitingWorkersAndExternalCancellationNeverLeavesCachedContactEvidence() = runBlocking {
        val live = AtomicInteger(); val calls = AtomicInteger()
        val seeds = (1..3).map { seed(target = "https://blocked$it.example/contacts", scope = "https://blocked$it.example/") }
        val timed = provider(ScheduleTransport { calls.incrementAndGet(); live.incrementAndGet()
            try { awaitCancellation() } finally { live.decrementAndGet() }
        }, seeds, timeout = 100).discover(parish(), emptySet())
        assertTrue(timed.unavailable); assertTrue(timed.candidates.isEmpty()); assertEquals(2, calls.get()); assertEquals(0, live.get())
        val entered = CompletableDeferred<Unit>(); var blocked = true; var gets = 0
        val next = provider(ScheduleTransport { gets++; if (blocked) { entered.complete(Unit); awaitCancellation() } else response(it) })
        val job = async { next.discover(parish(), emptySet()) }
        entered.await(); job.cancelAndJoin(); assertTrue(job.isCancelled)
        blocked = false
        assertTrue(next.discover(parish(), emptySet()).candidates.single().canConfirm(parish())); assertEquals(2, gets)
    }

    @Test fun incompleteParkedOversizeNonHtmlAndMissingChildCannotReviveAPreviousCandidate() = runBlocking {
        val failures = listOf(response(body = bytes("contacts.html").dropLast(30).toByteArray()),
            response(body = "<html><body>Domain is parked</body></html>".toByteArray()),
            ScheduleResponse(url, bytes("contacts.html"), "application/json"),
            response(body = ByteArray(OwnContactCandidateProvider.MAX_BYTES + 1)))
        for (failed in failures) {
            val result = provider(ScheduleTransport { failed }).discover(parish(), emptySet())
            assertTrue(result.unavailable); assertTrue(result.candidates.isEmpty())
        }
        var fresh = true
        val next = provider(ScheduleTransport { if (fresh) response(it) else response(it, "<html><body>Контакты</body></html>".toByteArray()) })
        assertTrue(next.discover(parish(), emptySet()).candidates.single().canConfirm(parish()))
        fresh = false
        assertTrue(next.discover(parish(), emptySet()).candidates.isEmpty())
    }

    @Test fun providerSpecificDtoGuardsRemainEffectiveAfterJsonDecodeAndSelectionOrGeometryTampering() = runBlocking {
        val selected = parish()
        val good = provider(ScheduleTransport { response(it) }).discover(selected, emptySet()).candidates.single()
        assertTrue(requireNotNull(ScheduleSourceCandidate.decode(JsonParser.parseString(Gson().toJson(good)), selected)).canConfirm(selected))
        for (bad in listOf(good.copy(latitude = good.latitude + .002), good.copy(geometryEvidence = null),
            good.copy(geometryEvidence = ScheduleGeometryEvidence(url, "f".repeat(64))),
            good.copy(evidenceUrl = home), good.copy(providerObjectId = "another-block"), good.copy(ownContact = null))) {
            assertFalse(bad.canConfirm(selected))
            assertNull(ScheduleSourceCandidate.decode(JsonParser.parseString(Gson().toJson(bad)), selected))
        }
        assertFalse(good.canConfirm(selected.copy(latitude = selected.latitude!! + .00001)))
        val anotherSelection = selected.copy(id = "osm:way:123", sourceUrl = "https://www.openstreetmap.org/way/123")
        assertFalse(good.canConfirm(anotherSelection))
        val fresh = provider(ScheduleTransport { response(it) }).discover(anotherSelection, emptySet()).candidates.single()
        assertEquals(good.id, fresh.id)
        assertNotEquals(good.selectedIdentity, fresh.selectedIdentity)
    }

    @Test fun theThreeAdditionalSavedOwnContactsDoNotBecomeNewChildDmsCoverage() {
        for (name in listOf("nikola-contact.html", "vladimir-contact.html", "kazan-contact.html")) {
            assertEquals(oracle["fixtureSha256"].asJsonObject[name].asString, ScheduleSourceCandidate.sha(bytes(name)))
            val parsed = PublishedChildContactParser.parse(response(body = bytes(name)))
            assertTrue(name, parsed.children.isEmpty())
        }
    }
}
