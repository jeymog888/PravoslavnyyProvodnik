package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBook

/** Add exact published pairs only. This is not a reparse, a translation or a schema migration. */
internal object SavedBookEnrichment {
    fun apply(book: ServiceBook, index: BookTranslationIndex): ServiceBook? {
        val published = BookTranslationIndex.ALL_PARALLEL_SOURCES.associateBy { it.url }
        var changed = false
        val chapters = book.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map blocks@{ block ->
            if (block.kind != BookBlockKind.PRAYER || block.russian != null) return@blocks block
            val pair = index.find(block.original) ?: return@blocks block
            val source = published[pair.source.url] ?: return@blocks block
            if (pair.russian.isBlank()) return@blocks block
            changed = true
            block.copy(russian = pair.russian, translationSource = source)
        }) }
        if (!changed) return null
        val candidate = book.copy(chapters = chapters,
            sources = (book.sources + chapters.flatMap { it.blocks }.mapNotNull { it.translationSource }).distinct())
        // Original date, text, meaning and the status of daily supplements are untouched.
        return candidate.copy(contentHash = ServiceBookIntegrity.contentHash(candidate))
    }
}
