package ru.liturgyguide.service.book

import java.time.LocalDate

/** A source date suggests a calendar lookup; it does not prove a patronal event. */
sealed interface PatronalDateRule {
    data class FixedJulian(val month: Int, val day: Int) : PatronalDateRule {
        init { require(month in 1..12 && day in 1..PatronalDates.daysInJulianMonth(month, leap = true)) }
    }
    data class PaschaOffset(val days: Int) : PatronalDateRule {
        init { require(days in -99..99) }
    }
}

object PatronalDates {
    const val FIRST_YEAR = 1900
    const val LAST_YEAR = 2200

    /** Gregorian year, including January commemorations belonging to the previous Julian year. */
    fun candidates(rule: PatronalDateRule, gregorianYear: Int): List<LocalDate> {
        if (gregorianYear !in FIRST_YEAR..LAST_YEAR) return emptyList()
        return when (rule) {
            is PatronalDateRule.FixedJulian -> listOf(gregorianYear - 1, gregorianYear).mapNotNull { julianYear ->
                runCatching { julianToGregorian(julianYear, rule.month, rule.day) }.getOrNull()
            }
            is PatronalDateRule.PaschaOffset -> listOf(orthodoxPascha(gregorianYear).plusDays(rule.days.toLong()))
        }.filter { it.year == gregorianYear }.distinct().sorted()
    }

    /** Julian day number at the civil date's noon, converted to Java's Gregorian epoch day. */
    fun julianToGregorian(year: Int, month: Int, day: Int): LocalDate {
        require(year in 1..9999 && month in 1..12)
        require(day in 1..daysInJulianMonth(month, leap = year % 4 == 0))
        val a = (14 - month) / 12
        val y = year.toLong() + 4800 - a
        val m = month + 12 * a - 3
        val julianDay = day + (153L * m + 2) / 5 + 365 * y + y / 4 - 32083
        return LocalDate.ofEpochDay(julianDay - 2440588)
    }

    /** Julian computus, followed by the same actual calendar conversion as fixed feasts. */
    fun orthodoxPascha(gregorianYear: Int): LocalDate {
        require(gregorianYear in FIRST_YEAR..LAST_YEAR)
        val a = gregorianYear % 4
        val b = gregorianYear % 7
        val c = gregorianYear % 19
        val d = (19 * c + 15) % 30
        val e = (2 * a + 4 * b - d + 34) % 7
        val value = d + e + 114
        return julianToGregorian(gregorianYear, value / 31, value % 31 + 1)
    }

    internal fun daysInJulianMonth(month: Int, leap: Boolean): Int = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
