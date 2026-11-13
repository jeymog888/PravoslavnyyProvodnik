package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.MemoryCommit
import java.time.LocalDate

class BookSourceCursorCommitTest {
    private fun position() = PatronalFixtures.parse().let { book ->
        BookReadingPosition(book.key, book.contentHash, "source-cas", book.blocks.first().id,
            book.chapters.first().id, anchorKind = BookAnchorKind.HEADER)
    }

    @Test fun absentDestinationCanBeCommittedOnceButCannotBeReplayedAfterAnotherCursorMove() {
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val at = position()
        assertTrue(store.compareAndSave(at, BookReadingLookup.Absent))
        val changed = at.copy(anchorKind = BookAnchorKind.BLOCK, fraction = .4f)
        assertTrue(store.saveReading(changed))
        assertFalse(store.compareAndSave(at, BookReadingLookup.Absent))
        assertFalse(store.compareAndSave(at, BookReadingLookup.Found(at)))
        assertEquals(changed, store.positionFor(at.key, at.parishId))
        assertEquals(changed, store.last(at.parishId))
    }

    @Test fun expectedExactDestinationIsComparedIncludingItsCursorNotOnlyItsEdition() {
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val at = position()
        assertTrue(store.save(at))
        val next = at.copy(anchorKind = BookAnchorKind.BLOCK, fraction = .2f)
        assertTrue(store.compareAndSave(next, BookReadingLookup.Found(at)))
        assertEquals(next, store.positionFor(at.key, at.parishId))
        assertFalse(store.compareAndSave(at, BookReadingLookup.Unreadable))
        assertEquals(next, store.positionFor(at.key, at.parishId))
    }

    @Test fun failedDiskCommitKeepsBothOldPointers() {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        assertTrue(store.save(at))
        val before = prefs.all
        prefs.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        assertFalse(store.compareAndSave(at.copy(fraction = .3f, anchorKind = BookAnchorKind.BLOCK),
            BookReadingLookup.Found(at)))
        assertEquals(before, prefs.all)
    }

    @Test fun sourceReceiptAndCursorAreOneCommitAndCannotBeReplayedOrUsedForAnotherOwner() {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        val identity = "a".repeat(64)
        assertEquals(BookReadingLookup.Absent, store.lookupDedicationReceipt(identity, at.key, at.parishId))
        assertTrue(store.compareAndSaveDedication(at, BookReadingLookup.Absent, identity))
        val before = prefs.all
        assertEquals(BookReadingLookup.Found(at), BookReadingStore(prefs).lookupDedicationReceipt(identity, at.key, at.parishId))
        assertEquals(BookReadingLookup.Unreadable, store.lookupDedicationReceipt(identity, at.key, "another-parish"))
        assertFalse(store.compareAndSaveDedication(at, BookReadingLookup.Found(at), identity))
        assertEquals(before, prefs.all)
        assertEquals(BookReadingLookup.Unreadable, store.lookupDedicationReceipt("bad", at.key, at.parishId))
        assertFalse(store.compareAndSaveDedication(at, BookReadingLookup.Found(at), "bad"))
        assertFalse(store.compareAndSaveDedication(at.copy(fraction = .3f, anchorKind = BookAnchorKind.BLOCK),
            BookReadingLookup.Found(at), "b".repeat(64)))
        assertTrue(store.compareAndSaveDedication(at, BookReadingLookup.Found(at), "b".repeat(64)))
    }

    @Test fun aFalseOrThrowingDiskCommitCannotLeaveAReceiptWithoutItsAcceptedCursor() {
        for (failure in listOf(MemoryCommit.FALSE_AFTER_MEMORY, MemoryCommit.THROW_AFTER_MEMORY)) {
            val prefs = AcceptanceMemoryPreferences()
            val store = BookReadingStore(prefs)
            val at = position()
            val identity = "c".repeat(64)
            prefs.outcomes += failure
            assertFalse(store.compareAndSaveDedication(at, BookReadingLookup.Absent, identity))
            assertEquals(BookReadingLookup.Absent, store.lookup(at.key, at.parishId))
            assertEquals(BookReadingLookup.Absent, store.lookupDedicationReceipt(identity, at.key, at.parishId))
            assertNull(store.last(at.parishId))
            assertTrue(prefs.all.isEmpty())
        }
    }

    @Test fun ordinaryBoundedCursorEvictionCannotEraseEvidenceOfTheAcceptedSource() {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        val identity = "d".repeat(64)
        assertTrue(store.compareAndSaveDedication(at, BookReadingLookup.Absent, identity))
        // These are explicitly synthetic cursor-only visits; no publication is fabricated.
        for (offset in 1L..33L) assertTrue(store.save(at.copy(key = at.key.copy(
            date = LocalDate.parse(at.key.date).plusDays(offset).toString()))))
        assertEquals(BookReadingLookup.Absent, store.lookup(at.key, at.parishId))
        assertEquals(BookReadingLookup.Found(at), BookReadingStore(prefs).lookupDedicationReceipt(identity, at.key, at.parishId))
        assertFalse(store.compareAndSaveDedication(at, BookReadingLookup.Absent, identity))
        assertEquals(BookReadingLookup.Absent, store.lookup(at.key, at.parishId))
    }

    @Test fun receiptCapacityAndMalformedRecordsFailClosedWithoutEvictingEarlierEvidence() {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        for (index in 0..127) {
            val identity = index.toString(16).padStart(64, '0')
            assertTrue(store.compareAndSaveDedication(at, store.lookup(at.key, at.parishId), identity))
        }
        val before = prefs.all
        assertFalse(store.compareAndSaveDedication(at, BookReadingLookup.Found(at), "f".repeat(64)))
        assertEquals(before, prefs.all)
        assertEquals(BookReadingLookup.Found(at), store.lookupDedicationReceipt("0".repeat(64), at.key, at.parishId))
        prefs.edit().putInt("dedication:${"0".repeat(64)}", 7).commit()
        assertEquals(BookReadingLookup.Unreadable, store.lookupDedicationReceipt("0".repeat(64), at.key, at.parishId))
        assertFalse(store.compareAndSaveDedication(at, BookReadingLookup.Found(at), "0".repeat(64)))
    }
}
