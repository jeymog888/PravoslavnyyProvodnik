package ru.liturgyguide.service.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

/** Public captured pages, with mutations explicitly confined to test memory. */
internal object MatinsRankFixtures {
    val dates = listOf("2026-11-13", "2026-11-18")
    fun key(date: String = dates.first()) = ServiceBookKey(date, WorshipKind.MATINS, "34", topLevel = "3")
    fun text(name: String): String = requireNotNull(javaClass.classLoader?.getResource("matins-rank/$name")).readText()
    fun document(date: String = dates.first(), ranked: Boolean = false): Document =
        Jsoup.parse(text("${if (ranked) "rank3" else "default"}-$date.html"))
    fun url(key: ServiceBookKey, main: String = "0") =
        "https://azbyka.ru/worships/?date=${key.date}&worship=matins&size=full&church=${key.churchType}&main_event=$main&top_level=3"
    fun selected(document: Document, id: String): Element = requireNotNull(document.selectFirst("#$id option[selected]"))
    fun body(document: Document): Element = requireNotNull(document.selectFirst(".article-single-content.main-page-content"))
    fun parse(date: String = dates.first()): ServiceBook {
        val key = key(date)
        return ServiceBookParser().parse(document(date, true), key, 1_794_489_600_000,
            rankRequest = MatinsRankSource.resolve(document(date), key))
    }
    fun rehash(book: ServiceBook) = book.copy(contentHash = ServiceBookIntegrity.contentHash(book))

    fun clearSection(document: Document, title: String) {
        val body = body(document)
        val heading = body.select("h1,h2,h3").single { BookText.normalized(it.text()) == title }
        val removal = mutableListOf<Node>()
        var next = heading.nextSibling()
        while (next != null) {
            // Vеличание is inside a div sibling; do not erase that following section too.
            if (next is Element && (next.tagName() in setOf("h1", "h2", "h3") ||
                next.selectFirst("h1,h2,h3") != null)) break
            removal += next
            next = next.nextSibling()
        }
        check(removal.any { it is Element && it.text().isNotBlank() }) { "Fixture section must contain text" }
        removal.forEach(Node::remove)
        check(heading.parent() != null)
    }
}

class MatinsRankSourceTest {
    private val parser = ServiceBookParser()
    private val key = MatinsRankFixtures.key()
    private val controls = listOf("bg_worship_type", "bg_worship_size", "bg_worships_church", "bg_worships_mainEvent")

    @Test fun bothRealDatesResolveTheSelectedMemoryAndContainTheActualPolyeleosAndGospel() {
        for (date in MatinsRankFixtures.dates) {
            val key = MatinsRankFixtures.key(date)
            val before = MatinsRankFixtures.document(date)
            val after = MatinsRankFixtures.document(date, true)
            val request = MatinsRankSource.resolve(before, key)
            assertEquals("0", request.mainEvent)
            assertEquals(key, request.key)
            assertEquals(MatinsRankFixtures.url(key), request.url)
            assertEquals(BookRankSelection("0", "", "3"), MatinsRankSource.validate(after, key, request))
            val book = parser.parse(after, key, 12345, rankRequest = request)
            assertEquals(key, book.key)
            assertEquals(request.url, book.sourceUrl)
            assertEquals(BookRankSelection("0", "", "3"), book.rankSelection)
            assertTrue(book.sources.any { it.url == request.url })
            assertTrue(ServiceBookIntegrity.valid(book))
            assertTrue(book.chapters.single { BookText.normalized(it.title) == "полиелей" }
                .blocks.any { it.kind == BookBlockKind.PRAYER && it.original.length > 20 })
            assertTrue(book.chapters.single { BookText.normalized(it.title) == "чтение евангелия" }
                .blocks.any { it.kind == BookBlockKind.SCRIPTURE && it.original.length > 100 && it.scriptureReferences.isNotEmpty() })
            assertTrue(book.chapters.single { BookText.normalized(it.title) == "славословие великое" }
                .blocks.any { it.kind == BookBlockKind.PRAYER })
            assertFalse(book.selectedEventTitle.orEmpty().contains("Епима"))
            assertTrue(book.chapters.any { BookText.normalized(it.title) == "отпуст" })
        }
        // Disabled browser controls did not prevent the independently captured successful response.
        assertEquals("disabled", MatinsRankFixtures.selected(MatinsRankFixtures.document(), "bg_worships_mainEvent").attr("data-toplevel"))
    }

    @Test fun aSyntheticNonzeroDefaultIdIsResolvedRatherThanHardcoded() {
        val before = MatinsRankFixtures.document()
        val after = MatinsRankFixtures.document(ranked = true)
        MatinsRankFixtures.selected(before, "bg_worships_mainEvent").attr("value", "273")
        MatinsRankFixtures.selected(after, "bg_worships_mainEvent").attr("value", "273")
        val request = MatinsRankSource.resolve(before, key)
        assertEquals("273", request.mainEvent)
        assertEquals(MatinsRankFixtures.url(key, "273"), request.url)
        val book = parser.parse(after, key, 12345, rankRequest = request)
        assertEquals("273", book.rankSelection?.mainEvent)
        assertEquals(key, book.key)
        assertTrue(ServiceBookIntegrity.valid(book))
    }

    @Test fun newProtocolRequiresOneControlAndAnExplicitUniqueSelection() {
        for (ranked in listOf(false, true)) {
            val ids = controls + "bg_worships_secondEvent"
            for (id in ids) {
                val mutations: List<Pair<String, (Document) -> Unit>> = listOf(
                    "missing control" to { d -> d.select("#$id").remove() },
                    "duplicate control" to { d -> val control = requireNotNull(d.selectFirst("#$id")); control.after(control.clone()) },
                    "no selected option" to { d -> d.select("#$id option").removeAttr("selected") },
                    "selected option without value" to { d -> MatinsRankFixtures.selected(d, id).removeAttr("value") },
                    "wrong control element" to { d -> requireNotNull(d.selectFirst("#$id")).tagName("div") },
                    "selected option in foreign wrapper" to { d ->
                        val option = MatinsRankFixtures.selected(d, id)
                        option.wrap("<template></template>")
                    },
                    "two selected options" to { d ->
                        val control = requireNotNull(d.selectFirst("#$id"))
                        control.appendChild(MatinsRankFixtures.selected(d, id).clone())
                    }
                )
                for ((label, mutation) in mutations) {
                    val document = MatinsRankFixtures.document(ranked = ranked)
                    mutation(document)
                    assertThrows("ranked=$ranked $id $label", InvalidServiceBook::class.java) { validate(document, ranked) }
                }
            }
            for (mutation in listOf<(Document) -> Unit>(
                { it.select("#bg_worships_setDay").remove() },
                { d -> val control = requireNotNull(d.selectFirst("#bg_worships_setDay")); control.after(control.clone()) },
                { it.select("#bg_worships_setDay").attr("value", "2026-11-14") },
                { it.select("#bg_worships_setDay").attr("type", "text") }
            )) {
                val document = MatinsRankFixtures.document(ranked = ranked); mutation(document)
                assertThrows("ranked=$ranked date control", InvalidServiceBook::class.java) { validate(document, ranked) }
            }
        }
    }

    @Test fun wrongSelectedValuesCannotBeReplacedByAnUnselectedCorrectOption() {
        for (ranked in listOf(false, true)) {
            for ((id, value) in listOf("bg_worship_type" to "vespers", "bg_worship_size" to "short", "bg_worships_church" to "30")) {
                val document = MatinsRankFixtures.document(ranked = ranked)
                val selected = MatinsRankFixtures.selected(document, id)
                selected.after(selected.clone().removeAttr("selected"))
                selected.attr("value", value)
                assertThrows("ranked=$ranked $id", InvalidServiceBook::class.java) { validate(document, ranked) }
            }
        }
        for (value in listOf("", "x", "-1", "1234567", "0&top_level=3")) {
            val before = MatinsRankFixtures.document()
            MatinsRankFixtures.selected(before, "bg_worships_mainEvent").attr("value", value)
            assertThrows("main=$value", InvalidServiceBook::class.java) { MatinsRankSource.resolve(before, key) }
        }
        for (value in listOf("x", "-1", "1234567")) {
            val before = MatinsRankFixtures.document()
            MatinsRankFixtures.selected(before, "bg_worships_secondEvent").attr("value", value)
            assertThrows("second=$value", InvalidServiceBook::class.java) { MatinsRankSource.resolve(before, key) }
        }
        for ((id, attribute, value) in listOf(
            Triple("bg_worships_mainEvent", "value", "1"),
            Triple("bg_worships_mainEvent", "data-level", "6"),
            Triple("bg_worships_secondEvent", "value", "1")
        )) {
            val after = MatinsRankFixtures.document(ranked = true)
            MatinsRankFixtures.selected(after, id).attr(attribute, value)
            assertThrows("$id $attribute=$value", InvalidServiceBook::class.java) { validate(after, true) }
        }
    }

    @Test fun higherFeastsAreNotDowngradedAndUnknownLevelsAreNotAssumed() {
        for (level in listOf("1", "2")) {
            val document = MatinsRankFixtures.document()
            MatinsRankFixtures.selected(document, "bg_worships_mainEvent").attr("data-level", level)
            assertThrows("level=$level", UnsupportedMatinsRank::class.java) { MatinsRankSource.resolve(document, key) }
        }
        for (level in listOf("", "0", "7", "3.0", "unknown")) {
            val document = MatinsRankFixtures.document()
            MatinsRankFixtures.selected(document, "bg_worships_mainEvent").attr("data-level", level)
            assertThrows("level=$level", InvalidServiceBook::class.java) { MatinsRankSource.resolve(document, key) }
        }
        // These are protocol boundary mutations, not a claim of live level3/4/5 coverage.
        for (level in listOf("3", "4", "5", "6")) {
            val document = MatinsRankFixtures.document()
            MatinsRankFixtures.selected(document, "bg_worships_mainEvent").attr("data-level", level)
            assertEquals("0", MatinsRankSource.resolve(document, key).mainEvent)
        }
    }

    @Test fun menuLinksAndRankControlsCannotStandInForTheRealText() {
        val daily = MatinsRankFixtures.document()
        MatinsRankFixtures.selected(daily, "bg_worships_mainEvent").attr("data-level", "3")
        daily.select("#bg_worships_secondEvent option").removeAttr("selected")
        requireNotNull(daily.selectFirst("#bg_worships_secondEvent option[value='']")).attr("selected", "selected")
        assertFalse(daily.select(".worship_menu a[href*=polielej]").isEmpty())
        assertThrows(InvalidServiceBook::class.java) { parse(daily) }

        for (title in listOf("полиелей", "славословие великое")) {
            val document = MatinsRankFixtures.document(ranked = true)
            MatinsRankFixtures.clearSection(document, title)
            assertTrue(MatinsRankFixtures.body(document).select("h1,h2,h3").any { BookText.normalized(it.text()) == title })
            assertFalse(document.select(".worship_menu a[href*=polielej]").isEmpty())
            assertThrows(title, InvalidServiceBook::class.java) { parse(document) }
        }
    }

    @Test fun gospelHeadingAndAcclamationsWithoutTheActualReadingAreRejected() {
        for (date in MatinsRankFixtures.dates) {
            val document = MatinsRankFixtures.document(date, true)
            val body = MatinsRankFixtures.body(document)
            assertEquals("Fixture precondition: only the actual Gospel is removed", 1, body.select(".bg_bibrefs_service").size)
            body.select(".bg_bibrefs_service").remove()
            assertTrue(body.select("h1,h2,h3").any { BookText.normalized(it.text()) == "чтение евангелия" })
            assertTrue(BookText.normalized(body.text()).contains("услышим святаго евангелия"))
            assertThrows(date, InvalidServiceBook::class.java) {
                parser.parse(document, MatinsRankFixtures.key(date), 12345,
                    rankRequest = MatinsRankSource.resolve(MatinsRankFixtures.document(date), MatinsRankFixtures.key(date)))
            }
        }
    }

    @Test fun resolutionCannotBeOmittedOrBorrowedFromAnotherDateOrIntent() {
        val document = MatinsRankFixtures.document(ranked = true)
        assertThrows(InvalidServiceBook::class.java) { parser.parse(document, key, 12345) }
        val otherKey = MatinsRankFixtures.key("2026-11-18")
        val other = MatinsRankSource.resolve(MatinsRankFixtures.document("2026-11-18"), otherKey)
        assertThrows(InvalidServiceBook::class.java) { parser.parse(document, key, 12345, rankRequest = other) }
        val request = MatinsRankSource.resolve(MatinsRankFixtures.document(), key)
        assertThrows(InvalidServiceBook::class.java) { parser.parse(MatinsRankFixtures.document(), key.copy(topLevel = null), 12345, rankRequest = request) }
    }

    private fun validate(document: Document, ranked: Boolean) {
        if (ranked) MatinsRankSource.validate(document, key, MatinsRankSource.resolve(MatinsRankFixtures.document(), key))
        else MatinsRankSource.resolve(document, key)
    }

    private fun parse(document: Document) = parser.parse(document, key, 12345,
        rankRequest = MatinsRankSource.resolve(MatinsRankFixtures.document(), key))
}
