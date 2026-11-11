package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Document
import java.net.URLDecoder

data class ScheduleDocumentLink(val url: String, val linkedFrom: String, val document: Boolean)

/** Cross-site documents are accepted only when explicitly linked from the selected parish page. */
object ScheduleDocumentLinks {
    private val extension = Regex("(?i)\\.(pdf|jpe?g|png|webp)(?:\\?|$)")
    private val schedule = Regex("(?iu)(расписан|raspisan|schedule|богослужен)")
    private val decorativeImage = Regex("(?iu)(?:^|[\\s/_.-])(logo|логотип|icon|иконка|tildacopy|t-tildalabel)(?=$|[\\s/_.-])")

    fun find(page: Document): List<ScheduleDocumentLink> {
        val links = mutableListOf<ScheduleDocumentLink>()
        val copy = page.clone()
        copy.select("nav,footer,aside,header,[role=navigation],.sidebar,.comments,#t-header,#t-footer,.t-tildalabel").remove()
        val content = copy.selectFirst("main,[role=main]") ?: copy.body()
        // Some site builders publish their visible page heading as a div. Page metadata is
        // a fallback only: a news heading must not inherit a site's generic schedule title.
        val headings = copy.select("h1,h2,h3,[role=heading],.t-title")
        val metadata = copy.title() + " " + copy.select("meta[property=og:title]").attr("content")
        val schedulePage = schedule.containsMatchIn(headings.text()) ||
            (headings.isEmpty() && schedule.containsMatchIn(metadata))
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
        content.select("img[src],img[data-src],img[data-original],img[data-img-zoom-url]").take(30).forEach { picture ->
            // Lazy-loaded previews may be transparent placeholders. OCR needs the published
            // original, but every candidate still goes through the same public-URL policy.
            val url = listOf("data-img-zoom-url", "data-original", "data-src", "src").firstNotNullOfOrNull { attribute ->
                ScheduleUrl.publicSource(picture.absUrl(attribute))?.takeIf {
                    extension.containsMatchIn(it) && !it.contains("/-/empty/", ignoreCase = true)
                }
            } ?: return@forEach
            val label = picture.attr("alt") + " " + picture.attr("title") + " " + decoded(url)
            if (decorativeImage.containsMatchIn(label + " " + picture.className()) ||
                picture.attr("role") == "presentation" || picture.attr("aria-hidden") == "true") return@forEach
            val small = listOf("width", "height").mapNotNull { picture.attr(it).toIntOrNull() }.any { it < 150 }
            // A separate news article does not become a schedule because its sibling is one.
            val articleHeadings = picture.closest("article")?.select("h1,h2,h3,[role=heading],.t-title")
            val inSchedule = if (articleHeadings != null && articleHeadings.isNotEmpty())
                schedule.containsMatchIn(articleHeadings.text()) else schedulePage
            if (small || (!schedule.containsMatchIn(label) && !inSchedule)) return@forEach
            links += ScheduleDocumentLink(url, page.baseUri(), true)
        }
        return links.distinctBy { it.url }.take(12)
    }
    private fun decoded(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    internal fun sameSite(first: String, second: String) = ScheduleUrl.sameOrigin(first, second) || ScheduleUrl.permittedRedirect(first, second)
}
