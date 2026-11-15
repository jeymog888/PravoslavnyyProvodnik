package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant

class ScheduleParserRevisionCacheTest {
    private val now = Instant.parse("2026-11-15T06:00:00Z")
    private val site = "https://example.org/"
    private val proof = "https://directory.example/church/42"
    private val parish = Parish("revision-cache", "Никольский храм", "Никольский храм", "Москва", "Адрес", "", site,
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val oldEvent = InternetService("2026-11-15", "08:00", "Литургия", "liturgy")
    private val binding = ScheduleEntityIdentity.bind(parish, site, proof, parish.name, ScheduleBindingMethod.ARTICLE_SCOPE)
    private val accepted = InternetScheduleState(parish.id, InternetScheduleStatus.READY, website = site,
        sourceUrl = site, checkedAt = now.toString(), configuredWebsite = site, siteEvidenceUrl = proof,
        siteBinding = binding, sourceHasWithdrawals = true,
        publication = SchedulePublication(parish.id, parish.timezone, site, site, now.toString(), listOf(oldEvent)))
    private class Cache(var value: String?) : ScheduleCacheStore {
        var writes = 0
        override fun read(parishId: String) = value
        override fun write(parishId: String, value: String) { this.value = value; writes++ }
    }
    private fun encoded(revisionToken: String?): String {
        val root = JsonParser.parseString(ScheduleCacheCodec.encode(accepted)).asJsonObject
        val publication = root.getAsJsonObject("publication")
        if (revisionToken == null) publication.remove("parserRevision")
        else publication.add("parserRevision", JsonParser.parseString(revisionToken))
        return root.toString()
    }
    private fun assertSourceHistoryRetained(state: InternetScheduleState) {
        assertEquals(binding, state.siteBinding)
        assertEquals(proof, state.siteEvidenceUrl)
        assertEquals(site, state.website)
        assertEquals(site, state.sourceUrl)
        assertEquals(site, state.configuredWebsite)
        assertEquals(listOf(oldEvent), state.publication?.events)
        assertTrue(state.sourceHasWithdrawals)
        assertFalse(state.sourceNeedsVerification)
        assertFalse(state.publicationRejected)
    }

    @Test fun newPublicationDefaultsCurrentAndCopyNeverPromotesAnOlderRevision() {
        val current = requireNotNull(accepted.publication)
        assertEquals(SchedulePublication.CURRENT_PARSER_REVISION, current.parserRevision)
        assertTrue(current.hasCurrentParserRevision)
        assertTrue(current.isFresh(now))
        assertTrue(current.isFresh(now.plusSeconds(24 * 3_600L)))
        assertFalse(current.isFresh(now.plusSeconds(24 * 3_600L + 1)))
        assertFalse(current.isFresh(now.minusSeconds(1)))
        for (revision in listOf(0, 1, 3, Int.MAX_VALUE)) {
            val older = current.copy(parserRevision = revision)
            assertFalse(older.hasCurrentParserRevision)
            assertFalse(older.isFresh(now))
            assertEquals(revision, older.copy(fetchedAt = now.plusSeconds(60).toString()).parserRevision)
        }
    }

    @Test fun malformedRevisionBecomesLegacyWithoutDiscardingEventsBindingOrWithdrawalHistory() {
        val malformed = listOf<String?>(null, "null", "\"1\"", "\"2\"", "true", "false", "1.0", "1e0", "2.0", "2e0", "-1", "2147483648", "[]", "{}")
        for (token in malformed) {
            val restored = requireNotNull(ScheduleCacheCodec.decode(encoded(token), parish)) { "State lost for $token" }
            assertSourceHistoryRetained(restored)
            assertEquals(SchedulePublication.LEGACY_PARSER_REVISION, restored.publication?.parserRevision)
            assertFalse(requireNotNull(restored.publication).isFresh(now))
            assertEquals(restored, ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(restored), parish))
            assertTrue(parish.withInternetSchedule(restored, now).schedule.isEmpty())
        }
        for (revision in listOf(0, 1, 2, 3, Int.MAX_VALUE)) {
            val restored = requireNotNull(ScheduleCacheCodec.decode(encoded(revision.toString()), parish))
            assertSourceHistoryRetained(restored)
            assertEquals(revision, restored.publication?.parserRevision)
            assertEquals(revision == SchedulePublication.CURRENT_PARSER_REVISION,
                requireNotNull(restored.publication).hasCurrentParserRevision)
        }
    }

    @Test fun noncurrentReadyReparsesWithinSixHoursButCurrentReadyKeepsTheShortcut() = runBlocking {
        for (revision in listOf(0, 1, 3)) {
            val cache = Cache(encoded(revision.toString()))
            var calls = 0
            val repo = InternetScheduleRepository(ScheduleTransport { url ->
                calls++
                ScheduleResponse(url, "<p>15 ноября 2026 08:30 Литургия</p>".toByteArray(), "text/html")
            }, cache)
            val previous = repo.cached(parish)
            assertSourceHistoryRetained(previous)
            val fresh = repo.refresh(parish, now.plusSeconds(60))
            assertEquals(1, calls)
            assertEquals(InternetScheduleStatus.READY, fresh.status)
            assertEquals(SchedulePublication.CURRENT_PARSER_REVISION, fresh.publication?.parserRevision)
            assertEquals(listOf("08:30"), fresh.publication?.events?.map { it.time })
            assertEquals(binding, fresh.siteBinding)
            assertFalse(fresh.sourceHasWithdrawals)
            assertEquals(fresh, repo.cached(parish))
            assertEquals(listOf("08:30"), parish.withInternetSchedule(fresh, now.plusSeconds(60)).schedule.map { it.time })
            repo.refresh(parish, now.plusSeconds(120))
            assertEquals(1, calls)
        }
        var currentCalls = 0
        val current = InternetScheduleRepository(ScheduleTransport { currentCalls++; error("Current cache is inside READY cooldown") },
            Cache(ScheduleCacheCodec.encode(accepted)))
        assertEquals(accepted, current.refresh(parish, now.plusSeconds(60)))
        assertEquals(0, currentCalls)
    }

    @Test fun failedOlderReparseKeepsHistoryAndFiveMinuteCooldownWhileExplicitRefreshStillWorks() = runBlocking {
        for (unavailable in listOf(true, false)) for (revisionToken in listOf(null, "1")) {
            val cache = Cache(encoded(revisionToken))
            var calls = 0
            val repo = InternetScheduleRepository(ScheduleTransport { url ->
                calls++
                if (unavailable) throw IOException("Offline")
                ScheduleResponse(url, "<p>Контакты</p>".toByteArray(), "text/html")
            }, cache)
            val failed = repo.refresh(parish, now.plusSeconds(60))
            assertEquals(if (unavailable) InternetScheduleStatus.UNAVAILABLE else InternetScheduleStatus.UNSUPPORTED, failed.status)
            assertEquals(1, calls)
            assertSourceHistoryRetained(failed)
            assertEquals(revisionToken?.toInt() ?: 0, failed.publication?.parserRevision)
            assertTrue(parish.withInternetSchedule(failed, now.plusSeconds(60)).schedule.isEmpty())
            assertEquals(failed, repo.cached(parish))
            repo.refresh(parish, now.plusSeconds(61))
            assertEquals(1, calls)
            val forced = repo.refresh(parish, now.plusSeconds(62), force = true)
            assertEquals(2, calls)
            assertSourceHistoryRetained(forced)
            repo.refresh(parish, now.plusSeconds(361))
            assertEquals(2, calls)
            repo.refresh(parish, now.plusSeconds(363))
            assertEquals(3, calls)
            assertSourceHistoryRetained(repo.cached(parish))
        }
    }
}
