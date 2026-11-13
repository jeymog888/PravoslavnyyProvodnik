package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import java.net.URI

enum class PublisherSeedRole { CONTACT_REFERENCE, WEBSITE }
enum class PublisherSeedOrigin { CURATED, OSM }

/** Local discovery metadata is a hint, never evidence that its owner publishes for the selection. */
data class PublisherSeedProvenance(val origin: PublisherSeedOrigin, val recordId: String, val recordName: String,
    val latitude: Double, val longitude: Double, val metadataSource: String, val metadataSha256: String) {
    internal fun valid() = recordId.length in 1..160 && recordName.length in 1..500 && metadataSource.length in 1..500 &&
        listOf(recordId, recordName, metadataSource).none { it.any(Char::isISOControl) } &&
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        metadataSha256.matches(Regex("[a-f0-9]{64}"))
}

data class PublisherSeed(val url: String, val role: PublisherSeedRole, val publisherScopeUrl: String,
    val provenance: PublisherSeedProvenance) {
    internal fun valid() = ScheduleUrl.publicSource(url) == url && provenance.valid() &&
        PublisherContactScope.valid(publisherScopeUrl) && PublisherContactScope.contains(publisherScopeUrl, url)
}

fun interface PublisherSeedPool {
    fun seeds(parish: Parish, radiusMeters: Double, limit: Int): List<PublisherSeed>
}

/** The catalog/curated readers supply immutable records once; this adapter does no asset or network decode. */
class LocalPublisherSeedPool(records: List<PublisherSeed>) : PublisherSeedPool {
    private val records = records.toList()
    override fun seeds(parish: Parish, radiusMeters: Double, limit: Int): List<PublisherSeed> {
        require(radiusMeters in 1.0..10_000.0 && limit in 1..60)
        val lat = parish.latitude ?: return emptyList()
        val lon = parish.longitude ?: return emptyList()
        return records.asSequence().filter { it.valid() }.map { it to OpenScheduleSiteResolver.distanceMeters(
            lat, lon, it.provenance.latitude, it.provenance.longitude) }.filter { it.second <= radiusMeters }
            .sortedWith(compareBy({ if (it.first.role == PublisherSeedRole.CONTACT_REFERENCE) 0 else
                if (it.first.provenance.origin == PublisherSeedOrigin.CURATED) 1 else 2 }, { it.second }, { it.first.url }))
            .take(limit).map { it.first }.toList()
    }
}

/** Exact public origin/explicit publisher path. A shared host does not collapse its tenants. */
internal object PublisherContactScope {
    fun valid(scope: String): Boolean = runCatching {
        ScheduleUrl.publicSource(scope) == scope && URI(scope).let {
            it.rawQuery == null && it.rawFragment == null && path(it.rawPath) != null
        }
    }.getOrDefault(false)

    fun contains(scope: String, target: String): Boolean = runCatching {
        if (!valid(scope) || ScheduleUrl.publicSource(target) != target || !ScheduleUrl.permittedRedirect(scope, target)) return false
        val base = path(URI(scope).rawPath) ?: return false
        val next = path(URI(target).rawPath) ?: return false
        base.isEmpty() || next == base || next.startsWith("$base/")
    }.getOrDefault(false)

    fun sameEndpoint(requested: String, final: String): Boolean = runCatching {
        val a = path(URI(requested).rawPath) ?: return false
        val b = path(URI(final).rawPath) ?: return false
        ScheduleUrl.permittedRedirect(requested, final) && a == b &&
            URI(requested).rawQuery == URI(final).rawQuery
    }.getOrDefault(false)

    private fun path(raw: String): String? {
        if (Regex("(?i)%2f|%5c|%2e").containsMatchIn(raw) || raw.split('/').any { it == "." || it == ".." }) return null
        return raw.trimEnd('/')
    }
}

/** Serializable candidate metadata, explicitly not a fresh foreign-image witness. */
data class OwnContactCandidateMetadata(val blockKey: String, val publishedPhone: String?,
    val seedOrigins: List<PublisherSeedProvenance>)
