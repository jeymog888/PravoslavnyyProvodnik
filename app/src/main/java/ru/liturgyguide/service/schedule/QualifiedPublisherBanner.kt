package ru.liturgyguide.service.schedule

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import ru.liturgyguide.model.Parish
import java.io.StringReader
import java.time.LocalDate
import java.util.Locale

/**
 * One current confirmed import may distinguish an identical external publisher title
 * from event venues. This never changes source authority, source bytes or the raw parse.
 */
internal class QualifiedPublisherBanner private constructor(
    private val fingerprint: String,
    private val candidateId: String,
    private val approvedSource: String,
    private val rootUrl: String,
    private val banner: String,
) {
    /** Null preserves the ordinary result. A stronger actual safety observation is never hidden. */
    fun inspect(parish: Parish, binding: ScheduleSiteBinding?, link: ScheduleDocumentLink,
        response: ScheduleResponse, raw: ScheduleParseResult, today: LocalDate): ScheduleParseResult? = runCatching {
        require(eligible(raw) && current(parish, binding))
        require(link.linkedFrom.isNotBlank() && response.url != rootUrl &&
            ScheduleDocumentLinks.sameSite(rootUrl, link.linkedFrom) &&
            ScheduleDocumentLinks.sameSite(rootUrl, link.url) &&
            ScheduleDocumentLinks.sameSite(rootUrl, response.url))
        val page = completePage(response) ?: return null
        val heading = externalBanner(page, rootUrl) ?: return null
        require(literal(heading.text()) == banner)
        val original = HtmlScheduleDocumentExtractor.fromHtml(page)
        val actual = ScheduleDocumentParser.inspect(original, today, parish)
        if (safety(actual)) return actual
        require(eligible(actual))
        val clone = page.clone()
        val removed = externalBanner(clone, rootUrl) ?: return null
        removed.remove()
        val filtered = HtmlScheduleDocumentExtractor.fromHtml(clone)
        val target = ScheduleDocumentLine(heading.text(), headingLevel = 1,
            htmlScopeText = heading.wholeText().takeIf { it.length <= 2_000 })
        var removedLines = 0
        val expected = original.copy(pages = original.pages.map { sourcePage ->
            sourcePage.copy(lines = sourcePage.lines.filterNot { line ->
                (line == target).also { if (it) removedLines++ }
            })
        })
        // This also rejects a take(2) heading becoming visible after the first was removed.
        require(removedLines == 1 && original.pages.all {
            !it.sourceTruncated && it.ocrFailure == null && it.rawOcrEvidence.isNullOrEmpty()
        } && filtered == expected)
        val result = ScheduleDocumentParser.inspect(filtered, today, parish)
        result.takeIf { safety(it) || it.disposition == ScheduleParseDisposition.PARSED && it.events.isNotEmpty() }
    }.getOrNull()

    private fun current(parish: Parish, binding: ScheduleSiteBinding?): Boolean =
        ScheduleEntityIdentity.fingerprint(parish) == fingerprint &&
            binding?.method == ScheduleBindingMethod.USER_CONFIRMED &&
            binding.validFor(parish, rootUrl, binding.evidenceUrl) &&
            binding.siteUrl == approvedSource && binding.candidateConfirmation?.candidate?.id == candidateId

    companion object {
        private const val FOREIGN = "nav,header,footer,aside,[role=complementary],.sidebar,#comments,.comments,.comment-list," +
            ".commentlist,.comment,.comment-content,#respond,.comment-respond,.discussion,blockquote,q"
        private val assignment = Regex("(?<![a-zA-Z0-9_$])ortoxParentSite\\s*=")
        private val literalAssignment = Regex("(?<![a-zA-Z0-9_$.])ortoxParentSite\\s*=\\s*jQuery\\.parseJSON\\(\\s*'([^'\\r\\n]*)'\\s*\\)\\s*;")
        private val word = Regex("[а-яё]+(?:-[а-яё]+)?")
        private val suffix = Regex("([а-яё-]+) благочиния ([а-яё-]+(?: и [а-яё-]+)?) епархии " +
            "([а-яё-]+) митрополии русской православной церкви")
        private val service = Regex("(?iu)(богослуж|служб|литург|вечерн|утрен|всенощ|молеб|панихид|акафист|исповед|" +
            "(?<![а-яё])час(?:ы|ов|а|у|ом)?(?![а-яё]))")
        private val relocation = Regex("(?iu)(переезд|переех|перемещ|перенос|перенес|временно|временн|закрыт)")

        /** Called only with a raw row from this action's current confirmation/recheck. */
        fun fromRoot(parish: Parish, binding: ScheduleSiteBinding?, freshMapRow: ScheduleSourceCandidate?,
            requestedRoot: String, response: ScheduleResponse): QualifiedPublisherBanner? = runCatching {
            val current = freshMapRow ?: return null
            require(OwnContactChildIdentity.ordinaryOsm(parish) &&
                ScheduleEntityIdentity.scope(parish.name) == ScheduleEntityScope.CHURCH &&
                current.provider == OfficialMapCandidateProvider.PROVIDER && current.canConfirm(parish) &&
                OwnContactChildIdentity.pointAgrees(parish, current.latitude, current.longitude))
            val accepted = binding?.takeIf { it.method == ScheduleBindingMethod.USER_CONFIRMED &&
                it.validFor(parish, response.url, it.evidenceUrl) } ?: return null
            val approved = accepted.candidateConfirmation?.candidate ?: return null
            require(current.id == approved.id && current.providerObjectId == approved.providerObjectId &&
                current.sourceUrl == accepted.siteUrl && ScheduleDocumentLinks.sameSite(current.sourceUrl, requestedRoot) &&
                ScheduleDocumentLinks.sameSite(requestedRoot, response.url))
            val page = completePage(response) ?: return null
            // Full static DOM body text, before discussion/chrome/banner/hidden removal.
            // This deliberately makes no claim about arbitrary script/attribute/dynamic notices.
            require(!ScheduleCancellation.containsMatchIn(page.body().text()))
            val title = externalBanner(page, response.url) ?: return null
            val fullTitle = literal(title.text())
            val pieces = literal(current.name).split(" д. ")
            require(pieces.size == 2 && literal(current.name).split(' ').count { it == "д." } == 1)
            val identity = pieces[0]
            val locality = pieces[1]
            require(identity.length in 1..300 && OwnContactChildIdentity.sameName(parish.name, identity) &&
                ScheduleEntityIdentity.scope(identity) == ScheduleEntityScope.CHURCH &&
                ScheduleEntityIdentity.normalize(identity).split(' ').count { it in setOf("храм", "церковь", "собор", "часовня") } == 1)
            require(locality.split(' ').let { it.size in 1..4 && it.all(::wordFits) })
            require(parish.city.isBlank() || literal(parish.city) == locality)
            val wantedAddress = PalomnikStreetAddress.parse(parish.address)
            val publishedAddress = PalomnikStreetAddress.parse(current.address)
            require(wantedAddress == null || publishedAddress == null ||
                !MoscowChurchIdentity.definitelyOtherAddress(wantedAddress, publishedAddress))
            require(grammar(fullTitle, identity + " деревни " + locality))

            val content = main(page)
            require(content !== page.body() && content.cssSelector() == main(cleaned(page)).cssSelector())
            val block = content.select(".reg-info").single()
            require(!foreign(block) && !hidden(block) && block.parents().none(::hidden))
            val name = block.select("#eparchy-info .t1").single()
            require(name.children().isEmpty() && name.childNodes().all { it is TextNode } && !hidden(name) && name.parents().none(::hidden))
            require(literal(name.text()) == literal(current.name))
            val sites = block.select(".reg-info-contact a[href]").map {
                require(!foreign(it) && !hidden(it) && it.parents().none(::hidden))
                requireNotNull(ScheduleUrl.publicSource(it.absUrl("href")))
            }.distinct()
            require(sites.single() == response.url)
            val rawSource = response.bytes.toString(Charsets.UTF_8)
            require(assignment.findAll(rawSource).count() == 1)
            val script = content.select("script").filter { assignment.containsMatchIn(it.data()) }.single()
            require(!script.hasAttr("src") && !foreign(script) && !hidden(script) && script.parents().none(::hidden) &&
                script.previousElementSibling() === block)
            val json = literalAssignment.find(script.data())?.groupValues?.get(1) ?: return null
            val array = strictJson(json).asJsonArray
            require(array.size() == 1 && array.single().isJsonObject)
            val map = array.single().asJsonObject
            require(map.size() in 5..16)
            fun string(key: String): String = map.get(key).let {
                require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString
            }
            require(string("id") == current.providerObjectId && string("type") == "40" &&
                literal(string("ti")) == literal(current.name))
            val latitude = string("la").toDouble()
            val longitude = string("lo").toDouble()
            require(latitude == current.latitude && longitude == current.longitude &&
                OwnContactChildIdentity.pointAgrees(parish, latitude, longitude))
            QualifiedPublisherBanner(ScheduleEntityIdentity.fingerprint(parish), current.id, accepted.siteUrl,
                response.url, fullTitle)
        }.getOrNull()

        private fun completePage(response: ScheduleResponse): Document? {
            if (ContactNavigationRootAudit.sourceBounds(response) != null) return null
            val page = response.html() ?: return null
            // Test the original DOM before either prepare routine could discard or transform it.
            if (page.select("script[type=text/plain]").isNotEmpty() || MyPrihodScheduleHtml.appliesTo(page)) return null
            return page
        }

        private fun cleaned(page: Document): Document = page.clone().also {
            ScheduleHtmlDiscussion.removeFrom(it)
            it.select("script,style,nav,footer,aside,header,[hidden],.sidebar").remove()
        }

        private fun main(page: Document): Element = page.selectFirst("main,[role=main]") ?:
            page.select("#content").singleOrNull()?.takeIf { it.select("h1").size == 1 } ?: page.body()

        private fun externalBanner(page: Document, homeUrl: String): Element? {
            val clean = cleaned(page)
            val content = main(clean)
            if (content === clean.body()) return null
            val headings = page.select("h1").filter { heading ->
                // Locate on the original DOM but use the same extractor main selection.
                val copy = clean.select("h1").firstOrNull { it.cssSelector() == heading.cssSelector() }
                copy != null && copy !in content.getAllElements() && !foreign(heading) &&
                    heading.parents().none(::hidden) && !hidden(heading)
            }.filter { heading ->
                val anchor = heading.children().singleOrNull()?.takeIf { it.tagName() == "a" } ?: return@filter false
                heading.childNodes().all { it === anchor || it is TextNode && it.text().isBlank() } &&
                    anchor.children().isEmpty() && anchor.childNodes().all { it is TextNode } &&
                    !hidden(anchor) && anchor.attr("rel").trim().lowercase(Locale.ROOT) == "home" &&
                    ScheduleUrl.publicSource(anchor.absUrl("href")) == homeUrl
            }
            return headings.singleOrNull()
        }

        private fun hidden(element: Element): Boolean = element.hasAttr("hidden") ||
            element.attr("aria-hidden").equals("true", ignoreCase = true) ||
            Regex("(?iu)(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(element.attr("style"))
        private fun foreign(element: Element): Boolean = element.closest(FOREIGN) != null || ScheduleHtmlDiscussion.contains(element)
        private fun literal(value: String): String = value.map { if (it == '\u00a0' || it.isWhitespace()) ' ' else it }
            .joinToString("").replace(Regex(" +"), " ").trim().lowercase(Locale.ROOT)
        private fun wordFits(value: String): Boolean = value.length in 1..40 && word.matches(value)
        private fun adjective(value: String, endings: Set<String>): Boolean = wordFits(value) && endings.any { ending ->
            value.endsWith(ending) && value.removeSuffix(ending).substringAfterLast('-').let {
                it.isNotEmpty() && it.all { character -> character in 'а'..'я' || character == 'ё' }
            }
        }
        private fun grammar(value: String, prefix: String): Boolean {
            if (value.length > 500 || value.any(Char::isDigit) || service.containsMatchIn(value) ||
                relocation.containsMatchIn(value) || ScheduleCancellation.containsMatchIn(value) ||
                !value.startsWith(prefix + " ")) return false
            val parts = suffix.matchEntire(value.removePrefix(prefix + " ")) ?: return false
            return adjective(parts.groupValues[1], setOf("ского", "цкого")) &&
                parts.groupValues[2].split(" и ").let { it.size in 1..2 && it.all { part -> adjective(part, setOf("ской", "цкой")) } } &&
                adjective(parts.groupValues[3], setOf("ской", "цкой"))
        }
        private fun safety(result: ScheduleParseResult): Boolean = result.safetyConflict || result.unresolvedWithdrawal ||
            result.withdrawsAll || result.withdrawnDates.isNotEmpty() || result.withdrawnStarts.isNotEmpty()
        private fun eligible(result: ScheduleParseResult): Boolean = result.disposition == ScheduleParseDisposition.REJECTED &&
            result.events.isEmpty() && !safety(result) && result.rejectionReason == null

        private fun strictJson(text: String): JsonElement {
            require(text.length in 1..30_000)
            return JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                var values = 0
                fun read(depth: Int): JsonElement {
                    require(depth < 8 && ++values <= 100)
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                require(key.length <= 40 && !obj.has(key))
                                obj.add(key, read(depth + 1))
                            }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
                            reader.beginArray(); while (reader.hasNext()) array.add(read(depth + 1)); reader.endArray()
                        }
                        JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 500) })
                        JsonToken.NUMBER -> JsonPrimitive(reader.nextString().also { require(it.length <= 40) }.toBigDecimal())
                        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                        else -> error("Not literal JSON")
                    }
                }
                read(0).also { require(reader.peek() == JsonToken.END_DOCUMENT) }
            }
        }
    }
}
