package ru.liturgyguide.service.book

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

class BookRussianUpdateDescriptorTest {
    private val owner = Any()
    private val occurrence = "parish:42|date:2026-11-13|time:08:00|title:Литургия"
    private val key = ServiceBookKey("2026-11-13", WorshipKind.LITURGY)
    private val source = BookSource("Тестовый перевод", "https://example.org/parallel")

    private fun block(id: String = "block-a", russian: String? = null) =
        BookBlock(id, BookBlockKind.PRAYER, "Священник", "Целая исходная молитва $id",
            russian = russian, translationSource = if (russian == null) null else source)

    private fun book(blocks: List<BookBlock> = listOf(block()), bookKey: ServiceBookKey = key) =
        ServiceBook(key = bookKey, title = bookKey.worship.title, sourceUrl = "https://example.org/book",
            sourceTitle = "Тестовое последование", fetchedAtMillis = 1, selectedEventTitle = null,
            chapters = listOf(BookChapter("chapter-a", "Раздел", "Объяснение", blocks)),
            contentHash = "a".repeat(64))

    private fun next(old: ServiceBook, id: String = "block-a", russian: String? = "Целый русский перевод") =
        old.copy(chapters = old.chapters.map { chapter ->
            chapter.copy(blocks = chapter.blocks.map { item ->
                if (item.id == id) item.copy(russian = russian, translationSource = source) else item
            })
        }, contentHash = "b".repeat(64))

    private fun descriptor(old: ServiceBook, candidate: ServiceBook?, saved: Boolean = true,
        verified: Boolean = true, token: Any = owner, identity: String = occurrence) =
        BookRussianUpdateDescriptor.create(old, candidate?.let { BookLoadResult.Ready(it, fromCache = true, saved = saved) },
            verified, token, identity)

    @Test fun exactPublicPrayerAdditionHasTheActualPairIdentityAndDoesNotMutateEitherBook() {
        val old = book(); val candidate = next(old)
        val beforeOld = Gson().toJson(old); val beforeCandidate = Gson().toJson(candidate)
        val proof = requireNotNull(descriptor(old, candidate))
        assertEquals(setOf("block-a"), proof.newlyRussianBlockIds)
        assertEquals(old.key, proof.acceptedKey); assertEquals(old.contentHash, proof.acceptedHash)
        assertEquals(candidate.key, proof.candidateKey); assertEquals(candidate.contentHash, proof.candidateHash)
        assertSame(owner, proof.ownerToken); assertEquals(occurrence, proof.ownerIdentity)
        assertEquals(beforeOld, Gson().toJson(old)); assertEquals(beforeCandidate, Gson().toJson(candidate))
        assertNull(old.blocks.single().russian)
        assertEquals("Целый русский перевод", candidate.blocks.single().russian)
    }

    @Test fun scriptureNeedsTheSameWholeOriginalAndReferenceScope() {
        val reading = block().copy(kind = BookBlockKind.SCRIPTURE, speaker = null,
            original = "Полное чтение Писания", scriptureReferences = listOf("Ин.1:1-17"))
        val old = book(listOf(reading)); val candidate = next(old)
        assertEquals(setOf(reading.id), requireNotNull(descriptor(old, candidate)).newlyRussianBlockIds)
        val changedReference = candidate.copy(chapters = listOf(candidate.chapters.single().copy(
            blocks = listOf(candidate.blocks.single().copy(scriptureReferences = listOf("Ин.1:1-18"))))))
        assertNull(descriptor(old, changedReference))
    }

    @Test fun legacyWholePublicPrayerCountsOnlyAfterEffectiveRussianActuallyAppears() {
        val original = "И священник возглашает: Боже наш, Тебе славу воссылаем"
        val legacy = block().copy(kind = BookBlockKind.RUBRIC, original = original, russian = original,
            translationSource = null, speaker = null)
        val old = book(listOf(legacy), key.copy(worship = WorshipKind.MATINS))
        assertTrue(BookBlockPresentation.isLegacyPrayer(old.key, legacy))
        assertNull(BookBlockPresentation.effectiveRussian(old.key, legacy))
        assertNull(descriptor(old, old.copy(contentHash = "b".repeat(64))))
        assertEquals(setOf(legacy.id), requireNotNull(descriptor(old, next(old))).newlyRussianBlockIds)
        // The same prefix is not a reviewed legacy prayer in another family.
        val unrelated = old.copy(key = key)
        assertNull(descriptor(unrelated, next(unrelated)))
    }

    @Test fun additionsElsewhereNeverClaimThatTheCurrentBlockWasTranslated() {
        val old = book(listOf(block(), block("block-b"), block("block-c")))
        val proof = requireNotNull(descriptor(old, next(old, "block-b")))
        assertFalse("A visible block-a cannot use block-b's proof", "block-a" in proof.newlyRussianBlockIds)
        assertEquals(setOf("block-b"), proof.newlyRussianBlockIds)
    }

    @Test fun absentUnsavedOrUnverifiedCandidateHasNoDescriptor() {
        val old = book(); val candidate = next(old)
        assertNull(descriptor(old, null))
        assertNull(descriptor(old, candidate, saved = false))
        assertNull(descriptor(old, candidate, verified = false))
        assertNull(descriptor(old, candidate.copy(contentHash = old.contentHash)))
    }

    @Test fun malformedHashesKeysAndOwnerIdentityFailClosedWithoutHashingTheBooks() {
        val old = book(); val candidate = next(old)
        for (bad in listOf("", "A".repeat(64), "b".repeat(63), "not-a-hash")) {
            assertNull(descriptor(old.copy(contentHash = bad), candidate))
            assertNull(descriptor(old, candidate.copy(contentHash = bad)))
        }
        assertNull(descriptor(old.copy(key = key.copy(date = "2026-13-13")), candidate))
        for (bad in listOf("", "x".repeat(8193), "owner\nchanged", "owner\u0000changed"))
            assertNull(descriptor(old, candidate, identity = bad))
    }

    @Test fun anotherDateFamilyDailyVariantRankOrDedicationIsNeverThisCandidate() {
        val old = book(); val candidate = next(old)
        val keys = listOf(key.copy(date = "2026-11-14"), key.copy(worship = WorshipKind.VESPERS),
            key.copy(churchType = "34"), key.copy(mainEvent = "123"), key.copy(secondEvent = "456"),
            key.copy(patronalId = "789"))
        keys.forEach { other -> assertNull(other.toString(), descriptor(old, candidate.copy(key = other))) }
        val matins = book(bookKey = key.copy(worship = WorshipKind.MATINS))
        assertNull(descriptor(matins, next(matins).copy(key = matins.key.copy(topLevel = "3"))))
    }

    @Test fun changedWholeTextKindSpeakerAltarOrReferencesCannotBorrowTheSameId() {
        val old = book(); val candidate = next(old); val translated = candidate.blocks.single()
        val changed = listOf(translated.copy(original = "Только начало"),
            translated.copy(original = translated.original + " еще слова"),
            translated.copy(kind = BookBlockKind.SCRIPTURE), translated.copy(speaker = null),
            translated.copy(isAltarText = true), translated.copy(scriptureReferences = listOf("Мф.1:1")))
        changed.forEach { row ->
            assertNull(row.toString(), descriptor(old, candidate.copy(chapters =
                listOf(candidate.chapters.single().copy(blocks = listOf(row))))))
        }
    }

    @Test fun existingOrBlankEffectiveRussianIsNotAFirstTranslation() {
        val old = book(); val candidate = next(old)
        for (blank in listOf<String?>(null, "", "  \n ")) assertNull(descriptor(old, next(old, russian = blank)))
        val alreadyRussian = book(listOf(block(russian = "Существующий русский перевод")))
        assertNull(descriptor(alreadyRussian, next(alreadyRussian)))
        val emptyStoredField = book(listOf(block(russian = "")))
        assertNotNull(BookBlockPresentation.effectiveRussian(emptyStoredField.key, emptyStoredField.blocks.single()))
        assertNull("An invalid blank stored field is not proof of a missing effective field", descriptor(emptyStoredField, candidate))
    }

    @Test fun rubricsAndLocalPlaceholdersDoNotBecomeAClaimAboutAPrayer() {
        for (kind in listOf(BookBlockKind.RUBRIC, BookBlockKind.LOCAL_VARIATION)) {
            val old = book(listOf(block().copy(kind = kind, speaker = null)))
            assertNull(descriptor(old, next(old)))
        }
        val blankOriginal = book(listOf(block().copy(original = "  ")))
        assertNull(descriptor(blankOriginal, next(blankOriginal)))
    }

    @Test fun publicAndPrivateFlagsAreNotInterchangeableAndPrivateAdditionsStayUnannounced() {
        val publicBlock = block(); val privateBlock = block("private").copy(isAltarText = true)
        val old = book(listOf(publicBlock, privateBlock))
        val candidate = next(next(old, privateBlock.id), publicBlock.id)
        assertEquals(setOf(publicBlock.id), requireNotNull(descriptor(old, candidate)).newlyRussianBlockIds)
        assertNull(descriptor(book(listOf(privateBlock)), next(book(listOf(privateBlock)), privateBlock.id)))
    }

    @Test fun positivelyRecognizedQuietPrayerGroupNeverBecomesAPublicTranslationTarget() {
        val old = quietMatins()
        val quiet = "quiet-0-a"
        assertTrue(BookVisibilityScope.of(old).isQuiet(quiet))
        val candidate = next(next(old, quiet), "public-after")
        assertEquals(setOf("public-after"), requireNotNull(descriptor(old, candidate)).newlyRussianBlockIds)
        assertNull(descriptor(old, next(old, quiet)))
        val unrecognized = old.copy(chapters = old.chapters.map { it.copy(title = "Прежнее заглавие") })
        assertFalse(BookVisibilityScope.of(unrecognized).isQuiet(quiet))
        assertNull("Private scope in either edition prevents a public claim", descriptor(unrecognized, next(old, quiet)))
        assertNull(descriptor(old, next(unrecognized, quiet)))
    }

    @Test fun duplicateChapterOrGlobalBlockIdsInvalidateTheWholeProofInEitherEdition() {
        val old = book(); val candidate = next(old)
        val duplicateChapter = old.copy(chapters = old.chapters + old.chapters.single().copy(
            blocks = listOf(block("another"))))
        assertNull(descriptor(duplicateChapter, candidate))
        assertNull(descriptor(old, candidate.copy(chapters = candidate.chapters + candidate.chapters.single().copy(
            blocks = listOf(block("another", "Перевод"))))))
        val duplicateBlock = BookChapter("chapter-b", "Другой раздел", "Смысл", listOf(old.blocks.single()))
        assertNull(descriptor(old.copy(chapters = old.chapters + duplicateBlock), candidate))
        val duplicateCandidate = candidate.copy(chapters = candidate.chapters +
            duplicateBlock.copy(blocks = listOf(candidate.blocks.single())))
        assertNull(descriptor(old, duplicateCandidate))
    }

    @Test fun movedMissingAndInvalidIdsCannotAcquireAnotherChaptersProof() {
        val old = book(); val candidate = next(old)
        assertNull(descriptor(old, candidate.copy(chapters = listOf(candidate.chapters.single().copy(id = "chapter-b")))))
        assertNull(descriptor(old, candidate.copy(chapters = listOf(candidate.chapters.single().copy(
            blocks = listOf(candidate.blocks.single().copy(id = "block-b")))))))
        for (bad in listOf("", " ", "x".repeat(181), "bad\nID")) {
            assertNull(descriptor(old.copy(chapters = listOf(old.chapters.single().copy(id = bad))), candidate))
            assertNull(descriptor(old, candidate.copy(chapters = listOf(candidate.chapters.single().copy(
                blocks = listOf(candidate.blocks.single().copy(id = bad)))))))
        }
    }

    @Test fun genericMeaningCalendarAndSourceUpdatesDoNotMeanNewRussian() {
        val old = book()
        val variants = listOf(old.copy(contentHash = "b".repeat(64), selectedEventTitle = "Другая память"),
            old.copy(contentHash = "b".repeat(64), chapters = old.chapters.map { it.copy(meaning = "Новое объяснение") }),
            old.copy(contentHash = "b".repeat(64), sources = listOf(source)),
            old.copy(contentHash = "b".repeat(64), calendarDay = BookCalendarDay(key.date, "Неделя", 1, emptyList(),
                "https://example.org/calendar")))
        variants.forEach { assertNull(descriptor(old, it)) }
        // Other editorial changes do not erase a real addition to this exact text.
        assertEquals(setOf("block-a"), requireNotNull(descriptor(old,
            next(old).copy(notices = listOf("Обновлены сведения")))).newlyRussianBlockIds)
    }

    @Test fun ownerMatchingUsesTokenIdentityAndBothAcceptedKeyAndHash() {
        class EqualOwner {
            override fun equals(other: Any?): Boolean = other is EqualOwner
            override fun hashCode(): Int = 1
        }
        val token = EqualOwner(); val equalToken = EqualOwner()
        assertEquals(token, equalToken); assertNotSame(token, equalToken)
        val old = book(); val proof = requireNotNull(descriptor(old, next(old), token = token))
        assertTrue(proof.matches(old.key, old.contentHash, token))
        assertFalse(proof.matches(old.key, old.contentHash, equalToken))
        assertFalse(proof.matches(old.key.copy(mainEvent = "1"), old.contentHash, token))
        assertFalse(proof.matches(old.key, "c".repeat(64), token))
    }

    @Test fun pairIdentitySurvivesNewRuntimeTokenButNotAnotherOccurrenceOrCandidate() {
        val old = book(); val candidate = next(old)
        val first = requireNotNull(descriptor(old, candidate))
        val recreated = requireNotNull(descriptor(old, candidate, token = Any()))
        assertEquals(first.pairIdentity, recreated.pairIdentity)
        assertNotEquals(first.pairIdentity, requireNotNull(descriptor(old, candidate,
            identity = occurrence + "|time:09:00")).pairIdentity)
        assertNotEquals(first.pairIdentity, requireNotNull(descriptor(old,
            candidate.copy(contentHash = "c".repeat(64)))).pairIdentity)
        assertFalse(recreated.matches(old.key, old.contentHash, owner))
    }

    @Test fun exposedTargetsAreDefensiveAndCannotMutateTheProof() {
        val mutableBlocks = mutableListOf(block(), block("block-b"))
        val old = book(mutableBlocks); val candidate = next(old, "block-b")
        val proof = requireNotNull(descriptor(old, candidate))
        mutableBlocks.clear()
        assertEquals(setOf("block-b"), proof.newlyRussianBlockIds)
        try {
            (proof.newlyRussianBlockIds as MutableSet<String>).add("forged")
            fail("Targets must be an immutable defensive set")
        } catch (_: UnsupportedOperationException) {
            // Expected from the public unmodifiable set.
        }
        assertEquals(setOf("block-b"), proof.newlyRussianBlockIds)
    }

    private fun quietMatins(): ServiceBook {
        val ordinals = listOf("первая", "вторая", "третия", "четвертая", "пятая", "шестая",
            "седмая", "осмая", "девятая", "десятая", "перваянадесять", "втораянадесять")
        fun rubric(id: String, value: String) = BookBlock(id, BookBlockKind.RUBRIC, null, value)
        val blocks = mutableListOf<BookBlock>()
        listOf(3, 37, 62).forEach { blocks += rubric("psalm-$it", "Псалом $it") }
        blocks += rubric("quiet-introduction",
            "Священник выходит из алтаря северной дверью и с непокрытой головой перед Царскими вратами тайно читает 12 утренних молитв.")
        ordinals.forEachIndexed { index, ordinal ->
            blocks += rubric("quiet-$index-label", "Молитва $ordinal:")
            blocks += block("quiet-$index-a")
            blocks += block("quiet-$index-b")
        }
        blocks += rubric("psalm-87", "Псалом 87")
        blocks += block("public-after")
        return book(blocks, key.copy(worship = WorshipKind.MATINS)).let { value ->
            value.copy(chapters = listOf(value.chapters.single().copy(title = "Шестопсалмие")))
        }
    }
}
