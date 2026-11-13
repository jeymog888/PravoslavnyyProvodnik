package ru.liturgyguide.service.book

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Complete synthetic books verify storage/ownership, not a parish's performed service order. */
class BookOfflinePreparationTest {
    private val active = fixture(WorshipKind.VESPERS, "Открытая вечерня")
    private val other = fixture(WorshipKind.MATINS, "Доступная утреня")
    private val parishId = "temple"
    private val journey = BookJourneyContext(parishId, "2026-11-12", "18:00", "Вечернее богослужение",
        listOf(active.key, other.key), partSet = BookPartSetKind.EVENING_TEXTS)
    private val preferences = AcceptanceMemoryPreferences()
    private val store = BookReadingStore(preferences)
    private val books = ControlledBooks()

    @Test fun acceptedOldEditionsAndTheShownActiveEditionWinWithoutLookingUpLatest() = runBlocking {
        val newerActive = fixture(WorshipKind.VESPERS, "Другая вечерня")
        val newerOther = fixture(WorshipKind.MATINS, "Другая утреня")
        books.publish(active)
        books.publish(other.copy(fetchedAtMillis = 99))
        books.publish(newerActive)
        books.publish(newerOther)
        assertTrue(store.save(position(active)))
        assertTrue(store.save(position(other)))
        val before = preferences.values.toMap()

        val state = inspect()

        assertTrue(state.isComplete)
        assertEquals(2, state.readyCount)
        assertEquals(active.contentHash, state.part(active).savedHash)
        assertEquals(other.contentHash, state.part(other).savedHash)
        assertEquals(BookPartSetKind.EVENING_TEXTS, state.partSet)
        assertTrue(books.latestReads.isEmpty())
        assertTrue(books.loads.isEmpty())
        assertEquals(before, preferences.values)
    }

    @Test fun anUnacceptedCachedNeighbourNeedsAValidDurableCopyButFetchTimeIsNotContent() = runBlocking {
        books.publish(active)
        books.latest[other.key] = other
        val before = preferences.values.toMap()
        val incomplete = inspect()
        assertEquals(BookOfflinePartStatus.MISSING, incomplete.part(other).status)
        assertEquals(1, incomplete.readyCount)
        assertFalse(incomplete.isComplete)
        assertTrue(incomplete.canPrepare)

        books.versions[other.key to other.contentHash] = other.copy(fetchedAtMillis = 123)
        val complete = inspect()
        assertTrue(complete.isComplete)
        assertEquals(other.contentHash, complete.part(other).savedHash)
        assertTrue(books.loads.isEmpty())
        assertEquals(before, preferences.values)
        assertEquals(0, preferences.editCount)

        books.afterVersionRead = { key, _, _ -> if (key == other.key) error("Storage read failed") }
        val unreadableCache = prepare()
        assertEquals(BookOfflinePartStatus.STORAGE_ERROR, unreadableCache.part(other).status)
        assertFalse(unreadableCache.isComplete)
        assertTrue(books.loads.isEmpty())
        assertEquals(before, preferences.values)
    }

    @Test fun aFailedPreparationRetainsTheFirstBookAndOnlyRetriesTheMissingNeighbour() = runBlocking {
        books.publish(active)
        assertTrue(store.save(position(active)))
        val before = preferences.values.toMap()
        val updates = mutableListOf<BookOfflineState>()
        val caller = Thread.currentThread()
        books.loadHandler = { BookLoadResult.Failed(BookFailure.NETWORK, "Нет соединения") }

        val failed = prepare { assertSame(caller, Thread.currentThread()); updates += it }
        assertEquals(1, failed.readyCount)
        assertEquals(BookOfflinePartStatus.READY, failed.part(active).status)
        assertEquals(BookOfflinePartStatus.FAILED, failed.part(other).status)
        assertEquals("Нет соединения", failed.part(other).message)
        assertTrue(failed.canPrepare)
        assertTrue(updates.any { it.part(other).status == BookOfflinePartStatus.SAVING })
        assertEquals(failed, updates.last())
        assertEquals(before, preferences.values)

        books.loadHandler = { key -> assertEquals(other.key, key); books.publish(other); BookLoadResult.Ready(other, false) }
        val retried = prepare()
        assertTrue(retried.isComplete)
        assertEquals(listOf(other.key, other.key), books.loads)
        assertEquals(before, preferences.values)

        // Another publisher may make the actual cache ready despite an old unsaved report.
        books.forget(other.key)
        books.loadHandler = { books.publish(other); BookLoadResult.Ready(other, false, saved = false) }
        val independentlySaved = prepare()
        assertTrue(independentlySaved.isComplete)
        assertNull(independentlySaved.part(other).message)
        assertEquals(before, preferences.values)
    }

    @Test fun missingAcceptedAndUnreadablePointersNeverPermitLatestOrADownload() = runBlocking {
        books.publish(active)
        books.publish(fixture(WorshipKind.MATINS, "Последняя, но не принятая утреня"))
        assertTrue(store.save(position(other)))
        val pointerKey = preferences.values.keys.single { it.startsWith("position:") }
        val originalPointer = requireNotNull(preferences.values[pointerKey])
        for (raw in listOf(originalPointer, 17, "{broken-json")) {
            preferences.values[pointerKey] = raw
            val before = preferences.values.toMap()
            books.latestReads.clear()
            val state = prepare()
            assertEquals(BookOfflinePartStatus.NEEDS_RECOVERY, state.part(other).status)
            assertFalse(state.isComplete)
            assertFalse(state.canPrepare)
            assertTrue(books.latestReads.none { it == other.key })
            assertTrue(books.loads.isEmpty())
            assertEquals(before, preferences.values)
        }
        val before = preferences.values.toMap()
        preferences.throwOnRead = true
        val unreadable = try { prepare() } finally { preferences.throwOnRead = false }
        assertTrue(unreadable.parts.all { it.needsRecovery })
        assertTrue(books.loads.isEmpty())
        assertEquals(before, preferences.values)
    }

    @Test fun anUnsavedActiveBookIsNeverReplacedButItsMissingNeighbourCanStillBePrepared() = runBlocking {
        val anotherActive = fixture(WorshipKind.VESPERS, "Другая сохраненная вечерня")
        books.publish(anotherActive)
        assertTrue(store.save(position(active)))
        val before = preferences.values.toMap()
        books.loadHandler = { key -> assertEquals(other.key, key); books.publish(other); BookLoadResult.Ready(other, false) }

        val state = prepare()

        assertEquals(BookOfflinePartStatus.NEEDS_REFRESH, state.part(active).status)
        assertEquals(1, state.readyCount)
        assertEquals(BookOfflinePartStatus.READY, state.part(other).status)
        assertTrue(state.needsRefresh)
        assertFalse(state.isComplete)
        assertEquals(listOf(other.key), books.loads)
        assertEquals(before, preferences.values)
        assertEquals(anotherActive.contentHash, books.latest[active.key]?.contentHash)
    }

    @Test fun normalCursorMovementDoesNotInvalidateReadinessButAnotherAcceptedActiveHashDoes() = runBlocking {
        books.publish(active)
        books.publish(other)
        assertTrue(store.save(position(active)))
        var moved = false
        books.afterVersionRead = { key, hash, _ ->
            if (key == active.key && hash == active.contentHash && !moved) {
                moved = true
                assertTrue(store.save(position(active).copy(fraction = .77f, mode = BookReadingMode.RUSSIAN)))
            }
        }
        val ready = inspect()
        assertTrue(ready.isComplete)
        assertTrue(moved)
        assertEquals(.77f, requireNotNull(store.positionFor(active.key, parishId)).fraction, .0001f)
        assertEquals(2, preferences.editCount)

        val anotherActive = fixture(WorshipKind.VESPERS, "Уже принятая другая вечерня")
        books.publish(anotherActive)
        assertTrue(store.save(position(anotherActive)))
        val before = preferences.values.toMap()
        val superseded = inspect()
        assertEquals(BookOfflinePartStatus.NEEDS_RECOVERY, superseded.part(active).status)
        assertFalse(superseded.isComplete)
        assertEquals(before, preferences.values)
    }

    @Test fun ownerAndLatestChangesDuringVerificationNeverPromiseThePreviousEdition() = runBlocking {
        val changedOther = fixture(WorshipKind.MATINS, "Уже принятая другая утреня")
        books.publish(active)
        books.publish(other)
        books.publish(changedOther)
        assertTrue(store.save(position(other)))
        val changedPreferences = AtomicReference<Map<String, Any?>>()
        var changed = false
        books.afterVersionRead = { key, hash, _ ->
            if (key == other.key && hash == other.contentHash && !changed) {
                changed = true
                assertTrue(store.save(position(changedOther)))
                changedPreferences.set(preferences.values.toMap())
            }
        }
        val currentOwner = inspect()
        assertTrue(currentOwner.isComplete)
        assertEquals(changedOther.contentHash, currentOwner.part(other).savedHash)
        assertEquals(changedPreferences.get(), preferences.values)

        // The other part's IO can change a previously inspected active owner too.
        val changedActive = fixture(WorshipKind.VESPERS, "Новая принятая вечерня")
        books.publish(changedActive)
        books.afterVersionRead = { key, _, _ ->
            if (key == other.key) {
                assertTrue(store.save(position(changedActive)))
                changedPreferences.set(preferences.values.toMap())
            }
        }
        val activeChanged = inspect()
        assertEquals(BookOfflinePartStatus.NEEDS_RECOVERY, activeChanged.part(active).status)
        assertFalse(activeChanged.isComplete)
        assertEquals(changedPreferences.get(), preferences.values)

        preferences.values.clear()
        books.afterVersionRead = null
        books.latest[other.key] = other
        var published = false
        books.afterLatestRead = { key, _ -> if (key == other.key && !published) {
            published = true
            books.publish(changedOther)
        } }
        val newLatest = inspect()
        assertEquals(changedOther.contentHash, newLatest.part(other).savedHash)
        assertTrue(newLatest.isComplete)
        assertTrue(preferences.values.isEmpty())
    }

    @Test fun invalidUnsavedOrNondurableResponsesRemainFailuresWithoutHidingANewRecovery() = runBlocking {
        books.publish(active)
        assertTrue(store.save(position(active)))
        val before = preferences.values.toMap()
        val replies = listOf<BookLoadResult>(
            BookLoadResult.Ready(other, false, saved = false),
            BookLoadResult.Ready(active, false),
            BookLoadResult.Ready(other.copy(contentHash = "0".repeat(64)), false),
            BookLoadResult.Ready(other, false),
            BookLoadResult.Failed(BookFailure.NETWORK, "Нет соединения", cachedBook = other)
        )
        for (reply in replies) {
            books.forget(other.key)
            books.loadHandler = {
                if (reply is BookLoadResult.Ready && reply.saved && reply.book === other)
                    books.latest[other.key] = other // Deliberately no exact durable copy.
                reply
            }
            val state = prepare()
            assertEquals(BookOfflinePartStatus.FAILED, state.part(other).status)
            assertFalse(state.isComplete)
            assertEquals(1, state.readyCount)
            assertEquals(before, preferences.values)
        }

        books.forget(other.key)
        books.loadHandler = {
            books.versions[other.key to other.contentHash] = other // No usable latest pointer.
            BookLoadResult.Ready(other, false)
        }
        val orphanVersion = prepare()
        assertEquals(BookOfflinePartStatus.FAILED, orphanVersion.part(other).status)
        assertNotNull(orphanVersion.part(other).message)
        assertFalse(orphanVersion.isComplete)
        assertEquals(before, preferences.values)

        books.forget(other.key)
        val acceptedByAnotherOwner = AtomicReference<Map<String, Any?>>()
        books.loadHandler = {
            assertTrue(store.save(position(other)))
            acceptedByAnotherOwner.set(preferences.values.toMap())
            BookLoadResult.Failed(BookFailure.NETWORK, "Нет соединения")
        }
        val recovery = prepare()
        assertEquals(BookOfflinePartStatus.NEEDS_RECOVERY, recovery.part(other).status)
        assertNotEquals("Нет соединения", recovery.part(other).message)
        assertEquals(acceptedByAnotherOwner.get(), preferences.values)
    }

    @Test fun cancellingAPendingDownloadKeepsTheAvailablePartAndDoesNotPublishCompletion() = runBlocking {
        books.publish(active)
        assertTrue(store.save(position(active)))
        val before = preferences.values.toMap()
        val entered = CompletableDeferred<Unit>()
        books.loadHandler = { entered.complete(Unit); awaitCancellation() }
        val updates = mutableListOf<BookOfflineState>()
        var completed = false
        val job = launch { prepare { updates += it }; completed = true }
        entered.await()
        assertTrue(updates.any { it.part(other).status == BookOfflinePartStatus.SAVING })
        job.cancelAndJoin()
        assertFalse(completed)
        assertFalse(updates.any { it.isComplete })
        assertEquals(before, preferences.values)
        assertEquals(active, books.versions[active.key to active.contentHash])
        val reopened = inspect()
        assertEquals(1, reopened.readyCount)
        assertEquals(BookOfflinePartStatus.MISSING, reopened.part(other).status)
    }

    @Test fun anInvalidJourneyCannotBecomeACompleteStandaloneAndAVigilIsOneCombinedBook() = runBlocking {
        books.publish(active)
        val invalid = BookOfflinePreparation.prepare(active, journey.copy(parishId = "another-temple"), parishId, books, store)
        assertNotNull(invalid.contextError)
        assertEquals(1, invalid.total)
        assertEquals(1, invalid.readyCount)
        assertFalse(invalid.isComplete)
        assertFalse(invalid.canPrepare)
        assertTrue(books.loads.isEmpty())
        assertTrue(books.latestReads.none { it == other.key })

        val vigil = fixture(WorshipKind.VIGIL, "Собранное бдение")
        books.publish(vigil)
        val combined = BookOfflinePreparation.prepare(vigil, null, parishId, books, store)
        assertTrue(combined.isComplete)
        assertEquals(1, combined.total)
        assertNull(combined.partSet)
        assertTrue(books.loads.isEmpty())
        assertTrue(preferences.values.isEmpty())
    }

    private suspend fun inspect() = BookOfflinePreparation.inspect(active, journey, parishId, books, store)
    private suspend fun prepare(onState: (BookOfflineState) -> Unit = {}) =
        BookOfflinePreparation.prepare(active, journey, parishId, books, store, onState)
    private fun BookOfflineState.part(book: ServiceBook) = parts.single { it.key == book.key }
    private fun position(book: ServiceBook) = BookReadingPosition(book.key, book.contentHash, parishId,
        book.blocks.first().id, book.chapters.first().id, fraction = .32f,
        journeyContext = journey.takeIf { book.key in it.parts })

    private class ControlledBooks : ServiceBooks {
        val latest = mutableMapOf<ServiceBookKey, ServiceBook>()
        val versions = mutableMapOf<Pair<ServiceBookKey, String>, ServiceBook>()
        val latestReads = CopyOnWriteArrayList<ServiceBookKey>()
        val loads = CopyOnWriteArrayList<ServiceBookKey>()
        var afterLatestRead: ((ServiceBookKey, ServiceBook?) -> Unit)? = null
        var afterVersionRead: ((ServiceBookKey, String, ServiceBook?) -> Unit)? = null
        var loadHandler: suspend (ServiceBookKey) -> BookLoadResult = {
            BookLoadResult.Failed(BookFailure.NETWORK, "Синтетический источник недоступен")
        }
        fun publish(book: ServiceBook) { latest[book.key] = book; versions[book.key to book.contentHash] = book }
        fun forget(key: ServiceBookKey) { latest.remove(key); versions.keys.removeAll { it.first == key } }
        override fun cached(key: ServiceBookKey): ServiceBook? {
            latestReads += key
            return latest[key].also { afterLatestRead?.invoke(key, it) }
        }
        override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? =
            versions[key to contentHash].also { afterVersionRead?.invoke(key, contentHash, it) }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            assertFalse("Preparation is not a forced edition refresh", force)
            loads += key
            return loadHandler(key)
        }
    }

    private fun fixture(kind: WorshipKind, edition: String): ServiceBook {
        val key = ServiceBookKey("2026-11-13", kind, "30")
        val blocks = (0..15).map { index -> BookBlock("prayer-$index", BookBlockKind.PRAYER, "Хор",
            "$edition. Синтетический исходный фрагмент $index для проверки сохранения. ".repeat(12)) }
        val book = ServiceBook(key = key, title = "${kind.title}: $edition", sourceUrl = key.sourceUrl(),
            sourceTitle = "Синтетический источник проверки", fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter", "Раздел", "Объяснение для теста", blocks)), contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }
}
