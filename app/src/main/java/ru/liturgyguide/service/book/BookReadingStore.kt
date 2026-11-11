package ru.liturgyguide.service.book

import android.content.SharedPreferences
import com.google.gson.Gson
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey

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
    val anchorKind: BookAnchorKind = BookAnchorKind.BLOCK
)

object BookReadingCodec {
    private val gson = Gson()
    fun decode(json: String?): BookReadingPosition? {
        if (json.isNullOrBlank() || json.length > 8192) return null
        return runCatching {
            gson.fromJson(json, BookReadingPosition::class.java).takeIf {
                it.key.isValid() && it.key.worship != null && it.mode != null && it.anchorKind != null &&
                    it.contentHash.matches(Regex("[0-9a-f]{64}")) &&
                    it.blockId.length in 1..180 && it.chapterId.length in 1..180 &&
                    (it.parishId == null || it.parishId.length in 1..160) &&
                    it.fraction.isFinite() && it.fraction in 0f..1f && it.wordsPerMinute in 30..180
            }
        }.getOrNull()
    }
    fun encode(position: BookReadingPosition): String = gson.toJson(position).also {
        require(decode(it) != null)
    }
}

data class BookViewport(val chapterId: String, val blockId: String, val fraction: Float, val kind: BookAnchorKind)

/** One semantic cursor owns both languages and the temporary chapter-level meaning view. */
class BookReadingCursor(initial: BookReadingPosition) {
    var value: BookReadingPosition = initial
        private set

    fun observe(viewport: BookViewport, mode: BookReadingMode) {
        value = if (mode == BookReadingMode.MEANING) {
            if (viewport.chapterId == value.chapterId) value.copy(mode = mode)
            else value.copy(chapterId = viewport.chapterId, blockId = viewport.blockId,
                fraction = 0f, anchorKind = BookAnchorKind.HEADER, mode = mode)
        } else value.copy(chapterId = viewport.chapterId, blockId = viewport.blockId,
            fraction = viewport.fraction.coerceIn(0f, 1f), anchorKind = viewport.kind, mode = mode)
    }

    fun seek(chapterId: String, blockId: String, kind: BookAnchorKind = BookAnchorKind.BLOCK, fraction: Float = 0f) {
        value = value.copy(chapterId = chapterId, blockId = blockId, anchorKind = kind, fraction = fraction.coerceIn(0f, 1f))
    }

    fun snapshot(mode: BookReadingMode, wordsPerMinute: Int, showAltar: Boolean): BookReadingPosition =
        value.copy(mode = mode, wordsPerMinute = wordsPerMinute, showAltar = showAltar).also { value = it }
}

/** Separate from the published 0.5-0.7 playback formats: old bookmarks remain recoverable. */
class BookReadingStore(private val preferences: SharedPreferences) {
    fun last(parishId: String?): BookReadingPosition? =
        BookReadingCodec.decode(preferences.getString(lastKey(parishId), null))?.takeIf { it.parishId == parishId }

    fun restore(book: ServiceBook, parishId: String?): BookReadingPosition? =
        BookReadingCodec.decode(preferences.getString(positionKey(book.key, parishId), null))?.takeIf {
            it.parishId == parishId && it.key == book.key && book.blocks.any { block -> block.id == it.blockId }
        }?.let {
            // An explicit source refresh can retain the fragment, but not an offset in changed prose.
            if (it.contentHash == book.contentHash) it else it.copy(contentHash = book.contentHash, fraction = 0f)
        }

    fun save(position: BookReadingPosition): Boolean = runCatching {
        val json = BookReadingCodec.encode(position)
        val key = positionKey(position.key, position.parishId)
        val edit = preferences.edit()
        val keys = preferences.all.keys.filter { it.startsWith("position:") }
        if (key !in keys && keys.size >= 32) keys.firstOrNull()?.let(edit::remove)
        edit.putString(key, json).putString(lastKey(position.parishId), json).commit()
    }.getOrDefault(false)

    private fun positionKey(key: ServiceBookKey, parishId: String?) =
        "position:" + BookText.sha256("${parishId.orEmpty()}|${key.canonical()}")
    private fun lastKey(parishId: String?) = "last:" + BookText.sha256(parishId.orEmpty())
}

data class BookSearchHit(val chapterId: String, val chapterTitle: String, val blockId: String, val excerpt: String)

object BookSearch {
    fun find(book: ServiceBook, query: String): List<BookSearchHit> {
        val needle = BookText.normalized(query)
        if (needle.length < 2) return emptyList()
        val words = needle.split(' ').filter { it.length >= 2 }
        return book.chapters.flatMap { chapter -> chapter.blocks.mapNotNull { block ->
            val original = BookText.normalized(block.original)
            val russian = BookText.normalized(block.russian.orEmpty())
            val exact = original.contains(needle) || russian.contains(needle)
            val allWords = words.size >= 2 && (words.all(original::contains) || words.all(russian::contains))
            if (exact || allWords) BookSearchHit(chapter.id, chapter.title, block.id,
                (if (russian.contains(needle)) block.russian else block.original).orEmpty().take(240)) else null
        } }.take(30)
    }
}
