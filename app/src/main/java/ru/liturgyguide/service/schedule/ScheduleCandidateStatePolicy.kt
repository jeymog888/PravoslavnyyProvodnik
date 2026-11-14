package ru.liturgyguide.service.schedule

import java.net.URI
import ru.liturgyguide.model.Parish

/** A failed new suggestion is not evidence against a previously accepted different source. */
internal fun candidateConflictsWithAcceptedSource(candidate: ScheduleSourceCandidate?, binding: ScheduleSiteBinding?,
    publication: SchedulePublication?): Boolean {
    if (candidate?.outcome != ScheduleIdentityOutcome.CONFLICT || candidate.verificationUnavailable) return false
    if (binding?.candidateConfirmation?.candidate?.id == candidate.id) return true
    return listOfNotNull(binding?.siteUrl, publication?.website).any { sameCandidateSource(it, candidate.sourceUrl) }
}

/** WWW/TLS canonicalization may preserve one source; a different pathname or query does not. */
internal fun sameCandidateSource(first: String, second: String): Boolean = runCatching {
    val a = URI(first); val b = URI(second)
    (ScheduleDocumentLinks.sameSite(first, second) || ScheduleDocumentLinks.sameSite(second, first)) &&
        scopePath(a) == scopePath(b) && a.rawQuery == b.rawQuery
}.getOrDefault(false)

private fun scopePath(uri: URI): String = URI(null, null, uri.path.orEmpty().ifBlank { "/" }, null)
    .normalize().path.trimEnd('/').ifBlank { "/" }

/** A conflict scoped to another church's pathname does not condemn an entire shared host. */
internal fun candidateCoversSource(candidate: ScheduleSourceCandidate, requested: String): Boolean = runCatching {
    val source = URI(candidate.sourceUrl); val target = URI(requested)
    require(ScheduleDocumentLinks.sameSite(candidate.sourceUrl, requested) || ScheduleDocumentLinks.sameSite(requested, candidate.sourceUrl))
    val root = scopePath(source)
    val path = scopePath(target)
    (source.rawQuery == null || source.rawQuery == target.rawQuery) &&
        (path == root || path.startsWith(root.trimEnd('/') + "/"))
}.getOrDefault(false)

/** Until dated tombstones exist, preserve actual withdrawal history across source switches. */
internal fun candidateChangeBlockedByHistory(state: InternetScheduleState, candidate: ScheduleSourceCandidate): Boolean =
    state.sourceHasWithdrawals &&
        state.siteBinding?.candidateConfirmation?.candidate?.id != candidate.id

/** URL entry cannot change the authority merely by avoiding the suggested-source interface. */
internal fun manualSourceChangeBlockedByHistory(state: InternetScheduleState, requested: String): Boolean =
    state.sourceHasWithdrawals && listOfNotNull(
        state.siteBinding?.siteUrl, state.publication?.website, state.website.takeIf(String::isNotBlank),
        state.customSource.takeIf(String::isNotBlank)
    ).none { sameCandidateSource(it, requested) }

/** Only a cold ordinary map object may finish discovery before the expensive proof graph. */
internal fun permitsInitialFastDiscovery(parish: Parish, state: InternetScheduleState): Boolean {
    val lat = parish.latitude ?: return false
    val lon = parish.longitude ?: return false
    return Regex("^osm:(node|way|relation):([1-9][0-9]{0,18})$").matches(parish.id) &&
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
        parish.website.isBlank() && state.website.isBlank() && state.sourceUrl.isBlank() &&
        state.configuredWebsite.isBlank() && state.customSource.isBlank() && state.siteEvidenceUrl.isBlank() &&
        state.publication == null && state.siteBinding == null && !state.loading &&
        !state.publicationRejected && !state.sourceHasWithdrawals && !state.sourceNeedsVerification &&
        state.sourceChangeBlock == ScheduleSourceChangeBlock.NONE
}

/** Fresh suggestions can stop the search only when the visible, merged row is actionable. */
internal fun hasActionableFastCandidate(parish: Parish, state: InternetScheduleState,
    fresh: ScheduleCandidateDiscovery, merged: List<ScheduleSourceCandidate>, excludedUrls: Set<String>): Boolean {
    val excluded = ScheduleSourceExclusions(excludedUrls)
    val approved = state.siteBinding?.candidateConfirmation?.candidate?.id
    return fresh.candidates.groupBy { it.id }.values.any { rows ->
        if (rows.size != 1) return@any false
        val raw = rows.single()
        val candidate = merged.singleOrNull { it.id == raw.id } ?: return@any false
        raw.canConfirm(parish) && candidate.canConfirm(parish) &&
            candidate.id !in state.dismissedCandidateIds && candidate.id != approved &&
            !candidateChangeBlockedByHistory(state, candidate) && !excluded.contains(candidate.sourceUrl) &&
            merged.none { it.validFor(parish) && it.outcome == ScheduleIdentityOutcome.CONFLICT &&
                candidateCoversSource(it, candidate.sourceUrl) }
    }
}

/** A retained conflict permits searching for information, never accepting that conflicted source. */
internal fun permitsOtherSourceSearch(parish: Parish, state: InternetScheduleState): Boolean {
    if (!OwnContactChildIdentity.ordinaryOsm(parish) || parish.website.isNotBlank() ||
        state.configuredWebsite.isNotBlank() || state.customSource.isNotBlank() ||
        state.publication != null || state.sourceHasWithdrawals) return false
    val providers = setOf(OfficialMapCandidateProvider.PROVIDER, ElitsyMapCandidateProvider.PROVIDER)
    val acceptedCandidate = state.siteBinding?.takeIf { it.method == ScheduleBindingMethod.USER_CONFIRMED }
        ?.candidateConfirmation?.candidate
    // Retained Map offers must not intercept retry/import of an already accepted
    // Own or other non-Map authority after its schedule leaf temporarily failed.
    if (state.siteBinding != null && acceptedCandidate?.provider !in providers) return false
    val offered = state.sourceCandidates.any { row -> row.provider in providers && row.validFor(parish) &&
        (row.canConfirm(parish) || row.outcome == ScheduleIdentityOutcome.CONFLICT && !row.verificationUnavailable) }
    val accepted = acceptedCandidate?.let { it.provider in providers && it.validFor(parish) } == true
    return offered || accepted
}

/** The predicate mirrors confirmation visibility without turning a grouped URL into authority. */
internal fun hasActionableOwnSearchCandidate(parish: Parish, state: InternetScheduleState,
    fresh: ScheduleCandidateDiscovery, merged: List<ScheduleSourceCandidate>, excludedUrls: Set<String>): Boolean {
    val excluded = ScheduleSourceExclusions(excludedUrls)
    val chosen = state.siteBinding?.takeIf { it.validFor(parish, it.siteUrl, it.evidenceUrl) }
        ?.candidateConfirmation?.candidate?.sourceUrl
    return fresh.candidates.filter { it.provider == OwnContactCandidateProvider.PROVIDER }.groupBy { it.id }.values.any { rows ->
        if (rows.size != 1) return@any false
        val raw = rows.single()
        val candidate = merged.singleOrNull { it.id == raw.id } ?: return@any false
        raw.canConfirm(parish) && candidate.canConfirm(parish) && candidate.id !in state.dismissedCandidateIds &&
            candidate.sourceUrl != chosen && !candidateChangeBlockedByHistory(state, candidate) &&
            !excluded.contains(candidate.sourceUrl) && merged.none { it.validFor(parish) &&
                it.outcome == ScheduleIdentityOutcome.CONFLICT && candidateCoversSource(it, candidate.sourceUrl) }
    }
}

/** Only these five fields are allowed to change in an ordinary metadata search, including after decoding. */
internal fun InternetScheduleState.withoutSearchMetadata(): InternetScheduleState = copy(
    sourceCandidates = emptyList(), candidateSearchIdentity = "", candidateObservedIds = emptyList(),
    candidateSearchIncomplete = true, candidateSearchUnavailable = false)
