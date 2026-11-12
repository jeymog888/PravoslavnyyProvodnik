package ru.liturgyguide.service.book

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class BookSharedRussianCorpusTest {
    private val directory = Files.createTempDirectory("shared-russian-").toFile()
    private val disk = FileBookCache(directory)
    private val core = BookTranslationIndex.PARALLEL_SOURCES
    private val coreUrls = core.map { it.url }.toSet()
    private val vigil = BookTranslationIndex.VIGIL_SOURCE
    private val hour = BookTranslationIndex.HOUR3_SOURCE
    private val panikhida = BookTranslationIndex.PANIKHIDA_SOURCE
    private val octoechos = BookTranslationIndex.OCTOECHOS7_SOURCE
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun primaryQueryAndFragmentResponseUrlsReachVisibleTextAndTheNextDayFromCache() = runBlocking {
        val original = BookFixtures.book()
        val next = parsed("2026-11-15", "30")
        val target = original.blocks.first { block -> block.kind == BookBlockKind.PRAYER && !block.isAltarText &&
            block.original.length > 100 && next.blocks.any { it.original == block.original && it.kind == block.kind } }
        val full = BookTranslationIndex.TIMROT_FULL_SOURCE
        for (suffix in listOf("?edition=verified", "#prayer", "?edition=verified#prayer")) {
            val saved = FileBookCache(directory.resolve("alias-${suffix.hashCode()}"))
            val actual = full.url + suffix
            val expected = "Проверенный перевод основного издания"
            val requests = mutableListOf<String>()
            val transport = ScheduleTransport { url ->
                requests += url
                when {
                    url == original.key.sourceUrl() -> response(url, BookFixtures.html())
                    url == next.key.sourceUrl() -> response(url, BookFixtures.html("liturgy-2026-11-15"))
                    url == full.url -> response(actual, table(target.original, expected))
                    url in coreUrls -> response(url, table("Иная молитва $url", "Иное основное издание"))
                    "/biblia/days/" in url || url.startsWith("https://azbyka.ru/days/") -> response(url, "<html></html>")
                    else -> error("Unexpected initial HTTP $url")
                }
            }
            val first = ServiceBookRepository(saved, transport).load(original.key) as BookLoadResult.Ready
            val displayed = first.book.blocks.single { it.id == target.id }
            assertEquals(expected, displayed.russian)
            assertEquals(full.copy(url = actual), displayed.translationSource)
            assertEquals(coreUrls, requests.filter { it in coreUrls }.toSet())
            assertEquals(3, requests.count { it in coreUrls })
            val corpus = BookTranslationCorpus.decode(saved.readTranslationCorpus())
            assertTrue(corpus.completeFor(WorshipKind.LITURGY))
            assertEquals(actual, corpus.index.find(target.original)?.source?.url)

            requests.clear()
            val second = ServiceBookRepository(FileBookCache(directory.resolve("alias-${suffix.hashCode()}")), transport)
                .load(next.key) as BookLoadResult.Ready
            assertTrue(requests.none { it in coreUrls })
            second.book.blocks.filter { it.original == target.original && it.kind == target.kind }.forEach {
                assertEquals(expected, it.russian)
                assertEquals(actual, it.translationSource?.url)
            }
            assertEquals(second.book, saved.read(next.key))
        }
    }

    @Test fun primarySourceMembershipRequiresExactHttpsOriginAndRawPath() {
        val source = BookTranslationIndex.TIMROT_FULL_SOURCE
        val original = "Точная молитва из основного издания"
        val valid = listOf(source.url, source.url + "?edition=verified", source.url + "#prayer",
            source.url.replace("azbyka.ru", "AZBYKA.RU:443") + "?edition=verified#prayer")
        valid.forEach { url ->
            assertEquals(url, pair(original, "Точный русский текст", source.copy(url = url))
                .fromSources(core).find(original)?.source?.url)
        }
        val invalid = listOf(source.url.replace("https:", "http:"), source.url.replace("azbyka.ru", "other.example"),
            source.url.replace("azbyka.ru", "sub.azbyka.ru"), source.url.replace("azbyka.ru", "azbyka.ru:444"),
            source.url.replace("azbyka.ru", "reader@azbyka.ru"), source.url.replace("liturgiya-", "%6citurgiya-"),
            source.url.replace("/bogosluzhenie/", "/bogosluzhenie/../bogosluzhenie/"),
            source.url + "other/", source.url.removeSuffix("/"), vigil.url + "?edition=verified")
        invalid.forEach { url ->
            assertNull("Different source must not become primary: $url",
                pair(original, "Не принимать как основное издание", source.copy(url = url)).fromSources(core).find(original))
        }
    }

    @Test fun oldCorpusPrimaryAliasSurvivesFailedRefreshAndStillReachesTheBook() = runBlocking {
        val original = BookFixtures.book()
        val target = original.blocks.first { it.kind == BookBlockKind.PRAYER && !it.isAltarText && it.original.length > 100 }
        val actual = BookTranslationIndex.TIMROT_FULL_SOURCE.copy(url = BookTranslationIndex.TIMROT_FULL_SOURCE.url + "?edition=verified")
        assertTrue(disk.writeTranslationCorpus(BookTranslationCorpus(pair(target.original, "Сохраненный точный перевод", actual),
            coreUrls, version = 8).encode()))
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
            requests += url
            when {
                url == original.key.sourceUrl() -> response(url, BookFixtures.html())
                url in coreUrls -> error("Primary page unavailable during migration")
                "/biblia/days/" in url || url.startsWith("https://azbyka.ru/days/") -> response(url, "<html></html>")
                else -> error("Unexpected HTTP $url")
            }
        })
        val result = repository.load(original.key) as BookLoadResult.Ready
        val displayed = result.book.blocks.single { it.id == target.id }
        assertEquals("Сохраненный точный перевод", displayed.russian)
        assertEquals(actual, displayed.translationSource)
        assertEquals(coreUrls, requests.filter { it in coreUrls }.toSet())
        assertEquals(3, requests.count { it in coreUrls })
        assertEquals(BookSupplementStatus.RETRY_NEEDED, result.book.enrichment.russian)
        assertEquals(result.book, disk.read(original.key))
    }

    @Test fun aFreshShortEditionCannotReplaceTheCachedFullEditionAliasWhenFullRefreshFails() = runBlocking {
        val original = BookFixtures.book()
        val target = original.blocks.first { it.kind == BookBlockKind.PRAYER && !it.isAltarText && it.original.length > 100 }
        val full = BookTranslationIndex.TIMROT_FULL_SOURCE.copy(url = BookTranslationIndex.TIMROT_FULL_SOURCE.url + "?edition=verified#prayer")
        assertTrue(disk.writeTranslationCorpus(BookTranslationCorpus(pair(target.original, "Полный сохраненный перевод", full),
            coreUrls).encode()))
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
            requests += url
            when {
                url == original.key.sourceUrl() -> response(url, BookFixtures.html())
                url == BookTranslationIndex.TIMROT_SOURCE.url -> response(url, table(target.original, "Сокращенный свежий перевод"))
                url == BookTranslationIndex.TIMROT_FULL_SOURCE.url -> error("Full edition unavailable during refresh")
                else -> response(url, "<html></html>")
            }
        })
        val result = repository.load(original.key, force = true) as BookLoadResult.Ready
        assertEquals("Полный сохраненный перевод", result.book.blocks.single { it.id == target.id }.russian)
        assertEquals(full, result.book.blocks.single { it.id == target.id }.translationSource)
        assertEquals("Полный сохраненный перевод", BookTranslationCorpus.decode(disk.readTranslationCorpus()).index.find(target.original)?.russian)
        assertEquals(3, requests.count { it in coreUrls })
        assertEquals(BookSupplementStatus.RETRY_NEEDED, result.book.enrichment.russian)
    }

    @Test fun initialLiturgyRequestsRemainThreeAndCachedExtrasCannotReplaceHtmlApiOrScripture() = runBlocking {
        val original = BookFixtures.book()
        val targets = original.blocks.filter { it.kind == BookBlockKind.PRAYER && it.original.length > 100 }.take(3)
        val scripture = original.blocks.first { it.kind == BookBlockKind.SCRIPTURE }
        var cached = pair(scripture.original, "Не подставлять к Писанию", vigil)
        targets.forEach { cached = cached.fillingMissing(pair(it.original, "Дополнительный русский", vigil)) }
        assertTrue(disk.writeTranslationCorpus(BookTranslationCorpus(cached, coreUrls).encode()))
        val requests = mutableListOf<String>()
        val apiSource = BookSource("Календарный API", "https://azbyka.ru/days/${original.key.date}")
        val api = CalendarSupplement(day(original.key.date, 6), pair(targets[1].original, "Точный API", apiSource))
        val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
            requests += url
            response(url, when {
                url == original.key.sourceUrl() -> BookFixtures.html()
                url in coreUrls -> table("Другой оригинал", "Тестовое основное издание")
                "/biblia/days/" in url -> "<html></html>"
                url == apiSource.url -> CalendarFixtures.page(original.key.date, targets[0].original, "Точный HTML")
                else -> error("Unexpected initial HTTP $url")
            })
        }, calendar = CalendarSupplements { api })
        val first = repository.load(original.key, force = true) as BookLoadResult.Ready
        assertEquals(coreUrls, requests.filter { it in coreUrls }.toSet())
        assertEquals(3, requests.count { it in coreUrls })
        assertEquals("Точный HTML", first.book.blocks.single { it.id == targets[0].id }.russian)
        assertEquals("Точный API", first.book.blocks.single { it.id == targets[1].id }.russian)
        assertEquals("Дополнительный русский", first.book.blocks.single { it.id == targets[2].id }.russian)
        assertNull(first.book.blocks.single { it.id == scripture.id }.russian)
        assertEquals(original.blocks.map { it.original }, first.book.blocks.map { it.original })
        requests.clear()
        val second = repository.load(original.key, force = true) as BookLoadResult.Ready
        assertEquals(first.book.chapters, second.book.chapters)
        assertTrue(requests.none { it in listOf(vigil.url, hour.url, panikhida.url, octoechos.url) })
    }

    @Test fun toneSelectionUsesTheActualOctoechosHeadingAndRejectsCalendarConflictsAndOtherTones() {
        val sunday = parsed("2026-11-15", "30")
        val common = listOf(vigil, hour, panikhida)
        assertEquals(common + octoechos, BookSharedRussianSources.forBook(sunday))
        assertEquals(common + octoechos, BookSharedRussianSources.forBook(sunday.copy(calendarDay = day(sunday.key.date, 7))))
        assertEquals(common + octoechos, BookSharedRussianSources.forBook(sunday.copy(calendarDay = day(sunday.key.date, null))))
        assertEquals(common, BookSharedRussianSources.forBook(sunday.copy(calendarDay = day(sunday.key.date, 6))))
        assertEquals(common, BookSharedRussianSources.forBook(sunday.copy(calendarDay = day("2026-11-12", 7))))
        assertEquals(common, BookSharedRussianSources.forBook(parsed("2026-11-12", "10").copy(calendarDay = day("2026-11-12", 7))))
        fun heading(text: String) = sunday.copy(chapters = sunday.chapters.map { c -> c.copy(blocks = c.blocks.map {
            if (it.original.startsWith("Октоих.")) it.copy(original = text) else it
        }) })
        assertEquals(common, BookSharedRussianSources.forBook(heading("Октоих. Глас 6. Неделя. На 8.")))
        assertEquals(common, BookSharedRussianSources.forBook(heading("Октоих. Глас 7. Неделя. На 8. Иная приписка")))
        val conflict = sunday.copy(chapters = sunday.chapters.map { c ->
            if (c.blocks.any { it.original.startsWith("Октоих.") }) c.copy(blocks = c.blocks +
                c.blocks.first().copy(kind = BookBlockKind.RUBRIC, original = "Октоих. Глас 6. Неделя. На 8.")) else c
        })
        assertEquals(common, BookSharedRussianSources.forBook(conflict))
        assertTrue(BookSharedRussianSources.forBook(sunday.copy(key = sunday.key.copy(worship = WorshipKind.VESPERS))).isEmpty())
    }

    @Test fun publishedOriginalFixturesFillOnlyTheMeasuredGapsOnFourRealOrders() {
        val provenance = JsonParser.parseString(BookFixturesText("shared-russian-provenance.json")).asJsonObject
        val sources = provenance.getAsJsonArray("sources").map { it.asJsonObject }
        var index = BookTranslationIndex.empty()
        sources.forEach { x ->
            val source = BookTranslationIndex.ALL_PARALLEL_SOURCES.single { it.url == x.get("sourceUrl").asString }
            index = index.fillingMissing(BookTranslationIndex.fromParallelPage(Jsoup.parse(
                BookFixturesText(x.get("fixture").asString), source.url), source))
        }
        provenance.getAsJsonArray("cases").forEach { x ->
            val original = x.asJsonObject.get("bookOriginal").asString
            assertNotNull(index.find(original))
            assertNull(index.find("Не $original"))
            assertNull(index.find(original + " Дополнительные молитвенные слова"))
        }
        val expected = listOf(9, 7, 12, 7)
        provenance.getAsJsonArray("baselines").forEachIndexed { i, raw ->
            val x = raw.asJsonObject
            val key = x.getAsJsonObject("key")
            val missing = x.getAsJsonArray("missingPrayerIds").map { it.asString }.toSet()
            val book = parsed(key.get("date").asString, key.get("churchType").asString)
            assertTrue(book.blocks.map { it.id }.containsAll(missing))
            val before = book.copy(chapters = book.chapters.map { c -> c.copy(blocks = c.blocks.map { b ->
                if (b.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE) && b.id !in missing)
                    b.copy(russian = "Уже доступный текст ${b.id}", translationSource = core[1]) else b
            }) }).rehash()
            val after = requireNotNull(SavedBookEnrichment.apply(before, index))
            val gained = before.blocks.zip(after.blocks).filter { (a, b) -> a.russian == null && b.russian != null }
            assertEquals(expected[i], gained.count { !it.first.isAltarText })
            assertEquals(1, gained.count { it.first.isAltarText })
            assertGapOnly(before, after)
        }
    }

    @Test fun completedCoreDoesNotBlockOptionalSourcesAndFailedUrlsRetryIndependently() = runBlocking {
        val book = BookFixtures.book()
        val target = book.blocks.first { it.kind == BookBlockKind.PRAYER && it.original.length > 100 }
        assertTrue(disk.write(book))
        assertTrue(disk.writeTranslationCorpus(BookTranslationCorpus(pair("Другой текст", "Сохраненный текст", core[1]), coreUrls).encode()))
        val requests = mutableListOf<String>()
        var failed = true
        val clock = MutableClock()
        val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
            requests += url
            if (url == hour.url && failed) error("Offline source")
            response(url, table(if (url == vigil.url) target.original else "Другая молитва $url", "Новая опубликованная пара"))
        }, clock = clock)
        val first = requireNotNull(repository.enrichSaved(book))
        assertEquals(listOf(vigil.url, hour.url, panikhida.url), requests)
        assertGapOnly(book, first.book)
        assertEquals(coreUrls + setOf(vigil.url, panikhida.url), BookTranslationCorpus.decode(disk.readTranslationCorpus()).completedSources)
        assertNull(repository.enrichSaved(first.book))
        assertEquals(3, requests.size)
        // A never-attempted tone is eligible even while another liturgy source cools down.
        val sunday = parsed("2026-11-15", "30")
        assertTrue(disk.write(sunday))
        repository.enrichSaved(sunday)
        assertEquals(octoechos.url, requests.last())
        assertEquals(4, requests.size)
        clock.now += 5 * 60_000
        failed = false
        repository.enrichSaved(first.book)
        assertEquals(hour.url, requests.last())
        assertEquals(5, requests.size)
        assertTrue(BookTranslationCorpus.decode(disk.readTranslationCorpus()).completedSources.containsAll(listOf(vigil.url, hour.url, panikhida.url, octoechos.url)))
    }

    @Test fun redirectedAndEmptyOptionalPagesStayIncompleteAndDoNotSupplyTranslations() = runBlocking {
        val book = BookFixtures.book()
        val target = book.blocks.first { it.kind == BookBlockKind.PRAYER }
        for (actual in listOf("https://example.org/changed", vigil.url + "?edition=verified", vigil.url + "#prayer")) {
            assertTrue(disk.write(book))
            assertTrue(disk.writeTranslationCorpus(BookTranslationCorpus(pair("Другой текст", "Сохраненный текст", core[1]), coreUrls).encode()))
            val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
                if (url == vigil.url) response(actual, table(target.original, "Подмена"))
                else response(url, "<html>Сервис недоступен</html>")
            })
            assertNull(repository.enrichSaved(book))
            assertEquals(coreUrls, BookTranslationCorpus.decode(disk.readTranslationCorpus()).completedSources)
            assertEquals(book, disk.read(book.key))
        }
    }

    @Test fun localCandidateIsNotReturnedAfterAConcurrentPublisherChangesLatestDuringNetwork() = runBlocking {
        val accepted = BookFixtures.book()
        val target = accepted.blocks.first { it.kind == BookBlockKind.PRAYER && it.original.length > 100 }
        assertTrue(disk.write(accepted))
        assertTrue(disk.writeTranslationCorpus(BookTranslationCorpus(pair(target.original, "Локальное дополнение", vigil), coreUrls).encode()))
        var newer: ServiceBook? = null
        val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
            if (newer == null) {
                val local = requireNotNull(disk.read(accepted.key))
                assertEquals("Локальное дополнение", local.blocks.single { it.id == target.id }.russian)
                newer = local.copy(chapters = local.chapters.map { c -> c.copy(blocks = c.blocks.map {
                    if (it.id == target.id) it.copy(original = it.original + " Иное окончание", russian = null, translationSource = null) else it
                }) }).rehash()
                assertTrue(FileBookCache(directory).write(requireNotNull(newer)))
            }
            response(url, table("Посторонний оригинал", "Не относится к книге"))
        })
        assertNull(repository.enrichSaved(accepted))
        assertEquals(newer, disk.read(accepted.key))
        assertEquals(accepted, disk.readVersion(accepted.key, accepted.contentHash))
    }

    @Test fun foregroundForceCancelsOnlyTheBackgroundChildAndKeepsSuccessfulSourceBeforeNewWriter() = runBlocking {
        for (differentDate in listOf(false, true)) {
            directory.listFiles()?.forEach { it.delete() }
            val accepted = BookFixtures.book()
            val target = accepted.blocks.first { it.kind == BookBlockKind.PRAYER && it.original.length > 100 }
            assertTrue(disk.write(accepted))
            assertTrue(disk.writeTranslationCorpus(BookTranslationCorpus(pair("Другой текст", "Старый corpus", core[1]), coreUrls).encode()))
            val entered = CompletableDeferred<Unit>()
            val requests = mutableListOf<String>()
            var forcing = false
            val nextKey = if (differentDate) ServiceBookKey("2026-11-15") else accepted.key
            val repository = ServiceBookRepository(disk, ScheduleTransport { url ->
                requests += url
                when {
                    url == vigil.url -> response(url, table(target.original, "Пара до отмены"))
                    url == hour.url -> { entered.complete(Unit); awaitCancellation() }
                    url == nextKey.sourceUrl() && forcing -> response(url, BookFixtures.html("liturgy-${nextKey.date}"))
                    url in coreUrls && forcing -> response(url, table("Новое проверенное ядро", "Пара явной загрузки"))
                    "/biblia/days/" in url || url.startsWith("https://azbyka.ru/days/") -> response(url, "<html></html>")
                    else -> error("Unexpected HTTP $url")
                }
            })
            val parent = currentCoroutineContext()[Job]!!
            val background = launch { repository.enrichSaved(accepted) }
            withTimeout(5_000) { entered.await() }
            // Completion is durable before the blocked second source, not just in session memory.
            assertEquals("Пара до отмены", BookTranslationCorpus.decode(disk.readTranslationCorpus()).index.find(target.original)?.russian)
            forcing = true
            val result = withTimeout(5_000) { repository.load(nextKey, force = true) } as BookLoadResult.Ready
            background.join()
            assertTrue(parent.isActive)
            assertTrue(background.isCancelled)
            val saved = BookTranslationCorpus.decode(FileBookCache(directory).readTranslationCorpus())
            assertEquals("Пара до отмены", saved.index.find(target.original)?.russian)
            assertEquals("Пара явной загрузки", saved.index.find("Новое проверенное ядро")?.russian)
            assertTrue(vigil.url in saved.completedSources)
            assertFalse(hour.url in saved.completedSources)
            repeat(3) { yield() }
            assertEquals(result.book, disk.read(nextKey))
            assertEquals(accepted, disk.readVersion(accepted.key, accepted.contentHash))
            assertEquals(1, requests.count { it == vigil.url })
            assertEquals(1, requests.count { it == hour.url })
            assertTrue(requests.none { it == panikhida.url || it == octoechos.url })
        }
    }

    @Test fun utf8ByteLimitRejectsOversizedCorpusWithoutDeletingUsefulPreviousFile() {
        val good = BookTranslationCorpus(pair("Краткая молитва", "Перевод", vigil), setOf(vigil.url)).encode()
        assertTrue(disk.writeTranslationCorpus(good))
        val entries = JsonArray()
        repeat(130) { i -> entries.add(JsonObject().apply {
            addProperty("original", "Уникальная молитва $i")
            addProperty("russian", "я".repeat(8_000))
            addProperty("source", vigil.title); addProperty("url", vigil.url)
        }) }
        val large = BookTranslationCorpus(BookTranslationIndex.decode(entries.toString()), setOf(vigil.url)).encode()
        assertTrue(large.length < BookTranslationCorpus.MAX_BYTES)
        assertTrue(large.toByteArray(Charsets.UTF_8).size > BookTranslationCorpus.MAX_BYTES)
        assertFalse(disk.writeTranslationCorpus(large))
        assertEquals(good, disk.readTranslationCorpus())
    }

    private fun assertGapOnly(before: ServiceBook, after: ServiceBook) {
        assertTrue(ServiceBookIntegrity.valid(after))
        assertEquals(before.chapters.map { it.id }, after.chapters.map { it.id })
        before.blocks.zip(after.blocks).forEach { (a, b) ->
            if (a.russian != null || a.kind != BookBlockKind.PRAYER) assertEquals(a, b)
            else assertEquals(a, b.copy(russian = null, translationSource = a.translationSource))
        }
        assertEquals(before.blocks.size, after.blocks.size)
        assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
    }
    private fun BookFixturesText(name: String) = requireNotNull(javaClass.getResourceAsStream("/book/$name")).bufferedReader().use { it.readText() }
    private fun parsed(date: String, church: String) = ServiceBookParser().parse(Jsoup.parse(BookFixtures.html("liturgy-$date${if (church == "10") "-church10" else ""}")), ServiceBookKey(date, churchType = church), 12345)
    private fun day(date: String, voice: Int?) = BookCalendarDay(date, "Память дня", voice, emptyList(), "https://azbyka.ru/days/$date")
    private fun pair(original: String, russian: String, source: BookSource) = BookTranslationIndex.fromParallelPage(Jsoup.parse(table(original, russian), source.url), source)
    private fun table(original: String, russian: String) = "<table><tr><td>${Entities.escape(original)}</td><td>${Entities.escape(russian)}</td></tr></table>"
    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(Charsets.UTF_8), "text/html;charset=utf-8")
    private fun ServiceBook.rehash() = copy(contentHash = ServiceBookIntegrity.contentHash(this))
    private class MutableClock(var now: Long = 1_794_348_000_000) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }
}
