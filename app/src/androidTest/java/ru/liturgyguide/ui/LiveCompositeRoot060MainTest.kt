package ru.liturgyguide.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Xml
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.model.*
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Live secondary-user Main only. No seed, dependency/URL/clock/GPS override or preference writes. */
@RunWith(AndroidJUnit4::class)
class LiveCompositeRoot060MainTest {
    @get:Rule val compose = createEmptyComposeRule() // Never launches Main before the method's guard.
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    private val parishId = "osm:way:367166582"
    private val date = "2026-11-15"
    private val keys = listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY).map { ServiceBookKey(date, it, "10") }
    private var owner: File? = null
    private val container get() = run { check(owner != null); (context.applicationContext as LiturgyGuideApplication).container }
    private val store get() = run { check(owner != null); BookReadingStore(context.getSharedPreferences("service_book_reading", Context.MODE_PRIVATE)) }
    private data class Part(val key: ServiceBookKey, val position: BookReadingPosition, val editionFile: String, val editionSha: String)
    private data class Snapshot(val uid: Int, val pid: Int, val completedAt: String, val last: BookReadingPosition, val parts: List<Part>)

    @Test fun onlineUiFindsExactChurchConfirmsObservedOfficialRowAndReadsAllThreeSavedParts() = runBlocking<Unit> {
        val output = guarded("online", "allow-public-main")
        val receipt = linkedMapOf<String, Any?>("status" to "NOT_COMPLETED", "uid" to Process.myUid(), "pid" to Process.myPid(),
            "applicationSuccess" to false, "sourceOverride" to false, "captures" to mutableListOf<String>())
        var scenario: ActivityScenario<MainActivity>? = null
        var progressJob: Job? = null
        try {
            for (name in listOf("guide_preferences", "internet_schedule_cache", "service_book_reading", "book_dedication"))
                assertTrue("Fresh QA profile must have empty $name", context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
            assertTrue(File(context.filesDir, "service-books-v2").listFiles().orEmpty().isEmpty())
            assertTrue(container.internetSchedules is InternetScheduleRepository); assertTrue(container.serviceBooks is ServiceBookRepository)
            receipt["networkBefore"] = network(requireAbsent = false)
            val phases = CopyOnWriteArrayList<ScheduleImportProgress>(); val started = CompletableDeferred<Unit>()
            progressJob = launch(Dispatchers.Default) { requireNotNull(container.internetSchedules.progress).collect { phases += it; started.complete(Unit) } }
            withTimeout(5_000) { started.await() }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            await(30_000) { exists("main_content") || exists("church_content") }
            tap("nav_church"); await(20_000) { exists("church_content") || exists("discovery_query") }
            if (exists("church_content")) reveal("change_church", "church_content").performClick()
            if (!exists("discovery_query")) { await(20_000) { exists("discover_churches") || exists("discovery_query") }; if (exists("discover_churches")) tap("discover_churches") }
            awaitTag("discovery_query"); reveal("discovery_mode_name", "discovery_content").performClick()
            reveal("discovery_query", "discovery_content").performTextReplacement("Сретения Господня Закрытая аллея")
            reveal("discovery_submit", "discovery_content").assertIsEnabled().performClick()
            await(90_000) { runCatching { reveal("discovery_church_$parishId", "discovery_content"); true }.getOrDefault(false) }
            val result = reveal("discovery_church_$parishId", "discovery_content").assertTextContains("Церковь Сретения Господня", substring = true)
            receipt["actualSearchResult"] = nodeText(result); capture(output, "01-search")
            result.performClick(); await(20_000) { exists("confirm_church_change") || guide().getString("church_id", null) == parishId }
            if (exists("confirm_church_change")) tap("confirm_church_change")
            await(20_000) { guide().getString("church_id", null) == parishId && exists("church_content") }
            val record = ChurchDirectoryStore(guide()).records().single { it.id == parishId }; receipt["actualSelected"] = record
            assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, record.origin)
            assertEquals("Live identity differs; do not repair it in the test", "Церковь Сретения Господня", record.name)
            assertEquals("Закрытая аллея, 19", record.address); assertEquals("", record.city); assertTrue(record.website.isBlank())
            assertEquals(55.95794645, requireNotNull(record.latitude), 1e-8); assertEquals(37.59411495, requireNotNull(record.longitude), 1e-8)
            val parish = record.asParish()
            await(240_000) { runCatching { reveal("refresh_schedule", "church_content").assertIsEnabled(); true }.getOrDefault(false) }
            var mark = phases.size; reveal("refresh_schedule", "church_content").performClick()
            await(240_000) { phases.drop(mark).any { it.parishId == parishId && it.phase == ScheduleImportPhase.FINISHED } }
            receipt["afterNormalRefresh"] = container.internetSchedules.cached(parish)
            check(container.internetSchedules.cached(parish).publication == null) { "Live automatic route differs; review separately instead of forcing a candidate path" }
            mark = phases.size; reveal("find_other_schedule_sources", "church_content").assertIsEnabled().performClick()
            await(240_000) { phases.drop(mark).any { it.parishId == parishId && it.phase == ScheduleImportPhase.FINISHED } }
            if (exists("source_search_review")) tap("source_search_review")
            await(30_000) {
                val live = container.internetSchedules.cached(parish)
                exists("schedule_candidate_choices") && !live.loading && scheduleCandidateGroups(parish, live).any { group ->
                    group.rows.any { it.provider == "official_map" && it.providerObjectId == "31383" }
                }
            }
            val before = container.internetSchedules.cached(parish)
            val group = scheduleCandidateGroups(parish, before).single { it.rows.any { row -> row.provider == "official_map" && row.providerObjectId == "31383" } }
            val candidate = requireNotNull(group.confirmationCandidate)
            receipt["reviewedGroup"] = group
            assertTrue(group.conflicts.isEmpty()); assertFalse(group.blockedByHistory)
            assertEquals("official_map", candidate.provider); assertEquals("31383", candidate.providerObjectId)
            assertEquals("Храм Сретения Господня д. Афанасово", candidate.name)
            assertEquals("http://afanasovo.cerkov.ru/", group.sourceUrl)
            val rowId = group.representative.id // Actual IDs are SHA values, not provider:objectId aliases.
            reveal("schedule_candidate_url_$rowId", "church_content").assertTextEquals(group.sourceUrl)
            reveal("source_details_$rowId", "church_content").performClick(); capture(output, "02-source-review")
            reveal("open_candidate_$rowId", "church_content").performClick()
            compose.waitUntil(30_000) { browserOwnSiteVisible() }
            capture(output, "03-own-site")
            check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitTag("church_content")
            compose.onNodeWithTag("schedule_website_input").assertDoesNotExist()
            mark = phases.size; reveal("confirm_candidate_${candidate.id}", "church_content").assertIsEnabled().assertTextEquals("Выбрать сайт").performClick()
            await(240_000) { phases.drop(mark).any { it.parishId == parishId && it.phase == ScheduleImportPhase.FINISHED } && container.internetSchedules.cached(parish).publication != null }
            val state = container.internetSchedules.cached(parish); receipt["actualSchedule"] = state
            assertEquals(ScheduleBindingMethod.USER_CONFIRMED, state.siteBinding?.method)
            assertEquals("31383", state.siteBinding?.candidateConfirmation?.candidate?.providerObjectId)
            assertEquals("https://afanasovo.cerkov.ru/raspisanie-bogosluzhenij/", state.publication?.sourceUrl)
            assertTrue(state.customSource.isBlank() && state.configuredWebsite.isBlank() && !state.sourceNeedsVerification)
            val today = LocalDate.now(ZoneId.of(parish.timezone)); check(today.toString() == date || today.plusDays(1).toString() == date) { "The planned occurrence is not selectable today/tomorrow; do not change the clock" }
            reveal(if (today.toString() == date) "schedule_today" else "schedule_tomorrow", "church_content").performClick()
            val event = requireNotNull(state.publication).events.single { it.date == date && it.time == "09:40" }
            assertEquals("Часы. Божественная Литургия", event.title)
            reveal("church_service_time", "church_content").assertTextEquals("09:40")
            val open = reveal("church_open_service", "church_content").assertTextEquals("Открыть часы и литургию")
            receipt["clickedOccurrence"] = event; capture(output, "04-published-occurrence")
            receipt["serviceTapElapsedRealtime"] = SystemClock.elapsedRealtime(); open.performClick()
            await(240_000) { exists("book_content") || exists("book_load_error") }; compose.onNodeWithTag("book_load_error").assertDoesNotExist()
            await(20_000) { store.last(parishId)?.key == keys.first() }
            assertEquals(requireNotNull(ServiceBookSelection.resolve(ParishService(event.date, event.time, event.title, event.kind), parish)).journeyContext, saved().journeyContext)
            receipt["entry20Seconds"] = "NOT_MEASURED; first visible prayer timestamp is not inferred from book_content"
            reveal("book_open_calendar", "book_content").performClick(); awaitTag("book_calendar")
            // Opening this sheet starts an asynchronous local inspection. Its container
            // can exist before the three statuses and preparation action are composed.
            await(30_000) { runCatching {
                for (key in keys) reveal("book_offline_part_${key.worship.name}", "book_calendar")
                reveal("book_prepare_service", "book_calendar").assertTextEquals("Сохранить три текста").assertIsEnabled()
                true
            }.getOrDefault(false) }
            reveal("book_prepare_service", "book_calendar").performClick()
            await(240_000) { runCatching {
                for (key in keys) reveal("book_offline_part_${key.worship.name}", "book_calendar")
                    .assertTextEquals("${key.worship.title} - ${if (key.worship == WorshipKind.LITURGY)
                        "сохранена" else "сохранен - требуется сверка"}")
                // Final labels can be emitted before prepare's final inspection.
                // This review action exists only after preparing=false and is enabled
                // only after busy/refreshing have both ended. Do not click or accept it.
                reveal("book_review_part_HOUR3", "book_calendar").assertIsEnabled()
                true
            }.getOrDefault(false) }
            val stored = BookOfflinePreparation.inspect(book(), saved().journeyContext, parishId, container.serviceBooks, store)
            assertEquals(keys, stored.parts.map { it.key }); assertNull(stored.contextError)
            assertEquals(3, stored.storedCount); assertTrue(stored.parts.all { it.hasStoredEdition }); assertFalse(stored.isComplete)
            for (part in stored.parts) {
                val durable = requireNotNull(container.serviceBooks.cachedVersion(part.key, requireNotNull(part.savedHash)))
                assertEquals(part.key, durable.key); assertEquals(part.savedHash, durable.contentHash); assertTrue(ServiceBookIntegrity.valid(durable))
                if (part.key.worship == WorshipKind.LITURGY) assertEquals(BookOfflinePartStatus.READY, part.status)
                else {
                    assertEquals(BookOfflinePartStatus.REVIEW_REQUIRED, part.status)
                    assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(durable, inMorningJourney = true).status)
                }
            }
            receipt["storedAvailability"] = stored
            receipt["storedScope"] = "Three editions saved for independent reading; both Hours require source review. No local hymn/order match claimed"
            compose.onNodeWithTag("book_offline_complete").assertDoesNotExist(); dismiss()
            val readingChecks = mutableListOf<Map<String, Any?>>()
            for (key in keys) { switchPart(key); readingChecks += readPairedPrayer(search = true) }
            switchPart(keys.first()); val snapshot = snapshot()
            assertEquals(keys.first(), snapshot.last.key); assertTrue(snapshot.last.fraction > 0f)
            receipt["readings"] = readingChecks; receipt["sourcePhases"] = phases.toList(); receipt["postalOwnSiteProof"] = "NOT_PROVEN"
            json(File(output, "state.json"), snapshot); capture(output, "05-online-reading"); receipt["status"] = "ONLINE_UI_COMPLETE_PENDING_ROOT_REVIEW"
        } finally {
            runCatching { capture(output, "final") }; scenario?.close(); progressJob?.cancelAndJoin()
            receipt["finishedAt"] = Instant.now().toString(); json(File(output, "receipt.json"), receipt)
        }
    }

    @Test fun coldOfflineMainRestoresExactThreeEditionsAndRealHourThreeCursor() {
        val output = guarded("cold", "allow-cold-offline")
        val receipt = linkedMapOf<String, Any?>("status" to "NOT_COMPLETED", "uid" to Process.myUid(), "pid" to Process.myPid(), "applicationSuccess" to false)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val previous = gson.fromJson(fixed(requireNotNull(owner), "online/state.json").readText(), Snapshot::class.java)
            assertEquals(Process.myUid(), previous.uid); assertNotEquals(previous.pid, Process.myPid())
            assertEquals(keys, previous.parts.map { it.key }); assertEquals(keys.first(), previous.last.key)
            val boundary = JsonParser.parseString(fixed(requireNotNull(owner), "cold-boundary.json").readText()).asJsonObject
            assertEquals(previous.pid, boundary["onlinePid"].asInt); assertEquals(previous.uid, boundary["uid"].asInt)
            assertEquals(requireNotNull(owner).name.removePrefix("feoria12-composite-main-"), boundary["ownerUuid"].asString)
            assertTrue(boundary["oldPidAbsent"].asBoolean && boundary["globalNetworkAbsent"].asBoolean)
            assertTrue(Instant.parse(boundary["forceStoppedAt"].asString).isAfter(Instant.parse(previous.completedAt)))
            receipt["rootColdBoundary"] = boundary; receipt["networkBeforeMain"] = network(requireAbsent = true)
            for (part in previous.parts) assertPart(part)
            sameCursor(previous.last, saved())
            scenario = ActivityScenario.launch(MainActivity::class.java)
            await(30_000) { exists("main_content") || exists("book_content") }
            if (exists("book_content")) tap("book_back")
            reveal("resume_book", "main_content").performClick(); awaitTag("book_content")
            await(20_000) { store.last(parishId)?.key == keys.first() }; sameCursor(previous.last, saved())
            val checks = mutableListOf<Map<String, Any?>>()
            for (part in previous.parts) { switchPart(part.key); sameCursor(part.position, saved()); assertPart(part); checks += readPairedPrayer(search = false) }
            switchPart(keys.first()); sameCursor(previous.last, saved()); for (part in previous.parts) assertPart(part)
            receipt["networkAfterReading"] = network(requireAbsent = true)
            receipt["readings"] = checks; receipt["restoredState"] = snapshot()
            receipt["networkScope"] = "No calling-UID active/default/allNetworks; global radios/old-PID absence verified in Root journal. HTTP attempts are not intercepted or counted"
            capture(output, "01-cold-offline"); receipt["status"] = "COLD_UI_COMPLETE_PENDING_ROOT_REVIEW"
        } finally { runCatching { capture(output, "final") }; scenario?.close(); receipt["finishedAt"] = Instant.now().toString(); json(File(output, "receipt.json"), receipt) }
    }

    private fun guarded(stage: String, optIn: String): File {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit secondary-user live Main opt-in", args.getString("root060Composite") == optIn)
        check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE == "ranchu")
        val actualUid = Process.myUid(); val user = actualUid / 100_000
        check(user > 0 && user == requireNotNull(args.getString("root060QaUser")).toInt() && context.applicationInfo.uid == actualUid) { "Owner0 or wrong secondary UID: never launch Main" }
        val uuid = requireNotNull(args.getString("root060OwnerUuid")); require(UUID.fromString(uuid).toString() == uuid)
        val directory = fixed(context.cacheDir.canonicalFile, "feoria12-composite-main-$uuid"); check(directory.isDirectory)
        val marker = fixed(directory, "OWNER.json"); check(marker.length() in 1..4096)
        val json = JsonParser.parseString(marker.readText()).asJsonObject
        check(json["schemaVersion"].asInt == 1 && json["ownerUuid"].asString == uuid && json["qaUserId"].asInt == user &&
            json["uid"].asInt == actualUid && json["packageName"].asString == context.packageName)
        owner = directory
        return fixed(directory, stage).also { check(!it.exists() && it.mkdir()) { "Use a new owned profile/run instead of overwriting evidence" } }
    }
    private fun guide() = run { check(owner != null); context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE) }
    private fun saved() = requireNotNull(store.last(parishId))
    private fun book(position: BookReadingPosition = saved()) = requireNotNull(container.serviceBooks.cachedVersion(position.key, position.contentHash)).also { assertTrue(ServiceBookIntegrity.valid(it)); assertEquals(position.key.sourceUrl(), it.sourceUrl) }
    private fun part(key: ServiceBookKey): Part {
        val position = (store.lookup(key, parishId) as? BookReadingLookup.Found)?.position ?: error("Missing accepted part ${key.canonical()}")
        val name = "${BookText.sha256(key.canonical()).take(32)}-${position.contentHash}.json"
        return Part(key, position, name, sha(File(File(context.filesDir, "service-books-v2"), name)))
    }
    private fun snapshot() = Snapshot(Process.myUid(), Process.myPid(), Instant.now().toString(), saved(), keys.map(::part))
    private fun assertPart(expected: Part) {
        assertTrue(expected.key in keys && expected.position.contentHash.matches(Regex("[a-f0-9]{64}")))
        assertEquals(expected.key, expected.position.key); assertEquals(parishId, expected.position.parishId)
        assertEquals("${BookText.sha256(expected.key.canonical()).take(32)}-${expected.position.contentHash}.json", expected.editionFile)
        assertEquals(expected.editionSha, sha(File(File(context.filesDir, "service-books-v2"), expected.editionFile)))
        assertEquals(expected.position.contentHash, book(expected.position).contentHash)
        sameCursor(expected.position, (store.lookup(expected.key, parishId) as BookReadingLookup.Found).position)
    }
    private fun switchPart(key: ServiceBookKey) {
        if (saved().key == key) return
        tap("book_order"); reveal("book_part_${key.worship.name.lowercase()}", "book_order_list").assertIsEnabled().performClick()
        await(60_000) { exists("book_content") && store.last(parishId)?.key == key }; compose.onNodeWithTag("book_part_switch_error").assertDoesNotExist()
    }
    private fun readPairedPrayer(search: Boolean): Map<String, Any?> {
        selectMode(BookReadingMode.ORIGINAL); settle(); var position = saved(); val book = book(position)
        val block = if (book.key.worship in setOf(WorshipKind.HOUR3, WorshipKind.HOUR6)) book.blocks.single { BookText.sha256(BookText.normalized(it.original)) == HOURS_ORIGINAL }
            else if (!search) book.blocks.single { it.id == position.blockId } else book.chapters.flatMap { chapter -> chapter.blocks.filter {
                it.kind == BookBlockKind.PRAYER && it.russian != null && it.translationSource != null && BookText.normalized(it.original).split(' ').size >= 40 &&
                    !BookVisibilityScope.of(book).isPrivate(it) && BookMeaningBriefCatalog.forPlace(book, chapter, it.id) != null } }.first()
        val russian = requireNotNull(block.russian); assertNotNull(block.translationSource)
        if (book.key.worship != WorshipKind.LITURGY) {
            assertEquals(98, BookText.normalized(block.original).split(' ').size); assertEquals(95, BookText.normalized(russian).split(' ').size)
            assertEquals(HOURS_RUSSIAN, BookText.sha256(BookText.normalized(russian)))
            assertEquals("Перевод иеромонаха Амвросия (Тимрота), Азбука веры", block.translationSource?.title)
        }
        if (search) {
            tap("book_find"); awaitTag("book_search_query")
            val query = if (book.key.worship == WorshipKind.LITURGY) BookText.normalized(BookText.withoutSpeaker(block.original).second).split(' ').take(6).joinToString(" ") else "Иже на всякое время"
            compose.onNodeWithTag("book_search_query").performTextReplacement(query)
            await(45_000) { runCatching { reveal("book_hit_${block.id}", "book_search_sheet"); true }.getOrDefault(false) }
            reveal("book_hit_${block.id}", "book_search_sheet").performClick()
            await(30_000) { !exists("book_search_sheet") && store.last(parishId)?.blockId == block.id }
            compose.onNodeWithTag("book_content").performTouchInput { swipeUp(startY = height * .65f, endY = height * .55f, durationMillis = 300) }
            settle(); assertEquals(block.id, saved().blockId); assertTrue(saved().fraction > 0f && saved().fraction < .85f)
        }
        position = saved(); assertEquals(block.id, position.blockId); assertEquals(BookAnchorKind.BLOCK, position.anchorKind)
        assertEquals(block.original, rendered("book_text_${block.id}"))
        selectMode(BookReadingMode.RUSSIAN); settle(); sameCursor(position, saved(), allowModeChange = true); assertEquals(russian, rendered("book_text_${block.id}"))
        val beforeMeaning = saved(); tap("book_mode_meaning"); awaitTag("book_meaning_sheet")
        val meaning = requireNotNull(BookMeaningContent.capture(book, beforeMeaning)); assertEquals(beforeMeaning, saved())
        compose.onNodeWithTag("book_meaning_title").assertTextEquals(meaning.chapterTitle)
        meaning.brief?.let { compose.onNodeWithTag("book_meaning_what").assertTextEquals(it.whatHappens); compose.onNodeWithTag("book_meaning_why").assertTextEquals(it.why) }
        if (book.key.worship != WorshipKind.LITURGY) { assertEquals("hours-common-prayer", meaning.brief?.topicId); assertEquals("Молитва о нашей жизни", meaning.chapterTitle) }
        compose.onNodeWithTag("book_meaning_excerpt_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("book_meaning_excerpt").assertTextEquals(russian)
        tap("book_meaning_close"); await(15_000) { !exists("book_meaning_sheet") }; assertEquals(beforeMeaning, saved())
        selectMode(BookReadingMode.ORIGINAL); settle(); sameCursor(position, saved())
        return mapOf("key" to book.key, "contentHash" to book.contentHash, "cursor" to saved(), "original" to block.original,
            "russian" to russian, "translationSource" to block.translationSource, "meaning" to meaning)
    }
    private fun sameCursor(expected: BookReadingPosition, actual: BookReadingPosition, allowModeChange: Boolean = false) {
        assertEquals(expected.copy(mode = if (allowModeChange) actual.mode else expected.mode,
            lastTextMode = if (allowModeChange) actual.lastTextMode else expected.lastTextMode,
            fraction = actual.fraction, followingGapFraction = actual.followingGapFraction), actual)
        assertTrue(abs(expected.fraction - actual.fraction) <= .015f && abs(expected.followingGapFraction - actual.followingGapFraction) <= .015f)
    }
    private fun selectMode(mode: BookReadingMode) { val tag = "book_mode_${mode.name.lowercase()}"; if (!exists(tag) && mode != BookReadingMode.MEANING) tap("book_mode_menu"); tap(tag) }
    private fun rendered(tag: String): String { val layouts = mutableListOf<TextLayoutResult>(); compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }; return layouts.single().layoutInput.text.text }
    private fun tap(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    private fun reveal(tag: String, list: String): SemanticsNodeInteraction { compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)); return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed() }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun awaitTag(tag: String) = await(30_000) { exists(tag) }
    private fun await(timeout: Long, condition: () -> Boolean) { compose.waitUntil(timeout) { condition() }; compose.waitForIdle() }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(800); compose.waitForIdle() }
    private fun dismiss() { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertCountEquals(1)[0].performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }; settle() }
    private fun nodeText(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
    private fun browserOwnSiteVisible(): Boolean {
        val window = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { val matches = window.findAccessibilityNodeInfosByText("afanasovo.cerkov.ru");
            try { window.packageName?.toString() != context.packageName && matches.isNotEmpty() }
            finally { matches.forEach { it.recycle() } }
        } finally { window.recycle() }
    }
    private fun network(requireAbsent: Boolean): Map<String, Any?> {
        if (Build.VERSION.SDK_INT < 29) error("Shell network probe requires API29+")
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.ACCESS_NETWORK_STATE)
        return try {
            val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
            val active = manager.activeNetwork; val networks = manager.allNetworks.toList()
            if (requireAbsent) check(active == null && networks.isEmpty()) { "A calling-UID network still exists" } else check(active != null)
            mapOf("observedAt" to Instant.now().toString(), "activeDefault" to active?.toString(), "allNetworks" to networks.map { mapOf("id" to it.toString(), "capabilities" to manager.getNetworkCapabilities(it)?.toString()) })
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }
    private fun fixed(root: File, relative: String): File {
        require(relative.split('/').all { it.isNotBlank() && it != "." && it != ".." })
        var path = root
        relative.split('/').forEach { path = File(path, it); check(!Files.isSymbolicLink(path.toPath()) && path.absoluteFile == path.canonicalFile) }
        check(path.canonicalPath.startsWith(root.canonicalPath + File.separator)); return path
    }
    private fun json(file: File, value: Any) { check(owner != null && file.canonicalPath.startsWith(requireNotNull(owner).canonicalPath + File.separator)); check(!file.exists()); file.writeText(gson.toJson(value)) }
    private fun sha(file: File): String { val digest = MessageDigest.getInstance("SHA-256"); file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }; return digest.digest().joinToString("") { "%02x".format(it) } }
    private fun capture(output: File, name: String) {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(output, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { screenshot.recycle() }
        val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow); var nodes = 0
        val writer = File(output, "$name.xml").writer(); val serializer = Xml.newSerializer().apply { setOutput(writer); startDocument("UTF-8", true) }
        fun writeNode(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            check(depth <= 80 && ++nodes <= 12_000); val bounds = Rect(); node.getBoundsInScreen(bounds)
            serializer.startTag(null, "node").attribute(null, "package", node.packageName?.toString().orEmpty()).attribute(null, "text", node.text?.toString().orEmpty())
                .attribute(null, "description", node.contentDescription?.toString().orEmpty()).attribute(null, "bounds", bounds.flattenToString())
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> try { writeNode(child, depth + 1) } finally { child.recycle() } }
            serializer.endTag(null, "node")
        }
        try { writeNode(root, 0); serializer.endDocument() } finally { root.recycle(); writer.close() }
    }
    companion object {
        private const val HOURS_ORIGINAL = "b34617e4f9f0b3d91df6d72415ffcb814b020f7e58791f6aa4083105f3f3b4aa"
        private const val HOURS_RUSSIAN = "5416885d1328e81e4954e9955be79d380bef37115c27d0acae1b1c95e6a19f3b"
    }
}
