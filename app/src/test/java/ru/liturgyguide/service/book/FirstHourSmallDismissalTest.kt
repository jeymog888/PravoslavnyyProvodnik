package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind

/** Exact public host responses, not a replay of Native85's unrecorded book requests. */
class FirstHourSmallDismissalTest {
    private fun document(date: String): Document = Jsoup.parse(requireNotNull(javaClass.classLoader
        ?.getResource("book/first-hour-dismissal/$date-chas1.html")).readText())
    private fun parse(document: Document, date: String = "2026-11-21", kind: WorshipKind = WorshipKind.HOUR1) =
        ServiceBookParser().parse(document, ServiceBookKey(date, kind), 12345)
    private fun refusal(document: Document, date: String = "2026-11-21", kind: WorshipKind = WorshipKind.HOUR1) {
        try { parse(document, date, kind); fail("Incomplete or mismatched source must be rejected") }
        catch (_: InvalidServiceBook) { }
    }
    private fun dismissal(document: Document): TextNode {
        val found = mutableListOf<TextNode>()
        fun visit(node: Node) {
            if (node is TextNode && BookText.normalized(node.text()).startsWith("христос истинный бог наш")) found += node
            node.childNodes().forEach(::visit)
        }
        visit(requireNotNull(document.selectFirst(".article-single-content.main-page-content")))
        return found.single()
    }

    @Test fun exactWeekdayFeastAndSundaySourcesHaveTheirOwnCompleteEndings() {
        for (date in listOf("2026-11-20", "2026-11-21", "2026-11-22")) {
            val book = parse(document(date), date)
            assertEquals(ServiceBookKey(date, WorshipKind.HOUR1), book.key)
            assertTrue(date, ServiceBookIntegrity.valid(book))
            val text = BookText.normalized(book.blocks.joinToString(" ") { it.original })
            assertTrue(date, if (date == "2026-11-20") text.endsWith("господи сохрани их на многая лета")
                else text.endsWith("господи помилуй трижды"))
            if (date == "2026-11-21") {
                assertFalse(text.contains("воскресый из мертвых христос истинный бог наш"))
                assertTrue(text.contains("христос истинный бог наш молитвами пречистыя своея матере"))
            }
        }
    }

    @Test fun aFinalResponseCannotReplaceTheFullDismissalOrItsInternalWords() {
        for (variant in listOf("missing", "internal", "ending")) {
            val source = document("2026-11-21")
            val node = dismissal(source)
            val normalized = BookText.normalized(node.text())
            node.text(when (variant) {
                "missing" -> ""
                "internal" -> normalized.replace("преподобных и богоносных отец наших", "")
                else -> normalized.replace("яко благ и человеколюбец", "")
            })
            refusal(source)
        }
    }

    @Test fun missingOrAlteredFinalResponseAndExtraEndingRemainInvalid() {
        for (variant in listOf("missing", "twice", "extra", "marker", "notMarker", "pseudoMarker")) {
            val source = document("2026-11-21")
            val body = requireNotNull(source.selectFirst(".article-single-content.main-page-content"))
            val finalResponse = body.select("p").last { BookText.normalized(it.text()) == "лик господи помилуй трижды" }
            when (variant) {
                "missing" -> finalResponse.remove()
                "twice" -> finalResponse.text("Лик: Господи, помилуй (Дважды).")
                "marker" -> body.select("h3").single { BookText.normalized(it.text()) == "отпуст" }.text("Окончание")
                "notMarker" -> body.select("h3").single { BookText.normalized(it.text()) == "отпуст" }.text("Неотпуст")
                "pseudoMarker" -> body.select("h3").single { BookText.normalized(it.text()) == "отпуст" }.text("Псевдоотпуст")
                else -> body.appendElement("p").text("Неизвестное продолжение службы")
            }
            refusal(source)
        }
    }

    @Test fun completeSmallDismissalDoesNotAuthorizeAnotherDateOrWorship() {
        refusal(document("2026-11-21"), "2026-11-22")
        refusal(document("2026-11-21"), kind = WorshipKind.HOUR3)
    }
}
