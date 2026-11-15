package ru.liturgyguide.service.book

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.liturgyguide.model.*

/** Two complete editorial pairs, shared by the fresh DOM and saved-book boundaries. */
internal object BookFirstHourDismissal {
    data class Record(val entry: FeoriaTranslationCatalog.Entry, val meaning: String, val normalized: String,
        val russianSha256: String, val meaningSha256: String)
    val records = listOf(
        Record(FeoriaTranslationCatalog.Entry("hour1-small-dismissal-non-sunday",
            "Христо́с И́стинный Бог наш, моли́твами Пречи́стыя Своея́ Ма́тере, преподо́бных и богоно́сных оте́ц на́ших и всех святы́х, поми́лует и спасе́т нас, я́ко Благ и Человеколю́бец.",
            "Христос, истинный Бог наш, по молитвам Пречистой Своей Матери, преподобных и богоносных отцов наших и всех святых, помилует и спасет нас, потому что Он благ и любит людей", revision = 9, originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-21&worship=chas1&size=full&church=30"),
            "Эти слова выражают надежду на милость и спасение от Христа по молитвам Его Матери, преподобных отцов и всех святых", "христос истинный бог наш молитвами пречистыя своея матере преподобных и богоносных отец наших и всех святых помилует и спасет нас яко благ и человеколюбец",
            "86b1cee7a0f04e9ed6df8aa3aa4e3bbb28fad0d5dbe98503755f1c6bc2277de2", "a6b69fd5ba1b4b4b8ef22a88645ed35006a22d5bb95c610d953966a8b345a85e"),
        Record(FeoriaTranslationCatalog.Entry("hour1-small-dismissal-sunday",
            "Воскресы́й из ме́ртвых Христо́с И́стинный Бог наш, моли́твами Пречи́стыя Своея́ Ма́тере, преподо́бных и богоно́сных оте́ц на́ших и всех святы́х, поми́лует и спасе́т нас, я́ко Благ и Человеколю́бец.",
            "Воскресший из мертвых Христос, истинный Бог наш, по молитвам Пречистой Своей Матери, преподобных и богоносных отцов наших и всех святых, помилует и спасет нас, потому что Он благ и любит людей", revision = 9, originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-22&worship=chas1&size=full&church=30"),
            "Эти слова выражают надежду на милость и спасение от воскресшего Христа по молитвам Его Матери, преподобных отцов и всех святых", "воскресый из мертвых христос истинный бог наш молитвами пречистыя своея матере преподобных и богоносных отец наших и всех святых помилует и спасет нас яко благ и человеколюбец",
            "5a61807a8c522f551d6186b9e77e5445b7285ab17ccfe4ae2def172efa190e19", "a3698ec6fb5c4f76bd5b0bc76207cbd2b9a79234269990df8b2d8557524bf6a1")
    )
    val entries: List<FeoriaTranslationCatalog.Entry> = records.map { it.entry }
    init {
        require(records.size == 2 && records.map { it.normalized }.distinct().size == 2)
        require(records.all { BookText.normalized(it.entry.original) == it.normalized &&
            BookText.sha256(it.entry.russian) == it.russianSha256 && BookText.sha256(it.meaning) == it.meaningSha256 })
    }
    fun isReviewedMeaning(value: String): Boolean = records.any { it.meaning == value }
    fun forBody(original: String): Record? = records.singleOrNull { it.normalized == BookText.normalized(original) }
    fun recognizes(original: String, translation: BookTranslation): Boolean =
        translation.source == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE && translation.meaning == null &&
            forBody(original)?.entry?.russian == translation.russian

    private fun meaningful(nodes: List<Node>) = nodes.filterNot { it is TextNode && it.wholeText.isBlank() }
    private fun plain(element: Element, tag: String): Boolean = element.tagName() == tag &&
        element.attributes().size() == 0 && element.childNodes().size == 1 && element.childNode(0) is TextNode

    /** Source controls and completeness have already been checked by the parser. */
    fun terminalBody(content: Element, key: ServiceBookKey): TextNode? {
        if (key.worship != WorshipKind.HOUR1) return null
        val region = meaningful(content.childNodes()).lastOrNull() as? Element ?: return null
        if (region.tagName() != "div" || region.classNames() != setOf("variable_text") ||
            region.attributes().size() != 1) return null
        val children = meaningful(region.childNodes())
        if (children.size != 3) return null
        val heading = children[0] as? Element ?: return null
        val body = children[1] as? TextNode ?: return null
        val response = children[2] as? Element ?: return null
        if (!plain(heading, "h3") || BookText.normalized(heading.text()) != "отпуст" || forBody(body.text()) == null ||
            response.tagName() != "p" || response.attributes().size() != 0 ||
            BookText.normalized(response.text()) != "лик господи помилуй трижды") return null
        val reply = response.childNodes()
        if (reply.size != 4) return null
        val speaker = reply[0] as? Element ?: return null
        val words = reply[1] as? TextNode ?: return null
        val repeat = reply[2] as? Element ?: return null
        val stop = reply[3] as? TextNode ?: return null
        if (!plain(speaker, "strong") || speaker.text() != "Лик:" ||
            BookText.normalized(words.text()) != "господи помилуй" || !plain(repeat, "em") ||
            repeat.text() != "(Трижды)" || BookText.clean(stop.text()) != ".") return null
        if (content.select("h3").count { BookText.normalized(it.text()) == "отпуст" } != 1 ||
            content.getAllElements().sumOf { element -> element.textNodes().count { forBody(it.text()) != null } } != 1) return null
        return body
    }
}

/** Read-only evidence for one complete immutable edition. Hashing happens only in of(). */
class BookFirstHourDismissalScope private constructor(
    private val key: ServiceBookKey?, private val contentHash: String?, private val chapter: BookChapter?,
    private val body: BookBlock?, private val explanation: String?
) {
    private fun owns(book: ServiceBook): Boolean = key != null && book.key == key && book.contentHash == contentHash
    fun isLegacyPrayer(book: ServiceBook, block: BookBlock): Boolean = owns(book) && body == block && block.kind == BookBlockKind.RUBRIC
    fun meaning(book: ServiceBook, chapter: BookChapter, blockId: String? = null): String? =
        explanation.takeIf { owns(book) && chapter == this.chapter && (blockId == null || blockId == body?.id) }

    companion object {
        private val NONE = BookFirstHourDismissalScope(null, null, null, null, null)
        private const val SOURCE_TITLE = "Азбука веры: Богослужения сегодня"
        private val chapterId = Regex("(vespers|matins|chas1)-[a-f0-9]{12}-[1-9][0-9]*")
        fun of(book: ServiceBook): BookFirstHourDismissalScope = runCatching { checked(book) }.getOrDefault(NONE)

        private fun checked(book: ServiceBook): BookFirstHourDismissalScope {
            if (book.key.worship !in setOf(WorshipKind.HOUR1, WorshipKind.VIGIL) || !ServiceBookIntegrity.valid(book)) return NONE
            val key = book.key
            if (book.sourceUrl != key.sourceUrl()) return NONE
            val kinds = if (key.worship == WorshipKind.HOUR1) listOf(WorshipKind.HOUR1)
                else listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1)
            val expectedSources = kinds.map { BookSource(SOURCE_TITLE, key.copy(worship = it).sourceUrl()) }.toSet()
            val ownedSources = book.sources.filter { it.title == SOURCE_TITLE }
            if (ownedSources.size != expectedSources.size || ownedSources.toSet() != expectedSources) return NONE
            val componentIds = mutableListOf<String>()
            for (owner in book.chapters) {
                val family = chapterId.matchEntire(owner.id)?.groupValues?.get(1) ?: return NONE
                if (componentIds.lastOrNull() != family) componentIds += family
                if (owner.blocks.any { !it.id.startsWith(owner.id + ":") }) return NONE
            }
            if (componentIds != kinds.map { it.queryValue }) return NONE
            // Internal serialized-profile check, not reconstructed DOM provenance.
            // Reuse the parser's unchanged complete-ending checks; its choir label became "Хор".
            for (kind in kinds) {
                val owned = book.chapters.filter { it.id.startsWith(kind.queryValue + "-") }
                if (owned.sumOf { it.blocks.size } < 15) return NONE
                val sourceText = owned.joinToString(" ") { owner -> owner.title + " " + owner.blocks.joinToString(" ") {
                    (if (it.speaker == "Хор") "Лик: " else it.speaker?.let { speaker -> "$speaker: " }.orEmpty()) + it.original
                } }
                if (runCatching { ServiceBookParser().validateCompleteness(sourceText, kind) }.isFailure) return NONE
            }
            val chapter = book.chapters.last()
            if (!chapter.id.startsWith("chas1-") || BookText.normalized(chapter.title) != "отпуст" || chapter.blocks.size != 2 ||
                book.chapters.count { it.id.startsWith("chas1-") && BookText.normalized(it.title) == "отпуст" } != 1) return NONE
            val body = chapter.blocks[0]
            val response = chapter.blocks[1]
            if (chapter.blocks.any { it.isAltarText || it.scriptureReferences.isNotEmpty() } || body.speaker != null ||
                body.kind !in setOf(BookBlockKind.RUBRIC, BookBlockKind.PRAYER) ||
                response.kind != BookBlockKind.PRAYER || response.speaker != "Хор" ||
                BookText.normalized(response.original) != "господи помилуй трижды") return NONE
            val record = BookFirstHourDismissal.forBody(body.original) ?: return NONE
            if (book.blocks.count { BookFirstHourDismissal.forBody(it.original) != null } != 1) return NONE
            return BookFirstHourDismissalScope(key, book.contentHash, chapter.copy(blocks = chapter.blocks.toList()), body, record.meaning)
        }
    }
}
