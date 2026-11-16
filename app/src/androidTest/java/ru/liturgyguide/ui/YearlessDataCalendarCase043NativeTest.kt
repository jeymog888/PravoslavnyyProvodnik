package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.ServiceBookSelection
import ru.liturgyguide.service.book.BookPartSetKind
import ru.liturgyguide.service.schedule.*
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** Known configured sanitized043 replay, actual cache/screens and route resolution; no live HTTP or book download. */
@RunWith(Parameterized::class)
class YearlessDataCalendarCase043NativeTest(
    private val width: Int, private val height: Int, private val scale: Float, private val dark: Boolean
) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val gson = Gson()
    private var previousFont: String? = null
    private var previousSize: String? = null
    private var previousDisplayQuery: String? = null
    private data class DisplayState(val fontScale: Float, val densityDpi: Int, val widthDp: Int, val heightDp: Int,
        val orientation: Int, val widthPx: Int, val heightPx: Int)
    private var previousDisplay: DisplayState? = null
    private var displayChanged = false
    private var contentSet = false
    private var mounted by mutableStateOf(true)
    private lateinit var output: File
    private val observations = mutableListOf<Map<String, Any?>>()
    private val screenshots = mutableListOf<Map<String, Any?>>()
    private fun bytes(path: String) = instrumentation.context.assets
        .open("yearless-data-calendar-case043-native/$path").use { it.readBytes() }
    private fun json(path: String) = JsonParser.parseString(bytes(path).toString(Charsets.UTF_8)).asJsonObject
    private fun sha(value: ByteArray) = ScheduleSourceCandidate.sha(value)
    private fun rows(value: JsonObject, name: String) = value.getAsJsonArray(name).map { it.asJsonObject }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Before fun configureOwnedEmulatorProfile() {
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("/sdk_gphone")) {
            "Native display profiles require the owned emulator"
        }
        previousFont = shell("settings get system font_scale").takeUnless { it == "null" }
        previousDisplayQuery = shell("wm size")
        previousSize = Regex("Override size: (\\d+x\\d+)").find(requireNotNull(previousDisplayQuery))?.groupValues?.get(1)
        compose.waitUntil(10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity { activity ->
                if (displayReady(activity)) { previousDisplay = displayState(activity); ready = true }
            }
            ready
        }
        val density = compose.activity.resources.displayMetrics.density
        displayChanged = true
        shell("settings put system font_scale $scale")
        shell("wm size ${(width * density).roundToInt()}x${(height * density).roundToInt()}")
        compose.waitUntil(10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity {
                ready = abs(it.resources.configuration.fontScale - scale) < .001f &&
                    abs(it.resources.configuration.screenWidthDp - width) <= 1 && it.window.decorView.hasWindowFocus()
            }
            ready
        }
        compose.activityRule.scenario.onActivity { WindowCompat.setDecorFitsSystemWindows(it.window, false) }
    }

    @After fun restoreOwnedEmulatorProfile() {
        var failure: Throwable? = null
        fun step(action: () -> Unit) {
            try { action() }
            catch (next: Throwable) {
                val first = failure
                if (first == null) failure = next else if (first !== next) first.addSuppressed(next)
            }
        }
        step {
            if (contentSet) { compose.runOnIdle { mounted = false }; compose.waitForIdle() }
        }
        if (displayChanged) {
            step { shell("wm size ${previousSize ?: "reset"}") }
            step { shell(previousFont?.let { "settings put system font_scale $it" } ?: "settings delete system font_scale") }
            step { awaitRestoredDisplay() }
        }
        failure?.let { throw it }
    }

    private fun displayState(activity: ComponentActivity): DisplayState {
        val configuration = activity.resources.configuration
        val decor = activity.window.decorView
        return DisplayState(configuration.fontScale, configuration.densityDpi, configuration.screenWidthDp,
            configuration.screenHeightDp, configuration.orientation, decor.width, decor.height)
    }

    private fun displayReady(activity: ComponentActivity): Boolean {
        val decor = activity.window.decorView
        return activity.lifecycle.currentState == Lifecycle.State.RESUMED && !activity.isChangingConfigurations &&
            !activity.isFinishing && !activity.isDestroyed && decor.isAttachedToWindow && decor.isLaidOut &&
            !decor.isLayoutRequested && decor.hasWindowFocus() && decor.width > 0 && decor.height > 0
    }

    private fun awaitRestoredDisplay() {
        val expected = requireNotNull(previousDisplay)
        fun checkSettings() {
            assertEquals("Restore the exact previous display override", previousDisplayQuery, shell("wm size"))
            assertEquals("Restore the exact previous font preference", previousFont ?: "null", shell("settings get system font_scale"))
        }
        checkSettings()
        var frameHost: ComponentActivity? = null
        var committed: AtomicBoolean? = null
        var stableFrames = 0
        compose.waitUntil(15_000) {
            var ready = false
            // Read the current scenario Activity after recreation, never a cached host.
            compose.activityRule.scenario.onActivity { activity ->
                if (!displayReady(activity) || displayState(activity) != expected) {
                    frameHost = null; committed = null; stableFrames = 0
                } else {
                    if (frameHost !== activity) { frameHost = activity; committed = null; stableFrames = 0 }
                    if (committed?.get() == true) { stableFrames++; committed = null }
                    if (stableFrames == 2) ready = true
                    else if (committed == null) {
                        val frame = AtomicBoolean(false)
                        committed = frame
                        val decor = activity.window.decorView
                        if (Build.VERSION.SDK_INT >= 29) decor.viewTreeObserver.registerFrameCommitCallback { frame.set(true) }
                        else decor.postOnAnimation { frame.set(true) }
                        decor.invalidate()
                    }
                }
            }
            ready
        }
        checkSettings()
    }

    private fun originalParish(): Parish {
        val recorded = json("original-case043.json")
        val selected = recorded.getAsJsonObject("originalSelected")
        val parish = gson.fromJson(recorded["savedParish"], Parish::class.java)
        check(parish.id == selected["id"].asString)
        check(parish.website == selected["website"].asString)
        check(parish.schedule.isEmpty() && !parish.scheduleKnownPartial)
        return parish
    }

    /** Ordinary production acquisition. Expected results come only from the pinned root oracle in the test. */
    private inner class OwnedReplay(val label: String, val controlled: Boolean = false) {
        val parish = if (controlled) Parish("native:043:partial", "Контроль месячного календаря", "Контроль месячного календаря",
            "", "", "", "https://calendar-native-control.example/", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
            else originalParish()
        val owner = "yearless-calendar-case043-$label-${UUID.randomUUID()}"
        private val owned = File(app.cacheDir, owner).apply { check(!exists() && mkdirs()) }
        val evidence = File(requireNotNull(app.getExternalFilesDir("yearless-calendar-case043-native")), owner)
            .apply { check(!exists() && mkdirs()) }
        private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(owned, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(owned, "cache").apply { check(mkdirs() || isDirectory) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val privateName = "$owner-$name"
                preferenceNames += privateName
                return app.getSharedPreferences(privateName, mode)
            }
        }
        private val preferences = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        private val mapping = if (controlled) rows(json("CONTROL-DEFINITION.json"), "responses")
            else rows(json("REQUEST-MAPPING.json"), "requests")
        val requests = CopyOnWriteArrayList<Map<String, Any?>>()
        val offlineCalls = AtomicInteger()
        private fun cache(store: SharedPreferences = preferences) = object : ScheduleCacheStore {
            override val coordinationLock: Any get() = store
            override fun read(parishId: String) = store.getString(parishId, null)
            override fun write(parishId: String, value: String) {
                check(store.edit().putString(parishId, value).commit())
            }
        }
        private val transport = ScheduleTransport { requested ->
            val row = mapping.singleOrNull { it["requestUrl"].asString == requested }
            if (row == null) {
                requests += mapOf("requestUrl" to requested, "outcome" to "UNAVAILABLE_NOT_RECORDED")
                throw IOException("No exact saved case043 response: $requested")
            }
            val body = bytes(row["fixture"].asString)
            check(body.size == row[if (controlled) "bodyBytes" else "savedSanitizedBytes"].asInt)
            check(sha(body) == row[if (controlled) "bodySha256" else "savedSanitizedSha256"].asString)
            requests += mapOf("requestUrl" to requested, "responseUrl" to row["responseUrl"].asString,
                "outcome" to if (controlled) "SERVED_CONTROLLED_HTML_NOT_CAPTURED_HTTP" else "SERVED_SAVED_SANITIZED_BYTES",
                "savedBytes" to body.size, "savedSha256" to sha(body),
                "originalHttpBytes" to row.get("networkOriginalBytes")?.asInt,
                "originalHttpSha256" to row.get("networkOriginalSha256")?.asString,
                "contentType" to row["contentType"].asString)
            ScheduleResponse(row["responseUrl"].asString, body, row["contentType"].asString)
        }
        private val repository = InternetScheduleRepository(transport, cache(), AndroidScheduleDocumentExtractor(context))

        suspend fun load(now: Instant): InternetScheduleState {
            check(context.applicationContext === context)
            check(raw() == null) { "Primary ordinary configured import requires an empty owned cache" }
            return repository.refresh(parish, now)
        }

        fun cold(): InternetScheduleState {
            val denied = ScheduleTransport { requested ->
                offlineCalls.incrementAndGet()
                throw IOException("Cache-only case043 read forbids request: $requested")
            }
            val coldPreferences = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
            return InternetScheduleRepository(denied, cache(coldPreferences), AndroidScheduleDocumentExtractor(context)).cached(parish)
        }

        fun raw() = preferences.getString(parish.id, null)
        fun values() = preferences.all.toMap()
        fun xml(): ByteArray {
            val file = File(app.applicationInfo.dataDir, "shared_prefs/$owner-schedule.xml")
            check(file.isFile) { "Owned synchronous preference commit must exist before disk snapshot" }
            return file.readBytes()
        }

        // The future test calls this only after awaited import and UI unmount have completed.
        fun cleanup(): List<String> {
            val failures = mutableListOf<String>()
            preferenceNames.forEach { name ->
                runCatching { check(app.deleteSharedPreferences(name)) }.onFailure { failures += "$name: ${it.javaClass.simpleName}" }
            }
            runCatching { check(owned.deleteRecursively()) }.onFailure { failures += "ownedFiles: ${it.javaClass.simpleName}" }
            return failures
        }
    }

    private data class ScreenCase(val church: Boolean, val parish: Parish, val state: InternetScheduleState,
        val now: Instant, val label: String)
    private var screen by mutableStateOf<ScreenCase?>(null)
    private var generation by mutableIntStateOf(0)
    private val opened = mutableListOf<ParishService>()
    private val urls = mutableListOf<String>()
    private val yearNote = "Год не указан - проверьте дату в расписании"
    private fun service(event: InternetService) = ParishService(event.date, event.time, event.title, event.kind)

    @Test fun configuredYearlessCalendarKeepsItsExactRowsOfflineDatesFlagsAndIndependentFutureActions() = runBlocking<Unit> {
        assertEquals("dd3b24590852ace0fc480fdedd20430542a4c6cf185a00874c36ab2124121c1d", sha(bytes("FIXTURE-MANIFEST.json")))
        rows(json("FIXTURE-MANIFEST.json"), "files").forEach { row ->
            val body = bytes(row["asset"].asString)
            assertEquals(row["bytes"].asInt, body.size)
            assertEquals(row["sha256"].asString, sha(body))
        }
        assertEquals("b63ff19eed008e48005fd33567cc8e493748f54286c93c0a258c3184cd93d4e9", sha(bytes("ACCEPTED-CONFIGURED-043.json")))
        assertEquals("ROOT_ACCEPTED_CONFIGURED_JVM_ORACLE", json("ROOT-JVM-ACCEPTANCE.json")["status"].asString)
        val oracle = json("ACCEPTED-CONFIGURED-043.json")
        val expected = gson.fromJson(oracle["state"], InternetScheduleState::class.java)
        val now = Instant.parse(oracle["instant"].asString)
        assertFalse(oracle["sourceOverrideUsed"].asBoolean)
        val owned = mutableListOf<OwnedReplay>()
        val primary = OwnedReplay("primary").also { owned += it }
        output = primary.evidence
        val receipt = linkedMapOf<String, Any?>("passed" to false, "classification" to "KNOWN_SAVED_SANITIZED043_NOT_FRESH_HTTP_OR_INDEPENDENT_COVERAGE",
            "clock" to now.toString(), "profile" to mapOf("widthDp" to width, "heightDp" to height, "fontScale" to scale, "dark" to dark),
            "rootOracleSha256" to sha(bytes("ACCEPTED-CONFIGURED-043.json")),
            "notMeasured" to listOf("physicalMain", "processRestart", "liveHttp", "bookDownload", "fullBookOrRussian", "wholeSourceCompleteness"))
        try {
            withTimeout(360_000) {
                val imported = primary.load(now)
                receipt["actualImportedState"] = imported
                assertEquals("Actual configured-owner state, not generic calendar-owner results", expected, imported)
                assertEquals(InternetScheduleStatus.READY, imported.status)
                val publication = requireNotNull(imported.publication)
                assertEquals(21, publication.events.size)
                assertTrue(publication.events.all { it.kind == "service_block" })
                assertTrue(publication.yearInferred); assertFalse(publication.knownPartial)
                assertTrue(imported.sourceHasWithdrawals)
                assertFalse(imported.publicationRejected); assertFalse(imported.sourceNeedsVerification)
                assertEquals("", imported.customSource)
                assertEquals(primary.parish.website, imported.configuredWebsite)
                assertEquals("https://businovohram.ru/raspisanie", publication.sourceUrl)
                assertEquals(oracle["requestUrls"].asJsonArray.map { it.asString }, primary.requests.map { it["requestUrl"] })
                assertEquals(2, primary.requests.size)
                assertTrue(primary.requests.all { it["outcome"] == "SERVED_SAVED_SANITIZED_BYTES" })
                val actualRows = rows(oracle, "rows")
                assertEquals(41, actualRows.size)
                assertEquals(21, actualRows.count { it["actualParserResult"].asString == "ACCEPTED_EXACT_SOURCE_ROW" })
                assertEquals(20, actualRows.count { it["actualParserResult"].asString == "OUTSIDE_IMPORT_WINDOW" })
                assertFalse(publication.events.any { it.date in setOf("2026-11-16", "2026-11-17") })
                val projected = primary.parish.withInternetSchedule(imported, now)
                assertEquals(publication.events.filter { it.date >= "2026-11-16" }.map(::service), projected.schedule)
                assertEquals(18, projected.schedule.size)
                assertFalse(projected.scheduleKnownPartial)
                val cold = offlineCheck(primary, imported)
                val primaryRaw = primary.raw(); val primaryValues = primary.values(); val primaryXml = primary.xml()
                val future = service(publication.events.single { it.date == "2026-11-18" && it.time == "08:00" })
                val hours = service(publication.events.single { it.date == "2026-11-22" && it.time == "06:40" })
                receipt["projection"] = projected.schedule
                receipt["coldState"] = cold
                launchScreens()
                for (church in listOf(false, true)) {
                    val current = ScreenCase(church, projected, imported, now, "live-${if (church) "church" else "today"}")
                    show(current)
                    emptyAndFuture(current, future)
                    if (church) agenda(current, future, if (width == 411) hours else null)
                }
                if (width == 411) {
                    for (church in listOf(false, true)) {
                        val current = ScreenCase(church, primary.parish, cold, now, "cold-${if (church) "church" else "today"}")
                        show(current); emptyAndFuture(current, future)
                        if (church) agenda(current, future, null)
                    }
                    val later = Instant.parse("2026-11-30T06:00:00Z")
                    for (church in listOf(false, true)) {
                        val current = ScreenCase(church, primary.parish, cold, later, "empty30-${if (church) "church" else "today"}")
                        show(current)
                        val prefix = if (church) "church" else "today"
                        val container = if (church) "church_content" else "main_content"
                        date(current, "30 ноября", "30 ноября 2026, понедельник")
                        reveal("${prefix}_empty_inferred_year", container).assertTextEquals(yearNote)
                        labelFits("${prefix}_empty_inferred_year", yearNote, true)
                        compose.onAllNodesWithTag("${prefix}_next_service").assertCountEquals(0)
                        capture("${current.label}-year-note")
                        sourceAction(current, if (church) "open_schedule_website" else "today_schedule_source", "Открыть сайт")
                    }
                }
                // The accepted actual source is not partial. This separate complete-month
                // control obtains both flags through production parsing, never DTO copies.
                val control = OwnedReplay("controlled", controlled = true).also { owned += it }
                val controlState = control.load(now)
                receipt["controlledClassification"] = "CONTROLLED_NOT_CAPTURED_HTTP_NOT_CASE043_SOURCE_SUCCESS"
                receipt["actualControlState"] = controlState
                assertEquals(InternetScheduleStatus.READY, controlState.status)
                val controlPublication = requireNotNull(controlState.publication)
                assertTrue(controlPublication.yearInferred && controlPublication.knownPartial)
                assertFalse(controlState.sourceHasWithdrawals)
                assertEquals("https://calendar-native-control.example/", controlPublication.sourceUrl)
                assertFalse(controlPublication.events.any { it.date == "2026-11-21" })
                assertEquals(1, control.requests.size)
                assertEquals("SERVED_CONTROLLED_HTML_NOT_CAPTURED_HTTP", control.requests.single()["outcome"])
                offlineCheck(control, controlState)
                val controlRaw = control.raw(); val controlValues = control.values(); val controlXml = control.xml()
                val controlProjected = control.parish.withInternetSchedule(controlState, now)
                assertTrue(controlProjected.scheduleKnownPartial)
                val controlEvent = service(controlPublication.events.single { it.date == "2026-11-16" })
                assertEquals("09:00", controlEvent.time); assertEquals("Литургия", controlEvent.title)
                for (church in listOf(false, true)) {
                    val current = ScreenCase(church, controlProjected, controlState, now, "control-${if (church) "church" else "today"}")
                    show(current); controlledFeatured(current, controlEvent)
                    if (church) agenda(current, controlEvent, null)
                }
                assertEquals(cold, primary.cold())
                assertEquals(primaryRaw, primary.raw()); assertEquals(primaryValues, primary.values())
                assertArrayEquals(primaryXml, primary.xml())
                assertEquals(controlRaw, control.raw()); assertEquals(controlValues, control.values())
                assertArrayEquals(controlXml, control.xml())
                assertEquals(0, primary.offlineCalls.get()); assertEquals(0, control.offlineCalls.get())
                receipt["passed"] = true
            }
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name; receipt["failureMessage"] = failure.message
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            if (contentSet) runCatching { compose.runOnIdle { mounted = false }; compose.waitForIdle() }
                .onFailure { cleanupFailure = it }
            receipt["replays"] = owned.map { replay -> mapOf("owner" to replay.owner, "controlled" to replay.controlled,
                "requests" to replay.requests.toList(), "offlineHttpCalls" to replay.offlineCalls.get(), "cleanupErrors" to replay.cleanup()) }
            receipt["observations"] = observations; receipt["screenshots"] = screenshots
            if (cleanupFailure != null) { receipt["passed"] = false; receipt["uiCleanupFailure"] = cleanupFailure.toString() }
            val errors = (receipt["replays"] as List<*>).map { (it as Map<*, *>)["cleanupErrors"] as List<*> }.flatten()
            if (errors.isNotEmpty()) receipt["passed"] = false
            File(output, "receipt.json").writeText(gson.toJson(receipt))
            if (!receipt.containsKey("failureType")) {
                cleanupFailure?.let { throw it }
                assertTrue("Only UUID-owned cleanup: $errors", errors.isEmpty())
            }
        }
    }

    private fun offlineCheck(replay: OwnedReplay, actual: InternetScheduleState): InternetScheduleState {
        val raw = requireNotNull(replay.raw())
        val beforeValues = replay.values()
        val beforeXml = replay.xml()
        val encoded = JsonParser.parseString(raw).asJsonObject.getAsJsonObject("publication")
        val publication = requireNotNull(actual.publication)
        assertEquals(publication.yearInferred, encoded["yearInferred"].asBoolean)
        assertEquals(publication.knownPartial, encoded["knownPartial"].asBoolean)
        val cold = replay.cold()
        assertEquals(actual, cold); assertNotSame(publication, cold.publication)
        assertEquals(raw, replay.raw()); assertEquals(beforeValues, replay.values())
        assertArrayEquals(beforeXml, replay.xml()); assertEquals(0, replay.offlineCalls.get())
        observations += mapOf("owner" to replay.owner, "kind" to "actual_cache_reopen", "rawSha256" to sha(raw.toByteArray()),
            "xmlSha256" to sha(beforeXml), "yearInferred" to publication.yearInferred, "knownPartial" to publication.knownPartial)
        return cold
    }

    private fun launchScreens() {
        compose.setContent { GuideTheme(dark) {
            Box(Modifier.fillMaxSize().testTag("yearless043_native_host")) {
                if (mounted) screen?.let { current -> key(generation) {
                    if (current.church) ChurchScreen(listOf(current.parish), current.parish.id,
                        current.now.atZone(java.time.ZoneId.of(current.parish.timezone)).toLocalDate(),
                        onSelect = { _, _ -> error("An occurrence must not select another parish") },
                        onOpenUrl = { urls += it }, onBack = {}, showBack = false, searchQuery = "", onSearchQueryChange = {},
                        internetSchedule = current.state, now = current.now, onOpenService = { opened += it })
                    else TodayScreen(current.parish, current.now, current.state, null, null,
                        onFindChurch = {}, onChurchDetails = {}, onResumeBook = {}, onRefresh = {}, onResumeLegacy = null,
                        bottomBar = {}, onOpenService = { opened += it }, onOpenBook = { _, _, _ -> error("Use the actual occurrence") },
                        onOpenScheduleUrl = { urls += it }, onOpenScheduleSource = {
                            // Legacy Unit callback has no URL argument; its direct-host proof
                            // is explicitly limited to this actual provider binding, not Main.
                            urls += requireNotNull(current.state.publication).sourceUrl
                        })
                } }
            }
        } }
        contentSet = true
        compose.onNodeWithTag("yearless043_native_host").assertWidthIsEqualTo(width.dp).assertHeightIsEqualTo(height.dp)
        observations += mapOf("kind" to "actual_profile", "hostBoundsDp" to rect(compose.onNodeWithTag("yearless043_native_host").getUnclippedBoundsInRoot()),
            "resourceFontScale" to compose.activity.resources.configuration.fontScale, "dark" to dark,
            "windowWidthPx" to compose.activity.window.decorView.width, "windowHeightPx" to compose.activity.window.decorView.height)
    }

    private fun show(next: ScreenCase) {
        compose.runOnIdle { screen = next; generation++; opened.clear(); urls.clear() }
        compose.waitForIdle()
    }

    private fun date(current: ScreenCase, today: String = "16 ноября", church: String = "16 ноября 2026, понедельник") {
        reveal(if (current.church) "schedule_date" else "today_service_date", if (current.church) "church_content" else "main_content", true)
            .assertTextEquals(if (current.church) church else today)
    }

    private fun readAction(current: ScreenCase, tag: String, label: String, event: ParishService) {
        val before = opened.toList(); val sourceBefore = urls.toList()
        val container = if (current.church) "church_content" else "main_content"
        reveal(tag, container).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertTextEquals(label)
        labelFits(tag, label); capture("${current.label}-reading")
        compose.onNodeWithTag(tag).performClick()
        compose.runOnIdle { assertEquals(before + event, opened); assertEquals(sourceBefore, urls) }
    }

    private fun sourceAction(current: ScreenCase, tag: String, label: String) {
        val before = urls.toList(); val readBefore = opened.toList()
        val container = if (current.church) "church_content" else "main_content"
        reveal(tag, container).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertTextEquals(label)
        labelFits(tag, label); capture("${current.label}-source")
        compose.onNodeWithTag(tag).performClick()
        compose.runOnIdle { assertEquals(before + requireNotNull(current.state.publication).sourceUrl, urls); assertEquals(readBefore, opened) }
    }

    private fun emptyAndFuture(current: ScreenCase, future: ParishService) {
        val prefix = if (current.church) "church" else "today"
        val container = if (current.church) "church_content" else "main_content"
        reveal(if (current.church) "schedule_today" else "today_day_today", container, true).assertIsSelected()
        date(current)
        reveal("${prefix}_schedule_empty", container).assertTextEquals("В расписании есть изменения")
        reveal("${prefix}_next_date_time", container).assertTextEquals("18 ноября 2026 · 08:00")
        reveal("${prefix}_next_title", container).assertTextEquals(future.title)
        reveal("${prefix}_next_inferred_year", container).assertTextEquals(yearNote)
        labelFits("${prefix}_next_inferred_year", yearNote, true)
        compose.onAllNodesWithTag("${prefix}_next_partial_schedule").assertCountEquals(0)
        readAction(current, "${prefix}_open_next_service", "Выбрать текст службы", future)
        sourceAction(current, if (current.church) "open_schedule_website" else "today_schedule_source", "Открыть сайт")
        date(current)
    }

    private fun controlledFeatured(current: ScreenCase, event: ParishService) {
        val prefix = if (current.church) "church" else "today"
        val container = if (current.church) "church_content" else "main_content"
        date(current)
        reveal("schedule_inferred_year", container).assertTextEquals(yearNote)
        labelFits("schedule_inferred_year", yearNote, true)
        val notice = "Часть расписания не удалось прочитать - проверьте сайт храма"
        reveal("${prefix}_service_partial_schedule", container).assertTextEquals(notice)
        labelFits("${prefix}_service_partial_schedule", notice, true)
        val reading = if (current.church) "church_open_service" else "open_service_book"
        readAction(current, reading, if (current.church) "Открыть литургию" else "Открыть службу", event)
        sourceAction(current, "${prefix}_service_partial_source", "Проверить расписание")
        val readBounds = requireNotNull(NativeRevealProbe(compose, container, reading).bounds().targetBounds)
        val sourceBounds = requireNotNull(NativeRevealProbe(compose, container, "${prefix}_service_partial_source").bounds().targetBounds)
        assertTrue("Reading first and at least12dp between targets", (sourceBounds.top - readBounds.bottom).value >= 11.5f)
        observations += mapOf("kind" to "both_flags_control", "screen" to prefix, "readingDp" to rect(readBounds), "sourceDp" to rect(sourceBounds))
    }

    private fun agenda(current: ScreenCase, event: ParishService, hours: ParishService?) {
        fun position(): Float {
            val nodes = compose.onAllNodesWithTag("church_content").fetchSemanticsNodes(atLeastOneRootRequired = false)
            assertEquals(1, nodes.size)
            return compose.runOnUiThread { nodes.single().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() }
        }
        fun open() { reveal("church_agenda_open", "church_content").assertHeightIsAtLeast(48.dp).performClick(); compose.waitForIdle() }
        open()
        fun note(text: String, count: Int) {
            val matcher = hasText(text) and hasAnyAncestor(hasTestTag("schedule_agenda"))
            compose.onAllNodes(matcher).assertCountEquals(count)
            if (count == 1) {
                val node = compose.onNode(matcher, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                assertTextFitsLayout(layouts.single(), text)
                assertEquals(scale, layouts.single().layoutInput.density.fontScale, .001f)
            }
        }
        note(yearNote, 1)
        note("Не все записи удалось прочитать", if (requireNotNull(current.state.publication).knownPartial) 1 else 0)
        note("Сохраненное расписание - проверьте изменения", 0) // Actual current16 READY editions are fresh, including cache reads.
        reveal("schedule_agenda_source", "schedule_agenda_list").assertHeightIsAtLeast(48.dp)
        labelFits("schedule_agenda_source", "Открыть источник расписания")
        val beforeUrls = urls.toList(); val beforeReads = opened.toList()
        capture("${current.label}-agenda-notes-source")
        compose.onNodeWithTag("schedule_agenda_source").performClick()
        compose.runOnIdle { assertEquals(beforeUrls + requireNotNull(current.state.publication).sourceUrl, urls); assertEquals(beforeReads, opened) }
        compose.onNodeWithTag("schedule_agenda_close").performClick(); compose.waitForIdle()
        date(current)
        reveal("church_agenda_open", "church_content")
        val beforeScroll = position()
        compose.onNodeWithTag("church_agenda_open").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("schedule_agenda_close").performClick(); compose.waitForIdle()
        assertEquals(beforeScroll, position(), .01f)
        open()
        val tag = agendaServiceTag(event)
        reveal(tag, "schedule_agenda_list").assertHeightIsAtLeast(48.dp)
        val before = opened.toList(); val sources = urls.toList()
        capture("${current.label}-agenda-occurrence")
        compose.onNodeWithTag(tag).performClick(); compose.waitForIdle()
        compose.runOnIdle { assertEquals(before + event, opened); assertEquals(sources, urls) }
        compose.onAllNodesWithTag("schedule_agenda").assertCountEquals(0)
        date(current)
        if (hours != null) {
            open(); reveal(agendaServiceTag(hours), "schedule_agenda_list").assertHeightIsAtLeast(48.dp)
            capture("${current.label}-agenda22")
            val beforeHours = opened.toList()
            compose.onNodeWithTag(agendaServiceTag(hours)).performClick(); compose.waitForIdle()
            compose.runOnIdle { assertEquals(beforeHours + hours, opened) }
            val route = requireNotNull(ServiceBookSelection.resolve(hours, current.parish))
            val journey = requireNotNull(route.journeyContext)
            assertEquals(current.parish.id, route.parishId); assertEquals(current.parish.id, journey.parishId)
            assertEquals("2026-11-22", journey.civilDate); assertEquals("06:40", journey.time)
            assertEquals("Часы. Литургия. Водосвятный молебен. Заупокойная лития", journey.title)
            assertEquals(BookPartSetKind.HOURS_LITURGY_TEXTS, journey.effectivePartSet)
            assertEquals(WorshipKind.HOUR3, route.activeKey.worship)
            assertEquals(listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY), journey.parts.map { it.worship })
            assertTrue(journey.parts.all { it.date == "2026-11-22" })
            observations += mapOf("kind" to "actual22_route_only_no_download", "route" to route)
            date(current)
        }
    }

    private fun inside(target: DpRect, viewport: DpRect) = target.right > target.left && target.bottom > target.top &&
        target.top >= viewport.top && target.bottom <= viewport.bottom && target.left >= viewport.left && target.right <= viewport.right

    private fun reveal(tag: String, containerTag: String, backwards: Boolean = false): SemanticsNodeInteraction {
        val probe = NativeRevealProbe(compose, containerTag, tag)
        var previousDirection: Boolean? = null
        var limit = .18f
        repeat(32) {
            compose.waitForIdle()
            val frame = probe.bounds()
            val viewport = frame.viewport
            val box = frame.targetBounds
            if (box != null && inside(box, viewport)) return probe.interaction(frame).assertIsDisplayed()
            if (box != null) {
                if (!listOf(box.left.value, box.top.value, box.right.value, box.bottom.value).all { it.isFinite() })
                    probe.fail("Nonfinite target bounds")
                if (box.left < viewport.left || box.right > viewport.right)
                    probe.fail("Target is horizontally clipped; vertical gestures cannot repair it")
                if ((box.bottom - box.top) > (viewport.bottom - viewport.top))
                    probe.fail("Target is taller than the available viewport")
            }
            val back = box?.let { it.top < viewport.top } ?: (previousDirection ?: backwards)
            if (previousDirection != null && previousDirection != back) limit = (limit * .5f).coerceAtLeast(.04f)
            previousDirection = back
            val distance = box?.let {
                (if (back) viewport.top - it.top else it.bottom - viewport.bottom).value.coerceAtLeast(0f)
            }
            val fraction = if (distance == null) limit else
                minOf(limit, distance / (viewport.bottom - viewport.top).value.coerceAtLeast(1f) * .75f + .035f).coerceAtLeast(.035f)
            probe.swipe(if (back) .5f - fraction / 2 else .5f + fraction / 2,
                if (back) .5f + fraction / 2 else .5f - fraction / 2, 500)
        }
        probe.fail("Could not fully reveal by bounded adaptive real touch")
    }

    private fun labelFits(tag: String, label: String, taggedText: Boolean = false) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = if (taggedText) compose.onNodeWithTag(tag) else compose.onNode(
            hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertTextFitsLayout(layout, label)
        assertEquals("Keep the actual accessibility font scale", scale, layout.layoutInput.density.fontScale, .001f)
        if (!taggedText) assertTrue("Complete label stays inside its action target", inside(
            node.getUnclippedBoundsInRoot(), compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()))
    }

    private fun rect(value: DpRect) = mapOf("left" to value.left.value, "top" to value.top.value,
        "right" to value.right.value, "bottom" to value.bottom.value)

    private fun capture(label: String) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        val tree = compose.runOnIdle { buildString {
            fun appendNode(node: SemanticsNode, depth: Int) {
                append("  ".repeat(depth)).append(node.id).append(' ').append(node.boundsInRoot).append(' ').append(node.config).append('\n')
                if (depth < 80) node.children.forEach { appendNode(it, depth + 1) }
            }
            roots.forEach { appendNode(it, 0) }
        } }
        File(output, "$label.semantics.txt").writeText(tree)
        if (Build.VERSION.SDK_INT >= 29) {
            val committed = CountDownLatch(1)
            compose.activityRule.scenario.onActivity {
                it.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                it.window.decorView.invalidate()
            }
            assertTrue("A real frame must commit before capture", committed.await(5, TimeUnit.SECONDS))
        }
        var bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var stable = false
        try {
            repeat(10) {
                if (!stable) {
                    SystemClock.sleep(120)
                    val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    stable = bitmap.sameAs(next)
                    bitmap.recycle(); bitmap = next
                }
            }
            File(output, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshots += mapOf("label" to label, "stableTwoFrames" to stable,
                "widthPx" to bitmap.width, "heightPx" to bitmap.height)
            assertTrue("Static production screen must settle before capture", stable)
        } finally { bitmap.recycle() }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}x{1}dp font={2} dark={3}")
        fun profiles() = listOf(arrayOf<Any>(411, 914, 1f, false), arrayOf<Any>(320, 800, 2f, false),
            arrayOf<Any>(320, 800, 2f, true))
    }
}
