package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.schedule.*
import java.time.Instant
import java.time.LocalDate
import java.io.File
import java.util.UUID

@RunWith(Parameterized::class)
class ScheduleSourceReviewFlowTest(private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val captureId = UUID.randomUUID().toString()
    private val church = Parish("osm:way:42", "Храм святителя Николая", "Николая", "Москва",
        "Осенняя улица, 32 с1", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "",
        55.76964415, 37.4061332)
    private fun row(provider: String = "public_index") = ScheduleSourceCandidate(
        selectedIdentity = ScheduleEntityIdentity.fingerprint(church), provider = provider,
        providerLabel = if (provider == "public_index") "Первый справочник" else "Второй справочник",
        providerObjectId = "42", name = church.name, address = church.address,
        sourceUrl = "https://parish.example/schedule/", evidenceUrl = "https://index.example/church/42",
        evidenceSha256 = "a".repeat(64), checkedAt = "2026-11-13T06:00:00Z",
        latitude = church.latitude!!, longitude = church.longitude!!, outcome = ScheduleIdentityOutcome.INSUFFICIENT,
        reason = "Проверьте название и адрес")
    private fun state(vararg rows: ScheduleSourceCandidate) = InternetScheduleState(church.id,
        InternetScheduleStatus.NO_WEBSITE, checkedAt = "2026-11-13T06:01:00Z",
        sourceCandidates = rows.toList(), candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(church))

    @Composable private fun Narrow(content: @Composable () -> Unit) {
        val native = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(native.density, 2f)) {
            GuideTheme(dark) { Box(Modifier.size(320.dp, 640.dp)) { content() } }
        }
    }

    @Test fun ordinaryTextKeepsTheGroupedSourceAndItsActionsReadable() {
        val first = row(); val second = row("second_index")
        compose.setContent { GuideTheme(dark) { Box(Modifier.size(360.dp, 760.dp)) {
            ChurchScreen(listOf(church), church.id, LocalDate.of(2026, 11, 13),
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                searchQuery = "", onSearchQueryChange = {}, internetSchedule = state(first, second),
                onConfirmScheduleCandidate = {}, onDismissScheduleCandidate = {})
        } } }
        reveal("church_content", "schedule_sources_heading")
        capture("church_content", "ordinary-source-heading", "360-font100")
        reveal("church_content", "confirm_candidate_${first.id}").assertTextContains("Выбрать сайт")
        capture("church_content", "ordinary-source-actions", "360-font100")
        compose.onNodeWithTag("confirm_candidate_${second.id}").assertDoesNotExist()
    }

    @Test fun exactDuplicateHasOnePrimaryActionAndIndividualDismissalsWithRealBounds() {
        val first = row(); val second = row("second_index")
        val state = mutableStateOf(state(first, second))
        val confirmed = mutableListOf<String>(); val dismissed = mutableListOf<String>()
        compose.setContent { Narrow {
            ChurchScreen(listOf(church), church.id, LocalDate.of(2026, 11, 13),
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                searchQuery = "", onSearchQueryChange = {}, internetSchedule = state.value,
                onConfirmScheduleCandidate = { confirmed += it },
                onDismissScheduleCandidate = { id ->
                    dismissed += id
                    state.value = state.value.copy(dismissedCandidateIds = state.value.dismissedCandidateIds + id)
                })
        } }
        reveal("church_content", "confirm_candidate_${first.id}").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("confirm_candidate_${second.id}").assertDoesNotExist()
        val open = compose.onNodeWithTag("open_candidate_${first.id}").getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithTag("confirm_candidate_${first.id}").getUnclippedBoundsInRoot()
        assertTrue("Open and confirm need separate touch areas", confirm.top - open.bottom >= 12.dp)
        assertWordsRemainWhole("Выбрать сайт")
        capture("church_content", "source-actions")
        reveal("church_content", "source_details_${first.id}").assertHeightIsAtLeast(48.dp)
        val details = compose.onNodeWithTag("source_details_${first.id}").getUnclippedBoundsInRoot()
        val movedConfirm = compose.onNodeWithTag("confirm_candidate_${first.id}").getUnclippedBoundsInRoot()
        assertTrue("Confirm and details need separate touch areas", details.top - movedConfirm.bottom >= 12.dp)
        compose.onNodeWithTag("source_details_${first.id}").performClick()
        reveal("church_content", "dismiss_candidate_${first.id}").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(listOf(first.id), dismissed)
            assertEquals(listOf(first.id), state.value.dismissedCandidateIds)
            assertEquals(listOf(first.id, second.id), state.value.sourceCandidates.map { it.id })
            assertNull(state.value.siteBinding)
        }
        reveal("church_content", "confirm_candidate_${second.id}").assertTextContains("Выбрать сайт").performClick()
        compose.runOnIdle { assertEquals(listOf(second.id), confirmed) }
    }

    @Test fun currentContactAddressIsVisibleBeforeDetailsAtNarrowWidthAndDoubleFont() {
        val first = row().copy(address = "")
        val current = row("second_index").copy(address = "Осенняя улица, 33 к3", currentContactAddress = church.address,
            currentContactUrl = "https://parish.example/contacts/", currentContactSha256 = "b".repeat(64),
            linkedEmail = "parish@example.org")
        assertTrue("Fixture must carry the real current-contact validation", current.canConfirm(church))
        compose.setContent { Narrow {
            ChurchScreen(listOf(church), church.id, LocalDate.of(2026, 11, 13),
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                searchQuery = "", onSearchQueryChange = {}, internetSchedule = state(first, current),
                onConfirmScheduleCandidate = {})
        } }
        reveal("church_content", "source_current_address_${current.id}")
            .assertTextEquals("В текущих контактах: " + church.address)
        compose.onNodeWithText("В справочнике: " + current.address).assertExists()
        compose.onNodeWithTag("schedule_candidate_detail_${current.id}").assertDoesNotExist()
        capture("church_content", "current-contact")
        reveal("church_content", "confirm_candidate_${current.id}").assertIsEnabled()
        compose.onNodeWithTag("confirm_candidate_${first.id}").assertDoesNotExist()
    }

    @Test fun pendingTodayActionNavigatesAndRevealsTheActualSourceHeadingWithoutExtraScroll() {
        val first = row(); val second = row("second_index")
        var details by mutableStateOf(false)
        var request by mutableIntStateOf(0)
        var fallbackCalls = 0
        val state = state(first, second)
        compose.setContent { Narrow {
            if (!details) TodayScreen(church, Instant.parse("2026-11-13T08:00:00Z"), state, null, null,
                onFindChurch = {}, onChurchDetails = { fallbackCalls++ }, onOpenBook = { _, _, _ -> },
                onResumeBook = {}, onRefresh = {}, onResumeLegacy = null, bottomBar = {},
                onReviewScheduleSources = { request++; details = true })
            else ChurchScreen(listOf(church), church.id, LocalDate.of(2026, 11, 13),
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                searchQuery = "", onSearchQueryChange = {}, internetSchedule = state,
                onConfirmScheduleCandidate = {}, sourceReviewRequest = request, showBack = false,
                // Keep the real unrelated reading item in this navigation test.
                onReadLiturgy = {}, onReadServiceText = { _, _ -> })
        } }
        reveal("main_content", "today_review_schedule_sources").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("today_schedule_checked").assertTextEquals(
            "Найдены сайты, ожидается проверка\nПопытка обновления: 13 ноября 2026, 09:01:00")
        capture("main_content", "today-review")
        compose.onNodeWithTag("today_review_schedule_sources").performClick()
        compose.waitForIdle()
        // No performScrollTo/scrollToNode here: this is the production anchor's result.
        compose.onNodeWithTag("schedule_sources_heading").assertIsDisplayed().assertTextEquals("Найденные сайты")
        val heading = compose.onNodeWithTag("schedule_sources_heading").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("church_content").getBoundsInRoot()
        assertTrue(heading.top >= viewport.top - 1.dp && heading.bottom <= viewport.bottom + 1.dp)
        capture("church_content", "source-heading")
        compose.runOnIdle { assertEquals(0, fallbackCalls) }
        reveal("church_content", "source_details_${first.id}").performClick()
        compose.onNodeWithTag("schedule_candidate_detail_${second.id}").assertExists()
    }

    @Test fun optionalReviewCallbackKeepsTheOldDetailsFallbackAndLoadingDisablesTheAction() {
        val first = row()
        var own by mutableStateOf(state(first).copy(loading = true))
        var fallbackCalls = 0
        compose.setContent { Narrow {
            TodayScreen(church, Instant.parse("2026-11-13T08:00:00Z"), own, null, null,
                onFindChurch = {}, onChurchDetails = { fallbackCalls++ }, onOpenBook = { _, _, _ -> },
                onResumeBook = {}, onRefresh = {}, onResumeLegacy = null, bottomBar = {})
        } }
        reveal("main_content", "today_review_schedule_sources").assertIsNotEnabled()
        compose.runOnIdle { own = own.copy(loading = false) }
        reveal("main_content", "today_review_schedule_sources").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, fallbackCalls) }
        compose.runOnIdle { own = own.copy(parishId = "other") }
        compose.waitForIdle()
        compose.onNodeWithTag("today_review_schedule_sources").assertDoesNotExist()
    }

    @Test fun tomorrowEmptyNavigationKeepsTheSavedDayAndFindsSourcesEvenWhenTodayPublishesLate() {
        val candidate = row()
        var showingChurch by mutableStateOf(true)
        var showBack by mutableStateOf(true)
        var published by mutableStateOf(true)
        var request by mutableIntStateOf(0)
        val own = state(candidate)
        compose.setContent { Narrow {
            val routes = rememberSaveableStateHolder()
            val selected = church.copy(sourceUrl = "https://parish.example/schedule/",
                scheduleValidFrom = "2026-11-13", scheduleValidUntil = "2026-11-14",
                schedule = if (published) listOf(ParishService("2026-11-13", "12:00", "Литургия", "service_block")) else emptyList())
            routes.SaveableStateProvider(if (showingChurch) "church" else "today") {
                if (showingChurch) ChurchScreen(listOf(selected), selected.id, LocalDate.of(2026, 11, 13),
                    onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                    searchQuery = "", onSearchQueryChange = {}, internetSchedule = own,
                    onConfirmScheduleCandidate = {}, onRefreshSchedule = {}, showBack = showBack,
                    onOpenService = {}, onReadLiturgy = {}, onReadServiceText = { _, _ -> },
                    userStarts = listOf(UserServiceStart(church.id, "2026-11-14", "08:00")),
                    sourceReviewRequest = request, sourceReviewIdentity = ScheduleEntityIdentity.fingerprint(selected))
                else TodayScreen(selected, Instant.parse("2026-11-13T08:00:00Z"), own, null, null,
                    onFindChurch = {}, onChurchDetails = {}, onOpenBook = { _, _, _ -> }, onResumeBook = {},
                    onRefresh = {}, onResumeLegacy = null, bottomBar = {},
                    onReviewScheduleSources = { published = true; request++; showingChurch = true })
            }
        } }
        // Both variants start with Church's saved scroll at the bottom.
        // First: tomorrow retained, today already published. Second: today retained,
        // publication arrives exactly while returning from an empty Tomorrow screen.
        for (backVisible in listOf(true, false)) {
            compose.runOnIdle { showBack = backVisible; published = backVisible; showingChurch = true }
            val retainedDay = if (backVisible) "schedule_tomorrow" else "schedule_today"
            reveal("church_content", retainedDay).performClick().assertIsSelected()
            reveal("church_content", "church_details")
            compose.runOnIdle { showingChurch = false }
            reveal("main_content", "today_day_tomorrow").performClick().assertIsSelected()
            compose.onNodeWithTag("today_service_time").assertDoesNotExist()
            reveal("main_content", "today_review_schedule_sources").performClick()
            compose.waitForIdle()
            // Observe production navigation before any test-side scroll in Church.
            compose.onNodeWithTag("schedule_sources_heading").assertIsDisplayed().assertTextEquals("Найденные сайты")
            val heading = compose.onNodeWithTag("schedule_sources_heading").getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("church_content").getBoundsInRoot()
            assertTrue(heading.top >= viewport.top - 1.dp && heading.bottom <= viewport.bottom + 1.dp)
            compose.onNodeWithTag("confirm_candidate_${candidate.id}").assertExists()
            reveal("church_content", retainedDay).assertIsSelected()
            if (backVisible) {
                // Empty-day controls have moved after the candidates, and remain usable once.
                reveal("church_content", "refresh_schedule").assertIsEnabled()
                compose.onAllNodesWithTag("refresh_schedule").assertCountEquals(1)
                reveal("church_content", "open_schedule_website").assertIsEnabled()
            } else {
                reveal("church_content", "church_service_time").assertTextEquals("12:00")
            }
        }
    }

    @Test fun heldActionsCannotCrossTheSameParishIdFingerprintOrLaterSafetyState() {
        val first = row()
        val owner = mutableStateOf(church)
        val own = mutableStateOf(state(first))
        val confirmed = mutableListOf<String>(); val dismissed = mutableListOf<String>()
        compose.setContent { Narrow {
            ChurchScreen(listOf(owner.value), owner.value.id, LocalDate.of(2026, 11, 13),
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                searchQuery = "", onSearchQueryChange = {}, internetSchedule = own.value,
                onConfirmScheduleCandidate = { confirmed += it }, onDismissScheduleCandidate = { dismissed += it })
        } }
        fun heldActions(id: String): Pair<() -> Boolean, () -> Boolean> {
            val confirm = requireNotNull(reveal("church_content", "confirm_candidate_${id}")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            if (compose.onAllNodesWithTag("dismiss_candidate_${id}").fetchSemanticsNodes().isEmpty())
                reveal("church_content", "source_details_${id}").performClick()
            val dismiss = requireNotNull(reveal("church_content", "dismiss_candidate_${id}")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            return confirm to dismiss
        }
        val old = heldActions(first.id)
        val changed = church.copy(address = "Осенняя улица, 34 с1")
        val fresh = first.copy(selectedIdentity = ScheduleEntityIdentity.fingerprint(changed), address = changed.address)
        assertEquals(first.id, fresh.id)
        assertNotEquals(first.selectedIdentity, fresh.selectedIdentity)
        assertTrue(fresh.canConfirm(changed))
        val ready = state(fresh).copy(candidateSearchIdentity = fresh.selectedIdentity)
        compose.runOnIdle { owner.value = changed; own.value = ready }
        compose.waitForIdle()
        compose.runOnIdle { old.first(); old.second(); assertTrue(confirmed.isEmpty()); assertTrue(dismissed.isEmpty()) }
        val current = heldActions(fresh.id)
        compose.runOnIdle { current.first(); current.second(); assertEquals(listOf(fresh.id), confirmed); assertEquals(listOf(fresh.id), dismissed) }
        compose.runOnIdle { confirmed.clear(); dismissed.clear() }

        val approved = ScheduleEntityIdentity.bind(changed, fresh.sourceUrl, fresh.evidenceUrl, fresh.name,
            ScheduleBindingMethod.USER_CONFIRMED).copy(candidateConfirmation = ScheduleCandidateConfirmation(1,
                ScheduleCandidateConfirmation.TYPE, fresh.selectedIdentity, fresh, "2026-11-13T06:01:00Z"))
        val other = changed.copy(id = "osm:way:43")
        val otherRow = fresh.copy(selectedIdentity = ScheduleEntityIdentity.fingerprint(other))
        val variants = listOf(
            changed to ready.copy(sourceCandidates = listOf(fresh.copy(evidenceSha256 = "c".repeat(64)))),
            changed to ready.copy(loading = true),
            changed to ready.copy(dismissedCandidateIds = listOf(fresh.id)),
            changed to ready.copy(sourceCandidates = listOf(fresh.copy(outcome = ScheduleIdentityOutcome.CONFLICT))),
            changed to ready.copy(siteBinding = approved),
            other to ready.copy(parishId = other.id, sourceCandidates = listOf(otherRow), candidateSearchIdentity = otherRow.selectedIdentity))
        for ((nextChurch, nextState) in variants) {
            compose.runOnIdle { owner.value = changed; own.value = ready }
            compose.waitForIdle()
            val held = heldActions(fresh.id)
            compose.runOnIdle { owner.value = nextChurch; own.value = nextState }
            compose.waitForIdle()
            compose.runOnIdle {
                held.first(); held.second()
                assertTrue("Old confirmation must be suppressed", confirmed.isEmpty())
                assertTrue("Old dismissal must be suppressed", dismissed.isEmpty())
            }
        }
        val newProof = fresh.copy(evidenceSha256 = "c".repeat(64))
        compose.runOnIdle { owner.value = changed; own.value = ready.copy(sourceCandidates = listOf(newProof)) }
        compose.waitForIdle()
        val latest = heldActions(newProof.id)
        compose.runOnIdle {
            latest.first(); latest.second()
            assertEquals(listOf(newProof.id), confirmed)
            assertEquals(listOf(newProof.id), dismissed)
        }
    }

    private fun capture(container: String, label: String, size: String = "320-font200") {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(container).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "source-ui-native/$captureId").apply { mkdirs() }
        File(folder, "$label-dark-$dark-$size.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun assertWordsRemainWhole(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertFalse("Action label must remain fully visible", layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line)
            assertTrue("Do not leave a letter of the action on another line",
                end in 1 until label.length && (label[end - 1].isWhitespace() || label[end].isWhitespace()))
        }
    }

    private fun reveal(container: String, tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
        val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val bounds = node.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag(container).getBoundsInRoot()
        assertTrue("The whole target must be touchable", bounds.top >= viewport.top - 1.dp && bounds.bottom <= viewport.bottom + 1.dp)
        return node
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}")
        fun themes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}

