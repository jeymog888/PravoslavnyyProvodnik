package ru.liturgyguide.service.book

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.MemoryCommit
import java.time.LocalDate

class BookDedicationOpeningTest {
    private val parish = "dedication-opening-temple"
    private val previous by lazy { ServiceBookParser().parse(PatronalFixtures.document("nicholas-may-default"),
        PatronalFixtures.key().copy(patronalId = null), 12345) }
    private val next by lazy { PatronalFixtures.parse() }
    private fun position(book: ServiceBook = previous): BookReadingPosition {
        val chapter = book.chapters.first { it.blocks.any { block -> !block.isAltarText && block.kind == BookBlockKind.PRAYER } }
        val block = chapter.blocks.first { !it.isAltarText && it.kind == BookBlockKind.PRAYER }
        return BookReadingPosition(book.key, book.contentHash, parish, block.id, chapter.id, fraction = .37f,
            wordsPerMinute = 151, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)
    }
    private fun route(at: BookReadingPosition = position()): BookRoute =
        requireNotNull(BookRoute(at.key, parish, at.journeyContext).withDedication(PatronalFixtures.proof(), at))
    private fun books(vararg saved: ServiceBook, beforeRead: (ServiceBookKey, String) -> Unit = { _, _ -> }): ServiceBooks =
        object : ServiceBooks {
            override fun cached(key: ServiceBookKey) = saved.lastOrNull { it.key == key }
            override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? {
                beforeRead(key, contentHash)
                return saved.singleOrNull { it.key == key && it.contentHash == contentHash }
            }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult =
                error("Navigation must use only verified exact local books")
        }

    @Test fun aFreshProfileChangesBothPartsButCannotBeAppliedToResumeOrSearchRoutes() {
        val evening = ServiceBookKey("2026-11-13", WorshipKind.VESPERS, "30", mainEvent = "2")
        val morning = evening.copy(worship = WorshipKind.MATINS, mainEvent = null, topLevel = "3")
        val journey = BookJourneyContext(parish, "2026-11-12", "18:00", "Вечерня. Полиелейная утреня", listOf(evening, morning))
        val original = BookRoute(evening, parish, journey)
        val selected = requireNotNull(original.withProfileForNewOpening(PatronalFixtures.proof()))
        assertEquals("3", selected.journeyContext?.parts?.last()?.topLevel)
        assertEquals(journey.title, selected.journeyContext?.title)
        for (part in requireNotNull(selected.journeyContext).parts) {
            assertEquals(PatronalFixtures.proof().id, part.patronalId)
            assertEquals("34", part.churchType)
            assertNull(part.mainEvent)
        }
        assertNull(route().withProfileForNewOpening(PatronalFixtures.proof("nicholas-dec")))
        assertEquals("The original route remains an unchanged resume identity", "30", original.activeKey.churchType)
    }

    @Test fun aRealMissingHymnContinuesAtThePublishedPrayerAndAcceptsItsExactLocalBook() = runBlocking {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val chapter = previous.chapters.first { it.blocks.any { block -> block.kind == BookBlockKind.LOCAL_VARIATION } }
        val gap = chapter.blocks.first { it.kind == BookBlockKind.LOCAL_VARIATION }
        val at = position().copy(blockId = gap.id, chapterId = chapter.id)
        assertTrue(store.save(at))
        val opened = prepareDedicationReading(route(at), BookLoadResult.Ready(next, false), BookReadingLookup.Absent,
            books(previous, next), store) { true }
        assertEquals(BookDedicationPreparation.Ready(false, BookDedicationMatchKind.KNOWN_HYMN), opened)
        val accepted = requireNotNull(store.positionFor(next.key, parish))
        assertEquals(PatronalFixtures.templePrayer(next, "тропарь храма").id, accepted.blockId)
        assertEquals(at.mode, accepted.mode)
        assertEquals(at.wordsPerMinute, accepted.wordsPerMinute)
        assertEquals(0f, accepted.fraction)
        assertEquals(accepted, store.last(parish))
        assertEquals(at, store.positionFor(previous.key, parish))
    }

    @Test fun recreationAndProfileRetriesPreserveAnAlreadyAcceptedDestinationCursorWithoutRebasingIt() = runBlocking {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        assertTrue(store.save(at))
        val advanced = position(next).copy(fraction = .83f, wordsPerMinute = 199)
        assertTrue(store.save(advanced))
        val writes = prefs.commitCount
        repeat(2) {
            val opened = prepareDedicationReading(route(at), BookLoadResult.Ready(next, true), BookReadingLookup.Found(advanced),
                books(previous, next), store) { true }
            assertEquals(BookDedicationPreparation.Ready(true), opened)
        }
        assertEquals(advanced, store.positionFor(next.key, parish))
        assertEquals("Only the first reuse records the durable receipt", writes + 1, prefs.commitCount)
    }

    @Test fun anAcceptedDestinationStillOpensAfterThePreviousSourceArchiveIsGone() = runBlocking {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        val advanced = position(next).copy(fraction = .79f)
        assertTrue(store.save(advanced))
        val writes = prefs.commitCount
        val opened = prepareDedicationReading(route(at), BookLoadResult.Ready(next, true), BookReadingLookup.Found(advanced),
            books(next), store) { true }
        assertEquals(BookDedicationPreparation.Ready(true), opened)
        assertEquals(advanced, store.positionFor(next.key, parish))
        assertEquals(writes + 1, prefs.commitCount)
        assertNull(preparePreviousDedication(route(at), books(next), store) { true })
    }

    @Test fun anAtomicReceiptPreventsRebasingAgainAfterDestinationEvictionAndRecreation() = runBlocking {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        val selected = route(at)
        assertTrue(store.save(at))
        assertTrue(prepareDedicationReading(selected, BookLoadResult.Ready(next, false), BookReadingLookup.Absent,
            books(previous, next), store) { true } is BookDedicationPreparation.Ready)
        val receiptId = requireNotNull(selected.dedicationReceiptIdentity())
        assertTrue(store.lookupDedicationReceipt(receiptId, next.key, parish) is BookReadingLookup.Found)
        for (offset in 1L..32L) assertTrue(store.save(position().copy(key = previous.key.copy(
            date = LocalDate.of(2027, 2, 1).plusDays(offset).toString()))))
        assertEquals(BookReadingLookup.Absent, store.lookup(next.key, parish))
        val recreated = requireNotNull(BookRouteCodec.decode(BookRouteCodec.encode(selected)))
        val reopenedStore = BookReadingStore(prefs)
        val writes = prefs.commitCount
        var cacheReads = 0
        val attempted = prepareDedicationReading(recreated, BookLoadResult.Ready(next, true), BookReadingLookup.Absent,
            books(previous, next, beforeRead = { _, _ -> cacheReads++ }), reopenedStore) { true }
        assertTrue(attempted is BookDedicationPreparation.Failed)
        assertEquals("A receipt rejects the second preparation before reading any replacement book", 0, cacheReads)
        assertEquals(BookReadingLookup.Absent, reopenedStore.lookup(next.key, parish))
        assertEquals(writes, prefs.commitCount)
    }

    @Test fun aReceiptNeverRollsBackAnExistingExplicitlyAcceptedNewerEdition() = runBlocking {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        val selected = route(at)
        assertTrue(store.save(at))
        assertTrue(prepareDedicationReading(selected, BookLoadResult.Ready(next, false), BookReadingLookup.Absent,
            books(previous, next), store) { true } is BookDedicationPreparation.Ready)
        val newer = PatronalFixtures.rehash(next.copy(title = next.title + " - synthetic explicit new edition"))
        assertTrue(ServiceBookIntegrity.valid(newer))
        val accepted = position(newer).copy(fraction = .86f)
        assertTrue(store.save(accepted)) // Models a separate explicit edition acceptance.
        val writes = prefs.commitCount
        val opened = prepareDedicationReading(selected, BookLoadResult.Ready(newer, true), BookReadingLookup.Found(accepted),
            books(next, newer), store) { true }
        assertEquals(BookDedicationPreparation.Ready(true), opened)
        assertEquals(accepted, store.positionFor(next.key, parish))
        assertEquals(writes, prefs.commitCount)
    }

    @Test fun receiptsBelongToTheFullSourceRequestAndToOneDestinationPart() {
        val selected = route()
        val original = requireNotNull(selected.dedicationReceiptIdentity())
        assertEquals(original, BookRouteCodec.decode(BookRouteCodec.encode(selected))?.dedicationReceiptIdentity())
        assertNotEquals(original, selected.copy(pendingDedication = requireNotNull(selected.pendingDedication)
            .copy(expectedProfile = PatronalFixtures.proof("nicholas-dec"))).dedicationReceiptIdentity())
        assertNotEquals(original, selected.copy(activeKey = selected.activeKey.copy(mainEvent = "0")).dedicationReceiptIdentity())
        assertNull(selected.clearPendingDedication().dedicationReceiptIdentity())
    }

    @Test fun changingThePartWhileProfileSaveIsPendingStartsThatPartWithoutRebasingTheOldMatinsCursor() = runBlocking {
        val proof = PatronalFixtures.proof()
        val oldKey = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34")
        val old = ServiceBookParser().parse(Jsoup.parse(MatinsRankFixtures.text("default-2026-11-13.html")), oldKey, 12345)
        val newKey = oldKey.copy(worship = WorshipKind.VESPERS, patronalId = proof.id)
        val html = requireNotNull(javaClass.classLoader?.getResource("patronal-parts/vespers.html")).readText()
        val part = ServiceBookParser().parse(Jsoup.parse(html), newKey, 12345, patronalSelection = proof)
        assertTrue(ServiceBookIntegrity.valid(old))
        assertTrue(ServiceBookIntegrity.valid(part))
        val oldJourney = BookJourneyContext(parish, "2026-11-12", "18:00", "Вечернее богослужение",
            listOf(oldKey.copy(worship = WorshipKind.VESPERS), oldKey), partSet = BookPartSetKind.EVENING_TEXTS)
        val lastChapter = old.chapters.last { it.blocks.any { block -> !block.isAltarText } }
        val at = BookReadingPosition(oldKey, old.contentHash, parish, lastChapter.blocks.last { !it.isAltarText }.id,
            lastChapter.id, fraction = .91f, wordsPerMinute = 177, journeyContext = oldJourney)
        val selected = requireNotNull(BookRoute(oldKey, parish, oldJourney).withDedication(proof, at))
        val switched = requireNotNull(selected.selectPart(newKey, true))
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        assertTrue(store.save(at))
        val opened = prepareDedicationReading(switched, BookLoadResult.Ready(part, true), BookReadingLookup.Absent,
            books(old, part), store) { true }
        assertEquals(BookDedicationPreparation.Ready(false, BookDedicationMatchKind.SECTION_START), opened)
        val expectedChapter = part.chapters.first { it.blocks.any { block -> !block.isAltarText } }
        val accepted = requireNotNull(store.positionFor(newKey, parish))
        assertEquals(expectedChapter.id, accepted.chapterId)
        assertEquals(expectedChapter.blocks.first { !it.isAltarText }.id, accepted.blockId)
        assertEquals(0f, accepted.fraction)
        assertEquals(at.wordsPerMinute, accepted.wordsPerMinute)
        assertEquals(switched.journeyContext, accepted.journeyContext)
        assertEquals(at, store.positionFor(old.key, parish))
    }

    @Test fun anExplicitRealDailyVariantStartsItsOwnReadingAndNeverRepeatsTheOriginalLocalHymnTransfer() = runBlocking {
        val document = PatronalFixtures.document("nicholas-may-patronal")
        assertEquals("0", document.select("#bg_worships_mainEvent option[selected]").single().attr("value"))
        val explicitKey = next.key.copy(mainEvent = "0")
        val explicit = ServiceBookParser().parse(document, explicitKey, 12345, patronalSelection = PatronalFixtures.proof())
        assertTrue(ServiceBookIntegrity.valid(explicit))
        val oldChapter = previous.chapters.first { it.blocks.any { block -> block.kind == BookBlockKind.LOCAL_VARIATION } }
        val oldGap = oldChapter.blocks.first { it.kind == BookBlockKind.LOCAL_VARIATION }
        val at = position().copy(blockId = oldGap.id, chapterId = oldChapter.id, fraction = .62f)
        val initial = route(at)
        val variant = requireNotNull(initial.withVariant(explicitKey, savedCurrentPart = false))
        assertEquals(initial.activeKey.worship, variant.activeKey.worship)
        val initialReceipt = requireNotNull(initial.dedicationReceiptIdentity())
        val variantReceipt = requireNotNull(variant.dedicationReceiptIdentity())
        assertNotEquals(initialReceipt, variantReceipt)
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        assertTrue(store.save(at))
        assertEquals(BookDedicationPreparation.Ready(false, BookDedicationMatchKind.KNOWN_HYMN),
            prepareDedicationReading(initial, BookLoadResult.Ready(next, true), BookReadingLookup.Absent,
                books(previous, next), store) { true })
        val initialAccepted = requireNotNull(store.positionFor(next.key, parish))
        assertTrue(store.lookupDedicationReceipt(initialReceipt, next.key, parish) is BookReadingLookup.Found)
        assertEquals(BookReadingLookup.Absent, store.lookupDedicationReceipt(variantReceipt, explicitKey, parish))
        assertEquals(BookDedicationPreparation.Ready(false, BookDedicationMatchKind.SECTION_START),
            prepareDedicationReading(variant, BookLoadResult.Ready(explicit, true), BookReadingLookup.Absent,
                books(explicit), store) { true })
        val ownStart = requireNotNull(store.positionFor(explicitKey, parish))
        val firstChapter = explicit.chapters.first { it.blocks.any { block -> !block.isAltarText } }
        assertEquals(firstChapter.id, ownStart.chapterId)
        assertEquals(firstChapter.blocks.first { !it.isAltarText }.id, ownStart.blockId)
        assertEquals(BookAnchorKind.HEADER, ownStart.anchorKind)
        assertEquals(0f, ownStart.fraction)
        assertNotEquals(PatronalFixtures.templePrayer(explicit, "тропарь храма").id, ownStart.blockId)
        assertEquals(initialAccepted, store.positionFor(next.key, parish))
        assertTrue(store.lookupDedicationReceipt(variantReceipt, explicitKey, parish) is BookReadingLookup.Found)
        val advanced = ownStart.copy(fraction = .73f, wordsPerMinute = 193)
        assertTrue(store.saveReading(advanced))
        val writes = prefs.commitCount
        val recreated = requireNotNull(BookRouteCodec.decode(BookRouteCodec.encode(variant)))
        assertEquals(BookDedicationPreparation.Ready(true), prepareDedicationReading(recreated,
            BookLoadResult.Ready(explicit, true), BookReadingLookup.Found(advanced), books(explicit), store) { true })
        assertEquals(advanced, store.positionFor(explicitKey, parish))
        assertEquals(initialAccepted, store.positionFor(next.key, parish))
        assertEquals(at, store.positionFor(previous.key, parish))
        assertEquals(writes, prefs.commitCount)
    }

    @Test fun aCompetingDestinationAcceptedDuringCacheVerificationWinsOverTheCapturedAbsence() = runBlocking {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        assertTrue(store.save(at))
        val competing = position(next).copy(fraction = .92f)
        var changed = false
        val cached = books(previous, next, beforeRead = { key, _ ->
            if (key == previous.key && !changed) { changed = true; assertTrue(store.save(competing)) }
        })
        assertTrue(prepareDedicationReading(route(at), BookLoadResult.Ready(next, false), BookReadingLookup.Absent,
            cached, store) { true } is BookDedicationPreparation.Failed)
        assertEquals(competing, store.positionFor(next.key, parish))
        assertEquals(at, store.positionFor(previous.key, parish))
    }

    @Test fun unsavedMissingUnreadableAndRetiredRoutesCannotAcceptANewPointer() = runBlocking {
        for (failure in listOf("unsaved", "missing-next", "missing-old", "unreadable", "retired")) {
            val prefs = AcceptanceMemoryPreferences()
            val store = BookReadingStore(prefs)
            val at = position()
            assertTrue(store.save(at))
            val expected = if (failure == "unreadable") BookReadingLookup.Unreadable else BookReadingLookup.Absent
            val cached = when (failure) {
                "missing-next" -> books(previous)
                "missing-old" -> books(next)
                else -> books(previous, next)
            }
            assertTrue(failure, prepareDedicationReading(route(at), BookLoadResult.Ready(next, false, saved = failure != "unsaved"),
                expected, cached, store) { failure != "retired" } is BookDedicationPreparation.Failed)
            assertEquals(BookReadingLookup.Absent, store.lookup(next.key, parish))
            assertEquals(at, store.last(parish))
        }
    }

    @Test fun aFailedDestinationCommitRollsBackBothAcceptedPointers() = runBlocking {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        assertTrue(store.save(at))
        prefs.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
        assertTrue(prepareDedicationReading(route(at), BookLoadResult.Ready(next, false), BookReadingLookup.Absent,
            books(previous, next), store) { true } is BookDedicationPreparation.Failed)
        assertEquals(BookReadingLookup.Absent, store.lookup(next.key, parish))
        assertEquals(at, store.last(parish))
    }

    @Test fun explicitReturnKeepsTheCurrentAcceptedOldCursorInsteadOfItsEarlierSnapshot() = runBlocking {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val snapshot = position()
        val current = snapshot.copy(fraction = .88f)
        assertTrue(store.save(current))
        assertTrue(store.save(position(next)))
        val restored = preparePreviousDedication(route(snapshot), books(previous, next), store) { true }
        assertEquals(BookRoute(previous.key, parish), restored)
        assertEquals(current, store.positionFor(previous.key, parish))
        assertEquals(current, store.last(parish))
    }

    @Test fun explicitReturnRestoresOnlyAnEvictedCursorAndRefusesAChangedOwner() = runBlocking {
        val prefs = AcceptanceMemoryPreferences()
        val store = BookReadingStore(prefs)
        val at = position()
        assertTrue(store.save(at))
        for (offset in 1L..32L) assertTrue(store.save(position(next).copy(key = next.key.copy(
            date = LocalDate.of(2027, 1, 1).plusDays(offset).toString()))))
        assertEquals(BookReadingLookup.Absent, store.lookup(at.key, parish))
        assertNull(preparePreviousDedication(route(at), books(previous, next), store) { false })
        assertEquals(BookReadingLookup.Absent, store.lookup(at.key, parish))
        assertNotNull(preparePreviousDedication(route(at), books(previous, next), store) { true })
        assertEquals(at, store.positionFor(at.key, parish))
        assertEquals(at, store.last(parish))
    }
}
