package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Discussion dates and links are not part of the parish's published schedule. */
internal object ScheduleHtmlDiscussion {
    // Exact standard containers only: comments-schedule / schedule-comments-note are content.
    private const val selector = ".comments,.comment-list,#comments,.commentlist,#respond,.comment-respond"

    fun removeFrom(document: Document) { document.select(selector).remove() }
    fun contains(element: Element): Boolean = element.closest(selector) != null
}
