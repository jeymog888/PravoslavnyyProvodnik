package ru.liturgyguide.service.book

import com.google.gson.Gson
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

/** Current orientation is read-only help, not a replacement source edition or a ritual requirement. */
class BookMeaningClarityTest {
    private val gson = Gson()
    private val sources = listOf(
        "reviewed-meanings/liturgy-2026-11-14-church30.html" to "2026-11-14",
        "book/liturgy-2026-11-15.html" to "2026-11-15")

    @Test fun theRealCherubicBriefExplainsTheAltarTableBeforeDetailsAreOpened() {
        for ((path, date) in sources) {
            val book = parse(path, date)
            val chapter = book.chapters.single { it.title == "Херувимская песнь" }
            val brief = requireNotNull(BookMeaningBriefCatalog.forChapter(book, chapter))
            assertEquals("cherubic-ordinary", brief.topicId)
            assertTrue(brief.whatHappens.contains("престол - главный стол в алтаре"))
            assertTrue(brief.sourceUrl.endsWith("/offertory-great-entrance"))
        }
    }

    @Test fun newAndPreviouslyAcceptedRealCommunionChaptersOfferTheSameOptionalOrientationWithoutChangingTheBook() {
        for ((path, date) in sources) {
            val fresh = parse(path, date)
            val chapter = fresh.chapters.single { it.title == "Причащение народа в храме" }
            assertTrue(chapter.meaning.contains(OPTIONAL))
            val old = fresh.copy(chapters = fresh.chapters.map {
                if (it.id == chapter.id) it.copy(meaning = STORED) else it
            }, contentHash = "").let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
            // A serialization round-trip represents a saved older edition; its source text,
            // Russian fragments and accepted hash are never rewritten to update this help.
            val accepted = gson.fromJson(gson.toJson(old), ServiceBook::class.java)
            val before = gson.toJson(accepted)
            val acceptedChapter = accepted.chapters.single { it.id == chapter.id }
            val block = acceptedChapter.blocks.first { it.kind == BookBlockKind.PRAYER && !it.isAltarText }
            val position = BookReadingPosition(accepted.key, accepted.contentHash, "parish", block.id,
                acceptedChapter.id, fraction = .37f, followingGapFraction = .21f,
                mode = BookReadingMode.MEANING, lastTextMode = BookReadingMode.RUSSIAN)
            val content = requireNotNull(BookMeaningContent.capture(accepted, position))
            assertEquals(chapter.meaning, content.detail)
            assertTrue(content.detail.contains(OPTIONAL))
            assertTrue(content.detail.contains("подготовившиеся к Причащению"))
            assertTrue(content.detail.contains("можно заранее уточнить у священника"))
            assertEquals(position.copy(mode = BookReadingMode.RUSSIAN), content.position)
            assertEquals(acceptedChapter.title, content.chapterTitle)
            assertEquals(STORED, acceptedChapter.meaning)
            assertEquals(before, gson.toJson(accepted))
            assertEquals(old.contentHash, accepted.contentHash)
        }
    }

    @Test fun onlyExactPublicCommunionAliasesWithTheReviewedInvitationUpdateAcceptedHelp() {
        for (title in listOf("Причащение народа", "Причащение народа в храме", "Причащение мирян", "Причаще́ние наро́да")) {
            val (book, chapter) = fixture(title, "Со стра́хом Бо́жиим и ве́рою приступи́те.")
            assertTrue(BookMeaningCatalog.displayMeaning(book, chapter).contains(OPTIONAL))
        }
        for (title in listOf("Литургия", "Литургия Иоанна Златоуста", "Литургия святителя Иоанна Златоуста",
            "Литургия Василия Великого", "Литургия святителя Василия Великого")) {
            val (book, chapter) = fixture("Причащение народа", INVITATION, bookTitle = title)
            assertTrue(BookMeaningCatalog.displayMeaning(book, chapter).contains(OPTIONAL))
        }
    }

    @Test fun similarHeadingsMissingBodiesAndOtherRitesKeepTheirAcceptedHelp() {
        for (title in listOf("Молитва перед Причащением народа", "Причащение священнослужителей", "Причащение народа местного чина")) {
            val (book, chapter) = fixture(title, INVITATION)
            assertEquals(STORED, BookMeaningCatalog.displayMeaning(book, chapter))
        }
        for (body in listOf("", "Местное описание", "Со страхом Божиим")) {
            val (book, chapter) = fixture("Причащение народа", body)
            assertEquals(STORED, BookMeaningCatalog.displayMeaning(book, chapter))
        }
        for (title in listOf("Литургия Преждеосвященных Даров", "Акафист", "Литургия особого местного чина")) {
            val (book, chapter) = fixture("Причащение народа", INVITATION, bookTitle = title)
            assertEquals(STORED, BookMeaningCatalog.displayMeaning(book, chapter))
        }
        val (book, chapter) = fixture("Причащение народа", INVITATION)
        for (worship in listOf(WorshipKind.MATINS, WorshipKind.VESPERS, WorshipKind.HOUR3)) {
            assertEquals(STORED, BookMeaningCatalog.displayMeaning(book.copy(key = book.key.copy(worship = worship)), chapter))
        }
    }

    @Test fun aPrivateParagraphStillGetsPrivateHelpInsteadOfThePublicCommunionAdvice() {
        val (base, chapter) = fixture("Причащение народа", INVITATION)
        val privateChapter = chapter.copy(blocks = chapter.blocks.map { it.copy(isAltarText = true) })
        val book = base.copy(chapters = listOf(privateChapter), contentHash = "").let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it))
        }
        val position = BookReadingPosition(book.key, book.contentHash, "parish", privateChapter.blocks.single().id,
            privateChapter.id, mode = BookReadingMode.ORIGINAL, showAltar = true)
        val content = requireNotNull(BookMeaningContent.capture(book, position))
        assertTrue(content.privateText)
        assertNull(content.brief)
        assertFalse(content.detail.contains(OPTIONAL))
        assertTrue(content.detail.contains("тихо"))
    }

    private fun parse(path: String, date: String): ServiceBook = ServiceBookParser().parse(
        Jsoup.parse(requireNotNull(javaClass.classLoader?.getResource(path)).readText()), ServiceBookKey(date), 1)

    private fun fixture(title: String, body: String, bookTitle: String = "Литургия Иоанна Златоуста"): Pair<ServiceBook, BookChapter> {
        val chapter = BookChapter("chapter", title, STORED,
            listOf(BookBlock("block", BookBlockKind.PRAYER, null, body, "Сохраненный русский текст")))
        val key = ServiceBookKey("2026-11-14")
        val book = ServiceBook(key = key, title = bookTitle, sourceUrl = key.sourceUrl(), sourceTitle = "Источник",
            fetchedAtMillis = 1, selectedEventTitle = null, chapters = listOf(chapter), contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)) to chapter
    }

    private companion object {
        const val STORED = "Сохраненное пояснение этой редакции"
        const val INVITATION = "Со страхом Божиим и верою приступите"
        const val OPTIONAL = "Если сегодня не причащаетесь, можно остаться на месте и молиться вместе со всеми"
    }
}
