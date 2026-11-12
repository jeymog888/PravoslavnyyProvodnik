package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.ui.arrivalSchedule
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/** Real public identity + weekly HTML fixtures; only the obsolete endpoint failure is simulated. */
class ScheduleEndpointRecoveryTest {
    @Test fun selectedMonasteryRecoversItsGeneralTimetableThroughCatalogAndReopensOffline() = runBlocking {
        val selected = NovosibirskSourceFixture.parish.copy(id = "catalog:novosibirsk-monastery",
            name = "Мужской монастырь Новомучеников Российских (Новосибирск)")
        val now = Instant.parse("2026-11-12T03:00:00Z")
        val requests = mutableListOf<String>()
        var saved: String? = null
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = saved
            override fun write(parishId: String, value: String) { saved = value }
        }
        val transport = ScheduleTransport { url ->
            requests += url
            when {
                url == NovosibirskSourceFixture.old -> throw IOException("obsolete configured endpoint")
                "api.openstreetmap.org" in url -> NovosibirskSourceFixture.response(url,
                    """{"elements":[{"type":"way","id":55228075,"tags":{"website":"${NovosibirskSourceFixture.old}"}}]}""")
                "categorymembers" in url -> NovosibirskSourceFixture.response(url, NovosibirskSourceFixture.category())
                "prop=revisions" in url -> NovosibirskSourceFixture.response(url, NovosibirskSourceFixture.revisions())
                url == NovosibirskSourceFixture.current -> ScheduleResponse(url,
                    NovosibirskSourceFixture.fixture("weekly/novosibirsk.html").toByteArray(), "text/html")
                else -> error("Unexpected request $url")
            }
        }
        val repository = InternetScheduleRepository(transport, cache)
        val ready = repository.refresh(selected, now, force = true)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(1, requests.count { it == NovosibirskSourceFixture.old })
        assertEquals(4, requests.size)
        assertEquals(NovosibirskSourceFixture.current, ready.website)
        assertEquals("https://azbyka.ru/palomnik/index.php?curid=6081", ready.siteEvidenceUrl)
        val publication = requireNotNull(ready.publication)
        assertTrue(publication.yearInferred)
        for (date in listOf("2026-11-12", "2026-11-15")) {
            assertEquals(listOf("08:30", "17:00"), publication.events.filter { it.date == date }.map { it.time })
        }
        assertEquals(ready, repository.cached(selected))
        val offline = InternetScheduleRepository(ScheduleTransport { throw IOException() }, cache)
        assertEquals(ready, offline.cached(selected))
        val failed = offline.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(publication, failed.publication)
        assertEquals(ready.website, failed.website)
        assertEquals(ready.sourceUrl, failed.sourceUrl)
        assertEquals(ready.siteEvidenceUrl, failed.siteEvidenceUrl)
        assertEquals(listOf("08:30", "17:00"), arrivalSchedule(selected, LocalDate.of(2026, 11, 12), now, failed).entries.map { it.time })
    }
}
