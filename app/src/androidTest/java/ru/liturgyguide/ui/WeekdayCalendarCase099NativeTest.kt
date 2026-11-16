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

/** Known exact-URL development replay, actual Android extraction/cache and direct production screens. */
@RunWith(Parameterized::class)
class WeekdayCalendarCase099NativeTest(
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
        .open("weekday-calendar-case099-native/$path").use { it.readBytes() }
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

    @Test fun exactRecordedCalendarImportsPartialPublicationReopensOfflineAndKeepsSelectedDateAndReadingPrimary() = runBlocking<Unit> {
        val manifestBytes = bytes("FIXTURE-MANIFEST.json")
        val mappingBytes = bytes("REQUEST-MAPPING.json")
        assertEquals("9477799710a3b5f27ccac86ad5e1215a317f1114385267aa77123262e139d5c4", sha(manifestBytes))
        assertEquals("e6abc0825c92daea0b8555d9da8c894c15602a99717fecd2159ed5c541ec3ee7", sha(mappingBytes))
        val manifest = json("FIXTURE-MANIFEST.json")
        val mapping = json("REQUEST-MAPPING.json")
        val fixtureRows = rows(manifest, "inputs")
        assertEquals(16, fixtureRows.size)
        fixtureRows.forEach { row ->
            val copied = bytes(row["path"].asString)
            assertEquals(row["path"].asString, row["bytes"].asInt, copied.size)
            assertEquals(row["path"].asString, row["sha256"].asString, sha(copied))
        }
        assertEquals(37, mapping["recordedGetResponseCount"].asInt)
        val expected = json("inputs/EXPECTED-099.json")
        assertEquals("adf3676ea2c5edae2de86a3fc7359772d509e1aea66aff6afc7db341d7d486f4", sha(bytes("inputs/EXPECTED-099.json")))
        assertEquals("1e87ee032275e9654be3d4185dc284c28b1d332c3ac866e00214bd7f97bd9a38", sha(bytes("inputs/case099/responses/0040.body")))
        val original = json("inputs/case099/case.json")
        assertTrue(original.getAsJsonObject("originalSelected")["website"].isJsonNull)
        val parish = gson.fromJson(original["savedParish"], Parish::class.java)
        assertEquals(expected["selectedId"].asString, parish.id)
        assertEquals("Europe/Moscow", parish.timezone)
        assertEquals("", parish.website)
        assertTrue(parish.schedule.isEmpty())
        assertFalse(parish.scheduleKnownPartial)
        val now = Instant.parse(expected["clockCapturedAt"].asString)
        val date = LocalDate.parse(expected["today"].asString)
        val sourceUrl = mapping["preferredInitialRequest"].asString
        val responseRows = rows(mapping, "providerResponses")
        assertEquals(10, responseRows.size)
        assertEquals(sourceUrl, responseRows.single { it["bodyFile"].asString == "responses/0040.body" }["requestedUrl"].asString)
        val historicalFive = rows(expected, "expectedEvents").map { gson.fromJson(it, InternetService::class.java) }
        assertEquals(5, historicalFive.size)
        // A separate derived oracle augments the immutable original package; never rewrite its inputs.
        val derivedBytes = bytes("EXPECTED-099-CALENDAR-MEMORY-V2.json")
        assertEquals("c30fccd89f40794709cb9dfe2712b573c36d711bca5444569b92cfb608eff38d", sha(derivedBytes))
        val derived = json("EXPECTED-099-CALENDAR-MEMORY-V2.json")
        assertEquals(sha(bytes("inputs/EXPECTED-099.json")), derived["historicalOracleSha256"].asString)
        assertEquals(sha(bytes("inputs/case099/responses/0040.body")), derived["sourceSha256"].asString)
        assertEquals(sha(bytes("inputs/case099-source-rows.json")), derived["sourceRowsSha256"].asString)
        val rowProof = derived.getAsJsonObject("additionalSourceProof")
        val originalRow11 = rows(json("inputs/case099-source-rows.json"), "rows").single { it["physicalRow"].asInt == 11 }
        assertEquals(11, rowProof["physicalRow"].asInt)
        assertEquals(originalRow11["literalCells"], rowProof["literalCells"])
        assertEquals(originalRow11["sourceCellOriginPhysicalRows"], rowProof["sourceCellOriginPhysicalRows"])
        assertTrue(bytes("inputs/case099/responses/0040.body").toString(Charsets.UTF_8).contains(rowProof["exactRawRow"].asString))
        val additional = rows(derived, "additionalEvents").map { gson.fromJson(it, InternetService::class.java) }
        assertEquals(listOf(InternetService("2026-11-18", "09:00", "Молебен", "service_block")), additional)
        val expectedEvents = rows(derived, "expectedEvents").map { gson.fromJson(it, InternetService::class.java) }
        assertEquals((historicalFive + additional).sortedBy { "${it.date}T${it.time}" }, expectedEvents)
        assertEquals(6, expectedEvents.size)

        val owner = "weekday-calendar-case099-${UUID.randomUUID()}"
        val owned = File(app.cacheDir, owner).apply { check(!exists() && mkdirs()) }
        output = File(requireNotNull(app.getExternalFilesDir("weekday-calendar-case099-native")), owner)
            .apply { check(!exists() && mkdirs()) }
        val preferenceNames = ConcurrentHashMap.newKeySet<String>()
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
        assertSame(context, context.applicationContext)
        val requests = CopyOnWriteArrayList<Map<String, Any?>>()
        val extraction = CopyOnWriteArrayList<Map<String, Any?>>()
        val nativeOcr = CopyOnWriteArrayList<String>()
        val offlineCalls = AtomicInteger()
        val receipt = linkedMapOf<String, Any?>(
            "passed" to false, "classification" to "KNOWN_CASE099_MANUAL_EXACT_URL_REPLAY_NOT_INDEPENDENT_SUCCESS",
            "entryMode" to "Original blank website; production sourceOverride with exact recorded article0040 URL",
            "owner" to owner, "selectedId" to parish.id, "clock" to now.toString(), "sourceUrl" to sourceUrl,
            "derivedExpectation" to mapOf("historicalOracleSha256" to sha(bytes("inputs/EXPECTED-099.json")),
                "derivedOracleSha256" to sha(derivedBytes), "additionalEvents" to additional,
                "rowProof" to "Source physical row11, same original source SHA, independent manual transcription"),
            "requestedProfile" to mapOf("widthDp" to width, "heightDp" to height, "fontScale" to scale, "dark" to dark),
            "notMeasured" to listOf("coldDiscovery", "freshUserConfirmation", "physicalMain", "physicalProcessRestart",
                "bookContent", "fullRussianTranslation", "wholeSourceCompleteness", "independentPercentage"),
            "protected68" to "Root-owned before/after check required; this test only creates UUID-owned storage")
        try {
            withTimeout(240_000) {
                val preferences = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
                fun cache() = object : ScheduleCacheStore {
                    override val coordinationLock: Any get() = preferences
                    override fun read(parishId: String) = preferences.getString(parishId, null)
                    override fun write(parishId: String, value: String) {
                        check(preferences.edit().putString(parishId, value).commit())
                    }
                }
                val transport = ScheduleTransport { requested ->
                    val row = responseRows.singleOrNull { it["requestedUrl"].asString == requested }
                    if (row == null) {
                        requests += mapOf("requestedUrl" to requested, "outcome" to "UNAVAILABLE_NOT_RECORDED")
                        throw IOException("No exact recorded case099 response: $requested")
                    }
                    val body = bytes(row["includedPath"].asString)
                    assertEquals(row["writtenBytes"].asInt, body.size)
                    assertEquals(row["writtenSha256"].asString, sha(body))
                    requests += mapOf("requestedUrl" to requested, "outcome" to "SERVED_RECORDED_BYTES",
                        "sha256" to sha(body), "originalCallIndex" to row["originalCallIndex"].asInt)
                    ScheduleResponse(row["recordedResponseUrl"].asString, body, row["contentType"].asString)
                }
                val native = AndroidScheduleDocumentExtractor(context) { nativeOcr += it.sourceUrl }
                val observedExtractor = ScheduleDocumentExtractor { response ->
                    val document = native.extract(response)
                    extraction += mapOf("sourceUrl" to response.url, "contentType" to response.contentType,
                        "responseSha256" to sha(response.bytes), "documentFormat" to document?.format,
                        "documentSourceUrl" to document?.sourceUrl)
                    document
                }
                // Production dispatches HTML directly to ScheduleHtmlParser. Exercise the
                // Android extractor separately on the same complete pinned article, then
                // let the repository independently acquire/parse it through its real branch.
                val articleRow = responseRows.single { it["requestedUrl"].asString == sourceUrl }
                val articleDocument = requireNotNull(observedExtractor.extract(ScheduleResponse(
                    articleRow["recordedResponseUrl"].asString, bytes(articleRow["includedPath"].asString),
                    articleRow["contentType"].asString)))
                assertEquals(sourceUrl, articleDocument.sourceUrl)
                assertEquals("html", articleDocument.format)
                val nativeParsed = ScheduleDocumentParser.inspect(articleDocument, date, parish)
                assertEquals(ScheduleParseDisposition.PARSED, nativeParsed.disposition)
                assertEquals(expectedEvents, nativeParsed.events)
                assertTrue(nativeParsed.knownPartial)
                receipt["directAndroidHtmlExtraction"] = mapOf("disposition" to nativeParsed.disposition.name,
                    "events" to nativeParsed.events, "knownPartial" to nativeParsed.knownPartial,
                    "role" to "Separate actual Android HTML extraction; repository owns its independent direct HTML dispatch")
                val repository = InternetScheduleRepository(transport, cache(), observedExtractor)
                assertNull(cache().read(parish.id))
                val imported = repository.refresh(parish, now, force = true, sourceOverride = sourceUrl)
                receipt["importedState"] = imported
                assertEquals(InternetScheduleStatus.READY, imported.status)
                assertEquals(parish.id, imported.parishId)
                assertEquals("", imported.configuredWebsite)
                assertEquals(sourceUrl, imported.customSource)
                assertFalse(imported.publicationRejected)
                assertFalse(imported.sourceNeedsVerification)
                assertNull("Manual exact URL does not invent a verified site binding", imported.siteBinding)
                val publication = requireNotNull(imported.publication)
                assertEquals(parish.id, publication.parishId)
                assertEquals(parish.timezone, publication.timezone)
                assertEquals(sourceUrl, publication.sourceUrl)
                assertEquals(now.toString(), publication.fetchedAt)
                assertFalse(publication.yearInferred)
                assertTrue(publication.hasCurrentParserRevision)
                assertTrue(publication.knownPartial)
                assertEquals("Immutable historical five plus independently proven row11, including past14Nov", expectedEvents, publication.events)
                (expected.getAsJsonArray("mustNotContainDates") + expected.getAsJsonArray("mustNotInventDates")).forEach { forbidden ->
                    assertFalse(publication.events.any { it.date == forbidden.asString })
                }
                assertTrue(requests.any { it["requestedUrl"] == sourceUrl && it["outcome"] == "SERVED_RECORDED_BYTES" })
                assertTrue("Separate exact HTML preflight traverses the actual Android extractor", extraction.any {
                    it["sourceUrl"] == sourceUrl && it["documentFormat"] == "html" &&
                        it["responseSha256"] == "1e87ee032275e9654be3d4185dc284c28b1d332c3ac866e00214bd7f97bd9a38"
                })
                val projected = parish.withInternetSchedule(imported, now)
                assertTrue(projected.scheduleKnownPartial)
                assertEquals(expectedEvents.filter { LocalDate.parse(it.date) >= date }.map {
                    ParishService(it.date, it.time, it.title, it.kind)
                }, projected.schedule)
                assertEquals(expectedEvents.count { LocalDate.parse(it.date) >= date }, projected.schedule.size)

                val rawBefore = requireNotNull(cache().read(parish.id))
                val serialized = JsonParser.parseString(rawBefore).asJsonObject.getAsJsonObject("publication")
                assertTrue(serialized["knownPartial"].asBoolean)
                assertEquals(expectedEvents, serialized.getAsJsonArray("events").map { gson.fromJson(it, InternetService::class.java) })
                val preferenceFile = File(app.applicationInfo.dataDir, "shared_prefs/$owner-schedule.xml")
                assertTrue("Synchronous commit must produce owned preference bytes", preferenceFile.isFile)
                val physicalBefore = preferenceFile.readBytes()
                val allBefore = preferences.all.toMap()
                val denied = ScheduleTransport { requested ->
                    offlineCalls.incrementAndGet()
                    throw IOException("Offline cache-only reopen forbids request: $requested")
                }
                val coldRepository = InternetScheduleRepository(denied, cache(), AndroidScheduleDocumentExtractor(context))
                val cold = coldRepository.cached(parish)
                assertEquals(imported, cold)
                assertNotSame(publication, cold.publication)
                assertEquals(expectedEvents, requireNotNull(cold.publication).events)
                assertTrue(cold.publication!!.knownPartial)
                assertEquals(rawBefore, cache().read(parish.id))
                assertEquals(allBefore, preferences.all)
                assertArrayEquals(physicalBefore, preferenceFile.readBytes())
                assertEquals(0, offlineCalls.get())
                receipt["cacheRawSha256"] = sha(rawBefore.toByteArray(Charsets.UTF_8))
                receipt["cachePhysicalXmlSha256"] = sha(physicalBefore)
                receipt["coldState"] = cold
                receipt["projectedEvents"] = projected.schedule

                data class ScreenCase(val church: Boolean, val parish: Parish, val state: InternetScheduleState, val mode: String)
                var screen by mutableStateOf(ScreenCase(false, projected, imported, "live"))
                var generation by mutableIntStateOf(0)
                val opened = mutableListOf<ParishService>()
                val sources = mutableListOf<String>()
                val manualBookChoices = mutableListOf<Triple<LocalDate, WorshipKind, Boolean>>()
                var allowManualBookChoice = false
                compose.setContent { GuideTheme(dark) {
                    Box(Modifier.fillMaxSize().testTag("weekday_calendar_native_host")) {
                        if (mounted) key(generation) {
                            val current = screen
                            if (current.church) ChurchScreen(listOf(current.parish), parish.id, date,
                                onSelect = { _, _ -> error("An occurrence must not select another parish") },
                                onOpenUrl = { sources += it }, onBack = {}, showBack = false,
                                searchQuery = "", onSearchQueryChange = {}, internetSchedule = current.state, now = now,
                                onOpenService = { opened += it })
                            else TodayScreen(current.parish, now, current.state, null, null,
                                onFindChurch = {}, onChurchDetails = {}, onResumeBook = {}, onRefresh = {},
                                onResumeLegacy = null, bottomBar = {}, onOpenService = { opened += it },
                                onOpenBook = { selectedDate, worship, evening ->
                                    check(allowManualBookChoice) { "A published occurrence must not masquerade as a book route" }
                                    manualBookChoices += Triple(selectedDate, worship, evening)
                                },
                                onOpenScheduleSource = { error("Use the exact URL callback") },
                                onOpenScheduleUrl = { sources += it })
                        }
                    }
                } }
                contentSet = true
                val host = requireNotNull(NativeRevealProbe(compose, "weekday_calendar_native_host", "today_service_date")
                    .bounds().container.unclipped)
                assertEquals("Actual host width", width.toFloat(), (host.right - host.left).value, .5f)
                assertEquals("Actual host height", height.toFloat(), (host.bottom - host.top).value, .5f)
                receipt["actualHostBoundsDp"] = rect(host)
                receipt["actualResourceFontScale"] = compose.activity.resources.configuration.fontScale
                receipt["actualWindowPixels"] = mapOf("width" to compose.activity.window.decorView.width, "height" to compose.activity.window.decorView.height)
                val modes = if (width == 411) listOf("live", "cold_saved") else listOf("live")
                for (mode in modes) for (church in listOf(false, true)) {
                    compose.runOnIdle {
                        screen = ScreenCase(church, if (mode == "live") projected else parish,
                            if (mode == "live") imported else cold, mode)
                        generation++
                        opened.clear(); sources.clear(); manualBookChoices.clear(); allowManualBookChoice = false
                    }
                    val prefix = if (church) "church" else "today"
                    val container = if (church) "church_content" else "main_content"
                    val dateTag = if (church) "schedule_date" else "today_service_date"
                    val todayTag = if (church) "schedule_today" else "today_day_today"
                    val tomorrowTag = if (church) "schedule_tomorrow" else "today_day_tomorrow"
                    val todayText = if (church) "15 ноября 2026, воскресенье" else "15 ноября"
                    val tomorrowText = if (church) "16 ноября 2026, понедельник" else "16 ноября"
                    val featured = expectedEvents.single { it.date == date.toString() }.let { ParishService(it.date, it.time, it.title, it.kind) }
                    val offer = expectedEvents.single { it.date == "2026-11-18" }.let { ParishService(it.date, it.time, it.title, it.kind) }
                    assertEquals(ParishService("2026-11-18", "09:00", "Молебен", "service_block"), offer)
                    assertNull("Moleben has no supported book and cannot silently become Liturgy", ServiceBookSelection.kindFor(offer.title))
                    reveal(todayTag, container, true).assertIsSelected()
                    reveal(dateTag, container).assertTextEquals(todayText)
                    reveal("${prefix}_service_time", container).assertTextEquals(featured.time)
                    reveal("${prefix}_service_title", container).assertTextEquals(featured.title)
                    reveal("${prefix}_service_relation", container).assertTextEquals(if (mode == "cold_saved")
                        "Сохраненное расписание - проверьте изменения" else "Служба из опубликованного расписания")
                    val featuredAction = if (church) "church_open_service" else "open_service_book"
                    checkBlock("$mode-$prefix-featured", container, featuredAction, "${prefix}_service_partial_schedule",
                        "${prefix}_service_partial_source", "Часть расписания не удалось прочитать - проверьте сайт храма",
                        sourceUrl, featured, opened, sources,
                        readingLabel = if (church) "Открыть литургию" else "Открыть службу")
                    reveal(tomorrowTag, container, true).performClick().assertIsSelected()
                    reveal(dateTag, container).assertTextEquals(tomorrowText)
                    reveal("${prefix}_schedule_empty", container).assertTextEquals("Расписание прочитано не полностью")
                    reveal("${prefix}_next_date_time", container).assertTextEquals("18 ноября 2026 · 09:00")
                    reveal("${prefix}_next_title", container).assertTextEquals(offer.title)
                    compose.onNode(hasText("Одна из опубликованных служб") and hasAnyAncestor(hasTestTag("${prefix}_next_service")))
                        .assertExists()
                    if (mode == "cold_saved") reveal("${prefix}_next_saved", container)
                        .assertTextEquals("Сохраненное расписание - проверьте изменения")
                    compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("${prefix}_next_service")))
                        .assertCountEquals(2)
                    compose.onAllNodes(hasText("Открыть литургию") and hasAnyAncestor(hasTestTag("${prefix}_next_service")))
                        .assertCountEquals(0)
                    compose.onAllNodes(hasText("Открыть службу") and hasAnyAncestor(hasTestTag("${prefix}_next_service")))
                        .assertCountEquals(0)
                    checkBlock("$mode-$prefix-empty16-offer18-moleben", container, "${prefix}_open_next_service",
                        "${prefix}_next_partial_schedule", "${prefix}_next_partial_source",
                        "Не все записи удалось прочитать. Проверьте выбранную дату на сайте храма",
                        sourceUrl, offer, opened, sources, readingLabel = "Выбрать текст службы")
                    compose.runOnIdle { assertTrue("The next occurrence callback must not choose a book", manualBookChoices.isEmpty()) }
                    reveal(dateTag, container, true).assertTextEquals(tomorrowText)
                    reveal(tomorrowTag, container, true).assertIsSelected()
                    val beforeManualReading = compose.runOnIdle { opened.toList() }
                    val beforeManualSources = compose.runOnIdle { sources.toList() }
                    if (!church) {
                        // The reading library belongs to the selected16Nov, independently of the next18Nov occurrence.
                        reveal("today_other_services", container).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
                        reveal("service_chooser_date", "service_book_chooser").assertTextEquals("Тексты на 16 ноября 2026")
                        labelFits("service_chooser_date", "Тексты на 16 ноября 2026", taggedText = true)
                        capture("$mode-$prefix-manual16-chooser")
                        val manualLiturgy = reveal("service_book_choose_liturgy", "service_book_chooser")
                            .assertIsEnabled().assertHeightIsAtLeast(48.dp)
                        compose.runOnIdle { allowManualBookChoice = true }
                        manualLiturgy.performClick()
                        compose.runOnIdle {
                            allowManualBookChoice = false
                            assertEquals(listOf(Triple(LocalDate.of(2026, 11, 16), WorshipKind.LITURGY, false)), manualBookChoices)
                            assertEquals(beforeManualReading, opened)
                            assertEquals(beforeManualSources, sources)
                        }
                        compose.onNodeWithTag("service_book_chooser").assertDoesNotExist()
                        observations += mapOf("label" to "$mode-$prefix-manual16-choice", "date" to "2026-11-16",
                            "worship" to "LITURGY", "evening" to false, "nextOccurrenceUnchanged" to offer)
                    } else {
                        // Retain the previous exact21Nov Liturgy callback through the actual agenda, not as the next offer.
                        val actual21 = expectedEvents.single { it.date == "2026-11-21" }
                            .let { ParishService(it.date, it.time, it.title, it.kind) }
                        reveal("church_agenda_open", container).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
                        reveal(agendaServiceTag(actual21), "schedule_agenda_list").assertIsEnabled().assertHeightIsAtLeast(48.dp)
                        capture("$mode-$prefix-agenda21-liturgy")
                        compose.onNodeWithTag(agendaServiceTag(actual21)).performClick()
                        compose.runOnIdle {
                            assertEquals(beforeManualReading + actual21, opened)
                            assertEquals(beforeManualSources, sources)
                            assertTrue(manualBookChoices.isEmpty())
                        }
                        compose.onNodeWithTag("schedule_agenda").assertDoesNotExist()
                        observations += mapOf("label" to "$mode-$prefix-agenda21-callback", "event" to actual21,
                            "selectedDateUnchanged" to "2026-11-16")
                    }
                    reveal(dateTag, container, true).assertTextEquals(tomorrowText)
                    reveal(tomorrowTag, container, true).assertIsSelected()
                }
                assertEquals(rawBefore, cache().read(parish.id))
                assertEquals(allBefore, preferences.all)
                assertArrayEquals(physicalBefore, preferenceFile.readBytes())
                assertEquals(0, offlineCalls.get())
                receipt["passed"] = true
            }
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name
            receipt["failureMessage"] = failure.message
            throw failure
        } finally {
            if (contentSet) { compose.runOnIdle { mounted = false }; compose.waitForIdle() }
            val cleanupErrors = mutableListOf<String>()
            preferenceNames.forEach { name -> runCatching { check(app.deleteSharedPreferences(name)) }
                .onFailure { cleanupErrors += "$name: ${it.javaClass.simpleName}" } }
            runCatching { check(owned.deleteRecursively()) }.onFailure { cleanupErrors += "ownedFiles: ${it.javaClass.simpleName}" }
            receipt["actualRequestClosure"] = requests.toList()
            receipt["actualExtractorClosure"] = extraction.toList()
            receipt["actualNativeOcrSources"] = nativeOcr.toList()
            receipt["offlineHttpCalls"] = offlineCalls.get()
            receipt["observations"] = observations
            receipt["screenshots"] = screenshots
            receipt["cleanupErrors"] = cleanupErrors
            if (cleanupErrors.isNotEmpty()) receipt["passed"] = false
            File(output, "receipt.json").writeText(gson.toJson(receipt))
            if (!receipt.containsKey("failureType")) assertTrue("Owned storage cleanup: $cleanupErrors", cleanupErrors.isEmpty())
        }
    }

    private fun checkBlock(label: String, container: String, readingTag: String, noticeTag: String, sourceTag: String,
        description: String, sourceUrl: String, event: ParishService, opened: MutableList<ParishService>, sources: MutableList<String>,
        readingLabel: String = "Открыть службу") {
        val beforeReading = compose.runOnIdle { opened.toList() }
        val beforeSources = compose.runOnIdle { sources.toList() }
        compose.onAllNodesWithTag(readingTag).assertCountEquals(1)
        reveal(readingTag, container).assertIsEnabled().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assertTextEquals(readingLabel)
        labelFits(readingTag, readingLabel)
        capture("$label-reading")
        compose.onNodeWithTag(readingTag).performClick()
        compose.runOnIdle { assertEquals(beforeReading + event, opened); assertEquals(beforeSources, sources) }
        reveal(noticeTag, container)
        capture("$label-notice")
        compose.onNodeWithTag(noticeTag).assertTextEquals(description)
        labelFits(noticeTag, description, taggedText = true)
        reveal(sourceTag, container).assertIsEnabled().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assertTextEquals("Проверить расписание")
        compose.onAllNodesWithTag(sourceTag).assertCountEquals(1)
        labelFits(sourceTag, "Проверить расписание")
        val reading = requireNotNull(NativeRevealProbe(compose, container, readingTag).bounds().targetBounds)
        val source = requireNotNull(NativeRevealProbe(compose, container, sourceTag).bounds().targetBounds)
        val gap = (source.top - reading.bottom).value
        assertTrue("Reading precedes the quiet source with at least12dp between targets: $reading / $source", gap >= 11.5f)
        observations += mapOf("label" to label, "readingBoundsDp" to rect(reading), "sourceBoundsDp" to rect(source),
            "touchTargetGapDp" to gap, "notice" to description, "event" to event, "sourceUrl" to sourceUrl,
            "quietSourceImplementation" to "PartialScheduleNotice TextButton; visually retained in screenshots")
        capture("$label-source")
        compose.onNodeWithTag(sourceTag).performClick()
        compose.runOnIdle { assertEquals(beforeReading + event, opened); assertEquals(beforeSources + sourceUrl, sources) }
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
