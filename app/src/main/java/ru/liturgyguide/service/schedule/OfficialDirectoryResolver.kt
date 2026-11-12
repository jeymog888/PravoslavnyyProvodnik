package ru.liturgyguide.service.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import ru.liturgyguide.model.Parish
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** A verified publisher/territory configuration, never a list of individual church websites. */
internal data class OfficialDirectoryProfile(
    val id: String, val url: String, val cities: Set<String>, val region: String,
    val minimumEntries: Int,
) {
    fun serves(city: String) = normalizedLocality(city) in cities.map(::normalizedLocality)

    companion object {
        val PUBLISHED = listOf(OfficialDirectoryProfile("voronezh-ortox", "https://vob.cerkov.ru/xramy/",
            setOf("Воронеж"), "Воронежская область", minimumEntries = 200))
        // The inspected complete collection contains 233 cards. A much smaller partial
        // response must not establish uniqueness. New publisher formats need their own review.
        fun normalizedLocality(value: String): String = normalize(value)
            .replace(Regex("^(?:город|г)\\s+"), "")
        fun normalize(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е')
            .replace(Regex("[^а-яa-z0-9 -]+"), " ").replace(Regex("\\s+"), " ").trim()
    }
}

/** Incomplete records stay in the collection and can make a same-name match ambiguous. */
internal data class OfficialDirectoryEntry(
    val id: String, val title: String, val address: String?, val website: String?, val coordinates: String? = null,
)

internal data class OfficialDirectorySnapshot(
    val profileId: String, val directoryUrl: String, val fetchedAt: String, val sourceSha256: String,
    val entries: List<OfficialDirectoryEntry>,
)

internal class OfficialDirectoryResolver(
    private val transport: ScheduleTransport,
    private val cache: OfficialDirectoryCacheStore,
    private val clock: Clock = Clock.systemUTC(),
    private val profiles: List<OfficialDirectoryProfile> = OfficialDirectoryProfile.PUBLISHED,
) {
    private val mutex = Mutex()
    private val memory = mutableMapOf<String, OfficialDirectorySnapshot>()

    /** Memo belongs to one refresh only, including failed fetches; a manual retry gets a new one. */
    suspend fun resolve(parish: Parish, excluded: ScheduleSourceExclusions,
        memo: MutableMap<String, OfficialDirectorySnapshot?>,
        contactMemo: MutableMap<String, ScheduleSite?> = mutableMapOf()): ScheduleSite? {
        val profile = profiles.singleOrNull { it.serves(parish.city) } ?: return null
        val selected = DirectoryAddress.parse(parish.address, profile)
        val wantedCity = OfficialDirectoryProfile.normalizedLocality(parish.city)
        if (!selected.confirmedCity(wantedCity) || selected.building == null) return null
        val snapshot = if (memo.containsKey(profile.id)) memo[profile.id] else
            load(profile).also { memo[profile.id] = it }
        val entries = snapshot?.entries ?: return null
        val provenLocalities = DirectoryAddress.provenLocalities(entries, profile)
        val possible = entries.filter { entry ->
            if (entry.title.isNotBlank() && !OpenScheduleSiteResolver.namesMatch(parish.name, entry.title)) return@filter false
            if (ScheduleEntityIdentity.hasExplicitConflict(parish.name, entry.title)) return@filter false
            val address = DirectoryAddress.parse(entry.address, profile, provenLocalities)
            // A complete other address is a different building. Missing/unparseable fields
            // are not evidence of a different building and cannot silently remove a namesake.
            if (address.locality != null && address.locality != wantedCity) return@filter false
            if (address.confirmedCity(wantedCity) && address.building != null &&
                !selected.building.sameBuilding(address.building)) return@filter false
            true
        }
        possible.singleOrNull()?.let { entry ->
            val address = DirectoryAddress.parse(entry.address, profile, provenLocalities)
            if (address.confirmedCity(wantedCity) && address.building?.sameBuilding(selected.building) == true &&
                OpenScheduleSiteResolver.namesMatch(parish.name, entry.title) &&
                ScheduleEntityIdentity.compatible(parish.name, entry.title) && coordinatesAgree(entry.coordinates, parish)) {
                val website = entry.website?.let(ScheduleUrl::publicSource) ?: return null
                // An excluded strict match does not authorize another identity or extra contact reads.
                if (excluded.contains(website)) return null
                return ScheduleSite(website, profile.url, entry.title,
                    ScheduleEntityIdentity.bind(parish, website, profile.url, entry.title, ScheduleBindingMethod.ARTICLE_SCOPE))
            }
        }
        val entry = ownContactCandidate(parish, profile, snapshot) ?: return null
        val website = entry.website?.let(ScheduleUrl::publicSource) ?: return null
        if (excluded.contains(website)) return null
        val key = listOf(ScheduleEntityIdentity.fingerprint(parish), profile.id, snapshot.sourceSha256,
            entry.id, ScheduleUrl.normalized(website)).joinToString("\u0000")
        if (contactMemo.containsKey(key)) return contactMemo[key]?.takeUnless { excluded.contains(it.url) }
        // The directory load has released its mutex before either own-page request starts.
        val result = OfficialSiteContactVerifier(transport, clock).verify(parish, profile, snapshot, entry)
        currentCoroutineContext().ensureActive()
        contactMemo[key] = result
        return result?.takeUnless { excluded.contains(it.url) }
    }

    private suspend fun load(profile: OfficialDirectoryProfile): OfficialDirectorySnapshot? = withTimeoutOrNull(30_000) {
        mutex.withLock {
            memory[profile.id]?.takeIf { fresh(it, profile) }?.let { return@withLock it }
            val persisted = runCatching { cache.read(profile.id) }.getOrNull()
                ?.let { OfficialDirectoryCodec.decode(it, profile) }?.takeIf { fresh(it, profile) }
            if (persisted != null) {
                memory[profile.id] = persisted
                return@withLock persisted
            }
            val loaded = try {
                val response = transport.fetchBounded(profile.url, OrtOxDirectoryParser.MAX_BYTES)
                OrtOxDirectoryParser.parse(response, profile, clock.instant())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (loaded != null) {
                memory[profile.id] = loaded
                runCatching { cache.write(profile.id, OfficialDirectoryCodec.encode(loaded)) }
            }
            loaded
        }
    }

    private fun fresh(snapshot: OfficialDirectorySnapshot, profile: OfficialDirectoryProfile): Boolean =
        snapshot.profileId == profile.id && snapshot.directoryUrl == profile.url && runCatching {
            val age = Duration.between(Instant.parse(snapshot.fetchedAt), clock.instant())
            !age.isNegative && age <= Duration.ofHours(24)
        }.getOrDefault(false)

    private fun coordinatesAgree(raw: String?, parish: Parish): Boolean {
        if (raw == null) return true
        val pair = Regex("^\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*[,;]\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*$")
            .matchEntire(raw) ?: return false
        val latitude = pair.groupValues[1].toDoubleOrNull() ?: return false
        val longitude = pair.groupValues[2].toDoubleOrNull() ?: return false
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        val selectedLatitude = parish.latitude ?: return false
        val selectedLongitude = parish.longitude ?: return false
        if (!selectedLatitude.isFinite() || !selectedLongitude.isFinite()) return false
        return OpenScheduleSiteResolver.distanceMeters(latitude, longitude, selectedLatitude, selectedLongitude) <= 150
    }
}

internal object OrtOxDirectoryParser {
    const val MAX_BYTES = 1_000_000
    const val MAX_ENTRIES = 2_000

    fun parse(response: ScheduleResponse, profile: OfficialDirectoryProfile, now: Instant): OfficialDirectorySnapshot? {
        if (response.bytes.size !in 1..MAX_BYTES || ScheduleUrl.publicSource(response.url) != profile.url) return null
        // Jsoup repairs a truncated tree. Require the actual complete document ending too.
        if (!Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(response.bytes.toString(Charsets.ISO_8859_1))) return null
        val document = response.html() ?: return null
        val container = document.select("#parent_site_list").singleOrNull() ?: return null
        val scope = container.closest("article") ?: container
        if (document.select("link[rel=next],link[rel=prev]").isNotEmpty() ||
            scope.select(".pagination,.page-numbers,a[rel=next],a[rel=prev],[data-page],[data-next-page]").isNotEmpty()) return null
        if (scope.select("a[href]").any { link ->
                val url = ScheduleUrl.publicSource(link.absUrl("href")) ?: return@any false
                url.substringBefore('?') == profile.url && url.contains('?')
            }) return null
        val list = container.children().singleOrNull { it.tagName() == "ul" } ?: return null
        val cards = list.children()
        if (cards.size !in profile.minimumEntries..MAX_ENTRIES || cards.any { it.tagName() != "li" } ||
            container.select(".site-info").size != cards.size) return null
        val entries = cards.map { card ->
            val info = card.children().singleOrNull { it.hasClass("site-info") } ?: return null
            val heading = info.select("h1").singleOrNull() ?: return null
            val id = heading.id()
            val title = heading.text().trim()
            // The real full collection also contains an unnamed card. Preserve it instead
            // of discarding it or treating the rest of an incomplete collection as unique.
            if (!Regex("t[0-9]{1,12}").matches(id) || title.length > 300 || title.any(Char::isISOControl)) return null
            val address = field(info, "Адрес")?.select(".cnt")?.singleOrNull()?.text()?.trim()?.takeIf(String::isNotBlank)
            if (address != null && (address.length > 500 || address.any(Char::isISOControl))) return null
            val links = field(info, "Сайт")?.select(".cnt a[href]").orEmpty()
            val website = links.singleOrNull()?.absUrl("href")?.let(ScheduleUrl::publicSource)
            val coordinateFields = info.select("dl").filter { label(it) in setOf("координаты", "gps") }
            val coordinates = when (coordinateFields.size) {
                0 -> null
                1 -> coordinateFields.single().select(".cnt").singleOrNull()?.text()?.trim() ?: ""
                else -> ""
            }
            if ((coordinates?.length ?: 0) > 100) return null
            OfficialDirectoryEntry(id, title, address, website, coordinates)
        }
        if (entries.map { it.id }.distinct().size != entries.size) return null
        return OfficialDirectorySnapshot(profile.id, profile.url, now.toString(),
            MessageDigest.getInstance("SHA-256").digest(response.bytes).joinToString("") { "%02x".format(it) }, entries)
    }

    private fun field(info: Element, name: String) = info.select("dl").filter {
        label(it) == name.lowercase(Locale.ROOT)
    }.singleOrNull()
    private fun label(row: Element) = row.children().firstOrNull { it.hasClass("ch") || it.hasClass("ttl") }
        ?.text()?.trim()?.removeSuffix(":")?.lowercase(Locale.ROOT)
}

/** Only an explicit city/address establishes an identity. A district or a nearby city does not. */
internal data class DirectoryAddress(val locality: String?, val completeContext: Boolean,
    val building: PalomnikStreetAddress?) {
    fun confirmedCity(city: String) = completeContext && locality == city

    companion object {
        private val street = Regex("(?iu)(?<![а-яa-z])(?:улица|ул\\.?|проспект|пр-кт|просп\\.?|переулок|пер\\.?|проезд|бульвар|бул\\.?|шоссе|набережная|наб\\.?|площадь|пл\\.?)(?![а-яa-z])")
        private val city = Regex("(?iu)^(?:г\\.?|город)\\s+(.+)$")
        private val otherSettlement = Regex("(?iu)^(?:с\\.?|село|п\\.?|пос\\.?|поселок|дер\\.?|деревня|д\\.?)\\s+(.+)$")
        // Bare names are evidence only when this complete collection independently contains
        // the same name with an explicit settlement type in the publisher's region. A city
        // and village sharing a name deliberately leave the bare form unresolved.
        fun provenLocalities(entries: List<OfficialDirectoryEntry>, profile: OfficialDirectoryProfile): Map<String, String> =
            entries.mapNotNull { entry ->
                val raw = entry.address ?: return@mapNotNull null
                val chunks = chunks(raw).takeWhile { !street.containsMatchIn(it) }
                if (!hasRegionalContext(chunks, profile) || !street.containsMatchIn(raw)) return@mapNotNull null
                val parsed = parse(raw, profile)
                if (!parsed.completeContext) return@mapNotNull null
                val typed = chunks.mapNotNull { chunk ->
                    city.matchEntire(chunk)?.let { OfficialDirectoryProfile.normalize(it.groupValues[1]) to
                        OfficialDirectoryProfile.normalizedLocality(it.groupValues[1]) }
                        ?: otherSettlement.matchEntire(chunk)?.let {
                            val name = OfficialDirectoryProfile.normalize(it.groupValues[1])
                            name to "settlement:$name"
                        }
                }.singleOrNull() ?: return@mapNotNull null
                typed
            }.groupBy({ it.first }, { it.second }).mapNotNull { (name, types) ->
                types.distinct().singleOrNull()?.let { name to it }
            }.toMap()

        fun parse(raw: String?, profile: OfficialDirectoryProfile,
            provenLocalities: Map<String, String> = emptyMap()): DirectoryAddress {
            if (raw.isNullOrBlank() || raw.length > 500) return DirectoryAddress(null, false, null)
            val value = raw.replace('\u00a0', ' ').trim().trimEnd('.')
            val allChunks = chunks(value)
            val chunks = allChunks.takeWhile { !street.containsMatchIn(it) }
            val bareLocalityContext = allChunks.any { street.containsMatchIn(it) } &&
                hasRegionalContext(chunks, profile)
            val places = mutableListOf<String>()
            var complete = true
            var inferredBare = false
            for (chunk in chunks) {
                val normalized = OfficialDirectoryProfile.normalize(chunk)
                when {
                    normalized in setOf("россия", "российская федерация", "рф", OfficialDirectoryProfile.normalize(profile.region)) -> Unit
                    Regex("[0-9]{6}").matches(normalized) -> Unit
                    city.matches(chunk) -> places += OfficialDirectoryProfile.normalizedLocality(city.matchEntire(chunk)!!.groupValues[1])
                    otherSettlement.matches(chunk) -> {
                        // Keep the settlement type: a village sharing the city's name is not the city.
                        places += "settlement:" + OfficialDirectoryProfile.normalize(otherSettlement.matchEntire(chunk)!!.groupValues[1])
                    }
                    profile.serves(chunk) -> places += OfficialDirectoryProfile.normalizedLocality(chunk)
                    bareLocalityContext && normalized in provenLocalities -> {
                        places += provenLocalities.getValue(normalized)
                        inferredBare = true
                    }
                    else -> complete = false
                }
            }
            val locality = places.singleOrNull()?.takeIf { complete || !inferredBare }
            return DirectoryAddress(locality, complete && locality != null,
                PalomnikStreetAddress.parse(value))
        }

        private fun chunks(value: String) = value.replace('\u00a0', ' ').trim().trimEnd('.')
            .split(',').map(String::trim).filter(String::isNotBlank)

        private fun hasRegionalContext(chunks: List<String>, profile: OfficialDirectoryProfile): Boolean {
            val normalized = chunks.map(OfficialDirectoryProfile::normalize)
            return OfficialDirectoryProfile.normalize(profile.region) in normalized &&
                normalized.any { it in setOf("россия", "российская федерация", "рф") }
        }
    }
}

/** Count all complete same-building cards before names, coordinates, websites or exclusions. */
internal fun ownContactCandidate(parish: Parish, profile: OfficialDirectoryProfile,
    snapshot: OfficialDirectorySnapshot): OfficialDirectoryEntry? {
    if (snapshot.profileId != profile.id || snapshot.directoryUrl != profile.url ||
        snapshot.entries.size !in profile.minimumEntries..OrtOxDirectoryParser.MAX_ENTRIES ||
        snapshot.entries.map { it.id }.distinct().size != snapshot.entries.size) return null
    val city = OfficialDirectoryProfile.normalizedLocality(parish.city)
    val selected = DirectoryAddress.parse(parish.address, profile)
    val building = selected.building ?: return null
    if (!selected.confirmedCity(city)) return null
    val localities = DirectoryAddress.provenLocalities(snapshot.entries, profile)
    val entry = snapshot.entries.filter {
        val address = DirectoryAddress.parse(it.address, profile, localities)
        address.confirmedCity(city) && address.building?.sameBuilding(building) == true
    }.singleOrNull() ?: return null
    if (!OfficialContactNames.matches(parish.name, entry.title, city, profile.region, building.street, building) ||
        !ScheduleEntityIdentity.compatible(parish.name, entry.title)) return null
    if (entry.coordinates != null) {
        val pair = OfficialContactProof.coordinatePair(entry.coordinates) ?: return null
        if (!OfficialContactProof.pointAgrees(pair.first, pair.second, parish)) return null
    }
    return entry
}
