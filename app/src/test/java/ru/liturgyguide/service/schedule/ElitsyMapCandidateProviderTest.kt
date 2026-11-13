package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ElitsyMapCandidateProviderTest {
    private val now = Instant.parse("2026-11-13T16:00:00Z")
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("schedule/elitsy-map/$name")!!.readText()
    private fun parish(name: String, lat: Double, lon: Double, city: String = "", address: String = "") =
        Parish("osm:node:1234", name, name, city, address, "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "", lat, lon)
    private fun selected(scene: String): Parish {
        val p = JsonParser.parseString(fixture("$scene-points.json")).asJsonObject.getAsJsonArray("points")
            .map { it.asJsonObject }.single { it.get("pk").asString == when (scene) { "nahabino" -> "17504"; "pskov" -> "12827"; else -> "12484" } }
        val c = p.getAsJsonArray("coords")
        return parish(p.get("text").asString, c[0].asDouble, c[1].asDouble)
    }
    private fun response(url: String, body: String, json: Boolean = false) = ScheduleResponse(url, body.toByteArray(),
        if (json) "application/json; charset=UTF-8" else "text/html; charset=UTF-8")
    private fun provider(scene: String, calls: MutableList<String> = mutableListOf(), points: String = fixture("$scene-points.json"),
        detail: String = fixture("$scene-detail.html")) = ElitsyMapCandidateProvider(ScheduleTransport { url ->
        calls += url
        when {
            url.startsWith(ElitsyMapCandidateProvider.API) -> response(url, points, true)
            url.startsWith("https://elitsy.ru/parish/") -> response(url, detail)
            else -> error("Provider may read only its collection and bounded detail cards")
        }
    }, Clock.fixed(now, ZoneOffset.UTC))

    @Test fun `three real regions preserve exact object coordinates sites and separate body proofs without automatic acceptance`() = runBlocking {
        for (scene in listOf("nahabino", "pskov", "novosibirsk")) {
            val selected = selected(scene); val calls = mutableListOf<String>()
            val result = provider(scene, calls).discover(selected, emptySet())
            assertFalse(scene, result.unavailable)
            assertTrue(result.incomplete)
            assertTrue(scene, result.candidates.isNotEmpty())
            assertEquals(2, calls.size)
            val query = calls.first().substringAfter('?').split('&')
            assertEquals(listOf("lb", "lb", "rt", "rt", "z"), query.map { it.substringBefore('=') })
            assertEquals("z=15", query.last())
            for (candidate in result.candidates) {
                assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, candidate.outcome)
                assertTrue(candidate.canConfirm(selected))
                assertEquals(selected.latitude!!, candidate.latitude, .000000001)
                assertEquals(selected.longitude!!, candidate.longitude, .000000001)
                assertEquals(ElitsyMapCandidateProvider.PROVIDER, candidate.provider)
                assertEquals(ScheduleSourceCandidate.sha(fixture("$scene-detail.html").toByteArray()), candidate.evidenceSha256)
                assertEquals(ScheduleSourceCandidate.sha(fixture("$scene-points.json").toByteArray()), candidate.geometryEvidence!!.sha256)
                assertEquals(calls.first(), candidate.geometryEvidence!!.url)
                assertEquals(now.toString(), candidate.checkedAt)
            }
        }
    }

    @Test fun `same city and dedication never lend the distant churches official site to the site-less church`() = runBlocking {
        val own = JsonParser.parseString(fixture("assumption-birch-point.json")).asJsonObject.getAsJsonArray("points")[0].asJsonObject
        val c = own.getAsJsonArray("coords")
        val selected = parish(own.get("text").asString, c[0].asDouble, c[1].asDouble, "Новосибирск", "улица Гоголя, 179")
        val both = JsonParser.parseString(fixture("assumption-city-point.json")).asJsonObject
        both.getAsJsonArray("points").add(own)
        val calls = mutableListOf<String>()
        val p = ElitsyMapCandidateProvider(ScheduleTransport { url ->
            calls += url
            when {
                url.startsWith(ElitsyMapCandidateProvider.API) -> response(url, both.toString(), true)
                url.endsWith("/6818/") -> response(url, fixture("assumption-birch-detail.html"))
                else -> error("The church 12.7 km away must never be read or borrowed")
            }
        })
        val result = p.discover(selected, emptySet())
        assertFalse(result.unavailable)
        assertEquals(setOf("6639", "6818"), result.observedIds.toSet())
        assertTrue(result.candidates.isEmpty())
        assertEquals(2, calls.size)
    }

    @Test fun `exact two labels deduplicate sites and unrelated links or posts cannot supply an ownsite`() = runBlocking {
        val selected = selected("novosibirsk")
        val result = provider("novosibirsk").discover(selected, emptySet())
        assertEquals(listOf("http://olega-bryanskogo.cerkov.ru/"), result.candidates.map { it.sourceUrl })
        val original = fixture("novosibirsk-detail.html")
        val body = original.replace("Страница храма на Prihod.ru", "Мой приход").replace("Официальный сайт храма", "Поделиться")
            .replace("</body>", "<article><a href='https://other.example/'>Официальный сайт храма</a></article></body>")
        val absent = provider("novosibirsk", detail = body).discover(selected, emptySet())
        assertFalse(absent.unavailable)
        assertTrue(absent.candidates.isEmpty())
    }

    @Test fun `excluded URLs still preserve observed identities and published HTTP is never rewritten as HTTPS`() = runBlocking {
        val first = provider("nahabino").discover(selected("nahabino"), emptySet())
        assertEquals(setOf("http://vsecnahabino.cerkov.ru/", "http://www.hram-elisaveta.ru/"), first.candidates.map { it.sourceUrl }.toSet())
        val excluded = provider("nahabino").discover(selected("nahabino"), first.candidates.map { it.sourceUrl }.toSet())
        assertEquals(listOf("17504"), excluded.observedIds)
        assertTrue(excluded.candidates.isEmpty())
        assertFalse(excluded.unavailable)
    }

    @Test fun `known different house city and kind remain conflicts that cannot be edited into confirmation`() = runBlocking {
        val original = selected("novosibirsk")
        for (selected in listOf(original.copy(address = "улица Лескова, 139"), original.copy(city = "Москва"),
            original.copy(name = original.name.replace("Храм", "Часовня")))) {
            val candidates = provider("novosibirsk").discover(selected, emptySet()).candidates
            assertTrue(candidates.isNotEmpty())
            for (candidate in candidates) {
                assertEquals(ScheduleIdentityOutcome.CONFLICT, candidate.outcome)
                assertFalse(candidate.canConfirm(selected))
                assertFalse(candidate.copy(outcome = ScheduleIdentityOutcome.INSUFFICIENT).canConfirm(selected))
            }
        }
    }

    @Test fun `mismatching map ID title multiple profiles or truncated HTML cannot produce a candidate`() = runBlocking {
        val selected = selected("novosibirsk"); val original = fixture("novosibirsk-detail.html")
        val bodies = listOf(original.replace("/map/12484/", "/map/6639/"),
            original.replace("<h1>", "<h1>Другой храм "), original.substringBefore("</body>"),
            original.replace("</body>", "<div class='profile'><div class='pf-left'><h1>Подмена</h1></div></div></body>"))
        for (body in bodies) {
            val result = provider("novosibirsk", detail = body).discover(selected, emptySet())
            assertTrue(result.unavailable)
            assertTrue(result.candidates.isEmpty())
        }
    }

    @Test fun `wrapper decodes once rejects duplicate query private URL and foreign wrapper`() = runBlocking {
        val original = fixture("novosibirsk-detail.html")
        for (href in listOf("https://elitsy.ru/redirect?url=http%253A%252F%252Fexample.org%252F",
            "https://elitsy.ru/redirect?url=https%3A%2F%2Fexample.org&url=https%3A%2F%2Fother.org",
            "https://elitsy.ru/redirect?url=http%3A%2F%2Flocalhost%2F", "https://elitsy.ru/other?url=https%3A%2F%2Fexample.org")) {
            val body = original.replace(Regex("https://elitsy\\.ru/redirect\\?url=[^\"']+"), href)
            assertTrue(provider("novosibirsk", detail = body).discover(selected("novosibirsk"), emptySet()).candidates.isEmpty())
        }
    }

    @Test fun `invalid collection shapes duplicates swapped coordinates and oversized or HTML responses are unavailable`() = runBlocking {
        val original = fixture("novosibirsk-points.json")
        for (body in listOf("{}", "[]", "{\"points\":[]", "{\"points\":[],\"points\":[]}",
            "{\"points\":[{\"pk\":12484,\"text\":\"Храм Олега\",\"coords\":[55,82,1]}]}",
            original.replace("55.019392", "null"), " ".repeat(196_609))) {
            val result = provider("novosibirsk", points = body).discover(selected("novosibirsk"), emptySet())
            assertTrue(result.unavailable)
            assertTrue(result.candidates.isEmpty())
        }
        val json = JsonParser.parseString(original).asJsonObject
        json.getAsJsonArray("points")[0].asJsonObject.add("coords", JsonParser.parseString("[82.957595,55.019392]"))
        val swapped = provider("novosibirsk", points = json.toString()).discover(selected("novosibirsk"), emptySet())
        assertTrue(swapped.candidates.isEmpty())
        val html = ElitsyMapCandidateProvider(ScheduleTransport { response(it, "<html>challenge</html>") })
            .discover(selected("novosibirsk"), emptySet())
        assertTrue(html.unavailable)
    }

    @Test fun `conflicting duplicate point ID is quarantined while independent valid rows are retained`() = runBlocking {
        val root = JsonParser.parseString(fixture("novosibirsk-points.json")).asJsonObject
        val changed = root.getAsJsonArray("points")[0].asJsonObject.deepCopy()
        changed.add("coords", JsonParser.parseString("[55.0194,82.9576]")); root.getAsJsonArray("points").add(changed)
        val result = provider("novosibirsk", points = root.toString()).discover(selected("novosibirsk"), emptySet())
        assertTrue(result.unavailable)
        assertTrue(result.candidates.isEmpty())
        assertEquals(listOf("12484"), result.observedIds)
    }

    @Test fun `three nearby plausible points read no more than two details and retain all observations`() = runBlocking {
        val root = JsonParser.parseString(fixture("novosibirsk-points.json")).asJsonObject
        val row = root.getAsJsonArray("points")[0].asJsonObject
        for (id in listOf(12485, 12486)) root.getAsJsonArray("points").add(row.deepCopy().also { it.addProperty("pk", id) })
        val calls = mutableListOf<String>()
        val p = ElitsyMapCandidateProvider(ScheduleTransport { url ->
            calls += url
            if (url.startsWith(ElitsyMapCandidateProvider.API)) response(url, root.toString(), true)
            else response(url, fixture("novosibirsk-detail.html").replace("/map/12484/", "/map/${url.trimEnd('/').substringAfterLast('/')}/"))
        })
        val found = p.discover(selected("novosibirsk"), emptySet())
        assertEquals(3, calls.size)
        assertEquals(3, found.observedIds.size)
        assertEquals(2, found.candidates.size)
        assertTrue(found.incomplete)
    }

    @Test fun `empty valid points remain incomplete and unsupported coordinates perform no requests`() = runBlocking {
        val calls = mutableListOf<String>()
        val p = provider("novosibirsk", calls, points = "{\"points\":[]}")
        val empty = p.discover(selected("novosibirsk"), emptySet())
        assertFalse(empty.unavailable)
        assertTrue(empty.incomplete)
        assertEquals(1, calls.size)
        calls.clear()
        for (selected in listOf(selected("novosibirsk").copy(latitude = null), selected("novosibirsk").copy(latitude = Double.NaN),
            selected("novosibirsk").copy(latitude = 86.0), selected("novosibirsk").copy(longitude = 179.999)))
            assertTrue(p.discover(selected, emptySet()).candidates.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test fun `provider deadline is unavailable and external cancellation propagates`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val p = ElitsyMapCandidateProvider(ScheduleTransport { entered.complete(Unit); awaitCancellation() }, timeoutMillis = 10)
        assertTrue(p.discover(selected("novosibirsk"), emptySet()).unavailable)
        val external = ElitsyMapCandidateProvider(ScheduleTransport { entered.complete(Unit); awaitCancellation() })
        val job = async { external.discover(selected("novosibirsk"), emptySet()) }
        yield(); job.cancel()
        try { job.await(); fail("External cancellation must not become empty success") } catch (_: CancellationException) { }
    }

    @Test fun `geometry proof survives candidate and binding cache round trips while legacy non Elitsy candidates stay valid`() = runBlocking {
        val selected = selected("novosibirsk")
        val candidate = provider("novosibirsk").discover(selected, emptySet()).candidates.single()
        val gson = Gson()
        val json = gson.toJsonTree(candidate).asJsonObject
        assertEquals(candidate, ScheduleSourceCandidate.decode(json, selected))
        val confirmation = ScheduleCandidateConfirmation(1, ScheduleCandidateConfirmation.TYPE,
            ScheduleEntityIdentity.fingerprint(selected), candidate, now.toString())
        val binding = ScheduleEntityIdentity.bind(selected, candidate.sourceUrl, candidate.evidenceUrl, candidate.name,
            ScheduleBindingMethod.USER_CONFIRMED, ScheduleEntityIdentity.scope(selected.name)).copy(candidateConfirmation = confirmation)
        val state = InternetScheduleState(selected.id, checkedAt = now.toString(), website = candidate.sourceUrl,
            siteEvidenceUrl = candidate.evidenceUrl, siteBinding = binding, sourceCandidates = listOf(candidate),
            candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(selected))
        assertEquals(candidate.geometryEvidence, ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(state), selected)!!.siteBinding!!.candidateConfirmation!!.candidate.geometryEvidence)
        json.remove("geometryEvidence")
        assertNull(ScheduleSourceCandidate.decode(json, selected))
        json.addProperty("provider", "official_map")
        assertNotNull(ScheduleSourceCandidate.decode(json, selected))
        assertFalse(candidate.copy(geometryEvidence = ScheduleGeometryEvidence("https://other.org/", "a".repeat(64))).validFor(selected))
    }
}
