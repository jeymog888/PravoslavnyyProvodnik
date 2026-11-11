package ru.liturgyguide.ui

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.*
import java.time.Instant

/** Explicit network witness, separate from deterministic regression tests. */
@RunWith(AndroidJUnit4::class)
class LiveInternetSchedulesTest {
    @Test fun threeIndependentOfficialSitesCanBeReadThroughTheAndroidTransport() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSchedules") == "true")
        val cache = mutableMapOf<String, String>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = InternetScheduleRepository(UrlConnectionScheduleTransport(timeoutMillis = 15_000,
            limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true), object : ScheduleCacheStore {
            override fun read(parishId: String) = cache[parishId]
            override fun write(parishId: String, value: String) { cache[parishId] = value }
        }, AndroidScheduleDocumentExtractor(context))
        // The selected parish has a real identity in production. Placeholder name=id
        // cannot satisfy the same-building check of an explicitly named source heading.
        data class Input(val id: String, val name: String, val shortName: String, val url: String, val zone: String)
        val inputs = listOf(
            Input("kresta", "Храм Воздвижения Креста Господня на Чистом Вражке гор. Москвы",
                "Крестовоздвиженский храм на Чистом Вражке", "https://hramkresta.ru/schedule/", "Europe/Moscow"),
            Input("petr-fevronia", "Храм святых благоверных князей Петра и Февронии",
                "Патриаршее подворье в Марьино г. Москвы", "https://hram-petr-fevronia.ru/parish-life/schedule/", "Europe/Moscow"),
            Input("nikolai", "Никольский приход", "Никольский храм", "https://www.svt-nikolai.org/ru/?page_id=6846", "Europe/Helsinki")
        )
        val results = inputs.map { (id, name, shortName, url, zone) ->
            val parish = Parish(id, name, shortName, "", "", "", url, zone, url, "", "", "", emptyList(), "", "")
            val state = repository.refresh(parish, Instant.now(), force = true)
            mapOf("parish" to id, "source" to state.sourceUrl, "status" to state.status.name,
                "checkedAt" to state.checkedAt, "events" to state.publication?.events.orEmpty(),
                "publicationRejected" to state.publicationRejected, "format" to state.publication?.documentFormat)
        }
        val report = Gson().toJson(results)
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("live-schedules-07.json").writeText(report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nLive schedule statuses: " + results.map { it["parish"] to it["status"] } + "\n") })
        assertEquals("See live-schedules-07.json; failures may indicate source or network changes", 3,
            results.count { it["status"] == InternetScheduleStatus.READY.name })
        results.forEach { result ->
            val events = result["events"] as List<*>
            assertTrue("${result["parish"]}: READY requires published events", events.isNotEmpty())
        }
    }
}
