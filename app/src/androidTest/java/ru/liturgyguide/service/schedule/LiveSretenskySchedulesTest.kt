package ru.liturgyguide.service.schedule

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.liturgyguide.model.ParishDirectory
import java.time.Instant

/** Real Android import of both bundled buildings. Fixed historical witness, explicitly opt-in. */
class LiveSretenskySchedulesTest {
    @Test fun bothBuildingsRefreshTheirPublishedTimesWithoutFallingBackToTypicalSchedule() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSretenskySchedules") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Gson().fromJson(context.assets.open("churches.json").bufferedReader().use { it.readText() }, ParishDirectory::class.java)
        val selected = directory.churches.filter { it.id in setOf("sretensky_old", "sretensky_new") }
        assertEquals(2, selected.size)
        val now = Instant.parse("2026-11-11T12:00:00Z")
        val cacheValues = mutableMapOf<String, String>()
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = cacheValues[parishId]
            override fun write(parishId: String, value: String) { cacheValues[parishId] = value }
        }
        val repository = InternetScheduleRepository(UrlConnectionScheduleTransport(timeoutMillis = 15_000,
            limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true),
            cache, AndroidScheduleDocumentExtractor(context))
        val results = selected.associate { parish -> parish.id to repository.refresh(parish, now, force = true) }
        context.filesDir.resolve("live-sretensky-release08.json").writeText(Gson().toJson(results))
        for (parish in selected) {
            val state = results.getValue(parish.id)
            assertEquals("${parish.id}: see live-sretensky-release08.json", InternetScheduleStatus.READY, state.status)
            assertFalse(state.publicationRejected)
            assertFalse(requireNotNull(state.publication).yearInferred)
            val displayed = parish.withInternetSchedule(state, now)
            assertEquals(state.publication!!.sourceUrl, displayed.sourceUrl)
            assertTrue(displayed.schedule.isNotEmpty())
            if (parish.id == "sretensky_old") {
                assertEquals(listOf("06:45", "08:00", "18:00"), displayed.schedule.filter { it.date == "2026-11-11" }.map { it.time })
                assertEquals(listOf("07:00"), displayed.schedule.filter { it.date == "2026-11-15" }.map { it.time })
            } else {
                assertTrue(displayed.schedule.none { it.date == "2026-11-11" })
                assertEquals(listOf("10:00", "13:00", "18:00"), displayed.schedule.filter { it.date == "2026-11-15" }.map { it.time })
            }
            assertEquals(state, repository.cached(parish))
        }
    }
}
