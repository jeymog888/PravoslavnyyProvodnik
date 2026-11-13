package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opt-in native MainActivity journey on the owned emulator, with the production AppContainer.
 * No seeded records, preference edits, injected dependencies, clock changes or state cleanup.
 * Leaves the actual selected church, exact books and cursor for root's separate radios-off cold resume.
 */
@RunWith(AndroidJUnit4::class)
class LivePublisherMainFlowTest {
    // An ordinary test run skips before launching MainActivity or reading application state.
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val parishId = "osm:way:155168069"
    private val churchName = "Архиерейское подворье. Храм в честь Вознесения Господня"
    private val source = "http://kaluga-voznesenie.ru/raspisanie-bogosluzhenii/"

    @Test fun actualMainAutomaticallyBindsTheExactKalugaChurchAndLeavesItsPublishedServicePreparedForColdResume() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit real-state Main UI and public-network opt-in required",
            args.getString("livePublisherMain") == "true" && args.getString("feoriaLiveNetwork") == "true")
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("/sdk_gphone")) {
            "Real-state actions are restricted to the owned Android emulator"
        }
        val run = requireNotNull(args.getString("mainPublisherRun")) { "Supply a unique mainPublisherRun" }
        require(run.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Use a safe evidence run ID" }
        val output = File(requireNotNull(context.getExternalFilesDir("live-publisher-main")), run)
        check(!output.exists() && output.mkdirs()) { "Use a new mainPublisherRun to preserve earlier evidence" }
        val guide = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
        val schedulePreferences = context.getSharedPreferences("internet_schedule_cache", Context.MODE_PRIVATE)
        val store = BookReadingStore(context.getSharedPreferences("service_book_reading", Context.MODE_PRIVATE))
        val container = (context.applicationContext as LiturgyGuideApplication).container
        val books = container.serviceBooks
        val preexistingFiles = File(context.filesDir, "service-books-v2").listFiles().orEmpty().map { it.name }.toSet()
        val captures = mutableListOf<Map<String, Any?>>()
        val phases = CopyOnWriteArrayList<ScheduleImportProgress>()
        val modePositions = mutableListOf<BookReadingPosition>()
        val receipt = linkedMapOf<String, Any?>(
            "passed" to false, "run" to run, "startedAtUtc" to Instant.now().toString(),
            "statePolicy" to "Production MainActivity/AppContainer; state changes only through native UI; selected church, books and positions deliberately retained",
            "scope" to "Automatic publisher binding, exact published occurrence, UI offline preparation and three reading modes; Activity close only",
            "rootColdCheckStillRequired" to true, "coldProcessTestedHere" to false,
            "radiosChangedHere" to false, "networkAvailabilityVerifiedHere" to false,
            "sourceOverrideUsed" to false, "sourceEditorOpened" to false,
            "selectedParishBefore" to guide.getString("church_id", null),
            "apkSha256" to sha256(File(context.applicationInfo.sourceDir)),
            "display" to mapOf("widthPixels" to context.resources.displayMetrics.widthPixels,
                "heightPixels" to context.resources.displayMetrics.heightPixels,
                "density" to context.resources.displayMetrics.density,
                "fontScale" to context.resources.configuration.fontScale),
            "captures" to captures, "modePositions" to modePositions)
        var scenario: ActivityScenario<MainActivity>? = null
        var progressJob: Job? = null
        var partKeys = emptyList<ServiceBookKey>()
        try {
            assertTrue("The application must use its production schedule repository", container.internetSchedules is InternetScheduleRepository)
            assertTrue("The application must use its production book repository", books is ServiceBookRepository)

            // Reject a previous manual source before the first UI action. Do not erase it to make a run pass.
            val beforeRaw = schedulePreferences.getString(parishId, null)
            if (beforeRaw != null) {
                val previous = JsonParser.parseString(beforeRaw).asJsonObject
                fun previousString(key: String) = previous.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                assertTrue("A previous manual source must not be reused", previousString("customSource").isBlank())
                assertTrue("A configured website must not replace automatic publisher discovery", previousString("configuredWebsite").isBlank())
            }
            val existing = ChurchDirectoryStore(guide).records().singleOrNull { it.id == parishId }
            if (existing != null) {
                assertTrue("A saved website must not bypass automatic discovery", existing.website.isBlank())
            }
            receipt["sourceGuardBeforeFirstUiAction"] = mapOf("existingTarget" to existing,
                "scheduleCachePresent" to (beforeRaw != null), "scheduleCacheSha256" to beforeRaw?.let { BookText.sha256(it) })

            val observerStarted = CompletableDeferred<Unit>()
            val progress = requireNotNull(container.internetSchedules.progress)
            progressJob = launch(Dispatchers.Default) {
                progress.collect { phases += it; observerStarted.complete(Unit) }
            }
            withTimeout(5_000) { observerStarted.await() }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            await(20_000) { exists("main_content") || exists("church_content") || exists("book_content") }
            if (exists("book_content")) compose.onNodeWithTag("book_back").assertIsDisplayed().performClick()
            compose.onNodeWithTag("nav_church").assertIsDisplayed().performClick()
            awaitTag("church_content")
            reveal("change_church", "church_content").performClick()
            awaitTag("discovery_query")
            reveal("discovery_mode_name", "discovery_content").performClick()
            val field = reveal("discovery_query", "discovery_content")
            field.performClick()
            field.performTextReplacement("Вознесения Калуга")
            keyboard(requireNotNull(scenario), true)
            field.performImeAction()
            keyboard(requireNotNull(scenario), false)

            // A saved preview is insufficient: this run must expose the exact object in actual query results.
            val target = hasTestTag("discovery_church_$parishId")
            await(60_000) { runCatching {
                compose.onNodeWithTag("discovery_content").performScrollToNode(target)
                compose.onNode(target).assertIsDisplayed(); true
            }.getOrDefault(false) }
            compose.onNode(target).assertIsDisplayed().assertTextContains(churchName, substring = true)
            receipt["discovery"] = mapOf("query" to "Вознесения Калуга", "resultTag" to "discovery_church_$parishId",
                "displayedText" to nodeText(compose.onNode(target)), "savedPreviewAccepted" to false)
            capture("01-exact-query-result-before-selection", output, captures)
            compose.onNode(target).assertIsDisplayed().performClick()
            await(15_000) { exists("confirm_church_change") || guide.getString("church_id", null) == parishId }
            if (exists("confirm_church_change")) compose.onNodeWithTag("confirm_church_change").assertIsDisplayed().performClick()
            await(15_000) { guide.getString("church_id", null) == parishId && exists("church_content") }
            val savedChurch = ChurchDirectoryStore(guide).records().single { it.id == parishId }
            assertEquals(churchName, savedChurch.name)
            assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, savedChurch.origin)
            assertEquals("https://www.openstreetmap.org/way/155168069", savedChurch.sourceUrl)
            assertEquals(54.5305961, requireNotNull(savedChurch.latitude), .00000001)
            assertEquals(36.3084198, requireNotNull(savedChurch.longitude), .00000001)
            assertEquals("Europe/Moscow", savedChurch.timezone)
            assertTrue("The actual UI-selected record must have no configured website", savedChurch.website.isBlank())
            receipt["actualSelectedChurch"] = savedChurch
            val parish = savedChurch.asParish()

            // Allow Main's selection-triggered import to settle, then demand a fresh, real UI refresh.
            // The production refresh button passes a null override; no repository method is called by this test.
            await(240_000) { container.internetSchedules.cached(parish).status != InternetScheduleStatus.IDLE }
            // The loading action belongs to the empty-state item. A successful
            // publication moves this control into the information item of the
            // LazyColumn, so reveal its current location after that transition.
            await(240_000) { runCatching {
                reveal("refresh_schedule", "church_content").assertIsEnabled(); true
            }.getOrDefault(false) }
            val refresh = reveal("refresh_schedule", "church_content")
            val beforeRefresh = container.internetSchedules.cached(parish)
            assertTrue("Manual source guard before refresh", beforeRefresh.customSource.isBlank())
            assertTrue("Configured source guard before refresh", beforeRefresh.configuredWebsite.isBlank())
            receipt["scheduleBeforeUiRefresh"] = beforeRefresh
            val marker = phases.size
            refresh.assertIsDisplayed().assertIsEnabled().performClick()
            reveal("schedule_network_status", "church_content")
            await(240_000) {
                val observed = phases.drop(marker).filter { it.parishId == parishId }
                val actual = container.internetSchedules.cached(parish)
                observed.any { it.phase == ScheduleImportPhase.LOADING_PAGE } &&
                    observed.lastOrNull()?.phase == ScheduleImportPhase.FINISHED && actual.status == InternetScheduleStatus.READY &&
                    text("schedule_network_status") == "Расписание загружено с сайта"
            }
            val state = container.internetSchedules.cached(parish)
            assertAutomaticPublisher(state, parish)
            receipt["actualAutomaticSchedule"] = state
            receipt["uiRefreshImportPhases"] = phases.drop(marker)
            receipt["transportScope"] = "Unmodified production repositories and transports; LOADING_PAGE/FINISHED observed after native UI refresh; no HTTP interception"
            compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
            reveal("schedule_network_status", "church_content").assertTextEquals("Расписание загружено с сайта")
            capture("02-automatic-publisher-binding", output, captures)

            reveal("church_schedule_card", "church_content")
            await(20_000) { exists("church_open_service") || exists("church_open_next_service") }
            val actionTag = if (exists("church_open_service")) "church_open_service" else "church_open_next_service"
            val action = reveal(actionTag, "church_content")
            val clickedTitle = text(if (actionTag == "church_open_service") "church_service_title" else "church_next_title")
            val dateLabel = text(if (actionTag == "church_open_service") "schedule_date" else "church_next_date_time")
            val russian = Locale.forLanguageTag("ru")
            val clickedDate: LocalDate
            val clickedTime: LocalTime
            if (actionTag == "church_open_service") {
                clickedDate = LocalDate.parse(dateLabel, DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", russian))
                clickedTime = LocalTime.parse(text("church_service_time"))
            } else {
                val dateTime = LocalDateTime.parse(dateLabel, DateTimeFormatter.ofPattern("d MMMM uuuu '·' HH:mm", russian))
                clickedDate = dateTime.toLocalDate(); clickedTime = dateTime.toLocalTime()
            }
            val publication = requireNotNull(state.publication)
            val published = publication.events.single {
                it.date == clickedDate.toString() && it.time == clickedTime.toString() && it.title == clickedTitle
            }
            val clickedEvent = ParishService(published.date, published.time, published.title, published.kind)
            assertEquals("An existing dedication selection must not alter this exact default route", PatronalProfileLookup.Absent,
                container.bookDedication.choices.readProfile(parishId))
            assertAutomaticPublisher(container.internetSchedules.cached(parish), parish)
            receipt["clickedPublishedService"] = mapOf("tag" to actionTag, "title" to clickedTitle, "dateLabel" to dateLabel,
                "date" to clickedDate.toString(), "time" to clickedTime.toString(), "exactPublicationEvent" to published)
            capture("03-real-publication-event-before-opening", output, captures)
            action.assertIsDisplayed().performClick()
            await(240_000) { exists("evening_service_choice") || exists("book_content") || exists("book_load_error") }
            var explicitEveningChoice: WorshipKind? = null
            if (exists("evening_service_choice")) {
                assertTrue("Only an actually unspecified evening occurrence may show this choice", ServiceBookSelection.isUnspecifiedEvening(clickedEvent.title))
                explicitEveningChoice = WorshipKind.VESPERS
                capture("04-actual-evening-choice", output, captures)
                reveal("evening_choose_vespers", "evening_service_choice").performClick()
            }
            await(240_000) { exists("book_content") || exists("book_load_error") }
            compose.onNodeWithTag("book_load_error").assertDoesNotExist()
            await(20_000) { store.last(parishId)?.let { books.cachedVersion(it.key, it.contentHash) != null } == true }
            selectMode(BookReadingMode.ORIGINAL)
            await(15_000) { store.last(parishId)?.mode == BookReadingMode.ORIGINAL }
            settle()
            val opened = requireNotNull(store.last(parishId))
            val journey = opened.journeyContext?.validatedFor(opened.key, parishId)
            assertEquals(opened.journeyContext, journey)
            val expectedRoute = requireNotNull(if (explicitEveningChoice != null)
                ServiceBookSelection.eveningTexts(clickedEvent, parish, explicitEveningChoice)
                else ServiceBookSelection.resolve(clickedEvent, parish))
            assertEquals("The full key must come from the clicked publication occurrence", expectedRoute.activeKey, opened.key)
            assertEquals(expectedRoute.parishId, opened.parishId)
            assertEquals("The full journey must come from the clicked publication occurrence", expectedRoute.journeyContext, journey)
            partKeys = journey?.parts ?: listOf(opened.key)
            assertTrue(partKeys.isNotEmpty())
            assertEquals(partKeys.size, partKeys.toSet().size)
            receipt["explicitEveningChoice"] = explicitEveningChoice
            receipt["expectedRouteFromActualEvent"] = expectedRoute
            receipt["acceptedRouteDerivedFromActualReading"] = BookRoute(opened.key, parishId, journey)
            receipt["partCount"] = partKeys.size
            receipt["preparationScope"] = "All and only parts of the actual ServiceBookSelection route; no fixed part count or service kind"
            receipt["positionBeforePreparation"] = opened
            val partsBefore = partKeys.associateWith { key -> partSnapshot(store, books, key, allowMissing = true) }
            openDetails()
            reveal("book_offline_state", "book_calendar")
            await(30_000) { exists("book_offline_state") && !exists("book_offline_progress") }
            val preparedThroughUi = exists("book_prepare_service")
            if (preparedThroughUi) reveal("book_prepare_service", "book_calendar").assertIsEnabled().performClick()
            await(240_000) { exists("book_offline_complete") && !exists("book_offline_progress") }
            reveal("book_offline_complete", "book_calendar").assertIsDisplayed()
            receipt["preparationButtonUsed"] = preparedThroughUi
            receipt["parts"] = partKeys.map { key ->
                val verified = partSnapshot(store, books, key, allowMissing = false)
                val book = requireNotNull(verified.book)
                assertEquals(book, FileBookCache(File(context.filesDir, "service-books-v2")).readVersion(key, book.contentHash))
                val versionFile = "${BookText.sha256(key.canonical()).take(32)}-${book.contentHash}.json"
                val disk = File(File(context.filesDir, "service-books-v2"), versionFile)
                assertTrue("The exact prepared edition must exist on disk", disk.isFile)
                mapOf("key" to key, "canonicalKey" to key.canonical(), "contentHash" to book.contentHash,
                    "title" to book.title, "sourceUrl" to book.sourceUrl, "blocks" to book.blocks.size,
                    "translatedPrayerCount" to book.translatedPrayerCount, "exactVersionFile" to versionFile,
                    "exactVersionFileSha256" to sha256(disk), "exactVersionPresentBeforeRun" to (versionFile in preexistingFiles),
                    "keyMissingBeforePreparation" to (partsBefore[key]?.book == null),
                    "fetchedAtMillis" to book.fetchedAtMillis, "acceptedPosition" to verified.acceptedPosition)
            }
            capture("05-actual-route-offline-ready", output, captures)
            dismissSheet()
            assertEquals(opened.key, store.last(parishId)?.key)
            assertEquals(opened.contentHash, store.last(parishId)?.contentHash)

            val beforeScroll = requireNotNull(store.last(parishId))
            var inText = false
            for (attempt in 0 until 10) {
                val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
                val current = requireNotNull(store.last(parishId))
                val direction = if (current.anchorKind == BookAnchorKind.END || attempt >= 6) -1 else 1
                val distance = (viewport.bottom - viewport.top).value * context.resources.displayMetrics.density * .42f * direction
                compose.onNodeWithTag("book_content").performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, distance)) }
                settle()
                val cursor = requireNotNull(store.last(parishId))
                if (cursor != beforeScroll && cursor.anchorKind == BookAnchorKind.BLOCK &&
                    cursor.fraction in .01f..0.90f && cursor.followingGapFraction == 0f) {
                    inText = true; break
                }
            }
            assertTrue("A real UI scroll must save an in-text semantic cursor", inText)
            val original = requireNotNull(store.last(parishId))
            assertEquals(BookReadingMode.ORIGINAL, original.mode)
            modePositions += original
            capture("06-original-scrolled-reading", output, captures)
            for ((mode, name) in listOf(BookReadingMode.RUSSIAN to "07-russian-same-cursor",
                BookReadingMode.MEANING to "08-meaning-same-cursor", BookReadingMode.ORIGINAL to "09-returned-original-same-cursor")) {
                selectMode(mode)
                await(15_000) { store.last(parishId)?.mode == mode }
                settle()
                val actual = requireNotNull(store.last(parishId))
                assertEquals(if (mode == BookReadingMode.MEANING) BookReadingMode.RUSSIAN else mode, actual.lastTextMode)
                assertSameCursor(original, actual)
                modePositions += actual
                compose.onNodeWithTag("book_save_error").assertDoesNotExist()
                capture(name, output, captures)
            }
            val beforeClose = requireNotNull(store.last(parishId))
            assertEquals(BookReadingMode.ORIGINAL, beforeClose.mode)
            assertSameCursor(original, beforeClose)
            receipt["positionBeforeActivityClose"] = beforeClose
            receipt["scrollBefore"] = beforeScroll
            scenario.close()
            scenario = null
            val afterClose = requireNotNull(BookReadingStore(context.getSharedPreferences("service_book_reading", Context.MODE_PRIVATE)).last(parishId))
            assertEquals(beforeClose, afterClose)
            assertEquals(parishId, guide.getString("church_id", null))
            assertAutomaticPublisher(container.internetSchedules.cached(parish), parish)
            receipt["positionAfterActivityClose"] = afterClose
            receipt["selectedParishAfterClose"] = guide.getString("church_id", null)
            receipt["passed"] = true
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name
            receipt["failure"] = failure.message?.take(1_000)
            runCatching { capture("failure", output, captures) }
            throw failure
        } finally {
            progressJob?.cancel()
            scenario?.close()
            receipt["actualSelectedParishAtEnd"] = guide.getString("church_id", null)
            receipt["finalPartPositions"] = partKeys.map { store.positionFor(it, parishId) }
            receipt["allObservedImportPhases"] = phases.toList()
            receipt["finishedAtUtc"] = Instant.now().toString()
            File(output, "receipt.json").writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(receipt))
        }
    }

    private fun assertAutomaticPublisher(state: InternetScheduleState, parish: Parish) {
        assertEquals(parishId, state.parishId)
        assertEquals(InternetScheduleStatus.READY, state.status)
        assertTrue("No custom source is allowed", state.customSource.isBlank())
        assertTrue("No configured website is allowed", state.configuredWebsite.isBlank())
        assertEquals(source, state.website)
        assertFalse(state.publicationRejected)
        assertFalse(state.sourceNeedsVerification)
        val binding = requireNotNull(state.siteBinding)
        assertEquals(ScheduleBindingMethod.PUBLISHER_GEOMETRY_CHAIN, binding.method)
        assertTrue("The publisher binding must validate for the actual UI-selected identity", binding.validFor(parish, state.website, state.siteEvidenceUrl))
        val proof = requireNotNull(binding.publisherGeometryProof)
        assertEquals(setOf("t20385", "t21054", "t20703"), proof.passports.map { it.id }.toSet())
        val publication = requireNotNull(state.publication)
        assertEquals(parishId, publication.parishId)
        assertEquals(source, publication.website)
        assertTrue("The bound publication must contain real dated events", publication.events.isNotEmpty())
    }

    private fun assertSameCursor(expected: BookReadingPosition, actual: BookReadingPosition) {
        assertEquals("Only the requested mode, last text mode and pixel rounding may change",
            expected.copy(mode = actual.mode, lastTextMode = actual.lastTextMode, fraction = actual.fraction), actual)
        assertEquals(expected.fraction, actual.fraction, .015f)
    }

    private data class PartSnapshot(val acceptedPosition: BookReadingPosition?, val book: ServiceBook?)
    private fun partSnapshot(store: BookReadingStore, books: ServiceBooks, key: ServiceBookKey, allowMissing: Boolean): PartSnapshot =
        when (val lookup = store.lookup(key, parishId)) {
            is BookReadingLookup.Found -> {
                val exact = requireNotNull(books.cachedVersion(key, lookup.position.contentHash)) { "Accepted exact edition is missing: ${key.canonical()}" }
                assertEquals(key, exact.key)
                assertEquals(lookup.position.contentHash, exact.contentHash)
                assertTrue(ServiceBookIntegrity.valid(exact))
                PartSnapshot(lookup.position, exact)
            }
            BookReadingLookup.Absent -> {
                val latest = books.cached(key)
                if (latest == null) {
                    check(allowMissing) { "A prepared part is missing: ${key.canonical()}" }
                    PartSnapshot(null, null)
                } else {
                    assertEquals(key, latest.key)
                    assertTrue(ServiceBookIntegrity.valid(latest))
                    assertEquals(latest, books.cachedVersion(key, latest.contentHash))
                    PartSnapshot(null, latest)
                }
            }
            BookReadingLookup.Unreadable -> error("Accepted reading pointer is unreadable: ${key.canonical()}")
        }

    private fun selectMode(mode: BookReadingMode) {
        val tag = "book_mode_${mode.name.lowercase()}"
        if (!exists(tag)) compose.onNodeWithTag("book_mode_menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun openDetails() {
        compose.onNodeWithContentDescription("Настроить чтение").assertIsDisplayed().performClick()
        compose.onNodeWithTag("book_options_offline").performScrollTo().assertIsDisplayed().performClick()
        awaitTag("book_calendar")
    }
    private fun reveal(tag: String, parent: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(parent).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun nodeText(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
    private fun text(tag: String) = nodeText(compose.onNodeWithTag(tag))
    private fun dismissSheet() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertCountEquals(1)[0]
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        settle()
    }
    private fun keyboard(scenario: ActivityScenario<MainActivity>, visible: Boolean) {
        await(15_000) {
            var matches = false
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                    (visible || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0)
            }
            matches
        }
    }
    private fun awaitTag(tag: String) = await(20_000) { exists(tag) }
    private fun await(timeout: Long, condition: () -> Boolean) { compose.waitUntil(timeout) { condition() }; compose.waitForIdle() }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(800); compose.waitForIdle() }
    private fun capture(name: String, output: File, captures: MutableList<Map<String, Any?>>) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(output, "$name.png")
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        captures += mapOf("file" to file.name, "sha256" to sha256(file), "capturedAtUtc" to Instant.now().toString())
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
