package ru.liturgyguide.service.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey

enum class BookOfflinePartStatus { READY, MISSING, NEEDS_RECOVERY, NEEDS_REFRESH, STORAGE_ERROR, SAVING, FAILED }

data class BookOfflinePart(
    val key: ServiceBookKey,
    val status: BookOfflinePartStatus,
    val savedHash: String? = null,
    val message: String? = null
) {
    val isReady: Boolean get() = status == BookOfflinePartStatus.READY
    val needsRecovery: Boolean get() = status == BookOfflinePartStatus.NEEDS_RECOVERY
    val needsRefresh: Boolean get() = status == BookOfflinePartStatus.NEEDS_REFRESH
}

/** Availability of the named books, not evidence of their locally performed order. */
data class BookOfflineState(
    val parts: List<BookOfflinePart>,
    val partSet: BookPartSetKind? = null,
    val contextError: String? = null
) {
    val readyCount: Int get() = parts.count { it.isReady }
    val total: Int get() = parts.size
    val isComplete: Boolean get() = contextError == null && parts.isNotEmpty() && parts.all { it.isReady }
    val canPrepare: Boolean get() = contextError == null && parts.none { it.status == BookOfflinePartStatus.SAVING } &&
        parts.any { it.status == BookOfflinePartStatus.MISSING || it.status == BookOfflinePartStatus.FAILED }
    val needsRecovery: Boolean get() = parts.any { it.needsRecovery }
    val needsRefresh: Boolean get() = parts.any { it.needsRefresh }
}

/** Reads accepted editions and saves missing neighbours; never accepts an edition or writes a cursor. */
object BookOfflinePreparation {
    suspend fun inspect(activeBook: ServiceBook, journey: BookJourneyContext?, parishId: String?,
        books: ServiceBooks, store: BookReadingStore): BookOfflineState = withContext(Dispatchers.IO) {
        val context = journey?.validatedFor(activeBook.key, parishId)
        val contextError = when {
            !activeBook.key.isValid() -> "Не удалось проверить выбранный текст"
            journey != null && context == null -> "Не удалось проверить состав выбранной службы"
            else -> null
        }
        val keys = context?.parts ?: listOf(activeBook.key)
        val verified = keys.map { key -> inspectPart(key, activeBook, parishId, books, store) }

        // Inspecting a later part must not leave an earlier READY tied to a stale owner.
        // Latest matters only for an unaccepted neighbour, never for the visible book.
        val latestStillMatches = verified.filter { it.part.isReady && it.part.key != activeBook.key &&
            it.owner == BookReadingLookup.Absent }.associate { checked ->
            currentCoroutineContext().ensureActive()
            checked.part.key to try {
                matches(books.cached(checked.part.key), checked.part.key, checked.part.savedHash)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }
        val parts = verified.map { checked ->
            currentCoroutineContext().ensureActive()
            val part = checked.part
            if (!part.isReady) part else {
                when (val owner = store.lookup(part.key, parishId)) {
                    BookReadingLookup.Unreadable -> recovery(part.key, "Не удалось проверить сохраненное место")
                    is BookReadingLookup.Found -> if (owner.position.contentHash == part.savedHash) part
                        else if (part.key == activeBook.key) recovery(part.key, "Выбрана другая сохраненная редакция этого текста")
                        else changed(part.key)
                    BookReadingLookup.Absent -> if (part.key == activeBook.key ||
                        checked.owner == BookReadingLookup.Absent && latestStillMatches[part.key] == true) part
                        else changed(part.key)
                }
            }
        }
        BookOfflineState(parts, context?.effectivePartSet, contextError)
    }

    suspend fun prepare(activeBook: ServiceBook, journey: BookJourneyContext?, parishId: String?,
        books: ServiceBooks, store: BookReadingStore, onState: (BookOfflineState) -> Unit = {}): BookOfflineState {
        var state = inspect(activeBook, journey, parishId, books, store)
        onState(state)
        if (state.contextError != null) return state
        val failures = mutableMapOf<ServiceBookKey, String>()
        for (key in state.parts.map { it.key }.filter { it != activeBook.key }) {
            currentCoroutineContext().ensureActive()
            state = inspect(activeBook, journey, parishId, books, store)
            val part = state.parts.single { it.key == key }
            if (part.status != BookOfflinePartStatus.MISSING) continue
            // Recheck just before starting the request. A newly accepted edition is
            // never a reason to download a replacement through this preparation path.
            if (store.lookup(key, parishId) != BookReadingLookup.Absent) continue
            onState(withFailures(state.copy(parts = state.parts.map {
                if (it.key == key) BookOfflinePart(key, BookOfflinePartStatus.SAVING) else it
            }), failures))
            val failure = try {
                val loaded = withContext(Dispatchers.IO) { books.load(key, force = false) }
                currentCoroutineContext().ensureActive()
                when (loaded) {
                    is BookLoadResult.Failed -> loaded.message
                    is BookLoadResult.Ready -> when {
                        !loaded.saved || !matches(loaded.book, key, loaded.book.contentHash) ->
                            "Не удалось сохранить полученный текст на телефоне"
                        !withContext(Dispatchers.IO) {
                            matches(books.cachedVersion(key, loaded.book.contentHash), key, loaded.book.contentHash)
                        } -> "Не удалось подтвердить сохранение текста на телефоне"
                        else -> null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Не удалось сохранить текст. Попробуйте еще раз" }
            currentCoroutineContext().ensureActive()
            if (failure != null) failures[key] = failure.take(1_000).ifBlank { "Не удалось сохранить текст" }
            val inspected = inspect(activeBook, journey, parishId, books, store)
            // A version file without a usable latest pointer still cannot reopen an
            // unaccepted book. Show this outcome rather than quietly returning MISSING.
            if (inspected.parts.single { it.key == key }.status == BookOfflinePartStatus.MISSING && key !in failures)
                failures[key] = "Не удалось подтвердить сохранение текста на телефоне"
            state = withFailures(inspected, failures)
            onState(state)
        }
        // A successful load count cannot establish readiness. Read every required
        // edition again, retaining a failure only where the part is still missing.
        return withFailures(inspect(activeBook, journey, parishId, books, store), failures).also(onState)
    }

    private data class Verified(val part: BookOfflinePart, val owner: BookReadingLookup)

    private suspend fun inspectPart(key: ServiceBookKey, active: ServiceBook, parishId: String?,
        books: ServiceBooks, store: BookReadingStore): Verified {
        var owner = store.lookup(key, parishId)
        return try {
            if (key == active.key) {
                if (!matches(active, key, active.contentHash))
                    return Verified(refresh(key), owner)
                if (owner == BookReadingLookup.Unreadable)
                    return Verified(recovery(key, "Не удалось проверить сохраненное место"), owner)
                if ((owner as? BookReadingLookup.Found)?.position?.contentHash?.let { it != active.contentHash } == true)
                    return Verified(recovery(key, "Выбрана другая сохраненная редакция этого текста"), owner)
                val saved = matches(books.cachedVersion(key, active.contentHash), key, active.contentHash)
                currentCoroutineContext().ensureActive()
                owner = store.lookup(key, parishId)
                val part = when {
                    owner == BookReadingLookup.Unreadable -> recovery(key, "Не удалось проверить сохраненное место")
                    (owner as? BookReadingLookup.Found)?.position?.contentHash?.let { it != active.contentHash } == true ->
                        recovery(key, "Выбрана другая сохраненная редакция этого текста")
                    saved -> ready(key, active.contentHash)
                    else -> refresh(key)
                }
                return Verified(part, owner)
            }
            // One retry lets a first bookmark or a changed accepted hash settle without
            // treating ordinary movement within the same edition as an owner change.
            repeat(2) {
                if (owner == BookReadingLookup.Unreadable)
                    return Verified(recovery(key, "Не удалось проверить сохраненное место"), owner)
                val acceptedHash = (owner as? BookReadingLookup.Found)?.position?.contentHash
                val cached = if (acceptedHash == null) books.cached(key) else books.cachedVersion(key, acceptedHash)
                val candidate = cached?.takeIf { matches(it, key, acceptedHash ?: it.contentHash) }
                val durable = candidate != null && (acceptedHash != null ||
                    matches(books.cachedVersion(key, candidate.contentHash), key, candidate.contentHash))
                val latestMatches = if (acceptedHash == null && durable)
                    matches(books.cached(key), key, candidate?.contentHash) else false
                currentCoroutineContext().ensureActive()
                val after = store.lookup(key, parishId)
                if (!sameEdition(owner, after)) { owner = after; return@repeat }
                if (acceptedHash != null) return Verified(if (durable) ready(key, acceptedHash)
                    else recovery(key, "Сохраненная редакция недоступна на телефоне"), after)
                if (!durable) return Verified(BookOfflinePart(key, BookOfflinePartStatus.MISSING), after)
                if (latestMatches) return Verified(ready(key, requireNotNull(candidate).contentHash), after)
                owner = after
            }
            Verified(changed(key), owner)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Verified(BookOfflinePart(key, BookOfflinePartStatus.STORAGE_ERROR,
            message = "Не удалось проверить загрузку. Попробуйте еще раз"), owner) }
    }

    private fun sameEdition(first: BookReadingLookup, second: BookReadingLookup): Boolean = when (first) {
        BookReadingLookup.Absent -> second == BookReadingLookup.Absent
        BookReadingLookup.Unreadable -> second == BookReadingLookup.Unreadable
        is BookReadingLookup.Found -> second is BookReadingLookup.Found &&
            first.position.contentHash == second.position.contentHash
    }

    private fun matches(book: ServiceBook?, key: ServiceBookKey, hash: String?): Boolean =
        book != null && hash != null && book.key == key && book.contentHash == hash && ServiceBookIntegrity.valid(book)

    private fun ready(key: ServiceBookKey, hash: String) = BookOfflinePart(key, BookOfflinePartStatus.READY, hash)
    private fun recovery(key: ServiceBookKey, message: String) = BookOfflinePart(key, BookOfflinePartStatus.NEEDS_RECOVERY, message = message)
    private fun refresh(key: ServiceBookKey) = BookOfflinePart(key, BookOfflinePartStatus.NEEDS_REFRESH,
        message = "Открытый текст пока не сохранен. Обновите его")
    private fun changed(key: ServiceBookKey) = BookOfflinePart(key, BookOfflinePartStatus.STORAGE_ERROR,
        message = "Сохраненный текст изменился. Проверьте загрузку еще раз")

    private fun withFailures(state: BookOfflineState, failures: Map<ServiceBookKey, String>) = state.copy(parts = state.parts.map {
        val failure = failures[it.key]
        if (failure != null && it.status == BookOfflinePartStatus.MISSING)
            it.copy(status = BookOfflinePartStatus.FAILED, message = failure) else it
    })
}
