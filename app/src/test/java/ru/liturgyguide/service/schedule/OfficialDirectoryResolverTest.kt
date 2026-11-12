package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class OfficialDirectoryResolverTest {
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val profile = OfficialDirectoryProfile.PUBLISHED.single()
    private val smallProfile = profile.copy(minimumEntries = 1)
    private val home = "http://prorok-iliya.cerkov.ru/"
    private val httpsHome = "https://prorok-iliya.cerkov.ru/"
    private val schedule = httpsHome + "raspisanie-bogosluzhenij/"
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/schedule/voronezh/$name")).readText()
    private fun parish(): Parish {
        val p = JsonParser.parseString(fixture("selected-parish.json")).asJsonObject
        return Parish(p["id"].asString, p["name"].asString, p["name"].asString, p["city"].asString,
            p["address"].asString, "", "", "Europe/Moscow", p["sourceUrl"].asString, "", "", "", emptyList(), "", "",
            p["center"].asJsonObject["latitude"].asDouble, p["center"].asJsonObject["longitude"].asDouble)
    }
    private fun html(text: String, url: String = profile.url) = ScheduleResponse(url, text.toByteArray(), "text/html; charset=UTF-8")
    private fun json(url: String, body: String = "{}") = ScheduleResponse(url, body.toByteArray(), "application/json")
    private fun card(id: String = "t1", address: String? = "Россия, г. Воронеж, ул. Севастьяновский Съезд, 26.",
        title: String = "Храм Илии пророка г. Воронеж", website: String? = home, coordinates: String? = null): String =
        """<li><span class="site-info"><dl><h1 id="$id">$title</h1></dl>
        ${address?.let { "<dl><span class='ch'>Адрес:</span><span class='cnt'>$it</span></dl>" }.orEmpty()}
        ${website?.let { "<dl><span class='ttl'>Сайт:</span><span class='cnt'><a href='$it'>Сайт</a></span></dl>" }.orEmpty()}
        ${coordinates?.let { "<dl><span class='ttl'>Координаты:</span><span class='cnt'>$it</span></dl>" }.orEmpty()}
        </span></li>"""
    private fun page(vararg cards: String) = "<html><body><article><div id='parent_site_list'><ul>${cards.joinToString("")}</ul></div></article></body></html>"
    private suspend fun match(body: String, selected: Parish = parish(), excluded: Set<String> = emptySet()): ScheduleSite? =
        OfficialDirectoryResolver(ScheduleTransport { html(body) }, MemoryOfficialDirectoryCacheStore(),
            Clock.fixed(now, ZoneOffset.UTC), listOf(smallProfile)).resolve(selected, ScheduleSourceExclusions(excluded), mutableMapOf())

    @Test fun `complete untouched catalog joins default discovery importer binding and cold schedule cache`() = runBlocking<Unit> {
        val raw = fixture("official-directory.html")
        val provenance = JsonParser.parseString(fixture("official-directory-provenance.json")).asJsonObject
        assertEquals(provenance["bytes"].asInt, raw.toByteArray().size)
        assertEquals(provenance["sha256"].asString, sha(raw.toByteArray()))
        val snapshot = requireNotNull(OrtOxDirectoryParser.parse(html(raw), profile, now))
        assertEquals(233, snapshot.entries.size)
        assertTrue(snapshot.entries.any { it.website == null })
        assertEquals(1, snapshot.entries.count { it.title.isBlank() })
        assertEquals(snapshot, OfficialDirectoryCodec.decode(OfficialDirectoryCodec.encode(snapshot), profile))
        val calls = mutableListOf<String>()
        val store = ScheduleMemory()
        val directory = MemoryOfficialDirectoryCacheStore()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                "openstreetmap.org/api" in url -> json(url)
                "categorymembers" in url -> json(url, """{"query":{"categorymembers":[]}}""")
                url == profile.url -> html(raw)
                url == home || url == httpsHome -> html(fixture("home.html"), httpsHome)
                url == schedule -> html(fixture("schedule.html"), schedule)
                else -> error("Unexpected request: $url")
            }
        }, store, directoryCache = directory)
        val selected = parish()
        val ready = repository.refresh(selected, now, force = true)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(1, calls.count { it == profile.url })
        assertEquals(5, calls.size)
        assertEquals(listOf(home, schedule), calls.takeLast(2))
        assertEquals(profile.url, ready.siteEvidenceUrl)
        assertEquals("", ready.configuredWebsite)
        assertEquals(ScheduleBindingMethod.ARTICLE_SCOPE, ready.siteBinding?.method)
        assertTrue(requireNotNull(ready.siteBinding).validFor(selected, ready.website, ready.siteEvidenceUrl))
        assertEquals(ScheduleEntityIdentity.fingerprint(selected), ready.siteBinding?.selectedIdentity)
        val publication = requireNotNull(ready.publication)
        assertTrue(publication.yearInferred)
        assertEquals(schedule, publication.sourceUrl)
        assertEquals(listOf("2026-11-11T07:45", "2026-11-13T07:45", "2026-11-13T16:00", "2026-11-14T07:45",
            "2026-11-14T16:00", "2026-11-15T07:30", "2026-11-15T08:00"), publication.events.map { "${it.date}T${it.time}" })
        assertFalse(publication.events.any { it.date == "2026-11-12" })
        calls.clear()
        val refreshed = repository.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(listOf(httpsHome, schedule), calls)
        assertEquals(ready.siteBinding, refreshed.siteBinding)
        var offlineCalls = 0
        val offline = InternetScheduleRepository(ScheduleTransport { offlineCalls++; throw IOException() }, store)
        assertEquals(refreshed, offline.cached(selected))
        assertEquals(refreshed, offline.refresh(selected, now.plusSeconds(90)))
        assertEquals(0, offlineCalls)
        assertNull(offline.cached(selected.copy(address = "Воронеж, улица Другая, 26")).publication)
    }

    @Test fun `identity is selected before URL availability and exclusions`() = runBlocking<Unit> {
        assertEquals(home, match(page(card()))?.url)
        assertEquals(home, match(page(card(), card("t2", address = "Россия, г. Воронеж, ул. Севастьяновский Съезд, 30", website = "https://other.example/")))?.url)
        assertNull(match(page(card()), excluded = setOf(httpsHome)))
        for (other in listOf(card("t2"), card("t2", website = null), card("t2", address = null),
            card("t2", address = "неизвестно"), card("t2", address = "улица Неизвестная, 1"),
            card("t2", address = "г. Воронеж", website = "https://other.example/"),
            card("t2", address = "г. Воронеж, ул. Севастьяновский Съезд", website = "https://other.example/"))) {
            assertNull("A missing same-name record cannot create uniqueness", match(page(card(), other)))
            assertNull(match(page(card(), other), excluded = setOf("https://other.example/")))
        }
        assertNull(match(page(card(website = null))))
        assertNull(match(page(card(website = "http://127.0.0.1/"))))
        assertEquals(home, match(page(card(), card("t2", address = "Россия, г. Москва, ул. Севастьяновский Съезд, 26")))?.url)
        assertNull(match(page(card(), card("t2", title = ""))))
        assertNull(match(page(card(), card("t2", title = "", address = null))))
        assertNull(match(page(card(), card("t2", title = "", address = "Россия, Воронежская область, Лиски, Первомайская улица, 77"))))
    }

    @Test fun `unknown bare words preserve full catalog ambiguity without a placeholder blacklist`() = runBlocking<Unit> {
        val raw = fixture("official-directory.html")
        for (phrase in listOf("адрес неизвестен", "адрес уточняется", "местонахождение пока проверяется")) {
            for (title in listOf("Храм Илии пророка г. Воронеж", "")) {
                val doc = Jsoup.parse(raw, profile.url)
                doc.selectFirst("#parent_site_list > ul")!!.append(card("t999999", title = title,
                    address = "Россия, Воронежская область, $phrase, улица Севастьяновский съезд, 26"))
                assertNull("$phrase / $title", match(doc.outerHtml()))
            }
        }
    }

    @Test fun `bare locality needs positive typed evidence from complete regional addresses`() = runBlocking<Unit> {
        val bare = card("t2", title = "", address = "Россия, Воронежская область, Лиски, Первомайская улица, 77")
        val typed = card("t3", title = "Другой храм", address = "Россия, Воронежская область, г. Лиски, пл. Революции, 20")
        assertNull(match(page(card(), bare)))
        assertEquals(home, match(page(card(), bare, typed))?.url)
        for (address in listOf(
            "Россия, г. Лиски, пл. Революции, 20",
            "Россия, неизвестная область, г. Лиски, пл. Революции, 20",
            "Россия, Воронежская область, Московская область, г. Лиски, пл. Революции, 20",
            "Украина, Воронежская область, г. Лиски, пл. Революции, 20",
            "Воронежская область, г. Лиски, пл. Революции, 20",
            "Россия, Воронежская область, г. Лиски, адрес уточняется, пл. Революции, 20",
            "Россия, Воронежская область, Лиски, пл. Революции, 20",
            "Россия, Воронежская область, г. Лиски",
        )) assertNull(address, match(page(card(), bare, card("t3", title = "Другой храм", address = address))))
        // A typed name in a heading or unrelated content is not an address proof.
        assertNull(match(page(card(), bare, card("t3", title = "г. Лиски", address = null))))
        assertNull(match(page(card(), bare) .replace("</article>", "<p>Россия, Воронежская область, г. Лиски, ул. Другая, 20</p></article>")))
    }

    @Test fun `bare locality cannot inherit proof across incomplete or conflicting target region`() = runBlocking<Unit> {
        val typed = card("t3", title = "Другой храм", address = "Россия, Воронежская область, г. Лиски, пл. Революции, 20")
        for (address in listOf(
            "Россия, Лиски, Первомайская улица, 77",
            "Россия, неизвестная область, Лиски, Первомайская улица, 77",
            "Россия, Воронежская область, Московская область, Лиски, Первомайская улица, 77",
            "Украина, Воронежская область, Лиски, Первомайская улица, 77",
            "Россия, Воронежская область, Лиски, адрес уточняется, Первомайская улица, 77",
        )) assertNull(address, match(page(card(), card("t2", title = "", address = address), typed)))
    }

    @Test fun `a city and village sharing a name leave its bare form ambiguous`() = runBlocking<Unit> {
        val bare = card("t2", title = "", address = "Россия, Воронежская область, Лиски, Первомайская улица, 77")
        val city = card("t3", title = "Другой храм", address = "Россия, Воронежская область, г. Лиски, пл. Революции, 20")
        val village = card("t4", title = "Другой храм", address = "Россия, Воронежская область, с. Лиски, ул. Ленина, 1")
        assertNull(match(page(card(), bare, city, village)))
        assertEquals(home, match(page(card(), city, village))?.url)
    }

    @Test fun `real unnamed Liski card needs typed corroboration preserved across compact cache`() = runBlocking<Unit> {
        val raw = fixture("official-directory.html")
        val snapshot = requireNotNull(OrtOxDirectoryParser.parse(html(raw), profile, now))
        val cache = MemoryOfficialDirectoryCacheStore().apply { write(profile.id, OfficialDirectoryCodec.encode(snapshot)) }
        val cold = OfficialDirectoryResolver(ScheduleTransport { error("Fresh compact cache must suffice") }, cache,
            Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(home, cold.resolve(parish(), ScheduleSourceExclusions(emptySet()), mutableMapOf())?.url)
        val doc = Jsoup.parse(raw, profile.url)
        var removed = 0
        doc.select("#parent_site_list > ul > li").forEach { element ->
            val address = element.select("dl").firstOrNull { it.select(".ch,.ttl").text().trim() == "Адрес:" }
                ?.selectFirst(".cnt")
            if (address != null && Regex("(?iu)г\\.\\s+Лиски(?:,|$)").containsMatchIn(address.text())) {
                address.text(address.text().replace(Regex("(?iu)г\\.\\s+Лиски"), "Лиски"))
                removed++
            }
        }
        assertEquals(5, removed)
        assertEquals(233, requireNotNull(OrtOxDirectoryParser.parse(html(doc.outerHtml()), profile, now)).entries.size)
        assertNull(match(doc.outerHtml()))
    }

    @Test fun `complete exact city street house and scope are required without erasing corpus or building`() = runBlocking<Unit> {
        for (address in listOf("", "Воронеж, улица Севастьяновский съезд", "Москва, улица Севастьяновский съезд, 26",
            "Воронеж, площадь Севастьяновский съезд, 26", "Воронеж, улица Севастьяновский съезд, 26 корпус 1",
            "Воронеж, улица Севастьяновский съезд, 26 строение 1", "Воронеж, улица Севастьяновский съезд, 26А"))
            assertNull(address, match(page(card()), parish().copy(address = address)))
        for (address in listOf("Россия, г. Москва, ул. Севастьяновский Съезд, 26", "Россия, с. Воронеж, ул. Севастьяновский Съезд, 26",
            "Россия, Московская область, г. Воронеж, ул. Севастьяновский Съезд, 26", "Украина, г. Воронеж, ул. Севастьяновский Съезд, 26",
            "Россия, г. Воронеж, г. Москва, ул. Севастьяновский Съезд, 26", "Воронеж, ул. Севастьяновский Съезд, 26 стр. 1"))
            assertNull(address, match(page(card(address = address))))
        val corpus = parish().copy(address = "Воронеж, улица Севастьяновский съезд, 26 корпус 1 строение 2")
        assertEquals(home, match(page(card(address = "Россия, г. Воронеж, ул. Севастьяновский Съезд, д.26 к.1 стр.2")), corpus)?.url)
        assertNull(match(page(card(title = "Монастырь Илии пророка г. Воронеж"))))
        assertNull(match(page(card(title = "Храм Николая Чудотворца г. Воронеж"))))
        assertNull(match(page(card()), parish().copy(name = "Монастырь Илии пророка")))
        assertNull(match(page(card()), parish().copy(city = "Москва")))
        assertNull(match(page(card()), parish().copy(city = "")))
    }

    @Test fun `independent coordinates cannot contradict the selected building`() = runBlocking<Unit> {
        assertEquals(home, match(page(card(coordinates = "51.65438305, 39.20722645")))?.url)
        for (coordinates in listOf("55.147559,61.275355", "NaN,39.2", "51.6", "200,40", "51.6,400", ""))
            assertNull(coordinates, match(page(card(coordinates = coordinates))))
        assertNull(match(page(card(coordinates = "51.65438305,39.20722645")), parish().copy(latitude = null)))
    }

    @Test fun `truncation pagination malformed cards and cross publisher responses never prove a complete catalog`() {
        val raw = fixture("official-directory.html")
        assertNotNull(OrtOxDirectoryParser.parse(html(raw), profile, now))
        assertNull(OrtOxDirectoryParser.parse(html(raw, "https://unrelated.example/"), profile, now))
        assertNull(OrtOxDirectoryParser.parse(html(raw.substringBeforeLast("</body>")), profile, now))
        assertNull(OrtOxDirectoryParser.parse(html(page(card())), profile, now))
        assertNull(OrtOxDirectoryParser.parse(html("x".repeat(OrtOxDirectoryParser.MAX_BYTES + 1)), profile, now))
        for (mutation in listOf<(org.jsoup.nodes.Document) -> Unit>(
            { it.selectFirst("#parent_site_list")!!.append("<div class='pagination'><a href='?page=2'>2</a></div>") },
            { it.head().append("<link rel='next' href='?page=2'>") },
            { it.selectFirst("#parent_site_list")!!.append("<a href='?start=200'>Еще</a>") },
            { it.selectFirst(".site-info")!!.remove() },
            { it.selectFirst(".site-info h1")!!.attr("id", "bad") },
            { it.select(".site-info h1")[1].attr("id", it.select(".site-info h1")[0].id()) },
        )) {
            val doc = Jsoup.parse(raw, profile.url); mutation(doc)
            assertNull(OrtOxDirectoryParser.parse(html(doc.outerHtml()), profile, now))
        }
    }

    @Test fun `fresh compact file cache is reused and stale corrupt future caches require a real retry`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("feoria-official-directory-test").toFile()
        try {
            val cache = FileOfficialDirectoryCacheStore(directory)
            var instant = now
            val clock = object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC
                override fun withZone(zone: ZoneId): Clock = this
                override fun instant(): Instant = instant
            }
            var calls = 0
            val network = ScheduleTransport { calls++; html(page(card())) }
            suspend fun find() = OfficialDirectoryResolver(network, FileOfficialDirectoryCacheStore(directory), clock, listOf(smallProfile))
                .resolve(parish(), ScheduleSourceExclusions(emptySet()), mutableMapOf())
            assertEquals(home, find()?.url)
            assertEquals(1, calls)
            val compact = requireNotNull(cache.read(profile.id))
            assertFalse(compact.contains("<html"))
            assertTrue(compact.toByteArray().size < 3000)
            instant = now.plusSeconds(24 * 3600)
            assertEquals(home, find()?.url); assertEquals(1, calls)
            instant = instant.plusSeconds(1)
            assertEquals(home, find()?.url); assertEquals(2, calls)
            cache.write(profile.id, "not JSON")
            assertEquals(home, find()?.url); assertEquals(3, calls)
            cache.write(profile.id, compact)
            instant = now.minusSeconds(1)
            assertEquals(home, find()?.url); assertEquals(4, calls)
            val invalid = JsonParser.parseString(compact).asJsonObject.apply { addProperty("directoryUrl", "https://other.example/") }
            assertNull(OfficialDirectoryCodec.decode(invalid.toString(), smallProfile))
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
            for (i in 1..12) cache.write("profile-$i", "{}")
            assertEquals(8, directory.listFiles().orEmpty().size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `failed catalog is memoized only inside one refresh and cancellation does not poison another parish`() = runBlocking<Unit> {
        var directoryCalls = 0
        var succeed = false
        val transport = ScheduleTransport { url ->
            when {
                url == profile.url -> { directoryCalls++; if (!succeed) throw IOException(); html(fixture("official-directory.html")) }
                "categorymembers" in url -> json(url, """{"query":{"categorymembers":[]}}""")
                else -> json(url)
            }
        }
        val open = OpenScheduleSiteResolver(transport)
        val first = open.forRefresh()
        assertNull(first.resolve(parish()))
        succeed = true
        assertNull(first.resolve(parish(), setOf("https://failed.example/")))
        assertEquals(1, directoryCalls)
        assertEquals(home, open.forRefresh().resolve(parish())?.url)
        assertEquals(2, directoryCalls)

        val entered = CompletableDeferred<Unit>()
        val suspendedCalls = AtomicInteger()
        val cancellable = OpenScheduleSiteResolver(ScheduleTransport { url ->
            when {
                url == profile.url -> {
                    if (suspendedCalls.incrementAndGet() == 1) { entered.complete(Unit); awaitCancellation() }
                    html(fixture("official-directory.html"))
                }
                "categorymembers" in url -> json(url, """{"query":{"categorymembers":[]}}""")
                else -> json(url)
            }
        })
        val job = launch { cancellable.forRefresh().resolve(parish()) }
        withTimeout(3000) { entered.await() }
        job.cancelAndJoin()
        val other = parish().copy(id = "another-selection", address = "Воронеж, улица Другая, 77")
        assertNull(cancellable.forRefresh().resolve(other))
        assertEquals(home, cancellable.forRefresh().resolve(parish())?.url)
        assertEquals(2, suspendedCalls.get())
    }

    @Test fun `existing withdrawal of a directory bound source cannot resurrect through fresh discovery`() = runBlocking<Unit> {
        val selected = parish()
        val binding = ScheduleEntityIdentity.bind(selected, home, profile.url, "Храм Илии пророка г. Воронеж", ScheduleBindingMethod.ARTICLE_SCOPE)
        val stored = InternetScheduleState(selected.id, InternetScheduleStatus.UNSUPPORTED, website = home, sourceUrl = schedule,
            checkedAt = now.minusSeconds(1000).toString(), siteEvidenceUrl = profile.url, siteBinding = binding,
            publicationRejected = true, sourceHasWithdrawals = true)
        val cache = ScheduleMemory().apply { write(selected.id, ScheduleCacheCodec.encode(stored)) }
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url -> calls += url; throw IOException() }, cache)
        val failed = repository.refresh(selected, now, force = true)
        assertEquals(listOf(home), calls)
        assertNull(failed.publication)
        assertTrue(failed.publicationRejected)
        assertTrue(failed.sourceHasWithdrawals)
        assertEquals(binding, failed.siteBinding)
    }

    private class ScheduleMemory : ScheduleCacheStore {
        private val values = mutableMapOf<String, String>()
        override fun read(parishId: String) = values[parishId]
        override fun write(parishId: String, value: String) { values[parishId] = value }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
