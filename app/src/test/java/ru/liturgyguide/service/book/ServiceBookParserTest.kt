package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

internal object BookFixtures {
    fun html(name: String = "liturgy-2026-11-11"): String = requireNotNull(javaClass.classLoader?.getResource("book/$name.html")).readText()
    fun book(): ServiceBook = ServiceBookParser().parse(Jsoup.parse(html()), ServiceBookKey("2026-11-11"), 1_794_348_000_000)
}

class ServiceBookParserTest {
    private val parser = ServiceBookParser()
    private val key = ServiceBookKey("2026-11-11")

    @Test fun actualMalformedHtmlIncludesCommunionDismissalAndThanksgiving() {
        val book = BookFixtures.book()
        val text = BookText.normalized(book.blocks.joinToString(" ") { it.original })
        assertTrue(text.contains("благословено царство"))
        assertTrue(text.contains("святая святым"))
        assertTrue(book.chapters.any { BookText.normalized(it.title) == "отпуст" })
        assertTrue(book.chapters.any { BookText.normalized(it.title).contains("благодарственные молитвы") })
        assertTrue(book.blocks.size > 200)
        assertTrue(book.blocks.any { it.isAltarText })
        assertTrue(book.blocks.any { it.kind == BookBlockKind.SCRIPTURE && "1Thes.4:1-12" in it.scriptureReferences })
        assertTrue(ServiceBookIntegrity.valid(book))
        assertEquals(book.blocks.size, book.blocks.map { it.id }.toSet().size)
    }

    @Test fun altarPrayersDoNotDisappearAndAreDistinguishedFromAudibleText() {
        val book = BookFixtures.book()
        assertTrue(book.blocks.any { it.isAltarText && BookText.normalized(it.original).contains("достойно") })
        assertTrue(book.blocks.any { !it.isAltarText && BookText.normalized(it.original).startsWith("миром господу") })
    }

    @Test fun localTempleGapIsVisibleAndNeverInvented() {
        val book = BookFixtures.book()
        assertTrue(book.blocks.any { it.kind == BookBlockKind.LOCAL_VARIATION })
        assertTrue(book.notices.any { it.contains("не указан") })
        assertFalse(book.blocks.any { it.original.contains("Укажите в настройках") })
    }

    @Test fun differentRealKindsAreDifferentBooks() {
        val kinds = listOf("vespers" to WorshipKind.VESPERS, "matins" to WorshipKind.MATINS, "hour1" to WorshipKind.HOUR1)
        for ((name, kind) in kinds) {
            val result = parser.parse(Jsoup.parse(BookFixtures.html("$name-2026-11-12")), ServiceBookKey("2026-11-12", kind), 12345)
            assertEquals(kind, result.key.worship)
            assertTrue(ServiceBookIntegrity.valid(result))
            assertTrue(result.blocks.size >= 15)
        }
    }

    private val completeFixtures = listOf(
        Triple("liturgy-2026-11-11", "2026-11-11", WorshipKind.LITURGY),
        Triple("liturgy-2026-11-15", "2026-11-15", WorshipKind.LITURGY),
        Triple("liturgy-2026-04-12", "2026-04-12", WorshipKind.LITURGY),
        Triple("vespers-2026-11-12", "2026-11-12", WorshipKind.VESPERS),
        Triple("vespers-2026-11-15", "2026-11-15", WorshipKind.VESPERS),
        Triple("matins-2026-11-12", "2026-11-12", WorshipKind.MATINS),
        Triple("matins-2026-11-15", "2026-11-15", WorshipKind.MATINS),
        Triple("matins-2026-04-12", "2026-04-12", WorshipKind.MATINS),
        Triple("hour1-2026-11-12", "2026-11-12", WorshipKind.HOUR1),
        Triple("hour1-2026-11-15", "2026-11-15", WorshipKind.HOUR1),
        Triple("chas3-2026-11-12", "2026-11-12", WorshipKind.HOUR3),
        Triple("chas6-2026-11-12", "2026-11-12", WorshipKind.HOUR6),
        Triple("chas9-2026-11-12", "2026-11-12", WorshipKind.HOUR9)
    )

    @Test fun completeWeekdaySundayAndPaschalOrdersRemainAccepted() {
        for ((name, date, kind) in completeFixtures) {
            val book = try { parser.parse(Jsoup.parse(BookFixtures.html(name)), ServiceBookKey(date, kind), 12345) }
                catch (failure: InvalidServiceBook) { throw AssertionError("Complete fixture $name was rejected", failure) }
            assertTrue(name, ServiceBookIntegrity.valid(book))
        }
    }

    @Test fun sundayFirstHourHasCompleteSmallDismissalWithoutManyYears() {
        val book = parser.parse(Jsoup.parse(BookFixtures.html("hour1-2026-11-15")),
            ServiceBookKey("2026-11-15", WorshipKind.HOUR1), 12345)
        assertTrue(ServiceBookIntegrity.valid(book))
        assertFalse(book.chapters.any { BookText.normalized(it.title) == "многолетие" })
        assertEquals("господи помилуй трижды", BookText.normalized(book.blocks.last().original))
        assertTrue(book.blocks.any { BookText.normalized(it.original).startsWith("воскресый из мертвых христос истинный бог наш") })
    }

    @Test fun finalThreefoldResponseDoesNotCoverMissingFirstHourDismissal() {
        val document = Jsoup.parse(BookFixtures.html("hour1-2026-11-15"))
        val body = requireNotNull(document.selectFirst(".article-single-content.main-page-content"))
        var removed = false
        fun removeDismissal(node: Node) {
            if (node is TextNode && BookText.normalized(node.text()).startsWith("воскресый из мертвых христос истинный бог наш")) {
                node.text("")
                removed = true
            } else node.childNodes().forEach(::removeDismissal)
        }
        removeDismissal(body)
        assertTrue(removed)
        assertTrue(BookText.normalized(body.text()).endsWith("лик господи помилуй трижды"))
        assertThrows(InvalidServiceBook::class.java) {
            parser.parse(document, ServiceBookKey("2026-11-15", WorshipKind.HOUR1), 12345)
        }
    }

    @Test fun paschalMidnightResponseIsNeverAcceptedAsVespers() {
        val document = Jsoup.parse(BookFixtures.html("vespers-2026-04-12"))
        assertThrows(InvalidServiceBook::class.java) {
            parser.parse(document, ServiceBookKey("2026-04-12", WorshipKind.VESPERS), 12345)
        }
    }

    @Test fun actualTitleRejectsAnotherOrderEvenWhenSelectorMatchesTheRequest() {
        val document = Jsoup.parse(BookFixtures.html("vespers-2026-04-12"))
        val option = requireNotNull(document.selectFirst("#bg_worship_type option[value=midnight]"))
        option.attr("value", "vespers").attr("selected", "selected")
        val failure = assertThrows(InvalidServiceBook::class.java) {
            parser.parse(document, ServiceBookKey("2026-04-12", WorshipKind.VESPERS), 12345)
        }
        assertTrue(failure.message.orEmpty().contains("Пасхальная полунощница"))
    }

    @Test fun realBooksCutBeforeTheirFinalPrayerAreRejected() {
        for ((name, date, kind) in completeFixtures) {
            for (fraction in listOf(0.61, 0.84, 0.90, 0.98)) {
                val document = Jsoup.parse(BookFixtures.html(name))
                val body = requireNotNull(document.selectFirst(".article-single-content.main-page-content"))
                body.select(".worship_menu,.bg_data_tooltip,script,style,button,input,select,iframe,nav").remove()
                val nodes = mutableListOf<TextNode>()
                fun collect(node: Node) {
                    if (node is TextNode) nodes += node else node.childNodes().forEach(::collect)
                }
                collect(body)
                var remaining = (nodes.sumOf { it.wholeText.length } * fraction).toInt()
                for (node in nodes) {
                    val value = node.wholeText
                    node.text(value.take(remaining.coerceAtLeast(0)))
                    remaining -= value.length
                }
                assertThrows("$name truncated at $fraction", InvalidServiceBook::class.java) {
                    parser.parse(document, ServiceBookKey(date, kind), 12345)
                }
            }
        }
    }

    @Test fun removingLastContentParagraphCannotMasqueradeAsACompleteService() {
        for ((name, date, kind) in completeFixtures) {
            val document = Jsoup.parse(BookFixtures.html(name))
            val body = requireNotNull(document.selectFirst(".article-single-content.main-page-content"))
            body.select("p").last { it.text().isNotBlank() }.remove()
            assertThrows(name, InvalidServiceBook::class.java) {
                parser.parse(document, ServiceBookKey(date, kind), 12345)
            }
        }
    }

    @Test fun hymnAfterInlineLabelIsPrayerAndNeverMasqueradesAsRussianRubric() {
        for (kind in listOf(WorshipKind.VESPERS, WorshipKind.MATINS)) {
            val book = parser.parse(Jsoup.parse(BookFixtures.html("${kind.queryValue}-2026-11-12")), ServiceBookKey("2026-11-12", kind), 12345)
            val hymns = book.blocks.filter { "мученицы твои господи" in BookText.normalized(it.original) }
            assertTrue(hymns.isNotEmpty())
            assertTrue(hymns.all { it.kind == BookBlockKind.PRAYER && it.russian == null })
            assertTrue(book.blocks.any { it.kind == BookBlockKind.RUBRIC && "тропарь" in BookText.normalized(it.original) && "зиновия" in BookText.normalized(it.original) })
            assertFalse(hymns.any { "тропарь сщмч" in BookText.normalized(it.original) })
        }
    }

    @Test fun anotherDateIsRejected() {
        assertThrows(InvalidServiceBook::class.java) { parser.parse(Jsoup.parse(BookFixtures.html()), key.copy(date = "2026-11-12"), 12345) }
    }

    @Test fun anotherKindIsRejected() {
        assertThrows(InvalidServiceBook::class.java) { parser.parse(Jsoup.parse(BookFixtures.html()), key.copy(worship = WorshipKind.VESPERS), 12345) }
    }

    @Test fun croppedLiturgyCannotReplaceFullBook() {
        val document = Jsoup.parse(BookFixtures.html())
        val body = requireNotNull(document.selectFirst(".article-single-content.main-page-content"))
        body.html("<h3>Сугубая ектения</h3><p>" + "Господи, помилуй. ".repeat(1200) + "</p>")
        assertThrows(InvalidServiceBook::class.java) { parser.parse(document, key, 12345) }
    }

    @Test fun refreshTimeDoesNotChangeContentAddress() {
        val a = parser.parse(Jsoup.parse(BookFixtures.html()), key, 12345)
        val b = parser.parse(Jsoup.parse(BookFixtures.html()), key, 54321)
        assertEquals(a.contentHash, b.contentHash)
        assertEquals(a.blocks.map { it.id }, b.blocks.map { it.id })
    }
}
