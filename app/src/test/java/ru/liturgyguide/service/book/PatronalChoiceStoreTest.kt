package ru.liturgyguide.service.book

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.MemoryCommit
import java.nio.file.Files

class PatronalChoiceStoreTest {
    private val directory = Files.createTempDirectory("patronal-choice-").toFile()
    private val cache = FileBookCache(directory)
    private val books = object : ServiceBooks {
        override fun cached(key: ServiceBookKey) = cache.read(key)
        override fun cachedVersion(key: ServiceBookKey, contentHash: String) = cache.readVersion(key, contentHash)
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult =
            error("Verification must not initiate a download")
    }
    @After fun clean() { directory.deleteRecursively() }

    @Test fun rememberingCalendarFactsNeverPublishesAParishProfile() {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val proof = PatronalFixtures.proof()
        preferences.values["unrelated"] = true
        assertTrue(store.rememberProof(proof))
        assertEquals(proof, store.find(proof.id))
        assertEquals(PatronalProfileLookup.Absent, store.readProfile("parish-a"))
        assertEquals(PatronalProfileLookup.Absent, store.readProfile("parish-b"))
        assertTrue(preferences.committedKeys.all { it == setOf(proofKey(proof.id)) })
        assertEquals(true, preferences.values["unrelated"])
        val writes = preferences.commitCount
        assertTrue(store.rememberProof(proof))
        assertEquals("Remembering the same public fact does not open another editor", writes, preferences.commitCount)
    }

    @Test fun aVerifiedSavedBookPromotesOnlyItsParishAndSurvivesANewStore() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val token = token()
        assertTrue(store.commitProfile("parish-a", token, PatronalProfileLookup.Absent))
        assertEquals(setOf(profileKey("parish-a"), proofKey(token.proof.id)), preferences.committedKeys.single())
        val reopened = PatronalChoiceStore(preferences)
        assertEquals(PatronalProfileLookup.Found(token.proof), reopened.readProfile("parish-a"))
        assertEquals(token.selection, reopened.find(token.proof.id))
        assertEquals(PatronalProfileLookup.Absent, reopened.readProfile("parish-b"))
        assertEquals(token.contentHash, cache.readVersion(token.key, token.contentHash)?.contentHash)
    }

    @Test fun verificationRejectsUnsavedWrongOrMissingBooksWithoutChangingPreferences() = runBlocking {
        val proof = PatronalFixtures.proof()
        val book = PatronalFixtures.parse()
        assertTrue(cache.write(book))
        val ready = BookLoadResult.Ready(book, fromCache = false)
        assertNull(VerifiedPatronalBook.verify("parish-a", book.key, proof, ready.copy(saved = false), books))
        assertNull(VerifiedPatronalBook.verify("parish-a", book.key.copy(date = "2026-11-12"), proof, ready, books))
        assertNull(VerifiedPatronalBook.verify("parish-a", book.key, proof.copy(id = "129540"), ready, books))
        assertNull(VerifiedPatronalBook.verify("parish-a", book.key, proof.copy(mineaId = "9906"), ready, books))
        assertNull(VerifiedPatronalBook.verify("parish-a", book.key, proof,
            ready.copy(book = book.copy(title = "Changed without a new content hash")), books))
        val missing = object : ServiceBooks by books {
            override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? = null
        }
        val failed = object : ServiceBooks by books {
            override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? = error("Synthetic disk failure")
        }
        assertNull(VerifiedPatronalBook.verify("parish-a", book.key, proof, ready, missing))
        assertNull(VerifiedPatronalBook.verify("parish-a", book.key, proof, ready, failed))
        assertNotNull(VerifiedPatronalBook.verify("parish-a", book.key, proof, ready, books))
    }

    @Test fun verificationPropagatesCancellationInsteadOfReturningAPublicationToken() = runBlocking {
        val proof = PatronalFixtures.proof()
        val book = PatronalFixtures.parse()
        assertTrue(cache.write(book))
        val cancelled = object : ServiceBooks by books {
            override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? = throw CancellationException("Test owner cancelled")
        }
        var propagated = false
        try { VerifiedPatronalBook.verify("parish-a", book.key, proof, BookLoadResult.Ready(book, false), cancelled) }
        catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
    }

    @Test fun aCachedBookMayKeepItsOlderSnapshotWhileTheRegistryKeepsNewPublicMetadata() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val original = PatronalFixtures.proof()
        val refreshed = original.copy(calendarDate = "2027-05-22", title = original.title + " - synthetic refreshed metadata",
            sourceUrl = "https://azbyka.ru/worships/calendar/api/2027-05-22/")
        assertTrue(store.rememberProof(refreshed))
        val book = PatronalFixtures.parse()
        assertTrue(cache.write(book))
        val token = requireNotNull(VerifiedPatronalBook.verify("parish-a", book.key, refreshed, BookLoadResult.Ready(book, true), books))
        assertEquals(original, token.proof)
        assertEquals(refreshed, token.selection)
        assertTrue(store.commitProfile("parish-a", token, PatronalProfileLookup.Absent))
        assertEquals(PatronalProfileLookup.Found(original), store.readProfile("parish-a"))
        assertEquals(refreshed, store.find(original.id))
        assertEquals(book, cache.readVersion(book.key, book.contentHash))
    }

    @Test fun aGlobalEventCannotBeReassignedToAnotherMineaOrChurchType() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val proof = PatronalFixtures.proof()
        assertTrue(store.rememberProof(proof))
        val before = preferences.values.toMap()
        assertFalse(store.rememberProof(proof.copy(mineaId = "9906")))
        assertFalse(store.rememberProof(proof.copy(churchType = "30")))
        assertEquals(before, preferences.values)
        val conflictingBook = PatronalFixtures.rehash(PatronalFixtures.parse().copy(patronalSelection = proof.copy(mineaId = "9906")))
        assertTrue(cache.write(conflictingBook))
        val candidate = requireNotNull(VerifiedPatronalBook.verify("parish-a", conflictingBook.key,
            requireNotNull(conflictingBook.patronalSelection), BookLoadResult.Ready(conflictingBook, true), books))
        assertFalse(store.commitProfile("parish-a", candidate, PatronalProfileLookup.Absent))
        assertEquals(before, preferences.values)
    }

    @Test fun staleAbsenceAndStaleFoundOwnersCannotReplaceANewerChoice() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val first = PatronalChoiceStore(preferences)
        val second = PatronalChoiceStore(preferences)
        val may = token()
        val december = token("nicholas-dec")
        val elizabeth = token("elizabeth")
        assertTrue(second.commitProfile("parish-a", may, PatronalProfileLookup.Absent))
        val before = preferences.values.toMap()
        assertFalse(first.commitProfile("parish-a", elizabeth, PatronalProfileLookup.Absent))
        assertEquals(before, preferences.values)
        val expected = first.readProfile("parish-a")
        assertTrue(second.commitProfile("parish-a", december, expected))
        val newer = preferences.values.toMap()
        assertFalse(first.commitProfile("parish-a", elizabeth, expected))
        assertEquals(newer, preferences.values)
        assertEquals(PatronalProfileLookup.Found(december.proof), first.readProfile("parish-a"))
    }

    @Test fun aTokenFromOneParishCannotPublishIntoAnotherParish() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val a = token("nicholas-may", "parish-a")
        val b = token("elizabeth", "parish-b")
        assertTrue(store.commitProfile("parish-a", a, PatronalProfileLookup.Absent))
        assertTrue(store.commitProfile("parish-b", b, PatronalProfileLookup.Absent))
        val before = preferences.values.toMap()
        assertFalse(store.commitProfile("parish-b", a, store.readProfile("parish-b")))
        assertEquals(before, preferences.values)
        assertEquals(PatronalProfileLookup.Found(a.proof), store.readProfile("parish-a"))
        assertEquals(PatronalProfileLookup.Found(b.proof), store.readProfile("parish-b"))
    }

    @Test fun malformedProfilesStayUnreadableAndDoNotUseAnotherParishOrAnAvailablePublicFact() = runBlocking {
        val candidate = token()
        for (raw in listOf<Any>(true, "{", "{}", "x".repeat(8_193),
            profileJson("another-parish", candidate.proof),
            profileJson("parish-a", candidate.proof).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            profileJson("parish-a", candidate.proof).replace("\"id\":\"111300\"", "\"id\":111300"))) {
            val preferences = AcceptanceMemoryPreferences()
            val store = PatronalChoiceStore(preferences)
            assertTrue(store.rememberProof(candidate.proof))
            preferences.values[profileKey("parish-a")] = raw
            val before = preferences.values.toMap()
            val edits = preferences.editCount
            assertEquals(PatronalProfileLookup.Unreadable, store.readProfile("parish-a"))
            assertEquals(PatronalProfileLookup.Absent, store.readProfile("parish-b"))
            assertFalse(store.commitProfile("parish-a", candidate, PatronalProfileLookup.Absent))
            assertFalse(store.commitProfile("parish-a", candidate, PatronalProfileLookup.Unreadable))
            assertEquals(before, preferences.values)
            assertEquals(edits, preferences.editCount)
        }
    }

    @Test fun aValidSelectedSnapshotRecoversALostDisposableProofWithoutChangingTheProfile() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val candidate = token()
        assertTrue(store.commitProfile("parish-a", candidate, PatronalProfileLookup.Absent))
        for (public in listOf(null, true, "{")) {
            if (public == null) preferences.values.remove(proofKey(candidate.proof.id))
            else preferences.values[proofKey(candidate.proof.id)] = public
            val before = preferences.values.toMap()
            val reopened = PatronalChoiceStore(preferences)
            assertEquals(PatronalProfileLookup.Found(candidate.proof), reopened.readProfile("parish-a"))
            assertEquals(candidate.proof, reopened.find(candidate.proof.id))
            assertEquals(before, preferences.values)
            assertTrue(reopened.rememberProof(candidate.proof))
            assertEquals(PatronalProfileLookup.Found(candidate.proof), reopened.readProfile("parish-a"))
        }
    }

    @Test fun malformedDataInAnotherParishDoesNotBlockOrModifyAValidChoice() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        preferences.values[profileKey("broken-parish")] = "{"
        val candidate = token()
        assertTrue(store.commitProfile("parish-a", candidate, PatronalProfileLookup.Absent))
        assertEquals("{", preferences.values[profileKey("broken-parish")])
        assertEquals(PatronalProfileLookup.Found(candidate.proof), store.readProfile("parish-a"))
        assertEquals(PatronalProfileLookup.Unreadable, store.readProfile("broken-parish"))
    }

    @Test fun conflictingGlobalIdentitiesAreUnreadableEvenWhenThePublicRecordWasLost() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val candidate = token()
        assertTrue(store.commitProfile("parish-a", candidate, PatronalProfileLookup.Absent))
        preferences.values.remove(proofKey(candidate.proof.id))
        preferences.values[profileKey("parish-b")] = profileJson("parish-b", candidate.proof.copy(mineaId = "9906"))
        val before = preferences.values.toMap()
        assertEquals(PatronalProfileLookup.Unreadable, store.readProfile("parish-a"))
        assertEquals(PatronalProfileLookup.Unreadable, store.readProfile("parish-b"))
        assertNull(store.find(candidate.proof.id))
        assertFalse(store.rememberProof(candidate.proof))
        assertFalse(store.commitProfile("parish-a", candidate, PatronalProfileLookup.Absent))
        assertEquals(before, preferences.values)
    }

    @Test fun failedProfileCommitsRestoreBothAbsentAndPreviousValuesAfterAndroidMemoryChanged() = runBlocking {
        for (outcome in listOf(MemoryCommit.FALSE_AFTER_MEMORY, MemoryCommit.THROW_AFTER_MEMORY)) {
            for (existing in listOf(false, true)) {
                val preferences = AcceptanceMemoryPreferences()
                val store = PatronalChoiceStore(preferences)
                preferences.values["unrelated"] = setOf("keep")
                if (existing) assertTrue(store.commitProfile("parish-a", token("elizabeth"), PatronalProfileLookup.Absent))
                val expected = store.readProfile("parish-a")
                val before = preferences.values.toMap()
                preferences.outcomes += outcome
                assertFalse(store.commitProfile("parish-a", token(), expected))
                assertEquals(before, preferences.values)
                assertEquals(expected, store.readProfile("parish-a"))
            }
        }
    }

    @Test fun aFailedPublicFactWriteRollsBackItsMemoryAndNeverCreatesAProfile() {
        for (outcome in listOf(MemoryCommit.FALSE_AFTER_MEMORY, MemoryCommit.THROW_AFTER_MEMORY)) {
            val preferences = AcceptanceMemoryPreferences()
            val store = PatronalChoiceStore(preferences)
            preferences.values["unrelated"] = true
            val before = preferences.values.toMap()
            preferences.outcomes += outcome
            assertFalse(store.rememberProof(PatronalFixtures.proof()))
            assertEquals(before, preferences.values)
            assertEquals(PatronalProfileLookup.Absent, store.readProfile("parish-a"))
        }
    }

    @Test fun anIndependentNewerProfileIsNotRolledBackOrReportedAsThisTokensSuccess() = runBlocking {
        val candidate = token()
        val newer = token("elizabeth")
        for (outcome in listOf(MemoryCommit.SUCCESS, MemoryCommit.FALSE_AFTER_MEMORY)) {
            val preferences = AcceptanceMemoryPreferences()
            val store = PatronalChoiceStore(preferences)
            preferences.outcomes += outcome
            preferences.afterMemory = { count -> if (count == 1)
                preferences.values[profileKey("parish-a")] = profileJson("parish-a", newer.proof)
            }
            assertFalse(store.commitProfile("parish-a", candidate, PatronalProfileLookup.Absent))
            assertEquals(PatronalProfileLookup.Found(newer.proof), store.readProfile("parish-a"))
        }
    }

    @Test fun storageReadFailuresAndInvalidOwnersNeverOpenAnEditor() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val candidate = token()
        preferences.throwOnRead = true
        assertEquals(PatronalProfileLookup.Unreadable, store.readProfile("parish-a"))
        assertNull(store.find(candidate.proof.id))
        assertFalse(store.rememberProof(candidate.proof))
        assertFalse(store.commitProfile("parish-a", candidate, PatronalProfileLookup.Absent))
        assertEquals(0, preferences.editCount)
        preferences.throwOnRead = false
        for (id in listOf("", " ", " parish-a", "parish-a\n", "x".repeat(161))) {
            assertEquals(PatronalProfileLookup.Unreadable, store.readProfile(id))
            assertFalse(store.commitProfile(id, candidate, PatronalProfileLookup.Absent))
            assertNull(VerifiedPatronalBook.verify(id, candidate.key, candidate.selection,
                BookLoadResult.Ready(requireNotNull(cache.read(candidate.key)), true), books))
        }
        assertEquals(0, preferences.editCount)
    }

    @Test fun publicFactsAreBoundedWithoutEvictingSelectedEvidenceOrSilentlyAddingANewProfile() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val original = PatronalFixtures.proof()
        repeat(512) { assertTrue(store.rememberProof(original.copy(id = (200000 + it).toString()))) }
        val before = preferences.values.toMap()
        val edits = preferences.editCount
        assertFalse(store.rememberProof(original))
        assertFalse(store.commitProfile("parish-a", token(), PatronalProfileLookup.Absent))
        assertEquals(before, preferences.values)
        assertEquals(edits, preferences.editCount)
        assertEquals(PatronalProfileLookup.Absent, store.readProfile("parish-a"))
        val existing = original.copy(id = "200000", title = original.title + " - revised metadata")
        assertTrue(store.rememberProof(existing))
        assertEquals(existing, store.find(existing.id))
        assertEquals(512, preferences.values.keys.count { it.startsWith("patronal:proof:") })
    }

    @Test fun profileCapacityRejectsANewParishWhileKeepingExistingSelections() = runBlocking {
        val preferences = AcceptanceMemoryPreferences()
        val store = PatronalChoiceStore(preferences)
        val proof = PatronalFixtures.proof()
        assertTrue(store.rememberProof(proof))
        repeat(128) { index ->
            val id = "seeded-parish-$index"
            preferences.values[profileKey(id)] = profileJson(id, proof)
        }
        val before = preferences.values.toMap()
        assertFalse(store.commitProfile("parish-a", token(), PatronalProfileLookup.Absent))
        assertEquals(before, preferences.values)
        assertEquals(PatronalProfileLookup.Found(proof), store.readProfile("seeded-parish-0"))
        assertEquals(PatronalProfileLookup.Absent, store.readProfile("parish-a"))
    }

    private suspend fun token(label: String = "nicholas-may", parishId: String = "parish-a"): VerifiedPatronalBook {
        val book = PatronalFixtures.parse(label)
        assertTrue(cache.write(book))
        return requireNotNull(VerifiedPatronalBook.verify(parishId, book.key, PatronalFixtures.proof(label),
            BookLoadResult.Ready(book, fromCache = true), books))
    }

    private fun profileJson(parishId: String, proof: PatronalEventProof): String = Gson().toJson(
        mapOf("schemaVersion" to 1, "parishId" to parishId, "proof" to proof))
    private fun profileKey(parishId: String) = "patronal:profile:" + BookText.sha256(parishId)
    private fun proofKey(id: String) = "patronal:proof:$id"
}
