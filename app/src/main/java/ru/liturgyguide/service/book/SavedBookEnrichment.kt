package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBook

/** Offer exact published or reviewed local pairs without changing the accepted reading object. */
internal object SavedBookEnrichment {
    fun apply(book: ServiceBook, index: BookTranslationIndex): ServiceBook? {
        val published = BookTranslationIndex.ALL_PARALLEL_SOURCES.associateBy { it.url }
        var changed = false
        val chapters = book.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map blocks@{ block ->
            val ownTranslation = block.translationSource == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE
            if (block.kind != BookBlockKind.PRAYER || block.russian != null && !ownTranslation) return@blocks block
            val pair = index.find(block.original) ?: return@blocks block
            val source = if (FeoriaTranslationCatalog.recognizes(block.original, pair))
                BookTranslationIndex.FEORIA_TRANSLATION_SOURCE else published[pair.source.url] ?: return@blocks block
            if (pair.russian.isBlank()) return@blocks block
            // A later published exact pair may supersede our fallback in a new candidate.
            // A local pair never overwrites an existing translation, including its own edition.
            if (block.russian != null && source == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE) return@blocks block
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
