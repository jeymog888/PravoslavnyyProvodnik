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
        scripture: BookScripture = BookScripture.empty(),
        rankRequest: ResolvedMatinsRankRequest? = null,
        patronalSelection: PatronalEventProof? = null
    ): ServiceBook {
        if (!key.isValid() || key.worship == WorshipKind.VIGIL) throw InvalidServiceBook("Неизвестная служба")
        val rankSelection = if (key.topLevel != null) {
            val resolved = rankRequest ?: throw InvalidServiceBook("Не определена основная память полиелейного варианта")
            MatinsRankSource.validate(document, key, resolved, patronalSelection)
        } else {
            if (rankRequest != null) throw InvalidServiceBook("Полиелейный вариант не относится к выбранной книге")
            null
        }
        if (document.selectFirst("#bg_worships_setDay")?.attr("value") != key.date)
            throw InvalidServiceBook("Источник вернул другую дату")
        if (selected(document, "bg_worship_type")?.attr("value") != key.worship.queryValue)
            throw InvalidServiceBook("Источник вернул другой вид службы")
        if (selected(document, "bg_worship_size")?.attr("value") != "full")
            throw InvalidServiceBook("Источник вернул сокращенный текст")
        PatronalSourceSelection.validate(document, key, patronalSelection)
        if (key.mainEvent != null && selected(document, "bg_worships_mainEvent")?.attr("value") != key.mainEvent)
            throw InvalidServiceBook("Источник вернул другой праздник")
        if (key.secondEvent != null && selected(document, "bg_worships_secondEvent")?.attr("value") != key.secondEvent)
            throw InvalidServiceBook("Источник вернул другой дополнительный праздник")

        val content = (if (rankSelection == null) document.selectFirst(".article-single-content.main-page-content")
            else document.select(".article-single-content.main-page-content").singleOrNull())?.clone()
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
                if (text.length >= 3) add(text,
                    if (!altar && key.worship in setOf(WorshipKind.MATINS, WorshipKind.VESPERS) &&
                        (isWholePublishedDismissal(node) || isPublishedDoxologyRun(node)))
                        BookBlockKind.PRAYER else BookBlockKind.RUBRIC, altar)
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
                        val speech = when {
                            !altar && key.worship == WorshipKind.LITURGY -> BookPrayerMetadata.splitLiturgyParagraph(text)
                            !altar && (key.worship == WorshipKind.MATINS || key.worship == WorshipKind.VESPERS) ->
                                BookPrayerMetadata.splitDatedPublicParagraph(text)
                            else -> BookPrayerMetadata.split(text)
                        }
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
        val eventTitle = listOfNotNull(selectedEventTitle(document, "bg_worships_mainEvent", rankSelection != null),
            selectedEventTitle(document, "bg_worships_secondEvent", rankSelection != null)).joinToString(" · ").ifBlank { null }
        val sourceUrl = rankRequest?.url ?: key.sourceUrl()
        val sources = (listOf(BookSource("Азбука веры: Богослужения сегодня", sourceUrl)) +
            blocks.mapNotNull { it.translationSource }).distinct()
        val book = ServiceBook(key = key, title = BookText.clean(title), sourceUrl = sourceUrl,
            sourceTitle = "Азбука веры: Богослужения сегодня", fetchedAtMillis = fetchedAtMillis,
            selectedEventTitle = eventTitle, chapters = chapters, contentHash = "", notices = notices, sources = sources,
            mainEventOptions = options(document, "bg_worships_mainEvent"), secondEventOptions = options(document, "bg_worships_secondEvent"),
            rankSelection = rankSelection, patronalSelection = patronalSelection)
        if (rankSelection != null && !MatinsRankSource.validBook(book))
            throw InvalidServiceBook("Источник не вернул полный полиелейный порядок")
        return book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    }

    /** A whole source-marked dismissal can be a bare text node after HTML repair.
     * An intervening element (especially an unfilled patronal slot) keeps the old boundary.
     */
    private fun isWholePublishedDismissal(node: TextNode): Boolean {
        val parent = node.parent() as? Element ?: return false
        if (parent.tagName() != "div" || !parent.hasClass("variable_text")) return false
        fun blank(value: Node) = value is TextNode && BookText.clean(value.text()).isEmpty()
        var previous = node.previousSibling()
        while (previous != null && blank(previous)) previous = previous.previousSibling()
        val heading = previous as? Element ?: return false
        if (heading.tagName() != "h3" || heading.select("a#otpust").size != 1 ||
            BookText.normalized(heading.text()) != "отпуст") return false
        var next = node.nextSibling()
        while (next != null && blank(next)) next = next.nextSibling()
        return next == null || next is Element && next.tagName() in setOf("h1", "h2", "h3")
    }

    /** Two exact italic runs repaired out of their signed hymn paragraphs by Jsoup. */
    private fun isPublishedDoxologyRun(node: TextNode): Boolean {
        val italic = node.parent() as? Element ?: return false
        if (italic.tagName() != "i" || italic.childNodeSize() != 1 || italic.childNode(0) !== node) return false
        when (node.wholeText) {
            "Сла́ва Отцу́, и Сы́ну, и Свято́му Ду́ху.",
            "И ны́не, и при́сно, и во ве́ки веко́в. Ами́нь." -> Unit
            else -> return false
        }
        val region = italic.parent() ?: return false
        if (region.tagName() != "div" || !region.hasClass("variable_text")) return false
        fun adjacent(before: Boolean): Node? {
            var sibling = if (before) italic.previousSibling() else italic.nextSibling()
            // Skip whitespace only, never another element, comment or editorial text.
            while (sibling is TextNode && sibling.wholeText.isBlank())
                sibling = if (before) sibling.previousSibling() else sibling.nextSibling()
            return sibling
        }
        fun signedParagraph(value: Node?): Boolean {
            val paragraph = value as? Element ?: return false
            if (paragraph.tagName() != "p") return false
            val labels = paragraph.select("label.bg_worships_info")
            return labels.size == 1 && labels.single().parent() === paragraph
        }
        return signedParagraph(adjacent(before = true)) && signedParagraph(adjacent(before = false))
    }

    private fun selected(document: Document, id: String): Element? = document.selectFirst("#$id option[selected]")
        ?: document.selectFirst("#$id option")

    private fun selectedEventTitle(document: Document, id: String, strictTooltip: Boolean = false): String? {
        val option = selected(document, id) ?: return null
        val label = BookText.clean(option.text()).takeIf { it.isNotEmpty() && it != "-" } ?: return null
        val shortened = Regex("(?:\\.\\.\\.|…)$")
        if (!shortened.containsMatchIn(label)) return label
        val prefix = BookText.normalized(shortened.replace(label, ""))
        // A tiny shared label such as 'св...' cannot identify which event the tooltip names.
        if (prefix.length < 24) return label
        val control = document.selectFirst("#$id") ?: return label
        val instruction = when (id) {
            "bg_worships_mainEvent" -> "Выбрать основное событие"
            "bg_worships_secondEvent" -> "Выбрать второе событие"
            else -> return label
        }
        val tooltip = control.attr("title")
        if (tooltip.length > 2_000 || tooltip.any { it == '<' || it == '>' || (it.isISOControl() && it !in "\r\n\t") }) return label
        val cleaned = BookText.clean(tooltip)
        if (!cleaned.startsWith("$instruction ")) return label
        val full = cleaned.removePrefix("$instruction ")
        // Some ranked responses contain an unclosed title attribute. Keep the
        // published shortened label instead of inventing the missing event name.
        if (strictTooltip && !balancedParentheses(full)) return label
        val normalized = BookText.normalized(full)
        if (full.length > 1_000 || shortened.containsMatchIn(full) || normalized.length <= prefix.length || !normalized.startsWith(prefix)) return label
        // A stale tooltip may belong to another option. A full label must agree with the
        // selected prefix and must not also identify a different entry in this control.
        val ambiguous = control.select("option").any { other ->
            if (other === option) false else {
                val otherLabel = BookText.clean(other.text())
                val otherPrefix = BookText.normalized(shortened.replace(otherLabel, ""))
                otherPrefix.isNotEmpty() && if (shortened.containsMatchIn(otherLabel)) normalized.startsWith(otherPrefix)
                    else normalized == otherPrefix
            }
        }
        return if (ambiguous) label else full
    }

    private fun balancedParentheses(value: String): Boolean {
        var depth = 0
        for (character in value) {
            if (character == '(') depth++
            if (character == ')' && --depth < 0) return false
        }
        return depth == 0
    }

    private fun options(document: Document, id: String): List<BookVariantOption> = document.select("#$id option").map {
        BookVariantOption(it.attr("value"), BookText.clean(it.text()))
    }

    private fun isRubric(text: String): Boolean {
        val normalized = BookText.normalized(text)
        if (normalized in verifiedRussianRubrics) return true
        if (normalized.startsWith("в положенное время") || normalized.startsWith("по причащении же да хранит")) return true
        return Regex("^(диакон|священник|иерей|лик|чтец|хор|затем|и священник|и диакон|и читаем|по окончании|при этом) ")
            .containsMatchIn(normalized) && Regex("\\b(читает|читают|произносит|поет|поют|возглашает|говорит|поклоняется|совершает|кадит|открывает|целует|отвечает|молится|предлагает)\\b")
            .containsMatchIn(normalized) && BookText.withoutSpeaker(text).first == null
    }

    // Whole Russian directions and standalone headings verified in published full services.
    // Matching the entire paragraph keeps a direction followed by prayer out of this whitelist.
    private val verifiedRussianRubrics = setOf(
        "Диакон же, поклонившись, отходит от своего места и стоит перед иконой Спасителя, держа орарь тремя перстами правой руки.",
        "Здесь открываются Царские врата на малый вход.",
        "Затем диакон подает священнику для целования св. Евангелие.",
        "И, сотворив поклон, входит в св. алтарь, а за ним и священник. Диакон кладет св. Евангелие на св. престол.",
        "Также диакон обводит орарем стоящих во храме, возглашая к ним громогласно:",
        "Во время пения священник и диакон и сами читают Трисвятое, совершая и три поклона перед св. престолом.",
        "Во время пения прокимна и чтения Апостола диакон кадит престол, алтарь, служащих, а также иконостас и весь народ.",
        "И подает ему святое Евангелие.",
        "Когда певцы, поющие третий антифон (или, в воскресенье, блаженны), доходят до слов “Сла́ва Отцу́ и Сы́ну и Свято́му Ду́ху:”, священник с диаконом трижды кланяются пред святым престолом. Священник, взяв св. Евангелие, дает его диакону и выйдя северными вратами, предшествуемые свещеносцами со светильниками, совершают малый вход. И, став на обычном месте, преклоняют оба главы.",
        "Если будет приношение о усопших, диакон или священник произносит следующую ектению:",
        "И раскрывает антиминс священник.",
        "(Когда священник служит без диакона, он следующих [четырех] прошений не произносит)",
        "И входит диакон в алтарь северными вратами.",
        "И открываются Царские врата.",
        "Во время пения Херувимской песни диакон, взяв кадило и вложив в него ладан, подходит к священнику и, взяв у него благословение, кадит святой престол вокруг, и весь алтарь, и иконостас; также священника, клиросы и народ. Читает же и 50 псалом, и тропари умилительные, сколько захочет. Священник же читает тайно следующую молитву:",
        "И священник, взяв воздух, возлагает его на левое плечо диакона, со словами:",
        "Также, взяв дискос, подает его диакону, который держит его на уровне головы. Сам же берет святой потир в руки и оба выходят через северные врата, предшествуемые свещеносцами.",
        "И ставит священник святой потир на св. престол, говоря:",
        "Священник же поднимает воздух и держит над святыми Дарами. Если же будут и другие служащие священники, они также поднимают святой воздух и держат его, колебля над святыми Дарами и произнося в себе, как и народ, исповедание веры:",
        "Диакон во время возглашения, взяв звездицу с дискоса, творит ей знак креста над ним.",
        "Диакон при этих словах, взяв в руки святой потир и дискос, возносит их над престолом.",
        "И кадит перед святым престолом трижды.",
        "И поминает усопших верных, кого хочет, по именам.",
        "И по окончании пения Достойно есть: или Задостойника, священник возглашает:",
        "И поминает по именам живых, кого хочет.",
        "И певцы поют причастен дня или святого по уставу.",
        "Священник же, взяв Святой Хлеб, дает диакону. Диакон же, принимает Святой Хлеб, со словами:",
        "Также и священник, взяв частицу Святого Хлеба, произносит:",
        "Если есть желающие причаститься Святых Таин, священник раздробляет две оставшиеся части Святого Агнца с надписанием NI и КА на небольшие частицы, чтобы хватило причастникам и влагает их в святой потир.",
        "И покрывает святый потир покровцем. (Если нет причастников, священник погружает все частицы со св. дискоса в св. чашу с установленными словами и возлагает на святой дискос звездицу и покровцы.)",
        "И приступают желающие причаститься один за другим, поклоняясь со всяким благоговением и страхом, имея руки, сложенными на груди. И принимает каждый Божественные Тайны. Священник же, причащая каждого произносит:",
        "После причащения священник входит в святой алтарь и ставит св. Чашу со Святыми Дарами на святой престол.",
        "Священник же, сложив антиминс и держа над ним святое Евангелие, начертывает им крест.",
        "Тропа́рь свт. Иоа́нну Златоу́стому, гла́с 8-й",
        "Конда́к, гла́с 6-й",
        "Священник, начиная каждение у престола:",
        "Священник, изображая кадилом крест перед престолом:",
        "И начинаем шестопсалмие, слушая со всяким молчанием и умилением; читающий же брат с благоговением и страхом Божиим произносит:",
        "Молитва первая:",
        "Молитва вторая:",
        "Молитва третия:",
        "Молитва четвертая:",
        "Молитва пятая:",
        "Молитва шестая:",
        "Молитва седмая:",
        "Молитва осмая:",
        "Молитва девятая:",
        "Молитва десятая:",
        "Молитва перваянадесять:",
        "Молитва втораянадесять:",
        "И поем на глас тропаря дня:",
        "Во время пения полиелея духовенство выходит царскими вратами в центр храма.",
        "Далее духовенство (священник в предшествии диакона) совершает полное каждение храма, начиная с центрального аналоя.",
        "Если сегодня служба святого (или храм святого), который указан выше в прошении, то далее повторно его не поминаем."
    ).map(BookText::normalized).toSet()

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
