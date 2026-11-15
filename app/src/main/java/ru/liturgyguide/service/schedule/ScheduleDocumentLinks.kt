package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import ru.liturgyguide.model.Parish

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


/** A one-attempt discovery hint, never a venue, year, cancellation or parser permission. */
internal class ScheduleNavigationIntent private constructor(
    private val attempt: Any,
    private val fingerprint: String,
    private val root: String,
    private val parent: ScheduleResponse,
    private val parentSha: String,
    // Discovery uses this original admitted cue edge; the FIFO request keeps its own provenance.
    private val cueLink: ScheduleDocumentLink,
) {
    private var consumed = false

    /** The old ordered result is always the exact prefix; failed use grants no extra link. */
    fun discover(parish: Parish, root: String, attempt: Any, link: ScheduleDocumentLink,
        response: ScheduleResponse, parsed: ScheduleParseResult,
        admitted: List<ScheduleDocumentLink>): List<ScheduleDocumentLink> {
        if (consumed || this.attempt !== attempt || this.root != root ||
            fingerprint != ScheduleEntityIdentity.fingerprint(parish) || link.document ||
            link.url != cueLink.url || cueLink.document || cueLink.linkedFrom != parent.url ||
            ScheduleSourceCandidate.sha(parent.bytes) != parentSha) return admitted
        consumed = true
        return runCatching {
            require(!safety(parsed) && exactTarget(cueLink.url, response.url))
            val page = originalPage(response) ?: return admitted
            require(admitted == ScheduleDocumentLinks.find(page))
            val area = ownArea(page) ?: return admitted
            // A nonblank title/og:title is harmless metadata, never inherited service context.
            require(page.select("h1,h2,h3,[role=heading],.t-title").none {
                visible(it, area) && it.text().isNotBlank()
            })
            val extras = mutableListOf<ScheduleDocumentLink>()
            page.select("a[href]").take(500).filter { inArea(it, area) && wholeVisible(it, area) }.forEach { anchor ->
                val url = ScheduleUrl.publicSource(anchor.absUrl("href")) ?: return@forEach
                val pictureLabel = anchor.attr("title") + " " + anchor.text() + " " + decoded(url) + " " +
                    anchor.select("img").joinToString(" ") { it.attr("alt") + " " + it.attr("title") }
                if (extension.containsMatchIn(url) && !url.contains("/-/empty/", ignoreCase = true) &&
                    !decorative.containsMatchIn(pictureLabel + " " + anchor.className()) &&
                    !anchor.attr("role").equals("presentation", true) && anchor.select("img").all { picture ->
                        imageUrl(picture)?.let { usableImage(picture, it, area) } == true
                    }) extras += ScheduleDocumentLink(url, response.url, true)
            }
            val directImageChoices = mutableMapOf<String, String>()
            page.select("img[src],img[data-src],img[data-original],img[data-img-zoom-url]").take(30)
                .filter { inArea(it, area) && visible(it, area) }.forEach { picture ->
                    val base = imageUrl(picture) ?: return@forEach
                    if (!usableImage(picture, base, area)) return@forEach
                    val url = directImageChoices[base] ?: readingImageUrl(picture, admitted + extras)
                        ?.takeIf { usableImage(picture, it, area) } ?: base
                    if (usableImage(picture, url, area)) {
                        directImageChoices.putIfAbsent(base, url)
                        extras += ScheduleDocumentLink(url, response.url, true)
                    }
                }
            (admitted + extras).distinctBy { it.url }.take(12)
        }.getOrDefault(admitted)
    }

    companion object {
        private val extension = Regex("(?i)\\.(pdf|jpe?g|png|webp)(?:\\?|$)")
        private val raster = Regex("(?i)\\.(jpe?g|png|webp)(?:\\?|$)")
        private val decorative = Regex("(?iu)(?:^|[\\s/_.-])(logo|логотип|icon|иконка|tildacopy|t-tildalabel)(?=$|[\\s/_.-])")
        private const val excluded = "nav,header,footer,aside,[role=navigation],[role=complementary],[role=menu]," +
            "[role=menubar],.menu,.pager,.pagination,.modal,dialog,[role=dialog],#sidebar,.sidebar," +
            "#header,#footer,.header-links,#t-header,#t-footer,.t-tildalabel," +
            "#comments,.comments,.comment-list,.commentlist,.comment,.comment-content,#respond,.comment-respond,.discussion,blockquote,q"
        private val labels = setOf("расписание", "расписание богослужений", "расписание служб")
        private val hiddenStyle = Regex("(?iu)(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)")

        /** Only the original response and the unchanged admitted list may issue this hint. */
        fun issue(parish: Parish, root: String, attempt: Any, response: ScheduleResponse,
            parsed: ScheduleParseResult, admitted: List<ScheduleDocumentLink>): Map<String, ScheduleNavigationIntent> = runCatching<Map<String, ScheduleNavigationIntent>> {
            require(!safety(parsed) && ScheduleUrl.publicSource(root) == root &&
                ScheduleDocumentLinks.sameSite(root, response.url))
            val page = originalPage(response) ?: return emptyMap()
            require(admitted == ScheduleDocumentLinks.find(page))
            val area = ownArea(page) ?: return emptyMap()
            val htmlTargets = admitted.filter { !it.document && it.linkedFrom == response.url }.map { it.url }.toSet()
            val sha = ScheduleSourceCandidate.sha(response.bytes)
            val result = linkedMapOf<String, ScheduleNavigationIntent>()
            page.select("a[href]").take(500).filter { inArea(it, area) && wholeVisible(it, area) }.forEach { anchor ->
                if (literal(anchor.text()) !in labels) return@forEach
                val href = anchor.attr("href").trim()
                if (href.isEmpty() || href.startsWith('#')) return@forEach
                val published = ScheduleUrl.publicSource(anchor.absUrl("href")) ?: return@forEach
                if (extension.containsMatchIn(published)) return@forEach
                // Exactly the same upgrade as the old HTML discovery, never an extra alias.
                val emitted = if (response.url.startsWith("https://") && published.startsWith("http://"))
                    ScheduleUrl.normalized(published)?.takeIf { ScheduleUrl.sameOrigin(response.url, it) } ?: published
                else published
                if (emitted !in htmlTargets || emitted == response.url || !ScheduleUrl.sameOrigin(response.url, emitted)) return@forEach
                if (anchor.attr("rel").split(Regex("\\s+")).any { it.equals("next", true) || it.equals("prev", true) }) return@forEach
                val query = URI(emitted).rawQuery.orEmpty().split('&').map { decoded(it.substringBefore('=')).lowercase(Locale.ROOT) }
                if (query.any { it in setOf("page", "paged", "share") }) return@forEach
                val cueLink = admitted.first { it.url == emitted && !it.document && it.linkedFrom == response.url }
                result.putIfAbsent(emitted, ScheduleNavigationIntent(attempt, ScheduleEntityIdentity.fingerprint(parish),
                    root, response, sha, cueLink))
            }
            result
        }.getOrDefault(emptyMap())

        // The anchor path must judge exactly the image URL the direct-image path would use.
        // A decorative unused src cannot override a valid published lazy original.
        private fun imageUrl(picture: Element): String? =
            listOf("data-img-zoom-url", "data-original", "data-src", "src").firstNotNullOfOrNull { attribute ->
                ScheduleUrl.publicSource(picture.absUrl(attribute))?.takeIf {
                    extension.containsMatchIn(it) && !it.contains("/-/empty/", ignoreCase = true)
                }
            }

        /** Pick one explicitly published width variant only for a new direct image.
         * The declared aspect is a selection estimate; the decoder still enforces real bounds. */
        private fun readingImageUrl(picture: Element, alreadyAdmitted: List<ScheduleDocumentLink>): String? {
            val base = imageUrl(picture) ?: return null
            if (alreadyAdmitted.any { it.url == base } || picture.closest("a[href],picture") != null ||
                ScheduleUrl.publicSource(picture.absUrl("src")) != base ||
                listOf("data-img-zoom-url", "data-original", "data-src").any { picture.hasAttr(it) }) return base
            return runCatching {
                val width = picture.attr("width").toInt().also { require(it in 1..30_000) }
                val height = picture.attr("height").toInt().also { require(it in 1..30_000) }
                val raw = picture.attr("srcset").also { require(it.length in 1..8_192) }
                val entries = raw.split(',').also { require(it.size in 2..16) }.map { entry ->
                    val parts = entry.trim().split(Regex("\\s+")).also { require(it.size == 2) }
                    require(Regex("[1-9][0-9]{0,4}w").matches(parts[1]))
                    val candidateWidth = parts[1].dropLast(1).toInt().also { require(it <= 30_000) }
                    val resolved = URI(picture.baseUri()).resolve(parts[0])
                    require(resolved.rawFragment == null)
                    val url = requireNotNull(ScheduleUrl.publicSource(resolved.toString()))
                    require(raster.containsMatchIn(url) && !url.contains("/-/empty/", ignoreCase = true) &&
                        ScheduleUrl.sameOrigin(base, url))
                    url to candidateWidth
                }
                require(entries.map { it.first }.distinct().size == entries.size &&
                    entries.map { it.second }.distinct().size == entries.size)
                require(entries.singleOrNull { it.first == base }?.second == width)
                entries.filter { (_, w) ->
                    val estimatedHeight = (w.toLong() * height + width - 1) / width
                    w >= width && estimatedHeight in 1..30_000 &&
                        w.toLong() * estimatedHeight <= ScheduleDocumentExtractor.MAX_IMAGE_PIXELS
                }.maxByOrNull { it.second }?.first ?: base
            }.getOrDefault(base)
        }
        private fun usableImage(picture: Element, url: String, area: Element): Boolean {
            val label = picture.attr("alt") + " " + picture.attr("title") + " " + decoded(url)
            val small = listOf("width", "height").mapNotNull { picture.attr(it).toIntOrNull() }.any { it < 150 }
            return visible(picture, area) && !small && !decorative.containsMatchIn(label + " " + picture.className()) &&
                !picture.attr("role").equals("presentation", true) && !picture.attr("aria-hidden").equals("true", true)
        }

        private fun originalPage(response: ScheduleResponse): Document? {
            if (ScheduleUrl.publicSource(response.url) != response.url) return null
            val page = response.html() ?: return null
            if (page.select("script[type=text/plain]").isNotEmpty() || MyPrihodScheduleHtml.appliesTo(page)) return null
            return page
        }
        private fun ownArea(page: Document): Element? {
            val mains = page.select("main,[role=main]")
            val area = if (mains.isNotEmpty()) mains.singleOrNull() else page.select("article").singleOrNull()
            return area?.takeIf { visible(it, it) && it.select("article").size <= 1 }
        }
        private fun inArea(element: Element, area: Element): Boolean = element === area || area in element.parents()
        private fun hidden(element: Element, layoutMain: Element?): Boolean = element.hasAttr("hidden") ||
            element.attr("aria-hidden").equals("true", true) || hiddenStyle.containsMatchIn(element.attr("style")) ||
            element.classNames().any { it.startsWith("sidebar-") || it.startsWith("sidebar_") } &&
                !(layoutMain != null && (element === layoutMain || layoutMain.parents().any { it === element }))
        private fun visible(element: Element, area: Element): Boolean {
            // A unique semantic main scopes layout modifiers; actual sidebar descendants
            // and explicit hidden/sidebar semantics retain every existing veto.
            val layoutMain = area.takeIf { inArea(element, it) &&
                (it.tagName() == "main" || it.attr("role").equals("main", true)) }
            return element.closest(excluded) == null && !ScheduleHtmlDiscussion.contains(element) &&
                !hidden(element, layoutMain) && element.parents().none { hidden(it, layoutMain) }
        }
        private fun wholeVisible(element: Element, area: Element): Boolean = visible(element, area) &&
            element.getAllElements().all { visible(it, area) }
        private fun literal(text: String) = text.map { if (it == '\u00a0' || it.isWhitespace()) ' ' else it }
            .joinToString("").replace(Regex(" +"), " ").trim().lowercase(Locale.ROOT)
        private fun decoded(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        private fun safety(result: ScheduleParseResult) = result.safetyConflict || result.unresolvedWithdrawal || result.withdrawsAll ||
            result.withdrawnDates.isNotEmpty() || result.withdrawnStarts.isNotEmpty()
        private fun exactTarget(requested: String, received: String): Boolean = runCatching {
            require(ScheduleUrl.publicSource(requested) == requested && ScheduleUrl.publicSource(received) == received)
            if (requested == received) return true
            val before = URI(requested); val after = URI(received)
            ScheduleUrl.sameOrigin(requested, received) && before.rawQuery == after.rawQuery &&
                before.rawPath.isNotEmpty() && before.rawPath != "/" && !before.rawPath.endsWith('/') &&
                after.rawPath == before.rawPath + "/"
        }.getOrDefault(false)
    }
}
