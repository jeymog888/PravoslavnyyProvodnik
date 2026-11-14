package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Comment
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.liturgyguide.model.Parish
import java.util.Locale

internal enum class ContactRootAuditDisposition {
    NO_APPLICABLE_FRESH_WITNESS, PROVED_NAVIGATION_ROOT, OBSERVED_UNCLASSIFIED_SAFETY
}
internal enum class ContactRootCardKind {
    PUBLISHER_CONTACT, SELECTED_CHILD_CONTACT, MAPPED_DIRECTORY_CONTACT, TERRITORY_DESCRIPTION, UNDATED_RECURRING_DIRECTORY
}
internal data class ContactRootOpeningPeriod(val days: String, val start: String, val end: String)
internal data class ContactRootContactFields(val locality: String? = null, val phone: String? = null, val email: String? = null,
    val address: String? = null, val directions: List<String> = emptyList(),
    val openingHours: List<ContactRootOpeningPeriod> = emptyList(), val territoryDescription: String? = null)
internal data class ContactRootInventoryCard(val owner: String, val name: String, val kind: ContactRootCardKind,
    val lines: List<String>, val latitude: Double? = null, val longitude: Double? = null,
    val contact: ContactRootContactFields = ContactRootContactFields())
internal data class ContactRootDirectoryStatement(val owner: String, val name: String, val text: String, val time: String)
internal data class ContactRootAuditResult(val disposition: ContactRootAuditDisposition, val reason: String,
    val inventory: List<ContactRootInventoryCard> = emptyList(),
    val recurringStatements: List<ContactRootDirectoryStatement> = emptyList(),
    val checkedScheduleTarget: String? = null) {
    companion object {
        fun notApplicable(reason: String) = ContactRootAuditResult(ContactRootAuditDisposition.NO_APPLICABLE_FRESH_WITNESS, reason)
    }
}

/** One complete observed contact-directory layout. It proves navigation, never a venue or a service. */
internal object ContactNavigationRootAudit {
    internal const val MAX_BYTES = 262_144
    internal const val MAX_NODES = 1_600
    internal const val MAX_LINES = 256
    internal const val MAX_DEPTH = 64
    private const val MAX_LINE = 1_000
    private val clock = "(?:[01]?[0-9]|2[0-3])[.:][0-5][0-9]"
    private val dailyOpening = Regex("(?iu)^Храм открыт ежедневно с $clock до $clock\\.?$")
    private val weeklyOpening = Regex("(?iu)^Храм открыт Пн-Сб с $clock до $clock, Вск $clock-$clock\\.$")
    private val quotedClock = Regex("[\"']\\s*$clock\\s*[\"']")
    private val recurring = Regex("(?iu)^По воскресным дням в ($clock) совершается литургия\\.$")
    private val service = Regex("(?iu)(расписан|богослуж|литург|молеб|панихид|акафист|вечерн|утрен|всенощ|исповед|служб|крестн[а-яё]*\\s+ход|schedule|raspisan|liturgy|worship)")
    private val date = Regex("(?iu)(?:\\b(?:19|20|21)[0-9]{2}\\b|\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|" +
        "январ[ьяе]|феврал[ьяе]|март[ае]?|апрел[ьяе]|(?<![а-яё])ма[йяе](?![а-яё])|июн[ьяе]|июл[ьяе]|август[ае]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе])")
    private val name = Regex("^[\\p{L}\\p{M} ,.\"«»'-]+$")
    private val territory = Regex("(?iu)^Находится на территории [\\p{L}\\p{M} ,.\"«»'-]{3,160}(?:храма|церкви)\\.$")
    private val locality = Regex("(?iu)^в (?:пос\\. )?[\\p{L}\\p{M} \"«»'-]{2,150}\\.?$")
    private val directionsMap = Regex("^Для автолюбителей ссылка на Google Maps\\.$")
    private val publicDirections = Regex("^Общественным транспортом: электропоездом с [\\p{L} -]{2,100} направления до станции [\\p{L} -]{2,100} " +
        "\\(выход из [1-9] вагона\\) или автобусом [0-9]{1,4} от станции [\\p{L} -]{2,100}\\.$")
    private val carDirections = Regex("^На автомобиле: по [\\p{L} -]{2,100} шоссе до указателя \"[\\p{L} -]{2,100}\", далее по главной дороге\\.$")
    private val address = Regex("^(?:[0-9]{6}, [\\p{L} -]{2,100} область, )?г\\. [\\p{L} -]{2,100}, мкр\\. [\\p{L} -]{2,100}, " +
        "ул\\. [\\p{L} .-]{2,100}, д\\. ?[0-9]{1,4}[а-яА-Я]?,?$")
    private const val PHONE = "[+0-9() -]{10,24}"
    private val childPhone = Regex("^Телефон ($PHONE)$")
    private val parentContact = Regex("^Телефон / факс: ($PHONE); E-mail: ([A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,160}\\.[A-Za-z]{2,20})$")
    private const val DMS_FIELD = "(?:широта|долгота):\\s*[NSEW][+-]?[0-9]{1,3}[°o][0-9]{1,2}'[0-9]{1,2}(?:\\.[0-9]{1,8})?''"
    private val dmsLine = Regex("(?iu)^$DMS_FIELD, $DMS_FIELD\\.$")
    private val gpsLine = Regex("(?iu)^Координаты для GPS навигатора: $DMS_FIELD, $DMS_FIELD\\.$")
    private val nameQualifiers = setOf("крестильный", "крестильная", "кладбищенский", "кладбищенская", "кафедральный", "кафедральная",
        "домовый", "домовая", "деревянный", "деревянная", "каменный", "каменная", "летний", "летняя", "зимний", "зимняя",
        "приписной", "приписная", "пещерный", "пещерная", "блж", "прп", "свт", "мч", "епархия")
    private val typedMarkup = setOf("div", "span", "p", "br", "em", "strong", "b", "i", "u", "a")
    private val dataAttributes = setOf("data-add-submenu-arrow", "data-animationappear", "data-appearoffset", "data-bg-color",
        "data-hideoffset", "data-hook", "data-menu", "data-menu-item-number", "data-menu-submenu-hook", "data-original",
        "data-record-type", "data-submenu-hook", "data-submenu-margin", "data-tilda-formskey", "data-tilda-lazy",
        "data-tilda-page-alias", "data-tilda-page-id", "data-tilda-project-country", "data-tilda-project-id",
        "data-tilda-root-zone", "data-tilda-sign", "data-tooltip-hook", "data-tu-max-height", "data-tu-max-width")

    // Closed recognition of the complete observed technical scripts, never a JavaScript blacklist.
    // New inline or external code must be reviewed before this narrow directory can receive a permit.
    private val observedInlineScriptSha256 = setOf(
        "0df7919c07ae926ff23ef3e7d080bdbc10f11e554f436d3a39e541a424cc456e",
        "1b4a3a2f4518ed635c4469ea7c6770b88d37c49422c2a8b88b01614fc8818c33",
        "61496331dde18e7fffa5ccbccee606b125cf961459fb01b98f52130e0e12c741",
        "7633dd4f927592e585c1ac0386d6f3cf4607b7feb78b7094046dc2c948cb6b87",
        "a2f8a564f46507dcc2dbe21fe5032c3ad4ee872c5b4815fcea8ab844f2b69a85",
        "af5b7bdd2fab0b0d66fce9982bc63b8ab2e0a3a676b622b1148ec371e2ea939d",
        "d27f9b3351a566ba186c2d8fd743efc24e003213d2d56b6f61a1f7733089724f",
        "fe65ea31cf820e249984100e8a3dddbbf176feb636398ca87a54f687a5e9efb8"
    )
    private val observedExternalScripts = setOf(
        "https://neo.tildacdn.com/js/tilda-fallback-1.0.min.js",
        "https://static.tildacdn.com/js/hammer.min.js",
        "https://static.tildacdn.com/js/jquery-1.10.2.min.js",
        "https://static.tildacdn.com/js/tilda-events-1.0.min.js",
        "https://static.tildacdn.com/js/tilda-lazyload-1.0.min.js",
        "https://static.tildacdn.com/js/tilda-menu-1.0.min.js",
        "https://static.tildacdn.com/js/tilda-menu-widgeticons-1.0.min.js",
        "https://static.tildacdn.com/js/tilda-menusub-1.0.min.js",
        "https://static.tildacdn.com/js/tilda-polyfill-1.0.min.js",
        "https://static.tildacdn.com/js/tilda-scripts-3.0.min.js",
        "https://static.tildacdn.com/js/tilda-skiplink-1.0.min.js",
        "https://static.tildacdn.com/js/tilda-slds-1.4.min.js",
        "https://static.tildacdn.com/ws/project5957836/tilda-blocks-page30456500.min.js?t=1780856472"
    )
    private val numericDeclarations = Regex("^(?:const [A-Za-z_][A-Za-z0-9_]{0,31}=[0-9]{1,12};){1,8}$")
    private fun recognizedScript(element: Element): Boolean {
        if (element.parents().any { it.tagName() in setOf("svg", "math") }) return false
        val allowedAttributes = setOf("src", "type", "charset", "async", "defer", "nomodule", "onerror")
        if (element.attributes().any { it.key !in allowedAttributes }) return false
        if (element.hasAttr("charset") && !element.attr("charset").equals("utf-8", true)) return false
        if (element.attr("type").lowercase() !in setOf("", "text/javascript", "application/javascript")) return false
        val data = element.data()
        return if (element.hasAttr("src")) element.attr("src") in observedExternalScripts && data.isBlank()
        else data.isBlank() || ScheduleSourceCandidate.sha(data) in observedInlineScriptSha256 || numericDeclarations.matches(data)
    }
    private fun recognizedEventHandler(element: Element, key: String, value: String): Boolean = when {
        key == "onerror" && element.tagName() in setOf("script", "link") -> value == "this.loaderr='y';"
        key == "onload" && element.tagName() == "link" -> value == "this.media='all';"
        else -> false
    }

    /** Refuse unbounded input before the recursive ordinary HTML document extraction. It cannot issue a permit. */
    fun sourceBounds(response: ScheduleResponse): String? {
        if (response.bytes.size !in 1..MAX_BYTES) return "source-completion-or-byte-cap"
        val source = response.bytes.toString(Charsets.UTF_8)
        if (!Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(source) ||
            source.contains('\uFFFD') || source.lines().size > 1_500) return "source-completion-or-byte-cap"
        val page = response.html() ?: return "not-html"
        val pending = ArrayDeque<Pair<Node, Int>>().apply { add(page to 0) }
        var count = 0
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (++count > MAX_NODES) return "node-cap"
            if (depth > MAX_DEPTH) return "depth-cap"
            node.childNodes().forEach { pending.add(it to depth + 1) }
        }
        return null
    }

    /** Called only by a live capability after exact current-response verification. No network or mutable DOM operations. */
    fun inspect(response: ScheduleResponse, selected: Parish, expected: PublishedChildContactParser.Child,
        document: ScheduleDocument, publisherScope: String,
        onProvedNavigation: ((ContactProvedNavigation) -> Unit)? = null): ContactRootAuditResult {
        val cards = mutableListOf<ContactRootInventoryCard>()
        val statements = mutableListOf<ContactRootDirectoryStatement>()
        fun fail(reason: String) = ContactRootAuditResult(ContactRootAuditDisposition.OBSERVED_UNCLASSIFIED_SAFETY,
            reason, cards.toList(), statements.toList())
        sourceBounds(response)?.let { return fail(it) }
        if (document.sourceUrl != response.url || document.format != "html" || document.pages.size != 1 || document.pages.any {
                it.sourceTruncated || it.ocrFailure != null || !it.rawOcrEvidence.isNullOrEmpty() || it.lines.size > MAX_LINES
            }) return fail("truncated-conflicting-or-unbounded-document")
        val page = response.html() ?: return fail("not-html")
        val nodes = mutableListOf<Node>()
        val pending = ArrayDeque<Node>().apply { add(page) }
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            if (nodes.size >= MAX_NODES) return fail("node-cap")
            nodes += next
            next.childNodes().forEach { pending.add(it) }
        }
        val elements = nodes.filterIsInstance<Element>()
        val textNodes = nodes.filterIsInstance<TextNode>()
        if (textNodes.size > 512 || elements.count { it.tagName() == "a" } > 200 || elements.count { it.tagName() == "img" } > 30)
            return fail("text-link-or-image-cap")
        val rawPieces = nodes.mapNotNull { when (it) {
            is TextNode -> it.wholeText
            is DataNode -> it.wholeData
            is Comment -> it.data
            else -> null
        } } + elements.flatMap { it.attributes().asList().map { attr -> attr.value } }
        // Include footer/hidden/raw data before deciding whether anything is navigation or decoration.
        if (rawPieces.any { ScheduleCancellation.containsMatchIn(withoutFormat(it)) } ||
            ScheduleCancellation.containsMatchIn(withoutFormat(page.wholeText()))) return fail("unclassified-cancellation")
        if (nodes.filterIsInstance<Comment>().any { service.containsMatchIn(it.data) || date.containsMatchIn(it.data) ||
                ScheduleClocks.pattern.containsMatchIn(it.data) }) return fail("relevant-raw-comment")
        if (elements.any { it.tagName() in setOf("table", "time", "del", "s", "strike", "iframe", "object", "embed", "template") ||
                it.hasAttr("data-day") || it.hasAttr("datetime") ||
                it.attr("style").contains(Regex("(?iu)line-through")) }) return fail("schedule-or-struck-structure")
        val ids = elements.map { it.id() }.filter(String::isNotEmpty)
        if (ids.distinct().size != ids.size) return fail("duplicate-dom-id")
        val root = page.select("body > .t-records").singleOrNull() ?: return fail("contact-record-root")
        if (page.title() != "Контакты") return fail("contact-title")
        val contactTitle = root.select(".t-title[field=title]").singleOrNull()?.takeIf { it.text() == "Контакты" }
            ?: return fail("contact-heading")
        val publisherTitle = root.select(".t-uptitle[field=subtitle]").singleOrNull()
            ?.takeIf { fullName(it.text()) && it.text().endsWith("епархия", true) } ?: return fail("publisher-heading")
        val parentBlock = root.select(".t004 .t-text[field=text]").singleOrNull() ?: return fail("publisher-contact-block")
        val childBlocks = root.select(".t195__text")
        if (childBlocks.size !in 2..8) return fail("directory-card-cap")
        val blocks = listOf(parentBlock) + childBlocks
        val owners = blocks.map { block ->
            val record = block.closest(".t-rec") ?: return fail("card-without-owner")
            if (record.parent() !== root || record.id().isEmpty() || block.parents().any { it in blocks || hidden(it) } ||
                block.closest("nav,header,footer,aside,blockquote,#comments,.comments,[hidden],[aria-hidden=true]") != null ||
                block.getAllElements().any { it.tagName() !in typedMarkup || hidden(it) }) return fail("nested-moved-or-hidden-card")
            record
        }
        if (owners.distinct().size != blocks.size) return fail("overlapping-card-owners")
        val header = root.children().filter { it.tagName() == "header" }.singleOrNull() ?: return fail("contact-header-boundary")
        val footer = root.children().filter { it.tagName() == "footer" }.singleOrNull() ?: return fail("contact-footer-boundary")
        val headingOwner = contactTitle.closest(".t-rec") ?: return fail("contact-heading-owner")
        if (publisherTitle.closest(".t-rec") !== headingOwner ||
            root.children().toList() != listOf(header, headingOwner) + owners + listOf(footer)) return fail("moved-or-unclassified-record")
        val parentLines = lines(parentBlock) ?: return fail("publisher-line-cap")
        if (parentLines.size != 8 || parentLines[0] != "Контакты" || parentLines[1] != "Как к нам проехать:" ||
            !publicDirections.matches(parentLines[2]) || !nonTemporal(parentLines[2]) ||
            !carDirections.matches(parentLines[3]) || !nonTemporal(parentLines[3]) || !nonTemporal(parentLines[5]) ||
            !gpsLine.matches(parentLines[4]) || !address.matches(parentLines[5]) ||
            !weeklyOpening.matches(parentLines[7])) return fail("publisher-contact-fields")
        // The physical contact line can include inline email anchors; every character remains in the whole grammar.
        val parentMatch = parentContact.matchEntire(parentLines[6])
        if (parentMatch == null || OwnContactChildIdentity.phone(parentMatch.groupValues[1]) == null)
            return fail("publisher-phone-email")
        val parentPoint = PublishedChildContactParser.dms(parentLines[4]) ?: return fail("publisher-dms")
        val parentHours = openingPeriods(parentLines[7], daily = false) ?: return fail("publisher-opening-hours")
        cards += ContactRootInventoryCard(owners[0].id(), publisherTitle.text(), ContactRootCardKind.PUBLISHER_CONTACT, parentLines,
            parentPoint.first, parentPoint.second, ContactRootContactFields(phone = OwnContactChildIdentity.phone(parentMatch.groupValues[1]),
                email = parentMatch.groupValues[2], address = parentLines[5], directions = parentLines.subList(2, 4), openingHours = parentHours))
        val parsed = PublishedChildContactParser.parse(response)
        val wanted = parsed.children.singleOrNull { it == expected }
        if (parsed.ambiguous || wanted == null) return fail("selected-child-not-unique")
        val nameKeys = mutableSetOf<OwnContactChildIdentity.NameKey>()
        var selectedCount = 0
        for ((index, block) in childBlocks.withIndex()) {
            val all = lines(block) ?: return fail("directory-line-cap")
            val prefix = all.firstOrNull() == "Приписные храмы"
            if (prefix != (index == 0)) return fail("moved-directory-heading")
            val fields = if (prefix) all.drop(1) else all
            val title = fields.firstOrNull()?.takeIf(::fullName) ?: return fail("incomplete-card-name")
            val key = OwnContactChildIdentity.nameKey(title) ?: return fail("invalid-card-name")
            if (!nameKeys.add(key)) return fail("duplicate-card-name")
            val record = owners[index + 1]
            val isSelected = OwnContactChildIdentity.sameName(selected.name, title)
            val point: Pair<Double, Double>?
            val kind: ContactRootCardKind
            val contactFields: ContactRootContactFields
            if (isSelected) {
                if (++selectedCount != 1 || !prefix || fields.size != 7 || title != wanted.name ||
                    !locality.matches(fields[1]) || !nonTemporal(fields[1]) || !directionsMap.matches(fields[2]) || fields[3] != "Координаты для GPS навигатора:" ||
                    !dmsLine.matches(fields[4]) || !dailyOpening.matches(fields[6]))
                    return fail("selected-contact-fields")
                val phone = childPhone.matchEntire(fields[5])?.groupValues?.get(1)?.let(OwnContactChildIdentity::phone)
                point = PublishedChildContactParser.dms(fields[4])
                if (phone == null || phone != wanted.phone || point != (wanted.latitude to wanted.longitude)) return fail("selected-contact-values")
                kind = ContactRootCardKind.SELECTED_CHILD_CONTACT
                contactFields = ContactRootContactFields(locality = fields[1], phone = phone, directions = listOf(fields[2]),
                    openingHours = openingPeriods(fields[6], daily = true) ?: return fail("selected-opening-hours"))
            } else if (fields.size == 5 && locality.matches(fields[1]) && nonTemporal(fields[1]) && directionsMap.matches(fields[2]) &&
                fields[3] == "Координаты для GPS навигатора:" && dmsLine.matches(fields[4])) {
                point = PublishedChildContactParser.dms(fields[4]) ?: return fail("directory-dms")
                kind = ContactRootCardKind.MAPPED_DIRECTORY_CONTACT
                contactFields = ContactRootContactFields(locality = fields[1], directions = listOf(fields[2]))
            } else if (fields.size == 2 && territory.matches(fields[1]) && nonTemporal(fields[1])) {
                point = null; kind = ContactRootCardKind.TERRITORY_DESCRIPTION
                contactFields = ContactRootContactFields(territoryDescription = fields[1])
            } else if (fields.size == 3 && title.startsWith("Крестильный храм ", true) && territory.matches(fields[1]) && nonTemporal(fields[1])) {
                if (index != childBlocks.lastIndex) return fail("moved-recurring-card")
                val whole = recurring.matchEntire(fields[2]) ?: return fail("unknown-recurring-statement")
                val parts = whole.groupValues[1].split('.', ':')
                statements += ContactRootDirectoryStatement(record.id(), title, fields[2], String.format(Locale.ROOT, "%02d:%02d", parts[0].toInt(), parts[1].toInt()))
                point = null; kind = ContactRootCardKind.UNDATED_RECURRING_DIRECTORY
                contactFields = ContactRootContactFields(territoryDescription = fields[1])
            } else return fail("unknown-directory-fields")
            cards += ContactRootInventoryCard(record.id(), title, kind, all, point?.first, point?.second, contactFields)
        }
        if (selectedCount != 1 || statements.size > 1) return fail("selected-or-recurring-count")
        val links = ScheduleDocumentLinks.find(page)
        val navigation = page.select("header a[href],nav a[href],[role=navigation] a[href]").filter { anchor ->
            anchor.text().equals("Расписание Богослужений", true) && links.any { link ->
                !link.document && link.url != response.url && ScheduleDocumentLinks.sameSite(response.url, link.url) &&
                    CuratedPublisherScope.owns(publisherScope, link.url) &&
                    ScheduleUrl.normalized(anchor.absUrl("href")) == ScheduleUrl.normalized(link.url)
            }
        }
        if (navigation.size != 1 || links.size !in 1..3 || links.any { it.document }) return fail("missing-or-ambiguous-schedule-navigation")
        val allowed = blocks + listOf(contactTitle, publisherTitle)
        val outsideLines = mutableListOf<String>()
        for (text in textNodes) {
            val value = clean(text.wholeText)
            if (value.isEmpty()) continue
            if (generateSequence(text.parentNode()) { it.parentNode() }.filterIsInstance<Element>().any { it in allowed }) continue
            val parent = text.parentNode() as? Element ?: return fail("orphan-text")
            if (parent.closest("head") != null && parent.tagName() == "title" && value == "Контакты") continue
            val anchor = parent.closest("header a[href],nav a[href],[role=navigation] a[href]")
            if (anchor != null && anchor.text().length <= 120 &&
                (anchor in navigation || !service.containsMatchIn(anchor.text()) && !date.containsMatchIn(anchor.text()) &&
                    !ScheduleClocks.pattern.containsMatchIn(anchor.text()) && anchor.text().none(Char::isDigit))) continue
            if (parent.closest("footer .t345-text") != null) continue // Whole footer fields are checked below.
            if (parent.closest(".t-tildalabel") != null && value in setOf("Made on", "Tilda")) continue
            outsideLines += value
        }
        if (outsideLines.isNotEmpty()) return fail("unclassified-text-outside-directory")
        val footerBlocks = page.select("footer .t345-text")
        if (footerBlocks.size > 2) return fail("footer-cap")
        for (footer in footerBlocks) {
            val fields = lines(footer) ?: return fail("footer-line-cap")
            if (fields.any { field ->
                val combined = Regex("^(https?://[^\\s,]{1,2048}) , ($PHONE)$").matchEntire(field)
                val combinedValid = combined != null &&
                    ScheduleUrl.publicSource(combined.groupValues[1]) == ScheduleUrl.publicSource(publisherScope) &&
                    OwnContactChildIdentity.phone(combined.groupValues[2]) != null
                field != "," && !(fullName(field) && field.endsWith("епархия", true)) && !(address.matches(field) && nonTemporal(field)) &&
                    ScheduleUrl.publicSource(field) != ScheduleUrl.publicSource(publisherScope) &&
                    !(Regex("^$PHONE$").matches(field) && OwnContactChildIdentity.phone(field) != null) && !combinedValid
            }) return fail("unclassified-footer-field")
        }
        for (element in elements) {
            for (attr in element.attributes()) {
                if (attr.key.startsWith("data-") && attr.key !in dataAttributes) return fail("unknown-source-data-attribute")
                val value = withoutFormat(attr.value)
                if (attr.key.startsWith("on") && !recognizedEventHandler(element, attr.key, value))
                    return fail("unclassified-event-handler")
                if (service.containsMatchIn(value) && !(element in navigation && attr.key == "href")) return fail("schedule-source-attribute")
                if (unclassifiedEncoding(value)) return fail("encoded-source-attribute")
                if (attr.key == "style" && Regex("(?iu)(?:^|[;{])\\s*content\\s*:").containsMatchIn(value))
                    return fail("content-source-attribute")
                if ((quotedClock.containsMatchIn(value) || date.containsMatchIn(value) || ScheduleClocks.pattern.containsMatchIn(value)) &&
                    !literalTechnicalAttribute(element, attr.key, value)) return fail("temporal-source-attribute")
            }
            if (element.tagName() in setOf("script", "style")) {
                if (element.tagName() == "script" && !recognizedScript(element)) return fail("unclassified-script")
                val data = element.data()
                val type = element.attr("type").lowercase()
                if (element.tagName() == "script" && data.isNotBlank() && type !in setOf("", "text/javascript", "application/javascript"))
                    return fail("unknown-raw-data-block")
                if (unclassifiedEncoding(data, css = element.tagName() == "style") ||
                    element.tagName() == "style" && Regex("(?iu)(?:^|[;{])\\s*content\\s*:").containsMatchIn(data) ||
                    service.containsMatchIn(data) || quotedClock.containsMatchIn(data) ||
                    date.containsMatchIn(data) && ScheduleClocks.pattern.containsMatchIn(data))
                    return fail("schedule-or-encoded-raw-data")
            }
        }
        if (blocks.sumOf { lines(it)?.size ?: MAX_LINES + 1 } > MAX_LINES) return fail("line-cap")
        val anchorUrl = ScheduleUrl.normalized(navigation.single().absUrl("href"))
        val target = links.singleOrNull { !it.document && ScheduleUrl.normalized(it.url) == anchorUrl }
            ?: return fail("ambiguous-exact-navigation-target")
        onProvedNavigation?.invoke(LinkedAttachmentIndexAudit.captureRoot(target, publisherTitle.text(), footerBlocks.toList(),
            cards.single { it.kind == ContactRootCardKind.PUBLISHER_CONTACT }.contact.phone))
        return ContactRootAuditResult(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT,
            "complete-typed-directory-with-explicit-schedule-navigation", cards.toList(), statements.toList(), target.url)
    }

    private fun literalTechnicalAttribute(element: Element, key: String, value: String): Boolean {
        // Exact namespace and whole layout values cannot carry a date or a service announcement.
        if (key == "xmlns" && element.tagName() == "svg") return value == "http://www.w3.org/2000/svg"
        if (key == "d" && element.tagName() == "path" && element.closest("svg") != null)
            return Regex("^[Mm][0-9+.,\\s-]+(?:[MmLlHhVvCcSsQqTtAaZz][0-9eE+.,\\s-]*)*$").matches(value)
        if (key in setOf("width", "height") && element.tagName() in setOf("svg", "img"))
            return Regex("^[0-9]+(?:\\.[0-9]+)?(?:px|%)?$").matches(value)
        if (key == "style") {
            val property = "(?:opacity|width|height|top|left|right|bottom|font-size|line-height|z-index|(?:padding|margin)(?:-(?:top|right|bottom|left))?)"
            val scalar = "[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:px|em|rem|%|s|ms|deg)?"
            return Regex("(?i)^(?:\\s*$property\\s*:\\s*$scalar\\s*;?\\s*)+$").matches(value)
        }
        return false
    }

    private fun unclassifiedEncoding(value: String, css: Boolean = false): Boolean {
        // The complete observed numeric media feature cannot emit text. All semantic checks still read the original value.
        val encodingCheck = if (css) value.replace("@media (hover),(min-width:0\\0){", "@media (hover),(min-width:0){") else value
        return '\\' in encodingCheck ||
            Regex("(?iu)\\b(?:atob|unescape|decodeURI(?:Component)?|fromCharCode|fromCodePoint)\\s*\\(").containsMatchIn(encodingCheck) ||
            Regex("(?iu)(?:^|[\\s:'\"(])(?:data|javascript):|&(?:#(?:x[0-9a-f]+|[0-9]+)|[a-z]+);").containsMatchIn(encodingCheck)
    }

    private fun openingPeriods(line: String, daily: Boolean): List<ContactRootOpeningPeriod>? {
        val times = Regex(clock).findAll(line).map { match ->
            val parts = match.value.split('.', ':'); String.format(Locale.ROOT, "%02d:%02d", parts[0].toInt(), parts[1].toInt())
        }.toList()
        if (times.size != (if (daily) 2 else 4)) return null
        val ranges = times.chunked(2)
        if (ranges.any { it[0] >= it[1] }) return null
        return ranges.mapIndexed { index, range -> ContactRootOpeningPeriod(if (daily) "ежедневно" else if (index == 0) "Пн-Сб" else "Вск",
            range[0], range[1]) }
    }

    private fun nonTemporal(value: String) = !service.containsMatchIn(value) && !date.containsMatchIn(value) &&
        !ScheduleClocks.pattern.containsMatchIn(value)
    private fun fullName(value: String) = name.matches(value) && OwnContactChildIdentity.fullName(value) &&
        OwnContactChildIdentity.nameKey(value)?.dedicationAndQualifiers?.any { it !in nameQualifiers } == true &&
        !service.containsMatchIn(value) && !date.containsMatchIn(value) && value.none(Char::isDigit)
    private fun hidden(element: Element): Boolean = element.hasAttr("hidden") || element.attr("aria-hidden") == "true" ||
        Regex("(?iu)(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(element.attr("style"))
    private fun withoutFormat(value: String) = value.filter { Character.getType(it) != Character.FORMAT.toInt() }
    private fun clean(value: String) = withoutFormat(value).replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
    private fun lines(block: Element): List<String>? {
        val out = StringBuilder()
        fun walk(node: Node) {
            if (node is TextNode) out.append(node.wholeText)
            else if (node is Element) {
                val split = node !== block && node.tagName() in setOf("br", "p", "h1", "h2", "h3", "h4", "h5", "h6", "div")
                if (split) out.append('\n')
                node.childNodes().forEach(::walk)
                if (split) out.append('\n')
            }
        }
        walk(block)
        val all = out.toString().split('\n').map(::clean).filter(String::isNotBlank)
        return all.takeIf { it.size <= MAX_LINES && it.all { line -> line.length <= MAX_LINE } }
    }
}
