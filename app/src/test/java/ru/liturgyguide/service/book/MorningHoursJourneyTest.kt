package ru.liturgyguide.service.book

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences

/** Actual dated books check useful coverage; controlled stores check exact ownership and failure boundaries. */
class MorningHoursJourneyTest {
    private val parish = Parish("morning", "Храм Новомучеников", "Храм", "", "", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
    private fun route(title: String = "Часы. Божественная Литургия", time: String = "08:30") =
        ServiceBookSelection.resolve(ParishService("2026-11-14", time, title, "service_block"), parish)
    private val journey get() = requireNotNull(route()?.journeyContext)
    private fun html(name: String) = javaClass.classLoader!!.getResourceAsStream("morning-hours/$name")!!.bufferedReader().use { it.readText() }
    private fun book(kind: WorshipKind, date: String = "2026-11-14") = ServiceBookParser().parse(
        Jsoup.parse(html("${kind.queryValue}-$date-church30.html")), ServiceBookKey(date, kind), 1)
    private fun position(book: ServiceBook) = BookReadingPosition(book.key, book.contentHash, parish.id,
        book.blocks.first().id, book.chapters.first().id, fraction = .27f, mode = BookReadingMode.RUSSIAN,
        journeyContext = journey)

    @Test fun publicationOffersThreeOrdinaryBooksOnTheSameCivilDateAtAnyValidStartTime() {
        for (time in listOf("00:00", "08:30", "13:00", "23:59")) {
            val selected = requireNotNull(route(time = time))
            assertEquals(WorshipKind.HOUR3, selected.activeKey.worship)
            assertEquals(listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY), selected.journeyContext?.parts?.map { it.worship })
            assertTrue(selected.journeyContext!!.parts.all { it.date == "2026-11-14" && it.churchType == "30" })
            assertEquals(BookPartSetKind.HOURS_LITURGY_TEXTS, selected.journeyContext?.partSet)
            assertEquals(selected, BookRouteCodec.decode(BookRouteCodec.encode(selected)))
        }
        assertNull(route(time = "24:00"))
        assertTrue(ServiceBookSelection.isHoursLiturgy("Мучеников 12 апостолов. Часы. Литургия"))
        assertTrue(ServiceBookSelection.hoursLiturgyScope("Часы. Литургия. Молебен. Панихида").contains("не входят"))
    }

    @Test fun timeMentionsSpecialHoursAndAnExplicitDifferentCompositionNeverBecomeOrdinaryThreeParts() {
        for (title in listOf("Молебен после литургии", "За час до литургии", "После часов. Литургия",
            "Царские часы. Литургия", "Пасхальные часы. Литургия", "Часы. Литургия Преждеосвященных Даров",
            "9-й час. Литургия", "Девятый час. Литургия", "Часы. Изобразительны. Литургия",
            "Часы (1-й, 3-й, 6-й, 9-й). Литургия", "Часы (9-й). Литургия",
            "Часы: первый, третий, шестой, девятый. Литургия", "ЧАСЫ (9-Й). ЛИТУРГИЯ", "Часы 9-й. Литургия")) {
            assertFalse(title, ServiceBookSelection.isHoursLiturgy(title))
            assertNull(title, route(title)?.journeyContext)
        }
        assertEquals(WorshipKind.LITURGY, route("Часы (9-й). Литургия")?.activeKey?.worship)
    }

    @Test fun strictContextRejectsWrongOrderMissingPartDifferentDateChurchAndDedication() {
        val expected = journey; val first = expected.parts.first()
        for (parts in listOf(expected.parts.reversed(), expected.parts.take(2), expected.parts + first,
            expected.parts.mapIndexed { i, key -> if (i == 1) key.copy(date = "2026-11-15") else key },
            expected.parts.mapIndexed { i, key -> if (i == 2) key.copy(churchType = "20") else key },
            expected.parts.mapIndexed { i, key -> if (i == 1) key.copy(patronalId = "42") else key }))
            assertNull(expected.copy(parts = parts).validatedFor(first, parish.id))
        assertNull(expected.copy(partSet = null).validatedFor(first, parish.id))
        val damaged = JsonParser.parseString(BookRouteCodec.encode(requireNotNull(route()))).asJsonObject
        damaged.getAsJsonObject("journeyContext").getAsJsonArray("parts").remove(1)
        assertNull("A new malformed three-part route cannot silently become standalone hours", BookRouteCodec.decode(damaged.toString()))
        val evening = BookJourneyContext(parish.id, "2026-11-13", "17:00", "Вечерня. Утреня",
            listOf(ServiceBookKey("2026-11-14", WorshipKind.VESPERS), ServiceBookKey("2026-11-14", WorshipKind.MATINS)))
        val old = BookRoute(evening.parts.first(), parish.id, evening)
        assertEquals(old, BookRouteCodec.decode(BookRouteCodec.encode(old)))
        assertNull(old.journeyContext?.partSet)
    }

    @Test fun realBooksContainAllSixHourPsalmsAndTheirOwnClosingPrayersAndDatedLiturgy() {
        val third = book(WorshipKind.HOUR3); val sixth = book(WorshipKind.HOUR6); val liturgy = book(WorshipKind.LITURGY)
        fun psalms(source: ServiceBook) = source.chapters.single { BookText.normalized(it.title) == "псалмы" }
            .blocks.filter { it.kind == BookBlockKind.RUBRIC }.map { BookText.normalized(it.original) }
            .filter { Regex("псалом [0-9]+").matches(it) }
        assertEquals(listOf("псалом 16", "псалом 24", "псалом 50"), psalms(third))
        assertEquals(listOf("псалом 53", "псалом 54", "псалом 90"), psalms(sixth))
        assertEquals("молитва святаго мардария", BookText.normalized(third.chapters.last().title))
        assertTrue(BookText.normalized(third.blocks.last().original).startsWith("владыко боже отче вседержителю"))
        assertTrue(BookText.normalized(third.blocks.last().original).endsWith("яко благословен еси во веки веков аминь"))
        assertEquals("молитва свт василия великаго", BookText.normalized(sixth.chapters.last().title))
        assertTrue(BookText.normalized(sixth.blocks.last().original).startsWith("боже и господи сил и всея твари содетелю"))
        assertTrue(BookText.normalized(sixth.blocks.last().original).endsWith("ныне и присно и во веки веков аминь"))
        assertTrue(listOf(third.blocks.last(), sixth.blocks.last()).all { it.kind == BookBlockKind.PRAYER && !it.isAltarText })
        assertTrue(liturgy.chapters.any { BookText.normalized(it.title) == "отпуст" })
        for (source in listOf(third, sixth, liturgy)) assertTrue(ServiceBookIntegrity.valid(source))
        assertEquals(BookHourReviewStatus.MATCHED_RULE, BookHourSourceReview.of(third).status)
        assertEquals(BookHourReviewStatus.MATCHED_RULE, BookHourSourceReview.of(sixth).status)
    }

    @Test fun allNeighboursAreSearchedWhileAcceptedEditionsArePinnedAndNoCursorIsWritten() = runBlocking {
        val third = book(WorshipKind.HOUR3); val sixth = book(WorshipKind.HOUR6); val liturgy = book(WorshipKind.LITURGY)
        val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs)
        assertTrue(store.save(position(sixth)))
        val newer = edition(sixth, "Новая редакция")
        val books = Books().apply { publish(third); publish(sixth); publish(newer); downloads[liturgy.key] = liturgy }
        val before = prefs.values.toMap()
        val result = BookSearchCorpusLoader.load(third, journey, parish.id, books, store)
        assertEquals(listOf(third, sixth, liturgy), result.books)
        assertNull(result.error)
        assertEquals(listOf(liturgy.key), books.loads)
        assertFalse(books.latestReads.contains(sixth.key))
        assertEquals(listOf(WorshipKind.HOUR6, WorshipKind.LITURGY), result.otherParts)
        assertEquals(before, prefs.values)
    }

    @Test fun aMissingOrUnreadableAcceptedSixthHourCannotHideTheLiturgyOrAuthorizeLatest() = runBlocking {
        for (unreadable in listOf(false, true)) {
            val third = book(WorshipKind.HOUR3); val sixth = book(WorshipKind.HOUR6); val liturgy = book(WorshipKind.LITURGY)
            val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs)
            assertTrue(store.save(position(sixth)))
            if (unreadable) prefs.values[prefs.values.keys.single { it.startsWith("position:") }] = "{broken"
            val books = Books().apply { publish(third); latest[sixth.key] = sixth; publish(liturgy) }
            val before = prefs.values.toMap()
            val result = BookSearchCorpusLoader.load(third, journey, parish.id, books, store)
            assertEquals(listOf(third, liturgy), result.books)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("шестого часа"))
            assertFalse(books.latestReads.contains(sixth.key)); assertTrue(books.loads.isEmpty())
            assertEquals(before, prefs.values)
        }
    }

    @Test fun cancellingTheFirstNeighbourPreventsReadingTheSecond() = runBlocking {
        val third = book(WorshipKind.HOUR3); val entered = CompletableDeferred<Unit>(); val requests = mutableListOf<ServiceBookKey>()
        val prefs = AcceptanceMemoryPreferences()
        val books = object : ServiceBooks {
            override fun cached(key: ServiceBookKey): ServiceBook? { requests += key; return null }
            override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
                requests += key; entered.complete(Unit); awaitCancellation()
            }
        }
        val job = launch { BookSearchCorpusLoader.load(third, journey, parish.id, books, BookReadingStore(prefs)) }
        entered.await(); job.cancelAndJoin()
        assertTrue(requests.all { it.worship == WorshipKind.HOUR6 }); assertTrue(prefs.values.isEmpty())
    }

    @Test fun preparationSavesTheMissingLiturgyAndReopensAllThreeExactBooksWithoutAcceptingThem() = runBlocking {
        val third = book(WorshipKind.HOUR3); val sixth = book(WorshipKind.HOUR6); val liturgy = book(WorshipKind.LITURGY)
        val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs)
        assertTrue(store.save(position(sixth)))
        val books = Books().apply { publish(third); publish(sixth); downloads[liturgy.key] = liturgy }
        val before = prefs.values.toMap()
        val state = BookOfflinePreparation.prepare(third, journey, parish.id, books, store)
        assertTrue(state.isComplete); assertEquals(3, state.readyCount); assertEquals(3, state.storedCount)
        assertEquals(listOf(liturgy.key), books.loads); assertEquals(before, prefs.values)
        books.allowLoads = false
        assertEquals(state, BookOfflinePreparation.inspect(third, journey, parish.id, books, store))
        assertEquals(before, prefs.values)
    }

    @Test fun theRealConflictingKondakIsFlaggedWithoutChangingItsTextAndDoesNotBlockOtherBooks() = runBlocking {
        val third = book(WorshipKind.HOUR3, "2026-11-13"); val sixth = book(WorshipKind.HOUR6, "2026-11-13")
        val liturgy = book(WorshipKind.LITURGY, "2026-11-13")
        val review = BookHourSourceReview.of(sixth)
        assertEquals(BookHourReviewStatus.CONFLICT, review.status)
        assertTrue(review.actualHeader!!.contains("Епима́ха")); assertTrue(review.expectedQuote!!.contains("Кондак апостолов"))
        val context = requireNotNull(ServiceBookSelection.resolve(ParishService("2026-11-13", "08:30", "Часы. Литургия", "service_block"), parish)?.journeyContext)
        val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs)
        val books = Books().apply { listOf(third, sixth, liturgy).forEach(::publish) }
        val state = BookOfflinePreparation.inspect(third, context, parish.id, books, store)
        assertFalse(state.isComplete); assertEquals(3, state.storedCount)
        assertEquals(BookOfflinePartStatus.REVIEW_REQUIRED, state.parts.single { it.key == sixth.key }.status)
        val result = BookSearchCorpusLoader.load(third, context, parish.id, books, store)
        assertEquals(listOf(third, liturgy), result.books); assertTrue(result.error!!.contains("Шестой час"))
        assertTrue(prefs.values.isEmpty()); assertEquals(sixth, books.cachedVersion(sixth.key, sixth.contentHash))
    }

    @Test fun unknownOldOrTamperedReviewNeverBecomesEvidenceAndTheOriginalBookRemainsReadable() {
        val source = book(WorshipKind.HOUR6)
        val notice = source.notices.single(BookHourSourceReview::isInternalNotice)
        fun changed(record: String) = source.copy(notices = source.notices.filterNot(BookHourSourceReview::isInternalNotice) + record)
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(source.copy(notices = emptyList())).status)
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(source.copy(notices = source.notices + notice)).status)
        val json = notice.removePrefix(BookHourSourceReview.PREFIX)
        for ((field, value) in listOf("version" to "2", "key" to "different", "mainEvent" to "1",
            "fingerprint" to "a".repeat(64), "header" to "Кондак другого святого")) {
            val data = JsonParser.parseString(json).asJsonObject
            if (field == "version") data.addProperty(field, 2) else data.addProperty(field, value)
            assertEquals(field, BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(changed(BookHourSourceReview.PREFIX + data)).status)
        }
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(changed(BookHourSourceReview.PREFIX + json.replaceFirst("{", "{\"version\":1,"))).status)
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(changed(BookHourSourceReview.PREFIX + "{broken")).status)
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(source.copy(key = source.key.copy(mainEvent = "0"))).status)
        val kondak = source.chapters.single { BookText.normalized(it.title) == "кондак" }
        val modified = source.copy(chapters = source.chapters.map { chapter -> if (chapter != kondak) chapter else chapter.copy(
            blocks = chapter.blocks.map { if (it.kind == BookBlockKind.PRAYER) it.copy(original = it.original + " Другой текст") else it }) })
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(modified).status)
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(source.copy(chapters = source.chapters + kondak.copy(id = "duplicate"))).status)
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(source.copy(sources = emptyList())).status)
    }

    @Test fun aRuleOutsideTheIndependentGuidanceParagraphAndAmbiguousFormCannotVerifyAnHour() {
        val key = ServiceBookKey("2026-11-14", WorshipKind.HOUR6)
        val raw = html("chas6-2026-11-14-church30.html")
        val original = ServiceBookParser().parse(Jsoup.parse(raw), key, 1)
        for (mutate in listOf<(org.jsoup.nodes.Document) -> Unit>(
            { doc -> doc.select(".patriarchiaBU > p").first { it.text().startsWith("На часах") }.wrap("<aside></aside>") },
            { doc -> val p = doc.select(".patriarchiaBU > p").first { it.text().startsWith("На часах") }; p.parent()!!.appendChild(p.clone()) },
            { doc -> val p = doc.select(".patriarchiaBU > p").first { it.text().startsWith("На часах") }; p.remove(); doc.body().prependChild(p) },
            { doc -> val option = doc.select("#bg_worships_secondEvent option[selected]").single(); option.parent()!!.appendChild(option.clone()) }
        )) {
            val doc = Jsoup.parse(raw); mutate(doc)
            val parsed = ServiceBookParser().parse(doc, key, 1)
            assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(parsed).status)
            assertEquals("A review annotation may not alter any source fragment", original.chapters, parsed.chapters)
        }
    }

    @Test fun anOldAcceptedUnknownHourStaysExactAndNeverBecomesVerifiedByANewerCachedEdition() = runBlocking {
        val third = book(WorshipKind.HOUR3); val sixth = book(WorshipKind.HOUR6); val liturgy = book(WorshipKind.LITURGY)
        val old = sixth.copy(notices = sixth.notices.filterNot(BookHourSourceReview::isInternalNotice)).let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs)
        assertTrue(store.save(position(old)))
        val books = Books().apply { listOf(third, old, sixth, liturgy).forEach(::publish) }
        val before = prefs.values.toMap()
        val state = BookOfflinePreparation.inspect(third, journey, parish.id, books, store)
        val part = state.parts.single { it.key == sixth.key }
        assertEquals(BookOfflinePartStatus.REVIEW_REQUIRED, part.status); assertEquals(old.contentHash, part.savedHash)
        assertFalse(state.isComplete); assertEquals(3, state.storedCount)
        val corpus = BookSearchCorpusLoader.load(third, journey, parish.id, books, store)
        assertEquals(listOf(third, old, liturgy), corpus.books); assertNull(corpus.error)
        assertTrue(corpus.warnings.single().contains("Шестой час"))
        assertTrue("A missing review cannot remove a usable exact hour from heard-word search",
            BookCorpusSearch.find(corpus.books, "Живый в помощи Вышняго", BookReadingMode.ORIGINAL).any { it.bookKey == old.key })
        assertFalse(books.latestReads.contains(sixth.key)); assertTrue(books.loads.isEmpty())
        assertEquals(before, prefs.values); assertEquals(old, books.cachedVersion(old.key, old.contentHash))
    }

    @Test fun royalAndPaschalFallbacksAreNotOrdinaryHoursAndPresanctifiedCannotCompleteAnOrdinarySet() {
        for ((file, date) in listOf("chas3-2026-01-06.html" to "2026-01-06", "chas3-2026-04-12.html" to "2026-04-12")) {
            try { ServiceBookParser().parse(Jsoup.parse(html(file)), ServiceBookKey(date, WorshipKind.HOUR3), 1); fail(file) }
            catch (_: InvalidServiceBook) { }
        }
        val special = ServiceBookParser().parse(Jsoup.parse(html("liturgy-2026-03-11.html")), ServiceBookKey("2026-03-11"), 1)
        assertTrue(ServiceBookIntegrity.valid(special))
        val review = BookHourSourceReview.of(special, inMorningJourney = true)
        assertTrue(review.needsReview); assertTrue(review.compositionOnly); assertFalse(review.blocksPlayback)
        assertEquals(BookHourReviewStatus.NOT_APPLICABLE, BookHourSourceReview.of(special).status)
    }

    @Test fun explicitHourVariantsRemainUnknownAndCannotInheritTheDefaultConflictOrBypassReview() {
        val key = ServiceBookKey("2026-11-13", WorshipKind.HOUR6, mainEvent = "0")
        val actual = ServiceBookParser().parse(Jsoup.parse(html("chas6-2026-11-13-main0.html")), key, 1)
        assertTrue(actual.blocks.any { BookText.normalized(it.original).startsWith("кондак мч епимаха") })
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(actual).status)
        assertTrue(BookHourSourceReview.of(actual).needsReview)
        val default = book(WorshipKind.HOUR6, "2026-11-13")
        assertEquals(BookHourReviewStatus.CONFLICT, BookHourSourceReview.of(default).status)
        for (variant in listOf(default.key.copy(mainEvent = "0"), default.key.copy(secondEvent = "1"),
            default.key.copy(mainEvent = "0", secondEvent = "1"), default.key.copy(patronalId = "123"))) {
            val review = BookHourSourceReview.of(default.copy(key = variant))
            assertEquals(variant.toString(), BookHourReviewStatus.UNKNOWN, review.status)
            assertTrue(review.blocksPlayback)
        }
    }

    @Test fun aLateAcceptedOwnerChangeRemovesTheUnknownEditionFromStoredCountWithoutChangingUserState() = runBlocking {
        for (changedKind in listOf(WorshipKind.HOUR3, WorshipKind.HOUR6)) {
            val third = book(WorshipKind.HOUR3); val sixth = book(WorshipKind.HOUR6); val liturgy = book(WorshipKind.LITURGY)
            val reviewed = if (changedKind == WorshipKind.HOUR3) third else sixth
            val accepted = unreviewed(reviewed)
            val next = edition(accepted, "Другая сохраненная редакция")
            val active = if (changedKind == WorshipKind.HOUR3) accepted else third
            val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs)
            assertTrue(store.save(position(accepted)))
            val books = Books().apply { listOf(third, sixth, liturgy, accepted, next).forEach(::publish) }
            var changed = false
            var expectedPreferences: Map<String, Any?>? = null
            books.afterVersionRead = { key, _ -> if (key == liturgy.key && !changed) {
                changed = true
                assertTrue(store.save(position(next)))
                expectedPreferences = prefs.values.toMap()
            } }
            val state = BookOfflinePreparation.inspect(active, journey, parish.id, books, store)
            val stale = state.parts.single { it.key == accepted.key }
            assertTrue(changed)
            assertEquals(changedKind.toString(), BookOfflinePartStatus.NEEDS_RECOVERY, stale.status)
            assertNull(stale.savedHash); assertEquals(2, state.storedCount); assertFalse(state.isComplete)
            assertEquals(expectedPreferences, prefs.values); assertTrue(books.loads.isEmpty())
        }
    }

    @Test fun aLateLatestChangeCannotLeaveAnUnacceptedUnknownEditionClaimedAsStored() = runBlocking {
        val third = book(WorshipKind.HOUR3); val sixth = unreviewed(book(WorshipKind.HOUR6)); val liturgy = book(WorshipKind.LITURGY)
        val next = edition(sixth, "Другая последняя редакция")
        val prefs = AcceptanceMemoryPreferences(); val store = BookReadingStore(prefs)
        val books = Books().apply { listOf(third, sixth, liturgy).forEach(::publish) }
        var changed = false
        books.afterVersionRead = { key, _ -> if (key == liturgy.key && !changed) { changed = true; books.publish(next) } }
        val state = BookOfflinePreparation.inspect(third, journey, parish.id, books, store)
        val stale = state.parts.single { it.key == sixth.key }
        assertTrue(changed); assertEquals(BookOfflinePartStatus.STORAGE_ERROR, stale.status)
        assertNull(stale.savedHash); assertEquals(2, state.storedCount); assertFalse(state.isComplete)
        assertTrue(prefs.values.isEmpty()); assertTrue(books.loads.isEmpty())
    }

    private fun unreviewed(book: ServiceBook) = book.copy(notices = book.notices.filterNot(BookHourSourceReview::isInternalNotice))
        .let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
    private fun edition(book: ServiceBook, note: String): ServiceBook = book.copy(notices = book.notices + note)
        .let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
    private class Books : ServiceBooks {
        val latest = mutableMapOf<ServiceBookKey, ServiceBook>()
        val versions = mutableMapOf<Pair<ServiceBookKey, String>, ServiceBook>()
        val downloads = mutableMapOf<ServiceBookKey, ServiceBook>()
        val latestReads = mutableListOf<ServiceBookKey>(); val loads = mutableListOf<ServiceBookKey>()
        var afterVersionRead: ((ServiceBookKey, String) -> Unit)? = null
        var allowLoads = true
        fun publish(book: ServiceBook) { latest[book.key] = book; versions[book.key to book.contentHash] = book }
        override fun cached(key: ServiceBookKey): ServiceBook? { latestReads += key; return latest[key] }
        override fun cachedVersion(key: ServiceBookKey, contentHash: String) = versions[key to contentHash].also {
            afterVersionRead?.invoke(key, contentHash)
        }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            check(allowLoads); loads += key
            val book = downloads[key] ?: return BookLoadResult.Failed(BookFailure.NETWORK, "Нет сети")
            publish(book); return BookLoadResult.Ready(book, fromCache = false)
        }
    }
}
