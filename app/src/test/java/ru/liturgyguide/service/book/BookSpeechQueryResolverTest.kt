package ru.liturgyguide.service.book

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

class BookSpeechQueryResolverTest {
    private val book = ServiceBook(key = ServiceBookKey("2026-11-11"), title = "Test", sourceUrl = "https://example.com",
        sourceTitle = "Test", fetchedAtMillis = 1, selectedEventTitle = null, chapters = emptyList(), contentHash = "test")

    @Test fun cancelledOldScanCannotPublishAfterNewRecordingEvenWhenWorkerIgnoresCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publications = mutableListOf<String>()
        val fresh = CompletableDeferred<Unit>()
        val ownerThread = Thread.currentThread()
        val resolver = BookSpeechQueryResolver(this) { _, variants ->
            assertNotSame("Book work must not use the owning UI thread", ownerThread, Thread.currentThread())
            if (variants.first() == "old") withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                "old result"
            } else "fresh result"
        }
        val old = resolver.resolve(book, listOf("old", "alternative")) { publications += it }
        try {
            withTimeout(5_000) { entered.await() }
            resolver.cancel() // Same invalidation used by manual editing, closing and a new attempt.
            val replacement = resolver.resolve(book, listOf("new", "alternative")) {
                assertSame(ownerThread, Thread.currentThread())
                publications += it
                fresh.complete(Unit)
            }
            withTimeout(5_000) { fresh.await() }
            release.complete(Unit)
            withTimeout(5_000) { old!!.join(); replacement!!.join() }
            assertEquals(listOf("fresh result"), publications)
        } finally {
            release.complete(Unit)
            resolver.cancel()
        }
    }

    @Test fun closingOrEditingDuringScanLeavesNoLatePublication() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var publications = 0
        val resolver = BookSpeechQueryResolver(this) { _, _ -> withContext(NonCancellable) {
            entered.complete(Unit)
            release.await()
            "late result"
        } }
        val work = resolver.resolve(book, listOf("first", "second")) { publications++ }
        try {
            withTimeout(5_000) { entered.await() }
            resolver.cancel()
            release.complete(Unit)
            withTimeout(5_000) { work!!.join() }
            assertEquals(0, publications)
        } finally {
            release.complete(Unit)
            resolver.cancel()
        }
    }

    @Test fun singleHypothesisDoesNotScheduleAnAlternativeScan() = runBlocking {
        val resolver = BookSpeechQueryResolver(this) { _, _ -> error("Must not scan for one hypothesis") }
        var value: String? = null
        assertNull(resolver.resolve(book, listOf(" Иже Херувимы ", "Иже Херувимы")) { value = it })
        assertEquals("Иже Херувимы", value)
    }
}
