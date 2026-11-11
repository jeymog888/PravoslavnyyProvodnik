package ru.liturgyguide.service.book

import org.jsoup.nodes.Document
import ru.liturgyguide.model.BookSource

/** Sinodal verses are indexed by their exact book/chapter/verse ids, never by page order. */
class BookScripture private constructor(private val verses: Map<String, String>, private val completePassages: Map<String, List<String>>) {
    fun russian(reference: String): BookTranslation? {
        val range = Passage.parse(reference) ?: return null
        if (range.firstChapter != range.lastChapter) {
            // A range crossing a chapter cannot infer its final verse from a truncated response.
            // Accept only a whole source section whose explicit row count and endpoints agree.
            val ids = completePassages[reference] ?: return null
            val texts = ids.map { verses[it] ?: return null }
            return translated(reference, texts)
        }
        val selected = mutableListOf<String>()
        for (chapter in range.firstChapter..range.lastChapter) {
            val inChapter = verses.keys.mapNotNull { Verse.parse(it) }.filter { it.book == range.book && it.chapter == chapter }
            val start = if (chapter == range.firstChapter) range.firstVerse else 1
            val end = if (chapter == range.lastChapter) range.lastVerse else inChapter.maxOfOrNull { it.verse } ?: return null
            if (end < start || end - start > 200) return null
            for (verse in start..end) selected += verses["${range.book}.$chapter:$verse"] ?: return null
        }
        if (selected.isEmpty()) return null
        return translated(reference, selected)
    }

    private fun translated(reference: String, texts: List<String>) = BookTranslation(texts.joinToString("\n\n"),
        BookSource("Библия, Синодальный перевод", "https://azbyka.ru/biblia/?$reference&r"))

    companion object {
        fun empty() = BookScripture(emptyMap(), emptyMap())
        fun fromPage(document: Document): BookScripture {
            val result = linkedMapOf<String, String>()
            val conflicted = mutableSetOf<String>()
            for (element in document.select(".verse[data-lang=r][data-verse]")) {
                val id = element.attr("data-verse")
                if (Verse.parse(id) == null || id in conflicted) continue
                val clean = element.clone()
                clean.select(".zachala,.icon-check,.parallel,.verse-fullnumber,[aria-hidden=true],.bg_data_tooltip,script,style").remove()
                val text = BookText.clean(clean.text()).removePrefix("[]").removePrefix("[ ]").trim()
                if (text.isBlank()) continue
                val old = result[id]
                // A conflicting duplicate cannot silently change an already selected verse.
                if (old == null) result[id] = text else if (old != text) { result.remove(id); conflicted += id }
            }
            val complete = linkedMapOf<String, List<String>>()
            for (table in document.select(".tbl-content[data-book-slug]")) {
                val reference = table.previousElementSibling()?.selectFirst("a[href*=/biblia/?]")?.attr("href")
                    ?.substringAfter('?')?.substringBefore('&') ?: continue
                val passage = Passage.parse(reference) ?: continue
                val ids = table.select(".verse[data-lang=r][data-verse]").map { it.attr("data-verse") }.distinct()
                val declared = Regex("repeat\\(\\s*(\\d+)\\s*,").find(table.attr("style"))?.groupValues?.get(1)?.toIntOrNull()
                if (declared == null || declared - 1 != ids.size || ids.any { it !in result }) continue
                val points = ids.mapNotNull(Verse::parse)
                if (points.size != ids.size || points.firstOrNull() != Verse(passage.book, passage.firstChapter, passage.firstVerse) ||
                    points.lastOrNull() != Verse(passage.book, passage.lastChapter, passage.lastVerse)) continue
                if (points.zipWithNext().any { (a, b) -> a.book != b.book ||
                        !(b.chapter == a.chapter && b.verse == a.verse + 1 || b.chapter == a.chapter + 1 && b.verse == 1) }) continue
                complete[reference] = ids
            }
            return BookScripture(result, complete)
        }
    }

    private data class Verse(val book: String, val chapter: Int, val verse: Int) {
        companion object {
            fun parse(value: String): Verse? = Regex("^([1-3]?[A-Za-z]+)\\.(\\d{1,3}):(\\d{1,3})$").matchEntire(value)?.let {
                Verse(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }
        }
    }

    private data class Passage(val book: String, val firstChapter: Int, val firstVerse: Int, val lastChapter: Int, val lastVerse: Int) {
        companion object {
            fun parse(value: String): Passage? = runCatching {
                val match = requireNotNull(Regex("^([1-3]?[A-Za-z]+)\\.(\\d{1,3}):(\\d{1,3})(?:-(?:(\\d{1,3}):)?(\\d{1,3}))?$").matchEntire(value))
                val firstChapter = match.groupValues[2].toInt()
                val firstVerse = match.groupValues[3].toInt()
                val lastChapter = match.groupValues[4].toIntOrNull() ?: firstChapter
                val lastVerse = match.groupValues[5].toIntOrNull() ?: firstVerse
                require(firstChapter > 0 && firstVerse > 0 && lastVerse > 0 && lastChapter in firstChapter..firstChapter + 5)
                Passage(match.groupValues[1], firstChapter, firstVerse, lastChapter, lastVerse)
            }.getOrNull()
        }
    }
}
