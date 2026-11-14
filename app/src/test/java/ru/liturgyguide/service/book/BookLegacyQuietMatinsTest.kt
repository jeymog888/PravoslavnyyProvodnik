package ru.liturgyguide.service.book

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

/** The untouched accepted Android edition, not a positive fixture with changed kinds. */
class BookLegacyQuietMatinsTest {
    private val gson = Gson()
    private val text by lazy { requireNotNull(javaClass.classLoader?.getResource(
        "quiet-matins/ranked-matins-2026-11-13-church34.json")).readText() }
    private val book by lazy { gson.fromJson(text, ServiceBook::class.java) }
    private val chapter get() = book.chapters.single { it.title == "Шестопсалмие" }
    private val group get() = chapter.blocks.subList(18, 54)
    private val ids get() = group.map { it.id }.toSet()
    private fun changed(edit: (MutableList<BookBlock>) -> Unit) = book.copy(chapters = book.chapters.map {
        if (it.id != chapter.id) it else it.copy(blocks = it.blocks.toMutableList().also(edit))
    })
    private fun rejected(candidate: ServiceBook, expectedIds: Set<String> = ids) {
        val scope = BookVisibilityScope.of(candidate)
        assertNull("A broken legacy guard must not partly hide text", scope.quietGroup)
        assertTrue(candidate.blocks.filter { it.id in expectedIds && !it.isAltarText }.all { scope.isVisible(it) })
    }

    private fun additionalBooks(): List<ServiceBook> = listOf(
        Triple("legacy-daily-2026-11-13-church30.json", "c7a8ea6f9915b4c60e33a6d709e5df45c19e22c813fcec500277c5f1648dff98",
            "46b462096ae0f952529eba8a3395a060684dd91af6a6a34187519d2d7d3e0689"),
        Triple("legacy-ranked-2026-11-18-church34.json", "427e8a5898de734a11ccb025628d010050b5bb8cad787abd561d111d5be125fe",
            "91d7a5d38eff1409649f83d2d6522856e9e2b76f20dfa0492ecc6d62c0082a5d")
    ).map { (name, rawSha, acceptedHash) ->
        val raw = requireNotNull(javaClass.classLoader?.getResource("quiet-matins/$name")).readText()
        assertEquals(rawSha, BookText.sha256(raw))
        gson.fromJson(raw, ServiceBook::class.java).also { assertEquals(acceptedHash, it.contentHash) }
    }

    private fun changed(other: ServiceBook, edit: (MutableList<BookBlock>) -> Unit) =
        other.copy(chapters = other.chapters.map { c ->
            if (c.title != "Шестопсалмие") c else c.copy(blocks = c.blocks.toMutableList().also(edit))
        })

    @Test fun actualAcceptedPrayerLabelsHideExactlyTheTwelveWholePrayersAndKeepAllSixPublicPsalms() {
        assertEquals("440b36d4b7f190d3da575d12f0f6ff0a1e3c272e40be87ea32ea5cf162bfc79d", BookText.sha256(text))
        assertEquals("4b9b2e7278f3d670ee813dd2afd341fcb7b2d33b0348a0a4754c0f36ad2f8b79", book.contentHash)
        val before = gson.toJson(book)
        val scope = BookVisibilityScope.of(book)
        assertEquals(ids, requireNotNull(scope.quietGroup).blockIds)
        assertEquals(36, group.size)
        assertTrue(group.all { it.kind == BookBlockKind.PRAYER && !it.isAltarText })
        // Legacy labels are also PRAYER. Bodies must be counted by their source positions.
        val bodies = group.filterIndexed { index, _ -> index % 3 != 0 }
        assertEquals(24, bodies.size)
        assertEquals(1278, bodies.sumOf { BookAutoScroll.wordCount(it.original) })
        assertEquals(1302, group.sumOf { BookAutoScroll.wordCount(it.original) })
        assertTrue(group.all { scope.isPrivate(it) && !scope.isVisible(it) && scope.isVisible(it, showQuiet = true) })
        val public = chapter.blocks.filter { scope.isVisible(it) }
        assertEquals(listOf(3, 37, 62, 87, 102, 142).map { "псалом $it" },
            public.map { BookText.normalized(it.original) }.filter { Regex("псалом [0-9]+").matches(it) })
        assertEquals("Псалом 87", public[public.indexOfFirst { it.id == scope.quietGroup!!.introductionId } + 1].original)
        assertEquals(before, gson.toJson(book))
    }

    @Test fun twoOtherGenuineLegacyKeysUseTheSameQuietBodiesWithoutDateWhitelists() {
        for (other in additionalBooks()) {
            assertNotEquals(book.key, other.key)
            val before = gson.toJson(other)
            val scope = BookVisibilityScope.of(other)
            val quiet = requireNotNull(scope.quietGroup)
            val otherChapter = other.chapters.single { it.id == quiet.chapterId }
            val actualGroup = otherChapter.blocks.filter { it.id in quiet.blockIds }
            assertEquals(36, actualGroup.size)
            assertEquals(group.map { it.original }, actualGroup.map { it.original })
            assertTrue(actualGroup.all { it.kind == BookBlockKind.PRAYER && !it.isAltarText && !scope.isVisible(it) && scope.isVisible(it, showQuiet = true) })
            assertEquals(24, actualGroup.filterIndexed { i, _ -> i % 3 != 0 }.size)
            assertEquals(1278, actualGroup.filterIndexed { i, _ -> i % 3 != 0 }.sumOf { BookAutoScroll.wordCount(it.original) })
            assertEquals(1302, actualGroup.sumOf { BookAutoScroll.wordCount(it.original) })
            assertEquals(listOf(3, 37, 62, 87, 102, 142).map { "псалом $it" },
                otherChapter.blocks.filter { scope.isVisible(it) }.map { BookText.normalized(it.original) }
                    .filter { Regex("псалом [0-9]+").matches(it) })
            val enriched = changed(other) { blocks -> for (i in 18..53) blocks[i] = blocks[i].copy(
                russian = "Русский текст $i", meaning = "Пояснение $i", translationSource = BookSource("Перевод", "https://example.org/translation")) }
                .copy(contentHash = "b".repeat(64), fetchedAtMillis = 9999)
            assertEquals(quiet.blockIds, BookVisibilityScope.of(enriched).quietGroup?.blockIds)
            val target = actualGroup[1]
            val position = BookReadingPosition(other.key, other.contentHash, "parish", target.id, otherChapter.id)
            val meaning = requireNotNull(BookMeaningContent.capture(other, position))
            assertTrue(meaning.privateText); assertNull(meaning.brief); assertEquals(quiet.explanation, meaning.detail)
            val hits = BookCorpusSearch.find(listOf(other), "яко ты еси бог наш", BookReadingMode.ORIGINAL)
            assertTrue(hits.first().hit.blockId !in quiet.blockIds)
            assertTrue(hits.any { it.hit.blockId in quiet.blockIds })
            hits.forEach { assertEquals(it, it.validatedFor(other)) }
            assertEquals(before, gson.toJson(other))
        }
    }

    @Test fun aConsistentValidCanonicalKeyAndUrlAreNotRejectedJustForAnotherDate() {
        // A pure source-contract case, distinct from the two untouched historical fixtures above.
        val key = book.key.copy(date = "2026-11-15")
        val other = book.copy(key = key, sourceUrl = ResolvedMatinsRankRequest(key, "0").url)
        assertEquals(ids, BookVisibilityScope.of(other).quietGroup?.blockIds)
    }

    @Test fun sourceIdentityAndResolvedRankAreRequiredWithoutUsingTheEditionHash() {
        val keys = listOf(book.key.copy(date = "2026-11-14"), book.key.copy(churchType = "30"),
            book.key.copy(worship = WorshipKind.VESPERS), book.key.copy(mainEvent = "0"),
            book.key.copy(secondEvent = "1"), book.key.copy(topLevel = null), book.key.copy(patronalId = "1"))
        keys.forEach { rejected(book.copy(key = it)) }
        listOf(book.sourceUrl.replace("azbyka.ru", "other.example"), book.sourceUrl.replace("/worships/", "/other/"),
            book.sourceUrl.substringBefore('&'), book.sourceUrl + "&extra=1", "").forEach {
            rejected(book.copy(sourceUrl = it))
        }
        listOf<BookRankSelection?>(null, BookRankSelection("1", "", "3"),
            BookRankSelection("0", "1", "3"), BookRankSelection("0", "", "2")).forEach {
            rejected(book.copy(rankSelection = it))
        }
        val strayProof = PatronalEventProof("42", "123", book.key.date, "Память", book.key.churchType,
            "https://azbyka.ru/worships/calendar/api/${book.key.date}/")
        assertNull(book.key.patronalId)
        rejected(book.copy(patronalSelection = strayProof))
        // A correctly shaped ranked URL does not replace the existing rank body contract.
        rejected(book.copy(chapters = book.chapters.filter { BookText.normalized(it.title) != "полиелей" }))
        rejected(book.copy(chapters = book.chapters.map { if (BookText.normalized(it.title) != "полиелей") it
            else it.copy(blocks = it.blocks.map { b -> b.copy(original = "Иные слова") }) }))
        assertEquals(ids, BookVisibilityScope.of(book.copy(contentHash = "b".repeat(64), fetchedAtMillis = 987654321)).quietGroup?.blockIds)
    }

    @Test fun theExistingRubricBranchKeepsItsPreviousScopeWithoutTheNewLegacySourceGuard() {
        val raw = requireNotNull(javaClass.classLoader?.getResource("legacy-presentation/matins-accepted-unchanged.json")).readText()
        val positive = gson.fromJson(raw, ServiceBook::class.java)
        val original = requireNotNull(BookVisibilityScope.of(positive).quietGroup)
        // A pure presentation regression case, not authorization to save this changed source.
        val unrelatedSource = positive.copy(sourceUrl = "https://example.org/unrelated")
        assertEquals(original, BookVisibilityScope.of(unrelatedSource).quietGroup)
    }

    @Test fun familySourceMismatchAndExactUrlNegativesCoverRanklessAndRanked() {
        for (other in additionalBooks()) {
            val quiet = requireNotNull(BookVisibilityScope.of(other).quietGroup)
            val badKeys = listOf(other.key.copy(date = "2026-02-30"), other.key.copy(churchType = "999"),
                other.key.copy(mainEvent = "1234567"), other.key.copy(secondEvent = "x"),
                other.key.copy(topLevel = "2"), other.key.copy(worship = WorshipKind.LITURGY),
                other.key.copy(date = "2026-12-01"), other.key.copy(churchType = "20"),
                other.key.copy(patronalId = "1"))
            badKeys.forEach { rejected(other.copy(key = it), quiet.blockIds) }
            val url = other.sourceUrl
            val badUrls = listOf(url.replace("https:", "http:"), url.replace("azbyka.ru", "azbyka.ru.evil.example"),
                url.replace("/worships/", "/days/"), url.replace("size=full", "size=short"), url.replace("&size=full", ""),
                url + "#fragment", url + "&date=${other.key.date}", url + "&x=1",
                url.replace("worship=matins&size=full", "size=full&worship=matins"),
                url.replace("church=${other.key.churchType}", "church=20"), url.replace("worship=matins", "worship=vespers"),
                url.replace("date=${other.key.date}", "date=2026-12-01"))
            badUrls.forEach { rejected(other.copy(sourceUrl = it), quiet.blockIds) }
            val stray = PatronalEventProof("42", "123", other.key.date, "Память", other.key.churchType,
                "https://azbyka.ru/worships/calendar/api/${other.key.date}/")
            rejected(other.copy(patronalSelection = stray), quiet.blockIds)
            if (other.key.topLevel == null) {
                rejected(other.copy(rankSelection = BookRankSelection("0", "", "3")), quiet.blockIds)
                for (event in listOf<String?>(null, "", "123")) for (second in listOf<String?>(null, "", "456")) {
                    val key = other.key.copy(mainEvent = event, secondEvent = second)
                    val consistent = other.copy(key = key, sourceUrl = key.sourceUrl())
                    assertEquals("Synthetic canonical contract only", quiet.blockIds, BookVisibilityScope.of(consistent).quietGroup?.blockIds)
                }
            } else {
                for (rank in listOf<BookRankSelection?>(null, BookRankSelection("", "", "3"), BookRankSelection("x", "", "3"),
                    BookRankSelection("1234567", "", "3"), BookRankSelection("0", "1", "3"), BookRankSelection("0", "", "2")))
                    rejected(other.copy(rankSelection = rank), quiet.blockIds)
                rejected(other.copy(key = other.key.copy(mainEvent = "")), quiet.blockIds)
                rejected(other.copy(key = other.key.copy(secondEvent = "")), quiet.blockIds)
                rejected(other.copy(sourceUrl = url.replace("&main_event=0", "")), quiet.blockIds)
                rejected(other.copy(sourceUrl = url.replace("&top_level=3", "")), quiet.blockIds)
                for (title in listOf("полиелей", "чтение евангелия", "славословие великое")) {
                    val section = other.chapters.single { BookText.normalized(it.title) == title }
                    rejected(other.copy(chapters = other.chapters.filter { it.id != section.id }), quiet.blockIds)
                    rejected(other.copy(chapters = other.chapters + section.copy(id = "duplicate-$title")), quiet.blockIds)
                }
                rejected(other.copy(chapters = other.chapters.map { c -> if (BookText.normalized(c.title) != "чтение евангелия") c
                    else c.copy(blocks = c.blocks.map { it.copy(scriptureReferences = emptyList()) }) }), quiet.blockIds)
                rejected(other.copy(chapters = other.chapters.map { c -> if (BookText.normalized(c.title) != "чтение евангелия") c
                    else c.copy(blocks = c.blocks.map { it.copy(original = "") }) }), quiet.blockIds)
            }
        }
    }

    @Test fun bothLegacyFamilyBranchesFailClosedForStructuralMetadataAndExternalIdChanges() {
        for (other in additionalBooks()) {
            val quiet = requireNotNull(BookVisibilityScope.of(other).quietGroup)
            val cases = listOf(
                changed(other) { it[18] = it[18].copy(kind = BookBlockKind.RUBRIC) },
                changed(other) { it[19] = it[19].copy(original = it[19].original + " ") },
                changed(other) { val a = it[19]; it[19] = it[22]; it[22] = a },
                changed(other) { it[19] = it[19].copy(speaker = "Священник") },
                changed(other) { it[19] = it[19].copy(scriptureReferences = listOf("Пс.87:1")) },
                changed(other) { it[19] = it[19].copy(isAltarText = true) },
                changed(other) { it.removeAt(19) },
                changed(other) { it[54] = it[54].copy(original = "Псалом 88") },
                other.copy(chapters = other.chapters + BookChapter("collision", "Другая глава", "",
                    listOf(other.blocks.single { it.id == quiet.blockIds.elementAt(1) }))))
            cases.forEach { rejected(it, quiet.blockIds) }
        }
    }

    @Test fun everyGroupMemberRequiresTheOriginalPhysicalRoleSpeakerAndReferenceShape() {
        for (index in 18..53) {
            rejected(changed { it[index] = it[index].copy(kind = BookBlockKind.RUBRIC) })
            rejected(changed { it[index] = it[index].copy(isAltarText = true) })
            rejected(changed { it[index] = it[index].copy(speaker = "Священник") })
            rejected(changed { it[index] = it[index].copy(scriptureReferences = listOf("Пс.87:1")) })
        }
    }

    @Test fun orderedFullBodiesCannotBeReplacedByIncipitsSetsOrNormalizedText() {
        for (index in 18..53) {
            if ((index - 18) % 3 == 0) continue
            for (altered in listOf(group[index - 18].original + " ", group[index - 18].original + ".",
                group[index - 18].original.replaceFirst('́'.toString(), ""), group[index - 18].original.take(80))) {
                assertNotEquals(group[index - 18].original, altered)
                rejected(changed { it[index] = it[index].copy(original = altered) })
            }
        }
        rejected(changed { val first = it[19]; it[19] = it[22]; it[22] = first })
        rejected(changed { val first = it[20]; it[20] = it[23]; it[23] = first })
        // The same conclusion occurs twice; a set cannot establish occurrence order.
        rejected(changed { it[20] = it[53].copy(id = it[20].id) })
        assertEquals(chapter.blocks[32].original, chapter.blocks[53].original)
    }

    @Test fun everyLabelOrdinalAndWholeSurroundingContextMustStayComplete() {
        for (index in 18..53 step 3) {
            rejected(changed { it[index] = it[index].copy(original = "Молитва другая:") })
        }
        rejected(changed { it[21] = it[21].copy(original = it[18].original) })
        rejected(changed { it.removeAt(19) })
        rejected(changed { it.add(19, it[19].copy(id = "inserted")) })
        rejected(changed { it[54] = it[54].copy(original = "Псалом 88") })
        rejected(changed { it[54] = it[54].copy(kind = BookBlockKind.PRAYER) })
        rejected(changed { it[17] = it[17].copy(original = "Священник читает молитвы") })
        rejected(changed { it.add(0, it[17].copy(id = "second-introduction")) })
        rejected(changed { blocks -> val i = blocks.indexOfFirst { it.original == "Псалом 37" }; blocks[i] = blocks[i].copy(original = "Псалом 38") })
        rejected(changed { blocks -> val i = blocks.indexOfFirst { it.original == "Псалом 3" }; val j = blocks.indexOfFirst { it.original == "Псалом 37" };
            val first = blocks[i]; blocks[i] = blocks[j]; blocks[j] = first })
        rejected(book.copy(chapters = book.chapters + chapter.copy(id = "second-six-psalms")))
        rejected(book.copy(chapters = book.chapters.map { if (it.id != chapter.id) it else it.copy(title = "Иная глава") }))
    }

    @Test fun idsMustBeNonblankAndGloballyUniqueButIdenticalOutsideWordsStayPublic() {
        rejected(changed { it[19] = it[19].copy(id = " ") })
        rejected(changed { it[19] = it[19].copy(id = it[20].id) })
        val repeatedOutside = book.copy(chapters = book.chapters + BookChapter("outside", "Иное", "",
            listOf(group[1])))
        rejected(repeatedOutside)
        val uniqueOutside = repeatedOutside.copy(chapters = repeatedOutside.chapters.map {
            if (it.id != "outside") it else it.copy(blocks = it.blocks.map { b -> b.copy(id = "outside-quiet-words") })
        })
        val scope = BookVisibilityScope.of(uniqueOutside)
        assertEquals(ids, scope.quietGroup?.blockIds)
        assertFalse(scope.isPrivate(uniqueOutside.blocks.single { it.id == "outside-quiet-words" }))
    }

    @Test fun enrichmentAndLabelWhitespaceDoNotChangeThePrivateRoleOrAcceptedData() {
        val enriched = changed { blocks ->
            for (i in 18..53) blocks[i] = blocks[i].copy(russian = "Дополненный русский текст $i",
                translationSource = BookSource("Другой проверенный перевод", "https://example.org/translation"), meaning = "Новое пояснение $i")
            for (i in 18..53 step 3) blocks[i] = blocks[i].copy(original = "  " + blocks[i].original.replace(" ", " \n ") + "  ")
        }.copy(contentHash = "b".repeat(64), fetchedAtMillis = 54321)
        val before = gson.toJson(enriched)
        assertEquals(ids, BookVisibilityScope.of(enriched).quietGroup?.blockIds)
        assertEquals(before, gson.toJson(enriched))
    }

    @Test fun actualManualSearchKeepsEveryExactOccurrenceButPublicDuplicatesRankBeforePrivate() {
        val query = "яко ты еси бог наш"
        val hits = BookCorpusSearch.find(listOf(book), query, BookReadingMode.ORIGINAL)
        val exact = hits.filter { hit ->
            val block = book.blocks.single { it.id == hit.hit.blockId }
            val source = if (hit.hit.mode == BookReadingMode.RUSSIAN) requireNotNull(block.russian) else block.original
            BookText.normalized(source.substring(hit.hit.matchStart, hit.hit.matchEndExclusive)) == query
        }
        val scope = BookVisibilityScope.of(book)
        assertTrue(exact.size >= 3)
        assertFalse(scope.isPrivate(book.blocks.single { it.id == exact.first().hit.blockId }))
        assertEquals(2, exact.count { it.hit.blockId in ids })
        exact.forEach { assertEquals(it, it.validatedFor(book)) }
        val unclassified = book.copy(sourceUrl = book.sourceUrl + "&unverified=1")
        val before = BookSearch.scan(unclassified, query, BookReadingMode.ORIGINAL) {}.map { it.third.copy(excerpt = "") }.toSet()
        val after = BookSearch.scan(book, query, BookReadingMode.ORIGINAL) {}.map { it.third.copy(excerpt = "") }.toSet()
        assertEquals("No ID or UTF-16 match offset is changed", before, after)
        assertEquals("Псалом 87", BookSearch.find(book, "Псалом 87").single { it.excerpt.startsWith("Псалом 87") }.excerpt)
        val privatePhrase = group[1].original.split(' ').take(3).joinToString(" ")
        val publicPhrase = chapter.blocks.first { !scope.isPrivate(it) && it.kind == BookBlockKind.PRAYER &&
            BookText.normalized(it.original).split(' ').size >= 3 }.original.split(' ').take(3).joinToString(" ")
        assertEquals(publicPhrase, BookSpeechSearch.query(book, listOf(privatePhrase, publicPhrase)))
        assertEquals(query, BookSpeechSearch.query(book, listOf(query)))
    }

    @Test fun actualQuietMeaningAndRussianNoticesUseTheNewScopeWithoutRewritingAnyEdition() {
        val scope = BookVisibilityScope.of(book)
        val quiet = requireNotNull(scope.quietGroup)
        for (block in group) {
            val position = BookReadingPosition(book.key, book.contentHash, "parish", block.id, chapter.id)
            val meaning = requireNotNull(BookMeaningContent.capture(book, position))
            assertTrue(meaning.privateText)
            assertEquals(quiet.explanation, meaning.detail)
            assertNull(meaning.brief)
            assertEquals(position.forTextReading(), meaning.position)
        }
        val intro = BookReadingPosition(book.key, book.contentHash, "parish", quiet.introductionId, chapter.id)
        val card = requireNotNull(BookMeaningContent.captureQuietGroup(book, intro))
        assertEquals(quiet.title, card.chapterTitle); assertEquals(quiet.explanation, card.detail)
        assertTrue(card.privateText); assertNull(card.brief); assertNull(card.excerpt)
        val old = changed { blocks -> for (i in 18..53) blocks[i] = blocks[i].copy(russian = null, translationSource = null) }
        val next = old.copy(chapters = old.chapters.map { c -> c.copy(blocks = c.blocks.map {
            if (it.id !in ids) it else it.copy(russian = "Добавленный русский текст", translationSource = BookSource("Перевод", "https://example.org/translation"))
        }) }, contentHash = "b".repeat(64))
        assertNull(BookRussianUpdateDescriptor.create(old, BookLoadResult.Ready(next, true), true, Any(), "same-occurrence"))
    }
}
