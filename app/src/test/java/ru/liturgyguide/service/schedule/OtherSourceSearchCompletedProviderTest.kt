package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** M2: the /contacts Map DTO is validator-valid controlled input, not a claim about the saved actual Map run. */
class OtherSourceSearchCompletedProviderTest {
    private val f = ContactWiringFixtures
    private class Cache : ScheduleCacheStore {
        val raw = AtomicReference<String?>()
        val safety = CopyOnWriteArrayList<String>()
        var onSafety: (() -> Unit)? = null
        override fun read(parishId: String): String? = raw.get()
        override fun write(parishId: String, value: String) { raw.set(value) }
        override fun writeSafety(parishId: String, value: String) {
            safety += value; write(parishId, value); onSafety?.invoke()
        }
    }
    private inner class Fixture(val otherPath: Boolean = false, val unverified: Boolean = false) {
        val selected = f.selected().copy(phone = "8-999-111-22-33")
        val map = f.mapCandidate(f.contact).copy(provider = OfficialMapCandidateProvider.PROVIDER)
        val dismissed = f.mapCandidate("https://unrelated.example/old/").copy(provider = OfficialMapCandidateProvider.PROVIDER)
        val cache = Cache()
        val mapHeld = AtomicBoolean(false)
        val mapEntered = CompletableDeferred<Unit>()
        val releaseMap = CompletableDeferred<Unit>()
        val ownCompleted = CompletableDeferred<Unit>()
        val ownBatches = AtomicInteger(); val mapBatches = AtomicInteger(); val successfulContactBodies = AtomicInteger()
        private val failFirstRoot = AtomicBoolean(true)
        val ownUrl = if (otherPath) f.home + "other/contacts" else f.contact
        private val network = ScheduleTransport { url ->
            if (url == f.contact && failFirstRoot.compareAndSet(true, false))
                throw IOException("The first accepted Map source fetch failed before any body was observed")
            check(url == ownUrl) { "No unrelated import in this closed source replay" }
            if (mapHeld.get()) mapEntered.await()
            successfulContactBodies.incrementAndGet()
            f.response(url) // complete unchanged actual contact HTML, including child phone9153464752
        }
        private val maps = ScheduleCandidateProvider { _, _ ->
            if (mapHeld.get()) { mapEntered.complete(Unit); releaseMap.await() }
            ScheduleCandidateDiscovery(listOf(map), listOf("controlled-positive-map"), incomplete = false)
        }
        private val production = OpenScheduleSiteResolver(network, candidateProvider = maps,
            ownPools = f.pools(listOf(f.seed(ownUrl, f.home))), clock = f.clock)
        private fun adjusted(found: ScheduleCandidateDiscovery): ScheduleCandidateDiscovery = if (!unverified) found else
            found.copy(candidates = found.candidates.map { row ->
                // Controlled operational negative: production Own still supplies the exact
                // body/name/point/phone conflict. This flag is not attributed to live HTTP.
                if (row.provider == OwnContactCandidateProvider.PROVIDER) row.copy(verificationUnavailable = true) else row
            })
        private val recorded = object : ScheduleSiteResolver by production {
            override fun forRefresh(): ScheduleSiteResolver {
                val session = production.forRefresh()
                return object : ScheduleSiteResolver by session {
                    override suspend fun candidatesForSourceSearch(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>,
                        useful: (ScheduleCandidateDiscovery) -> Boolean,
                        onProviderCompleted: (ScheduleCandidateDiscovery) -> Unit): ScheduleCandidateDiscovery {
                        val found = session.candidatesForSourceSearch(parish, excludedUrls, excludedCandidateIds, useful) { batch ->
                            val own = batch.candidates.any { it.provider == OwnContactCandidateProvider.PROVIDER }
                            if (own) ownBatches.incrementAndGet() else mapBatches.incrementAndGet()
                            onProviderCompleted(adjusted(batch))
                            if (own) ownCompleted.complete(Unit)
                        }
                        return adjusted(found)
                    }
                }
            }
        }
        val repository = InternetScheduleRepository(network, cache, siteResolver = recorded, curatedPublishers = { f.owners })
        init {
            assertTrue(OwnContactChildIdentity.ordinaryOsm(selected)); assertTrue(map.canConfirm(selected))
            cache.write(selected.id, ScheduleCacheCodec.encode(InternetScheduleState(selected.id,
                status = InternetScheduleStatus.NO_WEBSITE, checkedAt = f.now.minusSeconds(300).toString(),
                sourceCandidates = listOf(map, dismissed), candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(selected),
                candidateObservedIds = listOf("retained-before-manual"), dismissedCandidateIds = listOf(dismissed.id))))
        }
        suspend fun acceptedMapAfterARealFailedFetch(): InternetScheduleState {
            val failed = repository.confirmCandidate(selected, map.id, f.now)
            assertNull(failed.publication); assertEquals(0, successfulContactBodies.get()); assertTrue(cache.safety.isEmpty())
            assertEquals(map.id, failed.siteBinding?.candidateConfirmation?.candidate?.id)
            assertTrue(requireNotNull(failed.siteBinding).validFor(selected, failed.website, failed.siteEvidenceUrl))
            assertTrue(permitsOtherSourceSearch(selected, failed))
            assertTrue(dismissed.id in failed.dismissedCandidateIds)
            mapHeld.set(true)
            return failed
        }
        fun cold() = requireNotNull(ScheduleCacheCodec.decode(cache.read(selected.id), selected))
    }

    @Test fun completedActualOwnConflictAgainstAcceptedMapIsDurableBeforeTheHeldMapAndImmediateCancellation() = runBlocking<Unit> {
        val fixture = Fixture(); val before = fixture.acceptedMapAfterARealFailedFetch()
        lateinit var task: Job
        fixture.cache.onSafety = { task.cancel() }
        task = launch(start = CoroutineStart.LAZY) { fixture.repository.searchOtherSources(fixture.selected, f.now.plusSeconds(1)) }
        try {
            task.start(); withTimeout(5_000) { task.join() }
            assertTrue(task.isCancelled); assertTrue(fixture.mapEntered.isCompleted); assertFalse(fixture.releaseMap.isCompleted)
            assertTrue(fixture.ownCompleted.isCompleted); assertEquals(1, fixture.ownBatches.get()); assertEquals(0, fixture.mapBatches.get())
            assertEquals(1, fixture.successfulContactBodies.get()); assertEquals(1, fixture.cache.safety.size)
            val after = fixture.cold()
            assertNull(after.siteBinding); assertNull(after.publication)
            assertEquals(InternetScheduleStatus.UNAVAILABLE, after.status)
            assertTrue(after.publicationRejected); assertTrue(after.sourceNeedsVerification)
            assertEquals(ScheduleSourceChangeBlock.CONFLICT, after.sourceChangeBlock)
            assertEquals(before.website, after.website); assertEquals(before.sourceUrl, after.sourceUrl)
            assertEquals(before.siteEvidenceUrl, after.siteEvidenceUrl); assertEquals(before.configuredWebsite, after.configuredWebsite)
            assertEquals(before.customSource, after.customSource); assertEquals(before.dismissedCandidateIds, after.dismissedCandidateIds)
            assertEquals(before.sourceHasWithdrawals, after.sourceHasWithdrawals); assertFalse(after.sourceHasWithdrawals)
            val negative = after.sourceCandidates.single { it.provider == OwnContactCandidateProvider.PROVIDER }
            assertTrue(negative.validFor(fixture.selected)); assertEquals(ScheduleIdentityOutcome.CONFLICT, negative.outcome)
            assertFalse(negative.verificationUnavailable); assertEquals("9153464752", negative.ownContact?.publishedPhone)
            assertNotEquals(fixture.map.id, negative.id); assertEquals(fixture.map.sourceUrl, negative.sourceUrl)
            assertTrue(candidateConflictsWithAcceptedSource(negative, before.siteBinding, before.publication))
        } finally {
            fixture.releaseMap.complete(Unit)
            if (task.isActive) task.cancelAndJoin()
        }
    }

    @Test fun completedOwnDifferentPathOrUnverifiedConflictDoesNotRevokeTheAcceptedMap() = runBlocking<Unit> {
        for (otherPath in listOf(true, false)) {
            val fixture = Fixture(otherPath = otherPath, unverified = !otherPath)
            val before = fixture.acceptedMapAfterARealFailedFetch(); val original = fixture.cache.raw.get()
            val pending = async { fixture.repository.searchOtherSources(fixture.selected, f.now.plusSeconds(1)) }
            try {
                withTimeout(5_000) { fixture.ownCompleted.await() }
                assertTrue(pending.isActive); assertTrue(fixture.mapEntered.isCompleted); assertFalse(fixture.releaseMap.isCompleted)
                assertEquals(original, fixture.cache.raw.get()); assertTrue(fixture.cache.safety.isEmpty())
                fixture.releaseMap.complete(Unit)
                val after = withTimeout(5_000) { pending.await() }
                assertEquals(before.withoutSearchMetadata(), after.withoutSearchMetadata())
                assertEquals(before.siteBinding, fixture.cold().siteBinding); assertNull(after.publication)
                assertFalse(after.sourceNeedsVerification); assertFalse(after.publicationRejected)
                assertTrue(fixture.cache.safety.isEmpty()); assertEquals(1, fixture.successfulContactBodies.get())
                val negative = after.sourceCandidates.single { it.provider == OwnContactCandidateProvider.PROVIDER }
                assertEquals(ScheduleIdentityOutcome.CONFLICT, negative.outcome); assertTrue(negative.validFor(fixture.selected))
                if (otherPath) {
                    assertNotEquals(fixture.map.sourceUrl, negative.sourceUrl)
                    assertFalse(sameCandidateSource(fixture.map.sourceUrl, negative.sourceUrl))
                } else assertTrue(negative.verificationUnavailable)
                assertFalse(candidateConflictsWithAcceptedSource(negative, before.siteBinding, before.publication))
            } finally {
                fixture.releaseMap.complete(Unit)
                if (pending.isActive) pending.cancelAndJoin()
            }
        }
    }
}
