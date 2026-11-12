package ru.liturgyguide.service.book

/** Verified editorial prefixes from the dated book and its parallel edition, never prayer words. */
internal object BookPrayerMetadata {
    data class Parts(val rubric: String?, val prayer: String)

    // Dated liturgy paragraphs only. These directions do not authorize stripping either
    // column of a bilingual source; those two-column boundaries have their own DOM guard.
    private val liturgyPrefixes = listOf(
        "И священник возглашает:",
        "По окончании последнего тропаря, диакон, став впереди священника в Царских вратах, возвышает св. Евангелие и, показывая его народу возглашает громогласно:",
        "По окончании Трисвятого диакон, подойдя к Царским вратам, возглашает:",
        "Священник возглашает, благословляя народ:",
        "Диакон возглашает:",
        "Священник же, вознося Святой Хлеб, возглашает:",
        "Лик же поет:"
    ).sortedByDescending(String::length)

    // Verified in public Matins and Vespers paragraphs too. This dated-source
    // boundary must not expand split(), which also reads bilingual table cells.
    private val datedPublicPrefixes = listOf("И священник возглашает:")

    private val prefixes = listOf(
        "Священник, осеняя его крестным знамением, произносит:",
        "Священник возглашает:",
        "Священник молится:",
        "И открываются Царские врата. Диакон, сотворив поклон, берет св. Потир и, выходя Царскими вратами, возвышает св. Потир, показывая его народу и возглашая:",
        "В будни:",
        "Священник же молится:",
        "Перед причастием верных священник по обычаю читает молитвы:",
        "Перед причастием верных священник, по обычаю читает молитвы:",
        "Возвышая голос и делая звездицей знак креста над дискосом:",
        "Священник же благословляет народ, возглашая:",
        "Священник же благословляет люди, возглашая:",
        "И, взяв кадило, священник возглашает:",
        "Во время причащения по обычаю поем:",
        "И заканчивает Херувимскую песнь:",
        "Священник (возвышая голос):",
        "Возвышая голос:",
        "Возглашение:", "Возглас:",
        "Лик поет:", "Хор поет:",
        "И затем:", "И паки:", "{И снова:}"
    ).sortedByDescending(String::length)

    fun split(value: String): Parts = split(value, prefixes)

    fun splitLiturgyParagraph(value: String): Parts =
        split(value, liturgyPrefixes).takeIf { it.rubric != null } ?: split(value)

    fun splitDatedPublicParagraph(value: String): Parts =
        split(value, datedPublicPrefixes).takeIf { it.rubric != null } ?: split(value)

    private fun split(value: String, acceptedPrefixes: List<String>): Parts {
        val text = BookText.clean(value)
        val prefix = acceptedPrefixes.firstOrNull { text.startsWith(it, ignoreCase = true) }
            ?: return Parts(null, text)
        val prayer = text.substring(prefix.length).trim()
        // A standalone label is not permission to manufacture an empty prayer.
        return if (prayer.isEmpty()) Parts(null, text) else Parts(text.take(prefix.length), prayer)
    }
}
