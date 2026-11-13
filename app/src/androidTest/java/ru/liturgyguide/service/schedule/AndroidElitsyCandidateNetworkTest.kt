package ru.liturgyguide.service.schedule

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opt-in real Android HTTP. No UI, device location, preferences, existing user cache or schedule download. */
@RunWith(AndroidJUnit4::class)
class AndroidElitsyCandidateNetworkTest {
    private data class Scene(val name: String, val id: String, val title: String, val latitude: Double, val longitude: Double) {
        fun selected() = Parish("qa:elitsy:$id", title, title, "", "", "", "", "Europe/Moscow", "", "", "", "",
            emptyList(), "", "", latitude, longitude)
    }

    @Test fun threeDeclaredPublicPointsProduceReviewableCandidatesThroughActualAndroidAjax() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real HTTP requires feoriaLiveNetwork=true", args.getString("feoriaLiveNetwork") == "true")
        val run = requireNotNull(args.getString("elitsyCandidateNetworkRun")) { "Supply a fresh UUID elitsyCandidateNetworkRun" }
        require(UUID.fromString(run).toString() == run) { "elitsyCandidateNetworkRun must be a canonical, fresh UUID" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Instrumentation runs under the target UID. Use only a new UUID scratch
        // directory, export its receipts and remove it; existing app data is never read.
        val root = File(instrumentation.targetContext.cacheDir, "android-elitsy-candidate-${UUID.randomUUID()}")
        check(!root.exists() && root.mkdirs())
        val previousCookies = CookieHandler.getDefault()
        val recorder = LiveRecorder(root)
        val report = linkedMapOf<String, Any?>("run" to run, "startedAt" to Instant.now().toString(), "status" to "RUNNING",
            "scope" to "Real Android Elitsy candidate discovery at three declared public points; not schedule coverage or identity proof",
            "fixtureReference" to ".local/release12/federation/EXPLORATION.md",
            "transport" to "Production UrlConnectionScheduleTransport, allowExplicitHttp=false, limitBytes=196608",
            "provider" to ElitsyMapCandidateProvider::class.java.name, "providerDeadline" to "production default 25000ms",
            "deviceLocationUsed" to false, "sharedPreferencesUsed" to false, "existingUserCacheUsed" to false,
            "isolatedTargetCacheScratchDirectory" to root.name,
            "confirmationPerformed" to false, "scheduleRead" to false, "pdfRead" to false,
            "caseResults" to mutableListOf<Map<String, Any?>>())
        @Suppress("UNCHECKED_CAST") val results = report.getValue("caseResults") as MutableList<Map<String, Any?>>
        val failures = mutableListOf<String>()
        try {
            CookieHandler.setDefault(null)
            val transport = recorder.transport()
            // Search centers come from the public investigation, never a profile/device location.
            val scenes = listOf(
                Scene("nahabino", "17504", "Храм иконы Божией Матери \"Всецарица\" пос. Нахабино", 55.840779, 37.173044),
                Scene("pskov", "12827", "Кафедральный собор Троицы Живоначальной г. Псков", 57.8220791323, 28.3290957803),
                Scene("novosibirsk", "12484", "Храм во имя преподобного и благоверного князя Олега Брянского г. Новосибирск", 55.019392, 82.957595))
            for (scene in scenes) {
                recorder.scene = scene.name
                val selected = scene.selected()
                val entry = linkedMapOf<String, Any?>("scene" to scene.name, "expectedObjectId" to scene.id,
                    "declaredSearchPoint" to listOf(scene.latitude, scene.longitude), "selected" to selected,
                    "selectedFingerprint" to ScheduleEntityIdentity.fingerprint(selected), "status" to "FAILED")
                try {
                    assertNull("No process-wide cookies may be used", CookieHandler.getDefault())
                    val discovery = ElitsyMapCandidateProvider(transport).discover(selected, emptySet())
                    entry["unavailable"] = discovery.unavailable
                    entry["incomplete"] = discovery.incomplete
                    entry["observedIds"] = discovery.observedIds
                    entry["candidates"] = discovery.candidates.map { candidate -> linkedMapOf(
                        "candidate" to candidate, "validForSelected" to candidate.validFor(selected),
                        "canConfirmSelected" to candidate.canConfirm(selected)) }
                    assertTrue("${scene.name}: the index remains incomplete", discovery.incomplete)
                    assertFalse("${scene.name}: live source/HTTP changed; inspect receipts", discovery.unavailable)
                    assertTrue("${scene.name}: known public object absent", scene.id in discovery.observedIds)
                    val fetches = recorder.fetches.filter { it["scene"] == scene.name }
                    val collection = fetches.single { URI(it.getValue("requestedUrl") as String).path == "/map/ajaxpoints/" }
                    assertEquals("RETURNED", collection["state"])
                    val collectionUrl = collection.getValue("responseUrl") as String
                    val query = URI(collectionUrl).rawQuery.split('&')
                    assertEquals(listOf("lb", "lb", "rt", "rt", "z"), query.map { it.substringBefore('=') })
                    assertEquals("z=15", query.last())
                    val currentPoints = JsonParser.parseString(File(root, collection.getValue("bodyPath") as String).readText())
                        .asJsonObject.getAsJsonArray("points").map { it.asJsonObject }
                    val point = currentPoints.single { it.get("pk").asString == scene.id }
                    val coordinates = point.getAsJsonArray("coords")
                    val latitude = coordinates[0].asDouble; val longitude = coordinates[1].asDouble
                    assertTrue(latitude.isFinite() && longitude.isFinite())
                    assertTrue(latitude in -90.0..90.0 && longitude in -180.0..180.0)
                    assertTrue("${scene.name}: changed or swapped object geometry", OpenScheduleSiteResolver.distanceMeters(
                        scene.latitude, scene.longitude, latitude, longitude) <= 650)
                    entry["actualPublicPoint"] = point
                    val nativeAjax = recorder.connections.filter { it.scene == scene.name && it.url == collectionUrl && it.status == 200 }
                    assertTrue("${scene.name}: no actual Android HTTP200 AJAX connection", nativeAjax.isNotEmpty())
                    for (connection in nativeAjax) {
                        assertEquals("XMLHttpRequest", connection.requestHeaders["X-Requested-With"])
                        assertEquals("https://elitsy.ru/map/", connection.requestHeaders["Referer"])
                        assertEquals("application/json, text/javascript, */*; q=0.01", connection.requestHeaders["Accept"])
                    }
                    val positives = discovery.candidates.filter { it.providerObjectId == scene.id }
                    assertTrue("${scene.name}: no reviewable candidate for known object; websites may have changed", positives.isNotEmpty())
                    for (candidate in discovery.candidates) {
                        assertEquals(ElitsyMapCandidateProvider.PROVIDER, candidate.provider)
                        assertNotEquals(ScheduleIdentityOutcome.PROVED, candidate.outcome)
                        assertTrue(candidate.validFor(selected))
                        assertEquals(ScheduleEntityIdentity.fingerprint(selected), candidate.selectedIdentity)
                        assertFalse(candidate.validFor(selected.copy(id = "qa:different-selected-object")))
                    }
                    for (candidate in positives) {
                        assertEquals(latitude, candidate.latitude, 0.000000001)
                        assertEquals(longitude, candidate.longitude, 0.000000001)
                        assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, candidate.outcome)
                        assertTrue("${scene.name}: positive candidate must remain user-reviewable", candidate.canConfirm(selected))
                        assertEquals("https://elitsy.ru/parish/${scene.id}/", candidate.evidenceUrl)
                        val geometry = requireNotNull(candidate.geometryEvidence)
                        assertEquals(collectionUrl, geometry.url)
                        assertEquals(collection["bodySha256"], geometry.sha256)
                        val detail = fetches.single { it["responseUrl"] == candidate.evidenceUrl }
                        assertEquals(detail["bodySha256"], candidate.evidenceSha256)
                    }
                    assertTrue(fetches.size in 2..3)
                    entry["status"] = "PASSED"
                } catch (failure: Throwable) {
                    entry["failureClass"] = failure.javaClass.name
                    entry["failureMessage"] = failure.message.orEmpty().take(2000)
                    failures += "${scene.name}: ${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(300)}"
                    if (failure is CancellationException || failure is VirtualMachineError || failure is ThreadDeath) throw failure
                } finally {
                    results += entry
                }
            }
            assertEquals(3, results.size)
            assertTrue("Production sent a forbidden header", recorder.connections.all { it.forbiddenHeaderNames.isEmpty() })
            assertTrue("Public Elitsy source changed or Android HTTP failed: $failures", failures.isEmpty())
            report["status"] = "PASSED"
        } catch (failure: Throwable) {
            report["status"] = "FAILED"
            report["failureClass"] = failure.javaClass.name
            report["failureMessage"] = failure.message.orEmpty().take(2000)
            throw failure
        } finally {
            try {
                recorder.awaitClosed()
                report["finishedAt"] = Instant.now().toString()
                report["fetches"] = recorder.fetches.toList()
                report["connections"] = recorder.connections.map { it.snapshot() }
                report["openNativeConnections"] = recorder.openConnections.get()
                report["pacing"] = "At least3100ms between real Elitsy connections; included in default production deadlines"
                val receipts = File(root, "receipts.json")
                check(receipts.createNewFile())
                receipts.writeText(GSON.toJson(report) + "\n")
                val archive = File(root, "android-elitsy-candidate-$run.zip")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    // Export receipts only. Full response bodies are never printed or exported.
                    zip.putNextEntry(ZipEntry(receipts.name)); receipts.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                val bytes = archive.readBytes()
                check(bytes.size <= 2_400_000)
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val chunks = encoded.chunked(32_000)
                fun emit(line: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$line\n") })
                emit("ELITSY_ARTIFACT_META " + com.google.gson.Gson().toJson(mapOf("run" to run, "name" to archive.name,
                    "bytes" to bytes.size, "sha256" to sha(bytes), "chunks" to chunks.size)))
                chunks.forEachIndexed { index, chunk -> emit("ELITSY_ARTIFACT_CHUNK $run $index/${chunks.size} $chunk") }
            } finally {
                CookieHandler.setDefault(previousCookies)
                val deleted = root.deleteRecursively()
                instrumentation.sendStatus(2, Bundle().apply { putString("stream", "ELITSY_CLEANUP run=$run testCacheDeleted=$deleted\n") })
                check(deleted) { "Isolated test cache cleanup failed" }
            }
        }
    }

    private class LiveRecorder(private val root: File) {
        @Volatile var scene = "unassigned"
        val fetches = CopyOnWriteArrayList<Map<String, Any?>>()
        val connections = CopyOnWriteArrayList<NativeReceipt>()
        val openConnections = AtomicInteger()
        private val ids = AtomicInteger()
        private var nextConnectionAt = 0L
        @Synchronized private fun pace() {
            val wait = nextConnectionAt - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            nextConnectionAt = System.nanoTime() + 3_100_000_000L
        }
        fun transport(): ScheduleTransport = object : ScheduleTransport {
            override suspend fun fetch(url: String) = fetchBounded(url, 196_608)
            override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse {
                val id = ids.incrementAndGet()
                check(id <= 9) { "Maximum nine bounded public fetches for three cases" }
                val currentScene = scene
                val entry = linkedMapOf<String, Any?>("id" to id, "scene" to currentScene, "requestedUrl" to url,
                    "maximumBytes" to maximumBytes, "startedAt" to Instant.now().toString())
                val began = System.nanoTime()
                val production = UrlConnectionScheduleTransport(limitBytes = 196_608, allowExplicitHttp = false,
                    connectionFactory = { target ->
                        val uri = target.toURI()
                        check(uri.scheme == "https" && uri.host == "elitsy.ru" && uri.port == -1 &&
                            (uri.path == "/map/ajaxpoints/" || uri.path.matches(Regex("/parish/[1-9][0-9]{0,11}/"))))
                        pace()
                        val receipt = NativeReceipt(currentScene, id, target.toString())
                        connections += receipt
                        openConnections.incrementAndGet()
                        RecordingConnection(target.openConnection() as HttpURLConnection, receipt, openConnections)
                    })
                try {
                    val response = production.fetchBounded(url, maximumBytes)
                    assertTrue(response.bytes.size <= minOf(196_608, maximumBytes))
                    val body = File(root, "fetch-%02d-body.bin".format(id))
                    check(body.createNewFile())
                    body.writeBytes(response.bytes)
                    entry.putAll(mapOf("state" to "RETURNED", "responseUrl" to response.url, "contentType" to response.contentType,
                        "bodyPath" to body.name, "bytes" to response.bytes.size, "bodySha256" to sha(response.bytes)))
                    return response
                } catch (failure: Throwable) {
                    entry["state"] = if (failure is CancellationException) "CANCELLED" else "FAILED"
                    entry["failureClass"] = failure.javaClass.name
                    throw failure
                } finally {
                    entry["latencyMillis"] = (System.nanoTime() - began) / 1_000_000
                    fetches += entry
                }
            }
        }
        fun awaitClosed() {
            val until = System.nanoTime() + 3_000_000_000L
            while (openConnections.get() > 0 && System.nanoTime() < until) Thread.sleep(10)
        }
    }

    private class NativeReceipt(val scene: String, val fetchId: Int, val url: String) {
        val requestHeaders = ConcurrentHashMap<String, String>()
        val forbiddenHeaderNames = CopyOnWriteArrayList<String>()
        val responseHeaders = ConcurrentHashMap<String, String>()
        val readBytes = AtomicInteger()
        @Volatile var status: Int? = null
        @Volatile var bodyComplete = false
        @Volatile var closed = false
        val startedAt = Instant.now().toString()
        fun snapshot() = mapOf("scene" to scene, "fetchId" to fetchId, "url" to url, "startedAt" to startedAt,
            "httpStatus" to status, "requestHeaders" to requestHeaders.toMap(), "responseHeaders" to responseHeaders.toMap(),
            "forbiddenHeaderNames" to forbiddenHeaderNames.toList(), "nativeStreamBytesRead" to readBytes.get(),
            "nativeStreamReachedEof" to bodyComplete, "closed" to closed)
    }

    /** Native connection delegates every request; instrumentation observes only, never supplies a response. */
    private class RecordingConnection(private val delegate: HttpURLConnection, private val receipt: NativeReceipt,
        private val openConnections: AtomicInteger) : HttpURLConnection(delegate.url) {
        private val disconnected = AtomicBoolean()
        override fun getResponseCode(): Int = delegate.responseCode.also { status ->
            receipt.status = status
            for (header in listOf("Content-Type", "Content-Length", "Content-Encoding", "Date", "Location"))
                delegate.getHeaderField(header)?.let { receipt.responseHeaders[header] = it }
        }
        override fun getInputStream(): InputStream = object : FilterInputStream(delegate.inputStream) {
            override fun read(): Int = `in`.read().also { if (it < 0) receipt.bodyComplete = true else receipt.readBytes.incrementAndGet() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also {
                if (it < 0) receipt.bodyComplete = true else receipt.readBytes.addAndGet(it)
            }
        }
        override fun setRequestProperty(key: String, value: String) {
            if (key.equals("Cookie", true) || key.equals("Authorization", true)) receipt.forbiddenHeaderNames += key
            else receipt.requestHeaders[key] = value
            delegate.setRequestProperty(key, value)
        }
        override fun disconnect() {
            runCatching { delegate.disconnect() }
            if (disconnected.compareAndSet(false, true)) { receipt.closed = true; openConnections.decrementAndGet() }
        }
        override fun connect() = delegate.connect()
        override fun usingProxy(): Boolean = delegate.usingProxy()
        override fun setConnectTimeout(value: Int) { delegate.connectTimeout = value }
        override fun setReadTimeout(value: Int) { delegate.readTimeout = value }
        override fun setRequestMethod(value: String) { delegate.requestMethod = value }
        override fun setUseCaches(value: Boolean) { delegate.useCaches = value }
        override fun setInstanceFollowRedirects(value: Boolean) { delegate.instanceFollowRedirects = value }
        override fun getHeaderField(name: String): String? = delegate.getHeaderField(name)
        override fun getContentLengthLong(): Long = delegate.contentLengthLong
        override fun getContentType(): String? = delegate.contentType
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
