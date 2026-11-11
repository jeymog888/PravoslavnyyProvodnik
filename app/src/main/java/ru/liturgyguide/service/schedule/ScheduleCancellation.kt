package ru.liturgyguide.service.schedule

/** Liturgical commemorations are not notices that a service has moved. */
internal object ScheduleCancellation {
    private val marker = Regex("(?iu)(отмен|перенес|перенос|не\\s+(?:будет|соверша|служит|состоится|провод)|без\\s+(?:литургии|богослужения))")
    // Mask only these complete commemorative phrases while checking. Keep every other
    // word, including a real cancellation later in the same calendar row. Visible titles
    // and full OCR evidence are never rewritten or shortened by this detector.
    private val commemoration = Regex("(?iu)(?<![а-яё])перенесени(?:е|я|ю|ем|и)\\s+" +
        "(?:[а-яё][а-яё-]*\\.?\\s+){0,16}?(?:мощей|иконы|нерукотвор(?:енн|н)ого\\s+образа)(?![а-яё])")
    private val serviceInside = Regex("(?iu)(служб|богослуж|литург|молеб|панихид|акафист|вечерн|утрен|всенощ|исповед|лити[яюи]|крестн[а-яё]*\\s+ход|" +
        "(?<![а-яё])час(?:ы|ов|а|у|ом)?(?![а-яё]))")

    fun containsMatchIn(text: String): Boolean {
        if (!marker.containsMatchIn(text)) return false
        return marker.containsMatchIn(text.replace(commemoration) { match ->
            // Geography and "частицы мощей" belong to some published commemorations.
            // Never mask a service moved in their honour, another cancellation, or a time.
            val afterNoun = match.value.dropWhile { !it.isWhitespace() }.trimStart()
            if (marker.containsMatchIn(afterNoun) || serviceInside.containsMatchIn(afterNoun) || afterNoun.any(Char::isDigit)) match.value
            else " ".repeat(match.value.length)
        })
    }
}
