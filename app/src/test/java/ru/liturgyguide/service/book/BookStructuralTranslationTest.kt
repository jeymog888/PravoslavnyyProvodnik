package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBookKey

class BookStructuralTranslationTest {
    private val source = BookTranslationIndex.TIMROT_FULL_SOURCE
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/book/translation-structural-originals.html"))
        .bufferedReader().use { Jsoup.parse(it.readText(), source.url) }

    @Test fun publishedSpeakerAliasesKeepTheEntirePrayerAndItsSource() {
        val document = fixture()
        val index = BookTranslationIndex.fromParallelPage(document, source)
        for (row in document.select("tr").take(3)) {
            val original = row.children()[0].text().substringAfter(':').trim()
            val expected = row.children()[1].text().substringAfter(':').trim()
            assertEquals(expected, index.find(original)?.russian)
            assertEquals(source, index.find(original)?.source)
            assertNull(index.find(original.replaceFirst(" ", " не ")))
        }
        assertEquals("Хор", BookText.withoutSpeaker("Людие: Отче наш").first)
        assertEquals("Диакон", BookText.withoutSpeaker("Таже диакон: Помолися о мне").first)
        assertEquals("Диакон", BookText.withoutSpeaker("И паки диакон: Помяни мя").first)
        assertNull(BookText.withoutSpeaker("Людие Твои: спаси их").first)
        assertNull(BookText.withoutSpeaker("Неизвестный диакон: Помолися о мне").first)
        assertNull(index.find("Отче наш, Иже еси на небесех"))
    }

    @Test fun exactPairedPetitionIsSeparatedFromThePrecedingChoralResponse() {
        val document = fixture()
        val index = BookTranslationIndex.fromParallelPage(document, source)
        for (row in document.select("tr").drop(3)) {
            val original = row.children()[0].select("p").first { it.hasText() }.text()
            val expected = row.children()[1].select("p").first { it.hasText() }.text()
            assertEquals(expected, index.find(original)?.russian)
            assertEquals(source, index.find(original)?.source)
            assertFalse(index.find(original)!!.russian.startsWith("Господи, помилуй"))
            assertNull(index.find(original.replaceFirst("О ", "Не о ").replaceFirst("Прощения", "Осуждения")))
        }
    }

    @Test fun onlySymmetricResponseAndParagraphBoundariesMayProduceAPetitionPair() {
        val original = "О благорастворении воздухов, Господу помолимся."
        val left = "<span>Лик:</span> Господи, помилуй.<p>$original</p>"
        val right = "<span>Хор:</span> Господи, помилуй.<p>Тестовый перевод.</p>"
        fun parse(a: String, b: String) = BookTranslationIndex.fromParallelPage(
            Jsoup.parse("<table><tr><td>$a</td><td>$b</td></tr></table>"), source)
        assertEquals("Тестовый перевод.", parse(left, right).find(original)?.russian)
        for (changed in listOf(
            right.replace("Хор:", "Диакон:"),
            right.replace("Господи, помилуй.", "Подай, Господи."),
            "<p>Тестовый перевод.</p><span>Хор:</span> Господи, помилуй.",
            right + "<p>Другая молитва.</p>",
            right + "Другая молитва.",
            right.replace("<p>", "<div>").replace("</p>", "</div>"),
            right.replace("<p>", "<p>Диакон: ")
        )) assertNull(changed, parse(left, changed).find(original))
        // Two unlabeled sentences in a cell still do not authorize splitting by punctuation.
        assertNull(parse("Лик: Господи, помилуй. $original", "Хор: Господи, помилуй. Тестовый перевод.").find(original))
    }

    @Test fun changedSpeakerOrderDoesNotCreateCrossedReplyPairs() {
        val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("""
            <table><tr>
            <td>Людие: Ответ народа. Таже диакон: Просьба диакона.</td>
            <td>Диакон: Тестовая просьба. Хор: Тестовый ответ.</td>
            </tr></table>
        """), source)
        assertNull(index.find("Ответ народа."))
        assertNull(index.find("Просьба диакона."))
    }

    @Test fun verifiedRussianDirectionsDoNotShowAFalseMissingTranslation() {
        val directions = listOf(
            "Диакон же, поклонившись, отходит от своего места и стоит перед иконой Спасителя, держа орарь тремя перстами правой руки.",
            "Здесь открываются Царские врата на малый вход.",
            "Затем диакон подает священнику для целования св. Евангелие.",
            "И, сотворив поклон, входит в св. алтарь, а за ним и священник. Диакон кладет св. Евангелие на св. престол.",
            "Также диакон обводит орарем стоящих во храме, возглашая к ним громогласно:",
            "Во время пения священник и диакон и сами читают Трисвятое, совершая и три поклона перед св. престолом.",
            "Во время пения прокимна и чтения Апостола диакон кадит престол, алтарь, служащих, а также иконостас и весь народ.",
            "И подает ему святое Евангелие."
        )
        val document = Jsoup.parse(BookFixtures.html())
        val content = document.selectFirst(".article-single-content.main-page-content")!!.prependElement("div")
        directions.forEach { content.appendElement("p").appendElement("em").text(it) }
        val mixed = "Здесь открываются Царские врата на малый вход. Господи, помилуй."
        val negative = "Здесь не открываются Царские врата на малый вход."
        content.appendElement("p").text(mixed)
        content.appendElement("p").text(negative)
        val book = ServiceBookParser().parse(document, ServiceBookKey("2026-11-11"), 1L)
        directions.forEach { text ->
            val block = book.blocks.last { it.original == text }
            assertEquals(text, BookBlockKind.RUBRIC, block.kind)
            assertEquals(text, block.russian)
        }
        for (text in listOf(mixed, negative)) {
            val block = book.blocks.last { it.original == text }
            assertEquals(text, BookBlockKind.PRAYER, block.kind)
            assertNull(block.russian)
        }
    }
}
