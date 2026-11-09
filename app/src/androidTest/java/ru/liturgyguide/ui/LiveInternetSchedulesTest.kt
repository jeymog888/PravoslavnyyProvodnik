package ru.liturgyguide.ui

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        val repository = InternetScheduleRepository(UrlConnectionScheduleTransport(), object : ScheduleCacheStore {
            override fun read(parishId: String) = cache[parishId]
            override fun write(parishId: String, value: String) { cache[parishId] = value }
        })
        val inputs = listOf(
            Triple("kresta", "https://hramkresta.ru/schedule/", "Europe/Moscow"),
            Triple("petr-fevronia", "https://hram-petr-fevronia.ru/parish-life/schedule/", "Europe/Moscow"),
            Triple("nikolai", "https://www.svt-nikolai.org/ru/?page_id=6846", "Europe/Helsinki")
        )
        val results = inputs.map { (id, url, zone) ->
            val parish = Parish(id, id, id, "", "", "", url, zone, url, "", "", "", emptyList(), "", "")
            val state = repository.refresh(parish, Instant.now(), force = true)
            mapOf("parish" to id, "source" to state.sourceUrl, "status" to state.status.name,
                "checkedAt" to state.checkedAt, "events" to state.publication?.events.orEmpty())
        }
        val report = Gson().toJson(results)
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("live-schedules-07.json").writeText(report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nLive schedule statuses: " + results.map { it["parish"] to it["status"] } + "\n") })
        assertEquals("See live-schedules-07.json; failures may indicate source or network changes", 3,
            results.count { it["status"] == InternetScheduleStatus.READY.name })
    }
}
