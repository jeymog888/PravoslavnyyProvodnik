package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import ru.liturgyguide.model.Parish
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.*

data class ScheduleSite(val url: String, val evidenceUrl: String, val matchedName: String,
    val binding: ScheduleSiteBinding? = null)
fun interface ScheduleSiteResolver {
    suspend fun resolve(parish: Parish): ScheduleSite?
    suspend fun resolve(parish: Parish, excludedUrls: Set<String>): ScheduleSite? =
        resolve(parish)?.takeUnless { ScheduleSourceExclusions(excludedUrls).contains(it.url) }
    /** One scope for primary/alternative lookup; test/custom resolvers keep their existing behavior. */
    fun forRefresh(): ScheduleSiteResolver = this
}

/** Exact OSM identity first, then an identified encyclopedia entity, never the first search hit. */
class OpenScheduleSiteResolver(private val transport: ScheduleTransport,
    directoryCache: OfficialDirectoryCacheStore = MemoryOfficialDirectoryCacheStore(),
    moscowSearch: MoscowDirectorySearchTransport? = null) : ScheduleSiteResolver {
    private val directories = OfficialDirectoryResolver(transport, directoryCache)
    private val moscow = moscowSearch?.let { MoscowOfficialDirectoryResolver(transport, it) }
    override suspend fun resolve(parish: Parish): ScheduleSite? = resolve(parish, emptySet())

    override suspend fun resolve(parish: Parish, excludedUrls: Set<String>): ScheduleSite? =
        resolveInSession(parish, excludedUrls, mutableMapOf(), mutableMapOf(), mutableMapOf())

    override fun forRefresh(): ScheduleSiteResolver {
        val directoryMemo = mutableMapOf<String, OfficialDirectorySnapshot?>()
        val contactMemo = mutableMapOf<String, ScheduleSite?>()
        val moscowMemo = mutableMapOf<String, ScheduleSite?>()
        return object : ScheduleSiteResolver {
            override suspend fun resolve(parish: Parish): ScheduleSite? = resolve(parish, emptySet())
            override suspend fun resolve(parish: Parish, excludedUrls: Set<String>): ScheduleSite? =
                resolveInSession(parish, excludedUrls, directoryMemo, contactMemo, moscowMemo)
        }
    }

    private suspend fun resolveInSession(parish: Parish, excludedUrls: Set<String>,
        directoryMemo: MutableMap<String, OfficialDirectorySnapshot?>,
        contactMemo: MutableMap<String, ScheduleSite?>,
        moscowMemo: MutableMap<String, ScheduleSite?>): ScheduleSite? {
        val excluded = ScheduleSourceExclusions(excludedUrls)
        ScheduleUrl.publicSource(parish.website)?.takeUnless(excluded::contains)?.let { return ScheduleSite(it, parish.sourceUrl, parish.name) }
        var tags: JsonObject? = null
        val osm = Regex("^osm:(node|way|relation):([1-9][0-9]{0,18})$").matchEntire(parish.id)
        if (osm != null) {
            val url = "https://api.openstreetmap.org/api/0.6/${osm.groupValues[1]}/${osm.groupValues[2]}.json"
            val data = json(url)
            tags = data?.getAsJsonArray("elements")?.firstOrNull { item ->
                val value = item.takeIf { it.isJsonObject }?.asJsonObject
                value?.get("type")?.asString == osm.groupValues[1] && value.get("id")?.asString == osm.groupValues[2]
            }?.asJsonObject?.getAsJsonObject("tags")
            listOf("website", "contact:website").firstNotNullOfOrNull { key ->
                tags?.string(key)?.let(ScheduleUrl::publicSource)?.takeUnless(excluded::contains)
            }?.let { site ->
                val name = tags?.string("name") ?: parish.name
                return ScheduleSite(site, url, name,
                    ScheduleEntityIdentity.bind(parish, site, url, name, ScheduleBindingMethod.EXACT_MAP_LINK))
            }
        }
        // These links belong to the exact selected map object and need no broad search inference.
        val linkedTitle = tags?.string("wikipedia")?.takeIf { it.startsWith("ru:") }?.substringAfter(':')
        val linkedTitleCompatible = linkedTitle == null || !ScheduleEntityIdentity.hasExplicitConflict(parish.name, linkedTitle)
        tags?.string("wikidata")?.takeIf { linkedTitleCompatible && Regex("Q[1-9][0-9]{0,12}").matches(it) }?.let { id ->
            fromWikidata(parish, id, parish.name, excluded, ScheduleBindingMethod.EXACT_MAP_LINK)?.let { return it }
        }
        linkedTitle?.takeIf { linkedTitleCompatible }?.let { title ->
            val api = "https://ru.wikipedia.org/w/api.php?action=query&format=json&prop=pageprops&titles=${encode(title)}"
            pages(json(api)).firstNotNullOfOrNull { it.getAsJsonObject("pageprops")?.string("wikibase_item") }
                ?.let { id -> fromWikidata(parish, id, title, excluded, ScheduleBindingMethod.EXACT_MAP_LINK)?.let { return it } }
        }
        moscow?.resolve(parish, excluded, moscowMemo)?.let { return it }
        PalomnikScheduleSiteResolver(transport).resolve(parish, excludedUrls)?.let { return it }
        directories.resolve(parish, excluded, directoryMemo, contactMemo)?.let { return it }
        val latitude = parish.latitude ?: return null
        val longitude = parish.longitude ?: return null
        val distinctive = nameTokens(parish.name)
        if (distinctive.isEmpty() || !latitude.isFinite() || !longitude.isFinite()) return null
        val query = listOf(parish.name, parish.city).filter(String::isNotBlank).joinToString(" ")
        val url = "https://ru.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=0&gsrlimit=5&prop=coordinates%7Cpageprops&gsrsearch=${encode(query)}"
        val candidates = pages(json(url)).mapNotNull { page ->
            val title = page.string("title") ?: return@mapNotNull null
            if (!namesMatch(parish.name, title)) return@mapNotNull null
            if (!ScheduleEntityIdentity.compatible(parish.name, title)) return@mapNotNull null
            val coordinate = page.getAsJsonArray("coordinates")?.firstOrNull()?.asJsonObject ?: return@mapNotNull null
            val lat = coordinate.get("lat")?.asDouble ?: return@mapNotNull null
            val lon = coordinate.get("lon")?.asDouble ?: return@mapNotNull null
            val distance = distanceMeters(latitude, longitude, lat, lon)
            if (distance > 650.0) return@mapNotNull null
            val id = page.getAsJsonObject("pageprops")?.string("wikibase_item") ?: return@mapNotNull null
            Triple(id, title, distance)
        }.sortedBy { it.third }
        // Near-identical nearby names are ambiguous; do not select one by search ranking.
        if (candidates.size > 1 && candidates[1].third - candidates[0].third < 150) return null
        // Select the building before excluding endpoints. A failed website cannot turn a
        // farther namesake into the selected church merely because its website responds.
        val candidate = candidates.firstOrNull() ?: return null
        return fromWikidata(parish, candidate.first, candidate.second, excluded, ScheduleBindingMethod.ARTICLE_SCOPE)
    }

    private suspend fun fromWikidata(parish: Parish, id: String, title: String, excluded: ScheduleSourceExclusions,
        method: ScheduleBindingMethod): ScheduleSite? {
        if (!Regex("Q[1-9][0-9]{0,12}").matches(id)) return null
        val url = "https://www.wikidata.org/wiki/Special:EntityData/$id.json"
        val claims = json(url)?.getAsJsonObject("entities")?.getAsJsonObject(id)?.getAsJsonObject("claims")
            ?.getAsJsonArray("P856") ?: return null
        val active = claims.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }
            .filter { it.string("rank") != "deprecated" && it.getAsJsonObject("qualifiers")?.has("P582") != true }
            .sortedByDescending { it.string("rank") == "preferred" }
        val sites = active.mapNotNull { claim ->
            claim.getAsJsonObject("mainsnak")?.getAsJsonObject("datavalue")?.string("value")?.let(ScheduleUrl::publicSource)
        }.distinct()
        val chosen = sites.firstOrNull { !excluded.contains(it) } ?: return null
        val evidence = "https://www.wikidata.org/wiki/$id"
        return ScheduleSite(chosen, evidence, title, ScheduleEntityIdentity.bind(parish, chosen, evidence, title, method))
    }

    private suspend fun json(url: String): JsonObject? = try {
        val response = transport.fetch(url)
        JsonParser.parseString(response.bytes.toString(Charsets.UTF_8)).takeIf { it.isJsonObject }?.asJsonObject
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun pages(data: JsonObject?): List<JsonObject> = data?.getAsJsonObject("query")?.getAsJsonObject("pages")
        ?.entrySet()?.mapNotNull { it.value.takeIf { item -> item.isJsonObject }?.asJsonObject }.orEmpty()
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
        private val common = setOf("храм", "храме", "церк", "собор", "монас", "свято", "святы", "свято", "свят", "препо", "имени", "право", "русск", "мужск", "женск", "москов", "москв")
        internal fun nameTokens(text: String): Set<String> = Regex("[а-яёa-z]{3,}").findAll(text.lowercase(Locale.ROOT))
            .map { it.value.replace('ё', 'е').take(5) }.filter { token -> token != "при" && common.none { token.startsWith(it) } }.toSet()
        internal fun namesMatch(selected: String, candidate: String): Boolean {
            val wanted = nameTokens(selected)
            val found = nameTokens(candidate)
            if (wanted.isEmpty() || found.isEmpty()) return false
            val common = wanted.intersect(found)
            return common.isNotEmpty() && common.size.toDouble() / min(wanted.size, found.size) >= .65
        }
        internal fun distanceMeters(a: Double, b: Double, c: Double, d: Double): Double {
            val lat = Math.toRadians(c - a); val lon = Math.toRadians(d - b)
            val value = sin(lat / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(lon / 2).pow(2)
            return 6_371_000 * 2 * atan2(sqrt(value.coerceIn(0.0, 1.0)), sqrt((1 - value).coerceIn(0.0, 1.0)))
        }
    }
}
