package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

class MoscowDirectorySearchTransportTest {
    private val endpoint = UrlConnectionMoscowDirectorySearchTransport.ENDPOINT
    private val publicDns: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/moscow-directory/$name")).use { it.readBytes() }
    private class Connection(val body: ByteArray, private val status: Int = 200,
        private val cookies: List<String> = emptyList(), private val input: InputStream? = null,
        private val encoding: String? = null, private val length: Long = -1) : HttpURLConnection(
            URL(UrlConnectionMoscowDirectorySearchTransport.ENDPOINT)) {
        val disconnected = AtomicBoolean()
        val output = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() { disconnected.set(true) }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = "text/html; charset=UTF-8"
        override fun getContentLengthLong() = length
        override fun getHeaderFields(): Map<String, List<String>> = if (cookies.isEmpty()) emptyMap() else mapOf("Set-Cookie" to cookies)
        override fun getHeaderField(name: String?): String? = if (name.equals("Content-Encoding", true)) encoding else null
        override fun getInputStream(): InputStream = input ?: ByteArrayInputStream(body)
        override fun getOutputStream() = output
    }
    private fun bootstrap(body: ByteArray = fixture("bootstrap-public.html"), status: Int = 200,
        cookies: List<String> = listOf("directory_session=public-test-session; Path=/; Secure; HttpOnly")) = Connection(body, status, cookies)
    private suspend fun unavailable(block: suspend () -> Unit) {
        try { block(); fail("Expected a bounded directory failure") } catch (_: IOException) { }
    }

    @Test fun onePublicSessionSendsEncodedFormsWithItsOwnCookiesAndNeverChangesTheGlobalHandler() = runBlocking {
        val initial = CookieHandler.getDefault()
        assertNull("The application does not install a process-wide cookie handler", initial)
        val connections = mutableListOf<Connection>()
        val urls = mutableListOf<String>()
        val transport = UrlConnectionMoscowDirectorySearchTransport(resolve = publicDns, connectionFactory = { url ->
            urls += url.toString()
            val connection = when (connections.size) {
                0 -> bootstrap()
                1 -> Connection(fixture("search-maryina.html"))
                else -> Connection(fixture("search-listy.html"))
            }
            connections += connection; connection
        })
        val queries = listOf("Марьиной Роще", "Листах")
        val results = transport.search(queries)
        assertEquals(2, results.size)
        assertEquals(listOf(endpoint, endpoint, endpoint), urls)
        assertEquals(listOf("GET", "POST", "POST"), connections.map { it.requestMethod })
        assertTrue(connections.first().getRequestProperty("Cookie").isNullOrBlank())
        connections.drop(1).forEachIndexed { index, connection ->
            val form = URLDecoder.decode(connection.output.toString("UTF-8"), "UTF-8")
            assertEquals("query=${queries[index]}&area=all&parent_id=", form)
            assertTrue(connection.getRequestProperty("Cookie").contains("directory_session=public-test-session"))
            assertEquals("fixture-public-csrf-token-0000000000", connection.getRequestProperty("X-CSRF-TOKEN"))
            assertEquals(endpoint, connection.getRequestProperty("Referer"))
            assertEquals("XMLHttpRequest", connection.getRequestProperty("X-Requested-With"))
            assertFalse(connection.instanceFollowRedirects)
        }
        assertEquals("«Нечаянная Радость» иконы Божией Матери в Марьиной Роще",
            MoscowDirectoryParser.search(results.first())!!.single().title)
        assertTrue(connections.all { it.disconnected.get() })
        assertSame(initial, CookieHandler.getDefault())
    }

    @Test fun anotherOperationStartsWithoutThePreviousOperationsCookie() = runBlocking {
        val calls = mutableListOf<Connection>()
        val transport = UrlConnectionMoscowDirectorySearchTransport(resolve = publicDns, connectionFactory = {
            (if (calls.size % 2 == 0) bootstrap() else Connection(fixture("search-empty.html"))).also(calls::add)
        })
        repeat(2) { transport.search(listOf("Листах")) }
        assertTrue(calls[2].getRequestProperty("Cookie").isNullOrBlank())
        assertEquals(listOf("GET", "POST", "GET", "POST"), calls.map { it.requestMethod })
    }

    @Test fun malformedBootstrapMissingCookiesRedirectsAnd419NeverStartAnAccountOrRetryLoop() = runBlocking {
        val variants = listOf(
            bootstrap(cookies = emptyList()),
            bootstrap("<html><head></head></html>".toByteArray()),
            bootstrap("<meta name=csrf-token content='short'>".toByteArray()),
            bootstrap(("<meta name=csrf-token content='fixture-public-csrf-token-0000000000'>".repeat(2)).toByteArray()),
            bootstrap(status = 302),
            bootstrap(cookies = (1..9).map { "cookie$it=value; Path=/; Secure" }),
        )
        for (first in variants) {
            var count = 0
            val transport = UrlConnectionMoscowDirectorySearchTransport(resolve = publicDns, connectionFactory = { count++; first })
            unavailable { transport.search(listOf("Листах")) }
            assertEquals(1, count)
        }
        for (status in listOf(302, 419, 500)) {
            var count = 0
            val transport = UrlConnectionMoscowDirectorySearchTransport(resolve = publicDns,
                connectionFactory = { if (count++ == 0) bootstrap() else Connection(ByteArray(0), status) })
            unavailable { transport.search(listOf("Листах", "Троицы")) }
            assertEquals("No third request after failed POST $status", 2, count)
        }
    }

    @Test fun privateDnsAndAnExistingGlobalCookieHandlerAreRejectedBeforeOpeningAConnection() = runBlocking {
        var calls = 0
        val blockedDns = UrlConnectionMoscowDirectorySearchTransport(resolve = {
            arrayOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }, connectionFactory = { calls++; bootstrap() })
        unavailable { blockedDns.search(listOf("Листах")) }
        assertEquals(0, calls)
        val previous = CookieHandler.getDefault()
        try {
            val handler = CookieManager(); CookieHandler.setDefault(handler)
            val blockedCookies = UrlConnectionMoscowDirectorySearchTransport(resolve = publicDns, connectionFactory = { calls++; bootstrap() })
            unavailable { blockedCookies.search(listOf("Листах")) }
            assertEquals(0, calls)
            assertSame(handler, CookieHandler.getDefault())
        } finally { CookieHandler.setDefault(previous) }
    }

    @Test fun declaredWireAndExpandedBodyLimitsCannotBeBypassedWithCompression() = runBlocking {
        val big = "x".repeat(UrlConnectionMoscowDirectorySearchTransport.BOOTSTRAP_LIMIT + 1).toByteArray()
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(big) } }.toByteArray()
        for (first in listOf(Connection(ByteArray(0), length = big.size.toLong()), Connection(big), Connection(compressed, encoding = "gzip"))) {
            var count = 0
            val transport = UrlConnectionMoscowDirectorySearchTransport(resolve = publicDns, connectionFactory = { count++; first })
            unavailable { transport.search(listOf("Листах")) }
            assertEquals(1, count)
        }
        var oversizedCalls = 0
        val oversizedSearch = UrlConnectionMoscowDirectorySearchTransport(resolve = publicDns, connectionFactory = {
            if (oversizedCalls++ == 0) bootstrap() else Connection(ByteArray(UrlConnectionMoscowDirectorySearchTransport.SEARCH_LIMIT + 1))
        })
        unavailable { oversizedSearch.search(listOf("Листах")) }
        assertEquals(2, oversizedCalls)
        val packedBootstrap = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(fixture("bootstrap-public.html")) } }.toByteArray()
        var count = 0
        val valid = UrlConnectionMoscowDirectorySearchTransport(resolve = publicDns, connectionFactory = {
            if (count++ == 0) bootstrap(packedBootstrap) else Connection(fixture("search-empty.html"))
        })
        assertEquals(emptyList<MoscowDirectoryCandidate>(), MoscowDirectoryParser.search(valid.search(listOf("Листах")).single()))
    }

    @Test fun deadlineAndCallerCancellationDisconnectStalledReadsWithoutLatePosts() = runBlocking {
        for (callerCancellation in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val connection = Connection(ByteArray(0), input = object : InputStream() {
                override fun read(): Int { entered.complete(Unit); Thread.sleep(5_000); return -1 }
            })
            val calls = CopyOnWriteArrayList<URL>()
            val transport = UrlConnectionMoscowDirectorySearchTransport(timeoutMillis = if (callerCancellation) 5_000 else 100,
                resolve = publicDns, connectionFactory = { calls += it; connection })
            supervisorScope {
                val job = async { transport.search(listOf("Листах")) }
                withTimeout(1_000) { entered.await() }
                if (callerCancellation) { job.cancelAndJoin() } else unavailable { job.await() }
            }
            withTimeout(1_000) { while (!connection.disconnected.get()) delay(5) }
            assertEquals(1, calls.size)
        }
    }
}
