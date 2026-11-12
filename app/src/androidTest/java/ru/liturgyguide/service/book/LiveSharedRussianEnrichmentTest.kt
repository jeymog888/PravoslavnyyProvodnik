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

/** Opt-in native archive + real public HTTP acceptance. Repository/store only, no reader UI or process restart. */
@RunWith(AndroidJUnit4::class)
class LiveSharedRussianEnrichmentTest {
    @Test fun nativeType10GainsPublishedPairsAndAcceptsTheSamePositionOffline() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with feoriaSharedRussianLive=true", args.getString("feoriaSharedRussianLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir("shared-russian-acceptance"))
        val input = File(root, "input")
        val bookFile = File(input, "accepted-real-book.json")
        val corpusFile = File(input, "core-corpus8.json")
        val run = args.getString("sharedRussianRun") ?: UUID.randomUUID().toString()
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val output = File(root, "runs/$run")
        check(!output.exists() && output.mkdirs()) { "Use a new sharedRussianRun to keep earlier receipts" }
        val isolatedName = "shared-russian-acceptance-${UUID.randomUUID()}"
        val directory = File(context.filesDir, isolatedName)
        val preferences = context.getSharedPreferences(isolatedName, Context.MODE_PRIVATE)
        val userPreferences = listOf("guide_preferences", "service_book_reading", "internet_schedule_cache")
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        val attempts = CopyOnWriteArrayList<String>()
        val retrieved = CopyOnWriteArrayList<JSONObject>()
        val responses = ConcurrentHashMap<String, ScheduleResponse>()
        val offlineAttempts = AtomicInteger()
        val report = JSONObject().put("allPassed", false).put("run", run)
            .put("scope", "Native Android FileBookCache, production ServiceBookRepository, public URLConnection transport, BookReadingStore acceptance; no UI")
            .put("reopenScope", "New repository/cache/store instances in one instrumentation process, not process death")
            .put("offlineScope", "Repository transport rejects every request; device radios are unchanged")
            .put("inputBook", bookFile.absolutePath).put("inputCorpus", corpusFile.absolutePath)
            .put("isolatedFilesDirectory", directory.absolutePath).put("isolatedPreferences", isolatedName)
            .put("date", "2026-11-12").put("churchType", "10")
        var failure: Throwable? = null
        var originalBytes: ByteArray? = null
        var corpusBytes: ByteArray? = null
        try {
            assertTrue("Supply the exact checkpoint22 Android archive", bookFile.isFile && bookFile.length() in 1L..12_000_000L)
            assertTrue("Supply the archived core corpus8", corpusFile.isFile && corpusFile.length() in 1L..2_000_000L)
            val inputBookBytes = bookFile.readBytes()
            val inputCorpusBytes = corpusFile.readBytes()
            originalBytes = inputBookBytes
            corpusBytes = inputCorpusBytes
            val rawHash = sha256(inputBookBytes)
            val corpusHash = sha256(inputCorpusBytes)
            report.put("inputRawSha256", rawHash).put("inputCorpusSha256", corpusHash)
            assertEquals("Do not substitute or reserialize the checkpoint22 input", BOOK_RAW_SHA256, rawHash)
            assertEquals("Use the unmodified archived corpus8", CORPUS_RAW_SHA256, corpusHash)
            val before = Gson().fromJson(inputBookBytes.toString(Charsets.UTF_8), ServiceBook::class.java)
            val nativeHash = ServiceBookIntegrity.contentHash(before)
            report.put("inputStoredContentHash", before.contentHash).put("inputComputedNativeHash", nativeHash)
                .put("inputRehashed", false)
            assertEquals(BOOK_NATIVE_HASH, before.contentHash)
            assertEquals("Android must validate the original hash without repairing it", before.contentHash, nativeHash)
            assertTrue(ServiceBookIntegrity.valid(before))
            assertEquals(ServiceBookKey("2026-11-12", churchType = "10"), before.key)
            assertEquals(269, visibleTranslated(before))
            assertEquals(64, visibleMissing(before))
            val scriptures = before.blocks.filter { it.kind == BookBlockKind.SCRIPTURE }
            assertEquals(2, scriptures.size)
            assertTrue(scriptures.all { it.russian != null })

            val core = BookTranslationIndex.PARALLEL_SOURCES
            val extra = listOf(BookTranslationIndex.VIGIL_SOURCE, BookTranslationIndex.HOUR3_SOURCE, BookTranslationIndex.PANIKHIDA_SOURCE)
            val expectedUrls = (core + extra).map { it.url }
            val corpusJson = inputCorpusBytes.toString(Charsets.UTF_8)
            val corpusRoot = JSONObject(corpusJson)
            assertEquals(8, corpusRoot.getInt("version"))
            assertEquals(core.map { it.url }.toSet(), stringSet(corpusRoot.getJSONArray("completedSources")))
            report.put("inputCorpusVersion", 8).put("expectedRequestedUrls", JSONArray(expectedUrls))
                .put("migrationScope", "Corpus8 completion is stale for corpus9; production must fetch core3 plus common3")
            val cache = FileBookCache(directory)
            assertTrue(cache.write(before))
            assertTrue(cache.writeTranslationCorpus(corpusJson))
            assertTrue("Native readVersion must open the unchanged old edition",
                before == FileBookCache(directory).readVersion(before.key, before.contentHash))
            assertEquals(corpusJson, FileBookCache(directory).readTranslationCorpus())
            val oldEditionFile = File(directory, "${BookText.sha256(before.key.canonical()).take(32)}-${before.contentHash}.json")
            val oldEditionBytes = oldEditionFile.readBytes()

            // Anchor in already translated Russian prose: adding other prayers must not move it.
            val anchor = before.blocks.first { it.kind == BookBlockKind.PRAYER && !it.isAltarText && it.russian != null }
            val chapter = before.chapters.single { anchor in it.blocks }
            val parish = "isolated-shared-russian-type10"
            val position = BookReadingPosition(before.key, before.contentHash, parish, anchor.id, chapter.id,
                fraction = .37f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 93, showAltar = false,
                lastTextMode = BookReadingMode.RUSSIAN)
            val store = BookReadingStore(preferences)
            assertTrue(store.save(position))
            assertEquals(position, store.positionFor(before.key, parish))

            val realTransport = UrlConnectionScheduleTransport(timeoutMillis = 15_000)
            val transport = ScheduleTransport { url ->
                attempts += url
                assertTrue("Enrichment must request only public parallel pages, never API/date/service endpoints", url in expectedUrls)
                val started = SystemClock.elapsedRealtime()
                try {
                    val response = realTransport.fetch(url)
                    responses[url] = response
                    retrieved += JSONObject().put("requestedUrl", url).put("finalUrl", response.url)
                        .put("bytes", response.bytes.size).put("sha256", sha256(response.bytes))
                        .put("contentType", response.contentType).put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                    response
                } catch (error: Throwable) {
                    retrieved += JSONObject().put("requestedUrl", url).put("errorType", error.javaClass.simpleName)
                        .put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                    throw error
                }
            }
            val repository = ServiceBookRepository(cache, transport)
            val result = repository.enrichSaved(before, allowNetwork = true)
            val after = cache.read(before.key)
            report.put("candidatePresent", result != null).put("candidateSaved", result?.saved ?: false)
                .put("candidateHash", result?.book?.contentHash ?: JSONObject.NULL)
                .put("latestHash", after?.contentHash ?: JSONObject.NULL)
                .put("visibleBefore", visibleTranslated(before)).put("visibleAfter", after?.let(::visibleTranslated) ?: JSONObject.NULL)
                .put("missingBefore", visibleMissing(before)).put("missingAfter", after?.let(::visibleMissing) ?: JSONObject.NULL)
            val persistedCorpus = cache.readTranslationCorpus()
            if (persistedCorpus != null) {
                val actualCorpus = JSONObject(persistedCorpus)
                report.put("publishedCorpusVersion", actualCorpus.getInt("version"))
                    .put("completedSources", actualCorpus.getJSONArray("completedSources"))
                    .put("publishedCorpusUtf8Bytes", persistedCorpus.toByteArray(Charsets.UTF_8).size)
            }
            assertNotNull("A partial/missing live result is a failure; see receipt for attempted pages", result)
            val ready = requireNotNull(result)
            val next = ready.book
            assertTrue(ready.saved && ServiceBookIntegrity.valid(next))
            assertTrue("Published candidate must be the persisted latest edition", next == after)
            assertNotEquals(before.contentHash, next.contentHash)
            assertEquals("One attempt per selected source, in the production migration order", expectedUrls, attempts.toList())
            assertEquals(expectedUrls.toSet(), responses.keys.toSet())
            val nextCorpus = JSONObject(requireNotNull(persistedCorpus))
            assertEquals(9, nextCorpus.getInt("version"))
            assertEquals(expectedUrls.toSet(), stringSet(nextCorpus.getJSONArray("completedSources")))

            // Compare all metadata and structure, allowing only missing PRAYER Russian/source fields.
            assertEquals(before.chapters.size, next.chapters.size)
            assertEquals(before.blocks.size, next.blocks.size)
            assertTrue("Book metadata, date, Scripture status and calendar must remain unchanged",
                before == next.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
            before.chapters.zip(next.chapters).forEach { (oldChapter, newChapter) ->
                assertTrue("Chapter ID, order, title and meaning must remain unchanged",
                    oldChapter == newChapter.copy(blocks = oldChapter.blocks))
                assertEquals(oldChapter.blocks.size, newChapter.blocks.size)
            }
            val gained = before.blocks.zip(next.blocks).filter { (old, new) -> old.russian == null && new.russian != null }
            before.blocks.zip(next.blocks).forEach { (old, new) ->
                if (old.russian != null || old.kind != BookBlockKind.PRAYER) {
                    assertTrue("Existing Russian, Scripture and non-prayer blocks must remain exact: ${old.id}", old == new)
                } else {
                    assertTrue("Original, ID, order, speaker, scope, meaning and references must remain exact: ${old.id}",
                        old == new.copy(russian = null, translationSource = old.translationSource))
                    if (new.russian == null) assertTrue("Do not change a remaining gap", old == new)
                }
            }
            assertEquals(7, gained.count { !it.first.isAltarText })
            assertEquals(1, gained.count { it.first.isAltarText })
            assertEquals(276, visibleTranslated(next))
            assertEquals(57, visibleMissing(next))
            assertTrue(scriptures == next.blocks.filter { it.kind == BookBlockKind.SCRIPTURE })
            assertEquals((before.sources + next.blocks.mapNotNull { it.translationSource }).distinct(), next.sources)
            val liveIndexes = extra.associate { source ->
                val response = requireNotNull(responses[source.url])
                assertEquals(source.url, response.url)
                source.url to BookTranslationIndex.fromParallelPage(requireNotNull(response.html()), source)
            }
            val additions = JSONArray()
            gained.forEach { (old, new) ->
                val source = requireNotNull(new.translationSource)
                assertTrue("Only the selected published common sources may fill these gaps", source in extra)
                val published = requireNotNull(liveIndexes.getValue(source.url).find(old.original))
                assertEquals("New Russian must be an exact pair parsed from the real response", published.russian, new.russian)
                additions.put(JSONObject().put("id", new.id).put("altar", new.isAltarText)
                    .put("sourceUrl", source.url).put("originalSha256", BookText.sha256(old.original))
                    .put("russianSha256", BookText.sha256(requireNotNull(new.russian))))
            }
            report.put("additions", additions).put("visibleGain", 7).put("altarGain", 1)
                .put("exactContentPreserved", true).put("newPairsMatchLivePublishedPages", true)
            assertEquals("Downloading must not accept an edition for the reader", position, store.last(parish))
            assertEquals(position, store.positionFor(before.key, parish))
            assertTrue("Explicit store acceptance must succeed", store.acceptVersion(before, next, parish, position))
            val acceptedPosition = position.copy(contentHash = next.contentHash)
            assertEquals(acceptedPosition, store.last(parish))
            assertEquals(acceptedPosition, store.positionFor(next.key, parish))
            report.put("positionBefore", JSONObject(Gson().toJson(position)))
                .put("positionAccepted", JSONObject(Gson().toJson(acceptedPosition)))
                .put("acceptedOnlyExplicitly", true).put("positionRetained", true)

            val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport {
                offlineAttempts.incrementAndGet()
                throw AssertionError("Offline reentry must not request any transport")
            })
            val restoredStore = BookReadingStore(context.getSharedPreferences(isolatedName, Context.MODE_PRIVATE))
            val restored = requireNotNull(restoredStore.positionFor(next.key, parish))
            assertEquals(acceptedPosition, restored)
            assertTrue("Accepted immutable file must reopen through a new repository", next == offline.cachedVersion(restored.key, restored.contentHash))
            val reopened = offline.load(next.key)
            assertTrue(reopened is BookLoadResult.Ready && reopened.fromCache && reopened.saved && reopened.book == next)
            assertEquals(acceptedPosition, restoredStore.restore(next, parish))
            assertEquals(acceptedPosition, restoredStore.last(parish))
            assertNull("The completed same corpus must not invent another offline edition", offline.enrichSaved(next, allowNetwork = false))
            assertEquals(0, offlineAttempts.get())
            assertTrue("Old immutable edition must remain readable", before == offline.cachedVersion(before.key, before.contentHash))
            assertArrayEquals("Old immutable file must not be rewritten", oldEditionBytes, oldEditionFile.readBytes())
            report.put("offlineEqual", true).put("oldEditionExists", true).put("oldEditionRawSha256", sha256(oldEditionBytes))
                .put("publishedContentHash", next.contentHash).put("publishedNativeHash", ServiceBookIntegrity.contentHash(next))
            val publishedBytes = File(directory, "${BookText.sha256(next.key.canonical()).take(32)}-${next.contentHash}.json").readBytes()
            File(output, "enriched-book.json").writeBytes(publishedBytes)
            File(output, "russian-concordance.json").writeText(requireNotNull(persistedCorpus), Charsets.UTF_8)
            report.put("publishedRawSha256", sha256(publishedBytes))
        } catch (error: Throwable) {
            failure = error
        } finally {
            fun verifyFinal(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
            verifyFinal {
                originalBytes?.let { assertArrayEquals("Input book must remain byte-identical", it, bookFile.readBytes()) }
                corpusBytes?.let { assertArrayEquals("Input corpus8 must remain byte-identical", it, corpusFile.readBytes()) }
                report.put("inputsUnchanged", originalBytes != null && corpusBytes != null)
                userPreferences.forEach { (name, before) ->
                    assertTrue("The user's $name must remain unchanged", before == context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
                }
                report.put("userPreferencesUnchanged", true)
            }
            verifyFinal {
                assertTrue("Delete only the isolated preferences", context.deleteSharedPreferences(isolatedName))
                assertTrue("Delete only the isolated book directory", !directory.exists() || directory.deleteRecursively())
                report.put("isolatedStateCleaned", true)
            }
            report.put("requestedUrls", JSONArray(attempts.toList())).put("requestCounts", JSONObject(attempts.groupingBy { it }.eachCount()))
                .put("retrieved", JSONArray(retrieved.toList())).put("offlineRequests", offlineAttempts.get())
                .put("allPassed", failure == null)
            failure?.let { report.put("failureType", it.javaClass.simpleName).put("failure", it.message?.take(2_000) ?: "") }
            File(output, "receipt.json").writeText(report.toString(2), Charsets.UTF_8)
        }
        failure?.let { throw it }
    }

    private fun visibleTranslated(book: ServiceBook) = book.blocks.count {
        it.kind == BookBlockKind.PRAYER && !it.isAltarText && it.russian != null
    }
    private fun visibleMissing(book: ServiceBook) = book.blocks.count {
        it.kind == BookBlockKind.PRAYER && !it.isAltarText && it.russian == null
    }
    private fun stringSet(values: JSONArray) = (0 until values.length()).map { values.getString(it) }.toSet()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BOOK_RAW_SHA256 = "64fdf0145ef3fcf7f994d942d55f6351e57ba2967b601779df834fe1eb21c73d"
        private const val BOOK_NATIVE_HASH = "3e15f4a0c1150803cc1731923ba69d5b01b00ea9766ea30ea2cfeb501eb4bd97"
        private const val CORPUS_RAW_SHA256 = "131374094a6c03ee8fe93a5617c307df9964d0623b0317ccad3233f1fbd7fbb6"
    }
}
