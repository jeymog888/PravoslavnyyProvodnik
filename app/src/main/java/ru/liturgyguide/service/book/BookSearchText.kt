package ru.liturgyguide.service.book

/** UTF-16 offsets belong to the displayed source, never to the normalized search string. */
data class BookTextMatch(val start: Int, val endExclusive: Int)

object BookSearchText {
    private val tokens = Regex("[\\p{L}\\p{N}\\p{M}]+")

    fun find(text: String, query: String): BookTextMatch? {
        val needle = BookText.normalized(query)
        if (needle.length < 2) return null
        val words = needle.split(' ').filter { it.length >= 2 }
        val searchable = BookText.normalized(text)
        // Most paragraphs do not match. Do not allocate a source-offset map for them
        // on every keystroke, especially in long matins and vigil books.
        if (!searchable.contains(needle) && !(words.size >= 2 && words.all(searchable::contains))) return null
        // Map normalized characters back to complete source tokens. Accents, decomposed
        // letters and Slavonic substitutions can change lengths, so normalized offsets
        // must never be passed straight to TextLayoutResult.
        val normalized = StringBuilder()
        val sourceStarts = mutableListOf<Int>()
        val sourceEnds = mutableListOf<Int>()
        for (token in tokens.findAll(text)) {
            val value = BookText.normalized(token.value)
            if (value.isEmpty()) continue
            if (normalized.isNotEmpty()) {
                normalized.append(' ')
                sourceStarts += token.range.first
                sourceEnds += token.range.first
            }
            normalized.append(value)
            repeat(value.length) {
                sourceStarts += token.range.first
                sourceEnds += token.range.last + 1
            }
        }
        val phrase = normalized.indexOf(needle)
        if (phrase >= 0) return BookTextMatch(sourceStarts[phrase], sourceEnds[phrase + needle.length - 1])
        if (words.size < 2) return null
        val matches = words.map { word -> normalized.indexOf(word).takeIf { it >= 0 }?.let { it to word.length } ?: return null }
        // The existing search also accepts words in a different order. Anchor to the
        // first actual matched word, not a huge range between unrelated distant words.
        val first = matches.minBy { it.first }
        return BookTextMatch(sourceStarts[first.first], sourceEnds[first.first + first.second - 1])
    }
}
