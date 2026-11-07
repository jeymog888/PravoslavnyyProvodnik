package ru.liturgyguide.service

/** Exact whole-word variants shared by text search and speech phrase matching. */
internal object LiturgicalSpelling {
    private val aliases = mapOf(
        "благословенно" to "благословено", "святаго" to "святого",
        "святый" to "святой", "соборнаго" to "соборного",
        "единаго" to "единого", "небеснаго" to "небесного",
        "безсмертный" to "бессмертный", "безсмертнаго" to "бессмертного",
        "божиих" to "божьих", "божиим" to "божьим"
    )

    fun canonicalWord(word: String): String = aliases[word] ?: word
}
