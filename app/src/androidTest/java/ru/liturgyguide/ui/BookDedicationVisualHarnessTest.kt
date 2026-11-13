package ru.liturgyguide.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Component render evidence only: native font/IME, genuine catalog/calendar, no book opening or parish acceptance. */
@RunWith(AndroidJUnit4::class)
class BookDedicationVisualHarnessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "dedication-visual-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val choices = PatronalChoiceStore(preferences)
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val opened = AtomicInteger()
    private val calendarCalls = CopyOnWriteArrayList<String>()
    private val captures = JSONArray()
    private val layouts = JSONArray()
    private val targets = JSONArray()
    private lateinit var output: File
    private var font = 1f
    private var plainTextColor = Color.Unspecified
    private var surfaceColor = Color.Unspecified

    @After fun clean() {
        if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        context.deleteSharedPreferences(name)
    }

    @Test fun theNativeChooserShowsBothNicholasDatesTheFullMayEventAndReachableOpenAction() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with dedicationVisualProfile=light|dark", args.containsKey("dedicationVisualProfile"))
        val dark = when (args.getString("dedicationVisualProfile")) { "light" -> false; "dark" -> true; else -> error("dedicationVisualProfile must be light or dark") }
        font = if (dark) 2f else 1f
        val width = if (dark) 320 else 360
        val height = if (dark) 640 else 780
        val run = requireNotNull(args.getString("dedicationVisualRun")) { "Use a unique dedicationVisualRun" }
        require(run.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        output = File(requireNotNull(context.getExternalFilesDir(null)), "ded-visual/$run")
        check(!output.exists() && output.mkdirs()) { "Output must be a fresh run directory: $output" }
        val receipt = JSONObject().put("run", run).put("profile", if (dark) "dark" else "light")
            .put("scope", "Actual production chooser in a test-owned Activity; native font set externally, no LocalDensity override; component evidence, not a cold Main journey")
            .put("requestedWidthDp", width).put("requestedHeightDp", height).put("expectedFontScale", font)
            .put("captures", captures).put("textLayouts", layouts).put("targets", targets)
            .put("parentSurface", "Material Surface with surfaceContainerLow and onSurface contentColor, matching production sheet inheritance")
            .put("catalogResultTitlePolicy", "Catalog names wrap fully at fontScale >=1.5 and are measured separately; smaller unselected names allow two-line ellipsis; dates and selected event are never truncated")
        try {
            var nativeFont = 0f
            instrumentation.runOnMainSync { nativeFont = compose.activity.resources.configuration.fontScale }
            receipt.put("nativeActivityFontScale", nativeFont)
            assertEquals("Set the actual emulator font before this invocation", font, nativeFont, .001f)
            val catalog = requireNotNull(PatronalCatalog.decode(context.assets.open(PatronalCatalog.ASSET_NAME).bufferedReader().use { it.readText() }))
            assertEquals(1100, catalog.entries.size)
            receipt.put("catalogEntries", catalog.entries.size)
            val responses = mapOf("2026-05-22" to fixture("calendar-nicholas-may-2026-05-22.json"),
                "2026-12-19" to fixture("calendar-nicholas-dec-2026-12-19.json"))
            val services = BookDedicationServices(choices, PatronalCalendarResolver(choices, ScheduleTransport { url ->
                calendarCalls += url
                val date = url.removePrefix("${PatronalCalendarResolver.BASE_URL}/").removeSuffix("/")
                ScheduleResponse(url, requireNotNull(responses[date]), "text/html; charset=UTF-8")
            })) { catalog }
            compose.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply { screenWidthDp = width; screenHeightDp = height }
                CompositionLocalProvider(LocalConfiguration provides configuration) { GuideTheme(dark) {
                    val colors = MaterialTheme.colorScheme
                    SideEffect { plainTextColor = colors.onSurface; surfaceColor = colors.surfaceContainerLow }
                    Surface(Modifier.fillMaxSize(), color = colors.surfaceContainerLow, contentColor = colors.onSurface) {
                        Box(Modifier.fillMaxSize()) {
                            if (mounted.value) Box(Modifier.size(width.dp, height.dp)) {
                                BookDedicationChooser(services, "visual-test-parish", "Выбранный храм", 2026, PatronalProfileLookup.Absent,
                                    onOpen = { _, _ -> opened.incrementAndGet(); BookDedicationStart.STORAGE_FAILED },
                                    onDismiss = { mounted.value = false }, onOpenUrl = {})
                            }
                        }
                    }
                } }
            }
            contentSet = true
            await("book_dedication_results_status")
            val actualWidth = compose.onNodeWithTag("book_dedication_chooser").getBoundsInRoot().width.value
            assertEquals(width.toFloat(), actualWidth, 1f)
            receipt.put("actualChooserWidthDp", actualWidth)
            val query = reveal("book_dedication_query")
            query.performClick()
            query.performTextReplacement("Николай")
            keyboard(true)
            target("book_dedication_query")
            target("book_dedication_clear")
            capture("01-native-keyboard-input", nativeKeyboard = true)
            compose.onNodeWithTag("book_dedication_clear").performClick()
            query.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            query.performTextReplacement("Николай Чудотворец")
            query.performImeAction()
            keyboard(false)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("book_dedication_results_status").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Праздники в календаре на 2026 год" }
            } }
            reveal("book_dedication_result_9906").assertTextContains("19 декабря 2026", substring = true)
            for ((id, date) in listOf("11183" to "22 мая 2026", "9906" to "19 декабря 2026")) {
                target("book_dedication_result_$id")
                val dateNode = compose.onNode(hasText(date) and hasAnyAncestor(hasTestTag("book_dedication_result_$id")), useUnmergedTree = true)
                checkLayout(dateNode, "date-$id")
                if (font >= 1.5f) {
                    val fullTitle = catalog.entries.single { it.mineaId == id }.displayTitle
                    val titleNode = compose.onNode(hasText(fullTitle) and hasAnyAncestor(hasTestTag("book_dedication_result_$id")), useUnmergedTree = true)
                    checkLayout(titleNode.assertTextEquals(fullTitle), "catalog-title-$id")
                    capture(if (id == "11183") "02-nicholas-may" else "02b-nicholas-december")
                }
            }
            if (font < 1.5f) capture("02-nicholas-two-dates")
            reveal("book_dedication_result_11183").performClick()
            await("book_dedication_confirmed_title")
            val proof = requireNotNull(choices.find("111300"))
            assertTrue(proof.isValid())
            assertEquals("11183", proof.mineaId)
            reveal("book_dedication_confirmed_title").assertTextEquals(proof.title).assertTextContains("Перенесение мощей", substring = true)
            checkLayout(compose.onNodeWithTag("book_dedication_confirmed_title"), "published-event-title")
            receipt.put("selectedEventId", proof.id).put("selectedFullTitle", proof.title).put("calendarDate", proof.calendarDate)
            capture("03-may-full-published-title")
            target("book_dedication_open")
            compose.onNodeWithTag("book_dedication_open").assertTextEquals("Открыть текст").assertIsEnabled()
            val hint = compose.onNodeWithText("Разные праздники одного святого могут иметь разные тексты")
            checkLayout(hint.assertIsDisplayed(), "different-feasts-hint")
            val label = compose.onNode(hasText("Открыть текст") and hasAnyAncestor(hasTestTag("book_dedication_open")), useUnmergedTree = true)
            checkLayout(label, "open-label")
            val gap = compose.onNodeWithTag("book_dedication_open").getUnclippedBoundsInRoot().top - hint.getUnclippedBoundsInRoot().bottom
            assertTrue("Open action needs 12 dp from its hint, allowing 0.6 dp rounding", gap.value + .6f >= 12f)
            receipt.put("openHintGapDp", gap.value)
            capture("04-reachable-open-action-with-hint")
            assertEquals(0, opened.get())
            assertEquals(PatronalProfileLookup.Absent, choices.readProfile("visual-test-parish"))
            assertEquals(listOf("${PatronalCalendarResolver.BASE_URL}/2026-05-22/"), calendarCalls)
            receipt.put("calendarCalls", JSONArray(calendarCalls)).put("profileWritten", false).put("onOpenCalls", 0).put("status", "passed")
        } catch (failure: Throwable) { receipt.put("status", "failed").put("failure", failure.toString()); throw failure }
        finally { File(output, "receipt.json").writeText(receipt.toString(2)); android.util.Log.i("DedicationVisualQA", "output=${output.absolutePath}") }
    }

    private fun target(tag: String) {
        val node = reveal(tag).assertHeightIsAtLeast(48.dp)
        targets.put(JSONObject().put("tag", tag).put("boundsDp", node.getUnclippedBoundsInRoot().toString()))
    }
    private fun reveal(tag: String): SemanticsNodeInteraction {
        if (!fullyVisible(tag)) { compose.onNodeWithTag("book_dedication_chooser").performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).performScrollTo() }
        assertTrue("The whole $tag must be visible after scrolling", fullyVisible(tag))
        return compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun fullyVisible(tag: String) = runCatching {
        val box = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("book_dedication_chooser").getBoundsInRoot()
        box.left >= viewport.left && box.right <= viewport.right && box.top >= viewport.top && box.bottom <= viewport.bottom
    }.getOrDefault(false)
    private fun checkLayout(node: SemanticsNodeInteraction, label: String) {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertTrue(results.isNotEmpty())
        val full = node.getUnclippedBoundsInRoot(); val visible = node.getBoundsInRoot()
        assertTrue("The whole $label must be visible", full.top.value + .6f >= visible.top.value && full.bottom.value <= visible.bottom.value + .6f && full.left.value + .6f >= visible.left.value && full.right.value <= visible.right.value + .6f)
        for (layout in results) {
            assertEquals(font, layout.layoutInput.density.fontScale, .001f)
            assertTextFitsLayout(layout, layout.layoutInput.text.text)
            val detail = JSONObject().put("label", label).put("text", layout.layoutInput.text.text).put("fontScale", layout.layoutInput.density.fontScale).put("lineCount", layout.lineCount).put("sizePx", layout.size.toString())
            if (label == "published-event-title" || label == "different-feasts-hint") {
                val resolved = layout.layoutInput.style.color
                assertEquals("Plain $label must inherit the actual Surface onSurface color", plainTextColor, resolved)
                val contrast = (maxOf(resolved.luminance(), surfaceColor.luminance()) + .05f) /
                    (minOf(resolved.luminance(), surfaceColor.luminance()) + .05f)
                assertTrue("Plain $label needs at least 4.5 contrast against its Surface: $contrast", contrast >= 4.5f)
                detail.put("resolvedColorArgb", "%08x".format(resolved.toArgb())).put("surfaceColorArgb", "%08x".format(surfaceColor.toArgb())).put("contrastRatio", contrast)
            }
            layouts.put(detail)
        }
    }
    private fun keyboard(visible: Boolean) {
        compose.waitUntil(10_000) { var matches = false; compose.activityRule.scenario.onActivity {
            val insets = ViewCompat.getRootWindowInsets(it.window.decorView)
            matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == visible && (visible || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0)
        }; matches }
        settle()
    }
    private fun capture(label: String, nativeKeyboard: Boolean = false) {
        settle()
        val bitmap = if (nativeKeyboard) requireNotNull(instrumentation.uiAutomation.takeScreenshot()) else compose.onNodeWithTag("book_dedication_chooser").captureToImage().asAndroidBitmap()
        File(output, "$label.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        captures.put(JSONObject().put("file", "$label.png").put("source", if (nativeKeyboard) "native display including IME" else "actual Compose chooser render").put("pixelWidth", bitmap.width).put("pixelHeight", bitmap.height))
        if (nativeKeyboard) bitmap.recycle()
    }
    private fun fixture(file: String): ByteArray {
        val bytes = instrumentation.context.assets.open("dedication/$file").use { it.readBytes() }
        val provenance = instrumentation.context.assets.open("dedication/provenance.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val record = provenance.getAsJsonArray("fixtures").single { it.asJsonObject["fixture"].asString == file }.asJsonObject
        assertEquals(record["fixtureSha256"].asString, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return bytes
    }
    private fun await(tag: String) { compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }
}
