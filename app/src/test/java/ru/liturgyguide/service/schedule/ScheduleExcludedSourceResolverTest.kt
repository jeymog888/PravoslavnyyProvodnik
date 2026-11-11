package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish

internal object NovosibirskSourceFixture {
    const val old = "http://neomartires.prihod.ru/"
    const val current = "https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/"
    const val title = "Мужской монастырь Новомучеников Российских (Новосибирск)"
    val parish = Parish("osm:way:55228075", "Храм в честь Святых Новомучеников и Исповедников Российских", "Новомучеников", "Новосибирск",
        "улица Немировича-Данченко, 120/1", "", old, "Asia/Novosibirsk", "https://www.openstreetmap.org/way/55228075",
        "", "", "", emptyList(), "", "", 54.9755397, 82.9086203)
    fun fixture(name: String) = requireNotNull(javaClass.getResource("/schedule/$name")).readText()
    fun category(ids: List<Int> = listOf(6081)) = Gson().toJson(mapOf("query" to mapOf("categorymembers" to ids.map { mapOf("pageid" to it, "title" to title) })))
    fun revisions(contents: Map<Int, String> = mapOf(6081 to fixture("palomnik/novosibirsk.wiki"))) = Gson().toJson(mapOf("query" to mapOf("pages" to
        contents.mapKeys { it.key.toString() }.mapValues { (id, content) -> mapOf("pageid" to id.toInt(), "title" to title,
            "revisions" to listOf(mapOf("slots" to mapOf("main" to mapOf("*" to content))))) })))
    fun response(url: String, body: String) = ScheduleResponse(url, body.toByteArray(), "application/json")
}

class ScheduleExcludedSourceResolverTest {
    private fun response(url: String, body: String) = NovosibirskSourceFixture.response(url, body)
    private val parish = NovosibirskSourceFixture.parish
    private val old = NovosibirskSourceFixture.old
    private val current = NovosibirskSourceFixture.current
    private fun osm(tags: Map<String, String>) = Gson().toJson(mapOf("elements" to listOf(mapOf("type" to "way", "id" to 55228075, "tags" to tags))))

    @Test fun endpointExclusionsKeepDistinctSchedulePathsAndQueries() {
        val excluded = ScheduleSourceExclusions(setOf("http://www.example.org/#top", "https://example.org/schedule/?month=11"))
        assertTrue(excluded.contains("https://example.org/"))
        assertTrue(excluded.contains("https://www.example.org/schedule?month=11"))
        assertFalse(excluded.contains("https://example.org/schedule/?month=12"))
        assertFalse(excluded.contains("https://example.org/raspisanie/"))
        assertFalse(excluded.contains("https://different.example.org/"))
    }

    @Test fun excludingConfiguredAndOsmWebsiteContinuesToExactContactWebsite() = runBlocking {
        val calls = mutableListOf<String>()
        val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
            calls += url; response(url, osm(mapOf("website" to old, "contact:website" to current)))
        })
        val site = resolver.resolve(parish, setOf(old))
        assertEquals(current, site?.url)
        assertEquals("https://api.openstreetmap.org/api/0.6/way/55228075.json", site?.evidenceUrl)
        assertEquals(1, calls.size)
    }

    @Test fun excludedOsmWebsiteCanReachTheProvenSeparateSchedulePathOnTheSameDomain() = runBlocking {
        val website = "https://nskmonastyr.cerkov.ru/"
        val resolver = OpenScheduleSiteResolver(ScheduleTransport { url -> response(url, when {
            "openstreetmap" in url -> osm(mapOf("website" to website))
            "categorymembers" in url -> NovosibirskSourceFixture.category()
            else -> NovosibirskSourceFixture.revisions()
        }) })
        val site = resolver.resolve(parish.copy(website = website), setOf(website))
        assertEquals(current, site?.url)
        assertEquals("https://azbyka.ru/palomnik/index.php?curid=6081", site?.evidenceUrl)
    }

    @Test fun bothExactWikidataRoutesSkipAnExcludedPreferredOfficialUrl() = runBlocking {
        for (wikiTag in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
                calls += url
                response(url, when {
                    "openstreetmap" in url -> osm(mapOf("website" to old) + if (wikiTag) mapOf("wikipedia" to "ru:Монастырь") else mapOf("wikidata" to "Q42"))
                    "wikipedia.org" in url -> """{"query":{"pages":{"1":{"pageprops":{"wikibase_item":"Q42"}}}}}"""
                    else -> """{"entities":{"Q42":{"claims":{"P856":[
                        {"rank":"preferred","mainsnak":{"datavalue":{"value":"$old"}}},
                        {"rank":"normal","mainsnak":{"datavalue":{"value":"$current"}}}]}}}}"""
                })
            })
            val site = resolver.resolve(parish, setOf(old))
            assertEquals(current, site?.url)
            assertEquals("https://www.wikidata.org/wiki/Q42", site?.evidenceUrl)
            assertEquals(if (wikiTag) 3 else 2, calls.size)
        }
    }

    @Test fun anExcludedNearbyChurchDoesNotDisambiguateTwoMatchingCatalogIdentities() = runBlocking {
        val first = NovosibirskSourceFixture.fixture("palomnik/novosibirsk.wiki")
        val second = first.replace(current, "https://another.example/schedule/")
        val resolver = PalomnikScheduleSiteResolver(ScheduleTransport { url -> response(url,
            if ("categorymembers" in url) NovosibirskSourceFixture.category(listOf(6081, 6082))
            else NovosibirskSourceFixture.revisions(mapOf(6081 to first, 6082 to second))) })
        assertNull(resolver.resolve(parish, setOf(current)))
    }

    @Test fun multiplePublishedScheduleLinksRemainAmbiguousEvenAfterOneWasExcluded() = runBlocking {
        val content = NovosibirskSourceFixture.fixture("palomnik/novosibirsk.wiki") +
            "\n[https://another.example/schedule/ Расписание на сайте храма]\n"
        val resolver = PalomnikScheduleSiteResolver(ScheduleTransport { url -> response(url,
            if ("categorymembers" in url) NovosibirskSourceFixture.category()
            else NovosibirskSourceFixture.revisions(mapOf(6081 to content))) })
        assertNull(resolver.resolve(parish, setOf(current)))
    }

    @Test fun catalogMayUseItsUniqueHomepageWhenItsUniqueScheduleEndpointWasExcluded() = runBlocking {
        val homepage = "https://nskmonastyr.cerkov.ru/"
        val content = NovosibirskSourceFixture.fixture("palomnik/novosibirsk.wiki").replace(old, homepage)
        val resolver = PalomnikScheduleSiteResolver(ScheduleTransport { url -> response(url,
            if ("categorymembers" in url) NovosibirskSourceFixture.category()
            else NovosibirskSourceFixture.revisions(mapOf(6081 to content))) })
        assertEquals(homepage, resolver.resolve(parish, setOf(current))?.url)
        assertNull(resolver.resolve(parish, setOf(current, homepage)))
    }

    @Test fun excludingTheClosestIdentityWebsiteCannotSelectAFartherNamesake() = runBlocking {
        val selected = parish.copy(id = "test:42", name = "Никольский храм", latitude = 55.77, longitude = 37.63)
        val calls = mutableListOf<String>()
        val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
            calls += url
            response(url, when {
                "categorymembers" in url -> """{"query":{"categorymembers":[]}}"""
                "wikipedia.org" in url -> """{"query":{"pages":{
                    "1":{"title":"Никольский храм","coordinates":[{"lat":55.77,"lon":37.63}],"pageprops":{"wikibase_item":"Q1"}},
                    "2":{"title":"Никольский храм","coordinates":[{"lat":55.7736,"lon":37.63}],"pageprops":{"wikibase_item":"Q2"}}
                }}}"""
                "Q1.json" in url -> """{"entities":{"Q1":{"claims":{"P856":[{"mainsnak":{"datavalue":{"value":"$old"}}}]}}}}"""
                "Q2.json" in url -> """{"entities":{"Q2":{"claims":{"P856":[{"mainsnak":{"datavalue":{"value":"$current"}}}]}}}}"""
                else -> error("Unexpected request $url")
            })
        })
        assertNull(resolver.resolve(selected, setOf(old)))
        assertTrue(calls.any { "Q1.json" in it })
        assertFalse(calls.any { "Q2.json" in it })
    }

    @Test fun allExcludedOfficialUrlsAndAbsentCoordinatesNeverBecomeAnArbitrarySite() = runBlocking {
        val resolver = OpenScheduleSiteResolver(ScheduleTransport { url -> response(url, when {
            "openstreetmap" in url -> osm(mapOf("website" to old, "contact:website" to current, "wikidata" to "Q42"))
            else -> """{"entities":{"Q42":{"claims":{"P856":[{"rank":"normal","mainsnak":{"datavalue":{"value":"$current"}}}]}}}}"""
        }) })
        assertNull(resolver.resolve(parish.copy(latitude = null, longitude = null), setOf(old, current)))
    }
}
