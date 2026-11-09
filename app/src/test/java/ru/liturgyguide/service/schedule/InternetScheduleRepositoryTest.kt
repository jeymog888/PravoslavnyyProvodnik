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

    @Test fun `missing website unsupported PDF and failed refresh are honest and preserve curated data`() = runBlocking {
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
        assertEquals(bundled, bundled.withInternetSchedule(failed, now))
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
}
