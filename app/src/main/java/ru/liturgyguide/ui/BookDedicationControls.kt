package ru.liturgyguide.ui

import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.service.book.BookDedicationServices
import ru.liturgyguide.service.book.BookReadingPosition
import ru.liturgyguide.service.book.PatronalProfileLookup

enum class BookDedicationStart { OPENED, STALE_ROUTE, STORAGE_FAILED }

/** The reader measures its cursor; its live parent alone commits the source transaction. */
data class BookDedicationControls(
    val services: BookDedicationServices,
    val parishId: String,
    val parishTitle: String,
    val calendarYear: Int,
    val expectedProfile: PatronalProfileLookup,
    val currentSource: PatronalEventProof?,
    val busy: Boolean = false,
    val message: String? = null,
    val onRetryProfile: (() -> Unit)? = null,
    val beginSourceChange: (PatronalEventProof, PatronalProfileLookup, BookReadingPosition) -> BookDedicationStart,
    val onReturn: (suspend (BookReadingPosition) -> Boolean)? = null,
    val onRememberSource: (() -> Unit)? = null
)
