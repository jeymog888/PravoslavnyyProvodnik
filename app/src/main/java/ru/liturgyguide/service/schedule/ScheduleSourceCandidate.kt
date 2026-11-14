package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonElement
import ru.liturgyguide.model.Parish
import java.security.MessageDigest
import java.time.Instant
import java.net.URI

/** Separate provenance for the public response that actually supplied the coordinates. */
data class ScheduleGeometryEvidence(val url: String, val sha256: String) {
    fun valid(): Boolean = runCatching {
        ScheduleUrl.publicSource(url) == url && sha256.matches(Regex("[a-f0-9]{64}"))
    }.getOrDefault(false)
}

/** Provider discovery is not identity proof. Incomplete search results never establish uniqueness. */
enum class ScheduleIdentityOutcome { PROVED, INSUFFICIENT, CONFLICT }

/** A new provider can use this contract without changing the review UI or publication importer. */
fun interface ScheduleCandidateProvider {
    suspend fun discover(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery
}

data class ScheduleCandidateDiscovery(
    val candidates: List<ScheduleSourceCandidate> = emptyList(),
    val observedIds: List<String> = emptyList(),
    val incomplete: Boolean = true,
    val unavailable: Boolean = false,
    @Transient val freshContactEvidence: Map<String, PublishedChildContactEvidence>? = null,
)

data class ScheduleSourceDiscovery(
    val outcome: ScheduleIdentityOutcome,
    val site: ScheduleSite? = null,
    val candidates: ScheduleCandidateDiscovery = ScheduleCandidateDiscovery(),
    @Transient val fastMapReceipt: FastMapContinuationReceipt? = null,
)

/** Evidence about a candidate is retained even when its old address prevents confirmation. */
data class ScheduleSourceCandidate(
    val version: Int = 1,
    val selectedIdentity: String,
    val provider: String,
    val providerLabel: String,
    val providerObjectId: String,
    val name: String,
    val address: String,
    val sourceUrl: String,
    val evidenceUrl: String,
    val evidenceSha256: String,
    val checkedAt: String,
    val latitude: Double,
    val longitude: Double,
    val outcome: ScheduleIdentityOutcome,
    val reason: String,
    // Only a linked, current own contact page with the same parish email may explain an old house.
    val currentContactAddress: String = "",
    val currentContactUrl: String = "",
    val currentContactSha256: String = "",
    val linkedEmail: String = "",
    val verificationUnavailable: Boolean = false,
    val geometryEvidence: ScheduleGeometryEvidence? = null,
    // Candidate facts only; never used as the transient foreign-image witness.
    val ownContact: OwnContactCandidateMetadata? = null,
) {
    val id: String get() = sha("$provider\u0000$providerObjectId\u0000$sourceUrl")

    fun validFor(parish: Parish): Boolean = runCatching {
        require(version == 1 && selectedIdentity == ScheduleEntityIdentity.fingerprint(parish))
        require(provider.matches(Regex("[a-z][a-z0-9_-]{1,49}")))
        require(providerObjectId.length in 1..80 && providerObjectId.none(Char::isISOControl))
        require(listOf(providerLabel, name, reason).all { it.length in 1..500 && it.none(Char::isISOControl) })
        require(address.length <= 500 && address.none(Char::isISOControl))
        require(ScheduleUrl.publicSource(sourceUrl) == sourceUrl && ScheduleUrl.publicSource(evidenceUrl) == evidenceUrl)
        require(evidenceSha256.matches(Regex("[a-f0-9]{64}")))
        geometryEvidence?.let { require(it.valid()) }
        if (provider == ElitsyMapCandidateProvider.PROVIDER) {
            val geometry = requireNotNull(geometryEvidence)
            val uri = URI(geometry.url)
            require(uri.scheme == "https" && uri.host == "elitsy.ru" && uri.port == -1 &&
                uri.rawPath == "/map/ajaxpoints/" && uri.rawQuery != null && uri.rawFragment == null)
            val parts = uri.rawQuery.split('&')
            require(parts.map { it.substringBefore('=') } == listOf("lb", "lb", "rt", "rt", "z") && parts.last() == "z=15")
            val bounds = parts.take(4).map { it.substringAfter('=', "").toDouble().also { value -> require(value.isFinite()) } }
            require(bounds[0] in -90.0..90.0 && bounds[2] in -90.0..90.0 && bounds[0] < bounds[2] &&
                bounds[1] in -180.0..180.0 && bounds[3] in -180.0..180.0 && bounds[1] < bounds[3])
            require(evidenceUrl == "https://elitsy.ru/parish/$providerObjectId/")
        }
        if (provider == OwnContactCandidateProvider.PROVIDER) {
            val metadata = requireNotNull(ownContact)
            require(OwnContactChildIdentity.ordinaryOsm(parish) && OwnContactChildIdentity.fullName(name) &&
                OwnContactChildIdentity.pointAgrees(parish, latitude, longitude))
            require(sourceUrl == evidenceUrl && geometryEvidence == ScheduleGeometryEvidence(evidenceUrl, evidenceSha256))
            require(metadata.blockKey.matches(Regex("(?:child:[a-f0-9]{40}|dom:[A-Za-z0-9_-]{1,100})")))
            require(providerObjectId == OwnContactChildIdentity.objectId(evidenceUrl, metadata.blockKey, name))
            require(metadata.seedOrigins.size in 1..20 && metadata.seedOrigins.distinct().size == metadata.seedOrigins.size &&
                metadata.seedOrigins.all { it.valid() })
            require(metadata.publishedPhone == null || metadata.publishedPhone.matches(Regex("[0-9]{10}")))
            require(outcome == ScheduleIdentityOutcome.CONFLICT ||
                !OwnContactChildIdentity.conflict(parish, name, address, metadata.publishedPhone))
            require(listOf(currentContactAddress, currentContactUrl, currentContactSha256, linkedEmail).all(String::isEmpty))
        } else require(ownContact == null)
        Instant.parse(checkedAt)
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(outcome in setOf(ScheduleIdentityOutcome.INSUFFICIENT, ScheduleIdentityOutcome.CONFLICT))
        val selectedLat = requireNotNull(parish.latitude); val selectedLon = requireNotNull(parish.longitude)
        require(OpenScheduleSiteResolver.distanceMeters(selectedLat, selectedLon, latitude, longitude) <= 650)
        // Recompute every known conflict on decode/confirmation. Tampering with the outcome cannot erase it.
        val scopeConflict = ScheduleEntityIdentity.hasExplicitConflict(parish.name, name) ||
            !OpenScheduleSiteResolver.namesMatch(parish.name, name)
        val localityConflict = parish.city.isNotBlank() && ChildChurchRelation.city(address)?.let {
            it != ScheduleEntityIdentity.normalize(parish.city)
        } == true
        val wanted = PalomnikStreetAddress.parse(parish.address)
        val old = PalomnikStreetAddress.parse(address)
        val oldConflict = wanted != null && old != null && MoscowChurchIdentity.definitelyOtherAddress(wanted, old)
        val hasContact = listOf(currentContactAddress, currentContactUrl, currentContactSha256, linkedEmail).any(String::isNotEmpty)
        val resolved = if (hasContact) {
            require(ScheduleUrl.publicSource(currentContactUrl) == currentContactUrl &&
                ScheduleDocumentLinks.sameSite(sourceUrl, currentContactUrl))
            require(currentContactSha256.matches(Regex("[a-f0-9]{64}")))
            require(linkedEmail.matches(Regex("[^\\s@]{1,64}@[^\\s@]{1,190}\\.[a-zA-Z]{2,20}")))
            val current = requireNotNull(PalomnikStreetAddress.parse(currentContactAddress))
            require(wanted != null && wanted.sameBuilding(current))
            true
        } else false
        require(outcome == ScheduleIdentityOutcome.CONFLICT || !scopeConflict && !localityConflict && (!oldConflict || resolved))
        true
    }.getOrDefault(false)

    fun canConfirm(parish: Parish): Boolean = validFor(parish) && !verificationUnavailable && outcome == ScheduleIdentityOutcome.INSUFFICIENT &&
        ScheduleEntityIdentity.compatible(parish.name, name, ScheduleEntityIdentity.scope(parish.name))

    companion object {
        private val gson = Gson()
        internal fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        internal fun sha(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value).joinToString("") { "%02x".format(it) }
        internal fun decode(value: JsonElement, parish: Parish): ScheduleSourceCandidate? = runCatching {
            require(value.isJsonObject)
            gson.fromJson(value, ScheduleSourceCandidate::class.java).takeIf { it.validFor(parish) }
        }.getOrNull()
    }
}

/** User intent is never encoded as an exact OSM link or an automatic directory proof. */
data class ScheduleCandidateConfirmation(
    val version: Int,
    val type: String,
    val selectedIdentity: String,
    val candidate: ScheduleSourceCandidate,
    val confirmedAt: String,
) {
    fun validFor(parish: Parish, binding: ScheduleSiteBinding): Boolean = runCatching {
        require(version == 1 && type == TYPE && selectedIdentity == ScheduleEntityIdentity.fingerprint(parish))
        require(candidate.canConfirm(parish) && candidate.selectedIdentity == selectedIdentity)
        require(candidate.sourceUrl == binding.siteUrl && candidate.evidenceUrl == binding.evidenceUrl &&
            candidate.name == binding.matchedName)
        require(!Instant.parse(confirmedAt).isBefore(Instant.parse(candidate.checkedAt)))
        true
    }.getOrDefault(false)

    companion object {
        const val TYPE = "USER_CONFIRMED_SOURCE"
        internal fun decode(value: JsonElement, parish: Parish): ScheduleCandidateConfirmation? = runCatching {
            require(value.isJsonObject)
            val obj = value.asJsonObject
            ScheduleCandidateConfirmation(obj.get("version").asInt, obj.get("type").asString,
                obj.get("selectedIdentity").asString, requireNotNull(ScheduleSourceCandidate.decode(obj.get("candidate"), parish)),
                obj.get("confirmedAt").asString)
        }.getOrNull()
    }
}
