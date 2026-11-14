package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Saved actual Map/contact bytes, real resolver/repository, no live HTTP or OCR claim. */
class AutomaticSourceContinuationTest {
    private val f = ContactWiringFixtures
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/manual-other-source-search/$name"))
        .use { it.readBytes() }
    private val manifest by lazy { JsonParser.parseString(bytes("manifest.json").toString(Charsets.UTF_8)).asJsonObject }
    private val mapRows by lazy { manifest["map"].asJsonArray.map { it.asJsonObject } }
    private val oldRows by lazy { manifest["legacy"].asJsonArray.map { it.asJsonObject } }
    private fun redacted(url: String) = url.replace(Regex("([?&]token=)[^&]*"), "$1REDACTED")

    private class Cache : ScheduleCacheStore {
        val raw = AtomicReference<String?>(null)
        val writes = AtomicInteger()
        val onRead = AtomicReference<(() -> Unit)?>(null)
        var failWrite = false
        override fun read(parishId: String): String? { onRead.getAndSet(null)?.invoke(); return raw.get() }
        override fun write(parishId: String, value: String) {
            if (failWrite) throw IOException("Storage temporarily unavailable")
            raw.set(value); writes.incrementAndGet()
        }
    }

    private inner class Replay {
        val cache = Cache()
        val requests = CopyOnWriteArrayList<String>()
        val direct = AtomicInteger(); val websites = AtomicInteger(); val whole = AtomicInteger()
        val contactReads = AtomicInteger(); val scheduleReads = AtomicInteger()
        val activeContacts = AtomicInteger(); val peakContacts = AtomicInteger()
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        var nonCancellableFirstContact = false
        var contactFails = false
        var onWholeReturn: (() -> Unit)? = null
        var onFastReturn: (() -> Unit)? = null
        private fun response(row: JsonObject, requested: String): ScheduleResponse {
            val body = bytes(row["asset"].asString)
            assertEquals(row["sha256"].asString, ScheduleSourceCandidate.sha(body))
            assertEquals(row["bytes"].asInt, body.size)
            return ScheduleResponse(row["responseUrl"].asString.let { if (it.contains("token=REDACTED")) requested else it },
                body, row["contentType"].asString)
        }
        private val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool { direct.incrementAndGet(); return f.pools().direct(parish) }
            override suspend fun websites(parish: Parish): PublisherSeedPool { websites.incrementAndGet(); return f.pools().websites(parish) }
            override suspend fun curatedOwners() = f.owners
        }
        private val transport = ScheduleTransport { url ->
            requests += redacted(url)
            val map = mapRows.singleOrNull { redacted(it["requestedUrl"].asString) == redacted(url) }
            val old = oldRows.firstOrNull { it["requestedUrl"].asString == url || it["responseUrl"].asString == url }
            when {
                map != null -> response(map, url)
                old != null -> response(old, url)
                url == f.contact -> {
                    val count = contactReads.incrementAndGet()
                    val running = activeContacts.incrementAndGet(); peakContacts.updateAndGet { maxOf(it, running) }
                    try {
                        entered?.complete(Unit)
                        if (count == 1 && nonCancellableFirstContact) withContext(NonCancellable) { release?.await() }
                        else release?.await()
                        if (contactFails) throw IOException("Own contact is temporarily unavailable")
                        f.response(url)
                    } finally { activeContacts.decrementAndGet() }
                }
                url == LinkedNavigationFixtures.target -> {
                    scheduleReads.incrementAndGet()
                    throw IOException("This test stops at the genuine schedule leaf")
                }
                else -> throw IOException("No saved response; live HTTP disabled")
            }
        }
        private val production = OpenScheduleSiteResolver(transport, ownPools = pools, clock = f.clock)
        private val recorded = object : ScheduleSiteResolver by production {
            override fun forRefresh(): ScheduleSiteResolver {
                val session = production.forRefresh()
                return object : ScheduleSiteResolver by session {
                    override suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>,
                        useful: (ScheduleCandidateDiscovery) -> Boolean, excludedCandidateIds: Set<String>): ScheduleSourceDiscovery? =
                        session.initialFastDiscovery(parish, excludedUrls, useful, excludedCandidateIds).also { onFastReturn?.invoke() }
                    override suspend fun candidatesForSourceSearch(parish: Parish, excludedUrls: Set<String>, excludedCandidateIds: Set<String>,
                        useful: (ScheduleCandidateDiscovery) -> Boolean,
                        onProviderCompleted: (ScheduleCandidateDiscovery) -> Unit): ScheduleCandidateDiscovery {
                        whole.incrementAndGet()
                        val result = session.candidatesForSourceSearch(parish, excludedUrls, excludedCandidateIds, useful, onProviderCompleted)
                        onWholeReturn?.invoke()
                        return result
                    }
                }
            }
        }
        val repository = InternetScheduleRepository(transport, cache, siteResolver = recorded, curatedPublishers = { f.owners })
        suspend fun fast(): InternetScheduleState = repository.refresh(f.selected(), f.now, force = true).also {
            assertEquals(3, it.sourceCandidates.size)
            assertTrue(it.sourceCandidates.all { row -> row.canConfirm(f.selected()) && row.ownContact == null })
            assertEquals(7, requests.size); assertEquals(0, direct.get()); assertEquals(0, contactReads.get())
        }
        fun ticket(delivery: InternetScheduleState, owner: Any) =
            requireNotNull(repository.takeSourceContinuation(f.selected(), f.now, delivery, owner))
    }

    @Test fun actualFastRowsStayImmediateAndOneAutomaticWholeAddsAnIndependentOwnOfferWithoutAuthority() = runBlocking<Unit> {
        val r = Replay(); val fast = r.fast(); val owner = Any(); val ticket = r.ticket(fast, owner)
        assertNull(r.repository.takeSourceContinuation(f.selected(), f.now, fast, owner))
        assertEquals(0, r.direct.get())
        val result = requireNotNull(r.repository.continueSourceSearch(f.selected(), f.now, ticket, owner))
        assertEquals(1, r.whole.get()); assertEquals(1, r.direct.get()); assertEquals(0, r.websites.get())
        assertEquals(1, r.contactReads.get()); assertEquals(0, r.scheduleReads.get())
        assertEquals(fast.withoutSearchMetadata(), result.withoutSearchMetadata())
        assertTrue(result.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(f.selected()) })
        assertNull(result.siteBinding); assertNull(result.publication)
        assertEquals(result, r.repository.cached(f.selected()))
        assertFalse(requireNotNull(r.cache.raw.get()).contains("fastMapReceipt"))
        assertFalse(requireNotNull(r.cache.raw.get()).contains("freshContactEvidence"))
        assertNull(r.repository.continueSourceSearch(f.selected(), f.now, ticket, owner))
        assertNull(r.repository.takeSourceContinuation(f.selected(), f.now, result, Any()))
        assertEquals(1, r.whole.get())
    }

    @Test fun automaticOwnIsStillOnlyAnOfferAndExplicitConfirmationRereadsItsExactContact() = runBlocking<Unit> {
        val r = Replay(); val fast = r.fast(); val owner = Any()
        val result = requireNotNull(r.repository.continueSourceSearch(f.selected(), f.now, r.ticket(fast, owner), owner))
        val own = result.sourceCandidates.single { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(f.selected()) }
        assertEquals(1, r.contactReads.get()); assertNull(result.siteBinding)
        val chosen = r.repository.confirmCandidate(f.selected(), own.id, f.now.plusSeconds(1))
        assertEquals(2, r.contactReads.get()); assertEquals(1, r.scheduleReads.get())
        assertEquals(own.id, chosen.siteBinding?.candidateConfirmation?.candidate?.id)
        assertNull(chosen.publication)
        assertEquals(1, r.whole.get())
    }

    @Test fun copiesCachedObjectsAnotherRepositoryAndRestoredFastRowsCannotRecoverATicket() = runBlocking<Unit> {
        for (kind in listOf("copy", "decoded", "repository")) {
            val r = Replay(); val fast = r.fast()
            val delivered = when (kind) { "copy" -> fast.copy(); "decoded" -> r.repository.cached(f.selected()); else -> fast }
            val repository = if (kind == "repository") InternetScheduleRepository(ScheduleTransport { error("No network") }, r.cache) else r.repository
            assertNull(repository.takeSourceContinuation(f.selected(), f.now, delivered, Any()))
            if (kind != "repository") assertNull(r.repository.takeSourceContinuation(f.selected(), f.now, fast, Any()))
            assertEquals(0, r.direct.get()); assertEquals(0, r.whole.get())
        }
        val r = Replay(); r.fast()
        val again = r.repository.refresh(f.selected(), f.now.plusSeconds(1), force = true)
        assertNull(r.repository.takeSourceContinuation(f.selected(), f.now.plusSeconds(1), again, Any()))
        // This explicit forced retry may use the full resolver, but cannot restore a ticket.
        assertEquals(1, r.direct.get()); assertEquals(0, r.whole.get())
    }

    @Test fun customDefaultHooksNeverCallTheCompatibilityForcedRefresh() = runBlocking<Unit> {
        var refreshes = 0
        val custom = object : InternetSchedules {
            override fun cached(parish: Parish) = InternetScheduleState(parish.id)
            override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState {
                refreshes++; error("Unsupported automatic entry must not call refresh")
            }
        }
        val r = Replay(); val fast = r.fast(); val owner = Any(); val ticket = r.ticket(fast, owner)
        assertNull(custom.takeSourceContinuation(f.selected(), f.now, fast, owner))
        assertNull(custom.continueSourceSearch(f.selected(), f.now, ticket, owner))
        assertEquals(0, refreshes); assertEquals(0, r.direct.get())
        ticket.revoke()
    }

    @Test fun ownerIdentityAndParishDayAreRecheckedBeforeAnyDispatch() = runBlocking<Unit> {
        for (kind in listOf("owner", "identity", "day")) {
            val r = Replay(); val fast = r.fast(); val owner = Any(); val ticket = r.ticket(fast, owner)
            val parish = if (kind == "identity") f.selected().copy(address = f.selected().address + " другое здание") else f.selected()
            if (kind == "identity") assertNotEquals(ScheduleEntityIdentity.fingerprint(f.selected()), ScheduleEntityIdentity.fingerprint(parish))
            val now = if (kind == "day") f.now.plusSeconds(86_400) else f.now
            assertNull(r.repository.continueSourceSearch(parish, now, ticket, if (kind == "owner") Any() else owner))
            assertEquals(0, r.direct.get()); assertEquals(0, r.whole.get())
        }
    }

    @Test fun changedAuthorityOrHistoryBetweenDeliveryBindAndBeginDisallowsAutomaticSearch() = runBlocking<Unit> {
        for (stage in listOf("bind", "begin")) for (change in listOf("dismiss", "reject", "verify", "configured", "custom", "binding")) {
            val r = Replay(); val fast = r.fast(); val owner = Any()
            val ticket = if (stage == "begin") r.ticket(fast, owner) else null
            val row = fast.sourceCandidates.first()
            val binding = ScheduleEntityIdentity.bind(f.selected(), row.sourceUrl, row.evidenceUrl, row.name,
                ScheduleBindingMethod.USER_CONFIRMED).copy(candidateConfirmation =
                ScheduleCandidateConfirmation(1, ScheduleCandidateConfirmation.TYPE, row.selectedIdentity, row, f.now.toString()))
            val changed = when (change) {
                "dismiss" -> fast.copy(dismissedCandidateIds = listOf(row.id))
                "reject" -> fast.copy(publicationRejected = true)
                "verify" -> fast.copy(sourceNeedsVerification = true)
                "configured" -> fast.copy(configuredWebsite = "https://configured.example/")
                "custom" -> fast.copy(customSource = "https://custom.example/")
                else -> fast.copy(website = row.sourceUrl, sourceUrl = row.sourceUrl, siteEvidenceUrl = row.evidenceUrl, siteBinding = binding)
            }
            r.cache.raw.set(ScheduleCacheCodec.encode(changed))
            if (ticket == null) assertNull(r.repository.takeSourceContinuation(f.selected(), f.now, fast, owner))
            else assertNull(r.repository.continueSourceSearch(f.selected(), f.now, ticket, owner))
            assertEquals(0, r.direct.get()); assertEquals(0, r.whole.get())
        }
    }

    @Test fun revocationBeforeBeginAndDuringOwnNetworkNeverWritesOrdinaryMetadata() = runBlocking<Unit> {
        run {
            val r = Replay(); val fast = r.fast(); val owner = Any(); val ticket = r.ticket(fast, owner)
            val before = r.cache.raw.get(); ticket.revoke()
            assertNull(r.repository.continueSourceSearch(f.selected(), f.now, ticket, owner))
            assertEquals(before, r.cache.raw.get()); assertEquals(0, r.direct.get())
        }
        val r = Replay(); val fast = r.fast(); val owner = Any(); val ticket = r.ticket(fast, owner)
        r.entered = CompletableDeferred(); r.release = CompletableDeferred()
        val before = r.cache.raw.get(); val writes = r.cache.writes.get()
        val pending = async { r.repository.continueSourceSearch(f.selected(), f.now, ticket, owner) }
        withTimeout(5_000) { r.entered!!.await() }
        ticket.revoke(); r.release!!.complete(Unit)
        pending.join(); assertTrue(pending.isCancelled)
        assertEquals(before, r.cache.raw.get()); assertEquals(writes, r.cache.writes.get())
        assertEquals(1, r.direct.get()); assertEquals(1, r.whole.get())
    }

    @Test fun cancellationAtTheFinalReadIsRecheckedBeforeTheOrdinaryWrite() = runBlocking<Unit> {
        val r = Replay(); val fast = r.fast(); val owner = Any(); val ticket = r.ticket(fast, owner)
        val hook = AtomicInteger(); val before = r.cache.raw.get(); val writes = r.cache.writes.get()
        r.onWholeReturn = { r.cache.onRead.set { hook.incrementAndGet(); ticket.revoke() } }
        val pending = async { r.repository.continueSourceSearch(f.selected(), f.now, ticket, owner) }
        pending.join(); assertTrue(pending.isCancelled)
        assertEquals(1, hook.get()); assertEquals(before, r.cache.raw.get()); assertEquals(writes, r.cache.writes.get())
    }

    @Test fun rawCasPreservesConcurrentDismissalAndSafetyInsteadOfApplyingOldSuggestions() = runBlocking<Unit> {
        for (safety in listOf(false, true)) {
            val r = Replay(); val fast = r.fast(); val owner = Any(); val ticket = r.ticket(fast, owner)
            val concurrent = if (safety) fast.copy(publicationRejected = true, sourceNeedsVerification = true,
                sourceChangeBlock = ScheduleSourceChangeBlock.CONFLICT) else fast.copy(dismissedCandidateIds = listOf(fast.sourceCandidates.first().id))
            val encoded = ScheduleCacheCodec.encode(concurrent)
            r.onWholeReturn = { r.cache.raw.set(encoded) }
            val writes = r.cache.writes.get()
            val result = r.repository.continueSourceSearch(f.selected(), f.now, ticket, owner)
            assertEquals(encoded, r.cache.raw.get()); assertEquals(writes, r.cache.writes.get())
            assertEquals(r.repository.cached(f.selected()), result)
            assertNull(r.repository.takeSourceContinuation(f.selected(), f.now, requireNotNull(result), Any()))
        }
    }

    @Test fun anExplicitSearchJoinsCancelledOwnBeforeOpeningTheNextContext() = runBlocking<Unit> {
        val r = Replay(); val fast = r.fast(); val owner = Any(); val ticket = r.ticket(fast, owner)
        r.entered = CompletableDeferred(); r.release = CompletableDeferred(); r.nonCancellableFirstContact = true
        val automatic = async { r.repository.continueSourceSearch(f.selected(), f.now, ticket, owner) }
        withTimeout(5_000) { r.entered!!.await() }
        val explicit = async { r.repository.searchOtherSources(f.selected(), f.now.plusSeconds(1)) }
        delay(40)
        assertFalse(explicit.isCompleted); assertEquals(1, r.direct.get())
        r.release!!.complete(Unit)
        automatic.join(); assertTrue(automatic.isCancelled)
        val result = withTimeout(5_000) { explicit.await() }
        assertEquals(2, r.whole.get()); assertEquals(2, r.direct.get()); assertEquals(1, r.peakContacts.get())
        assertTrue(result.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(f.selected()) })
    }

    @Test fun anUnavailableCompletionDoesNotCreateARetryOrMintFromTheSavedRows() = runBlocking<Unit> {
        val r = Replay(); val fast = r.fast(); val owner = Any(); r.contactFails = true
        val result = requireNotNull(r.repository.continueSourceSearch(f.selected(), f.now, r.ticket(fast, owner), owner))
        assertTrue(result.candidateSearchUnavailable); assertEquals(1, r.whole.get()); assertEquals(1, r.direct.get())
        assertTrue(result.sourceCandidates.map { it.id }.containsAll(fast.sourceCandidates.map { it.id }))
        assertNull(r.repository.takeSourceContinuation(f.selected(), f.now, result, Any()))
        val restored = r.repository.refresh(f.selected(), f.now.plusSeconds(1), force = true)
        assertNull(r.repository.takeSourceContinuation(f.selected(), f.now.plusSeconds(1), restored, Any()))
        // A new explicit retry is distinct from an automatic retry after the failed completion.
        assertEquals(1, r.whole.get()); assertEquals(2, r.direct.get())
    }

    @Test fun aFailedFastCacheCommitAndACustomFastHookCannotMintAProductionReceipt() = runBlocking<Unit> {
        val r = Replay(); r.cache.failWrite = true
        val temporary = r.repository.refresh(f.selected(), f.now, force = true)
        assertEquals(3, temporary.sourceCandidates.size)
        assertNull(r.repository.takeSourceContinuation(f.selected(), f.now, temporary, Any()))
        assertEquals(0, r.direct.get())
        val custom = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: Parish): ScheduleSite? = error("Fast hook owns this case")
            override suspend fun initialFastDiscovery(parish: Parish, excludedUrls: Set<String>) =
                ScheduleSourceDiscovery(ScheduleIdentityOutcome.INSUFFICIENT,
                    candidates = ScheduleCandidateDiscovery(listOf(f.mapCandidate().copy(provider = OfficialMapCandidateProvider.PROVIDER))))
        }
        val repository = InternetScheduleRepository(ScheduleTransport { error("No network") }, Cache(), siteResolver = custom)
        val fast = repository.refresh(f.selected(), f.now, force = true)
        assertNull(repository.takeSourceContinuation(f.selected(), f.now, fast, Any()))
    }
    @Test fun aFastResultThatAlreadyAttemptedOwnDoesNotReceiveASecondAutomaticAttempt() = runBlocking<Unit> {
        val direct = AtomicInteger()
        val pools = object : OwnContactPoolFactories {
            override suspend fun direct(parish: Parish): PublisherSeedPool { direct.incrementAndGet(); return f.pools().direct(parish) }
            override suspend fun websites(parish: Parish) = f.pools().websites(parish)
            override suspend fun curatedOwners() = f.owners
        }
        val transport = ScheduleTransport { url -> if (url.startsWith("https://api.openstreetmap.org/")) f.osm(url) else f.response(url) }
        val resolver = OpenScheduleSiteResolver(transport,
            candidateProvider = ScheduleCandidateProvider { _, _ -> ScheduleCandidateDiscovery(incomplete = false) },
            ownPools = pools, clock = f.clock)
        val repository = InternetScheduleRepository(transport, Cache(), siteResolver = resolver)
        val result = repository.refresh(f.selected(), f.now, force = true)
        assertEquals(1, direct.get())
        assertTrue(result.sourceCandidates.any { it.provider == OwnContactCandidateProvider.PROVIDER && it.canConfirm(f.selected()) })
        assertNull(repository.takeSourceContinuation(f.selected(), f.now, result, Any()))
        assertEquals(1, direct.get())
    }

    @Test fun cancellationAtTheFinalFastReadCannotCommitOrIssueAReceipt() = runBlocking<Unit> {
        val r = Replay()
        val pending = async(start = CoroutineStart.LAZY) { r.repository.refresh(f.selected(), f.now, force = true) }
        r.onFastReturn = {
            // The resolver's first post-network read arms cancellation for the
            // final raw-CAS read directly before its metadata write.
            r.cache.onRead.set { r.cache.onRead.set { pending.cancel() } }
        }
        pending.start(); pending.join()
        assertTrue(pending.isCancelled); assertNull(r.cache.raw.get()); assertEquals(0, r.cache.writes.get())
        assertEquals(0, r.direct.get()); assertEquals(0, r.whole.get())
        assertNull(r.repository.takeSourceContinuation(f.selected(), f.now, InternetScheduleState(f.selected().id), Any()))
    }

}
