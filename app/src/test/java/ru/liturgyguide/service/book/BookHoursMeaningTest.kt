package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import java.io.File

class BookHoursMeaningTest {
    private val gson = Gson()
    private val expected = JsonParser.parseString(listOf(File("app/src/androidTest/assets/meaning-hours/prayer.json"),
        File("../app/src/androidTest/assets/meaning-hours/prayer.json")).first { it.isFile }.readText()).asJsonObject
    private val words = expected["expected"].asJsonObject
    private val raw = requireNotNull(javaClass.getResourceAsStream("/book/hour-prayer/actual-hour3-2026-11-15.json"))
        .bufferedReader().use { it.readText() }
    private val actual = gson.fromJson(raw, ServiceBook::class.java)
    private val chapterId = expected["chapterId"].asString
    private val blockId = expected["blockId"].asString
    private val chapter get() = actual.chapters.single { it.id == chapterId }
    private val target get() = chapter.blocks.single { it.id == blockId }
    private val topic = "hours-common-prayer"

    @Test fun actualUnchangedThirdHourHasSpecificTitleAndDetailWithoutNeedingItsMissingRussian() {
        assertEquals("90324859c263d2fa27397ec6e87ac39d415107a9655603e4171c550427cc3153", BookText.sha256(raw))
        assertEquals("b34617e4f9f0b3d91df6d72415ffcb814b020f7e58791f6aa4083105f3f3b4aa",
            BookText.sha256(BookText.normalized(target.original)))
        assertNull(target.russian); assertEquals("Кондак", chapter.title)
        val unchanged = gson.toJson(actual)
        for (mode in listOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN)) for (gap in listOf(0f, .23f)) {
            val position = position(actual, mode).copy(fraction = if (gap == 0f) .39f else 1f, followingGapFraction = gap)
            val content = requireNotNull(BookMeaningContent.capture(actual, position))
            assertSpecific(content, WorshipKind.HOUR3)
            assertEquals(position, content.position); assertEquals(target.original, content.excerpt)
            assertFalse(content.privateText)
            assertEquals(unchanged, gson.toJson(actual)); assertEquals("Кондак", chapter.title)
            assertEquals(expected["inheritedWrongMeaning"].asString, chapter.meaning)
        }
    }

    @Test fun completeOwnedPrayerUsesOnlyItsHourPageAndNeverTheWholeChaptersHeading() {
        for (kind in listOf(WorshipKind.HOUR1, WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.HOUR9)) {
            val book = actual.copy(key = actual.key.copy(worship = kind), chapters = listOf(chapter.copy(title = "Другая исходная глава")))
                .rehash()
            val owned = book.chapters.single()
            assertNull(BookMeaningBriefCatalog.forChapter(book, owned))
            assertEquals(topic, BookMeaningBriefCatalog.forPlace(book, owned, blockId)?.topicId)
            assertSpecific(requireNotNull(BookMeaningContent.capture(book, position(book))), kind)
        }
    }

    @Test fun exactHoursBlockPrecedesAnUnknownSimeonFragmentButHeaderAndEndKeepTheOldPath() {
        val book = actual.copy(chapters = listOf(chapter.copy(title = "Песнь Симеона Богоприимца"))).rehash()
        val owned = book.chapters.single()
        val existing = requireNotNull(BookAfterCommunionMeaning.forPlace(book, owned, target))
        assertNull(existing.brief); assertEquals("О выбранном фрагменте", existing.title)
        val before = gson.toJson(book)
        assertSpecific(requireNotNull(BookMeaningContent.capture(book, position(book))), WorshipKind.HOUR3)
        for (kind in listOf(BookAnchorKind.HEADER, BookAnchorKind.END)) {
            val position = position(book).copy(anchorKind = kind)
            val content = requireNotNull(BookMeaningContent.capture(book, position))
            assertNull(content.brief); assertEquals(existing.title, content.chapterTitle); assertEquals(existing.detail, content.detail)
            assertEquals(position, content.position)
            if (kind == BookAnchorKind.END) assertNull(content.excerpt) else assertEquals(target.original, content.excerpt)
        }
        // Same exact words outside Hours still follow the existing fragment priority.
        val other = book.copy(key = book.key.copy(worship = WorshipKind.LITURGY)).rehash()
        val content = requireNotNull(BookMeaningContent.capture(other, position(other)))
        assertNull(content.brief); assertEquals(existing.title, content.chapterTitle); assertEquals(existing.detail, content.detail)
        assertEquals(before, gson.toJson(book))
    }

    @Test fun partialChangedPrivateForeignAndAmbiguousPlacesCannotBorrowTheNewPrayerExplanation() {
        val original = target.original
        val alterations = listOf(original.substringBeforeLast(' '), "$original Иная молитва", "Чтец: $original",
            original.replace("пра́ведныя любя́й", "лю́бяй пра́ведныя"))
        alterations.forEach { changed -> absent(replace { it.copy(original = changed) }) }
        for (kind in listOf(BookBlockKind.RUBRIC, BookBlockKind.SCRIPTURE, BookBlockKind.LOCAL_VARIATION))
            absent(replace { it.copy(kind = kind) })
        absent(replace { it.copy(isAltarText = true) })
        for (kind in WorshipKind.entries.filterNot { it in setOf(WorshipKind.HOUR1, WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.HOUR9) })
            absent(actual.copy(key = actual.key.copy(worship = kind)).rehash())
        absent(actual.copy(key = actual.key.copy(date = "not-a-date")).rehash())
        val duplicateInside = chapter.copy(blocks = chapter.blocks + target)
        absent(actual.copy(chapters = actual.chapters.map { if (it.id == chapterId) duplicateInside else it }).rehash())
        absent(actual.copy(chapters = actual.chapters + chapter.copy(id = "other-owner", blocks = listOf(target))).rehash())
        absent(actual.copy(chapters = actual.chapters + chapter).rehash())
        assertNull(BookMeaningBriefCatalog.forPlace(actual, chapter.copy(id = "foreign"), blockId))
        assertNull(BookMeaningBriefCatalog.forPlace(actual, chapter.copy(title = "Foreign changed chapter"), blockId))
        assertNull(BookMeaningBriefCatalog.forPlace(actual, chapter, "missing"))
        for (kind in listOf(BookAnchorKind.HEADER, BookAnchorKind.END)) {
            val content = requireNotNull(BookMeaningContent.capture(actual, position(actual).copy(anchorKind = kind)))
            assertNull(content.brief); assertEquals("Кондак", content.chapterTitle)
            assertNotEquals(words["detail"].asString, content.detail)
            if (kind == BookAnchorKind.END) assertNull(content.excerpt)
        }
        assertNull(BookMeaningContent.capture(actual, position(actual).copy(contentHash = "0".repeat(64))))
        assertNull(BookMeaningContent.capture(actual, position(actual).copy(key = actual.key.copy(date = "2026-11-16"))))
        assertNull(BookMeaningContent.capture(actual, position(actual).copy(chapterId = "foreign")))
        assertNull(BookMeaningContent.capture(actual, position(actual).copy(blockId = "foreign")))
    }

    private fun assertSpecific(content: BookMeaningContent, kind: WorshipKind) {
        val brief = requireNotNull(content.brief)
        assertEquals(topic, brief.topicId); assertEquals(words["title"].asString, content.chapterTitle)
        assertEquals(words["title"].asString, brief.title); assertEquals(words["detail"].asString, brief.detail)
        assertEquals(words["detail"].asString, content.detail)
        assertEquals(words["whatHappens"].asString, brief.whatHappens); assertEquals(words["why"].asString, brief.why)
        assertEquals(BookMeaningCueKind.HEAR, brief.cue?.kind); assertEquals(words["cue"].asJsonObject["text"].asString, brief.cue?.text)
        assertEquals(words["sourceByWorship"].asJsonObject[kind.name].asString, brief.sourceUrl)
        assertFalse(content.detail.contains("песнопения о празднике"))
    }
    private fun absent(book: ServiceBook) {
        val own = book.chapters.first { it.id == chapterId }
        assertNotEquals(topic, BookMeaningBriefCatalog.forPlace(book, own, blockId)?.topicId)
        BookMeaningContent.capture(book, position(book))?.let {
            assertNotEquals(topic, it.brief?.topicId); assertNotEquals(words["title"].asString, it.chapterTitle)
            assertNotEquals(words["detail"].asString, it.detail)
        }
    }
    private fun replace(transform: (BookBlock) -> BookBlock) = actual.copy(chapters = actual.chapters.map { c ->
        c.copy(blocks = c.blocks.map { if (it.id == blockId) transform(it) else it }) }).rehash()
    private fun position(book: ServiceBook, mode: BookReadingMode = BookReadingMode.ORIGINAL) = BookReadingPosition(
        book.key, book.contentHash, "meaning-hours", blockId, chapterId, fraction = .39f,
        mode = mode, lastTextMode = mode, wordsPerMinute = 129)
    private fun ServiceBook.rehash() = copy(contentHash = ServiceBookIntegrity.contentHash(this))
}
