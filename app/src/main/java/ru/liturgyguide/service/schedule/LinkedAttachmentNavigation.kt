package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.liturgyguide.model.Parish
import java.net.URI
import java.time.LocalDate

internal enum class LinkedAttachmentDisposition { NO_NAVIGATION_REPLACEMENT, PROVED_LINKED_ATTACHMENT_INDEX, OBSERVED_UNCLASSIFIED_SAFETY }
internal data class LinkedAttachmentAuditResult(val disposition: LinkedAttachmentDisposition, val reason: String,
    val imageUrls: List<String> = emptyList())
internal data class LinkedAttachmentRead(val applicable: Boolean, val sourceUrl: String, val sourceSha256: String,
    val htmlResult: ScheduleParseResult? = null, val documentResult: ScheduleParseResult? = null,
    val audit: LinkedAttachmentAuditResult? = null)

/** Implemented only by the private, one-use issuer nested in a live contact capability. A DTO is not this issuer. */
internal interface LinkedAttachmentNavigationPermit {
    fun reserve(parish: Parish, root: String, binding: ScheduleSiteBinding?, link: ScheduleDocumentLink,
        rescue: Boolean, attempt: Any): LinkedAttachmentNavigationUse?
}
internal interface LinkedAttachmentNavigationUse {
    fun read(response: ScheduleResponse, today: LocalDate, parish: Parish, binding: ScheduleSiteBinding?,
        curated: List<Parish>, attempt: Any): LinkedAttachmentRead
    fun owns(read: LinkedAttachmentRead): Boolean
    fun parsedFor(read: LinkedAttachmentRead): ScheduleParseResult?
    fun linksFor(read: LinkedAttachmentRead): List<ScheduleDocumentLink>
    fun grants(read: LinkedAttachmentRead): Boolean
}

/** Captured inside the successful root audit before its public receipt escapes. */
internal data class ContactProvedNavigation(val target: ScheduleDocumentLink, val subtitle: String,
    val footerFields: List<String>, val phonesAgree: Boolean)

/** Read-only closure of a single published index, with dynamic complete image tuples and no image identity authority. */
internal object LinkedAttachmentIndexAudit {
    private const val ROOT_SUBTITLE = "Елисаветинский храм Одинцовская епархия"
    private const val LEAF_SUBTITLE = "Елисаветинский храм Одинцовской епархии"
    private val records = listOf("rec493057783", "rec493057870", "rec493057971")
    private val imageExtension = Regex("(?i)\\.(?:jpe?g|png|webp)(?:\\?[^#]*)?$")

    fun captureRoot(target: ScheduleDocumentLink, subtitle: String, footer: List<Element>, contactPhone: String?): ContactProvedNavigation {
        val phones = footer.flatMap { it.select("a[href^=tel:]") }
        val phone = phones.singleOrNull()
        val printed = phone?.text()?.let(OwnContactChildIdentity::phone)
        val linked = phone?.attr("href")?.removePrefix("tel:")?.let(OwnContactChildIdentity::phone)
        return ContactProvedNavigation(target.copy(), spacing(subtitle), footer.map(::ownerField),
            contactPhone != null && printed == contactPhone && linked == contactPhone)
    }

    fun generic(result: ScheduleParseResult) = result.disposition == ScheduleParseDisposition.REJECTED && result.events.isEmpty() &&
        result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty() && !result.withdrawsAll &&
        !result.safetyConflict && result.rejectionReason == null
    fun hasSafety(result: ScheduleParseResult) = result.safetyConflict || result.withdrawsAll ||
        result.withdrawnDates.isNotEmpty() || result.withdrawnStarts.isNotEmpty()
    fun frozen(result: ScheduleParseResult) = result.copy(events = result.events.map { it.copy() },
        withdrawnDates = result.withdrawnDates.toSet(), withdrawnStarts = result.withdrawnStarts.toSet())

    fun effectiveParsed(html: ScheduleParseResult, document: ScheduleParseResult): ScheduleParseResult {
        val primary = when {
            hasSafety(html) -> html
            hasSafety(document) -> document
            html.disposition != ScheduleParseDisposition.UNSUPPORTED -> html
            else -> document
        }
        if (!hasSafety(html) && !hasSafety(document)) return primary
        // Neither actual result may hide the other's observed safety. Raw receipts remain intact.
        return primary.copy(safetyConflict = html.safetyConflict || document.safetyConflict,
            withdrawsAll = html.withdrawsAll || document.withdrawsAll,
            withdrawnDates = html.withdrawnDates + document.withdrawnDates,
            withdrawnStarts = html.withdrawnStarts + document.withdrawnStarts)
    }

    fun inspect(response: ScheduleResponse, page: Document, document: ScheduleDocument, htmlResult: ScheduleParseResult,
        documentResult: ScheduleParseResult, owner: ContactProvedNavigation, independentOwner: Boolean): LinkedAttachmentAuditResult {
        fun no(reason: String) = LinkedAttachmentAuditResult(LinkedAttachmentDisposition.NO_NAVIGATION_REPLACEMENT, reason)
        fun fail(reason: String) = LinkedAttachmentAuditResult(LinkedAttachmentDisposition.OBSERVED_UNCLASSIFIED_SAFETY, reason)
        if (!generic(htmlResult) || !generic(documentResult)) return no("actual-parser-result-is-authoritative")
        ContactNavigationRootAudit.sourceBounds(response)?.let { return fail(it) }
        if (document.sourceUrl != response.url || document.format != "html" || document.pages.size != 1 || document.pages.any {
                it.sourceTruncated || it.ocrFailure != null || !it.rawOcrEvidence.isNullOrEmpty() || it.lines.size > 256
            }) return fail("incomplete-or-conflicting-document")
        val lines = listOf(LEAF_SUBTITLE, "Расписание Богослужений", "Made on", "Tilda").map { ScheduleDocumentLine(it) }
        if (document.pages.single().lines != lines) return fail("unexpected-extracted-document")
        val source = response.bytes.toString(Charsets.UTF_8)
        // Read every raw value before constructing any slot normalization. No DOM/data is removed or rewritten.
        if (source.any(::unsafeCharacter)) return fail("control-or-format-character")
        val pending = ArrayDeque<Node>().apply { add(page) }
        val pieces = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            when (node) {
                is TextNode -> pieces += node.wholeText
                is DataNode -> pieces += node.wholeData
                is Comment -> pieces += node.data
                is Element -> pieces += node.attributes().map { it.value }
            }
            node.childNodes().forEach { pending.add(it) }
        }
        if (pieces.any { ScheduleCancellation.containsMatchIn(it) } || ScheduleCancellation.containsMatchIn(page.wholeText()))
            return fail("raw-cancellation")
        if (!LinkedAttachmentIndexTemplate.rawBodiesMatch(source)) return fail("unknown-raw-script-style-or-encoding")
        val subtitle = page.select("#rec492632588 .t-uptitle[field=subtitle]").singleOrNull()?.text()?.let(::spacing)
        if (!independentOwner || !owner.phonesAgree || owner.subtitle != ROOT_SUBTITLE || subtitle != LEAF_SUBTITLE ||
            owner.footerFields.isEmpty() || page.select("footer .t345-text").map(::ownerField) != owner.footerFields)
            return fail("root-publisher-owner-fields")
        val slots = linkedMapOf<Element, Map<String, String>>()
        val urls = mutableListOf<String>()
        for ((index, id) in records.withIndex()) {
            val record = page.select("#$id").singleOrNull() ?: return fail("missing-or-duplicate-image-record")
            val meta = record.select("meta[itemprop=image]").singleOrNull() ?: return fail("image-meta-count")
            val image = record.select("img").singleOrNull() ?: return fail("image-count")
            val url = meta.attr("content")
            if (ScheduleUrl.publicSource(url) != url || !imageExtension.containsMatchIn(url) || url in urls ||
                image.attr("data-original") != url || image.attr("data-img-zoom-url") != url ||
                image.attr("src") != derivative(url, "empty")) return fail("whole-image-tuple")
            urls += url
            val n = index + 1
            slots[meta] = mapOf("content" to "IMAGE_$n")
            slots[image] = mapOf("data-original" to "IMAGE_$n", "data-img-zoom-url" to "IMAGE_$n", "src" to "EMPTY_$n")
        }
        val og = page.select("head meta[property=og:image]").singleOrNull() ?: return fail("og-image-count")
        if (og.attr("content") != derivative(urls.first(), "resize/504x")) return fail("og-image-derivative")
        slots[og] = mapOf("content" to "OG_IMAGE_1")
        if (!LinkedAttachmentIndexTemplate.matches(page, slots)) return fail("whole-technical-template")
        val links = ScheduleDocumentLinks.find(page)
        val expected = listOf(ScheduleDocumentLink(response.url, response.url, false)) + urls.map {
            ScheduleDocumentLink(it, response.url, true)
        }
        if (links != expected) return fail("ordered-published-links")
        return LinkedAttachmentAuditResult(LinkedAttachmentDisposition.PROVED_LINKED_ATTACHMENT_INDEX,
            "complete-current-tuples-and-closed-technical-index", urls.toList())
    }

    private fun derivative(url: String, transform: String): String? = runCatching {
        val uri = URI(url); val path = uri.rawPath; val cut = path.lastIndexOf('/')
        require(cut >= 0 && cut < path.lastIndex)
        val next = path.substring(0, cut) + "/-/" + transform + path.substring(cut)
        "${uri.scheme}://${uri.rawAuthority}$next${uri.rawQuery?.let { "?$it" }.orEmpty()}"
    }.getOrNull()
    private fun spacing(value: String) = value.replace(Regex("[ \\t\\r\\n\\u000C\\u00a0]+"), " ").trim(' ')
    private fun unsafeCharacter(char: Char) = Character.getType(char) == Character.FORMAT.toInt() ||
        char.isISOControl() && char !in listOf('\n', '\r', '\t', '\u000C')
    private fun ownerField(element: Element): String {
        val out = StringBuilder()
        fun field(value: String) { out.append(value.length).append(':').append(value) }
        fun node(value: Node) {
            when (value) {
                is Element -> {
                    out.append('E'); field(value.tagName())
                    value.attributes().sortedBy { it.key }.forEach { field(it.key); field(it.value) }
                    out.append('['); value.childNodes().forEach(::node); out.append(']')
                }
                is TextNode -> spacing(value.wholeText).takeIf(String::isNotEmpty)?.let { out.append('T'); field(it) }
                is DataNode -> { out.append('D'); field(value.wholeData) }
                is Comment -> { out.append('C'); field(value.data) }
            }
        }
        node(element)
        return out.toString()
    }
}
