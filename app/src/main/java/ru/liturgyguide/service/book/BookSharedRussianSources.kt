package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookSource
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind
import java.time.DayOfWeek
import java.time.LocalDate

/** Optional exact concordances; the initial three-source liturgy path remains unchanged. */
internal object BookSharedRussianSources {
    private val common = listOf(BookTranslationIndex.VIGIL_SOURCE, BookTranslationIndex.HOUR3_SOURCE,
        BookTranslationIndex.PANIKHIDA_SOURCE)
    private val oldLiturgyHeading = Regex("октоих глас ([1-8]) неделя на 8")
    // These full rubrics are present in the inspected Sunday constructor books.
    private val sundayHeading = Regex("октоих глас ([1-8]) неделя на (?:4|7|8)")

    fun forBook(book: ServiceBook): List<BookSource> {
        if (book.key.worship !in setOf(WorshipKind.LITURGY, WorshipKind.MATINS, WorshipKind.VESPERS)) return emptyList()
        val existing = if (book.key.worship == WorshipKind.LITURGY) common else emptyList()
        val sunday = sundaySource(book)
        // Never fan out to eight tones or add another two seasonal pages to a Sunday load.
        return existing + if (sunday != null) listOf(sunday) else BookPublishedIrmos6.sourcesForMissing(book)
    }

    private fun sundaySource(book: ServiceBook): BookSource? {
        val headings = book.chapters.flatMap { it.blocks }.filter { it.kind == BookBlockKind.RUBRIC }
            .map { BookText.normalized(it.original) }.filter { it.startsWith("октоих") }
        val voices = headings.map { sundayHeading.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (voices.any { it == null } || voices.toSet().size > 1) return null
        val explicit = voices.filterNotNull().toSet().singleOrNull()
        val day = book.calendarDay
        if (day != null && (!PublishedCalendarSupplements.valid(day, book.key.date) ||
                day.voice != null && explicit != null && day.voice != explicit)) return null
        // Preserve the previously supported complete liturgy rubric as independent evidence.
        val liturgyHeadings = book.chapters.filter { BookText.normalized(it.title).startsWith("блаженны ") }
            .flatMap { it.blocks }.filter { it.kind == BookBlockKind.RUBRIC }
            .map { BookText.normalized(it.original) }.filter { it.startsWith("октоих") }
        val independent = book.key.worship == WorshipKind.LITURGY && liturgyHeadings.isNotEmpty() &&
            liturgyHeadings.all { oldLiturgyHeading.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() == explicit }
        val calendarSunday = day != null && (day.voice ?: 0) in 1..8 &&
            runCatching { LocalDate.parse(day.date).dayOfWeek == DayOfWeek.SUNDAY }.getOrDefault(false)
        val tone = when {
            independent && explicit != null -> explicit
            calendarSunday -> day!!.voice
            else -> null
        } ?: return null
        return BookTranslationIndex.OCTOECHOS_SOURCES[tone - 1]
    }
}
