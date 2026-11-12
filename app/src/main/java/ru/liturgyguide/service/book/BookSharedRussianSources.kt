package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookSource
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind

/** Optional published pages for exact concordance, never part of initial liturgy HTTP. */
internal object BookSharedRussianSources {
    private val common = listOf(BookTranslationIndex.VIGIL_SOURCE, BookTranslationIndex.HOUR3_SOURCE,
        BookTranslationIndex.PANIKHIDA_SOURCE)
    private val octoechosHeading = Regex("октоих глас ([1-8]) неделя на 8")

    fun forBook(book: ServiceBook): List<BookSource> {
        if (book.key.worship != WorshipKind.LITURGY) return emptyList()
        val headings = book.chapters.filter { BookText.normalized(it.title).startsWith("блаженны ") }
            .flatMap { it.blocks }.filter { it.kind == BookBlockKind.RUBRIC }
            .map { BookText.normalized(it.original) }.filter { it.startsWith("октоих") }
        // An unrecognized or conflicting heading must not select a nearby tone.
        val voices = headings.map { octoechosHeading.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (voices.isEmpty() || voices.any { it == null } || voices.toSet() != setOf(7)) return common
        val day = book.calendarDay
        if (day != null && (!PublishedCalendarSupplements.valid(day, book.key.date) ||
                    (day.voice != null && day.voice != 7))) return common
        return common + BookTranslationIndex.OCTOECHOS7_SOURCE
    }
}
