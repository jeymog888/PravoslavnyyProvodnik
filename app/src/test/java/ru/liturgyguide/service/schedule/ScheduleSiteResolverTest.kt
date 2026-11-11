package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish

class ScheduleSiteResolverTest {
    private fun parish() = Parish("osm:node:42", "Сретенский монастырь", "Сретенский монастырь", "Москва", "Большая Лубянка, 19", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.76556, 37.62972)
    private fun response(url: String, body: String) = ScheduleResponse(url, body.toByteArray(), "application/json")
    private val entity = """{"entities":{"Q1810846":{"claims":{"P856":[
        {"rank":"deprecated","mainsnak":{"datavalue":{"value":"https://old.org/"}}},
        {"rank":"normal","qualifiers":{"P582":[{}]},"mainsnak":{"datavalue":{"value":"https://ended.org/"}}},
        {"rank":"normal","mainsnak":{"datavalue":{"value":"https://monastery.ru/"}}}]}}}}"""

    @Test fun `exact OSM wikidata tag resolves the current official property without search`() = runBlocking {
        val calls = mutableListOf<String>()
        val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
            calls += url
            response(url, if (url.contains("openstreetmap")) """{"elements":[{"type":"node","id":42,"tags":{"wikidata":"Q1810846"}}]}""" else entity)
        })
        val result = resolver.resolve(parish())
        assertEquals("https://monastery.ru/", result?.url)
        assertEquals("https://www.wikidata.org/wiki/Q1810846", result?.evidenceUrl)
        assertEquals(2, calls.size)
    }

    @Test fun `encyclopedia search requires matching name and nearby coordinate`() = runBlocking {
        var latitude = "55.76556"
        var title = "Сретенский монастырь (Москва)"
        val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
            response(url, when {
                url.contains("openstreetmap") -> """{"elements":[{"type":"node","id":42,"tags":{}}]}"""
                url.contains("wikipedia") -> """{"query":{"pages":{"1":{"title":"$title","coordinates":[{"lat":$latitude,"lon":37.62972}],"pageprops":{"wikibase_item":"Q1810846"}}}}}"""
                else -> entity
            })
        })
        assertEquals("https://monastery.ru/", resolver.resolve(parish())?.url)
        latitude = "59.9"
        assertNull(resolver.resolve(parish()))
        latitude = "55.76556"; title = "Новодевичий монастырь"
        assertNull(resolver.resolve(parish()))
    }

    @Test fun `unknown coordinates and generic names never pick an arbitrary site`() = runBlocking {
        val resolver = OpenScheduleSiteResolver(ScheduleTransport { response(it, "{}") })
        assertNull(resolver.resolve(parish().copy(id = "custom:1", latitude = null, longitude = null)))
        assertFalse(OpenScheduleSiteResolver.namesMatch("Храм", "Храм Христа Спасителя"))
        assertFalse(OpenScheduleSiteResolver.namesMatch("Сретенский монастырь", "Новодевичий монастырь"))
    }
}
