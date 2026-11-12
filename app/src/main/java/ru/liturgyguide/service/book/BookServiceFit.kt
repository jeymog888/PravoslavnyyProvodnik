package ru.liturgyguide.service.book

import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind

/** A parish's published note can rule out an order, but cannot identify an unmentioned local saint. */
internal object BookServiceFit {
    fun needsPolyeleosChoice(book: ServiceBook, journey: BookJourneyContext?, parishId: String?): Boolean {
        val occurrence = journey?.validatedFor(book.key, parishId) ?: return false
        return occurrence.isEveningTexts && book.key.worship == WorshipKind.MATINS &&
            ServiceBookSelection.hasPublishedEveningPolyeleos(occurrence.title) &&
            !MatinsRankSource.hasPolyeleosOrder(book)
    }

    /** Save the identity with the acknowledgement itself: restored Boolean inputs alone cannot bind a new occurrence. */
    fun polyeleosMismatchIdentity(book: ServiceBook, journey: BookJourneyContext?, parishId: String?): String? {
        if (!needsPolyeleosChoice(book, journey, parishId)) return null
        val occurrence = requireNotNull(journey)
        return BookText.sha256(listOf(book.key.canonical(), book.contentHash, parishId.orEmpty(), occurrence.civilDate,
            occurrence.time, occurrence.title, occurrence.effectivePartSet.name)
            .joinToString("") { "${it.length}:$it" })
    }
}
