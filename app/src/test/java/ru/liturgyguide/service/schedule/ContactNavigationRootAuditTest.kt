package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

/** Exact primary bytes and fresh provider-issued capabilities; no output-derived safety oracle. */
class ContactNavigationRootAuditTest {
    private val f = ContactWiringFixtures
    private data class Input(val response: ScheduleResponse, val rawHtml: ScheduleParseResult,
        val document: ScheduleDocument, val rawDocument: ScheduleParseResult, val row: ScheduleSourceCandidate?,
        val proof: PublishedChildContactEvidence?)
    private suspend fun input(body: ByteArray = f.bytes("contacts.html")): Input {
        val response = f.response(bytes = body)
        val found = OwnContactCandidateProvider(ScheduleTransport { response }, LocalPublisherSeedPool(listOf(f.seed())), f.clock)
            .discover(f.selected(), emptySet())
        val row = found.candidates.singleOrNull { it.canConfirm(f.selected()) }
        val html = requireNotNull(response.html())
        val document = HtmlScheduleDocumentExtractor.fromHtml(html)
        return Input(response, ScheduleHtmlParser.inspect(html, ImageScheduleFixture.today, f.selected()), document,
            ScheduleDocumentParser.inspect(document, ImageScheduleFixture.today, f.selected()), row,
            row?.let { found.freshContactEvidence?.get(it.id) })
    }
    private fun audit(value: Input, proof: PublishedChildContactEvidence? = value.proof,
        response: ScheduleResponse = value.response, document: ScheduleDocument = value.document,
        approved: ScheduleSourceCandidate? = value.row, selected: ru.liturgyguide.model.Parish = f.selected(), root: String = f.contact) =
        if (proof == null || approved == null) ContactRootAuditResult.notApplicable("test-no-fresh-provider-witness") else
            proof.auditNavigationRoot(selected, approved, root, response, value.rawHtml, document, value.rawDocument)
    private fun mutate(block: (Document) -> Unit): ByteArray = requireNotNull(f.response().html()).also(block).outerHtml().toByteArray()
    private suspend fun blocked(body: ByteArray, requireFresh: Boolean = true): ContactRootAuditResult {
        val value = input(body)
        if (requireFresh) assertNotNull("Mutation must exercise a live applicable witness", value.proof)
        val result = audit(value)
        assertNotEquals(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT, result.disposition)
        if (requireFresh && value.rawHtml == ScheduleParseResult(ScheduleParseDisposition.REJECTED) &&
            value.rawDocument == ScheduleParseResult(ScheduleParseDisposition.REJECTED))
            assertEquals(result.reason, ContactRootAuditDisposition.OBSERVED_UNCLASSIFIED_SAFETY, result.disposition)
        return result
    }

    @Test fun entireActualContactKeepsBothRawRejectionsAndInventoriesIntactRecurringStatementWithoutGeometryOrEvents() = runBlocking {
        val value = input(); val before = value.response.bytes.copyOf(); val raw = Gson().toJson(listOf(value.rawHtml, value.rawDocument))
        assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), value.rawHtml)
        assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), value.rawDocument)
        val result = audit(value)
        assertEquals(result.reason, ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT, result.disposition)
        assertEquals(5, result.inventory.size)
        assertEquals(4, result.inventory.count { it.kind != ContactRootCardKind.PUBLISHER_CONTACT })
        val recurring = result.recurringStatements.single()
        assertEquals("rec493422718", recurring.owner)
        assertEquals("Крестильный храм блж. Ксении Петербургской.", recurring.name)
        assertEquals("По воскресным дням в 8.40 совершается литургия.", recurring.text)
        assertEquals("08:40", recurring.time)
        val card = result.inventory.single { it.owner == recurring.owner }
        assertEquals(ContactRootCardKind.UNDATED_RECURRING_DIRECTORY, card.kind); assertNull(card.latitude); assertNull(card.longitude)
        assertEquals(listOf(recurring.name, "Находится на территории Елисаветинского храма.", recurring.text), card.lines)
        assertTrue(result.inventory.single { it.kind == ContactRootCardKind.PUBLISHER_CONTACT }.lines.contains("Храм открыт Пн-Сб с 7.00 до 19.00, Вск 6.15-19.00."))
        val publisher = result.inventory.single { it.kind == ContactRootCardKind.PUBLISHER_CONTACT }
        assertEquals(listOf(ContactRootOpeningPeriod("Пн-Сб", "07:00", "19:00"), ContactRootOpeningPeriod("Вск", "06:15", "19:00")),
            publisher.contact.openingHours)
        assertEquals("4955649773", publisher.contact.phone); assertEquals("HramElisaveta@yandex.ru", publisher.contact.email)
        assertNull(card.contact.phone); assertTrue(card.contact.openingHours.isEmpty())
        assertEquals(raw, Gson().toJson(listOf(value.rawHtml, value.rawDocument)))
        assertArrayEquals(before, value.response.bytes)
        assertEquals("cfa400bf4123d60f239bce4094efac569791ec7078a49ad3b98a9a881f129309", ScheduleSourceCandidate.sha(value.response.bytes))
    }

    @Test fun noCachedDtoNullOrJsonReconstructedWitnessAndNoSecondActionMayReuseAnOldEnvelope() = runBlocking {
        val value = input(); val row = requireNotNull(value.row); val proof = requireNotNull(value.proof)
        assertEquals(ContactRootAuditDisposition.NO_APPLICABLE_FRESH_WITNESS, audit(value, proof = null).disposition)
        val reconstructed = Gson().fromJson(Gson().toJson(proof), PublishedChildContactEvidence::class.java)
        assertEquals(ContactRootAuditDisposition.NO_APPLICABLE_FRESH_WITNESS, audit(value, proof = reconstructed).disposition)
        assertEquals(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT, audit(value).disposition)
        assertFalse(proof.matchesFresh(f.selected(), row))
        assertEquals(ContactRootAuditDisposition.NO_APPLICABLE_FRESH_WITNESS, audit(value).disposition)
        assertTrue(proof.matchesApproved(f.selected(), row, f.contact)) // Same import may still use its unchanged foreign-image guard.
        val nextAction = input()
        assertNotSame(proof, nextAction.proof)
        assertEquals(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT, audit(nextAction).disposition)
    }

    @Test fun aNewActionUsesCurrentPublishedContactFieldsRatherThanOldAcceptedDtoGeometry() = runBlocking {
        val approved = requireNotNull(input().row)
        val current = input(f.bytes("contacts.html").toString(Charsets.UTF_8).replace("50'26.30''", "50'26.31''").toByteArray())
        assertNotNull(current.proof); assertEquals(approved.id, current.row?.id)
        assertNotEquals(approved.evidenceSha256, current.row?.evidenceSha256)
        assertNotEquals(approved.latitude.toBits(), requireNotNull(current.row).latitude.toBits())
        assertEquals(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT, audit(current, approved = approved).disposition)
    }

    @Test fun changedBytesMimeUrlSelectedOrCandidateCannotBorrowTheExactCurrentResponseCapability() = runBlocking {
        for (change in listOf<(Input) -> ContactRootAuditResult>(
            { value -> audit(value, response = value.response.copy(bytes = value.response.bytes + " ".toByteArray())) },
            { value -> audit(value, response = value.response.copy(contentType = "application/json")) },
            { value -> audit(value, response = value.response.copy(contentType = "text/html;charset=windows-1251")) },
            { value -> audit(value, response = value.response.copy(url = f.home + "schedule")) },
            { value -> audit(value, root = f.home + "contacts/") },
            { value -> audit(value, selected = f.selected().copy(latitude = f.selected().latitude!! + .00001)) },
            { value -> audit(value, approved = requireNotNull(value.row).copy(sourceUrl = f.home + "other-contact")) },
            { value -> audit(value, approved = requireNotNull(value.row).copy(name = "Храм Казанской иконы Божией Матери")) }
        )) assertEquals(ContactRootAuditDisposition.NO_APPLICABLE_FRESH_WITNESS, change(input()).disposition)
    }

    @Test fun duplicateNestedMovedHiddenOrFooterCardsCannotGetANavigationPermit() = runBlocking {
        val mutations = listOf<(Document) -> Unit>(
            { page -> val rec = page.getElementById("rec493422718")!!; rec.after(rec.outerHtml()) },
            { page -> page.select(".t195__text")[1].appendChild(page.select(".t195__text").last()!!.clone()) },
            { page -> val card = page.getElementById("rec493422226")!!; page.getElementById("rec493422471")!!.after(card) },
            { page -> page.getElementById("rec494151077")!!.before(page.getElementById("rec493422226")!!) },
            { page -> page.getElementById("rec493422226")!!.after(page.getElementById("rec493422718")!!) },
            { page -> page.getElementById("rec493422718")!!.attr("hidden", "") },
            { page -> page.select("footer").single().appendChild(page.getElementById("rec493422718")!!) },
            { page -> page.select(".t195__text")[1].appendChild(page.select(".t195__text").last()!!.select("span").last()!!) }
        )
        for (mutation in mutations) blocked(mutate(mutation))
        blocked(mutate { page -> val selected = page.getElementById("rec493422226")!!; selected.after(selected.outerHtml()) }, requireFresh = false)
        // Fresh evidence still exists for nonselected mutations; the audit must reject them independently.
        val hidden = input(mutate { it.getElementById("rec493422718")!!.attr("style", "display:none") })
        assertNotNull(hidden.proof)
        assertEquals(ContactRootAuditDisposition.OBSERVED_UNCLASSIFIED_SAFETY, audit(hidden).disposition)
    }

    @Test fun wrongChildOrGenericRecurringNameCannotBecomeTypedMetadata() = runBlocking<Unit> {
        blocked(mutate { page -> page.getElementById("rec493422718")!!.select("span").first()!!.text("Крестильный храм") })
        blocked(mutate { page -> page.getElementById("rec493422226")!!.select("span").single {
            it.text().startsWith("Храм в честь") }.text("Храм в честь иконы Божией Матери Казанская") }, requireFresh = false)
    }

    @Test fun fullOpeningAndRecurringLinesRejectPrefixTailsSecondClocksServicesAndOtherWeeklyClaims() = runBlocking {
        for (tail in listOf(" Еще в 09:00", " После этого исповедь", " 15 ноября", " 2026", " Панихида")) {
            blocked(mutate { page -> val span = page.getElementById("rec493422718")!!.select("span").last()!!; span.appendText(tail) })
            blocked(mutate { page -> val span = page.select(".t004 .t-text span").last()!!; span.appendText(tail) })
        }
        for (whole in listOf("По субботним дням в 8.40 совершается литургия.",
            "По воскресным дням в 8.40 совершается литургия. В 9.00 молебен.",
            "По воскресным дням в 24.40 совершается литургия.",
            "По воскресным дням в 8.60 совершается литургия.")) {
            blocked(mutate { it.getElementById("rec493422718")!!.select("span").last()!!.text(whole) })
        }
    }

    @Test fun completePhoneAndGpsGrammarsDoNotSwallowAClockOrServiceFragment() = runBlocking {
        for (tail in listOf(" 09:30", " Исповедь", "; молебен")) {
            blocked(mutate { page -> page.getElementById("rec493422226")!!.select("span").single {
                it.text().startsWith("Телефон ") }.appendText(tail) }, requireFresh = tail.none(Char::isDigit))
            blocked(mutate { page -> page.getElementById("rec493422471")!!.select("em").last()!!.appendText(tail) })
            blocked(mutate { page -> page.select("footer .t345-text").single().appendText(tail) })
        }
    }

    @Test fun datesCalendarTableTimeAndUnknownServiceMaterialNeverReceiveNavigationRole() = runBlocking {
        blocked(mutate { page -> page.select(".t004 .t-text span").single {
            it.text().startsWith("На автомобиле:") }.text("На автомобиле: по Литургия шоссе до указателя \"Станция Опалиха\", далее по главной дороге.") })
        for (html in listOf("<p>15 ноября 2026</p>", "<table><tr><td>08:00 Литургия</td></tr></table>",
            "<div data-day='2026-11-14'>Литургия</div>", "<time datetime='2026-11-14T08:00'>08:00</time>",
            "<p>Молебен по окончании службы</p>", "<p>Следующее богослужение уточняется</p>",
            "<script type='application/json'>{\"schedule\":\"unknown\"}</script>",
            "<script>const schedule='08:00';</script>", "<script>const value='08:00';</script>",
            "<style>.x::after{content:'08:00'}</style>", "<script>const day='2026-11-14'; const start='08:00';</script>",
            "<div data-calendar='unknown'></div>")) {
            blocked(mutate { it.body().append(html) })
        }
    }

    @Test fun cancellationAcrossTheFullRawSemanticBodyIsCheckedBeforeMetadataOrDecoration() = runBlocking {
        for (where in listOf("header", "footer", "#rec493422226", "#rec493422718")) {
            blocked(mutate { it.select(where).first()!!.append("<p>Богослужения отменены</p>") })
        }
        for (html in listOf("<div hidden>Литургия отменена</div>", "<script type='text/plain'>Все богослужения отменены</script>",
            "<!-- Литургия перенесена -->", "<div data-note='Литургия отменена'></div>",
            "<p><span>Все богослужения </span><span>не будут совершаться</span></p>",
            "<p>Литургия от\u200bменена</p>", "<s>Литургия</s>")) blocked(mutate { it.body().append(html) })
        for (selector in listOf(".t004 .t-text span", "#rec493422718 span")) {
            blocked(mutate { page -> page.select(selector).last()!!.appendText(" Все богослужения отменены") })
        }
    }

    @Test fun everyCapAndTruncatedConflictingDocumentRefusesPermitWithoutCheckingOnlyAPrefix() = runBlocking {
        val crowded = mutate { page -> repeat(ContactNavigationRootAudit.MAX_NODES + 1) { page.body().appendElement("span") } }
        val nodeResult = blocked(crowded)
        assertEquals("node-cap", nodeResult.reason)
        val deep = blocked(mutate { page -> page.body().append("<div>".repeat(ContactNavigationRootAudit.MAX_DEPTH + 1) +
            "Контакты" + "</div>".repeat(ContactNavigationRootAudit.MAX_DEPTH + 1)) })
        assertEquals("depth-cap", deep.reason)
        for (documentChange in listOf<(ScheduleDocument) -> ScheduleDocument>(
            { it.copy(pages = listOf(it.pages.single().copy(sourceTruncated = true))) },
            { it.copy(pages = listOf(it.pages.single().copy(ocrFailure = ScheduleOcrFailure.CONFLICTING_EVIDENCE))) },
            { it.copy(pages = listOf(it.pages.single().copy(rawOcrEvidence = listOf("Все богослужения отменены")))) },
            { it.copy(pages = listOf(it.pages.single().copy(lines = List(ContactNavigationRootAudit.MAX_LINES + 1) { ScheduleDocumentLine("Контакты") }))) }
        )) {
            val value = input()
            assertEquals(ContactRootAuditDisposition.OBSERVED_UNCLASSIFIED_SAFETY, audit(value, document = documentChange(value.document)).disposition)
        }
        val incomplete = input(f.bytes("contacts.html").dropLast(10).toByteArray())
        assertNull(incomplete.proof); assertEquals(ContactRootAuditDisposition.NO_APPLICABLE_FRESH_WITNESS, audit(incomplete).disposition)
        val overBytes = input(f.bytes("contacts.html") + ByteArray(OwnContactCandidateProvider.MAX_BYTES) { 32 })
        assertNull(overBytes.proof); assertEquals(ContactRootAuditDisposition.NO_APPLICABLE_FRESH_WITNESS, audit(overBytes).disposition)
    }

    @Test fun cssEscapedCancellationAndTemporalValuesInEveryAttributeRefuseFreshNavigation() = runBlocking {
        val css = """<style>body::before{content:"\41e\442\43c\435\43d\430\20\432\441\435\445\20\431\43e\433\43e\441\43b\443\436\435\43d\438\439"}</style>"""
        val mutations = listOf<(Document) -> Unit>(
            { it.head().append(css) },
            { it.head().append("<style>body::before{content:'Notice'}</style>") },
            { it.head().append("<script>const value=String.fromCharCode(1054,1090,1084,1077,1085,1072);</script>") },
            { it.head().append("<meta name='description' content='14 ноября 2026 08:00'>") },
            { it.select("[data-tooltip-hook]").first()!!.attr("data-tooltip-hook", "14 ноября 2026 08:00") },
            { it.select("[data-tooltip-hook]").first()!!.attr("data-tooltip-hook", "08:00") },
            { it.head().append("<meta name='description' content='2026-11-14'>") },
            { it.select("svg").first()!!.attr("xmlns", "https://example.org/2026-11-14") },
            { it.select("svg").first()!!.attr("xmlns", "http://www.w3.org/2000/svg 08:00") },
            { it.body().attr("aria-description", "14 ноября 2026 08:00") },
            { it.body().attr("lang", "08:00") },
            { it.head().append("<meta name='description' content='data:text/plain;base64,MDg6MDA='>") },
            { it.body().attr("style", "--notice:'\\41e\\442\\43c\\435\\43d\\430'") }
        )
        for (mutation in mutations) {
            val value = input(mutate(mutation))
            assertNotNull("Each CR-1 mutation must have a capability for those exact changed bytes", value.proof)
            assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), value.rawHtml)
            assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), value.rawDocument)
            assertEquals(ContactRootAuditDisposition.OBSERVED_UNCLASSIFIED_SAFETY, audit(value).disposition)
        }
    }

    @Test fun unknownWholeScriptsAndEventHandlersCannotHideEncodedCancellation() = runBlocking {
        val encoded = "String[\"fromCharCode\"](1054,1090,1084,1077,1085,1072,32,1074,1089,1077,1093,32,1073,1086,1075,1086,1089,1083,1091,1078,1077,1085,1080,1081)"
        for (mutation in listOf<(Document) -> Unit>(
            { it.head().append("<script>document.body.append($encoded);</script>") },
            { it.head().append("<script>window.notice=1;</script>") },
            { it.head().append("<script src='https://parish.example/notice.js'></script>") },
            { it.body().append("<svg><script href='https://parish.example/notice.js'></script></svg>") },
            { it.body().append("<svg><script xlink:href='https://parish.example/notice.js'></script></svg>") },
            { it.body().append("<math><script src='https://static.tildacdn.com/js/hammer.min.js'></script></math>") },
            { it.head().append("<script href='https://parish.example/notice.js'></script>") },
            { it.body().attr("onload", "document.body.append($encoded)") }
        )) {
            val value = input(mutate(mutation))
            assertNotNull("Unknown code must be checked with the current real capability", value.proof)
            assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), value.rawHtml)
            assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), value.rawDocument)
            val result = audit(value)
            assertEquals(result.reason, ContactRootAuditDisposition.OBSERVED_UNCLASSIFIED_SAFETY, result.disposition)
            assertTrue(result.reason in setOf("unclassified-script", "unclassified-event-handler"))
        }
    }

    @Test fun recognizedScriptPrefixAndNumericDeclarationCannotPermitAdditionalCode() = runBlocking<Unit> {
        blocked(mutate { page ->
            val script = page.select("script").first { it.data().contains("window.dataLayer") }
            script.append("window.notice=1;")
        })
        blocked(mutate { it.head().append("<script>const tick=1780856472;document.body.append(tick);</script>") })
    }

    @Test fun observedNumericCssMediaFeatureAndOrdinaryNumericSourceValuesRemainAuditable() = runBlocking {
        val source = f.bytes("contacts.html").toString(Charsets.UTF_8)
        assertTrue(source.contains("@media (hover),(min-width:0\\0){"))
        val value = input(mutate { page ->
            page.body().attr("style", "opacity:0.15;width:2026px")
            page.head().append("<meta name='build-timestamp' content='1780856472'>")
            page.head().append("<style>.numeric{opacity:0.15;width:2026px}</style>")
            page.head().append("<script>const tick=1780856472;const delay=1500;</script>")
        })
        assertNotNull(value.proof)
        assertEquals(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT, audit(value).disposition)
    }

    @Test fun originalParsedEventsWithdrawalsAndSafetyResultsAreNeverConvertedToNavigation() = runBlocking {
        for (raw in listOf(ScheduleParseResult(ScheduleParseDisposition.PARSED,
            events = listOf(InternetService("2026-11-14", "08:00", "Литургия", "liturgy"))),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, withdrawnDates = setOf("2026-11-14")),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, withdrawnStarts = setOf("2026-11-14T08:00")),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, withdrawsAll = true),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, safetyConflict = true))) {
            val value = input(); val proof = requireNotNull(value.proof); val row = requireNotNull(value.row)
            assertEquals(ContactRootAuditDisposition.NO_APPLICABLE_FRESH_WITNESS,
                proof.auditNavigationRoot(f.selected(), row, f.contact, value.response, raw, value.document, value.rawDocument).disposition)
            assertEquals(raw, raw.copy())
        }
    }
}
