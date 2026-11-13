package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

/** Genuine accepted/public source texts; this does not establish a parish's performed order. */
class BookVisibilityScopeTest {
    private val gson = Gson()
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResource(name)).readText()
    private val accepted by lazy { gson.fromJson(resource("legacy-presentation/matins-accepted-unchanged.json"), ServiceBook::class.java) }
    private val published by lazy { ServiceBookParser().parse(Jsoup.parse(resource("quiet-matins/matins-2026-11-14-church30.html")),
        ServiceBookKey("2026-11-14", WorshipKind.MATINS, "30"), 12345) }

    @Test fun bothRealDatesKeepAllSixPsalmsAndRemoveExactlyTheParallelTwelvePrayersFromDefaultReading() {
        val provenance = JsonParser.parseString(resource("quiet-matins/provenance.json")).asJsonObject
        val html = resource("quiet-matins/matins-2026-11-14-church30.html")
        assertEquals(provenance["sha256"].asString, BookText.sha256(html))
        assertEquals(provenance["bytes"].asInt, html.toByteArray(Charsets.UTF_8).size)
        for (book in listOf(accepted, published)) {
            val json = gson.toJson(book)
            val scope = BookVisibilityScope.of(book)
            val group = requireNotNull(scope.quietGroup)
            val chapter = book.chapters.single { it.id == group.chapterId }
            assertEquals(36, group.blockIds.size)
            assertEquals(24, chapter.blocks.count { scope.isQuiet(it.id) && it.kind == BookBlockKind.PRAYER })
            assertEquals(1278, chapter.blocks.filter { scope.isQuiet(it.id) && it.kind == BookBlockKind.PRAYER }
                .sumOf { BookAutoScroll.wordCount(it.original) })
            val public = chapter.blocks.filter { scope.isVisible(it) }
            assertEquals(listOf("псалом 3", "псалом 37", "псалом 62", "псалом 87", "псалом 102", "псалом 142"),
                public.map { BookText.normalized(it.original) }.filter { Regex("псалом [0-9]+").matches(it) })
            val introduction = public.indexOfFirst { it.id == group.introductionId }
            assertEquals("Псалом 87", public[introduction + 1].original)
            assertTrue(chapter.blocks.filter { scope.isQuiet(it.id) }.none { scope.isVisible(it) })
            assertTrue(chapter.blocks.filter { scope.isQuiet(it.id) }.all { scope.isVisible(it, showQuiet = true) })
            assertEquals(json, gson.toJson(book))
        }
    }

    @Test fun everyBrokenContextOrPartialShapeLeavesTheEntireGroupPublic() {
        val group = requireNotNull(BookVisibilityScope.of(accepted).quietGroup)
        val chapter = accepted.chapters.single { it.id == group.chapterId }
        val start = chapter.blocks.indexOfFirst { it.id in group.blockIds }
        fun changed(edit: (MutableList<BookBlock>) -> Unit) = accepted.copy(chapters = accepted.chapters.map {
            if (it.id != chapter.id) it else it.copy(blocks = it.blocks.toMutableList().also(edit))
        })
        val cases = listOf(
            accepted.copy(key = accepted.key.copy(worship = WorshipKind.LITURGY)),
            accepted.copy(chapters = accepted.chapters + chapter.copy(id = "duplicate-six-psalms")),
            accepted.copy(chapters = accepted.chapters.map { if (it.id == chapter.id) it.copy(title = "Другие молитвы") else it }),
            changed { it.removeAt(start) },
            changed { it.removeAt(start + 1) },
            changed { it.add(start + 1, it[start + 1].copy(id = "duplicate-prayer")) },
            changed { it[start + 3] = it[start + 3].copy(original = "Молитва первая:") },
            changed { it[start + 1] = it[start + 1].copy(original = "  ") },
            changed { it[start + 2] = it[start + 2].copy(kind = BookBlockKind.SCRIPTURE) },
            changed { it[start - 1] = it[start - 1].copy(original = "Священник читает молитвы") },
            changed { it[start + 36] = it[start + 36].copy(original = "Псалом 88") },
            changed { blocks -> val index = blocks.indexOfFirst { it.original == "Псалом 37" }; blocks[index] = blocks[index].copy(original = "Псалом 38") },
            changed { blocks -> val first = blocks.indexOfFirst { it.original == "Псалом 3" }; val second = blocks.indexOfFirst { it.original == "Псалом 37" };
                val temp = blocks[first]; blocks[first] = blocks[second]; blocks[second] = temp }
        )
        for ((index, book) in cases.withIndex()) {
            val scope = BookVisibilityScope.of(book)
            assertNull("Negative context $index must not partly hide prayers", scope.quietGroup)
            assertTrue(book.blocks.filter { it.id in group.blockIds }.all { scope.isVisible(it) })
        }
    }

    @Test fun whitespaceIsAllowedButIdenticalWordsOutsideTheExactGroupStayPublic() {
        val original = requireNotNull(BookVisibilityScope.of(accepted).quietGroup)
        val book = accepted.copy(chapters = accepted.chapters.map { chapter -> if (chapter.id != original.chapterId) chapter
            else chapter.copy(blocks = chapter.blocks.map { block ->
                if (block.kind == BookBlockKind.RUBRIC) block.copy(original = "  " + block.original.replace(" ", " \n ") + "  ") else block
            }) } + BookChapter("outside", "Другие молитвы", "", listOf(accepted.blocks.first { it.id in original.blockIds && it.kind == BookBlockKind.PRAYER }.copy(id = "outside-prayer"))))
        val scope = BookVisibilityScope.of(book)
        assertEquals(original.blockIds, scope.quietGroup?.blockIds)
        assertFalse(scope.isPrivate(book.blocks.single { it.id == "outside-prayer" }))
    }

    @Test fun manualSearchKeepsQuietPrayersButPublicExcerptsCannotBorrowTheirWords() {
        val scope = BookVisibilityScope.of(accepted)
        val group = requireNotNull(scope.quietGroup)
        val prayer = accepted.blocks.first { it.id in group.blockIds && it.kind == BookBlockKind.PRAYER }
        val query = prayer.original.split(' ').take(7).joinToString(" ")
        assertTrue(BookSearch.find(accepted, query).any { it.blockId == prayer.id })
        val label = BookSearch.find(accepted, "Псалом 87").single { it.excerpt.startsWith("Псалом 87") }
        assertEquals("Псалом 87", label.excerpt)
        val privatePhrase = prayer.original.split(' ').take(3).joinToString(" ")
        val chapter = accepted.chapters.single { it.id == group.chapterId }
        val publicPhrase = chapter.blocks.first { scope.isVisible(it) && it.kind == BookBlockKind.PRAYER &&
            BookText.normalized(it.original).split(' ').size >= 3 }.original.split(' ').take(3).joinToString(" ")
        assertEquals(publicPhrase, BookSpeechSearch.query(accepted, listOf(privatePhrase, publicPhrase)))
    }

    @Test fun oneRecognizedPhraseRanksTheGenuinePublicExclamationBeforeBothQuietDuplicatesWithoutDroppingAny() {
        val query = "яко ты еси бог наш"
        assertEquals(query, BookSpeechSearch.query(accepted, listOf(query)))
        val expected = listOf("matins-ed716e9f74f9-1:8c8318bb48324ed9:1",
            "matins-c3d8dcfa7a5c-1:56b201f15e38b7e9:1", "matins-c3d8dcfa7a5c-1:56b201f15e38b7e9:2")
        val hits = BookCorpusSearch.find(listOf(accepted), query, BookReadingMode.ORIGINAL)
        assertEquals(expected, hits.take(3).map { it.hit.blockId })
        val scope = BookVisibilityScope.of(accepted)
        assertFalse(scope.isPrivate(accepted.blocks.single { it.id == hits.first().hit.blockId }))
        for (hit in hits.take(3)) {
            assertEquals(accepted.key, hit.bookKey)
            assertEquals(accepted.contentHash, hit.contentHash)
            assertEquals(hit, hit.validatedFor(accepted))
            val original = accepted.blocks.single { it.id == hit.hit.blockId }.original
            assertEquals(BookText.normalized(query), BookText.normalized(original.substring(hit.hit.matchStart, hit.hit.matchEndExclusive)))
        }
        val sourceOrder = BookSearch.scan(accepted, query, BookReadingMode.ORIGINAL) {}
        assertEquals(expected.toSet(), sourceOrder.filter { it.first == BookTextMatchKind.PHRASE }.map { it.third.blockId }.toSet())
        assertEquals("Only ordering changes: all manual hits and UTF-16 offsets remain identical",
            sourceOrder.map { it.third }.toSet(), hits.map { it.hit }.toSet())
        val qualities = sourceOrder.associate { it.third.blockId to it.first.ordinal }
        assertEquals(hits.map { qualities.getValue(it.hit.blockId) }.sorted(), hits.map { qualities.getValue(it.hit.blockId) })
    }
}
