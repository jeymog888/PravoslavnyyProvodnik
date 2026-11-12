package ru.liturgyguide.service.book

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookChapter
import ru.liturgyguide.model.BookRankSelection
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey

/** A verified default selection is required before the source can apply the requested rank. */
data class ResolvedMatinsRankRequest internal constructor(val key: ServiceBookKey, val mainEvent: String) {
    init {
        require(key.isValid() && key.topLevel == "3")
        require(mainEvent.matches(Regex("[0-9]{1,6}")))
    }

    val url: String get() = key.copy(topLevel = null, mainEvent = mainEvent).sourceUrl() + "&top_level=3"
}

/** A higher holiday order must not be silently reduced to the requested polyeleos order. */
internal class UnsupportedMatinsRank : IllegalArgumentException()

internal object MatinsRankSource {
    private val eventId = Regex("[0-9]{1,6}")
    private val optionalEventId = Regex("[0-9]{0,6}")

    fun resolve(document: Document, key: ServiceBookKey): ResolvedMatinsRankRequest {
        requireRankedKey(key)
        validateCommonControls(document, key)
        val main = selected(document, "bg_worships_mainEvent")
        val id = main.attr("value")
        if (!eventId.matches(id)) invalid("Источник не выбрал основную память")
        val second = selected(document, "bg_worships_secondEvent").attr("value")
        if (!optionalEventId.matches(second)) invalid("Источник вернул неизвестную дополнительную память")
        when (main.attr("data-level")) {
            "1", "2" -> throw UnsupportedMatinsRank()
            "3", "4", "5", "6" -> Unit
            else -> invalid("Источник не указал праздничный порядок")
        }
        // The source UI may disable its switch, but the verified public request still
        // works for these days. The returned rank and text are checked independently.
        return ResolvedMatinsRankRequest(key, id)
    }

    fun validate(document: Document, key: ServiceBookKey, request: ResolvedMatinsRankRequest): BookRankSelection {
        requireRankedKey(key)
        if (request.key != key) invalid("Выбран другой вариант службы")
        validateCommonControls(document, key)
        val main = selected(document, "bg_worships_mainEvent")
        val second = selected(document, "bg_worships_secondEvent")
        if (main.attr("value") != request.mainEvent) invalid("Источник вернул другую основную память")
        if (main.attr("data-level") != "3") invalid("Источник не применил полиелейный порядок")
        if (second.attr("value").isNotEmpty()) invalid("Источник сохранил другое дополнительное событие")
        return BookRankSelection(main.attr("value"), second.attr("value"), main.attr("data-level"))
    }

    /** Used both after parsing and when a saved edition is read from disk. */
    fun validBook(book: ServiceBook): Boolean = runCatching {
        if (!book.key.isValid()) return false
        if (book.key.topLevel == null) return book.rankSelection == null
        requireRankedKey(book.key)
        val selection = book.rankSelection ?: return false
        if (selection.topLevel != "3" || selection.secondEvent != "" || !eventId.matches(selection.mainEvent)) return false
        if (book.sourceUrl != ResolvedMatinsRankRequest(book.key, selection.mainEvent).url) return false
        hasRankSections(book.chapters)
    }.getOrDefault(false)

    private fun hasRankSections(chapters: List<BookChapter>): Boolean {
        fun section(title: String) = chapters.singleOrNull { BookText.normalized(it.title) == title }
        fun prayers(chapter: BookChapter) = chapter.blocks.filter {
            it.kind == BookBlockKind.PRAYER || it.kind == BookBlockKind.SCRIPTURE
        }.joinToString(" ") { BookText.normalized(it.original) }
        val polyeleos = section("полиелей") ?: return false
        val gospel = section("чтение евангелия") ?: return false
        val doxology = section("славословие великое") ?: return false
        val psalms = prayers(polyeleos)
        // These openings identify the actual psalms and doxology. Headings, a menu
        // entry, or a rubric explaining the rite cannot stand in for their prayers.
        return "хвалите имя господне" in psalms && "исповедайтеся господеви" in psalms &&
            "слава в вышних богу" in prayers(doxology) &&
            gospel.blocks.any { it.kind == BookBlockKind.SCRIPTURE && it.original.isNotBlank() && it.scriptureReferences.isNotEmpty() }
    }

    private fun requireRankedKey(key: ServiceBookKey) {
        if (!key.isValid() || key.topLevel != "3") invalid("Неизвестный полиелейный вариант")
    }

    private fun validateCommonControls(document: Document, key: ServiceBookKey) {
        val date = document.select("#bg_worships_setDay").singleOrNull()
            ?: invalid("Источник не указал единственную дату")
        if (date.tagName() != "input" || date.attr("type") != "date" || date.attr("value") != key.date)
            invalid("Источник вернул другую дату")
        if (selected(document, "bg_worship_type").attr("value") != key.worship.queryValue)
            invalid("Источник вернул другой вид службы")
        if (selected(document, "bg_worship_size").attr("value") != "full")
            invalid("Источник вернул сокращенный текст")
        if (selected(document, "bg_worships_church").attr("value") != key.churchType)
            invalid("Источник вернул другой вариант храма")
    }

    private fun selected(document: Document, id: String): Element {
        val control = document.select("#$id").singleOrNull()
            ?: invalid("Источник вернул неоднозначные параметры службы")
        if (control.tagName() != "select") invalid("Источник вернул неизвестный параметр службы")
        val option = control.select("option[selected]").singleOrNull()
            ?: invalid("Источник не подтвердил выбранный параметр службы")
        val parent = option.parent()
        if (parent !== control && !(parent?.tagName() == "optgroup" && parent.parent() === control))
            invalid("Выбранный параметр не относится к своему полю")
        if (!option.hasAttr("value")) invalid("Источник не указал значение выбранного параметра")
        return option
    }

    private fun invalid(message: String): Nothing = throw InvalidServiceBook(message)
}
