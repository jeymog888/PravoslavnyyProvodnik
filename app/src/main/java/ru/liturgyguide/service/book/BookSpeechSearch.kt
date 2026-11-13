package ru.liturgyguide.service.book

import ru.liturgyguide.model.ServiceBook

/** Selects an actual recognizer hypothesis. Never invents words or opens a hit. */
object BookSpeechSearch {
    fun query(book: ServiceBook, hypotheses: List<String>, checkActive: () -> Unit = {}): String =
        query(listOf(book), hypotheses, checkActive)

    /** All available related books participate; a phrase need not belong to the open part. */
    fun query(books: List<ServiceBook>, hypotheses: List<String>, checkActive: () -> Unit = {}): String {
        val candidates = hypotheses.take(5).map { it.trim().take(300) }.filter(String::isNotBlank).distinct()
        if (candidates.size <= 1) return candidates.firstOrNull().orEmpty()
        // A longer complete phrase is more useful than a generic single-word alternative.
        // Ties retain the speech engine's order. Word-order and fuzzy matches do not promote a hypothesis.
        val ranked = candidates.map { it to BookText.normalized(it) }
            .filter { it.second.length >= 2 }.sortedByDescending { it.second.split(' ').size }
        if (ranked.isEmpty()) return candidates.first()
        var best = ranked.size
        // Normalize one paragraph at a time, checking cancellation between paragraphs.
        // No second 3 MB copy of the whole book and no scan at all for one hypothesis.
        for (book in books) for (chapter in book.chapters) for (block in chapter.blocks) {
            checkActive()
            for (source in listOfNotNull(block.original, BookBlockPresentation.effectiveRussian(book.key, block))) {
                checkActive()
                val text = " ${BookText.normalized(source)} "
                for (index in 0 until best) if (text.contains(" ${ranked[index].second} ")) {
                    best = index
                    break
                }
                if (best == 0) return ranked[0].first
            }
        }
        return ranked.getOrNull(best)?.first ?: candidates.first()
    }
}
