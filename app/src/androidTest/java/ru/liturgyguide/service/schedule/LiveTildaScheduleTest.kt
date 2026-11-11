package ru.liturgyguide.service.schedule

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.service.ParishRepository
import java.time.Instant

/** Opt-in real website -> lazy images -> native Russian OCR -> dated parish events. */
class LiveTildaScheduleTest {
    @Test fun publishedMonthlyImagesReachNativeRecognitionWithoutBorrowingTheDaughterChurch() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTildaSchedules") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val selected = ParishRepository(context.assets.open("churches.json").bufferedReader().use { it.readText() })
            .selected("elisavetinsky_opalikha").copy(schedule = emptyList(), scheduleValidFrom = "", scheduleValidUntil = "")
        val output = context.filesDir.resolve("schedule-tilda-10").apply { mkdirs() }
        val network = UrlConnectionScheduleTransport(timeoutMillis = 20_000)
        val native = AndroidScheduleDocumentExtractor(context)
        val documents = mutableListOf<ScheduleDocument>()
        val requests = mutableListOf<String>()
        val cache = mutableMapOf<String, String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            requests += url
            network.fetch(url)
        }, object : ScheduleCacheStore {
            override fun read(parishId: String) = cache[parishId]
            override fun write(parishId: String, value: String) { cache[parishId] = value }
        }, ScheduleDocumentExtractor { response ->
            native.extract(response)?.also { document ->
                documents += document
                output.resolve("document-${documents.size}.json").writeText(Gson().toJson(document))
            }
        })
        val result = repository.refresh(selected, Instant.parse("2026-11-12T07:00:00Z"), force = true)
        output.resolve("result.json").writeText(Gson().toJson(mapOf("state" to result, "requests" to requests)))
        assertEquals("Inspect schedule-tilda-10/result.json", InternetScheduleStatus.READY, result.status)
        assertEquals(3, documents.size)
        val events = requireNotNull(result.publication).events
        fun liturgies(date: String) = events.filter { it.date == date && it.title.contains("Литурги", true) }.map { it.time }
        assertEquals(listOf("08:00"), liturgies("2026-11-12"))
        assertEquals(listOf("07:00", "09:30"), liturgies("2026-11-15"))
        // This continuation page has no repeated month heading; the third picture is a different church.
        assertEquals(listOf("08:00"), liturgies("2026-11-16"))
        assertEquals(listOf("07:00", "09:30"), liturgies("2026-11-29"))
        assertEquals(result, repository.cached(selected))
    }
}
