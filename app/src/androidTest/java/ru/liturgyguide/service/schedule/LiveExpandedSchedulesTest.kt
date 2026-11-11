package ru.liturgyguide.service.schedule

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.Instant

/** Real independent sources beyond the original three HTML templates. Explicit opt-in only. */
class LiveExpandedSchedulesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-11-11T07:00:00Z")
    private fun parish(id: String, name: String, website: String, zone: String) = Parish(
        id, name, name, "", "", "", website, zone, website, "", "", "", emptyList(), "", "")
    private fun repository(label: String = "cache") : InternetScheduleRepository {
        val transport = UrlConnectionScheduleTransport(timeoutMillis = 20_000, allowExplicitHttp = true)
        val extractor = AndroidScheduleDocumentExtractor(instrumentation.targetContext)
        val directory = instrumentation.targetContext.filesDir.resolve("expanded-schedule-evidence").apply { mkdirs() }
        var responseNumber = 0
        return InternetScheduleRepository(ScheduleTransport { url ->
            transport.fetch(url).also { response ->
                val number = ++responseNumber
                directory.resolve("$label-response-$number.json").writeText(Gson().toJson(mapOf(
                    "url" to response.url, "type" to response.contentType, "bytes" to response.bytes.size)))
            }
        },
        object : ScheduleCacheStore {
            private val preferences = instrumentation.targetContext.getSharedPreferences("live-expanded-schedule", 0)
            override fun read(parishId: String) = preferences.getString(parishId, null)
            override fun write(parishId: String, value: String) { check(preferences.edit().putString(parishId, value).commit()) }
        }, ScheduleDocumentExtractor { response ->
            try {
                extractor.extract(response).also { document ->
                    directory.resolve("$label-document-$responseNumber.json").writeText(Gson().toJson(document))
                }
            } catch (error: Exception) {
                directory.resolve("$label-error-$responseNumber.txt").writeText(error.toString())
                throw error
            }
        })
    }

    @Test fun realTablesPdfsAndAnIllustrationProduceOnlyPublishedDatesOnAndroid() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveExpandedSchedules") == "true")
        val kruticy = parish("live:kruticy", "Успенский собор Крутицкого подворья", "https://kruticy.moscow/bogosluzheniya/", "Europe/Moscow")
        val dnepr = parish("live:dnepr", "Трехсвятительский храм", "https://trisvyat-dp.church.ua/raspisanie-bogosluzhenij/", "Europe/Kyiv")
        val helsinki = parish("live:helsinki", "Никольский приход", "https://www.svt-nikolai.org/ru/?page_id=378", "Europe/Helsinki")
        val kaluga = parish("live:kaluga", "Архиерейское подворье. Храм в честь Вознесения Господня", "", "Europe/Moscow")
        val inputs = listOf(
            Triple("kruticy-html", kruticy, null),
            Triple("dnepr-html", dnepr, null),
            Triple("dnepr-pdf", dnepr, "https://trisvyat-dp.church.ua/files/2026/11/РАСПИСАНИЕ-БОГОСЛУЖЕНИЙ-НА-НОЯБРЬ-2026.pdf"),
            // This independently verified URL is entered explicitly. It is NOT automatic church-source discovery.
            Triple("kaluga-explicit-http", kaluga, "http://kaluga-voznesenie.ru/raspisanie-bogosluzhenii/"),
            Triple("kruticy-webp", kruticy, "https://kruticy.moscow/wp-content/uploads/2026/10/generated-image2026-10-31-200814.webp"),
            Triple("helsinki-pdf", helsinki, "https://www.svt-nikolai.org/ru/wp-content/uploads/2026/09/%D0%A0%D0%B0%D1%81%D0%BF%D0%B8%D1%81%D0%B0%D0%BD%D0%B8%D0%B5-%D0%91%D0%BE%D0%B3%D0%BE%D1%81%D0%BB%D1%83%D0%B6%D0%B5%D0%BD%D0%B8%D0%B9-2026-10-12-1.pdf")
        )
        val results = inputs.map { (label, selected, override) ->
            val bound = selected.copy(id = "${selected.id}:$label")
            val started = System.nanoTime()
            val state = repository(label).refresh(bound, now, force = true, sourceOverride = override)
            label to mapOf("state" to state, "parish" to bound, "explicitSourceOverride" to override,
                "elapsedMillis" to (System.nanoTime() - started) / 1_000_000)
        }.toMap()
        instrumentation.targetContext.filesDir.resolve("live-expanded-schedules-08.json").writeText(Gson().toJson(results))
        for ((label, result) in results) {
            val state = result.getValue("state") as InternetScheduleState
            if (label == "kruticy-webp") {
                // Visually inspected: this is an illustrated cover, with no dates or clocks.
                assertEquals(InternetScheduleStatus.UNSUPPORTED, state.status)
                assertNull(state.publication)
                continue
            }
            assertEquals("$label: see live-expanded-schedules-08.json", InternetScheduleStatus.READY, state.status)
            val events = requireNotNull(state.publication).events
            val sundayTime = when { label.startsWith("dnepr") -> "08:00"; label.startsWith("helsinki") -> "09:30"; else -> "09:00" }
            assertTrue("$label: published Sunday liturgy", events.any { it.date == "2026-11-15" && it.time == sundayTime && it.title.contains("Литурги", true) })
            if (label.startsWith("dnepr")) {
                assertTrue("$label: feast preface keeps Saturday liturgy", events.any { it.date == "2026-11-21" && it.time == "07:30" })
                assertFalse("$label: a recurring Wednesday service is not on Sunday", events.any { it.date == "2026-11-29" && it.time == "09:00" })
            }
            if (label == "kaluga-explicit-http") {
                assertEquals("http://kaluga-voznesenie.ru/raspisanie-bogosluzhenii/", state.publication?.sourceUrl)
                assertEquals(state.customSource, state.publication?.sourceUrl)
                assertTrue(requireNotNull(state.publication).yearInferred)
                assertEquals(listOf("07:00", "09:00", "17:00"), events.filter { it.date == "2026-11-15" }.map { it.time })
            }
            assertEquals("Persistent cache must reopen through another repository", state.publication,
                repository().cached(result.getValue("parish") as Parish).publication)
        }
    }
}
