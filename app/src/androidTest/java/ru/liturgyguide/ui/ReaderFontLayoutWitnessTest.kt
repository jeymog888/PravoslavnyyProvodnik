package ru.liturgyguide.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Native requested-font-scale witnesses; real system 200% is a separate integration run. */
@RunWith(AndroidJUnit4::class)
class ReaderFontLayoutWitnessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = AppContainer(instrumentation.targetContext)

    @Test fun narrowPortrait320By640AtRequested260KeepsAllStatesReadable() =
        profile(320, 640, 2.6f)

    @Test fun portrait400By800AtRequested260KeepsAllStatesReadable() =
        profile(400, 800, 2.6f)

    @Test fun landscape640By360AtRequested169KeepsAllStatesReadable() =
        profile(640, 360, 1.69f)

    private data class Case(val name: String, val reading: ActiveServicePlayback,
        val primary: String, val primaryDescription: String, val fullStatus: String, val meaning: Boolean = false)

    private fun profile(width: Int, height: Int, scale: Float) = withCiFailureDiagnostics("fontWitness.$width.$height.$scale") {
        val landscape = width > height
        try {
            orient(if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT)
            val text = reading("creed", 1, 12)
            val cases = listOf(
                Case("text", text, "play_lyrics", "Двигать текст", "На паузе · сохраненное чтение"),
                Case("wait", segment("anaphora", PlaybackSegment.Wait::class.java), "play_lyrics", "Продолжить по времени", "Примерный переход остановлен · сохраненное чтение"),
                Case("before-start", segment("preparation", PlaybackSegment.Wait::class.java), "start_liturgy", "Литургия началась", "До начала литургии · сохраненное чтение"),
                Case("missing", segment("epistle", PlaybackSegment.MissingReading::class.java, "2026-11-09"), "continue_missing_reading", "Чтение в храме закончилось", "Текст на 9 ноября 2026 пока не добавлен · пауза · сохраненное чтение"),
                Case("end", segment("dismissal", PlaybackSegment.End::class.java), "finish_reading", "Закончить чтение", "Порядок в приложении завершен · сохраненное чтение"),
                Case("russian-scripture", reading("gospel", 1, 0), "play_lyrics", "Двигать текст", "На паузе · сохраненное чтение · по-русски"),
                // Last: meaning is entered by the real visible control, never an injected flag.
                Case("meaning", text, "return_to_text", "К тексту", "Пояснение · на паузе · сохраненное чтение", meaning = true))
            val h = launch(text, width, height, scale)
            val failures = mutableListOf<Throwable>()
            val capturedImages = mutableSetOf<String>()
            for (case in cases) {
                try {
                    compose.runOnIdle { assertTrue(h.player.accept(case.reading, false)) }
                    if (case.meaning) enterMeaning()
                    compose.waitForIdle()
                    val captureHash = captureWitness("${width}x${height}-requested-$scale", case)
                    assertTrue("Different reader states must not reuse a stale image: ${case.name}", capturedImages.add(captureHash))
                    checkCase(case, h, width, height, scale)
                } catch (failure: Throwable) {
                    Log.e("FeoriaFontWitness", "FAILED ${width}x$height/$scale/${case.name}", failure)
                    failures += AssertionError("${width}x$height/$scale/${case.name}: ${failure.message}", failure)
                }
            }
            if (failures.isNotEmpty()) {
                val aggregate = AssertionError("${failures.size}/${cases.size} font layout cases failed; all cases were attempted")
                failures.forEach(aggregate::addSuppressed)
                throw aggregate
            }
        } finally {
            if (landscape) orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        }
    }

    private fun enterMeaning() {
        if (compose.onAllNodesWithTag("reader_mode_chooser", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            compose.openReaderControls()
            compose.readerControl("controls_meaning").performClick()
        } else compose.onNodeWithTag("player_meaning_tab").performClick()
    }

    private fun checkCase(case: Case, h: Harness, width: Int, height: Int, scale: Float) {
        val root = compose.onNodeWithTag("font_witness_host").assertIsDisplayed()
        // The real insets are consumed by ServiceScreen. Do not subtract them here again.
        root.assertWidthIsEqualTo(width.dp).assertHeightIsEqualTo(height.dp)
        val viewport = compose.onNodeWithTag(if (case.meaning) "service_content" else "lyrics_list").assertIsDisplayed()
        assertTrue("Visible reading viewport below 120dp: ${viewport.getBoundsInRoot()}", viewport.getBoundsInRoot().height >= 120.dp)
        viewport.assertHeightIsAtLeast(120.dp)
        compose.onAllNodesWithTag("reader_surface").assertCountEquals(1)
        val primary = fullTarget(case.primary).assertContentDescriptionEquals(case.primaryDescription)
        primary.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, case.fullStatus))
        val status = compose.onNodeWithTag("reader_status", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, case.fullStatus))
        readable(status, scale)
        readable(compose.onNodeWithTag("reader_primary_label", useUnmergedTree = true), scale)
        val layouts = listOf("normal", "compact", "sidebar").filter {
            compose.onAllNodesWithTag("reader_layout_$it").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Exactly one reader layout is active", 1, layouts.size)
        if (width < height) assertNotEquals("A portrait reader must not turn into narrow columns", "sidebar", layouts.single())
        if (layouts.single() == "sidebar") {
            assertEquals("A narrow pill must not wrap and clip its label", 1,
                layout(compose.onNodeWithTag("reader_primary_label", useUnmergedTree = true)).lineCount)
            assertEquals("Sidebar mode must remain a whole label", 1,
                layout(compose.onNodeWithTag("reader_selected_mode", useUnmergedTree = true)).lineCount)
        }
        val title = compose.onNodeWithTag("current_stage_title", useUnmergedTree = true)
            .assertTextEquals(if (layouts.single() == "normal") case.reading.stage.name else case.reading.stage.shortTitle)
        readable(title, scale)
        fullTarget("open_map")
        fullTarget("player_menu")
        fullTarget("reader_controls")
        fullTarget("clarify_moment")
        assertFixedTargetsDoNotOverlap(case.primary)
        fixedDescendantText("reader_controls", scale)
        fixedDescendantText("clarify_moment", scale, singleLine = layouts.single() == "sidebar")
        if (compose.onAllNodesWithTag("reader_mode_chooser", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            val mode = if (case.meaning) "Смысл" else if (case.reading.tracks.any { track -> track.lines.any { it.original != null } }) "Оригинал" else "По-русски"
            val soundless = case.name in setOf("text", "wait", "before-start", "russian-scripture")
            compose.onNodeWithTag("reader_controls")
                .assertContentDescriptionEquals("Режим чтения и управление")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    mode + if (soundless) " · Без звука" else ""))
            compose.onNodeWithTag("reader_selected_mode", useUnmergedTree = true)
                .assertTextEquals(mode)
            readable(compose.onNodeWithTag("reader_selected_mode", useUnmergedTree = true), scale)
        } else {
            val selected = if (case.meaning) "player_meaning_tab" else if (case.reading.tracks.any { track -> track.lines.any { it.original != null } }) "player_original_tab" else "player_text_tab"
            fullTarget(selected).assertIsSelected()
            listOf("player_original_tab", "player_text_tab", "player_meaning_tab").forEach {
                fullTarget(it)
                fixedDescendantText(it, scale)
            }
        }
        if (case.name == "text") {
            val line = compose.onFeedLine(requireNotNull(case.reading.track), 12).assertIsDisplayed().assertIsSelected()
            val layout = completeLayout(line)
            assertEquals(28.sp, layout.layoutInput.style.fontSize)
            assertEquals(39.2.sp, layout.layoutInput.style.lineHeight)
            assertEquals(scale, layout.layoutInput.density.fontScale, .0001f)
            val native = Density(layout.layoutInput.density.density, scale)
            assertEquals(with(native) { 28.sp.toDp().value }, with(layout.layoutInput.density) { 28.sp.toDp().value }, .001f)
        }
        // Secondary provenance remains reachable after leaving the compact fixed chrome.
        compose.openReaderControls()
        compose.onNodeWithTag("reader_controls_list").performScrollToNode(hasTestTag("controls_reading_status"))
        compose.onNodeWithTag("controls_reading_status").assertIsDisplayed().assertTextEquals(case.fullStatus)
        if (case.name in setOf("text", "wait", "before-start", "russian-scripture")) {
            compose.onNodeWithTag("reader_controls_list").performScrollToNode(hasTestTag("controls_soundless_note"))
            compose.onNodeWithTag("controls_soundless_note").assertIsDisplayed().assertTextEquals("Движение текста без звука")
        }
        compose.dismissReaderControls()
        if (case.name == "russian-scripture") {
            val track = requireNotNull(case.reading.track)
            assertTrue(track.isScripture)
            assertTrue(track.lines.all { it.original == null })
            val language = "feed_language_${track.id}"
            compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag(language))
            compose.onNodeWithTag(language).assertIsDisplayed()
                .assertTextContains("Церковнославянский текст пока не добавлен", substring = true)
            compose.onNodeWithTag("player_menu").performClick()
            compose.onNodeWithTag("open_reading_details").performClick()
            compose.onNodeWithTag("reading_details_language").performScrollTo().assertIsDisplayed()
                .assertTextContains("Церковнославянский текст пока не добавлен", substring = true)
            compose.onNodeWithTag("close_reading_details").performClick()
        }
        compose.runOnIdle {
            assertEquals(case.reading.context, h.current.context)
            assertEquals(case.reading.state.segmentIndex, h.current.state.segmentIndex)
            assertEquals(case.reading.state.elapsedQuarterMillis, h.current.state.elapsedQuarterMillis)
            assertEquals(case.reading.state.speed, h.current.state.speed)
            assertFalse(h.current.state.isPlaying)
            assertTrue(h.original)
        }
    }

    private fun fullTarget(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val full = node.getUnclippedBoundsInRoot()
        val visible = node.getBoundsInRoot()
        val tolerance = .5.dp
        assertTrue("Clipped target $tag: full=$full visible=$visible", visible.left <= full.left + tolerance &&
            visible.top <= full.top + tolerance && visible.right >= full.right - tolerance && visible.bottom >= full.bottom - tolerance)
        return node
    }

    private fun assertFixedTargetsDoNotOverlap(primaryTag: String) {
        val tags = listOf(primaryTag, "clarify_moment", "reader_controls", "open_map", "player_menu") +
            listOf("return_to_phrase", "before_start_pause").filter {
                compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()
            }
        val targets = tags.map { it to fullTarget(it).fetchSemanticsNode().boundsInRoot }
        for (first in targets.indices) for (second in first + 1 until targets.size) {
            val (firstTag, a) = targets[first]
            val (secondTag, b) = targets[second]
            val overlapWidth = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val overlapHeight = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            assertTrue("Fixed targets overlap: $firstTag=$a, $secondTag=$b; overlap=${overlapWidth}x$overlapHeight px",
                overlapWidth <= 0f || overlapHeight <= 0f)
        }
    }

    private fun fixedDescendantText(tag: String, scale: Float, singleLine: Boolean = false) {
        val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        assertTrue("No visible text inside $tag", nodes.fetchSemanticsNodes().isNotEmpty())
        nodes.fetchSemanticsNodes().indices.forEach {
            readable(nodes[it], scale)
            if (singleLine) assertEquals("Sidebar action must remain a whole label", 1, layout(nodes[it]).lineCount)
        }
    }

    private fun readable(node: SemanticsNodeInteraction, scale: Float) {
        val layout = completeLayout(node)
        assertNativeTextMetrics(layout, Density(layout.layoutInput.density.density, scale))
        assertEquals("Font conversion was capped: ${layout.layoutInput.text}", scale, layout.layoutInput.density.fontScale, .0001f)
        val full = node.getUnclippedBoundsInRoot()
        val visible = node.getBoundsInRoot()
        val tolerance = (1f / layout.layoutInput.density.density).dp
        assertTrue("Fixed text clipped: ${layout.layoutInput.text}; full=$full visible=$visible",
            visible.left <= full.left + tolerance && visible.top <= full.top + tolerance &&
                visible.right >= full.right - tolerance && visible.bottom >= full.bottom - tolerance)
    }

    private fun completeLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        node.assertIsDisplayed()
        val layout = layout(node)
        // Match ReaderAccessibilityTest: Compose 1.7.3 String semantics rebuild a wider
        // MultiParagraph. Its unused width is diagnostic; actual line extents decide clipping.
        assertFalse("Line count clipped: ${layout.layoutInput.text}", layout.multiParagraph.didExceedMaxLines)
        for (line in 0 until layout.lineCount) {
            assertFalse("Ellipsized line: ${layout.layoutInput.text}", layout.isLineEllipsized(line))
            assertTrue("Horizontal clipping: ${layout.layoutInput.text}", layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("Vertical clipping: ${layout.layoutInput.text}", layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
            if (line > 0) assertTrue("Overlapping lines: ${layout.layoutInput.text}", layout.getLineTop(line) >= layout.getLineBottom(line - 1) - 1f)
        }
        return layout
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(result)) }
        return result.single()
    }

    private fun captureWitness(profile: String, case: Case): String {
        val args = InstrumentationRegistry.getArguments()
        val mirrorEnabled = args.getString("feoriaCiDiagnostics") == "true"
        val run = if (mirrorEnabled) requireNotNull(args.getString("feoriaCiDiagnosticsRun")) {
            "feoriaCiDiagnostics=true requires an explicit feoriaCiDiagnosticsRun"
        } else args.getString("feoriaCiDiagnosticsRun") ?: "local-${System.currentTimeMillis()}"
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir("ci-diagnostics")),
            "$run/reader-font-witness/$profile/${case.name}")
        check(directory.mkdirs() || directory.isDirectory)
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        // captureToImage waits for a committed redraw before PixelCopy. UiAutomation's
        // global screenshot could still return the previous case after Compose was idle.
        val screen = compose.onNodeWithTag("font_witness_host").captureToImage().asAndroidBitmap()
        try { File(directory, "screen.png").outputStream().use { check(screen.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screen.recycle() }
        val captureHash = MessageDigest.getInstance("SHA-256").digest(File(directory, "screen.png").readBytes())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val observations = JSONArray()
        val tags = listOf("font_witness_host", "reader_layout_normal", "reader_layout_compact", "reader_layout_sidebar",
            "reader_surface", "lyrics_list", "service_content", "reader_status", "reader_primary_label", "current_stage_title",
            case.primary, "reader_controls", "reader_mode_chooser", "reader_selected_mode", "clarify_moment", "open_map", "soundless_note")
        for (tag in tags) {
            val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            val count = nodes.fetchSemanticsNodes().size
            val record = JSONObject().put("tag", tag).put("count", count)
            if (count == 1) {
                val node = nodes[0]
                record.put("visibleDp", node.getBoundsInRoot().toString()).put("fullDp", node.getUnclippedBoundsInRoot().toString())
                if (node.fetchSemanticsNode().config.contains(SemanticsActions.GetTextLayoutResult)) {
                    addTextPrecision(record, layout(node))
                }
            }
            observations.put(record)
        }
        for (tag in listOf("reader_controls", "clarify_moment", "player_original_tab", "player_text_tab", "player_meaning_tab")) {
            val texts = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            texts.fetchSemanticsNodes().indices.forEach { index ->
                val node = texts[index]
                val record = JSONObject().put("parent", tag).put("child", index)
                    .put("visibleDp", node.getBoundsInRoot().toString()).put("fullDp", node.getUnclippedBoundsInRoot().toString())
                observations.put(addTextPrecision(record, layout(node)))
            }
        }
        val record = JSONObject().put("profile", profile).put("state", case.name).put("conversion", "native requested font scale, not proportional glyph scale")
            .put("captureSource", "Compose captureToImage of font_witness_host: committed redraw then window PixelCopy")
            .put("pngSha256", captureHash)
            .put("observations", observations).put("verdict", "OBSERVATION_BEFORE_ASSERTIONS")
        File(directory, "bounds.json").writeText(record.toString(2))
        Log.i("FeoriaFontWitness", record.toString())
        if (mirrorEnabled) mirrorWitness(directory, run, profile, case.name)
        return captureHash
    }

    private fun addTextPrecision(record: JSONObject, text: TextLayoutResult): JSONObject {
        val constraints = text.layoutInput.constraints
        val lines = JSONArray()
        for (line in 0 until text.lineCount) lines.put(JSONObject().put("line", line)
            .put("leftPx", text.getLineLeft(line)).put("rightPx", text.getLineRight(line))
            .put("topPx", text.getLineTop(line)).put("bottomPx", text.getLineBottom(line))
            .put("baselinePx", when (line) {
                0 -> text.firstBaseline
                text.lineCount - 1 -> text.lastBaseline
                else -> JSONObject.NULL // Compose 1.7.3 exposes only first/last baselines.
            }).put("ellipsized", text.isLineEllipsized(line)))
        return record.put("text", text.layoutInput.text.text).put("lines", text.lineCount)
            .put("visualOverflow", text.hasVisualOverflow).put("didExceedMaxLines", text.multiParagraph.didExceedMaxLines)
            .put("didOverflowWidth", text.didOverflowWidth).put("didOverflowHeight", text.didOverflowHeight)
            .put("paragraphWidthPx", text.multiParagraph.width).put("paragraphHeightPx", text.multiParagraph.height)
            .put("firstBaselinePx", text.firstBaseline).put("lastBaselinePx", text.lastBaseline)
            .put("baselineLimit", "Compose 1.7.3 public API exposes only first/last baselines; other line baselines are null")
            .put("layoutSizePx", text.size.toString()).put("density", text.layoutInput.density.density)
            .put("fontScaleMetadata", text.layoutInput.density.fontScale)
            .put("fontSize", text.layoutInput.style.fontSize.toString()).put("lineHeight", text.layoutInput.style.lineHeight.toString())
            .put("constraintsPx", JSONObject().put("minWidth", constraints.minWidth).put("maxWidth", constraints.maxWidth)
                .put("minHeight", constraints.minHeight).put("maxHeight", constraints.maxHeight))
            .put("lineGeometry", lines)
    }

    /** Same shell-owned destination and marker convention as CiFailureDiagnostics, before UTP cleanup. */
    private fun mirrorWitness(directory: File, run: String, profile: String, state: String) {
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(profile.matches(Regex("[0-9]+x[0-9]+-requested-[0-9]+\\.[0-9]+")))
        require(state in setOf("text", "wait", "before-start", "missing", "end", "meaning", "russian-scripture"))
        require(instrumentation.targetContext.packageName == "ru.liturgyguide")
        val source = directory.absolutePath
        require(source.matches(Regex("/storage/emulated/[0-9]+/Android/data/ru\\.liturgyguide/files/ci-diagnostics/" +
            Regex.escape("$run/reader-font-witness/$profile/$state"))))
        val runDirectory = "/data/local/tmp/feoria-ci-diagnostics/$run"
        val parent = "$runDirectory/reader-font-witness/$profile"
        val destination = "$parent/$state"
        val script = File(directory, "mirror.sh")
        script.writeText("""
            #!/system/bin/sh
            (
                test -d "$runDirectory" && test -s "$source/screen.png" && test -s "$source/bounds.json" &&
                mkdir -p "$parent" && test ! -e "$destination" &&
                cp -R "$source" "$destination" &&
                cmp "$source/screen.png" "$destination/screen.png" &&
                cmp "$source/bounds.json" "$destination/bounds.json" &&
                printf 'COMPLETE\n' > "$destination/.complete" && test -s "$destination/.complete"
            )
            result=${'$'}?
            printf 'MIRROR_EXIT=%s\n' "${'$'}result"
            exit "${'$'}result"
        """.trimIndent() + "\n")
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val cancelled = AtomicBoolean(false)
        val task = FutureTask<String> {
            val pipe = instrumentation.uiAutomation.executeShellCommand("timeout 2 sh ${script.absolutePath}")
            descriptor.set(pipe)
            if (cancelled.get()) { pipe.close(); error("Witness mirror cancelled") }
            ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        Thread(task, "feoria-font-witness-mirror").apply { isDaemon = true; start() }
        try {
            val output = task.get(3, TimeUnit.SECONDS)
            Log.i("FeoriaFontWitness", "Mirror $destination: ${output.trim()}")
            check(output.lineSequence().any { it == "MIRROR_EXIT=0" }) { "Witness mirror failed: $destination; $output" }
        } finally {
            cancelled.set(true)
            task.cancel(true)
            runCatching { descriptor.get()?.close() }
                .onFailure { Log.w("FeoriaFontWitness", "Could not close mirror descriptor", it) }
        }
    }

    private class Harness(initial: ActiveServicePlayback) {
        var original by mutableStateOf(true)
        val player = ServicePlayerState(initial, { 1_000L }, ServicePlaybackWriter { _, _, _ -> true })
        val current get() = requireNotNull(player.reading)
    }

    private fun launch(initial: ActiveServicePlayback, width: Int, height: Int, scale: Float): Harness {
        val h = Harness(initial)
        compose.setContent {
            val density = LocalDensity.current
            val config = Configuration(LocalConfiguration.current).apply { screenWidthDp = width; screenHeightDp = height }
            CompositionLocalProvider(LocalConfiguration provides config,
                LocalDensity provides Density(density.density, scale)) {
                GuideTheme(false) { Box(Modifier.size(width.dp, height.dp).testTag("font_witness_host")) {
                    ServiceScreen(h.player, onStageChange = { h.player.seekStage(it) }, onBack = {}, onSelectStage = {},
                        onFindStage = { h.player.pause() }, onSettings = {}, dark = false, onDarkChange = {},
                        onOpenSource = {}, onSoundSettings = {}, onFinish = {}, preferredOriginal = h.original,
                        onOriginalChange = { h.original = it }, restoredNotice = true)
                } }
            }
        }
        return h
    }

    private fun plan(date: String) = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), LocalDate.parse(date),
        container.readings?.days?.singleOrNull { it.date == date })
    private fun reading(stage: String, track: Int, line: Int): ActiveServicePlayback {
        val plan = plan("2026-11-08")
        val text = plan.tracksByStage.getValue(stage)[track]
        val engine = ServicePlaybackEngine(plan)
        return ActiveServicePlayback(plan, ReadingContext("selected-church", "2026-11-08", "Europe/Moscow", ReadingContextSource.MANUAL),
            engine.seekLine(engine.start(stage), text.id, text.lines[line].id).copy(elapsedQuarterMillis = 1_234))
    }
    private fun segment(stage: String, type: Class<out PlaybackSegment>, date: String = "2026-11-08"): ActiveServicePlayback {
        val plan = plan(date)
        val index = plan.segments.indexOfFirst { it.stageId == stage && type.isInstance(it) }
        check(index >= 0)
        return ActiveServicePlayback(plan, ReadingContext("selected-church", date, "Europe/Moscow", ReadingContextSource.MANUAL),
            ServicePlaybackEngine(plan).start(stage).copy(segmentIndex = index,
                elapsedQuarterMillis = if (plan.segments[index] is PlaybackSegment.Wait) 1_234 else 0))
    }
    private fun orient(requested: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity { activity ->
                val decor = activity.window.decorView
                ready = activity.resources.configuration.orientation == expected && decor.hasWindowFocus() &&
                    decor.width > 0 && decor.height > 0 && (decor.width > decor.height) == (expected == Configuration.ORIENTATION_LANDSCAPE)
            }
            ready
        }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
    }
}
