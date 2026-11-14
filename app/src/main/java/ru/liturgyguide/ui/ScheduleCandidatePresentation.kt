package ru.liturgyguide.ui

import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.*

/** A view of raw rows, never a new source identity or a persisted approval. */
internal data class ScheduleCandidateGroup(
    val sourceUrl: String,
    val rows: List<ScheduleSourceCandidate>,
    val representative: ScheduleSourceCandidate,
    val conflicts: List<ScheduleSourceCandidate>,
    val alreadyChosen: Boolean,
    val confirmationCandidate: ScheduleSourceCandidate?,
    val blockedByHistory: Boolean,
) {
    val providerCount: Int get() = rows.map { it.provider }.distinct().size
}

/** Exact public URL only: scheme, hostname, pathname and query keep their meaning. */
internal fun scheduleCandidateGroups(church: Parish, state: InternetScheduleState): List<ScheduleCandidateGroup> {
    if (state.parishId != church.id) return emptyList()
    val rows = state.sourceCandidates.filter { it.validFor(church) }
    val approved = state.siteBinding?.takeIf { it.validFor(church, it.siteUrl, it.evidenceUrl) }
        ?.candidateConfirmation?.candidate
    return rows.groupBy { requireNotNull(ScheduleUrl.publicSource(it.sourceUrl)) }.mapNotNull { (url, members) ->
        val visible = members.filter { it.id !in state.dismissedCandidateIds &&
            (it.id != approved?.id || it.outcome == ScheduleIdentityOutcome.CONFLICT) }
        if (visible.isEmpty()) return@mapNotNull null
        // A dismissed/approved conflict still cannot be bypassed by another row.
        // It remains explainable inside an otherwise visible group.
        val conflicts = rows.filter { it.outcome == ScheduleIdentityOutcome.CONFLICT && candidateCoversSource(it, url) }
        val chosen = approved?.sourceUrl == url
        val eligible = visible.filter { row -> canPresentConfirmation(row, visible, church) }
        // Prefer the raw row that can explain an old address with checked current contacts.
        // The primary action still targets that same raw row, never synthesized metadata.
        val candidate = if (chosen || conflicts.isNotEmpty()) null else
            (eligible.firstOrNull { it.currentContactAddress.isNotBlank() } ?: eligible.firstOrNull())
        val representative = candidate ?: visible.firstOrNull { it.currentContactAddress.isNotBlank() } ?: visible.first()
        ScheduleCandidateGroup(url, members.toList(), representative, conflicts, chosen,
            candidate, candidate?.let { candidateChangeBlockedByHistory(state, it) } == true)
    }
}

private fun canPresentConfirmation(row: ScheduleSourceCandidate, visible: List<ScheduleSourceCandidate>, church: Parish): Boolean =
    visible.count { it.id == row.id } == 1 && row.canConfirm(church)

/** Non-authoritative copy/receipt predicate; confirmation always uses the checked group + repository. */
internal fun hasUnreviewedScheduleSources(state: InternetScheduleState?, church: Parish? = null): Boolean {
    if (church != null) return hasReviewableScheduleSources(church, state)
    if (state == null) return false
    val approved = state.siteBinding?.candidateConfirmation?.candidate
    return state.sourceCandidates.any { row ->
        ScheduleUrl.publicSource(row.sourceUrl) == row.sourceUrl && row.id !in state.dismissedCandidateIds &&
            (row.outcome == ScheduleIdentityOutcome.CONFLICT ||
                row.id != approved?.id && row.sourceUrl != approved?.sourceUrl)
    }
}

internal fun hasReviewableScheduleSources(church: Parish, state: InternetScheduleState?): Boolean =
    state?.takeIf { it.parishId == church.id }?.let { own ->
        scheduleCandidateGroups(church, own).any { !it.alreadyChosen || it.conflicts.isNotEmpty() }
    } == true

