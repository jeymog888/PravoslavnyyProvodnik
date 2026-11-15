package ru.liturgyguide.service.schedule

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Hermetic test-only workflow contract. All object IDs and .example sources below are synthetic. */
@RunWith(AndroidJUnit4::class)
class LiveSealedHoldout12NormalRetryContractTest {
    private val now = Instant.parse("2026-11-15T00:00:00Z")
    private val selected = Parish("osm:node:999000111", "Храм святителя Николая", "Николая", "", "", "", "",
        "Europe/Moscow", "https://www.openstreetmap.org/node/999000111", "", "", "", emptyList(), "", "", 55.0, 37.0)
    private val gson = GsonBuilder().serializeNulls().create()

    private fun failed(status: InternetScheduleStatus = InternetScheduleStatus.UNAVAILABLE) =
        InternetScheduleState(selected.id, status = status, checkedAt = now.toString())
    private fun ready() = failed(InternetScheduleStatus.READY).copy(publication = SchedulePublication(
        selected.id, selected.timezone, "https://synthetic.example/", "https://synthetic.example/",
        now.toString(), listOf(InternetService("2026-11-15", "09:00", "Литургия", "liturgy"))))
    private fun candidate(index: Int, url: String = "https://synthetic-$index.example/",
        provider: String = OfficialMapCandidateProvider.PROVIDER) = ScheduleSourceCandidate(
        selectedIdentity = ScheduleEntityIdentity.fingerprint(selected), provider = provider,
        providerLabel = "Synthetic provider", providerObjectId = "synthetic-$index", name = selected.name,
        address = selected.address, sourceUrl = url, evidenceUrl = "https://synthetic-index.example/$index/",
        evidenceSha256 = "a".repeat(64), checkedAt = now.toString(), latitude = selected.latitude!!,
        longitude = selected.longitude!!, outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Synthetic fixture")
    private fun binding(row: ScheduleSourceCandidate) = ScheduleSiteBinding(
        ScheduleEntityIdentity.POLICY_VERSION, row.selectedIdentity, row.name, ScheduleEntityScope.CHURCH,
        ScheduleBindingMethod.USER_CONFIRMED, row.sourceUrl, row.evidenceUrl,
        candidateConfirmation = ScheduleCandidateConfirmation(1, ScheduleCandidateConfirmation.TYPE,
            row.selectedIdentity, row, now.toString()))

    private data class RefreshCall(val selected: Parish, val now: Instant, val force: Boolean, val sourceOverride: String?)
    private inner class Fake(initial: InternetScheduleState, next: InternetScheduleState = initial) : InternetSchedules {
        var cache = InternetScheduleState(selected.id)
        val refreshCalls = mutableListOf<RefreshCall>()
        var metadataCalls = 0
        val confirmations = mutableListOf<String>()
        val observed = Holdout12SourceCoordinator.COUNTER_NAMES.associateWith { 0 }.toMutableMap()
        var onRefresh: suspend (Int) -> InternetScheduleState = { if (it == 1) initial else next }
        var onMetadata: suspend () -> InternetScheduleState = { cache }
        var onConfirm: suspend (String) -> InternetScheduleState = { cache }
        override fun cached(parish: Parish): InternetScheduleState { assertSame(selected, parish); return cache }
        override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState {
            refreshCalls += RefreshCall(parish, now, force, sourceOverride)
            return onRefresh(refreshCalls.size).also { cache = it }
        }
        override suspend fun searchOtherSources(parish: Parish, now: Instant): InternetScheduleState {
            assertSame(selected, parish); assertSame(this@LiveSealedHoldout12NormalRetryContractTest.now, now)
            metadataCalls++
            return onMetadata().also { cache = it }
        }
        override suspend fun confirmCandidate(parish: Parish, candidateId: String, now: Instant): InternetScheduleState {
            assertSame(selected, parish); assertSame(this@LiveSealedHoldout12NormalRetryContractTest.now, now)
            confirmations += candidateId
            return onConfirm(candidateId).also { cache = it }
        }
        override fun takeSourceContinuation(parish: Parish, now: Instant, delivered: InternetScheduleState,
            owner: Any): ScheduleSourceContinuation? = error("Workflow must never take a continuation")
        fun started(name: String, count: Int = 1) { observed[name] = observed.getValue(name) + count }
    }
    private suspend fun run(fake: Fake, receipt: MutableMap<String, Any?> = linkedMapOf(),
        snapshots: MutableList<JsonObject> = mutableListOf(), clock: () -> Long = System::nanoTime): InternetScheduleState =
        Holdout12SourceCoordinator(fake, selected, now, receipt, counters = { fake.observed.toMap() },
            persist = { snapshots += gson.toJsonTree(receipt).asJsonObject }, monotonicNanos = clock).run()
    @Suppress("UNCHECKED_CAST")
    private fun actions(receipt: Map<String, Any?>) = receipt.getValue("sourceActions") as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    private fun retry(receipt: Map<String, Any?>) = receipt.getValue("normalRetry") as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun counts(receipt: Map<String, Any?>, action: String) =
        (receipt.getValue("sourceActionCounts") as Map<String, Map<String, Int>>).getValue(action)
    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Throwable) { return error }
        error("Expected an interrupted workflow")
    }
    private fun assertOrdinaryCalls(fake: Fake, count: Int) {
        assertEquals(count, fake.refreshCalls.size)
        fake.refreshCalls.forEach { call ->
            assertSame(selected, call.selected); assertSame(now, call.now)
            assertTrue(call.force); assertNull(call.sourceOverride)
        }
    }
    private fun assertInterrupted(receipt: Map<String, Any?>, phase: String, status: String) {
        assertEquals(status, receipt["sourceWorkflowStatus"]); assertNull(receipt["finalSourceState"])
        val last = actions(receipt).last()
        assertEquals(phase, last["action"]); assertEquals(status, last["status"])
        assertNull(last["returnedState"]); assertNotNull(last["errorClass"])
        assertNotNull(last["elapsedMillis"]); assertNotNull(last["countersAfter"]); assertNotNull(last["counterDeltas"])
        assertEquals(1, counts(receipt, phase)["attempted"]); assertEquals(0, counts(receipt, phase)["completed"])
    }

    @Test fun aCaseWithoutARepositoryStillHasExplicitNullSourceReceipts() {
        val row = linkedMapOf<String, Any?>()
        holdout12PrepareSourceReceipt(row)
        assertEquals("normal-retry-v2", row["workflowPolicy"]); assertEquals("NOT_RUN", row["sourceWorkflowStatus"])
        assertTrue(listOf("initialSourceState", "finalSourceState", "lastCompletedSourceState", "cachedAfterRun").all { row.containsKey(it) && row[it] == null })
        assertNull(retry(row)["before"]); assertNull(retry(row)["after"])
        assertEquals("INITIAL_NOT_COMPLETED", retry(row)["skipReason"])
        Holdout12SourceCoordinator.ACTION_NAMES.forEach { assertEquals(mapOf("attempted" to 0, "completed" to 0), counts(row, it)) }
    }
    @Test fun bothCompletedFailureStatusesRetryOnceWithNoCandidatesAndNoMetadataPermission() = runBlocking<Unit> {
        for (status in listOf(InternetScheduleStatus.NO_WEBSITE, InternetScheduleStatus.UNAVAILABLE)) {
            val before = failed(status); val fake = Fake(before); val receipt = linkedMapOf<String, Any?>()
            assertFalse(permitsOtherSourceSearch(selected, before)); assertTrue(before.sourceCandidates.isEmpty())
            assertSame(before, run(fake, receipt)); assertOrdinaryCalls(fake, 2)
            assertEquals(0, fake.metadataCalls); assertTrue(fake.confirmations.isEmpty())
            assertSame(before, receipt["initialSourceState"]); assertSame(before, retry(receipt)["before"])
            assertSame(before, retry(receipt)["after"]); assertEquals("ATTEMPT", retry(receipt)["decision"])
            assertEquals(1, retry(receipt)["attempted"]); assertEquals(1, retry(receipt)["completed"])
        }
    }
    @Test fun configuredWebsiteDoesNotBlockTheOrdinaryRetry() = runBlocking<Unit> {
        val before = failed().copy(configuredWebsite = "https://configured-synthetic.example/")
        val fake = Fake(before); assertFalse(permitsOtherSourceSearch(selected, before))
        run(fake); assertOrdinaryCalls(fake, 2)
    }
    @Test fun repeatedNoWebsiteOrUnavailableNeverCreatesAThirdRefresh() = runBlocking<Unit> {
        for (status in listOf(InternetScheduleStatus.NO_WEBSITE, InternetScheduleStatus.UNAVAILABLE)) {
            val fake = Fake(failed(status)); val receipt = linkedMapOf<String, Any?>()
            run(fake, receipt); assertOrdinaryCalls(fake, 2)
            assertEquals(listOf("initialRefresh", "normalRetry"), actions(receipt).map { it["action"] })
            assertEquals("COMPLETED", receipt["sourceWorkflowStatus"])
        }
    }
    @Test fun initialUsableReadySkipsEveryLaterAction() = runBlocking<Unit> {
        val state = ready(); val fake = Fake(state); val receipt = linkedMapOf<String, Any?>()
        assertSame(state, run(fake, receipt)); assertOrdinaryCalls(fake, 1)
        assertEquals(0, fake.metadataCalls); assertTrue(fake.confirmations.isEmpty())
        assertEquals("SKIP", retry(receipt)["decision"]); assertNull(retry(receipt)["after"])
    }
    @Test fun retrySkipMatrixUsesTheNormallyReturnedStateWithoutMutatingIt() = runBlocking<Unit> {
        val cases = listOf(
            failed().copy(loading = true) to "INITIAL_LOADING",
            failed().copy(checkedAt = "") to "INITIAL_NO_CHECKED_AT",
            failed().copy(customSource = "https://custom-synthetic.example/") to "CUSTOM_SOURCE_PRESENT",
            failed(InternetScheduleStatus.IDLE) to "STATUS_NOT_ELIGIBLE",
            failed(InternetScheduleStatus.READY) to "STATUS_NOT_ELIGIBLE",
            failed(InternetScheduleStatus.UNSUPPORTED) to "STATUS_NOT_ELIGIBLE")
        for ((state, reason) in cases) {
            val encoded = gson.toJson(state); val fake = Fake(state); val receipt = linkedMapOf<String, Any?>()
            assertSame(state, run(fake, receipt)); assertOrdinaryCalls(fake, 1)
            assertEquals(reason, retry(receipt)["skipReason"]); assertNull(retry(receipt)["after"])
            assertEquals(0, retry(receipt)["attempted"]); assertEquals(0, retry(receipt)["completed"])
            assertEquals(encoded, gson.toJson(state)); assertSame(state, fake.cache)
        }
    }
    @Test fun initialErrorPreventsRetryAndDoesNotExposeCacheAsReturnedState() = runBlocking<Unit> {
        val fake = Fake(failed()); val receipt = linkedMapOf<String, Any?>(); val expected = IOException("synthetic")
        fake.onRefresh = { fake.cache = failed(); throw expected }
        assertSame(expected, failure { run(fake, receipt) }); assertOrdinaryCalls(fake, 1)
        assertInterrupted(receipt, "initialRefresh", "ERROR")
        assertNull(receipt["initialSourceState"]); assertNull(receipt["lastCompletedSourceState"])
        assertEquals("INITIAL_NOT_COMPLETED", retry(receipt)["skipReason"]); assertNull(retry(receipt)["before"])
        assertEquals(0, fake.metadataCalls); assertTrue(fake.confirmations.isEmpty())
    }
    @Test fun initialCancellationIsRethrownAndNeverStartsRetry() = runBlocking<Unit> {
        val fake = Fake(failed()); val receipt = linkedMapOf<String, Any?>(); val expected = CancellationException("synthetic")
        fake.onRefresh = { throw expected }
        assertSame(expected, failure { run(fake, receipt) }); assertOrdinaryCalls(fake, 1)
        assertInterrupted(receipt, "initialRefresh", "CANCELLED")
        assertEquals("INITIAL_NOT_COMPLETED", retry(receipt)["skipReason"])
    }
    @Test fun initialTimeoutLeavesAllUnreturnedStatesNull() = runBlocking<Unit> {
        val fake = Fake(failed()); val receipt = linkedMapOf<String, Any?>()
        fake.onRefresh = { awaitCancellation() }
        assertTrue(failure { withTimeout(500) { run(fake, receipt) } } is TimeoutCancellationException)
        assertInterrupted(receipt, "initialRefresh", "TIMEOUT"); assertOrdinaryCalls(fake, 1)
        assertNull(receipt["initialSourceState"]); assertNull(retry(receipt)["after"])
        assertEquals(0, counts(receipt, "normalRetry")["attempted"])
    }
    @Test fun retryErrorLeavesInitialAndLastCompletedSeparateAndStartsNoLaterAction() = runBlocking<Unit> {
        val before = failed().copy(sourceCandidates = listOf(candidate(1)))
        val fake = Fake(before); val receipt = linkedMapOf<String, Any?>(); val expected = IOException("synthetic retry")
        fake.onRefresh = { if (it == 1) before else throw expected }
        assertSame(expected, failure { run(fake, receipt) }); assertOrdinaryCalls(fake, 2)
        assertInterrupted(receipt, "normalRetry", "ERROR"); assertSame(before, receipt["initialSourceState"])
        assertSame(before, receipt["lastCompletedSourceState"]); assertNull(retry(receipt)["after"])
        assertEquals(0, fake.metadataCalls); assertTrue(fake.confirmations.isEmpty())
    }
    @Test fun retryExternalCancellationIsRethrownWithoutMetadataOrConfirmation() = runBlocking<Unit> {
        val before = failed().copy(sourceCandidates = listOf(candidate(1)))
        val fake = Fake(before); val receipt = linkedMapOf<String, Any?>(); val entered = CompletableDeferred<Unit>()
        fake.onRefresh = { if (it == 1) before else { entered.complete(Unit); awaitCancellation() } }
        supervisorScope {
            val work = async { run(fake, receipt) }
            entered.await(); work.cancel(CancellationException("synthetic external cancellation"))
            assertTrue(failure { work.await() } is CancellationException)
        }
        assertInterrupted(receipt, "normalRetry", "CANCELLED"); assertOrdinaryCalls(fake, 2)
        assertNull(retry(receipt)["after"]); assertEquals(0, fake.metadataCalls); assertTrue(fake.confirmations.isEmpty())
    }
    @Test fun cancellationAfterANormalInitialReturnSkipsRetryBeforeItsAttempt() = runBlocking<Unit> {
        val before = failed(); val fake = Fake(before); val receipt = linkedMapOf<String, Any?>()
        fake.onRefresh = { currentCoroutineContext()[Job]!!.cancel(CancellationException("synthetic boundary cancellation")); before }
        supervisorScope {
            val work = async { run(fake, receipt) }
            assertTrue(failure { work.await() } is CancellationException)
        }
        assertOrdinaryCalls(fake, 1); assertSame(before, receipt["initialSourceState"])
        assertEquals(1, counts(receipt, "initialRefresh")["completed"])
        assertEquals("SKIP", retry(receipt)["decision"]); assertEquals("CANCELLED", retry(receipt)["skipReason"])
        assertEquals(0, retry(receipt)["attempted"]); assertNull(retry(receipt)["after"])
        assertNull(receipt["finalSourceState"]); assertSame(before, receipt["lastCompletedSourceState"])
    }
    @Test fun retrySharesTheInitialDeadlineJobAndDoesNotReceiveANewBudget() = runBlocking<Unit> {
        val before = failed(); val fake = Fake(before); val receipt = linkedMapOf<String, Any?>()
        var initialJob: Job? = null; var retryJob: Job? = null
        fake.onRefresh = { ordinal ->
            if (ordinal == 1) { initialJob = currentCoroutineContext()[Job]; delay(25); before }
            else { retryJob = currentCoroutineContext()[Job]; awaitCancellation() }
        }
        assertTrue(failure { withTimeout(500) { run(fake, receipt) } } is TimeoutCancellationException)
        assertNotNull(initialJob); assertSame(initialJob, retryJob)
        assertInterrupted(receipt, "normalRetry", "TIMEOUT"); assertOrdinaryCalls(fake, 2)
        assertEquals(1, counts(receipt, "initialRefresh")["completed"])
        assertSame(before, receipt["lastCompletedSourceState"]); assertNull(retry(receipt)["after"])
    }
    @Test fun metadataPermissionIsReevaluatedAndCanBecomeTrueAfterRetry() = runBlocking<Unit> {
        val before = failed(); val after = before.copy(sourceCandidates = listOf(candidate(1)))
        val fake = Fake(before, after); val receipt = linkedMapOf<String, Any?>()
        assertFalse(permitsOtherSourceSearch(selected, before)); assertTrue(permitsOtherSourceSearch(selected, after))
        fake.onMetadata = { ready() }
        run(fake, receipt); assertOrdinaryCalls(fake, 2); assertEquals(1, fake.metadataCalls)
        assertTrue(fake.confirmations.isEmpty())
        assertEquals(listOf("initialRefresh", "normalRetry", "explicitSearchOtherSources"), actions(receipt).map { it["action"] })
    }
    @Test fun metadataPermissionIsReevaluatedAndCanBecomeFalseAfterRetry() = runBlocking<Unit> {
        val before = failed().copy(sourceCandidates = listOf(candidate(1))); val after = failed()
        val fake = Fake(before, after); assertTrue(permitsOtherSourceSearch(selected, before))
        assertFalse(permitsOtherSourceSearch(selected, after)); run(fake)
        assertOrdinaryCalls(fake, 2); assertEquals(0, fake.metadataCalls); assertTrue(fake.confirmations.isEmpty())
    }
    @Test fun orderAndTwoDistinctIdsAndUrlsArePreservedWithoutARefreshAfterConfirmation() = runBlocking<Unit> {
        val first = candidate(1); val sameUrl = candidate(2, first.sourceUrl, "synthetic_other")
        val second = candidate(3); val third = candidate(4)
        val after = failed().copy(sourceCandidates = listOf(first, sameUrl, second, third))
        listOf(first, sameUrl, second, third).forEach { assertTrue(it.canConfirm(selected)) }
        val fake = Fake(failed(), after); val receipt = linkedMapOf<String, Any?>()
        fake.onMetadata = { after }; fake.onConfirm = { after }
        run(fake, receipt); assertOrdinaryCalls(fake, 2); assertEquals(1, fake.metadataCalls)
        assertEquals(listOf(first.id, second.id), fake.confirmations)
        assertEquals(listOf("initialRefresh", "normalRetry", "explicitSearchOtherSources",
            "simulatedCandidateConfirmation", "simulatedCandidateConfirmation"), actions(receipt).map { it["action"] })
        assertEquals(listOf(1, 2, 3, 4, 5), actions(receipt).map { it["ordinal"] })
    }
    @Test fun historyChosenDismissedAndConflictedGroupsRemainExcluded() = runBlocking<Unit> {
        val chosen = candidate(1); val duplicate = candidate(2, chosen.sourceUrl, "synthetic_other")
        val blocked = candidate(3); val dismissed = candidate(4)
        val conflict = candidate(5).copy(outcome = ScheduleIdentityOutcome.CONFLICT)
        val input = failed().copy(sourceCandidates = listOf(chosen, duplicate, blocked, dismissed, conflict),
            siteBinding = binding(chosen), sourceHasWithdrawals = true, publicationRejected = true,
            sourceNeedsVerification = true, sourceChangeBlock = ScheduleSourceChangeBlock.HISTORY,
            dismissedCandidateIds = listOf(dismissed.id))
        val encoded = gson.toJson(input); val fake = Fake(input)
        fake.onRefresh = { ordinal -> if (ordinal == 2) assertSame(input, fake.cache); input }
        run(fake); assertOrdinaryCalls(fake, 2); assertTrue(fake.confirmations.isEmpty()); assertEquals(0, fake.metadataCalls)
        assertEquals(encoded, gson.toJson(input)); assertSame(input, fake.cache)
        assertTrue(fake.cache.publicationRejected); assertTrue(fake.cache.sourceHasWithdrawals); assertTrue(fake.cache.sourceNeedsVerification)
    }
    @Test fun retryUsableReadyStopsMetadataAndConfirmation() = runBlocking<Unit> {
        val after = ready(); val fake = Fake(failed().copy(sourceCandidates = listOf(candidate(1))), after)
        val receipt = linkedMapOf<String, Any?>(); assertSame(after, run(fake, receipt))
        assertOrdinaryCalls(fake, 2); assertEquals(0, fake.metadataCalls); assertTrue(fake.confirmations.isEmpty())
        assertSame(after, receipt["finalSourceState"]); assertEquals("COMPLETED", receipt["sourceWorkflowStatus"])
    }
    @Test fun startedIsPersistedBeforeAwaitAndActionCountersAndDurationsAreHonest() = runBlocking<Unit> {
        val before = failed(); val fake = Fake(before); val receipt = linkedMapOf<String, Any?>()
        val snapshots = mutableListOf<JsonObject>(); var nanos = 0L
        fake.onRefresh = { ordinal ->
            val saved = snapshots.last(); val last = saved.getAsJsonArray("sourceActions").last().asJsonObject
            assertEquals("STARTED", last["status"].asString); assertTrue(last["returnedState"].isJsonNull)
            assertTrue(last["countersAfter"].isJsonNull); assertTrue(last["elapsedMillis"].isJsonNull)
            assertEquals(ordinal, last["ordinal"].asInt)
            if (ordinal == 2) {
                assertEquals(1, saved.getAsJsonObject("normalRetry")["attempted"].asInt)
                assertEquals(0, saved.getAsJsonObject("normalRetry")["completed"].asInt)
                assertTrue(saved.getAsJsonObject("normalRetry")["after"].isJsonNull)
            }
            fake.started("GET_START", ordinal); if (ordinal == 2) fake.started("MOSCOW_DIRECTORY_SEARCH_START")
            nanos += ordinal * 7_000_000L
            before
        }
        run(fake, receipt, snapshots, clock = { nanos })
        val entries = actions(receipt)
        assertEquals(listOf(7L, 14L), entries.map { it["elapsedMillis"] })
        assertEquals(mapOf("GET_START" to 1, "MOSCOW_DIRECTORY_SEARCH_START" to 0), entries[0]["counterDeltas"])
        assertEquals(mapOf("GET_START" to 2, "MOSCOW_DIRECTORY_SEARCH_START" to 1), entries[1]["counterDeltas"])
        assertEquals(mapOf("GET_START" to 3, "MOSCOW_DIRECTORY_SEARCH_START" to 1), receipt["sourceWorkflowCounterDeltas"])
        assertEquals(mapOf("attempted" to 1, "completed" to 1), counts(receipt, "initialRefresh"))
        assertEquals(mapOf("attempted" to 1, "completed" to 1), counts(receipt, "normalRetry"))
        assertTrue(entries.all { it["status"] == "COMPLETED" && it["returnedState"] != null && it["errorClass"] == null })
    }
    @Test fun retryFailureRecordsObservedCounterDeltasFromFinally() = runBlocking<Unit> {
        val fake = Fake(failed()); val receipt = linkedMapOf<String, Any?>()
        fake.onRefresh = { ordinal ->
            fake.started("GET_START"); if (ordinal == 1) failed() else {
                fake.started("MOSCOW_DIRECTORY_SEARCH_START", 2); throw IOException("synthetic")
            }
        }
        failure { run(fake, receipt) }
        assertInterrupted(receipt, "normalRetry", "ERROR")
        assertEquals(mapOf("GET_START" to 1, "MOSCOW_DIRECTORY_SEARCH_START" to 2), actions(receipt).last()["counterDeltas"])
        assertEquals(mapOf("GET_START" to 2, "MOSCOW_DIRECTORY_SEARCH_START" to 2), receipt["sourceWorkflowCounterDeltas"])
    }
    @Test fun metadataErrorDoesNotRunConfirmationsOrAnotherRefresh() = runBlocking<Unit> {
        val state = failed().copy(sourceCandidates = listOf(candidate(1))); val fake = Fake(state)
        val receipt = linkedMapOf<String, Any?>(); fake.onMetadata = { throw IOException("synthetic metadata") }
        failure { run(fake, receipt) }; assertInterrupted(receipt, "explicitSearchOtherSources", "ERROR")
        assertOrdinaryCalls(fake, 2); assertEquals(1, fake.metadataCalls); assertTrue(fake.confirmations.isEmpty())
        assertSame(state, receipt["lastCompletedSourceState"])
    }
    @Test fun confirmationCancellationStopsTheSecondConfirmationAndNeverRetriesRefresh() = runBlocking<Unit> {
        val state = failed().copy(sourceCandidates = listOf(candidate(1), candidate(2))); val fake = Fake(state)
        val receipt = linkedMapOf<String, Any?>(); fake.onConfirm = { throw CancellationException("synthetic confirmation") }
        failure { run(fake, receipt) }; assertInterrupted(receipt, "simulatedCandidateConfirmation", "CANCELLED")
        assertOrdinaryCalls(fake, 2); assertEquals(1, fake.confirmations.size)
    }
    @Test fun failedStartedPersistencePreventsTheUnderlyingAction() = runBlocking<Unit> {
        val fake = Fake(failed()); val receipt = linkedMapOf<String, Any?>(); val expected = IOException("synthetic receipt write")
        var writes = 0
        val error = failure {
            Holdout12SourceCoordinator(fake, selected, now, receipt, counters = { fake.observed.toMap() },
                persist = { writes++; if (writes == 2) throw expected }).run()
        }
        assertSame(expected, error); assertOrdinaryCalls(fake, 0)
        assertInterrupted(receipt, "initialRefresh", "ERROR"); assertEquals("INITIAL_NOT_COMPLETED", retry(receipt)["skipReason"])
    }
    @Test fun cleanupAfterErrorRemovesOwnedStorageAndPreservesProtectedFiles() = runBlocking<Unit> {
        cleanupAfterFailure(IOException("synthetic cleanup"))
    }
    @Test fun cleanupAfterCancellationRemovesOwnedStorageAndPreservesProtectedFiles() = runBlocking<Unit> {
        cleanupAfterFailure(CancellationException("synthetic cleanup"))
    }
    private suspend fun cleanupAfterFailure(expected: Exception) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val protectedBefore = protectedHashes(app)
        val owned = LiveSealedHoldout12Test.OwnedContext(app, UUID.randomUUID().toString())
        val fake = Fake(failed()); fake.onRefresh = { throw expected }
        var cleanup: LiveSealedHoldout12Test.OwnedContext.CleanupResult? = null
        try {
            val preference = owned.getSharedPreferences("normal_retry_contract", Context.MODE_PRIVATE)
            assertTrue(preference.edit().putString("synthetic", "owned-only").commit())
            File(owned.filesDir, "synthetic-owned.txt").writeText("owned-only")
            assertSame(expected, failure { run(fake) })
        } finally { cleanup = owned.cleanup() }
        assertTrue(requireNotNull(cleanup).success); assertTrue(requireNotNull(cleanup).workspaceAttempted)
        assertEquals(1, requireNotNull(cleanup).attemptedPreferences.size)
        assertFalse(owned.owned.exists()); assertEquals(protectedBefore, protectedHashes(app))
    }
    private fun protectedHashes(app: Context): Map<String, String> =
        mapOf("preferences" to File(app.applicationInfo.dataDir, "shared_prefs"),
            "books" to File(app.filesDir, "service-books-v2")).flatMap { (label, directory) ->
            if (!directory.isDirectory) emptyList() else directory.walkTopDown().filter { it.isFile }.map { file ->
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
                }
                "$label/${file.relativeTo(directory).path}" to hash.digest().joinToString("") { "%02x".format(it) }
            }.toList()
        }.toMap().toSortedMap()

    @Test fun interruptionOutsideCoordinatorPreservesLastCompletedAndCompletedBookSource() = runBlocking<Unit> {
        val after = ready(); val fake = Fake(failed(), after)
        val row = linkedMapOf<String, Any?>("activePhase" to "SOURCE")
        run(fake, row)
        row["activePhase"] = "BOOK"
        holdout12MarkSourceInterrupted(row, IOException("synthetic later book failure"))
        assertSame(after, row["finalSourceState"]); assertEquals("COMPLETED", row["sourceWorkflowStatus"])
        row["activePhase"] = "SOURCE"
        holdout12MarkSourceInterrupted(row, CancellationException("synthetic progress close cancellation"))
        assertNull(row["finalSourceState"]); assertSame(after, row["lastCompletedSourceState"])
        assertEquals("CANCELLED", row["sourceWorkflowStatus"])
    }
    @Test fun safeSummaryKeepsDenominatorsTransitionsAndCountersWithoutSelectedObjects() = runBlocking<Unit> {
        val before = failed(); val after = ready(); val fake = Fake(before, after)
        val row = linkedMapOf<String, Any?>("batch" to 4, "stratum" to "no_website", "originalSelected" to selected)
        fake.onRefresh = { ordinal -> fake.started("GET_START"); if (ordinal == 1) before else after }
        run(fake, row)
        val notRun = mapOf<String, Any?>("state" to "NOT_RUN")
        val summary = holdout12SourceSummary(listOf(row, notRun))
        assertEquals(2, summary["denominator"]); assertEquals(1, summary["retryEligible"])
        assertEquals(1, summary["retryAttempted"]); assertEquals(1, summary["retryCompleted"])
        assertEquals(mapOf("UNAVAILABLE->READY" to 1), summary["retryTransitions"])
        assertEquals(mapOf("GET_START" to 2, "MOSCOW_DIRECTORY_SEARCH_START" to 0), summary["observableTransportCounters"])
        val text = gson.toJson(summary)
        assertFalse(text.contains("originalSelected")); assertFalse(text.contains(selected.id)); assertFalse(text.contains("cases"))
        assertEquals(false, summary["applicationSuccess"]); assertEquals("PENDING_PRIMARY_SOURCE_REVIEW", summary["readyInterpretation"])
    }
}
