package ru.liturgyguide.service.book

import android.util.Log
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import ru.liturgyguide.service.schedule.UrlConnectionScheduleTransport
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in source protocol proof. Deliberately does not verify translation downloads or reader UI. */
@RunWith(AndroidJUnit4::class)
class LiveMatinsRankSourceTest {
    private val gson = Gson()

    @Test fun realDatedPolyeleosBooksPersistAcrossAnOfflineRepository() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("feoriaLiveMatinsRank") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "live-matins-rank-$id")
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "matins-rank-qa-$id").apply { mkdirs() }
        val rows = mutableListOf<Map<String, Any?>>()
        val receipt = linkedMapOf<String, Any?>("status" to "RUNNING", "scope" to "source protocol only",
            "dates" to listOf("2026-11-13", "2026-11-18"), "translationNetwork" to false, "cases" to rows)
        try {
            val network = UrlConnectionScheduleTransport(timeoutMillis = 15_000, limitBytes = 1_500_000)
            for (date in listOf("2026-11-13", "2026-11-18")) {
                val key = ServiceBookKey(date, WorshipKind.MATINS, "34", topLevel = "3")
                val calls = CopyOnWriteArrayList<String>()
                val sourceUrls = CopyOnWriteArrayList<String>()
                val pages = CopyOnWriteArrayList<ScheduleResponse>()
                val transport = ScheduleTransport { url ->
                    calls += url
                    if (url.startsWith("https://azbyka.ru/worships/?")) {
                        assertTrue("Only the two dated source requests are allowed", sourceUrls.size < 2)
                        sourceUrls += url
                        network.fetch(url).also { response ->
                            pages += response
                            File(output, "$date-source-${pages.size}.html").writeBytes(response.bytes)
                        }
                    } else {
                        assertEquals("Supplement request must stay explicitly bounded", "https://azbyka.ru/biblia/days/$date", url)
                        ScheduleResponse(url, "<html><body></body></html>".toByteArray(), "text/html; charset=UTF-8")
                    }
                }
                val online = ServiceBookRepository(FileBookCache(directory), transport, downloadRussian = false)
                val result = withTimeout(60_000) { online.load(key) }
                assertTrue("$date: $result", result is BookLoadResult.Ready)
                val ready = result as BookLoadResult.Ready
                assertTrue(ready.saved); assertFalse(ready.fromCache)
                assertEquals(2, sourceUrls.size); assertEquals(2, pages.size)
                val main = requireNotNull(pages[0].html()).select("#bg_worships_mainEvent option[selected]").single().attr("value")
                assertTrue(main.matches(Regex("[0-9]{1,6}")))
                val expectedFinal = "https://azbyka.ru/worships/?date=$date&worship=matins&size=full&church=34&main_event=$main&top_level=3"
                assertEquals(listOf(key.copy(topLevel = null).sourceUrl(), expectedFinal), sourceUrls.toList())
                assertEquals(sourceUrls.toList(), pages.map { it.url })
                assertEquals(key, ready.book.key)
                assertEquals(main, ready.book.rankSelection?.mainEvent)
                assertEquals("", ready.book.rankSelection?.secondEvent)
                assertEquals("3", ready.book.rankSelection?.topLevel)
                assertEquals(expectedFinal, ready.book.sourceUrl)
                assertTrue(ServiceBookIntegrity.valid(ready.book))
                val chapters = ready.book.chapters.associateBy { BookText.normalized(it.title) }
                assertTrue(requireNotNull(chapters["полиелей"]).blocks.any { it.kind == BookBlockKind.PRAYER })
                assertTrue(requireNotNull(chapters["славословие великое"]).blocks.any { it.kind == BookBlockKind.PRAYER })
                assertTrue(requireNotNull(chapters["чтение евангелия"]).blocks.any {
                    it.kind == BookBlockKind.SCRIPTURE && it.original.isNotBlank() && it.scriptureReferences.isNotEmpty()
                })
                assertTrue(chapters.containsKey("отпуст"))
                assertEquals(listOf("https://azbyka.ru/biblia/days/$date"), calls.filterNot { it in sourceUrls })
                File(output, "$date-book.json").writeText(gson.toJson(ready.book))
                val offlineCalls = CopyOnWriteArrayList<String>()
                val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
                    offlineCalls += url; error("Offline repository requested network")
                }, downloadRussian = false)
                val restored = offline.load(key) as BookLoadResult.Ready
                assertTrue(restored.fromCache); assertEquals(ready.book, restored.book)
                assertEquals(ready.book, offline.cachedVersion(key, ready.book.contentHash))
                assertTrue(offlineCalls.isEmpty())
                rows += linkedMapOf("date" to date, "canonical" to key.canonical(), "sourceRequests" to sourceUrls.toList(),
                    "sourceResponseSha256" to pages.map { sha(it.bytes) }, "supplementNetworkRequests" to 0,
                    "supplementStubCalls" to 1, "offlineRequests" to offlineCalls.size,
                    "contentHash" to ready.book.contentHash, "mainEvent" to main,
                    "chapters" to ready.book.chapters.size, "blocks" to ready.book.blocks.size, "status" to "PASS")
            }
            receipt["status"] = "PASS"
        } catch (failure: Throwable) {
            receipt["status"] = "FAIL"; receipt["error"] = failure.toString()
            throw failure
        } finally {
            File(output, "receipt.json").writeText(gson.toJson(receipt))
            Log.i("FeoriaRankQA", "Source receipt: ${File(output, "receipt.json").absolutePath}")
            directory.deleteRecursively()
        }
    }

    /** Reads only the explicit frozen fixture folder. Never opens or edits the user's live preferences. */
    @Test fun prechangeNativeBooksAndAcceptedBookmarksRemainReadable() {
        val fixtureName = InstrumentationRegistry.getArguments().getString("feoriaRankLegacyFixture")
        assumeTrue(!fixtureName.isNullOrBlank())
        require(requireNotNull(fixtureName).matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        val fixture = File(external, fixtureName).canonicalFile
        require(fixture.parentFile == external && fixture.isDirectory)
        val directory = File(context.cacheDir, "rank-native-legacy-${UUID.randomUUID()}").apply { mkdirs() }
        val output = File(external, "rank-native-legacy-qa-${UUID.randomUUID()}").apply { mkdirs() }
        val receipt = linkedMapOf<String, Any?>("status" to "RUNNING", "sourceFixtureModified" to false)
        val sourceHashes = linkedMapOf<File, String>()
        try {
            val manifestFile = File(fixture, "manifest.json")
            sourceHashes[manifestFile] = sha(manifestFile.readBytes())
            val manifest = JsonParser.parseString(manifestFile.readText()).asJsonObject
            assertEquals("8f6765a", manifest["baselineCommit"].asString)
            val entries = manifest.getAsJsonArray("acceptedBooks")
            assertEquals("Frozen before model extension, not a new roundtrip", 5, entries.size())
            val books = linkedMapOf<String, ServiceBook>()
            for (entry in entries) {
                val item = entry.asJsonObject
                val filename = item["file"].asString
                require(filename.matches(Regex("[a-f0-9]{32}-[a-f0-9]{64}\\.json")))
                val input = File(fixture, filename)
                val bytes = input.readBytes()
                sourceHashes[input] = sha(bytes)
                assertEquals(item["bytes"].asLong, bytes.size.toLong())
                assertEquals(item["sha256"].asString, sha(bytes))
                val book = gson.fromJson(bytes.toString(Charsets.UTF_8), ServiceBook::class.java)
                assertNull(book.key.topLevel); assertNull(book.rankSelection)
                assertEquals(item["contentHash"].asString, book.contentHash)
                assertEquals(book.key, gson.fromJson(item["key"], ServiceBookKey::class.java))
                assertTrue(ServiceBookIntegrity.valid(book))
                assertEquals(book.contentHash, ServiceBookIntegrity.contentHash(book))
                val oldCanonical = listOf(book.key.date, book.key.worship.name, book.key.churchType,
                    book.key.mainEvent ?: "~", book.key.secondEvent ?: "~").joinToString("|")
                assertEquals(oldCanonical, book.key.canonical())
                val oldStem = BookText.sha256(oldCanonical).take(32)
                assertEquals("$oldStem-${book.contentHash}.json", filename)
                File(directory, filename).writeBytes(bytes)
                File(directory, "$oldStem.latest").writeText(book.contentHash)
                val cache = FileBookCache(directory)
                assertEquals(book, cache.read(book.key)); assertEquals(book, cache.readVersion(book.key, book.contentHash))
                books[book.contentHash] = book
            }
            val reading = File(fixture, "reading.xml")
            val readingBytes = reading.readBytes()
            sourceHashes[reading] = sha(readingBytes)
            assertEquals(manifest["readingSha256"].asString, sha(readingBytes))
            var positions = 0
            var lastPositions = 0
            reading.inputStream().use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, "UTF-8")
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string") {
                        val name = parser.getAttributeValue(null, "name")
                        val raw = parser.nextText()
                        if (name.startsWith("position:") || name.startsWith("last:")) {
                            val position = requireNotNull(BookReadingCodec.decode(raw))
                            assertNull(position.key.topLevel)
                            val book = requireNotNull(books[position.contentHash])
                            assertEquals(book.key, position.key)
                            val chapter = book.chapters.single { it.id == position.chapterId }
                            assertTrue(chapter.blocks.any { it.id == position.blockId })
                            val expectedName = if (name.startsWith("position:")) {
                                positions++
                                "position:" + BookText.sha256("${position.parishId.orEmpty()}|${book.key.canonical()}")
                            } else {
                                lastPositions++
                                "last:" + BookText.sha256(position.parishId.orEmpty())
                            }
                            assertEquals(expectedName, name)
                            assertEquals(position, BookReadingCodec.decode(BookReadingCodec.encode(position)))
                        }
                    }
                    parser.next()
                }
            }
            assertEquals(6, positions); assertEquals(3, lastPositions)
            receipt["books"] = books.size; receipt["positions"] = positions; receipt["lastPositions"] = lastPositions
            receipt["legacyReadingSha256"] = sha(readingBytes)
            receipt["status"] = "PASS"
        } catch (failure: Throwable) {
            receipt["status"] = "FAIL"; receipt["error"] = failure.toString()
            throw failure
        } finally {
            // No baseline write/delete is allowed, including on a failed assertion.
            val unchanged = sourceHashes.all { (file, hash) -> file.isFile && sha(file.readBytes()) == hash }
            receipt["sourceFixtureModified"] = !unchanged
            File(output, "receipt.json").writeText(gson.toJson(receipt))
            Log.i("FeoriaRankQA", "Legacy receipt: ${File(output, "receipt.json").absolutePath}")
            directory.deleteRecursively()
            assertTrue("Frozen public books and private reading snapshot must remain unchanged", unchanged)
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
