package ru.liturgyguide.service.schedule

/** A hyphen is a clock separator only before an isolated minute pair, never a month/date range or anniversary. */
internal object ScheduleClocks {
    private const val MONTH = "(?:январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])"
    val pattern = Regex("(?iu)(?<![\\d./:-])(\\d{1,2})\\s*(?:[.:]|-(?!\\s*\\d{2}\\s*(?:$MONTH|лет|год|век))(?=\\s*\\d{2}(?:\\.(?!\\d)|\\s|[|,;]|$)))\\s*(\\d{2})(?:\\.(?!\\d))?(?![\\d./])")
}
