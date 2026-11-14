package ru.liturgyguide.service.schedule

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.discovery.BundledChurchCatalog
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/** Closed saved-HTTP replay. The only OCR documents come from these three JPGs in this test run. */
@RunWith(AndroidJUnit4::class)
class OwnContactSourceToBookTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePreferenceNames = ConcurrentHashMap.newKeySet<String>()
    private val gson = Gson()
    private val runId = "own-contact-${UUID.randomUUID()}"
    private val output = File(context.filesDir, "contact-wiring-native/$runId").apply { mkdirs() }
    private val requests = CopyOnWriteArrayList<Map<String, Any>>()
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private fun fixturePreferences(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE).also {
        check(name.startsWith("$runId-"))
        fixturePreferenceNames += name
    }
    @After fun removeIsolatedPreferencesEvenAfterFailure() {
        fixturePreferenceNames.forEach { context.deleteSharedPreferences(it) }
    }
    private fun bytes(name: String) = instrumentation.context.assets.open("contact-wiring/$name").use { it.readBytes() }
    private fun json(name: String) = JsonParser.parseString(bytes(name).toString(Charsets.UTF_8)).asJsonObject
    private fun save(name: String, value: Any) = File(output, name).writeText(gson.toJson(value))
    private fun sha(value: ByteArray) = ScheduleSourceCandidate.sha(value)
    private val manifest by lazy { json("manifest.json") }
    private val inputs by lazy { (manifest["inputs"].asJsonArray + manifest["bookSources"].asJsonArray).map { it.asJsonObject } }
    private val contact by lazy { inputs.single { it["asset"].asString == "contacts.html" }["url"].asString }
    private val schedule by lazy { inputs.single { it["asset"].asString == "schedule.html" }["url"].asString }
    private val imageRows by lazy { inputs.filter { it["asset"].asString.matches(Regex("image-[1-3]\\.jpg")) } }
    private val owners by lazy { ParishRepository(bytes("curated.json").toString(Charsets.UTF_8)).directory.churches }
    private fun selected(): Parish {
        val row = json("selected-oracle.json")["selected"].asJsonObject
        val center = row["center"].asJsonObject
        return Parish(row["id"].asString, row["name"].asString, "", "", "", "", "", "Europe/Moscow",
            row["sourceUrl"].asString, "", "", "", emptyList(), "", "", center["latitude"].asDouble, center["longitude"].asDouble)
    }
    private fun sealed(row: JsonObject): ScheduleResponse {
        val body = bytes(row["asset"].asString)
        check(row["sha256"].asString == sha(body) && row["bytes"].asInt == body.size)
        return ScheduleResponse(row["url"].asString, body, row["contentType"].asString)
    }
    private fun checkInputs(): Map<String, String> {
        val result = inputs.associate { row ->
            val name = row["asset"].asString; val body = bytes(name)
            assertEquals(name, row["sha256"].asString, sha(body)); assertEquals(name, row["bytes"].asInt, body.size)
            name to sha(body)
        }.toMutableMap()
        val oracle = bytes("book-expected.json")
        assertEquals(manifest["bookWholePairOracle"].asJsonObject["sha256"].asString, sha(oracle))
        result["book-expected.json"] = sha(oracle)
        return result
    }
    private inner class DiskScheduleCache(private val name: String) : ScheduleCacheStore {
        private val prefs = fixturePreferences(name)
        val safetyWrites = CopyOnWriteArrayList<String>()
        override fun read(parishId: String): String? = prefs.getString(parishId, null)
        override fun write(parishId: String, value: String) { check(prefs.edit().putString(parishId, value).commit()) }
        override fun writeSafety(parishId: String, value: String) { safetyWrites += value; write(parishId, value) }
    }
    private class Control(val phase: String) {
        var waitAtSecond = false
        var failAfterFirst = false
        val reachedSecond = CompletableDeferred<Unit>()
    }
    private fun pools() = BundledPublisherPoolFactories(
        BundledCuratedPublisherMetadata { bytes("curated.json").inputStream() },
        BundledChurchCatalog({ error("Useful direct offer must not decode the large catalog") }, clock))
    private fun transport(control: Control): ScheduleTransport = ScheduleTransport { url ->
        val saved = inputs.singleOrNull { it.has("url") && it["url"].asString == url }
        val entry = linkedMapOf<String, Any>("phase" to control.phase, "requestedUrl" to url)
        requests += entry
        if (url == imageRows[1]["url"].asString) {
            control.reachedSecond.complete(Unit)
            if (control.waitAtSecond) awaitCancellation()
        }
        if (control.failAfterFirst && imageRows.drop(1).any { it["url"].asString == url })
            throw IOException("Deterministic failure of remaining attachments after actual positive foreign image 1")
        val response = when {
            saved != null -> sealed(saved).also {
                entry["role"] = "PRIMARY_SAVED_HTTP_RESPONSE"; entry["asset"] = saved["asset"].asString
            }
            url.startsWith("https://api.openstreetmap.org/api/0.6/way/226509018.json") -> {
                val tags = json("selected-oracle.json")["selected"].asJsonObject["sourceTags"]
                val root = JsonObject().apply { add("elements", JsonArray().apply { add(JsonObject().apply {
                    addProperty("type", "way"); addProperty("id", 226509018); add("tags", tags)
                }) }) }
                entry["role"] = "SYNTHETIC_EXACT_OSM_NO_WEBSITE_CONTROL"
                ScheduleResponse(url, root.toString().toByteArray(), "application/json")
            }
            url == OfficialMapCandidateProvider.HOME -> {
                entry["role"] = "SYNTHETIC_EMPTY_MAP_PROTOCOL_CONTROL"
                ScheduleResponse(url, "<html><body><script src='/js/ymap-filters-box.js'></script></body></html>".toByteArray(), "text/html")
            }
            url == OfficialMapCandidateProvider.HOME + "js/ymap-filters-box.js" -> {
                entry["role"] = "SYNTHETIC_EMPTY_MAP_PROTOCOL_CONTROL"
                ScheduleResponse(url, "const query='token=abcdefghijklmnop&country=';".toByteArray(), "application/javascript")
            }
            url.startsWith(OfficialMapCandidateProvider.API + "?") -> {
                entry["role"] = "SYNTHETIC_EMPTY_MAP_PROTOCOL_CONTROL"
                ScheduleResponse(url, "{\"type\":\"FeatureCollection\",\"features\":[]}".toByteArray(), "application/json")
            }
            url.startsWith(ElitsyMapCandidateProvider.API + "?") -> {
                entry["role"] = "SYNTHETIC_EMPTY_ELITSY_PROTOCOL_CONTROL"
                ScheduleResponse(url, "{\"points\":[]}".toByteArray(), "application/json")
            }
            else -> throw IOException("Unavailable saved source, no network: $url").also { entry["role"] = "UNAVAILABLE_NO_NETWORK" }
        }
        entry["responseUrl"] = response.url; entry["sha256"] = sha(response.bytes)
        response
    }
    private fun resolver(network: ScheduleTransport) = productionScheduleSiteResolver(network, MemoryOfficialDirectoryCacheStore(),
        moscowSearch = MoscowDirectorySearchTransport { throw IOException("Heavy search is outside this direct-source replay") },
        ownPools = pools(), clock = clock)
    private fun repository(cache: DiskScheduleCache, control: Control, documents: Map<String, ScheduleDocument>): InternetScheduleRepository {
        val network = transport(control)
        return InternetScheduleRepository(network, cache, ScheduleDocumentExtractor { response ->
            val row = imageRows.single { it["url"].asString == response.url }
            assertEquals(row["sha256"].asString, sha(response.bytes))
            requireNotNull(documents[response.url]) // These objects were extracted from exact JPG bytes in this invocation
        }, siteResolver = resolver(network), curatedPublishers = { owners })
    }
    private fun hashes(directory: File) = directory.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(directory).path to sha(it.readBytes())
    }

    @Test fun ordinaryOsmFreshContactActualThreeJpgExplicitConfirmationAndSelectedLiturgyRemainOffline() = runBlocking {
        val before = checkInputs()
        val gates = linkedMapOf<String, Any>("status" to "RUNNING", "fullRussianClaim" to false,
            "scope" to "ordinary OSM contact -> three actual native JPG -> selected liturgy only -> same-process cold repositories")
        val selected = selected(); val fingerprint = ScheduleEntityIdentity.fingerprint(selected)
        val documents = linkedMapOf<String, ScheduleDocument>()
        val captures = CopyOnWriteArrayList<Map<String, Any>>()
        try {
            assertEquals("osm:way:226509018", selected.id)
            assertTrue(listOf(selected.website, selected.city, selected.address, selected.phone).all { it.isEmpty() })
            val native = AndroidScheduleDocumentExtractor(context) { capture ->
                val row = imageRows.single { it["url"].asString == capture.sourceUrl }
                val file = "${row["asset"].asString}-${capture.stage}-${captures.size}.hocr"
                File(output, file).writeText(capture.hocr)
                captures += mapOf("sourceUrl" to capture.sourceUrl, "sourceSha256" to row["sha256"].asString,
                    "stage" to capture.stage, "width" to capture.width, "height" to capture.height,
                    "hocrFile" to file, "hocrSha256" to BookText.sha256(capture.hocr),
                    "words" to Jsoup.parse(capture.hocr).select(".ocrx_word,.ocr_word").map { word ->
                        mapOf("text" to word.text(), "title" to word.attr("title")) })
            }
            // Capture every page before making any disposition assertion. A bad page cannot hide its neighbors.
            val extractionFailures = linkedMapOf<String, String>()
            for (row in imageRows) {
                val url = row["url"].asString
                try { native.extract(sealed(row))?.let { document ->
                    documents[url] = document; save("${row["asset"].asString}.document.json", document)
                } ?: run { extractionFailures[url] = "extract returned null" } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { extractionFailures[url] = "${error.javaClass.name}: ${error.message}" }
            }
            save("ocr-captures.json", captures); save("extraction-failures.json", extractionFailures)
            val nativeAudit = imageRows.map { row ->
                val document = documents[row["url"].asString]
                mapOf("asset" to row["asset"].asString, "sourceUrl" to row["url"].asString,
                    "sourceSha256" to row["sha256"].asString, "selected" to document?.let {
                        ScheduleDocumentParser.inspect(it, LocalDate.parse("2026-11-14"), selected) },
                    "publisher" to document?.let { ScheduleDocumentParser.inspect(it, LocalDate.parse("2026-11-14"), owners.single { it.id == "elisavetinsky_opalikha" }) })
            }
            save("actual-parses.json", nativeAudit)
            assertEquals("Inspect all actual documents, not preset dispositions", emptyMap<String, String>(), extractionFailures)
            assertEquals(3, documents.size); assertTrue(captures.isNotEmpty())

            val cache = DiskScheduleCache("$runId-main")
            val control = Control("offer-and-confirm")
            val repo = repository(cache, control, documents)
            val offered = repo.refresh(selected, now, force = true)
            save("offered.json", offered)
            val candidate = offered.sourceCandidates.single { it.provider == OwnContactCandidateProvider.PROVIDER }
            assertEquals(ScheduleIdentityOutcome.INSUFFICIENT, candidate.outcome); assertTrue(candidate.canConfirm(selected))
            assertNull(offered.siteBinding); assertNull(offered.publication)
            assertEquals(fingerprint, ScheduleEntityIdentity.fingerprint(selected))
            assertEquals(json("selected-oracle.json")["contact"].asJsonObject["sha256"].asString, candidate.evidenceSha256)
            assertEquals(ScheduleGeometryEvidence(contact, candidate.evidenceSha256), candidate.geometryEvidence)

            // A separate fresh production review supplies the independent audit witness. Repository obtains its own fresh envelope.
            val auditFresh = resolver(transport(Control("fresh-audit"))).forRefresh().candidatesForConfirmation(selected, emptySet(), candidate)
            val fresh = auditFresh.candidates.single { it.id == candidate.id }
            val witness = requireNotNull(auditFresh.freshContactEvidence?.get(fresh.id))
            assertTrue(witness.matchesFresh(selected, fresh))
            val contactResponse = sealed(inputs.single { it["asset"].asString == "contacts.html" })
            val contactHtml = requireNotNull(contactResponse.html())
            val contactDocument = HtmlScheduleDocumentExtractor.fromHtml(contactHtml)
            val rawContactHtml = ScheduleHtmlParser.inspect(contactHtml, LocalDate.parse("2026-11-14"), selected)
            val rawContactDocument = ScheduleDocumentParser.inspect(contactDocument, LocalDate.parse("2026-11-14"), selected)
            val auditAttempt = Any()
            val contactAudit = witness.auditNavigationRoot(selected, fresh, contact, contactResponse,
                rawContactHtml, contactDocument, rawContactDocument, auditAttempt)
            save("contact-root-process-audit.json", mapOf("rawHtml" to rawContactHtml, "rawDocument" to rawContactDocument,
                "audit" to contactAudit, "sourceSha256" to sha(contactResponse.bytes)))
            assertEquals(ContactRootAuditDisposition.PROVED_NAVIGATION_ROOT, contactAudit.disposition)
            assertEquals("rec493422718", contactAudit.recurringStatements.single().owner)
            assertEquals("По воскресным дням в 8.40 совершается литургия.", contactAudit.recurringStatements.single().text)
            assertEquals("08:40", contactAudit.recurringStatements.single().time)
            assertNull(contactAudit.inventory.single { it.owner == "rec493422718" }.latitude)
            assertNull(contactAudit.inventory.single { it.owner == "rec493422718" }.longitude)
            val linkedPermit = requireNotNull(witness.takeLinkedNavigation(contactAudit, auditAttempt))
            val auditBinding = ScheduleEntityIdentity.bind(selected, contact, contact, fresh.name, ScheduleBindingMethod.USER_CONFIRMED)
                .copy(candidateConfirmation = ScheduleCandidateConfirmation(1, ScheduleCandidateConfirmation.TYPE,
                    fingerprint, fresh, now.toString()))
            val linkedUse = requireNotNull(linkedPermit.reserve(selected, contact, auditBinding,
                ScheduleDocumentLink(schedule, contact, false), false, auditAttempt))
            val linkedRead = linkedUse.read(sealed(inputs.single { it["asset"].asString == "schedule.html" }),
                LocalDate.parse("2026-11-14"), selected, auditBinding, owners, auditAttempt)
            save("linked-navigation-process-audit.json", mapOf("read" to linkedRead, "granted" to linkedUse.grants(linkedRead),
                "links" to linkedUse.linksFor(linkedRead)))
            assertTrue(linkedUse.grants(linkedRead))
            assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), linkedRead.htmlResult)
            assertEquals(ScheduleParseResult(ScheduleParseDisposition.REJECTED), linkedRead.documentResult)
            assertEquals(imageRows.map { it["url"].asString }, linkedUse.linksFor(linkedRead).filter { it.document }.map { it.url })
            val known = requireNotNull(KnownOtherScheduleVenue.fromFreshContact(selected, contact, schedule, fresh, witness, owners))
            val today = LocalDate.parse("2026-11-14")
            val classifications = imageRows.map { row ->
                val document = requireNotNull(documents[row["url"].asString])
                val raw = ScheduleDocumentParser.inspect(document, today, selected)
                ScheduleForeignImage.certify(document, today, selected, contact, schedule, known, raw)
            }
            save("actual-certified-parses.json", classifications)
            gates["actualImageDispositions"] = classifications.map { it.disposition.name + (it.rejectionReason?.let { reason -> "_$reason" } ?: "") }
            // Run cancellation/failure controls whenever the actual first certificate and actual child events exist.
            // This guard is recorded, never converted into a skipped/passed acceptance gate.
            if (classifications[0].rejectionReason == ScheduleRejectionReason.OTHER_ENTITY_ONLY && classifications[2].events.isNotEmpty()) {
                cancellationAndFailureBaseline(selected, fresh, classifications[2].events, documents, gates)
            } else gates["cancellationGate"] = "BLOCKED_BY_ACTUAL_FOREIGN1_OR_CHILD3_FAILURE"

            val ready = repo.confirmCandidate(selected, candidate.id, now)
            save("confirmed.json", ready)
            gates["repositoryStatus"] = ready.status.name
            gates["publicationSource"] = ready.publication?.sourceUrl.orEmpty()
            // Do not drop image 2 or repair a REJECTED result into UNSUPPORTED.
            assertEquals(ScheduleRejectionReason.OTHER_ENTITY_ONLY, classifications[0].rejectionReason)
            assertEquals(ScheduleParseDisposition.UNSUPPORTED, classifications[1].disposition)
            assertEquals(ScheduleParseDisposition.PARSED, classifications[2].disposition)
            assertEquals(InternetScheduleStatus.READY, ready.status)
            assertTrue(cache.safetyWrites.isEmpty())
            val publication = requireNotNull(ready.publication)
            assertEquals(imageRows[2]["url"].asString, publication.sourceUrl)
            assertEquals(ScheduleBindingMethod.USER_CONFIRMED, ready.siteBinding?.method)
            assertEquals(fingerprint, ready.siteBinding?.selectedIdentity)
            assertEquals(candidate.evidenceSha256, ready.siteBinding?.candidateConfirmation?.candidate?.evidenceSha256)
            assertEquals(manifest["expectedControlStarts"].asJsonArray.map { it.asString }, publication.events
                .filter { it.date in setOf("2026-11-13", "2026-11-14", "2026-11-15") }.map { "${it.date}T${it.time}" }.sorted())
            assertFalse(publication.events.any { it.time in setOf("07:00", "09:30") })
            val mainRequests = requests.filter { it["phase"] == control.phase }.map { it["requestedUrl"] }
            assertEquals(imageRows.map { it["url"].asString }, mainRequests.filter { url -> imageRows.any { it["url"].asString == url } })
            assertEquals(2, mainRequests.count { it == contact })
            assertEquals(ready, repository(DiskScheduleCache("$runId-main"), Control("cold-schedule"), documents).cached(selected))
            gates["scheduleGate"] = "PASS"
            bookReplay(selected, publication, gates)
            gates["status"] = "PASS"
        } catch (error: Throwable) {
            gates["status"] = "FAIL"; gates["error"] = "${error.javaClass.name}: ${error.message}"
            throw error
        } finally {
            val after = inputs.associate { it["asset"].asString to sha(bytes(it["asset"].asString)) } +
                ("book-expected.json" to sha(bytes("book-expected.json")))
            gates["inputsUnchanged"] = before == after
            gates["inputsBefore"] = before; gates["inputsAfter"] = after
            gates["selectedFingerprint"] = fingerprint
            save("requests.json", requests); save("ocr-captures.json", captures); save("receipt.json", gates)
            assertEquals("Pinned assets changed during replay", before, after)
        }
    }

    private suspend fun cancellationAndFailureBaseline(selected: Parish, candidate: ScheduleSourceCandidate,
        events: List<InternetService>, documents: Map<String, ScheduleDocument>, gates: MutableMap<String, Any>) = coroutineScope {
        // Explicit protocol baseline from the actual child3 parse. This is NOT a successful full-import claim.
        val identity = ScheduleEntityIdentity.fingerprint(selected)
        val binding = ScheduleSiteBinding(ScheduleEntityIdentity.POLICY_VERSION, identity, candidate.name,
            ScheduleEntityScope.CHURCH, ScheduleBindingMethod.USER_CONFIRMED, contact, contact,
            candidateConfirmation = ScheduleCandidateConfirmation(1, ScheduleCandidateConfirmation.TYPE, identity, candidate, now.toString()))
        assertTrue(binding.validFor(selected, contact, contact))
        val baseline = InternetScheduleState(selected.id, InternetScheduleStatus.READY, website = contact,
            sourceUrl = imageRows[2]["url"].asString, checkedAt = now.toString(),
            publication = SchedulePublication(selected.id, selected.timezone, contact, imageRows[2]["url"].asString,
                now.minusSeconds(3_600).toString(), events, "image", schedule), siteEvidenceUrl = contact,
            siteBinding = binding, sourceCandidates = listOf(candidate), candidateSearchIdentity = identity,
            sourceHasWithdrawals = true, dismissedCandidateIds = listOf("b".repeat(64)), candidateObservedIds = listOf("retained-baseline-history"))
        assertTrue(baseline.sourceHasWithdrawals); assertTrue(baseline.dismissedCandidateIds.isNotEmpty()); assertTrue(baseline.candidateObservedIds.isNotEmpty())
        val cacheName = "$runId-cancel-baseline"; val cache = DiskScheduleCache(cacheName)
        cache.write(selected.id, ScheduleCacheCodec.encode(baseline))
        val baselineBytes = cache.read(selected.id)
        val normalizedBaseline = repository(cache, Control("baseline-read"), documents).cached(selected)
        assertEquals(baseline.publication, normalizedBaseline.publication); assertEquals(binding, normalizedBaseline.siteBinding)
        val waiting = Control("cancel-after-foreign1").apply { waitAtSecond = true }
        val job = launch { repository(cache, waiting, documents).refresh(selected, now.plusSeconds(60), force = true) }
        withTimeout(15_000) { waiting.reachedSecond.await() }
        assertEquals(baselineBytes, cache.read(selected.id)); assertTrue(cache.safetyWrites.isEmpty())
        job.cancelAndJoin(); assertTrue(job.isCancelled)
        val cold = repository(DiskScheduleCache(cacheName), Control("cold-cancel"), documents).cached(selected)
        assertEquals(normalizedBaseline, cold); assertEquals(baselineBytes, cache.read(selected.id))
        val failure = Control("failure-after-foreign1").apply { failAfterFirst = true }
        val failed = repository(cache, failure, documents).refresh(selected, now.plusSeconds(120), force = true)
        assertTrue(failure.reachedSecond.isCompleted)
        assertEquals(baseline.publication, failed.publication); assertEquals(baseline.siteBinding, failed.siteBinding)
        assertEquals(baseline.sourceUrl, failed.sourceUrl); assertEquals(baseline.siteEvidenceUrl, failed.siteEvidenceUrl)
        assertEquals(baseline.sourceHasWithdrawals, failed.sourceHasWithdrawals); assertFalse(failed.publicationRejected)
        assertEquals(baseline.dismissedCandidateIds, failed.dismissedCandidateIds)
        assertTrue(failed.candidateObservedIds.isNotEmpty()); assertTrue(cache.safetyWrites.isEmpty())
        gates["cancellationGate"] = "PASS_ACTUAL_FOREIGN1_THEN_DETERMINISTIC_IMAGE2_WAIT_AND_FAILURE"
        save("cancel-protocol-baseline.json", normalizedBaseline)
    }

    private suspend fun bookReplay(selected: Parish, publication: SchedulePublication, gates: MutableMap<String, Any>) {
        val event = publication.events.single { it.date == "2026-11-14" && it.time == "08:00" && it.title.contains("Литурги", true) }
        val occurrence = ParishService(event.date, event.time, event.title, event.kind)
        val route = requireNotNull(ServiceBookSelection.resolve(occurrence, selected))
        val liturgyRoute = if (route.activeKey.worship == WorshipKind.LITURGY) route else {
            val key = requireNotNull(route.journeyContext).parts.single { it.worship == WorshipKind.LITURGY }
            requireNotNull(route.selectPart(key, savedCurrentPart = false))
        }
        assertEquals("2026-11-14|LITURGY|20|~|~", liturgyRoute.activeKey.canonical())
        val directory = File(output, "book-cache")
        val cache = FileBookCache(directory)
        val network = transport(Control("book-source"))
        val books = ServiceBookRepository(cache, network, translations = BookTranslationIndex.fromFeoriaCatalog(), clock = clock)
        val result = books.load(liturgyRoute.activeKey)
        save("book-load.json", result)
        assertTrue("Actual church20 source must parse as the selected liturgy", result is BookLoadResult.Ready)
        val ready = result as BookLoadResult.Ready; val book = ready.book
        assertTrue(ready.saved); assertTrue(ServiceBookIntegrity.valid(book))
        val expected = json("book-expected.json")
        val block = book.blocks.single { BookText.normalized(it.original) == BookText.normalized(expected["original"].asString) }
        assertEquals(expected["originalSha256"].asString, BookText.sha256(block.original))
        assertEquals(expected["russian"].asString, block.russian)
        assertEquals(expected["russianSha256"].asString, BookText.sha256(requireNotNull(block.russian)))
        assertEquals(BookTranslationIndex.FEORIA_TRANSLATION_SOURCE, block.translationSource)
        assertEquals(expected["attribution"].asString, block.translationSource?.title)
        assertTrue(book.sources.any { it.url == liturgyRoute.activeKey.sourceUrl() })
        val chapter = book.chapters.single { it.blocks.any { value -> value.id == block.id } }
        val preferences = fixturePreferences("$runId-book-reading")
        val store = BookReadingStore(preferences)
        val position = BookReadingPosition(book.key, book.contentHash, selected.id, block.id, chapter.id,
            fraction = .35f, mode = BookReadingMode.RUSSIAN, wordsPerMinute = 103, lastTextMode = BookReadingMode.RUSSIAN)
        assertTrue(store.save(position))
        // Only selected liturgy is prepared; absent hours sources cannot make a complete journey claim.
        val prepared = BookOfflinePreparation.prepare(book, null, selected.id, books, store)
        assertTrue(prepared.isComplete); assertEquals(listOf(book.key), prepared.parts.map { it.key })
        val diskBefore = hashes(directory)
        val textBefore = book.blocks.map { listOf(it.id, BookText.sha256(it.original), BookText.sha256(it.russian.orEmpty())) }
        var offlineRequests = 0
        val cold = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { offlineRequests++; throw IOException("Offline") },
            translations = BookTranslationIndex.fromFeoriaCatalog(), clock = clock)
        val restored = cold.load(book.key)
        assertTrue(restored is BookLoadResult.Ready && restored.fromCache)
        val reopened = (restored as BookLoadResult.Ready).book
        assertEquals(book, reopened)
        val coldStore = BookReadingStore(fixturePreferences("$runId-book-reading"))
        assertEquals(position, coldStore.restore(reopened, selected.id))
        assertEquals(textBefore, reopened.blocks.map { listOf(it.id, BookText.sha256(it.original), BookText.sha256(it.russian.orEmpty())) })
        assertEquals(book.sources, reopened.sources); assertEquals(diskBefore, hashes(directory)); assertEquals(0, offlineRequests)
        assertTrue(BookOfflinePreparation.inspect(reopened, null, selected.id, cold, coldStore).isComplete)
        gates["bookGate"] = "PASS_SELECTED_LITURGY_ONLY"
        gates["bookKey"] = book.key.canonical(); gates["bookContentHash"] = book.contentHash
        gates["bookSources"] = book.sources; gates["bookCacheFiles"] = diskBefore
        gates["physicalProcessRestart"] = "NOT_RUN_SEPARATE_DEVICE_GATE"
    }
}
