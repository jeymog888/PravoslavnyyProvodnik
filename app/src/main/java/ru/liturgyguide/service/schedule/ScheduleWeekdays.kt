package ru.liturgyguide.service.schedule

import java.time.LocalDate
import java.util.Locale

internal object ScheduleWeekdays {
    private const val standardWords = "пн|вт|ср|чт|пт|сб|вс|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье"
    // Some parish headings spell the weekday this way. Only a complete weekday cell,
    // delimiter or following clock establishes that meaning; "Воскресение Христово" does not.
    private const val sundayAlias = "воскресение(?=\\.?\\s*(?:$|[|,;]|\\d{1,2}\\s*[.:-]\\s*\\d{2}(?!\\d)))"
    const val dateWords = "$standardWords|$sundayAlias"
    val prefix = Regex("(?iu)^[\\s,|.-]*($dateWords)\\.?(?![а-яё])")
    private val standard = Regex("(?iu)(?<![а-яё])($standardWords)\\.?(?![а-яё])")
    private val aliasPrefix = Regex("(?iu)^[\\s,|.-]*($sundayAlias)\\.?(?![а-яё])")
    private val names = listOf(setOf("пн", "понедельник"), setOf("вт", "вторник"), setOf("ср", "среда"),
        setOf("чт", "четверг"), setOf("пт", "пятница"), setOf("сб", "суббота"), setOf("вс", "воскресенье", "воскресение"))

    fun agrees(value: String, date: LocalDate): Boolean = value.lowercase(Locale.ROOT) in names[date.dayOfWeek.value - 1]

    // Preserve the existing standard-weekday validation; the extra spelling needs date position.
    fun inDateRemainder(text: String): MatchResult? = aliasPrefix.find(text) ?: standard.find(text)

    fun removeFromDateRemainder(text: String): String {
        val alias = aliasPrefix.find(text)
        return (alias?.let { text.removeRange(it.range) } ?: text).replace(standard, " ")
    }
}
