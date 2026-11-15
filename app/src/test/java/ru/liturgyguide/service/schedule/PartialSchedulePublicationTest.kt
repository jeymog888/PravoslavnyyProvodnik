package ru.liturgyguide.service.schedule

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant

class PartialSchedulePublicationTest {
    private val now = Instant.parse("2026-11-15T16:35:59Z")
    private val site = "https://calendar.example/"
    private val parish = Parish("custom:calendar", "Никольский храм", "Никольский храм", "", "", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
    private class Cache : ScheduleCacheStore {
        val data = mutableMapOf<String, String>()
        override fun read(parishId: String) = data[parishId]
        override fun write(parishId: String, value: String) { data[parishId] = value }
    }
    private fun calendar(time: String = "09:00", partial: Boolean = false, onlyUnknown: Boolean = false) =
        "<article><h2>Расписание богослужений в ноябре 2026 года</h2><table>" +
            (if (onlyUnknown) "" else "<tr><td>Сб.</td><td>21</td><td>Память святого</td><td>$time</td><td>Литургия</td></tr>") +
            (if (partial || onlyUnknown) "<tr><td>Сб.</td><td>21</td><td>Память святого</td><td>16:00</td><td>Бдение</td></tr>" else "") + "</table></article>"
    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(), "text/html; charset=UTF-8")
    private fun repo(cache: Cache, transport: ScheduleTransport) = InternetScheduleRepository(transport, cache,
        siteResolver = ScheduleSiteResolver { null })
    private fun state(partial: Boolean = true) = InternetScheduleState(parish.id, InternetScheduleStatus.READY,
        website = site, sourceUrl = site, checkedAt = now.toString(), customSource = site,
        publication = SchedulePublication(parish.id, parish.timezone, site, site, now.toString(),
            listOf(InternetService("2026-11-21", "09:00", "Литургия", "liturgy")), knownPartial = partial))

    @Test fun knownPartialBooleanRoundtripsAndLegacyReadDoesNotRewriteTheStoredBytes() {
        val original = state()
        assertEquals(original, ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(original), parish))
        val json = JsonParser.parseString(ScheduleCacheCodec.encode(original)).asJsonObject
        json.getAsJsonObject("publication").remove("knownPartial")
        val oldBytes = json.toString()
        val cache = Cache().apply { data[parish.id] = oldBytes }
        val read = repo(cache, ScheduleTransport { error("No network during read") }).cached(parish)
        assertFalse(requireNotNull(read.publication).knownPartial)
        assertEquals(oldBytes, cache.data[parish.id])
        json.getAsJsonObject("publication").add("knownPartial", JsonNull.INSTANCE)
        assertFalse(requireNotNull(ScheduleCacheCodec.decode(json.toString(), parish)?.publication).knownPartial)
    }

    @Test fun presentNonBooleanPartialIsNeverCoercedAndForeignPublicationRemainsRejected() {
        for (value in listOf("\"true\"", "1", "{}", "[]")) {
            val json = JsonParser.parseString(ScheduleCacheCodec.encode(state())).asJsonObject
            json.getAsJsonObject("publication").add("knownPartial", JsonParser.parseString(value))
            assertNull(value, ScheduleCacheCodec.decode(json.toString(), parish))
        }
        val wrongOwner = state().copy(publication = state().publication!!.copy(parishId = "other"))
        assertNull(ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(wrongOwner), parish))
        val wrongSource = state().copy(publication = state().publication!!.copy(sourceUrl = "https://other.example/schedule/"))
        assertNull(ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(wrongSource), parish))
    }

    @Test fun winnerChoosesEventsSourceAndPartialTogetherRatherThanOrAcrossVisitedPages() = runBlocking {
        for (partialEarlier in listOf(false, true)) {
            val cache = Cache()
            val partialTime = if (partialEarlier) "08:00" else "09:00"
            val fullTime = if (partialEarlier) "09:00" else "08:00"
            val repository = repo(cache, ScheduleTransport { url -> when (url) {
                site -> response(url, "<a href='/schedule-partial/'>Расписание</a><a href='/schedule-full/'>Расписание</a>")
                site + "schedule-partial/" -> response(url, calendar(partialTime, partial = true))
                site + "schedule-full/" -> response(url, calendar(fullTime))
                else -> throw IOException("Controlled unmapped request")
            } })
            val loaded = repository.refresh(parish, now, sourceOverride = site)
            val publication = requireNotNull(loaded.publication)
            assertEquals(InternetScheduleStatus.READY, loaded.status)
            assertEquals("08:00", publication.events.single().time)
            assertEquals(partialEarlier, publication.knownPartial)
            assertEquals(site + if (partialEarlier) "schedule-partial/" else "schedule-full/", publication.sourceUrl)
            assertEquals(publication, repository.cached(parish).publication)
        }
    }

    @Test fun failedRefreshAndNewRepositoryKeepTheSamePublicationAndItsDiagnostic() = runBlocking {
        val cache = Cache()
        val repository = repo(cache, ScheduleTransport { response(it, calendar(partial = true)) })
        val loaded = repository.refresh(parish, now, sourceOverride = site)
        val accepted = requireNotNull(loaded.publication)
        assertTrue(accepted.knownPartial)
        val offline = repo(cache, ScheduleTransport { throw IOException("Controlled offline") })
        assertEquals(accepted, offline.cached(parish).publication)
        val failed = offline.refresh(parish, now.plusSeconds(60), force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
        assertEquals(accepted, failed.publication)
        assertTrue(parish.withInternetSchedule(failed, now).scheduleKnownPartial)
        assertEquals(accepted.sourceUrl, parish.withInternetSchedule(failed, now).sourceUrl)
    }

    @Test fun zeroEventPartialDoesNotCreateReadyWithdrawalOrReplaceAnAcceptedEdition() = runBlocking {
        val cache = Cache()
        var html = calendar()
        val repository = repo(cache, ScheduleTransport { response(it, html) })
        val loaded = repository.refresh(parish, now, sourceOverride = site)
        val accepted = requireNotNull(loaded.publication)
        assertFalse(accepted.knownPartial)
        html = calendar(onlyUnknown = true)
        val standalone = ScheduleHtmlParser.inspect(requireNotNull(response(site, html).html()), now.atZone(java.time.ZoneId.of(parish.timezone)).toLocalDate(), parish)
        assertTrue(standalone.knownPartial)
        assertTrue(standalone.events.isEmpty())
        assertFalse(standalone.safetyConflict || standalone.withdrawsAll || standalone.unresolvedWithdrawal)
        val refreshed = repository.refresh(parish, now.plusSeconds(60), force = true)
        assertEquals(accepted, refreshed.publication)
        assertFalse(refreshed.publicationRejected)
        assertFalse(refreshed.sourceHasWithdrawals)
        val emptyCache = Cache()
        val fresh = repo(emptyCache, ScheduleTransport { response(it, html) }).refresh(parish, now, sourceOverride = site)
        assertNull(fresh.publication)
        assertNotEquals(InternetScheduleStatus.READY, fresh.status)
        assertFalse(fresh.sourceHasWithdrawals)
    }

    @Test fun captionAndBrCancellationPersistBeforeFollowingIoAndNeverReviveTheWithdrawnStart() = runBlocking {
        fun sourceRow(day: Int, weekday: String, time: String) = "<tr><td>$weekday</td><td>$day</td><td>Память святого</td><td>$time</td><td>Литургия</td></tr>"
        val neighbors = sourceRow(21, "Сб.", "10:00") + sourceRow(26, "Чт.", "10:00")
        val expected = listOf(InternetService("2026-11-21", "10:00", "Литургия", "liturgy"),
            InternetService("2026-11-26", "10:00", "Литургия", "liturgy"))
        for (scope in listOf("<caption>Все богослужения отменены</caption>", "<p>Все богослужения не<br>состоятся</p>")) {
            val cache = Cache()
            var changed = false
            var safetySeenBeforeIo = false
            val follow = "<a href='/schedule-unavailable/'>Расписание богослужений</a>"
            val positive = calendar().replace("</table>", "$neighbors</table>") + follow
            val first = if (scope.startsWith("<caption>")) calendar().replace("<table>", "<table>$scope")
                else calendar().replace("<table>", "$scope<table>")
            // The other article/table includes a different time on the same day and another day.
            val other = calendar(time = "10:00").replace("</table>", sourceRow(26, "Чт.", "10:00") + "</table>")
            val repository = repo(cache, ScheduleTransport { url -> when (url) {
                site -> response(url, if (changed) first + other + follow else positive)
                site + "schedule-unavailable/" -> {
                    if (changed) {
                        val durable = requireNotNull(ScheduleCacheCodec.decode(cache.data[parish.id], parish))
                        assertTrue(durable.sourceHasWithdrawals)
                        assertEquals(expected, requireNotNull(durable.publication).events)
                        assertFalse(durable.publicationRejected)
                        safetySeenBeforeIo = true
                    }
                    throw IOException("Controlled failure after the source safety write")
                }
                else -> throw IOException("Controlled unmapped request")
            } })
            val prior = repository.refresh(parish, now, sourceOverride = site)
            assertEquals(3, requireNotNull(prior.publication).events.size)
            changed = true
            val corrected = repository.refresh(parish, now.plusSeconds(60), force = true)
            assertTrue(scope, safetySeenBeforeIo)
            assertTrue(corrected.sourceHasWithdrawals)
            assertEquals(expected, requireNotNull(corrected.publication).events)
            val offline = repo(cache, ScheduleTransport { throw IOException("Controlled subsequent offline refresh") })
            assertEquals(expected, requireNotNull(offline.cached(parish).publication).events)
            val failed = offline.refresh(parish, now.plusSeconds(120), force = true)
            assertEquals(expected, requireNotNull(failed.publication).events)
            assertTrue(failed.sourceHasWithdrawals)
            assertFalse(failed.publicationRejected)
        }
    }

    @Test fun projectionClearsDiagnosticTogetherWithUnverifiedOrRejectedEvents() {
        val projected = parish.withInternetSchedule(state(), now)
        assertTrue(projected.scheduleKnownPartial)
        assertEquals(1, projected.schedule.size)
        val rejected = projected.withInternetSchedule(state().copy(publication = null, publicationRejected = true), now)
        assertTrue(rejected.schedule.isEmpty())
        assertFalse(rejected.scheduleKnownPartial)
        val unverified = projected.withInternetSchedule(state().copy(sourceNeedsVerification = true), now)
        assertTrue(unverified.schedule.isEmpty())
        assertFalse(unverified.scheduleKnownPartial)
        assertEquals(parish, parish.withInternetSchedule(state().copy(parishId = "other"), now))
        assertFalse(parish.withInternetSchedule(state(partial = false), now).scheduleKnownPartial)
    }
}
