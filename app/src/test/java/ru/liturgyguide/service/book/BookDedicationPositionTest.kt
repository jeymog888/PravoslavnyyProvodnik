package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.MemoryCommit
import java.time.LocalDate

/** Genuine source hymns are the anchors; synthetic mutations are confined to negative checks. */
class BookDedicationPositionTest {
    private val previous by lazy { ServiceBookParser().parse(PatronalFixtures.document("nicholas-may-default"),
        PatronalFixtures.key().copy(patronalId = null), 12345) }
    private val next by lazy { PatronalFixtures.parse() }
    private val parish = "source-position-temple"

    @Test fun bothRealMissingTempleHymnsContinueAtTheirNewPrayerInsteadOfTheBookOpening() {
        for (label in listOf("тропарь храма", "кондак храма")) {
            val at = slotPosition(label)
            val result = BookPositionUpdate.rebaseForDedication(at, previous, next)
            assertEquals(BookDedicationMatchKind.KNOWN_HYMN, result.matchKind)
            assertEquals(PatronalFixtures.templePrayer(next, label).id, result.position.blockId)
            assertEquals(at.chapterId, result.position.chapterId)
            assertEquals(next.key, result.position.key)
            assertEquals(next.contentHash, result.position.contentHash)
            assertEquals(0f, result.position.fraction)
            assertEquals(0f, result.position.followingGapFraction)
            assertEquals(BookAnchorKind.BLOCK, result.position.anchorKind)
            assertEquals(at.mode, result.position.mode)
            assertEquals(at.wordsPerMinute, result.position.wordsPerMinute)
            assertEquals(at.showAltar, result.position.showAltar)
            assertNotEquals(next.blocks.first().id, result.position.blockId)
            assertEquals(previous, ServiceBookParser().parse(PatronalFixtures.document("nicholas-may-default"), previous.key, 12345))
            assertEquals(next, PatronalFixtures.parse())
        }
    }

    @Test fun aRemainingKontakionGapCannotTakeTheTroparionCursorThroughARenumberedId() {
        val document = PatronalFixtures.document("nicholas-may-patronal")
        val label = document.select("label.bg_worships_info").single { BookText.normalized(it.text()) == "кондак храма" }
        var prayer = requireNotNull(label.parent()).nextElementSibling()
        while (prayer != null && prayer.tagName() != "p") prayer = prayer.nextElementSibling()
        requireNotNull(prayer)
        assertTrue(BookText.normalized(prayer.text()).startsWith("взыдоша яко звезда"))
        // A partial source result fills the troparion but keeps the genuine source placeholder at the kontakion.
        prayer.html(" ... <em>Укажите в настройках престольный праздник вашего храма</em> ... ")
        val partial = ServiceBookParser().parse(document, next.key, 12345, patronalSelection = PatronalFixtures.proof())
        assertTrue(ServiceBookIntegrity.valid(partial))
        val at = slotPosition("тропарь храма")
        val remaining = partial.blocks.single { it.kind == BookBlockKind.LOCAL_VARIATION }
        assertEquals("The remaining kontakion now owns the old troparion's occurrence ID", at.blockId, remaining.id)
        val expected = PatronalFixtures.templePrayer(partial, "тропарь храма")
        val result = BookPositionUpdate.rebaseForDedication(at, previous, partial)
        assertEquals(BookDedicationMatchKind.KNOWN_HYMN, result.matchKind)
        assertEquals(expected.id, result.position.blockId)
        assertNotEquals(remaining.id, result.position.blockId)
        val atKontakion = slotPosition("кондак храма")
        val sameGap = BookPositionUpdate.rebaseForDedication(atKontakion, previous, partial)
        assertEquals("The remaining gap also retains its own label ownership", BookDedicationMatchKind.EXACT, sameGap.matchKind)
        assertEquals(remaining.id, sameGap.position.blockId)
        assertEquals(atKontakion.fraction, sameGap.position.fraction)
        assertEquals(atKontakion.followingGapFraction, sameGap.position.followingGapFraction)
    }

    @Test fun anUnchangedOccurrenceKeepsItsVisibleFractionAndFollowingSpaceInTheNewSource() {
        val at = commonPosition().copy(fraction = 1f, followingGapFraction = .58f)
        val result = BookPositionUpdate.rebaseForDedication(at, previous, next)
        assertEquals(BookDedicationMatchKind.EXACT, result.matchKind)
        assertEquals(at.copy(key = next.key, contentHash = next.contentHash), result.position)
    }

    @Test fun aChangedRussianPairResetsOnlyTheVisibleTextOffsetIncludingMeaningReturnLanguage() {
        val at = commonPosition().copy(fraction = .41f)
        val changed = PatronalFixtures.rehash(next.copy(chapters = next.chapters.map { chapter -> chapter.copy(
            blocks = chapter.blocks.map { if (it.id == at.blockId) it.copy(
                russian = "Синтетический русский перевод для проверки изменения видимого текста") else it }) }))
        assertTrue(ServiceBookIntegrity.valid(changed))
        for ((mode, last, expected) in listOf(
            Triple(BookReadingMode.ORIGINAL, BookReadingMode.ORIGINAL, .41f),
            Triple(BookReadingMode.RUSSIAN, BookReadingMode.RUSSIAN, 0f),
            Triple(BookReadingMode.MEANING, BookReadingMode.RUSSIAN, 0f),
            Triple(BookReadingMode.MEANING, BookReadingMode.ORIGINAL, .41f))) {
            val result = BookPositionUpdate.rebaseForDedication(at.copy(mode = mode, lastTextMode = last), previous, changed)
            assertEquals(BookDedicationMatchKind.EXACT, result.matchKind)
            assertEquals(expected, result.position.fraction)
            assertEquals(mode, result.position.mode)
            assertEquals(last, result.position.lastTextMode)
        }
    }

    @Test fun aMissingReplacementDoesNotBorrowTheFollowingSaintsPrayer() {
        val at = slotPosition("тропарь храма")
        val templePrayer = PatronalFixtures.templePrayer(next, "тропарь храма")
        val incomplete = PatronalFixtures.rehash(next.copy(chapters = next.chapters.map { chapter ->
            chapter.copy(blocks = chapter.blocks.filterNot { it.id == templePrayer.id }) }))
        assertTrue(ServiceBookIntegrity.valid(incomplete))
        val result = BookPositionUpdate.rebaseForDedication(at, previous, incomplete)
        assertEquals(BookDedicationMatchKind.SECTION_START, result.matchKind)
        assertEquals(BookAnchorKind.HEADER, result.position.anchorKind)
        assertEquals(at.chapterId, result.position.chapterId)
        assertEquals(incomplete.chapters.single { it.id == at.chapterId }.blocks.first().id, result.position.blockId)
    }

    @Test fun anUnknownLocalSlotUsesTheConservativeSectionFallbackWithoutInventingAName() {
        val at = slotPosition("тропарь храма")
        val label = previous.blocks.first { BookText.normalized(it.original) == "тропарь храма" }
        val unknown = PatronalFixtures.rehash(previous.copy(chapters = previous.chapters.map { chapter -> chapter.copy(
            blocks = chapter.blocks.map { if (it.id == label.id) it.copy(original = "Синтетическое неизвестное местное указание") else it }) }))
        val result = BookPositionUpdate.rebaseForDedication(at.copy(contentHash = unknown.contentHash), unknown, next)
        assertEquals(BookDedicationMatchKind.SECTION_START, result.matchKind)
        assertEquals(at.chapterId, result.position.chapterId)
        assertEquals(BookAnchorKind.HEADER, result.position.anchorKind)
    }

    @Test fun changingSourceCannotChangeTheDateWorshipOrPublicationUnderAnExistingCursor() {
        val at = commonPosition()
        for ((before, after, cursor) in listOf(
            Triple(previous, next, at.copy(contentHash = "b".repeat(64))),
            Triple(previous, next, at.copy(chapterId = "missing")),
            Triple(previous, next, at.copy(key = previous.key.copy(date = "2026-11-12"))),
            Triple(previous, next.copy(key = next.key.copy(date = "2026-11-12")), at),
            Triple(previous, next.copy(key = next.key.copy(worship = WorshipKind.VESPERS)), at),
            Triple(previous, next.copy(patronalSelection = null), at))) {
            assertThrows(IllegalArgumentException::class.java) { BookPositionUpdate.rebaseForDedication(cursor, before, after) }
        }
    }

    @Test fun theSameEveningOccurrenceSurvivesRebasingAndAnotherPublicationIsRejected() {
        val beforeKey = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34")
        val before = ServiceBookParser().parse(Jsoup.parse(MatinsRankFixtures.text("default-2026-11-13.html")), beforeKey, 12345)
        val afterKey = beforeKey.copy(patronalId = PatronalFixtures.proof().id)
        val after = ServiceBookParser().parse(PatronalFixtures.document("matins-default-2026-11-13"), afterKey, 12345,
            patronalSelection = PatronalFixtures.proof())
        val original = before.chapters.flatMap { chapter -> chapter.blocks.map { chapter to it } }
            .first { (_, block) -> !block.isAltarText && block.kind == BookBlockKind.PRAYER &&
                after.blocks.any { it.id == block.id && it.original == block.original } }
        val oldJourney = BookJourneyContext(parish, "2026-11-12", "18:00", "Вечернее богослужение",
            listOf(beforeKey.copy(worship = WorshipKind.VESPERS), beforeKey), partSet = BookPartSetKind.EVENING_TEXTS)
        val newJourney = oldJourney.copy(parts = oldJourney.parts.map { it.copy(patronalId = afterKey.patronalId) })
        val at = BookReadingPosition(beforeKey, before.contentHash, parish, original.second.id, original.first.id,
            fraction = .32f, journeyContext = oldJourney)
        val result = BookPositionUpdate.rebaseForDedication(at, before, after, newJourney)
        assertEquals(newJourney, result.position.journeyContext)
        assertEquals(.32f, result.position.fraction)
        assertThrows(IllegalArgumentException::class.java) { BookPositionUpdate.rebaseForDedication(at, before, after) }
        assertThrows(IllegalArgumentException::class.java) {
            BookPositionUpdate.rebaseForDedication(at, before, after, newJourney.copy(time = "19:00"))
        }
    }

    @Test fun anEvictedCursorCanBeExplicitlyRestoredFromItsExactBookAndSourceReturn() {
        val preferences = AcceptanceMemoryPreferences()
        val store = BookReadingStore(preferences)
        val at = commonPosition()
        assertTrue(store.save(at))
        for (offset in 1L..31L) assertTrue(store.save(at.copy(key = previous.key.copy(
            date = LocalDate.of(2027, 1, 1).plusDays(offset).toString()))))
        val changed = requireNotNull(BookRoute(at.key, parish).withDedication(PatronalFixtures.proof(), at))
        val rebased = BookPositionUpdate.rebaseForDedication(at, previous, next).position
        assertTrue(store.saveReading(rebased))
        assertEquals(BookReadingLookup.Absent, store.lookup(at.key, parish))
        val returned = requireNotNull(changed.returnToPreviousSource())
        assertEquals(at.key, returned.activeKey)
        assertEquals(at, changed.sourceReturn?.position)
        assertTrue(store.restoreMissing(previous, requireNotNull(changed.sourceReturn).position))
        assertEquals(at, store.positionFor(at.key, parish))
        assertEquals(at, store.last(parish))
        assertEquals(rebased, store.positionFor(next.key, parish))
        assertEquals(32, preferences.values.keys.count { it.startsWith("position:") })
    }

    @Test fun explicitRestoreNeverOverwritesAnExistingOwnerEvenInTheSameEdition() {
        for (hash in listOf(previous.contentHash, "b".repeat(64))) {
            val preferences = AcceptanceMemoryPreferences()
            val store = BookReadingStore(preferences)
            val saved = commonPosition().copy(contentHash = hash, fraction = .81f)
            assertTrue(store.save(saved))
            val before = preferences.values.toMap()
            assertFalse(store.restoreMissing(previous, commonPosition()))
            assertEquals(before, preferences.values)
            assertEquals(saved, store.last(parish))
        }
    }

    @Test fun unreadableAndLastOnlyAcceptedPointersAreNotConsideredMissing() {
        for (corrupt in listOf(false, true)) {
            val preferences = AcceptanceMemoryPreferences()
            val store = BookReadingStore(preferences)
            val at = commonPosition()
            assertTrue(store.save(at))
            val key = preferences.values.keys.single { it.startsWith("position:") }
            if (corrupt) preferences.values[key] = 42 else preferences.values.remove(key)
            val before = preferences.values.toMap()
            assertFalse(store.restoreMissing(previous, at))
            assertEquals(before, preferences.values)
        }
    }

    @Test fun aFailedExplicitRestoreRollsBackBothPointersAndAnyEviction() {
        for (failure in listOf(MemoryCommit.FALSE_AFTER_MEMORY, MemoryCommit.THROW_AFTER_MEMORY)) {
            val preferences = AcceptanceMemoryPreferences()
            val store = BookReadingStore(preferences)
            val at = commonPosition()
            for (offset in 1L..32L) assertTrue(store.save(at.copy(key = previous.key.copy(
                date = LocalDate.of(2027, 1, 1).plusDays(offset).toString()))))
            preferences.values["unrelated"] = "retained"
            val before = preferences.values.toMap()
            preferences.outcomes += failure
            assertFalse(store.restoreMissing(previous, at))
            assertEquals(before, preferences.values)
            assertEquals(BookReadingLookup.Absent, store.lookup(at.key, parish))
        }
    }

    @Test fun invalidBookHashAnchorOrPositionCannotCreateANewAcceptedCursor() {
        val preferences = AcceptanceMemoryPreferences()
        val store = BookReadingStore(preferences)
        val at = commonPosition()
        for (position in listOf(at.copy(key = next.key), at.copy(contentHash = next.contentHash),
            at.copy(chapterId = "missing"), at.copy(blockId = "missing"), at.copy(fraction = 1.5f)))
            assertFalse(store.restoreMissing(previous, position))
        assertFalse(store.restoreMissing(previous.copy(title = "Synthetic damaged book"), at))
        assertTrue(preferences.values.isEmpty())
    }

    private fun slotPosition(label: String): BookReadingPosition {
        val labelIndex = previous.blocks.indexOfFirst { BookText.normalized(it.original) == label }
        require(labelIndex >= 0)
        val slot = previous.blocks.drop(labelIndex + 1).first { it.kind == BookBlockKind.LOCAL_VARIATION }
        val chapter = previous.chapters.single { slot in it.blocks }
        return BookReadingPosition(previous.key, previous.contentHash, parish, slot.id, chapter.id,
            fraction = 1f, followingGapFraction = .24f, mode = BookReadingMode.RUSSIAN,
            wordsPerMinute = 152, showAltar = true, lastTextMode = BookReadingMode.RUSSIAN)
    }

    private fun commonPosition(): BookReadingPosition {
        val (chapter, block) = previous.chapters.flatMap { chapter -> chapter.blocks.map { chapter to it } }
            .first { (_, block) -> !block.isAltarText && block.kind == BookBlockKind.PRAYER && block.original.length > 100 &&
                next.blocks.any { it.id == block.id && it.original == block.original } }
        return BookReadingPosition(previous.key, previous.contentHash, parish, block.id, chapter.id,
            fraction = .41f, mode = BookReadingMode.ORIGINAL, wordsPerMinute = 152, showAltar = true,
            lastTextMode = BookReadingMode.ORIGINAL)
    }
}
