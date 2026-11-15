package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Exact retained four bodies + independently reviewed rows. Bootstrap/transport/cache are unit controls. */
class QualifiedPublisherBannerTest {
    private val gson = Gson()
    private val oracle = JsonParser.parseString(resource("oracle.json").toString(Charsets.UTF_8)).asJsonObject
    private val parish = gson.fromJson(oracle.get("parish"), Parish::class.java)
    private val candidate = gson.fromJson(oracle.get("candidate"), ScheduleSourceCandidate::class.java)
    private val now = Instant.parse(candidate.checkedAt).plusSeconds(1)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val today = now.atZone(ZoneId.of(parish.timezone)).toLocalDate()
    private val root = oracle.getAsJsonObject("sourceUrls").get("root").asString
    private val schedule = oracle.getAsJsonObject("sourceUrls").get("schedule").asString
    private val source = candidate.sourceUrl
    private val originalRoot = resource("own-root.html")
    private val originalLeaf = resource("schedule.html")
    private val expected = oracle.getAsJsonArray("events").map { gson.fromJson(it, InternetService::class.java) }
    private val link = ScheduleDocumentLink(schedule, root, false)

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/publisher-banner/$name"))
        .use { it.readBytes() }
    private fun response(bytes: ByteArray, url: String = root, type: String = "text/html; charset=UTF-8") =
        ScheduleResponse(url, bytes, type)
    private fun binding(p: Parish = parish, row: ScheduleSourceCandidate = candidate): ScheduleSiteBinding {
        val confirmedAt = maxOf(now, Instant.parse(row.checkedAt)).toString()
        return ScheduleEntityIdentity.bind(p, row.sourceUrl, row.evidenceUrl, row.name, ScheduleBindingMethod.USER_CONFIRMED)
            .copy(candidateConfirmation = ScheduleCandidateConfirmation(1, ScheduleCandidateConfirmation.TYPE,
                ScheduleEntityIdentity.fingerprint(p), row, confirmedAt))
    }
    private fun context(bytes: ByteArray = originalRoot, p: Parish = parish, row: ScheduleSourceCandidate? = candidate,
        accepted: ScheduleSiteBinding? = binding(p, row ?: candidate)) =
        QualifiedPublisherBanner.fromRoot(p, accepted, row, source, response(bytes))
    private fun raw(bytes: ByteArray = originalLeaf, url: String = schedule) =
        ScheduleHtmlParser.inspect(requireNotNull(response(bytes, url).html()), today, parish)
    private fun parsed(bytes: ByteArray = originalLeaf, accepted: ScheduleSiteBinding = binding()) =
        requireNotNull(context()).inspect(parish, accepted, link, response(bytes, schedule), raw(bytes), today)
    private fun html(bytes: ByteArray = originalRoot, url: String = root, change: (Document) -> Unit): ByteArray =
        Jsoup.parse(bytes.toString(Charsets.UTF_8), url).also {
            it.outputSettings().prettyPrint(false); change(it)
        }.outerHtml().toByteArray(Charsets.UTF_8)
    private fun banner(page: Document) = page.select("#site-introduction > h1").single()
    private fun setTitle(bytes: ByteArray, title: (String) -> String): ByteArray = html(bytes) {
        banner(it).select("a").single().let { anchor -> anchor.text(title(anchor.text())) }
    }
    private fun literal(bytes: ByteArray = originalRoot, change: (JsonObject) -> Unit): ByteArray = html(bytes) { page ->
        val script = page.select("script").single { "ortoxParentSite =" in it.data() }
        val capture = requireNotNull(Regex("jQuery\\.parseJSON\\('([^'\\r\\n]*)'\\)").find(script.data()))
        val tree = JsonParser.parseString(capture.groupValues[1]).asJsonArray
        change(tree.single().asJsonObject)
        script.html(script.data().replaceRange(capture.groups[1]!!.range, gson.toJson(tree)))
    }
    private fun literalText(bytes: ByteArray = originalRoot, change: (String) -> String): ByteArray = html(bytes) { page ->
        val script = page.select("script").single { "ortoxParentSite =" in it.data() }
        script.html(change(script.data()))
    }
    private fun safety(result: ScheduleParseResult) = result.safetyConflict || result.unresolvedWithdrawal ||
        result.withdrawsAll || result.withdrawnDates.isNotEmpty() || result.withdrawnStarts.isNotEmpty()

    private class Cache : ScheduleCacheStore {
        val values = mutableMapOf<String, String>()
        val safety = mutableListOf<String>()
        override fun read(parishId: String) = values[parishId]
        override fun write(parishId: String, value: String) { values[parishId] = value }
        override fun writeSafety(parishId: String, value: String) { safety += value; write(parishId, value) }
    }
    private inner class Network : ScheduleTransport {
        val calls = mutableListOf<String>()
        var failMap = false
        var failAll = false
        var rootBytes = originalRoot
        var leafBytes = originalLeaf
        var before: suspend (String) -> Unit = {}
        override suspend fun fetch(url: String): ScheduleResponse {
            calls += url; before(url)
            if (failAll) throw IOException("Controlled unit offline")
            return when {
                url == OfficialMapCandidateProvider.HOME -> response(
                    "<html><body><script src='/unit/ymap-filters-box.js'></script></body></html>".toByteArray(), url)
                url == OfficialMapCandidateProvider.HOME + "unit/ymap-filters-box.js" -> response(
                    "token=LOCAL_WIDGET_REPLAY_060&country=".toByteArray(), url, "text/javascript")
                url.startsWith(OfficialMapCandidateProvider.API + "?") -> {
                    if (failMap) throw IOException("Controlled fresh evidence failure")
                    if ("m=info" in url) response(resource("official-detail.html"), url)
                    else response(resource("official-feature.json"), url, "application/json")
                }
                url == source || url == root -> response(rootBytes)
                url == schedule -> response(leafBytes, schedule)
                else -> throw IOException("Unmapped controlled fixture request")
            }
        }
    }
    private fun repository(cache: Cache, network: Network): InternetScheduleRepository {
        val provider = OfficialMapCandidateProvider(network, clock)
        return InternetScheduleRepository(network, cache,
            siteResolver = OpenScheduleSiteResolver(network, candidateProvider = provider, clock = clock))
    }
    private suspend fun seed(cache: Cache, network: Network): ScheduleSourceCandidate {
        // Controlled pre-review UI state. The confirmation below fetches the actual provider again.
        val discovery = OfficialMapCandidateProvider(network, clock).discover(parish, emptySet())
        assertFalse(discovery.unavailable)
        val row = discovery.candidates.single { it.id == candidate.id }
        assertTrue(row.canConfirm(parish))
        cache.write(parish.id, ScheduleCacheCodec.encode(InternetScheduleState(parish.id,
            status = InternetScheduleStatus.NO_WEBSITE, checkedAt = now.toString(), sourceCandidates = discovery.candidates,
            candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(parish))))
        return row
    }

    @Test fun exactBodiesAndPhysicalRowsArePinnedAndRawRejectionRemainsVisible() {
        for ((name, sha) in oracle.getAsJsonObject("fixtureSha256").entrySet())
            assertEquals(name, sha.asString, ScheduleSourceCandidate.sha(resource(name)))
        val rootBefore = originalRoot.copyOf(); val leafBefore = originalLeaf.copyOf()
        assertEquals(ScheduleParseDisposition.REJECTED, raw(originalRoot, root).disposition)
        assertEquals(ScheduleParseDisposition.REJECTED, raw().disposition)
        assertTrue(raw().events.isEmpty()); assertFalse(safety(raw()))
        assertFalse(raw().yearInferred)
        val got = requireNotNull(parsed())
        assertEquals(ScheduleParseDisposition.PARSED, got.disposition)
        assertEquals(expected, got.events); assertEquals(9, got.events.size); assertFalse(got.yearInferred)
        // Two capture-day rows are already past. They are source rows, not nine future services.
        assertEquals(2, got.events.count { it.date == today.toString() })
        assertArrayEquals(rootBefore, originalRoot); assertArrayEquals(leafBefore, originalLeaf)
    }

    @Test fun actualProviderConfirmationRecheckAndOfflineCacheKeepSourceAndUserAuthority() = runBlocking {
        val cache = Cache(); val network = Network(); val row = seed(cache, network)
        val beforeConfirmation = network.calls.count { it.startsWith(OfficialMapCandidateProvider.API) }
        val repo = repository(cache, network)
        val ready = repo.confirmCandidate(parish, row.id, now)
        assertEquals(beforeConfirmation + 2, network.calls.count { it.startsWith(OfficialMapCandidateProvider.API) })
        assertEquals(InternetScheduleStatus.READY, ready.status)
        val publication = requireNotNull(ready.publication)
        assertEquals(expected, publication.events); assertEquals(schedule, publication.sourceUrl)
        assertEquals(root, publication.website); assertEquals(root, publication.linkedFrom)
        assertFalse(publication.yearInferred)
        assertEquals(ScheduleBindingMethod.USER_CONFIRMED, ready.siteBinding?.method)
        assertEquals(candidate.id, ready.siteBinding?.candidateConfirmation?.candidate?.id)
        assertEquals(source, ready.siteBinding?.siteUrl)
        assertEquals(ready, repository(cache, Network()).cached(parish))
        val beforeRefresh = network.calls.count { it.startsWith(OfficialMapCandidateProvider.API) }
        val refreshed = repo.refresh(parish, now.plusSeconds(30), force = true)
        assertEquals(beforeRefresh + 2, network.calls.count { it.startsWith(OfficialMapCandidateProvider.API) })
        assertEquals(expected, refreshed.publication?.events)
        val rootFetches = network.calls.count { it == source || it == root }
        network.failMap = true
        val offline = repo.refresh(parish, now.plusSeconds(60), force = true)
        assertEquals(rootFetches, network.calls.count { it == source || it == root })
        assertEquals(refreshed.publication, offline.publication)
        assertEquals(refreshed.siteBinding, offline.siteBinding)
        assertEquals(offline, repository(cache, Network().apply { failAll = true }).cached(parish))
    }

    @Test fun cachedAgeAloneCannotCreateContextAndChangedBindingOrIdentityCannotReuseIt() {
        assertNull(context(row = null))
        assertNull(context(row = candidate.copy(verificationUnavailable = true)))
        assertNull(context(accepted = binding().copy(method = ScheduleBindingMethod.ARTICLE_SCOPE, candidateConfirmation = null)))
        val context = requireNotNull(context())
        val changed = parish.copy(address = "Иная улица, 77")
        assertNull(context.inspect(changed, binding(changed, candidate.copy(selectedIdentity = ScheduleEntityIdentity.fingerprint(changed))),
            link, response(originalLeaf, schedule), raw(), today))
        assertNull(parsed(accepted = binding().copy(siteUrl = "https://different.example/")))
        assertNull(context.inspect(parish, binding(), link, response(originalLeaf, schedule),
            ScheduleParseResult(ScheduleParseDisposition.UNSUPPORTED), today))
        assertNull(context.inspect(parish, binding(), link, response(originalLeaf, schedule),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, rejectionReason = ScheduleRejectionReason.OTHER_ENTITY_ONLY), today))
    }

    @Test fun ownLiteralMustHaveUniqueTypedExactIdNameAndSelectedPoint() {
        val mutations = listOf<Pair<String, (JsonObject) -> Unit>>(
            "wrong id" to { it.addProperty("id", "999999") },
            "numeric id" to { it.addProperty("id", 31383) },
            "wrong type" to { it.addProperty("type", "3") },
            "numeric type" to { it.addProperty("type", 40) },
            "wrong name" to { it.addProperty("ti", "Храм другого святого д. Афанасово") },
            "numeric latitude" to { it.addProperty("la", 55.9580025) },
            "wrong point" to { it.addProperty("lo", "37.595") },
            "nonfinite" to { it.addProperty("la", "NaN") },
            "missing" to { it.remove("id") })
        for ((label, mutate) in mutations) assertNull(label, context(literal(change = mutate)))
        assertNull("duplicate JSON key", context(literalText { it.replace("\"id\":\"31383\"", "\"id\":\"31383\",\"id\":\"31383\"") }))
        assertNull("second assignment", context(html { it.body().append("<script>ortoxParentSite = [];</script>") }))
        assertNull("second own block", context(html { it.select(".reg-info").single().after(it.select(".reg-info").single().outerHtml()) }))
        for (selector in listOf(".reg-info", "#eparchy-info", ".reg-info-contact"))
            assertNull("hidden proof $selector", context(html { it.select(selector).single().attr("style", "display:none") }))
        val far = candidate.copy(latitude = requireNotNull(parish.latitude) + .002)
        assertTrue("still inside existing 650m candidate bound", far.canConfirm(parish))
        assertFalse(OwnContactChildIdentity.pointAgrees(parish, far.latitude, far.longitude))
        assertNull("new 150m corroboration veto", context(literal { it.addProperty("la", far.latitude.toString()) }, row = far))
    }

    @Test fun selectedCityStreetScopeAndAllQualifiersRemainVetoes() {
        val city = parish.copy(city = "Москва")
        val cityRow = candidate.copy(selectedIdentity = ScheduleEntityIdentity.fingerprint(city))
        assertTrue(cityRow.canConfirm(city)); assertNull(context(p = city, row = cityRow))
        val street = candidate.copy(address = "Другая улица, 77")
        val wanted = requireNotNull(PalomnikStreetAddress.parse(parish.address))
        val published = requireNotNull(PalomnikStreetAddress.parse(street.address))
        assertTrue(MoscowChurchIdentity.definitelyOtherAddress(wanted, published))
        // The common candidate validator must reject this before the helper's later gate.
        assertFalse(street.validFor(parish)); assertFalse(street.canConfirm(parish))
        assertNull(context(row = street))
        val matchingStreet = candidate.copy(address = parish.address)
        assertTrue(matchingStreet.validFor(parish)); assertTrue(matchingStreet.canConfirm(parish))
        assertNotNull(context(row = matchingStreet))
        assertTrue(candidate.canConfirm(parish)); assertNotNull(context())
        for (name in listOf("Часовня Сретения Господня", "Монастырь Сретения Господня", "Нижняя церковь Сретения Господня")) {
            val selected = parish.copy(name = name)
            assertNull(name, context(p = selected, row = candidate.copy(selectedIdentity = ScheduleEntityIdentity.fingerprint(selected))))
        }
        val added = candidate.copy(name = candidate.name.replace("Храм ", "Храм церковь "))
        // The existing NameKey omits both building words; the new gate must not collapse them.
        assertTrue(OwnContactChildIdentity.sameName(parish.name, added.name.substringBefore(" д. ")))
        var duplicate = literal { it.addProperty("ti", added.name) }
        duplicate = html(duplicate) { it.select("#eparchy-info .t1").single().text(added.name) }
        duplicate = setTitle(duplicate) { it.replace("Храм ", "Храм церковь ") }
        assertNull("additional omitted building noun", context(duplicate, row = added))
        val punctuated = candidate.copy(name = candidate.name.replace("Храм ", "Храм (церковь) "))
        assertTrue("punctuation variant passes the existing candidate policy", punctuated.canConfirm(parish))
        assertTrue("existing NameKey omits the punctuation-wrapped noun",
            OwnContactChildIdentity.sameName(parish.name, punctuated.name.substringBefore(" д. ")))
        var punctuationDuplicate = literal { it.addProperty("ti", punctuated.name) }
        punctuationDuplicate = html(punctuationDuplicate) { it.select("#eparchy-info .t1").single().text(punctuated.name) }
        punctuationDuplicate = setTitle(punctuationDuplicate) { it.replace("Храм ", "Храм (церковь) ") }
        assertNull("coherent punctuation-separated second building noun", context(punctuationDuplicate, row = punctuated))
    }

    @Test fun institutionalGrammarIsClosedAndLocalityAgreementIsLiteral() {
        val title = banner(requireNotNull(response(originalRoot).html())).text()
        val bad = listOf(
            title.replace("Мытищинского", "Мытищиного"),
            title.replace("Мытищинского", "Мытищинского Второго"),
            title.replace("Московской митрополии", "Московской и Тверской митрополии"),
            title.replace("Московской митрополии", "Московкой митрополии"),
            title.replace("Сергиево-Посадской и Дмитровской", "Сергиево-Посадской и Дмитровской и Тверской"),
            title.replace("Афанасово", "Афанасёво"),
            title.replace("деревни Афанасово", "деревни Афанасово. "),
            title + " расписание", title + " храм", title + " 08:00", title + " временно", title + " неизвестно")
        for (value in bad) assertNull(value, context(setTitle(originalRoot) { value }))
        for (marker in listOf("дер.", "с.", "г.", "д", "д. д."))
            assertNull(marker, context(row = candidate.copy(name = candidate.name.replace("д.", marker))))
        for (locality in listOf("Афанасово 2", "Афанасово/Б", "Афанасово. Б", "А-б-в", "А Б В Г Д", "А".repeat(41)))
            assertNull(locality, context(row = candidate.copy(name = candidate.name.substringBefore(" д. ") + " д. " + locality)))
        assertNull("different valid root/leaf banner", parsed(setTitle(originalLeaf) { it.replace("Московской", "Тверской") }))
        // No е/ё normalization, punctuation stripping or unknown suffix is authorized.
        assertNotNull("literal case and whitespace only", context(setTitle(originalRoot) { it.uppercase().replace(" деревни ", "\u00a0ДЕРЕВНИ  ") }))
    }

    @Test fun fullStaticRootCancellationBlocksQualificationEvenOutsideExtractorMain() {
        val additions = listOf(
            "<p>15 ноября 2026 года все богослужения отменены</p>",
            "<div hidden>15 ноября 2026 года все богослужения отменены</div>",
            "<aside>15 ноября 2026 года все богослужения отменены</aside>",
            "<div id='comments'>15 ноября 2026 года все богослужения отменены</div>",
            "<div class='sidebar'>Архив: 15 ноября 2026 года все богослужения отменены</div>")
        for (addition in additions) {
            val bytes = html { it.body().append(addition) }
            assertTrue(ScheduleCancellation.containsMatchIn(requireNotNull(response(bytes).html()).body().text()))
            assertNull(addition, context(bytes))
        }
        val exact = requireNotNull(response(originalRoot).html())
        assertTrue(originalRoot.toString(Charsets.UTF_8).contains("value='Отмена'"))
        assertFalse(ScheduleCancellation.containsMatchIn(exact.body().text()))
        assertNotNull(context())
        // These are explicitly outside the DOM-body-text guarantee, not evidence of JS safety.
        assertNotNull(context(html { it.body().append("<script>var notice='Богослужения отменены';</script><!-- Богослужения отменены -->") }))
    }

    @Test fun recognizedDeclarativeAndMyPrihodDocumentsNeverReceiveTheNewQualification() {
        for (script in listOf("<script type='text/plain'></script>", "<script type='text/plain'>bad</script>",
            "<div id='comments'><script type='text/plain'>bad</script></div>")) {
            assertNull("root $script", context(html { it.body().append(script) }))
            val leaf = html(originalLeaf, schedule) { it.body().append(script) }
            assertNull("leaf $script", requireNotNull(context()).inspect(parish, binding(), link,
                response(leaf, schedule), ScheduleParseResult(ScheduleParseDisposition.REJECTED), today))
        }
        // Rebase the entire controlled fixture to a synthetic recognized tenant; host alone grants nothing.
        val oldHost = java.net.URI(root).host
        val tenant = "controlled.myprihod.ru"
        fun rebase(bytes: ByteArray) = bytes.toString(Charsets.UTF_8).replace(oldHost, tenant).toByteArray(Charsets.UTF_8)
        val tenantRoot = root.replace(oldHost, tenant)
        val tenantLeaf = schedule.replace(oldHost, tenant)
        val tenantRow = candidate.copy(sourceUrl = source.replace(oldHost, tenant))
        val accepted = binding(row = tenantRow)
        fun platform(bytes: ByteArray, url: String) = html(rebase(bytes), url) {
            it.head().append("<link href='/multisite/church/css/schedule.css'>")
            it.body().append("<div class='content-block schedule'><table><tr><td>bad</td></tr></table></div>")
        }
        val rootPlatform = response(platform(originalRoot, tenantRoot), tenantRoot)
        assertTrue(MyPrihodScheduleHtml.appliesTo(requireNotNull(rootPlatform.html())))
        assertNull(QualifiedPublisherBanner.fromRoot(parish, accepted, tenantRow, tenantRow.sourceUrl, rootPlatform))
        val tenantContext = requireNotNull(QualifiedPublisherBanner.fromRoot(parish, accepted, tenantRow, tenantRow.sourceUrl,
            response(rebase(originalRoot), tenantRoot)))
        val leafPlatform = response(platform(originalLeaf, tenantLeaf), tenantLeaf)
        assertTrue(MyPrihodScheduleHtml.appliesTo(requireNotNull(leafPlatform.html())))
        assertNull(tenantContext.inspect(parish, accepted, ScheduleDocumentLink(tenantLeaf, tenantRoot, false), leafPlatform,
            ScheduleParseResult(ScheduleParseDisposition.REJECTED), today))
    }

    @Test fun onlyOneVisibleOwnExternalPlainHeadingCanBeRemoved() {
        val mutations = listOf<Pair<String, (Document) -> Unit>>(
            "inside main" to { val title = banner(it); it.select("[role=main]").single().prependChild(title) },
            "hidden" to { banner(it).attr("hidden", "") },
            "aria hidden ancestor" to { banner(it).parent()!!.attr("aria-hidden", "true") },
            "aside ancestor" to { banner(it).wrap("<aside></aside>") },
            "comment ancestor" to { banner(it).parent()!!.attr("class", "comment") },
            "nested title markup" to { val a = banner(it).select("a").single(); a.html("<span>${a.text()}</span>") },
            "extra title text" to { banner(it).appendText(" текст") },
            "second home anchor" to { val a = banner(it).select("a").single(); a.after(a.outerHtml()) },
            "second matching heading" to { banner(it).after(banner(it).outerHtml()) },
            "foreign home" to { banner(it).select("a").single().attr("href", "https://foreign.example/") })
        for ((label, change) in mutations) {
            assertNull("root $label", context(html(change = change)))
            assertNull("leaf $label", parsed(html(originalLeaf, schedule, change)))
        }
    }

    @Test fun exactExtractedDiffRejectsTakeTwoRevelationAndPreservesAllOtherDocumentFields() {
        val third = html(originalLeaf, schedule) { banner(it).after("<h1>Вторая шапка</h1><h1>Третья шапка</h1>") }
        val before = HtmlScheduleDocumentExtractor.fromHtml(requireNotNull(response(third, schedule).html()))
        assertEquals(1, before.pages.single().lines.count { it.headingLevel == 1 && it.text == "Вторая шапка" })
        assertFalse(before.pages.single().lines.any { it.text == "Третья шапка" })
        assertNull("removing first title would introduce previously unextracted third h1", parsed(third))
        val context = requireNotNull(context())
        for ((url, type) in listOf("https://foreign.example/schedule" to "text/html", schedule to "application/pdf"))
            assertNull(context.inspect(parish, binding(), link, response(originalLeaf, url, type), raw(), today))
        assertNull(context.inspect(parish, binding(), link.copy(linkedFrom = ""), response(originalLeaf, schedule), raw(), today))
        assertNull(context.inspect(parish, binding(), link.copy(linkedFrom = "https://foreign.example/"), response(originalLeaf, schedule), raw(), today))
        assertNull(context.inspect(parish, binding(), link.copy(url = root), response(originalRoot), raw(), today))
        assertNull("incomplete closing source", context(originalRoot.copyOf(originalRoot.size - 100)))
        assertNull("incomplete leaf", parsed(originalLeaf.copyOf(originalLeaf.size - 100)))
        val invalidUtf8 = originalRoot + byteArrayOf(0xc3.toByte(), 0x28)
        assertNull("invalid UTF-8/full source", context(invalidUtf8))
    }

    @Test fun explicitOtherVenueAndNewSafetyAreNeverTurnedIntoSelectedEvents() {
        val other = html(originalLeaf, schedule) { it.select(".entry-content").single().prepend("<h2>Расписание храма Николая Чудотворца</h2>") }
        val got = parsed(other)
        assertTrue(got == null || got.events.isEmpty() && safety(got))
        val cancelled = html(originalLeaf, schedule) {
            it.select(".entry-content").single().append("<p>15 ноября 2026 года все богослужения отменены</p>")
        }
        val safetyResult = requireNotNull(parsed(cancelled))
        assertTrue(safety(safetyResult)); assertFalse(safetyResult.events.any { it.date == "2026-11-15" })
        val rawDocumentSafety = html(originalLeaf, schedule) {
            it.select(".entry-content").single().append("<h2>Храм Сретения Господня</h2><p>15 ноября 2026 года все богослужения отменены</p>")
        }
        val documentResult = ScheduleDocumentParser.inspect(HtmlScheduleDocumentExtractor.fromHtml(
            requireNotNull(response(rawDocumentSafety, schedule).html())), today, parish)
        assertTrue(safety(documentResult))
        assertEquals("actual DocumentParser safety outranks generic HTML rejection", documentResult,
            requireNotNull(context()).inspect(parish, binding(), link, response(rawDocumentSafety, schedule),
                ScheduleParseResult(ScheduleParseDisposition.REJECTED), today))
        val protected = requireNotNull(context()).inspect(parish, binding(), link, response(originalLeaf, schedule),
            ScheduleParseResult(ScheduleParseDisposition.REJECTED, unresolvedWithdrawal = true), today)
        assertNull("ordinary raw typed safety is returned unchanged by caller", protected)
    }

    @Test fun cancellationReachesDurableSafetyBeforeNextFetchAndSourceHistorySurvivesInterruption() = runBlocking {
        val cache = Cache(); val network = Network(); val row = seed(cache, network)
        val repo = repository(cache, network)
        val ready = repo.confirmCandidate(parish, row.id, now)
        assertEquals(expected, ready.publication?.events)
        network.leafBytes = html(originalLeaf, schedule) {
            it.select(".entry-content").single().append("<p>15 ноября 2026 года все богослужения отменены</p>")
        }
        var observedSafety = false
        var leafFetched = false
        network.before = { url ->
            if (leafFetched && !url.startsWith(OfficialMapCandidateProvider.HOME)) {
                val cold = repository(cache, Network()).cached(parish)
                assertTrue(cache.safety.isNotEmpty()); assertTrue(cold.sourceHasWithdrawals)
                assertFalse(cold.publication?.events.orEmpty().any { it.date == "2026-11-15" })
                observedSafety = true
                throw CancellationException("Controlled suspension after durable withdrawal")
            }
            if (url == schedule) leafFetched = true
        }
        try { repo.refresh(parish, now.plusSeconds(30), force = true) }
        catch (_: CancellationException) { /* durable safety must precede cancellation */ }
        // If the existing crawl stops without another fetch, the synchronous safety receipt is still required.
        val cold = repository(cache, Network()).cached(parish)
        assertTrue(leafFetched)
        assertTrue(observedSafety || cache.safety.isNotEmpty())
        assertTrue(cache.safety.isNotEmpty()); assertTrue(cold.sourceHasWithdrawals)
        assertFalse(cold.publication?.events.orEmpty().any { it.date == "2026-11-15" })
        assertEquals(ready.siteBinding, cold.siteBinding)
        val alternative = row.copy(providerObjectId = "99999", sourceUrl = "https://another.example/",
            evidenceUrl = "https://map.patriarchia.ru/?infoid=99999")
        assertTrue(alternative.canConfirm(parish))
        cache.write(parish.id, ScheduleCacheCodec.encode(cold.copy(sourceCandidates = (cold.sourceCandidates + alternative).distinctBy { it.id })))
        val callsBefore = network.calls.size
        val blocked = repo.confirmCandidate(parish, alternative.id, now.plusSeconds(60))
        assertEquals(callsBefore, network.calls.size)
        assertEquals(ScheduleSourceChangeBlock.HISTORY, blocked.sourceChangeBlock)
        assertEquals(cold.publication, blocked.publication); assertEquals(cold.siteBinding, blocked.siteBinding)
    }
}
