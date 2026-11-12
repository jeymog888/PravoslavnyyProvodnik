package ru.liturgyguide.service.book

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import ru.liturgyguide.service.SinglePreferenceCommit
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookChapter

enum class BookReadingMode(val title: String) { ORIGINAL("Оригинал"), RUSSIAN("Русский"), MEANING("Смысл") }
enum class BookAnchorKind { HEADER, BLOCK, END }

data class BookReadingPosition(
    val key: ServiceBookKey,
    val contentHash: String,
    val parishId: String?,
    val blockId: String,
    val chapterId: String,
    val fraction: Float = 0f,
    val mode: BookReadingMode = BookReadingMode.ORIGINAL,
    val wordsPerMinute: Int = 85,
    val showAltar: Boolean = false,
    val anchorKind: BookAnchorKind = BookAnchorKind.BLOCK,
    /** Last actual text language, including when a saved reader is in Meaning. */
    val lastTextMode: BookReadingMode? = null,
    val journeyContext: BookJourneyContext? = null,
    /** Progress through the following inter-item space, separate from the item's text. */
    val followingGapFraction: Float = 0f
)

object BookReadingCodec {
    private val gson = Gson()
    fun decode(json: String?): BookReadingPosition? {
        if (json.isNullOrBlank() || json.length > 8192) return null
        return runCatching {
            val value = JsonParser.parseString(json).asJsonObject
            val journey = value.remove("journeyContext")
            if (value.has("followingGapFraction") && value.get("followingGapFraction").let {
                    !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber
                }) return null
            val position = gson.fromJson(value, BookReadingPosition::class.java).takeIf {
                it.key.isValid() && it.key.worship != null && it.mode != null && it.anchorKind != null &&
                    it.lastTextMode != BookReadingMode.MEANING &&
                    it.contentHash.matches(Regex("[0-9a-f]{64}")) &&
                    it.blockId.length in 1..180 && it.chapterId.length in 1..180 &&
                    (it.parishId == null || it.parishId.length in 1..160) &&
                    it.fraction.isFinite() && it.fraction in 0f..1f &&
                    it.followingGapFraction.isFinite() && it.followingGapFraction in 0f..1f &&
                    (it.followingGapFraction == 0f || it.fraction == 1f && it.anchorKind != BookAnchorKind.END) &&
                    it.wordsPerMinute in BookAutoScroll.MIN_RATE..BookAutoScroll.MAX_RATE
            } ?: return null
            position.copy(journeyContext = BookJourneyCodec.decode(journey, position.key, position.parishId))
        }.getOrNull()
    }
    fun encode(position: BookReadingPosition): String = gson.toJsonTree(position).asJsonObject.apply {
        // A new gap coordinate is optional. Ordinary legacy positions retain their
        // exact serialized form, avoiding a rewrite merely because the app was updated.
        if (position.followingGapFraction == 0f) remove("followingGapFraction")
    }.let { gson.toJson(it) }.also { require(decode(it) != null) }
}

data class BookViewport(val chapterId: String, val blockId: String, val fraction: Float, val kind: BookAnchorKind,
    val followingGapFraction: Float = 0f)

/** One semantic cursor owns both languages and the temporary chapter-level meaning view. */
class BookReadingCursor(initial: BookReadingPosition) {
    var value: BookReadingPosition = initial
        private set

    fun observe(viewport: BookViewport, mode: BookReadingMode) {
        value = if (mode == BookReadingMode.MEANING) {
            if (viewport.chapterId == value.chapterId) value.copy(mode = mode)
            else value.copy(chapterId = viewport.chapterId, blockId = viewport.blockId,
                fraction = 0f, followingGapFraction = 0f, anchorKind = BookAnchorKind.HEADER, mode = mode)
        } else value.copy(chapterId = viewport.chapterId, blockId = viewport.blockId,
            fraction = viewport.fraction.coerceIn(0f, 1f), followingGapFraction = viewport.followingGapFraction,
            anchorKind = viewport.kind, mode = mode)
    }

    fun seek(chapterId: String, blockId: String, kind: BookAnchorKind = BookAnchorKind.BLOCK, fraction: Float = 0f) {
        value = value.copy(chapterId = chapterId, blockId = blockId, anchorKind = kind,
            fraction = fraction.coerceIn(0f, 1f), followingGapFraction = 0f)
    }

    fun snapshot(mode: BookReadingMode, wordsPerMinute: Int, showAltar: Boolean): BookReadingPosition =
        value.copy(mode = mode, wordsPerMinute = wordsPerMinute, showAltar = showAltar).also { value = it }
}

object BookPositionUpdate {
    fun rebase(position: BookReadingPosition, previous: ServiceBook, next: ServiceBook): BookReadingPosition {
        require(position.key == previous.key && previous.key == next.key)
        val exact = next.blocks.firstOrNull { it.id == position.blockId }
        val old = previous.blocks.firstOrNull { it.id == position.blockId }
        val oldChapter = previous.chapters.firstOrNull { old != null && old in it.blocks }
        val sameChapter = next.chapters.firstOrNull { it.id == oldChapter?.id }
        // Correcting a rubric changes its content-derived ID. Match only an unambiguous,
        // verbatim original in the same chapter and altar/public scope, never a similar prayer.
        val migrated = if (exact == null && old != null && sameChapter != null &&
            oldChapter?.blocks?.count { it.original == old.original && it.isAltarText == old.isAltarText } == 1) {
            val whole = sameChapter.blocks.filter { it.original == old.original && it.isAltarText == old.isAltarText }
            val split = sameChapter.blocks.zipWithNext().filter { (rubric, prayer) ->
                rubric.kind == BookBlockKind.RUBRIC && prayer.kind == BookBlockKind.PRAYER &&
                    rubric.isAltarText == old.isAltarText && prayer.isAltarText == old.isAltarText &&
                    "${rubric.original} ${prayer.original}" == old.original
            }.map { it.first }
            (whole + split).singleOrNull()
        } else null
        val matched = exact ?: migrated
        val chapter = next.chapters.firstOrNull { item -> matched?.let { it in item.blocks } == true }
            ?: next.chapters.firstOrNull { it.id == position.chapterId } ?: next.chapters.first()
        val block = matched ?: chapter.blocks.first()
        val textMode = if (position.mode == BookReadingMode.MEANING)
            position.lastTextMode ?: BookReadingMode.ORIGINAL else position.mode
        fun visibleText(value: ru.liturgyguide.model.BookBlock) =
            if (textMode == BookReadingMode.RUSSIAN) value.russian ?: value.original else value.original
        val unchanged = old != null && matched != null && visibleText(old) == visibleText(matched)
        return position.copy(contentHash = next.contentHash, chapterId = chapter.id, blockId = block.id,
            fraction = if (unchanged && position.anchorKind == BookAnchorKind.BLOCK) position.fraction else 0f,
            followingGapFraction = if (unchanged && position.anchorKind == BookAnchorKind.BLOCK) position.followingGapFraction else 0f,
            anchorKind = if (matched != null) position.anchorKind else BookAnchorKind.HEADER)
    }
}

/** Separate from the published 0.5-0.7 playback formats: old bookmarks remain recoverable. */
class BookReadingStore(private val preferences: SharedPreferences) {
    fun last(parishId: String?): BookReadingPosition? =
        BookReadingCodec.decode(preferences.getString(lastKey(parishId), null))?.takeIf { it.parishId == parishId }

    /** The selected date has its own accepted edition, even after reading another service. */
    fun positionFor(key: ServiceBookKey, parishId: String?): BookReadingPosition? =
        BookReadingCodec.decode(preferences.getString(positionKey(key, parishId), null))?.takeIf {
            it.parishId == parishId && it.key == key
        }

    fun restore(book: ServiceBook, parishId: String?): BookReadingPosition? =
        positionFor(book.key, parishId)?.takeIf {
            book.blocks.any { block -> block.id == it.blockId }
        }?.let {
            // An explicit source refresh can retain the fragment, but not an offset in changed prose.
            if (it.contentHash == book.contentHash) it else it.copy(contentHash = book.contentHash, fraction = 0f, followingGapFraction = 0f)
        }

    fun save(position: BookReadingPosition): Boolean = synchronized(preferences) {
        runCatching {
            val json = BookReadingCodec.encode(position)
            val key = positionKey(position.key, position.parishId)
            val changes = linkedMapOf<String, Any?>(key to json, lastKey(position.parishId) to json)
            val keys = preferences.all.keys.filter { it.startsWith("position:") }
            if (key !in keys && keys.size >= 32) keys.firstOrNull()?.let { changes[it] = null }
            // Two reading pointers (and at most one evicted old position) form one
            // acceptance. A failed disk report must not leave the new hash in memory.
            SinglePreferenceCommit.write(preferences, changes)
        }.getOrDefault(false)
    }

    fun acceptVersion(previous: ServiceBook, next: ServiceBook, parishId: String?,
        currentPosition: BookReadingPosition? = null): Boolean = synchronized(preferences) {
        if (currentPosition != null && (currentPosition.key != previous.key ||
                currentPosition.contentHash != previous.contentHash || currentPosition.parishId != parishId))
            return@synchronized false
        val position = currentPosition ?: restore(previous, parishId) ?: return@synchronized true
        runCatching { save(BookPositionUpdate.rebase(position, previous, next)) }.getOrDefault(false)
    }

    private fun positionKey(key: ServiceBookKey, parishId: String?) =
        "position:" + BookText.sha256("${parishId.orEmpty()}|${key.canonical()}")
    private fun lastKey(parishId: String?) = "last:" + BookText.sha256(parishId.orEmpty())
}

data class BookSearchHit(val chapterId: String, val chapterTitle: String, val blockId: String, val excerpt: String,
    val mode: BookReadingMode = BookReadingMode.ORIGINAL, val matchStart: Int = 0, val matchEndExclusive: Int = 0)

object BookSearch {
    fun find(book: ServiceBook, query: String, preferredMode: BookReadingMode = BookReadingMode.RUSSIAN,
        checkActive: () -> Unit = {}): List<BookSearchHit> =
        scan(book, query, preferredMode, checkActive).sortedBy { it.first.ordinal }.map { it.second }

    /** Source-order matches retain their quality for one global sort across related books. */
    internal fun scan(book: ServiceBook, query: String, preferredMode: BookReadingMode,
        checkActive: () -> Unit): List<Pair<BookTextMatchKind, BookSearchHit>> {
        if (BookText.normalized(query).length < 2) return emptyList()
        val candidates = mutableListOf<Pair<BookTextMatchKind, BookSearchHit>>()
        for (chapter in book.chapters) for (index in chapter.blocks.indices) {
            checkActive()
            val block = chapter.blocks[index]
            val original = BookSearchText.find(block.original, query)
            val russian = block.russian?.let { BookSearchText.find(it, query) }
            val inRussian = russian != null && (original == null || russian.kind < original.kind ||
                russian.kind == original.kind && preferredMode == BookReadingMode.RUSSIAN)
            val match = (if (inRussian) russian else original) ?: continue
            val mode = if (inRussian) BookReadingMode.RUSSIAN else BookReadingMode.ORIGINAL
            val text = if (inRussian) block.russian.orEmpty() else block.original
            candidates += match.kind to BookSearchHit(chapter.id, chapter.title, block.id,
                contextualExcerpt(chapter, index, text, match.start, mode), mode, match.start, match.endExclusive)
        }
        // A common response can occur more than thirty times. Keep later occurrences
        // accessible, and do not let early unordered words hide a later exact phrase.
        checkActive()
        return candidates
    }

    private fun contextualExcerpt(chapter: BookChapter, index: Int, text: String, matchStart: Int,
        mode: BookReadingMode): String {
        if (text.length > 100) return excerpt(text, matchStart)
        val current = BookText.normalized(text)
        // Nearby context only, with bounded work even for thousands of identical responses.
        for (previousIndex in index - 1 downTo (index - 8).coerceAtLeast(0)) {
            val previous = chapter.blocks[previousIndex]
            if (previous.isAltarText || previous.kind !in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE)) continue
            // Do not borrow an older translated petition across an untranslated one.
            val source = (if (mode == BookReadingMode.RUSSIAN) previous.russian else previous.original) ?: break
            val normalized = BookText.normalized(source)
            if (normalized == current || normalized.split(' ').size < 3) continue
            return text + "\nПеред этим: " + excerpt(source, source.length, 120)
        }
        return text
    }

    /** Show the same source occurrence that the reader will open, including accents. */
    private fun excerpt(text: String, matchStart: Int, maxLength: Int = 240): String {
        if (text.length <= maxLength) return text
        val tokens = Regex("[\\p{L}\\p{N}\\p{M}]+").findAll(text).toList()
        val desiredStart = (matchStart - 60).coerceAtLeast(0)
        val start = tokens.lastOrNull { it.range.first <= desiredStart }?.range?.first ?: 0
        val limit = (start + maxLength - 4).coerceAtMost(text.length)
        val end = if (limit == text.length) limit else tokens.lastOrNull { it.range.last < limit }?.range?.last?.plus(1) ?: limit
        return (if (start > 0) "… " else "") + text.substring(start, end).trim() + if (end < text.length) " …" else ""
    }
}
