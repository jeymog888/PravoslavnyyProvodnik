package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** A titleless image is not dated from the phone's clock or merely from a neighboring URL. */
internal object ScheduleImageContinuation {
    private val monthNames = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private val monthYear = Regex("(?iu)(январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])\\s+(20\\d{2})")
    private val printedDate = Regex("(?iu)(?:19|20|21)\\d{2}|\\d{1,2}[./]\\d{1,2}|январ|феврал|март|апрел|ма[йяе]\\b|июн|июл|август|сентябр|октябр|ноябр|декабр")
    private val day = Regex("(?iu)^\\s*(\\d{1,2})\\s*[| ]*\\s*(пн|вт|ср|чт|пт|сб|вс|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\.?(?![а-яё])")
    private val weekdayNames = listOf(setOf("пн", "понедельник"), setOf("вт", "вторник"), setOf("ср", "среда"),
        setOf("чт", "четверг"), setOf("пт", "пятница"), setOf("сб", "суббота"), setOf("вс", "воскресенье"))
    private val contact = Regex("(?iu)^\\s*(?:телефон(?:\\s+храма)?|тел\\.?)(?=\\s|:)")
    private val phone = Regex("(?<!\\d)\\+?\\d[\\d() .-]{8,28}\\d(?!\\d)")
    private val cancellation = Regex("(?iu)(отмен|перенес|перенос|не\\s+(?:будет|соверша|служит|состоится|провод)|без\\s+(?:литургии|богослужения))")

    /** Call only for consecutive, successfully fetched image links from the same HTML page. */
    fun join(first: ScheduleDocument, next: ScheduleDocument, parentUrl: String, parish: Parish): ScheduleDocument? {
        if (first.format != "image" || next.format != "image" || first.pages.size != 1 || next.pages.size != 1 ||
            ScheduleUrl.publicSource(parentUrl) == null || first.sourceUrl == next.sourceUrl) return null
        val before = first.pages.single().lines
        val after = next.pages.single().lines
        if (before.isEmpty() || after.isEmpty() || before.size + after.size > 2_000) return null
        val headingEnd = before.indexOfFirst { day.containsMatchIn(it.text) || ScheduleClocks.pattern.containsMatchIn(it.text) }
        if (headingEnd <= 0) return null
        val heading = before.take(headingEnd)
        val venues = heading.filter { ScheduleVenue.isLabel(it.text) }
        if (venues.isEmpty() || venues.any { !reliable(it) || !ScheduleVenue.matches(it.text, parish) }) return null
        val months = heading.mapNotNull { line ->
            monthYear.find(line.text)?.takeIf { reliable(line) }?.let { match ->
                val number = monthNames.indexOfFirst { match.groupValues[1].lowercase(Locale.ROOT).startsWith(it) } + 1
                runCatching { YearMonth.of(match.groupValues[2].toInt(), number) }.getOrNull()
            }
        }.distinct()
        val month = months.singleOrNull() ?: return null
        // Another month/date/heading starts its own publication; never override printed evidence.
        if (after.any { !contact.containsMatchIn(it.text) && printedDate.containsMatchIn(it.text) }) return null
        if (after.any { !contact.containsMatchIn(it.text) && ScheduleVenue.isLabel(it.venueEvidence ?: it.text) && !ScheduleVenue.matches(it.venueEvidence ?: it.text, parish) }) return null
        val targetPhone = normalizedPhone(parish.phone) ?: return null
        val publishedPhones = after.filter { contact.containsMatchIn(it.text) && reliable(it) }.flatMap { line ->
            phone.findAll(line.text).mapNotNull { normalizedPhone(it.value) }.toList()
        }.toSet()
        if (publishedPhones != setOf(targetPhone)) return null

        if (before.any { !contact.containsMatchIn(it.text) && ScheduleVenue.isLabel(it.venueEvidence ?: it.text) && !ScheduleVenue.matches(it.venueEvidence ?: it.text, parish) }) return null
        if (before.any { line -> monthYear.findAll(line.text).any { match ->
                val number = monthNames.indexOfFirst { match.groupValues[1].lowercase(Locale.ROOT).startsWith(it) } + 1
                number != month.monthValue || match.groupValues[2].toInt() != month.year
            } }) return null
        // Only the final anchors establish the continuation boundary. An earlier illegible
        // date is still rejected by the parser; it cannot overwrite these later printed dates.
        val finalAnchors = before.drop(headingEnd).mapNotNull { line -> day.find(line.text)?.let { line to it } }.takeLast(3)
        if (finalAnchors.size < 3) return null
        val tail = finalAnchors.map { (line, match) -> anchor(line, match, month) ?: return null }
        if (tail.zipWithNext().any { (a, b) -> b != a.plusDays(1) }) return null
        val last = tail.last()
        val expected = last.plusDays(1)
        if (YearMonth.from(expected) != month) return null
        val continued = mutableListOf<LocalDate>()
        var cutoff = after.size
        for ((index, line) in after.withIndex()) {
            val match = day.find(line.text) ?: continue
            val number = match.groupValues[1].toInt()
            if (continued.lastOrNull()?.dayOfMonth == month.lengthOfMonth() && number == 1) {
                // Do not assign an unprinted following month, even when its weekday repeats.
                // A safety notice beyond this boundary cannot be dropped with the tail.
                if (after.drop(index).any { cancellation.containsMatchIn(it.text) }) return null
                cutoff = index
                break
            }
            val date = anchor(line, match, month) ?: return null
            val previous = continued.lastOrNull()
            if (previous == null && date != expected) return null
            if (previous != null && date != previous && date != previous.plusDays(1)) return null
            if (previous != date) continued += date
        }
        if (continued.size < 3) return null
        return ScheduleDocument(parentUrl, "image", listOf(ScheduleDocumentPage(before + after.take(cutoff))))
    }

    private fun anchor(line: ScheduleDocumentLine, match: MatchResult, month: YearMonth): LocalDate? {
        if (!reliable(line)) return null
        val date = runCatching { month.atDay(match.groupValues[1].toInt()) }.getOrNull() ?: return null
        return date.takeIf { match.groupValues[2].lowercase(Locale.ROOT) in weekdayNames[it.dayOfWeek.value - 1] }
    }

    private fun reliable(line: ScheduleDocumentLine) = !line.struck && line.confidence >= 45 && line.temporalConfidence >= 85
    private fun normalizedPhone(value: String): String? {
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 10..15) return null
        return if (digits.length == 11 && digits.startsWith('8')) "7" + digits.drop(1) else digits
    }
}
