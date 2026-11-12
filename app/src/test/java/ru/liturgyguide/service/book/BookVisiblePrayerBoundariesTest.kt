package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

class BookVisiblePrayerBoundariesTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/book/$name"))
        .bufferedReader().use { it.readText() }
    private val evidence = JsonParser.parseString(resource("translation-visible-boundaries-provenance.json")).asJsonObject
    private fun JsonObject.text(key: String) = get(key).asString
    private fun source(edition: String) = if (edition == "full") BookTranslationIndex.TIMROT_FULL_SOURCE else BookTranslationIndex.TIMROT_SOURCE
    private fun document(edition: String) = Jsoup.parse(resource("translation-visible-boundaries-$edition.html"), source(edition).url)
    private fun index(edition: String) = BookTranslationIndex.fromParallelPage(document(edition), source(edition))
    private fun row(edition: String, number: Int) = requireNotNull(document(edition).selectFirst("tr[data-source-row=$number]"))
    private fun rowIndex(row: Element) = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${row.outerHtml()}</table>"), source("full"))
    private fun body(row: Element) = BookText.clean(row.children()[0].clone().apply { select("span.k").first()!!.remove() }.text())
    private val combined get() = index("full").plus(index("short"))
    private val documents get() = evidence.getAsJsonArray("documents").map { it.asJsonObject }
    private fun structural(chapterId: String, block: BookBlock) = listOf(chapterId, block.id, block.kind.name,
        block.speaker ?: "~", block.isAltarText.toString(), block.original, block.scriptureReferences.joinToString(",")).joinToString("\u001f")

    @Test fun twelveVisibleDirectionsSplitWithoutChangingAnyOtherIdWordChapterOrScopeOnFourBooks() {
        val oldPairs = BookTranslationIndex.decode(evidence.getAsJsonArray("legacyPairs").toString())
        val translations = BookTranslationIndex.decode(oldPairs.fillingMissing(combined).encode())
        assertEquals(4, documents.size)
        for (item in documents) {
            val html = resource(item.text("fixture"))
            assertEquals(item.text("fixtureSha256"), BookText.sha256(html))
            val key = ServiceBookKey(item.text("date"), churchType = item.text("churchType"))
            assertEquals(item.text("sourceUrl"), key.sourceUrl())
            val book = ServiceBookParser().parse(Jsoup.parse(html), key, 12345, translations)
            val splits = item.getAsJsonArray("splits").map { it.asJsonObject }
            assertEquals(12, splits.size)
            assertEquals(item.get("baselineBlocks").asInt + 12, book.blocks.size)
            assertEquals(item.text("fullOriginalSha256"), BookText.sha256(book.blocks.joinToString(" ") { it.original }))
            assertEquals(book.blocks.size, book.blocks.map { it.id }.toSet().size)
            assertEquals(item.getAsJsonArray("chapters").map { it.asJsonObject.text("id") }, book.chapters.map { it.id })
            assertEquals(item.getAsJsonArray("chapters").map { it.asJsonObject.text("title") }, book.chapters.map { it.title })
            val newIds = splits.flatMap { listOf(it.text("rubricId"), it.text("prayerId")) }.toSet()
            val unchanged = book.chapters.flatMap { chapter -> chapter.blocks.filter { it.id !in newIds }
                .map { structural(chapter.id, it) } }
            assertEquals(item.get("baselineBlocks").asInt - 12, unchanged.size)
            assertEquals("Every unaffected ID, duplicate counter, original, role, kind and scope", item.text("unaffectedDigest"),
                BookText.sha256(unchanged.joinToString("\n")))
            for (split in splits) {
                assertFalse(book.blocks.any { it.id == split.text("oldId") })
                val chapter = book.chapters.single { it.id == split.text("chapterId") }
                val position = chapter.blocks.indexOfFirst { it.id == split.text("rubricId") }
                assertTrue(position >= 0 && position + 1 < chapter.blocks.size)
                val rubric = chapter.blocks[position]
                val prayer = chapter.blocks[position + 1]
                assertEquals(BookBlockKind.RUBRIC, rubric.kind)
                assertEquals(split.text("prefix"), rubric.original)
                assertEquals(rubric.original, rubric.russian)
                assertEquals(rubric.original, rubric.meaning)
                assertNull(rubric.translationSource)
                assertEquals(split.text("prayerId"), prayer.id)
                assertEquals(BookBlockKind.PRAYER, prayer.kind)
                assertEquals(split.text("prayer"), prayer.original)
                assertFalse(rubric.isAltarText || prayer.isAltarText)
                assertEquals(translations.find(prayer.original)?.russian, prayer.russian)
                assertEquals(translations.find(prayer.original)?.source, prayer.translationSource)
                assertTrue(requireNotNull(prayer.russian).startsWith("Тестовый русский текст"))
            }
            assertTrue(ServiceBookIntegrity.valid(book))
        }
    }

    @Test fun parserDirectionsDoNotExpandTheGenericBilingualSplitter() {
        val prefixes = evidence.getAsJsonArray("parserPrefixes").map { it.asString }
        assertEquals(7, prefixes.size)
        for (prefix in prefixes) {
            assertNull(BookPrayerMetadata.split("$prefix Господи, помилуй.").rubric)
            assertEquals(prefix, BookPrayerMetadata.splitLiturgyParagraph("$prefix Господи, помилуй.").rubric)
            assertEquals("Господи, помилуй.", BookPrayerMetadata.splitLiturgyParagraph("$prefix Господи, помилуй.").prayer)
            assertNull(BookPrayerMetadata.splitLiturgyParagraph(prefix).rubric)
            assertNull(BookPrayerMetadata.splitLiturgyParagraph("Неведомое указание: $prefix Господи, помилуй.").rubric)
            assertNull(BookPrayerMetadata.splitLiturgyParagraph("Когда произносится $prefix Господи, помилуй.").rubric)
        }
    }

    @Test fun newDatedDirectionsDoNotChangeTheOtherServiceParser() {
        val document = Jsoup.parse(resource("vespers-2026-11-12.html"))
        val mixed = "И священник возглашает: Господи, помилуй."
        document.selectFirst(".article-single-content.main-page-content p")!!.text(mixed)
        val book = ServiceBookParser().parse(document, ServiceBookKey("2026-11-12", worship = WorshipKind.VESPERS), 12345)
        assertEquals(1, book.blocks.count { it.original == mixed })
    }

    @Test fun theVisibleBoundaryGroupDoesNotReclassifyAltarInstructions() {
        val document = Jsoup.parse(resource("liturgy-2026-11-12.html"))
        val mixed = "И священник возглашает: Господи, помилуй."
        document.selectFirst(".article-single-content.main-page-content details p")!!.text(mixed)
        val book = ServiceBookParser().parse(document, ServiceBookKey("2026-11-12"), 12345)
        assertTrue(book.blocks.single { it.original == mixed }.isAltarText)
    }

    @Test fun bothExactPublishedDirectionsAreRequiredAndOldWholeCellValuesRemainAvailable() {
        for (item in evidence.getAsJsonArray("sources").map { it.asJsonObject }) {
            val edition = item.text("edition")
            assertEquals(item.text("fixtureSha256"), BookText.sha256(resource(item.text("fixture"))))
            for (number in if (edition == "full") listOf(409, 470) else listOf(371)) {
                val row = row(edition, number)
                val cells = row.children()
                assertEquals(BookText.clean(cells[1].text()), index(edition).find(cells[0].text())?.russian)
                assertEquals("Тестовый русский текст $edition-$number-0.", index(edition).find(body(row))?.russian)
                assertEquals(source(edition), index(edition).find(body(row))?.source)
            }
        }
        val prayer = body(row("full", 470))
        for (merged in listOf(index("full").plus(index("short")), index("short").plus(index("full"))))
            assertEquals("Тестовый русский текст full-470-0.", BookTranslationIndex.decode(merged.encode()).find(prayer)?.russian)
    }

    @Test fun wrongUnknownOneSidedAndReversedRolesCannotCreateABodyPair() {
        for (number in listOf(409, 470)) {
            val prayer = body(row("full", number))
            val mutations: List<Pair<String, (Element) -> Unit>> = listOf(
                "wrong role" to { it.children()[1].selectFirst("span.k")!!.text("Священник:") },
                "unknown role" to { it.children()[1].selectFirst("span.k")!!.text("Неизвестный служитель:") },
                "missing right direction" to { it.children()[1].selectFirst("span.k")!!.remove() },
                "missing left direction" to { it.children()[0].selectFirst("span.k")!!.remove() },
                "empty right direction" to { it.children()[1].selectFirst("span.k")!!.text("") },
                "empty left direction" to { it.children()[0].selectFirst("span.k")!!.text("") },
                "different known pair" to { it.children()[1].selectFirst("span.k")!!.text(if (number == 409) "Хор же поет:" else "Священник же, вознося Святой Хлеб, возглашает:") },
                "reversed columns" to { val left = it.children()[0]; left.remove(); it.appendChild(left) }
            )
            for ((name, mutate) in mutations) {
                val changed = row("full", number).also(mutate)
                assertNull("$number $name", rowIndex(changed).find(prayer))
            }
        }
    }

    @Test fun ordinarySpeakerPrefixesCannotHideAOneSidedDirectionFromTheStrictGuard() {
        val speakers = listOf("Лик", "1 Лик", "2 Хор", "Хор", "Людие", "Диакон", "Таже Диакон",
            "И паки Диакон", "И снова Диакон", "Священник", "Иерей", "Чтец", "Народ", "Предстоятель")
        for (number in listOf(409, 470)) {
            val prayer = body(row("full", number))
            for (speaker in speakers) for (count in 1..3) for (directionSide in 0..1) {
                val changed = row("full", number)
                changed.children()[1 - directionSide].selectFirst("span.k")!!.remove()
                changed.children()[directionSide].prependText("$speaker: ".repeat(count))
                assertNull("row $number, $speaker x$count, direction side $directionSide", rowIndex(changed).find(prayer))
            }
        }
    }

    @Test fun earlierLabelsAndRepliesCannotExposeTheDirectionThroughAnotherFallback() {
        val earlier = listOf("Возглас: ", "Возвышая голос: ", "Священник молится: ",
            "Неизвестная роль: ", "Хор: Аминь. Священник: ", "Хор: Иерей: Возглас: ")
        for (number in listOf(409, 470)) {
            val prayer = body(row("full", number))
            for (prefix in earlier) for (directionSide in 0..1) {
                val changed = row("full", number)
                changed.children()[1 - directionSide].selectFirst("span.k")!!.remove()
                changed.children()[directionSide].prependText(prefix)
                assertNull("row $number, prefix $prefix, direction side $directionSide", rowIndex(changed).find(prayer))
            }
            val both = row("full", number)
            both.children().forEach { it.prependText("Хор: Аминь. Священник: ") }
            assertNull("Both columns have an earlier reply", rowIndex(both).find(prayer))
            val matchingSegments = row("full", number)
            matchingSegments.children()[0].selectFirst("span.k")!!.remove()
            matchingSegments.children().forEach { it.prependText("Хор: Аминь. Священник: ") }
            assertNull("The second matching speaker segment cannot bypass the DOM guard", rowIndex(matchingSegments).find(prayer))
        }
    }

    @Test fun extraMarkupRepliesOrContentBeforeTheDirectionCannotBeDiscarded() {
        for (number in listOf(409, 470)) {
            val prayer = body(row("full", number))
            val mutations: List<Pair<String, (Element) -> Unit>> = listOf(
                "earlier reply left" to { it.children()[0].prependText("Вонмем. ") },
                "earlier reply right" to { it.children()[1].prependText("Будем внимать. ") },
                "nested body left" to { cell -> val c = cell.children()[0]; val tail = c.childNodes().last(); tail.remove(); c.appendElement("p").appendChild(tail) },
                "extra paragraph right" to { it.children()[1].appendElement("p").text("Другая реплика") },
                "internal direction" to { it.children()[1].appendElement("span").addClass("k").text("Трижды.") },
                "extra speaker right" to { it.children()[1].appendText(" Диакон: Другая реплика") },
                "marker has nested content" to { it.children()[0].selectFirst("span.k")!!.appendElement("b").text("Дополнение") },
                "unverified marker class" to { it.children()[0].selectFirst("span.k")!!.addClass("other") }
            )
            for ((name, mutate) in mutations) assertNull("$number $name", rowIndex(row("full", number).also(mutate)).find(prayer))
        }
        assertNull(rowIndex(row("short", 304)).find("Святая святым."))
        assertNull(rowIndex(row("short", 311)).find("Святая святым."))
    }

    @Test fun emptyAndChangedBodiesNeverBorrowACompletePrayerTranslation() {
        for (number in listOf(409, 470)) {
            val prayer = body(row("full", number))
            for (side in 0..1) {
                val empty = row("full", number)
                empty.children()[side].childNodes().filterIsInstance<TextNode>().forEach { it.remove() }
                assertNull("empty side $side", rowIndex(empty).find(prayer))
            }
            val changed = row("full", number).also { it.children()[0].appendText(" Добавленное слово") }
            assertNull(rowIndex(changed).find(prayer))
            assertNotNull(rowIndex(changed).find("$prayer Добавленное слово"))
            val shortened = prayer.substringBeforeLast(' ')
            assertNull(combined.find(shortened))
        }
    }

    @Test fun derivedPairsOnlyFillMissingKeysAndRetainTheirSourceOffline() {
        val prayer = body(row("full", 409))
        val whole = "<tr><td>$prayer</td><td>Прежняя полная пара</td></tr>"
        for (html in listOf(whole + row("full", 409).outerHtml(), row("full", 409).outerHtml() + whole)) {
            val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>$html</table>"), source("full"))
            assertEquals("Прежняя полная пара", BookTranslationIndex.decode(index.encode()).find(prayer)?.russian)
        }
    }

    @Test fun exactLiturgyPageAliasesKeepTheStrictRouteAndItsPublishedAttribution() {
        val row = row("full", 409)
        val prayer = body(row)
        for (url in listOf("${source("full").url}?edition=verified#parallel",
                source("full").url.replace("https://azbyka.ru", "https://AZBYKA.RU:443"))) {
            val alias = source("full").copy(url = url)
            val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${row.outerHtml()}</table>"), alias)
            assertEquals("Тестовый русский текст full-409-0.", index.find(prayer)?.russian)
            assertEquals(alias, index.find(prayer)?.source)
            val changed = row.clone()
            changed.children()[0].selectFirst("span.k")!!.remove()
            changed.children()[1].prependText("Хор: ")
            assertNull(BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${changed.outerHtml()}</table>"), alias).find(prayer))
        }
    }

    @Test fun aDirectionInTheVigilEditionCannotEraseItsPreviouslyAvailableWholePrayer() {
        // Published vigil row 77 has this exact left label and a different right label.
        // Its Russian body is synthetic; the existing whole-cell rendering must survive.
        val original = "Свете тихий святыя славы, / Безсмертнаго, Отца Небеснаго, / Святаго Блаженнаго, Иисусе Христе. / Пришедше на запад солнца, / видевше свет вечерний, / поем Отца, Сына и Святаго Духа, Бога. / Достоин еси во вся времена / пет быти гласы преподобными, / Сыне Божий, живот даяй, / темже мiр Тя славит."
        val html = """<table><tr><td><span class="k"><a id="vecherniaia-pesn-synu-bozhiiu-svete-tihii"></a>Лик:</span> $original</td>
            <td><span class="k">Хор же поет:</span> Тестовый русский текст всенощной.</td></tr></table>"""
        val index = BookTranslationIndex.fromParallelPage(Jsoup.parse(html), BookTranslationIndex.VIGIL_SOURCE)
        assertEquals("Хор же поет: Тестовый русский текст всенощной.", index.find(original)?.russian)
        assertEquals(BookTranslationIndex.VIGIL_SOURCE, index.find(original)?.source)
        // The newly reviewed liturgy-only pair cannot add a body key on another page.
        val liturgyRow = row("full", 409)
        val other = BookTranslationIndex.fromParallelPage(Jsoup.parse("<table>${liturgyRow.outerHtml()}</table>"), BookTranslationIndex.VIGIL_SOURCE)
        assertNull(other.find(body(liturgyRow)))
        assertNotNull(other.find(liturgyRow.children()[0].text()))
    }
}
