package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Document
import java.net.URLDecoder

data class ScheduleDocumentLink(val url: String, val linkedFrom: String, val document: Boolean)

/** Cross-site documents are accepted only when explicitly linked from the selected parish page. */
object ScheduleDocumentLinks {
    private val extension = Regex("(?i)\\.(pdf|jpe?g|png|webp)(?:\\?|$)")
    private val schedule = Regex("(?iu)(расписан|raspisan|schedule|богослужен)")

    fun find(page: Document): List<ScheduleDocumentLink> {
        val links = mutableListOf<ScheduleDocumentLink>()
        val copy = page.clone()
        copy.select("nav,footer,aside,header,.sidebar,.comments").remove()
        val content = copy.selectFirst("main,article,.entry-content,.post-content") ?: copy.body()
        val schedulePage = schedule.containsMatchIn(copy.select("h1,h2,h3").text())
        // A schedule heading does not make every attachment in the site's footer a schedule.
        // Keep all articles on the cleaned page: the schedule need not be its first article.
        val contentLinks = copy.select("a[href]").mapNotNull {
            ScheduleUrl.publicSource(it.absUrl("href"))
        }.toSet()
        // Navigation often contains the only schedule link; retain anchors there, but not images.
        page.select("a[href]").take(500).forEach { anchor ->
            val url = ScheduleUrl.publicSource(anchor.absUrl("href")) ?: return@forEach
            val label = anchor.text() + " " + decoded(anchor.attr("href"))
            val binary = extension.containsMatchIn(url)
            if (!schedule.containsMatchIn(label) && !(binary && schedulePage && url in contentLinks)) return@forEach
            if (!binary && !sameSite(page.baseUri(), url)) return@forEach
            links += ScheduleDocumentLink(url, page.baseUri(), binary)
        }
        content.select("img[src],img[data-src]").take(30).forEach { picture ->
            val raw = picture.absUrl("data-src").ifBlank { picture.absUrl("src") }
            val url = ScheduleUrl.publicSource(raw) ?: return@forEach
            val label = picture.attr("alt") + " " + picture.attr("title") + " " + decoded(raw)
            val small = listOf("width", "height").mapNotNull { picture.attr(it).toIntOrNull() }.any { it < 150 }
            if (small || (!schedule.containsMatchIn(label) && !schedulePage)) return@forEach
            if (!extension.containsMatchIn(url)) return@forEach
            links += ScheduleDocumentLink(url, page.baseUri(), true)
        }
        return links.distinctBy { it.url }.take(12)
    }
    private fun decoded(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    internal fun sameSite(first: String, second: String) = ScheduleUrl.sameOrigin(first, second) || ScheduleUrl.permittedRedirect(first, second)
}
