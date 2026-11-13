package ru.liturgyguide.service.book

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import ru.liturgyguide.service.SinglePreferenceCommit
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookChapter
import ru.liturgyguide.model.BookBlock

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

enum class BookDedicationMatchKind { EXACT, KNOWN_HYMN, SECTION_START }
data class BookDedicationPosition(val position: BookReadingPosition, val matchKind: BookDedicationMatchKind)

object BookPositionUpdate {
    fun rebase(position: BookReadingPosition, previous: ServiceBook, next: ServiceBook): BookReadingPosition {
        require(position.key == previous.key && previous.key == next.key)
        return rebaseContent(position, previous, next).position
    }

    /** EXACT identifies the same source occurrence; changed visible prose still resets its fraction. */
    fun rebaseForDedication(position: BookReadingPosition, previous: ServiceBook, next: ServiceBook,
        journeyContext: BookJourneyContext? = null): BookDedicationPosition {
        require(position.key == previous.key && position.contentHash == previous.contentHash)
        require(previous.key.date == next.key.date && previous.key.worship == next.key.worship &&
            previous.key.topLevel == next.key.topLevel)
        require(ServiceBookIntegrity.valid(previous) && ServiceBookIntegrity.valid(next) &&
            next.patronalSelection?.matches(next.key) == true)
        require(BookReadingCodec.decode(BookReadingCodec.encode(position)) != null)
        require(previous.chapters.any { it.id == position.chapterId && it.blocks.any { block -> block.id == position.blockId } })
        val oldJourney = position.journeyContext
        require(oldJourney?.validatedFor(previous.key, position.parishId) == oldJourney &&
            journeyContext?.validatedFor(next.key, position.parishId) == journeyContext)
        require((oldJourney == null) == (journeyContext == null))
        if (oldJourney != null && journeyContext != null) require(
            oldJourney.parishId == journeyContext.parishId && oldJourney.civilDate == journeyContext.civilDate &&
                oldJourney.time == journeyContext.time && oldJourney.title == journeyContext.title &&
                oldJourney.effectivePartSet == journeyContext.effectivePartSet &&
                oldJourney.parts.zip(journeyContext.parts).all { (before, after) ->
                    before.date == after.date && before.worship == after.worship && before.topLevel == after.topLevel
                })
        val slot = knownTempleSlot(position, previous, next)
        val result = if (slot?.second?.kind == BookBlockKind.PRAYER) BookDedicationPosition(position.copy(contentHash = next.contentHash,
            chapterId = slot.first.id, blockId = slot.second.id, fraction = 0f, followingGapFraction = 0f,
            anchorKind = BookAnchorKind.BLOCK), BookDedicationMatchKind.KNOWN_HYMN)
        else rebaseContent(position, previous, next, verifiedLocalSlot = slot?.second,
            allowUnverifiedLocalIdentity = false)
        return result.copy(position = result.position.copy(key = next.key, journeyContext = journeyContext))
    }

    private fun rebaseContent(position: BookReadingPosition, previous: ServiceBook, next: ServiceBook,
        verifiedLocalSlot: BookBlock? = null, allowUnverifiedLocalIdentity: Boolean = true): BookDedicationPosition {
        val old = previous.blocks.firstOrNull { it.id == position.blockId }
        // Missing hymn descriptions repeat verbatim. Filling one renumbers the
        // other, so an old LOCAL_VARIATION ID alone cannot identify its owner.
        val ignoreLocalIdentity = !allowUnverifiedLocalIdentity && old?.kind == BookBlockKind.LOCAL_VARIATION
        val exact = verifiedLocalSlot ?: if (ignoreLocalIdentity) null else next.blocks.firstOrNull { it.id == position.blockId }
        val oldChapter = previous.chapters.firstOrNull { old != null && old in it.blocks }
        val sameChapter = next.chapters.firstOrNull { it.id == oldChapter?.id }
        // Correcting a rubric changes its content-derived ID. Match only an unambiguous,
        // verbatim original in the same chapter and altar/public scope, never a similar prayer.
        val migrated = if (!ignoreLocalIdentity && exact == null && old != null && sameChapter != null &&
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
        val rebased = position.copy(contentHash = next.contentHash, chapterId = chapter.id, blockId = block.id,
            fraction = if (unchanged && position.anchorKind == BookAnchorKind.BLOCK) position.fraction else 0f,
            followingGapFraction = if (unchanged && position.anchorKind == BookAnchorKind.BLOCK) position.followingGapFraction else 0f,
            anchorKind = if (matched != null) position.anchorKind else BookAnchorKind.HEADER)
        return BookDedicationPosition(rebased,
            if (matched != null) BookDedicationMatchKind.EXACT else BookDedicationMatchKind.SECTION_START)
    }

    /** Only the two verified temple hymn slots have a safe replacement anchor. */
    private fun knownTempleSlot(position: BookReadingPosition, previous: ServiceBook,
        next: ServiceBook): Pair<BookChapter, BookBlock>? {
        val oldChapter = previous.chapters.singleOrNull { it.id == position.chapterId } ?: return null
        val oldIndex = oldChapter.blocks.indexOfFirst { it.id == position.blockId }
        if (oldIndex < 0) return null
        val old = oldChapter.blocks[oldIndex]
        if (old.kind != BookBlockKind.LOCAL_VARIATION || old.isAltarText) return null
        val labels = setOf("тропарь храма", "кондак храма")
        val labelIndex = oldChapter.blocks.take(oldIndex).indexOfLast {
            it.kind == BookBlockKind.RUBRIC && !it.isAltarText && BookText.normalized(it.original) in labels
        }
        if (labelIndex < 0 || oldChapter.blocks.subList(labelIndex + 1, oldIndex).any {
                it.kind != BookBlockKind.RUBRIC || it.isAltarText
            }) return null
        val label = oldChapter.blocks[labelIndex]
        val chapter = next.chapters.singleOrNull { it.id == oldChapter.id } ?: return null
        val selectedLabel = chapter.blocks.singleOrNull { it.id == label.id && it.original == label.original &&
            it.kind == BookBlockKind.RUBRIC && !it.isAltarText } ?: return null
        val after = oldChapter.blocks.drop(oldIndex + 1).firstOrNull { candidate ->
            chapter.blocks.any { it.id == candidate.id }
        } ?: return null
        val start = chapter.blocks.indexOf(selectedLabel) + 1
        val end = chapter.blocks.indexOfFirst { it.id == after.id }
        if (end <= start) return null
        val section = chapter.blocks.subList(start, end)
        if (section.any { it.isAltarText }) return null
        val material = section.filter { it.kind != BookBlockKind.RUBRIC }
        val target = when {
            material.isNotEmpty() && material.all { it.kind == BookBlockKind.PRAYER } -> material.first()
            material.size == 1 && material.single().kind == BookBlockKind.LOCAL_VARIATION -> material.single()
            else -> return null
        }
        return chapter to target
    }
}

/** A damaged stored pointer is not the same as a book which has never been opened. */
sealed interface BookReadingLookup {
    data object Absent : BookReadingLookup
    data class Found(val position: BookReadingPosition) : BookReadingLookup
    data object Unreadable : BookReadingLookup
}

/** Separate from the published 0.5-0.7 playback formats: old bookmarks remain recoverable. */
class BookReadingStore(private val preferences: SharedPreferences) {
    fun last(parishId: String?): BookReadingPosition? =
        BookReadingCodec.decode(preferences.getString(lastKey(parishId), null))?.takeIf { it.parishId == parishId }

    /** The selected date has its own accepted edition, even after reading another service. */
    fun positionFor(key: ServiceBookKey, parishId: String?): BookReadingPosition? =
        (lookup(key, parishId) as? BookReadingLookup.Found)?.position

    /** One snapshot distinguishes absence, malformed data, a wrong stored type and read failure. */
    fun lookup(key: ServiceBookKey, parishId: String?): BookReadingLookup = synchronized(preferences) {
        runCatching {
            val values = preferences.all
            val storageKey = positionKey(key, parishId)
            if (!values.containsKey(storageKey)) {
                // The bounded per-book index can evict a pointer while this parish's last
                // reading still proves its accepted edition. Never borrow another book's last.
                val last = (values[lastKey(parishId)] as? String)?.let(BookReadingCodec::decode)
                    ?.takeIf { it.parishId == parishId && it.key == key }
                return@runCatching last?.let { BookReadingLookup.Found(it) } ?: BookReadingLookup.Absent
            }
            val raw = values[storageKey] as? String ?: return@runCatching BookReadingLookup.Unreadable
            val position = BookReadingCodec.decode(raw)?.takeIf { it.parishId == parishId && it.key == key }
                ?: return@runCatching BookReadingLookup.Unreadable
            BookReadingLookup.Found(position)
        }.getOrDefault(BookReadingLookup.Unreadable)
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

    /** An open reader may move its cursor, but may not reclaim an edition accepted elsewhere. */
    fun saveReading(position: BookReadingPosition): Boolean = synchronized(preferences) {
        when (val reading = lookup(position.key, position.parishId)) {
            BookReadingLookup.Unreadable -> false
            is BookReadingLookup.Found -> if (reading.position.contentHash == position.contentHash) save(position) else false
            BookReadingLookup.Absent -> save(position)
        }
    }

    /** Explicit return only: an evicted cursor can be restored, never an existing owner's cursor. */
    fun restoreMissing(book: ServiceBook, position: BookReadingPosition): Boolean = synchronized(preferences) {
        runCatching {
            if (position.key != book.key || position.contentHash != book.contentHash || !ServiceBookIntegrity.valid(book) ||
                book.chapters.none { it.id == position.chapterId && it.blocks.any { block -> block.id == position.blockId } } ||
                BookReadingCodec.decode(BookReadingCodec.encode(position)) == null ||
                position.journeyContext?.validatedFor(position.key, position.parishId) != position.journeyContext ||
                lookup(position.key, position.parishId) != BookReadingLookup.Absent) return@synchronized false
            save(position)
        }.getOrDefault(false)
    }

    fun acceptVersion(previous: ServiceBook, next: ServiceBook, parishId: String?,
        currentPosition: BookReadingPosition? = null): Boolean = synchronized(preferences) {
        if (currentPosition != null && (currentPosition.key != previous.key ||
                currentPosition.contentHash != previous.contentHash || currentPosition.parishId != parishId))
            return@synchronized false
        val accepted = lookup(previous.key, parishId)
        if (accepted == BookReadingLookup.Unreadable ||
            accepted is BookReadingLookup.Found && accepted.position.contentHash != previous.contentHash)
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
