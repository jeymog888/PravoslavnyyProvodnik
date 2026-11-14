package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OtherSourceSearchRepositoryTest {
    private val f = ContactWiringFixtures
    private val selected get() = f.selected()
    private fun map(url: String = "http://www.hram-elisaveta.ru/") =
        f.mapCandidate(url).copy(provider = OfficialMapCandidateProvider.PROVIDER)
    private fun binding(row: ScheduleSourceCandidate) = ScheduleEntityIdentity.bind(selected, row.sourceUrl,
        row.evidenceUrl, row.name, ScheduleBindingMethod.USER_CONFIRMED).copy(candidateConfirmation =
        ScheduleCandidateConfirmation(1, ScheduleCandidateConfirmation.TYPE, row.selectedIdentity, row, f.now.toString()))
    private fun initial(accepted: Boolean = false): InternetScheduleState {
        val row = map()
        return InternetScheduleState(selected.id, status = InternetScheduleStatus.NO_WEBSITE,
            website = if (accepted) row.sourceUrl else "", sourceUrl = if (accepted) row.sourceUrl else "",
            checkedAt = f.now.minusSeconds(300).toString(), siteEvidenceUrl = if (accepted) row.evidenceUrl else "",
            siteBinding = if (accepted) binding(row) else null, sourceCandidates = listOf(row),
            candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(selected), candidateObservedIds = listOf("retained-map"),
            candidateSearchIncomplete = true)
    }
    private class Cache(initial: String?) : ScheduleCacheStore {
        val value = AtomicReference(initial)
        val writes = AtomicInteger()
        val safety = CopyOnWriteArrayList<String>()
        val onRead = AtomicReference<(() -> Unit)?>(null)
        var failRead = false
        var onSafety: (() -> Unit)? = null
        override fun read(parishId: String): String? {
            if (failRead) throw IOException("Read is unavailable")
            onRead.getAndSet(null)?.invoke()
            return value.get()
        }
        override fun write(parishId: String, value: String) { this.value.set(value); writes.incrementAndGet() }
        override fun writeSafety(parishId: String, value: String) {
            safety += value; write(parishId, value); onSafety?.invoke()
        }
    }
    private fun cache(state: InternetScheduleState = initial()) = Cache(ScheduleCacheCodec.encode(state))
    private fun repo(cache: ScheduleCacheStore, resolver: ScheduleSiteResolver) = InternetScheduleRepository(
        ScheduleTransport { error("An offer must not import $it") }, cache, siteResolver = resolver)
    private fun resolver(block: suspend ((ScheduleCandidateDiscovery) -> Unit) -> ScheduleCandidateDiscovery) = object : ScheduleSiteResolver {
        override suspend fun resolve(parish: Parish): ScheduleSite? = error("No proof graph before explicit whole")
        override suspend fun candidatesForSourceSearch(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>,
            useful: (ScheduleCandidateDiscovery) -> Boolean, onProviderCompleted: (ScheduleCandidateDiscovery) -> Unit) = block(onProviderCompleted)
    }
    private fun cold(cache: Cache) = requireNotNull(ScheduleCacheCodec.decode(cache.value.get(), selected))

    @Test fun ordinaryManualSearchOnlyAddsOffersAndDiagnosticsBeforeAndAfterCodecRecreation() = runBlocking<Unit> {
        for (accepted in listOf(false, true)) {
            val before = initial(accepted); val cache = cache(before); val own = f.fresh()
            var whole = 0
            val result = repo(cache, resolver { completed ->
                whole++; completed(ScheduleCandidateDiscovery(listOf(map())))
                mergeFreshScheduleDiscoveries(selected, own, ScheduleCandidateDiscovery(listOf(map()), listOf("fresh-map"), false))
            }).searchOtherSources(selected, f.now.plusSeconds(60))
            assertEquals(1, whole)
            assertEquals(before.withoutSearchMetadata(), result.withoutSearchMetadata())
            assertEquals(before.checkedAt, result.checkedAt); assertEquals(before.status, result.status)
            assertEquals(before.siteBinding, result.siteBinding); assertNull(result.publication)
            assertTrue(result.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(selected) })
            assertEquals(result, cold(cache)); assertTrue(cache.safety.isEmpty())
            assertFalse(requireNotNull(cache.value.get()).contains("freshContactEvidence"))
        }
    }

    @Test fun retainedDurableMapConflictStillAllowsInformationWithoutRestoringOldAuthority() = runBlocking<Unit> {
        val row = map().copy(outcome = ScheduleIdentityOutcome.CONFLICT)
        val before = initial(true).copy(status = InternetScheduleStatus.UNAVAILABLE, siteBinding = null, publicationRejected = true,
            sourceNeedsVerification = true, sourceChangeBlock = ScheduleSourceChangeBlock.CONFLICT, sourceCandidates = listOf(row))
        val cache = cache(before); val own = f.fresh()
        val result = repo(cache, resolver { own }).searchOtherSources(selected, f.now)
        assertEquals(before.withoutSearchMetadata(), result.withoutSearchMetadata())
        assertNull(result.publication); assertNull(result.siteBinding); assertTrue(result.sourceNeedsVerification)
        assertTrue(result.publicationRejected); assertEquals(ScheduleSourceChangeBlock.CONFLICT, result.sourceChangeBlock)
        assertEquals(ScheduleIdentityOutcome.CONFLICT, result.sourceCandidates.single { it.id == row.id }.outcome)
        assertTrue(result.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(selected) })
        assertEquals(result, cold(cache))
    }

    @Test fun freshExactAcceptedConflictIsDurableBeforeAwaitingOwnAndSurvivesImmediateCancellation() = runBlocking<Unit> {
        for (duplicatePositiveFirst in listOf(false, true)) {
            val before = initial(true); val cache = cache(before)
            val conflict = map().copy(outcome = ScheduleIdentityOutcome.CONFLICT, checkedAt = f.now.toString(),
                evidenceSha256 = "b".repeat(64), reason = "Fresh explicit identity conflict")
            lateinit var task: Job
            cache.onSafety = { task.cancel() }
            val repository = repo(cache, resolver { completed ->
                completed(ScheduleCandidateDiscovery(if (duplicatePositiveFirst) listOf(map(), conflict) else listOf(conflict),
                    listOf("fresh-conflict")))
                awaitCancellation()
            })
            task = launch(start = CoroutineStart.LAZY) { repository.searchOtherSources(selected, f.now) }
            task.start(); task.join()
            assertTrue(task.isCancelled); assertEquals(1, cache.safety.size)
            val after = cold(cache)
            assertNull(after.publication); assertNull(after.siteBinding)
            assertTrue(after.publicationRejected); assertTrue(after.sourceNeedsVerification)
            assertEquals(ScheduleSourceChangeBlock.CONFLICT, after.sourceChangeBlock)
            assertEquals(before.website, after.website); assertEquals(before.sourceUrl, after.sourceUrl)
            assertEquals(before.siteEvidenceUrl, after.siteEvidenceUrl); assertEquals(before.customSource, after.customSource)
            assertEquals(before.dismissedCandidateIds, after.dismissedCandidateIds)
            assertEquals(before.sourceHasWithdrawals, after.sourceHasWithdrawals)
            assertEquals(f.now.toString(), after.checkedAt)
            assertEquals(conflict, after.sourceCandidates.single { it.id == conflict.id })
        }
    }

    @Test fun anUnrelatedPathOrUnverifiedConflictCannotWithdrawAcceptedAuthority() = runBlocking<Unit> {
        val before = initial(true)
        val changes = listOf(map().copy(sourceUrl = map().sourceUrl + "another-church/", outcome = ScheduleIdentityOutcome.CONFLICT),
            map().copy(outcome = ScheduleIdentityOutcome.CONFLICT, verificationUnavailable = true))
        for (row in changes) {
            val cache = cache(before)
            val result = repo(cache, resolver { completed ->
                val found = ScheduleCandidateDiscovery(listOf(row)); completed(found); found
            }).searchOtherSources(selected, f.now)
            assertEquals(before.withoutSearchMetadata(), result.withoutSearchMetadata())
            assertEquals(before.siteBinding, cold(cache).siteBinding)
            assertTrue(cache.safety.isEmpty()); assertFalse(result.sourceNeedsVerification)
        }
    }

    @Test fun codecCannotSilentlyRevokeAuthorityWhenACustomResolverOmitsTheCompletedConflictBoundary() = runBlocking<Unit> {
        val before = initial(true); val cache = cache(before); val bytes = cache.value.get()
        val conflict = map().copy(outcome = ScheduleIdentityOutcome.CONFLICT)
        // A custom implementation that violates the callback contract still cannot
        // smuggle an authority mutation through an ordinary metadata write.
        val result = repo(cache, resolver { ScheduleCandidateDiscovery(listOf(conflict)) }).searchOtherSources(selected, f.now)
        assertEquals(bytes, cache.value.get()); assertEquals(0, cache.writes.get())
        assertTrue(result.candidateSearchUnavailable); assertEquals(before.siteBinding, result.siteBinding)
    }

    @Test fun cancellationWhileSearchingDoesNotWriteAnyOrdinaryMetadata() = runBlocking<Unit> {
        val cache = cache(); val bytes = cache.value.get(); val entered = CompletableDeferred<Unit>()
        val task = launch { repo(cache, resolver { entered.complete(Unit); awaitCancellation() }).searchOtherSources(selected, f.now) }
        entered.await(); task.cancelAndJoin()
        assertEquals(bytes, cache.value.get()); assertEquals(0, cache.writes.get()); assertTrue(cache.safety.isEmpty())
    }

    @Test fun concurrentDismissalAcceptanceAndWithdrawalWinTheRawSnapshotComparison() = runBlocking<Unit> {
        val own = f.fresh()
        for (change in listOf("dismiss", "accept", "safety")) {
            val before = initial(); val cache = cache(before); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val repository = repo(cache, resolver { entered.complete(Unit); release.await(); own })
            val task = async { repository.searchOtherSources(selected, f.now) }
            entered.await()
            val updated = when (change) {
                "dismiss" -> repository.dismissCandidate(selected, map().id)
                "accept" -> before.copy(website = map().sourceUrl, sourceUrl = map().sourceUrl,
                    siteEvidenceUrl = map().evidenceUrl, siteBinding = binding(map()))
                else -> initial(true).copy(sourceHasWithdrawals = true, publicationRejected = true)
            }
            if (change != "dismiss") {
                val coordinated = CoordinatedScheduleCacheStore(cache)
                if (change == "safety") coordinated.writeSafety(selected.id, ScheduleCacheCodec.encode(updated))
                else coordinated.write(selected.id, ScheduleCacheCodec.encode(updated))
            }
            val bytes = cache.value.get(); release.complete(Unit)
            assertEquals(updated, task.await()); assertEquals(bytes, cache.value.get()); assertEquals(updated, cold(cache))
        }
    }

    @Test fun finalReadAndWriteShareTheLockWithARepositoryDismissalAtTheExactBoundary() = runBlocking<Unit> {
        val own = f.fresh(); val before = initial(); val cache = cache(before)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repository = repo(cache, resolver { entered.complete(Unit); release.await(); own })
        val task = async(Dispatchers.Default) { repository.searchOtherSources(selected, f.now) }
        entered.await()
        val finalReadEntered = CountDownLatch(1); val writerStarted = CountDownLatch(1)
        cache.onRead.set {
            finalReadEntered.countDown()
            check(writerStarted.await(3, TimeUnit.SECONDS))
        }
        val dismissal = async(Dispatchers.Default) {
            check(finalReadEntered.await(3, TimeUnit.SECONDS))
            writerStarted.countDown()
            repository.dismissCandidate(selected, map().id)
        }
        release.complete(Unit)
        task.await(); dismissal.await()
        val after = cold(cache)
        assertTrue(map().id in after.dismissedCandidateIds)
        assertTrue(after.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER })
        assertEquals(before.checkedAt, after.checkedAt); assertNull(after.siteBinding)
    }

    @Test fun twoSearchesCompletingInReverseOrderCannotOverwriteTheFirstCompletedSnapshot() = runBlocking<Unit> {
        val cache = cache(); val firstStarted = CompletableDeferred<Unit>(); val releaseFirst = CompletableDeferred<Unit>()
        val own = f.fresh(); val later = own.copy(candidates = own.candidates.map { it.copy(reason = "Later completed response") })
        val first = async { repo(cache, resolver { firstStarted.complete(Unit); releaseFirst.await(); own }).searchOtherSources(selected, f.now) }
        firstStarted.await()
        val second = repo(cache, resolver { later }).searchOtherSources(selected, f.now.plusSeconds(1))
        val bytes = cache.value.get(); releaseFirst.complete(Unit)
        assertEquals(second, first.await()); assertEquals(bytes, cache.value.get())
        assertEquals("Later completed response", cold(cache).sourceCandidates.single { it.provider == OwnContactCandidateProvider.PROVIDER }.reason)
    }

    @Test fun invalidNonNullSnapshotAndReadFailureDoNotBecomeColdWritableStates() = runBlocking<Unit> {
        for (readFailure in listOf(false, true)) {
            val cache = Cache(if (readFailure) ScheduleCacheCodec.encode(initial()) else "{broken")
            cache.failRead = readFailure
            var searched = 0
            val result = repo(cache, resolver { searched++; ScheduleCandidateDiscovery() }).searchOtherSources(selected, f.now)
            assertEquals(0, searched); assertEquals(0, cache.writes.get())
            assertTrue(result.candidateSearchUnavailable)
            if (!readFailure) assertEquals("{broken", cache.value.get())
        }
    }

    @Test fun compatibilityInterfaceDispatchesOneForcedRefreshWithoutInventingMetadataAuthority() = runBlocking<Unit> {
        var refreshes = 0
        val old = object : InternetSchedules {
            override fun cached(parish: Parish) = initial()
            override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState {
                refreshes++; assertTrue(force); assertNull(sourceOverride); return initial()
            }
        }
        assertEquals(initial(), old.searchOtherSources(selected, f.now)); assertEquals(1, refreshes)
    }
}
