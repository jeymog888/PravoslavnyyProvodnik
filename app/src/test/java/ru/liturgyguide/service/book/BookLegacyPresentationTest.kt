package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import java.nio.file.Files

/** Genuine accepted editions stay frozen; only their read-only interpretation changes. */
class BookLegacyPresentationTest {
    private val gson = Gson()
    private val directory = Files.createTempDirectory("legacy-presentation-").toFile()
    private val fixtures by lazy {
        val entries = JsonParser.parseString(resource("provenance.json").toString(Charsets.UTF_8))
            .asJsonObject.getAsJsonArray("inputs")
        entries.map { value ->
            val item = value.asJsonObject
            val filename = item["file"].asString
            val bytes = resource(filename)
            assertEquals(item["bytes"].asLong, bytes.size.toLong())
            assertEquals(item["sha256"].asString, BookText.sha256(bytes.toString(Charsets.UTF_8)))
            val raw = bytes.toString(Charsets.UTF_8)
            val book = gson.fromJson(raw, ServiceBook::class.java)
            assertEquals(item["contentHash"].asString, book.contentHash)
            // Android/Dex and desktop JVM expose fields in different reflection orders.
            // Verify the archived representation without rehashing or changing the book.
            // Production Integrity.valid and FileBookCache are checked on Android.
            val archivedRepresentation = JsonParser.parseString(raw).asJsonObject.deepCopy().apply {
                addProperty("fetchedAtMillis", 0)
                addProperty("contentHash", "")
            }
            assertEquals(book.contentHash, BookText.sha256(gson.toJson(archivedRepresentation)))
            assertTrue(book.key.isValid())
            assertTrue(PatronalSourceSelection.validBook(book))
            assertTrue(MatinsRankSource.validBook(book))
            Fixture(filename, bytes, book, item.getAsJsonArray("matches").map { match ->
                Target(match.asJsonObject["blockId"].asString, match.asJsonObject["prayerBodyWords"].asInt)
            })
        }
    }

    @After fun clean() { directory.deleteRecursively() }

    @Test fun bothAcceptedArchivesRecognizeOnlyTheTenPublicExclamationsAndCountTheirSpokenWords() {
        assertEquals(listOf(3, 7), fixtures.map { it.targets.size })
        assertEquals(listOf(21, 23, 23, 23, 21, 22, 26, 23, 25, 23),
            fixtures.flatMap { it.targets }.map { it.words })
        for (fixture in fixtures) {
            val book = fixture.book
            val expected = fixture.targets.map { it.id }.toSet()
            assertEquals(expected, book.blocks.filter { BookBlockPresentation.isLegacyPrayer(book.key, it) }.map { it.id }.toSet())
            for (target in fixture.targets) {
                val block = book.blocks.single { it.id == target.id }
                assertEquals(BookBlockKind.RUBRIC, block.kind)
                assertFalse(block.isAltarText)
                assertTrue(block.original.startsWith(PREFIX))
                assertEquals(block.original, block.russian)
                assertEquals(block.original, block.meaning)
                assertNull(block.translationSource)
                assertTrue(BookBlockPresentation.isSpoken(book.key, block))
                assertNull("A saved Slavonic prayer must not be called a Russian translation",
                    BookBlockPresentation.effectiveRussian(book.key, block))
                val body = block.original.substring(PREFIX.length).trim()
                for (mode in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN)) {
                    val spoken = requireNotNull(BookBlockPresentation.spokenText(book.key, block, mode))
                    assertEquals(body, spoken)
                    assertEquals(target.words, BookAutoScroll.wordCount(spoken))
                    assertEquals(target.words * 60f / 150, BookAutoScroll.seconds(BookAutoScroll.wordCount(spoken), 150), .001f)
                    assertTrue("Even the fastest supported rate must not use the 0.8-second rubric pause",
                        BookAutoScroll.seconds(BookAutoScroll.wordCount(spoken), BookAutoScroll.MAX_RATE) > .8f)
                }
                assertNull(BookBlockPresentation.spokenText(book.key, block, BookReadingMode.MEANING))
            }
        }
    }

    @Test fun presentationAndSearchDoNotRewriteTheAcceptedJsonHashIdsOrArchivedFiles() {
        for (fixture in fixtures) directory.resolve(fixture.filename).writeBytes(fixture.bytes)
        val filesBefore = directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
        for (fixture in fixtures) {
            val book = fixture.book
            val jsonBefore = gson.toJson(book)
            val computedHashBefore = ServiceBookIntegrity.contentHash(book)
            val idsBefore = book.blocks.map { it.id }
            val originalsBefore = book.blocks.map { it.original }
            for (block in book.blocks) {
                BookBlockPresentation.isLegacyPrayer(book.key, block)
                BookBlockPresentation.effectiveRussian(book.key, block)
                BookBlockPresentation.isSpoken(book.key, block)
                BookReadingMode.entries.forEach { BookBlockPresentation.spokenText(book.key, block, it) }
            }
            for (mode in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN))
                BookCorpusSearch.find(listOf(book), "Тебе славу", mode)
            assertEquals(jsonBefore, gson.toJson(book))
            assertEquals(idsBefore, book.blocks.map { it.id })
            assertEquals(originalsBefore, book.blocks.map { it.original })
            assertEquals(computedHashBefore, ServiceBookIntegrity.contentHash(book))
            assertEquals(book.contentHash, gson.fromJson(jsonBefore, ServiceBook::class.java).contentHash)
            assertArrayEquals("The archived input itself must remain byte-identical", fixture.bytes, resource(fixture.filename))
        }
        val filesAfter = directory.listFiles().orEmpty().associate { it.name to it.readBytes() }
        assertEquals(filesBefore.keys, filesAfter.keys)
        for ((name, bytes) in filesBefore) assertArrayEquals("Accepted archive file $name changed", bytes, filesAfter[name])
    }

    @Test fun newSearchHitsUseTheFullOriginalAndItsUtf16OffsetsEvenWhenRussianIsPreferred() {
        for (fixture in fixtures) for (target in fixture.targets) {
            val book = fixture.book
            val block = book.blocks.single { it.id == target.id }
            val query = prayerQuery(block)
            for (preference in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN)) {
                val result = BookCorpusSearch.find(listOf(book), query, preference).single { it.hit.blockId == target.id }
                assertEquals(BookReadingMode.ORIGINAL, result.hit.mode)
                assertTrue("The editorial prefix must not shift source offsets", result.hit.matchStart >= PREFIX.length)
                assertEquals(BookText.normalized(query), BookText.normalized(block.original.substring(
                    result.hit.matchStart, result.hit.matchEndExclusive)))
                assertEquals("All ten actual paragraphs fit the unchanged source excerpt", block.original, result.hit.excerpt)
                assertSame(result, result.validatedFor(book))
            }
        }
    }

    @Test fun previouslySavedRussianTargetsRemainValidWithoutRelabellingOrMovingTheirUtf16Offsets() {
        val parish = "legacy-presentation-test"
        val journey = BookJourneyContext(parish, "2026-11-12", "18:00", "Вечернее богослужение",
            fixtures.map { it.book.key }, partSet = BookPartSetKind.EVENING_TEXTS)
        for (fixture in fixtures) for (target in fixture.targets) {
            val book = fixture.book
            val block = book.blocks.single { it.id == target.id }
            val hit = BookSearch.find(book, prayerQuery(block), BookReadingMode.ORIGINAL)
                .single { it.blockId == target.id }.copy(mode = BookReadingMode.RUSSIAN)
            val pending = BookLocatedSearchHit(book.key, book.contentHash, hit)
            assertNull(BookBlockPresentation.effectiveRussian(book.key, block))
            assertSame("Raw Russian remains a valid compatibility source for the old target", pending, pending.validatedFor(book))
            assertEquals(block.original.substring(hit.matchStart, hit.matchEndExclusive),
                requireNotNull(block.russian).substring(hit.matchStart, hit.matchEndExclusive))
            val route = BookRoute(book.key, parish, journey, pendingSearchTarget = pending)
            val encoded = BookRouteCodec.encode(route)
            val reopened = requireNotNull(BookRouteCodec.decode(encoded))
            assertEquals(pending, reopened.pendingSearchTarget)
            assertEquals(BookReadingMode.RUSSIAN, reopened.pendingSearchTarget?.hit?.mode)
            assertEquals(pending, reopened.pendingSearchTarget?.validatedFor(book))
            assertEquals(encoded, BookRouteCodec.encode(reopened))
        }
    }

    @Test fun onlyTheExactPublicMatinsAndVespersShapeIsInterpretedAndGenuineTranslationsRemainAvailable() {
        val fixture = fixtures.last()
        val key = fixture.book.key
        val block = fixture.book.blocks.single { it.id == fixture.targets.first().id }
        val altered = listOf(
            block.copy(original = PREFIX, russian = PREFIX),
            block.copy(original = "$PREFIX   ", russian = "$PREFIX   "),
            block.copy(original = block.original.replace(PREFIX, "Диакон возглашает:")),
            block.copy(original = block.original.replace(PREFIX, "Священник возглашает:")),
            block.copy(original = "и" + block.original.drop(1)),
            block.copy(original = " " + block.original),
            block.copy(isAltarText = true),
            block.copy(kind = BookBlockKind.PRAYER),
            block.copy(kind = BookBlockKind.SCRIPTURE),
            block.copy(kind = BookBlockKind.LOCAL_VARIATION)
        )
        for (other in altered) {
            assertFalse(other.original, BookBlockPresentation.isLegacyPrayer(key, other))
            assertEquals("Unreviewed shapes retain their existing translation field", other.russian,
                BookBlockPresentation.effectiveRussian(key, other))
        }
        for (kind in WorshipKind.entries.filterNot { it == WorshipKind.MATINS || it == WorshipKind.VESPERS }) {
            val other = key.copy(worship = kind)
            assertFalse("Composite VIGIL is deliberately not inferred from component IDs", BookBlockPresentation.isLegacyPrayer(other, block))
            assertFalse(BookBlockPresentation.isSpoken(other, block))
            assertNull(BookBlockPresentation.spokenText(other, block))
            assertEquals(block.russian, BookBlockPresentation.effectiveRussian(other, block))
        }
        val published = BookSource("Проверенная опубликованная пара", "https://azbyka.ru/days/2026-11-13")
        val translated = block.copy(russian = "Ибо Ты милостив и любишь людей", translationSource = published)
        assertEquals(translated.russian, BookBlockPresentation.effectiveRussian(key, translated))
        assertEquals(translated.russian, BookBlockPresentation.spokenText(key, translated, BookReadingMode.RUSSIAN))
        val attributedSameText = block.copy(translationSource = published)
        assertEquals("An attributed value cannot be erased by the legacy empty-source guard", block.original,
            BookBlockPresentation.effectiveRussian(key, attributedSameText))
        val missing = block.copy(russian = null)
        assertTrue(BookBlockPresentation.isSpoken(key, missing))
        assertNull(BookBlockPresentation.effectiveRussian(key, missing))
        assertEquals(block.original.substring(PREFIX.length).trim(), BookBlockPresentation.spokenText(key, missing))
    }

    @Test fun russianResponseContextCannotJumpAcrossAnUntranslatedLegacyPrayerButOriginalCanUseIt() {
        val fixture = fixtures.first()
        val legacy = fixture.book.blocks.single { it.id == fixture.targets.first().id }
        val older = BookBlock("older-petition", BookBlockKind.PRAYER, null,
            "Предыдущее прошение о совершенно другом", "Ранее просили о другом")
        val response = BookBlock("short-response", BookBlockKind.PRAYER, null, "Ами́нь.", "Аминь.")
        // An explicitly synthetic context rearranges only the two test petitions around a genuine unchanged paragraph.
        val book = fixture.book.copy(chapters = listOf(BookChapter("synthetic-context", "Тест контекста",
            "Синтетический контекст для проверки поиска", listOf(older, legacy, response))))
        val russian = BookSearch.find(book, "Аминь", BookReadingMode.RUSSIAN).single()
        assertEquals(BookReadingMode.RUSSIAN, russian.mode)
        assertEquals("Аминь.", russian.excerpt)
        val original = BookSearch.find(book, "Аминь", BookReadingMode.ORIGINAL).single()
        assertEquals(BookReadingMode.ORIGINAL, original.mode)
        assertTrue("The immediately preceding prayer is actual source context", original.excerpt.contains(legacy.original.takeLast(35)))
        assertFalse(original.excerpt.contains(older.original))
        assertFalse(original.excerpt.contains(older.russian.orEmpty()))
    }

    @Test fun microphoneHypothesisSelectionStillRecognizesTheUnchangedHeardPrayer() {
        for (fixture in fixtures) {
            val block = fixture.book.blocks.single { it.id == fixture.targets.first().id }
            val heard = prayerQuery(block)
            assertEquals(heard, BookSpeechSearch.query(fixture.book, listOf("совсем другая фраза", heard)))
        }
    }

    private fun prayerQuery(block: BookBlock) = block.original.substring(PREFIX.length).trim().split(' ').take(6).joinToString(" ")
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResource("legacy-presentation/$name")).readBytes()
    private data class Target(val id: String, val words: Int)
    private data class Fixture(val filename: String, val bytes: ByteArray, val book: ServiceBook, val targets: List<Target>)

    private companion object { const val PREFIX = "И священник возглашает:" }
}
