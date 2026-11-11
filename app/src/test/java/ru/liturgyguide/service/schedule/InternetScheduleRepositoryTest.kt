package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.io.IOException
import java.time.Instant

class InternetScheduleRepositoryTest {
    private val now = Instant.parse("2026-11-10T06:00:00Z")
    private val html = """<article data-day="2026-11-10"><li><time>09:00</time><span>Литургия</span></li></article>"""
    private fun parish(id: String = "osm:node:42", site: String = "") = Parish(id, "Храм", "Храм", "Москва", "Адрес", "", site,
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private class Cache : ScheduleCacheStore {
        val data = mutableMapOf<String, String>()
        var writes = 0
        override fun read(parishId: String) = data[parishId]
        override fun write(parishId: String, value: String) { writes++; data[parishId] = value }
    }
    private fun response(url: String, body: String, type: String = "text/html; charset=utf-8") = ScheduleResponse(url, body.toByteArray(), type)

    @Test fun `known website is visible before the first request without inventing a publication`() {
        val cache = Cache()
        val repo = InternetScheduleRepository(ScheduleTransport { error("Initial state must not request the network") }, cache)
        val selected = parish(site = "https://EXAMPLE.org/schedule/#top")
        val state = repo.cached(selected)
        assertEquals("https://example.org/schedule/", state.website)
        assertEquals(state.website, state.configuredWebsite)
        assertEquals(InternetScheduleStatus.IDLE, state.status)
        assertNull(state.publication)
        assertEquals("", state.checkedAt)
        assertEquals(0, cache.writes)
        assertEquals(state, OfflineInternetSchedules.cached(selected))
        assertEquals("", repo.cached(selected.copy(website = "javascript:alert(1)")).website)
    }

    @Test fun `exact OSM identity enriches a website then imports a real same origin schedule`() = runBlocking {
        val cache = Cache()
        val calls = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when (url) {
                "https://api.openstreetmap.org/api/0.6/node/42.json" -> response(url,
                    """{"elements":[{"type":"node","id":99,"tags":{"website":"https://wrong.org/"}},
                    {"type":"node","id":42,"tags":{"contact:website":"https://example.org/"}}]}""", "application/json")
                "https://example.org/" -> response(url, "<a href='/schedule/'>Расписание богослужений</a>")
                "https://example.org/schedule/" -> response(url, html)
                else -> error("Unexpected request $url")
            }
        }, cache)
        val selected = parish()
        val result = repo.refresh(selected, now)
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertEquals(3, calls.size)
        assertEquals("https://example.org/schedule/", result.publication?.sourceUrl)
        assertEquals("liturgy", selected.withInternetSchedule(result, now).schedule.single().kind)
        assertEquals(result, repo.cached(selected))
        repo.refresh(selected, now.plusSeconds(60))
        assertEquals(3, calls.size)
        assertNull(repo.cached(parish("osm:node:43")).publication)
    }

    @Test fun `www redirect on a child schedule survives recreation and offline refresh`() = runBlocking {
        val cache = Cache()
        val selected = parish()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            when (url) {
                "https://example.org/" -> response(url, "<a href='/schedule/'>Расписание</a>")
                "https://example.org/schedule/" -> response("https://www.example.org/schedule/", html)
                else -> error("Unexpected request $url")
            }
        }, cache)
        val result = repo.refresh(selected, now, sourceOverride = "https://example.org/")
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertEquals("https://www.example.org/schedule/", result.publication?.sourceUrl)
        assertEquals("https://example.org/", result.customSource)
        assertEquals(result, repo.cached(selected))

        val restored = InternetScheduleRepository(ScheduleTransport { throw IOException() }, cache)
        assertEquals(result, restored.cached(selected))
        val offline = restored.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, offline.status)
        assertEquals(result.publication, offline.publication)
        assertEquals(result.customSource, offline.customSource)
        assertEquals(offline, restored.cached(selected))

        // Accept only the transport's www alias, not arbitrary subdomains or unrelated sites.
        for (foreign in listOf("https://other.example.org/schedule/", "https://another.org/schedule/")) {
            cache.data[selected.id] = ScheduleCacheCodec.encode(result.copy(
                publication = result.publication!!.copy(sourceUrl = foreign)))
            assertNull(restored.cached(selected).publication)
        }
    }

    @Test fun `missing website unsupported PDF and failed refresh preserve a confirmed publication`() = runBlocking {
        val cache = Cache()
        var fail = false
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            if (fail) throw IOException()
            response(url, if (url.endsWith("pdf")) "PDF" else html, if (url.endsWith("pdf")) "application/pdf" else "text/html")
        }, cache)
        assertEquals(InternetScheduleStatus.NO_WEBSITE, repo.refresh(parish("custom:1"), now).status)
        val selected = parish(site = "https://example.org/")
        val ready = repo.refresh(selected, now)
        fail = true
        val failed = repo.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
        assertEquals(ready.publication, failed.publication)
        val bundled = selected.copy(scheduleValidFrom = "2026-11-10", scheduleValidUntil = "2026-11-10",
            schedule = listOf(ParishService("2026-11-10", "10:00", "Литургия", "liturgy")))
        assertEquals(listOf("09:00"), bundled.withInternetSchedule(failed, now).schedule.map { it.time })
        fail = false
        val pdf = repo.refresh(selected, now.plusSeconds(120), force = true, sourceOverride = "https://example.org/schedule.pdf")
        assertEquals(InternetScheduleStatus.UNSUPPORTED, pdf.status)
        assertNull(pdf.publication) // Changing the source cannot silently keep data from the former source.
    }

    @Test fun `expired cache cannot drive current service and changing configured website invalidates binding`() = runBlocking {
        val repo = InternetScheduleRepository(ScheduleTransport { response(it, html) }, Cache())
        val selected = parish(site = "https://example.org/")
        val result = repo.refresh(selected, now)
        assertEquals(selected, selected.withInternetSchedule(result, now.plusSeconds(86_401)))
        assertNull(repo.cached(selected.copy(website = "https://another.org/")).publication)
        assertNull(repo.cached(selected.copy(timezone = "Europe/Helsinki")).publication)
        assertFalse(result.publication!!.isFresh(now.minusSeconds(1)))
    }

    @Test fun `cancelled or stale lookup never publishes a late result`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = Cache()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            response(url, html)
        }, cache)
        val job = launch { repo.refresh(parish(site = "https://example.org/"), now) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(0, cache.writes)
    }

    @Test fun `a lookup is bounded to three same origin HTML pages and never follows external links`() = runBlocking {
        val calls = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            response(url, "<a href='/schedule/1'>Расписание</a><a href='/schedule/2'>Расписание</a><a href='https://outside.org/schedule'>Расписание</a>")
        }, Cache())
        assertEquals(InternetScheduleStatus.UNSUPPORTED, repo.refresh(parish(site = "https://example.org/"), now).status)
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.startsWith("https://example.org/") })
    }

    @Test fun `generic HTML table imports without a known site template`() = runBlocking {
        val repo = InternetScheduleRepository(ScheduleTransport { url -> response(url,
            "<h1>Расписание ноябрь 2026</h1><table><tr><td>15 ноября</td><td>09:00</td><td>Литургия</td></tr></table>") }, Cache())
        val state = repo.refresh(parish(site = "https://example.org/"), now)
        assertEquals(InternetScheduleStatus.READY, state.status)
        assertEquals("2026-11-15", state.publication!!.events.single().date)
        assertEquals("09:00", state.publication.events.single().time)
    }

    @Test fun `a future homepage announcement does not hide the linked current schedule`() = runBlocking {
        val calls = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            response(url, if (url.endsWith("/schedule/"))
                "<h1>Ноябрь 2026</h1><p>11 ноября | 08:00 | Литургия</p>" else
                """<p>30.11.2026 09:00 Литургия</p><a href="/schedule/">Расписание богослужений</a>""")
        }, Cache())
        val result = repo.refresh(parish(site = "https://example.org/"), now)
        assertEquals(listOf("https://example.org/", "https://example.org/schedule/"), calls)
        assertEquals("https://example.org/schedule/", result.publication?.sourceUrl)
        assertEquals(listOf("2026-11-11"), result.publication?.events?.map { it.date })
    }

    @Test fun `a fresh source replaces the bundled time and source even when the bundle covers today`() = runBlocking {
        val selected = parish(site = "https://example.org/").copy(sourceUrl = "https://example.org/old/",
            scheduleSource = "Старое расписание", scheduleValidFrom = "2026-11-10", scheduleValidUntil = "2026-11-10",
            schedule = listOf(ParishService("2026-11-10", "10:00", "Литургия", "liturgy")))
        val repo = InternetScheduleRepository(ScheduleTransport { response(it, html) }, Cache())
        val effective = selected.withInternetSchedule(repo.refresh(selected, now), now)
        assertEquals(listOf("09:00"), effective.schedule.map { it.time })
        assertEquals(selected.website, effective.sourceUrl)
        assertEquals("Страница расписания прихода", effective.scheduleSource)
    }

    @Test fun `a dated cancellation filters a linked schedule without hiding the next day`() = runBlocking {
        val repo = InternetScheduleRepository(ScheduleTransport { url -> response(url,
            if (url.endsWith("/schedule/")) """<h1>Ноябрь 2026</h1><p>15 ноября 09:00 Литургия</p>
                <p>16 ноября 08:00 Литургия</p>""" else """<h1>Ноябрь 2026</h1>
                <h2>15 ноября - все службы отменены</h2><a href="/schedule/">Расписание</a>""") }, Cache())
        val selected = parish(site = "https://example.org/")
        val state = repo.refresh(selected, now)
        assertEquals(listOf("2026-11-16"), state.publication?.events?.map { it.date })
        assertFalse(state.publicationRejected)
        assertEquals(state, repo.cached(selected))
    }

    @Test fun `an explicit cancellation cannot fall through or resurrect cached and bundled events`() = runBlocking {
        val selected = parish(site = "https://example.org/").copy(scheduleValidFrom = "2026-11-10", scheduleValidUntil = "2026-11-15",
            schedule = listOf(ParishService("2026-11-10", "10:00", "Литургия", "liturgy")))
        val cache = Cache()
        var body = html
        val repo = InternetScheduleRepository(ScheduleTransport { response(it, body) }, cache)
        assertNotNull(repo.refresh(selected, now).publication)
        body = """<article class="hentry"><h1 class="entry-title">Ноябрь 2026</h1>
            <div class="entry-content"><h2>Все службы отменены</h2><p>15 Вс. 09:00 Литургия</p></div></article>"""
        val rejected = repo.refresh(selected, now.plusSeconds(60), force = true)
        assertNull(rejected.publication)
        assertTrue(rejected.publicationRejected)
        assertTrue(selected.withInternetSchedule(rejected, now).schedule.isEmpty())
        val restored = InternetScheduleRepository(ScheduleTransport { throw IOException() }, cache)
        assertEquals(rejected, restored.cached(selected))
        val offline = restored.refresh(selected, now.plusSeconds(120), force = true)
        assertTrue(offline.publicationRejected)
        assertTrue(selected.withInternetSchedule(offline, now).schedule.isEmpty())
        assertEquals(offline, restored.cached(selected))
        body = html
        val recovered = repo.refresh(selected, now.plusSeconds(180), force = true)
        assertFalse(recovered.publicationRejected)
        assertEquals(listOf("09:00"), selected.withInternetSchedule(recovered, now.plusSeconds(180)).schedule.map { it.time })
    }

    @Test fun `known day containers keep date and building context when a section needs general parsing`() = runBlocking {
        val selected = parish(site = "https://example.org/").copy(name = "Никольский храм", shortName = "Никольский храм")
        val page = """<h1>Ноябрь 2026</h1><h2>Казанский храм</h2>
            <article data-day="2026-11-15"><li><time>09:00</time><span>Литургия</span></li></article>
            <h2>Никольский храм</h2><article data-day="2026-11-15"><li><time>10:00</time><span>Литургия</span></li></article>"""
        val repo = InternetScheduleRepository(ScheduleTransport { response(it, page) }, Cache())
        val result = repo.refresh(selected, now)
        assertEquals(listOf("10:00"), result.publication?.events?.map { it.time })
    }

    @Test fun `external PDF filename returning HTML cannot publish another site or follow its links`() = runBlocking {
        val selected = parish(site = "https://example.org/")
        val cache = Cache()
        val documentUrl = "https://outside.org/schedule.pdf"
        val calls = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            response(url, when (url) {
                selected.website -> "<a href='$documentUrl'>Расписание PDF</a>"
                documentUrl -> html + "<a href='/schedule/'>Еще расписание</a>"
                else -> error("Foreign HTML navigation must not be followed")
            })
        }, cache)
        val state = repo.refresh(selected, now)
        assertEquals(listOf(selected.website, documentUrl), calls)
        assertEquals(InternetScheduleStatus.UNSUPPORTED, state.status)
        assertNull(state.publication)
        assertEquals(state, repo.cached(selected))
    }

    @Test fun `same site document filename returning HTML still has a valid parish binding`() = runBlocking {
        val selected = parish(site = "https://example.org/")
        val documentUrl = "https://example.org/schedule.pdf"
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            response(url, if (url == selected.website) "<a href='$documentUrl'>Расписание PDF</a>" else html)
        }, Cache())
        val state = repo.refresh(selected, now)
        assertEquals(InternetScheduleStatus.READY, state.status)
        assertEquals(documentUrl, state.publication?.sourceUrl)
        assertEquals("html", state.publication?.documentFormat)
        assertEquals(state, repo.cached(selected))
    }

    @Test fun `explicit linked PDF on another host retains its source relationship through offline cache`() = runBlocking {
        val selected = parish(site = "https://example.org/")
        val cache = Cache()
        val documentUrl = "https://cdn.example.org/schedule.pdf"
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            if (url == selected.website) response(url, "<h1>Расписание</h1><a href='$documentUrl'>Скачать расписание PDF</a>")
            else response(url, "%PDF-fixture", "application/pdf")
        }, cache, ScheduleDocumentExtractor { data -> ScheduleDocument(data.url, "pdf", listOf(
            ScheduleDocumentPage(listOf(ScheduleDocumentLine("Ноябрь 2026"), ScheduleDocumentLine("15 Вс. 09:30 Исповедь и Литургия"))))) })
        val state = repo.refresh(selected, now)
        assertEquals(InternetScheduleStatus.READY, state.status)
        assertEquals(documentUrl, state.publication?.sourceUrl)
        assertEquals(selected.website, state.publication?.linkedFrom)
        assertEquals(state, repo.cached(selected))
        cache.data[selected.id] = ScheduleCacheCodec.encode(state.copy(publication = state.publication!!.copy(linkedFrom = "https://wrong.org/")))
        assertNull(repo.cached(selected).publication)
    }

    @Test fun `an unsuccessful lookup can retry after five minutes instead of hiding for six hours`() = runBlocking {
        var calls = 0
        val repo = InternetScheduleRepository(ScheduleTransport { calls++; throw IOException() }, Cache())
        val selected = parish(site = "https://example.org/")
        repo.refresh(selected, now)
        assertEquals(1, calls)
        repo.refresh(selected, now.plusSeconds(60))
        assertEquals(1, calls)
        repo.refresh(selected, now.plusSeconds(301))
        assertEquals(2, calls)
    }
}
