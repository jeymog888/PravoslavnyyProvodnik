package ru.liturgyguide.service.discovery

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DiscoveryTransportTest {
    private class Connection(
        var data: ByteArray = "{}".toByteArray(),
        var status: Int = 200,
        var advertisedLength: Long = -1,
        var source: InputStream? = null,
        val entered: CountDownLatch? = null
    ) : HttpURLConnection(URL("https://example.com/test")) {
        val output = ByteArrayOutputStream()
        val disconnected = AtomicBoolean(false)
        override fun connect() = Unit
        override fun disconnect() { disconnected.set(true) }
        override fun usingProxy() = false
        override fun getResponseCode(): Int { entered?.countDown(); return status }
        override fun getContentLengthLong() = advertisedLength
        override fun getInputStream(): InputStream = source ?: ByteArrayInputStream(data)
        override fun getOutputStream() = output
    }

    private suspend fun assertFailure(expected: DiscoveryFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $expected") }
        catch (error: DiscoveryException) { assertEquals(expected, error.reason) }
    }

    @Test fun `POST identifies the app and does not follow redirects`() = runBlocking {
        val connection = Connection()
        val transport = UrlConnectionDiscoveryTransport(connectionFactory = { connection })
        assertEquals("{}", transport.fetch(DiscoveryRequest("https://example.com/test", "data=test%20query")))
        assertEquals("POST", connection.requestMethod)
        assertEquals("data=test%20query", connection.output.toString("UTF-8"))
        assertEquals(UrlConnectionDiscoveryTransport.USER_AGENT, connection.getRequestProperty("User-Agent"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.instanceFollowRedirects)
    }

    @Test fun `known and streaming response lengths are both limited`() = runBlocking {
        val advertised = Connection(advertisedLength = 101)
        assertFailure(DiscoveryFailure.TOO_LARGE) {
            UrlConnectionDiscoveryTransport(responseLimitBytes = 100, connectionFactory = { advertised })
                .fetch(DiscoveryRequest("https://example.com/test"))
        }
        val streamed = Connection(data = ByteArray(101))
        assertFailure(DiscoveryFailure.TOO_LARGE) {
            UrlConnectionDiscoveryTransport(responseLimitBytes = 100, connectionFactory = { streamed })
                .fetch(DiscoveryRequest("https://example.com/test"))
        }
        val exact = Connection(data = ByteArray(100) { 'a'.code.toByte() })
        assertEquals(100, UrlConnectionDiscoveryTransport(responseLimitBytes = 100, connectionFactory = { exact })
            .fetch(DiscoveryRequest("https://example.com/test")).length)
    }

    @Test fun `total deadline returns before a stalled read completes`() = runBlocking {
        val entered = CountDownLatch(1)
        val connection = Connection(source = object : InputStream() {
            override fun read(): Int { Thread.sleep(5_000); return -1 }
        }, entered = entered)
        val transport = UrlConnectionDiscoveryTransport(timeoutMillis = 80, connectionFactory = { connection })
        withTimeout(1_000) {
            assertFailure(DiscoveryFailure.TIMEOUT) { transport.fetch(DiscoveryRequest("https://example.com/test")) }
        }
        assertEquals(0, entered.count)
        withTimeout(1_000) { while (!connection.disconnected.get()) delay(5) }
    }

    @Test fun `cancellation frees ownership and a late result cannot complete the next request`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Connection(source = object : InputStream() {
            override fun read(): Int {
                // Simulates a platform call that does not finish just because its Future is interrupted.
                while (release.count > 0) { try { release.await(30, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { } }
                return -1
            }
        }, entered = entered)
        var count = 0
        val transport = UrlConnectionDiscoveryTransport(minimumRequestIntervalMillis = 0, connectionFactory = {
            if (count++ == 0) first else Connection("second".toByteArray())
        })
        val initial = async { transport.fetch(DiscoveryRequest("https://example.com/test")) }
        withTimeout(1_000) { while (entered.count > 0) delay(5) }
        initial.cancelAndJoin()
        withTimeout(1_000) { while (!first.disconnected.get()) delay(5) }
        assertEquals("second", withTimeout(1_000) { transport.fetch(DiscoveryRequest("https://example.com/test")) })
        release.countDown()
        assertTrue(initial.isCancelled)
    }

    @Test fun `a simultaneous request is rejected without queueing a second network operation`() = runBlocking {
        val entered = CountDownLatch(1)
        val connection = Connection(source = object : InputStream() {
            override fun read(): Int { Thread.sleep(5_000); return -1 }
        }, entered = entered)
        val transport = UrlConnectionDiscoveryTransport(connectionFactory = { connection })
        val initial = async { transport.fetch(DiscoveryRequest("https://example.com/test")) }
        withTimeout(1_000) { while (entered.count > 0) delay(5) }
        assertFailure(DiscoveryFailure.BUSY) { transport.fetch(DiscoveryRequest("https://example.com/test")) }
        initial.cancelAndJoin()
    }

    @Test fun `request interval prevents a rapid loop but does not retry automatically`() = runBlocking {
        val nanos = AtomicLong(10_000_000_000)
        var calls = 0
        val transport = UrlConnectionDiscoveryTransport(monotonicNanos = nanos::get, connectionFactory = { calls++; Connection() })
        transport.fetch(DiscoveryRequest("https://example.com/test"))
        assertFailure(DiscoveryFailure.RATE_LIMITED) { transport.fetch(DiscoveryRequest("https://example.com/test")) }
        assertEquals(1, calls)
        nanos.addAndGet(1_000_000_000)
        transport.fetch(DiscoveryRequest("https://example.com/test"))
        assertEquals(2, calls)
    }

    @Test fun `another provider can be queried immediately without bypassing the first provider rate limit`() = runBlocking {
        var calls = 0
        val transport = UrlConnectionDiscoveryTransport(monotonicNanos = { 10_000_000_000L }, connectionFactory = { calls++; Connection() })
        transport.fetch(DiscoveryRequest("https://first.example/test"))
        transport.fetch(DiscoveryRequest("https://second.example/test"))
        assertFailure(DiscoveryFailure.RATE_LIMITED) { transport.fetch(DiscoveryRequest("https://first.example/other")) }
        assertEquals(2, calls)
    }

    @Test fun `per request deadline can shorten but never increase the transport deadline`() = runBlocking {
        for ((transportTimeout, requestTimeout) in listOf(80L to 25_000L, 25_000L to 80L)) {
            val connection = Connection(source = object : InputStream() {
                override fun read(): Int { Thread.sleep(5_000); return -1 }
            })
            val transport = UrlConnectionDiscoveryTransport(timeoutMillis = transportTimeout, connectionFactory = { connection })
            withTimeout(1_000) {
                assertFailure(DiscoveryFailure.TIMEOUT) { transport.fetch(DiscoveryRequest("https://example.com/test", timeoutMillis = requestTimeout)) }
            }
        }
    }

    @Test fun `unsafe request URL is rejected before the connection factory`() = runBlocking {
        var called = false
        val transport = UrlConnectionDiscoveryTransport(connectionFactory = { called = true; Connection() })
        assertFailure(DiscoveryFailure.INVALID_QUERY) { transport.fetch(DiscoveryRequest("http://example.com/test")) }
        assertFalse(called)
    }

    @Test fun `HTTP error and redirect statuses do not become valid JSON responses`() = runBlocking {
        for ((status, expected) in listOf(429 to DiscoveryFailure.RATE_LIMITED, 302 to DiscoveryFailure.UNAVAILABLE,
            403 to DiscoveryFailure.UNAVAILABLE, 500 to DiscoveryFailure.SERVER_UNAVAILABLE,
            503 to DiscoveryFailure.SERVER_UNAVAILABLE, 504 to DiscoveryFailure.SERVER_UNAVAILABLE)) {
            var attempts = 0
            val transport = UrlConnectionDiscoveryTransport(connectionFactory = { attempts++; Connection(status = status) })
            assertFailure(expected) { transport.fetch(DiscoveryRequest("https://example.com/test")) }
            assertEquals("An HTTP failure must not start a hidden retry: $status", 1, attempts)
        }
    }

    @Test fun `connection failure is not reported as a responding server failure`() = runBlocking {
        val transport = UrlConnectionDiscoveryTransport(connectionFactory = { throw java.io.IOException("Connection refused") })
        assertFailure(DiscoveryFailure.UNAVAILABLE) { transport.fetch(DiscoveryRequest("https://example.com/test")) }
    }
}
