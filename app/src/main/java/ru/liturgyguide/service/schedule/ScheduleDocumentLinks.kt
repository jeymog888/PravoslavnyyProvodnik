package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

data class ScheduleDocumentLink(val url: String, val linkedFrom: String, val document: Boolean)

/** Cross-site documents are accepted only when explicitly linked from the selected parish page. */
object ScheduleDocumentLinks {
    private val extension = Regex("(?i)\\.(pdf|jpe?g|png|webp)(?:\\?|$)")
    private val schedule = Regex("(?iu)(расписан|raspisan|schedule|богослужен)")
    private val serviceSchedule = Regex("(?iu)(?<![а-яё])расписани[ея][\\s\\p{P}]+(?:богослужени[йя]|служб)(?![а-яё])")
    private val pageControl = Regex("(?iu)(?:следующ[а-яё]*\\s+страниц|предыдущ[а-яё]*\\s+страниц|страниц[а-яё]*\\s*\\d)")
    private val decorativeImage = Regex("(?iu)(?:^|[\\s/_.-])(logo|логотип|icon|иконка|tildacopy|t-tildalabel)(?=$|[\\s/_.-])")

    fun find(page: Document): List<ScheduleDocumentLink> {
        val links = mutableListOf<ScheduleDocumentLink>()
        val preferredHtml = mutableSetOf<String>()
        val pinnedHtml = mutableSetOf<String>()
        val requireDocumentLabel = MyPrihodScheduleHtml.appliesTo(page)
        val copy = page.clone()
        ScheduleHtmlDiscussion.removeFrom(copy)
        copy.select("nav,footer,aside,header,[role=navigation],.sidebar,#sidebar,#header,#footer,.header-links,#t-header,#t-footer,.t-tildalabel").remove()
        // Hidden site-wide dialogs must not borrow the page's schedule heading for
        // unlabelled downloads. Explicit schedule anchors are still read from page below.
        copy.select("[hidden],[aria-hidden=true],.modal").remove()
        val content = copy.selectFirst("main,[role=main]") ?: copy.body()
        // Some site builders publish their visible page heading as a div. Page metadata is
        // a fallback only: a news heading must not inherit a site's generic schedule title.
        val headings = copy.select("h1,h2,h3,[role=heading],.t-title").filter { it.text().isNotBlank() }
        val primaryHeadings = headings.filter { it.tagName() == "h1" || it.attr("aria-level") == "1" }
        val metadata = copy.title() + " " + copy.select("meta[property=og:title]").attr("content")
        // A small "Богослужения" subsection of a contact page does not make its
        // portraits, prayers and social icons timetable attachments.
        val schedulePage = schedule.containsMatchIn((primaryHeadings.ifEmpty { headings }).joinToString(" ") { it.text() }) ||
            (headings.isEmpty() && schedule.containsMatchIn(metadata))
        fun inSchedule(element: Element): Boolean {
            val articleHeadings = element.closest("article")?.select("h1,h2,h3,[role=heading],.t-title")
                ?.filter { it.text().isNotBlank() }.orEmpty()
            if (articleHeadings.isEmpty()) return schedulePage
            val primary = articleHeadings.filter { it.tagName() == "h1" || it.attr("aria-level") == "1" }
            return schedule.containsMatchIn(primary.ifEmpty { articleHeadings }.joinToString(" ") { it.text() })
        }
        // A schedule heading does not make every attachment in the site's footer a schedule.
        // Keep all articles on the cleaned page: the schedule need not be its first article.
        val contentLinks = copy.select("a[href]").filter(::inSchedule).mapNotNull {
            ScheduleUrl.publicSource(it.absUrl("href"))
        }.toSet()
        // Navigation often contains the only schedule link; retain anchors there, but not images.
        page.select("a[href]").filterNot(ScheduleHtmlDiscussion::contains).take(500).forEach { anchor ->
            val publishedUrl = ScheduleUrl.publicSource(anchor.absUrl("href")) ?: return@forEach
            // WordPress sharing buttons repeat the schedule pathname but perform a share
            // action. Require both explicit button metadata and its action parameter; normal
            // date/page queries and public share-token document links remain eligible.
            val sharingButton = anchor.hasAttr("data-shared") || anchor.classNames().any { it.startsWith("share-") }
            val sharingAction = publishedUrl.substringAfter('?', "").split('&').any {
                decoded(it.substringBefore('=')).equals("share", ignoreCase = true)
            }
            if (sharingButton && sharingAction) return@forEach
            val metadata = if (requireDocumentLabel) anchor.attr("title") + " " +
                anchor.select("img").joinToString(" ") { it.attr("alt") + " " + it.attr("title") } else ""
            val label = anchor.text() + " " + decoded(anchor.attr("href")) + " " + metadata
            val binary = extension.containsMatchIn(publishedUrl)
            if (!schedule.containsMatchIn(label) && !(binary && !requireDocumentLabel && publishedUrl in contentLinks)) return@forEach
            val url = if (binary) publishedUrl else secureHtmlLink(page.baseUri(), publishedUrl)
            if (!binary && !sameSite(page.baseUri(), url)) return@forEach
            links += ScheduleDocumentLink(url, page.baseUri(), binary)
            if (!binary) {
                // Every old eligible occurrence can pin safety, even in navigation or a hidden wrapper.
                if (ScheduleCancellation.containsMatchIn(anchor.text() + " " + decoded(anchor.attr("href")))) pinnedHtml += url
                if (preferredHtmlAnchor(anchor, url)) preferredHtml += url
            }
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
            if (small || (!schedule.containsMatchIn(label) && (requireDocumentLabel || !inSchedule(picture)))) return@forEach
            links += ScheduleDocumentLink(url, page.baseUri(), true)
        }
        val admitted = links.distinctBy { it.url }.take(12)
        // Keep the old admitted set and every binary/safety slot; reorder only the other HTML slots.
        val movable = admitted.filter { !it.document && it.url !in pinnedHtml }
        val (preferred, fallback) = movable.partition { it.url in preferredHtml }
        val ordered = (preferred + fallback).iterator()
        return admitted.map { if (it.document || it.url in pinnedHtml) it else ordered.next() }
    }

    private fun preferredHtmlAnchor(anchor: Element, url: String): Boolean {
        if (anchor.closest("nav,header,footer,[role=navigation],[role=menu],[role=menubar],.menu,.pager,.pagination,[hidden],[aria-hidden=true],.modal") != null)
            return false
        // Hidden descendants cannot lend their label to a visible news link. Do not alter the source DOM.
        val visible = anchor.clone().apply { select("[hidden],[aria-hidden=true],.modal").remove() }.text()
            .replace('\u00a0', ' ')
        if (!serviceSchedule.containsMatchIn(visible) || pageControl.containsMatchIn(visible)) return false
        if (anchor.attr("rel").split(Regex("\\s+")).any { it.equals("next", true) || it.equals("prev", true) }) return false
        return url.substringAfter('?', "").substringBefore('#').split('&').none { part ->
            val key = decoded(part.substringBefore('='))
            key.equals("page", true) || key.equals("paged", true)
        }
    }

    private fun secureHtmlLink(pageUrl: String, publishedUrl: String): String {
        val page = ScheduleUrl.publicSource(pageUrl) ?: return publishedUrl
        if (!page.startsWith("https://") || !publishedUrl.startsWith("http://")) return publishedUrl
        // Old same-host anchors may still say HTTP on a working HTTPS page. Request only
        // the HTTPS equivalent; neither foreign HTML nor cleartext fallback is authorized.
        val upgraded = ScheduleUrl.normalized(publishedUrl) ?: return publishedUrl
        return upgraded.takeIf { ScheduleUrl.sameOrigin(page, it) } ?: publishedUrl
    }
    private fun decoded(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    internal fun sameSite(first: String, second: String) = ScheduleUrl.sameOrigin(first, second) || ScheduleUrl.permittedRedirect(first, second)
}
