package ru.liturgyguide.service

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.service.discovery.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Explicit diagnostic of public endpoints, using a declared Kazan map point, never device location. */
class LiveDiscoveryNetworkTest {
    @Test fun publicEndpointsReportTheirRealAndroidConnectionOutcome() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeDiscoveryNetwork") == "true")
        val endpoints = DiscoveryEndpoints()
        val area = GeoPoint(55.80, 49.11)
        val requests = listOf(
            "overpass" to DiscoveryRequest(endpoints.churches,
                "data=" + URLEncoder.encode(DefaultPlaceDiscovery.churchQuery(area, 3_800), "UTF-8")),
            "photon" to DiscoveryRequest(DefaultPlaceDiscovery.nearbyPlacesUrl(endpoints.cities, area, 3_800))
        )
        val report = requests.map { (provider, request) ->
            val started = System.nanoTime()
            var connection: HttpURLConnection? = null
            val entry = linkedMapOf<String, Any>("provider" to provider, "declaredPoint" to area)
            try {
                val opened = (URL(request.url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 12_000; readTimeout = 12_000
                    setRequestProperty("User-Agent", UrlConnectionDiscoveryTransport.USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                connection = opened
                request.postBody?.let { body ->
                    opened.requestMethod = "POST"; opened.doOutput = true
                    opened.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    opened.setFixedLengthStreamingMode(bytes.size)
                    opened.outputStream.use { it.write(bytes) }
                }
                entry["httpStatus"] = opened.responseCode
                opened.getHeaderField("Location")?.let { entry["redirect"] = it }
                if (opened.responseCode == 200) {
                    val body = opened.inputStream.bufferedReader().use { it.readText() }
                    entry["bodyLength"] = body.length
                    entry["places"] = if (provider == "photon") DiscoveryJson.places(body).size
                        else DiscoveryJson.churches(body, area, 3_800).size
                }
            } catch (failure: Exception) {
                entry["failure"] = generateSequence<Throwable>(failure) { it.cause }.take(4)
                    .map { it.javaClass.simpleName + ": " + it.message.orEmpty().take(300) }.toList()
            } finally {
                runCatching { connection?.disconnect() }
                entry["elapsedMillis"] = (System.nanoTime() - started) / 1_000_000
            }
            entry
        }
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            .resolve("live-discovery-network.json").writeText(Gson().toJson(report))
        assertTrue("Public endpoint errors: $report", report.all { it["httpStatus"] == 200 && (it["places"] as? Int ?: 0) > 0 })
    }
}
