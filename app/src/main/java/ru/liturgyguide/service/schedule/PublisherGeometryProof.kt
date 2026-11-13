package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonObject
import ru.liturgyguide.model.Parish
import java.time.Instant

/** Normalized observations retain both complete candidate lists, not just the winning church. */
data class PublisherGeometryProof(val proofVersion: Int, val profileId: String, val profileVersion: Int,
    val selectedFingerprint: String, val contour: PublisherContour, val contourDigest: String,
    val neighbours: List<PublisherNeighbour>, val neighbourDigest: String,
    val ortoxRows: List<PublisherCatalogRow>, val directoryRows: List<PublisherCatalogRow>,
    val ortoxRowsDigest: String, val directoryRowsDigest: String, val passports: List<PublisherPassport>,
    val selectedPassportId: String, val detail: PublisherCatalogRow, val own: PublisherOwnIdentity,
    val linkedAnchor: PublisherLinkedAnchor?, val links: List<PublisherVerifiedLink>,
    val evidence: List<PublisherPageEvidence>, val checkedAt: String) {

    internal fun validFor(parish: Parish, binding: ScheduleSiteBinding): Boolean = runCatching {
        val profile = PublisherGeometryProfile.PUBLISHED.single { it.id == profileId && it.version == profileVersion }
        require(proofVersion == 1 && profile.eligible(parish) && binding.method == ScheduleBindingMethod.PUBLISHER_GEOMETRY_CHAIN)
        require(parish.city.isBlank() || PublisherIdentity.normalized(parish.city) == PublisherIdentity.normalized(profile.city))
        require(selectedFingerprint == ScheduleEntityIdentity.fingerprint(parish) && binding.selectedIdentity == selectedFingerprint)
        require(contour.id == PublisherGeometry.osmId(parish) && PublisherGeometry.valid(contour))
        require(ScheduleEntityIdentity.normalize(contour.name) == ScheduleEntityIdentity.normalize(parish.name))
        require(contourDigest == PublisherGeometry.digest(contour) && neighbourDigest == PublisherGeometry.digest(neighbours))
        require(neighbours.size in 1..30 && neighbours.map { it.type to it.id }.distinct().size == neighbours.size)
        require(neighbours.single { it.type == "way" && it.id == contour.id }.points == contour.points)
        require(neighbours.all { it.points.all(PublisherPoint::valid) && if (it.type == "node") it.points.size == 1 else
            it.type == "way" && PublisherGeometry.valid(PublisherContour(it.id, 1, "", it.points)) })
        require(ortoxRows.size in profile.minimumOrtOx..2000 && directoryRows.size in profile.minimumDirectory..40)
        require(ortoxRowsDigest == PublisherGeometry.digest(ortoxRows) && directoryRowsDigest == PublisherGeometry.digest(directoryRows))
        require(listOf(ortoxRows, directoryRows).all { rows -> rows.map { it.id }.distinct().size == rows.size && rows.all(::validRow) })
        val potential = possible(ortoxRows, parish)
        require(potential.size in 1..3 && passports.size == potential.size && passports.map { it.id }.toSet() == potential.map { it.id }.toSet())
        val nearby = passports.filter { passport ->
            val row = potential.single { it.id == passport.id }
            require(PublisherIdentity.correspondingPassport(row, passport))
            val near = PublisherGeometry.distance(passport.mapPoint,
                PublisherPoint(requireNotNull(parish.latitude), requireNotNull(parish.longitude))) <= 150
            if (near) {
                // Only a geometrically eligible winner needs the full current church contract.
                val city = requireNotNull(PublisherIdentity.city(passport.address))
                require(PublisherIdentity.sameName(parish.name, passport.name, city, passport.address) &&
                    PublisherIdentity.sameName(parish.name, passport.mapName, city, passport.address))
                require(PublisherIdentity.sameName(parish.name, row.name, city, passport.address))
                require(PublisherIdentity.rector(passport.rector) != null)
                require(PublisherIdentity.street(requireNotNull(row.address)) == PublisherIdentity.street(passport.address))
            }
            near
        }
        val passport = nearby.single()
        require(passport.id == selectedPassportId && PublisherIdentity.city(passport.address) == PublisherIdentity.normalized(profile.city))
        require(binding.matchedScope == ScheduleEntityScope.CHURCH && binding.matchedName == passport.name)
        val official = possible(directoryRows, parish).single()
        require(detail == official && detail.website != null && detail.detailUrl == binding.evidenceUrl)
        require(PublisherIdentity.sameName(parish.name, detail.name, profile.city, passport.address))
        require(PublisherIdentity.city(requireNotNull(detail.address)) == PublisherIdentity.normalized(profile.city))
        require(PublisherIdentity.rector(requireNotNull(detail.rector)) == PublisherIdentity.rector(passport.rector) &&
            PublisherIdentity.rector(own.rector) == PublisherIdentity.rector(passport.rector))
        require(PublisherIdentity.phone(requireNotNull(detail.phone)) == PublisherIdentity.phone(own.phone) && PublisherIdentity.phone(own.phone) != null)
        require(PublisherIdentity.normalized(own.city) == PublisherIdentity.normalized(profile.city) &&
            PublisherIdentity.city(own.address) == PublisherIdentity.normalized(profile.city))
        require(PublisherIdentity.building(own.address) != null && PublisherIdentity.street(own.address) == PublisherIdentity.street(passport.address))
        require(PublisherIdentity.sameName(parish.name, own.name, profile.city, passport.address))
        require(PublisherIdentity.currentStatement(own, parish.name))
        val old = requireNotNull(PublisherIdentity.building(requireNotNull(detail.address)))
        val current = requireNotNull(PublisherIdentity.building(own.address))
        if (!old.sameBuilding(current)) require(PublisherIdentity.relocated(own, detail.address, parish.name))
        val anchors = mutableListOf(passport.mapPoint)
        if (linkedAnchor != null) {
            require(Regex("https://sobory\\.ru/article/\\?object=[1-9][0-9]{0,12}").matches(linkedAnchor.url))
            require(PublisherIdentity.normalized(linkedAnchor.city) == PublisherIdentity.normalized(profile.city))
            require(PublisherIdentity.sameName(parish.name, linkedAnchor.name, profile.city, passport.address))
            require(PublisherIdentity.street(linkedAnchor.address) == PublisherIdentity.street(passport.address))
            require(linkedAnchor.point.valid() && PublisherGeometry.strictlyInside(linkedAnchor.point, contour.points))
            require(PublisherGeometry.distance(linkedAnchor.point, passport.mapPoint) <= 150)
            anchors += linkedAnchor.point
        }
        require(PublisherGeometry.strictlyInside(passport.mapPoint, contour.points) || linkedAnchor != null)
        require(PublisherGeometry.unambiguous(contour, neighbours, anchors))
        require(evidence.size in 12..16 && evidence.map { it.role }.distinct().size == evidence.size)
        require(evidence.all { page -> page.role.length in 1..100 && Regex("[a-f0-9]{64}").matches(page.sha256) &&
            ScheduleUrl.publicSource(page.requestedUrl) == page.requestedUrl && ScheduleUrl.publicSource(page.finalUrl) == page.finalUrl &&
            ScheduleUrl.permittedRedirect(page.requestedUrl, page.finalUrl) })
        fun page(role: String) = evidence.single { it.role == role }
        require(links.size in 7..10 && links.map { it.ownerRole to it.targetRole }.distinct().size == links.size)
        require(links.all { link -> link.label.isNotBlank() && link.label.length <= 100 &&
            evidence.any { it.role == link.ownerRole } && page(link.targetRole).requestedUrl == link.url })
        require(page("ortox").requestedUrl == profile.ortoxUrl && page("ortox").finalUrl == profile.ortoxUrl)
        require(page("directory:0").requestedUrl == profile.directoryUrl && page("directory:0").finalUrl == profile.directoryUrl)
        require(PublisherGeometryResolver.directoryIndex(page("directory:20").requestedUrl, profile) == 20)
        require(page("directory:20").requestedUrl == page("directory:20").finalUrl)
        require(page("osm").requestedUrl == PublisherGeometry.fullUrl(parish) && page("osm").finalUrl == page("osm").requestedUrl)
        require(page("neighbourhood").requestedUrl == PublisherGeometry.neighbourhoodUrl(contour) &&
            page("neighbourhood").finalUrl == page("neighbourhood").requestedUrl)
        for (candidate in potential) {
            val record = page("passport:${candidate.id}")
            require(record.requestedUrl == candidate.website)
            require(links.single { it.targetRole == record.role }.let { it.ownerRole == "ortox" && it.label == "сайт" })
        }
        require(page("detail").requestedUrl == detail.detailUrl && page("detail").finalUrl == detail.detailUrl)
        require(page("home").requestedUrl == detail.website)
        require(links.single { it.targetRole == "detail" }.let { it.ownerRole in setOf("directory:0", "directory:20") && it.label == "подробнее" })
        require(links.single { it.targetRole == "home" }.let { it.ownerRole == "detail" && it.label == "официальный сайт храма" })
        val home = page("home").finalUrl
        require(listOf("contact", "history", "clergy", "schedule").all { role ->
            ScheduleUrl.sameOrigin(home, page(role).requestedUrl) && ScheduleUrl.sameOrigin(home, page(role).finalUrl) &&
                links.single { it.targetRole == role }.let { it.ownerRole == "home" && it.label in PublisherGeometryResolver.navLabels(role) }
        })
        require(page("schedule").finalUrl == binding.siteUrl)
        if (linkedAnchor != null) {
            require(page("linked-anchor").requestedUrl == linkedAnchor.url && page("linked-anchor").finalUrl == linkedAnchor.url)
            require(links.single { it.targetRole == "linked-anchor" }.let { it.ownerRole == "detail" && it.label == "источник фото" })
        }
        require(checkedAt.length in 1..40); Instant.parse(checkedAt)
        true
    }.getOrDefault(false)

    companion object {
        private fun validRow(row: PublisherCatalogRow): Boolean = row.id.length in 1..2048 && row.name.length <= 300 &&
            row.name.none(Char::isISOControl) && listOfNotNull(row.address, row.rector, row.phone).all { it.length <= 500 && it.none(Char::isISOControl) } &&
            listOfNotNull(row.website, row.detailUrl).all { ScheduleUrl.publicSource(it) == it }

        internal fun possible(rows: List<PublisherCatalogRow>, parish: Parish): List<PublisherCatalogRow> = rows.filter { row ->
            val tokens = OpenScheduleSiteResolver.nameTokens(row.name)
            // The selected map object may have no locality. A published village label
            // is not geographic evidence against it; inspect every plausible passport.
            row.name.isBlank() || tokens.isEmpty() || OpenScheduleSiteResolver.namesMatch(parish.name, row.name)
        }
        internal fun decode(value: JsonObject): PublisherGeometryProof {
            require(value.toString().length <= 140_000)
            val gson = Gson()
            val proof = gson.fromJson(value, PublisherGeometryProof::class.java)
            // Reject unknown keys and primitive type coercion instead of trusting Gson's constructor bypass.
            require(gson.toJsonTree(proof) == value)
            return proof
        }
    }
}
