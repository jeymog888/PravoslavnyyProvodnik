package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.time.Instant
import javax.net.ssl.SSLHandshakeException

class PublicHttpScheduleTest {
    private class Connection(url: URL, private val status: Int = 200, private val failure: IOException? = null,
        private val location: String? = null, private val bytes: ByteArray = "ok".toByteArray()) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int { failure?.let { throw it }; return status }
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getContentType() = "text/html"
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }
    private val publicDns: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getByAddress(byteArrayOf(8,8,8,8))) }
    private fun parish() = Parish("osm:node:42", "Никольский храм", "Никольский храм", "Москва", "", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "")

    @Test fun `public source preserves published protocol and rejects credentials local addresses ports and controls`() {
        assertEquals("http://example.org/schedule/?page=a%26b", ScheduleUrl.publicSource("http://example.org:80/schedule/?page=a%26b#today"))
        assertEquals("https://example.org/", ScheduleUrl.publicSource("https://example.org:443"))
        for (bad in listOf("http://user:password@example.org/", "http://127.0.0.1/", "http://[::1]/", "http://router.local/", "http://localhost/",
            "http://localhost./", "http://example.org:8080/", "https://example.org:80/", "http://example.org/\nHeader:x", "file:///schedule")) {
            assertNull(bad, ScheduleUrl.publicSource(bad))
        }
    }

    @Test fun `explicit HTTP prefers valid HTTPS and fallback retains original source only after failed upgrade`() = runBlocking {
        val calls = mutableListOf<String>()
        val good = UrlConnectionScheduleTransport(resolve = publicDns, allowExplicitHttp = true,
            connectionFactory = { calls += it.toString(); Connection(it) })
        assertEquals("https://example.org/schedule/", good.fetch("http://example.org/schedule/").url)
        assertEquals(listOf("https://example.org/schedule/"), calls)
        calls.clear()
        val fallback = UrlConnectionScheduleTransport(resolve = publicDns, allowExplicitHttp = true,
            connectionFactory = { calls += it.toString(); Connection(it, failure = if (it.protocol == "https") SSLHandshakeException("No TLS site") else null) })
        assertEquals("http://example.org/schedule/", fallback.fetch("http://example.org/schedule/").url)
        assertEquals(listOf("https://example.org/schedule/", "http://example.org/schedule/"), calls)
    }

    @Test fun `explicit HTTPS failure and default book transport never downgrade`() = runBlocking {
        for ((url, enabled) in listOf("https://example.org/" to true, "http://example.org/" to false)) {
            val calls = mutableListOf<String>()
            val transport = UrlConnectionScheduleTransport(resolve = publicDns, allowExplicitHttp = enabled,
                connectionFactory = { calls += it.toString(); Connection(it, failure = SSLHandshakeException("invalid certificate")) })
            try { transport.fetch(url); fail("Expected TLS failure") } catch (_: IOException) { }
            assertEquals(listOf("https://example.org/"), calls)
        }
    }

    @Test fun `downgrade redirects foreign redirects and invalid public DNS never trigger cleartext fallback`() = runBlocking {
        for (destination in listOf("http://example.org/schedule/", "https://foreign.example/schedule/")) {
            val calls = mutableListOf<String>()
            val transport = UrlConnectionScheduleTransport(resolve = publicDns, allowExplicitHttp = true,
                connectionFactory = { calls += it.toString(); Connection(it, status = 302, location = destination) })
            try { transport.fetch("http://example.org/"); fail("Unsafe redirect") } catch (_: IOException) { }
            assertEquals(listOf("https://example.org/"), calls)
        }
        for (ip in listOf("127.0.0.1", "10.1.2.3", "100.64.0.1", "198.18.0.1", "fc00::1", "fe80::1")) {
            assertFalse(ip, UrlConnectionScheduleTransport.publicAddress(InetAddress.getByName(ip)))
        }
        var calls = 0
        val privateHost = UrlConnectionScheduleTransport(resolve = { arrayOf(InetAddress.getByName("127.0.0.1")) }, allowExplicitHttp = true,
            connectionFactory = { calls++; Connection(it) })
        try { privateHost.fetch("http://example.org/"); fail("Private DNS") } catch (_: IOException) { }
        assertEquals(0, calls)
    }

    @Test fun `valid HTTPS body limit failure is not an invitation to use HTTP`() = runBlocking {
        val calls = mutableListOf<String>()
        val transport = UrlConnectionScheduleTransport(limitBytes = 2, resolve = publicDns, allowExplicitHttp = true,
            connectionFactory = { calls += it.toString(); Connection(it, bytes = "large".toByteArray()) })
        try { transport.fetch("http://example.org/"); fail("Oversized") } catch (_: IOException) { }
        assertEquals(listOf("https://example.org/"), calls)
    }

    @Test fun `public requests do not add session headers and registered cookie handler blocks HTTP`() = runBlocking {
        val opened = mutableListOf<Connection>()
        val transport = UrlConnectionScheduleTransport(resolve = publicDns, allowExplicitHttp = true, connectionFactory = {
            Connection(it, failure = if (it.protocol == "https") SSLHandshakeException("No TLS") else null).also(opened::add)
        })
        transport.fetch("http://example.org/")
        assertTrue(opened.all { it.requestMethod == "GET" && !it.useCaches })
        assertTrue(opened.all { connection -> connection.requestProperties.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) || it.equals("Referer", true) } })
        val previous = CookieHandler.getDefault()
        try {
            CookieHandler.setDefault(CookieManager())
            opened.clear()
            try { transport.fetch("http://example.org/"); fail("Cookie handler must block cleartext") } catch (_: IOException) { }
            assertEquals(listOf("https"), opened.map { it.url.protocol })
        } finally { CookieHandler.setDefault(previous) }
    }

    @Test fun `exact OSM and Wikidata official HTTP fields preserve protocol without domain guessing`() = runBlocking {
        for (osmDirect in listOf(true, false)) {
            val resolver = OpenScheduleSiteResolver(ScheduleTransport { url ->
                val body = if (url.contains("openstreetmap")) {
                    if (osmDirect) """{"elements":[{"type":"node","id":42,"tags":{"website":"http://example.org/"}}]}"""
                    else """{"elements":[{"type":"node","id":42,"tags":{"wikidata":"Q42"}}]}"""
                } else """{"entities":{"Q42":{"claims":{"P856":[{"rank":"normal","mainsnak":{"datavalue":{"value":"http://example.org/"}}}]}}}}"""
                ScheduleResponse(url, body.toByteArray(), "application/json")
            })
            assertEquals("http://example.org/", resolver.resolve(parish())?.url)
        }
    }

    @Test fun `same-site links and cached publication retain actual HTTP and evidence after offline reopen`() = runBlocking {
        val root = "http://example.org/"
        val schedule = "http://example.org/raspisanie/"
        val links = ScheduleDocumentLinks.find(Jsoup.parse("<a href='/raspisanie/'>Расписание</a><a href='http://foreign.example/raspisanie/'>Расписание</a>", root))
        assertEquals(listOf(schedule), links.map { it.url })
        var saved: String? = null
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = saved
            override fun write(parishId: String, value: String) { saved = value }
        }
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            ScheduleResponse(url, (if (url == root) "<a href='/raspisanie/'>Расписание</a>" else
                "<article data-day='2026-11-11'><li><time>08:00</time><span>Литургия</span></li></article>").toByteArray(), "text/html")
        }, cache, siteResolver = ScheduleSiteResolver { selected ->
            val evidence = "https://directory.example/card/42"
            ScheduleSite(root, evidence, "Никольский храм",
                ScheduleEntityIdentity.bind(selected, root, evidence, "Никольский храм", ScheduleBindingMethod.ARTICLE_SCOPE))
        })
        val p = parish(); val now = Instant.parse("2026-11-11T06:00:00Z")
        val state = repo.refresh(p, now)
        assertEquals(InternetScheduleStatus.READY, state.status)
        assertEquals(schedule, state.publication?.sourceUrl)
        assertEquals("https://directory.example/card/42", state.siteEvidenceUrl)
        assertTrue(p.withInternetSchedule(state, now).scheduleNote.contains("HTTP"))
        val offline = InternetScheduleRepository(ScheduleTransport { throw IOException() }, cache)
        assertEquals(state.publication, offline.cached(p).publication)
        assertEquals(state.publication, offline.refresh(p, now.plusSeconds(60), force = true).publication)
        val custom = repo.refresh(p, now.plusSeconds(120), force = true, sourceOverride = schedule)
        assertEquals(schedule, custom.customSource)
    }
}
