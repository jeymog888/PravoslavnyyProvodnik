package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.discovery.DiscoveryJson
import ru.liturgyguide.service.discovery.GeoPoint
import java.net.URI

class ScheduleUrlIdnTest {
    private val unicode = "ппсобор.рф"
    private val ascii = "xn--90azbbajd.xn--p1ai"

    @Test fun publishedCyrillicHostAndPathBecomeAnExplicitAsciiEndpoint() {
        val path = "/расписание/?date=2026-11-12&title=молебен%26служба#сегодня"
        val expected = URI("https://$ascii${path.substringBefore('#')}").toASCIIString()
        assertEquals(expected, ScheduleUrl.publicSource("https://$unicode$path"))
        assertEquals(expected, ScheduleUrl.normalized("https://$unicode$path"))
        assertEquals(expected, ScheduleUrl.publicSource(expected))
        assertEquals("date=2026-11-12&title=%D0%BC%D0%BE%D0%BB%D0%B5%D0%B1%D0%B5%D0%BD%26%D1%81%D0%BB%D1%83%D0%B6%D0%B1%D0%B0",
            URI(expected).rawQuery)
    }

    @Test fun protocolAndExplicitPortsKeepTheirExistingMeaning() {
        assertEquals("http://$ascii/", ScheduleUrl.publicSource("http://$unicode:80"))
        assertEquals("https://$ascii/", ScheduleUrl.publicSource("https://$unicode:443"))
        assertEquals("https://$ascii/", ScheduleUrl.normalized("http://$unicode"))
        assertNull(ScheduleUrl.normalized("http://$unicode:80"))
        for (url in listOf("https://$unicode:80/", "http://$unicode:443/", "https://$unicode:8443/", "https://$unicode:/"))
            assertNull(url, ScheduleUrl.publicSource(url))
    }

    @Test fun unicodeAuthorityCannotBypassPublicSourceRestrictions() {
        val bad = listOf(
            "https://логин:пароль@$unicode/", "https://логин@$unicode/",
            "https://$unicode%40example.org/", "https://%D0%BF%D0%BF%D1%81%D0%BE%D0%B1%D0%BE%D1%80.%D1%80%D1%84/",
            "https://127。0。0。1/", "https://[::1]/", "https://храм.local/", "https://храм.localhost/",
            "https://$unicode\\@example.org/", "https://-храм.рф/", "https://храм_.рф/", "https://храм..рф/",
            "https://ппсо\u202Eбор.рф/", "https://ппсо\u200Bбор.рф/", "https://$unicode/\u2066расписание",
            "https://$unicode/\nрасписание", "https://$unicode:４４３/", "//$unicode/", "$unicode/расписание"
        )
        for (url in bad) {
            assertNull(url, ScheduleUrl.publicSource(url))
            assertNull(url, ScheduleUrl.normalized(url))
        }
    }

    @Test fun idnaMappingIsDeterministicAndDoesNotDecodeEscapedQueryDelimiters() {
        assertEquals("https://strasse.de/?page=a%26b%3Dc", ScheduleUrl.publicSource("https://straße.de/?page=a%26b%3Dc"))
        assertEquals("https://$ascii/", ScheduleUrl.publicSource("https://ППСОБОР.РФ."))
    }

    @Test fun escapedUnicodeLengthCannotProduceAnEndpointRejectedOnItsNextRead() {
        val accepted = requireNotNull(ScheduleUrl.publicSource("https://$unicode/" + "х".repeat(300)))
        assertEquals(accepted, ScheduleUrl.publicSource(accepted))
        assertNull(ScheduleUrl.publicSource("https://$unicode/" + "х".repeat(400)))
    }

    @Test fun redirectPolicyStillRequiresTheSelectedHostAndNeverDowngradesHttps() {
        val selected = requireNotNull(ScheduleUrl.publicSource("https://$unicode/"))
        val www = requireNotNull(ScheduleUrl.publicSource("https://www.$unicode/расписание"))
        assertTrue(ScheduleUrl.permittedRedirect(selected, www))
        assertFalse(ScheduleUrl.permittedRedirect(selected, "https://another.example/"))
        assertFalse(ScheduleUrl.permittedRedirect(selected, "http://$ascii/"))
    }

    @Test fun mapSourcePreservesAnIdnWebsiteWithoutAddingHttpUpgrade() {
        fun church(website: String) = DiscoveryJson.churches("""{"elements":[
            {"type":"node","id":42,"lat":55.0,"lon":37.0,"tags":{"name":"Петропавловский собор",
            "amenity":"place_of_worship","religion":"christian","denomination":"orthodox","website":"$website"}}]}""",
            GeoPoint(55.0, 37.0), 3_000).single()
        assertEquals("https://$ascii/", church("https://$unicode").website)
        assertNull(church("http://$unicode/").website)
        assertNull(church("https://user@$unicode/").website)
        assertEquals("osm:node:42", church("https://$unicode/").id)
    }
}
