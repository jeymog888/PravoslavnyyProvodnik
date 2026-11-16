package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import org.jsoup.Jsoup
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class YearlessDataCalendarRepositoryTest {
    private val now = Instant.parse("2026-11-16T06:00:00Z")
    private val oldTime = now.minusSeconds(3600)
    private val site = "https://calendar.example/"
    private val parish = Parish("custom:yearless", "Никольский храм", "Никольский храм", "", "", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
    private class Cache : ScheduleCacheStore {
        val values = mutableMapOf<String, String>()
        val safetyWrites = mutableListOf<String>()
        var onSafety: ((String) -> Unit)? = null
        override fun read(parishId: String) = values[parishId]
        override fun write(parishId: String, value: String) { values[parishId] = value }
        override fun writeSafety(parishId: String, value: String) { safetyWrites += value; write(parishId, value); onSafety?.invoke(value) }
    }
    private fun repo(cache: Cache, transport: ScheduleTransport) = InternetScheduleRepository(transport, cache,
        siteResolver = ScheduleSiteResolver { null })
    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(), "text/html; charset=UTF-8")
    private val names = listOf("ПОНЕДЕЛЬНИК", "ВТОРНИК", "СРЕДА", "ЧЕТВЕРГ", "ПЯТНИЦА", "СУББОТА", "ВОСКРЕСЕНЬЕ")
    private fun calendar(entries: (Int) -> String = { "09:00 — Литургия" }, before: String = "", after: String = "") =
        "<title>Расписание</title><div>$before<script type='text/plain'>" + (1..30).joinToString("\n") {
            "${names[LocalDate.of(2026, 11, it).dayOfWeek.value - 1]},$it ноября\nПамять святого\n${entries(it)}"
        } + "</script>$after</div>"
    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/yearless-data-calendar-case043/$name")).use { it.readBytes() }
    private fun previous(cache: Cache) {
        val publication = SchedulePublication(parish.id, parish.timezone, site, site, oldTime.toString(),
            listOf(InternetService("2026-11-16", "09:00", "Литургия", "liturgy"), InternetService("2026-12-01", "09:00", "Литургия", "liturgy")))
        cache.values[parish.id] = ScheduleCacheCodec.encode(InternetScheduleState(parish.id, InternetScheduleStatus.READY,
            website = site, sourceUrl = site, checkedAt = oldTime.toString(), customSource = site, publication = publication))
    }

    @Test fun ordinaryConfiguredWebsiteReachesRecordedCalendarWithoutAnExactUrlOverrideAndReopensOffline() = runBlocking {
        val configured = parish.copy(id = "osm:way:123186364", name = "Церковь Преподобного Сергия Радонежского в Бусинове",
            shortName = "Церковь Преподобного Сергия Радонежского в Бусинове", city = "Москва", address = "Москва, Ижорская улица, 1 с1",
            website = "https://www.businovohram.ru/", latitude = 55.88327875, longitude = 37.498854)
        val mapping = JsonParser.parseString(fixture("REQUEST-MAPPING.json").toString(Charsets.UTF_8)).asJsonObject["requests"].asJsonArray
        val calls = mutableListOf<String>()
        val cache = Cache()
        val repository = repo(cache, ScheduleTransport { requested ->
            calls += requested
            val item = mapping.map { it.asJsonObject }.singleOrNull { it["requestUrl"].asString == requested }
                ?: throw IOException("Unrecorded request: $requested")
            val bytes = fixture(item["fixture"].asString)
            assertEquals(item["savedSanitizedSha256"].asString, ScheduleSourceCandidate.sha(bytes))
            ScheduleResponse(item["responseUrl"].asString, bytes, item["contentType"].asString)
        })
        val loaded = repository.refresh(configured, now)
        System.getenv("FEORIA_043_OUTPUT")?.takeIf { it.isNotBlank() }?.let { path ->
            val output = File(path).canonicalFile
            check(output.isDirectory || output.mkdirs())
            val date = now.atZone(ZoneId.of(configured.timezone)).toLocalDate()
            val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
            val inventory = JsonParser.parseString(fixture("EXPECTED-043.json").toString(Charsets.UTF_8)).asJsonObject["allRows"].asJsonArray
            val events = loaded.publication?.events.orEmpty()
            val report = JsonObject().apply {
                addProperty("status", "ACTUAL_CONFIGURED_JVM_REPLAY_NOT_ANDROID_OR_LIVE")
                addProperty("date", date.toString()); addProperty("instant", now.toString())
                addProperty("sourceOverrideUsed", false); add("selectedParish", gson.toJsonTree(configured))
                add("requestUrls", gson.toJsonTree(calls)); add("responseMapping", mapping.deepCopy())
                add("state", gson.toJsonTree(loaded)); addProperty("ready", loaded.status == InternetScheduleStatus.READY && events.isNotEmpty())
                add("rows", JsonArray().also { array -> inventory.forEach { value ->
                    val row = value.asJsonObject.deepCopy()
                    val day = LocalDate.parse(row["dateIfQualifiedCurrentMonth"].asString)
                    val event = InternetService(day.toString(), row["time"].asString, row["expectedTitle"].asString, row["kind"].asString)
                    val outcome = when {
                        day !in date.minusDays(1)..date.plusDays(45) -> "OUTSIDE_IMPORT_WINDOW"
                        event in events -> "ACCEPTED_EXACT_SOURCE_ROW"
                        else -> "OMITTED_REQUIRES_ROOT_GUARD_REASON"
                    }
                    row.addProperty("actualParserResult", outcome)
                    if (outcome == "OMITTED_REQUIRES_ROOT_GUARD_REASON") row.addProperty("releaseAcceptance", "NOT_ACCEPTED_UNTIL_CONCRETE_REASON")
                    array.add(row)
                } })
            }
            val file = File(output, "configured-case043-$date.json")
            check(!file.exists()) { "Use a fresh configured replay output" }
            file.writeText(gson.toJson(report))
        }
        assertEquals("Known configured source should reach its calendar, without repairing identity", InternetScheduleStatus.READY, loaded.status)
        val publication = requireNotNull(loaded.publication)
        assertEquals("https://businovohram.ru/raspisanie", publication.sourceUrl)
        assertEquals("", loaded.customSource)
        assertEquals(configured.website, loaded.configuredWebsite)
        assertTrue(publication.yearInferred)
        val directActual = ScheduleHtmlParser.inspect(Jsoup.parse(fixture("source.html").toString(Charsets.UTF_8), publication.sourceUrl),
            now.atZone(ZoneId.of(configured.timezone)).toLocalDate(), configured)
        assertEquals("Actual configured parish/source, not the generic Nikolsky fixture, owns this oracle", directActual.events, publication.events)
        assertEquals(directActual.yearInferred, publication.yearInferred)
        assertEquals(directActual.knownPartial, publication.knownPartial)
        assertTrue(publication.events.any { it.date == "2026-11-22" && it.time == "06:40" })
        assertTrue(publication.events.none { it.date == "2026-11-16" || it.date == "2026-11-17" })
        assertEquals(mapping.map { it.asJsonObject["requestUrl"].asString }, calls)
        val raw = requireNotNull(cache.read(configured.id))
        var network = 0
        val offline = repo(cache, ScheduleTransport { network++; throw IOException("Offline") }).cached(configured)
        assertEquals(publication, offline.publication)
        assertEquals(0, network)
        assertEquals(raw, cache.read(configured.id))
    }

    @Test fun exactNegativeIsDurableBeforeNextIoAndKeepsOtherDatesAndOriginalAgeAfterFailure() = runBlocking {
        val cache = Cache(); previous(cache)
        var sawSafetyBeforeIo = false
        val repository = repo(cache, ScheduleTransport { requested -> when (requested) {
            site -> response(site, calendar(entries = { "СЛУЖБ НЕТ" }, after = "<a href='/schedule-next/'>Расписание богослужений</a>"))
            site + "schedule-next/" -> {
                val durable = requireNotNull(ScheduleCacheCodec.decode(cache.read(parish.id), parish))
                assertTrue(cache.safetyWrites.isNotEmpty())
                assertEquals(listOf("2026-12-01"), requireNotNull(durable.publication).events.map { it.date })
                assertEquals(oldTime.toString(), durable.publication!!.fetchedAt)
                assertTrue(durable.sourceHasWithdrawals)
                sawSafetyBeforeIo = true
                throw IOException("Failure after durable safety write")
            }
            else -> throw IOException("Unmapped")
        } })
        val after = repository.refresh(parish, now, force = true)
        assertTrue(sawSafetyBeforeIo)
        val retained = requireNotNull(after.publication)
        assertEquals(listOf("2026-12-01"), retained.events.map { it.date })
        assertEquals(oldTime.toString(), retained.fetchedAt)
        val raw = requireNotNull(cache.read(parish.id))
        var calls = 0
        val offline = repo(cache, ScheduleTransport { calls++; throw IOException("Offline") })
        assertEquals(retained, offline.cached(parish).publication)
        assertEquals(raw, cache.read(parish.id))
        assertEquals(0, calls)
        assertEquals(retained, offline.refresh(parish, now.plusSeconds(60), force = true).publication)
    }

    @Test fun staleYearCannotTurnTheSameNegativeMarkerIntoA2026Withdrawal() = runBlocking {
        val cache = Cache(); previous(cache)
        val accepted = requireNotNull(repo(cache, ScheduleTransport { error("No request") }).cached(parish).publication)
        val repository = repo(cache, ScheduleTransport { response(it, calendar(entries = { "СЛУЖБ НЕТ" }, before = "<h2>Расписание 2025 года</h2>")) })
        val result = repository.refresh(parish, now, force = true)
        assertEquals(accepted, result.publication)
        assertTrue(cache.safetyWrites.isEmpty())
        assertFalse(result.sourceHasWithdrawals)
    }

    @Test fun aPositivelyForeignNegativeDoesNotEraseAnotherBuildingsAcceptedSchedule() = runBlocking {
        val cache = Cache(); previous(cache)
        val accepted = requireNotNull(repo(cache, ScheduleTransport { error("No request") }).cached(parish).publication)
        val repository = repo(cache, ScheduleTransport { response(it, calendar(entries = { "СЛУЖБ НЕТ" }, before = "<h2>В Сергиевском храме</h2>")) })
        val result = repository.refresh(parish, now, force = true)
        assertEquals(accepted, result.publication)
        assertTrue(cache.safetyWrites.isEmpty())
        assertFalse(result.sourceHasWithdrawals)
    }

    @Test fun winningEventsSourceAndBothFlagsAreChosenTogether() = runBlocking {
        for (inferredEarlier in listOf(false, true)) {
            val cache = Cache()
            val repository = repo(cache, ScheduleTransport { requested -> when (requested) {
                site -> response(site, "<a href='/schedule-data/'>Расписание</a><a href='/schedule-printed/'>Расписание</a>")
                site + "schedule-data/" -> response(requested, calendar(entries = {
                    if (it == 21) "09:00 — Литургия\n09:00 — Бдение" else if (inferredEarlier) "08:00 — Литургия" else "09:00 — Литургия"
                }))
                site + "schedule-printed/" -> response(requested, "<div data-day='2026-11-16'><li><time>${if (inferredEarlier) "09:00" else "08:00"}</time><span>Литургия</span></li></div>")
                else -> throw IOException("Unmapped")
            } })
            val state = repository.refresh(parish, now, sourceOverride = site)
            val publication = requireNotNull(state.publication)
            // Explicit dates retain the existing priority over an inferred candidate.
            // When both are viable, this gate must not OR the inferred candidate's diagnostics into the printed winner.
            assertFalse(publication.yearInferred)
            assertFalse(publication.knownPartial)
            assertEquals(site + "schedule-printed/", publication.sourceUrl)
            assertEquals(publication, repository.cached(parish).publication)
        }
    }

    @Test fun inferredAndPartialPublicationRoundtripsAndOfflineNeverAssignsTheNewYear() = runBlocking {
        val cache = Cache()
        val repository = repo(cache, ScheduleTransport { response(it, calendar(entries = { if (it == 21) "09:00 — Бдение" else "09:00 — Литургия" })) })
        val initial = repository.refresh(parish, now, sourceOverride = site)
        val publication = requireNotNull(initial.publication)
        assertTrue(publication.yearInferred && publication.knownPartial)
        assertEquals(publication, ScheduleCacheCodec.decode(cache.read(parish.id), parish)?.publication)
        val offline = repo(cache, ScheduleTransport { throw IOException("Offline") })
        assertEquals(publication, offline.cached(parish).publication)
        val nextYear = offline.refresh(parish, Instant.parse("2027-11-16T06:00:00Z"), force = true)
        assertEquals(publication, nextYear.publication)
        assertTrue(nextYear.publication!!.events.all { it.date.startsWith("2026-") })
        assertEquals(publication.fetchedAt, nextYear.publication!!.fetchedAt)
    }
    @Test fun unsafeCurrentBlockPersistsReadableNegativeAsUnresolvedBeforeAnOfflineRetry() = runBlocking {
        val cache = Cache(); previous(cache)
        var persisted = false
        cache.onSafety = { value ->
            val state = requireNotNull(ScheduleCacheCodec.decode(value, parish))
            assertNull(state.publication)
            assertTrue(state.sourceHasWithdrawals && state.publicationRejected)
            persisted = true
        }
        val unsafe = calendar(entries = { if (it == 16) "СЛУЖБ НЕТ" else "09:00 — Литургия" })
            .replace("Память святого", "Память\u202E святого")
        val parsed = ScheduleHtmlParser.inspect(Jsoup.parse(unsafe, site), LocalDate.of(2026, 11, 16), parish)
        assertTrue(parsed.safetyConflict && parsed.unresolvedWithdrawal)
        assertTrue(parsed.withdrawnDates.isEmpty() && parsed.withdrawnStarts.isEmpty())
        assertFalse(parsed.withdrawsAll || parsed.yearInferred)
        val repository = repo(cache, ScheduleTransport { response(it, unsafe) })
        val result = repository.refresh(parish, now, force = true)
        assertTrue(persisted)
        assertNull(result.publication)
        val offline = repo(cache, ScheduleTransport { throw IOException("Controlled I/O after the safety observation") })
        assertNull(offline.cached(parish).publication)
        assertNull(offline.refresh(parish, now.plusSeconds(60), force = true).publication)
    }

    @Test fun unsafeNegativeKeepsExplicitArchiveAndPositiveForeignExclusions() = runBlocking {
        for (foreign in listOf(false, true)) {
            val cache = Cache(); previous(cache)
            val accepted = requireNotNull(repo(cache, ScheduleTransport { error("No network") }).cached(parish).publication)
            val unsafe = calendar(entries = { if (it == 16) "СЛУЖБ НЕТ" else "09:00 — Литургия" },
                before = if (foreign) "<h2>  В  Сергиевском  храме </h2>" else "<h2>Расписание 2025</h2>")
                .replace("Память святого", "Память\u202E святого")
            val result = repo(cache, ScheduleTransport { response(it, unsafe) }).refresh(parish, now, force = true)
            assertEquals(accepted, result.publication)
            assertTrue(cache.safetyWrites.isEmpty())
            assertFalse(result.sourceHasWithdrawals)
        }
    }

}
