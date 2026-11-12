package ru.liturgyguide.service.book

import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey

/** A location belongs to one exact edition, even when related books reuse block IDs. */
data class BookLocatedSearchHit(val bookKey: ServiceBookKey, val contentHash: String, val hit: BookSearchHit) {
    val identity: String get() = BookText.sha256(listOf(bookKey.canonical(), contentHash, hit.chapterId,
        hit.blockId, hit.mode.name, hit.matchStart.toString(), hit.matchEndExclusive.toString())
        .joinToString("") { "${it.length}:$it" })

    /** Verify the actual source before using its UTF-16 offsets for a reader jump. */
    fun validatedFor(book: ServiceBook): BookLocatedSearchHit? {
        if (book.key != bookKey || book.contentHash != contentHash) return null
        val chapter = book.chapters.singleOrNull { it.id == hit.chapterId } ?: return null
        if (chapter.title != hit.chapterTitle) return null
        val block = chapter.blocks.singleOrNull { it.id == hit.blockId } ?: return null
        val text = when (hit.mode) {
            BookReadingMode.ORIGINAL -> block.original
            BookReadingMode.RUSSIAN -> block.russian ?: return null
            else -> return null
        }
        if (hit.matchStart < 0 || hit.matchEndExclusive <= hit.matchStart || hit.matchEndExclusive > text.length) return null
        // A damaged serialized target must not split a UTF-16 surrogate pair.
        fun splitsPair(offset: Int) = offset in 1 until text.length &&
            Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])
        if (splitsPair(hit.matchStart) || splitsPair(hit.matchEndExclusive)) return null
        return this
    }
}

/** Pure search only: callers choose editions and own loading, acceptance and reading positions. */
object BookCorpusSearch {
    fun find(books: List<ServiceBook>, query: String, preferredMode: BookReadingMode = BookReadingMode.RUSSIAN,
        checkActive: () -> Unit = {}): List<BookLocatedSearchHit> {
        if (BookText.normalized(query).length < 2) return emptyList()
        require(books.map { it.key }.distinct().size == books.size) { "Choose exactly one edition for each book key" }
        val candidates = mutableListOf<Pair<BookTextMatchKind, BookLocatedSearchHit>>()
        for (book in books) {
            checkActive()
            BookSearch.scan(book, query, preferredMode, checkActive).forEach { (kind, hit) ->
                candidates += kind to BookLocatedSearchHit(book.key, book.contentHash, hit)
            }
        }
        checkActive()
        // Kotlin's stable sort keeps corpus and source order among equally good matches.
        return candidates.sortedBy { it.first.ordinal }.map { it.second }
    }
}
