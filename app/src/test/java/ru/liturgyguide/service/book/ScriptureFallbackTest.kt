package ru.liturgyguide.service.book

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class ScriptureFallbackTest {
    private val directory = Files.createTempDirectory("scripture-fallback-").toFile()
    private val disk = FileBookCache(directory)
    private val calls = CopyOnWriteArrayList<String>()
    private val writes = CopyOnWriteArrayList<ServiceBook>()
    private val cache = object : BookCache by disk {
        override fun write(book: ServiceBook): Boolean { writes += book; return disk.write(book) }
    }
    private val ref13 = "Lk.10:1-21"
    private val direct13 = "https://azbyka.ru/biblia/?Lk.10:1-21&c~r"
    @After fun clean() { directory.deleteRecursively() }

    @Test fun realPagesSupplyOnlyTheExactRussianRangeAndItsVerifiedSource() {
        val daily = BookScripture.fromPage(page("daily-2026-11-13.html"))
        assertNull(daily.russian(ref13))
        val result = daily.withDirectPage(ref13, page("direct-2026-11-13.html"), direct13)
        val translated = requireNotNull(result.russian(ref13))
        assertEquals(expected("2026-11-13"), translated.russian)
        assertEquals(21, translated.russian.split("\n\n").size)
        assertEquals(BookSource("Библия, Синодальный перевод", direct13), translated.source)
        val outside = verse(page("direct-2026-11-13.html"), "Lk.10:22").text()
        assertFalse(translated.russian.contains(outside))
        assertNull("A whole direct passage must not become a bag of reusable spare verses", result.russian("Lk.10:22"))
        assertEquals(expected("2026-11-18"), BookScripture.fromPage(page("daily-2026-11-18.html")).russian("Lk.21:12-19")?.russian)
        assertEquals("https://azbyka.ru/biblia/?Mt.11:27-30&c~r", BookScripture.directUrl("Mt.11:27-30"))
        for (bad in listOf("Lk.10:1-11:2", "Lk.10:1;2", "Lk.10:3-1", "Lk.10:0", "Lk.10:1&x=1", "Lk.10:1-999"))
            assertNull(bad, BookScripture.directUrl(bad))
    }

    @Test fun incompleteAmbiguousWrongLanguageOrUnownedDirectRowsCannotCreateATranslation() {
        val mutations: List<Pair<String, (Document) -> Unit>> = listOf(
            "missing verse" to { d -> verse(d, "Lk.10:7").remove() },
            "empty verse" to { d -> verse(d, "Lk.10:7").empty() },
            "wrong language c" to { d -> verse(d, "Lk.10:7").attr("data-lang", "c") },
            "wrong language ru" to { d -> verse(d, "Lk.10:7").attr("data-lang", "ru") },
            "wrong chapter" to { d -> verse(d, "Lk.10:7").attr("data-verse", "Lk.11:7") },
            "identical duplicate" to { d -> val v = verse(d, "Lk.10:7"); v.after(v.clone()) },
            "conflicting duplicate" to { d -> val v = verse(d, "Lk.10:7"); v.after(v.clone().text("Конфликтующий текст QA")) },
            "nested required row" to { d -> verse(d, "Lk.10:7").wrap("<div></div>") },
            "next verse nested in requested verse" to { d ->
                val extra = verse(d, "Lk.10:22"); extra.remove(); verse(d, "Lk.10:21").appendChild(extra)
            },
            "nested verse attribute" to { d -> verse(d, "Lk.10:21").append("<span data-verse='Lk.10:22'>Чужой стих QA</span>") },
            "nested foreign language verse" to { d ->
                verse(d, "Lk.10:21").append("<span class='verse' data-lang='c' data-verse='Lk.10:21'>Чужая колонка QA</span>")
            },
            "nested foreign language annotation" to { d -> verse(d, "Lk.10:21").append("<span data-lang='ru'>Чужой язык QA</span>") },

            "foreign table" to { d -> verse(d, "Lk.10:7").let { it.remove(); d.body().appendChild(it) } },
            "missing header" to { d -> d.select("#column_header_r").remove() },
            "duplicate header" to { d -> val h = requireNotNull(d.selectFirst("#column_header_r")); h.after(h.clone()) },
            "wrong header language" to { d -> d.select("#column_header_r .lang-label").attr("data-lang-code", "ru") },
            "wrong edition" to { d -> d.select("#column_header_r .lang-label__title").single().text("Современный перевод") },
            "ambiguous edition" to { d -> val l = requireNotNull(d.selectFirst("#column_header_r .lang-label__title")); l.after(l.clone()) },
            "wrong book table" to { d -> requireNotNull(d.selectFirst("#column_header_r")?.parent()).attr("data-book-slug", "Mt") },
            "oversized translation" to { d -> verse(d, "Lk.10:7").text("а".repeat(80_001)) }
        )
        for ((label, mutate) in mutations) {
            val document = page("direct-2026-11-13.html"); mutate(document)
            assertNull(label, BookScripture.empty().withDirectPage(ref13, document, direct13).russian(ref13))
        }
        for (url in listOf(direct13.replace("https:", "http:"), direct13.replace("azbyka.ru", "example.org"),
            direct13.replace("10:1-21", "11:1-21"), direct13.replace("&c~r", "&r"), "$direct13#chapter", "$direct13&x=1"))
            assertNull(url, BookScripture.empty().withDirectPage(ref13, page("direct-2026-11-13.html"), url).russian(ref13))
        val partial = page("direct-2026-11-13.html")
        val dailyHalf = Jsoup.parse((1..10).joinToString("") { verse(partial, "Lk.10:$it").outerHtml() })
        (1..10).forEach { verse(partial, "Lk.10:$it").remove() }
        assertNull("Partial responses cannot be spliced into apparent complete provenance",
            BookScripture.fromPage(dailyHalf).withDirectPage(ref13, partial, direct13).russian(ref13))
    }

    @Test fun sharedSessionDeduplicatesSuccessesAndFailuresCapsAllPartsAtSixAndSharesRemainingBudget() = runBlocking {
        val session = BookScriptureFallback.Session()
        val transport = ScheduleTransport { url ->
            calls += url
            if (url.contains("Lk.10:2&")) response(url, "<html></html>") else response(url, text("direct-2026-11-13.html"))
        }
        val first = BookScriptureFallback.load(BookScripture.empty(), listOf("Lk.10:1", "Lk.10:1", "Lk.10:2"), transport, session = session)
        val remainingAfterFirst = session.remainingNanos
        assertNotNull(first.russian("Lk.10:1")); assertNull(first.russian("Lk.10:2"))
        val second = BookScriptureFallback.load(BookScripture.empty(), (1..8).map { "Lk.10:$it" }, transport, session = session)
        assertEquals((1..6).map { "https://azbyka.ru/biblia/?Lk.10:$it&c~r" }, calls.toList())
        assertEquals(first.russian("Lk.10:1"), second.russian("Lk.10:1"))
        assertNull(second.russian("Lk.10:2")); assertNull(second.russian("Lk.10:7"))
        assertTrue(session.remainingNanos < remainingAfterFirst)
        val completedDaily = BookScripture.fromPage(page("direct-2026-11-13.html"))
        val priorCount = calls.size
        val dailyWins = BookScriptureFallback.load(completedDaily, listOf("Lk.10:1"), transport, session = session)
        assertEquals(completedDaily.russian("Lk.10:1"), dailyWins.russian("Lk.10:1"))
        assertEquals(priorCount, calls.size)

        // Model a later vigil part with only 80ms of the shared 20-second budget left.
        val almostSpent = BookScriptureFallback.Session().apply { remainingNanos = 80_000_000 }
        val cancelled = AtomicBoolean(false)
        val pendingCalls = CopyOnWriteArrayList<String>()
        val hanging = ScheduleTransport { url -> pendingCalls += url; try { awaitCancellation() } finally { cancelled.set(true) } }
        val stopped = withTimeout(5_000) { BookScriptureFallback.load(BookScripture.empty(), listOf(ref13), hanging, session = almostSpent) }
        assertNull(stopped.russian(ref13)); assertTrue(cancelled.get())
        assertTrue(almostSpent.remainingNanos < 1_000_000)
        BookScriptureFallback.load(BookScripture.empty(), listOf("Lk.10:22"), hanging, session = almostSpent)
        assertEquals(listOf(direct13), pendingCalls.toList())
    }

    @Test fun malformedAndBoundedResponsesFailClosedAndExternalCancellationPropagates() = runBlocking {
        for (response in listOf(ScheduleResponse(direct13, byteArrayOf(1, 2), "application/pdf"),
            response("https://example.org/foreign", text("direct-2026-11-13.html")),
            ScheduleResponse(direct13, ByteArray(2_097_153), "text/html"))) {
            assertNull(BookScriptureFallback.load(BookScripture.empty(), listOf(ref13), ScheduleTransport { response }).russian(ref13))
        }
        var bounded = 0
        val boundedTransport = object : ScheduleTransport {
            override suspend fun fetch(url: String): ScheduleResponse = error("Direct requires bounded transport")
            override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse {
                assertEquals(direct13, url); assertEquals(2_097_152, maximumBytes); bounded++
                throw IOException("Rejected expanded response exceeds the byte limit")
            }
        }
        assertNull(BookScriptureFallback.load(BookScripture.empty(), listOf(ref13), boundedTransport).russian(ref13))
        assertEquals(1, bounded)
        val entered = CompletableDeferred<Unit>(); val cancelled = AtomicBoolean(false)
        val job = launch {
            BookScriptureFallback.load(BookScripture.empty(), listOf(ref13, "Lk.10:22"), ScheduleTransport { url ->
                calls += url; entered.complete(Unit); try { awaitCancellation() } finally { cancelled.set(true) }
            })
        }
        try {
            withTimeout(5_000) { entered.await() }; job.cancelAndJoin()
            assertTrue(job.isCancelled); assertTrue(cancelled.get()); assertEquals(listOf(direct13), calls.toList())
        } finally { job.cancelAndJoin() }
    }

    @Test fun twoActualDatesHaveExactRequestCountsAndWholeOfflineScriptureInANewRepository() = runBlocking {
        for (date in MatinsRankFixtures.dates) {
            calls.clear(); writes.clear()
            val key = MatinsRankFixtures.key(date)
            val original = MatinsRankFixtures.parse(date)
            val ready = repository(date).load(key) as BookLoadResult.Ready
            assertTrue(ready.saved); assertFalse(ready.fromCache)
            assertEquals(listOf(key.copy(topLevel = null).sourceUrl(), MatinsRankFixtures.url(key)), sourceCalls())
            assertEquals(listOf("https://azbyka.ru/biblia/days/$date"), dailyCalls())
            assertEquals(if (date.endsWith("13")) listOf(direct13) else emptyList<String>(), directCalls())
            assertEquals(listOf(ready.book), writes)
            assertEquals(original.blocks.map { it.original }, ready.book.blocks.map { it.original })
            val gospel = ready.book.blocks.single { it.kind == BookBlockKind.SCRIPTURE }
            assertEquals(expected(date), gospel.russian)
            val expectedUrl = if (date.endsWith("13")) direct13 else "https://azbyka.ru/biblia/?Lk.21:12-19&r"
            assertEquals(BookSource("Библия, Синодальный перевод", expectedUrl), gospel.translationSource)
            assertTrue(gospel.translationSource in ready.book.sources)
            assertEquals(BookSupplementStatus.AVAILABLE, ready.book.enrichment.scripture)
            assertTrue(ServiceBookIntegrity.valid(ready.book))
            calls.clear()
            val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url -> calls += url; error("Offline") }, downloadRussian = false)
            val restored = offline.load(key) as BookLoadResult.Ready
            assertTrue(restored.fromCache); assertEquals(ready.book, restored.book); assertTrue(calls.isEmpty())
        }
    }

    @Test fun repositoryDeduplicatesOnePassageAcrossBlocksButDoesNotGuessAmongMultipleReferences() = runBlocking {
        val key = MatinsRankFixtures.key()
        val repeated = MatinsRankFixtures.document(ranked = true)
        val element = requireNotNull(MatinsRankFixtures.body(repeated).selectFirst(".bg_bibrefs_service"))
        element.after(element.clone())
        val repeatedBook = (repository(key.date, source = repeated).load(key) as BookLoadResult.Ready).book
        val readings = repeatedBook.blocks.filter { it.kind == BookBlockKind.SCRIPTURE }
        assertEquals(2, readings.size); assertTrue(readings.all { it.russian == expected(key.date) && it.translationSource?.url == direct13 })
        assertEquals(listOf(direct13), directCalls())
        calls.clear()
        val ambiguous = MatinsRankFixtures.document(ranked = true)
        val label = requireNotNull(MatinsRankFixtures.body(ambiguous).selectFirst(".bg_bibrefs_service p"))
        label.append("<a href='https://azbyka.ru/biblia/?Mt.11:27-30&c~r'>Another reference, synthetic QA mutation</a>")
        val book = (repository(key.date, source = ambiguous).load(key, force = true) as BookLoadResult.Ready).book
        assertEquals(2, book.blocks.single { it.kind == BookBlockKind.SCRIPTURE }.scriptureReferences.size)
        assertNull(book.blocks.single { it.kind == BookBlockKind.SCRIPTURE }.russian)
        assertTrue(directCalls().isEmpty())
        assertEquals(BookSupplementStatus.RETRY_NEEDED, book.enrichment.scripture)
    }

    @Test fun failedRefreshRetainsExactOldScriptureAndFinalStatusButNeverCarriesToChangedOriginal() = runBlocking {
        val key = MatinsRankFixtures.key()
        val original = (repository(key.date).load(key) as BookLoadResult.Ready).book
        val oldGospel = original.blocks.single { it.kind == BookBlockKind.SCRIPTURE }
        calls.clear()
        val retained = (repository(key.date, failBible = true).load(key, force = true) as BookLoadResult.Ready).book
        val gospel = retained.blocks.single { it.kind == BookBlockKind.SCRIPTURE }
        assertEquals(oldGospel.russian, gospel.russian); assertEquals(oldGospel.translationSource, gospel.translationSource)
        assertEquals(BookSupplementStatus.AVAILABLE, retained.enrichment.scripture)
        assertNull(BookWarnings.supplementWarning(retained))
        val storedPrevious = requireNotNull(disk.readVersion(key, original.contentHash))
        // A same-content refresh rewrites only the retrieval time under the same immutable content key.
        assertEquals(original.contentHash, storedPrevious.contentHash)
        assertEquals(original.sourceUrl, storedPrevious.sourceUrl)
        assertEquals(original.enrichment, storedPrevious.enrichment)
        assertEquals(original, storedPrevious.copy(fetchedAtMillis = original.fetchedAtMillis))
        val changed = MatinsRankFixtures.document(ranked = true)
        val paragraphs = requireNotNull(MatinsRankFixtures.body(changed).selectFirst(".bg_bibrefs_service")).select("p")
        requireNotNull(paragraphs.last()).appendText(" Измененный оригинал: тестовая мутация QA")
        val missing = (repository(key.date, source = changed, failBible = true).load(key, force = true) as BookLoadResult.Ready).book
        assertNull(missing.blocks.single { it.kind == BookBlockKind.SCRIPTURE }.russian)
        assertEquals(BookSupplementStatus.RETRY_NEEDED, missing.enrichment.scripture)
        val warning = requireNotNull(BookWarnings.supplementWarning(missing))
        assertTrue(warning.contains("русские чтения Писания")); assertFalse(warning.contains("при появлении связи"))
    }

    @Test fun cancellingTheDirectPhaseDoesNotPublishOrMoveAcceptanceAndBackgroundEnrichmentDoesNotRequestBible() = runBlocking {
        val key = MatinsRankFixtures.key()
        val old = MatinsRankFixtures.parse()
        assertTrue(disk.write(old))
        val store = BookReadingStore(AcceptanceMemoryPreferences())
        val position = BookReadingPosition(key, old.contentHash, "scripture-test", old.blocks.first().id, old.chapters.first().id)
        assertTrue(store.save(position))
        val entered = CompletableDeferred<Unit>(); val cancelled = AtomicBoolean(false)
        val transport = fixtureTransport(key.date, pending = {
            entered.complete(Unit); try { awaitCancellation() } finally { cancelled.set(true) }
        })
        val job = launch { ServiceBookRepository(cache, transport, downloadRussian = false).load(key, force = true) }
        try {
            withTimeout(5_000) { entered.await() }; job.cancelAndJoin()
            assertTrue(job.isCancelled); assertTrue(cancelled.get()); assertTrue(writes.isEmpty())
            assertEquals(old, disk.read(key)); assertEquals(position, store.last("scripture-test"))
            assertEquals(2, sourceCalls().size); assertEquals(listOf(direct13), directCalls())
        } finally { job.cancelAndJoin() }
        calls.clear()
        ServiceBookRepository(cache, ScheduleTransport { url -> calls += url; response(url, "<html></html>") }).enrichSaved(old)
        assertTrue(calls.none { "/biblia/" in it || "/worships/" in it })
        assertEquals(position, store.last("scripture-test"))
    }

    private fun repository(date: String, source: Document? = null, failBible: Boolean = false) =
        ServiceBookRepository(cache, fixtureTransport(date, source, failBible), downloadRussian = false)
    private fun fixtureTransport(date: String, source: Document? = null, failBible: Boolean = false,
        pending: (suspend () -> ScheduleResponse)? = null) = ScheduleTransport { url ->
        calls += url
        val key = MatinsRankFixtures.key(date)
        val direct = if (date.endsWith("13")) direct13 else "https://azbyka.ru/biblia/?Lk.21:12-19&c~r"
        when (url) {
            key.copy(topLevel = null).sourceUrl() -> response(url, MatinsRankFixtures.text("default-$date.html"))
            MatinsRankFixtures.url(key) -> response(url, source?.outerHtml() ?: MatinsRankFixtures.text("rank3-$date.html"))
            "https://azbyka.ru/biblia/days/$date" -> if (failBible) throw IOException("Daily unavailable") else response(url, text("daily-$date.html"))
            direct ->
                if (failBible) throw IOException("Direct unavailable") else pending?.invoke() ?: response(url, text("direct-$date.html"))
            else -> error("Unexpected request: $url")
        }
    }
    private fun sourceCalls() = calls.filter { it.startsWith("https://azbyka.ru/worships/?") }
    private fun dailyCalls() = calls.filter { "/biblia/days/" in it }
    private fun directCalls() = calls.filter { it.startsWith("https://azbyka.ru/biblia/?") }
    private fun text(name: String) = requireNotNull(javaClass.classLoader?.getResource("scripture-fallback/$name")).readText()
    private fun page(name: String) = Jsoup.parse(text(name))
    private fun expected(date: String) = JsonParser.parseString(text("expected-$date.json")).asJsonObject.getAsJsonArray("verses")
        .joinToString("\n\n") { it.asJsonObject["text"].asString }
    private fun verse(document: Document, id: String) = document.select(".verse[data-lang=r][data-verse='$id']").single()
    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(), "text/html; charset=UTF-8")
}
