package ru.liturgyguide.service.book

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.BookBlock
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import ru.liturgyguide.service.schedule.UrlConnectionScheduleTransport
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in native C9 archive + live forced refresh. No UI, radio changes or process-death claim. */
@RunWith(AndroidJUnit4::class)
class LiveVisiblePrayerBoundariesTest {
    @Test fun forcedRefreshSeparatesTwelvePublishedPrayersAndAcceptsTheirPositionsOffline() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with feoriaVisiblePrayerLive=true", args.getString("feoriaVisiblePrayerLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir("visible-prayer-acceptance"))
        val bookInput = File(root, "input/accepted-real-book.json")
        val corpusInput = File(root, "input/corpus9.json")
        val run = args.getString("visiblePrayerRun") ?: UUID.randomUUID().toString()
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val output = File(root, "runs/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new visiblePrayerRun to preserve earlier receipts" }
        val responseDirectory = File(output, "responses").apply { check(mkdirs()) }
        val isolatedName = "visible-prayer-acceptance-${UUID.randomUUID()}"
        val directory = File(context.filesDir, isolatedName)
        val preferences = context.getSharedPreferences(isolatedName, Context.MODE_PRIVATE)
        val userPreferences = listOf("guide_preferences", "service_book_reading", "internet_schedule_cache", "place_discovery_cache")
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        val requests = CopyOnWriteArrayList<JSONObject>()
        val responses = ConcurrentHashMap<String, ScheduleResponse>()
        val requestSequence = AtomicInteger()
        val offlineRequests = AtomicInteger()
        val started = SystemClock.elapsedRealtime()
        val report = JSONObject().put("allPassed", false).put("run", run)
            .put("scope", "Native FileBookCache, production forced ServiceBookRepository refresh, public URLConnection and calendar feed, explicit BookReadingStore acceptance")
            .put("reopenScope", "New cache/repository/store instances in one instrumentation process; no UI or process restart")
            .put("offlineScope", "Both book and calendar transports reject every request; device radios are unchanged")
            .put("responseStatusScope", "ScheduleResponse exposes transport success/failure, not the numeric HTTP status")
            .put("inputRehashed", false).put("isolatedDirectory", directory.absolutePath)
            .put("isolatedPreferences", isolatedName).put("inputBook", bookInput.absolutePath)
            .put("inputCorpus", corpusInput.absolutePath)
        var inputBookBytes: ByteArray? = null
        var inputCorpusBytes: ByteArray? = null
        var failure: Throwable? = null
        try {
            assertTrue("Supply the unmodified checkpoint26 native book", bookInput.isFile && bookInput.length() in 1L..12_000_000L)
            assertTrue("Supply the unmodified checkpoint26 corpus9", corpusInput.isFile && corpusInput.length() in 1L..2_000_000L)
            val rawBook = bookInput.readBytes().also { inputBookBytes = it }
            val rawCorpus = corpusInput.readBytes().also { inputCorpusBytes = it }
            report.put("inputBookRawSha256", sha256(rawBook)).put("inputCorpusRawSha256", sha256(rawCorpus))
            assertEquals(BOOK_RAW_SHA256, sha256(rawBook))
            assertEquals(CORPUS_RAW_SHA256, sha256(rawCorpus))
            val before = Gson().fromJson(rawBook.toString(Charsets.UTF_8), ServiceBook::class.java)
            val corpusJson = rawCorpus.toString(Charsets.UTF_8)
            val oldCorpus = BookTranslationCorpus.decode(corpusJson)
            val key = ServiceBookKey("2026-11-12", churchType = "10")
            report.put("inputStoredHash", before.contentHash).put("inputComputedNativeHash", ServiceBookIntegrity.contentHash(before))
            assertEquals(BOOK_NATIVE_HASH, before.contentHash)
            assertEquals("Validate the original Android hash without repairing it", before.contentHash, ServiceBookIntegrity.contentHash(before))
            assertTrue(ServiceBookIntegrity.valid(before))
            assertEquals(key, before.key)
            assertEquals(9, oldCorpus.version)
            assertEquals(1034, oldCorpus.index.size)
            assertEquals(587, before.blocks.size)
            assertEquals(333, visibleTotal(before))
            assertEquals(276, visibleTranslated(before))
            assertEquals(57, visibleMissing(before))
            assertEquals(12, TARGET_IDS.size)
            val oldById = before.blocks.associateBy { it.id }
            assertTrue(TARGET_IDS.all { oldById[it]?.let { block -> block.kind == BookBlockKind.RUBRIC && !block.isAltarText } == true })

            val cache = FileBookCache(directory)
            assertTrue(cache.write(before))
            assertTrue(cache.writeTranslationCorpus(corpusJson))
            assertEquals(before, FileBookCache(directory).readVersion(key, before.contentHash))
            val oldFile = editionFile(directory, before)
            val immutableBefore = oldFile.readBytes()
            val store = BookReadingStore(preferences)
            assertTrue(preferences.edit().putString("unrelated-sentinel", "keep").putInt("unrelated-setting", 93).commit())
            val seedPositions = (TARGET_IDS + UNCHANGED_ID).mapIndexed { index, id ->
                val chapter = before.chapters.single { item -> item.blocks.any { it.id == id } }
                BookReadingPosition(key, before.contentHash, "isolated-visible-$index", id, chapter.id,
                    fraction = .37f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 93,
                    showAltar = false, lastTextMode = BookReadingMode.RUSSIAN)
            }
            seedPositions.forEach { assertTrue(store.save(it)) }
            val preferencesBeforeDownload = preferences.all.toMap()

            val coreSources = BookTranslationIndex.PARALLEL_SOURCES
            val feedUrl = "${PublishedCalendarSupplements.BASE_URL}/${key.date}.json"
            val bookUrls = coreSources.map { it.url }.toSet() + setOf(key.sourceUrl(),
                "https://azbyka.ru/biblia/days/${key.date}", "https://azbyka.ru/days/${key.date}")
            fun observedTransport(kind: String, allowed: Set<String>, delegate: ScheduleTransport) = ScheduleTransport { url ->
                val sequence = requestSequence.incrementAndGet()
                val row = JSONObject().put("sequence", sequence).put("kind", kind).put("requestedUrl", url)
                    .put("transportOutcome", "pending")
                requests += row
                val requestStart = SystemClock.elapsedRealtime()
                try {
                    assertTrue("Only the reviewed public source set is allowed", url in allowed)
                    val response = delegate.fetch(url)
                    val bodyName = "%02d-%s.body".format(sequence, kind)
                    File(responseDirectory, bodyName).writeBytes(response.bytes)
                    responses[url] = response
                    row.put("transportOutcome", "returned").put("finalUrl", response.url)
                        .put("contentType", response.contentType).put("bytes", response.bytes.size)
                        .put("sha256", sha256(response.bytes)).put("bodyFile", "responses/$bodyName")
                    // ScheduleResponse does not expose an HTTP status. Never manufacture a 200.
                    response
                } catch (error: Throwable) {
                    row.put("transportOutcome", "failed").put("failureType", error.javaClass.simpleName)
                    throw error
                } finally { row.put("elapsedMillis", SystemClock.elapsedRealtime() - requestStart) }
            }
            val bookTransport = observedTransport("book", bookUrls, UrlConnectionScheduleTransport(timeoutMillis = 15_000))
            val calendarTransport = observedTransport("calendar", setOf(feedUrl),
                UrlConnectionScheduleTransport(timeoutMillis = 3_000, limitBytes = PublishedCalendarSupplements.MAX_BYTES))
            val calendar = PublishedCalendarSupplements(calendarTransport)
            val repository = ServiceBookRepository(cache, bookTransport, calendar = calendar)
            report.put("phase", "forced-refresh")
            val result = repository.load(key, force = true)
            assertTrue("A forced refresh must succeed, not return the old fallback", result is BookLoadResult.Ready)
            val ready = result as BookLoadResult.Ready
            val next = ready.book
            val newCorpusJson = requireNotNull(cache.readTranslationCorpus())
            report.put("publishedHash", next.contentHash).put("publishedComputedNativeHash", ServiceBookIntegrity.contentHash(next))
                .put("saved", ready.saved).put("fromCache", ready.fromCache)
                .put("beforeBlocks", before.blocks.size).put("afterBlocks", next.blocks.size)
                .put("visiblePrayerTotal", visibleTotal(next)).put("visibleRussian", visibleTranslated(next))
                .put("visibleMissing", visibleMissing(next))
                .put("metadataChanges", JSONObject().put("fetchedAtChanged", before.fetchedAtMillis != next.fetchedAtMillis)
                    .put("calendarChanged", before.calendarDay != next.calendarDay).put("sourcesChanged", before.sources != next.sources)
                    .put("noticesChanged", before.notices != next.notices).put("enrichmentChanged", before.enrichment != next.enrichment))
            File(output, "refreshed-book.json").writeBytes(editionFile(directory, next).readBytes())
            File(output, "russian-concordance.json").writeText(newCorpusJson, Charsets.UTF_8)
            assertTrue(ready.saved)
            assertFalse(ready.fromCache)
            assertNotEquals(before.contentHash, next.contentHash)
            assertTrue(ServiceBookIntegrity.valid(next))
            assertEquals(next, FileBookCache(directory).read(key))
            assertEquals(next, FileBookCache(directory).readVersion(key, next.contentHash))
            assertEquals("All expected live public requests must return", bookUrls + feedUrl, responses.keys.toSet())
            assertEquals("No hidden repeat or extra request", (bookUrls + feedUrl).size, requests.size)
            assertTrue(requests.all { it.getString("transportOutcome") == "returned" })
            val newCorpus = BookTranslationCorpus.decode(newCorpusJson)
            assertEquals(10, newCorpus.version)
            assertEquals(coreSources.map { it.url }.toSet(), newCorpus.completedSources)
            report.put("publishedCorpusVersion", newCorpus.version).put("publishedCorpusEntries", newCorpus.index.size)
                .put("completedSources", JSONArray(newCorpus.completedSources.sorted())).put("phase", "source-and-structure")

            val feed = responses.getValue(feedUrl)
            assertEquals(feedUrl, feed.url)
            val supplement = requireNotNull(PublishedCalendarSupplements.decode(feed.bytes.toString(Charsets.UTF_8), key.date))
            assertEquals(supplement.day, next.calendarDay)
            assertEquals(key, next.key)
            assertEquals(before.title, next.title)
            assertEquals(before.sourceUrl, next.sourceUrl)
            assertEquals(before.sourceTitle, next.sourceTitle)
            assertEquals(before.selectedEventTitle, next.selectedEventTitle)
            assertEquals(before.mainEventOptions, next.mainEventOptions)
            assertEquals(before.secondEventOptions, next.secondEventOptions)
            assertEquals(before.chapters.size, next.chapters.size)
            assertEquals(599, next.blocks.size)
            assertEquals(next.blocks.size, next.blocks.map { it.id }.toSet().size)
            val streamBefore = originalStream(before)
            val streamAfter = originalStream(next)
            report.put("originalStreamBeforeSha256", BookText.sha256(streamBefore))
                .put("originalStreamAfterSha256", BookText.sha256(streamAfter))
            assertEquals("A structural correction cannot lose, add or reorder any original word", streamBefore, streamAfter)

            // Obtain the expectations from the archived whole blocks and document order, not the production prefix list.
            val splitPairs = linkedMapOf<String, Pair<BookBlock, BookBlock>>()
            val changedUnselected = JSONArray()
            report.put("changedUnselectedBlocks", changedUnselected)
            var unchangedCount = 0
            before.chapters.zip(next.chapters).forEach { (oldChapter, newChapter) ->
                assertEquals(oldChapter, newChapter.copy(blocks = oldChapter.blocks))
                var newIndex = 0
                oldChapter.blocks.forEach { old ->
                    val current = newChapter.blocks.getOrNull(newIndex)
                    assertNotNull("Missing block at ${old.id}", current)
                    val first = requireNotNull(current)
                    if (old.id in TARGET_IDS) {
                        val prayer = requireNotNull(newChapter.blocks.getOrNull(newIndex + 1))
                        assertEquals(BookBlockKind.RUBRIC, first.kind)
                        assertEquals(BookBlockKind.PRAYER, prayer.kind)
                        assertEquals(old.isAltarText, first.isAltarText)
                        assertEquals(old.isAltarText, prayer.isAltarText)
                        assertEquals(old.original, "${first.original} ${prayer.original}")
                        assertEquals(first.original, first.russian)
                        assertEquals(first.original, first.meaning)
                        assertNull(first.translationSource)
                        assertTrue(first.scriptureReferences.isEmpty() && prayer.scriptureReferences.isEmpty())
                        assertTrue(next.blocks.none { it.id == old.id })
                        assertEquals(1, oldChapter.blocks.count { it.original == old.original && it.isAltarText == old.isAltarText })
                        assertEquals(1, newChapter.blocks.zipWithNext().count { (a, b) ->
                            a.kind == BookBlockKind.RUBRIC && b.kind == BookBlockKind.PRAYER &&
                                a.isAltarText == old.isAltarText && b.isAltarText == old.isAltarText &&
                                "${a.original} ${b.original}" == old.original
                        })
                        splitPairs[old.id] = first to prayer
                        newIndex += 2
                    } else {
                        if (old != first) changedUnselected.put(JSONObject().put("old", JSONObject(Gson().toJson(old)))
                            .put("new", JSONObject(Gson().toJson(first))))
                        assertEquals("Existing ID/order, original, Russian, source, meaning, scope and references must survive: ${old.id}", old, first)
                        unchangedCount++
                        newIndex++
                    }
                }
                assertEquals("No unaccounted fragment may be appended", newChapter.blocks.size, newIndex)
            }
            report.put("changedUnselectedBlocks", changedUnselected).put("unaffectedBlocksExactlyEqual", unchangedCount)
            assertEquals(TARGET_IDS.toSet(), splitPairs.keys)
            assertEquals(575, unchangedCount)
            assertEquals(345, visibleTotal(next))
            assertEquals(288, visibleTranslated(next))
            assertEquals(57, visibleMissing(next))
            assertEquals(before.blocks.filter { it.kind == BookBlockKind.SCRIPTURE }, next.blocks.filter { it.kind == BookBlockKind.SCRIPTURE })

            val liveIndexes = coreSources.map { source ->
                val response = responses.getValue(source.url)
                BookTranslationIndex.fromParallelPage(requireNotNull(response.html()), source.copy(url = response.url))
            }
            val pairEvidence = JSONArray()
            report.put("splitPairEvidence", pairEvidence).put("phase", "published-pairs")
            var archivedPairCount = 0
            var newLivePairCount = 0
            splitPairs.forEach { (oldId, pair) ->
                val (rubric, prayer) = pair
                assertNotNull("Every separated prayer needs a published whole Russian pair", prayer.russian)
                val source = requireNotNull(prayer.translationSource)
                assertNotEquals(BookTranslationIndex.EDITORIAL_SOURCE, source)
                val actual = BookTranslation(requireNotNull(prayer.russian), source)
                val live = liveIndexes.mapNotNull { it.find(prayer.original) }
                    .firstOrNull { it.russian == actual.russian && it.source == actual.source }
                val archived = oldCorpus.index.find(prayer.original)?.takeIf {
                    it.russian == actual.russian && it.source == actual.source
                }
                assertTrue("The whole original and Russian must match a real returned page or the byte-verified C9 corpus: $oldId",
                    live != null || archived != null)
                if (archived != null) archivedPairCount++ else if (live != null) newLivePairCount++
                pairEvidence.put(JSONObject().put("oldId", oldId).put("rubricId", rubric.id).put("prayerId", prayer.id)
                    .put("sourceUrl", source.url).put("originalSha256", BookText.sha256(prayer.original))
                    .put("russianSha256", BookText.sha256(actual.russian))
                    .put("matchesLivePair", live != null).put("matchesArchivedC9Pair", archived != null))
            }
            report.put("splitCount", splitPairs.size).put("archivedExactPairs", archivedPairCount)
                .put("newLiveExactPairs", newLivePairCount)
            assertEquals(10, archivedPairCount)
            assertEquals(2, newLivePairCount)
            assertEquals("Downloading must not change accepted reading positions or unrelated settings", preferencesBeforeDownload, preferences.all)
            seedPositions.forEach { assertEquals(it, store.positionFor(key, it.parishId)); assertEquals(it, store.last(it.parishId)) }
            assertEquals(before, FileBookCache(directory).readVersion(key, before.contentHash))
            assertArrayEquals(immutableBefore, oldFile.readBytes())

            val acceptedPositions = linkedMapOf<String, BookReadingPosition>()
            val acceptanceRows = JSONArray()
            report.put("acceptanceCases", acceptanceRows).put("phase", "explicit-acceptance")
            seedPositions.forEach { seed ->
                BookReadingMode.entries.forEach { mode ->
                    val position = seed.copy(mode = mode, lastTextMode = BookReadingMode.RUSSIAN)
                    assertTrue(store.save(position))
                    val snapshot = preferences.all.toMap()
                    val split = splitPairs[position.blockId]
                    val expected = position.copy(contentHash = next.contentHash,
                        blockId = split?.first?.id ?: position.blockId,
                        fraction = if (split == null) position.fraction else 0f)
                    assertTrue("Explicit acceptance must succeed", store.acceptVersion(before, next, position.parishId, position))
                    assertEquals(expected, store.positionFor(key, position.parishId))
                    assertEquals(expected, store.last(position.parishId))
                    val afterPreferences = preferences.all.toMap()
                    val changedKeys = (snapshot.keys + afterPreferences.keys).filter { snapshot[it] != afterPreferences[it] }
                    assertEquals("Only per-key and last accepted positions may change", 2, changedKeys.size)
                    changedKeys.forEach { changed ->
                        assertEquals(position, BookReadingCodec.decode(snapshot[changed] as? String))
                        assertEquals(expected, BookReadingCodec.decode(afterPreferences[changed] as? String))
                    }
                    acceptedPositions[requireNotNull(position.parishId)] = expected
                    acceptanceRows.put(JSONObject().put("before", JSONObject(Gson().toJson(position)))
                        .put("accepted", JSONObject(Gson().toJson(expected))).put("split", split != null))
                }
            }
            report.put("acceptanceCases", acceptanceRows).put("acceptedOnlyExplicitly", true)
            assertEquals(39, acceptanceRows.length())

            report.put("phase", "offline")
            val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport {
                offlineRequests.incrementAndGet(); throw AssertionError("Offline book transport is forbidden")
            }, calendar = PublishedCalendarSupplements(ScheduleTransport {
                offlineRequests.incrementAndGet(); throw AssertionError("Offline calendar transport is forbidden")
            }))
            val restoredStore = BookReadingStore(context.getSharedPreferences(isolatedName, Context.MODE_PRIVATE))
            acceptedPositions.forEach { (parishId, expected) ->
                assertEquals(expected, restoredStore.positionFor(key, parishId))
                assertEquals(expected, restoredStore.last(parishId))
                assertEquals(expected, restoredStore.restore(next, parishId))
                assertEquals(next, offline.cachedVersion(expected.key, expected.contentHash))
            }
            val reopened = offline.load(key)
            assertTrue(reopened is BookLoadResult.Ready && reopened.fromCache && reopened.saved && reopened.book == next)
            assertNull(offline.enrichSaved(next, allowNetwork = false))
            assertEquals(0, offlineRequests.get())
            assertEquals(before, offline.cachedVersion(key, before.contentHash))
            assertArrayEquals("The old immutable edition must survive acceptance", immutableBefore, oldFile.readBytes())
            report.put("oldEditionRawSha256", sha256(immutableBefore)).put("oldEditionUnchanged", true)
                .put("offlineBookAndPositionsEqual", true).put("phase", "complete")
        } catch (error: Throwable) {
            failure = error
        } finally {
            fun verifyFinal(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
            verifyFinal {
                inputBookBytes?.let { assertArrayEquals(it, bookInput.readBytes()) }
                inputCorpusBytes?.let { assertArrayEquals(it, corpusInput.readBytes()) }
                report.put("inputsUnchanged", inputBookBytes != null && inputCorpusBytes != null)
                userPreferences.forEach { (name, values) ->
                    assertEquals("User preference store $name must stay unchanged", values,
                        context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
                }
                report.put("userPreferencesUnchanged", true)
            }
            verifyFinal {
                assertTrue("Delete only the isolated preference store", context.deleteSharedPreferences(isolatedName))
                assertTrue(!directory.exists() || directory.deleteRecursively())
                report.put("isolatedStateCleaned", true)
            }
            report.put("requests", JSONArray(requests.sortedBy { it.getInt("sequence") }))
                .put("offlineRequests", offlineRequests.get()).put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                .put("allPassed", failure == null)
            failure?.let { report.put("failureType", it.javaClass.simpleName).put("failure", it.message?.take(2_000) ?: "") }
            File(output, "receipt.json").writeText(report.toString(2), Charsets.UTF_8)
        }
        failure?.let { throw it }
    }

    private fun editionFile(directory: File, book: ServiceBook) =
        File(directory, "${BookText.sha256(book.key.canonical()).take(32)}-${book.contentHash}.json")
    private fun originalStream(book: ServiceBook) = book.blocks.joinToString(" ") { it.original }
    private fun visibleTotal(book: ServiceBook) = book.blocks.count { it.kind == BookBlockKind.PRAYER && !it.isAltarText }
    private fun visibleTranslated(book: ServiceBook) = book.blocks.count { it.kind == BookBlockKind.PRAYER && !it.isAltarText && it.russian != null }
    private fun visibleMissing(book: ServiceBook) = book.blocks.count { it.kind == BookBlockKind.PRAYER && !it.isAltarText && it.russian == null }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BOOK_RAW_SHA256 = "ce67c53520c9e13e88de7164939f04d4724d4d3704e30b615c4e4da4fd74188e"
        private const val BOOK_NATIVE_HASH = "5d3b66f3fc0ed67fe8fb1247c18b7db47ff97bcba2757f9c29b2666bad20c046"
        private const val CORPUS_RAW_SHA256 = "987302f2ac7f27f39dc7a62c239cdf6bae9ea3474a075a74737c4da3d37c1371"
        private const val UNCHANGED_ID = "liturgy-f310daaa5360-1:88641a8d633c1e9b:1"
        // Independent archived block identities from the complete native C9 census, not parser prefixes.
        private val TARGET_IDS = listOf(
            "liturgy-06714216a0de-1:b10848b54ed919ac:1",
            "liturgy-178e5055ac2b-1:00a89f4488a8e4fe:1",
            "liturgy-178e5055ac2b-1:a779530bf587806c:1",
            "liturgy-178e5055ac2b-1:ac09dd84e8c5bf36:1",
            "liturgy-21d28bc25f3d-1:405d7b204081bc00:1",
            "liturgy-5b5535a3d8e6-1:7322e9e89c5b022c:1",
            "liturgy-5d1f94f15d27-1:0bc39105b1999873:1",
            "liturgy-af63642abcec-1:6bfea96e4df0c5da:1",
            "liturgy-d66ea8339379-1:49fe2d58c581bf32:1",
            "liturgy-e8e07c76cf8b-1:2f77e49339cfdc87:1",
            "liturgy-ed716e9f74f9-1:00a89f4488a8e4fe:1",
            "liturgy-ed716e9f74f9-2:0eecffd339f17734:1"
        )
    }
}
