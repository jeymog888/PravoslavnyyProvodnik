package ru.liturgyguide.service.book

import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey

/** A location belongs to one exact edition, even when related books reuse block IDs. */
data class BookLocatedSearchHit(val bookKey: ServiceBookKey, val contentHash: String, val hit: BookSearchHit) {
    val identity: String get() = BookText.sha256(listOf(bookKey.canonical(), contentHash, hit.chapterId,
        hit.blockId, hit.mode.name, hit.matchStart.toString(), hit.matchEndExclusive.toString())
        .joinToString("") { "${it.length}:$it" })

    /** Bounds for a persisted request, before any book text is available. */
    fun validated(): BookLocatedSearchHit? = runCatching {
        takeIf { bookKey.isValid() && contentHash.matches(Regex("[a-f0-9]{64}")) &&
            hit.chapterId.length in 1..240 && hit.blockId.length in 1..240 &&
            hit.chapterTitle.length in 1..1000 && hit.excerpt.length in 1..3000 &&
            hit.mode in setOf(BookReadingMode.ORIGINAL, BookReadingMode.RUSSIAN) &&
            hit.matchStart in 0..4_000_000 && hit.matchEndExclusive in (hit.matchStart + 1)..4_000_000 }
    }.getOrNull()

    /** Verify the actual source before using its UTF-16 offsets for a reader jump. */
    fun validatedFor(book: ServiceBook): BookLocatedSearchHit? {
        if (validated() == null) return null
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
        val candidates = mutableListOf<Triple<BookTextMatchKind, Boolean, BookLocatedSearchHit>>()
        for (book in books) {
            checkActive()
            // Carry visibility from the cancellable search pass. A preliminary full
            // traversal would read the whole book even after the user closes search.
            BookSearch.scan(book, query, preferredMode, checkActive).forEach { (kind, isPrivate, hit) ->
                candidates += Triple(kind, isPrivate, BookLocatedSearchHit(book.key, book.contentHash, hit))
            }
        }
        checkActive()
        // Match quality always wins. A quiet duplicate must not outrank an equally
        // good public phrase just because its source chapter occurs earlier.
        // Stable sorting retains every result and source order within each scope.
        return candidates.sortedWith(compareBy<Triple<BookTextMatchKind, Boolean, BookLocatedSearchHit>> { it.first.ordinal }
            .thenBy { it.second }).map { it.third }
    }
}
