package ru.liturgyguide.service.discovery

import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.ScheduleUrl
import java.util.Locale

/** Explicit, reviewed cross-source identities. This is not a name/distance deduplication algorithm.
 * Evidence and snapshot hashes: docs/CHURCH_IDENTITY.md
 */
internal object KnownChurchIdentity {
    private data class Binding(
        val osmId: String, val parishId: String, val mapName: String, val parishName: String,
        val point: GeoPoint, val website: String, val source: String
    )

    private val bindings = listOf(
        Binding("osm:way:186160039", "elisavetinsky_opalikha",
            "Храм Святой Преподобномученицы Елисаветы в Опалихе", "Елисаветинский храм в Опалихе",
            GeoPoint(55.82548645, 37.24346205),
            "https://xn----7sbbgncsdakjgxi3cgfp8d.xn--p1ai/", "https://mosmit.ru/eparhy/temple/3_ch353/"),
        Binding("osm:way:51259798", "sretensky_old", "Храм Сретения Владимирской иконы Божией Матери",
            "Сретенский монастырь. Храм Сретения Владимирской иконы Божией Матери", GeoPoint(55.7656764, 37.6301738),
            "https://monastery.ru/bogosluzhenie/raspisanie-bogosluzheniy/", "https://monastery.ru/bogosluzhenie/raspisanie-bogosluzheniy/"),
        Binding("osm:way:484755695", "sretensky_new", "Храм Новомучеников и исповедников Российских на Лубянке",
            "Сретенский монастырь. Храм Воскресения Христова и Новомучеников и Исповедников Церкви Русской", GeoPoint(55.7659801, 37.62943655),
            "https://monastery.ru/bogosluzhenie/raspisanie-bogosluzheniy/", "https://monastery.ru/bogosluzhenie/raspisanie-bogosluzheniy/")
    ).associateBy { it.osmId }

    /** Never change an already saved identity or its reading/cache key through an alias. */
    fun existingFor(place: DiscoveredChurch, availableById: Map<String, Parish>): Parish? {
        availableById[place.id]?.let { return it }
        val binding = bindings[place.id] ?: return null
        val expectedSource = "https://www.openstreetmap.org/" + binding.osmId.removePrefix("osm:").replace(':', '/')
        if (place.sourceUrl != expectedSource || place.isChapel ||
            place.kind !in setOf(DiscoveredPlaceKind.CHURCH, DiscoveredPlaceKind.WORSHIP_PLACE) ||
            normalized(place.name) != normalized(binding.mapName)) return null
        // Coordinates only veto a moved/corrupt known object; they never discover an identity.
        if (place.center.distanceMeters(binding.point) > 100) return null
        val parish = availableById[binding.parishId] ?: return null
        if (parish.id != binding.parishId || normalized(parish.name) != normalized(binding.parishName) ||
            publicDocument(parish.website) != binding.website || publicDocument(parish.sourceUrl) != binding.source) return null
        return parish
    }

    private fun normalized(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun publicDocument(value: String) = ScheduleUrl.publicSource(value)?.substringBefore('?')
}
