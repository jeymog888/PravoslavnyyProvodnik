package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDate

/** A process capability issued only after reparsing a complete response read in this attempt. */
class PublishedChildContactEvidence private constructor(
    private val selectedFingerprint: String,
    private val osmId: String,
    private val osmSource: String,
    private val osmLatitude: Double,
    private val osmLongitude: Double,
    internal val contactUrl: String,
    private val responseSha256: String,
    internal val publisherScope: String,
    private val candidateId: String,
    private val literalName: String,
    private val locality: String,
    private val latitude: Double,
    private val longitude: Double,
    private val blockKey: String,
    private val phone: String?,
    @Transient private val observedResponse: ScheduleResponse?,
) {
    // A Gson/serialized reconstruction cannot obtain this process reference.
    @Transient private val issuer: Any = ISSUER
    @Transient private val navigationUse: AtomicBoolean? = AtomicBoolean(false)
    @Transient private val linkedLock: Any = Any()
    @Transient private var issuedRootResult: ContactRootAuditResult? = null
    @Transient private var pendingLinkedPermit: PrivateLinkedPermit? = null

    internal fun responseForApproved(parish: Parish, approved: ScheduleSourceCandidate, root: String): ScheduleResponse? =
        observedResponse?.takeIf { matchesApproved(parish, approved, root) }?.let { it.copy(bytes = it.bytes.copyOf()) }

    /** A role for this exact fresh response only, not another response with the same cached DTO fields. */
    internal fun auditNavigationRoot(parish: Parish, approved: ScheduleSourceCandidate, root: String,
        response: ScheduleResponse, htmlResult: ScheduleParseResult, document: ScheduleDocument,
        documentResult: ScheduleParseResult, attempt: Any = Any()): ContactRootAuditResult {
        if (!matchesCurrentResponse(parish, approved, root, response) || navigationUse?.compareAndSet(false, true) != true)
            return ContactRootAuditResult.notApplicable("no-exact-current-response-capability")
        fun generic(result: ScheduleParseResult) = result.disposition == ScheduleParseDisposition.REJECTED &&
            result.events.isEmpty() && result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty() &&
            !result.withdrawsAll && !result.safetyConflict && result.rejectionReason == null
        // A real parsed publication or withdrawal remains owned by the original parser result.
        if (!generic(htmlResult) || !generic(documentResult))
            return ContactRootAuditResult.notApplicable("raw-publication-or-safety-result")
        val currentChild = PublishedChildContactParser.Child(literalName, locality, latitude, longitude, phone, blockKey)
        var observed: ContactProvedNavigation? = null
        val result = ContactNavigationRootAudit.inspect(response, parish, currentChild, document, publisherScope) { observation ->
            observed = observation.copy(target = observation.target.copy(), footerFields = observation.footerFields.toList())
        }
        if (result.disposition == ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT) observed?.let { navigation ->
            synchronized(linkedLock) {
                issuedRootResult = result
                pendingLinkedPermit = PrivateLinkedPermit(attempt, navigation)
            }
        }
        return result
    }

    internal fun matchesCurrentResponse(parish: Parish, approved: ScheduleSourceCandidate, root: String,
        response: ScheduleResponse): Boolean {
        val observed = observedResponse ?: return false
        return navigationUse?.get() == false && matchesApproved(parish, approved, root) && response.url == root && response.url == observed.url &&
            response.contentType == observed.contentType &&
            response.contentType.substringBefore(';').trim().lowercase() in setOf("text/html", "application/xhtml+xml") &&
            response.bytes.contentEquals(observed.bytes) && ScheduleSourceCandidate.sha(response.bytes) == responseSha256
    }

    internal fun matchesFresh(parish: Parish, candidate: ScheduleSourceCandidate): Boolean = runCatching {
        navigationUse?.get() == false && matchesApproved(parish, candidate, candidate.sourceUrl) && candidate.evidenceSha256 == responseSha256 &&
            candidate.geometryEvidence == ScheduleGeometryEvidence(contactUrl, responseSha256) &&
            candidate.name == literalName && candidate.address == locality && candidate.latitude == latitude &&
            candidate.longitude == longitude && candidate.ownContact?.blockKey == blockKey && candidate.ownContact?.publishedPhone == phone
    }.getOrDefault(false)

    internal fun matchesApproved(parish: Parish, approved: ScheduleSourceCandidate, root: String): Boolean = runCatching {
        issuer === ISSUER && OwnContactChildIdentity.ordinaryOsm(parish) &&
            selectedFingerprint == ScheduleEntityIdentity.fingerprint(parish) && parish.id == osmId &&
            parish.sourceUrl == osmSource && parish.latitude == osmLatitude && parish.longitude == osmLongitude &&
            candidateId == approved.id && approved.provider == OwnContactCandidateProvider.PROVIDER && approved.canConfirm(parish) &&
            approved.sourceUrl == contactUrl && approved.evidenceUrl == contactUrl && root == contactUrl &&
            OwnContactChildIdentity.sameName(parish.name, literalName) && OwnContactChildIdentity.pointAgrees(parish, latitude, longitude) &&
            CuratedPublisherScope.owns(publisherScope, contactUrl)
    }.getOrDefault(false)

    /** Public audit receipts are not issuers. Only the exact result and current attempt may take this private transition. */
    internal fun takeLinkedNavigation(result: ContactRootAuditResult, attempt: Any): LinkedAttachmentNavigationPermit? {
        if (issuer !== ISSUER) return null
        return synchronized(linkedLock) {
            val permit = pendingLinkedPermit
            if (issuedRootResult !== result || permit == null || !permit.forAttempt(attempt)) null else {
                issuedRootResult = null; pendingLinkedPermit = null; permit
            }
        }
    }

    private inner class PrivateLinkedPermit(@Transient private val attempt: Any, private val navigation: ContactProvedNavigation) : LinkedAttachmentNavigationPermit {
        @Transient private val permitIssuer: Any = ISSUER
        @Transient private val reserved = AtomicBoolean(false)
        fun forAttempt(value: Any) = value === attempt
        override fun reserve(parish: Parish, root: String, binding: ScheduleSiteBinding?, link: ScheduleDocumentLink,
            rescue: Boolean, attempt: Any): LinkedAttachmentNavigationUse? {
            if (permitIssuer !== ISSUER || !forAttempt(attempt)) return null
            val approved = binding?.takeIf { it.method == ScheduleBindingMethod.USER_CONFIRMED && it.validFor(parish, root, contactUrl) }
                ?.candidateConfirmation?.candidate ?: return null
            if (rescue || link.document || root != contactUrl || link != navigation.target || link.linkedFrom != contactUrl ||
                !matchesApproved(parish, approved, root) || !reserved.compareAndSet(false, true)) return null
            return PrivateLinkedUse(navigation, attempt)
        }
    }

    private inner class PrivateLinkedUse(private val navigation: ContactProvedNavigation, @Transient private val attemptToken: Any) : LinkedAttachmentNavigationUse {
        @Transient private val useIssuer: Any = ISSUER
        @Transient private val consumed = AtomicBoolean(false)
        @Transient private var issued: LinkedAttachmentRead? = null
        @Transient private var effective: ScheduleParseResult? = null
        @Transient private var publishedLinks: List<ScheduleDocumentLink> = emptyList()
        @Transient private var success = false
        override fun read(response: ScheduleResponse, today: LocalDate, parish: Parish, binding: ScheduleSiteBinding?, curated: List<Parish>, attempt: Any): LinkedAttachmentRead {
            fun unavailable() = LinkedAttachmentRead(false, response.url, "")
            if (useIssuer !== ISSUER || !consumed.compareAndSet(false, true)) return unavailable()
            if (attempt !== attemptToken) return unavailable()
            val approved = binding?.takeIf { it.method == ScheduleBindingMethod.USER_CONFIRMED && it.validFor(parish, contactUrl, contactUrl) }
                ?.candidateConfirmation?.candidate ?: return unavailable()
            if (!matchesApproved(parish, approved, contactUrl) || response.url != navigation.target.url ||
                response.contentType.substringBefore(';').trim().lowercase() !in setOf("text/html", "application/xhtml+xml")) return unavailable()
            val snapshot = response.copy(bytes = response.bytes.copyOf())
            val sha = ScheduleSourceCandidate.sha(snapshot.bytes)
            val bounds = ContactNavigationRootAudit.sourceBounds(snapshot)
            if (bounds != null) {
                effective = ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true)
                return LinkedAttachmentRead(true, snapshot.url, sha, audit = LinkedAttachmentAuditResult(
                    LinkedAttachmentDisposition.OBSERVED_UNCLASSIFIED_SAFETY, bounds)).also { issued = it }
            }
            val html = requireNotNull(snapshot.html())
            val rawHtml = ScheduleHtmlParser.inspect(html, today, parish)
            val document = HtmlScheduleDocumentExtractor.fromHtml(html)
            val rawDocument = ScheduleDocumentParser.inspect(document, today, parish)
            val owner = KnownOtherScheduleVenue.fromFreshContact(parish, contactUrl, snapshot.url, approved,
                this@PublishedChildContactEvidence, curated) != null
            val result = LinkedAttachmentIndexAudit.inspect(snapshot, html, document, rawHtml, rawDocument, navigation, owner)
            val ordinary = LinkedAttachmentIndexAudit.effectiveParsed(rawHtml, rawDocument)
            effective = LinkedAttachmentIndexAudit.frozen(if (result.disposition == LinkedAttachmentDisposition.OBSERVED_UNCLASSIFIED_SAFETY)
                ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true) else ordinary)
            publishedLinks = ScheduleDocumentLinks.find(html).map { it.copy() }
            success = result.disposition == LinkedAttachmentDisposition.PROVED_LINKED_ATTACHMENT_INDEX
            return LinkedAttachmentRead(true, snapshot.url, sha, LinkedAttachmentIndexAudit.frozen(rawHtml),
                LinkedAttachmentIndexAudit.frozen(rawDocument), result.copy(imageUrls = result.imageUrls.toList())).also { issued = it }
        }
        override fun owns(read: LinkedAttachmentRead) = useIssuer === ISSUER && issued === read
        override fun parsedFor(read: LinkedAttachmentRead) = effective?.takeIf { owns(read) }?.let(LinkedAttachmentIndexAudit::frozen)
        override fun linksFor(read: LinkedAttachmentRead) = if (owns(read)) publishedLinks.map { it.copy() } else emptyList()
        override fun grants(read: LinkedAttachmentRead) = owns(read) && success
    }

    companion object {
        private val ISSUER = Any()
        internal fun fromFreshResponse(parish: Parish, response: ScheduleResponse, scope: String,
            candidate: ScheduleSourceCandidate): PublishedChildContactEvidence? = runCatching {
            require(candidate.provider == OwnContactCandidateProvider.PROVIDER && candidate.canConfirm(parish))
            require(response.bytes.size in 1..OwnContactCandidateProvider.MAX_BYTES &&
                Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(response.bytes.toString(Charsets.ISO_8859_1)))
            require(response.html() != null && ScheduleUrl.publicSource(response.url) == response.url &&
                CuratedPublisherScope.owns(scope, response.url))
            require(response.url == candidate.sourceUrl && response.url == candidate.evidenceUrl &&
                ScheduleSourceCandidate.sha(response.bytes) == candidate.evidenceSha256)
            val metadata = requireNotNull(candidate.ownContact)
            val parsed = PublishedChildContactParser.parse(response)
            require(!parsed.ambiguous)
            val child = parsed.children.single { it.blockKey == metadata.blockKey && it.name == candidate.name &&
                it.locality == candidate.address && it.latitude == candidate.latitude && it.longitude == candidate.longitude && it.phone == metadata.publishedPhone }
            PublishedChildContactEvidence(ScheduleEntityIdentity.fingerprint(parish), parish.id, parish.sourceUrl,
                requireNotNull(parish.latitude), requireNotNull(parish.longitude), response.url, candidate.evidenceSha256,
                scope, candidate.id, child.name, child.locality, child.latitude, child.longitude, child.blockKey, child.phone,
                response.copy(bytes = response.bytes.copyOf()))
                .takeIf { it.matchesFresh(parish, candidate) }
        }.getOrNull()
    }
}

/** Merge only fresh rows and their process capabilities; this never consults cached candidate DTOs. */
internal fun mergeFreshScheduleDiscoveries(parish: Parish, vararg results: ScheduleCandidateDiscovery): ScheduleCandidateDiscovery {
    val rows = mutableListOf<ScheduleSourceCandidate>()
    val observed = mutableListOf<String>()
    for (index in 0 until 200) for (result in results) {
        result.candidates.getOrNull(index)?.let { if (rows.size < 20 && rows.none { row -> row.id == it.id }) rows += it }
        result.observedIds.getOrNull(index)?.let { if (observed.size < 200 && it !in observed) observed += it }
    }
    val evidence = rows.mapNotNull { row -> results.firstNotNullOfOrNull {
        it.freshContactEvidence?.get(row.id)?.takeIf { proof -> proof.matchesFresh(parish, row) }
    }?.let { row.id to it } }.toMap()
    return ScheduleCandidateDiscovery(rows, observed, results.any { it.incomplete }, results.any { it.unavailable }, evidence)
}
