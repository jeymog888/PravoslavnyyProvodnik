package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class ScheduleTransportTest {
    private class Connection(val bytes: ByteArray = "ok".toByteArray(), val status: Int = 200,
        val location: String? = null, val input: InputStream? = null) : HttpURLConnection(URL("https://example.org/")) {
        val disconnected = AtomicBoolean()
        override fun connect() = Unit
        override fun disconnect() { disconnected.set(true) }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = "text/html; charset=utf-8"
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
        override fun getInputStream(): InputStream = input ?: ByteArrayInputStream(bytes)
    }
    private val publicDns: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }

    @Test fun `HTTPS redirects permit www but do not leave the selected source site`() = runBlocking {
        var calls = 0
        val transport = UrlConnectionScheduleTransport(resolve = publicDns, connectionFactory = {
            calls++
            if (calls == 1) Connection(status = 301, location = "https://www.example.org/schedule/") else Connection()
        })
        assertEquals("https://www.example.org/schedule/", transport.fetch("https://example.org/").url)
        assertEquals(2, calls)
        calls = 0
        val external = UrlConnectionScheduleTransport(resolve = publicDns, connectionFactory = {
            calls++; Connection(status = 302, location = "https://another.org/")
        })
        try { external.fetch("https://example.org/"); fail() } catch (_: IOException) { }
        assertEquals(1, calls)
    }

    @Test fun `private addresses and oversized bodies are rejected`() = runBlocking {
        var calls = 0
        val local = UrlConnectionScheduleTransport(resolve = { arrayOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) },
            connectionFactory = { calls++; Connection() })
        try { local.fetch("https://example.org/"); fail() } catch (_: IOException) { }
        assertEquals(0, calls)
        val large = UrlConnectionScheduleTransport(limitBytes = 2, resolve = publicDns, connectionFactory = { Connection("large".toByteArray()) })
        try { large.fetch("https://example.org/"); fail() } catch (_: IOException) { }
    }

    @Test fun `deadline completes without waiting for a stalled body`() = runBlocking {
        val connection = Connection(input = object : InputStream() {
            override fun read(): Int { Thread.sleep(5_000); return -1 }
        })
        val transport = UrlConnectionScheduleTransport(timeoutMillis = 80, resolve = publicDns, connectionFactory = { connection })
        withTimeout(1_000) {
            try { transport.fetch("https://example.org/"); fail() } catch (_: IOException) { }
        }
        withTimeout(1_000) { while (!connection.disconnected.get()) delay(5) }
    }

    @Test fun `HTML charset is respected and PDF never masquerades as parsed text`() {
        val text = "<html><body>Литургия</body></html>"
        val response = ScheduleResponse("https://example.org/", text.toByteArray(charset("windows-1251")), "text/html; charset=windows-1251")
        assertEquals("Литургия", response.html()!!.body().text())
        assertNull(ScheduleResponse("https://example.org/", text.toByteArray(), "application/pdf").html())
    }
}
