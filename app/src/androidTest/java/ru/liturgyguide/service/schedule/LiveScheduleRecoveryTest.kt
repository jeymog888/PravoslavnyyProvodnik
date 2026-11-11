package ru.liturgyguide.service.schedule

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.Instant

/** Sources checked independently in the 20-church audit. No discovery response or OCR is mocked. */
class LiveScheduleRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-11-12T03:00:00Z")

    @Test fun obsoleteConfiguredWebsiteFindsTheSameNovosibirskChurchAndReopensOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveScheduleRecovery") == "true")
        // This verifies source recovery and the published monastery timetable. Whether
        // every unlabelled event belongs to this building needs separate shared-site acceptance.
        val parish = Parish("osm:way:55228075", "Храм в честь Святых Новомучеников и Исповедников Российских", "Новомучеников",
            "Новосибирск", "улица Немировича-Данченко, 120/1", "", "http://neomartires.prihod.ru/", "Asia/Novosibirsk",
            "https://www.openstreetmap.org/way/55228075", "", "", "", emptyList(), "", "", 54.9755397, 82.9086203)
        val result = checkSource("novosibirsk", parish)
        assertEquals("https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/", result.website)
        assertEquals("https://azbyka.ru/palomnik/index.php?curid=6081", result.siteEvidenceUrl)
        assertTrue(requireNotNull(result.publication).yearInferred)
        for (day in listOf("2026-11-12", "2026-11-13")) {
            assertEquals(listOf("08:30", "17:00"), result.publication?.events?.filter { it.date == day }?.map { it.time })
        }
    }

    @Test fun tomskHomepageFindsItsCurrentScheduleImageInsteadOfTheOldCover() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveScheduleRecovery") == "true")
        // The official homepage is known here. This tests document discovery, not map-to-website matching.
        val parish = Parish("live:tomsk-peter-paul", "Петропавловский собор", "Петропавловский собор", "Томск", "Алтайская улица, 47", "",
            "https://xn--90azbbajd.xn--p1ai/", "Asia/Tomsk", "https://xn--90azbbajd.xn--p1ai/", "", "", "", emptyList(), "", "")
        val result = checkSource("tomsk", parish)
        assertFalse(requireNotNull(result.publication).yearInferred)
        for (day in listOf("2026-11-12", "2026-11-13")) {
            assertEquals(listOf("08:30", "16:45", "17:00"), result.publication?.events?.filter { it.date == day }?.map { it.time })
        }
    }

    private suspend fun checkSource(label: String, parish: Parish): InternetScheduleState {
        val folder = context.filesDir.resolve("live-source-recovery/$label").apply { mkdirs() }
        val preferences = context.getSharedPreferences("live-source-recovery-$label", 0)
        check(preferences.edit().clear().commit())
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = preferences.getString(parishId, null)
            override fun write(parishId: String, value: String) { check(preferences.edit().putString(parishId, value).commit()) }
        }
        val network = UrlConnectionScheduleTransport(timeoutMillis = 20_000, allowExplicitHttp = true)
        val extractor = AndroidScheduleDocumentExtractor(context)
        val requests = mutableListOf<Map<String, Any?>>()
        val transport = ScheduleTransport { url ->
            val started = System.nanoTime()
            try {
                network.fetch(url).also { response ->
                    requests += mapOf("requested" to url, "returned" to response.url, "type" to response.contentType,
                        "bytes" to response.bytes.size, "elapsedMillis" to (System.nanoTime() - started) / 1_000_000)
                    folder.resolve("requests.json").writeText(Gson().toJson(requests))
                }
            } catch (failure: Exception) {
                requests += mapOf("requested" to url, "error" to failure.javaClass.simpleName,
                    "elapsedMillis" to (System.nanoTime() - started) / 1_000_000)
                folder.resolve("requests.json").writeText(Gson().toJson(requests))
                throw failure
            }
        }
        val repository = InternetScheduleRepository(transport, cache, ScheduleDocumentExtractor { response ->
            extractor.extract(response).also { document ->
                folder.resolve("document-${requests.size}.json").writeText(Gson().toJson(document))
            }
        })
        val result = repository.refresh(parish, now, force = true)
        folder.resolve("result.json").writeText(Gson().toJson(result))
        assertEquals("$label: inspect live-source-recovery evidence", InternetScheduleStatus.READY, result.status)
        assertNotNull(result.publication)
        val offline = InternetScheduleRepository(ScheduleTransport { error("Offline: no request allowed") }, cache)
        assertEquals(result, offline.cached(parish))
        return result
    }
}
