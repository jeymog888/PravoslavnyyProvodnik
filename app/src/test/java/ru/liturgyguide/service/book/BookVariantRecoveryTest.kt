package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.liturgyguide.model.*

class BookVariantRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val church = Parish("listy-test", "Храм Троицы Живоначальной в Листах", "Троицы в Листах", "Москва",
        "Сретенка", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val previous = BookFixtures.book()
    private val requested = previous.key.copy(churchType = "10")
    private val position = BookReadingPosition(previous.key, previous.contentHash, church.id,
        previous.blocks.first().id, previous.chapters.first().id, mode = BookReadingMode.RUSSIAN)

    @Test fun ownAcceptedOldEditionIsRecoverableWithoutWritingANewKey() {
        val cache = FileBookCache(temporary.newFolder())
        assertTrue(cache.write(previous))
        val recovered = BookVariantRecovery.candidate(requested, church.id, church, { key, id ->
            assertEquals(previous.key, key); assertEquals(church.id, id); position
        }, cache::readVersion)
        assertEquals(previous, recovered)
        assertNull("Never alias an old book under its corrected category", cache.read(requested))
        assertEquals(previous, cache.read(previous.key))
        assertEquals(position.contentHash, recovered!!.contentHash)
    }

    @Test fun anotherParishOrAnUnacceptedSharedCacheCannotBeRecovered() {
        val unexpectedPosition: (ServiceBookKey, String?) -> BookReadingPosition? = { _, _ -> error("No lookup for another parish") }
        assertNull(BookVariantRecovery.candidate(requested, "other", church, unexpectedPosition) { _, _ -> previous })
        assertNull(BookVariantRecovery.candidate(requested, null, church, unexpectedPosition) { _, _ -> previous })
        assertNull(BookVariantRecovery.candidate(requested, church.id, church, { _, _ -> null }) { _, _ -> previous })
        assertNull(BookVariantRecovery.candidate(requested, church.id, church, { _, _ -> position.copy(parishId = "other") }) { _, _ -> previous })
    }

    @Test fun dateServiceAndBothEventVariantsMustMatchTheAcceptedPosition() {
        listOf(requested.copy(date = "2026-11-12"), requested.copy(worship = WorshipKind.VESPERS),
            requested.copy(mainEvent = "1"), requested.copy(secondEvent = "2")).forEach { request ->
            assertNull(BookVariantRecovery.candidate(request, church.id, church, { _, _ -> position }) { _, _ ->
                error("A different date or order must be rejected before reading a book")
            })
        }
        assertNull(BookVariantRecovery.legacyKey(requested.copy(churchType = "20"), church))
        assertNull(BookVariantRecovery.legacyKey(previous.key, church))
    }

    @Test fun onlyThePinnedHashAndExistingAnchorAreAccepted() {
        fun recover(book: ServiceBook, saved: BookReadingPosition = position) =
            BookVariantRecovery.candidate(requested, church.id, church, { _, _ -> saved }) { _, _ -> book }
        assertNull(recover(previous.copy(contentHash = "a".repeat(64))))
        assertNull(recover(previous.copy(key = requested)))
        assertNull(recover(previous.copy(title = "Damaged cached content")))
        assertNull(recover(previous, position.copy(blockId = "missing")))
        assertNull(recover(previous, position.copy(chapterId = "missing")))
    }

    @Test fun anAmbiguousNameCanRecoverItsOwnEarlierCategoryWithoutCallingItVerified() {
        val ambiguous = church.copy(name = "Храм Николая Чудотворца и Святой Троицы")
        val classification = ChurchTypeClassifier.classify(ambiguous)
        assertEquals(ChurchTypeEvidence.AMBIGUOUS, classification.evidence)
        assertNull(classification.type)
        assertEquals("34", BookVariantRecovery.legacyKey(previous.key, ambiguous)?.churchType)
    }
}
