package ru.liturgyguide.service.book

/** UTF-16 offsets belong to the displayed source, never to the normalized search string. */
enum class BookTextMatchKind { PHRASE, WORDS }
data class BookTextMatch(val start: Int, val endExclusive: Int, val kind: BookTextMatchKind = BookTextMatchKind.PHRASE)

object BookSearchText {
    private val tokens = Regex("[\\p{L}\\p{N}\\p{M}]+")

    fun find(text: String, query: String): BookTextMatch? {
        val needle = BookText.normalized(query)
        if (needle.length < 2) return null
        val words = needle.split(' ').filter { it.length >= 2 }
        val severalWords = ' ' in needle
        val searchable = BookText.normalized(text)
        // Most paragraphs do not match. Do not allocate a source-offset map for them
        // on every keystroke, especially in long matins and vigil books.
        if (locate(searchable, needle, words, severalWords) == null) return null
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
        val match = locate(normalized.toString(), needle, words, severalWords) ?: return null
        return BookTextMatch(sourceStarts[match.start], sourceEnds[match.endExclusive - 1], match.kind)
    }

    private fun locate(text: String, needle: String, words: List<String>, severalWords: Boolean): BookTextMatch? {
        // A single partly typed word keeps the existing substring behavior. Several
        // heard words must not accidentally cross word endings, e.g. Владимир/мир.
        val phrase = if (severalWords) wholeWordIndex(text, needle) else text.indexOf(needle)
        if (phrase >= 0) return BookTextMatch(phrase, phrase + needle.length)
        if (words.size < 2) return null
        val used = mutableSetOf<Int>()
        val matches = words.map { word ->
            var at = wholeWordIndex(text, word)
            while (at >= 0 && at in used) at = wholeWordIndex(text, word, at + word.length)
            if (at < 0) return null
            used += at
            at to word.length
        }
        // The existing search also accepts words in a different order. Anchor to the
        // first actual matched word, not a huge range between unrelated distant words.
        val first = matches.minBy { it.first }
        return BookTextMatch(first.first, first.first + first.second, BookTextMatchKind.WORDS)
    }

    private fun wholeWordIndex(text: String, value: String, from: Int = 0): Int {
        var at = text.indexOf(value, from)
        while (at >= 0) {
            val end = at + value.length
            if ((at == 0 || text[at - 1] == ' ') && (end == text.length || text[end] == ' ')) return at
            at = text.indexOf(value, at + 1)
        }
        return -1
    }
}
