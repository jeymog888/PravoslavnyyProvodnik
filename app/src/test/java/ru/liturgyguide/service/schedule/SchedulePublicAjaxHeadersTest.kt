package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class SchedulePublicAjaxHeadersTest {
    private class Connection(url: URL, val redirect: String? = null) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = if (redirect == null) 200 else 302
        override fun getHeaderField(name: String?) = if (name == "Location") redirect else null
        override fun getContentType() = "application/json"
        override fun getInputStream() = ByteArrayInputStream("{\"points\":[]}".toByteArray())
    }
    private val publicDns: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }

    @Test fun `fixed public AJAX headers appear only on the exact HTTPS map endpoint`() = runBlocking {
        for (url in listOf("https://elitsy.ru/map/ajaxpoints/?lb=55&lb=37&rt=55.1&rt=37.1&z=15",
            "https://elitsy.ru/parish/17504/", "https://elitsy.ru/map/", "https://other.elitsy.ru/map/ajaxpoints/",
            "https://www.elitsy.ru/map/ajaxpoints/", "https://elitsy.ru/map/%61jaxpoints/", "https://elitsy.ru/map/ajaxpoints/other/")) {
            val connections = mutableListOf<Connection>()
            UrlConnectionScheduleTransport(resolve = publicDns, connectionFactory = { Connection(it).also(connections::add) }).fetch(url)
            val request = connections.single()
            val ajax = url.startsWith("https://elitsy.ru/map/ajaxpoints/?")
            assertEquals(url, if (ajax) "XMLHttpRequest" else null, request.getRequestProperty("X-Requested-With"))
            assertEquals(if (ajax) "https://elitsy.ru/map/" else null, request.getRequestProperty("Referer"))
            assertEquals("identity", request.getRequestProperty("Accept-Encoding"))
            assertNull(request.getRequestProperty("Authorization"))
            assertNull(request.getRequestProperty("Cookie"))
            assertEquals(if (ajax) "application/json, text/javascript, */*; q=0.01"
                else "text/html,application/xhtml+xml,application/json,application/pdf,image/*;q=0.9", request.getRequestProperty("Accept"))
        }
    }

    @Test fun `redirect recomputes exact target policy instead of inheriting AJAX headers`() = runBlocking {
        for ((start, end) in listOf("https://elitsy.ru/map/ajaxpoints/?pk=17504" to "https://elitsy.ru/parish/17504/",
            "https://elitsy.ru/map/" to "https://elitsy.ru/map/ajaxpoints/?pk=17504")) {
            val connections = mutableListOf<Connection>()
            val transport = UrlConnectionScheduleTransport(resolve = publicDns, connectionFactory = {
                Connection(it, if (connections.isEmpty()) end else null).also(connections::add)
            })
            assertEquals(end, transport.fetch(start).url)
            assertEquals(2, connections.size)
            assertEquals(if (start.contains("ajaxpoints")) "XMLHttpRequest" else null, connections[0].getRequestProperty("X-Requested-With"))
            assertEquals(if (end.contains("ajaxpoints")) "XMLHttpRequest" else null, connections[1].getRequestProperty("X-Requested-With"))
        }
    }
}
