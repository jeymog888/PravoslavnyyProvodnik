package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScheduleEntityScopeResolverTest {
    private val gson = Gson()
    private val church = NovosibirskSourceFixture.parish.copy(website = "")
    private val title = NovosibirskSourceFixture.title
    private val full = NovosibirskSourceFixture.fixture("palomnik/cityless/novosibirsk-complex.wiki")
    private fun response(url: String, body: String) = NovosibirskSourceFixture.response(url, body)
    private fun revision(name: String, content: String) = gson.toJson(mapOf("query" to mapOf("pages" to mapOf("6081" to mapOf(
        "pageid" to 6081, "title" to name, "revisions" to listOf(mapOf("slots" to mapOf("main" to mapOf("*" to content)))))))))
    private fun transport(name: String = title, content: String = full, requests: MutableList<String> = mutableListOf()) = ScheduleTransport { url ->
        requests += url
        response(url, when {
            "openstreetmap" in url -> """{"elements":[{"type":"way","id":55228075,"tags":{}}]}"""
            "categorymembers" in url -> gson.toJson(mapOf("query" to mapOf("categorymembers" to listOf(mapOf("pageid" to 6081, "title" to name)))))
            "list=search" in url -> gson.toJson(mapOf("query" to mapOf("searchinfo" to mapOf("totalhits" to 1),
                "search" to listOf(mapOf("pageid" to 6081, "ns" to 0, "title" to name)))))
            "prop=revisions" in url -> revision(name, content)
            "wikipedia.org" in url -> """{"query":{"pages":{"6081":{"title":"$title","coordinates":[{"lat":54.975263,"lon":82.908625}],"pageprops":{"wikibase_item":"Q42"}}}}}"""
            "wikidata.org" in url -> """{"entities":{"Q42":{"claims":{"P856":[{"mainsnak":{"datavalue":{"value":"${NovosibirskSourceFixture.current}"}}}]}}}}"""
            else -> error("Unexpected request $url")
        })
    }

    @Test fun realComplexArticleIsRejectedForBuildingWithAndWithoutCityAcrossFullResolverChain() = runBlocking {
        assertTrue(full.contains("вид=Монастырь")); assertTrue(full.contains("Церковь Серафима Саровского"))
        for (city in listOf("Новосибирск", "")) {
            val requests = mutableListOf<String>()
            assertNull(OpenScheduleSiteResolver(transport(requests = requests)).resolve(church.copy(city = city)))
            assertTrue(requests.any { "prop=revisions" in it })
            assertTrue(requests.any { "generator=search" in it })
            assertFalse("Wikipedia must not bypass the incompatible article", requests.any { "wikidata.org" in it })
        }
    }

    @Test fun selectedMonasteryCanUseItsOwnFullArticleWithAndWithoutCity() = runBlocking {
        for (city in listOf("Новосибирск", "")) {
            val selected = church.copy(id = "catalog:novosibirsk-monastery", name = title, city = city)
            val found = PalomnikScheduleSiteResolver(transport()).resolve(selected)!!
            assertEquals(NovosibirskSourceFixture.current, found.url)
            assertEquals(ScheduleEntityScope.MONASTERY, found.binding!!.matchedScope)
            assertTrue(found.binding.validFor(selected, found.url, found.evidenceUrl))
        }
    }

    @Test fun churchOfMonasteryArticleIsAllowedButExplicitConflictingKindIsNot() = runBlocking {
        // Synthetic title/kind variants, not rewritten claims about the real complex fixture.
        val name = "Храм Новомучеников Российских при монастыре (Новосибирск)"
        for (city in listOf("Новосибирск", "")) {
            val selected = church.copy(city = city)
            assertNotNull("city=$city", PalomnikScheduleSiteResolver(transport(name, full.replace("вид=Монастырь", "вид=Храм"))).resolve(selected))
            assertNull(PalomnikScheduleSiteResolver(transport(name, full)).resolve(selected))
        }
    }

    @Test fun conflictingExplicitWikipediaTagCannotBeBypassedBySiblingWikidataTag() = runBlocking {
        for (withWikidata in listOf(true, false)) {
            val requests = mutableListOf<String>()
            val base = transport(requests = requests)
            val sibling = if (withWikidata) ",\"wikidata\":\"Q42\"" else ""
            val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
                if ("openstreetmap" in url) response(url, """{"elements":[{"type":"way","id":55228075,"tags":{
                    "wikipedia":"ru:$title"$sibling}}]}""") else base.fetch(url)
            })
            assertNull(resolver.resolve(church))
            assertFalse(requests.any { "wikidata.org" in it })
        }
    }

    @Test fun exactOsmWikipediaProperNameWithUnknownTypeWorksWithAndWithoutSiblingWikidata() = runBlocking {
        val selected = church.copy(id = "osm:way:42", name = "Собор Воскресения Христова", city = "Санкт-Петербург")
        for (withWikidata in listOf(true, false)) {
            val requests = mutableListOf<String>()
            val sibling = if (withWikidata) ",\"wikidata\":\"Q42\"" else ""
            val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
                requests += url
                response(url, when {
                    "openstreetmap.org" in url -> """{"elements":[{"type":"way","id":42,"tags":{"wikipedia":"ru:Спас на Крови"$sibling}}]}"""
                    "wikipedia.org" in url -> """{"query":{"pages":{"42":{"title":"Спас на Крови","pageprops":{"wikibase_item":"Q42"}}}}}"""
                    "wikidata.org" in url -> """{"entities":{"Q42":{"claims":{"P856":[{"mainsnak":{"datavalue":{"value":"https://selected.example/"}}}]}}}}"""
                    else -> error("Exact link must not require broad search: $url")
                })
            })
            val found = resolver.resolve(selected)!!
            assertEquals("https://selected.example/", found.url)
            assertEquals(ScheduleBindingMethod.EXACT_MAP_LINK, found.binding!!.method)
            assertTrue(found.binding.validFor(selected, found.url, found.evidenceUrl))
            assertEquals(if (withWikidata) 2 else 3, requests.size)
            if (!withWikidata) assertEquals(ScheduleEntityScope.UNKNOWN, found.binding.matchedScope)

            val cached = InternetScheduleState(selected.id, InternetScheduleStatus.READY, found.url, found.url,
                "2026-11-12T06:00:00Z", publication = SchedulePublication(selected.id, selected.timezone, found.url, found.url,
                    "2026-11-12T06:00:00Z", listOf(InternetService("2026-11-12", "08:00", "Литургия", "liturgy"))),
                siteEvidenceUrl = found.evidenceUrl, siteBinding = found.binding)
            assertEquals(cached, ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(cached), selected))
            val legacy = JsonParser.parseString(ScheduleCacheCodec.encode(cached)).asJsonObject.apply {
                remove("siteBinding"); remove("sourceNeedsVerification")
            }
            val pending = ScheduleCacheCodec.decode(legacy.toString(), selected)!!
            assertTrue(pending.sourceNeedsVerification); assertNull(pending.publication)
            assertEquals(found.url, pending.website)
            assertEquals(pending, ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(pending), selected))
        }
    }

    @Test fun unknownScopeRemainsInsufficientForBroadWikipediaSearch() = runBlocking {
        val selected = church.copy(id = "catalog:42", name = "Спас на Крови")
        val requests = mutableListOf<String>()
        val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
            requests += url
            response(url, when {
                "categorymembers" in url -> """{"query":{"categorymembers":[]}}"""
                "wikipedia.org" in url -> """{"query":{"pages":{"42":{"title":"Спас на Крови","coordinates":[{"lat":54.9755397,"lon":82.9086203}],"pageprops":{"wikibase_item":"Q42"}}}}}"""
                else -> error("Unknown broad-search identity must not reach a website: $url")
            })
        })
        assertNull(resolver.resolve(selected))
        assertFalse(requests.any { "wikidata.org" in it })
    }
}
