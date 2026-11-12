package ru.liturgyguide.service.book

/** Verified editorial prefixes from the dated book and its parallel edition, never prayer words. */
internal object BookPrayerMetadata {
    data class Parts(val rubric: String?, val prayer: String)

    private val prefixes = listOf(
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

    fun split(value: String): Parts {
        val text = BookText.clean(value)
        val prefix = prefixes.firstOrNull { text.startsWith(it, ignoreCase = true) }
            ?: return Parts(null, text)
        val prayer = text.substring(prefix.length).trim()
        // A standalone label is not permission to manufacture an empty prayer.
        return if (prayer.isEmpty()) Parts(null, text) else Parts(text.take(prefix.length), prayer)
    }
}
