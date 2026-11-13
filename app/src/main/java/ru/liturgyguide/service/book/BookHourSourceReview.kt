package ru.liturgyguide.service.book

import com.google.gson.Gson
import org.jsoup.nodes.Document
import ru.liturgyguide.model.*

enum class BookHourReviewStatus { NOT_APPLICABLE, UNKNOWN, CONFLICT, MATCHED_RULE }

data class BookHourReview(
    val status: BookHourReviewStatus,
    val expectedQuote: String? = null,
    val actualHeader: String? = null,
    val compositionOnly: Boolean = false
) {
    val needsReview: Boolean get() = status in setOf(BookHourReviewStatus.UNKNOWN, BookHourReviewStatus.CONFLICT)
    val blocksPlayback: Boolean get() = needsReview && !compositionOnly
    val message: String get() = when {
        compositionOnly -> "Перед Преждеосвященной литургией нужны также девятый час и изобразительны. Этот набор не включает их тексты"
        status == BookHourReviewStatus.CONFLICT -> "В тексте: $actualHeader. В богослужебных указаниях: $expectedQuote"
        status == BookHourReviewStatus.MATCHED_RULE -> "Предписание о кондаке сопоставлено с текстом часа. Это не проверка всего последования и местного порядка"
        else -> "Не удалось сопоставить кондак этого часа с богослужебными указаниями. Текст доступен для самостоятельного чтения"
    }
}

/** A bounded source annotation in the existing hash-covered notices. Old books are never rewritten. */
object BookHourSourceReview {
    internal const val PREFIX = "feoria-hour-source-review:"
    private val gson = Gson()
    private val apostles = "На часах - тропарь апостолов. «Слава» - тропарь мученика. Кондак апостолов."
    private val unmercenaries = "На часах - тропарь и кондак бессребреников."
    private val fields = setOf("version", "key", "mainEvent", "secondEvent", "eventTitle", "quote", "header", "fingerprint")
    private data class Evidence(val version: Int, val key: String, val mainEvent: String, val secondEvent: String,
        val eventTitle: String, val quote: String, val header: String, val fingerprint: String)

    fun isInternalNotice(value: String): Boolean = value.startsWith(PREFIX.removeSuffix(":"))
    fun guidanceUrl(book: ServiceBook) = "https://azbyka.ru/bogosluzhebnye-ukazaniya?date=${book.key.date}"
    private fun isDefaultHour(book: ServiceBook) = book.key.worship in setOf(WorshipKind.HOUR3, WorshipKind.HOUR6) &&
        book.key.mainEvent == null && book.key.secondEvent == null && book.key.patronalId == null && book.key.topLevel == null

    internal fun record(document: Document, book: ServiceBook): String? {
        if (!isDefaultHour(book)) return null
        val bu = document.select(".patriarchiaBU").singleOrNull() ?: return null
        val paragraph = bu.children().filter { it.tagName() == "p" &&
            BookText.normalized(it.text()).startsWith("на часах ") }.singleOrNull() ?: return null
        val quote = BookText.clean(paragraph.text())
        if (rule(quote) == null) return null
        val section = kondak(book) ?: return null
        fun selected(id: String): String? = document.select("#$id").singleOrNull()?.select("option[selected]")
            ?.singleOrNull()?.attr("value")?.takeIf { it.matches(Regex("[0-9]{0,6}")) }
        val main = selected("bg_worships_mainEvent") ?: return null
        val second = selected("bg_worships_secondEvent") ?: return null
        if (!selectionMatches(book, main, second)) return null
        return (PREFIX + gson.toJson(Evidence(1, book.key.canonical(), main, second, book.selectedEventTitle.orEmpty(),
            quote, section.first, fingerprint(section.second)))).takeIf { it.length <= 2_000 }
    }

    fun of(book: ServiceBook, inMorningJourney: Boolean = false): BookHourReview {
        if (inMorningJourney && book.key.worship == WorshipKind.LITURGY &&
            "преждеосвященных" in BookText.normalized(book.title))
            return BookHourReview(BookHourReviewStatus.UNKNOWN, compositionOnly = true)
        if (book.key.worship !in setOf(WorshipKind.HOUR3, WorshipKind.HOUR6))
            return BookHourReview(BookHourReviewStatus.NOT_APPLICABLE)
        // An explicit variant has not acquired the default selection's evidence. It
        // remains readable, but cannot bypass the warning or inherit its conflict.
        if (!isDefaultHour(book)) return unknown()
        return runCatching {
            val record = book.notices.filter(::isInternalNotice).singleOrNull() ?: return unknown()
            if (!record.startsWith(PREFIX)) return unknown()
            val raw = record.removePrefix(PREFIX)
            if (raw.length !in 1..6_000) return unknown()
            val data = StrictBookJson.objectValue(raw, maximumDepth = 2, maximumValues = 30)
            if (data.keySet() != fields || data.get("version")?.toString() != "1" || fields.filter { it != "version" }.any {
                    data.get(it)?.let { value -> !value.isJsonPrimitive || !value.asJsonPrimitive.isString } != false }) return unknown()
            val evidence = gson.fromJson(data, Evidence::class.java)
            val section = kondak(book) ?: return unknown()
            if (evidence.key != book.key.canonical() || evidence.eventTitle != book.selectedEventTitle.orEmpty() ||
                book.sources.none { it == BookSource("Богослужебные указания", guidanceUrl(book)) } ||
                listOf(evidence.quote, evidence.header, evidence.eventTitle).any { it.any(Char::isISOControl) } ||
                !selectionMatches(book, evidence.mainEvent, evidence.secondEvent) ||
                evidence.header != section.first || evidence.fingerprint != fingerprint(section.second)) return unknown()
            val expectation = rule(evidence.quote) ?: return unknown()
            val actual = BookText.normalized(evidence.header)
            val status = when {
                expectation == "apostles" && (actual.startsWith("кондак апостолов ") || actual.startsWith("кондак апп ")) -> BookHourReviewStatus.MATCHED_RULE
                expectation == "unmercenaries" && actual.startsWith("кондак бессребреников ") -> BookHourReviewStatus.MATCHED_RULE
                expectation == "apostles" && (actual.startsWith("кондак мч ") || actual.startsWith("кондак мученика ")) -> BookHourReviewStatus.CONFLICT
                else -> BookHourReviewStatus.UNKNOWN
            }
            BookHourReview(status, evidence.quote, evidence.header)
        }.getOrElse { unknown() }
    }

    private fun unknown() = BookHourReview(BookHourReviewStatus.UNKNOWN)
    private fun selectionMatches(book: ServiceBook, main: String, second: String): Boolean {
        if (listOf(main, second).any { !it.matches(Regex("[0-9]{0,6}")) }) return false
        val mainOption = book.mainEventOptions.singleOrNull { it.value == main } ?: return false
        val secondOption = book.secondEventOptions.singleOrNull { it.value == second } ?: return false
        val labels = listOf(mainOption.title, secondOption.title).filter { it.isNotBlank() && it != "-" }
        val titles = book.selectedEventTitle?.split(" · ").orEmpty()
        if (labels.size != titles.size) return false
        return labels.zip(titles).all { (label, title) ->
            if (label == title) true else {
                val ending = Regex("(?:\\.\\.\\.|…)$")
                val prefix = BookText.normalized(ending.replace(label, ""))
                ending.containsMatchIn(label) && prefix.length >= 24 && BookText.normalized(title).startsWith(prefix)
            }
        }
    }
    private fun rule(quote: String): String? = when (BookText.normalized(quote)) {
        BookText.normalized(apostles) -> "apostles"
        BookText.normalized(unmercenaries) -> "unmercenaries"
        else -> null
    }
    private fun kondak(book: ServiceBook): Pair<String, BookChapter>? {
        val section = book.chapters.singleOrNull { BookText.normalized(it.title) == "кондак" } ?: return null
        val heading = section.blocks.filter { it.kind == BookBlockKind.RUBRIC &&
            BookText.normalized(it.original).startsWith("кондак ") }.singleOrNull()?.original ?: return null
        if (section.blocks.none { it.kind == BookBlockKind.PRAYER && it.original.isNotBlank() } || heading.length !in 1..1_000) return null
        return heading to section
    }
    private fun fingerprint(chapter: BookChapter) = BookText.sha256(chapter.blocks.joinToString("") {
        listOf(it.kind.name, it.speaker.orEmpty(), it.isAltarText.toString(), it.original)
            .joinToString("") { text -> "${text.length}:$text" }
    })
}
