package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.net.URLDecoder
import java.security.MessageDigest

class OfficialMapCandidateProviderTest {
    private val now = Instant.parse("2026-11-13T13:00:00Z")
    private val home = OfficialMapCandidateProvider.HOME
    private val script = home + "wp-content/plugins/ortox-network-sites/js/ymap-filters-box.js?ver=2.6.15"
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("schedule/official-map/$name")!!.readText()
    private fun parish(ermogen: Boolean = false) = if (ermogen)
        Parish("osm:way:291669713", "Храм Священномученика Ермогена в Крылатском", "Ермогена", "", "Осенняя улица, 32 с1", "", "",
            "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.76964415, 37.4061332)
    else Parish("osm:way:95943763", "Храм Петра и Павла", "Петра и Павла", "", "Петропавловский парк, 25а", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "", 57.6025314, 39.84369965)
    private fun response(url: String, content: String, json: Boolean = false) =
        ScheduleResponse(url, content.toByteArray(), if (json) "application/json" else "text/html; charset=UTF-8")

    private fun provider(ermogen: Boolean = false, contacts: String? = null,
        collection: String? = null, calls: MutableList<String> = mutableListOf(),
        fixtureBase: String? = null, detail: String? = null): OfficialMapCandidateProvider {
        return OfficialMapCandidateProvider(ScheduleTransport { url ->
            calls += url
            when {
                url == home -> response(url, "<html><body><script src='$script'></script></body></html>")
                url == script -> response(url, "var p_mode='token=fictional_public_widget_12345&country='+country;")
                url.startsWith(OfficialMapCandidateProvider.API) && !url.contains("m=info") ->
                    response(url, collection ?: fixture(fixtureBase?.let { "$it-collection.json" } ?:
                        if (ermogen) "ermogen-collection.json" else "yar-collection.json"), true)
                url.startsWith(OfficialMapCandidateProvider.API) -> response(url, detail ?: fixture(fixtureBase?.let { "$it-detail.html" } ?:
                    if (ermogen) "ermogen-detail.html" else "yar-detail.html"))
                url == "http://hram-ermogena.ru/" -> response(url, fixture("ermogen-home.html"))
                url == "http://hram-ermogena.ru/kontakty" -> response(url, contacts ?: fixture("ermogen-contacts.html"))
                else -> error("Unexpected fixture request")
            }
        }, Clock.fixed(now, ZoneOffset.UTC))
    }

    private fun holdoutParish(name: String): Parish = when (name) {
        "kasimov-michael" -> parish().copy(id = "osm:node:4277678958", name = "Храм Архистратига Божия Михаила г. Касимова",
            address = "", latitude = 54.9469726, longitude = 41.4102457)
        "serednikovo-alexius" -> parish().copy(id = "osm:way:137321439", name = "Церковь Алексия, митрополита Московского",
            address = "1А", latitude = 55.93230765, longitude = 37.2276877)
        "alabushevo-seraphim" -> parish().copy(id = "osm:way:136573404", name = "Церковь Серафима Саровского",
            address = "Алабушево, Центральная улица, 32А", city = "Алабушево", latitude = 56.00897615, longitude = 37.1259598)
        "bykovo-resurrection" -> parish().copy(id = "osm:way:287674135", name = "Церковь Воскресения Словущего",
            address = "Октябрьская улица, 1А", latitude = 55.62883265, longitude = 38.07644415)
        "melikhovo-nativity" -> parish().copy(id = "osm:way:331716429", name = "Церковь Рождества Христова",
            address = "", latitude = 55.11581665, longitude = 37.65534435)
        else -> error("Unknown holdout fixture")
    }

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    @Test fun `real Yaroslavl marker preserves latitude longitude but one result is only a suggestion`() = runBlocking {
        val found = provider().discover(parish(), emptySet())
        assertFalse(found.unavailable)
        assertTrue(found.incomplete)
        assertEquals(listOf("8715"), found.observedIds)
        val candidate = found.candidates.single()
        assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, candidate.outcome)
        assertEquals(57.602532, candidate.latitude, .0000001)
        assertEquals(39.843732, candidate.longitude, .0000001)
        assertEquals("http://manufacturahram.cerkov.ru/", candidate.sourceUrl)
        assertTrue(candidate.canConfirm(parish()))
        assertFalse(candidate.canConfirm(parish().copy(latitude = 57.603)))
    }

    @Test fun `swapped coordinates and malformed geometry never turn incomplete collection into proof`() = runBlocking {
        val json = JsonParser.parseString(fixture("yar-collection.json")).asJsonObject
        val geometry = json.getAsJsonArray("features")[0].asJsonObject.getAsJsonObject("geometry")
        geometry.add("coordinates", JsonParser.parseString("[39.843732,57.602532]"))
        assertTrue(provider(collection = json.toString()).discover(parish(), emptySet()).candidates.isEmpty())
        geometry.addProperty("type", "Cluster")
        val failed = provider(collection = json.toString()).discover(parish(), emptySet())
        assertEquals(listOf("8715"), failed.observedIds)
        assertTrue(failed.incomplete)
        assertTrue(failed.candidates.isEmpty())
    }

    @Test fun `exact currently linked own contacts explain the real old Hermogenes house but still require review`() = runBlocking {
        val selected = parish(true)
        val candidate = provider(true).discover(selected, emptySet()).candidates.single()
        assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, candidate.outcome)
        assertTrue(candidate.address.contains("33"))
        assertTrue(candidate.currentContactAddress.contains("32"))
        assertEquals("hram-ermogena@mail.ru", candidate.linkedEmail)
        assertTrue(candidate.canConfirm(selected))
        assertTrue(candidate.currentContactSha256.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun `missing current house or different parish email leaves the known contradiction blocked`() = runBlocking {
        val original = fixture("ermogen-contacts.html")
        for (content in listOf(original.replace("hram-ermogena@mail.ru", "other@example.org"),
            original.replace("д. 32", "д. 33"), "<html><body>Контакты недоступны</body></html>")) {
            val candidate = provider(true, content).discover(parish(true), emptySet()).candidates.single()
            assertEquals(ScheduleIdentityOutcome.CONFLICT, candidate.outcome)
            assertFalse(candidate.canConfirm(parish(true)))
            // Changing the outcome by itself cannot erase the retained address contradiction.
            assertFalse(candidate.copy(outcome = ScheduleIdentityOutcome.INSUFFICIENT).validFor(parish(true)))
        }
    }

    @Test fun `excluded website does not erase the observed selected identity`() = runBlocking {
        val found = provider().discover(parish(), setOf("http://manufacturahram.cerkov.ru/"))
        assertEquals(listOf("8715"), found.observedIds)
        assertTrue(found.candidates.isEmpty())
        assertTrue(found.incomplete)
    }

    @Test fun `query encloses the same 650 metre radius accepted by candidate geometry`() = runBlocking {
        val calls = mutableListOf<String>()
        // The real Yaroslavl point is about 450 metres north of this selected coordinate.
        val selected = parish().copy(latitude = parish().latitude!! - 450.0 / 111_000.0)
        val found = provider(calls = calls).discover(selected, emptySet())
        assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, found.candidates.single().outcome)
        val query = calls.single { it.startsWith(OfficialMapCandidateProvider.API) && !it.contains("m=info") }
            .substringAfter('?').split('&').associate { part ->
                part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8")
            }
        val bounds = JsonParser.parseString(query.getValue("geo")).asJsonArray
        assertEquals(650.0 / 111_000.0, bounds[1].asJsonArray[0].asDouble - selected.latitude!!, 1e-10)
        assertTrue(bounds[1].asJsonArray[0].asDouble > parish().latitude!!)
        assertTrue(found.incomplete)
        assertFalse(found.unavailable)
    }

    @Test fun `real structured main dedication rescues lexical aliases without manufacturing a title`() = runBlocking {
        for ((base, expectedName) in listOf(
            "kasimov-michael" to "Архангельский храм г. Касимов [Архангела Михаила]",
            "alabushevo-seraphim" to "Серафимовский храм с. Алабушево [Серафима Саровского]",
            "melikhovo-nativity" to "Христорождественский храм с. Мелихово [Рождества Христова]")) {
            val selected = holdoutParish(base)
            val calls = mutableListOf<String>()
            val found = provider(calls = calls, fixtureBase = base).discover(selected, emptySet())
            val candidate = found.candidates.single()
            assertEquals(expectedName, candidate.name)
            assertEquals(sha(fixture("$base-detail.html")), candidate.evidenceSha256)
            assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, candidate.outcome)
            assertTrue(candidate.validFor(selected))
            assertTrue(candidate.canConfirm(selected))
            assertTrue(candidate.reason.contains("главный престол"))
            assertEquals(1, calls.count { it.contains("m=info") })
            assertTrue(found.incomplete)
            assertFalse(found.unavailable)
        }
    }

    @Test fun `different archangels saints and resurrection dedications never inherit a neighbouring source`() = runBlocking {
        val controls = listOf(
            "kasimov-michael" to "Храм Архангела Гавриила г. Касимова",
            "serednikovo-alexius" to "Церковь Алексия, человека Божия",
            "alabushevo-seraphim" to "Церковь Серафима Выритского",
            "bykovo-resurrection" to "Церковь Воскресения Словущего")
        for ((base, name) in controls) {
            val calls = mutableListOf<String>()
            val found = provider(calls = calls, fixtureBase = base).discover(holdoutParish(base).copy(name = name), emptySet())
            assertTrue("Wrong dedication: $name", found.candidates.isEmpty())
            // Geometry still reaches the real detail; a hint gate did not hide this control.
            assertEquals(1, calls.count { it.contains("m=info") })
            assertEquals(1, found.observedIds.size)
            assertTrue(found.incomplete)
            assertFalse(found.unavailable)
        }
    }

    @Test fun `an exact published title survives a grammatical difference in the structured altar`() = runBlocking {
        val title = "Храм Иоанна Богослова"
        val collection = JsonParser.parseString(fixture("yar-collection.json")).asJsonObject
        val properties = collection.getAsJsonArray("features")[0].asJsonObject.getAsJsonObject("properties")
        val oldTitle = properties.get("hintContent").asString
        properties.addProperty("hintContent", title)
        val detail = fixture("yar-detail.html").replace(oldTitle, title)
            .replace("Петра и Павла - Июль", "Иоанн Богослов - Июль")
        val selected = parish().copy(name = title)
        val found = provider(collection = collection.toString(), detail = detail).discover(selected, emptySet())
        val candidate = found.candidates.single()
        assertEquals(title, candidate.name)
        assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, candidate.outcome)
        assertTrue(candidate.validFor(selected))
        assertTrue(candidate.canConfirm(selected))
        assertFalse(found.unavailable)
        assertTrue(found.incomplete)
    }

    @Test fun `literal abbreviated Alexius evidence preserves published words and passes the unchanged validator`() = runBlocking {
        val calls = mutableListOf<String>()
        val selected = holdoutParish("serednikovo-alexius")
        val found = provider(calls = calls, fixtureBase = "serednikovo-alexius")
            .discover(selected, emptySet())
        val candidate = found.candidates.single()
        assertEquals("Алексиевский храм с. Середниково [митр. Алексия Московского]", candidate.name)
        assertEquals("http://hram-mcyri.ru/", candidate.sourceUrl)
        assertEquals(sha(fixture("serednikovo-alexius-detail.html")), candidate.evidenceSha256)
        assertTrue(candidate.validFor(selected))
        assertTrue(candidate.canConfirm(selected))
        assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, candidate.outcome)
        assertEquals(1, calls.count { it.contains("m=info") })
        assertFalse(found.unavailable)
        assertTrue(fixture("serednikovo-alexius-detail.html").contains("митр. Алексия Московского"))
    }

    @Test fun `minor altars history and administrative headings cannot supply the missing dedication`() = runBlocking {
        val base = "alabushevo-seraphim"
        val original = fixture("$base-detail.html")
        for (detail in listOf(
            original.replace("altar is_main", "altar"),
            original.replace("Престольные праздники:", "Историческая справка:"),
            original.replace("Серафима Саровского -", "Серафима Выритского -")
                .replace("</ul></li><li><span class='ttl'>Статус", "<li class='altar'>Серафима Саровского - Январь 15 [по н.с.]</li></ul></li><li><span class='ttl'>Статус"))) {
            val found = provider(fixtureBase = base, detail = detail).discover(holdoutParish(base), emptySet())
            assertTrue(found.candidates.isEmpty())
            assertFalse(found.unavailable)
        }
        val title = "Серафимовское благочиние"
        val collection = JsonParser.parseString(fixture("$base-collection.json")).asJsonObject
        collection.getAsJsonArray("features")[0].asJsonObject.getAsJsonObject("properties").addProperty("hintContent", title)
        val management = original.replace("Серафимовский храм с. Алабушево", title)
        val found = provider(fixtureBase = base, detail = management, collection = collection.toString())
            .discover(holdoutParish(base), emptySet())
        assertTrue(found.candidates.isEmpty())
        assertFalse(found.unavailable)
    }

    @Test fun `real structured fixtures retain captured source SHA`() {
        val manifest = JsonParser.parseString(fixture("structured-main-fixtures.json")).asJsonArray
        assertEquals(10, manifest.size())
        for (entry in manifest) {
            val item = entry.asJsonObject
            assertEquals(item.get("sha256").asString, sha(fixture(item.get("fixture").asString)))
        }
    }

    @Test fun `deadline cancels discovery without a partial success claim`() = runBlocking {
        val provider = OfficialMapCandidateProvider(ScheduleTransport { delay(1_000); error("Cancelled") },
            Clock.fixed(now, ZoneOffset.UTC), timeoutMillis = 10)
        val found = provider.discover(parish(), emptySet())
        assertTrue(found.unavailable)
        assertTrue(found.candidates.isEmpty())
    }

    @Test fun `no advertised widget script means no guessed API token or account calls`() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = OfficialMapCandidateProvider(ScheduleTransport { url ->
            calls += url; response(url, "<html><body>Карта временно недоступна</body></html>")
        })
        assertTrue(provider.discover(parish(), emptySet()).unavailable)
        assertEquals(listOf(home), calls)
    }
}
