package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

enum class InternetScheduleStatus { IDLE, READY, NO_WEBSITE, UNSUPPORTED, UNAVAILABLE }
enum class ScheduleSourceChangeBlock { NONE, HISTORY, CONFLICT }
data class SchedulePublication(
    val parishId: String, val timezone: String, val website: String, val sourceUrl: String,
    val fetchedAt: String, val events: List<InternetService>,
    val documentFormat: String = "html", val linkedFrom: String = "",
    val yearInferred: Boolean = false,
    val parserRevision: Int = CURRENT_PARSER_REVISION
) {
    companion object {
        const val LEGACY_PARSER_REVISION = 0
        const val CURRENT_PARSER_REVISION = 1
    }
    val hasCurrentParserRevision: Boolean get() = parserRevision == CURRENT_PARSER_REVISION
    fun forDate(date: LocalDate): List<InternetService> = events.filter { it.date == date.toString() }
    fun isFresh(now: Instant): Boolean = hasCurrentParserRevision && runCatching {
        val age = Duration.between(Instant.parse(fetchedAt), now)
        !age.isNegative && age <= Duration.ofHours(24)
    }.getOrDefault(false)
}

data class InternetScheduleState(
    val parishId: String, val status: InternetScheduleStatus = InternetScheduleStatus.IDLE,
    val website: String = "", val sourceUrl: String = "", val checkedAt: String = "",
    val publication: SchedulePublication? = null, val loading: Boolean = false,
    val configuredWebsite: String = "", val customSource: String = "",
    val siteEvidenceUrl: String = "",
    // A cancellation or conflicting building invalidated old data; this does not assert all services were cancelled.
    val publicationRejected: Boolean = false,
    // A partial withdrawal can coexist with valid events. Keep its source authoritative
    // across failed refreshes instead of reviving cancelled events from another website.
    val sourceHasWithdrawals: Boolean = false,
    val siteBinding: ScheduleSiteBinding? = null,
    // A source identity check is pending, not evidence that services were cancelled or absent.
    val sourceNeedsVerification: Boolean = false,
    val sourceCandidates: List<ScheduleSourceCandidate> = emptyList(),
    val candidateSearchIdentity: String = "",
    val candidateSearchUnavailable: Boolean = false,
    val dismissedCandidateIds: List<String> = emptyList(),
    val candidateObservedIds: List<String> = emptyList(),
    val candidateSearchIncomplete: Boolean = true,
    val sourceChangeBlock: ScheduleSourceChangeBlock = ScheduleSourceChangeBlock.NONE,
)

interface ScheduleCacheStore {
    /** Shared by all instances that write the same storage; no suspension is allowed while held. */
    val coordinationLock: Any get() = this
    fun read(parishId: String): String?
    fun write(parishId: String, value: String)
    /** Completed safety observations must survive cancellation of the next network request. */
    fun writeSafety(parishId: String, value: String) = write(parishId, value)
}

enum class ScheduleImportPhase { IDLE, FINDING_SITE, LOADING_PAGE, READING_DOCUMENT, FINISHED }
data class ScheduleImportProgress(val parishId: String, val phase: ScheduleImportPhase)

interface InternetSchedules {
    val progress: StateFlow<ScheduleImportProgress>? get() = null
    fun cached(parish: Parish): InternetScheduleState
    suspend fun refresh(parish: Parish, now: Instant, force: Boolean = false, sourceOverride: String? = null): InternetScheduleState
    /** A manual action only. Compatibility defaults do not opt into an automatic continuation. */
    suspend fun searchOtherSources(parish: Parish, now: Instant): InternetScheduleState = refresh(parish, now, force = true)
    /** Unsupported implementations perform no automatic network/import action. */
    fun takeSourceContinuation(parish: Parish, now: Instant, delivered: InternetScheduleState, owner: Any): ScheduleSourceContinuation? = null
    suspend fun continueSourceSearch(parish: Parish, now: Instant, ticket: ScheduleSourceContinuation, owner: Any): InternetScheduleState? = null
    suspend fun confirmCandidate(parish: Parish, candidateId: String, now: Instant): InternetScheduleState = cached(parish)
    fun dismissCandidate(parish: Parish, candidateId: String): InternetScheduleState = cached(parish)
}

/** Injectable in UI tests; no production test-environment detection and no implicit network mock. */
object OfflineInternetSchedules : InternetSchedules {
    override fun cached(parish: Parish) = initialScheduleState(parish)
    override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?) = cached(parish)
}

private fun initialScheduleState(parish: Parish): InternetScheduleState {
    val website = ScheduleUrl.publicSource(parish.website).orEmpty()
    return InternetScheduleState(parish.id, website = website, configuredWebsite = website)
}

class InternetScheduleRepository(
    private val transport: ScheduleTransport,
    cache: ScheduleCacheStore,
    private val documentExtractor: ScheduleDocumentExtractor = HtmlScheduleDocumentExtractor,
    siteResolver: ScheduleSiteResolver? = null,
    directoryCache: OfficialDirectoryCacheStore = MemoryOfficialDirectoryCacheStore(),
    // Only the shipped reviewed parish directory may supply independent publisher identities.
    private val curatedPublishers: () -> List<Parish> = { emptyList() }
) : InternetSchedules {
    private val cache: ScheduleCacheStore = CoordinatedScheduleCacheStore(cache)
    private val siteResolver = siteResolver ?: OpenScheduleSiteResolver(transport, directoryCache)
    private val importProgress = MutableStateFlow(ScheduleImportProgress("", ScheduleImportPhase.IDLE))
    override val progress: StateFlow<ScheduleImportProgress> = importProgress
    private val continuationProducer = Any()
    private val activeContinuation = AtomicReference<ScheduleSourceContinuation?>(null)
    private val pendingContinuation = AtomicReference<ScheduleSourceContinuation?>(null)
    private val progressLock = Any()
    private var progressOwner: Any? = null
    private fun claimProgress(): Any = synchronized(progressLock) { Any().also { progressOwner = it } }
    private fun phase(owner: Any, parishId: String, phase: ScheduleImportPhase) = synchronized(progressLock) {
        if (progressOwner === owner) importProgress.value = ScheduleImportProgress(parishId, phase)
    }

    override fun cached(parish: Parish): InternetScheduleState =
        ScheduleCacheCodec.decode(runCatching { cache.read(parish.id) }.getOrNull(), parish)
            ?: initialScheduleState(parish)

    private fun blockedSourceChange(previous: InternetScheduleState, now: Instant,
        reason: ScheduleSourceChangeBlock): InternetScheduleState {
        val next = previous.copy(status = InternetScheduleStatus.UNAVAILABLE, checkedAt = now.toString(), sourceChangeBlock = reason)
        runCatching { cache.write(previous.parishId, ScheduleCacheCodec.encode(next)) }
        return next
    }

    private suspend fun cancelSourceContinuation() {
        val old = activeContinuation.getAndSet(null)
        pendingContinuation.compareAndSet(old, null)
        old?.revokeAndJoin()
    }

    override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState {
        cancelSourceContinuation()
        return refreshInternal(parish, now, force, sourceOverride)
    }

    override fun takeSourceContinuation(parish: Parish, now: Instant, delivered: InternetScheduleState,
        owner: Any): ScheduleSourceContinuation? {
        val ticket = pendingContinuation.getAndSet(null) ?: return null
        val bound = runCatching { ticket.bind(continuationProducer, parish, now, delivered, owner) {
            val raw = cache.read(parish.id)
            raw == ticket.rawSnapshot && ScheduleCacheCodec.decode(raw, parish)?.let {
                permitsAutomaticSourceContinuation(parish, it)
            } == true
        } }.getOrDefault(false)
        if (!bound) { ticket.revoke(); activeContinuation.compareAndSet(ticket, null); return null }
        return ticket
    }

    override suspend fun continueSourceSearch(parish: Parish, now: Instant, ticket: ScheduleSourceContinuation,
        owner: Any): InternetScheduleState? {
        // Consume on the caller before dispatch/suspension, including an invalid owner.
        val job = requireNotNull(currentCoroutineContext()[kotlinx.coroutines.Job])
        val started = runCatching { ticket.begin(continuationProducer, parish, now, owner, job) {
            val raw = cache.read(parish.id)
            activeContinuation.get() === ticket && raw == ticket.rawSnapshot &&
                ScheduleCacheCodec.decode(raw, parish)?.let { permitsAutomaticSourceContinuation(parish, it) } == true
        } }.getOrDefault(false)
        if (!started) { ticket.revoke(); activeContinuation.compareAndSet(ticket, null); return null }
        return try { sourceSearch(parish, now, ticket) }
        finally { ticket.finish(); activeContinuation.compareAndSet(ticket, null) }
    }

    private data class SourceSearchSnapshot(val raw: String?, val state: InternetScheduleState)
    private class StaleSourceSearch : IllegalStateException()

    override suspend fun searchOtherSources(parish: Parish, now: Instant): InternetScheduleState {
        cancelSourceContinuation()
        return sourceSearch(parish, now)
    }

    private suspend fun sourceSearch(parish: Parish, now: Instant,
        automatic: ScheduleSourceContinuation? = null): InternetScheduleState = withContext(Dispatchers.IO) {
        fun unavailable() = cached(parish).copy(candidateSearchUnavailable = true)
        val initial = try {
            synchronized(cache.coordinationLock) {
                automatic?.ensureActive()
                val raw = cache.read(parish.id)
                if (automatic != null && raw != automatic.rawSnapshot) return@withContext cached(parish)
                val state = if (raw == null) initialScheduleState(parish) else
                    ScheduleCacheCodec.decode(raw, parish) ?: return@withContext unavailable()
                SourceSearchSnapshot(raw, state)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withContext unavailable() }
        if (automatic != null && !permitsAutomaticSourceContinuation(parish, initial.state)) return@withContext cached(parish)
        if (!permitsOtherSourceSearch(parish, initial.state))
            return@withContext if (automatic != null) cached(parish)
                else refreshInternal(parish, now, force = true, sourceOverride = null)

        val context = currentCoroutineContext()
        val current = AtomicReference(initial)
        val ownedProgress = if (automatic == null) claimProgress() else null
        fun merged(base: InternetScheduleState, found: ScheduleCandidateDiscovery) =
            mergeCandidateEvidence(parish, base.sourceCandidates, found.candidates.sortedBy {
                // A duplicate positive DTO must not hide a completed explicit negative.
                if (it.outcome == ScheduleIdentityOutcome.CONFLICT && !it.verificationUnavailable) 0 else 1
            })
        fun metadata(base: InternetScheduleState, found: ScheduleCandidateDiscovery) = base.copy(
            sourceCandidates = merged(base, found),
            candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(parish),
            candidateObservedIds = found.observedIds.takeIf { it.isNotEmpty() }?.take(200) ?: base.candidateObservedIds,
            candidateSearchIncomplete = found.incomplete, candidateSearchUnavailable = found.unavailable)
        val excludedUrls = initial.state.sourceCandidates.filter { it.validFor(parish) &&
            it.outcome == ScheduleIdentityOutcome.CONFLICT && !it.verificationUnavailable }.map { it.sourceUrl }.toSet()
        val excludedIds = initial.state.dismissedCandidateIds.toSet() + initial.state.sourceCandidates.filter {
            it.validFor(parish) && it.outcome == ScheduleIdentityOutcome.CONFLICT && !it.verificationUnavailable }.map { it.id }
        if (ownedProgress != null) phase(ownedProgress, parish.id, ScheduleImportPhase.FINDING_SITE)
        try {
            context.ensureActive()
            automatic?.ensureActive()
            val found = siteResolver.forRefresh().candidatesForSourceSearch(parish, excludedUrls, excludedIds,
                useful = { fresh ->
                    val base = current.get().state
                    hasActionableOwnSearchCandidate(parish, base, fresh, merged(base, fresh), excludedUrls)
                },
                onProviderCompleted = { batch ->
                    val negatives = batch.candidates.filter { row -> row.validFor(parish) &&
                        row.outcome == ScheduleIdentityOutcome.CONFLICT && !row.verificationUnavailable }
                    if (negatives.isNotEmpty()) {
                        // AUTHORITY_CONFLICT is the sole non-metadata outcome. Both
                        // completed providers use this exact accepted-ID/source gate.
                        context.ensureActive()
                        synchronized(cache.coordinationLock) {
                            context.ensureActive()
                            automatic?.ensureActive()
                            // Map and Own may complete together. Re-read our own snapshot
                            // under the lock so one durable write cannot make the other
                            // look like an unrelated external CAS mutation.
                            val before = current.get()
                            if (negatives.any { candidateConflictsWithAcceptedSource(it,
                                    before.state.siteBinding, before.state.publication) }) {
                                if (cache.read(parish.id) != before.raw) throw StaleSourceSearch()
                                val next = metadata(before.state, batch).copy(
                                    status = InternetScheduleStatus.UNAVAILABLE, checkedAt = now.toString(),
                                    publication = null, siteBinding = null, publicationRejected = true,
                                    sourceNeedsVerification = true, sourceChangeBlock = ScheduleSourceChangeBlock.CONFLICT)
                                val encoded = ScheduleCacheCodec.encode(next)
                                val decoded = ScheduleCacheCodec.decode(encoded, parish)
                                check(decoded == next) { "Conflict observation did not survive the schedule codec" }
                                cache.writeSafety(parish.id, encoded)
                                check(cache.read(parish.id) == encoded) { "Conflict observation was not saved" }
                                current.set(SourceSearchSnapshot(encoded, requireNotNull(decoded)))
                            }
                        }
                    }
                })
            context.ensureActive()
            val before = current.get()
            val next = metadata(before.state, found)
            val encoded = ScheduleCacheCodec.encode(next)
            val decoded = ScheduleCacheCodec.decode(encoded, parish)
            // METADATA_ONLY must preserve authority/history after serialization too.
            // A new unrelated conflict cannot implicitly revoke an accepted source.
            if (decoded == null || decoded.withoutSearchMetadata() != before.state.withoutSearchMetadata())
                return@withContext unavailable()
            synchronized(cache.coordinationLock) {
                context.ensureActive()
                automatic?.ensureActive()
                if (cache.read(parish.id) != before.raw) return@synchronized cached(parish)
                if (automatic != null && !permitsAutomaticSourceContinuation(parish, before.state))
                    return@synchronized cached(parish)
                context.ensureActive()
                automatic?.ensureActive()
                cache.write(parish.id, encoded)
                check(cache.read(parish.id) == encoded) { "Source suggestions were not saved" }
                decoded
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: StaleSourceSearch) { cached(parish) }
        catch (_: Exception) { unavailable() }
        finally { if (ownedProgress != null) phase(ownedProgress, parish.id, ScheduleImportPhase.FINISHED) }
    }

    override fun dismissCandidate(parish: Parish, candidateId: String): InternetScheduleState {
        activeContinuation.get()?.revoke()
        pendingContinuation.set(null)
        return synchronized(cache.coordinationLock) {
        val previous = cached(parish)
        if (previous.sourceCandidates.none { it.id == candidateId && it.validFor(parish) }) return@synchronized previous
        val next = previous.copy(dismissedCandidateIds = (previous.dismissedCandidateIds + candidateId).distinct().takeLast(40))
        runCatching { cache.write(parish.id, ScheduleCacheCodec.encode(next)) }
        next
        }
    }

    override suspend fun confirmCandidate(parish: Parish, candidateId: String, now: Instant): InternetScheduleState {
        cancelSourceContinuation()
        return withContext(Dispatchers.IO) {
        // A finishing older search cannot replace progress owned by this action.
        claimProgress()
        val previous = cached(parish)
        val candidate = previous.sourceCandidates.singleOrNull { it.id == candidateId }
            ?.takeIf { it.canConfirm(parish) && it.id !in previous.dismissedCandidateIds }
            ?: return@withContext previous
        if (candidateChangeBlockedByHistory(previous, candidate)) {
            // A different candidate has not reconciled anything with the source that withdrew the service.
            // Keep that source, its cancellation/rejection history and its exact accepted binding authoritative.
            return@withContext blockedSourceChange(previous, now, ScheduleSourceChangeBlock.HISTORY)
        }
        if (previous.sourceHasWithdrawals &&
            previous.siteBinding?.candidateConfirmation?.candidate?.id == candidate.id)
            return@withContext refreshInternal(parish, now, force = true, sourceOverride = null)
        // Review is one action. Refresh its public evidence before activating any new events.
        val discovery = try { siteResolver.forRefresh().candidatesForConfirmation(parish, emptySet(), candidate) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ScheduleCandidateDiscovery(unavailable = true) }
        currentCoroutineContext().ensureActive()
        val fresh = mergeCandidateEvidence(parish, previous.sourceCandidates, discovery.candidates)
        val checked = fresh.singleOrNull { it.id == candidate.id }
        val raw = discovery.candidates.singleOrNull { it.id == candidate.id }
        val freshContact = raw?.let { row -> discovery.freshContactEvidence?.get(row.id)?.takeIf { it.matchesFresh(parish, row) } }
        val missingContact = candidate.provider == OwnContactCandidateProvider.PROVIDER && freshContact == null
        val requestedConflict = discovery.candidates.any { it.validFor(parish) && it.provider == candidate.provider &&
            it.outcome == ScheduleIdentityOutcome.CONFLICT && !it.verificationUnavailable && sameCandidateSource(it.sourceUrl, candidate.sourceUrl) }
        if (raw?.canConfirm(parish) != true || checked?.canConfirm(parish) != true || missingContact) {
            val conflict = candidateConflictsWithAcceptedSource(checked, previous.siteBinding, previous.publication) ||
                discovery.candidates.any { it.validFor(parish) && candidateConflictsWithAcceptedSource(it, previous.siteBinding, previous.publication) }
            val next = previous.copy(status = InternetScheduleStatus.UNAVAILABLE, checkedAt = now.toString(),
                publication = previous.publication.takeUnless { conflict },
                sourceNeedsVerification = previous.sourceNeedsVerification || conflict,
                siteBinding = previous.siteBinding.takeUnless { conflict },
                sourceCandidates = fresh.ifEmpty { previous.sourceCandidates }, candidateSearchUnavailable = discovery.unavailable || missingContact,
                sourceChangeBlock = if (requestedConflict || checked?.outcome == ScheduleIdentityOutcome.CONFLICT) ScheduleSourceChangeBlock.CONFLICT
                    else ScheduleSourceChangeBlock.NONE)
            if (conflict) cache.writeSafety(parish.id, ScheduleCacheCodec.encode(next))
            else runCatching { cache.write(parish.id, ScheduleCacheCodec.encode(next)) }
            return@withContext next
        }
        refreshInternal(parish, now, force = true, sourceOverride = null, confirmedCandidate = checked,
            confirmedDiscovery = discovery)
        }
    }

    private suspend fun refreshInternal(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?,
        confirmedCandidate: ScheduleSourceCandidate? = null,
        confirmedDiscovery: ScheduleCandidateDiscovery? = null): InternetScheduleState = withContext(Dispatchers.IO) {
        val ownedProgress = claimProgress()
        val refreshContext = currentCoroutineContext()
        val previous = cached(parish)
        if (confirmedCandidate != null && confirmedCandidate.id in previous.dismissedCandidateIds) return@withContext previous
        // The URL editor and a suggested source are the same change of authority. A
        // manual URL cannot wipe a cancellation that the candidate route must preserve.
        if (sourceOverride != null && previous.sourceHasWithdrawals) {
            val requested = ScheduleUrl.publicSource(sourceOverride)
                ?: return@withContext previous.copy(status = InternetScheduleStatus.UNAVAILABLE)
            if (manualSourceChangeBlockedByHistory(previous, requested))
                return@withContext blockedSourceChange(previous, now, ScheduleSourceChangeBlock.HISTORY)
            // Retrying the accepted source keeps its binding and history rather than
            // recoding it as a changed, unbound custom source.
            return@withContext refreshInternal(parish, now, force = true, sourceOverride = null)
        }
        // Recheck after the fresh identity request too: another refresh may have observed a withdrawal meanwhile.
        if (confirmedCandidate != null && previous.sourceHasWithdrawals) {
            if (candidateChangeBlockedByHistory(previous, confirmedCandidate)) {
                return@withContext blockedSourceChange(previous, now, ScheduleSourceChangeBlock.HISTORY)
            }
            return@withContext refreshInternal(parish, now, force = true, sourceOverride = null)
        }
        val age = runCatching { Duration.between(Instant.parse(previous.checkedAt), now) }.getOrNull()
        val retryAfter = if (previous.status == InternetScheduleStatus.READY) Duration.ofHours(6) else Duration.ofMinutes(5)
        val needsParserRefresh = previous.status == InternetScheduleStatus.READY && previous.publication?.hasCurrentParserRevision == false
        if (!force && !needsParserRefresh && sourceOverride == null && confirmedCandidate == null && age != null && !age.isNegative && age < retryAfter) return@withContext previous
        val date = runCatching { now.atZone(ZoneId.of(parish.timezone)).toLocalDate() }.getOrNull()
            ?: return@withContext previous.copy(status = InternetScheduleStatus.UNAVAILABLE)
        if (sourceOverride != null && ScheduleUrl.publicSource(sourceOverride) == null) return@withContext previous.copy(status = InternetScheduleStatus.UNAVAILABLE)
        val resolverSession = siteResolver.forRefresh()
        val configured = ScheduleUrl.publicSource(parish.website).orEmpty()
        val custom = if (confirmedCandidate != null) "" else sourceOverride?.let(ScheduleUrl::publicSource) ?: previous.customSource
        // URL entry remains available, but cannot turn an already-known wrong house into consent.
        if (custom.isNotBlank() && previous.sourceCandidates.any { it.validFor(parish) &&
                it.outcome == ScheduleIdentityOutcome.CONFLICT && candidateCoversSource(it, custom) })
        {
            val againstAccepted = previous.sourceCandidates.any { candidate -> candidate.validFor(parish) &&
                candidateCoversSource(candidate, custom) &&
                candidateConflictsWithAcceptedSource(candidate, previous.siteBinding, previous.publication) }
            val blocked = previous.copy(status = InternetScheduleStatus.UNAVAILABLE, checkedAt = now.toString(),
                publication = previous.publication.takeUnless { againstAccepted },
                siteBinding = previous.siteBinding.takeUnless { againstAccepted },
                sourceNeedsVerification = previous.sourceNeedsVerification || againstAccepted || previous.publication == null,
                sourceChangeBlock = ScheduleSourceChangeBlock.CONFLICT)
            runCatching { cache.write(parish.id, ScheduleCacheCodec.encode(blocked)) }
            return@withContext blocked
        }
        val changedSource = confirmedCandidate != null || sourceOverride != null && custom != previous.customSource
        var sourceCandidates = previous.sourceCandidates
        var candidateUnavailable = previous.candidateSearchUnavailable
        var candidateObservedIds = previous.candidateObservedIds
        var candidateIncomplete = previous.candidateSearchIncomplete
        fun recordCandidates(found: ScheduleCandidateDiscovery) {
            val checked = mergeCandidateEvidence(parish, sourceCandidates, found.candidates)
            if (checked.isNotEmpty() || !found.unavailable) sourceCandidates = checked
            candidateUnavailable = found.unavailable
            if (found.observedIds.isNotEmpty()) candidateObservedIds = found.observedIds.take(200)
            candidateIncomplete = found.incomplete
        }
        confirmedDiscovery?.let(::recordCandidates)
        var freshContactEvidence = confirmedCandidate?.let { candidate ->
            confirmedDiscovery?.candidates?.singleOrNull { it.id == candidate.id }?.let { raw ->
                confirmedDiscovery?.freshContactEvidence?.get(raw.id)?.takeIf { it.matchesFresh(parish, raw) }
            }
        }
        if (confirmedCandidate?.provider == OwnContactCandidateProvider.PROVIDER && freshContactEvidence == null)
            return@withContext previous.copy(status = InternetScheduleStatus.UNAVAILABLE, candidateSearchUnavailable = true)
        val recheckUserConfirmation = confirmedCandidate == null && sourceOverride == null && custom.isBlank() &&
            previous.siteBinding?.method == ScheduleBindingMethod.USER_CONFIRMED
        // A saved relationship permits offline reading, not a fresh network publication
        // after the monastery may have acquired another church. Recheck it per refresh.
        val recheckChildRelationship = !changedSource && custom.isBlank() && previous.siteBinding?.method in setOf(
            ScheduleBindingMethod.CHILD_CHURCH_OWN_SITE, ScheduleBindingMethod.PUBLISHER_GEOMETRY_CHAIN)
        // Cache validation already requires the configured source to be unchanged. Reuse a
        // confirmed resolved source instead of retrying the same obsolete configured URL.
        // A withdrawal has no publication, but its source must still be retried: returning to
        // the old configured page could resurrect events that the new source cancelled.
        val resolved = previous.website.takeIf { it.isNotBlank() && custom.isBlank() && previous.siteEvidenceUrl.isNotBlank() &&
            (previous.publication?.website == it || previous.publicationRejected ||
                previous.siteBinding?.validFor(parish, it, previous.siteEvidenceUrl) == true) }
        var needsVerification = previous.sourceNeedsVerification && !changedSource || recheckChildRelationship
        var binding = previous.siteBinding.takeUnless { changedSource || needsVerification }
        if (confirmedCandidate != null) {
            require(confirmedCandidate.canConfirm(parish))
            val confirmation = ScheduleCandidateConfirmation(1, ScheduleCandidateConfirmation.TYPE,
                ScheduleEntityIdentity.fingerprint(parish), confirmedCandidate,
                maxOf(now, Instant.parse(confirmedCandidate.checkedAt)).toString())
            binding = ScheduleEntityIdentity.bind(parish, confirmedCandidate.sourceUrl, confirmedCandidate.evidenceUrl,
                confirmedCandidate.name, ScheduleBindingMethod.USER_CONFIRMED,
                ScheduleEntityIdentity.scope(parish.name)).copy(candidateConfirmation = confirmation)
            require(requireNotNull(binding).validFor(parish, confirmedCandidate.sourceUrl, confirmedCandidate.evidenceUrl))
            sourceCandidates = mergeCandidateEvidence(parish, sourceCandidates, listOf(confirmedCandidate))
            needsVerification = false
        }
        var prefetchedSchedule: ScheduleResponse? = null
        var website = if (confirmedCandidate != null) confirmedCandidate.sourceUrl else if (needsVerification) "" else
            custom.takeIf(String::isNotBlank) ?: resolved ?: configured.takeIf(String::isNotBlank) ?: previous.website
        var source = website
        var evidence = confirmedCandidate?.evidenceUrl ?: previous.siteEvidenceUrl.takeUnless { changedSource }.orEmpty()
        var status = InternetScheduleStatus.UNAVAILABLE
        var publication: SchedulePublication? = null
        var rejectedPublication = false
        var sourceHasWithdrawals = previous.sourceHasWithdrawals && !changedSource
        var blockedConflict = false
        var concurrentFastState: InternetScheduleState? = null
        var pendingFastReview: Pair<String?, InternetScheduleState>? = null
        var pendingFastReceipt: FastMapContinuationReceipt? = null
        var safetyObserved = false
        var safetyRetained: SchedulePublication? = null
        val withdrawnDates = mutableSetOf<String>()
        val withdrawnStarts = mutableSetOf<String>()
        var withdrawAll = false
        fun phase(value: ScheduleImportPhase) { this@InternetScheduleRepository.phase(ownedProgress, parish.id, value) }
        fun knownConflict(url: String) = sourceCandidates.any { it.validFor(parish) &&
            it.outcome == ScheduleIdentityOutcome.CONFLICT && !it.verificationUnavailable && candidateCoversSource(it, url) }
        fun recordSafety(checkedWebsite: String, checkedSource: String, safety: ScheduleParseResult,
            checkedEvidence: String, checkedBinding: ScheduleSiteBinding?) {
            // A failed trial of B is not evidence against the already accepted A.
            if (changedSource && knownConflict(checkedSource)) return
            val withdrawal = safety.unresolvedWithdrawal || safety.withdrawsAll || safety.withdrawnDates.isNotEmpty() || safety.withdrawnStarts.isNotEmpty()
            safetyObserved = true
            withdrawnDates += safety.withdrawnDates
            withdrawnStarts += safety.withdrawnStarts
            withdrawAll = withdrawAll || safety.unresolvedWithdrawal || safety.withdrawsAll || safety.safetyConflict ||
                (safety.disposition == ScheduleParseDisposition.REJECTED && !withdrawal)
            safetyRetained = previous.publication?.takeIf { !changedSource &&
                sameCandidateSource(it.website, checkedWebsite) && !withdrawAll }?.let { old ->
                old.copy(events = old.events.filterNot { it.date in withdrawnDates ||
                    "${it.date}T${it.time}" in withdrawnStarts }).takeIf { it.events.isNotEmpty() }
            }
            rejectedPublication = true
            sourceHasWithdrawals = sourceHasWithdrawals || withdrawal
            website = checkedWebsite; source = checkedSource; evidence = checkedEvidence; binding = checkedBinding
            if (knownConflict(checkedSource)) { needsVerification = true; binding = null }
            // Persist before another suspension, using the old fetchedAt for retained rows.
            cache.writeSafety(parish.id, ScheduleCacheCodec.encode(previous.copy(
                status = InternetScheduleStatus.UNAVAILABLE, website = website, sourceUrl = source,
                checkedAt = now.toString(), publication = safetyRetained, configuredWebsite = configured,
                customSource = custom, siteEvidenceUrl = evidence, siteBinding = binding,
                publicationRejected = safetyRetained == null, sourceHasWithdrawals = sourceHasWithdrawals,
                sourceNeedsVerification = needsVerification, sourceCandidates = sourceCandidates,
                candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(parish))))
        }
        fun accept(attempt: SourceAttempt, siteEvidence: String, siteBinding: ScheduleSiteBinding?) {
            website = attempt.website; source = attempt.sourceUrl; evidence = siteEvidence; binding = siteBinding
            publication = attempt.publication; status = attempt.status
            rejectedPublication = attempt.rejected || attempt.hasWithdrawal
            if (attempt.hasWithdrawal || attempt.publication != null) sourceHasWithdrawals = attempt.hasWithdrawal
        }
        try {
            val completed = withTimeoutOrNull(180_000) {
                if (recheckUserConfirmation) {
                    phase(ScheduleImportPhase.FINDING_SITE)
                    val approved = requireNotNull(previous.siteBinding?.candidateConfirmation?.candidate)
                    val current = resolverSession.candidatesForConfirmation(parish, emptySet(), approved)
                    recordCandidates(current)
                    val candidate = sourceCandidates.singleOrNull { it.id == approved.id }
                    val raw = current.candidates.singleOrNull { it.id == approved.id }
                    val contact = raw?.let { row -> current.freshContactEvidence?.get(row.id)?.takeIf { it.matchesFresh(parish, row) } }
                    val missingContact = approved.provider == OwnContactCandidateProvider.PROVIDER && contact == null
                    val sameSourceConflict = current.candidates.any { it.validFor(parish) && it.provider == approved.provider &&
                        it.outcome == ScheduleIdentityOutcome.CONFLICT && !it.verificationUnavailable && sameCandidateSource(it.sourceUrl, approved.sourceUrl) }
                    // Fresh network rows require a fresh public identity check. Offline reading retains the old accepted copy.
                    if (raw?.canConfirm(parish) != true || candidate?.canConfirm(parish) != true || missingContact) {
                        val conflict = candidate?.outcome == ScheduleIdentityOutcome.CONFLICT && candidate?.verificationUnavailable != true || sameSourceConflict
                        val replacement = current.candidates.any { it.provider == approved.provider &&
                            it.providerObjectId == approved.providerObjectId && it.sourceUrl != approved.sourceUrl }
                        if (conflict || replacement) { needsVerification = true; binding = null }
                        if (sameSourceConflict) recordSafety(previous.website, approved.sourceUrl,
                            ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true), previous.siteEvidenceUrl, null)
                        if (missingContact) candidateUnavailable = true
                        status = InternetScheduleStatus.UNAVAILABLE
                        return@withTimeoutOrNull true
                    }
                    freshContactEvidence = contact
                }
                if (website.isNotBlank() && sourceCandidates.any {
                        candidateConflictsWithAcceptedSource(it, binding, previous.publication.takeUnless { changedSource })
                    }) {
                    needsVerification = true; binding = null; status = InternetScheduleStatus.UNAVAILABLE
                    return@withTimeoutOrNull true
                }
                if (recheckChildRelationship) {
                    phase(ScheduleImportPhase.FINDING_SITE)
                    val previousBinding = requireNotNull(previous.siteBinding)
                    val revalidated = if (previousBinding.method == ScheduleBindingMethod.PUBLISHER_GEOMETRY_CHAIN)
                        PublisherGeometryResolver(transport, Clock.fixed(now, ZoneOffset.UTC)).revalidate(parish, previousBinding)
                    else MonasteryChurchSiteVerifier(transport, Clock.fixed(now, ZoneOffset.UTC)).revalidate(parish, previousBinding)
                    when (val checked = revalidated) {
                        is ChildChurchSiteCheck.Confirmed -> {
                            website = checked.site.url; binding = checked.site.binding; evidence = checked.site.evidenceUrl
                            prefetchedSchedule = checked.site.observedSchedule
                            needsVerification = false
                        }
                        is ChildChurchSiteCheck.Unavailable -> {
                            // A failed connection cannot turn a previously verified offline copy
                            // into evidence of a different church, or publish newly fetched rows.
                            website = previous.website; source = previous.sourceUrl; evidence = previous.siteEvidenceUrl
                            binding = previous.siteBinding; needsVerification = false
                            status = InternetScheduleStatus.UNAVAILABLE
                            // The first page was already read to obtain its own navigation.
                            // Even if contacts subsequently fail, an observed withdrawal wins.
                            checked.observedSchedule?.html()?.let { html ->
                                val existing = ScheduleHtmlParser.inspect(html, date, parish)
                                val safety = if (existing.disposition != ScheduleParseDisposition.UNSUPPORTED) existing else
                                    ScheduleDocumentParser.inspect(HtmlScheduleDocumentExtractor.fromHtml(html), date, parish)
                                val withdraws = safety.unresolvedWithdrawal || safety.withdrawsAll || safety.withdrawnDates.isNotEmpty() || safety.withdrawnStarts.isNotEmpty()
                                if (withdraws || safety.safetyConflict || safety.disposition == ScheduleParseDisposition.REJECTED)
                                    recordSafety(website, checked.observedSchedule.url, safety, evidence, binding)
                            }
                            return@withTimeoutOrNull true
                        }
                        ChildChurchSiteCheck.Rejected -> {
                            website = previous.website; source = previous.sourceUrl; evidence = previous.siteEvidenceUrl
                            binding = null; needsVerification = true; status = InternetScheduleStatus.UNAVAILABLE
                            return@withTimeoutOrNull true
                        }
                    }
                }
                if (website.isBlank()) {
                    phase(ScheduleImportPhase.FINDING_SITE)
                    var fastSite: ScheduleSite? = null
                    val coldFastDiscovery = previous.checkedAt.isBlank()
                    val excludedFastCandidateIds = (previous.dismissedCandidateIds + sourceCandidates.filter {
                        it.outcome == ScheduleIdentityOutcome.CONFLICT && !it.verificationUnavailable
                    }.map { it.id }).toSet()
                    // A retry still needs the state-aware Own traversal when an earlier
                    // suggestion was dismissed; only a cold result may stop at its preview.
                    if (sourceOverride == null && confirmedCandidate == null &&
                        (coldFastDiscovery || excludedFastCandidateIds.isNotEmpty()) &&
                        permitsInitialFastDiscovery(parish, previous)) {
                        val snapshot = runCatching { cache.read(parish.id) }
                        val snapshotState = snapshot.getOrNull()?.let { ScheduleCacheCodec.decode(it, parish) }
                            ?: initialScheduleState(parish)
                        if (snapshot.isSuccess && snapshotState == previous) {
                            val early = try { resolverSession.initialFastDiscovery(parish, emptySet(), { found ->
                                val merged = mergeCandidateEvidence(parish, sourceCandidates, found.candidates)
                                hasActionableFastCandidate(parish, previous, found, merged, emptySet())
                            }, excludedFastCandidateIds) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null }
                            currentCoroutineContext().ensureActive()
                            val latest = runCatching { cache.read(parish.id) }
                            if (latest.isFailure || latest.getOrNull() != snapshot.getOrNull()) {
                                // A late proposal cannot erase a newer dismissal, acceptance or withdrawal.
                                concurrentFastState = cached(parish)
                                return@withTimeoutOrNull true
                            }
                            if (early != null) {
                                recordCandidates(early.candidates)
                                fastSite = provedSite(parish, emptySet(), early)?.takeUnless { site ->
                                    sourceCandidates.any { it.outcome == ScheduleIdentityOutcome.CONFLICT &&
                                        candidateCoversSource(it, site.url) }
                                }
                                if (coldFastDiscovery && fastSite == null && hasActionableFastCandidate(parish, previous,
                                        early.candidates, sourceCandidates, emptySet())) {
                                    pendingFastReceipt = early.fastMapReceipt
                                    pendingFastReview = snapshot.getOrNull() to previous.copy(
                                        status = if (candidateUnavailable) InternetScheduleStatus.UNAVAILABLE else InternetScheduleStatus.NO_WEBSITE,
                                        checkedAt = now.toString(), sourceCandidates = sourceCandidates,
                                        candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(parish),
                                        candidateSearchUnavailable = candidateUnavailable, candidateObservedIds = candidateObservedIds,
                                        candidateSearchIncomplete = candidateIncomplete)
                                    return@withTimeoutOrNull true
                                }
                            }
                        } else if (snapshot.isSuccess) {
                            concurrentFastState = cached(parish)
                            return@withTimeoutOrNull true
                        }
                    }
                    // A stale configured website must not bypass the source-identity check.
                    val site = (fastSite ?: lookup(if (needsVerification) parish.copy(website = "") else parish, emptySet(), resolverSession, ::recordCandidates))
                        ?.takeUnless { site ->
                            sourceCandidates.any { it.outcome == ScheduleIdentityOutcome.CONFLICT &&
                                candidateCoversSource(it, site.url) } ||
                            needsVerification && (previous.publicationRejected || previous.sourceHasWithdrawals) &&
                                !ScheduleDocumentLinks.sameSite(previous.website, site.url)
                        }
                    if (site == null && needsVerification) {
                        // Keep the direct link and withdrawal tombstones, but no old publication.
                        website = previous.website; source = previous.sourceUrl
                        status = InternetScheduleStatus.UNAVAILABLE
                        return@withTimeoutOrNull true
                    }
                    website = site?.url.orEmpty(); evidence = site?.evidenceUrl.orEmpty(); binding = site?.binding
                    prefetchedSchedule = site?.observedSchedule
                    if (site != null) needsVerification = false
                }
                source = website
                if (website.isBlank()) {
                    status = if (candidateUnavailable) InternetScheduleStatus.UNAVAILABLE else InternetScheduleStatus.NO_WEBSITE
                    return@withTimeoutOrNull true
                }
                val primaryEvidence = evidence
                val primaryBinding = binding
                val contactResponse = primaryBinding?.candidateConfirmation?.candidate?.let { approved ->
                    freshContactEvidence?.responseForApproved(parish, approved, website)
                }
                val primary = importSource(parish, website, date, now, primaryBinding, prefetchedSchedule ?: contactResponse,
                    freshContactEvidence = freshContactEvidence?.takeIf { proof -> primaryBinding?.candidateConfirmation?.candidate
                        ?.let { proof.matchesApproved(parish, it, website) } == true },
                    sourceConflict = ::knownConflict, importOwner = ownedProgress) { checkedWebsite, checkedSource, safety ->
                    recordSafety(checkedWebsite, checkedSource, safety, primaryEvidence, primaryBinding)
                }
                if (primary.conflictingRoot && changedSource) {
                    blockedConflict = true
                    return@withTimeoutOrNull true
                }
                accept(primary, primaryEvidence, primaryBinding)
                // Neither a manual source nor an explicit rejection/withdrawal is a reason to
                // shop for a different answer. This also protects persisted prior rejections.
                if (primary.publication == null && custom.isBlank() && binding?.method != ScheduleBindingMethod.USER_CONFIRMED && !previous.publicationRejected && !sourceHasWithdrawals &&
                    !primary.rejected && !primary.hasWithdrawal) {
                    phase(ScheduleImportPhase.FINDING_SITE)
                    val alternative = lookup(parish, primary.attemptedUrls + configured, resolverSession, ::recordCandidates)
                        ?.takeUnless { site -> sourceCandidates.any { it.outcome == ScheduleIdentityOutcome.CONFLICT &&
                            candidateCoversSource(it, site.url) } }
                    if (alternative != null) {
                        val next = importSource(parish, alternative.url, date, now, alternative.binding, alternative.observedSchedule,
                            sourceConflict = ::knownConflict, importOwner = ownedProgress) { checkedWebsite, checkedSource, safety ->
                            // A different building at a new candidate cannot invalidate an
                            // older accepted source. Actual withdrawals of this parish can.
                            if (safety.unresolvedWithdrawal || safety.withdrawsAll || safety.withdrawnDates.isNotEmpty() || safety.withdrawnStarts.isNotEmpty())
                                recordSafety(checkedWebsite, checkedSource, safety, alternative.evidenceUrl, alternative.binding)
                        }
                        if (next.publication != null || next.hasWithdrawal) accept(next, alternative.evidenceUrl, alternative.binding)
                    }
                }
                true
            }
            if (completed != true) status = InternetScheduleStatus.UNAVAILABLE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = InternetScheduleStatus.UNAVAILABLE }
        finally { phase(ScheduleImportPhase.FINISHED) }
        currentCoroutineContext().ensureActive()
        concurrentFastState?.let { return@withContext it }
        pendingFastReview?.let { (snapshot, review) ->
            // No suspension between the last check and this metadata-only write.
            return@withContext synchronized(cache.coordinationLock) {
                refreshContext.ensureActive()
                val current = runCatching { cache.read(parish.id) }
                if (current.isFailure || current.getOrNull() != snapshot) cached(parish) else {
                    val encoded = ScheduleCacheCodec.encode(review)
                    val committed = try {
                        refreshContext.ensureActive()
                        cache.write(parish.id, encoded)
                        check(cache.read(parish.id) == encoded)
                        true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    refreshContext.ensureActive()
                    // A restored/cached result never mints a new process receipt.
                    if (committed && snapshot == null && permitsAutomaticSourceContinuation(parish, review) &&
                        pendingFastReceipt?.consume(parish) == true) {
                        val ticket = ScheduleSourceContinuation(continuationProducer, review, encoded,
                            ScheduleEntityIdentity.fingerprint(parish), date.toString(), cache.coordinationLock)
                        activeContinuation.getAndSet(ticket)?.revoke()
                        pendingContinuation.set(ticket)
                    }
                    review
                }
            }
        }
        if (blockedConflict) return@withContext blockedSourceChange(previous, now, ScheduleSourceChangeBlock.CONFLICT)
        val retainedPublication = (publication ?: if (safetyObserved) safetyRetained else
            previous.publication.takeUnless { changedSource || rejectedPublication }).takeUnless { needsVerification }
        val rejection = retainedPublication == null && (rejectedPublication || (!changedSource && previous.publicationRejected))
        // A failed alternative must preserve every part of the accepted binding, not attach
        // a new homepage/evidence to cached events from a different source.
        if (publication == null && retainedPublication != null) {
            website = retainedPublication.website; source = retainedPublication.sourceUrl
            evidence = previous.siteEvidenceUrl
            binding = previous.siteBinding
        }
        if (needsVerification) {
            website = previous.website; source = previous.sourceUrl; evidence = previous.siteEvidenceUrl
            binding = null; status = InternetScheduleStatus.UNAVAILABLE
        }
        val result = InternetScheduleState(parish.id, status, website, retainedPublication?.sourceUrl ?: source, now.toString(),
            retainedPublication, configuredWebsite = configured,
            customSource = custom, siteEvidenceUrl = evidence, publicationRejected = rejection,
            sourceHasWithdrawals = sourceHasWithdrawals, siteBinding = binding, sourceNeedsVerification = needsVerification,
            sourceCandidates = sourceCandidates, candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(parish),
            candidateSearchUnavailable = candidateUnavailable, dismissedCandidateIds = previous.dismissedCandidateIds,
            candidateObservedIds = candidateObservedIds, candidateSearchIncomplete = candidateIncomplete)
        runCatching { cache.write(parish.id, ScheduleCacheCodec.encode(result)) }
        result
    }

    private data class SourceAttempt(val website: String, val sourceUrl: String,
        val status: InternetScheduleStatus, val publication: SchedulePublication?, val attemptedUrls: Set<String>,
        val rejected: Boolean, val hasWithdrawal: Boolean, val conflictingRoot: Boolean = false)

    private suspend fun lookup(parish: Parish, excludedUrls: Set<String>, resolver: ScheduleSiteResolver,
        onCandidates: (ScheduleCandidateDiscovery) -> Unit): ScheduleSite? = try {
        val discovery = resolver.discover(parish, excludedUrls)
        onCandidates(discovery.candidates)
        provedSite(parish, excludedUrls, discovery)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        onCandidates(ScheduleCandidateDiscovery(unavailable = true))
        null
    }

    private fun provedSite(parish: Parish, excludedUrls: Set<String>, discovery: ScheduleSourceDiscovery): ScheduleSite? {
        val excluded = ScheduleSourceExclusions(excludedUrls)
        return discovery.site?.takeIf { discovery.outcome == ScheduleIdentityOutcome.PROVED }?.let { site ->
            val url = ScheduleUrl.publicSource(site.url)
            val evidence = ScheduleUrl.publicSource(site.evidenceUrl)
            if (url == null || evidence == null || excluded.contains(url) || site.binding?.validFor(parish, url, evidence) != true) null
            else site.copy(url = url, evidenceUrl = evidence)
        }
    }

    /** Each attempt has the same small crawl budget; refresh owns the single shared deadline. */
    private suspend fun importSource(parish: Parish, root: String, date: LocalDate, now: Instant, binding: ScheduleSiteBinding?,
        initialResponse: ScheduleResponse? = null,
        freshContactEvidence: PublishedChildContactEvidence? = null,
        sourceConflict: (String) -> Boolean = { false }, importOwner: Any,
        onSafety: (website: String, sourceUrl: String, safety: ScheduleParseResult) -> Unit): SourceAttempt {
        var website = root
        var source = root
        var rejectedPublication = false
        var hasWithdrawal = false
        val navigationAttempt = Any()
        var linkedPermit: LinkedAttachmentNavigationPermit? = null
        fun phase(value: ScheduleImportPhase) { this@InternetScheduleRepository.phase(importOwner, parish.id, value) }
        data class QueuedDocument(val link: ScheduleDocumentLink, val rescue: Boolean = false)
        val pending = ArrayDeque<QueuedDocument>()
        pending += QueuedDocument(ScheduleDocumentLink(website, "", false))
        val visited = mutableSetOf<String>()
        val attempted = mutableSetOf<String>()
        var htmlPages = 0
        var documents = 0
        var rescueHtmlPages = 0
        var rescueDocuments = 0
        // One approved-source attempt owns one small REST budget. Ordinary pages and all
        // directly published documents are exhausted before using this last discovery path.
        val wordpress = ScheduleWordPressDiscovery(transport)
        var wordpressParent: org.jsoup.nodes.Document? = null
        var wordpressAttempted = false
        var anyResponse = false
        var bestEvents = emptyList<InternetService>()
        var bestFormat = "html"
        var bestLinkedFrom = ""
        var bestDedicated = false
        var bestYearInferred = false
        var previousImage: ScheduleDocument? = null
        var previousImageParent = ""
        val withdrawnDates = mutableSetOf<String>()
        val withdrawnStarts = mutableSetOf<String>()
        fun retained(values: List<InternetService>) = values.filterNot {
            it.date in withdrawnDates || "${it.date}T${it.time}" in withdrawnStarts
        }
        fun conflictingRoot(url: String): SourceAttempt {
            // Known identity contradictions are rejection evidence, never withdrawals.
            onSafety(website, url, ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true))
            return SourceAttempt(website, url,
                if (anyResponse) InternetScheduleStatus.UNSUPPORTED else InternetScheduleStatus.UNAVAILABLE,
                null, attempted, rejected = true, hasWithdrawal = hasWithdrawal, conflictingRoot = true)
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (pending.isEmpty()) {
                val parent = wordpressParent
                if (bestEvents.isNotEmpty() || wordpressAttempted || parent == null) break
                wordpressAttempted = true
                phase(ScheduleImportPhase.LOADING_PAGE)
                // Every completed parent's safety result and withdrawals have already
                // passed onSafety below. REST metadata cannot replace that context.
                val found = wordpress.discover(website, parent, date)
                attempted += found.queries.map { it.requestUrl }
                val exclusions = ScheduleSourceExclusions(attempted)
                // Redirect/www aliases can collapse two results into one fetched page.
                // Reserve the two canonical slots at fetch time, not while queuing links.
                found.links.asSequence().filter { !it.document &&
                    ScheduleDocumentLinks.sameSite(website, it.url) && it.linkedFrom == parent.baseUri() &&
                    it.url !in visited && !exclusions.contains(it.url) }
                    .forEach { pending += QueuedDocument(it, rescue = true) }
                if (pending.isEmpty()) break
            }
            val queued = pending.removeFirst()
            val next = queued.link
            if (ScheduleSourceExclusions(attempted).contains(next.url)) continue
            if (next.url in visited) continue
            val rootRequest = next.url == root && next.linkedFrom.isBlank()
            if (sourceConflict(next.url)) {
                if (rootRequest) return conflictingRoot(next.url)
                previousImage = null; previousImageParent = ""
                continue
            }
            if (queued.rescue) {
                if (next.document && rescueDocuments >= 2 || !next.document && rescueHtmlPages >= 2) continue
                if (next.document) rescueDocuments++ else rescueHtmlPages++
            } else {
                if (next.document && documents >= 3 || !next.document && htmlPages >= 3) continue
                if (next.document) documents++ else htmlPages++
            }
            // A link skipped by the ordinary quota was never read. It can still be a
            // canonical REST result, so only mark a URL visited after reserving a slot.
            visited += next.url
            val firstPage = visited.size == 1
            phase(if (next.document) ScheduleImportPhase.READING_DOCUMENT else ScheduleImportPhase.LOADING_PAGE)
            attempted += next.url
            val linkedUse = linkedPermit?.reserve(parish, root, binding, next, queued.rescue, navigationAttempt)
            val response = try {
                initialResponse?.takeIf { firstPage && next.url == root && it.url == root } ?: transport.fetch(next.url)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { previousImage = null; previousImageParent = ""; continue }
            attempted += response.url
            visited += response.url
            anyResponse = true
            // A transport-approved same-site redirect still cannot enter a known wrong
            // building's scope. A rejected child must not invalidate its accepted parent.
            if (sourceConflict(response.url)) {
                if (rootRequest) return conflictingRoot(response.url)
                previousImage = null; previousImageParent = ""
                continue
            }
            val approved = binding?.takeIf { it.method == ScheduleBindingMethod.USER_CONFIRMED }?.candidateConfirmation?.candidate
            val exactFreshRoot = firstPage && rootRequest && next.url == root && next.linkedFrom.isEmpty() && response.url == root &&
                OwnContactChildIdentity.ordinaryOsm(parish) && approved != null &&
                freshContactEvidence?.matchesCurrentResponse(parish, approved, root, response) == true
            // ScheduleHtmlParser itself recursively extracts HTML. Bounds therefore precede both ordinary parsers and response DOM reuse.
            if (exactFreshRoot && ContactNavigationRootAudit.sourceBounds(response) != null) {
                onSafety(website, response.url, ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true))
                rejectedPublication = true; bestEvents = emptyList(); source = response.url
                break
            }
            val linkedRead = linkedUse?.read(response, date, parish, binding,
                runCatching { curatedPublishers() }.getOrDefault(emptyList()), navigationAttempt)
            val linkedResponse = linkedRead != null && linkedUse?.owns(linkedRead) == true
            val html = if (linkedResponse) null else response.html()
            if (html != null || linkedResponse) { previousImage = null; previousImageParent = "" }
            // External hosts are authorized only for a directly linked PDF/image. A
            // filename ending in .pdf cannot turn a foreign HTML page into this parish.
            if ((html != null || linkedResponse) && !ScheduleDocumentLinks.sameSite(website, response.url)) continue
            if (firstPage && html != null) website = response.url
            val format: String
            val parsed: ScheduleParseResult
            var rootAudit: ContactRootAuditResult? = null
            var linkedNavigation = false
            var publicationSource = response.url
            if (linkedResponse) {
                val read = requireNotNull(linkedRead)
                val use = requireNotNull(linkedUse)
                parsed = requireNotNull(use.parsedFor(read))
                format = "html"
                use.linksFor(read).filter { it.url !in visited }.forEach { pending += QueuedDocument(it) }
                linkedNavigation = use.grants(read)
            } else if (html != null) {
                val existing = ScheduleHtmlParser.inspect(html, date, parish)
                val auditFreshRoot = exactFreshRoot && existing.disposition == ScheduleParseDisposition.REJECTED &&
                    existing.events.isEmpty() && existing.withdrawnDates.isEmpty() && existing.withdrawnStarts.isEmpty() &&
                    !existing.withdrawsAll && !existing.unresolvedWithdrawal && !existing.safetyConflict && existing.rejectionReason == null
                val wholeDocument = if (rootAudit == null && (auditFreshRoot || existing.disposition == ScheduleParseDisposition.UNSUPPORTED))
                    HtmlScheduleDocumentExtractor.fromHtml(html) else null
                val documentResult = wholeDocument?.let { ScheduleDocumentParser.inspect(it, date, parish) }
                fun hasSafety(result: ScheduleParseResult) = result.unresolvedWithdrawal || result.safetyConflict || result.withdrawsAll ||
                    result.withdrawnDates.isNotEmpty() || result.withdrawnStarts.isNotEmpty()
                // Preserve both actual results. A stronger actual document safety observation cannot be hidden by a generic HTML rejection.
                parsed = when {
                    hasSafety(existing) -> existing
                    exactFreshRoot && documentResult != null && hasSafety(documentResult) -> documentResult
                    existing.disposition != ScheduleParseDisposition.UNSUPPORTED || rootAudit != null -> existing
                    else -> requireNotNull(documentResult)
                }
                format = "html"
                ScheduleDocumentLinks.find(html).filter { it.url !in visited && (!queued.rescue || it.document) }
                    .forEach { pending += QueuedDocument(it, rescue = queued.rescue) }
                if (auditFreshRoot && rootAudit == null) rootAudit = requireNotNull(freshContactEvidence).auditNavigationRoot(parish,
                    requireNotNull(approved), root, response, existing, requireNotNull(wholeDocument), requireNotNull(documentResult), navigationAttempt)
            } else {
                phase(ScheduleImportPhase.READING_DOCUMENT)
                val document = try { documentExtractor.extract(response) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                val continuation = document?.takeIf { next.linkedFrom.isNotBlank() && next.linkedFrom == previousImageParent }
                    ?.let { current -> previousImage?.let { previous ->
                        ScheduleImageContinuation.join(previous, current, next.linkedFrom, parish)
                    } }
                val selectedDocument = continuation ?: document
                previousImage = selectedDocument?.takeIf { it.format == "image" }
                previousImageParent = next.linkedFrom.takeIf { previousImage != null }.orEmpty()
                if (continuation != null) publicationSource = continuation.sourceUrl
                val inspected = selectedDocument?.let { ScheduleDocumentParser.inspect(it, date, parish) }
                    ?: ScheduleParseResult(ScheduleParseDisposition.UNSUPPORTED)
                format = selectedDocument?.format ?: "unknown"
                parsed = if (selectedDocument != null && continuation == null && !rootRequest && next.document &&
                    next.linkedFrom.isNotBlank() && selectedDocument.sourceUrl == response.url && format == "image" &&
                    response.contentType.substringBefore(';').trim().startsWith("image/", ignoreCase = true)) {
                    val owners = runCatching { curatedPublishers() }.getOrDefault(emptyList())
                    val witness = KnownOtherScheduleVenue.resolve(parish, website, next.linkedFrom, owners)
                        ?: KnownOtherScheduleVenue.fromFreshContact(parish, website, next.linkedFrom,
                            binding?.takeIf { it.method == ScheduleBindingMethod.USER_CONFIRMED }?.candidateConfirmation?.candidate,
                            freshContactEvidence, owners)
                    ScheduleForeignImage.certify(selectedDocument, date, parish, website, next.linkedFrom, witness, inspected)
                } else inspected
            }
            val withdraws = parsed.unresolvedWithdrawal || parsed.withdrawsAll ||
                parsed.withdrawnDates.isNotEmpty() || parsed.withdrawnStarts.isNotEmpty()
            // Preserve the observed withdrawal even when a scope conflict stops this attempt.
            hasWithdrawal = hasWithdrawal || withdraws
            if (parsed.safetyConflict || parsed.unresolvedWithdrawal) {
                // A newer OCR pass can contradict an already-readable page from this same
                // source. Its unknown scope cannot be turned into global withdrawal dates,
                // nor ignored while returning the earlier HTML as a successful publication.
                onSafety(website, publicationSource, parsed)
                rejectedPublication = true
                bestEvents = emptyList(); source = publicationSource
                break
            }
            when (rootAudit?.disposition) {
                ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT -> if (!withdraws) {
                    // This complete response is only an audited navigation entrypoint. Both raw REJECTED results remain unchanged.
                    // Only the private transition from this same successful audit can authorize its exact queued HTML target.
                    linkedPermit = freshContactEvidence?.takeLinkedNavigation(requireNotNull(rootAudit), navigationAttempt)
                    continue
                }
                ContactRootAuditDisposition.OBSERVED_UNCLASSIFIED_SAFETY -> {
                    onSafety(website, publicationSource, ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true))
                    rejectedPublication = true; bestEvents = emptyList(); source = publicationSource
                    break
                }
                else -> Unit
            }
            if (linkedNavigation && !withdraws) {
                // The index grants navigation only. Every attachment retains its complete existing content/venue/safety checks.
                continue
            }
            if (parsed.rejectionReason == ScheduleRejectionReason.OTHER_ENTITY_ONLY &&
                parsed.disposition == ScheduleParseDisposition.REJECTED && parsed.events.isEmpty() &&
                !parsed.safetyConflict && !withdraws && !rootRequest && next.document && format == "image" &&
                next.linkedFrom.isNotBlank() && !sourceConflict(publicationSource)) {
                // A positively identified neighbour's complete image is neither a new
                // selected publication nor a reason to erase the accepted offline schedule.
                previousImage = null; previousImageParent = ""
                continue
            }
            if (parsed.disposition == ScheduleParseDisposition.REJECTED || withdraws) {
                // Report safety evidence immediately: a later timeout must not restore an
                // earlier cached service after this attempt already observed its withdrawal.
                onSafety(website, publicationSource, parsed)
            }
            withdrawnDates += parsed.withdrawnDates
            withdrawnStarts += parsed.withdrawnStarts
            bestEvents = retained(bestEvents)
            if (parsed.disposition == ScheduleParseDisposition.REJECTED) {
                rejectedPublication = true
                if (bestEvents.isEmpty()) source = publicationSource
            }
            if (parsed.withdrawsAll) {
                // Only a truly unscoped withdrawal stops the lookup. A cancelled day must
                // not hide another day's schedule on a linked page.
                bestEvents = emptyList(); source = publicationSource
                break
            }
            if (html != null && !queued.rescue && wordpressParent == null && html.select("link[rel][href]").any {
                    "https://api.w.org/" in it.attr("rel").split(Regex("\\s+"))
                }) wordpressParent = html
            // The relationship proves the parent's main church, not every occasional service
            // on its shared site. Venue and withdrawal checks above retain their precedence.
            val events = retained(parsed.events).filter {
                binding?.method !in setOf(ScheduleBindingMethod.CHILD_CHURCH_OWN_SITE,
                    ScheduleBindingMethod.PUBLISHER_GEOMETRY_CHAIN) || ChildChurchRelation.mainService(it.title)
            }
            val dedicated = next.linkedFrom.isNotBlank() || next.document ||
                Regex("(?iu)(schedule|raspisan|расписан)").containsMatchIn(response.url)
            fun nearest(values: List<InternetService>) = values.filter { it.date >= date.toString() }
                .minOfOrNull { "${it.date}T${it.time}" }
            val candidateNext = nearest(events)
            val bestNext = nearest(bestEvents)
            val prefer = bestEvents.isEmpty() || when {
                candidateNext != null && bestNext == null -> true
                candidateNext == null && bestNext != null -> false
                parsed.yearInferred != bestYearInferred -> !parsed.yearInferred
                candidateNext != bestNext -> requireNotNull(candidateNext) < requireNotNull(bestNext)
                dedicated != bestDedicated -> dedicated
                else -> events.size > bestEvents.size
            }
            if (events.isNotEmpty() && prefer) {
                bestEvents = events; source = publicationSource; bestFormat = format; bestLinkedFrom = next.linkedFrom; bestDedicated = dedicated
                bestYearInferred = parsed.yearInferred
            }
        }
        val publication = bestEvents.takeIf { it.isNotEmpty() }?.let {
            SchedulePublication(parish.id, parish.timezone, website, source, now.toString(), it,
                documentFormat = bestFormat, linkedFrom = bestLinkedFrom, yearInferred = bestYearInferred)
        }
        val status = if (publication != null) InternetScheduleStatus.READY else
            if (anyResponse) InternetScheduleStatus.UNSUPPORTED else InternetScheduleStatus.UNAVAILABLE
        return SourceAttempt(website, source, status, publication, attempted, rejectedPublication, hasWithdrawal)
    }

}

/** Validated cache, including binding and age. Gson constructor bypass must not admit malformed data. */
internal object ScheduleCacheCodec {
    private val gson = Gson()
    fun encode(state: InternetScheduleState): String = gson.toJson(state)
    fun decode(json: String?, parish: Parish): InternetScheduleState? = runCatching {
        require(json != null && json.length <= 200_000)
        val root = JsonParser.parseString(json).asJsonObject
        fun field(key: String) = root.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        require(field("parishId") == parish.id)
        val website = field("website").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
        val source = field("sourceUrl").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
        val configured = field("configuredWebsite").also { require(it == ScheduleUrl.publicSource(parish.website).orEmpty()) }
        val custom = field("customSource").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
        val pendingFlag = root.get("sourceNeedsVerification")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        val checked = field("checkedAt").also { require(it.isNotEmpty() || pendingFlag); if (it.isNotEmpty()) Instant.parse(it) }
        val evidence = field("siteEvidenceUrl").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
        val rejected = root.get("publicationRejected")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        val sourceHasWithdrawals = root.get("sourceHasWithdrawals")?.takeUnless { it.isJsonNull }?.let { flag ->
            require(flag.isJsonPrimitive && flag.asJsonPrimitive.isBoolean)
            require(!flag.asBoolean || website.isNotBlank())
            flag.asBoolean
        } ?: (rejected && website.isNotBlank()) // Old cache did not distinguish rejection from a withdrawal.
        val sourceChangeBlock = runCatching { ScheduleSourceChangeBlock.valueOf(field("sourceChangeBlock")) }
            .getOrDefault(ScheduleSourceChangeBlock.NONE)
        // A malformed/newer proof means re-verification; it must not erase withdrawal history.
        val binding = runCatching {
            val value = root.getAsJsonObject("siteBinding") ?: return@runCatching null
            fun part(key: String) = value.get(key).asString
            val proof = value.get("officialContactProof")?.let {
                require(it.isJsonObject)
                OfficialContactProof.decode(it.asJsonObject)
            }
            val childProof = value.get("childChurchOwnSiteProof")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonObject)
                ChildChurchOwnSiteProof.decode(it.asJsonObject)
            }
            val publisherProof = value.get("publisherGeometryProof")?.takeUnless { it.isJsonNull }?.let {
                require(it.isJsonObject)
                PublisherGeometryProof.decode(it.asJsonObject)
            }
            val confirmation = value.get("candidateConfirmation")?.takeUnless { it.isJsonNull }?.let {
                requireNotNull(ScheduleCandidateConfirmation.decode(it, parish))
            }
            ScheduleSiteBinding(value.get("policyVersion").asInt, part("selectedIdentity"), part("matchedName"),
                ScheduleEntityScope.valueOf(part("matchedScope")), ScheduleBindingMethod.valueOf(part("method")),
                part("siteUrl"), part("evidenceUrl"), proof, childProof, publisherProof, confirmation)
        }.getOrNull()
        val needsVerification = pendingFlag || (custom.isBlank() && (evidence.isNotBlank() || root.get("siteBinding")?.isJsonNull == false) &&
            binding?.validFor(parish, website, evidence) != true)
        val publication = root.get("publication")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { value ->
            fun part(key: String) = value.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            require(part("parishId") == parish.id && part("timezone") == parish.timezone)
            val site = requireNotNull(ScheduleUrl.publicSource(part("website")))
            val url = requireNotNull(ScheduleUrl.publicSource(part("sourceUrl")))
            if (binding?.method == ScheduleBindingMethod.USER_CONFIRMED)
                require(site == website && ScheduleDocumentLinks.sameSite(requireNotNull(binding).siteUrl, site))
            // A child page may use the same www redirect already accepted by the transport.
            val format = part("documentFormat").ifBlank { "html" }.also { require(it in setOf("html", "pdf", "image")) }
            val linkedFrom = part("linkedFrom").also { require(it.isEmpty() || ScheduleUrl.publicSource(it) == it) }
            require(ScheduleDocumentLinks.sameSite(site, url) || (format in setOf("pdf", "image") &&
                linkedFrom.isNotEmpty() && ScheduleDocumentLinks.sameSite(site, linkedFrom)))
            val fetched = part("fetchedAt").also { Instant.parse(it) }
            val array = value.getAsJsonArray("events")
            require(array.size() in 1..200)
            val events = array.map { element ->
                val event = element.asJsonObject
                val date = event.get("date").asString.also { LocalDate.parse(it) }
                val time = event.get("time").asString.also { require(LocalTime.parse(it).second == 0 && it.length == 5) }
                val title = event.get("title").asString.also { require(it.length in 2..300 && it.none(Char::isISOControl)) }
                val kind = event.get("kind").asString.also { require(it in setOf("liturgy", "service_block")) }
                InternetService(date, time, title, kind)
            }
            val publicationDate = Instant.parse(fetched).atZone(ZoneId.of(parish.timezone)).toLocalDate()
            require(events.all { LocalDate.parse(it.date) in publicationDate.minusDays(1)..publicationDate.plusDays(45) })
            val yearInferred = value.get("yearInferred")?.takeUnless { it.isJsonNull }?.let { flag ->
                require(flag.isJsonPrimitive && flag.asJsonPrimitive.isBoolean)
                flag.asBoolean
            } ?: false
            if (yearInferred) require(events.all { java.time.YearMonth.from(LocalDate.parse(it.date)) == java.time.YearMonth.from(publicationDate) })
            // Revision alone cannot invalidate the source/history stored around this publication.
            val revision = value.get("parserRevision")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.toString()?.takeIf { Regex("0|[1-9]\\d*").matches(it) }?.toIntOrNull()
                ?: SchedulePublication.LEGACY_PARSER_REVISION
            SchedulePublication(parish.id, parish.timezone, site, url, fetched, events, format, linkedFrom, yearInferred, revision)
        }
        require(!rejected || publication == null)
        val candidateIdentity = field("candidateSearchIdentity")
        val candidates = if (candidateIdentity == ScheduleEntityIdentity.fingerprint(parish)) {
            val values = root.get("sourceCandidates")?.takeUnless { it.isJsonNull }?.asJsonArray
            require(values == null || values.size() <= 20)
            values?.mapNotNull { ScheduleSourceCandidate.decode(it, parish) }.orEmpty().distinctBy { it.id }
        } else emptyList()
        val dismissed = if (candidateIdentity == ScheduleEntityIdentity.fingerprint(parish))
            root.get("dismissedCandidateIds")?.takeUnless { it.isJsonNull }?.asJsonArray?.also { require(it.size() <= 40) }
                ?.map { it.asString.also { id -> require(id.matches(Regex("[a-f0-9]{64}"))) } }.orEmpty() else emptyList()
        val candidateUnavailable = root.get("candidateSearchUnavailable")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
        } ?: false
        val observedIds = if (candidateIdentity == ScheduleEntityIdentity.fingerprint(parish))
            root.get("candidateObservedIds")?.takeUnless { it.isJsonNull }?.asJsonArray?.also { require(it.size() <= 200) }
                ?.map { it.asString.also { id -> require(id.length in 1..80 && id.none(Char::isISOControl)) } }.orEmpty() else emptyList()
        val incomplete = root.get("candidateSearchIncomplete")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
        } ?: true
        val effectiveNeedsVerification = needsVerification || candidates.any {
            candidateConflictsWithAcceptedSource(it, binding, publication)
        }
        InternetScheduleState(parish.id,
            if (effectiveNeedsVerification) InternetScheduleStatus.UNAVAILABLE else InternetScheduleStatus.valueOf(field("status")),
            website, source, if (effectiveNeedsVerification && !pendingFlag) "" else checked, publication.takeUnless { effectiveNeedsVerification },
            configuredWebsite = configured, customSource = custom, siteEvidenceUrl = evidence, publicationRejected = rejected,
            sourceHasWithdrawals = sourceHasWithdrawals, siteBinding = binding.takeUnless { effectiveNeedsVerification },
            sourceNeedsVerification = effectiveNeedsVerification, sourceCandidates = candidates,
            candidateSearchIdentity = candidateIdentity, candidateSearchUnavailable = candidateUnavailable,
            dismissedCandidateIds = dismissed, candidateObservedIds = observedIds, candidateSearchIncomplete = incomplete,
            sourceChangeBlock = sourceChangeBlock)
    }.getOrNull()
}

/** Fresh source data replaces the bundle, including its provenance. Rejected data must not resurrect it. */
fun Parish.withInternetSchedule(state: InternetScheduleState, now: Instant): Parish {
    if (state.parishId != id) return this
    if (state.sourceNeedsVerification) return copy(schedule = emptyList(), scheduleValidFrom = "", scheduleValidUntil = "",
        website = state.website, sourceUrl = state.sourceUrl, scheduleSource = "Источник требует проверки",
        scheduleCoverage = "", scheduleNote = "Принадлежность расписания этому храму пока не подтверждена. Можно открыть сайт и проверить расписание")
    if (state.publicationRejected) return copy(schedule = emptyList(), scheduleValidFrom = "", scheduleValidUntil = "",
        website = state.website, sourceUrl = state.sourceUrl, scheduleSource = "Страница расписания прихода",
        scheduleCoverage = "", scheduleNote = "Прежнее расписание больше не подтверждено источником")
    val online = state.publication?.takeIf { it.parishId == id && it.timezone == timezone && it.isFresh(now) } ?: return this
    val today = now.atZone(ZoneId.of(timezone)).toLocalDate()
    val events = online.events.filter { LocalDate.parse(it.date) >= today }
    return copy(website = online.website, sourceUrl = online.sourceUrl,
        scheduleSource = if (online.yearInferred) "Время с сайта прихода; год не указан" else "Страница расписания прихода",
        scheduleValidFrom = events.minOfOrNull { it.date }.orEmpty(),
        scheduleValidUntil = events.maxOfOrNull { it.date }.orEmpty(),
        schedule = events.map { ParishService(it.date, it.time, it.title, it.kind) },
        scheduleCoverage = if (online.yearInferred) "Год предположен по месяцу и дням недели" else "События с указанной датой на сайте прихода",
        scheduleNote = (if (online.yearInferred) "Год не указан - проверьте дату в расписании. " else "") +
            (if (online.sourceUrl.startsWith("http://")) "Источник использует HTTP. " else "") +
            "Загружено с сайта ${java.net.URI(online.sourceUrl).host}. Составные службы показаны с временем начала всего блока")
}


internal fun mergeCandidateEvidence(parish: Parish, previous: List<ScheduleSourceCandidate>,
    fresh: List<ScheduleSourceCandidate>): List<ScheduleSourceCandidate> {
    val merged = fresh.filter { it.validFor(parish) }.map { candidate ->
        val acceptedContact = previous.firstOrNull { it.id == candidate.id && it.validFor(parish) &&
            it.currentContactAddress.isNotBlank() && it.outcome == ScheduleIdentityOutcome.INSUFFICIENT }
        if (candidate.verificationUnavailable && acceptedContact != null) return@map acceptedContact
        val known = previous.firstOrNull { it.id == candidate.id && it.validFor(parish) &&
            it.outcome == ScheduleIdentityOutcome.CONFLICT }
        if (known != null && candidate.outcome == ScheduleIdentityOutcome.INSUFFICIENT && candidate.currentContactAddress.isBlank())
            known.copy(checkedAt = candidate.checkedAt)
        else candidate
    }
    // An incomplete/filtered response is not evidence that a previously read conflict disappeared.
    val absentConflicts = previous.filter { old -> old.validFor(parish) && old.outcome == ScheduleIdentityOutcome.CONFLICT &&
        merged.none { it.id == old.id } }
    val absentSuggestions = previous.filter { old -> old.validFor(parish) && old.outcome == ScheduleIdentityOutcome.INSUFFICIENT &&
        merged.none { it.id == old.id } }
    return (absentConflicts + merged + absentSuggestions).distinctBy { it.id }.take(20)
}
