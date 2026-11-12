package ru.liturgyguide.service.book

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import ru.liturgyguide.model.BookSource
import ru.liturgyguide.model.WorshipKind
import java.net.URI
import java.time.LocalDate

data class BookTranslation(val russian: String, val source: BookSource, val meaning: String? = null)

/** A lexical concordance, not a machine translator. Absent matches deliberately remain absent. */
class BookTranslationIndex private constructor(private val entries: Map<String, BookTranslation>) {
    fun find(original: String): BookTranslation? = entries[normal(original)]

    fun plus(other: BookTranslationIndex): BookTranslationIndex {
        val merged = other.entries.toMutableMap()
        entries.forEach { (original, incoming) ->
            // A partial refresh may load the short edition while the full edition is offline.
            // Keep the previously verified full rendering of this exact prayer in that case.
            val previous = merged[original]
            if (incoming.source.url != TIMROT_SOURCE.url || previous?.source?.url != TIMROT_FULL_SOURCE.url)
                merged[original] = incoming
        }
        return BookTranslationIndex(merged)
    }

    val size: Int get() = entries.size

    fun encode(): String = JsonArray().apply {
        entries.forEach { (key, value) -> add(JsonObject().apply {
            addProperty("original", key); addProperty("russian", value.russian)
            addProperty("source", value.source.title); addProperty("url", value.source.url)
            value.meaning?.let { addProperty("meaning", it) }
        }) }
    }.toString()

    companion object {
        val EDITORIAL_SOURCE = BookSource("Русское изложение: редакция Феории", "https://github.com/jeymog888/PravoslavnyyProvodnik")
        val TIMROT_SOURCE = BookSource("Перевод иеромонаха Амвросия (Тимрота), Азбука веры",
            "https://azbyka.ru/bogosluzhenie/liturgiya-svt-ioanna-zlatousta/")
        val TIMROT_FULL_SOURCE = TIMROT_SOURCE.copy(url = "https://azbyka.ru/bogosluzhenie/liturgiya-ioanna-zlatousta/")
        val TIMROT_THANKSGIVING_SOURCE = TIMROT_SOURCE.copy(url = "https://azbyka.ru/bogosluzhenie/molitvy-posle-svyatogo-prichashheniya/")
        // Preserve the three-source liturgy path; other orders never delay a liturgy download.
        val PARALLEL_SOURCES = listOf(TIMROT_SOURCE, TIMROT_FULL_SOURCE, TIMROT_THANKSGIVING_SOURCE)
        val VESPERS_SOURCE = TIMROT_SOURCE.copy(url = "https://azbyka.ru/bogosluzhenie/vechernya/")
        val VIGIL_SOURCE = TIMROT_SOURCE.copy(url = "https://azbyka.ru/bogosluzhenie/vsenoshhnoe-bdenie/")
        val HOUR1_SOURCE = TIMROT_SOURCE.copy(url = "https://azbyka.ru/bogosluzhenie/chas-pervyj/")
        val HOUR3_SOURCE = TIMROT_SOURCE.copy(url = "https://azbyka.ru/bogosluzhenie/chas-tretij/")
        val HOUR6_SOURCE = TIMROT_SOURCE.copy(url = "https://azbyka.ru/bogosluzhenie/chas-shestoj/")
        val HOUR9_SOURCE = TIMROT_SOURCE.copy(url = "https://azbyka.ru/bogosluzhenie/chas-devyatyj/")
        // This page publishes paired Psalms, but does not name their translator. Do not credit Timrot by inheritance.
        val PSALTER_SOURCE = BookSource("Азбука веры · Псалтирь с переводом",
            "https://azbyka.ru/molitvoslov/psaltir-po-kafizmam-s-perevodom.html")
        val ALL_PARALLEL_SOURCES = PARALLEL_SOURCES + listOf(VESPERS_SOURCE, VIGIL_SOURCE,
            HOUR1_SOURCE, HOUR3_SOURCE, HOUR6_SOURCE, HOUR9_SOURCE, PSALTER_SOURCE)

        fun requiredSources(worship: WorshipKind): List<BookSource> = when (worship) {
            WorshipKind.LITURGY -> PARALLEL_SOURCES
            // The third-hour page supplies complete common opening prayers used at Vespers too.
            WorshipKind.VESPERS -> listOf(VESPERS_SOURCE, VIGIL_SOURCE, HOUR3_SOURCE, PSALTER_SOURCE)
            WorshipKind.MATINS -> listOf(VIGIL_SOURCE, TIMROT_SOURCE, PSALTER_SOURCE)
            WorshipKind.HOUR1 -> listOf(HOUR1_SOURCE, VIGIL_SOURCE)
            WorshipKind.HOUR3 -> listOf(HOUR3_SOURCE, VIGIL_SOURCE, PSALTER_SOURCE)
            WorshipKind.HOUR6 -> listOf(HOUR6_SOURCE, VIGIL_SOURCE)
            WorshipKind.HOUR9 -> listOf(HOUR9_SOURCE, VIGIL_SOURCE, HOUR3_SOURCE)
            WorshipKind.VIGIL -> listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1)
                .flatMap(::requiredSources).distinctBy { it.url }
        }

        fun empty(): BookTranslationIndex = BookTranslationIndex(emptyMap())

        private fun normal(value: String): String = BookText.normalized(BookText.withoutSpeaker(value).second)

        fun fromAssets(legacyStagesJson: String?, commonJson: String? = null): BookTranslationIndex {
            val result = linkedMapOf<String, BookTranslation>()
            fun add(original: String?, russian: String?, source: BookSource = EDITORIAL_SOURCE, meaning: String? = null) {
                if (original.isNullOrBlank() || russian.isNullOrBlank()) return
                val key = normal(original)
                if (key.isNotBlank()) result.putIfAbsent(key, BookTranslation(BookText.clean(russian), source, meaning))
            }
            runCatching {
                if (legacyStagesJson != null) JsonParser.parseString(legacyStagesJson).asJsonObject.getAsJsonArray("stages")?.forEach { value ->
                    val stage = value.asJsonObject
                    stage.getAsJsonArray("texts")?.forEach { textValue ->
                        val text = textValue.asJsonObject
                        val meaning = text.string("explanation")
                        add(text.string("original"), text.string("translation"), meaning = meaning)
                        val pairs = text.getAsJsonArray("lines")?.mapNotNull { line ->
                            val item = line.asJsonObject
                            val original = item.string("original")
                            val russian = item.string("translation")
                            if (original.isNullOrBlank() || russian.isNullOrBlank()) null else original to russian
                        }.orEmpty()
                        // Source paragraphs and the existing reader split the same prayer differently.
                        // A concatenation is accepted only when every original word is present in order.
                        for (start in pairs.indices) {
                            for (end in start until minOf(pairs.size, start + 32)) {
                                val sequence = pairs.subList(start, end + 1)
                                add(sequence.joinToString(" ") { it.first }, sequence.joinToString(" ") { it.second }, meaning = meaning)
                            }
                        }
                    }
                }
            }
            runCatching {
                if (commonJson != null) JsonParser.parseString(commonJson).asJsonArray.forEach { value ->
                    val item = value.asJsonObject
                    add(item.string("original"), item.string("russian"), meaning = item.string("meaning"))
                }
            }
            return BookTranslationIndex(result)
        }

        /** The public bilingual edition is downloaded for this reading, never bundled into the APK. */
        fun fromParallelPage(document: Document, source: BookSource = TIMROT_SOURCE.copy(
            url = document.location().ifBlank { TIMROT_SOURCE.url }
        )): BookTranslationIndex {
            if (source.url == PSALTER_SOURCE.url) return fromPsalterPage(document)
            val result = linkedMapOf<String, BookTranslation>()
            val structuralBodies = linkedMapOf<String, BookTranslation>()
            for (row in document.select("table tr")) {
                val cells = row.children().filter { it.tagName() == "td" }
                if (cells.size != 2) continue
                val original = BookText.clean(cells[0].text())
                val russian = BookText.clean(cells[1].text())
                if (original.length < 3 || russian.length < 3 || original == "Церковнославянский") continue
                val sourceParts = speakerSegments(original)
                val russianParts = speakerSegments(russian)
                // Paragraphs occasionally contain several replies. Matching an entire Russian cell
                // to one shorter original reply would change its meaning and is not allowed.
                if (sourceParts.size == russianParts.size && sourceParts.size > 1) {
                    sourceParts.zip(russianParts).forEach { (left, right) ->
                        if (left.first == right.first) addParallel(result, left.second, right.second, source)
                    }
                }
                addParallel(result, original, russian, source)
                pairedResponseParagraph(cells[0], cells[1])?.let { (left, right) ->
                    addParallel(structuralBodies, left, right, source)
                }
                pairedHeadingBody(cells[0], cells[1])?.let { (left, right) ->
                    addParallel(structuralBodies, left, right, source)
                }
                pairedLeadingNumber(cells[0], cells[1])?.let { (left, right) ->
                    addParallel(structuralBodies, left, right, source)
                }
                // A Psalm heading belongs to the edition's structure. Keep the original pair too,
                // and add a body-only pair only when both leading DOM headings name the same Psalm.
                val leftPsalm = psalmBody(cells[0])
                val rightPsalm = psalmBody(cells[1])
                if (leftPsalm != null && rightPsalm != null && leftPsalm.first == rightPsalm.first)
                    addParallel(result, leftPsalm.second, rightPsalm.second, source)
            }
            // Preserve an already available whole-cell edition even when a derived pair occurs
            // earlier on the page. New boundaries should only fill missing concordance entries.
            structuralBodies.forEach { (key, value) -> result.putIfAbsent(key, value) }
            return BookTranslationIndex(result)
        }

        /** Some litany rows place the choir's response before a separate petition paragraph.
         * Accept that boundary only when both columns have the same, complete response first.
         * Arbitrary paragraph counts or similar words do not establish a translation pair. */
        private fun pairedResponseParagraph(left: Element, right: Element): Pair<String, String>? {
            data class Response(val words: String, val repeats: Int)
            fun parts(cell: Element): Pair<Response, String>? {
                // A stray closing </p> in the published table becomes an empty paragraph in Jsoup.
                // Discard only those empty nodes, never a second paragraph containing words or markup.
                val copy = cell.clone().apply {
                    children().filter { it.tagName() == "p" && it.children().isEmpty() && !it.hasText() }
                        .forEach { it.remove() }
                }
                val paragraph = copy.children().singleOrNull { it.tagName() == "p" } ?: return null
                if (paragraph.select("p,table,div,blockquote,li").any { it !== paragraph }) return null
                // Nothing may follow the petition: otherwise the two columns could be ordered differently.
                if (paragraph.nextSibling() != null && copy.childNodes().dropWhile { it !== paragraph }.drop(1)
                        .any { it !is TextNode || BookText.clean(it.text()).isNotEmpty() }) return null
                val prayer = BookText.clean(paragraph.text())
                paragraph.remove()
                // Count only the edition's separate, trailing prefix marker. Do not erase
                // span.k inside the petition: it may contain names or other prayer words.
                val counters = copy.select("span.k").filter {
                    BookText.clean(it.text()).lowercase() in setOf("трижды.", "трижды", "(3)")
                }
                if (counters.size > 1) return null
                val counter = counters.singleOrNull()
                if (counter != null) {
                    if (counter.parent() !== copy || counter.children().isNotEmpty() ||
                        copy.childNodes().dropWhile { it !== counter }.drop(1).any {
                            it !is TextNode || BookText.normalized(it.text()).isNotEmpty()
                        }) return null
                    counter.remove()
                }
                val (speaker, response) = BookText.withoutSpeaker(copy.text())
                if (speaker != "Хор") return null
                val responseKey = BookText.normalized(response)
                if (responseKey !in setOf("господи помилуй", "подай господи", "аминь")) return null
                // The verified threefold marker belongs to this response only.
                if (counter != null && responseKey != "господи помилуй") return null
                return Response(responseKey, if (counter == null) 1 else 3) to prayer
            }
            val a = parts(left) ?: return null
            val b = parts(right) ?: return null
            if (a.first != b.first || a.second.isBlank() || b.second.isBlank()) return null
            val leftSpeaker = BookText.withoutSpeaker(a.second).first
            val rightSpeaker = BookText.withoutSpeaker(b.second).first
            // The complete paired prayer in short row 234 / full row 340 uses a leading
            // editorial direction on the left and the ordinary priest label on the right.
            // split also requires a nonempty prayer; unknown directions stay unmatched.
            val priestDirection = BookPrayerMetadata.split(a.second).rubric
                ?.equals("Священник молится:", ignoreCase = true) == true
            // Unknown labels both produce null. That equality cannot authorize this new
            // boundary: the parallel column must explicitly name the same priest role.
            if (priestDirection) {
                if (rightSpeaker != "Священник") return null
            } else if (leftSpeaker != rightSpeaker) return null
            return a.second to b.second
        }

        // Reviewed hymn labels in the short edition (rows 400/402). Identical arbitrary
        // headings are not evidence of a prayer boundary; Psalms retain their separate number guard.
        private val pairedHymnHeadings = setOf("и ныне богородичен", "отпустительный тропарь глас 8")

        /** Remove only a reviewed hymn heading that leads both columns with the same words.
         * The prayer bodies are still indexed whole and must match every original word. */
        private fun pairedHeadingBody(left: Element, right: Element): Pair<String, String>? {
            fun body(cell: Element): Pair<String, String>? {
                val heading = cell.select("h1,h2,h3,h4,h5,h6").singleOrNull() ?: return null
                if (heading.tagName() != "h3" || heading.parent() !== cell || firstMeaningfulNode(cell) !== heading) return null
                val title = BookText.normalized(heading.text()).takeIf { it in pairedHymnHeadings } ?: return null
                val copy = cell.clone().apply { select("h3").single().remove() }
                val prayer = BookText.clean(copy.text()).takeIf(String::isNotBlank) ?: return null
                return title to prayer
            }
            val a = body(left) ?: return null
            val b = body(right) ?: return null
            if (a.first != b.first || BookText.withoutSpeaker(a.second).first != BookText.withoutSpeaker(b.second).first) return null
            return a.second to b.second
        }

        /** The antiphon edition alternates 1./2. markers; these are not Bible verse numbers.
         * Only the two verified, standalone leading span.n forms may be separated. */
        private fun pairedLeadingNumber(left: Element, right: Element): Pair<String, String>? {
            fun body(cell: Element): Pair<String, String>? {
                val marker = firstMeaningfulNode(cell) as? Element ?: return null
                if (marker.tagName() != "span" || marker.classNames() != setOf("n") ||
                    marker.children().isNotEmpty() || cell.select("span.n").size != 1 ||
                    cell.select("h1,h2,h3,h4,h5,h6").isNotEmpty()) return null
                val number = BookText.clean(marker.text()).takeIf { it == "1." || it == "2." } ?: return null
                val copy = cell.clone().apply { select("span.n").single().remove() }
                val prayer = BookText.clean(copy.text()).takeIf(String::isNotBlank) ?: return null
                return number to prayer
            }
            val a = body(left) ?: return null
            val b = body(right) ?: return null
            if (a.first != b.first || BookText.withoutSpeaker(a.second).first != BookText.withoutSpeaker(b.second).first) return null
            return a.second to b.second
        }

        private fun firstMeaningfulNode(cell: Element) = cell.childNodes().firstOrNull { node ->
            node is Element || node is TextNode && BookText.clean(node.text()).isNotEmpty()
        }

        /** Only whole paired Psalm rows with identical, continuous verse markers are accepted. */
        private fun fromPsalterPage(document: Document): BookTranslationIndex {
            if (document.select("link[rel=canonical]").singleOrNull()?.attr("href") != PSALTER_SOURCE.url ||
                document.select("h1").singleOrNull()?.text()?.let(BookText::clean) != "Псалтирь по кафизмам с переводом и молитвами") return empty()
            val result = linkedMapOf<String, BookTranslation>()
            for (table in document.select("table.adaptive")) {
                var inPsalm = false
                for (row in table.select("tr")) {
                    if (row.closest("table") !== table) continue
                    val cells = row.children().filter { it.tagName() == "td" }
                    if (cells.size == 1) {
                        val number = Regex("Псалом ([0-9]{1,3})").matchEntire(BookText.clean(cells[0].text()))
                            ?.groupValues?.get(1)?.toIntOrNull()
                        inPsalm = cells[0].attr("colspan") == "2" && number != null && number in 1..151
                        continue
                    }
                    if (!inPsalm || cells.size != 2) continue
                    val leftMarkers = psalmVerseMarkers(cells[0]) ?: continue
                    if (leftMarkers.isEmpty() || leftMarkers != psalmVerseMarkers(cells[1])) continue
                    // Footnote references are not prayer words; leave an ambiguous row untranslated.
                    if (cells.any { it.select("a[href^=#_ftn]").isNotEmpty() }) continue
                    val clean = cells.map { cell -> cell.clone().apply { select("span.letter > sup").remove() } }
                    val original = BookText.clean(clean[0].text())
                    val russian = BookText.clean(clean[1].text())
                    if (original.length in 3..30_000 && russian.length in 3..40_000)
                        addParallel(result, original, russian, PSALTER_SOURCE)
                }
            }
            return BookTranslationIndex(result)
        }

        private fun psalmVerseMarkers(cell: Element): List<Int>? {
            val markers = mutableListOf<Int>()
            for (marker in cell.select("sup")) {
                val wrapper = marker.parent() ?: return null
                if (wrapper.tagName() != "span" || !wrapper.hasClass("letter") || marker.children().isNotEmpty() ||
                    wrapper.children().size != 1 || wrapper.ownText().isNotBlank() ||
                    !marker.text().matches(Regex("[0-9]{1,3}"))) return null
                val number = marker.text().toIntOrNull()?.takeIf { it in 1..176 } ?: return null
                if (markers.isNotEmpty() && number != markers.last() + 1) return null
                markers += number
            }
            return markers
        }

        private fun psalmBody(cell: Element): Pair<Int, String>? {
            val heading = cell.select("h1,h2,h3,h4,h5,h6").singleOrNull() ?: return null
            if (heading.parent() !== cell) return null
            val first = cell.childNodes().firstOrNull { node ->
                node is Element || node is TextNode && BookText.clean(node.text()).isNotEmpty()
            }
            if (first !== heading) return null
            val match = Regex("^(?:(?:И псалмы:|Таже псалмы\\.)\\s*)?Псалом\\s+([0-9]{1,3}):?$",
                RegexOption.IGNORE_CASE).matchEntire(BookText.clean(heading.text())) ?: return null
            val number = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..150 } ?: return null
            val copy = cell.clone()
            copy.select("h1,h2,h3,h4,h5,h6").single().remove()
            val body = BookText.clean(copy.text()).takeIf { it.isNotBlank() } ?: return null
            return number to body
        }

        /** Calendar hymns are a dated supplement, never part of the permanent corpus. */
        fun fromCalendarPage(document: Document, requestedDate: String): BookTranslationIndex? {
            val date = runCatching { LocalDate.parse(requestedDate) }.getOrNull() ?: return null
            if (date.toString() != requestedDate || date.year !in 1900..2200) return null
            val canonical = document.select("link[rel=canonical]").singleOrNull()?.attr("href") ?: return null
            val address = runCatching { URI(canonical) }.getOrNull() ?: return null
            if (address.scheme != "https" || address.host != "azbyka.ru" || address.port != -1 ||
                address.userInfo != null || address.rawQuery != null || address.rawFragment != null ||
                address.path.trimEnd('/') != "/days/$requestedDate") return null
            val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
                "августа", "сентября", "октября", "ноября", "декабря")
            val expectedHeading = "${date.dayOfMonth} ${months[date.monthValue - 1]} ${date.year}"
            val heading = document.select("h1").singleOrNull()?.text()?.let(BookText::clean) ?: return null
            if (heading != expectedHeading) return null

            val source = BookSource("Азбука веры · календарь на $requestedDate", canonical)
            val result = linkedMapOf<String, BookTranslation>()
            for (part in document.select(".taks_content")) {
                val translation = part.select(".taks-explanation-info").singleOrNull() ?: continue
                val container = translation.parent() ?: continue
                if (container.parent() !== part) continue
                val label = translation.previousElementSibling()?.takeIf { it.hasClass("taks-explanation") } ?: continue
                if (BookText.normalized(label.text()) != "перевод") continue
                val originals = container.children().takeWhile { it !== label }
                if (originals.isEmpty() || originals.any { it.tagName() != "p" }) continue
                val original = BookText.clean(originals.joinToString(" ") { it.text() })
                val russian = BookText.clean(translation.text())
                if (original.length !in 3..30_000 || russian.length !in 3..40_000) continue
                addParallel(result, original, russian, source)
            }
            return result.takeIf { it.isNotEmpty() }?.let(::BookTranslationIndex)
        }

        fun decode(json: String?): BookTranslationIndex = runCatching {
            require(json != null && json.length <= BookTranslationCorpus.MAX_BYTES)
            val result = linkedMapOf<String, BookTranslation>()
            JsonParser.parseString(json).asJsonArray.forEach { value ->
                val item = value.asJsonObject
                val original = requireNotNull(item.string("original"))
                val russian = requireNotNull(item.string("russian"))
                val source = BookSource(requireNotNull(item.string("source")), requireNotNull(item.string("url")))
                require(original.length <= 30_000 && russian.length <= 40_000)
                result[normal(original)] = BookTranslation(russian, source, item.string("meaning"))
            }
            BookTranslationIndex(result)
        }.getOrElse { empty() }

        private fun addParallel(target: MutableMap<String, BookTranslation>, original: String, russian: String, source: BookSource) {
            val left = BookText.withoutSpeaker(original).second
            val right = BookText.withoutSpeaker(russian).second
            val key = normal(left)
            val speechLeft = BookPrayerMetadata.split(left)
            val speechRight = BookPrayerMetadata.split(right)
            if (speechLeft.rubric != null || speechRight.rubric != null) {
                val speechKey = normal(speechLeft.prayer)
                if (speechKey.isNotBlank() && speechRight.prayer.isNotBlank())
                    target.putIfAbsent(speechKey, BookTranslation(speechRight.prayer, source))
            }
            if (key.isNotBlank() && right.isNotBlank()) target.putIfAbsent(key, BookTranslation(right, source))
        }

        private fun speakerSegments(value: String): List<Pair<String?, String>> {
            val starts = Regex("(?i)${BookText.SPEAKER_LABEL}:\\s*")
                .findAll(value).toList()
            if (starts.isEmpty()) return listOf(null to value)
            if (starts.first().range.first != 0) return listOf(null to value)
            return starts.mapIndexed { index, match ->
                val end = starts.getOrNull(index + 1)?.range?.first ?: value.length
                BookText.withoutSpeaker(value.substring(match.range.first, end))
            }
        }

        private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}
