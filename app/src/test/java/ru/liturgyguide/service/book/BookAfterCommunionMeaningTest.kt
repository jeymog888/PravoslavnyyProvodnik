package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import java.io.File

class BookAfterCommunionMeaningTest {
    private fun file(path: String) = listOf(File(path), File("../$path")).first { it.isFile }
    private val oracle by lazy { JsonParser.parseString(file("app/src/test/resources/reviewed-meanings/after-communion-oracle.json").readText()).asJsonObject }
    private fun books() = oracle["sources"].asJsonArray.map { source ->
        val row = source.asJsonObject
        val html = file(row["path"].asString).readText()
        assertEquals(row["sha256"].asString, BookText.sha256(html))
        val document = Jsoup.parse(html)
        val date = requireNotNull(document.selectFirst("#bg_worships_setDay")).attr("value")
        ServiceBookParser().parse(document, ServiceBookKey(date), 1L)
    }
    private fun position(book: ServiceBook, chapter: BookChapter, block: BookBlock, mode: BookReadingMode) =
        BookReadingPosition(book.key, book.contentHash, "parish", block.id, chapter.id,
            fraction = 1f, mode = mode, lastTextMode = mode, followingGapFraction = .3f)

    @Test fun realThreeDayClosingParagraphsUseTheirOwnSubjectAndNeverDescribeVespers() {
        assertEquals(23, oracle["rows"].asJsonArray.size())
        for (book in books()) {
            val serialized = Gson().toJson(book)
            val expected = oracle["rows"].asJsonArray.map { it.asJsonObject }.associateBy { it["normalizedSha256"].asString }
            val found = mutableSetOf<String>()
            for (chapter in book.chapters) for (block in chapter.blocks) {
                val hash = BookText.sha256(BookText.normalized(block.original))
                val row = expected[hash] ?: continue
                val result = BookAfterCommunionMeaning.forPlace(book, chapter, block) ?: continue
                found += hash
                assertEquals(row["expectedTitle"].asString, result.title)
                assertEquals(row["expectedBrief"].asBoolean, result.brief != null)
                if (row["expectedBrief"].asBoolean) assertEquals("after-communion-" + row["category"].asString, result.brief?.topicId)
                assertFalse(result.detail.contains("На вечерне"))
                assertTrue(result.detail.contains("после Причащения"))
                if (row["category"].asString == "simeon") {
                    assertEquals("Песнь Симеона", result.title)
                    assertTrue(requireNotNull(result.brief).whatHappens.contains("Симеона"))
                } else assertFalse(result.title.contains("Симеона"))
                assertNull(result.brief?.cue)
                result.brief?.let { brief ->
                    assertFalse("Expanded context must add information instead of repeating the overview", result.detail.contains(brief.whatHappens))
                    assertFalse(result.detail.contains(brief.why))
                }
            }
            assertEquals(expected.keys, found)
            assertEquals(serialized, Gson().toJson(book))
            assertTrue(ServiceBookIntegrity.valid(book))
        }
    }

    @Test fun selectedIntercessionAndSaintHymnsKeepTheirOwnMeaningAndTheExactTextCursor() {
        for (book in books()) {
            val chapter = book.chapters.last()
            val intercession = chapter.blocks.single { BookText.normalized(it.original).startsWith("богородичен предстательство христиан") }
            val troparion = chapter.blocks.single { BookText.normalized(it.original).startsWith("уст твоих якоже светлость огня") }
            for ((block, title, word) in listOf(Triple(intercession, "Молитва о заступничестве Богородицы", "Богородицу"),
                Triple(troparion, "Тропарь Иоанну Златоусту", "Иоанна Златоуста"))) {
                for (mode in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN)) {
                    val anchor = position(book, chapter, block, mode)
                    val meaning = requireNotNull(BookMeaningContent.capture(book, anchor))
                    assertEquals(anchor, meaning.position)
                    assertEquals(title, meaning.chapterTitle)
                    assertTrue(requireNotNull(meaning.brief).whatHappens.contains(word))
                    assertEquals(block.original, meaning.excerpt)
                    assertFalse(meaning.detail.contains("На вечерне"))
                }
            }
        }
    }

    @Test fun anIdenticalHeadingWithAnotherBodyCannotIdentifySimeonOrAThanksgivingSetting() {
        val book = books().first()
        val chapter = book.chapters.last()
        val unknown = chapter.blocks.first().copy(original = "Другой полный текст молитвы")
        val isolated = book.copy(chapters = listOf(chapter.copy(blocks = listOf(unknown))))
        val result = requireNotNull(BookAfterCommunionMeaning.forPlace(isolated, isolated.chapters.single(), unknown))
        assertEquals("О выбранном фрагменте", result.title)
        assertNull(result.brief)
        assertFalse(result.detail.contains("после Причащения"))
        assertFalse(result.detail.contains("вечерне"))
        val song = chapter.blocks.single { BookText.normalized(it.original).startsWith("ныне отпущаеши раба твоего") }
        val single = book.copy(chapters = listOf(chapter.copy(blocks = listOf(song))))
        val actualSong = requireNotNull(BookAfterCommunionMeaning.forPlace(single, single.chapters.single(), song))
        assertEquals("Песнь Симеона", actualSong.title)
        assertFalse(actualSong.detail.contains("после Причащения"))
    }

    @Test fun missingDuplicatedOrDisplacedPrintedContextNeverCreatesAPostCommunionSetting() {
        val book = books().first()
        val final = book.chapters.last()
        val prayer = final.blocks.single { BookText.normalized(it.original).startsWith("богородичен предстательство христиан") }
        val marker = book.chapters.single { BookText.normalized(it.title) == "благодарственные молитвы по святом причащении" }
        val markerIndex = book.chapters.indexOf(marker)
        val missing = book.copy(chapters = book.chapters.filterNot { it.id == marker.id })
        val duplicate = book.copy(chapters = book.chapters.take(markerIndex) + marker.copy(id = "other-context") + book.chapters.drop(markerIndex))
        val displaced = book.copy(chapters = book.chapters.take(markerIndex) + book.chapters.drop(markerIndex + 1) + marker)
        val unknownIntro = book.copy(chapters = book.chapters.map { if (it.id == marker.id)
            it.copy(blocks = it.blocks + it.blocks.first().copy(id = "other-intro", original = "Другая молитва")) else it })
        val wrongOrder = book.copy(chapters = book.chapters.map { if (it.id == book.chapters[markerIndex + 1].id)
            it.copy(title = "Другая глава") else it })
        for (bad in listOf(missing, duplicate, displaced, unknownIntro, wrongOrder, book.copy(key = book.key.copy(worship = WorshipKind.VESPERS)))) {
            val result = requireNotNull(BookAfterCommunionMeaning.forPlace(bad, bad.chapters.single { it.id == final.id }, prayer))
            assertEquals("О выбранном фрагменте", result.title)
            assertNull(result.brief)
        }
        val beforeChapter = book.chapters[markerIndex - 1]
        assertNull(BookAfterCommunionMeaning.forPlace(book, beforeChapter, beforeChapter.blocks.first()))
    }

    @Test fun aGenericKontakionLabelCannotBorrowTheSaintFromAnotherOrMissingFollowingHymn() {
        val book = books().first()
        val chapter = book.chapters.last()
        val label = chapter.blocks.single { BookText.normalized(it.original) == "кондак глас 6 й" }
        val index = chapter.blocks.indexOf(label)
        assertEquals(BookBlockKind.RUBRIC, label.kind)
        val verified = requireNotNull(BookAfterCommunionMeaning.forPlace(book, chapter, label))
        assertEquals("Кондак Иоанну Златоусту", verified.title)
        assertNull(verified.brief)
        for (blocks in listOf(chapter.blocks.take(index + 1), chapter.blocks.mapIndexed { i, block ->
            if (i == index + 1) block.copy(original = "Другое полное песнопение") else block })) {
            val alteredChapter = chapter.copy(blocks = blocks)
            val altered = book.copy(chapters = book.chapters.dropLast(1) + alteredChapter)
            val result = requireNotNull(BookAfterCommunionMeaning.forPlace(altered, alteredChapter, label))
            assertEquals("Молитвы после Причащения", result.title)
            assertFalse(result.detail.contains("Златоуст"))
            assertNull(result.brief)
        }
    }

    @Test fun bothLabelsRequireTheirActualRubricAndTheFollowingPublicPrayerKind() {
        val book = books().first()
        val chapter = book.chapters.last()
        val labels = chapter.blocks.filter { BookText.normalized(it.original) in setOf(
            "кондак глас 6 й", "тропарь свт иоанну златоустому глас 8 й") }
        assertEquals(2, labels.size)
        for (label in labels) {
            val index = chapter.blocks.indexOf(label)
            val next = chapter.blocks[index + 1]
            for (badNext in listOf(next.copy(isAltarText = true), next.copy(kind = BookBlockKind.RUBRIC),
                next.copy(kind = BookBlockKind.SCRIPTURE))) {
                val changedChapter = chapter.copy(blocks = chapter.blocks.map { if (it.id == next.id) badNext else it })
                val changed = book.copy(chapters = book.chapters.dropLast(1) + changedChapter)
                val result = requireNotNull(BookAfterCommunionMeaning.forPlace(changed, changedChapter, label))
                assertEquals("Молитвы после Причащения", result.title)
                assertFalse(result.detail.contains("Златоуст"))
                assertNull(result.brief)
            }
            val badLabel = label.copy(kind = BookBlockKind.PRAYER)
            val changedChapter = chapter.copy(blocks = chapter.blocks.map { if (it.id == label.id) badLabel else it })
            val changed = book.copy(chapters = book.chapters.dropLast(1) + changedChapter)
            val result = requireNotNull(BookAfterCommunionMeaning.forPlace(changed, changedChapter, badLabel))
            assertNull(result.brief)
            assertFalse(result.detail.contains("Златоуст"))
        }
    }

    @Test fun privateAndForeignOwnerParagraphsCannotBorrowPublicHelp() {
        val book = books().first()
        val chapter = book.chapters.last()
        val body = chapter.blocks.first { it.kind == BookBlockKind.PRAYER }
        assertNull(BookAfterCommunionMeaning.forPlace(book, chapter, body.copy(isAltarText = true)))
        assertNull(BookAfterCommunionMeaning.forPlace(book, chapter, body.copy(id = "foreign")))
        assertNull(BookAfterCommunionMeaning.forPlace(book, chapter.copy(id = "foreign"), body))
    }
}
