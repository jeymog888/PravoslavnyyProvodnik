package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.MemoryCommit
import java.time.LocalDate

class BookReadingAtomicTest {
    private val position = BookReadingPosition(ServiceBookKey("2026-11-11"), "a".repeat(64), "temple",
        "prayer", "chapter", fraction = .37f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)

    @Test fun restoringAnotherEditionClearsTheOldTextAndFollowingSpaceTogether() {
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val atGap = position.copy(fraction = 1f, followingGapFraction = .75f)
        assertTrue(store.save(atGap))
        val book = ServiceBook(key = position.key, title = "Литургия", sourceUrl = position.key.sourceUrl(), sourceTitle = "Источник",
            fetchedAtMillis = 1, selectedEventTitle = null, contentHash = position.contentHash,
            chapters = listOf(BookChapter("chapter", "Молитва", "Смысл",
                listOf(BookBlock("prayer", BookBlockKind.PRAYER, null, "Отче наш")))))
        assertEquals(atGap, store.restore(book, position.parishId))
        val restored = requireNotNull(store.restore(book.copy(contentHash = "b".repeat(64)), position.parishId))
        assertEquals(0f, restored.fraction)
        assertEquals(0f, restored.followingGapFraction)
        assertTrue(store.save(restored))
        assertEquals(restored, store.last(position.parishId))
    }

    @Test fun failedWriteRestoresBothPointersAndPreservesUnrelatedChanges() {
        for (failure in listOf(MemoryCommit.FALSE_AFTER_MEMORY, MemoryCommit.THROW_AFTER_MEMORY)) {
            val preferences = AcceptanceMemoryPreferences()
            val store = BookReadingStore(preferences)
            assertTrue(store.save(position))
            preferences.values["unrelated"] = "before"
            val before = preferences.values.toMap()
            val failedCommit = preferences.commitCount + 1
            preferences.outcomes += failure
            preferences.afterMemory = { count -> if (count == failedCommit) preferences.values["unrelated"] = "during" }
            assertFalse(store.save(position.copy(contentHash = "b".repeat(64), fraction = .5f)))
            assertEquals(before + ("unrelated" to "during"), preferences.values)
            assertEquals(position, store.last("temple"))
            assertEquals(failedCommit + 1, preferences.commitCount)
            assertEquals(2, preferences.committedKeys[failedCommit - 1].size)
            assertEquals(preferences.committedKeys[failedCommit - 1], preferences.committedKeys[failedCommit])
            val failed = preferences.memoryAfterCommit[failedCommit - 1].values.filterIsInstance<String>()
                .mapNotNull(BookReadingCodec::decode)
            assertEquals(2, failed.count { it.contentHash == "b".repeat(64) })
        }
    }

    @Test fun rejectedNewPositionAlsoRestoresTheEvictedThirtySecondEntry() {
        val preferences = AcceptanceMemoryPreferences()
        val store = BookReadingStore(preferences)
        for (offset in 0L until 32L) assertTrue(store.save(position.copy(key = position.key.copy(
            date = LocalDate.of(2026, 11, 1).plusDays(offset).toString()))))
        val before = preferences.values.toMap()
        val count = preferences.commitCount
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        assertFalse(store.save(position.copy(key = position.key.copy(date = "2026-12-05"))))
        assertEquals(before, preferences.values)
        assertEquals(32, preferences.values.keys.count { it.startsWith("position:") })
        assertEquals(count + 2, preferences.commitCount)
        assertEquals("The two pointers and only one eviction belong to this write", 3, preferences.committedKeys[count].size)
        assertEquals(preferences.committedKeys[count], preferences.committedKeys[count + 1])
    }

    @Test fun rollbackFailureIsStillFalseAndDoesNotRetryForever() {
        val preferences = AcceptanceMemoryPreferences()
        val store = BookReadingStore(preferences)
        assertTrue(store.save(position))
        val before = preferences.values.toMap()
        val count = preferences.commitCount
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        assertFalse(store.save(position.copy(contentHash = "b".repeat(64))))
        assertEquals(count + 2, preferences.commitCount)
        assertEquals(before, preferences.values)
        assertEquals(position, store.last("temple"))
    }

    @Test fun failedOlderWriteCannotRollbackANewerCoherentPosition() {
        val preferences = AcceptanceMemoryPreferences()
        val store = BookReadingStore(preferences)
        assertTrue(store.save(position))
        val newer = position.copy(contentHash = "c".repeat(64), fraction = .8f)
        val count = preferences.commitCount
        preferences.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        preferences.afterMemory = { commit -> if (commit == count + 1) {
            // An independent writer has installed both newer pointers before this
            // older commit reports failure. Its state must not be rolled back.
            preferences.committedKeys.last().forEach { key -> preferences.values[key] = BookReadingCodec.encode(newer) }
        } }
        assertFalse(store.save(position.copy(contentHash = "b".repeat(64))))
        assertEquals(newer, store.last("temple"))
        assertEquals("No rollback may overwrite the newer group", count + 1, preferences.commitCount)
    }

    @Test fun anUpdateOfferedToAnOlderReaderCannotReplaceAnEditionAcceptedElsewhere() {
        fun book(label: String): ServiceBook {
            val draft = ServiceBook(key = position.key, title = "Литургия", sourceUrl = position.key.sourceUrl(),
                sourceTitle = "Синтетический источник", fetchedAtMillis = 1, selectedEventTitle = null,
                contentHash = "", chapters = listOf(BookChapter("chapter", "Молитва", "Пояснение", (0..15).map {
                    BookBlock(if (it == 0) "prayer" else "prayer-$it", BookBlockKind.PRAYER, null,
                        "Синтетическая редакция $label. Просим помощи и мира для всех людей. ".repeat(12))
                })))
            return draft.copy(contentHash = ServiceBookIntegrity.contentHash(draft)).also {
                assertTrue(ServiceBookIntegrity.valid(it))
            }
        }
        val preferences = AcceptanceMemoryPreferences()
        val store = BookReadingStore(preferences)
        val previous = book("А")
        val offered = book("В")
        val acceptedElsewhere = book("Б")
        val oldPosition = position.copy(contentHash = previous.contentHash)
        val newPosition = position.copy(contentHash = acceptedElsewhere.contentHash, fraction = .8f)
        assertTrue(store.save(oldPosition))
        assertTrue(store.save(newPosition))
        val before = preferences.values.toMap()
        assertFalse(store.acceptVersion(previous, offered, position.parishId, oldPosition))
        assertEquals(before, preferences.values)
        assertEquals(newPosition, store.last(position.parishId))
    }
}
