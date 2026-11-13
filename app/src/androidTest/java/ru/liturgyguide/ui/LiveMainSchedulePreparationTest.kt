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
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in UI actions on the owned emulator. No injected dependencies, clock, seed, preference edits or cleanup. */
@RunWith(AndroidJUnit4::class)
class LiveMainSchedulePreparationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val parishId = "osm:way:55228075"
    private val source = "https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/"

    @Test fun actualMainSelectsTheChurchImportsItsManualSourceAndLeavesPreparedOriginalReadingForExternalColdResume() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit owned-emulator real-state and network opt-in required",
            args.getString("liveMainSchedulePreparation") == "true" && args.getString("feoriaLiveNetwork") == "true")
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("/sdk_gphone")) { "Real-state actions are restricted to the owned emulator" }
        val run = requireNotNull(args.getString("mainSchedulePreparationRun")) { "Supply a unique mainSchedulePreparationRun" }
        require(run.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        val output = File(requireNotNull(context.getExternalFilesDir("live-main-schedule-preparation")), run)
        check(!output.exists() && output.mkdirs())
        val guide = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
        val store = BookReadingStore(context.getSharedPreferences("service_book_reading", Context.MODE_PRIVATE))
        val container = (context.applicationContext as LiturgyGuideApplication).container
        val books = container.serviceBooks
        assertTrue(books is ServiceBookRepository)
        val preexistingFiles = File(context.filesDir, "service-books-v2").listFiles().orEmpty().map { it.name }.toSet()
        val captures = mutableListOf<String>()
        val phases = CopyOnWriteArrayList<ScheduleImportProgress>()
        val receipt = linkedMapOf<String, Any?>("passed" to false, "run" to run,
            "statePolicy" to "Production MainActivity/AppContainer; state changes only through UI; selected church/books/positions deliberately retained",
            "scope" to "Online production UI preparation ending with Activity close; root must separately disable radios, force-stop, cold-launch and resume",
            "coldProcessTestedHere" to false, "networkAvailabilityVerifiedHere" to false,
            "selectedParishBefore" to guide.getString("church_id", null), "apkSha256" to sha256(File(context.applicationInfo.sourceDir)),
            "captures" to captures)
        var scenario: ActivityScenario<MainActivity>? = null
        var progressJob: Job? = null
        var partKeys = emptyList<ServiceBookKey>()
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            await(20_000) { exists("main_content") || exists("church_content") || exists("book_content") }
            if (exists("book_content")) compose.onNodeWithTag("book_back").performClick()
            compose.onNodeWithTag("nav_church").performClick()
            awaitTag("church_content")
            reveal("change_church", "church_content").performClick()
            awaitTag("discovery_query")
            val field = reveal("discovery_query", "discovery_content")
            field.performClick()
            field.performTextReplacement("храм Новосибирск")
            keyboard(requireNotNull(scenario), true)
            field.performImeAction()
            keyboard(requireNotNull(scenario), false)
            val target = hasTestTag("discovery_church_$parishId") or hasTestTag("discovery_saved_$parishId")
            await(45_000) { runCatching {
                compose.onNodeWithTag("discovery_content").performScrollToNode(target)
                compose.onNode(target).assertIsDisplayed(); true
            }.getOrDefault(false) }
            compose.onNode(target).assertTextContains("Новомучеников", substring = true)
                .assertTextContains("Немировича-Данченко", substring = true)
            capture("01-exact-discovery-result", output, captures)
            compose.onNode(target).performClick()
            await(15_000) { exists("confirm_church_change") || guide.getString("church_id", null) == parishId }
            if (exists("confirm_church_change")) compose.onNodeWithTag("confirm_church_change").performClick()
            await(15_000) { guide.getString("church_id", null) == parishId && exists("church_content") }
            val savedChurch = ChurchDirectoryStore(guide).records().single { it.id == parishId }
            assertEquals("Храм в честь Святых Новомучеников и Исповедников Российских", savedChurch.name)
            assertEquals("улица Немировича-Данченко, 120/1", savedChurch.address)
            assertEquals("https://www.openstreetmap.org/way/55228075", savedChurch.sourceUrl)
            assertEquals("Asia/Novosibirsk", savedChurch.timezone)
            receipt["actualSelectedChurch"] = savedChurch
            val parish = savedChurch.asParish()
            await(150_000) { container.internetSchedules.cached(parish).status != InternetScheduleStatus.IDLE }
            reveal("edit_schedule_website", "church_content").performClick()
            awaitTag("schedule_website_input")
            compose.onNodeWithTag("schedule_source_church").assertTextEquals(savedChurch.name)
            val input = reveal("schedule_website_input", "schedule_source_content")
            input.performTextReplacement(source)
            val observerStarted = CompletableDeferred<Unit>()
            val progress = requireNotNull(container.internetSchedules.progress)
            progressJob = launch(Dispatchers.Default) { progress.collect { phases += it; observerStarted.complete(Unit) } }
            withTimeout(5_000) { observerStarted.await() }
            val marker = phases.size
            receipt["scheduleBeforeManualSubmit"] = container.internetSchedules.cached(parish)
            input.performImeAction() // The production source editor submits on Done and closes itself.
            await(180_000) {
                val observed = phases.drop(marker).filter { it.parishId == parishId }
                val actual = container.internetSchedules.cached(parish)
                observed.any { it.phase == ScheduleImportPhase.LOADING_PAGE } && observed.lastOrNull()?.phase == ScheduleImportPhase.FINISHED &&
                    actual.status == InternetScheduleStatus.READY && actual.customSource == source && actual.publication?.sourceUrl == source &&
                    !exists("schedule_source_content")
            }
            val state = container.internetSchedules.cached(parish)
            assertFalse(state.publicationRejected)
            assertFalse(state.sourceNeedsVerification)
            val publication = requireNotNull(state.publication)
            assertEquals(parishId, publication.parishId)
            receipt["actualImportedSchedule"] = state
            receipt["manualSubmissionImportPhases"] = phases.drop(marker)
            receipt["transportScope"] = "No transport replacement or interception; loading/finished phases observed from the real production repository"
            reveal("church_schedule_card", "church_content")
            await(20_000) { exists("church_open_service") || exists("church_open_next_service") }
            val actionTag = if (exists("church_open_service")) "church_open_service" else "church_open_next_service"
            val action = reveal(actionTag, "church_content")
            val clickedTitle = text(if (actionTag == "church_open_service") "church_service_title" else "church_next_title")
            val russian = Locale.forLanguageTag("ru")
            val dateLabel = text(if (actionTag == "church_open_service") "schedule_date" else "church_next_date_time")
            val clickedDate: LocalDate
            val clickedTime: LocalTime
            if (actionTag == "church_open_service") {
                clickedDate = LocalDate.parse(dateLabel, DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", russian))
                clickedTime = LocalTime.parse(text("church_service_time"))
            } else {
                val dateTime = LocalDateTime.parse(dateLabel, DateTimeFormatter.ofPattern("d MMMM uuuu '·' HH:mm", russian))
                clickedDate = dateTime.toLocalDate(); clickedTime = dateTime.toLocalTime()
            }
            val published = publication.events.single { it.date == clickedDate.toString() && it.time == clickedTime.toString() && it.title == clickedTitle }
            val clickedEvent = ParishService(published.date, published.time, published.title, published.kind)
            assertEquals("This real-state path requires the actual NSK dedication profile to be absent", PatronalProfileLookup.Absent,
                container.bookDedication.choices.readProfile(parishId))
            receipt["clickedPublishedService"] = mapOf("tag" to actionTag, "title" to clickedTitle, "dateLabel" to dateLabel,
                "date" to clickedDate.toString(), "time" to clickedTime.toString(), "exactPublicationEvent" to published)
            capture("02-real-published-service", output, captures)
            action.performClick()
            await(240_000) { exists("evening_service_choice") || exists("book_content") || exists("book_load_error") }
            var explicitEveningChoice: WorshipKind? = null
            if (exists("evening_service_choice")) {
                explicitEveningChoice = WorshipKind.VESPERS
                reveal("evening_choose_vespers", "evening_service_choice").performClick()
            }
            await(240_000) { exists("book_content") || exists("book_load_error") }
            compose.onNodeWithTag("book_load_error").assertDoesNotExist()
            await(20_000) { store.last(parishId)?.key?.let { books.cachedVersion(it, requireNotNull(store.last(parishId)).contentHash) != null } == true }
            selectOriginal()
            await(15_000) { store.last(parishId)?.mode == BookReadingMode.ORIGINAL }
            settle()
            val opened = requireNotNull(store.last(parishId))
            val journey = opened.journeyContext?.validatedFor(opened.key, parishId)
            assertEquals(opened.journeyContext, journey)
            val expectedRoute = requireNotNull(if (explicitEveningChoice != null)
                ServiceBookSelection.eveningTexts(clickedEvent, parish, explicitEveningChoice)
                else ServiceBookSelection.resolve(clickedEvent, parish))
            assertEquals("The complete key must belong to the single clicked publication event", expectedRoute.activeKey, opened.key)
            assertEquals(expectedRoute.parishId, opened.parishId)
            assertEquals("The complete occurrence must belong to the clicked publication event", expectedRoute.journeyContext, journey)
            if (journey != null) {
                assertEquals(BookPartSetKind.EVENING_TEXTS, journey.effectivePartSet)
                assertEquals(2, journey.parts.size)
            }
            partKeys = journey?.parts ?: listOf(opened.key)
            receipt["partCount"] = partKeys.size
            receipt["preparationScope"] = "All available parts of the actual accepted route; no assumption that the route has two parts"
            receipt["acceptedRouteDerivedFromActualReading"] = BookRoute(opened.key, parishId, journey)
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
                assertTrue(File(File(context.filesDir, "service-books-v2"), versionFile).isFile)
                mapOf("key" to key, "hash" to book.contentHash, "blocks" to book.blocks.size,
                    "exactVersionFile" to versionFile, "exactVersionPresentBeforeRun" to (versionFile in preexistingFiles),
                    "keyMissingBeforePreparation" to (partsBefore[key]?.book == null), "fetchedAtMillis" to book.fetchedAtMillis,
                    "acceptedPosition" to verified.acceptedPosition)
            }
            capture("03-all-available-parts-ready", output, captures)
            dismissSheet()
            assertEquals(opened.key, store.last(parishId)?.key)
            assertEquals(opened.contentHash, store.last(parishId)?.contentHash)
            var moved = false
            for (attempt in 0 until 4) {
                val viewport = compose.onNodeWithTag("book_content").getBoundsInRoot()
                val distance = (viewport.bottom - viewport.top).value * context.resources.displayMetrics.density * .45f
                compose.onNodeWithTag("book_content").performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, distance)) }
                settle()
                val cursor = requireNotNull(store.last(parishId))
                if (cursor != opened && cursor.anchorKind == BookAnchorKind.BLOCK) { moved = true; break }
            }
            assertTrue("A real UI scroll must leave a durable in-text cursor", moved)
            val beforeClose = requireNotNull(store.last(parishId))
            assertEquals(BookReadingMode.ORIGINAL, beforeClose.mode)
            compose.onNodeWithTag("book_save_error").assertDoesNotExist()
            capture("04-original-reading-for-root-cold-resume", output, captures)
            receipt["positionBeforeActivityClose"] = beforeClose
            scenario.close()
            scenario = null
            val afterClose = requireNotNull(BookReadingStore(context.getSharedPreferences("service_book_reading", Context.MODE_PRIVATE)).last(parishId))
            assertEquals(beforeClose, afterClose)
            assertEquals(parishId, guide.getString("church_id", null))
            receipt["positionAfterActivityClose"] = afterClose
            receipt["selectedParishAfterClose"] = guide.getString("church_id", null)
            receipt["passed"] = true
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name
            receipt["failure"] = failure.message?.take(700)
            runCatching { capture("failure", output, captures) }
            throw failure
        } finally {
            progressJob?.cancel()
            scenario?.close()
            receipt["actualSelectedParishAtEnd"] = guide.getString("church_id", null)
            receipt["finalPartPositions"] = partKeys.map { store.positionFor(it, parishId) }
            receipt["rootColdCheckStillRequired"] = true
            File(output, "receipt.json").writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(receipt))
        }
    }

    private fun selectOriginal() {
        if (!exists("book_mode_original")) compose.onNodeWithTag("book_mode_menu").performClick()
        compose.onNodeWithTag("book_mode_original").performClick()
    }
    private data class PartSnapshot(val acceptedPosition: BookReadingPosition?, val book: ServiceBook?)
    private fun partSnapshot(store: BookReadingStore, books: ServiceBooks, key: ServiceBookKey, allowMissing: Boolean): PartSnapshot {
        return when (val lookup = store.lookup(key, parishId)) {
            is BookReadingLookup.Found -> {
                val exact = requireNotNull(books.cachedVersion(key, lookup.position.contentHash)) { "Accepted exact edition is missing: ${key.canonical()}" }
                assertEquals(key, exact.key)
                assertEquals(lookup.position.contentHash, exact.contentHash)
                assertTrue(ServiceBookIntegrity.valid(exact))
                PartSnapshot(lookup.position, exact)
            }
            BookReadingLookup.Absent -> {
                val latest = books.cached(key)
                if (latest == null) { check(allowMissing) { "A prepared part is missing: ${key.canonical()}" }; PartSnapshot(null, null) }
                else {
                    assertEquals(key, latest.key)
                    assertTrue(ServiceBookIntegrity.valid(latest))
                    assertEquals(latest, books.cachedVersion(key, latest.contentHash))
                    PartSnapshot(null, latest)
                }
            }
            BookReadingLookup.Unreadable -> error("Accepted reading pointer is unreadable: ${key.canonical()}")
        }
    }
    private fun openDetails() {
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithTag("book_options_offline").performScrollTo().assertIsDisplayed().performClick()
        awaitTag("book_calendar")
    }
    private fun reveal(tag: String, parent: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(parent).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
    private fun dismissSheet() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertCountEquals(1)[0].performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        settle()
    }
    private fun keyboard(scenario: ActivityScenario<MainActivity>, visible: Boolean) {
        await(15_000) { var matches = false; scenario.onActivity { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible && (visible || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0)
        }; matches }
    }
    private fun awaitTag(tag: String) = await(20_000) { exists(tag) }
    private fun await(timeout: Long, condition: () -> Boolean) { compose.waitUntil(timeout) { condition() }; compose.waitForIdle() }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(800); compose.waitForIdle() }
    private fun capture(name: String, output: File, captures: MutableList<String>) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        captures += "$name.png"
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
