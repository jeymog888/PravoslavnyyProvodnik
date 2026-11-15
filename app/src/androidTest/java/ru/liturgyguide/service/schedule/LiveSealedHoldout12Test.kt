package ru.liturgyguide.service.schedule

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.*
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.TodayServiceSelection
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.discovery.*
import ru.liturgyguide.ui.scheduleCandidateGroups
import ru.liturgyguide.ui.toSavedChurch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Diagnostic only. One invariant test is not twenty successful parish schedules. */
@RunWith(AndroidJUnit4::class)
class LiveSealedHoldout12Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    private val evidenceBudget = AtomicInteger(96 * 1024 * 1024)
    private val failures = CopyOnWriteArrayList<String>()

    @Test fun selectedBatchUsesRealProductionSourcesAndOwnedStorage() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit opt-in to public network", args.getString("liveHoldout12") == "allow-public-network")
        val run = requireNotNull(args.getString("holdoutRun")) { "A unique holdoutRun is required" }
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val batch = args.getString("holdoutBatch", "1")!!.toInt().also { require(it in 1..5) }
        val knownIndexArg = args.getString("holdoutKnownIndex")
        // Reject subset requests for unopened batches before any asset/catalog lookup or network.
        require(knownIndexArg == null || batch in 1..3) { "Known-case replay is limited to batches 1..3" }
        val output = File(requireNotNull(app.getExternalFilesDir("feoria12-holdout")), run)
        check(!output.exists() && output.mkdirs()) { "Refusing to overwrite evidence; use a new holdoutRun" }
        val original = protectedFiles()
        val sealedBytes = instrumentation.context.assets.open(SEALED_ASSET).use { it.readBytes() }
        check(sha(sealedBytes) == SEALED_SHA)
        val sealed = JsonParser.parseString(sealedBytes.toString(Charsets.UTF_8)).asJsonObject
        val catalogBytes = app.assets.open(BundledChurchCatalog.ASSET_PATH).use { it.readBytes() }
        check(sha(catalogBytes) == CATALOG_SHA && sealed["catalogSha256"].asString == CATALOG_SHA)
        val records = sealed.getAsJsonArray("samples").map { it.asJsonObject }
        check(records.size == 100 && records.map { it.getAsJsonObject("selected")["id"].asString }.distinct().size == 100)
        (1..5).forEach { n ->
            val rows = records.filter { it["batch"].asInt == n }
            check(rows.size == 20 && rows.count { it["stratum"].asString == "has_website" } == 10 &&
                rows.count { it["stratum"].asString == "no_website" } == 10)
        }
        val catalogJson = JsonParser.parseString(catalogBytes.toString(Charsets.UTF_8)).asJsonObject
        val catalogRows = catalogJson.getAsJsonArray("churches").associate { it.asJsonObject["id"].asString to it }
        records.forEach { check(catalogRows[it.getAsJsonObject("selected")["id"].asString] == it["selected"]) }
        val batchIndices = records.indices.filter { records[it]["batch"].asInt == batch }
        val selectedIndices = holdout12SelectedIndices(batchIndices, batch, knownIndexArg)
        val selectionMode = if (knownIndexArg == null) "FIXED_SEALED_BATCH" else "KNOWN_DEVELOPMENT_CASE"
        val requestedIndex = if (knownIndexArg == null) null else selectedIndices.single()
        val decoded = LocalChurchCatalog.decode(catalogBytes.toString(Charsets.UTF_8).reader(), Instant.now())
        val resultRows = selectedIndices.map { index ->
            val sample = records[index]
            linkedMapOf<String, Any?>(
            "sealedIndex" to index, "batch" to sample["batch"].asInt,
            "stratum" to sample["stratum"].asString, "sealed100SamplingProbability" to sample["samplingProbability"].asDouble,
            "originalSelected" to sample["selected"], "state" to "NOT_RUN",
            "activity" to "NOT_VERIFIED; sourceTags preserved; no ruins/graves/activity filtering",
            "applicationSuccess" to false, "primarySourceOracle" to "NOT_REVIEWED",
            "workflowPolicy" to Holdout12SourceCoordinator.WORKFLOW_POLICY,
            "selectionMode" to selectionMode, "fixedBatchSize" to 20,
            "selectedDenominator" to selectedIndices.size, "requestedIndex" to requestedIndex
        ) }
        val receipt = linkedMapOf<String, Any?>(
            "run" to run, "batch" to batch, "workflowPolicy" to Holdout12SourceCoordinator.WORKFLOW_POLICY, "startedAt" to Instant.now().toString(),
            "sealedSha256" to SEALED_SHA, "catalogSha256" to CATALOG_SHA,
            "sourcePins" to JsonParser.parseString(instrumentation.context.assets.open(PINS_ASSET).bufferedReader().use { it.readText() }),
            "apkSha256" to sha(File(app.applicationInfo.sourceDir)),
            "selectedBatchSize" to selectedIndices.size, "fixedBatchSize" to 20, "sealedDenominator" to 100,
            "selectionMode" to selectionMode, "selectedDenominator" to selectedIndices.size, "requestedIndex" to requestedIndex,
            "selectionInterpretation" to if (knownIndexArg == null) "Full fixed batch; no subset filtering" else
                "Known development case only; not an independent sample or cohort coverage estimate",
            "population" to sealed["population"], "stratumPopulations" to sealed["stratumPopulations"],
            "measurement" to "Exact selected map object -> source resolver/importer -> featured published occurrence -> one book part -> fresh disk reopen",
            "notMeasured" to listOf("name/GPS search", "UI/20 seconds", "human source confirmation", "physical process restart", "microphone", "all composite book parts", "all services full translation"),
            "policy" to "No unweighted pooled success percentage. Missing/unavailable/unsupported/timeout/NOT_RUN stay in denominators. READY is pending primary-source review.",
            "cases" to resultRows, "invariantFailures" to failures,
            "protectedBefore" to original, "harnessPass" to false
        )
        writeJson(File(output, "report.json"), receipt)
        try {
            selectedIndices.forEach { index ->
                currentCoroutineContext().ensureActive()
                val sample = records[index]
                val selected = sample.getAsJsonObject("selected")
                val center = selected.getAsJsonObject("center")
                val point = GeoPoint(center["latitude"].asDouble, center["longitude"].asDouble)
                val exact = decoded.nearby(point, 1) { }.singleOrNull { it.id == selected["id"].asString }
                check(exact != null && exact.name == selected["name"].asString && exact.center == point)
                val row = resultRows.single { it["sealedIndex"] == index }
                val caseDirectory = File(output, "batch-$batch/case-${index.toString().padStart(3, '0')}").also { check(it.mkdirs()) }
                row["evidenceDirectory"] = caseDirectory.relativeTo(output).path
                row["state"] = "RUNNING"
                writeJson(File(output, "report.json"), receipt)
                runCase(exact, row, caseDirectory)
                writeJson(File(output, "report.json"), receipt)
            }
        } finally {
            val after = protectedFiles()
            receipt["protectedAfter"] = after
            invariant(original == after, "Original preferences and saved books were unchanged")
            receipt["finishedAt"] = Instant.now().toString()
            receipt["completedBatchRows"] = resultRows.count { it["batch"] == batch && it["state"] !in listOf("NOT_RUN", "RUNNING") }
            receipt["strata"] = listOf("has_website", "no_website").associateWith { stratum ->
                val rows = resultRows.filter { it["batch"] == batch && it["stratum"] == stratum }
                mapOf("denominator" to rows.size, "states" to rows.groupingBy { it["state"].toString() }.eachCount(),
                    "readyPendingPrimaryReview" to rows.count { it["publicationReadyPendingReview"] == true },
                    "offlineBookVerified" to rows.count { it["offlineBookVerified"] == true },
                    "externallyOracleConfirmedApplicationSuccess" to 0)
            }
            receipt["sourceWorkflowSummary"] = holdout12SourceSummary(resultRows)
            receipt["sourceWorkflowSummaryByStratum"] = listOf("has_website", "no_website").associateWith { stratum ->
                holdout12SourceSummary(resultRows.filter { it["stratum"] == stratum })
            }
            receipt["applicationSuccess"] = false
            receipt["harnessPass"] = failures.isEmpty() && receipt["completedBatchRows"] == selectedIndices.size
            writeJson(File(output, "summary.json"), linkedMapOf(
                "run" to run, "batch" to batch, "workflowPolicy" to Holdout12SourceCoordinator.WORKFLOW_POLICY,
                "finishedAt" to receipt["finishedAt"], "selectedBatchDenominator" to resultRows.size,
                "selectionMode" to selectionMode, "fixedBatchSize" to 20,
                "selectedDenominator" to selectedIndices.size, "requestedIndex" to requestedIndex,
                "selectionInterpretation" to receipt["selectionInterpretation"],
                "sourceWorkflow" to receipt["sourceWorkflowSummary"],
                "sourceWorkflowByStratum" to receipt["sourceWorkflowSummaryByStratum"],
                "strata" to receipt["strata"], "applicationSuccess" to false,
                "readyInterpretation" to "PENDING_PRIMARY_SOURCE_REVIEW", "harnessPass" to receipt["harnessPass"]))
            writeJson(File(output, "report.json"), receipt)
        }
        assertTrue("Invariant failures: ${failures.joinToString()}; product outcomes are in report.json", failures.isEmpty())
    }

    private suspend fun runCase(church: DiscoveredChurch, row: MutableMap<String, Any?>, output: File) {
        val owned = OwnedContext(app, UUID.randomUUID().toString())
        val started = System.nanoTime()
        val now = Instant.now()
        row["clock"] = now.toString()
        row["ownedStorage"] = owned.owned.absolutePath
        row["ownedPreferencePrefix"] = owned.prefix
        holdout12PrepareSourceReceipt(row)
        val source = Recorder(output)
        var parish: Parish? = null
        var repository: InternetScheduleRepository? = null
        try {
            withTimeout(240_000) {
                val saved = church.toSavedChurch()
                if (saved == null) { row["state"] = "MAP_OBJECT_NOT_SAVABLE"; return@withTimeout }
                val directory = ChurchDirectoryStore(owned.getSharedPreferences("church_directory", Context.MODE_PRIVATE))
                invariant(directory.save(saved), "${church.id}: selected map object saved to owned directory")
                invariant(directory.records().singleOrNull() == saved, "${church.id}: exact selected map object restored")
                val selected = saved.asParish()
                parish = selected
                row["savedParish"] = selected
                val container = AppContainer(owned)
                val real = UrlConnectionScheduleTransport(timeoutMillis = 15_000,
                    limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true)
                val transport = source.transport(real)
                val pools = BundledPublisherPoolFactories(BundledCuratedPublisherMetadata(open = {
                    owned.assets.open(BundledCuratedPublisherMetadata.ASSET_PATH)
                }), BundledChurchCatalog(open = { owned.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader(Charsets.UTF_8) }))
                val native = AndroidScheduleDocumentExtractor(owned, observer = source::nativeOcr)
                val extractor = ScheduleDocumentExtractor { response ->
                    native.extract(response).also { doc -> source.document(doc) }
                }
                val post = UrlConnectionMoscowDirectorySearchTransport()
                val observedPost = MoscowDirectorySearchTransport { queries ->
                    source.post(queries) { post.search(queries) }
                }
                val repo = InternetScheduleRepository(transport, productionCache(owned), extractor,
                    siteResolver = productionScheduleSiteResolver(transport,
                        FileOfficialDirectoryCacheStore(File(owned.cacheDir, "official-parish-directories")),
                        moscowSearch = observedPost, ownPools = pools),
                    curatedPublishers = { container.parishes.getOrNull()?.directory?.churches.orEmpty() })
                repository = repo
                row["activePhase"] = "SOURCE"
                val state = withTimeout(180_000) {
                    coroutineScope {
                        val progress = launch { repo.progress.collect { source.progress(it) } }
                        try {
                            Holdout12SourceCoordinator(repo, selected, now, row,
                                counters = source::counters,
                                persist = { writeJson(File(output, "case.json"), row) }).run()
                        } finally { progress.cancelAndJoin() }
                    }
                }
                row["publicationReadyPendingReview"] = usable(state, now)
                row["state"] = if (usable(state, now)) "READY_PENDING_PRIMARY_SOURCE_REVIEW" else when {
                    state.sourceNeedsVerification -> "SOURCE_NEEDS_VERIFICATION"
                    state.publicationRejected -> "PUBLICATION_REJECTED"
                    state.sourceCandidates.isNotEmpty() -> "CANDIDATES_WITHOUT_USABLE_PUBLICATION"
                    else -> state.status.name
                }
                row["sourceNotSuccessExplanation"] = "READY and simulated confirmation are not correctness proof. Root must inspect primary source, date, venue, cancellation and times."
                val published = selected.withInternetSchedule(state, now)
                row["presentedPublishedServices"] = published.schedule
                row["presentedToday"] = published.schedule.filter { it.date == now.atZone(ZoneId.of(selected.timezone)).toLocalDate().toString() }
                row["presentedTomorrow"] = published.schedule.filter { it.date == now.atZone(ZoneId.of(selected.timezone)).toLocalDate().plusDays(1).toString() }
                row["sourceOutcome"] = row["state"]
                if (usable(state, now)) {
                    row["activePhase"] = "BOOK"
                    withTimeout(60_000) { bookPhase(container, owned, published, now, row) }
                } else row["bookState"] = "NOT_RUN_NO_USABLE_PUBLICATION"
                row["activePhase"] = "COMPLETE"
            }
        } catch (timeout: TimeoutCancellationException) {
            holdout12MarkSourceInterrupted(row, timeout)
            currentCoroutineContext().ensureActive()
            row["stateBeforeTimeout"] = row["state"]
            row["state"] = if (row["activePhase"] == "BOOK") "BOOK_TIMEOUT_SOURCE_READY_PENDING_REVIEW" else "SOURCE_TIMEOUT"
            if (row["activePhase"] == "BOOK") row["bookState"] = "TIMEOUT"
            row["error"] = timeout.javaClass.simpleName
        } catch (cancelled: CancellationException) {
            holdout12MarkSourceInterrupted(row, cancelled)
            row["state"] = "CANCELLED"
            throw cancelled
        } catch (error: Exception) {
            holdout12MarkSourceInterrupted(row, error)
            row["state"] = if (row["activePhase"] == "BOOK") "BOOK_ERROR_SOURCE_READY_PENDING_REVIEW" else "SOURCE_ERROR"
            if (row["activePhase"] == "BOOK") row["bookState"] = "ERROR"
            row["error"] = error.javaClass.name + ": " + error.message.orEmpty().take(2000)
        } finally {
            // Archiving must never own the cleanup gate: even a failed hash/read still enters it.
            try {
                parish?.let { selected ->
                    row["cachedAfterRun"] = repository?.cached(selected)
                    saveAndCheckSourceDisk(owned, selected, row, output)
                }
                row["ownedFilesBeforeCleanup"] = hashes(owned.owned)
                row["ownedPreferencesBeforeCleanup"] = owned.preferenceNames().associateWith { name ->
                    owned.basePreferenceFile(name).takeIf { it.isFile }?.let { sha(it) }
                }
                owned.preferenceNames().forEach { name ->
                    val file = owned.basePreferenceFile(name)
                    if (file.isFile) writeSafeBytes(File(output, "owned-preferences/$name.xml"), file.readBytes(), "text/xml", source)
                }
                val bookDirectory = File(owned.filesDir, "service-books-v2")
                bookDirectory.walkTopDown().filter { it.isFile }.forEach {
                    writeSafeBytes(File(output, "saved-book-cache/${it.relativeTo(bookDirectory).path}"), it.readBytes(), "application/json", source)
                }
                val directoryCache = File(owned.cacheDir, "official-parish-directories")
                directoryCache.walkTopDown().filter { it.isFile }.forEach {
                    writeSafeBytes(File(output, "official-directory-cache/${it.relativeTo(directoryCache).path}"), it.readBytes(), "application/json", source)
                }
                source.finish()
                row["archiveComplete"] = true
            } catch (cancelled: CancellationException) {
                row["archiveComplete"] = false
                row["archiveError"] = "CANCELLED"
                throw cancelled
            } catch (error: Exception) {
                row["archiveComplete"] = false
                row["archiveError"] = error.javaClass.name
                invariant(false, "${church.id}: evidence archive failed: ${error.javaClass.simpleName}")
            } finally {
                // Every own preference and workspace is attempted independently, even after IO failure.
                val cleanup = owned.cleanup()
                row["cleanup"] = cleanup.success
                row["cleanupReceipt"] = cleanup
                invariant(cleanup.success, "${church.id}: all owned preferences/files removed: ${cleanup.errors.joinToString()}")
                row["elapsedMillis"] = (System.nanoTime() - started) / 1_000_000
                row["applicationSuccess"] = false
                row["primarySourceOracle"] = "PENDING_ROOT_REVIEW; no automatic correctness claim"
                runCatching { writeJson(File(output, "case.json"), row) }.onFailure {
                    invariant(false, "${church.id}: case receipt failed after cleanup: ${it.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun bookPhase(container: AppContainer, context: OwnedContext, parish: Parish, now: Instant,
        row: MutableMap<String, Any?>) {
        val today = now.atZone(ZoneId.of(parish.timezone)).toLocalDate()
        val featured = TodayServiceSelection.pick(parish, today, now, lookAhead = true)
        row["featuredOccurrence"] = featured
        if (featured == null) { row["bookState"] = "NO_TODAY_OR_UPCOMING_OCCURRENCE"; return }
        val route = ServiceBookSelection.resolve(featured.service, parish)
        row["productionBookRoute"] = route
        if (route == null) { row["bookState"] = "PUBLISHED_SERVICE_KIND_NOT_SUPPORTED"; return }
        row["bookScope"] = "One active production-mapped part only; composite journey not fully downloaded or verified"
        val key = route.activeKey
        val progress = mutableListOf<BookLoadProgress>()
        row["bookProgress"] = progress
        val loaded = container.serviceBooks.load(key, onProgress = { progress += it })
        row["bookLoadResult"] = when (loaded) {
            is BookLoadResult.Ready -> mapOf("type" to "Ready", "fromCache" to loaded.fromCache,
                "saved" to loaded.saved, "warning" to loaded.warning, "key" to loaded.book.key,
                "source" to loaded.book.sourceUrl, "contentHash" to loaded.book.contentHash,
                "chapters" to loaded.book.chapters.size, "blocks" to loaded.book.blocks.size,
                "translatedPrayerCount" to loaded.book.translatedPrayerCount, "prayerCount" to loaded.book.prayerCount,
                "notices" to loaded.book.notices, "calendarDay" to loaded.book.calendarDay)
            is BookLoadResult.Failed -> mapOf("type" to "Failed", "reason" to loaded.reason, "message" to loaded.message)
        }
        if (loaded !is BookLoadResult.Ready) { row["bookState"] = "LOAD_FAILED"; return }
        val book = loaded.book
        invariant(book.key == key && ServiceBookIntegrity.valid(book), "${parish.id}: exact mapped book key/integrity")
        if (!loaded.saved) { row["bookState"] = "BOOK_NOT_DURABLY_SAVED"; return }
        val chapter = book.chapters.firstOrNull { it.blocks.any { block -> !block.isAltarText } }
        val block = chapter?.blocks?.firstOrNull { !it.isAltarText }
        if (chapter == null || block == null) { row["bookState"] = "NO_PUBLIC_READING_BLOCK"; return }
        val prefs = context.getSharedPreferences("service_book_reading", Context.MODE_PRIVATE)
        val cursor = BookReadingPosition(key, book.contentHash, parish.id, block.id, chapter.id, journeyContext = route.journeyContext)
        invariant(BookReadingStore(prefs).save(cursor), "${parish.id}: owned reading cursor committed")
        val cache = File(context.filesDir, "service-books-v2")
        val before = hashes(cache)
        val calls = AtomicInteger()
        val offline = ServiceBookRepository(FileBookCache(cache), transport = ScheduleTransport {
            calls.incrementAndGet(); throw IOException("Offline verification forbids every network request")
        }, translations = BookTranslationIndex.fromBundledRussian(), downloadRussian = false)
        val restored = offline.load(key)
        val reopened = (restored as? BookLoadResult.Ready)?.book
        val same = restored is BookLoadResult.Ready && restored.fromCache && restored.saved && reopened == book &&
            calls.get() == 0 && before == hashes(cache) && BookReadingStore(prefs).restore(book, parish.id) == cursor
        invariant(same, "${parish.id}: fresh disk cache/repository exact offline reopen, zero HTTP, unchanged cursor and disk")
        row["offlineBookVerified"] = same
        row["offlineBookHttpCalls"] = calls.get()
        row["offlineBookFileHashes"] = before
        row["bookState"] = if (same) "ONE_PART_SAVED_AND_REOPENED_OFFLINE" else "OFFLINE_REOPEN_INVARIANT_FAILED"
        row["offlineScope"] = "Fresh FileBookCache and repository; unchanged source data and no network. Same Android process; no UI or physical restart claim"
    }

    private fun productionCache(context: Context): ScheduleCacheStore = object : ScheduleCacheStore {
        private val preferences = context.getSharedPreferences("internet_schedule_cache", Context.MODE_PRIVATE)
        override val coordinationLock: Any get() = ScheduleCacheCoordination.productionLock
        override fun read(parishId: String): String? = preferences.getString(parishId, null)
        override fun write(parishId: String, value: String) {
            if (value.length > 200_000) return
            val edit = preferences.edit().putString(parishId, value)
            if (!preferences.contains(parishId) && preferences.all.size >= 100) preferences.all.keys.firstOrNull()?.let(edit::remove)
            edit.apply()
        }
        override fun writeSafety(parishId: String, value: String) {
            require(value.length <= 200_000)
            val edit = preferences.edit().putString(parishId, value)
            if (!preferences.contains(parishId) && preferences.all.size >= 100) preferences.all.keys.firstOrNull()?.let(edit::remove)
            check(edit.commit()) { "Schedule safety observation was not saved" }
        }
    }

    private suspend fun saveAndCheckSourceDisk(context: OwnedContext, parish: Parish, row: MutableMap<String, Any?>, output: File) {
        val expected = context.getSharedPreferences("internet_schedule_cache", Context.MODE_PRIVATE).getString(parish.id, null)
        if (expected == null) { row["sourceDiskCache"] = "ABSENT"; return }
        var raw: String? = null
        withContext(NonCancellable) {
            repeat(60) { if (raw != expected) { raw = xmlString(context.preferenceFile("internet_schedule_cache"), parish.id); if (raw != expected) delay(50) } }
        }
        row["sourceDiskCache"] = if (raw == expected) "EXACT_PHYSICAL_XML_VALUE" else "NOT_DURABLE_WITHIN_3_SECONDS"
        invariant(raw == expected, "${parish.id}: source cache apply reached disk without replacing production apply with commit")
        if (raw == null) return
        val calls = AtomicInteger()
        val diskRaw = raw
        val offline = InternetScheduleRepository(ScheduleTransport { calls.incrementAndGet(); throw IOException("Offline") },
            object : ScheduleCacheStore {
                override fun read(parishId: String): String? = if (parishId == parish.id) diskRaw else null
                override fun write(parishId: String, value: String) = error("Read-only physical disk snapshot")
            })
        val restored = offline.cached(parish)
        row["sourceDiskRawSha256"] = sha(raw!!.toByteArray())
        row["offlineSourceCacheState"] = restored
        row["offlineSourceCacheHttpCalls"] = calls.get()
        invariant(ScheduleCacheCodec.decode(raw, parish) == restored && calls.get() == 0,
            "${parish.id}: production codec reopened physical source cache with zero HTTP")
        writeJson(File(output, "source-cache-decoded.json"), restored)
    }

    private fun xmlString(file: File, key: String): String? = runCatching {
        if (!file.isFile) return@runCatching null
        file.inputStream().use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
            var type = parser.eventType
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.name == "string" && parser.getAttributeValue(null, "name") == key)
                    return@runCatching parser.nextText()
                type = parser.next()
            }
        }
        null
    }.getOrNull()

    private fun usable(state: InternetScheduleState, now: Instant) = holdout12Usable(state, now)

    private fun invariant(value: Boolean, message: String) { if (!value) failures += message }
    private fun protectedFiles(): Map<String, String> = mapOf("preferences" to File(app.applicationInfo.dataDir, "shared_prefs"),
        "books" to File(app.filesDir, "service-books-v2")).flatMap { (label, directory) ->
        hashes(directory).map { (name, hash) -> "$label/$name" to hash }
    }.toMap().toSortedMap()
    private fun hashes(root: File): Map<String, String> = if (!root.isDirectory) emptyMap() else root.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(root).path to sha(it) }.toSortedMap()
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Bounded scalar-credential policy shared by URL, JSON, HTML and plain-text evidence.
     * A structured ServiceBookKey object is data, not a scalar credential named "key".
     * This is not a universal secret detector and never handles account headers/cookies.
     */
    private object SensitiveKeys {
        const val pattern = "(?:password|authorization|_?token|access[_-]?token|refresh[_-]?token|(?:x[_-])?(?:csrf|xsrf)(?:[_-]?token)?|csrfmiddlewaretoken|nonce|sessid|api[_-]?key|cookie|secret|auth|key)"
        private val key = Regex("(?i)^$pattern$")
        private val query = Regex("([?&](?:amp;)?)([^=&#\\s\"'<>]+)=([^&#\\s\"'<>]*)", RegexOption.IGNORE_CASE)
        private val scalar = Regex("""(?is)((?<![A-Za-z0-9_-])["']?(?:$pattern)["']?(\s*[:=]\s*))(?:("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|([^\s"'&<>;,}\]\[{]+))""")
        private val tag = Regex("""(?is)<(?:input|meta)\b[^>]*>""")
        private val name = Regex("""(?is)\bname\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
        private val value = Regex("""(?is)(\b(?:value|content)\s*=\s*)(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
        fun matches(value: String): Boolean = key.matches(value)
        private fun queryKey(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
        fun sensitiveQuery(url: String): Boolean = query.findAll(url).any { match -> queryKey(match.groupValues[2])?.let(::matches) == true }
        fun redact(input: String): String {
            var result = query.replace(input) { match ->
                if (queryKey(match.groupValues[2])?.let(::matches) == true)
                    match.groupValues[1] + match.groupValues[2] + "=REDACTED" else match.value
            }
            result = scalar.replace(result) { match ->
                val quoted = match.groups[3]?.value
                val replacement = if (quoted != null) {
                    val quote = quoted.first(); "$quote" + "REDACTED" + quote
                } else if (match.groupValues[2].trim().startsWith(":")) "\"REDACTED\"" else "REDACTED"
                match.groupValues[1] + replacement
            }
            result = tag.replace(result) { match ->
                val attr = name.find(match.value)
                val attributeName = attr?.groups?.drop(1)?.firstOrNull { it != null }?.value
                if (attributeName == null || !matches(attributeName)) match.value else value.replace(match.value) { field ->
                    val replacement = when {
                        field.groups[2] != null -> "\"REDACTED\""
                        field.groups[3] != null -> "'REDACTED'"
                        else -> "REDACTED"
                    }
                    field.groupValues[1] + replacement
                }
            }
            return result.replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~-]+"), "$1REDACTED")
        }
    }
    private fun sanitized(element: JsonElement): JsonElement {
        if (element.isJsonObject) return JsonObject().also { result -> element.asJsonObject.entrySet().forEach { (key, value) ->
            if (SensitiveKeys.matches(key) && value.isJsonPrimitive) result.addProperty(key, "REDACTED")
            else result.add(key, sanitized(value))
        } }
        if (element.isJsonArray) return com.google.gson.JsonArray().also { result -> element.asJsonArray.forEach { result.add(sanitized(it)) } }
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) return com.google.gson.JsonPrimitive(redact(element.asString))
        return element
    }
    private fun redact(value: String): String = SensitiveKeys.redact(value)
    private fun writeJson(file: File, value: Any?) {
        file.parentFile?.mkdirs()
        val bytes = gson.toJson(sanitized(gson.toJsonTree(value))).toByteArray(Charsets.UTF_8)
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        check(temporary.renameTo(file))
    }

    private data class EvidenceBody(val bytes: ByteArray?, val declaredCharset: String?, val originalCharset: String?,
        val writtenCharset: String?, val transformation: String, val omissionReason: String?)

    private fun safeEvidence(bytes: ByteArray, type: String, sensitiveBinaryUrl: Boolean): EvidenceBody {
        // ISO-8859-1 here is a reversible byte view, never a claim about the source's language.
        val byteView = bytes.toString(Charsets.ISO_8859_1)
        val sniff = byteView.take(80).trimStart()
        val text = type.contains("text", true) || type.contains("json", true) || type.contains("xml", true) ||
            type.contains("html", true) || sniff.startsWith("<") || sniff.startsWith("{") || sniff.startsWith("[")
        if (!text) return if (sensitiveBinaryUrl) EvidenceBody(null, null, null, null, "OMITTED", "SENSITIVE_BINARY_URL")
            else EvidenceBody(bytes, null, null, null, "EXACT_BINARY", null)
        val charsetPattern = Regex("""(?i)charset\s*=\s*["']?([^\s;"'>/]+)""")
        val header = charsetPattern.find(type)?.groupValues?.get(1)
        val meta = Regex("""(?is)<meta\b[^>]*>""").findAll(byteView.take(64 * 1024))
            .mapNotNull { charsetPattern.find(it.value)?.groupValues?.get(1) }.firstOrNull()
        val xml = Regex("""(?is)^\s*<\?xml\b[^>]*\bencoding\s*=\s*["']([^"']+)["']""").find(byteView)?.groupValues?.get(1)
        val declared = header ?: meta ?: xml
        val charset = try { Charset.forName(declared ?: "UTF-8") }
            catch (_: Exception) { return EvidenceBody(null, declared, null, null, "OMITTED", "UNKNOWN_CHARSET") }
        // No byte-regex redaction for UTF-16/stateful encodings. Honest omission is safer.
        if (charset.name() !in setOf("UTF-8", "windows-1251", "KOI8-R", "ISO-8859-1", "US-ASCII"))
            return EvidenceBody(null, declared, charset.name(), null, "OMITTED", "UNSUPPORTED_EVIDENCE_CHARSET")
        fun decoded(value: ByteArray): String = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString()
        val originalText = try { decoded(bytes) }
            catch (_: Exception) { return EvidenceBody(null, declared, charset.name(), null, "OMITTED", "INVALID_TEXT_ENCODING") }
        val redactedView = redact(byteView)
        val written = if (redactedView == byteView) bytes else redactedView.toByteArray(Charsets.ISO_8859_1)
        val writtenText = try { decoded(written) }
            catch (_: Exception) { return EvidenceBody(null, declared, charset.name(), null, "OMITTED", "REDACTION_INVALIDATED_ENCODING") }
        val structuralText = writtenText.trimStart { it.isWhitespace() || it == '\uFEFF' }
        try {
            if (type.contains("json", true) || structuralText.startsWith("{") || structuralText.startsWith("[")) {
                fun checked(value: JsonElement): Boolean = when {
                    value.isJsonObject -> value.asJsonObject.entrySet().all { (name, item) ->
                        if (SensitiveKeys.matches(name) && item.isJsonPrimitive) item.asString == "REDACTED" else checked(item)
                    }
                    value.isJsonArray -> value.asJsonArray.all(::checked)
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> redact(value.asString) == value.asString
                    else -> true
                }
                if (!checked(JsonParser.parseString(structuralText)))
                    return EvidenceBody(null, declared, charset.name(), null, "OMITTED", "UNSAFE_RAW_JSON_CREDENTIAL")
            }
            if (type.contains("html", true) || originalText.contains(Regex("(?i)<(?:input|meta)\\b"))) {
                val document = org.jsoup.Jsoup.parse(writtenText)
                if (document.getElementsByAttribute("name").any { element -> SensitiveKeys.matches(element.attr("name")) &&
                    listOf("value", "content").any { element.hasAttr(it) && element.attr(it) != "REDACTED" } })
                    return EvidenceBody(null, declared, charset.name(), null, "OMITTED", "UNSAFE_RAW_HTML_CREDENTIAL")
            }
        } catch (_: Exception) {
            return EvidenceBody(null, declared, charset.name(), null, "OMITTED", "AMBIGUOUS_RAW_TEXT")
        }
        return EvidenceBody(written, declared, charset.name(), charset.name(),
            if (written === bytes) "EXACT_TEXT_BYTES" else "BYTE_PRESERVING_CREDENTIAL_REDACTION", null)
    }

    private fun writeSafeBytes(file: File, bytes: ByteArray, type: String, recorder: Recorder,
        sensitiveBinaryUrl: Boolean = false): Map<String, Any?> {
        val prepared = safeEvidence(bytes, type, sensitiveBinaryUrl)
        val metadata = linkedMapOf<String, Any?>("declaredCharset" to prepared.declaredCharset,
            "originalCharset" to prepared.originalCharset, "writtenCharset" to prepared.writtenCharset,
            "transformation" to prepared.transformation, "omissionReason" to prepared.omissionReason,
            "originalBytes" to bytes.size, "originalSha256" to sha(bytes))
        val written = prepared.bytes
        if (written == null) {
            recorder.omitted(file.name, bytes.size, sha(bytes), prepared.omissionReason ?: "UNSAFE_EVIDENCE", metadata)
            return metadata
        }
        if (evidenceBudget.addAndGet(-written.size) < 0) {
            metadata["transformation"] = "OMITTED"
            metadata["omissionReason"] = "BATCH_EVIDENCE_BUDGET"
            recorder.omitted(file.name, bytes.size, sha(bytes), "BATCH_EVIDENCE_BUDGET", metadata)
            return metadata
        }
        runCatching { file.parentFile?.mkdirs(); file.writeBytes(written) }.onFailure {
            invariant(false, "Evidence write failed: ${it.javaClass.simpleName}")
            metadata["transformation"] = "OMITTED"
            metadata["omissionReason"] = "EVIDENCE_WRITE_ERROR"
            recorder.omitted(file.name, bytes.size, sha(bytes), "EVIDENCE_WRITE_ERROR", metadata)
        }
        if (file.isFile) { metadata["writtenBytes"] = file.length(); metadata["writtenSha256"] = sha(file) }
        return metadata
    }

    private inner class Recorder(private val output: File) {
        private val events = mutableListOf<Map<String, Any?>>()
        private var sequence = 0
        private var nativeOcrBytes = 0
        @Synchronized fun event(value: Map<String, Any?>) {
            events += value + mapOf("observedAt" to Instant.now().toString())
            runCatching { writeJson(File(output, "calls.json"), events.toList()) }.onFailure {
                synchronized(failures) { failures += "Call log write failed: ${it.javaClass.simpleName}" }
            }
        }
        @Synchronized fun counters(): Map<String, Int> = Holdout12SourceCoordinator.COUNTER_NAMES.associateWith { name ->
            events.count { it["type"] == name }
        }
        @Synchronized private fun next(): Int = sequence++
        fun omitted(name: String, size: Int, hash: String, reason: String, metadata: Map<String, Any?> = emptyMap()) =
            event(mapOf("type" to "EVIDENCE_OMITTED", "name" to name, "bytes" to size, "sha256" to hash, "reason" to reason, "metadata" to metadata))
        private fun response(label: String, response: ScheduleResponse) {
            val file = File(output, "responses/${next().toString().padStart(4, '0')}.body")
            val original = sha(response.bytes)
            val body = writeSafeBytes(file, response.bytes, response.contentType, this,
                sensitiveBinaryUrl = SensitiveKeys.sensitiveQuery(response.url))
            event(mapOf("type" to label, "url" to response.url, "contentType" to response.contentType,
                "bytes" to response.bytes.size, "originalSha256" to original,
                "bodyFile" to file.takeIf { it.isFile }?.relativeTo(output)?.path,
                "writtenSha256" to file.takeIf { it.isFile }?.let { sha(it) },
                "bodyEvidence" to body,
                "bodyScope" to "Known scalar credential keys only. Same-charset byte redaction; exact original bytes iff written SHA equals original SHA. Omission is explicit."))
        }
        fun transport(real: ScheduleTransport): ScheduleTransport = object : ScheduleTransport {
            override suspend fun fetch(url: String): ScheduleResponse = call(url, null) { real.fetch(url) }
            override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse = call(url, maximumBytes) { real.fetchBounded(url, maximumBytes) }
            private suspend fun call(url: String, bound: Int?, operation: suspend () -> ScheduleResponse): ScheduleResponse {
                val begin = System.nanoTime()
                event(mapOf("type" to "GET_START", "url" to url, "maximumBytes" to bound))
                try { return operation().also { response("GET_RESPONSE", it) } }
                catch (error: Exception) { event(mapOf("type" to "GET_ERROR", "url" to url, "error" to error.javaClass.name,
                    "message" to error.message, "cancelled" to (error is CancellationException))); throw error }
                finally { event(mapOf("type" to "GET_END", "url" to url, "elapsedMillis" to (System.nanoTime() - begin) / 1_000_000)) }
            }
        }
        suspend fun post(queries: List<String>, operation: suspend () -> List<ScheduleResponse>): List<ScheduleResponse> {
            event(mapOf("type" to "MOSCOW_DIRECTORY_SEARCH_START", "queries" to queries,
                "scope" to "Production transport bootstrap, headers and CSRF remain internal; returned responses recorded"))
            try { return operation().also { results -> results.forEach { response("MOSCOW_DIRECTORY_SEARCH_RESPONSE", it) } } }
            catch (error: Exception) { event(mapOf("type" to "MOSCOW_DIRECTORY_SEARCH_ERROR", "error" to error.javaClass.name)); throw error }
        }
        fun document(doc: ScheduleDocument?) {
            if (doc == null) { event(mapOf("type" to "DOCUMENT_NULL")); return }
            val name = "documents/${next().toString().padStart(4, '0')}.json"
            writeSafeBytes(File(output, name), gson.toJson(sanitized(gson.toJsonTree(doc))).toByteArray(), "application/json", this)
            event(mapOf("type" to "ANDROID_EXTRACTOR_DOCUMENT", "sourceUrl" to doc.sourceUrl, "format" to doc.format, "pages" to doc.pages.size, "file" to name.takeIf { File(output, it).isFile }))
        }
        @Synchronized fun nativeOcr(capture: NativeScheduleOcrCapture) {
            val metadata = mapOf("stage" to capture.stage, "sourceUrl" to capture.sourceUrl,
                "width" to capture.width, "height" to capture.height, "characters" to capture.hocr.length,
                "coordinateScope" to if (capture.stage == "initial") "SOURCE_BITMAP"
                    else "CAPTURE_BITMAP_ONLY_NO_SOURCE_OFFSET")
            if (!Regex("[A-Za-z0-9-]{1,64}").matches(capture.stage) || capture.hocr.length > 1_000_000) {
                event(mapOf("type" to "EVIDENCE_OMITTED", "reason" to "OCR_STAGE_OR_CHARACTER_LIMIT",
                    "metadata" to metadata, "sha256" to null, "hashScope" to "NOT_COMPUTED_FOR_REJECTED_CAPTURE"))
                return
            }
            val bytes = capture.hocr.toByteArray(Charsets.UTF_8)
            if (bytes.size > 4_000_000 - nativeOcrBytes) {
                omitted("native-ocr", bytes.size, sha(bytes), "CASE_OCR_EVIDENCE_BUDGET", metadata)
                return
            }
            nativeOcrBytes += bytes.size
            val name = "ocr/" + next().toString().padStart(4, '0') + "-" + capture.stage + ".hocr"
            val file = File(output, name)
            val evidence = writeSafeBytes(file, bytes, "application/xhtml+xml; charset=utf-8", this,
                sensitiveBinaryUrl = SensitiveKeys.sensitiveQuery(capture.sourceUrl))
            event(mapOf("type" to "NATIVE_OCR_PASS", "metadata" to metadata, "originalSha256" to sha(bytes),
                "file" to name.takeIf { file.isFile }, "writtenSha256" to file.takeIf { it.isFile }?.let { sha(it) },
                "bodyEvidence" to evidence,
                "scope" to "Read-only completed-pass evidence. No recognition, parser or authority override. Missing exports remain explicit."))
        }
        fun progress(value: ScheduleImportProgress) = event(mapOf("type" to "PRODUCTION_PROGRESS", "progress" to value, "at" to Instant.now().toString()))
        fun finish() = event(mapOf("type" to "CASE_FINISHED", "at" to Instant.now().toString()))
    }

    internal class OwnedContext(private val original: Context, id: String) : ContextWrapper(original) {
        val prefix = "feoria12-holdout-$id-"
        val owned = File(original.cacheDir, "feoria12-holdout-$id").also { check(!it.exists() && it.mkdirs()) }
        private val names = linkedSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(owned, "files").also { it.mkdirs() }
        override fun getCacheDir(): File = File(owned, "cache").also { it.mkdirs() }
        override fun getDataDir(): File = owned
        override fun getNoBackupFilesDir(): File = File(owned, "no-backup").also { it.mkdirs() }
        override fun getCodeCacheDir(): File = File(owned, "code-cache").also { it.mkdirs() }
        override fun getExternalFilesDir(type: String?): File = File(owned, "external/${safe(type ?: "root")}").also { it.mkdirs() }
        override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(getExternalFilesDir(type))
        override fun getExternalCacheDir(): File = File(owned, "external-cache").also { it.mkdirs() }
        override fun getExternalCacheDirs(): Array<File> = arrayOf(getExternalCacheDir())
        override fun getDir(name: String, mode: Int): File = File(owned, "dirs/${safe(name)}").also { it.mkdirs() }
        @Synchronized override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val full = prefix + safe(name); names += full
            return original.getSharedPreferences(full, Context.MODE_PRIVATE)
        }
        override fun deleteSharedPreferences(name: String): Boolean = original.deleteSharedPreferences(prefix + safe(name))
        override fun getFileStreamPath(name: String): File = File(filesDir, safe(name))
        override fun openFileInput(name: String): FileInputStream = FileInputStream(getFileStreamPath(name))
        override fun openFileOutput(name: String, mode: Int): FileOutputStream = FileOutputStream(getFileStreamPath(name), mode and Context.MODE_APPEND != 0)
        override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()
        override fun fileList(): Array<String> = filesDir.list() ?: emptyArray()
        @Synchronized fun preferenceNames(): List<String> = names.toList()
        fun basePreferenceFile(name: String): File { require(name.startsWith(prefix)); return File(original.applicationInfo.dataDir, "shared_prefs/$name.xml") }
        fun preferenceFile(name: String): File = basePreferenceFile(prefix + safe(name))
        data class CleanupResult(val success: Boolean, val attemptedPreferences: List<String>,
            val workspaceAttempted: Boolean, val errors: List<String>)
        fun cleanup(): CleanupResult {
            val errors = mutableListOf<String>()
            val attempted = mutableListOf<String>()
            val ownNames = try { preferenceNames() } catch (error: Exception) {
                errors += "preference-list:${error.javaClass.simpleName}"; emptyList()
            }
            ownNames.forEach { name ->
                if (!name.startsWith(prefix)) { errors += "outside-UUID-allowlist"; return@forEach }
                attempted += name
                // Each operation has its own gate: a failed clear/delete cannot suppress later attempts.
                try {
                    if (!original.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) errors += "$name:clear-not-committed"
                } catch (error: Exception) { errors += "$name:clear:${error.javaClass.simpleName}" }
                try { original.deleteSharedPreferences(name) }
                catch (error: Exception) { errors += "$name:delete:${error.javaClass.simpleName}" }
                try {
                    if (basePreferenceFile(name).exists() || File(basePreferenceFile(name).path + ".bak").exists()) errors += "$name:still-present"
                } catch (error: Exception) { errors += "$name:verify:${error.javaClass.simpleName}" }
            }
            var workspaceAttempted = false
            try {
                check(owned.parentFile.canonicalFile == original.cacheDir.canonicalFile && owned.name == prefix.removeSuffix("-"))
                workspaceAttempted = true
                if (!owned.deleteRecursively() || owned.exists()) errors += "workspace:still-present"
            } catch (error: Exception) { errors += "workspace:${error.javaClass.simpleName}" }
            return CleanupResult(errors.isEmpty() && attempted.size == ownNames.size && workspaceAttempted,
                attempted, workspaceAttempted, errors)
        }

        private fun safe(name: String): String { require(name.matches(Regex("[A-Za-z0-9_.-]{1,140}"))); return name }
    }

    companion object {
        private const val SEALED_ASSET = "release12/holdout-sealed.json"
        private const val PINS_ASSET = "release12/holdout-source-pins.json"
        private const val SEALED_SHA = "e032d861eaf36e9c42ac65b121d6c1a8f7d29979dbe46eaa2f26c59a317d6cbf"
        private const val CATALOG_SHA = "3002f107dc408eec1fcebd31d53d9373b9ac5af6f1fcaf5094d35615e9abc31d"
    }
}


/** Nulls also exist for a case that exits before creating its repository. */
internal fun holdout12PrepareSourceReceipt(receipt: MutableMap<String, Any?>) {
    receipt["workflowPolicy"] = Holdout12SourceCoordinator.WORKFLOW_POLICY
    receipt["initialSourceState"] = null
    receipt["normalRetry"] = linkedMapOf<String, Any?>("before" to null, "after" to null,
        "eligible" to null, "decision" to "SKIP", "skipReason" to "INITIAL_NOT_COMPLETED", "attempted" to 0, "completed" to 0)
    receipt["finalSourceState"] = null
    receipt["lastCompletedSourceState"] = null
    receipt["sourceWorkflowStatus"] = "NOT_RUN"
    receipt["cachedAfterRun"] = null
    receipt["sourceActions"] = emptyList<Map<String, Any?>>()
    receipt["sourceActionCounts"] = Holdout12SourceCoordinator.ACTION_NAMES.associateWith {
        mapOf("attempted" to 0, "completed" to 0)
    }
}

/** Test-only workflow shared by the live diagnostic and hermetic InternetSchedules fakes. */
internal fun holdout12Usable(state: InternetScheduleState, now: Instant) = state.status == InternetScheduleStatus.READY &&
    !state.sourceNeedsVerification && !state.publicationRejected && state.publication?.let { it.isFresh(now) && it.events.isNotEmpty() } == true

internal class Holdout12SourceCoordinator(
    private val repo: InternetSchedules,
    private val selected: Parish,
    private val now: Instant,
    private val receipt: MutableMap<String, Any?>,
    private val counters: () -> Map<String, Int>,
    private val persist: () -> Unit,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val actions = mutableListOf<MutableMap<String, Any?>>()
    private val counts = ACTION_NAMES.associateWith { linkedMapOf("attempted" to 0, "completed" to 0) }
    private val retry = linkedMapOf<String, Any?>("before" to null, "after" to null,
        "eligible" to null, "decision" to "PENDING", "skipReason" to null, "attempted" to 0, "completed" to 0)

    suspend fun run(): InternetScheduleState {
        holdout12PrepareSourceReceipt(receipt)
        receipt["workflowPolicy"] = WORKFLOW_POLICY
        receipt["sourceActions"] = actions
        receipt["sourceActionCounts"] = counts
        receipt["initialSourceState"] = null
        receipt["normalRetry"] = retry
        receipt["finalSourceState"] = null
        receipt["lastCompletedSourceState"] = null
        receipt["sourceWorkflowStatus"] = "STARTED"
        receipt["transportCounterScope"] = COUNTER_CAVEAT
        val workflowStarted = monotonicNanos()
        receipt["sourceWorkflowStartedAtMonotonicNanos"] = workflowStarted
        receipt["sourceWorkflowCountersBefore"] = snapshotCounters()
        var failure: Throwable? = null
        try {
            persist()
            var state = action("initialRefresh", repo.cached(selected)) {
                repo.refresh(selected, now, force = true, sourceOverride = null)
            }
            receipt["initialSourceState"] = state
            retry["before"] = state
            val skip = retrySkipReason(state)
            retry["eligible"] = skip == null
            if (skip == null) {
                // The caller owns the only source deadline. Never create a new budget here.
                currentCoroutineContext().ensureActive()
                retry["decision"] = "ATTEMPT"
                state = action("normalRetry", state) {
                    repo.refresh(selected, now, force = true, sourceOverride = null)
                }
                retry["after"] = state
            } else {
                retry["decision"] = "SKIP"
                retry["skipReason"] = skip
            }
            // Reevaluate the existing production gate on the returned retry state.
            if (!holdout12Usable(state, now) && permitsOtherSourceSearch(selected, state)) {
                state = action("explicitSearchOtherSources", state) { repo.searchOtherSources(selected, now) }
            }
            val tried = mutableSetOf<String>()
            val triedUrls = mutableSetOf<String>()
            repeat(2) {
                if (!holdout12Usable(state, now)) {
                    val candidate = scheduleCandidateGroups(selected, state)
                        .filter { !it.alreadyChosen && !it.blockedByHistory }
                        .mapNotNull { it.confirmationCandidate }
                        .firstOrNull { it.id !in tried && it.sourceUrl !in triedUrls }
                    if (candidate != null) {
                        tried += candidate.id
                        triedUrls += candidate.sourceUrl
                        state = action("simulatedCandidateConfirmation", state, mapOf("candidate" to candidate,
                            "scope" to "Production eligible first displayed group; no human verification")) {
                            repo.confirmCandidate(selected, candidate.id, now)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            receipt["finalSourceState"] = state
            receipt["sourceWorkflowStatus"] = "COMPLETED"
            return state
        } catch (error: Throwable) {
            failure = error
            receipt["finalSourceState"] = null
            receipt["sourceWorkflowStatus"] = status(error)
            receipt["sourceWorkflowErrorClass"] = error.javaClass.name
            if (retry["decision"] == "PENDING" || retry["decision"] == "ATTEMPT" && retry["attempted"] == 0) {
                retry["decision"] = "SKIP"
                retry["skipReason"] = when {
                    receipt["initialSourceState"] == null -> "INITIAL_NOT_COMPLETED"
                    error is TimeoutCancellationException -> "SOURCE_BUDGET_EXHAUSTED"
                    error is CancellationException -> "CANCELLED"
                    else -> "INITIAL_NOT_COMPLETED"
                }
            }
            throw error
        } finally {
            receipt["sourceWorkflowElapsedMillis"] = (monotonicNanos() - workflowStarted) / 1_000_000
            val after = snapshotCounters()
            receipt["sourceWorkflowCountersAfter"] = after
            @Suppress("UNCHECKED_CAST")
            val before = receipt["sourceWorkflowCountersBefore"] as Map<String, Int>
            receipt["sourceWorkflowCounterDeltas"] = delta(before, after)
            persistWithoutMasking(failure)
        }
    }

    private fun retrySkipReason(state: InternetScheduleState): String? = when {
        state.loading -> "INITIAL_LOADING"
        state.checkedAt.isBlank() -> "INITIAL_NO_CHECKED_AT"
        state.customSource.isNotBlank() -> "CUSTOM_SOURCE_PRESENT"
        state.status !in setOf(InternetScheduleStatus.NO_WEBSITE, InternetScheduleStatus.UNAVAILABLE) -> "STATUS_NOT_ELIGIBLE"
        holdout12Usable(state, now) -> "USABLE_INITIAL"
        else -> null
    }

    private suspend fun action(name: String, before: InternetScheduleState,
        extra: Map<String, Any?> = emptyMap(), operation: suspend () -> InternetScheduleState): InternetScheduleState {
        currentCoroutineContext().ensureActive()
        val started = monotonicNanos()
        val beforeCounters = snapshotCounters()
        val entry = linkedMapOf<String, Any?>("action" to name, "ordinal" to actions.size + 1,
            "status" to "STARTED", "beforeState" to before, "returnedState" to null,
            "startedAt" to Instant.now().toString(), "startedAtMonotonicNanos" to started,
            "elapsedMillis" to null, "countersBefore" to beforeCounters, "countersAfter" to null,
            "counterDeltas" to null, "errorClass" to null).apply { putAll(extra) }
        actions += entry
        val count = counts.getValue(name)
        count["attempted"] = count.getValue("attempted") + 1
        if (name == "normalRetry") retry["attempted"] = 1
        var failure: Throwable? = null
        try {
            // A failed STARTED write prevents the action rather than hiding its start.
            persist()
            return operation().also { returned ->
                entry["returnedState"] = returned
                entry["status"] = "COMPLETED"
                count["completed"] = count.getValue("completed") + 1
                if (name == "initialRefresh") receipt["initialSourceState"] = returned
                if (name == "normalRetry") {
                    retry["completed"] = 1
                    retry["after"] = returned
                }
                receipt["lastCompletedSourceState"] = returned
            }
        } catch (error: Throwable) {
            failure = error
            entry["status"] = status(error)
            entry["errorClass"] = error.javaClass.name
            throw error
        } finally {
            entry["elapsedMillis"] = (monotonicNanos() - started) / 1_000_000
            val after = snapshotCounters()
            entry["countersAfter"] = after
            entry["counterDeltas"] = delta(beforeCounters, after)
            persistWithoutMasking(failure)
        }
    }

    private fun persistWithoutMasking(failure: Throwable?) {
        try { persist() } catch (writeError: Throwable) {
            if (failure == null) throw writeError
            failure.addSuppressed(writeError)
        }
    }
    private fun snapshotCounters(): Map<String, Int> {
        val observed = counters()
        return COUNTER_NAMES.associateWith { observed[it] ?: 0 }
    }
    private fun delta(before: Map<String, Int>, after: Map<String, Int>) =
        COUNTER_NAMES.associateWith { after.getValue(it) - before.getValue(it) }
    private fun status(error: Throwable) = holdout12FailureStatus(error)

    companion object {
        const val WORKFLOW_POLICY = "normal-retry-v2"
        val ACTION_NAMES = listOf("initialRefresh", "normalRetry", "explicitSearchOtherSources", "simulatedCandidateConfirmation")
        val COUNTER_NAMES = listOf("GET_START", "MOSCOW_DIRECTORY_SEARCH_START")
        const val COUNTER_CAVEAT = "Observable GET wrapper starts and directory search wrapper calls only; not total physical HTTP/bootstrap requests"
    }
}

internal fun holdout12FailureStatus(error: Throwable) = when (error) {
    is TimeoutCancellationException -> "TIMEOUT"
    is CancellationException -> "CANCELLED"
    else -> "ERROR"
}

/** Also covers cancellation while the caller closes the progress collector after the coordinator returns. */
internal fun holdout12MarkSourceInterrupted(receipt: MutableMap<String, Any?>, error: Throwable) {
    if (receipt["activePhase"] != "SOURCE") return
    receipt["finalSourceState"] = null
    receipt["sourceWorkflowStatus"] = holdout12FailureStatus(error)
    receipt["sourceWorkflowErrorClass"] = error.javaClass.name
}

/** Safe selected-batch summary: does not carry selected objects or unrun batch rows. */
internal fun holdout12SourceSummary(rows: List<Map<String, Any?>>): Map<String, Any?> {
    fun state(row: Map<String, Any?>, field: String) = (row[field] as? InternetScheduleState)?.status?.name ?: "NOT_COMPLETED"
    @Suppress("UNCHECKED_CAST")
    fun retry(row: Map<String, Any?>) = row["normalRetry"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    fun count(row: Map<String, Any?>, action: String, field: String): Int =
        ((row["sourceActionCounts"] as? Map<String, Map<String, Int>>)?.get(action)?.get(field)) ?: 0
    @Suppress("UNCHECKED_CAST")
    fun transport(row: Map<String, Any?>, name: String) = (row["sourceWorkflowCounterDeltas"] as? Map<String, Int>)?.get(name) ?: 0
    val eligible = rows.filter { retry(it)["eligible"] == true }
    @Suppress("UNCHECKED_CAST")
    fun retryAfter(row: Map<String, Any?>) = (retry(row)["after"] as? InternetScheduleState)?.status?.name ?: "NOT_COMPLETED"
    @Suppress("UNCHECKED_CAST")
    fun retryTimeout(row: Map<String, Any?>) = (row["sourceActions"] as? List<Map<String, Any?>>).orEmpty()
        .any { it["action"] == "normalRetry" && it["status"] == "TIMEOUT" }
    return linkedMapOf("workflowPolicy" to Holdout12SourceCoordinator.WORKFLOW_POLICY,
        "denominator" to rows.size,
        "initialOutcomes" to rows.groupingBy { state(it, "initialSourceState") }.eachCount(),
        "retryEligible" to eligible.size,
        "retryAttempted" to rows.sumOf { count(it, "normalRetry", "attempted") },
        "retryCompleted" to rows.sumOf { count(it, "normalRetry", "completed") },
        "retrySkipped" to rows.count { retry(it)["decision"] == "SKIP" },
        "retryTimeout" to rows.count(::retryTimeout),
        "retrySkipReasons" to rows.filter { retry(it)["decision"] == "SKIP" }.groupingBy { retry(it)["skipReason"].toString() }.eachCount(),
        "retryTransitions" to eligible.groupingBy { state(it, "initialSourceState") + "->" + retryAfter(it) }.eachCount(),
        "finalSourceStatuses" to rows.groupingBy { state(it, "finalSourceState") }.eachCount(),
        "finalOutcomes" to rows.groupingBy { it["sourceOutcome"]?.toString() ?: it["state"]?.toString() ?: "NOT_COMPLETED" }.eachCount(),
        "sourceWorkflowStatuses" to rows.groupingBy { it["sourceWorkflowStatus"]?.toString() ?: "NOT_RUN" }.eachCount(),
        "actionCounts" to Holdout12SourceCoordinator.ACTION_NAMES.associateWith { name ->
            mapOf("attempted" to rows.sumOf { count(it, name, "attempted") }, "completed" to rows.sumOf { count(it, name, "completed") }) },
        "observableTransportCounters" to Holdout12SourceCoordinator.COUNTER_NAMES.associateWith { name -> rows.sumOf { transport(it, name) } },
        "transportCounterScope" to Holdout12SourceCoordinator.COUNTER_CAVEAT,
        "readyInterpretation" to "PENDING_PRIMARY_SOURCE_REVIEW", "applicationSuccess" to false)
}

/** Test-only selection. A known development replay is never an independent coverage cohort. */
internal fun holdout12SelectedIndices(batchIndices: List<Int>, batch: Int, rawArg: String?): List<Int> {
    require(batch in 1..5) { "A fixed batch from 1..5 is required" }
    require(rawArg == null || batch in 1..3) { "Known-case replay is limited to batches 1..3" }
    val requested = rawArg?.let { value ->
        require(value.matches(Regex("(?:0|[1-9][0-9]?)"))) { "holdoutKnownIndex must be one canonical index from 0..99" }
        value.toInt()
    }
    require(batchIndices.size == 20 && batchIndices.all { it in 0..99 } && batchIndices.distinct().size == 20) {
        "The original batch must contain exactly 20 unique bounded indices"
    }
    if (requested == null) return batchIndices.toList()
    require(requested in batchIndices) { "The requested known index must already belong to this batch" }
    return listOf(requested)
}
