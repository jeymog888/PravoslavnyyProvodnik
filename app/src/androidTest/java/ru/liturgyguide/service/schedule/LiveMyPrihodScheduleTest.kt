package ru.liturgyguide.service.schedule

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Configured public platform page; this checks import, not automatic website discovery. */
@RunWith(AndroidJUnit4::class)
class LiveMyPrihodScheduleTest {
    @Test fun configuredFilippPageImportsTheDatedMonthAndReopensItsCommittedCache() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in to the current public MyPrihod page", args.getString("liveMyPrihod") == "true")
        val run = requireNotNull(args.getString("myPrihodRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(requireNotNull(context.getExternalFilesDir("myprihod-live")), run)
        check(!folder.exists() && folder.mkdirs()) { "Use a unique myPrihodRun" }
        val source = "https://hram-sv-filippa.myprihod.ru/raspisanie-bogosluzhenij"
        val parish = Parish("filipp", "Храм святителя Филиппа, митрополита Московского", "Храм святителя Филиппа",
            "Москва", "Гиляровского, 35", "", source, "Europe/Moscow", "", "", "", "", emptyList(), "", "")
        val now = Instant.parse("2026-11-12T06:00:00Z")
        val preferenceName = "myprihod-live-${UUID.randomUUID()}"
        val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
        val requests = mutableListOf<MutableMap<String, Any?>>()
        val offlineCalls = AtomicInteger()
        val receipt = linkedMapOf<String, Any?>("passed" to false, "parish" to parish, "clock" to now.toString(),
            "apkSha256" to sha256(File(context.applicationInfo.sourceDir)), "requests" to requests,
            "cachePreferenceName" to preferenceName, "discoveryTested" to false,
            "statusScope" to "Successful responses are HTTP 200 under the production GET transport contract",
            "offlineScope" to "New repository/cache adapter in one process reading committed UUID test preferences; no cold-process or radio-disabled claim",
            "statePolicy" to "Configured website, no sourceOverride; no main preferences, fake provider, raw response bodies, headers or session data")
        fun write(name: String, value: Any?) { File(folder, name).writeText(gson.toJson(value)) }
        fun save() { receipt["offlineCalls"] = offlineCalls.get(); write("receipt.json", receipt) }
        fun cache(): ScheduleCacheStore = object : ScheduleCacheStore {
            private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            override fun read(parishId: String) = preferences.getString(parishId, null)
            override fun write(parishId: String, value: String) {
                require(parishId == parish.id && value.length <= 200_000)
                check(preferences.edit().putString(parishId, value).commit())
            }
        }
        save()
        try {
            assertNull(cache().read(parish.id))
            val network = UrlConnectionScheduleTransport(timeoutMillis = 15_000,
                limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true)
            val transport = ScheduleTransport { url ->
                val call = linkedMapOf<String, Any?>("requestedUrl" to url, "method" to "GET")
                requests += call
                val start = System.nanoTime()
                try {
                    network.fetch(url).also { response -> call.putAll(mapOf("status" to 200,
                        "returnedUrl" to response.url, "contentType" to response.contentType, "bytes" to response.bytes.size)) }
                } catch (failure: Exception) { call["failureType"] = failure.javaClass.name; throw failure }
                finally { call["elapsedMillis"] = (System.nanoTime() - start) / 1_000_000; save() }
            }
            val repository = InternetScheduleRepository(transport, cache(), AndroidScheduleDocumentExtractor(context),
                siteResolver = productionScheduleSiteResolver(transport, MemoryOfficialDirectoryCacheStore()))
            val result = withTimeout(120_000) { repository.refresh(parish, now, force = true) }
            write("result.json", result)
            receipt["status"] = result.status.name; receipt["eventCount"] = result.publication?.events?.size
            receipt["documentFormat"] = result.publication?.documentFormat; save()
            assertEquals(InternetScheduleStatus.READY, result.status)
            assertEquals(source, result.configuredWebsite); assertEquals(source, result.website)
            assertEquals("", result.customSource); assertFalse(result.publicationRejected); assertFalse(result.sourceNeedsVerification)
            val publication = requireNotNull(result.publication)
            assertEquals(source, publication.sourceUrl); assertEquals("html", publication.documentFormat)
            assertEquals(32, publication.events.size); assertFalse(publication.yearInferred)
            assertEquals(listOf(source), requests.map { it["requestedUrl"] })
            fun clocks(date: String) = publication.forDate(LocalDate.parse(date)).sortedBy { it.time }.map { it.time }
            assertEquals(listOf("09:00"), clocks("2026-11-12"))
            assertEquals(listOf("09:00", "17:00"), clocks("2026-11-13"))
            assertEquals(listOf("09:00", "09:40", "17:00"), clocks("2026-11-15"))
            assertTrue("Undated recurring prose is not this month's dated table", publication.events.none { it.time == "10:00" })
            assertTrue(publication.events.none { it.title.contains("иерей", true) || it.title.contains("диакон", true) })
            assertEquals(result, ScheduleCacheCodec.decode(requireNotNull(cache().read(parish.id)), parish))
            val offline = InternetScheduleRepository(ScheduleTransport { offlineCalls.incrementAndGet(); throw IOException() }, cache())
            val reopened = offline.cached(parish); write("offline-result.json", reopened)
            assertEquals(result, reopened); assertEquals(0, offlineCalls.get())
            receipt["codecRoundTrip"] = true; receipt["freshRepositoryCachedReopen"] = true; receipt["passed"] = true
        } catch (failure: Throwable) { receipt["failureType"] = failure.javaClass.name; throw failure }
        finally { save() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
