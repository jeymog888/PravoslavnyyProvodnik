package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.zip.GZIPOutputStream

class OfficialDirectoryTransportTest {
    private class Connection(val body: ByteArray, val declaredSize: Long = -1, val encoding: String? = null) :
        HttpURLConnection(URL("https://example.org/")) {
        var opened = false
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getContentType() = "text/html"
        override fun getContentLengthLong() = declaredSize
        override fun getHeaderField(name: String?): String? = if (name == "Content-Encoding") encoding else null
        override fun getInputStream() = ByteArrayInputStream(body).also { opened = true }
    }
    private val dns: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }

    @Test fun `per request byte ceiling bounds declared streamed and expanded bodies without lowering ordinary document limit`() = runBlocking<Unit> {
        val plain = "x".repeat(2000).toByteArray()
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(plain) } }.toByteArray()
        for (connection in listOf(Connection(plain, 2000), Connection(plain), Connection(gzip, encoding = "gzip"))) {
            val transport = UrlConnectionScheduleTransport(limitBytes = 4000, connectionFactory = { connection }, resolve = dns)
            try { transport.fetchBounded("https://example.org/", 1000); fail("The directory ceiling must apply before parsing") }
            catch (_: IOException) { }
            if (connection.declaredSize == 2000L) assertFalse(connection.opened)
            assertArrayEquals(plain, transport.fetch("https://example.org/").bytes)
        }
        val globallySmall = UrlConnectionScheduleTransport(limitBytes = 1000, connectionFactory = { Connection(plain) }, resolve = dns)
        try { globallySmall.fetchBounded("https://example.org/", 4000); fail("A per-request budget cannot raise the global limit") }
        catch (_: IOException) { }
    }
}
