package ru.liturgyguide.service

import ru.liturgyguide.model.Parish

/** A bundled default is a legacy recovery address, never a first-visit selection. */
object ChurchSelection {
    fun resolve(
        churches: List<Parish>,
        hasExplicitSelection: Boolean,
        selectedId: String?,
        hasReadingContext: Boolean,
        readingContext: ReadingContext?,
        hasPlaybackSnapshot: Boolean,
        hasLegacyReading: Boolean,
        legacyDefaultId: String?
    ): Parish? {
        if (hasExplicitSelection) return churches.firstOrNull { it.id == selectedId }
        if (hasReadingContext) {
            val context = readingContext?.let(ReadingContextCodec::validated) ?: return null
            return churches.firstOrNull { it.id == context.parishId }
        }
        if (hasPlaybackSnapshot || !hasLegacyReading) return null
        return churches.firstOrNull { it.id == legacyDefaultId }
    }
}
