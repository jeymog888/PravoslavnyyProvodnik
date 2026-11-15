package ru.liturgyguide.ui

import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.ServiceBookSelection
import java.time.LocalDate

internal enum class ChurchReadingEntry { NONE, FULL, COMPACT }

/** Current callbacks and personal reading decide whether a retained chooser still has an action. */
internal fun churchReadingChooser(
    featured: ParishService?, hasPersonalReading: Boolean, hasReadLiturgy: Boolean,
    hasChooser: Boolean, hasOpenService: Boolean, retainedChooser: Boolean? = null
): Boolean = hasChooser && ((retainedChooser ?: (featured == null || !hasOpenService)) ||
    !hasReadLiturgy && !hasPersonalReading)

/** The same effective controls own rendering, the stable reading item and source-review count. */
internal fun churchReadingEntry(
    church: Parish?, date: LocalDate, featured: ParishService?, showLiturgy: Boolean,
    showChooser: Boolean, hasChooser: Boolean, hasOpenService: Boolean, keepFull: Boolean = false
): ChurchReadingEntry {
    if (church == null || !showLiturgy && !showChooser) return ChurchReadingEntry.NONE
    // Match MainActivity.openPublishedBook: evening choice first, then the existing resolver.
    // Its book may belong to tomorrow. Eligibility and chooser ownership use the civil date.
    val currentFeatured = featured?.takeIf { it.date == date.toString() }
    val usable = if (hasOpenService && hasChooser && currentFeatured != null)
        ServiceBookSelection.eveningTexts(currentFeatured, church, WorshipKind.VESPERS)
            ?: ServiceBookSelection.resolve(currentFeatured, church)
        else null
    // A fresh usable publication did not previously add a chooser-only library item.
    if (!showLiturgy && !keepFull && usable != null) return ChurchReadingEntry.NONE
    if (keepFull || usable == null) return ChurchReadingEntry.FULL
    return ChurchReadingEntry.COMPACT
}
