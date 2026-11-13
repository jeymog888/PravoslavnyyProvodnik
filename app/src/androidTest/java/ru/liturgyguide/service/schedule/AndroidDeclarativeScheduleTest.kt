package ru.liturgyguide.service.schedule

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Full published HTML, production parser and a private on-device cache; no real user preferences. */
class AndroidDeclarativeScheduleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val url = "https://www.hramnaholmah.ru/raspisanie-bogosluzhenij/"
    private val now = Instant.parse("2026-11-13T12:00:00Z")
    private val parish = Parish("osm:way:28769189", "Храм Рождества Пресвятой Богородицы в Крылатском",
        "Рождества в Крылатском", "Москва", "Крылатские Холмы, 12", "", url, "Europe/Moscow", url,
        "", "", "", emptyList(), "", "")

    private fun fixture(): ByteArray = instrumentation.context.assets.open("schedule12/nativity-actual.html").use { it.readBytes() }.also {
        assertEquals("1dcb19ef36a5d3ee9cb9be04339ac2d0bbb4f5d8a402ff5904d150642a71a4de", sha(it))
    }

    private fun response(bytes: ByteArray = fixture()) = ScheduleResponse(url, bytes, "text/html; charset=UTF-8")
    private fun repository(transport: ScheduleTransport, store: ScheduleCacheStore) = InternetScheduleRepository(
        transport, store, HtmlScheduleDocumentExtractor, siteResolver = ScheduleSiteResolver { null })

    @Test fun actualCompleteSourceKeepsAllPrintedStartsOnAndroid() {
        val result = ScheduleHtmlParser.inspect(requireNotNull(response().html()), LocalDate.of(2026, 11, 13), parish)
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertEquals(97, result.events.size)
        assertEquals(listOf("08:40", "17:00"), result.events.filter { it.date == "2026-11-13" }.map { it.time })
        assertEquals(listOf("06:40", "09:40", "17:00"), result.events.filter { it.date == "2026-11-15" }.map { it.time })
        assertEquals("service_block", result.events.first { it.date == "2026-11-13" }.kind)
        assertFalse(result.events.any { "служащий:" in it.title || "служащие:" in it.title })
    }

    @Test fun publicationReopensFromPrivateDeviceStorageWithoutAnyNetworkRequest() = runBlocking {
        withStore { store ->
            val bytes = fixture()
            val received = repository(ScheduleTransport { response(bytes) }, store).refresh(parish, now, force = true)
            assertEquals(InternetScheduleStatus.READY, received.status)
            val publication = requireNotNull(received.publication)
            assertEquals(97, publication.events.size)
            val savedJson = requireNotNull(store.read(parish.id))
            var networkRequests = 0
            val restarted = repository(ScheduleTransport { networkRequests++; throw IOException("No connection") }, store)
            val offline = restarted.cached(parish)
            assertEquals(publication, offline.publication)
            assertEquals(0, networkRequests)
            assertEquals(savedJson, store.read(parish.id))
            assertEquals(listOf("08:40", "17:00"), requireNotNull(offline.publication).forDate(LocalDate.of(2026, 11, 13)).map { it.time })
        }
    }

    @Test fun anOuterWithdrawalCannotReturnFromTheStoredSourceAfterRestart() = runBlocking {
        withStore { store ->
            val bytes = fixture()
            assertNotNull(repository(ScheduleTransport { response(bytes) }, store).refresh(parish, now, force = true).publication)
            val cancelled = Jsoup.parse(bytes.toString(Charsets.UTF_8), url)
            val root = cancelled.selectFirst("main,[role=main]") ?: cancelled.body()
            root.appendElement("p").text("Все богослужения отменены")
            val changed = response(cancelled.outerHtml().toByteArray())
            val withdrawn = repository(ScheduleTransport { changed }, store).refresh(parish, now.plusSeconds(60), force = true)
            assertNull(withdrawn.publication)
            assertTrue(withdrawn.publicationRejected)
            val offline = repository(ScheduleTransport { throw IOException("No connection") }, store).cached(parish)
            assertNull(offline.publication)
            assertTrue(offline.publicationRejected)
        }
    }

    private suspend fun withStore(block: suspend (ScheduleCacheStore) -> Unit) {
        val name = "qa12_declarative_${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val store = object : ScheduleCacheStore {
            override fun read(parishId: String) = prefs.getString(parishId, null)
            override fun write(parishId: String, value: String) { check(prefs.edit().putString(parishId, value).commit()) }
        }
        try { block(store) } finally { app.deleteSharedPreferences(name) }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
