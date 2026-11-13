package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.discovery.GeoPoint
import java.net.URI

/** A finite curated identity witness, never a way to approve a website or a map candidate. */
internal class KnownOtherScheduleVenue private constructor(
    val parish: Parish,
    private val selectedIdentity: String,
    private val publisherSite: String,
) {
    fun validFor(selected: Parish, website: String, parent: String): Boolean =
        selectedIdentity == ScheduleEntityIdentity.fingerprint(selected) &&
            owns(publisherSite, website) && owns(publisherSite, parent)

    companion object {
        /** The caller supplies the shipped, reviewed directory, not saved/user-added map records. */
        fun resolve(selected: Parish, website: String, parent: String, curated: List<Parish>): KnownOtherScheduleVenue? {
            if (curated.size !in 2..100 || curated.map { it.id }.distinct().size != curated.size) return null
            val selectedIdentity = ScheduleEntityIdentity.fingerprint(selected)
            val own = curated.singleOrNull { trusted(it) && ScheduleEntityIdentity.fingerprint(it) == selectedIdentity }
                ?: return null
            // Multiple curated identities sharing a publisher are intentionally ambiguous.
            // Matching only their host must never identify which church owns an attachment.
            val owner = curated.filter { trusted(it) && owns(it.website, website) && owns(it.website, parent) }
                .singleOrNull() ?: return null
            if (owner.id == own.id) return null
            val ownPoint = point(own) ?: return null
            val otherPoint = point(owner) ?: return null
            // Both points already identify curated records; distance is only a contradiction
            // guard against aliases/duplicate records at one building, not a proximity proof.
            if (ownPoint.distanceMeters(otherPoint) <= 650.0) return null
            return KnownOtherScheduleVenue(owner, selectedIdentity, requireNotNull(ScheduleUrl.publicSource(owner.website)))
        }

        private fun trusted(parish: Parish): Boolean =
            parish.id.isNotBlank() && parish.name.length in 3..300 && parish.name.none(Char::isISOControl) &&
                parish.city.isNotBlank() && parish.address.isNotBlank() && point(parish) != null &&
                ScheduleEntityIdentity.scope(parish.name) == ScheduleEntityScope.CHURCH &&
                ScheduleUrl.publicSource(parish.website) != null && ScheduleUrl.publicSource(parish.sourceUrl) != null

        private fun point(parish: Parish): GeoPoint? {
            val point = GeoPoint(parish.latitude ?: return null, parish.longitude ?: return null)
            return point.takeIf { it.isValid }
        }

        /** Exact origin and slash-bounded publisher path; general shared hosts are not evidence. */
        private fun owns(publisher: String, context: String): Boolean = runCatching {
            val owner = URI(requireNotNull(ScheduleUrl.publicSource(publisher)))
            val target = URI(requireNotNull(ScheduleUrl.publicSource(context)))
            if (owner.rawQuery != null || owner.scheme != target.scheme || owner.host != target.host || owner.port != target.port)
                return@runCatching false
            fun safePath(uri: URI): String? = uri.rawPath.takeIf {
                '%' !in it && '\\' !in it && uri.normalize().rawPath == it
            }?.trimEnd('/')
            val prefix = safePath(owner) ?: return@runCatching false
            val path = safePath(target) ?: return@runCatching false
            prefix.isEmpty() || path == prefix || path.startsWith("$prefix/")
        }.getOrDefault(false)
    }
}
