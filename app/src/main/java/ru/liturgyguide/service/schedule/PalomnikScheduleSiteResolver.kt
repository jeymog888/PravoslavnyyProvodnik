package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import ru.liturgyguide.model.Parish
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Public catalog discovery only: no domain guessing, HTML navigation scraping or redirect relaxation. */
internal class PalomnikScheduleSiteResolver(private val transport: ScheduleTransport) : ScheduleSiteResolver {
    override suspend fun resolve(parish: Parish): ScheduleSite? = try {
        resolveSafely(parish)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private suspend fun resolveSafely(parish: Parish): ScheduleSite? {
        val city = parish.city.trim().takeIf { it.length in 2..100 } ?: return null
        val latitude = parish.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val longitude = parish.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        if (OpenScheduleSiteResolver.nameTokens(parish.name).isEmpty()) return null
        val candidates = linkedMapOf<String, String>()
        var continuation: String? = null
        var complete = false
        for (index in 0 until 2) {
            val next = continuation?.let { "&cmcontinue=${encode(it)}" }.orEmpty()
            val data = json("$API?action=query&format=json&list=categorymembers&cmnamespace=0&cmlimit=500&cmtitle=${encode("Категория:$city")}$next") ?: return null
            val members = data.getAsJsonObject("query")?.getAsJsonArray("categorymembers") ?: return null
            if (members.size() > 500) return null
            for (member in members) {
                val value = member.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val id = value.string("pageid")?.takeIf { PAGE_ID.matches(it) } ?: continue
                val title = value.string("title") ?: continue
                if (OpenScheduleSiteResolver.namesMatch(parish.name, title)) candidates[id] = title
            }
            if (candidates.size > 3) return null // Too many plausible identities, never select by category order
            val rawContinuation = data.getAsJsonObject("continue")?.string("cmcontinue")
            if (rawContinuation == null) { complete = true; break }
            if (rawContinuation.length !in 1..512 || rawContinuation == continuation) return null
            continuation = rawContinuation
        }
        // An incomplete category could hide a second nearby namesake; don't treat a network budget as proof
        if (!complete || candidates.isEmpty()) return null
        val data = json("$API?action=query&format=json&prop=revisions&rvprop=ids%7Ccontent&rvslots=main&pageids=${candidates.keys.joinToString("%7C")}") ?: return null
        val pages = data.getAsJsonObject("query")?.getAsJsonObject("pages") ?: return null
        if (!pages.entrySet().map { it.key }.containsAll(candidates.keys)) return null
        val matched = mutableListOf<ScheduleSite?>()
        for ((id, value) in pages.entrySet()) {
            if (id !in candidates || !value.isJsonObject) continue
            val page = value.asJsonObject
            if (page.string("pageid") != id || page.string("title") != candidates[id]) return null
            val revision = page.getAsJsonArray("revisions")?.singleOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val content = revision.getAsJsonObject("slots")?.getAsJsonObject("main")?.string("*") ?: return null
            val source = sourceFor(parish, candidates.getValue(id), content, latitude, longitude) ?: continue
            matched += source.url?.let { ScheduleSite(it, "https://azbyka.ru/palomnik/index.php?curid=$id", candidates.getValue(id)) }
        }
        return matched.singleOrNull()
    }

    private suspend fun json(url: String): JsonObject? = try {
        val response = transport.fetch(url)
        if (response.bytes.size > 768 * 1024 || !ScheduleUrl.sameOrigin(API, response.url)) null
        else JsonParser.parseString(response.bytes.toString(Charsets.UTF_8)).takeIf { it.isJsonObject }?.asJsonObject
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private data class MatchingIdentity(val url: String?)

    private fun sourceFor(parish: Parish, title: String, text: String, latitude: Double, longitude: Double): MatchingIdentity? {
        if (text.length > 160_000 || !OpenScheduleSiteResolver.namesMatch(parish.name, title)) return null
        val locations = LOCATION.findAll(text).toList()
        if (locations.size != 1) return null
        val lat = locations.single().groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val lon = locations.single().groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val distance = OpenScheduleSiteResolver.distanceMeters(latitude, longitude, lat, lon)
        if (distance > 650) return null
        val lines = text.lineSequence().toList()
        val addresses = lines.map { it.replace("'", "").trim() }.filter { it.startsWith("Адрес:") }.map { it.substringAfter(':').trim() }
        val address = addresses.singleOrNull() ?: return null
        if (!Regex("(?<![а-яa-z])${Regex.escape(normalize(parish.city.trim()))}(?![а-яa-z])").containsMatchIn(normalize(address))) return null
        val selectedStreet = streetWords(parish.address)
        if (selectedStreet.isNotEmpty() && !streetWords(address).containsAll(selectedStreet)) return null
        val selectedHouse = house(parish.address)
        if (selectedHouse != null && selectedHouse != house(address)) return null
        // A street alone cannot disambiguate distant buildings on it
        if (selectedHouse == null && distance > 150) return null
        var section: String? = null
        val schedules = linkedSetOf<String>()
        val sites = linkedSetOf<String>()
        for (line in lines) {
            HEADING.matchEntire(line.trim())?.let { section = it.groupValues[1].trim() }
            if (section == "Богослужения") {
                for (link in LINK.findAll(line)) {
                    val label = normalize(link.groupValues[2]).replace("'", "")
                    if ("расписани" in label && SITE_LABEL.containsMatchIn(label) && !STALE_LABEL.containsMatchIn(label)) {
                        https(link.groupValues[1])?.let(schedules::add)
                    }
                }
            }
            val clean = line.replace("'", "").trim()
            if (clean.startsWith("Сайт:") && !STALE_LABEL.containsMatchIn(normalize(clean))) {
                URL.findAll(clean).mapNotNull { https(it.value) }.forEach(sites::add)
            }
        }
        // An explicit schedule on this exact church's page takes precedence over a stale homepage field
        return MatchingIdentity((schedules.takeIf { it.isNotEmpty() } ?: sites).singleOrNull())
    }

    private fun https(value: String): String? {
        if (!value.startsWith("https://", ignoreCase = true)) return null
        val normalized = ScheduleUrl.normalized(value) ?: return null
        val host = runCatching { URI(normalized).host.lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if (host == "azbyka.ru" || host.endsWith(".azbyka.ru")) return null
        return normalized
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
        private const val API = "https://azbyka.ru/palomnik/api.php"
        private val PAGE_ID = Regex("[1-9][0-9]{0,12}")
        private val LOCATION = Regex("\\{\\{Местоположение святыни\\|[^{}]*?локация\\s*=\\s*([0-9.-]+)\\s*,\\s*([0-9.-]+)[^{}]*\\}\\}")
        private val HEADING = Regex("={2,6}\\s*(.*?)\\s*={2,6}")
        private val LINK = Regex("\\[(https?://[^\\s\\]]+)\\s+([^\\]]+)\\]")
        private val URL = Regex("https?://[^\\s<>\\[\\]]+")
        private val SITE_LABEL = Regex("сайт[ае]?\\s+(?:храма|монастыря|прихода)")
        private val STALE_LABEL = Regex("архив|устар|прежн|старый|не\\s+работает|неактуал")
        private val STREET_TYPES = setOf("улица", "дом", "корпус", "проспект", "переулок", "проезд", "бульвар", "шоссе", "набережная")
        private val HOUSE = Regex("\\b([0-9]{1,4}(?:\\s*-?\\s*[а-яa-z])?(?:/[0-9]{1,4})?)\\s*$")
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun normalize(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е')
        private fun streetWords(value: String): Set<String> = Regex("[а-яёa-z]{3,}").findAll(normalize(value)).map { it.value }.filter { it !in STREET_TYPES }.toSet()
        private fun house(value: String): String? = HOUSE.find(normalize(value))?.groupValues?.get(1)?.replace(Regex("[\\s-]"), "")
    }
}
