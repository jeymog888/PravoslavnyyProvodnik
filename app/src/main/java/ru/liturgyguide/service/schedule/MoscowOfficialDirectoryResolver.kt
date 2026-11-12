package ru.liturgyguide.service.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import ru.liturgyguide.model.Parish

/** Finds a publisher for one selected church, never treats a directory's usual hours as today's schedule. */
internal class MoscowOfficialDirectoryResolver(
    private val transport: ScheduleTransport,
    private val search: MoscowDirectorySearchTransport,
    private val timeoutMillis: Long = 15_000,
) {
    init { require(timeoutMillis in 1..20_000) }

    suspend fun resolve(parish: Parish, excluded: ScheduleSourceExclusions,
        memo: MutableMap<String, ScheduleSite?>): ScheduleSite? {
        if (!eligible(parish)) return null
        val key = ScheduleEntityIdentity.fingerprint(parish)
        if (memo.containsKey(key)) return memo[key]?.takeUnless { excluded.contains(it.url) }
        val result = try {
            withTimeoutOrNull(timeoutMillis) {
                val queries = queries(parish.name)
                if (queries.isEmpty()) return@withTimeoutOrNull null
                val responses = search.search(queries)
                if (responses.size != queries.size) return@withTimeoutOrNull null
                val candidates = linkedMapOf<String, MoscowDirectoryCandidate>()
                for (response in responses) {
                    val found = MoscowDirectoryParser.search(response) ?: return@withTimeoutOrNull null
                    for (candidate in found) {
                        if (!MoscowChurchIdentity.sameName(parish.name, candidate.title) || candidate.kind != "Храм") continue
                        val old = candidates.put(candidate.url, candidate)
                        if (old != null && old != candidate) return@withTimeoutOrNull null
                    }
                }
                val selected = PalomnikStreetAddress.parse(parish.address) ?: return@withTimeoutOrNull null
                val possible = candidates.values.filter { candidate ->
                    val address = candidate.address?.let(PalomnikStreetAddress::parse)
                    // Missing evidence cannot silently remove a potential namesake.
                    address == null || !MoscowChurchIdentity.definitelyOtherAddress(selected, address)
                }
                if (possible.size !in 1..2) return@withTimeoutOrNull null
                val matches = mutableListOf<ScheduleSite>()
                for (candidate in possible) {
                    currentCoroutineContext().ensureActive()
                    val response = transport.fetchBounded(candidate.url, MoscowDirectoryParser.CARD_LIMIT)
                    val site = MoscowDirectoryParser.card(response, candidate.url, parish)
                        ?: return@withTimeoutOrNull null
                    matches += site
                }
                matches.singleOrNull()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        memo[key] = result
        return result?.takeUnless { excluded.contains(it.url) }
    }

    companion object {
        private val generic = setOf("храм", "церковь", "собор", "святого", "святой", "святых", "святителя", "преподобного", "праведного")
        internal fun eligible(parish: Parish): Boolean {
            if (ScheduleEntityIdentity.scope(parish.name) !in setOf(ScheduleEntityScope.CHURCH, ScheduleEntityScope.UNKNOWN)) return false
            val lat = parish.latitude ?: return false
            val lon = parish.longitude ?: return false
            if (!lat.isFinite() || !lon.isFinite() || parish.timezone != "Europe/Moscow") return false
            val city = ScheduleEntityIdentity.normalize(parish.city).removePrefix("город ").removePrefix("г ")
            // The rectangle only bounds a directory request; it does not supply a city to
            // a map record. The resulting own card must independently say Moscow.
            return (city == "москва" || city.isBlank() && lat in 55.0..56.1 && lon in 36.7..38.3) &&
                PalomnikStreetAddress.parse(parish.address) != null
        }

        internal fun queries(name: String): List<String> {
            val words = ScheduleEntityIdentity.normalize(name).split(' ').filter(String::isNotBlank)
            val locationIndex = words.indexOfLast { it in setOf("в", "во", "на", "у") }
            val local = words.drop(locationIndex + 1).takeIf { locationIndex >= 0 && it.size in 1..4 &&
                it.none { word -> word in setOf("честь", "москве", "москва", "имя") } }?.joinToString(" ")
            val dedication = words.filterNot { it in generic }.joinToString(" ")
            return listOfNotNull(local, dedication).distinct().filter { it.length in 2..160 }.take(2)
        }
    }
}

internal data class MoscowDirectoryCandidate(val url: String, val title: String, val kind: String, val address: String?)

internal object MoscowDirectoryParser {
    const val CARD_LIMIT = 262_144
    private val cardUrl = Regex("^https://sprav\\.moseparh\\.ru/org/[1-9][0-9]{0,9}$")

    fun search(response: ScheduleResponse): List<MoscowDirectoryCandidate>? {
        if (response.url != UrlConnectionMoscowDirectorySearchTransport.ENDPOINT ||
            response.bytes.size !in 1..UrlConnectionMoscowDirectorySearchTransport.SEARCH_LIMIT ||
            !Regex("(?is)<!--\\s*end \\.main-table\\s*-->\\s*$").containsMatchIn(response.bytes.toString(Charsets.ISO_8859_1))) return null
        val document = response.html() ?: return null
        val scope = document.select(".main-table-monasteries").singleOrNull() ?: return null
        if (scope.select(".pagination,[data-page],a[rel=next]").isNotEmpty()) return null
        val table = scope.select("table").singleOrNull() ?: return null
        val body = table.children().singleOrNull { it.tagName() == "tbody" } ?: return null
        val rows = body.children()
        if (rows.size !in 1..300 || rows.any { it.tagName() != "tr" }) return null
        if (rows.size == 1 && rows.single().children().size == 1 && rows.single().text().trim() == "Ничего не найдено") return emptyList()
        val result = rows.map { row ->
            val cells = row.children()
            if (cells.size != 2 || cells.any { it.tagName() != "td" }) return null
            val kind = cells.getOrNull(0)?.text()?.trim() ?: return null
            if (kind !in setOf("Храм", "Монастырь", "Скит", "Часовня")) return null
            val ownCell = cells.getOrNull(1) ?: return null
            val headings = ownCell.select(".table-head > .text > div > a[href]")
            val heading = headings.singleOrNull() ?: return null
            val url = heading.absUrl("href").takeIf(cardUrl::matches) ?: return null
            val title = heading.text().trim().takeIf { it.length in 1..300 && it.none(Char::isISOControl) } ?: return null
            val addresses = ownCell.select(".small.grey").filter { it.text().trim().startsWith("Адрес:") }
            if (addresses.size > 1) return null
            val address = addresses.singleOrNull()?.text()?.trim()?.removePrefix("Адрес:")?.trim()
            MoscowDirectoryCandidate(url, title, kind, address)
        }
        if (result.map { it.url }.distinct().size != result.size) return null
        return result
    }

    fun card(response: ScheduleResponse, expectedUrl: String, parish: Parish): ScheduleSite? {
        if (!cardUrl.matches(expectedUrl) || response.url != expectedUrl || response.bytes.size !in 1..CARD_LIMIT) return null
        val html = response.bytes.toString(Charsets.ISO_8859_1)
        if (!Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(html)) return null
        val document = response.html() ?: return null
        val heading = document.select(".main-table-item h1").singleOrNull() ?: return null
        val title = heading.text().trim()
        if (title.length !in 1..300 || ScheduleEntityIdentity.scope(title) != ScheduleEntityScope.CHURCH ||
            !MoscowChurchIdentity.sameName(parish.name, title)) return null
        val table = heading.closest("table") ?: return null
        fun field(label: String): Element? = table.select("tr").filter { row ->
            row.closest("table") == table && row.children().firstOrNull { it.select("strong").isNotEmpty() }
                ?.select("strong")?.singleOrNull()?.text()?.trim() == label
        }.singleOrNull()?.children()?.lastOrNull()
        val contacts = field("Контакты")?.select(".item-contacts > .text")?.singleOrNull() ?: return null
        val contactLines = contacts.clone().also { it.select("br").forEach { br -> br.after("\n") } }
            .wholeText().lines().map(String::trim).filter(String::isNotBlank)
        val ownAddress = contactLines.firstOrNull()?.takeIf { it.length in 3..500 &&
            Regex("(?iu)(?<![а-я])(?:г\\.?\\s*)?москва(?=\\s*,|$)").containsMatchIn(it) } ?: return null
        val selectedAddress = PalomnikStreetAddress.parse(parish.address) ?: return null
        val publishedAddress = PalomnikStreetAddress.parse(ownAddress) ?: return null
        if (!MoscowChurchIdentity.addressesAgree(selectedAddress, publishedAddress)) return null
        val coordinateText = field("Координаты для навигации")?.text()?.trim() ?: return null
        val coordinates = Regex("^([0-9]{1,2}\\.[0-9]+)\\s*,\\s*([0-9]{1,3}\\.[0-9]+)$").matchEntire(coordinateText) ?: return null
        val lat = coordinates.groupValues[1].toDoubleOrNull() ?: return null
        val lon = coordinates.groupValues[2].toDoubleOrNull() ?: return null
        val selectedLat = parish.latitude ?: return null
        val selectedLon = parish.longitude ?: return null
        if (!lat.isFinite() || !lon.isFinite() || !selectedLat.isFinite() || !selectedLon.isFinite() ||
            selectedLat !in -90.0..90.0 || selectedLon !in -180.0..180.0 || lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            OpenScheduleSiteResolver.distanceMeters(lat, lon, selectedLat, selectedLon) > 50) return null
        // Missing building details can be tolerated only for a standalone, fully identified
        // church; a child list means the parent address cannot identify this one building.
        if ((selectedAddress.corpus != publishedAddress.corpus || selectedAddress.building != publishedAddress.building) &&
            document.select(".main-table-monasteries").isNotEmpty()) return null
        val websites = contacts.select("a[href]").mapNotNull { link ->
            ScheduleUrl.publicSource(link.absUrl("href"))
        }.distinct()
        val website = websites.singleOrNull() ?: return null
        val serviceLinks = field("Богослужения")?.select("a[href]").orEmpty().mapNotNull { link ->
            ScheduleUrl.publicSource(link.absUrl("href"))?.takeIf { ScheduleDocumentLinks.sameSite(website, it) }
        }.distinct()
        if (serviceLinks.size > 1) return null
        val source = serviceLinks.singleOrNull() ?: website
        return ScheduleSite(source, expectedUrl, title,
            ScheduleEntityIdentity.bind(parish, source, expectedUrl, title, ScheduleBindingMethod.ARTICLE_SCOPE))
    }
}

/** Local policy for this publisher only; generic address matching remains strict. */
internal object MoscowChurchIdentity {
    fun sameName(selected: String, title: String): Boolean {
        val wanted = OpenScheduleSiteResolver.nameTokens(selected)
        return wanted.isNotEmpty() && wanted == OpenScheduleSiteResolver.nameTokens(title)
    }

    fun addressesAgree(selected: PalomnikStreetAddress, published: PalomnikStreetAddress): Boolean {
        val words = Regex("[а-яa-z0-9]+")
        if (selected.streetKind != published.streetKind || words.findAll(selected.street).map { it.value }.toList() !=
            words.findAll(published.street).map { it.value }.toList()) return false
        if (published.corpus != null && published.corpus != selected.corpus ||
            published.building != null && published.building != selected.building) return false
        if (published.house == selected.house) return true
        // A published combined street number can name the selected entrance, but only
        // with the same explicit building. Never equate arbitrary adjacent houses.
        val houses = published.house.split('/')
        return houses.size == 2 && houses.all { Regex("[0-9]{1,4}").matches(it) } &&
            selected.house in houses && published.building != null && published.building == selected.building &&
            published.corpus == selected.corpus
    }

    /** A missing detail fails positive proof, but cannot erase another possible identity. */
    fun definitelyOtherAddress(selected: PalomnikStreetAddress, published: PalomnikStreetAddress): Boolean {
        val words = Regex("[а-яa-z0-9]+")
        if (selected.streetKind != published.streetKind || words.findAll(selected.street).map { it.value }.toList() !=
            words.findAll(published.street).map { it.value }.toList()) return true
        val sameHouse = selected.house == published.house || selected.house in published.house.split('/') ||
            published.house in selected.house.split('/')
        return !sameHouse || (selected.corpus != null && published.corpus != null && selected.corpus != published.corpus) ||
            (selected.building != null && published.building != null && selected.building != published.building)
    }
}

/** App composition and its test use the same resolver graph; ordinary fake resolvers remain offline. */
internal fun productionScheduleSiteResolver(transport: ScheduleTransport, cache: OfficialDirectoryCacheStore,
    moscowSearch: MoscowDirectorySearchTransport = UrlConnectionMoscowDirectorySearchTransport()): ScheduleSiteResolver =
    OpenScheduleSiteResolver(transport, cache, moscowSearch)
