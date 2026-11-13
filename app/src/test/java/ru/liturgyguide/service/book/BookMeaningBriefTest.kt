package ru.liturgyguide.service.book

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import ru.liturgyguide.model.*

class BookMeaningBriefTest {
    @Test fun fiveReviewedBodiesHaveBriefsAndOnlyActualAudiblePhrasesBecomeCues() {
        val cases = listOf(
            fixture("Ектения великая", "Ми́ром Го́споду помо́лимся", "О свышнем мире и спасении душ наших", "О мире всего мира") to "great-litany",
            fixture("Херувимская песнь", "И́же Херуви́мы та́йно образу́юще и животворящей Троице трисвятую песнь припевающе") to "cherubic-ordinary",
            fixture("Святое Возношение", "Станем добре, станем со страхом", "Твоя от Твоих Тебе приносяще", "Тебе поем, Тебе благословим") to "anaphora-ordinary",
            fixture("Псалом 103", "Благослови, душе моя, Господа. Господи Боже мой, возвеличился еси зело. Вся премудростию сотворил еси",
                worship = WorshipKind.VESPERS) to "vespers-psalm-103",
            sixPsalms() to "matins-six-psalms"
        )
        for ((book, topic) in cases) {
            val brief = requireNotNull(BookMeaningBriefCatalog.forChapter(book, book.chapters.single()))
            assertEquals(topic, brief.topicId)
            assertFalse(brief.whatHappens.isBlank())
            assertFalse(brief.why.isBlank())
            brief.cue?.let { cue ->
                assertEquals(BookMeaningCueKind.HEAR, cue.kind)
                assertTrue(book.blocks.any { BookText.normalized(it.original).contains(BookText.normalized(cue.text)) })
            }
        }
    }

    @Test fun aTitleAndAbsenceOfKnownSubstitutesCannotApproveAnOrdinaryCherubicBrief() {
        val wrong = fixture("Херувимская песнь", "Неизвестное местное песнопение")
        assertNull(brief(wrong))
        val ordinary = fixture("Херувимская песнь", "Иже Херувимы тайно образующе")
        for (replacement in listOf("Ныне Силы небесныя", "Да молчит всякая плоть", "Вечери Твоея тайныя днесь")) {
            assertNull(brief(ordinary.copy(chapters = listOf(ordinary.chapters.single().copy(blocks = ordinary.blocks +
                BookBlock("replacement", BookBlockKind.PRAYER, null, replacement))))))
        }
        for (title in listOf("Литургия Преждеосвященных Даров", "Акафист", "Литургия особой местной редакции"))
            assertNull(brief(ordinary.copy(title = title)))
        assertNull(brief(ordinary.copy(key = ordinary.key.copy(worship = WorshipKind.MATINS))))
        assertNull(brief(ordinary.copy(chapters = listOf(ordinary.chapters.single().copy(title = "Вместо Херувимской песни")))))
        val privateBody = ordinary.copy(chapters = listOf(ordinary.chapters.single().copy(
            blocks = ordinary.blocks.map { it.copy(isAltarText = true) })))
        assertNull(brief(privateBody))
    }

    @Test fun lookalikeLitaniesCanonsPsalmsAndMissingBodiesUseTheExistingFullHelp() {
        for ((title, body) in listOf("Ектения великая" to "Господи, помилуй", "Анафора" to "Тебе поем",
            "Канон" to "Станем добре. Твоя от Твоих. Тебе поем", "Псалом 103" to "Отче наш",
            "Шестопсалмие" to "Господи, услыши молитву мою")) assertNull(brief(fixture(title, body)))
        val psalm = fixture("Псалом 103", "Благослови душе моя Господа Господи Боже мой. Вся премудростию сотворил еси")
        assertNull(brief(psalm)) // The same words in a Liturgy book are not the reviewed Vespers context.
        assertNull(brief(sixPsalms().let { book -> book.copy(chapters = listOf(book.chapters.single().copy(
            blocks = book.blocks.filterNot { it.original == "Псалом 87" }))) }))
    }

    @Test fun anaphoraRequiresAnOrdinaryBookAndItsOriginalPrayerRatherThanAnAkathistOrLocalHeading() {
        val ordinary = fixture("Евхаристический канон", "Станем добре", "Твоя от Твоих", "Тебе поем")
        assertNotNull(brief(ordinary))
        for (title in listOf("Акафист", "Литургия Преждеосвященных Даров", "Литургия местного чина"))
            assertNull(brief(ordinary.copy(title = title)))
        assertNull(brief(ordinary.copy(key = ordinary.key.copy(worship = WorshipKind.MATINS))))
        assertNull(brief(ordinary.copy(chapters = listOf(ordinary.chapters.single().copy(
            blocks = ordinary.blocks.dropLast(1))))))
    }

    @Test fun captureKeepsEditionLanguageGapAndExactTextWhileUnknownHelpIsNotInvented() {
        val book = fixture("Неизвестная часть", "Слова молитвы")
        val before = Gson().toJson(book)
        val position = position(book).copy(mode = BookReadingMode.MEANING, lastTextMode = BookReadingMode.RUSSIAN,
            fraction = 1f, followingGapFraction = .4f)
        val content = requireNotNull(BookMeaningContent.capture(book, position))
        assertEquals(position.copy(mode = BookReadingMode.RUSSIAN), content.position)
        assertEquals("Перевод: Слова молитвы", content.excerpt)
        assertNull(content.brief)
        assertEquals("Сохраненное пояснение этой части", content.detail)
        assertEquals(before, Gson().toJson(book))
        assertNull(BookMeaningContent.capture(book, position.copy(contentHash = "0".repeat(64))))
        assertNull(BookMeaningContent.capture(book, position.copy(chapterId = "other")))
        assertNull(BookMeaningContent.capture(book, position.copy(blockId = "other")))
        assertEquals(position.copy(mode = BookReadingMode.ORIGINAL, lastTextMode = BookReadingMode.ORIGINAL),
            position.copy(lastTextMode = null).forTextReading())
    }

    @Test fun aPrivateParagraphNeverInheritsItsPublicChaptersAudibleCue() {
        val book = fixture("Херувимская песнь", "Иже Херувимы тайно образующе").let { original ->
            original.copy(chapters = listOf(original.chapters.single().copy(blocks = original.blocks +
                BookBlock("private", BookBlockKind.PRAYER, null, "Тихая молитва", isAltarText = true))))
        }
        assertNotNull(brief(book))
        val content = requireNotNull(BookMeaningContent.capture(book, position(book).copy(blockId = "private", showAltar = true)))
        assertTrue(content.privateText)
        assertNull(content.brief)
        assertTrue(content.detail.contains("тихо"))
    }

    @Test fun acceptedRealQuietMatinsKeepsItsBytesAndDistinguishesThePrivatePrayer() {
        val bytes = requireNotNull(javaClass.classLoader?.getResource("legacy-presentation/matins-accepted-unchanged.json")).readText()
        val book = Gson().fromJson(bytes, ServiceBook::class.java)
        val serializedBefore = Gson().toJson(book)
        val acceptedHash = book.contentHash
        val chapter = book.chapters.single { it.title == "Шестопсалмие" }
        assertEquals("matins-six-psalms", BookMeaningBriefCatalog.forChapter(book, chapter)?.topicId)
        val group = requireNotNull(BookVisibilityScope.of(book).quietGroup)
        val private = chapter.blocks.first { it.id in group.blockIds && it.kind == BookBlockKind.PRAYER }
        val content = requireNotNull(BookMeaningContent.capture(book, BookReadingPosition(book.key, book.contentHash,
            "parish", private.id, chapter.id, fraction = .34f, mode = BookReadingMode.RUSSIAN, showAltar = true)))
        assertTrue(content.privateText)
        assertNull(content.brief)
        assertEquals(group.explanation, content.detail)
        assertNotEquals(private.meaning, content.detail)
        assertEquals(.34f, content.position.fraction, 0f)
        val cardPosition = content.position.copy(blockId = group.introductionId, fraction = 0f, showAltar = false)
        val card = requireNotNull(BookMeaningContent.captureQuietGroup(book, cardPosition))
        assertEquals(cardPosition, card.position)
        assertEquals(group.title, card.chapterTitle)
        assertEquals(group.explanation, card.detail)
        assertNull(card.excerpt)
        assertNull(card.brief)
        assertNull(BookMeaningContent.captureQuietGroup(book, content.position))
        assertEquals("c1a622cb4fad548172bf44179d0c34433cff89a6e7b377fb8a8d14b3adbe7ca1", BookText.sha256(bytes))
        // Preserve the accepted Android edition, rather than re-hash its serialization on another runtime.
        assertEquals("bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", acceptedHash)
        assertEquals(acceptedHash, book.contentHash)
        assertEquals(serializedBefore, Gson().toJson(book))
    }

    @Test fun realLiturgyBodiesAndTheNestedVespersPsalmReceiveOnlyTheirOwnScopedHelp() {
        fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResource(name)).readText()
        val liturgy = ServiceBookParser().parse(Jsoup.parse(resource("reviewed-meanings/liturgy-2026-11-14-church30.html")),
            ServiceBookKey("2026-11-14"), 1)
        for ((title, topic) in listOf("Ектения великая" to "great-litany", "Херувимская песнь" to "cherubic-ordinary",
            "Святое Возношение" to "anaphora-ordinary")) {
            val chapter = liturgy.chapters.single { it.title == title }
            assertEquals(topic, BookMeaningBriefCatalog.forChapter(liturgy, chapter)?.topicId)
        }
        val vespers = ServiceBookParser().parse(Jsoup.parse(resource("book/vespers-2026-11-12.html")),
            ServiceBookKey("2026-11-12", WorshipKind.VESPERS), 1)
        val chapter = vespers.chapters.first()
        assertEquals("Перед началом", chapter.title)
        val label = chapter.blocks.single { BookText.normalized(it.original) == "псалом 103" }
        val body = chapter.blocks[chapter.blocks.indexOf(label) + 1]
        assertEquals("vespers-psalm-103", BookMeaningBriefCatalog.forPlace(vespers, chapter, body.id)?.topicId)
        assertEquals("vespers-psalm-103", BookMeaningBriefCatalog.forPlace(vespers, chapter, label.id)?.topicId)
        assertNull(BookMeaningBriefCatalog.forPlace(vespers, chapter, chapter.blocks.first().id))
    }

    private fun brief(book: ServiceBook) = BookMeaningBriefCatalog.forChapter(book, book.chapters.single())
    private fun position(book: ServiceBook) = BookReadingPosition(book.key, book.contentHash, "parish",
        book.blocks.first().id, book.chapters.first().id, fraction = .43f)
    private fun fixture(title: String, vararg bodies: String, worship: WorshipKind = WorshipKind.LITURGY): ServiceBook {
        val key = ServiceBookKey("2026-11-14", worship)
        val blocks = bodies.mapIndexed { index, text -> BookBlock("block-$index", BookBlockKind.PRAYER, null, text, "Перевод: $text") }
        val book = ServiceBook(key = key, title = worship.title, sourceUrl = key.sourceUrl(), sourceTitle = "Источник",
            fetchedAtMillis = 1, selectedEventTitle = null, chapters = listOf(BookChapter("chapter", title,
                "Сохраненное пояснение этой части", blocks)), contentHash = "")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }
    private fun sixPsalms(): ServiceBook = fixture("Шестопсалмие", "Господи что ся умножиша стужающии ми",
        "Господи услыши молитву мою", worship = WorshipKind.MATINS).let { book ->
        book.copy(chapters = listOf(book.chapters.single().copy(blocks = book.blocks + listOf(3, 37, 62, 87, 102, 142).map {
            BookBlock("psalm-$it", BookBlockKind.RUBRIC, null, "Псалом $it") })))
    }
}
