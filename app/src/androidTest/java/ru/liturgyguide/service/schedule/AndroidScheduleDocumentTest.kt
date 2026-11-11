package ru.liturgyguide.service.schedule

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant

/** Uses production native Tesseract, real scanned bytes, and production event parsing. No injected OCR text. */
class AndroidScheduleDocumentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-11-10T08:00:00Z")
    private val selected = Parish("test:helsinki", "Никольский храм", "Никольский храм", "Хельсинки", "Tuonelankuja 3", "",
        "https://www.svt-nikolai.org/ru/", "Europe/Helsinki", "", "", "", "", emptyList(), "", "", 60.169, 24.922)
    private class Cache : ScheduleCacheStore {
        private val data = mutableMapOf<String, String>()
        override fun read(parishId: String) = data[parishId]
        override fun write(parishId: String, value: String) { data[parishId] = value }
    }
    private fun fixture(name: String) = instrumentation.context.assets.open("schedule/$name").use { it.readBytes() }

    @Test fun scannedPdfImportsDatedServicesAndSurvivesRepositoryRecreationOffline() = runBlocking {
        withTimeout(170_000) {
            val bytes = fixture("helsinki-2026-10-12.pdf")
            val url = "https://www.svt-nikolai.org/ru/schedule.pdf"
            val cache = Cache()
            val transport = ScheduleTransport { source ->
                if (source == selected.website) ScheduleResponse(source,
                    "<h1>Расписание богослужений</h1><a href='schedule.pdf'>Ноябрь - скачать PDF</a>".toByteArray(), "text/html")
                else if (source == url) ScheduleResponse(source, bytes, "application/pdf")
                else throw IOException("Unexpected URL")
            }
            val repo = InternetScheduleRepository(transport, cache, AndroidScheduleDocumentExtractor(instrumentation.targetContext))
            val loaded = repo.refresh(selected, now)
            assertEquals(InternetScheduleStatus.READY, loaded.status)
            assertEquals("pdf", loaded.publication?.documentFormat)
            assertEquals(url, loaded.publication?.sourceUrl)
            assertServices(requireNotNull(loaded.publication).events)
            val offline = InternetScheduleRepository(ScheduleTransport { throw IOException("Offline fixture") }, cache,
                AndroidScheduleDocumentExtractor(instrumentation.targetContext))
            assertEquals(loaded.publication, offline.cached(selected).publication)
            assertEquals(loaded.publication, offline.refresh(selected, now.plusSeconds(1), force = true).publication)
        }
    }

    @Test fun standaloneScheduleImageUsesTheSameNativeRecognitionAndEventParser() = runBlocking {
        withTimeout(130_000) {
            val url = "https://www.svt-nikolai.org/ru/november.png"
            val bytes = fixture("helsinki-2026-11.png")
            val repo = InternetScheduleRepository(ScheduleTransport { ScheduleResponse(it, bytes, "image/png") }, Cache(),
                AndroidScheduleDocumentExtractor(instrumentation.targetContext))
            val loaded = repo.refresh(selected, now, sourceOverride = url)
            assertEquals(InternetScheduleStatus.READY, loaded.status)
            assertEquals("image", loaded.publication?.documentFormat)
            assertServices(requireNotNull(loaded.publication).events)
        }
    }

    private fun assertServices(events: List<InternetService>) {
        val saturday = events.single { it.date == "2026-11-14" && it.time == "18:00" }
        val sunday = events.single { it.date == "2026-11-15" && it.time == "09:30" }
        assertTrue(saturday.title.contains("всенощ", ignoreCase = true))
        assertTrue(sunday.title.contains("Литурги", ignoreCase = true))
        assertEquals("service_block", sunday.kind) // 09:30 begins confession plus liturgy, not a precise liturgy anchor.
    }
}
