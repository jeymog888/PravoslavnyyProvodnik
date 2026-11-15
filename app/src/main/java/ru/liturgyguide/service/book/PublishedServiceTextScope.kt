package ru.liturgyguide.service.book

import ru.liturgyguide.model.WorshipKind

/** A finite title classifier, not a reconstruction of a local service or its timing. */
internal object PublishedServiceTextScope {
    enum class Extra(val title: String) {
        AKATHIST("Акафист"), MOLEBEN("Молебен"), MEMORIAL("Панихида"), CONFESSION("Исповедь")
    }
    data class Analysis(val independent: String, val extras: List<Extra>, val ambiguous: Boolean)
    private val vespers = Regex("\\bвечерн(?:я|и|ю|е)\\b")
    private val matins = Regex("\\bутрен(?:я|и|ю|е)\\b")
    private val liturgy = Regex("\\bлитурги[а-я]*\\b")
    private val extraPatterns = listOf(
        Extra.AKATHIST to Regex("\\bакафист[а-я]*\\b"),
        Extra.MOLEBEN to Regex("\\bмолеб[а-я]*\\b"),
        Extra.MEMORIAL to Regex("\\bпанихид[а-я]*\\b"),
        Extra.CONFESSION to Regex("\\bисповед[а-я]*\\b")
    )
    private val noun = "(?:литурги[а-я]*|вечерн(?:я|и|ю|е)|утрен(?:я|и|ю|е)|всенощ[а-я]*(?:\\s+бдени[а-я]*)?|бдени[а-я]*|часы|часов|часами|акафист[а-я]*|молеб[а-я]*|панихид[а-я]*|исповед[а-я]*)"
    private val adjectives = "(?:(?:(?:ранн|поздн|божественн|праздничн|архиерейск|полиелейн)[а-я]*|(?:утренн|вечерн)(?:ей|его|юю|ее|яя|ий|ем))\\s+){0,3}"
    private val timing = Regex("\\b(?:после(?: окончания| завершения)?|по окончании|по завершении|перед|до)\\s+" + adjectives + noun + "\\b")
    private val without = Regex("\\bбез\\s+" + adjectives + noun + "\\b")
    private const val CANCEL = "(?:отмен[а-я]*|перенес[а-я]*|не\\s+(?:будет|соверша[а-я]*|предусмотр[а-я]*))"
    private val cancelledAfter = Regex("\\b" + noun + "\\s+" + CANCEL + "\\b")
    private val cancelledBefore = Regex("\\b" + CANCEL + "\\s+" + adjectives + noun + "\\b")
    private val cancellation = Regex("\\b" + CANCEL + "\\b")
    private val anyNoun = Regex("\\b" + noun + "\\b")
    private val spacedDash = Regex("\\s+[-–—]\\s+")
    private val independentStart = Regex("^" + adjectives + "(?:литургия|вечерня|утреня|всенощное\\s+бдение|часы)\\b")

    private fun afterLeadingReference(clause: String): String {
        val cleaned = BookText.clean(clause)
        val boundary = spacedDash.find(cleaned) ?: return clause
        val prefix = BookText.normalized(cleaned.substring(0, boundary.range.first))
        val suffix = cleaned.substring(boundary.range.last + 1)
        // Only a complete leading time reference and a new nominative subject establish this boundary.
        return if (timing.matches(prefix) && independentStart.containsMatchIn(BookText.normalized(suffix))) suffix else clause
    }

    fun analyze(title: String): Analysis {
        var ambiguous = false
        // Scope a cancellation before normalization erases the original sentence boundary.
        val positive = title.split(Regex("[.!?;\\r\\n]+")).joinToString(" ") { clause ->
            val normalized = BookText.normalized(afterLeadingReference(clause))
            // The remainder of an unparsed dependent list is not independent evidence.
            // Keep this conservative: another service noun after the scoped head needs a choice.
            if (sequenceOf(timing, without).flatMap { it.findAll(normalized) }.any { head ->
                    anyNoun.containsMatchIn(normalized.substring(head.range.last + 1))
                }) ambiguous = true
            val independent = timing.replace(normalized, " ")
            if (cancellation.containsMatchIn(independent) && anyNoun.findAll(independent).count() > 1)
                ambiguous = true // A shared predicate must not silently cancel only its nearest noun.
            val kept = cancelledBefore.replace(cancelledAfter.replace(without.replace(independent, " "), " "), " ")
            if (cancellation.containsMatchIn(kept) && anyNoun.containsMatchIn(kept)) ambiguous = true
            kept
        }.let(BookText::normalized)
        val eveningBase = vespers.containsMatchIn(positive) || matins.containsMatchIn(positive) ||
            Regex("\\b(?:всенощ[а-я]*|бдени[а-я]*)\\b").containsMatchIn(positive)
        if (eveningBase && (liturgy.containsMatchIn(positive) || Regex("\\bчасы\\b").containsMatchIn(positive)))
            ambiguous = true
        if (listOf("повечер", "полунощ", "изобразительн").any { it in positive } &&
            (eveningBase || liturgy.containsMatchIn(positive))) ambiguous = true
        return Analysis(positive, extraPatterns.filter { it.second.containsMatchIn(positive) }.map { it.first }, ambiguous)
    }

    // This new allowance sees the original punctuation, before the legacy analyzer removes it.
    // A closed full-title grammar excludes unknown services, alternatives and residual words.
    private val vigilConfessionTitle = Regex("всенощное бдение(?: *[.,] *исповедь| и исповедь| с исповедью)(?: *\\.)?")
    private fun isVigilWithConfession(title: String): Boolean {
        if (title.any { it.isISOControl() }) return false
        val value = title.replace('\u00a0', ' ').trim().lowercase().replace(Regex("[\\s\\p{Zs}]+"), " ")
        return vigilConfessionTitle.matches(value)
    }

    fun partialKind(analysis: Analysis, title: String): WorshipKind? {
        if (analysis.ambiguous || analysis.extras.isEmpty()) return null
        val value = analysis.independent
        if (analysis.extras == listOf(Extra.CONFESSION) && isVigilWithConfession(title) &&
            Regex("\\bвсенощное бдение\\b").containsMatchIn(value)) return WorshipKind.VIGIL
        if (matins.containsMatchIn(value) || listOf("всенощ", "бдени", "повечер", "полунощ", "изобразительн").any { it in value } ||
            Regex("\\b(?:часы|час|часа|часов)\\b").containsMatchIn(value)) return null
        val evening = vespers.containsMatchIn(value)
        val morning = liturgy.containsMatchIn(value)
        return when {
            evening && !morning -> WorshipKind.VESPERS
            morning && !evening -> WorshipKind.LITURGY
            else -> null
        }
    }

    fun exclusion(analysis: Analysis): String? = analysis.extras.takeIf { it.isNotEmpty() }?.let { extras ->
        val names = extras.map { it.title.lowercase() }
        val subject = (if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " и " + names.last())
            .replaceFirstChar { it.uppercase() }
        "$subject в этот текст ${if (names.size == 1) "не входит" else "не входят"}"
    }
}
