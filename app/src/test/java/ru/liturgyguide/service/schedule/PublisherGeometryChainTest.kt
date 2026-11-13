package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class PublisherGeometryChainTest {
    private val profile = PublisherGeometryProfile.PUBLISHED.single { it.id == "kaluga-publisher" }
    private val site = "http://kaluga-voznesenie.ru/"
    private val schedule = site + "raspisanie-bogosluzhenii/"
    private val contact = site + "kontakty/"
    private val history = site + "o-hrame/"
    private val clergy = site + "duhovenstvo-hrama/"
    private val aboutServices = site + "bogosluzheniya-v-nashem-hrame/"
    private val passport = "http://kalujskoe-19.cerkov.ru/"
    private val distantPassports = linkedMapOf("http://babyninskoe-10.cerkov.ru/" to "ortox-babynino.html",
        "http://tarusskoe-6.cerkov.ru/" to "ortox-voznesenye.html")
    private val anchor = "https://sobory.ru/article/?object=17648"
    private val detail = profile.directoryUrl.removeSuffix(".html") + "/1587-oficialnyj-sajt-kaluzhskoj-eparhii-prihody-gorod-kaluga-domovyj-hram-v-chest-voznesenija-gospodnja.html"
    private val now = Instant.parse("2026-11-13T04:00:00Z")
    private fun parish() = Parish("osm:way:155168069", "Архиерейское подворье. Храм в честь Вознесения Господня",
        "Храм Вознесения Господня", "", "", "", "", "Europe/Moscow", "https://www.openstreetmap.org/way/155168069",
        "", "", "", emptyList(), "", "", 54.5305961, 36.3084198)
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("schedule/publisher-geometry/$name")!!.readBytes()
    private fun html(name: String) = fixture(name).toString(Charsets.UTF_8)
    private inner class Network(private val xmlMap: Boolean = false) : ScheduleTransport {
        val calls = mutableListOf<String>()
        var before: suspend (String) -> Unit = {}
        var rewrite: (String, ScheduleResponse) -> ScheduleResponse = { _, response -> response }
        val pages = linkedMapOf(profile.ortoxUrl to "official-ortox-collection.html", profile.directoryUrl to "diocese-city-directory.html",
            profile.directoryUrl + "?start=20" to "diocese-city-directory-page2.html", passport to "ortox-own-https.html",
            detail to "diocese-card.html", site to "own-http-home.html", contact to "own-http-contact.html", history to "own-http-about.html",
            clergy to "own-http-clergy.html", schedule to "own-http-schedule.html", anchor to "official-linked-sobory.html",
            aboutServices to "own-http-about-services.html").apply {
                putAll(distantPassports)
            }
        override suspend fun fetch(url: String): ScheduleResponse {
            calls += url; before(url)
            val response = when {
                url.endsWith("/way/155168069/full.json") || url.endsWith("/way/155168069.json") ->
                    ScheduleResponse(url, fixture("osm-way-full.jsonraw"), "application/json")
                url.startsWith("https://api.openstreetmap.org/api/0.6/map?bbox=") ->
                    if (xmlMap) ScheduleResponse(url, fixture("osm-exact-rounded-neighbourhood.xml"), "application/xml")
                    else ScheduleResponse(url, fixture("osm-neighbourhood-native.jsonraw"), "application/json")
                url in pages -> ScheduleResponse(if (url == passport || url in distantPassports) url.replaceFirst("http:", "https:") else url,
                    fixture(pages.getValue(url)), "text/html; charset=UTF-8")
                else -> throw IOException("No recorded response for $url")
            }
            return rewrite(url, response)
        }
        fun mutate(url: String, change: (String) -> String) {
            val previous = rewrite
            rewrite = { requested, response -> previous(requested, response).let {
                if (requested == url) it.copy(bytes = change(it.bytes.toString(Charsets.UTF_8)).toByteArray()) else it
            } }
        }
    }
    private class Cache : ScheduleCacheStore {
        val data = mutableMapOf<String, String>()
        override fun read(parishId: String) = data[parishId]
        override fun write(parishId: String, value: String) { data[parishId] = value }
    }
    private suspend fun resolve(network: Network) = PublisherGeometryResolver(network).resolve(parish(), ScheduleSourceExclusions(emptySet()))

    @Test fun `genuine publisher chain automatically imports the exact selected blank-address church and exact offline timetable`() = runBlocking {
        val network = Network(); val cache = Cache(); val selected = parish()
        val result = InternetScheduleRepository(network, cache).refresh(selected, now, true)
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertEquals("", selected.city); assertEquals("", selected.address); assertEquals("", selected.website)
        assertEquals("", result.customSource); assertEquals("", result.configuredWebsite)
        assertEquals(schedule, result.website); assertEquals(detail, result.siteEvidenceUrl)
        val binding = requireNotNull(result.siteBinding)
        assertEquals(ScheduleBindingMethod.PUBLISHER_GEOMETRY_CHAIN, binding.method)
        assertTrue(binding.validFor(selected, result.website, result.siteEvidenceUrl))
        val proof = requireNotNull(binding.publisherGeometryProof)
        assertEquals(188, proof.ortoxRows.size); assertEquals(36, proof.directoryRows.size)
        assertEquals("t20385", proof.selectedPassportId)
        assertEquals(setOf("t20385", "t21054", "t20703"), proof.passports.map { it.id }.toSet())
        val selectedPassport = proof.passports.single { it.id == proof.selectedPassportId }
        assertFalse(PublisherGeometry.strictlyInside(selectedPassport.mapPoint, proof.contour.points))
        assertTrue(distantPassports.keys.all { url -> network.calls.count { it == url } == 1 })
        assertTrue(proof.passports.filter { it.id != proof.selectedPassportId }.all {
            PublisherGeometry.distance(it.mapPoint, PublisherPoint(selected.latitude!!, selected.longitude!!)) > 150
        })
        assertTrue(PublisherGeometry.strictlyInside(requireNotNull(proof.linkedAnchor).point, proof.contour.points))
        assertEquals(listOf("08:00", "17:00"), requireNotNull(result.publication).forDate(LocalDate.of(2026, 11, 13)).map { it.time })
        assertEquals(listOf("07:00", "09:00", "17:00"), result.publication!!.forDate(LocalDate.of(2026, 11, 15)).map { it.time })
        assertTrue(result.publication!!.yearInferred)
        assertEquals("The exact observed page is consumed, without a second schedule GET", 1, network.calls.count { it == schedule })
        assertEquals(15, proof.evidence.size)
        assertEquals("The complete proof, exact-map metadata and one legitimate importer link stay bounded", 17, network.calls.size)
        assertEquals(listOf("https://api.openstreetmap.org/api/0.6/way/155168069.json", aboutServices),
            network.calls.filter { request -> proof.evidence.none { it.requestedUrl == request } })
        assertEquals(1, network.calls.count { it == aboutServices })
        val encoded = requireNotNull(cache.read(selected.id)); assertTrue(encoded.length <= 200_000)
        assertEquals(result, ScheduleCacheCodec.decode(encoded, selected))
        var requests = 0
        val offline = InternetScheduleRepository(ScheduleTransport { requests++; throw IOException() }, cache)
        assertEquals(result, offline.cached(selected))
        assertEquals(result, offline.refresh(selected, now.plusSeconds(60)))
        assertEquals(0, requests)
    }

    @Test fun `the actual outward rounded OSM request is covered exactly without accepting inward response bounds`() {
        val contour = requireNotNull(PublisherGeometry.contour(ScheduleResponse(PublisherGeometry.fullUrl(parish())!!,
            fixture("osm-way-full.jsonraw"), "application/json"), parish()))
        val area = PublisherGeometry.neighbourhoodArea(contour)
        assertEquals("36.3058572,54.5291495,36.3109824,54.5320427", area.query())
        val url = "https://api.openstreetmap.org/api/0.6/map?bbox=${area.query()}"
        assertEquals(url, PublisherGeometry.neighbourhoodUrl(contour))
        val actual = ScheduleResponse(url, fixture("osm-exact-rounded-neighbourhood.xml"), "application/xml")
        assertNotNull("The exact primary HTTP response must pass, not a wider substitute", PublisherGeometry.neighbours(actual, contour))
        val returned = Jsoup.parse(actual.bytes.toString(Charsets.UTF_8), url, Parser.xmlParser()).select("bounds").single()
        assertEquals(area, PublisherArea(returned.attr("minlon").toDouble(), returned.attr("minlat").toDouble(),
            returned.attr("maxlon").toDouble(), returned.attr("maxlat").toDouble()))
        for ((field, value) in listOf("minlon" to area.west + .0000001, "minlat" to area.south + .0000001,
            "maxlon" to area.east - .0000001, "maxlat" to area.north - .0000001)) {
            val changed = Jsoup.parse(actual.bytes.toString(Charsets.UTF_8), url, Parser.xmlParser())
            changed.select("bounds").single().attr(field, value.toString())
            assertNull("Inward $field is still rejected without epsilon", PublisherGeometry.neighbours(
                actual.copy(bytes = changed.outerHtml().toByteArray()), contour))
        }
    }

    @Test fun `real negotiated JSON and exact XML have the same selected geometry and religious neighbours`() {
        val contour = requireNotNull(PublisherGeometry.contour(ScheduleResponse(PublisherGeometry.fullUrl(parish())!!,
            fixture("osm-way-full.jsonraw"), "application/json"), parish()))
        val url = PublisherGeometry.neighbourhoodUrl(contour)
        val xml = requireNotNull(PublisherGeometry.neighbours(ScheduleResponse(url,
            fixture("osm-exact-rounded-neighbourhood.xml"), "application/xml"), contour))
        val json = requireNotNull(PublisherGeometry.neighbours(ScheduleResponse(url,
            fixture("osm-neighbourhood-native.jsonraw"), "application/json"), contour))
        assertEquals(xml, json)
        assertEquals(listOf(PublisherNeighbour("way", contour.id, contour.points)), json)
    }

    @Test fun `negotiated JSON rejects incomplete ambiguous duplicate or loosely typed map data`() {
        val contour = requireNotNull(PublisherGeometry.contour(ScheduleResponse(PublisherGeometry.fullUrl(parish())!!,
            fixture("osm-way-full.jsonraw"), "application/json"), parish()))
        val url = PublisherGeometry.neighbourhoodUrl(contour)
        val original = html("osm-neighbourhood-native.jsonraw")
        fun selected(root: JsonObject) = root.getAsJsonArray("elements").first {
            it.asJsonObject.get("type").asString == "way" && it.asJsonObject.get("id").asString == contour.id
        }.asJsonObject
        val mutations = linkedMapOf<String, (JsonObject) -> Unit>(
            "missing bounds" to { it.remove("bounds") },
            "error response" to { it.addProperty("error", "Incomplete") },
            "string coordinate" to { root -> root.getAsJsonArray("elements").first().asJsonObject.let {
                it.addProperty("lat", it.get("lat").asString)
            } },
            "string bound" to { root -> root.getAsJsonObject("bounds").let { it.addProperty("minlon", it.get("minlon").asString) } },
            "fraction id" to { root -> selected(root).addProperty("id", 155168069.5) },
            "string id" to { root -> selected(root).addProperty("id", contour.id) },
            "fraction version" to { root -> selected(root).addProperty("version", 1.5) },
            "fraction ref" to { root -> selected(root).getAsJsonArray("nodes").set(0, JsonPrimitive(1.5)) },
            "unresolved ref" to { root -> selected(root).getAsJsonArray("nodes").set(0, JsonPrimitive(990000000001L)) },
            "missing selected" to { root -> root.getAsJsonArray("elements").remove(selected(root)) },
            "duplicate nonreligious node" to { root -> root.getAsJsonArray("elements").add(root.getAsJsonArray("elements").first().deepCopy()) },
            "missing referenced node" to { root ->
                val id = selected(root).getAsJsonArray("nodes").first().asString
                val all = root.getAsJsonArray("elements")
                all.remove(all.first { it.asJsonObject.get("type").asString == "node" && it.asJsonObject.get("id").asString == id })
            },
            "religious relation" to { root -> root.getAsJsonArray("elements").add(JsonObject().apply {
                addProperty("type", "relation"); addProperty("id", 990000000001L); addProperty("version", 1)
                add("tags", JsonObject().apply { addProperty("amenity", "place_of_worship") })
            }) }
        )
        val area = PublisherGeometry.neighbourhoodArea(contour)
        for ((field, value) in listOf("minlon" to area.west + .0000001, "minlat" to area.south + .0000001,
            "maxlon" to area.east - .0000001, "maxlat" to area.north - .0000001)) {
            mutations["inward $field"] = { it.getAsJsonObject("bounds").addProperty(field, value) }
        }
        for ((name, mutate) in mutations) {
            val changed = JsonParser.parseString(original).asJsonObject.also(mutate).toString()
            assertNull(name, PublisherGeometry.neighbours(ScheduleResponse(url, changed.toByteArray(), "application/json"), contour))
        }
        val duplicateKey = original.replaceFirst("\"bounds\":", "\"bounds\":{},\"bounds\":")
        assertNotEquals(original, duplicateKey)
        val exponentId = original.replaceFirst("\"id\":155168069", "\"id\":155168069e0")
        assertNotEquals(original, exponentId)
        for ((name, changed) in listOf("duplicate JSON key" to duplicateKey, "exponent id" to exponentId,
            "truncated" to original.dropLast(8), "trailing data" to (original + "{}"))) {
            assertNull(name, PublisherGeometry.neighbours(ScheduleResponse(url, changed.toByteArray(), "application/json"), contour))
        }
    }

    @Test fun `JSON worship node inside selected church keeps the candidate ambiguous`() = runBlocking {
        val network = Network()
        val contour = requireNotNull(PublisherGeometry.contour(ScheduleResponse(PublisherGeometry.fullUrl(parish())!!,
            fixture("osm-way-full.jsonraw"), "application/json"), parish()))
        val url = PublisherGeometry.neighbourhoodUrl(contour)
        network.mutate(url) { text -> JsonParser.parseString(text).asJsonObject.apply {
            getAsJsonArray("elements").add(JsonObject().apply {
                addProperty("type", "node"); addProperty("id", 990000000001L); addProperty("version", 1)
                addProperty("lat", 54.53062); addProperty("lon", 36.308492)
                add("tags", JsonObject().apply { addProperty("amenity", "place_of_worship") })
            })
        }.toString() }
        val neighbours = requireNotNull(PublisherGeometry.neighbours(network.fetch(url), contour))
        assertEquals(2, neighbours.size)
        assertFalse(PublisherGeometry.unambiguous(contour, neighbours, listOf(PublisherPoint(54.53062, 36.308492))))
        assertNull(resolve(network))
    }

    @Test fun `published village localities do not hide a missing malformed or nearby competing passport`() = runBlocking {
        for (url in distantPassports.keys) {
            val missing = Network().also { network -> network.before = { requested -> if (requested == url) throw IOException("Unavailable passport") } }
            assertNull("Unavailable potential candidate $url", resolve(missing))
            for (change in listOf<(String) -> String>(
                { it.replace("ortoxParentSite =", "ortoxParentSite = []; ortoxParentSite =") },
                { it.replace("Храм Вознесения Господня", "Храм святителя Николая") },
                { text -> text.replace(Regex("\\\"la\\\":\\\"[0-9.]+\\\""), "\"la\":\"54.5306200\"")
                    .replace(Regex("\\\"lo\\\":\\\"[0-9.]+\\\""), "\"lo\":\"36.3084920\"") }
            )) {
                val network = Network().also { it.mutate(url, change) }
                assertNull("Unresolved competing identity $url", resolve(network))
            }
        }
    }

    @Test fun `a distant geometrically identified church need not publish a city street or a personal rector`() = runBlocking {
        val network = Network()
        val url = distantPassports.keys.last()
        network.mutate(url) { text ->
            val document = Jsoup.parse(text, url)
            document.select(".reg-info li").single { item -> item.select(".ch,.ttl").firstOrNull()?.text()?.trim() == "Настоятель:" }.remove()
            document.outerHtml()
        }
        val found = requireNotNull(resolve(network))
        val distant = found.binding!!.publisherGeometryProof!!.passports.single { it.id == "t20703" }
        assertEquals("", distant.rector)
        assertNull(PublisherIdentity.city(distant.address))
        assertNull(PublisherIdentity.street(distant.address))
        assertEquals("settlement:вознесенье", PublisherIdentity.publishedLocality(distant.address))
    }

    @Test fun `an own anchor strictly inside the selected building does not need any secondary source`() = runBlocking {
        val network = Network().also { it.mutate(passport) { value -> value.replace("54.5307148", "54.5306200").replace("36.3086205", "36.3084920") } }
        val found = requireNotNull(resolve(network))
        assertNull(found.binding!!.publisherGeometryProof!!.linkedAnchor)
        assertFalse(network.calls.contains(anchor))
    }

    @Test fun `exact OSM contour identity complete nodes closure and orthodox church type are required`() = runBlocking {
        for (mutation in listOf<(com.google.gson.JsonObject) -> Unit>(
            { root -> root.getAsJsonArray("elements").first { it.asJsonObject.get("type").asString == "way" }.asJsonObject.addProperty("id", 12345) },
            { root -> root.getAsJsonArray("elements").remove(0) },
            { root -> val way = root.getAsJsonArray("elements").first { it.asJsonObject.get("type").asString == "way" }.asJsonObject; way.getAsJsonArray("nodes").remove(way.getAsJsonArray("nodes").size() - 1) },
            { root -> root.getAsJsonArray("elements").first { it.asJsonObject.get("type").asString == "way" }.asJsonObject.getAsJsonObject("tags").addProperty("denomination", "catholic") },
            { root -> root.getAsJsonArray("elements").first { it.asJsonObject.get("type").asString == "way" }.asJsonObject.getAsJsonObject("tags").addProperty("building", "chapel") }
        )) {
            val network = Network().also { it.mutate(PublisherGeometry.fullUrl(parish())!!) { text ->
                JsonParser.parseString(text).asJsonObject.also(mutation).toString()
            } }
            assertNull(resolve(network))
        }
    }

    @Test fun `two outside anchors a boundary point and double Church JSON-LD never establish an interior anchor`() = runBlocking {
        val full = ScheduleResponse(PublisherGeometry.fullUrl(parish())!!, fixture("osm-way-full.jsonraw"), "application/json")
        val boundary = requireNotNull(PublisherGeometry.contour(full, parish())).points.first()
        for (change in listOf<(String) -> String>(
            { it.replace("54.53062", "54.53090") },
            { it.replace("54.53062", boundary.latitude.toString()).replace("36.308492", boundary.longitude.toString()) },
            { text -> val doc = Jsoup.parse(text, anchor); doc.body().appendElement("script").attr("type", "application/ld+json")
                .html(doc.select("script[type=application/ld+json]").first()!!.data()); doc.outerHtml() },
            { it.replace("object=17648", "object=17649") }
        )) {
            val network = Network().also { it.mutate(anchor, change) }
            assertNull(resolve(network))
        }
    }

    @Test fun `a competing religious contour containing the official own anchor cannot be rescued by a different interior anchor`() = runBlocking {
        val network = Network(xmlMap = true)
        network.rewrite = { url, value -> if (!url.contains("/map?bbox=")) value else {
            val document = Jsoup.parse(value.bytes.toString(Charsets.UTF_8), url, Parser.xmlParser())
            val osm = document.select("osm").single()
            listOf(54.530705 to 36.30860, 54.530725 to 36.30860, 54.530725 to 36.30864, 54.530705 to 36.30864).forEachIndexed { i, p ->
                osm.append("<node id='${99000 + i}' lat='${p.first}' lon='${p.second}'/>")
            }
            osm.append("<way id='99010' version='1'><nd ref='99000'/><nd ref='99001'/><nd ref='99002'/><nd ref='99003'/><nd ref='99000'/><tag k='building' v='church'/></way>")
            value.copy(bytes = document.outerHtml().toByteArray())
        } }
        assertNull(resolve(network))
    }

    @Test fun `a second worship object at the interior anchor is ambiguous`() = runBlocking {
        val network = Network(xmlMap = true)
        network.rewrite = { url, value -> if (!url.contains("/map?bbox=")) value else value.copy(bytes = value.bytes.toString(Charsets.UTF_8)
            .replace("</osm>", "<node id='99011' lat='54.53062' lon='36.308492'><tag k='amenity' v='place_of_worship'/></node></osm>").toByteArray()) }
        assertNull(resolve(network))
    }

    @Test fun `incomplete publisher lists hidden pagination and unavailable unknown competitors do not establish uniqueness`() = runBlocking {
        val variants = listOf<Pair<String, (String) -> String>>(
            profile.ortoxUrl to { text -> val doc = Jsoup.parse(text, profile.ortoxUrl); doc.select("#parent_site_list > ul > li").last()!!.remove(); doc.outerHtml() },
            profile.directoryUrl to { it.replace("?start=20", "?start=40") },
            profile.directoryUrl + "?start=20" to { text -> val doc = Jsoup.parse(text, profile.directoryUrl); doc.select(".items-row").last()!!.remove(); doc.outerHtml() },
            profile.ortoxUrl to { text -> val doc = Jsoup.parse(text, profile.ortoxUrl); doc.select("#parent_site_list h1").first()!!.text(""); doc.outerHtml() }
        )
        for ((url, change) in variants) {
            val network = Network().also { it.mutate(url, change) }
            assertNull(resolve(network))
        }
    }

    @Test fun `a duplicate own map or a publisher id mismatch cannot confirm the selected church`() = runBlocking {
        for (change in listOf<(String) -> String>(
            { it.replace("ortoxParentSite =", "ortoxParentSite = []; ortoxParentSite =") },
            { it.replace("\"id\":\"20385\"", "\"id\":\"20386\"") }
        )) {
            val network = Network().also { it.mutate(passport, change) }
            assertNull(resolve(network))
        }
    }

    @Test fun `a current website comes only from the single labelled field of matching official records`() = runBlocking {
        for (url in listOf(profile.directoryUrl + "?start=20", detail)) {
            val network = Network().also { it.mutate(url) { text -> text.replace("href=\"http://kaluga-voznesenie.ru/\"",
                "href=\"http://kaluga-voznesenie.ru/\"").replace("http://kaluga-voznesenie.ru</a>",
                "http://kaluga-voznesenie.ru</a><a href='http://other.example.org/'>other</a>") } }
            assertNull(resolve(network))
        }
    }

    @Test fun `complete phone rector and current address must agree and old address must have an own historical transition`() = runBlocking {
        val variants = listOf<Pair<String, (String) -> String>>(
            contact to { it.replace("8 (920) 090-98-27", "8 (920) 090-98-28") },
            contact to { it.replace("8 (920) 090-98-27", "9200909827") },
            clergy to { it.replace("Евгений Чупров", "Павел Иванов") },
            contact to { it.replace("Молодежная, 40", "Молодежная, 41") },
            history to { it.replace("Грабцевское шоссе, 114", "Грабцевское шоссе, 115") },
            history to { it.replace("Сначала приход располагался", "Другой приход располагался") },
            history to { it.replace("к месту строительства нового храма на улице Молодежной", "к месту строительства нового храма на улице Центральной") },
            history to { text -> val doc = Jsoup.parse(text, history); val p = doc.select("article .entry-content p").single { "Сначала приход располагался" in it.text() };
                p.wrap("<blockquote></blockquote>"); doc.outerHtml() }
        )
        for ((url, change) in variants) {
            val network = Network().also { it.mutate(url, change) }
            assertNull("Mismatch at $url", resolve(network))
        }
    }

    @Test fun `truncated oversized and foreign redirected source pages never confirm a chain`() = runBlocking {
        for (url in listOf(profile.ortoxUrl, profile.directoryUrl, passport, detail, contact, history, anchor)) {
            for (mode in listOf("truncated", "foreign", "oversized")) {
                val network = Network()
                network.rewrite = { requested, response -> if (requested != url) response else when (mode) {
                    "foreign" -> response.copy(url = "https://foreign.example.org/copied/")
                    "oversized" -> response.copy(bytes = ByteArray(1_000_001))
                    else -> response.copy(bytes = response.bytes.toString(Charsets.UTF_8).substringBeforeLast("</body>").toByteArray())
                } }
                assertNull("$url $mode", resolve(network))
            }
        }
    }

    @Test fun `new network attempts revalidate the whole chain while seven hour network failure preserves original saved age`() = runBlocking {
        val network = Network(); val cache = Cache(); val selected = parish()
        val repository = InternetScheduleRepository(network, cache)
        val accepted = repository.refresh(selected, now, true)
        assertEquals(InternetScheduleStatus.READY, accepted.status)
        network.before = { throw IOException("Offline") }
        val saved = repository.refresh(selected, now.plusSeconds(7 * 60 * 60))
        assertEquals(InternetScheduleStatus.UNAVAILABLE, saved.status)
        assertEquals(accepted.publication, saved.publication); assertEquals(accepted.siteBinding, saved.siteBinding)
        assertFalse(saved.sourceNeedsVerification); assertEquals(saved, repository.cached(selected))
        network.before = {}
        network.mutate(contact) { it.replace("Молодежная, 40", "Молодежная, 41") }
        val rejected = repository.refresh(selected, now.plusSeconds(7 * 60 * 60 + 60), true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, rejected.status)
        assertTrue(rejected.sourceNeedsVerification); assertNull(rejected.publication); assertNull(repository.cached(selected).publication)
    }

    @Test fun `observed cancellation is imported once and cannot be resurrected by a later offline refresh`() = runBlocking {
        val network = Network(); val cache = Cache(); val selected = parish()
        val repository = InternetScheduleRepository(network, cache)
        assertEquals(InternetScheduleStatus.READY, repository.refresh(selected, now, true).status)
        network.mutate(schedule) { text ->
            val document = Jsoup.parse(text, schedule)
            val day = document.select("article .entry-content > p").single {
                ScheduleEntityIdentity.normalize(it.text()).startsWith("13 ноября ")
            }
            val before = day.text()
            assertTrue(before.contains("8-00.") && before.contains("17-00.") && before.contains("Всецарица"))
            day.appendText(" отменена")
            assertNotEquals("The real HTML entities must not make this cancellation a no-op", before, day.text())
            document.outerHtml()
        }
        val cancelled = repository.refresh(selected, now.plusSeconds(60), true)
        assertTrue(cancelled.sourceHasWithdrawals)
        assertEquals(listOf("08:00"), requireNotNull(cancelled.publication).forDate(LocalDate.of(2026, 11, 13)).map { it.time })
        assertEquals(2, network.calls.count { it == schedule })
        network.before = { throw IOException() }
        val saved = repository.refresh(selected, now.plusSeconds(120), true)
        assertTrue(saved.sourceHasWithdrawals); assertEquals(cancelled.publication, saved.publication)
    }

    @Test fun `a proven publisher source never assigns an explicitly offsite main service to the selected building`() = runBlocking {
        val network = Network()
        network.mutate(schedule) { text ->
            val document = Jsoup.parse(text, schedule)
            val day = document.select("article .entry-content > p").single {
                ScheduleEntityIdentity.normalize(it.text()).startsWith("13 ноября ")
            }
            listOf("16-15. Литургия в скиту Иоанна Предтечи", "16-20. Литургия на подворье",
                "16-25. Литургия в другом монастыре").forEach { title -> day.appendElement("br"); day.appendText(title) }
            document.outerHtml()
        }
        val result = InternetScheduleRepository(network, Cache()).refresh(parish(), now, true)
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertEquals(listOf("08:00", "17:00"), requireNotNull(result.publication)
            .forDate(LocalDate.of(2026, 11, 13)).map { it.time })
    }

    @Test fun `cancellation escapes without partial cache and unknown malformed proof cannot confirm an offline publication`() = runBlocking {
        val network = Network(); val cache = Cache(); val selected = parish()
        val repository = InternetScheduleRepository(network, cache)
        val accepted = repository.refresh(selected, now, true)
        assertEquals(InternetScheduleStatus.READY, accepted.status)
        val encoded = requireNotNull(cache.read(selected.id))
        network.before = { url -> if (url == history) throw CancellationException("Caller stopped") }
        try { repository.refresh(selected, now.plusSeconds(60), true); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(encoded, cache.read(selected.id))
        for (key in listOf("proofVersion", "profileId", "selectedFingerprint", "contourDigest", "own")) {
            val root = JsonParser.parseString(encoded).asJsonObject
            root.getAsJsonObject("siteBinding").getAsJsonObject("publisherGeometryProof").remove(key)
            val value = requireNotNull(ScheduleCacheCodec.decode(root.toString(), selected))
            assertTrue("Missing $key", value.sourceNeedsVerification); assertNull(value.publication)
        }
        assertFalse(accepted.siteBinding!!.validFor(selected.copy(latitude = selected.latitude!! + .0001), schedule, detail))
    }
}
