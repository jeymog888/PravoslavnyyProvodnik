package ru.liturgyguide.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.schedule.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Component viewport/control checks only. No real HTTP, repository confirmation or Main success claim. */
@RunWith(Parameterized::class)
class SchedulePendingSourceViewportFlowTest(private val width: Int, private val height: Int,
    private val scale: Float, private val dark: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
    private val saved = SavedChurchRecord("osm:way:155168069", "Архиерейское подворье. Храм в честь Вознесения Господня",
        "", "", "Europe/Moscow", 54.5305961, 36.3084198, origin = SavedChurchOrigin.OPEN_STREET_MAP,
        sourceUrl = "https://www.openstreetmap.org/way/155168069")
    private val parish = saved.asParish()
    private val captured = requireNotNull(ScheduleCacheCodec.decode(CAPTURED_STATE, parish))
    private val group = scheduleCandidateGroups(parish, captured).single()
    private val candidate = requireNotNull(group.confirmationCandidate)
    private val now = Instant.parse("2026-11-14T21:33:00Z")
    private val reviewCalls = AtomicInteger()
    private val refreshed = AtomicInteger()
    private val searched = AtomicInteger()
    private val opened = mutableListOf<String>()
    private val confirmed = mutableListOf<String>()
    private val record = linkedMapOf<String, Any?>()
    private var actualDensity: Float? = null
    private val sourceViewKey = mutableStateOf(Any())
    private val renderedSchedule = mutableStateOf(captured)
    private val reviewCallbackEnabled = mutableStateOf(true)
    private val observedReviewRequest = AtomicInteger()
    private val runId = "pending-${width}-${height}-${scale}-${dark}-${UUID.randomUUID()}"
    private val output by lazy { File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext
        .getExternalFilesDir("pending-source-viewport")), runId).apply { mkdirs() } }

    @Test fun productionReviewShowsFullCapturedIdentityAndFirstOpenBeforeAnyTestScroll() = receipt("captured-pending") {
        mount(captured); productionReview()
        val context = compose.onNodeWithTag("schedule_pending_context")
        val name = textInGroup(candidate.name)
        val address = textInGroup(candidate.address)
        val open = compose.onNodeWithTag("open_candidate_${candidate.id}")
        val viewport = compose.onNodeWithTag("church_content").getBoundsInRoot()
        record["beforeTestScroll"] = true
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
        record["stripCollapsed"] = true
        record["geometryBeforeTestScroll"] = mapOf("window" to geometry(compose.onNodeWithTag("pending_viewport_window")),
            "viewport" to geometry(compose.onNodeWithTag("church_content")),
            "context" to geometry(context), "name" to geometry(name), "address" to geometry(address), "open" to geometry(open))
        record["semanticsAfterProductionReview"] = compose.onRoot().printToString()
        capture("after-production-review-before-test-scroll")
        recordActionLayout("open_candidate_${candidate.id}", "Открыть сайт")
        recordActionLayout("confirm_candidate_${candidate.id}", "Выбрать сайт")
        context.assertTextEquals("Сайт еще не выбран. Сверьте храм и адрес")
        whole("pending context", context, viewport); whole("full name", name, viewport)
        whole("full address", address, viewport); whole("first Open", open, viewport)
        assertFullText("name", name, candidate.name); assertFullText("address", address, candidate.address)
        assertTrue(name.getUnclippedBoundsInRoot().bottom <= address.getUnclippedBoundsInRoot().top)
        assertTrue(address.getUnclippedBoundsInRoot().bottom <= open.getUnclippedBoundsInRoot().top)
        open.assertIsEnabled().assertHeightIsAtLeast(52.dp).performTouchInput { click(center) }; settle()
        assertEquals(listOf(group.sourceUrl), opened); assertTrue(confirmed.isEmpty())
        assertNull(captured.publication); assertNull(captured.siteBinding)
        // Only now may the test scroll. This does not contribute to the first-Open fit claim.
        record["testScrollStartsAfterInitialAssertions"] = true
        val confirm = reveal("confirm_candidate_${candidate.id}").assertIsEnabled().assertHeightIsAtLeast(56.dp)
        val openBounds = compose.onNodeWithTag("open_candidate_${candidate.id}").getUnclippedBoundsInRoot()
        assertTrue("Open and Confirm need12dp", confirm.getUnclippedBoundsInRoot().top - openBounds.bottom >= 12.dp)
        confirm.performTouchInput { click(center) }; settle(); assertEquals(listOf(candidate.id), confirmed)
        val other = reveal("find_other_schedule_sources").assertIsEnabled().assertHeightIsAtLeast(52.dp)
        val refresh = compose.onNodeWithTag("refresh_schedule")
        assertTrue("Separate source actions need12dp", other.getUnclippedBoundsInRoot().top - refresh.getUnclippedBoundsInRoot().bottom >= 12.dp)
        other.performClick(); settle(); assertEquals(1, searched.get()); assertEquals(0, refreshed.get())
        reveal("refresh_schedule").performClick(); settle(); assertEquals(1, refreshed.get()); assertEquals(1, searched.get())
        assertEquals(listOf(candidate.id), confirmed); capture("after-actions")
    }

    @Test fun aDismissedCoveringConflictKeepsTheFullWarningBranchWithoutSourceLevelFlags() = receipt("covering-conflict") {
        val conflict = candidate.copy(providerObjectId = "20386", name = "Часовня Вознесения Господня г. Калуга",
            evidenceUrl = "https://map.patriarchia.ru/?infoid=20386", evidenceSha256 = "b".repeat(64),
            outcome = ScheduleIdentityOutcome.CONFLICT, reason = "Карточка относится к часовне, а выбран храм")
        val blocked = captured.copy(sourceCandidates = listOf(candidate, conflict), dismissedCandidateIds = listOf(conflict.id))
        assertEquals(blocked, ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(blocked), parish))
        assertEquals(ScheduleSourceChangeBlock.NONE, blocked.sourceChangeBlock)
        assertFalse(blocked.publicationRejected); assertFalse(blocked.sourceHasWithdrawals); assertFalse(blocked.sourceNeedsVerification)
        assertTrue(candidate.canConfirm(parish)); assertTrue(scheduleCandidateGroups(parish, blocked).single().conflicts.contains(conflict))
        mount(blocked); productionReview()
        compose.onNodeWithTag("schedule_source_search_strip").assertIsDisplayed()
        compose.onNodeWithTag("schedule_sources_heading").assertIsDisplayed().assertTextEquals("Найденные сайты")
        compose.onNodeWithTag("schedule_pending_context").assertDoesNotExist()
        compose.onNodeWithTag("confirm_candidate_${candidate.id}").assertDoesNotExist()
        val reason = compose.onNodeWithText(conflict.providerLabel + ": " + conflict.reason, useUnmergedTree = true)
        compose.onNodeWithTag("church_content").performScrollToNode(hasText(conflict.providerLabel + ": " + conflict.reason))
        reason.performScrollTo().assertIsDisplayed()
        record["visibleCriticalReason"] = geometry(reason)
        assertTrue(confirmed.isEmpty()); assertTrue(opened.isEmpty()); capture("conflict-warning")
    }

    @Test fun anotherViewRestoresTheStripWithTheSameChurchAndHandledRequestUntilAnExplicitReview() = receipt("view-reset") {
        val firstView = sourceViewKey.value
        mount(captured); productionReview()
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
        assertEquals(1, observedReviewRequest.get())
        val conflict = candidate.copy(providerObjectId = "20386", name = "Часовня Вознесения Господня г. Калуга",
            evidenceUrl = "https://map.patriarchia.ru/?infoid=20386", evidenceSha256 = "b".repeat(64),
            outcome = ScheduleIdentityOutcome.CONFLICT, reason = "Карточка относится к часовне, а выбран храм")
        compose.runOnIdle { renderedSchedule.value = captured.copy(sourceCandidates = listOf(candidate, conflict),
            dismissedCandidateIds = listOf(conflict.id)) }; settle()
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
        compose.onNodeWithTag("confirm_candidate_${candidate.id}").assertDoesNotExist()
        val reasonText = conflict.providerLabel + ": " + conflict.reason
        compose.onNodeWithTag("church_content").performScrollToNode(hasText(reasonText))
        compose.onNodeWithText(reasonText, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        record["collapseStayedThroughConflict"] = true
        compose.runOnIdle { renderedSchedule.value = captured }; settle()
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
        val secondView = Any()
        compose.runOnIdle { sourceViewKey.value = secondView }; settle(); settle()
        assertNotSame(firstView, secondView)
        assertSame(secondView, sourceViewKey.value)
        assertEquals("Changing a view must not replay the handled request", 1, observedReviewRequest.get())
        assertEquals(1, reviewCalls.get())
        val window = compose.onNodeWithTag("pending_viewport_window").getBoundsInRoot()
        whole("new view source strip", compose.onNodeWithTag("schedule_source_search_strip"), window)
        val review = compose.onNodeWithTag("source_search_review").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        whole("new view review action", review, window)
        record["secondViewStripBeforeReview"] = geometry(compose.onNodeWithTag("schedule_source_search_strip"))
        assertEquals(0, refreshed.get()); assertEquals(0, searched.get())
        assertTrue(opened.isEmpty()); assertTrue(confirmed.isEmpty())
        capture("new-view-same-handled-request")
        review.performTouchInput { click(center) }; settle()
        assertEquals(2, reviewCalls.get()); assertEquals(2, observedReviewRequest.get())
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
        record["newViewVisualReset"] = true
        record["retainedRequestBeforeExplicitReview"] = 1
        record["oldViewIdentity"] = System.identityHashCode(firstView)
        record["newViewIdentity"] = System.identityHashCode(secondView)
        assertEquals(0, refreshed.get()); assertEquals(0, searched.get())
        assertTrue(opened.isEmpty()); assertTrue(confirmed.isEmpty())
        capture("new-view-explicit-review-collapsed")
        // The optional-handler fallback has the same scroll/frame boundary, but
        // must neither invent a handled request nor call a source-search action.
        compose.runOnIdle { reviewCallbackEnabled.value = false; sourceViewKey.value = Any() }; settle()
        whole("fallback view source strip", compose.onNodeWithTag("schedule_source_search_strip"), window)
        val fallback = compose.onNodeWithTag("source_search_review").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        whole("fallback review action", fallback, window)
        fallback.performTouchInput { click(center) }; settle()
        compose.onNodeWithTag("schedule_source_search_strip").assertDoesNotExist()
        assertEquals(2, reviewCalls.get()); assertEquals(2, observedReviewRequest.get())
        assertEquals(0, refreshed.get()); assertEquals(0, searched.get())
        assertTrue(opened.isEmpty()); assertTrue(confirmed.isEmpty())
        record["fallbackCollapsedAfterExplicitReviewWithoutRequestReplay"] = true
        capture("fallback-explicit-review-collapsed")
    }

    private fun mount(state: InternetScheduleState) {
        record["renderedState"] = state
        renderedSchedule.value = state
        compose.setContent {
            var request by remember { mutableIntStateOf(0) }
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, scale)) {
                val presentedRequest = request
                SideEffect { actualDensity = native.density; observedReviewRequest.set(presentedRequest) }
                GuideTheme(dark) { Box(Modifier.size(width.dp, height.dp).testTag("pending_viewport_window")) {
                    ChurchScreen(listOf(parish), parish.id, LocalDate.of(2026, 11, 15),
                        onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onOpenUrl = { opened += it },
                        onBack = {}, showBack = false, searchQuery = "", onSearchQueryChange = {},
                        internetSchedule = renderedSchedule.value, sourceReviewParish = parish, now = now,
                        sourceReviewRequest = presentedRequest, sourceReviewIdentity = ScheduleEntityIdentity.fingerprint(parish),
                        sourceSearchActive = false, sourceSearchViewKey = sourceViewKey.value,
                        onReviewSourceSearch = if (reviewCallbackEnabled.value) ({ reviewCalls.incrementAndGet(); request++; Unit }) else null,
                        onConfirmScheduleCandidate = { confirmed += it }, onRefreshSchedule = { refreshed.incrementAndGet() },
                        onFindOtherScheduleSources = { searched.incrementAndGet() },
                        bottomBar = { GuideBottomBar("church", onHome = {}, onChurch = {}, onSettings = {}) })
                } }
            }
        }
        settle()
    }
    private fun productionReview() {
        val window = compose.onNodeWithTag("pending_viewport_window").getBoundsInRoot()
        val review = compose.onNodeWithTag("source_search_review").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        whole("source strip", compose.onNodeWithTag("schedule_source_search_strip"), window)
        whole("review action", review, window)
        record["stripGeometryBeforeReview"] = geometry(compose.onNodeWithTag("schedule_source_search_strip"))
        record["reviewGeometryBeforeReview"] = geometry(review)
        compose.onNodeWithTag("nav_church").assertIsDisplayed()
        review.performTouchInput { click(center) }; settle(); assertEquals(1, reviewCalls.get())
        val actual = compose.onNodeWithTag("pending_viewport_window").getUnclippedBoundsInRoot()
        record["actualWindowDp"] = rect(actual)
        record["parentConstrainedRequestedWidth"] = (actual.right - actual.left).value < width - .5f
        record["parentConstrainedRequestedHeight"] = (actual.bottom - actual.top).value < height - .5f
    }
    private fun textInGroup(value: String) = compose.onNode(hasText(value) and
        hasAnyAncestor(hasTestTag("schedule_source_group_${candidate.id}")), useUnmergedTree = true)
    private fun reveal(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun whole(label: String, node: SemanticsNodeInteraction, viewport: DpRect) {
        node.assertIsDisplayed(); val full = node.getUnclippedBoundsInRoot()
        assertEquals("$label must be unclipped", full, node.getBoundsInRoot())
        assertTrue("$label must fit the real viewport", full.left >= viewport.left && full.right <= viewport.right &&
            full.top >= viewport.top && full.bottom <= viewport.bottom && (full.right - full.left).value > 0f && (full.bottom - full.top).value > 0f)
    }
    private fun assertFullText(label: String, node: SemanticsNodeInteraction, expected: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single(); assertEquals(expected, layout.layoutInput.text.text)
        record["${label}TextLayout"] = mapOf("lineCount" to layout.lineCount, "hasVisualOverflow" to layout.hasVisualOverflow)
        assertTextFitsLayout(layout, expected)
    }
    private fun recordActionLayout(tag: String, label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        val text = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        record["${tag}LabelLayout"] = mapOf("text" to layout.layoutInput.text.text,
            "widthPx" to layout.size.width, "heightPx" to layout.size.height,
            "maxWidthPx" to layout.layoutInput.constraints.maxWidth, "maxHeightPx" to layout.layoutInput.constraints.maxHeight,
            "paragraphWidthPx" to layout.multiParagraph.width, "paragraphHeightPx" to layout.multiParagraph.height,
            "lineCount" to layout.lineCount, "didOverflowWidth" to layout.didOverflowWidth,
            "didOverflowHeight" to layout.didOverflowHeight, "hasVisualOverflow" to layout.hasVisualOverflow,
            "fontSizeSp" to layout.layoutInput.style.fontSize.value, "lineHeightSp" to layout.layoutInput.style.lineHeight.value,
            "lines" to (0 until layout.lineCount).map { line -> mapOf("start" to layout.getLineStart(line),
                "end" to layout.getLineEnd(line), "topPx" to layout.getLineTop(line), "bottomPx" to layout.getLineBottom(line)) })
        assertTextFitsLayout(layout, label)
        for (word in Regex("[^ ]+").findAll(label))
            assertEquals("A button word must not break by letters", layout.getLineForOffset(word.range.first),
                layout.getLineForOffset(word.range.last))
    }
    private fun rect(r: DpRect) = listOf(r.left.value, r.top.value, r.right.value, r.bottom.value)
    private fun geometry(node: SemanticsNodeInteraction): Any = runCatching {
        mapOf("clippedDp" to rect(node.getBoundsInRoot()), "unclippedDp" to rect(node.getUnclippedBoundsInRoot()))
    }.getOrElse { mapOf("error" to it.toString()) }
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("pending_viewport_window").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun receipt(label: String, block: () -> Unit) {
        record["status"] = "NOT_COMPLETED"; record["scope"] = "CONTROLLED_COMPONENT_UI_ONLY; runtime fit not pre-proven"
        record["case"] = label; record["requestedDp"] = listOf(width, height); record["fontScale"] = scale; record["dark"] = dark
        record["capturedXmlSha256"] = "95ae7b021db3569082ddf005ffe168a40c68cb21af17441f70cae9e4b45eef22"
        record["capturedState"] = captured; record["rawCandidateId"] = candidate.id
        try { block(); record["status"] = "PASS_COMPONENT_UI_ONLY" }
        catch (failure: Throwable) { record["status"] = "FAIL"; record["failure"] = failure.toString(); throw failure }
        finally {
            record["density"] = actualDensity; record["reviews"] = reviewCalls.get(); record["opened"] = opened
            record["confirmedRawIds"] = confirmed; record["normalRefreshes"] = refreshed.get(); record["otherSearches"] = searched.get()
            runCatching { capture("final") }.onFailure { record["finalCaptureError"] = it.toString() }
            File(output, "receipt.json").writeText(gson.toJson(record))
        }
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0},height={1},font={2},dark={3}")
        fun configurations() = listOf(false, true).flatMap { dark -> listOf(
            arrayOf<Any>(411, 914, 1f, dark), arrayOf<Any>(320, 640, 2f, dark)) }
        // Exact persisted value from main-fresh-final/live-export/qa-data/shared_prefs/internet_schedule_cache.xml.
        private val CAPTURED_STATE = """
            {
              "candidateObservedIds": [
                "official_map:20385"
              ],
              "candidateSearchIdentity": "325fcb99005144fc0cd8dd3ec9f8d08b3bbc2b5b2db24e5544fb11b2a0496eee",
              "candidateSearchIncomplete": true,
              "candidateSearchUnavailable": true,
              "checkedAt": "2026-11-14T21:32:34.916830Z",
              "configuredWebsite": "",
              "customSource": "",
              "dismissedCandidateIds": [],
              "loading": false,
              "parishId": "osm:way:155168069",
              "publicationRejected": false,
              "siteEvidenceUrl": "",
              "sourceCandidates": [
                {
                  "address": "Россия, г. Калуга, мкр. Тайфун, ул. Молодежная",
                  "checkedAt": "2026-11-14T21:32:36.392131Z",
                  "currentContactAddress": "",
                  "currentContactSha256": "",
                  "currentContactUrl": "",
                  "evidenceSha256": "806ab496719f2e184ae526a0feaf1ba36e91dc3caf3dc3bd70a96edc8fb7173c",
                  "evidenceUrl": "https://map.patriarchia.ru/?infoid=20385",
                  "latitude": 54.5307148,
                  "linkedEmail": "",
                  "longitude": 36.3086205,
                  "name": "Храм Вознесения Господня г. Калуга",
                  "outcome": "INSUFFICIENT",
                  "provider": "official_map",
                  "providerLabel": "Официальная карта храмов",
                  "providerObjectId": "20385",
                  "reason": "Проверьте, что на этом сайте опубликовано расписание выбранного храма",
                  "selectedIdentity": "325fcb99005144fc0cd8dd3ec9f8d08b3bbc2b5b2db24e5544fb11b2a0496eee",
                  "sourceUrl": "http://kalujskoe-19.cerkov.ru/",
                  "verificationUnavailable": false,
                  "version": 1
                }
              ],
              "sourceChangeBlock": "NONE",
              "sourceHasWithdrawals": false,
              "sourceNeedsVerification": false,
              "sourceUrl": "",
              "status": "UNAVAILABLE",
              "website": ""
            }
        """.trimIndent()
    }
}
