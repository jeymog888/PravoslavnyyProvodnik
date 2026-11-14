package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

internal object LinkedNavigationFixtures {
    val f = ContactWiringFixtures
    val today: LocalDate = LocalDate.parse("2026-11-14")
    val target = ImageScheduleFixture.parent
    val link get() = ScheduleDocumentLink(target, f.contact, false)
    fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/linked-navigation/$name")).use { it.readBytes() }
    fun response(body: ByteArray = bytes("schedule.html")) = ScheduleResponse(target, body, "text/html; charset=UTF-8")
    fun documents() = (1..3).map { Gson().fromJson(bytes("image-$it.document.json").toString(Charsets.UTF_8), ScheduleDocument::class.java) }
    fun certifiedParses(): List<ScheduleParseResult> = Gson().fromJson(bytes("native-capture-oracle.json").toString(Charsets.UTF_8),
        com.google.gson.JsonObject::class.java)["parseResults"].asJsonArray.map { Gson().fromJson(it, ScheduleParseResult::class.java) }
    fun binding(row: ScheduleSourceCandidate) = ScheduleEntityIdentity.bind(f.selected(), f.contact, f.contact, row.name,
        ScheduleBindingMethod.USER_CONFIRMED).copy(candidateConfirmation = ScheduleCandidateConfirmation(1,
        ScheduleCandidateConfirmation.TYPE, ScheduleEntityIdentity.fingerprint(f.selected()), row, f.now.toString()))
    data class Issued(val proof: PublishedChildContactEvidence, val row: ScheduleSourceCandidate, val binding: ScheduleSiteBinding,
        val result: ContactRootAuditResult, val attempt: Any, val permit: LinkedAttachmentNavigationPermit)
    suspend fun issue(contact: ByteArray = f.bytes("contacts.html")): Issued {
        val response = f.response(bytes = contact)
        val found = OwnContactCandidateProvider(ScheduleTransport { response }, LocalPublisherSeedPool(listOf(f.seed())), f.clock)
            .discover(f.selected(), emptySet())
        val row = found.candidates.single { it.canConfirm(f.selected()) }
        val proof = requireNotNull(found.freshContactEvidence?.get(row.id))
        val page = requireNotNull(response.html()); val document = HtmlScheduleDocumentExtractor.fromHtml(page)
        val attempt = Any()
        val result = proof.auditNavigationRoot(f.selected(), row, f.contact, response,
            ScheduleHtmlParser.inspect(page, today, f.selected()), document,
            ScheduleDocumentParser.inspect(document, today, f.selected()), attempt)
        assertEquals(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT, result.disposition)
        assertEquals(target, result.checkedScheduleTarget)
        val permit = requireNotNull(proof.takeLinkedNavigation(result, attempt))
        return Issued(proof, row, binding(row), result, attempt, permit)
    }
    fun use(issued: Issued) = requireNotNull(issued.permit.reserve(f.selected(), f.contact, issued.binding, link, false, issued.attempt))
    fun mutate(change: (Document) -> Unit) = requireNotNull(response().html()).also {
        it.outputSettings().prettyPrint(false); change(it)
    }.outerHtml().toByteArray()
    val nextUrls = listOf("https://media.publisher.example/next/first.jpg", "https://media.publisher.example/next/second.jpg", "https://media.publisher.example/next/third.jpg")
    // Independent fixture construction: the three whole published tuples and first og derivative change together.
    fun monthly(): ByteArray = mutate { page ->
        page.select(".t107").forEachIndexed { index, record ->
            val u = nextUrls[index]; val parent = u.substringBeforeLast('/'); val file = u.substringAfterLast('/')
            record.select("meta[itemprop=image]").single().attr("content", u)
            record.select("img").single().attr("data-original", u).attr("data-img-zoom-url", u)
                .attr("src", "$parent/-/empty/$file")
        }
        val first = nextUrls.first()
        page.select("meta[property=og:image]").single().attr("content", first.substringBeforeLast('/') + "/-/resize/504x/" + first.substringAfterLast('/'))
    }
}

class LinkedAttachmentNavigationTest {
    private val x = LinkedNavigationFixtures
    private val f = x.f
    private suspend fun refused(body: ByteArray, beforeExtraction: Boolean = false) {
        val issued = x.issue(); val use = x.use(issued)
        val result = use.read(x.response(body), x.today, f.selected(), issued.binding, f.owners, issued.attempt)
        assertTrue(use.owns(result)); assertFalse(use.grants(result))
        if (beforeExtraction) { assertNull(result.htmlResult); assertNull(result.documentResult) }
        val actualSafety = listOfNotNull(result.htmlResult, result.documentResult).any(LinkedAttachmentIndexAudit::hasSafety)
        assertEquals(if (actualSafety) LinkedAttachmentDisposition.NO_NAVIGATION_REPLACEMENT else
            LinkedAttachmentDisposition.OBSERVED_UNCLASSIFIED_SAFETY, result.audit?.disposition)
        assertTrue(LinkedAttachmentIndexAudit.hasSafety(requireNotNull(use.parsedFor(result))))
    }

    @Test fun originalWholeSourceKeepsBothActualRawRejectionsAndGrantsOnlyTheExactThreeLinkedAttachments() = runBlocking {
        val issued = x.issue(); val use = x.use(issued); val response = x.response(); val before = response.bytes.copyOf()
        val result = use.read(response, x.today, f.selected(), issued.binding, f.owners, issued.attempt)
        assertTrue(use.owns(result)); assertTrue(use.grants(result))
        assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), result.htmlResult)
        assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), result.documentResult)
        assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), use.parsedFor(result))
        assertEquals("8cf4d0a03195b2ae36256082a4c2168b84132f0f1f11ed7a608c7e7307873c0d", result.sourceSha256)
        assertEquals(x.documents().map { it.sourceUrl }, result.audit?.imageUrls)
        assertEquals(listOf(ScheduleDocumentLink(x.target, x.target, false)) + x.documents().map {
            ScheduleDocumentLink(it.sourceUrl, x.target, true) }, use.linksFor(result))
        assertArrayEquals(before, response.bytes)
        val known = requireNotNull(KnownOtherScheduleVenue.fromFreshContact(f.selected(), f.contact, x.target,
            issued.row, issued.proof, f.owners))
        val actualImages = x.documents().map { document -> ScheduleForeignImage.certify(document, x.today, f.selected(),
            f.contact, x.target, known, ScheduleDocumentParser.inspect(document, x.today, f.selected())) }
        assertEquals(x.certifiedParses(), actualImages)
        assertEquals(12, actualImages.last().events.size)
        assertNotEquals(OwnContactChildIdentity.phone(f.owners.single { it.id == "elisavetinsky_opalikha" }.phone), "4955649773")
    }

    @Test fun freshMonthlyTuplesAndTheirOgDerivativeWorkWithoutProductionUrlChanges() = runBlocking {
        val issued = x.issue(); val use = x.use(issued)
        val result = use.read(x.response(x.monthly()), x.today, f.selected(), issued.binding, f.owners, issued.attempt)
        assertTrue(use.grants(result)); assertEquals(x.nextUrls, result.audit?.imageUrls)
        assertEquals(x.nextUrls, use.linksFor(result).filter { it.document }.map { it.url })
        assertTrue(use.linksFor(result).all { it.linkedFrom == x.target })
    }

    @Test fun rootReceiptOrJsonIsNeverAnIssuerAndPrivateUseDoesNotTrustCopiedReceipts() = runBlocking {
        val fresh = f.fresh(); val row = fresh.candidates.single(); val proof = requireNotNull(fresh.freshContactEvidence?.get(row.id))
        assertNull(proof.takeLinkedNavigation(ContactRootAuditResult(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT,
            "forged", checkedScheduleTarget = x.target), Any()))
        val issued = x.issue()
        assertNull(issued.proof.takeLinkedNavigation(issued.result.copy(), issued.attempt))
        assertNull(issued.proof.takeLinkedNavigation(issued.result, issued.attempt)) // already taken once
        val reconstructed = Gson().fromJson(Gson().toJson(issued.proof), PublishedChildContactEvidence::class.java)
        assertNull(reconstructed.takeLinkedNavigation(issued.result, issued.attempt))
        val forgedPermit = runCatching { Gson().fromJson(Gson().toJson(issued.permit), issued.permit.javaClass) }.getOrNull()
        if (forgedPermit != null) assertNull(forgedPermit.reserve(f.selected(), f.contact, issued.binding, x.link, false, issued.attempt))
        val use = x.use(issued); val result = use.read(x.response(), x.today, f.selected(), issued.binding, f.owners, issued.attempt)
        assertTrue(use.grants(result)); assertFalse(use.grants(result.copy()))
        val exposedLinks = use.linksFor(result) as MutableList<ScheduleDocumentLink>
        exposedLinks.clear()
        assertEquals(4, use.linksFor(result).size)
        val dto = Gson().fromJson(Gson().toJson(result), LinkedAttachmentRead::class.java)
        assertFalse(use.owns(dto)); assertFalse(use.grants(dto)); assertNull(use.parsedFor(dto)); assertTrue(use.linksFor(dto).isEmpty())
    }

    @Test fun exactRequestIdentityBindingAttemptAndResponseCannotBeReusedOrRedirected() = runBlocking {
        val issued = x.issue()
        for (link in listOf(x.link.copy(url = x.target + "/"), x.link.copy(url = x.target + "?alias=1"),
            x.link.copy(linkedFrom = f.home), x.link.copy(document = true)))
            assertNull(issued.permit.reserve(f.selected(), f.contact, issued.binding, link, false, issued.attempt))
        assertNull(issued.permit.reserve(f.selected(), f.contact, issued.binding, x.link, true, issued.attempt))
        assertNull(issued.permit.reserve(f.selected(), f.contact, issued.binding, x.link, false, Any()))
        assertNull(issued.permit.reserve(f.selected().copy(latitude = f.selected().latitude!! + .00001), f.contact,
            issued.binding, x.link, false, issued.attempt))
        assertNull(issued.permit.reserve(f.selected(), f.contact + "/", issued.binding, x.link, false, issued.attempt))
        assertNull(issued.permit.reserve(f.selected(), f.contact, null, x.link, false, issued.attempt))
        for (method in ScheduleBindingMethod.values().filter { it != ScheduleBindingMethod.USER_CONFIRMED })
            assertNull(issued.permit.reserve(f.selected(), f.contact, issued.binding.copy(method = method), x.link, false, issued.attempt))
        val otherMetadata = requireNotNull(issued.row.ownContact).copy(blockKey = "dom:another-child")
        val otherRow = issued.row.copy(ownContact = otherMetadata, providerObjectId =
            OwnContactChildIdentity.objectId(issued.row.evidenceUrl, otherMetadata.blockKey, issued.row.name))
        assertTrue(otherRow.canConfirm(f.selected()))
        assertNotEquals(issued.row.id, otherRow.id)
        val changedCandidate = requireNotNull(issued.binding.candidateConfirmation).copy(candidate = otherRow)
        assertNull(issued.permit.reserve(f.selected(), f.contact, issued.binding.copy(candidateConfirmation = changedCandidate), x.link, false, issued.attempt))
        val use = x.use(issued)
        assertNull(issued.permit.reserve(f.selected(), f.contact, issued.binding, x.link, false, issued.attempt))
        val alias = use.read(x.response().copy(url = x.target + "/alias"), x.today, f.selected(), issued.binding, f.owners, issued.attempt)
        assertFalse(use.owns(alias)); assertFalse(use.grants(alias))
        assertFalse(use.grants(use.read(x.response(), x.today, f.selected(), issued.binding, f.owners, issued.attempt)))
        val again = x.issue(); val wrongType = x.use(again).let { next -> next.read(x.response().copy(contentType = "image/jpeg"),
            x.today, f.selected(), again.binding, f.owners, again.attempt).also { assertFalse(next.owns(it)) } }
        assertFalse(wrongType.applicable)
        val nextAttempt = x.issue(); val staleUse = x.use(nextAttempt)
        val stale = staleUse.read(x.response(), x.today, f.selected(), nextAttempt.binding, f.owners, Any())
        assertFalse(staleUse.owns(stale)); assertFalse(staleUse.grants(stale))
        assertFalse(staleUse.grants(staleUse.read(x.response(), x.today, f.selected(), nextAttempt.binding, f.owners, nextAttempt.attempt)))
    }

    @Test fun rawResultMatrixIsNeverNavigationAndDocumentSafetyWinsGenericHtmlRejection() = runBlocking {
        val page = requireNotNull(x.response().html()); val doc = HtmlScheduleDocumentExtractor.fromHtml(page)
        val owner = LinkedAttachmentIndexAudit.captureRoot(x.link, "Елисаветинский храм Одинцовская епархия",
            requireNotNull(f.response().html()).select("footer .t345-text").toList(), "4955649773")
        val empty = ScheduleParseResult(ScheduleParseDisposition.REJECTED)
        val alternatives = listOf(ScheduleParseResult(ScheduleParseDisposition.PARSED), ScheduleParseResult(ScheduleParseDisposition.UNSUPPORTED),
            empty.copy(events = listOf(InternetService("2026-11-14", "08:00", "Литургия", "liturgy"))),
            empty.copy(rejectionReason = ScheduleRejectionReason.OTHER_ENTITY_ONLY), empty.copy(safetyConflict = true),
            empty.copy(withdrawsAll = true), empty.copy(withdrawnDates = setOf("2026-11-14")),
            empty.copy(withdrawnStarts = setOf("2026-11-14T08:00")))
        for (value in alternatives) for ((html, document) in listOf(value to empty, empty to value)) {
            val result = LinkedAttachmentIndexAudit.inspect(x.response(), page, doc, html, document, owner, true)
            assertEquals(LinkedAttachmentDisposition.NO_NAVIGATION_REPLACEMENT, result.disposition)
            if (LinkedAttachmentIndexAudit.hasSafety(value)) assertEquals(value, LinkedAttachmentIndexAudit.effectiveParsed(html, document))
        }
        val left = empty.copy(withdrawnDates = setOf("2026-11-14"), withdrawnStarts = setOf("2026-11-15T08:00"))
        val right = empty.copy(safetyConflict = true, withdrawsAll = true, withdrawnDates = setOf("2026-11-22"),
            withdrawnStarts = setOf("2026-11-21T17:00"))
        for ((html, document) in listOf(left to right, right to left)) {
            val combined = LinkedAttachmentIndexAudit.effectiveParsed(html, document)
            assertTrue(combined.safetyConflict); assertTrue(combined.withdrawsAll)
            assertEquals(setOf("2026-11-14", "2026-11-22"), combined.withdrawnDates)
            assertEquals(setOf("2026-11-15T08:00", "2026-11-21T17:00"), combined.withdrawnStarts)
        }
        assertEquals(setOf("2026-11-14"), left.withdrawnDates)
        assertEquals(setOf("2026-11-22"), right.withdrawnDates)
        for (broken in listOf(doc.copy(sourceUrl = x.target + "/alias"), doc.copy(format = "image"),
            doc.copy(pages = doc.pages + doc.pages), doc.copy(pages = doc.pages.map { it.copy(sourceTruncated = true) }),
            doc.copy(pages = doc.pages.map { it.copy(ocrFailure = ScheduleOcrFailure.CONFLICTING_EVIDENCE) }),
            doc.copy(pages = doc.pages.map { it.copy(lines = it.lines + ScheduleDocumentLine("08:00")) }),
            doc.copy(pages = doc.pages.map { it.copy(lines = List(257) { ScheduleDocumentLine("extra") }) }))) {
            assertEquals(LinkedAttachmentDisposition.OBSERVED_UNCLASSIFIED_SAFETY,
                LinkedAttachmentIndexAudit.inspect(x.response(), page, broken, empty, empty, owner, true).disposition)
        }
    }

    @Test fun everyTupleDerivativeExtraSlotAndMovedOrUnlinkedImageFailsClosed() = runBlocking {
        for (change in listOf<(Document) -> Unit>(
            { it.select(".t107 img").first()!!.attr("data-original", x.nextUrls[0]) },
            { it.select(".t107 meta").first()!!.attr("content", x.nextUrls[0]) },
            { it.select(".t107 img").first()!!.attr("src", x.nextUrls[0]) },
            { it.select("meta[property=og:image]").single().attr("content", x.nextUrls[0]) },
            { it.select("meta[property=og:image]").single().after(it.select("meta[property=og:image]").single().outerHtml()) },
            { it.select(".t107 img")[1].attr("data-img-zoom-url", it.select(".t107 img").first()!!.attr("data-original")) },
            { page ->
                // A complete consistent repeated tuple must also fail, not only
                // a partially mismatched URL or duplicate record.
                val url = page.select(".t107 img").first()!!.attr("data-original")
                val second = page.select(".t107")[1]
                second.select("meta[itemprop=image]").single().attr("content", url)
                second.select("img").single().attr("data-original", url).attr("data-img-zoom-url", url)
                    .attr("src", url.substringBeforeLast('/') + "/-/empty/" + url.substringAfterLast('/'))
            },
            { it.getElementById("rec493057971")!!.after(it.getElementById("rec493057971")!!.outerHtml()) },
            { it.body().append("<a href='https://other.example/extra.jpg'>Расписание</a>") },
            { it.select(".t107 img").first()!!.attr("hidden", "") },
            { it.select("footer").single().appendChild(it.select(".t107 img").first()!!) },
            { it.select(".t107")[1].appendChild(it.select(".t107 img").first()!!) },
            { it.getElementById("rec493057783")!!.after(it.getElementById("rec493057971")!!) },
            { it.getElementById("rec493057783")!!.id("different-record") },
            { it.select(".t107 img").first()!!.attr("title", "") }
        )) refused(x.mutate(change))
    }

    @Test fun fullRawDomCodeAttributesAndNamespacesHaveNoGenericAllowlistEscape() = runBlocking {
        for (payload in listOf("<p>Литургия отменена</p>", "<p>Литургия</p>", "<p>14 ноября 2026</p>",
            "<p hidden>14 ноября 2026 08:00</p>", "<!-- 09:00 молебен -->",
            "<meta name='description' content='08:00'>", "<div></div>", "<script>const tick=1;</script>",
            "<style>body::before{content:'\\41e\\442\\43c\\435\\43d\\430'}</style>")) refused(x.mutate { it.body().append(payload) })
        val raw = x.bytes("schedule.html").toString(Charsets.UTF_8)
        refused(raw.replaceFirst("alt=\"\"", "alt=\"\" alt=\"08:40\"" ).toByteArray())
        refused(raw.replace("</body>", "</unknown></body>").toByteArray())
        for (change in listOf<(Document) -> Unit>(
            { it.select("footer").single().append("<p>Все богослужения отменены</p>") },
            { it.select("svg").first()!!.append("<text>08:00</text>") },
            { it.select("svg").first()!!.attr("xmlns", "https://other.example/namespace") },
            { it.select(".t107 img").first()!!.attr("onerror", "this.hidden=true") },
            { it.select(".t107 img").first()!!.attr("ordinary", "1") },
            { it.select("script[src]").first()!!.attr("src", "https://other.example/code.js") },
            { it.select("script:not([src])").first()!!.append(" ") },
            { it.select("style").first()!!.append(" ") },
            { it.select(".t107 img").first()!!.attr("data-extra", "1") },
            { it.select("meta[name=description]").single().attr("content", "14 ноября 2026") },
            { it.select(".t107 img").first()!!.attr("data-extra", "08:40") }
        )) refused(x.mutate(change))
    }

    @Test fun ownerSubtitlePairFooterAndIndependentCuratedOwnerRemainSeparateGates() = runBlocking {
        for (change in listOf<(Document) -> Unit>(
            { it.select(".t-uptitle").single().text("Елисаветинский храм Одинцовская епархия") },
            { it.select("footer .t345-text").single().prepend("Новый владелец ") },
            { val footer = it.select("footer .t345-text").single(); footer.html(footer.html().replace("Пушкина", "Ленина")) },
            { it.select("footer a[href^=tel:]").single().text("8(495)111-11-11") },
            { it.select("footer .t345-text a[href^=http]").single().attr("href", "https://other.example/") }
        )) refused(x.mutate(change))
        for (change in listOf<(Document) -> Unit>(
            { it.select(".t-uptitle").single().text("Никольский храм Одинцовская епархия") },
            { it.select("footer a[href^=tel:]").single().text("8(495)111-11-11") }
        )) {
            val contact = requireNotNull(f.response().html()).also { it.outputSettings().prettyPrint(false); change(it) }.outerHtml().toByteArray()
            val issued = x.issue(contact); val use = x.use(issued)
            val result = use.read(x.response(), x.today, f.selected(), issued.binding, f.owners, issued.attempt)
            assertFalse(use.grants(result)); assertTrue(requireNotNull(use.parsedFor(result)).safetyConflict)
        }
        val owner = f.owners.single { it.id == "elisavetinsky_opalikha" }
        for (owners in listOf(f.owners + owner.copy(id = "duplicate-owner"), f.owners.map {
            if (it.id == owner.id) it.copy(latitude = f.selected().latitude, longitude = f.selected().longitude) else it
        }, f.owners.map { if (it.id == owner.id) it.copy(id = f.selected().id) else it })) {
            val issued = x.issue(); val use = x.use(issued)
            val read = use.read(x.response(), x.today, f.selected(), issued.binding, owners, issued.attempt)
            assertTrue(use.owns(read)); assertFalse(use.grants(read)); assertTrue(requireNotNull(use.parsedFor(read)).safetyConflict)
        }
    }

    @Test fun exactEligibleCapsAndIncompleteResponseStopBeforeOrdinaryRecursiveExtraction() = runBlocking {
        val source = x.bytes("schedule.html").toString(Charsets.UTF_8)
        for (tail in listOf("<div>".repeat(8_192) + "x" + "</div>".repeat(8_192),
            "<span></span>".repeat(1_601), "<!--" + "x".repeat(262_144) + "-->"))
            refused(source.replace("</body>", "$tail</body>").toByteArray(), beforeExtraction = true)
        refused(source.replace("</body>", "<!--" + "\n".repeat(1_501) + "--></body>").toByteArray(), beforeExtraction = true)
        refused(source.replace("</body>", "&#x41e;&#x442;&#x43c;&#x435;&#x43d;&#x430;</body>").toByteArray())
        refused(source.replace("</body>", "<span>\u200b</span></body>").toByteArray())
        refused(x.bytes("schedule.html").dropLast(12).toByteArray(), beforeExtraction = true)
        refused(source.substringBefore("</body>").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) +
            ("</body>" + source.substringAfter("</body>")).toByteArray(), beforeExtraction = true)
    }
}
