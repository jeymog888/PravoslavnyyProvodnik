package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.ui.arrivalSchedule
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class ScheduleSourceRecoveryTest {
    private val original = "https://old.example/"
    private val alternative = "https://current.example/raspisanie/"
    private val proof = "https://directory.example/church/42"
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val html = "<article data-day='2026-11-12'><li><time>08:00</time><span>Литургия</span></li></article>"
    private val parish = Parish("test:42", "Никольский храм", "Никольский храм", "Москва", "Садовая, 1", "", original,
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private class Cache : ScheduleCacheStore {
        var value: String? = null
        var writes = 0
        override fun read(parishId: String) = value
        override fun write(parishId: String, value: String) { this.value = value; writes++ }
    }
    private class Resolver(private val site: ScheduleSite?) : ScheduleSiteResolver {
        var calls = 0
        var excluded = emptySet<String>()
        override suspend fun resolve(parish: Parish): ScheduleSite? = error("Repository must pass exclusions")
        override suspend fun resolve(parish: Parish, excludedUrls: Set<String>): ScheduleSite? {
            calls++; excluded = excludedUrls; return site
        }
    }
    private fun response(url: String, body: String = html) = ScheduleResponse(url, body.toByteArray(), "text/html")
    private fun site(url: String = alternative) = ScheduleSite(url, proof, parish.name,
        ScheduleEntityIdentity.bind(parish, url, proof, parish.name, ScheduleBindingMethod.ARTICLE_SCOPE))
    private suspend fun seed(cache: Cache): InternetScheduleState = InternetScheduleRepository(ScheduleTransport { response(it) }, cache)
        .refresh(parish, now, force = true)

    @Test fun confirmedAlternativeIsAcceptedAtomicallyAndReusedAfterRepositoryRecreation() = runBlocking {
        val cache = Cache(); val resolver = Resolver(site()); val calls = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            if (url == original) throw IOException()
            response(url)
        }, cache, siteResolver = resolver)
        val first = repo.refresh(parish, now, force = true)
        assertEquals(InternetScheduleStatus.READY, first.status)
        assertEquals(listOf(original, alternative), calls)
        assertEquals(setOf(original), resolver.excluded)
        assertEquals(alternative, first.website)
        assertEquals(alternative, first.sourceUrl)
        assertEquals(original, first.configuredWebsite)
        assertEquals(proof, first.siteEvidenceUrl)
        assertEquals("", first.customSource)
        assertEquals(first, repo.cached(parish))
        val again = mutableListOf<String>(); val unused = Resolver(null)
        val restored = InternetScheduleRepository(ScheduleTransport { url -> again += url; response(url) }, cache, siteResolver = unused)
        val refreshed = restored.refresh(parish, now.plusSeconds(60), force = true)
        assertEquals(listOf(alternative), again)
        assertEquals(0, unused.calls)
        assertEquals(proof, refreshed.siteEvidenceUrl)
        assertEquals(InternetScheduleStatus.READY, refreshed.status)
        assertNull(restored.cached(parish.copy(website = "https://changed.example/")).publication)
    }

    @Test fun failedOrForeignAlternativeNeverChangesAcceptedCachedBindingOrOfflineArrival() = runBlocking {
        for (body in listOf<String?>(null, "<p>Контакты</p>", "<h1>Казанский храм</h1><p>12 ноября 2026 09:00 Литургия</p>")) {
            val cache = Cache(); val accepted = seed(cache); val resolver = Resolver(site())
            val repo = InternetScheduleRepository(ScheduleTransport { url ->
                if (url == original || body == null) throw IOException()
                response(url, body)
            }, cache, siteResolver = resolver)
            val failed = repo.refresh(parish, now.plusSeconds(60), force = true)
            assertEquals(1, resolver.calls)
            assertEquals(accepted.publication, failed.publication)
            assertEquals(accepted.website, failed.website)
            assertEquals(accepted.sourceUrl, failed.sourceUrl)
            assertEquals(accepted.siteEvidenceUrl, failed.siteEvidenceUrl)
            assertFalse(failed.publicationRejected)
            assertEquals(failed, repo.cached(parish))
            assertEquals(listOf("08:00"), arrivalSchedule(parish, LocalDate.of(2026, 11, 12), now, failed).entries.map { it.time })
        }
    }

    @Test fun unacceptedAlternativeCannotPoisonTheSourceForAParishWithoutCache() = runBlocking {
        val resolver = Resolver(site())
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            if (url == original) throw IOException()
            response(url, "<p>Контакты</p>")
        }, Cache(), siteResolver = resolver)
        val result = repo.refresh(parish, now, force = true)
        assertNull(result.publication)
        assertEquals(original, result.website)
        assertEquals(original, result.sourceUrl)
        assertEquals("", result.siteEvidenceUrl)
        assertEquals(1, resolver.calls)
    }

    @Test fun explicitAndPersistedManualSourcesNeverTriggerAutomaticDiscovery() = runBlocking {
        val cache = Cache(); val resolver = Resolver(site())
        val repo = InternetScheduleRepository(ScheduleTransport { throw IOException() }, cache, siteResolver = resolver)
        val manual = repo.refresh(parish, now, force = true, sourceOverride = "https://manual.example/schedule")
        assertEquals("https://manual.example/schedule", manual.customSource)
        val repeated = repo.refresh(parish, now.plusSeconds(60), force = true)
        assertEquals(manual.customSource, repeated.customSource)
        assertEquals(0, resolver.calls)
    }

    @Test fun primaryCancellationOrDifferentBuildingCannotBeBypassedByAnotherSource() = runBlocking {
        val bodies = listOf(
            "<h1>Ноябрь 2026</h1><h2>Все службы отменены</h2><p>12 ноября 08:00 Литургия</p>",
            "<h1>Казанский храм</h1><p>12 ноября 2026 08:00 Литургия</p>")
        for (body in bodies) {
            val cache = Cache(); seed(cache); val resolver = Resolver(site())
            val repo = InternetScheduleRepository(ScheduleTransport { response(it, body) }, cache, siteResolver = resolver)
            val rejected = repo.refresh(parish, now.plusSeconds(60), force = true)
            assertNull(rejected.publication)
            assertTrue(rejected.publicationRejected)
            assertEquals(0, resolver.calls)
            val failed = InternetScheduleRepository(ScheduleTransport { throw IOException() }, cache, siteResolver = resolver)
                .refresh(parish, now.plusSeconds(120), force = true)
            assertTrue(failed.publicationRejected)
            assertNull(failed.publication)
            assertEquals(0, resolver.calls)
        }
    }

    @Test fun realWithdrawalAtConfirmedAlternativeCannotResurrectAnOldCachedService() = runBlocking {
        val cache = Cache(); seed(cache); val resolver = Resolver(site())
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            if (url == original) throw IOException()
            response(url, "<h1>Никольский храм</h1><h2>Ноябрь 2026</h2><p>12 ноября все службы отменены</p>")
        }, cache, siteResolver = resolver)
        val result = repo.refresh(parish, now.plusSeconds(60), force = true)
        assertNull(result.publication)
        assertTrue(result.publicationRejected)
        assertEquals(alternative, result.sourceUrl)
        assertEquals(proof, result.siteEvidenceUrl)
        assertEquals(result, repo.cached(parish))
    }

    @Test fun persistedAlternativeWithdrawalNeverFallsBackToStaleConfiguredEventsButCanRecoverAtItsOwnSource() = runBlocking {
        val cache = Cache(); seed(cache)
        val cancellation = "<h1>Никольский храм</h1><h2>Ноябрь 2026</h2><p>12 ноября все службы отменены</p>"
        val rejected = InternetScheduleRepository(ScheduleTransport { url ->
            if (url == original) throw IOException()
            response(url, cancellation)
        }, cache, siteResolver = Resolver(site())).refresh(parish, now.plusSeconds(60), force = true)
        assertTrue(rejected.publicationRejected)
        assertEquals(alternative, rejected.website)
        for (unavailable in listOf(false, true)) {
            val calls = mutableListOf<String>(); val resolver = Resolver(site(original))
            val repo = InternetScheduleRepository(ScheduleTransport { url ->
                calls += url
                if (url == alternative && unavailable) throw IOException()
                response(url, if (url == alternative) cancellation else html)
            }, cache, siteResolver = resolver)
            val again = repo.refresh(parish, now.plusSeconds(if (unavailable) 180 else 120), force = true)
            assertEquals(listOf(alternative), calls)
            assertEquals(0, resolver.calls)
            assertNull(again.publication)
            assertTrue(again.publicationRejected)
            assertEquals(alternative, again.website)
            assertEquals(proof, again.siteEvidenceUrl)
        }
        val calls = mutableListOf<String>()
        val recovered = InternetScheduleRepository(ScheduleTransport { url -> calls += url; response(url) }, cache)
            .refresh(parish, now.plusSeconds(240), force = true)
        assertEquals(listOf(alternative), calls)
        assertFalse(recovered.publicationRejected)
        assertEquals(alternative, recovered.publication?.website)
        assertEquals(listOf("08:00"), recovered.publication?.events?.map { it.time })
    }

    @Test fun partialWithdrawalKeepsOtherServicesButCannotBeOverriddenByAStaleAlternativeAfterRecreation() = runBlocking {
        val cache = Cache(); seed(cache)
        val partial = "<h1>Никольский храм</h1><h2>Ноябрь 2026</h2>" +
            "<p>12 ноября все службы отменены</p><p>13 ноября 08:00 Литургия</p>"
        val accepted = InternetScheduleRepository(ScheduleTransport { url ->
            if (url == original) throw IOException()
            response(url, partial)
        }, cache, siteResolver = Resolver(site())).refresh(parish, now.plusSeconds(60), force = true)
        assertFalse(accepted.publicationRejected)
        assertTrue(accepted.sourceHasWithdrawals)
        assertEquals(listOf("2026-11-13"), accepted.publication?.events?.map { it.date })
        repeat(2) { attempt ->
            val calls = mutableListOf<String>()
            val resolver = Resolver(site("https://stale.example/schedule"))
            val repo = InternetScheduleRepository(ScheduleTransport { url ->
                calls += url
                if (url == alternative) throw IOException()
                response(url) // Stale 12 November service would pass parsing if incorrectly requested.
            }, cache, siteResolver = resolver)
            assertTrue(repo.cached(parish).sourceHasWithdrawals)
            val failed = repo.refresh(parish, now.plusSeconds(120L + attempt * 60L), force = true)
            assertEquals(listOf(alternative), calls)
            assertEquals(0, resolver.calls)
            assertTrue(failed.sourceHasWithdrawals)
            assertEquals(accepted.publication, failed.publication)
            assertTrue(arrivalSchedule(parish, LocalDate.of(2026, 11, 12), now, failed).entries.isEmpty())
            assertEquals(1, arrivalSchedule(parish, LocalDate.of(2026, 11, 13), now, failed).entries.size)
        }
        // A new valid publication at the authoritative source may explicitly restore service.
        val recovered = InternetScheduleRepository(ScheduleTransport { url ->
            assertEquals(alternative, url); response(url)
        }, cache).refresh(parish, now.plusSeconds(240), force = true)
        assertFalse(recovered.sourceHasWithdrawals)
        assertEquals(listOf("2026-11-12"), recovered.publication?.events?.map { it.date })
    }

    @Test fun missingWebsiteStateAndLegacyCacheRemainReadableWithoutWithdrawalMetadata() = runBlocking {
        val cache = Cache()
        val unknown = parish.copy(website = "")
        val repo = InternetScheduleRepository(ScheduleTransport { error("No endpoint") }, cache, siteResolver = Resolver(null))
        val empty = repo.refresh(unknown, now, force = true)
        assertEquals(empty, repo.cached(unknown))
        val accepted = seed(cache)
        val legacy = com.google.gson.JsonParser.parseString(requireNotNull(cache.value)).asJsonObject
        legacy.remove("sourceHasWithdrawals"); cache.value = legacy.toString()
        assertEquals(accepted, repo.cached(parish))
    }

    @Test fun resolverCannotReturnExcludedAliasesAndOnlyOneAlternativeIsImported() = runBlocking {
        for (candidate in listOf("http://www.old.example/#top", alternative)) {
            val resolver = Resolver(site(candidate)); val calls = mutableListOf<String>()
            val repo = InternetScheduleRepository(ScheduleTransport { url -> calls += url; throw IOException() }, Cache(), siteResolver = resolver)
            val result = repo.refresh(parish, now, force = true)
            assertNull(result.publication)
            assertEquals(1, resolver.calls)
            assertEquals(if (candidate == alternative) listOf(original, alternative) else listOf(original), calls)
        }
    }

    @Test fun redirectsAndAlreadyReadSchedulePathsAreExcludedWithoutBanningOtherPaths() = runBlocking {
        val resolver = Resolver(site("https://old.example/other-schedule"))
        val repo = InternetScheduleRepository(ScheduleTransport { url -> when (url) {
            original -> response("https://www.old.example/", "<a href='/schedule'>Расписание</a>")
            "https://www.old.example/schedule" -> response(url, "<p>Контакты</p>")
            else -> response(url)
        } }, Cache(), siteResolver = resolver)
        val result = repo.refresh(parish, now, force = true)
        assertEquals(setOf(original, "https://www.old.example/", "https://www.old.example/schedule"), resolver.excluded)
        assertEquals("https://old.example/other-schedule", result.sourceUrl)
        assertEquals(InternetScheduleStatus.READY, result.status)
    }

    @Test fun resolverFailureLeavesAcceptedCacheAndCandidateWithoutProofIsNotFetched() = runBlocking {
        for (resolver in listOf<ScheduleSiteResolver>(ScheduleSiteResolver { throw IOException() },
            ScheduleSiteResolver { ScheduleSite(alternative, "", parish.name) })) {
            val cache = Cache(); val accepted = seed(cache); val calls = mutableListOf<String>()
            val result = InternetScheduleRepository(ScheduleTransport { url -> calls += url; throw IOException() }, cache, siteResolver = resolver)
                .refresh(parish, now.plusSeconds(60), force = true)
            assertEquals(listOf(original), calls)
            assertEquals(accepted.publication, result.publication)
            assertEquals(original, result.website)
            assertEquals(original, result.sourceUrl)
        }
    }

    @Test fun cancellationPropagatesDuringBothDiscoveryAndAlternativeImportWithoutWritingPartialState() = runBlocking {
        for (duringDiscovery in listOf(true, false)) withTimeout(2_000) {
            val cache = Cache(); val accepted = seed(cache); val entered = CompletableDeferred<Unit>()
            val resolver = ScheduleSiteResolver {
                if (duringDiscovery) { entered.complete(Unit); awaitCancellation() } else site()
            }
            val repo = InternetScheduleRepository(ScheduleTransport { url ->
                if (url == original) throw IOException()
                entered.complete(Unit); awaitCancellation()
            }, cache, siteResolver = resolver)
            val job = launch { repo.refresh(parish, now.plusSeconds(60), force = true) }
            entered.await(); job.cancelAndJoin()
            assertEquals(1, cache.writes)
            assertEquals(accepted, repo.cached(parish))
        }
    }
}
