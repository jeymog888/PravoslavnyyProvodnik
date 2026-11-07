package ru.liturgyguide.service

import java.text.Normalizer
import java.util.Locale

/** A curated phrase that can help locate a stage, not a recognition probability. */
data class StageAnchor(val stageId: String, val phrases: List<String>)

data class PhraseMatch(val stageId: String, val matchedPhrase: String)

/**
 * Finds complete, distinctive phrases in a transcript. A match is a suggestion for
 * the user to confirm. Shared phrases and transcripts spanning multiple stages
 * deliberately produce no result; elapsed time is never evidence of a stage.
 */
class PhraseMatcher(anchors: List<StageAnchor>) {
    private data class Candidate(val stageId: String, val phrase: String, val normalized: String)

    private val candidates = anchors.flatMap { anchor ->
        anchor.phrases.mapNotNull { phrase ->
            val normalized = normalize(phrase)
            val tokens = normalized.split(' ').filter(String::isNotEmpty)
            if (anchor.stageId.isBlank() || tokens.size < 2 ||
                tokens.all { it in genericWords } || normalized in genericPhrases ||
                repeatedLitanyPhrases.any { repeated -> normalized == repeated || normalized.startsWith("$repeated ") }
            ) {
                null
            } else {
                Candidate(anchor.stageId, phrase, normalized)
            }
        }
    }.distinctBy { it.stageId to it.normalized }.let { candidates ->
        // A shared phrase is not evidence for either stage. Another distinctive
        // phrase in the same transcript can still support one of them.
        val ambiguous = candidates.groupBy { it.normalized }.filterValues { group ->
            group.map { it.stageId }.distinct().size > 1
        }.keys
        candidates.filterNot { it.normalized in ambiguous }
    }

    fun match(transcript: String): PhraseMatch? {
        val normalized = normalize(transcript)
        if (normalized.isBlank()) return null
        val padded = " $normalized "
        val matches = candidates.filter { padded.contains(" ${it.normalized} ") }
        if (matches.map { it.stageId }.distinct().size != 1) return null
        val strongest = matches.maxByOrNull { it.normalized.length } ?: return null
        return PhraseMatch(strongest.stageId, strongest.phrase)
    }

    companion object {
        private val marks = Regex("\\p{M}")
        private val separators = Regex("[^а-яa-z]+")
        private val terminalHardSign = Regex("ъ(?=[^а-яa-z]|$)")
        private val genericWords = setOf("господи", "помилуй", "аминь", "слава", "тебе", "боже").map(::normalize).toSet()
        private val genericPhrases = setOf(
            "мир всем", "и духови твоему", "и духу твоему", "господу помолимся",
            "слава отцу и сыну и святому духу", "слава отцу и сыну и святому духу аминь",
            "ныне и присно и во веки веков", "и ныне и присно и во веки веков"
        ).map(::normalize).toSet()

        // These petitions recur in the litany of the faithful after the Gospel.
        // They remain useful human-readable listenFor cues in the guide, but
        // their length does not make them unique acoustic evidence of the opening.
        private val repeatedLitanyPhrases = setOf(
            "о свышнем мире и спасении",
            "о мире всего мира благостоянии святых Божиих Церквей"
        ).map(::normalize).toSet()

        private fun normalize(value: String): String {
            val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            // Keep the breve that distinguishes й from и, while removing stress.
            val withoutMarks = Normalizer.normalize(
                marks.replace(decomposed) { if (it.value == "\u0306") it.value else "" },
                Normalizer.Form.NFC
            )
            val modernLetters = withoutMarks
                .replace('ѣ', 'е').replace('і', 'и').replace('ї', 'и')
                .replace('ѳ', 'ф').replace('ѵ', 'и').replace('ё', 'е')
            return separators.replace(terminalHardSign.replace(modernLetters, ""), " ")
                .trim().split(' ').filter(String::isNotEmpty)
                .joinToString(" ", transform = LiturgicalSpelling::canonicalWord)
        }
    }
}
