package ru.liturgyguide.service.book

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.liturgyguide.model.*

class InvalidServiceBook(message: String) : IllegalArgumentException(message)

/** Reads the whole repaired HTML tree in document order, including quiet altar prayers. */
class ServiceBookParser {
    fun parse(
        document: Document,
        key: ServiceBookKey,
        fetchedAtMillis: Long,
        translations: BookTranslationIndex = BookTranslationIndex.empty(),
        scripture: BookScripture = BookScripture.empty()
    ): ServiceBook {
        if (!key.isValid() || key.worship == WorshipKind.VIGIL) throw InvalidServiceBook("Неизвестная служба")
        if (document.selectFirst("#bg_worships_setDay")?.attr("value") != key.date)
            throw InvalidServiceBook("Источник вернул другую дату")
        if (selected(document, "bg_worship_type")?.attr("value") != key.worship.queryValue)
            throw InvalidServiceBook("Источник вернул другой вид службы")
        if (selected(document, "bg_worship_size")?.attr("value") != "full")
            throw InvalidServiceBook("Источник вернул сокращенный текст")
        if (selected(document, "bg_worships_church")?.attr("value") != key.churchType)
            throw InvalidServiceBook("Источник вернул другой вариант храма")
        if (key.mainEvent != null && selected(document, "bg_worships_mainEvent")?.attr("value") != key.mainEvent)
            throw InvalidServiceBook("Источник вернул другой праздник")
        if (key.secondEvent != null && selected(document, "bg_worships_secondEvent")?.attr("value") != key.secondEvent)
            throw InvalidServiceBook("Источник вернул другой дополнительный праздник")

        val content = document.selectFirst(".article-single-content.main-page-content")?.clone()
            ?: throw InvalidServiceBook("Не найден текст службы")
        content.select(".worship_menu,.bg_data_tooltip,script,style,button,input,select,iframe,nav").remove()
        val title = document.select("section.worship h1, .main-area-content.worship h1").lastOrNull()?.text()
            ?: document.select("h1").lastOrNull()?.text() ?: key.worship.title
        validateTitle(title, key.worship)
        val wholeText = BookText.clean(content.text())
        validateCompleteness(wholeText, key.worship)

        data class DraftChapter(val id: String, val title: String, val blocks: MutableList<BookBlock>)
        val drafts = mutableListOf<DraftChapter>()
        val headingOccurrences = mutableMapOf<String, Int>()
        val blockOccurrences = mutableMapOf<String, Int>()
        var sectionTitle = "Перед началом"
        var current: DraftChapter? = null
        fun chapter(title: String): DraftChapter {
            val clean = BookText.clean(title)
            val root = "${key.worship.queryValue}-${BookText.sha256(BookText.normalized(clean)).take(12)}"
            val occurrence = headingOccurrences.getOrDefault(root, 0) + 1
            headingOccurrences[root] = occurrence
            return DraftChapter("$root-$occurrence", clean, mutableListOf()).also { drafts += it; current = it; sectionTitle = clean }
        }
        fun add(originalText: String, kind: BookBlockKind, altar: Boolean, references: List<String> = emptyList()) {
            val raw = BookText.clean(originalText)
            if (raw.isEmpty()) return
            if (raw.length > 60_000) throw InvalidServiceBook("Необычно длинный фрагмент службы")
            val owner = current ?: chapter(sectionTitle)
            val (speaker, body) = if (kind == BookBlockKind.PRAYER) BookText.withoutSpeaker(raw) else null to raw
            if (body.isBlank()) return
            val found = if (kind == BookBlockKind.SCRIPTURE && references.size == 1) scripture.russian(references.first())
                else if (kind == BookBlockKind.PRAYER) translations.find(body) else null
            val meaning = found?.meaning ?: when (kind) {
                BookBlockKind.RUBRIC -> body
                BookBlockKind.LOCAL_VARIATION -> "Песнопение зависит от посвящения этого храма. В выбранном источнике его текст не указан"
                else -> BookMeaningCatalog.meaningFor(owner.title, body, key.worship.queryValue)
            }
            val root = "${owner.id}:${BookText.sha256("${kind.name}|$speaker|$altar|$body|${references.joinToString()}").take(16)}"
            val occurrence = blockOccurrences.getOrDefault(root, 0) + 1
            blockOccurrences[root] = occurrence
            owner.blocks += BookBlock("$root:$occurrence", kind, speaker, body,
                russian = when (kind) {
                    BookBlockKind.RUBRIC, BookBlockKind.LOCAL_VARIATION -> body
                    else -> found?.russian
                }, translationSource = found?.source, scriptureReferences = references, isAltarText = altar, meaning = meaning)
        }

        fun walk(node: Node, altar: Boolean = false) {
            if (node is TextNode) {
                val text = BookText.clean(node.text())
                if (text.length >= 3) add(text, BookBlockKind.RUBRIC, altar)
                return
            }
            if (node !is Element) return
            val tag = node.tagName()
            if (tag == "summary" || tag in setOf("script", "style", "nav")) return
            if (tag == "details") {
                val quiet = altar || node.selectFirst("summary")?.text()?.let { BookText.normalized(it) == "в алтаре" } == true
                node.childNodes().filterNot { it is Element && it.tagName() == "summary" }.forEach { walk(it, quiet) }
                return
            }
            if (tag in setOf("h1", "h2", "h3")) {
                val value = node.text()
                if (value.isNotBlank()) chapter(value)
                return
            }
            if (tag == "h4" || tag == "h5") {
                add(node.text(), BookBlockKind.RUBRIC, altar)
                return
            }
            if (node.hasClass("bg_bibrefs_service")) {
                val paragraphs = node.select("p")
                val references = node.select("a[href*=/biblia/?]").mapNotNull { link ->
                    link.attr("href").substringAfter('?', "").substringBefore('&').takeIf {
                        it.matches(Regex("[1-3]?[A-Za-z]+\\.[0-9:,;\\-]+"))
                    }
                }.distinct()
                val label = paragraphs.firstOrNull()?.takeIf { it.selectFirst("a[href*=/biblia/?]") != null }
                label?.let { add(it.text(), BookBlockKind.RUBRIC, altar) }
                val text = paragraphs.filter { it !== label }.joinToString("\n\n") { it.text() }
                if (text.isNotBlank()) add(text, BookBlockKind.SCRIPTURE, altar, references)
                return
            }
            if (tag == "p" || tag == "li" || tag == "blockquote") {
                // Jsoup repairs malformed nested paragraphs. Never consume an outer wrapper and
                // then consume its descendant paragraphs again.
                if (node.children().any { it.tagName() in setOf("p", "div", "h2", "h3", "details") }) {
                    node.childNodes().forEach { walk(it, altar) }
                    return
                }
                fun paragraph(value: String) {
                    val text = BookText.clean(value)
                    if (text.contains("Укажите в настройках") || text.contains("Укажите престольный")) {
                        add("Текст песнопения этого храма здесь не указан. В этот момент поют тропарь или кондак в честь его святого или праздника",
                            BookBlockKind.LOCAL_VARIATION, altar)
                    } else {
                        val speech = BookPrayerMetadata.split(text)
                        if (speech.rubric != null) {
                            add(speech.rubric, BookBlockKind.RUBRIC, altar)
                            add(speech.prayer, BookBlockKind.PRAYER, altar)
                        } else add(text, if (isRubric(text)) BookBlockKind.RUBRIC else BookBlockKind.PRAYER, altar)
                    }
                }
                if (node.selectFirst("label.bg_worships_info") == null) paragraph(node.text()) else {
                    // A heading and a hymn can share one paragraph. Keep their order and kinds:
                    // treating the whole paragraph as a rubric falsely labels Slavonic as Russian.
                    val pending = StringBuilder()
                    fun flush() { paragraph(pending.toString()); pending.clear() }
                    fun inline(child: Node) {
                        when (child) {
                            is TextNode -> pending.append(child.text())
                            is Element -> if (child.tagName() == "label" && child.hasClass("bg_worships_info")) {
                                flush()
                                add(child.text(), BookBlockKind.RUBRIC, altar)
                            } else {
                                if (child.tagName() == "br") pending.append(' ')
                                child.childNodes().forEach(::inline)
                            }
                        }
                    }
                    node.childNodes().forEach(::inline)
                    flush()
                }
                return
            }
            node.childNodes().forEach { walk(it, altar) }
        }
        content.childNodes().forEach { walk(it) }
        val chapters = drafts.filter { it.blocks.isNotEmpty() }.map { draft ->
            BookChapter(draft.id, draft.title,
                BookMeaningCatalog.meaningFor(draft.title, draft.blocks.firstOrNull()?.original.orEmpty(), key.worship.queryValue)
                    ?: genericMeaning(key.worship), draft.blocks.toList())
        }
        val blocks = chapters.flatMap { it.blocks }
        if (blocks.size < 15 || blocks.size > 4_000 || blocks.sumOf { it.original.length } < wholeText.length * 0.65)
            throw InvalidServiceBook("Текст службы получен не полностью")
        val notices = buildList {
            add("Последование по церковному календарю. В вашем храме порядок и сокращения могут отличаться")
            if (blocks.any { it.kind == BookBlockKind.LOCAL_VARIATION }) add("Текст отдельных песнопений этого храма в источнике не указан; эти места отмечены в книге")
            if (blocks.any { it.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE) && it.russian == null })
                add("Русский текст есть не для всех фрагментов. Для остальных можно открыть оригинал")
        }
        val eventTitle = listOfNotNull(selected(document, "bg_worships_mainEvent")?.text(),
            selected(document, "bg_worships_secondEvent")?.text()).filter { it != "-" && it.isNotBlank() }.joinToString(" · ").ifBlank { null }
        val sources = (listOf(BookSource("Азбука веры: Богослужения сегодня", key.sourceUrl())) +
            blocks.mapNotNull { it.translationSource }).distinct()
        val book = ServiceBook(key = key, title = BookText.clean(title), sourceUrl = key.sourceUrl(),
            sourceTitle = "Азбука веры: Богослужения сегодня", fetchedAtMillis = fetchedAtMillis,
            selectedEventTitle = eventTitle, chapters = chapters, contentHash = "", notices = notices, sources = sources,
            mainEventOptions = options(document, "bg_worships_mainEvent"), secondEventOptions = options(document, "bg_worships_secondEvent"))
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }

    private fun selected(document: Document, id: String): Element? = document.selectFirst("#$id option[selected]")
        ?: document.selectFirst("#$id option")

    private fun options(document: Document, id: String): List<BookVariantOption> = document.select("#$id option").map {
        BookVariantOption(it.attr("value"), BookText.clean(it.text()))
    }

    private fun isRubric(text: String): Boolean {
        val normalized = BookText.normalized(text)
        if (normalized.startsWith("в положенное время") || normalized.startsWith("по причащении же да хранит")) return true
        return Regex("^(диакон|священник|иерей|лик|чтец|хор|затем|и священник|и диакон|и читаем|по окончании|при этом) ")
            .containsMatchIn(normalized) && Regex("\\b(читает|читают|произносит|поет|поют|возглашает|говорит|поклоняется|совершает|кадит|открывает|целует|отвечает|молится|предлагает)\\b")
            .containsMatchIn(normalized) && BookText.withoutSpeaker(text).first == null
    }

    private fun genericMeaning(kind: WorshipKind): String = when (kind) {
        WorshipKind.LITURGY -> "Общая молитва литургии. Читайте следующий фрагмент и прислушивайтесь к словам священника и хора"
        WorshipKind.VESPERS -> "Вечерняя молитва: благодарение за прожитый день и подготовка к церковному празднованию"
        WorshipKind.MATINS -> "Утренняя молитва: псалмы и песнопения помогают благодарить Бога и встречать новый день"
        else -> "Короткая служба одного из часов дня: псалмы и молитвы обращают внимание к Богу"
    }

    private fun validateTitle(title: String, kind: WorshipKind) {
        val normalized = BookText.normalized(title)
        val expected = when (kind) {
            WorshipKind.LITURGY -> "литург" in normalized
            WorshipKind.VESPERS -> "вечерн" in normalized
            WorshipKind.MATINS -> "утрен" in normalized
            WorshipKind.HOUR1 -> Regex("(?:^| )(?:1(?: й)?|первый) час(?: |$)").containsMatchIn(normalized)
            WorshipKind.HOUR3 -> Regex("(?:^| )(?:3(?: й)?|третий) час(?: |$)").containsMatchIn(normalized)
            WorshipKind.HOUR6 -> Regex("(?:^| )(?:6(?: й)?|шестой) час(?: |$)").containsMatchIn(normalized)
            WorshipKind.HOUR9 -> Regex("(?:^| )(?:9(?: й)?|девятый) час(?: |$)").containsMatchIn(normalized)
            WorshipKind.VIGIL -> false
        }
        if (!expected) throw InvalidServiceBook("Источник вернул \"${BookText.clean(title)}\" вместо \"${kind.title}\"")
    }

    private fun validateCompleteness(text: String, kind: WorshipKind) {
        val normalized = BookText.normalized(text)
        // An Amen somewhere near the end is not proof of an intact ending: it occurs
        // throughout every service. Match the actual terminal prayer for each order.
        val manyYears = normalized.endsWith("господи сохрани их на многая лета")
        val dailyMatinsEnd = normalized.endsWith("утверди боже святую православную веру православных христиан во век века")
        val vigilBlessing = normalized.endsWith("благословение господне на вас того благодатию и человеколюбием всегда ныне и присно и во веки веков лик аминь")
        // Sunday's first hour uses the small dismissal and the final threefold response,
        // not the weekday many-years acclamation. Match the whole published ending.
        val firstHourSundayEnd = normalized.endsWith("отпуст воскресый из мертвых христос истинный бог наш " +
            "молитвами пречистыя своея матере преподобных и богоносных отец наших и всех святых " +
            "помилует и спасет нас яко благ и человеколюбец лик господи помилуй трижды")
        val finalAmen = normalized.endsWith("во веки веков аминь")
        val complete = when (kind) {
            WorshipKind.LITURGY -> text.length >= 15_000 && "причащ" in normalized && "отпуст" in normalized &&
                "благодарственные молитвы" in normalized &&
                normalized.endsWith("помилуй нас аминь по причащении же да хранит себе всяк в трезвении и воздержании и немногоглаголании дабы в той день честно соблюл в себе христа приятаго")
            WorshipKind.VESPERS -> text.length >= 5_000 && "господи воззвах" in normalized &&
                (manyYears || vigilBlessing)
            WorshipKind.MATINS -> text.length >= 12_000 &&
                ("шестопсалм" in normalized || "христос воскресе" in normalized && "песнь 9" in normalized) &&
                (manyYears || dailyMatinsEnd)
            WorshipKind.HOUR1 -> text.length >= 2_000 && "отче наш" in normalized && (manyYears || firstHourSundayEnd)
            WorshipKind.HOUR3 -> text.length >= 2_000 && "отче наш" in normalized && finalAmen &&
                "владыко боже отче вседержителю" in normalized.takeLast(500) &&
                "яко благословен еси" in normalized.takeLast(80)
            WorshipKind.HOUR6 -> text.length >= 2_000 && "отче наш" in normalized && finalAmen &&
                "боже и господи сил и всея твари содетелю" in normalized.takeLast(1_500) &&
                "непрестанное тебе исповедание и благодарение возсылаем" in normalized.takeLast(350)
            WorshipKind.HOUR9 -> text.length >= 2_000 && "отче наш" in normalized && finalAmen &&
                "владыко господи иисусе христе боже наш долготерпевый" in normalized.takeLast(1_700) &&
                "истинное веселие и радость любящих тя" in normalized.takeLast(350)
            WorshipKind.VIGIL -> false
        }
        if (!complete) throw InvalidServiceBook("Не удалось подтвердить, что служба загрузилась целиком")
    }
}
