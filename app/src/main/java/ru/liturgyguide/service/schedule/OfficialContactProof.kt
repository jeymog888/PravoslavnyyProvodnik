package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import ru.liturgyguide.model.Parish
import java.net.URI
import java.time.Instant

/** A compact record of the inspected publisher chain, never a replacement for its HTML. */
enum class OfficialContactLayout { CONTACT_PAGE, HOME_PASSPORT }

data class OfficialContactProof(
    val proofVersion: Int,
    val publisherProfileId: String,
    val directoryUrl: String,
    val directorySha256: String,
    val directoryFetchedAt: String,
    val cardId: String,
    val cardName: String,
    val cardAddress: String,
    val cardWebsite: String,
    val cardCoordinates: String?,
    val homeRequestedUrl: String,
    val homeFinalUrl: String,
    val homeSha256: String,
    val evidenceRequestedUrl: String,
    val evidenceFinalUrl: String,
    val evidenceSha256: String,
    val layout: OfficialContactLayout,
    val ownName: String,
    val siteTitle: String,
    val mapName: String,
    val rawAddress: String,
    val city: String,
    val streetKind: String,
    val street: String,
    val house: String,
    val corpus: String?,
    val building: String?,
    val publisherEntityId: String,
    val latitude: Double,
    val longitude: Double,
    val checkedAt: String,
) {
    internal fun validFor(parish: Parish, binding: ScheduleSiteBinding): Boolean = runCatching {
        require(proofVersion == 1 && binding.method == ScheduleBindingMethod.DIRECTORY_OWN_CONTACT)
        val profile = OfficialDirectoryProfile.PUBLISHED.single { it.id == publisherProfileId }
        require(profile.serves(parish.city) && directoryUrl == profile.url)
        require(listOf(directorySha256, homeSha256, evidenceSha256).all { Regex("[a-f0-9]{64}").matches(it) })
        listOf(directoryFetchedAt, checkedAt).forEach { require(it.length in 1..40); Instant.parse(it) }
        require(Regex("t[1-9][0-9]{0,11}").matches(cardId) && cardId == "t$publisherEntityId")
        require(listOf(cardName, ownName, siteTitle).all { text(it, 300) } && text(mapName, 600))
        require(text(cardAddress, 500) && text(rawAddress, 500))
        require(cardCoordinates == null || text(cardCoordinates, 100))
        require(listOf(cardWebsite, homeRequestedUrl, homeFinalUrl, evidenceRequestedUrl, evidenceFinalUrl)
            .all { ScheduleUrl.publicSource(it) == it })
        require(homeRequestedUrl == cardWebsite && homeUrl(homeRequestedUrl) && homeUrl(homeFinalUrl))
        require(ScheduleUrl.permittedRedirect(homeRequestedUrl, homeFinalUrl))
        require(binding.siteUrl == homeFinalUrl && binding.evidenceUrl == evidenceFinalUrl && binding.matchedName == ownName)
        require(binding.selectedIdentity == ScheduleEntityIdentity.fingerprint(parish))
        if (layout == OfficialContactLayout.HOME_PASSPORT) {
            require(evidenceRequestedUrl == homeRequestedUrl && evidenceFinalUrl == homeFinalUrl && evidenceSha256 == homeSha256)
        } else {
            require(ScheduleDocumentLinks.sameSite(homeFinalUrl, evidenceRequestedUrl) &&
                ScheduleUrl.permittedRedirect(evidenceRequestedUrl, evidenceFinalUrl))
            require(evidenceFinalUrl != homeFinalUrl)
        }
        val selected = DirectoryAddress.parse(parish.address, profile)
        val card = DirectoryAddress.parse(cardAddress, profile)
        val own = DirectoryAddress.parse(rawAddress, profile)
        val wantedCity = OfficialDirectoryProfile.normalizedLocality(parish.city)
        require(listOf(selected, card, own).all { it.confirmedCity(wantedCity) && it.building != null })
        val address = requireNotNull(own.building)
        require(address.sameBuilding(requireNotNull(selected.building)) && address.sameBuilding(requireNotNull(card.building)))
        require(city == wantedCity && streetKind == address.streetKind && street == address.street && house == address.house &&
            corpus == address.corpus && building == address.building)
        require(listOf(cardName, ownName, mapName).all {
            OfficialContactNames.matches(parish.name, it, city, profile.region, street, address) &&
                ScheduleEntityIdentity.compatible(parish.name, it)
        })
        require(ScheduleEntityIdentity.compatible(parish.name, ownName, binding.matchedScope))
        require(OfficialContactNames.auxiliaryAgrees(ownName, siteTitle, city, profile.region, street, address))
        if (layout == OfficialContactLayout.CONTACT_PAGE) require(ownName == siteTitle)
        require(pointAgrees(latitude, longitude, parish))
        if (cardCoordinates != null) {
            val pair = coordinatePair(cardCoordinates) ?: error("Invalid directory point")
            require(pointAgrees(pair.first, pair.second, parish))
            require(OpenScheduleSiteResolver.distanceMeters(pair.first, pair.second, latitude, longitude) <= 50)
        }
        true
    }.getOrDefault(false)

    companion object {
        private fun text(value: String, max: Int) = value.length in 1..max && value.none(Char::isISOControl)
        internal fun homeUrl(url: String): Boolean = runCatching { URI(url).let { it.path == "/" && it.rawQuery == null } }.getOrDefault(false)
        internal fun pointAgrees(latitude: Double, longitude: Double, parish: Parish): Boolean {
            val lat = parish.latitude ?: return false
            val lon = parish.longitude ?: return false
            return latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
                OpenScheduleSiteResolver.distanceMeters(latitude, longitude, lat, lon) <= 50
        }
        internal fun coordinatePair(value: String): Pair<Double, Double>? {
            val found = Regex("\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*[,;]\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*")
                .matchEntire(value) ?: return null
            return (found.groupValues[1].toDoubleOrNull() ?: return null) to
                (found.groupValues[2].toDoubleOrNull() ?: return null)
        }

        /** Explicit decoding avoids Gson's constructor bypass and silent missing/default fields. */
        internal fun decode(value: JsonObject): OfficialContactProof {
            require(value.toString().length <= 12_000)
            fun string(key: String): String {
                val item = requireNotNull(value.get(key))
                require(item.isJsonPrimitive && item.asJsonPrimitive.isString)
                return item.asString
            }
            fun optional(key: String): String? = value.get(key)?.takeUnless { it.isJsonNull }?.let { string(key) }
            fun number(key: String): Double {
                val item = requireNotNull(value.get(key))
                require(item.isJsonPrimitive && item.asJsonPrimitive.isNumber)
                return item.asDouble.also { require(it.isFinite()) }
            }
            val version = requireNotNull(value.get("proofVersion"))
            require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "1")
            return OfficialContactProof(1, string("publisherProfileId"), string("directoryUrl"), string("directorySha256"),
                string("directoryFetchedAt"), string("cardId"), string("cardName"), string("cardAddress"), string("cardWebsite"),
                optional("cardCoordinates"), string("homeRequestedUrl"), string("homeFinalUrl"), string("homeSha256"),
                string("evidenceRequestedUrl"), string("evidenceFinalUrl"), string("evidenceSha256"),
                OfficialContactLayout.valueOf(string("layout")), string("ownName"), string("siteTitle"), string("mapName"),
                string("rawAddress"), string("city"), string("streetKind"), string("street"), string("house"),
                optional("corpus"), optional("building"), string("publisherEntityId"), number("latitude"), number("longitude"), string("checkedAt"))
        }
    }
}

/** This conservative agreement is local to the new proof; the existing discovery matcher is unchanged. */
internal object OfficialContactNames {
    private val serviceWords = setOf("храм", "церковь", "собор", "во", "в", "имя", "имени", "святителя", "святитель", "свт",
        "святой", "святого", "святых", "свято", "св", "православный", "православная", "приход", "приходской", "сайт")
    private val addressMarker = Regex("(?<![а-яa-z])(?:улица|ул\\.?|проспект|пр-кт|просп\\.?|переулок|пер\\.?|проезд|бульвар|бул\\.?|шоссе|набережная|наб\\.?|площадь|пл\\.?|адрес)(?![а-яa-z])")
    private fun tokens(name: String, city: String, region: String, street: String,
        expectedAddress: PalomnikStreetAddress?): Set<String>? {
        fun phrase(value: String) = ScheduleEntityIdentity.normalize(value).split(' ').joinToString("\\s+") { Regex.escape(it) }
        val cityPattern = phrase(city)
        var value = name.lowercase(java.util.Locale.ROOT).replace('ё', 'е')
        if (street.isNotBlank()) {
            // Remove a whole published address, never its words from the dedication itself.
            val kind = "(?:улица|ул\\.|проспект|пр-кт|просп\\.|переулок|пер\\.|проезд|бульвар|бул\\.|шоссе|набережная|наб\\.|площадь|пл\\.)"
            val number = "[0-9]{1,4}(?:/[0-9]{1,4})?(?:\\s*-?\\s*[а-яa-z](?![а-яa-z]))?"
            val address = "(?<![а-яa-z])(?:россия\\s*,?\\s*)?(?:${phrase(region)}\\s*,?\\s*)?" +
                "(?:(?:г\\.?|город)\\s*)?$cityPattern\\s*,\\s*$kind\\s*${phrase(street)}\\s*,?\\s*(?:д\\.?|дом)?\\s*$number" +
                "(?:\\s*,?\\s*(?:корпус|корп\\.|к\\.|строение|стр\\.|с\\.)\\s*$number)*(?=\\s*(?:\\(|$))"
            val fragments = Regex(address).findAll(value).toList()
            for (fragment in fragments) {
                val parsed = PalomnikStreetAddress.parse(fragment.value) ?: return null
                if (expectedAddress?.sameBuilding(parsed) != true) return null
            }
            value = value.replace(Regex(address), " ")
        }
        // Another or only partly parsed explicit address must not become dedication tokens.
        if (addressMarker.containsMatchIn(value)) return null
        value = value.replace(Regex("\\(\\s*(?:(?:г\\.?|город)\\s*)?$cityPattern\\s*\\)"), " ")
            .replace(Regex("(?<![а-яa-z])(?:г\\.?|город)\\s+$cityPattern(?=\\s*(?:$|[,()]))"), " ")
        val words = ScheduleEntityIdentity.normalize(value).split(' ').filter { it.isNotBlank() && it !in serviceWords }
        // Only the two published forms of the same dedication omit "Образа".
        val normalized = words.filterNot { it in setOf("образа", "образ") && "спаса" in words && "нерукотворного" in words }
        return normalized.map { word ->
            when (word) {
                "николай", "николая", "никольский", "никольская", "никольского" -> "николай"
                "спас", "спаса", "спасский", "спасская" -> "спас"
                else -> word
            }
        }.toSet()
    }
    fun matches(selected: String, candidate: String, city: String, region: String, street: String = "",
        expectedAddress: PalomnikStreetAddress? = null): Boolean {
        val wanted = tokens(selected, city, region, street, expectedAddress) ?: return false
        val found = tokens(candidate, city, region, street, expectedAddress) ?: return false
        return wanted.size >= 2 && wanted == found && !ScheduleEntityIdentity.hasExplicitConflict(selected, candidate)
    }
    internal fun auxiliaryAgrees(fullName: String, title: String, city: String, region: String, street: String,
        expectedAddress: PalomnikStreetAddress? = null): Boolean {
        if (ScheduleEntityIdentity.hasExplicitConflict(fullName, title)) return false
        val full = tokens(fullName, city, region, street, expectedAddress) ?: return false
        val short = tokens(title, city, region, street, expectedAddress) ?: return false
        return short.isNotEmpty() && full.containsAll(short)
    }
}
