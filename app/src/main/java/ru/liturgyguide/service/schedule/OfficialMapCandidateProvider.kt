package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import ru.liturgyguide.model.Parish
import java.net.URLEncoder
import java.time.Clock
import java.util.Locale
import kotlin.math.cos

/** The published public widget API is a candidate index, never an exhaustive building registry. */
class OfficialMapCandidateProvider(
    private val transport: ScheduleTransport,
    private val clock: Clock = Clock.systemUTC(),
    private val timeoutMillis: Long = 25_000,
) : ScheduleCandidateProvider {
    init { require(timeoutMillis in 1..30_000) }
    private var widgetValue: String? = null

    override suspend fun discover(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery {
        val lat = parish.latitude; val lon = parish.longitude
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
            lat !in -85.0..85.0 || lon !in -180.0..180.0 || OpenScheduleSiteResolver.nameTokens(parish.name).isEmpty())
            return ScheduleCandidateDiscovery()
        val candidates = mutableListOf<ScheduleSourceCandidate>()
        val observed = mutableListOf<String>()
        val incomplete = true // This API has no completeness certificate even for one returned feature.
        var unavailable = false
        try {
            val completed = withTimeoutOrNull(timeoutMillis) {
                val widget = widgetValue ?: widget().also { widgetValue = it }
                val dLat = 650.0 / 111_000.0
                val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(.1)
                val bounds = "[[${lat - dLat},${lon - dLon}],[${lat + dLat},${lon + dLon}]]"
                val collection = get(API + "?" + query("token" to widget, "country" to "", "church" to "", "ctype" to "",
                    "t" to "0", "s" to "", "params" to "[]", "zf" to "8", "z" to "16", "geo" to bounds), 196_608)
                val root = JsonParser.parseString(collection.bytes.toString(Charsets.UTF_8)).asJsonObject
                require(root.get("type")?.asString == "FeatureCollection")
                val features = root.getAsJsonArray("features")
                require(features.size() <= 200)
                data class Feature(val id: String, val name: String, val latitude: Double, val longitude: Double, val distance: Double)
                val possible = mutableListOf<Feature>()
                for ((index, item) in features.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val value = item.takeIf { it.isJsonObject }?.asJsonObject
                    val id = value?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                    // Keep identities before title, scope, geometry and endpoint filters.
                    observed += id?.takeIf { it.length <= 80 } ?: "unreadable:$index"
                    if (value == null || id == null || !Regex("[1-9][0-9]{0,11}").matches(id)) continue
                    val geometry = value.getAsJsonObject("geometry") ?: continue
                    if (geometry.get("type")?.asString != "Point") continue
                    val coordinates = geometry.getAsJsonArray("coordinates") ?: continue
                    if (coordinates.size() != 2) continue
                    // Despite FeatureCollection naming, this publisher's frontend uses latitude,longitude.
                    val pointLat = runCatching { coordinates[0].asDouble }.getOrNull() ?: continue
                    val pointLon = runCatching { coordinates[1].asDouble }.getOrNull() ?: continue
                    if (!pointLat.isFinite() || !pointLon.isFinite() || pointLat !in -90.0..90.0 || pointLon !in -180.0..180.0) continue
                    val name = value.getAsJsonObject("properties")?.text("hintContent") ?: continue
                    val distance = OpenScheduleSiteResolver.distanceMeters(lat, lon, pointLat, pointLon)
                    if (distance <= 650) possible += Feature(id, name, pointLat, pointLon, distance)
                }
                val excluded = ScheduleSourceExclusions(excludedUrls)
                for (feature in possible.sortedWith(compareBy({ it.distance }, { it.id })).take(20)) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val detail = get(API + "?" + query("token" to widget, "m" to "info", "msg" to "1", "id" to feature.id,
                            "urlmode" to "0", "lang" to "ru"), 131_072)
                        val page = requireNotNull(detail.html())
                        val content = page.select(".marker-info-content").single()
                        val heading = content.select("h1").single()
                        val publishedName = heading.text().trim()
                        require(publishedName.length in 1..300 &&
                            ScheduleEntityIdentity.normalize(publishedName) == ScheduleEntityIdentity.normalize(feature.name))
                        val publishedScope = ScheduleEntityIdentity.scope(publishedName)
                        if (publishedScope in setOf(ScheduleEntityScope.UNKNOWN, ScheduleEntityScope.COMPLEX)) continue
                        fun field(label: String): String = content.select("li").filter { row ->
                            row.children().firstOrNull { it.hasClass("ch") || it.hasClass("ttl") }?.text()?.trim()?.trimEnd(':') == label
                        }.singleOrNull()?.children()?.firstOrNull { it.hasClass("cnt") }?.text()?.trim().orEmpty()
                        val mainDedication = mainDedication(content)
                        val wantedDedication = dedicationWords(parish.name)
                        val publishedDedication = mainDedication?.let(::dedicationWords)
                        // Grammatical or liturgical wording differences are not negative evidence.
                        // Only independently explicit, mutually exclusive variants reject a short title.
                        if (definiteDedicationConflict(wantedDedication, publishedDedication)) continue
                        val titleMatches = OpenScheduleSiteResolver.namesMatch(parish.name, publishedName)
                        val dedicationMatches = wantedDedication != null && wantedDedication == publishedDedication &&
                            ScheduleEntityIdentity.scope(parish.name) == ScheduleEntityScope.CHURCH &&
                            publishedScope == ScheduleEntityScope.CHURCH
                        if (!titleMatches && !dedicationMatches) continue
                        // Keep the publisher's literal heading and dedication, never manufacture an alias.
                        val name = if (!titleMatches) "$publishedName [$mainDedication]" else publishedName
                        // Some literal abbreviations still cannot pass the existing candidate contract.
                        // Missing lexical evidence is not a conflict or permission to change that contract.
                        if (!OpenScheduleSiteResolver.namesMatch(parish.name, name)) continue
                        val address = field("Адрес").take(500)
                        val mail = field("E-mail").lowercase().take(254)
                        val siteRows = content.select("li").filter { row -> row.children().firstOrNull {
                            it.hasClass("ch") || it.hasClass("ttl") }?.text()?.trim()?.trimEnd(':') == "Сайт" }
                        val direct = siteRows.flatMap { row -> row.select(".cnt a[href]").mapNotNull {
                            ScheduleUrl.publicSource(it.absUrl("href")) } }.distinct()
                        val passport = heading.select("a[href]").mapNotNull { ScheduleUrl.publicSource(it.absUrl("href")) }.distinct()
                        val sources = direct.ifEmpty { passport }
                        require(sources.size in 1..3)
                        for (source in sources) {
                            if (excluded.contains(source)) continue
                            val wanted = PalomnikStreetAddress.parse(parish.address)
                            val published = PalomnikStreetAddress.parse(address)
                            val scopeConflict = ScheduleEntityIdentity.hasExplicitConflict(parish.name, name)
                            val cityConflict = parish.city.isNotBlank() && ChildChurchRelation.city(address)?.let {
                                it != ScheduleEntityIdentity.normalize(parish.city)
                            } == true
                            val addressConflict = wanted != null && published != null && MoscowChurchIdentity.definitelyOtherAddress(wanted, published)
                            val contactCheck = if (addressConflict && source in direct && !scopeConflict && !cityConflict)
                                currentContact(parish, source, mail) else ContactCheck(null)
                            val contact = contactCheck.contact
                            unavailable = unavailable || contactCheck.unavailable
                            val conflict = scopeConflict || cityConflict || addressConflict && contact == null
                            val candidate = ScheduleSourceCandidate(selectedIdentity = ScheduleEntityIdentity.fingerprint(parish),
                                provider = PROVIDER, providerLabel = "Официальная карта храмов", providerObjectId = feature.id,
                                name = name, address = address, sourceUrl = source, evidenceUrl = "$HOME?infoid=${feature.id}",
                                evidenceSha256 = ScheduleSourceCandidate.sha(detail.bytes), checkedAt = clock.instant().toString(),
                                latitude = feature.latitude, longitude = feature.longitude,
                                outcome = if (conflict) ScheduleIdentityOutcome.CONFLICT else ScheduleIdentityOutcome.INSUFFICIENT,
                                reason = when {
                                    scopeConflict -> "Карточка относится к другому виду места богослужения"
                                    cityConflict -> "Населенный пункт источника не совпадает с выбранным храмом"
                                    addressConflict && contact == null -> "Адрес источника не совпадает с выбранным храмом"
                                    contact != null -> "В старой карточке другой адрес; текущие контакты сайта подтверждают адрес выбранного храма"
                                    !titleMatches -> "В карточке указан главный престол \"$mainDedication\"; проверьте сайт выбранного храма"
                                    else -> "Проверьте, что на этом сайте опубликовано расписание выбранного храма"
                                }, currentContactAddress = contact?.address.orEmpty(), currentContactUrl = contact?.url.orEmpty(),
                                currentContactSha256 = contact?.sha.orEmpty(), linkedEmail = if (contact != null) mail else "",
                                verificationUnavailable = contactCheck.unavailable)
                            if (candidate.validFor(parish)) candidates += candidate
                            if (candidates.size >= 20) break
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { unavailable = true }
                    if (candidates.size >= 20) break
                }
                true
            }
            if (completed != true) unavailable = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable = true }
        return ScheduleCandidateDiscovery(candidates.distinctBy { it.id }.take(20), observed.distinct().take(200), incomplete, unavailable)
    }

    /** Only the explicit main altar in the structured field supplies dedication evidence. */
    private fun mainDedication(content: org.jsoup.nodes.Element): String? {
        val row = content.select("li").filter { item -> item.children().firstOrNull {
            it.hasClass("ch") || it.hasClass("ttl")
        }?.text()?.trim()?.trimEnd(':') == "Престольные праздники" }.singleOrNull() ?: return null
        val altars = row.children().filter { it.tagName() == "ul" && it.hasClass("ul-altars") }.singleOrNull() ?: return null
        val main = altars.children().filter { it.tagName() == "li" && it.hasClass("altar") && it.hasClass("is_main") }
            .singleOrNull()?.text()?.trim() ?: return null
        return Regex("^(.{3,180}?)\\s+-\\s+(?:Январь|Февраль|Март|Апрель|Май|Июнь|Июль|Август|Сентябрь|Октябрь|Ноябрь|Декабрь)\\s+[0-9]{1,2}\\b",
            RegexOption.IGNORE_CASE).find(main)?.groupValues?.get(1)?.trim()
    }

    /** Exact full words after a narrow set of titles, not the global five-letter name overlap. */
    private fun dedicationWords(value: String): Set<String>? {
        var text = value.lowercase(Locale.ROOT).replace('ё', 'е')
            .replace(Regex("\\s+(?:г\\.|города\\b|с\\.|села\\b|пос\\.|поселка\\b|п\\.|пгт\\b|дер\\.|деревни\\b).*"), "")
            .replace(Regex("^(?:храм|церковь|собор)\\s+"), "")
            .replace(Regex("^(?:во\\s+имя|в\\s+честь)\\s+"), "")
        // These are linguistic titles, not permission to identify different named saints.
        text = text.replace(Regex("\\bархистратига\\s+божия\\b"), "архангела")
        if (Regex("(?<![а-я])(?:в|на|при|от)(?![а-я])").containsMatchIn(text)) return null
        val titles = setOf("св", "свт", "прп", "вмц", "митр", "митрополита", "святителя", "святого", "святой",
            "преподобного", "благоверного", "священномученика", "великомученика", "мученика")
        val words = Regex("[а-яa-z]+").findAll(text).map { it.value }.filter { it.length > 1 && it !in titles }.toSet()
        return words.takeIf { it.size >= 2 }
    }

    private fun definiteDedicationConflict(wanted: Set<String>?, published: Set<String>?): Boolean {
        if (wanted == null || published == null) return false
        val variants = listOf(
            listOf(setOf("архангела", "михаила"), setOf("архангела", "гавриила")),
            listOf(setOf("алексия", "московского"), setOf("алексия", "человека", "божия")),
            listOf(setOf("серафима", "саровского"), setOf("серафима", "выритского")),
            listOf(setOf("воскресения", "христова"), setOf("воскресения", "словущего")))
        return variants.any { group ->
            val selectedVariant = group.indices.filter { wanted.containsAll(group[it]) }.singleOrNull()
            val publishedVariant = group.indices.filter { published.containsAll(group[it]) }.singleOrNull()
            selectedVariant != null && publishedVariant != null && selectedVariant != publishedVariant
        }
    }

    private suspend fun widget(): String {
        val home = get(HOME, 262_144)
        val page = requireNotNull(home.html())
        val scripts = page.select("script[src]").mapNotNull { ScheduleUrl.publicSource(it.absUrl("src")) }
            .filter { ScheduleUrl.sameOrigin(HOME, it) && java.net.URI(it).path.endsWith("/ymap-filters-box.js") }.distinct()
        val script = get(scripts.single(), 131_072).bytes.toString(Charsets.UTF_8)
        // Read a constant from the publicly advertised frontend. Do not execute JavaScript or use an account token.
        return Regex("token=([a-zA-Z0-9_-]{16,128})&(?:country|m)=").findAll(script)
            .map { it.groupValues[1] }.distinct().toList().single()
    }

    private data class Contact(val address: String, val url: String, val sha: String)
    private data class ContactCheck(val contact: Contact?, val unavailable: Boolean = false)
    private class ContactConflict : Exception()
    private suspend fun currentContact(parish: Parish, source: String, mail: String): ContactCheck = try {
        require(mail.matches(Regex("[^\\s@]{1,64}@[^\\s@]{1,190}\\.[a-zA-Z]{2,20}")))
        val home = get(source, 524_288)
        val page = complete(home)
        require(OpenScheduleSiteResolver.namesMatch(parish.name, page.title() + " " + page.select("h1").text()))
        val links = page.select("a[href]").filter {
            ScheduleEntityIdentity.normalize(it.text()) in setOf("контакты", "контактная информация")
        }.mapNotNull { ScheduleUrl.publicSource(it.absUrl("href")) }
            .filter { ScheduleDocumentLinks.sameSite(home.url, it) }.distinct()
        val response = get(links.single(), 524_288)
        val contacts = complete(response)
        contacts.select("script,style,nav,footer,aside,header").remove()
        val text = contacts.body().text()
        val emails = Regex("[^\\s<>@;,]+@[^\\s<>@;,]+\\.[a-zA-Z]{2,20}").findAll(text).map { it.value.lowercase() }.toSet()
        require(mail in emails)
        val address = Regex("(?iu)адрес(?:\\s+храма)?\\s*:\\s*(.{3,400}?)(?=\\s+(?:телефон|почта|e-mail|вконтакте|время\\s+работы|как\\s+добраться)\\s*[: ]|$)")
            .findAll(text).map { it.groupValues[1].trim() }.toList().single()
        val wanted = requireNotNull(PalomnikStreetAddress.parse(parish.address))
        val current = requireNotNull(PalomnikStreetAddress.parse(address))
        if (!wanted.sameBuilding(current) || parish.city.isNotBlank() && ChildChurchRelation.city(address)?.let {
                it != ScheduleEntityIdentity.normalize(parish.city)
            } == true) throw ContactConflict()
        ContactCheck(Contact(address, response.url, ScheduleSourceCandidate.sha(response.bytes)))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: ContactConflict) { ContactCheck(null) }
    catch (_: Exception) { ContactCheck(null, unavailable = true) }

    private fun complete(response: ScheduleResponse): Document {
        require(Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(response.bytes.toString(Charsets.ISO_8859_1)))
        return requireNotNull(response.html())
    }

    private suspend fun get(url: String, limit: Int): ScheduleResponse {
        require(ScheduleUrl.publicSource(url) == url)
        val response = transport.fetchBounded(url, limit)
        require(response.bytes.size in 1..limit && ScheduleUrl.publicSource(response.url) == response.url &&
            ScheduleUrl.permittedRedirect(url, response.url))
        return response
    }

    companion object {
        const val PROVIDER = "official_map"
        internal const val HOME = "https://map.patriarchia.ru/"
        internal const val API = "https://map.patriarchia.ru/api/index.php"
        private fun query(vararg parts: Pair<String, String>) = parts.joinToString("&") {
            URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8")
        }
        private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }
}
