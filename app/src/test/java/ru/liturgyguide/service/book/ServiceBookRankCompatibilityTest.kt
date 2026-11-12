package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import java.io.File
import java.nio.file.Files

class ServiceBookRankCompatibilityTest {
    private val gson = Gson()
    private val directory = Files.createTempDirectory("rank-compatibility-").toFile()
    @After fun clean() { directory.deleteRecursively() }

    @Test fun frozenPrechangeJvmBookAndPositionKeepTheirJsonHashAndStorageAddress() {
        val frozen = JsonParser.parseString(MatinsRankFixtures.text("legacy-freeze-manifest.json")).asJsonObject
        assertEquals("8f6765a", frozen["baselineCommit"].asString)
        assertEquals(3, frozen.getAsJsonObject("loadedClassSha256").size())
        frozen.getAsJsonArray("files").forEach { item ->
            val entry = item.asJsonObject
            val bytes = MatinsRankFixtures.text(entry["path"].asString).toByteArray(Charsets.UTF_8)
            assertEquals(entry["bytes"].asLong, bytes.size.toLong())
            assertEquals(entry["sha256"].asString, BookText.sha256(bytes.toString(Charsets.UTF_8)))
        }
        val raw = MatinsRankFixtures.text("legacy-book.json")
        val positionRaw = MatinsRankFixtures.text("legacy-position.json")
        val receipt = JsonParser.parseString(MatinsRankFixtures.text("legacy-receipt.json")).asJsonObject
        assertTrue(receipt["validBeforeChange"].asBoolean)
        assertTrue(receipt["rankFieldsAbsentInLoadedClasses"].asBoolean)
        val expectedHash = receipt["contentHash"].asString
        val expectedCanonical = receipt["canonical"].asString
        val book = gson.fromJson(raw, ServiceBook::class.java)
        assertNull(book.key.topLevel)
        assertNull(book.rankSelection)
        assertEquals(expectedCanonical, book.key.canonical())
        assertEquals(receipt["sourceUrl"].asString, book.key.sourceUrl())
        assertEquals(expectedHash, book.contentHash)
        assertEquals(expectedHash, ServiceBookIntegrity.contentHash(book))
        assertTrue(ServiceBookIntegrity.valid(book))
        assertEquals("Null additions must not change pre-change JSON", raw, gson.toJson(book))
        val position = requireNotNull(BookReadingCodec.decode(positionRaw))
        assertEquals(positionRaw, BookReadingCodec.encode(position))
        assertEquals(book.key, position.key)
        assertEquals(expectedHash, position.contentHash)

        // Put the unchanged old bytes at the independently frozen old canonical address.
        val stem = BookText.sha256(expectedCanonical).take(32)
        File(directory, "$stem-$expectedHash.json").writeText(raw)
        File(directory, "$stem.latest").writeText(expectedHash)
        val cache = FileBookCache(directory)
        assertEquals(book, cache.read(book.key))
        assertEquals(book, cache.readVersion(book.key, expectedHash))
        assertEquals(raw, File(directory, "$stem-$expectedHash.json").readText())

        val preferences = AcceptanceMemoryPreferences()
        preferences.edit().putString("position:" + BookText.sha256("${position.parishId}|$expectedCanonical"), positionRaw)
            .putString("last:" + BookText.sha256(position.parishId.orEmpty()), positionRaw).commit()
        val before = preferences.all.toMap()
        val store = BookReadingStore(preferences)
        assertEquals(position, store.positionFor(book.key, position.parishId))
        assertEquals(position, store.last(position.parishId))
        assertEquals(before, preferences.all)
    }

    @Test fun newRankHasASeparateKeyAndUnsupportedCombinationsCannotDegradeToDaily() {
        val key = MatinsRankFixtures.key()
        val daily = key.copy(topLevel = null)
        assertEquals("2026-11-13|MATINS|34|~|~", daily.canonical())
        assertEquals("2026-11-13|MATINS|34|~|~|top_level=3", key.canonical())
        assertTrue(key.isValid())
        assertNotEquals(daily, key)
        assertThrows(IllegalArgumentException::class.java) { key.sourceUrl() }
        for (value in listOf("", "0", "1", "2", "4", "unexpected")) {
            assertFalse(value, key.copy(topLevel = value).isValid())
        }
        for (kind in WorshipKind.entries.filterNot { it == WorshipKind.MATINS })
            assertFalse(kind.name, key.copy(worship = kind).isValid())
        for (value in listOf("", "0", "27")) {
            assertFalse(key.copy(mainEvent = value).isValid())
            assertFalse(key.copy(secondEvent = value).isValid())
        }
        val position = BookReadingPosition(daily, "a".repeat(64), "rank-test", "block", "chapter")
        val json = JsonParser.parseString(BookReadingCodec.encode(position)).asJsonObject
        json.getAsJsonObject("key").addProperty("topLevel", "unknown-rank")
        assertNull(BookReadingCodec.decode(json.toString()))
        val route = JsonParser.parseString(BookRouteCodec.encode(BookRoute(daily, "rank-test"))).asJsonObject
        route.getAsJsonObject("activeKey").addProperty("topLevel", "unknown-rank")
        assertNull(BookRouteCodec.decode(route.toString()))
    }

    @Test fun metadataAndResolvedUrlMustAgreeEvenWhenACorruptBookHashIsRecomputed() {
        val original = MatinsRankFixtures.parse()
        val selection = requireNotNull(original.rankSelection)
        val variants = listOf(
            original.copy(rankSelection = null),
            original.copy(key = original.key.copy(topLevel = null)),
            original.copy(rankSelection = selection.copy(mainEvent = "27")),
            original.copy(rankSelection = selection.copy(mainEvent = "")),
            original.copy(rankSelection = selection.copy(secondEvent = "1")),
            original.copy(rankSelection = selection.copy(topLevel = "4")),
            original.copy(sourceUrl = original.key.copy(topLevel = null).sourceUrl()),
            original.copy(sourceUrl = original.sourceUrl.replace("main_event=0", "main_event=27")),
            original.copy(sourceUrl = original.sourceUrl.replace("2026-11-13", "2026-11-18")),
            original.copy(sourceUrl = original.sourceUrl.replace("church=34", "church=30")),
            original.copy(sourceUrl = original.sourceUrl.replace("https://azbyka.ru", "https://example.org")),
            original.copy(sourceUrl = original.sourceUrl + "&second_event=1"),
            original.copy(sourceUrl = original.sourceUrl + "#polielej")
        )
        for ((index, changed) in variants.withIndex()) {
            val corrupt = MatinsRankFixtures.rehash(changed)
            assertFalse("metadata/url mutation $index", ServiceBookIntegrity.valid(corrupt))
            assertFalse("cache must reject mutation $index", FileBookCache(directory).write(corrupt))
        }
        val absentField = JsonParser.parseString(gson.toJson(original)).asJsonObject
        absentField.getAsJsonObject("rankSelection").remove("secondEvent")
        val corrupt = gson.fromJson(absentField, ServiceBook::class.java)
        assertFalse(ServiceBookIntegrity.valid(MatinsRankFixtures.rehash(corrupt)))
    }

    @Test fun rankCacheCannotAcceptMetadataOverARealDailyBookOrAnEmptyGospel() {
        val ranked = MatinsRankFixtures.parse()
        val daily = ServiceBookParser().parse(MatinsRankFixtures.document(), ranked.key.copy(topLevel = null), 12345)
        val disguised = MatinsRankFixtures.rehash(daily.copy(key = ranked.key, rankSelection = ranked.rankSelection,
            sourceUrl = ranked.sourceUrl))
        assertFalse(ServiceBookIntegrity.valid(disguised))
        val missingReading = MatinsRankFixtures.rehash(ranked.copy(chapters = ranked.chapters.map { chapter ->
            if (BookText.normalized(chapter.title) == "чтение евангелия")
                chapter.copy(blocks = chapter.blocks.filterNot { it.kind == BookBlockKind.SCRIPTURE }) else chapter
        }))
        assertFalse(ServiceBookIntegrity.valid(missingReading))
        val stem = BookText.sha256(ranked.key.canonical()).take(32)
        File(directory, "$stem-${missingReading.contentHash}.json").writeText(gson.toJson(missingReading))
        File(directory, "$stem.latest").writeText(missingReading.contentHash)
        assertNull(FileBookCache(directory).read(ranked.key))
        assertNull(FileBookCache(directory).readVersion(ranked.key, missingReading.contentHash))
    }

    @Test fun dailyAndRankedBooksAndTheirAcceptedPositionsRemainIndependent() {
        val ranked = MatinsRankFixtures.parse()
        val daily = ServiceBookParser().parse(MatinsRankFixtures.document(), ranked.key.copy(topLevel = null), 12345)
        val cache = FileBookCache(directory)
        assertTrue(cache.write(daily)); assertTrue(cache.write(ranked))
        assertEquals(daily, cache.read(daily.key)); assertEquals(ranked, cache.read(ranked.key))
        assertNull(cache.readVersion(ranked.key, daily.contentHash))
        assertNull(cache.readVersion(daily.key, ranked.contentHash))
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val oldPosition = BookReadingPosition(daily.key, daily.contentHash, "rank-test", daily.blocks.first().id,
            daily.chapters.first().id, fraction = .31f, mode = BookReadingMode.RUSSIAN)
        val newPosition = BookReadingPosition(ranked.key, ranked.contentHash, "rank-test", ranked.blocks.last().id,
            ranked.chapters.last().id, fraction = .62f, mode = BookReadingMode.ORIGINAL)
        assertTrue(store.save(oldPosition)); assertTrue(store.save(newPosition))
        assertEquals(oldPosition, store.positionFor(daily.key, "rank-test"))
        assertEquals(newPosition, store.positionFor(ranked.key, "rank-test"))
        assertEquals(newPosition, store.last("rank-test"))
    }
}
