package ru.liturgyguide.service.schedule

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import ru.liturgyguide.model.Parish
import java.io.StringReader
import java.net.URI
import java.time.Clock

/** Two complete publisher collections, an exact selected contour and source-owned current identity. */
internal class PublisherGeometryResolver(private val transport: ScheduleTransport,
    private val clock: Clock = Clock.systemUTC(),
    private val memo: MutableMap<String, ScheduleSite?> = mutableMapOf()) {
    private class Unavailable : Exception()
    private data class Page(val response: ScheduleResponse, val document: Document)
    private data class Directory(val rows: List<PublisherCatalogRow>, val next: String?)

    suspend fun resolve(parish: Parish, excluded: ScheduleSourceExclusions): ScheduleSite? {
        val profile = PublisherGeometryProfile.PUBLISHED.singleOrNull { it.eligible(parish) } ?: return null
        val key = "publisher-geometry:${profile.id}:${ScheduleEntityIdentity.fingerprint(parish)}"
        if (memo.containsKey(key)) return memo[key]?.takeUnless { excluded.contains(it.url) }
        val found = (check(parish, profile) as? ChildChurchSiteCheck.Confirmed)?.site
        currentCoroutineContext().ensureActive()
        memo[key] = found
        return found?.takeUnless { excluded.contains(it.url) }
    }

    suspend fun revalidate(parish: Parish, previous: ScheduleSiteBinding): ChildChurchSiteCheck {
        val proof = previous.publisherGeometryProof ?: return ChildChurchSiteCheck.Rejected
        if (!previous.validFor(parish, previous.siteUrl, previous.evidenceUrl)) return ChildChurchSiteCheck.Rejected
        val profile = PublisherGeometryProfile.PUBLISHED.singleOrNull { it.id == proof.profileId } ?: return ChildChurchSiteCheck.Rejected
        return when (val result = check(parish, profile)) {
            is ChildChurchSiteCheck.Confirmed -> if (ScheduleDocumentLinks.sameSite(previous.siteUrl, result.site.url)) result
                else ChildChurchSiteCheck.Rejected
            else -> result
        }
    }

    private suspend fun check(parish: Parish, profile: PublisherGeometryProfile): ChildChurchSiteCheck = try {
        withTimeoutOrNull(90_000) {
            val evidence = mutableListOf<PublisherPageEvidence>()
            val links = mutableListOf<PublisherVerifiedLink>()
            var requestCount = 0
            suspend fun fetch(role: String, url: String, maximum: Int): ScheduleResponse {
                require(++requestCount <= 16 && ScheduleUrl.publicSource(url) == url)
                val response = try { transport.fetchBounded(url, maximum) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw Unavailable() }
                currentCoroutineContext().ensureActive()
                require(ScheduleUrl.publicSource(response.url) == response.url && ScheduleUrl.permittedRedirect(url, response.url))
                if (response.bytes.size !in 1..maximum) throw Unavailable()
                evidence += PublisherPageEvidence(role, url, response.url, PublisherGeometry.sha(response.bytes))
                return response
            }
            suspend fun page(role: String, url: String, maximum: Int = 1_000_000): Page {
                val response = fetch(role, url, maximum)
                if (!Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(response.bytes.toString(Charsets.ISO_8859_1))) throw Unavailable()
                return Page(response, response.html() ?: throw Unavailable())
            }
            val full = fetch("osm", requireNotNull(PublisherGeometry.fullUrl(parish)), 768_000)
            val contour = requireNotNull(PublisherGeometry.contour(full, parish))
            val neighbourhood = fetch("neighbourhood", PublisherGeometry.neighbourhoodUrl(contour), 768_000)
            val neighbours = requireNotNull(PublisherGeometry.neighbours(neighbourhood, contour))
            val ortoxPage = page("ortox", profile.ortoxUrl)
            require(ortoxPage.response.url == profile.ortoxUrl)
            val ortoxRows = ortox(ortoxPage, profile)
            val first = page("directory:0", profile.directoryUrl)
            val firstRows = directory(first, profile, 0)
            val secondUrl = requireNotNull(firstRows.next)
            val second = page("directory:20", secondUrl)
            val secondRows = directory(second, profile, 20)
            require(secondRows.next == null)
            val rows = firstRows.rows + secondRows.rows
            require(rows.size >= profile.minimumDirectory && rows.map { it.id }.distinct().size == rows.size)
            val possible = PublisherGeometryProof.possible(ortoxRows, parish)
            require(possible.size in 1..3)
            val passports = mutableListOf<PublisherPassport>()
            for (candidate in possible) {
                links += PublisherVerifiedLink("ortox", "passport:${candidate.id}", "сайт", requireNotNull(candidate.website))
                val own = page("passport:${candidate.id}", requireNotNull(candidate.website), 512_000)
                val parsed = passport(own, candidate)
                require(PublisherIdentity.correspondingPassport(candidate, parsed))
                passports += parsed
            }
            val near = passports.filter { PublisherGeometry.distance(it.mapPoint,
                PublisherPoint(requireNotNull(parish.latitude), requireNotNull(parish.longitude))) <= 150 }.single()
            val official = PublisherGeometryProof.possible(rows, parish).single()
            links += PublisherVerifiedLink(if (official in firstRows.rows) "directory:0" else "directory:20", "detail", "подробнее", requireNotNull(official.detailUrl))
            val detailPage = page("detail", requireNotNull(official.detailUrl), 512_000)
            require(detailPage.response.url == official.detailUrl)
            val detail = detail(detailPage, profile)
            require(detail == official)
            links += PublisherVerifiedLink("detail", "home", "официальный сайт храма", requireNotNull(detail.website))
            val home = page("home", requireNotNull(detail.website))
            fun nav(role: String): String {
                val chosen = navigation(home, navLabels(role))
                links += PublisherVerifiedLink("home", role, chosen.second, chosen.first)
                return chosen.first
            }
            val contactUrl = nav("contact"); val historyUrl = nav("history")
            val clergyUrl = nav("clergy"); val scheduleUrl = nav("schedule")
            require(setOf(contactUrl, historyUrl, clergyUrl, scheduleUrl, home.response.url).size == 5)
            val contacts = page("contact", contactUrl)
            val history = page("history", historyUrl)
            val clergy = page("clergy", clergyUrl)
            val ownIdentity = own(contacts, history, clergy, profile)
            val additional = if (PublisherGeometry.strictlyInside(near.mapPoint, contour.points)) null else {
                val link = detailPage.document.select(".item-page a[href]").filter {
                    !foreign(it) && PublisherIdentity.normalized(it.text()) == "источник фото"
                }.map { requireNotNull(ScheduleUrl.publicSource(it.absUrl("href"))) }.distinct().single()
                require(Regex("https://sobory\\.ru/article/\\?object=[1-9][0-9]{0,12}").matches(link))
                links += PublisherVerifiedLink("detail", "linked-anchor", "источник фото", link)
                linkedAnchor(page("linked-anchor", link, 512_000), link)
            }
            // All identity pages precede the timetable. Consume this exact response in import,
            // so a later connection failure cannot resurrect a cancellation already observed here.
            val schedule = page("schedule", scheduleUrl)
            val proof = PublisherGeometryProof(1, profile.id, profile.version, ScheduleEntityIdentity.fingerprint(parish),
                contour, PublisherGeometry.digest(contour), neighbours, PublisherGeometry.digest(neighbours), ortoxRows, rows,
                PublisherGeometry.digest(ortoxRows), PublisherGeometry.digest(rows), passports, near.id, detail, ownIdentity,
                additional, links.toList(), evidence.toList(), clock.instant().toString())
            require(Gson().toJson(proof).length <= 140_000)
            val binding = ScheduleEntityIdentity.bind(parish, schedule.response.url, requireNotNull(detail.detailUrl), near.name,
                ScheduleBindingMethod.PUBLISHER_GEOMETRY_CHAIN, ScheduleEntityScope.CHURCH).copy(publisherGeometryProof = proof)
            require(binding.validFor(parish, schedule.response.url, requireNotNull(detail.detailUrl)))
            ChildChurchSiteCheck.Confirmed(ScheduleSite(schedule.response.url, requireNotNull(detail.detailUrl), near.name, binding,
                observedSchedule = schedule.response))
        } ?: ChildChurchSiteCheck.Unavailable()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Unavailable) { ChildChurchSiteCheck.Unavailable() }
    catch (_: Exception) { ChildChurchSiteCheck.Rejected }

    private fun ortox(page: Page, profile: PublisherGeometryProfile): List<PublisherCatalogRow> {
        val container = page.document.select("#parent_site_list").single()
        require(!foreign(container) && container.select(".pagination,[data-page],a[rel=next]").isEmpty())
        val lists = container.children().filter { it.tagName() == "ul" }.single()
        val cards = lists.children()
        require(cards.size in profile.minimumOrtOx..2000 && cards.all { it.tagName() == "li" } && container.select(".site-info").size == cards.size)
        return cards.map { card ->
            val info = card.children().filter { it.hasClass("site-info") }.single()
            val h = info.select("h1").single()
            require(Regex("t[1-9][0-9]{0,11}").matches(h.id()))
            fun row(label: String) = info.select("dl").filter { dl -> dl.children().firstOrNull {
                it.hasClass("ch") || it.hasClass("ttl") }?.text()?.let(PublisherIdentity::normalized) == label }.singleOrNull()
            val address = row("адрес")?.select(".cnt")?.singleOrNull()?.text()?.trim()?.takeIf(String::isNotBlank)
            val website = row("сайт")?.select("a[href]")?.mapNotNull { ScheduleUrl.publicSource(it.absUrl("href")) }
                ?.distinct()?.singleOrNull()
            PublisherCatalogRow(h.id(), h.text().trim(), address, website, null, null, null)
        }.also { require(it.map(PublisherCatalogRow::id).distinct().size == it.size) }
    }

    private fun directory(page: Page, profile: PublisherGeometryProfile, index: Int): Directory {
        require(directoryIndex(page.response.url, profile) == index)
        val main = page.document.select(".blog").single()
        require(!foreign(main))
        val heading = main.select(".componentheading").single().text()
        require(PublisherIdentity.normalized(heading).endsWith("город ${PublisherIdentity.normalized(profile.city)}"))
        val items = main.select(".items-row .item")
        require(items.size in 1..20 && main.select(".items-row").size == items.size)
        val rows = items.map { item ->
            val name = item.select("h2").single().text().substringAfterLast(" - ").trim()
            val link = item.select("p.readmore a[href]").single()
            val detail = requireNotNull(ScheduleUrl.publicSource(link.absUrl("href")))
            require(ScheduleUrl.sameOrigin(profile.directoryUrl, detail) && URI(detail).path.startsWith(URI(profile.directoryUrl).path.removeSuffix(".html") + "/"))
            record(item, name, detail)
        }
        val pagination = main.select(".pagination").single()
        val pages = pagination.select("a[href]").map { link ->
            val url = requireNotNull(ScheduleUrl.publicSource(link.absUrl("href")))
            val part = requireNotNull(directoryIndex(url, profile)); require(part in setOf(0, 20)); part to url
        }
        val next = pages.filter { it.first > index }.map { it.second }.distinct().singleOrNull()
        if (index == 0) require(items.size == 20 && pages.any { it.first == 20 } && next != null)
        else require(items.size < 20 && pages.none { it.first > index } && pages.any { it.first == 0 })
        return Directory(rows, next)
    }

    private fun record(owner: Element, name: String, detail: String): PublisherCatalogRow {
        fun field(labels: Set<String>): Element? = owner.children().filter { it.tagName() == "p" }.filter { p ->
            val strong = p.select("strong").firstOrNull() ?: return@filter false
            labels.any { PublisherIdentity.normalized(strong.text()) == it }
        }.singleOrNull()
        fun text(labels: Set<String>): String? = field(labels)?.let { p ->
            val label = requireNotNull(p.select("strong").firstOrNull()).text()
            p.text().substringAfter(label).trim().trimStart(':').trim().takeIf(String::isNotBlank)
        }
        val site = field(setOf("официальный сайт храма"))?.select("a[href]")?.mapNotNull {
            ScheduleUrl.publicSource(it.absUrl("href")) }?.distinct()?.singleOrNull()
        return PublisherCatalogRow(detail, name, text(setOf("адрес", "адресс")), site, detail,
            text(setOf("настоятель")), text(setOf("телефон", "телефоны")))
    }

    private fun detail(page: Page, profile: PublisherGeometryProfile): PublisherCatalogRow {
        val item = page.document.select(".item-page").single()
        require(!foreign(item) && PublisherIdentity.normalized(item.select("h1").single().text())
            .endsWith("город ${PublisherIdentity.normalized(profile.city)}"))
        val name = item.select(".contentheading").single().text().substringAfterLast(" - ").trim()
        return record(item, name, page.response.url)
    }

    private fun passport(page: Page, row: PublisherCatalogRow): PublisherPassport {
        val content = mainContent(page)
        val passport = content.select(".reg-info").single()
        val name = passport.select("#eparchy-info .t1").single().text().trim()
        val address = passport.select("dl.addr").single().let { address ->
            require(address.childrenSize() == 2 && PublisherIdentity.normalized(address.select("dt").single().text()) == "адрес")
            address.select("dd").single().text().trim()
        }
        val rectorRows = passport.select("li").filter { item -> item.children().firstOrNull { it.hasClass("ch") || it.hasClass("ttl") }
            ?.text()?.let(PublisherIdentity::normalized) == "настоятель" }
        require(rectorRows.size <= 1)
        val rector = rectorRows.singleOrNull()?.select(".cnt")?.single()?.text()?.trim().orEmpty()
        val raw = page.response.bytes.toString(Charsets.UTF_8)
        require(ASSIGNMENT.findAll(raw).count() == 1)
        val script = content.select("script").filter { ASSIGNMENT.containsMatchIn(it.data()) }.single()
        require(!script.hasAttr("src") && !foreign(script))
        val json = LITERAL.find(script.data())?.groupValues?.get(1) ?: error("No publisher literal")
        val map = strictJson(json).asJsonArray.single().asJsonObject
        require(map.size() <= 16 && map.get("type")?.asString == "40")
        fun string(key: String) = map.get(key).let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        return PublisherPassport(row.id, name, address, rector, string("id"), string("type"), Parser.unescapeEntities(string("ti"), false),
            PublisherPoint(string("la").toDouble(), string("lo").toDouble()))
    }

    private fun navigation(page: Page, labels: Set<String>): Pair<String, String> {
        val links = page.document.select("nav a[href]").filter { !foreign(it) && it.closest("article,.entry-content") == null &&
            PublisherIdentity.normalized(it.text()) in labels }
        val urls = links.map { link ->
            val url = requireNotNull(ScheduleUrl.publicSource(link.absUrl("href")))
            require(ScheduleUrl.sameOrigin(page.response.url, url)); url
        }.distinct().single()
        return urls to PublisherIdentity.normalized(links.first().text())
    }

    private fun own(contacts: Page, history: Page, clergy: Page, profile: PublisherGeometryProfile): PublisherOwnIdentity {
        val contact = mainContent(contacts)
        fun field(label: String): String {
            val p = contact.children().filter { it.tagName() == "p" && it.select("strong").firstOrNull()?.text()
                ?.let(PublisherIdentity::normalized) == label }.single()
            return p.text().substringAfter(':').trim().trimEnd('.')
        }
        val address = field("адрес"); val phone = field("телефон")
        val content = mainContent(history)
        val statements = content.select("p").mapNotNull { paragraph ->
            Regex("(?iu)наш\\s+храм:\\s*([^()]{3,250})\\(([^()]{3,500})\\)\\.?\\s*$").find(paragraph.text())
        }
        val self = statements.single()
        val name = self.groupValues[1].trim()
        require(PublisherIdentity.building(self.groupValues[2])?.sameBuilding(requireNotNull(PublisherIdentity.building(address))) == true)
        require(PublisherIdentity.city(self.groupValues[2]) == PublisherIdentity.normalized(profile.city))
        val historyHeading = content.select("h2").filter { heading -> PublisherIdentity.normalized(heading.text()).let {
            "истори" in it && "нашего храма" in it } }.single()
        val section = sectionAfter(historyHeading)
        val historical = section.filter { it.tagName() == "p" && "Сначала приход располагался" in it.text() }.single().text()
        val transition = section.filter { it.tagName() == "p" && "где в то время располагался наш храм" in it.text() }.single().text()
        val people = mainContent(clergy)
        val current = people.select("h2").filter { PublisherIdentity.normalized(it.text()) == "священники нашего храма" }.single()
        val rector = sectionAfter(current).flatMap { it.select("li") }.mapNotNull { item ->
            Regex("(?iu)^((?:протоиерей|иерей|священник|игумен|архимандрит) [а-яё-]+(?: [а-яё-]+){1,3}),\\s*настоятель храма$")
                .matchEntire(item.text().trim())?.groupValues?.get(1)
        }.single()
        return PublisherOwnIdentity(name, profile.city, address, rector, phone, historyHeading.text(), historical, transition, self.value)
    }

    private fun sectionAfter(heading: Element): List<Element> {
        val result = mutableListOf<Element>(); var next = heading.nextElementSibling()
        while (next != null && next.tagName() != "h2") { result += next; next = next.nextElementSibling() }
        return result
    }
    private fun mainContent(page: Page): Element {
        val article = page.document.select("article").single()
        require(!foreign(article) && article.closest("nav") == null)
        val original = article.select(".entry-content").single()
        require(!foreign(original) && original.closest("article") == article)
        return original.clone().also { it.select(FOREIGN).remove() }
    }

    private fun linkedAnchor(page: Page, expected: String): PublisherLinkedAnchor {
        require(page.response.url == expected)
        val canonical = page.document.select("link[rel=canonical]").single().attr("href")
        require(canonical == expected)
        val values = page.document.select("script[type=application/ld+json]").map { strictJson(it.data()) }
        val churches = values.filter { it.isJsonObject && it.asJsonObject.get("@type")?.asString == "Church" }
        val church = churches.single().asJsonObject
        require(church.get("url")?.asString == canonical)
        val address = church.getAsJsonObject("address"); val geo = church.getAsJsonObject("geo")
        require(address.get("@type")?.asString == "PostalAddress" && geo.get("@type")?.asString == "GeoCoordinates")
        return PublisherLinkedAnchor(canonical, church.get("name").asString, address.get("addressLocality").asString,
            address.get("streetAddress").asString, PublisherPoint(geo.get("latitude").asDouble, geo.get("longitude").asDouble))
    }

    private fun foreign(element: Element) = element.closest(FOREIGN) != null
    companion object {
        private const val FOREIGN = "aside,footer,.sidebar,[role=complementary],#comments,.comments,.comment,.comment-content,.discussion,blockquote,q"
        private val ASSIGNMENT = Regex("(?<![a-zA-Z0-9_$])ortoxParentSite\\s*=")
        private val LITERAL = Regex("(?<![a-zA-Z0-9_$.])ortoxParentSite\\s*=\\s*jQuery\\.parseJSON\\(\\s*'([^'\\r\\n]*)'\\s*\\)\\s*;")
        internal fun navLabels(role: String): Set<String> = when (role) {
            "contact" -> setOf("контакты")
            "history" -> setOf("о храме", "о нашем храме")
            "clergy" -> setOf("духовенство", "духовенство храма")
            "schedule" -> setOf("расписание", "расписание богослужений")
            else -> emptySet()
        }

        internal fun directoryIndex(url: String, profile: PublisherGeometryProfile): Int? = runCatching {
            val target = URI(url); val base = URI(profile.directoryUrl)
            require(ScheduleUrl.sameOrigin(url, profile.directoryUrl) && target.path == base.path && target.fragment == null)
            if (target.rawQuery == null) 0 else {
                val value = Regex("(?:start|limitstart)=([0-9]{1,3})").matchEntire(target.rawQuery) ?: error("Unknown pagination")
                value.groupValues[1].toInt()
            }
        }.getOrNull()

        /** No duplicate keys, execution, coercion or multiple map objects. */
        private fun strictJson(text: String): JsonElement {
            require(text.length in 1..30_000)
            return JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                var count = 0
                fun read(depth: Int): JsonElement {
                    require(depth < 12 && ++count <= 2000)
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                            reader.beginObject()
                            while (reader.hasNext()) { val key = reader.nextName(); require(!obj.has(key) && key.length <= 100); obj.add(key, read(depth + 1)) }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> JsonArray().also { array -> reader.beginArray(); while (reader.hasNext()) array.add(read(depth + 1)); reader.endArray() }
                        JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 10_000) })
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
