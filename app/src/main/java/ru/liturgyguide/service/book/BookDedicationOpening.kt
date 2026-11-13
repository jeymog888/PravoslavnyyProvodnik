package ru.liturgyguide.service.book

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey

/** A saved parish preference belongs to fresh openings, never to resume or search. */
internal fun BookRoute.withProfileForNewOpening(proof: PatronalEventProof): BookRoute? {
    val current = validated() ?: return null
    if (!proof.isValid() || current.pendingSearchTarget != null || current.pendingDedication != null ||
        current.sourceReturn != null) return null
    fun selected(key: ServiceBookKey): ServiceBookKey = if (key.patronalId == proof.id && key.churchType == proof.churchType) key
        else key.copy(churchType = proof.churchType, patronalId = proof.id, mainEvent = null, secondEvent = null)
    return current.copy(activeKey = selected(current.activeKey),
        journeyContext = current.journeyContext?.let { it.copy(parts = it.parts.map(::selected)) },
        lastSuccessfulKey = null).validated()
}

internal sealed interface BookDedicationPreparation {
    data class Ready(val reusedPosition: Boolean, val matchKind: BookDedicationMatchKind? = null) : BookDedicationPreparation
    data class Failed(val message: String) : BookDedicationPreparation
}

/** Stable across part navigation/recreation, distinct for another explicit source choice. */
internal fun BookRoute.dedicationReceiptIdentity(): String? {
    if (validated() != this || pendingDedication == null || sourceReturn == null) return null
    return BookText.sha256(Gson().toJson(linkedMapOf("schemaVersion" to 1,
        "request" to pendingDedication, "sourceReturn" to sourceReturn, "destination" to activeKey)))
}

/** Suspended cache checks finish before the live-owner check and atomic pointer acceptance. */
internal suspend fun prepareDedicationReading(route: BookRoute, ready: BookLoadResult.Ready,
    expected: BookReadingLookup, books: ServiceBooks, store: BookReadingStore,
    isCurrent: () -> Boolean): BookDedicationPreparation {
    fun failed() = BookDedicationPreparation.Failed("Не удалось сохранить выбранный текст и место чтения. Прежний текст остался доступен")
    val request = route.pendingDedication ?: return failed()
    val receiptIdentity = route.dedicationReceiptIdentity() ?: return failed()
    val previous = route.sourceReturn?.position ?: return failed()
    val book = ready.book
    val snapshot = book.patronalSelection
    if (route.validated() != route || !ready.saved || book.key != route.activeKey ||
        !request.selection.matches(book.key) || !ServiceBookIntegrity.valid(book) ||
        snapshot == null || snapshot.id != request.selection.id || snapshot.mineaId != request.selection.mineaId ||
        snapshot.churchType != request.selection.churchType ||
        expected == BookReadingLookup.Unreadable) return failed()
    val receipt = store.lookupDedicationReceipt(receiptIdentity, book.key, route.parishId)
    if (receipt == BookReadingLookup.Unreadable || receipt is BookReadingLookup.Found && expected == BookReadingLookup.Absent)
        return BookDedicationPreparation.Failed("Выбранный текст уже был подготовлен, но его место чтения недоступно. Вернитесь к прежнему тексту")
    val durable = withContext(Dispatchers.IO) { books.cachedVersion(book.key, book.contentHash) == book }
    if (!durable) return failed()
    currentCoroutineContext().ensureActive()
    if (expected is BookReadingLookup.Found) {
        // A recreated or previously visited destination owns its own exact cursor.
        // Never replace it with the cursor captured before selecting the source.
        if (expected.position.parishId != route.parishId || !validDedicationPosition(expected.position, book) ||
            expected.position.journeyContext != route.journeyContext)
            return BookDedicationPreparation.Failed("У этого текста уже есть другое сохраненное место. Вернитесь к прежнему тексту и откройте сохраненное чтение")
        if (!isCurrent() || store.lookup(book.key, route.parishId) != expected ||
            store.lookupDedicationReceipt(receiptIdentity, book.key, route.parishId) != receipt) return failed()
        if (receipt == BookReadingLookup.Absent && !store.compareAndSaveDedication(expected.position, expected, receiptIdentity))
            return failed()
        return BookDedicationPreparation.Ready(reusedPosition = true)
    }
    val originalDestination = if (previous.key.patronalId == request.selection.id &&
        previous.key.churchType == request.selection.churchType) previous.key else previous.key.copy(
        patronalId = request.selection.id, churchType = request.selection.churchType, mainEvent = null, secondEvent = null)
    val transferred = if (book.key == originalDestination) {
        val oldBook = withContext(Dispatchers.IO) {
            books.cachedVersion(previous.key, previous.contentHash)?.takeIf { validDedicationPosition(previous, it) }
        } ?: return failed()
        currentCoroutineContext().ensureActive()
        BookPositionUpdate.rebaseForDedication(previous, oldBook, book, route.journeyContext)
    } else {
        // Selecting the other part is an ordinary part opening, never a rebase
        // from a Vespers cursor into Matins or into a later selected daily variant.
        val chapter = book.chapters.firstOrNull { it.blocks.any { block -> previous.showAltar || !block.isAltarText } }
            ?: return failed()
        val block = chapter.blocks.first { previous.showAltar || !it.isAltarText }
        BookDedicationPosition(BookReadingPosition(book.key, book.contentHash, route.parishId, block.id, chapter.id,
            mode = previous.mode, wordsPerMinute = previous.wordsPerMinute, showAltar = previous.showAltar,
            anchorKind = BookAnchorKind.HEADER, lastTextMode = previous.lastTextMode,
            journeyContext = route.journeyContext), BookDedicationMatchKind.SECTION_START)
    }
    if (!isCurrent() || !store.compareAndSaveDedication(transferred.position, expected, receiptIdentity)) return failed()
    return BookDedicationPreparation.Ready(reusedPosition = false, matchKind = transferred.matchKind)
}

/** Returning is explicit. An existing accepted cursor wins over the older route snapshot. */
internal suspend fun preparePreviousDedication(route: BookRoute, books: ServiceBooks, store: BookReadingStore,
    isCurrent: () -> Boolean): BookRoute? {
    val previousRoute = route.returnToPreviousSource() ?: return null
    val snapshot = route.sourceReturn?.position ?: return null
    val accepted = store.lookup(previousRoute.activeKey, previousRoute.parishId)
    if (accepted == BookReadingLookup.Unreadable) return null
    val position = (accepted as? BookReadingLookup.Found)?.position ?: snapshot
    val book = withContext(Dispatchers.IO) {
        books.cachedVersion(position.key, position.contentHash)?.takeIf { validDedicationPosition(position, it) }
    } ?: return null
    currentCoroutineContext().ensureActive()
    // A different publication sharing a book key is not this source's return route.
    if (!sameDedicationOccurrence(position.journeyContext, previousRoute.journeyContext)) return null
    val restored = previousRoute.copy(journeyContext = position.journeyContext).validated() ?: return null
    if (!isCurrent() || store.lookup(position.key, position.parishId) != accepted) return null
    if (accepted == BookReadingLookup.Absent && !store.restoreMissing(book, position)) return null
    if (accepted is BookReadingLookup.Found && !store.compareAndSave(position, accepted)) return null
    return restored
}

private fun validDedicationPosition(position: BookReadingPosition, book: ServiceBook): Boolean =
    position.key == book.key && position.contentHash == book.contentHash && ServiceBookIntegrity.valid(book) &&
        position.journeyContext?.validatedFor(position.key, position.parishId) == position.journeyContext &&
        book.chapters.any { it.id == position.chapterId && it.blocks.any { block -> block.id == position.blockId } } &&
        runCatching { BookReadingCodec.decode(BookReadingCodec.encode(position)) != null }.getOrDefault(false)

private fun sameDedicationOccurrence(first: BookJourneyContext?, second: BookJourneyContext?): Boolean {
    if (first == null || second == null) return first == second
    return first.parishId == second.parishId && first.civilDate == second.civilDate && first.time == second.time &&
        first.title == second.title && first.effectivePartSet == second.effectivePartSet && first.parts == second.parts
}
