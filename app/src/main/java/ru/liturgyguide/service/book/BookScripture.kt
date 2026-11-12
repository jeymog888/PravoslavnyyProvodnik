package ru.liturgyguide.service.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.liturgyguide.model.BookSource
import ru.liturgyguide.service.schedule.ScheduleTransport

/** Sinodal verses are indexed by their exact book/chapter/verse ids, never by page order. */
class BookScripture private constructor(
    private val verses: Map<String, String>,
    private val completePassages: Map<String, List<String>>,
    private val directPassages: Map<String, BookTranslation> = emptyMap()
) {
    fun russian(reference: String): BookTranslation? = dailyRussian(reference) ?: directPassages[reference]

    private fun dailyRussian(reference: String): BookTranslation? {
        val range = Passage.parse(reference) ?: return null
        if (range.firstChapter != range.lastChapter) {
            // A range crossing a chapter cannot infer its final verse from a truncated response.
            // Accept only a whole source section whose explicit row count and endpoints agree.
            val ids = completePassages[reference] ?: return null
            val texts = ids.map { verses[it] ?: return null }
            return translated(reference, texts)
        }
        val selected = (range.sameChapterIds() ?: return null).map { verses[it] ?: return null }
        return translated(reference, selected)
    }

    private fun translated(reference: String, texts: List<String>) = BookTranslation(texts.joinToString("\n\n"),
        BookSource("Библия, Синодальный перевод", "https://azbyka.ru/biblia/?$reference&r"))

    /** A direct page is a whole verified passage, never spare verses to repair a partial daily page. */
    internal fun withDirectPage(reference: String, document: Document, finalUrl: String): BookScripture {
        val requestedUrl = directUrl(reference) ?: return this
        if (finalUrl != requestedUrl || russian(reference) != null) return this
        val range = Passage.parse(reference) ?: return this
        val header = document.select("[id=column_header_r]").singleOrNull()
            ?.takeIf { it.hasClass("column-header") } ?: return this
        val label = header.select(".lang-label").singleOrNull()
            ?.takeIf { it.attr("data-lang-code") == "r" } ?: return this
        if (label.select(".lang-label__title").singleOrNull()?.text()?.let(BookText::clean) != "Синодальный") return this
        val table = header.parent()?.takeIf { it.hasClass("tbl-content") && it.attr("data-book-slug") == range.book }
            ?: return this
        val required = range.sameChapterIds() ?: return this
        val elements = table.children().filter {
            it.hasClass("verse") && it.attr("data-lang") == "r" && it.attr("data-verse") in required
        }
        // Even an identical duplicate is ambiguous in a direct response. Do not borrow a
        // missing verse from another chapter, another column, or another source response.
        if (elements.size != required.size || elements.map { it.attr("data-verse") }.toSet() != required.toSet()) return this
        // Broken row boundaries can nest a foreign verse/column inside a valid verse.
        // Reject the passage before text() flattens descendants; ordinary inline spans stay.
        if (elements.any { row -> row.select(".verse,[data-verse],[data-lang]").any { it !== row } }) return this
        val found = readVerses(elements)
        val texts = required.map { found[it] ?: return this }
        val russian = texts.joinToString("\n\n")
        // An invalid supplement must not prevent the otherwise valid book from being saved.
        if (russian.length > 80_000) return this
        val translation = BookTranslation(russian, BookSource("Библия, Синодальный перевод", finalUrl))
        return BookScripture(verses, completePassages, directPassages + (reference to translation))
    }

    internal fun withDirectPassages(other: BookScripture): BookScripture =
        BookScripture(verses, completePassages, other.directPassages + directPassages)

    companion object {
        fun empty() = BookScripture(emptyMap(), emptyMap())
        internal fun directUrl(reference: String): String? {
            val range = Passage.parse(reference) ?: return null
            if (range.sameChapterIds() == null) return null
            return "https://azbyka.ru/biblia/?$reference&c~r"
        }

        fun fromPage(document: Document): BookScripture {
            val result = readVerses(document.select(".verse[data-lang=r][data-verse]"))
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

        private fun readVerses(elements: Iterable<Element>): Map<String, String> {
            val result = linkedMapOf<String, String>()
            val conflicted = mutableSetOf<String>()
            for (element in elements) {
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
            return result
        }
    }

    private data class Verse(val book: String, val chapter: Int, val verse: Int) {
        companion object {
            fun parse(value: String): Verse? = Regex("^([1-3]?[A-Za-z]+)\\.(\\d{1,3}):(\\d{1,3})$").matchEntire(value)?.let {
                Verse(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }
        }
    }

    private data class Passage(val book: String, val firstChapter: Int, val firstVerse: Int, val lastChapter: Int,
        val lastVerse: Int, val selectedVerses: List<Int>? = null) {
        fun sameChapterIds(): List<String>? {
            if (firstChapter != lastChapter) return null
            val selected = selectedVerses ?: if (lastVerse >= firstVerse && lastVerse - firstVerse <= 200)
                (firstVerse..lastVerse).toList() else return null
            return selected.takeIf { it.size in 1..201 }?.map { "$book.$firstChapter:$it" }
        }

        companion object {
            fun parse(value: String): Passage? = runCatching {
                if (',' in value) {
                    val match = requireNotNull(Regex("^([1-3]?[A-Za-z]+)\\.(\\d{1,3}):(.+)$").matchEntire(value))
                    val chapter = match.groupValues[2].toInt()
                    require(chapter > 0)
                    val segments = match.groupValues[3].split(',', limit = 202)
                    require(segments.size in 2..201)
                    val selected = mutableListOf<Int>()
                    for (segment in segments) {
                        val part = requireNotNull(Regex("^(\\d{1,3})(?:-(\\d{1,3}))?$").matchEntire(segment))
                        val start = part.groupValues[1].toInt()
                        val end = part.groupValues[2].toIntOrNull() ?: start
                        // Source order is authoritative: do not sort, merge overlaps or fill gaps.
                        require(start > (selected.lastOrNull() ?: 0) && end >= start)
                        require(end - start + 1 <= 201 - selected.size)
                        selected += start..end
                    }
                    return@runCatching Passage(match.groupValues[1], chapter, selected.first(), chapter, selected.last(), selected)
                }
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

/** One bounded, cancellable extra phase per book. Daily-complete passages never use it. */
internal object BookScriptureFallback {
    /** Shared by sequential parts of a vigil, and discarded when this user load finishes. */
    class Session {
        internal val attempted = mutableSetOf<String>()
        internal var completed = BookScripture.empty()
        internal var remainingNanos = 20_000_000_000L
    }

    suspend fun load(base: BookScripture, references: List<String>, transport: ScheduleTransport,
        budgetMillis: Long = 20_000, session: Session = Session()): BookScripture {
        require(budgetMillis in 1..20_000)
        currentCoroutineContext().ensureActive()
        var result = base.withDirectPassages(session.completed)
        val pending = references.distinct().filter { result.russian(it) == null }
            .mapNotNull { reference -> BookScripture.directUrl(reference)?.let { reference to it } }
            .distinctBy { it.second }.filterNot { it.second in session.attempted }
            .take((6 - session.attempted.size).coerceAtLeast(0))
        val allowedMillis = minOf(budgetMillis, session.remainingNanos / 1_000_000)
        if (pending.isEmpty() || allowedMillis <= 0) return result
        val started = System.nanoTime()
        try {
            withTimeoutOrNull(allowedMillis) {
                for ((reference, url) in pending) {
                    currentCoroutineContext().ensureActive()
                    session.attempted += url
                    try {
                        val response = transport.fetchBounded(url, 2_097_152)
                        currentCoroutineContext().ensureActive()
                        if (response.url != url) continue
                        val enriched = withContext(Dispatchers.Default) {
                            response.html()?.let { result.withDirectPage(reference, it, response.url) }
                        }
                        currentCoroutineContext().ensureActive()
                        if (enriched != null) {
                            result = enriched
                            session.completed = session.completed.withDirectPassages(enriched)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A failed supplement leaves the original and prior complete passages intact.
                    }
                }
            }
        } finally {
            // Count only extra Scripture work. Source/common loading between vigil parts
            // consumes neither another twenty seconds nor this shared supplement budget.
            session.remainingNanos = (session.remainingNanos - (System.nanoTime() - started)).coerceAtLeast(0)
        }
        currentCoroutineContext().ensureActive()
        return result
    }
}
