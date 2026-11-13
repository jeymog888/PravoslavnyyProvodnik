package ru.liturgyguide.service.schedule

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate

class ScheduleWordPressDiscoveryTest {
    private val site = "https://parish.example/"
    private val today = LocalDate.of(2026, 11, 13)
    private fun page(api: String = site + "wp-json/", base: String = site) = Jsoup.parse(
        "<html><head><link rel='https://api.w.org/' href='$api'></head><body></body></html>", base)
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("schedule/wordpress-discovery/$name")!!.readText()
    private fun response(url: String, body: String, type: String = "application/json") = ScheduleResponse(url, body.toByteArray(), type)
    private fun entry(id: Int, title: String = "Расписание богослужений", link: String = site + "schedule/") = JsonObject().apply {
        addProperty("id", id); addProperty("status", "publish"); addProperty("type", "page"); addProperty("link", link)
        add("title", JsonObject().apply { addProperty("rendered", title) })
        add("excerpt", JsonObject().apply { addProperty("rendered", ""); addProperty("protected", false) })
    }
    private fun array(vararg entries: JsonObject) = JsonArray().also { array -> entries.forEach(array::add) }.toString()
    private fun transport(pages: String, posts: String = "[]", calls: MutableList<String> = mutableListOf()) = ScheduleTransport { url ->
        calls += url
        response(url, if (URLDecoder.decode(url, "UTF-8").contains("wp/v2/pages")) pages else posts)
    }

    @Test fun `three independent live primary responses return only their actual canonical publication page`() = runBlocking {
        for ((name, origin, expected) in listOf(
            Triple("krylatskoe", "https://www.hramnaholmah.ru/", "https://www.hramnaholmah.ru/raspisanie-bogosluzhenij/"),
            Triple("novosibirsk", "https://nskmonastyr.cerkov.ru/", "https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/"),
            Triple("zaraysk", "https://blagohram.cerkov.ru/", "https://blagohram.cerkov.ru/main-page/raspisanie-bogosluzhenij/"))) {
            val calls = mutableListOf<String>()
            val discovery = ScheduleWordPressDiscovery(transport(fixture("$name-pages.json"), fixture("$name-posts.json"), calls))
            val result = discovery.discover(origin, Jsoup.parse(fixture("$name-home.html"), origin), today)
            assertEquals(ScheduleWordPressDiscoveryStatus.SEARCHED, result.status)
            assertEquals(listOf(expected), result.links.map { it.url })
            assertTrue(result.links.all { !it.document && it.linkedFrom == origin })
            assertEquals(2, calls.size)
            assertEquals(listOf("pages", "posts"), result.queries.map { it.collection })
            assertTrue(result.queries.all { it.status == ScheduleWordPressQueryStatus.READ && it.page == 1 && !it.completenessKnown })
            assertFalse(result.completenessKnown)
        }
    }

    @Test fun `undeclared REST is not guessed from platform names or an arbitrary script`() = runBlocking {
        val doc = Jsoup.parse("<html><body>WordPress<script>var api='/wp-json/';</script></body></html>", site)
        val result = ScheduleWordPressDiscovery(ScheduleTransport { error("No API was declared") }).discover(site, doc, today)
        assertEquals(ScheduleWordPressDiscoveryStatus.NOT_ADVERTISED, result.status)
        assertTrue(result.links.isEmpty())
    }

    @Test fun `foreign REST and conflicting advertisements never authorize an external GET`() = runBlocking {
        val denied = ScheduleTransport { error("Foreign REST must not be requested") }
        for (doc in listOf(page("https://foreign.example/wp-json/"), Jsoup.parse(
            "<link rel='https://api.w.org/' href='/wp-json/'><link rel='https://api.w.org/' href='https://foreign.example/wp-json/'>", site))) {
            val result = ScheduleWordPressDiscovery(denied).discover(site, doc, today)
            assertEquals(ScheduleWordPressDiscoveryStatus.UNAVAILABLE, result.status)
            assertTrue(result.links.isEmpty())
            assertTrue(result.queries.isEmpty())
        }
    }

    @Test fun `the supplied parent itself must still belong to the approved site`() = runBlocking {
        val result = ScheduleWordPressDiscovery(ScheduleTransport { error("Foreign parent") })
            .discover(site, page(base = "https://foreign.example/"), today)
        assertEquals(ScheduleWordPressDiscoveryStatus.UNAVAILABLE, result.status)
        assertTrue(result.queries.isEmpty())
    }

    @Test fun `foreign permalinks API links administrative pages and media URLs never become document links`() = runBlocking {
        val bodies = array(entry(1, link = "https://foreign.example/schedule/"),
            entry(2, link = site + "wp-json/wp/v2/pages/2"), entry(3, link = site + "wp-admin/edit.php"),
            entry(4, link = site + "files/schedule.pdf"), entry(5, link = site + "?rest_route=/wp/v2/pages/5"))
        val calls = mutableListOf<String>()
        val result = ScheduleWordPressDiscovery(transport(bodies, calls = calls)).discover(site, page(), today)
        assertTrue(result.links.isEmpty())
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.startsWith(site + "wp-json/wp/v2/") })
    }

    @Test fun `only positive public page or post records with unambiguous strict JSON shape are accepted`() = runBlocking {
        val badId = entry(0)
        val stringId = entry(1).apply { addProperty("id", "1") }
        val draft = entry(1).apply { addProperty("status", "draft") }
        val wrongType = entry(1).apply { addProperty("type", "attachment") }
        val protected = entry(1).apply { getAsJsonObject("excerpt").addProperty("protected", true) }
        val malformed = entry(1).apply { addProperty("title", "Расписание") }
        for (body in listOf(array(badId), array(stringId), array(draft), array(wrongType), array(protected), array(malformed),
            array(entry(1), entry(1, link = site + "different/")), "{\"code\":\"rest_forbidden\"}", "[{\"id\":1,\"id\":2}]", "[{", "[{'id':1}]")) {
            val result = ScheduleWordPressDiscovery(transport(body)).discover(site, page(), today)
            assertEquals(ScheduleWordPressDiscoveryStatus.UNAVAILABLE, result.status)
            assertTrue(result.links.isEmpty())
        }
    }

    @Test fun `a response larger than the per-response budget is a fallback error not an empty schedule`() = runBlocking {
        val result = ScheduleWordPressDiscovery(transport(" ".repeat(ScheduleWordPressDiscovery.MAX_RESPONSE_BYTES + 1)))
            .discover(site, page(), today)
        assertEquals(ScheduleWordPressDiscoveryStatus.UNAVAILABLE, result.status)
        assertEquals(ScheduleWordPressQueryStatus.FAILED, result.queries.first().status)
        assertTrue(result.links.isEmpty())
    }

    @Test fun `combined byte budget keeps a valid first permalink and never performs a third REST request`() = runBlocking {
        val first = array(entry(1).apply { addProperty("serverIgnoredFields", "x".repeat(220_000)) })
        val second = array(entry(2).apply { addProperty("type", "post"); addProperty("serverIgnoredFields", "x".repeat(220_000)) })
        val calls = mutableListOf<String>()
        val result = ScheduleWordPressDiscovery(transport(first, second, calls)).discover(site, page(), today)
        assertEquals(ScheduleWordPressDiscoveryStatus.UNAVAILABLE, result.status)
        assertEquals(listOf(site + "schedule/"), result.links.map { it.url })
        assertEquals(listOf(ScheduleWordPressQueryStatus.READ, ScheduleWordPressQueryStatus.FAILED), result.queries.map { it.status })
        assertEquals(2, calls.size)
    }

    @Test fun `a failed bounded read still reserves its consumed allowance before the second request`() = runBlocking {
        val limits = mutableListOf<Int>()
        val source = object : ScheduleTransport {
            override suspend fun fetch(url: String): ScheduleResponse = error("Discovery must use bounded requests")
            override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse {
                limits += maximumBytes
                if (limits.size == 1) throw IOException("Stream failed after its allowed bytes")
                return response(url, "[]")
            }
        }
        val result = ScheduleWordPressDiscovery(source).discover(site, page(), today)
        assertEquals(listOf(262_144, 131_072), limits)
        assertEquals(ScheduleWordPressDiscovery.MAX_TOTAL_BYTES, limits.sum())
        assertEquals(ScheduleWordPressDiscoveryStatus.UNAVAILABLE, result.status)
        assertTrue(result.links.isEmpty())
    }

    @Test fun `whole-operation timeout preserves first safe permalink but does not claim search completeness`() = runBlocking {
        val result = ScheduleWordPressDiscovery(ScheduleTransport { url ->
            if (url.contains("/posts?")) delay(2_000)
            response(url, if (url.contains("/pages?")) array(entry(1)) else "[]")
        }, timeoutMillis = 300).discover(site, page(), today)
        assertEquals(ScheduleWordPressDiscoveryStatus.UNAVAILABLE, result.status)
        assertEquals(listOf(site + "schedule/"), result.links.map { it.url })
        assertEquals(ScheduleWordPressQueryStatus.TIMED_OUT, result.queries.last().status)
        assertFalse(result.completenessKnown)
    }

    @Test fun `outer cancellation is propagated instead of returning a successful empty search`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val discovery = ScheduleWordPressDiscovery(ScheduleTransport { started.complete(Unit); delay(5_000); error("Cancelled") })
        var returned = false
        val job = launch { discovery.discover(site, page(), today); returned = true }
        started.await(); job.cancelAndJoin()
        assertFalse(returned)
    }

    @Test fun `one refresh instance spends its REST budget once even on repeated HTML pages`() = runBlocking {
        val calls = mutableListOf<String>()
        val discovery = ScheduleWordPressDiscovery(transport(array(entry(1)), calls = calls))
        assertEquals(1, discovery.discover(site, page(), today).links.size)
        val again = discovery.discover(site, page(), today)
        assertEquals(ScheduleWordPressDiscoveryStatus.ALREADY_ATTEMPTED, again.status)
        assertTrue(again.links.isEmpty())
        assertEquals(2, calls.size)
    }

    @Test fun `modified date never outranks actual printed schedule period and calendar or clergy widgets are excluded`() = runBlocking {
        val stale = entry(1, "Расписание богослужений на октябрь 2025", site + "old/").apply { addProperty("modified", "2026-11-13T10:00:00") }
        val actual = entry(2, "Расписание богослужений на ноябрь 2026", site + "november/").apply { addProperty("modified", "2025-01-01T00:00:00") }
        val calendar = entry(3, "Православный календарь", site + "calendar/").apply {
            getAsJsonObject("excerpt").addProperty("rendered", "<h2>Расписание богослужений на ноябрь 2026</h2>")
        }
        val clergy = entry(4, "Духовенство", site + "clergy/").apply {
            getAsJsonObject("excerpt").addProperty("rendered", "<h2>Расписание богослужений</h2>")
        }
        val bishop = entry(5, "Расписание архиерейских богослужений", site + "bishop/")
        val result = ScheduleWordPressDiscovery(transport(array(stale, actual, calendar, clergy, bishop))).discover(site, page(), today)
        assertEquals(listOf(site + "november/", site + "old/"), result.links.map { it.url })
        assertFalse(result.completenessKnown)
    }

    @Test fun `plain permalink routing uses only an explicitly advertised rest_route root`() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ScheduleWordPressDiscovery(transport(array(entry(1, link = "//parish.example/schedule/")), calls = calls))
            .discover(site, page(site + "?rest_route=/"), today)
        assertEquals(listOf(site + "schedule/"), result.links.map { it.url })
        assertTrue(calls.all { it.startsWith(site + "?") && it.contains("rest_route=%2Fwp%2Fv2%2F") })
        assertTrue(calls.all { !URLDecoder.decode(it, "UTF-8").contains("content") })
    }

    @Test fun `API errors non JSON and foreign or different endpoint redirects remain fallback failures`() = runBlocking {
        val error = ScheduleTransport { throw IOException("HTTP 503") }
        val html = ScheduleTransport { url -> response(url, "<html>Login</html>", "text/html") }
        val foreign = ScheduleTransport { _ -> response("https://foreign.example/wp-json/wp/v2/pages", array(entry(1))) }
        val otherEndpoint = ScheduleTransport { _ -> response(site + "wp-json/wp/v2/users", array(entry(1))) }
        for (transport in listOf(error, html, foreign, otherEndpoint)) {
            val result = ScheduleWordPressDiscovery(transport).discover(site, page(), today)
            assertEquals(ScheduleWordPressDiscoveryStatus.UNAVAILABLE, result.status)
            assertTrue(result.links.isEmpty())
        }
    }

    @Test fun `discovery never substitutes clean rendered JSON for cancellation and venue context of the full permalink`() = runBlocking {
        val canonical = site + "schedule/"
        val row = entry(1, link = canonical).apply {
            add("content", JsonObject().apply { addProperty("rendered", "<p>13 ноября 2026 09:00 Литургия</p>") })
        }
        val result = ScheduleWordPressDiscovery(transport(array(row))).discover(site, page(), today)
        val link = result.links.single()
        assertEquals(canonical, link.url)
        assertEquals(site, link.linkedFrom)
        assertFalse(link.document)
        val parish = Parish("test", "Храм святителя Николая", "Николая", "Москва", "Адрес", "", site,
            "Europe/Moscow", "", "", "", "", emptyList(), "", "")
        val cancelledHtml = """<html><body><h1>Храм святителя Николая</h1><p>13 ноября 2026 службы отменены</p>
            <article data-day="2026-11-13"><li><time>09:00</time><span>Литургия</span></li></article></body></html>"""
        val cancelled = ScheduleHtmlParser.inspect(Jsoup.parse(cancelledHtml, link.url), today, parish)
        assertTrue(cancelled.events.isEmpty())
        assertTrue(cancelled.withdrawsAll || "2026-11-13" in cancelled.withdrawnDates || "2026-11-13T09:00" in cancelled.withdrawnStarts)
        val foreignVenue = cancelledHtml.replace("Храм святителя Николая", "Храм Петра и Павла").replace("<p>13 ноября 2026 службы отменены</p>", "")
        val wrong = ScheduleHtmlParser.inspect(Jsoup.parse(foreignVenue, link.url), today, parish)
        assertTrue(wrong.events.isEmpty())
    }

    @Test fun `canonical link handoff through the real importer retains cancellation already read in parent HTML`() = runBlocking {
        val canonical = site + "schedule/"
        val parentHtml = """<html><head><link rel="https://api.w.org/" href="/wp-json/"></head><body>
            <h1>Храм святителя Николая</h1><p>13 ноября 2026 все богослужения отменены</p></body></html>"""
        val childHtml = """<html><body><h1>Храм святителя Николая</h1><article data-day="2026-11-13">
            <li><time>09:00</time><span>Литургия</span></li></article></body></html>"""
        val api = ScheduleWordPressDiscovery(transport(array(entry(1, link = canonical))))
        val fetched = mutableListOf<String>()
        val cache = object : ScheduleCacheStore {
            var value: String? = null
            override fun read(parishId: String) = value
            override fun write(parishId: String, value: String) { this.value = value }
        }
        val parish = Parish("contract", "Храм святителя Николая", "Николая", "Москва", "Адрес", "", site,
            "Europe/Moscow", "", "", "", "", emptyList(), "", "")
        val reader = InternetScheduleRepository(ScheduleTransport { url ->
            fetched += url
            if (url == site) {
                val links = api.discover(site, Jsoup.parse(parentHtml, site), today).links
                // Contract harness only: hand off the discovered ordinary links while retaining every parent word.
                val anchors = links.joinToString("") { "<a href='${it.url}'>Расписание богослужений</a>" }
                response(url, parentHtml.replace("</body>", anchors + "</body>"), "text/html")
            } else {
                check(url == canonical)
                response(url, childHtml, "text/html")
            }
        }, cache)
        val result = reader.refresh(parish, Instant.parse("2026-11-13T06:00:00Z"))
        assertTrue("The stale child must actually be read for this regression", canonical in fetched)
        assertNull(result.publication)
        assertTrue(result.sourceHasWithdrawals)
        assertNull(reader.cached(parish).publication)
    }


    @Test fun `REST snippets cannot authorize foreign media and only three ordinary permalinks leave discovery`() = runBlocking {
        val rows = (1..4).map { id -> entry(id, link = site + "schedule$id/").apply {
            getAsJsonObject("excerpt").addProperty("rendered", "<h2>Расписание богослужений</h2><a href='https://cdn.example/stale.pdf'>PDF</a><img src='https://cdn.example/foreign.jpg'>")
            add("content", JsonObject().apply { addProperty("rendered", "<p>13 ноября 2026 09:00 Литургия</p>") })
        } }
        val calls = mutableListOf<String>()
        val result = ScheduleWordPressDiscovery(transport(array(*rows.toTypedArray()), calls = calls)).discover(site, page(), today)
        assertEquals(listOf(site + "schedule1/", site + "schedule2/", site + "schedule3/"), result.links.map { it.url })
        assertTrue(result.links.all { !it.document && it.linkedFrom == site })
        assertEquals(2, calls.size)
        assertTrue(calls.none { it.contains("cdn.example") })
    }

}
