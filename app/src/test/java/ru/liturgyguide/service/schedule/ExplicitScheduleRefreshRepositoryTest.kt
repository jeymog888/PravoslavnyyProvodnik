package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Captured cache shapes through the real repository/resolver; all HTTP below is controlled. */
class ExplicitScheduleRefreshRepositoryTest {
    private val selected = SavedChurchRecord(
        id = "osm:way:155168069", name = "Архиерейское подворье. Храм в честь Вознесения Господня",
        city = "", address = "", timezone = "Europe/Moscow", latitude = 54.5305961, longitude = 36.3084198,
        origin = SavedChurchOrigin.OPEN_STREET_MAP,
        sourceUrl = "https://www.openstreetmap.org/way/155168069").asParish()
    private val identity = "325fcb99005144fc0cd8dd3ec9f8d08b3bbc2b5b2db24e5544fb11b2a0496eee"
    private val now = Instant.parse("2026-11-14T21:33:00Z")
    private val osmUrl = "https://api.openstreetmap.org/api/0.6/way/155168069.json"
    private val entityUrl = "https://www.wikidata.org/wiki/Special:EntityData/Q42.json"
    private val evidenceUrl = "https://www.wikidata.org/wiki/Q42"
    private val strongSource = "https://strong-refresh.example/"
    private val service = InternetService("2026-11-15", "09:00", "Литургия", "liturgy")

    private class Cache(initial: String) : ScheduleCacheStore {
        val raw = AtomicReference(initial)
        val writes = AtomicInteger()
        val safetyWrites = AtomicInteger()
        override fun read(parishId: String): String = raw.get()
        override fun write(parishId: String, value: String) { raw.set(value); writes.incrementAndGet() }
        override fun writeSafety(parishId: String, value: String) { safetyWrites.incrementAndGet(); write(parishId, value) }
    }

    private fun captured(raw: String): InternetScheduleState {
        assertEquals("Use the actual saved selection, including its blank city/address", identity,
            ScheduleEntityIdentity.fingerprint(selected))
        return requireNotNull(ScheduleCacheCodec.decode(raw, selected)).also { before ->
            assertEquals(before, ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(before), selected))
            assertTrue(before.checkedAt.isNotBlank())
            assertTrue("The explicit retry must bypass the ordinary five-minute failure throttle",
                java.time.Duration.between(Instant.parse(before.checkedAt), now).seconds in 1L..299L)
            assertNull(before.publication); assertNull(before.siteBinding)
            assertEquals(InternetScheduleStatus.UNAVAILABLE, before.status)
        }
    }
    private fun emptyOffer() = captured(CAPTURED_EMPTY)
    private fun retainedMap() = captured(CAPTURED_RETAINED_MAP).also { before ->
        assertEquals(1, before.sourceCandidates.size)
        assertTrue(before.sourceCandidates.single().canConfirm(selected))
        assertEquals(OfficialMapCandidateProvider.PROVIDER, before.sourceCandidates.single().provider)
        assertTrue(permitsOtherSourceSearch(selected, before))
    }

    private inner class Replay(before: InternetScheduleState) {
        val cache = Cache(ScheduleCacheCodec.encode(before))
        val calls = CopyOnWriteArrayList<String>()
        val candidateBatches = AtomicInteger()
        private val actionableMap = retainedMap().sourceCandidates.single()
        private val transport = ScheduleTransport { url ->
            calls += url
            when (url) {
                osmUrl -> ScheduleResponse(url,
                    """{"elements":[{"type":"way","id":155168069,"tags":{"wikidata":"Q42"}}]}""".toByteArray(), "application/json")
                entityUrl -> ScheduleResponse(url,
                    """{"entities":{"Q42":{"claims":{"P856":[{"rank":"normal","mainsnak":{"datavalue":{"value":"$strongSource"}}}]}}}}""".toByteArray(), "application/json")
                strongSource -> ScheduleResponse(url,
                    """<html><body><article data-day="2026-11-15"><li><time>09:00</time><span>Литургия</span></li></article></body></html>""".toByteArray(), "text/html; charset=UTF-8")
                else -> error("Unexpected GET, including the unconfirmed Map hint: $url")
            }
        }
        private val resolver = OpenScheduleSiteResolver(transport, candidateProvider = ScheduleCandidateProvider { parish, excluded ->
            assertEquals(identity, ScheduleEntityIdentity.fingerprint(parish)); assertTrue(excluded.isEmpty())
            candidateBatches.incrementAndGet()
            // If a completed retry wrongly re-enters initialFastDiscovery, this actionable
            // unconfirmed Map stops it before Q42 and causes the READY/call assertions to fail.
            ScheduleCandidateDiscovery(listOf(actionableMap), listOf("official_map:20385"), incomplete = true)
        })
        val repository = InternetScheduleRepository(transport, cache, siteResolver = resolver)
        fun assertImported(result: InternetScheduleState) {
            assertEquals(listOf(osmUrl, entityUrl, strongSource), calls.toList())
            assertEquals("Completed refresh must not run the cold candidate prelude", 0, candidateBatches.get())
            assertFalse(calls.contains(actionableMap.sourceUrl))
            assertEquals(InternetScheduleStatus.READY, result.status); assertFalse(result.loading)
            assertEquals(now.toString(), result.checkedAt)
            assertEquals(strongSource, result.website); assertEquals(strongSource, result.sourceUrl)
            assertEquals(evidenceUrl, result.siteEvidenceUrl)
            val binding = requireNotNull(result.siteBinding)
            assertEquals(ScheduleBindingMethod.EXACT_MAP_LINK, binding.method)
            assertTrue(binding.validFor(selected, strongSource, evidenceUrl))
            assertNull(binding.candidateConfirmation)
            val publication = requireNotNull(result.publication)
            assertEquals(listOf(service), publication.events); assertTrue(publication.hasCurrentParserRevision)
            assertEquals(strongSource, publication.website); assertEquals(now.toString(), publication.fetchedAt)
            assertFalse(result.publicationRejected); assertFalse(result.sourceHasWithdrawals)
            assertFalse(result.sourceNeedsVerification); assertEquals(ScheduleSourceChangeBlock.NONE, result.sourceChangeBlock)
            assertEquals(0, cache.safetyWrites.get()); assertTrue(cache.writes.get() > 0)
            assertEquals(result, repository.cached(selected))
            val recreated = InternetScheduleRepository(ScheduleTransport { error("Cache recreation must not GET $it") }, cache)
            assertEquals(result, recreated.cached(selected))
            assertNull(repository.takeSourceContinuation(selected, now, result, Any()))
            assertEquals(ScheduleImportPhase.FINISHED, repository.progress.value.phase)
        }
    }

    @Test fun completedCapturedEmptyOfferForcedRefreshReachesFullResolverAndImportsStrongSource() = runBlocking<Unit> {
        val before = emptyOffer(); assertTrue(before.sourceCandidates.isEmpty())
        assertFalse(permitsOtherSourceSearch(selected, before))
        val replay = Replay(before)
        val result = replay.repository.refresh(selected, now, force = true)
        replay.assertImported(result)
        assertEquals(before.dismissedCandidateIds, result.dismissedCandidateIds)
    }

    @Test fun completedCapturedRetainedMapForcedRefreshDoesNotStopAtTheSameOfferAgain() = runBlocking<Unit> {
        val before = retainedMap(); val replay = Replay(before)
        val result = replay.repository.refresh(selected, now, force = true)
        replay.assertImported(result)
        assertEquals("The retained Map remains metadata, never the accepted authority", before.sourceCandidates, result.sourceCandidates)
        assertEquals(before.dismissedCandidateIds, result.dismissedCandidateIds)
    }

    @Test fun explicitOtherSourceSearchFromRetainedMapChangesOnlyMetadataAndNeverGetsAnySchedule() = runBlocking<Unit> {
        val before = retainedMap(); val cache = Cache(ScheduleCacheCodec.encode(before))
        val calls = CopyOnWriteArrayList<String>(); val batches = AtomicInteger()
        val updated = before.sourceCandidates.single().copy(checkedAt = now.toString(), reason = "Controlled fresh metadata diagnostic")
        val transport = ScheduleTransport { url -> calls += url; error("Metadata search must not GET OSM, Q42 or a source: $url") }
        val resolver = OpenScheduleSiteResolver(transport, candidateProvider = ScheduleCandidateProvider { parish, exclusions ->
            assertEquals(identity, ScheduleEntityIdentity.fingerprint(parish)); assertTrue(exclusions.isEmpty())
            batches.incrementAndGet(); ScheduleCandidateDiscovery(listOf(updated), listOf("controlled-metadata"), incomplete = false)
        })
        val repository = InternetScheduleRepository(transport, cache, siteResolver = resolver)
        val result = repository.searchOtherSources(selected, now)
        assertEquals(1, batches.get()); assertTrue(calls.isEmpty())
        assertEquals(before.withoutSearchMetadata(), result.withoutSearchMetadata())
        assertEquals(before.checkedAt, result.checkedAt); assertEquals(before.status, result.status)
        assertEquals(updated, result.sourceCandidates.single()); assertEquals(listOf("controlled-metadata"), result.candidateObservedIds)
        assertFalse(result.candidateSearchIncomplete); assertFalse(result.candidateSearchUnavailable)
        assertNull(result.publication); assertNull(result.siteBinding); assertEquals(0, cache.safetyWrites.get())
        assertEquals(result, repository.cached(selected))
        val recreated = InternetScheduleRepository(transport, cache)
        assertEquals(result, recreated.cached(selected)); assertTrue(calls.isEmpty())
        assertNull(repository.takeSourceContinuation(selected, now, result, Any()))
    }

    @Test fun emptyOfferCompatibilitySearchFallsThroughToNormalRefreshRatherThanAnotherFastMapStop() = runBlocking<Unit> {
        val before = emptyOffer(); val replay = Replay(before)
        val result = replay.repository.searchOtherSources(selected, now)
        replay.assertImported(result)
    }

    companion object {
        // Exact scheduleBeforeUiRefresh, receipt.json SHA 46ca277abadf4055332d4327c706b9cc0132d72fba153618536d4e49da935835
        // .local/release12/integration/main-fresh-final/live-export/receipt.json
        private val CAPTURED_EMPTY = """
            {
              "candidateObservedIds": [],
              "candidateSearchIdentity": "325fcb99005144fc0cd8dd3ec9f8d08b3bbc2b5b2db24e5544fb11b2a0496eee",
              "candidateSearchIncomplete": true,
              "candidateSearchUnavailable": true,
              "checkedAt": "2026-11-14T21:32:11.335915Z",
              "configuredWebsite": "",
              "customSource": "",
              "dismissedCandidateIds": [],
              "loading": false,
              "parishId": "osm:way:155168069",
              "publication": null,
              "publicationRejected": false,
              "siteBinding": null,
              "siteEvidenceUrl": "",
              "sourceCandidates": [],
              "sourceChangeBlock": "NONE",
              "sourceHasWithdrawals": false,
              "sourceNeedsVerification": false,
              "sourceUrl": "",
              "status": "UNAVAILABLE",
              "website": ""
            }
        """.trimIndent()
        // Exact persisted JSON value, checkpoint XML SHA 95ae7b021db3569082ddf005ffe168a40c68cb21af17441f70cae9e4b45eef22
        // .local/release12/integration/main-fresh-final/live-schedule-cache-checkpoint.xml
        private val CAPTURED_RETAINED_MAP = """
            {
              "candidateObservedIds": [
                "official_map:20385"
              ],
              "candidateSearchIdentity": "325fcb99005144fc0cd8dd3ec9f8d08b3bbc2b5b2db24e5544fb11b2a0496eee",
              "candidateSearchIncomplete": true,
              "candidateSearchUnavailable": true,
              "checkedAt": "2026-11-14T21:32:34.916830Z",
              "configuredWebsite": "",
              "customSource": "",
              "dismissedCandidateIds": [],
              "loading": false,
              "parishId": "osm:way:155168069",
              "publicationRejected": false,
              "siteEvidenceUrl": "",
              "sourceCandidates": [
                {
                  "address": "Россия, г. Калуга, мкр. Тайфун, ул. Молодежная",
                  "checkedAt": "2026-11-14T21:32:36.392131Z",
                  "currentContactAddress": "",
                  "currentContactSha256": "",
                  "currentContactUrl": "",
                  "evidenceSha256": "806ab496719f2e184ae526a0feaf1ba36e91dc3caf3dc3bd70a96edc8fb7173c",
                  "evidenceUrl": "https://map.patriarchia.ru/?infoid=20385",
                  "latitude": 54.5307148,
                  "linkedEmail": "",
                  "longitude": 36.3086205,
                  "name": "Храм Вознесения Господня г. Калуга",
                  "outcome": "INSUFFICIENT",
                  "provider": "official_map",
                  "providerLabel": "Официальная карта храмов",
                  "providerObjectId": "20385",
                  "reason": "Проверьте, что на этом сайте опубликовано расписание выбранного храма",
                  "selectedIdentity": "325fcb99005144fc0cd8dd3ec9f8d08b3bbc2b5b2db24e5544fb11b2a0496eee",
                  "sourceUrl": "http://kalujskoe-19.cerkov.ru/",
                  "verificationUnavailable": false,
                  "version": 1
                }
              ],
              "sourceChangeBlock": "NONE",
              "sourceHasWithdrawals": false,
              "sourceNeedsVerification": false,
              "sourceUrl": "",
              "status": "UNAVAILABLE",
              "website": ""
            }
        """.trimIndent()
    }
}
