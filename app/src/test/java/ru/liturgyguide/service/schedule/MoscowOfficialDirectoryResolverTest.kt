package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class MoscowOfficialDirectoryResolverTest {
    private val endpoint = UrlConnectionMoscowDirectorySearchTransport.ENDPOINT
    private val maryinaUrl = "https://sprav.moseparh.ru/org/1032"
    private val listyUrl = "https://sprav.moseparh.ru/org/406"
    private val maryinaSchedule = "https://troitseparaskevo.ru/hram-ikony-bozhiej-materi-nechayannaya-radost-v-marinoj-roshhe/raspisanie/"
    private fun maryina() = Parish("osm:way:207742244", "Храм Иконы Божией Матери Нечаянная Радость в Марьиной Роще",
        "Нечаянная Радость", "", "Шереметьевская улица, 33 с1", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.8055899, 37.61505585)
    private fun listy() = maryina().copy(id = "osm:relation:19595736", name = "Храм Троицы Живоначальной в Листах",
        shortName = "Троицы в Листах", address = "улица Сретенка, 27 с3", latitude = 55.7716959, longitude = 37.63184935)
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/moscow-directory/$name")).bufferedReader().use { it.readText() }
    private fun response(url: String, text: String) = ScheduleResponse(url, text.toByteArray(), "text/html; charset=UTF-8")
    private fun searchFixture(name: String) = response(endpoint, fixture(name))
    private fun resolver(selected: Parish, calls: MutableList<String> = mutableListOf(), searchCalls: MutableList<List<String>> = mutableListOf(),
        card: String = fixture(if (selected.id == maryina().id) "card-maryina.html" else "card-listy.html")) = MoscowOfficialDirectoryResolver(
        ScheduleTransport { url -> calls += url; response(url, card) },
        MoscowDirectorySearchTransport { queries -> searchCalls += queries; queries.mapIndexed { index, _ ->
            searchFixture(if (index == 0) if (selected.id == maryina().id) "search-maryina.html" else "search-listy.html" else "search-empty.html")
        } })

    @Test fun twoRealOwnCardsResolveTheirPublishedSchedulesAndBindingSurvivesCodecRoundTrip() = runBlocking {
        val cases = listOf(Triple(maryina(), maryinaUrl, maryinaSchedule), Triple(listy(), listyUrl, "https://hram-troicy.ru/raspisanie/"))
        for ((selected, evidence, expected) in cases) {
            val calls = mutableListOf<String>(); val searches = mutableListOf<List<String>>()
            val site = requireNotNull(resolver(selected, calls, searches).resolve(selected, ScheduleSourceExclusions(emptySet()), mutableMapOf()))
            assertEquals(expected, site.url); assertEquals(evidence, site.evidenceUrl)
            assertEquals(listOf(evidence), calls)
            assertEquals(1, searches.size)
            assertEquals(if (selected.id == maryina().id) "марьиной роще" else "листах", searches.single().first())
            assertTrue(site.binding!!.validFor(selected, site.url, site.evidenceUrl))
            val state = InternetScheduleState(selected.id, InternetScheduleStatus.READY, website = site.url, sourceUrl = site.url,
                checkedAt = "2026-11-12T06:00:00Z", siteEvidenceUrl = site.evidenceUrl, siteBinding = site.binding,
                publication = SchedulePublication(selected.id, selected.timezone, site.url, site.url, "2026-11-12T06:00:00Z",
                    listOf(InternetService("2026-11-12", "08:00", "Литургия", "liturgy"))))
            val restored = ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(state), selected)
            assertEquals(state, restored)
            assertNull(ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(state), selected.copy(address = selected.address + " корпус 2"))?.publication)
        }
    }

    @Test fun explicitlyPublishedHttpScheduleRetainsItsProtocolAndValidBinding() = runBlocking {
        val selected = maryina()
        val card = fixture("card-maryina.html").replace("https://troitseparaskevo.ru/", "http://troitseparaskevo.ru/")
        val site = requireNotNull(resolver(selected, card = card).resolve(selected,
            ScheduleSourceExclusions(emptySet()), mutableMapOf()))
        assertEquals(maryinaSchedule.replace("https:", "http:"), site.url)
        assertTrue(requireNotNull(site.binding).validFor(selected, site.url, site.evidenceUrl))
    }

    @Test fun missingBuildingAndCompoundHouseArePublisherLocalExceptionsWithAllOtherEvidenceRequired() {
        val selected = listy()
        assertNotNull(MoscowDirectoryParser.card(response(listyUrl, fixture("card-listy.html")), listyUrl, selected))
        val strictSelected = requireNotNull(PalomnikStreetAddress.parse(selected.address))
        val strictPublished = requireNotNull(PalomnikStreetAddress.parse("ул. Сретенка, д. 27/29, стр. 3"))
        assertFalse("Generic map/address comparison stays strict", strictSelected.sameBuilding(strictPublished))
        val unknownBuilding = requireNotNull(PalomnikStreetAddress.parse("ул. Сретенка, д. 27/29"))
        assertFalse(MoscowChurchIdentity.addressesAgree(strictSelected, unknownBuilding))
        assertFalse("Unproved identity must remain in the ambiguity set", MoscowChurchIdentity.definitelyOtherAddress(strictSelected, unknownBuilding))
        for (bad in listOf(selected.copy(address = "улица Сретенка, 27 с2"), selected.copy(address = "улица Сретенка, 27"),
            selected.copy(address = "улица Сретенка, 28 с3"), selected.copy(latitude = 55.78),
            selected.copy(name = "Храм Николая Чудотворца в Листах"), selected.copy(latitude = Double.NaN)))
            assertNull(bad.toString(), MoscowDirectoryParser.card(response(listyUrl, fixture("card-listy.html")), listyUrl, bad))
        assertNull(MoscowDirectoryParser.card(response(maryinaUrl, fixture("card-maryina.html")), maryinaUrl,
            maryina().copy(address = "Шереметьевская улица, 34 с1")))
        val explicitOtherBuilding = fixture("card-maryina.html").replace("ул. Шереметьевская, д. 33<br>", "ул. Шереметьевская, д. 33, стр. 2<br>")
        assertNull(MoscowDirectoryParser.card(response(maryinaUrl, explicitOtherBuilding), maryinaUrl, maryina()))
    }

    @Test fun footerNamesAndParentMonasteriesCannotStandInForTheSelectedChurch() {
        val original = Jsoup.parse(fixture("card-maryina.html"), maryinaUrl)
        val contactRow = original.select("tr").single { it.children().firstOrNull()?.text() == "Контакты" }
        val moved = original.clone(); moved.select(".item-contacts").remove(); moved.body().appendChild(contactRow.clone())
        assertNull(MoscowDirectoryParser.card(response(maryinaUrl, moved.outerHtml()), maryinaUrl, maryina()))
        val noCoordinate = original.clone(); noCoordinate.select("tr").filter { it.text().startsWith("Координаты для навигации") }.forEach { it.remove() }
        assertNull(MoscowDirectoryParser.card(response(maryinaUrl, noCoordinate.outerHtml()), maryinaUrl, maryina()))
        assertNull(MoscowDirectoryParser.card(response(maryinaUrl, fixture("card-maryina.html")), listyUrl, maryina()))
        assertNull(MoscowDirectoryParser.card(response("https://foreign.example.org/org/1032", fixture("card-maryina.html")), maryinaUrl, maryina()))
        val monastery = fixture("card-monastery.html")
        assertNull(MoscowDirectoryParser.card(response("https://sprav.moseparh.ru/org/924", monastery),
            "https://sprav.moseparh.ru/org/924", maryina().copy(name = "Сретенский монастырь - старый храм")))
        val childList = original.clone(); childList.body().append("<div class='main-table-monasteries'>Другие здания</div>")
        assertNull(MoscowDirectoryParser.card(response(maryinaUrl, childList.outerHtml()), maryinaUrl, maryina()))
    }

    @Test fun malformedTruncatedOrAmbiguousSearchCannotEstablishOneSource() = runBlocking {
        val selected = maryina()
        val body = fixture("search-maryina.html")
        assertNull(MoscowDirectoryParser.search(response(endpoint, body.substringBefore("<!-- end"))))
        assertNull(MoscowDirectoryParser.search(response("https://foreign.example.org/monasteries", body)))
        val duplicate = Jsoup.parse(body, endpoint); val row = duplicate.select("tbody > tr").single(); row.after(row.clone())
        assertNull(MoscowDirectoryParser.search(response(endpoint, duplicate.body().html() + "<!-- end .main-table-->")))
        val ambiguous = body.replace("/org/1032", "/org/1119")
        var cards = 0
        val lookup = MoscowOfficialDirectoryResolver(ScheduleTransport { url -> cards++; response(url, fixture("card-maryina.html")) },
            MoscowDirectorySearchTransport { listOf(response(endpoint, body), response(endpoint, ambiguous)) })
        assertNull(lookup.resolve(selected, ScheduleSourceExclusions(emptySet()), mutableMapOf()))
        assertEquals("Both possible identities must be checked; don't pick the first", 2, cards)
    }

    @Test fun oneRefreshMemoPreservesIdentityAcrossExcludedEndpointsAndANewRefreshCanRetry() = runBlocking {
        val selected = maryina(); val searches = mutableListOf<List<String>>()
        val lookup = resolver(selected, searchCalls = searches); val memo = mutableMapOf<String, ScheduleSite?>()
        val first = requireNotNull(lookup.resolve(selected, ScheduleSourceExclusions(emptySet()), memo))
        assertNull(lookup.resolve(selected, ScheduleSourceExclusions(setOf(first.url)), memo))
        assertEquals(1, searches.size)
        assertNotNull(lookup.resolve(selected, ScheduleSourceExclusions(emptySet()), mutableMapOf()))
        assertEquals(2, searches.size)
        val noRequests = mutableListOf<String>(); val noSearches = mutableListOf<List<String>>()
        val ineligible = resolver(selected, noRequests, noSearches)
        for (bad in listOf(selected.copy(city = "Казань"), selected.copy(latitude = null), selected.copy(name = "Сретенский монастырь")))
            assertNull(ineligible.resolve(bad, ScheduleSourceExclusions(emptySet()), mutableMapOf()))
        assertTrue(noRequests.isEmpty())
        assertTrue(noSearches.isEmpty())
    }

    @Test fun productionResolverGraphUsesTheDirectoryBeforeBroadSearchAndImportsThenReopensWithoutNetwork() = runBlocking {
        val selected = maryina(); val calls = mutableListOf<String>(); val searchCalls = mutableListOf<List<String>>()
        var cached: String? = null
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = cached
            override fun write(parishId: String, value: String) { cached = value }
        }
        val transport = ScheduleTransport { url ->
            calls += url
            when (url) {
                "https://api.openstreetmap.org/api/0.6/way/207742244.json" -> ScheduleResponse(url, "{}".toByteArray(), "application/json")
                maryinaUrl -> response(url, fixture("card-maryina.html"))
                maryinaSchedule -> response(url, """<article data-day="2026-11-12"><li><time>08:00</time><span>Литургия</span></li></article>""")
                else -> throw IOException("Unexpected endpoint")
            }
        }
        val search = MoscowDirectorySearchTransport { queries -> searchCalls += queries; queries.indices.map {
            searchFixture(if (it == 0) "search-maryina.html" else "search-empty.html")
        } }
        val repository = InternetScheduleRepository(transport, cache,
            siteResolver = productionScheduleSiteResolver(transport, MemoryOfficialDirectoryCacheStore(), search))
        val state = repository.refresh(selected, Instant.parse("2026-11-12T06:00:00Z"), force = true)
        assertEquals(InternetScheduleStatus.READY, state.status)
        assertEquals(listOf("08:00"), state.publication!!.forDate(LocalDate.parse("2026-11-12")).map { it.time })
        assertEquals(maryinaUrl, state.siteEvidenceUrl)
        assertTrue(state.siteBinding!!.validFor(selected, state.website, state.siteEvidenceUrl))
        assertEquals(1, searchCalls.size)
        assertEquals(listOf("https://api.openstreetmap.org/api/0.6/way/207742244.json", maryinaUrl, maryinaSchedule), calls)
        var offlineCalls = 0
        val offline = InternetScheduleRepository(ScheduleTransport { offlineCalls++; throw IOException() }, cache)
        assertEquals(state, offline.cached(selected))
        assertEquals(0, offlineCalls)
    }

    @Test fun cancellationDoesNotMemoizeALateSourceAndDirectoryFailureDoesNotEraseAnOldPublication() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val memo = mutableMapOf<String, ScheduleSite?>()
        val lookup = MoscowOfficialDirectoryResolver(ScheduleTransport { error("No card after cancellation") },
            MoscowDirectorySearchTransport { entered.complete(Unit); awaitCancellation() })
        val job = launch { lookup.resolve(maryina(), ScheduleSourceExclusions(emptySet()), memo) }
        withTimeout(1_000) { entered.await() }; job.cancelAndJoin(); assertTrue(memo.isEmpty())
        val site = requireNotNull(resolver(maryina()).resolve(maryina(), ScheduleSourceExclusions(emptySet()), mutableMapOf()))
        val previous = InternetScheduleState(maryina().id, InternetScheduleStatus.READY, website = site.url, sourceUrl = site.url,
            checkedAt = "2026-11-12T06:00:00Z", siteEvidenceUrl = site.evidenceUrl, siteBinding = site.binding,
            publication = SchedulePublication(maryina().id, maryina().timezone, site.url, site.url, "2026-11-12T06:00:00Z",
                listOf(InternetService("2026-11-12", "08:00", "Литургия", "liturgy"))))
        var value = ScheduleCacheCodec.encode(previous)
        val cache = object : ScheduleCacheStore { override fun read(parishId: String) = value; override fun write(parishId: String, item: String) { value = item } }
        val transport = ScheduleTransport { throw IOException() }
        val failed = InternetScheduleRepository(transport, cache,
            siteResolver = productionScheduleSiteResolver(transport, MemoryOfficialDirectoryCacheStore(), MoscowDirectorySearchTransport { throw IOException() }))
            .refresh(maryina(), Instant.parse("2026-11-12T06:01:00Z"), force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
        assertEquals(previous.publication, failed.publication)
    }
}
