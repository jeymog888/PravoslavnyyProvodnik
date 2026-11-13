package ru.liturgyguide.service.schedule

import java.net.URI

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
