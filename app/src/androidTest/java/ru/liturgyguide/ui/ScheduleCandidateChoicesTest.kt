package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.schedule.*
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ScheduleCandidateChoicesTest {
    @get:Rule val compose = createComposeRule()
    private val selected = Parish("osm:way:42", "Храм святителя Николая", "Николая", "Москва", "Осенняя улица, 32 с1", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.76964415, 37.4061332)
    private fun candidate(conflict: Boolean = false) = ScheduleSourceCandidate(selectedIdentity = ScheduleEntityIdentity.fingerprint(selected),
        provider = "public_index", providerLabel = "Публичный справочник", providerObjectId = "42", name = selected.name,
        address = if (conflict) "Осенняя улица, 33 к3" else selected.address, sourceUrl = "https://parish.example/schedule/",
        evidenceUrl = "https://index.example/church/42", evidenceSha256 = "a".repeat(64), checkedAt = "2026-11-13T06:00:00Z",
        latitude = selected.latitude!!, longitude = selected.longitude!!,
        outcome = if (conflict) ScheduleIdentityOutcome.CONFLICT else ScheduleIdentityOutcome.INSUFFICIENT,
        reason = if (conflict) "Адрес источника не совпадает с выбранным храмом" else "Проверьте принадлежность расписания")

    @Test fun oneConfirmationNeedsNoTypingAndActionsRemainSeparateAtNarrowWidthAndDoubleFont() {
        val candidate = candidate(); val confirmed = mutableListOf<String>(); val opened = mutableListOf<String>()
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, 2f)) {
                GuideTheme(false) { Box(Modifier.size(320.dp, 640.dp)) {
                    ChurchScreen(listOf(selected), selected.id, LocalDate.of(2026, 11, 13),
                        onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {},
                        onOpenUrl = { opened += it }, searchQuery = "", onSearchQueryChange = {},
                        internetSchedule = InternetScheduleState(selected.id, InternetScheduleStatus.NO_WEBSITE,
                            sourceCandidates = listOf(candidate), candidateSearchIdentity = candidate.selectedIdentity),
                        onConfirmScheduleCandidate = { confirmed += it }, onDismissScheduleCandidate = {})
                } }
            }
        }
        reveal("open_candidate_${candidate.id}").assertHeightIsAtLeast(48.dp).performClick()
        reveal("confirm_candidate_${candidate.id}").assertHeightIsAtLeast(48.dp)
        val open = compose.onNodeWithTag("open_candidate_${candidate.id}").getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithTag("confirm_candidate_${candidate.id}").getUnclippedBoundsInRoot()
        assertTrue(confirm.top - open.bottom >= 12.dp)
        compose.onNodeWithTag("confirm_candidate_${candidate.id}").assertTextContains("Выбрать сайт").performClick()
        compose.onNodeWithTag("schedule_website_input").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(candidate.id), confirmed); assertEquals(listOf(candidate.sourceUrl), opened) }
    }

    @Test fun aKnownHouseConflictHasNoConfirmationAction() {
        val candidate = candidate(true)
        compose.setContent { GuideTheme(false) {
            ChurchScreen(listOf(selected), selected.id, LocalDate.of(2026, 11, 13),
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                searchQuery = "", onSearchQueryChange = {},
                internetSchedule = InternetScheduleState(selected.id, sourceCandidates = listOf(candidate)),
                onConfirmScheduleCandidate = { error("A known house conflict cannot be accepted") })
        } }
        reveal("schedule_candidate_conflict_${candidate.id}").assertIsDisplayed()
        compose.onNodeWithTag("confirm_candidate_${candidate.id}").assertDoesNotExist()
    }

    @Test fun loadingDenialAndChurchChangesCannotActivateOldReviewActions() {
        val candidate = candidate(); val church = mutableStateOf(selected)
        val state = mutableStateOf(InternetScheduleState(selected.id, sourceCandidates = listOf(candidate), loading = true))
        val confirmed = mutableListOf<String>()
        compose.setContent { GuideTheme(false) {
            ChurchScreen(listOf(church.value), church.value.id, LocalDate.of(2026, 11, 13),
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                searchQuery = "", onSearchQueryChange = {}, internetSchedule = state.value,
                onConfirmScheduleCandidate = { confirmed += it }, onDismissScheduleCandidate = {
                    state.value = state.value.copy(dismissedCandidateIds = listOf(it))
                })
        } }
        reveal("confirm_candidate_${candidate.id}").assertIsNotEnabled()
        reveal("source_details_${candidate.id}").performClick()
        reveal("dismiss_candidate_${candidate.id}").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        reveal("dismiss_candidate_${candidate.id}").performClick()
        compose.onNodeWithTag("confirm_candidate_${candidate.id}").assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(dismissedCandidateIds = emptyList())
            church.value = selected.copy(id = "osm:way:43", name = "Храм Петра и Павла")
        }
        compose.onNodeWithTag("confirm_candidate_${candidate.id}").assertDoesNotExist()
        compose.runOnIdle { assertTrue(confirmed.isEmpty()) }
    }

    @Test fun emptyProviderFailureStillShowsItsRetryMessageAndNoConfirmation() {
        compose.setContent { GuideTheme(false) {
            ChurchScreen(listOf(selected), selected.id, LocalDate.of(2026, 11, 13),
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                searchQuery = "", onSearchQueryChange = {},
                internetSchedule = InternetScheduleState(selected.id, InternetScheduleStatus.UNAVAILABLE,
                    candidateSearchUnavailable = true, sourceCandidates = emptyList()),
                onConfirmScheduleCandidate = { error("There is no candidate to confirm") }, onRefreshSchedule = {})
        } }
        reveal("schedule_candidates_unavailable").assertIsDisplayed()
            .assertTextEquals("Не удалось проверить источники расписания. Повторите поиск")
        reveal("refresh_schedule").assertIsEnabled()
        compose.onNodeWithTag("schedule_candidate_choices").assertDoesNotExist()
    }

    @Test fun withdrawalHistoryDisablesAlternativeConfirmationAndExplainsWhy() {
        val candidate = candidate()
        compose.setContent { GuideTheme(false) {
            ChurchScreen(listOf(selected), selected.id, LocalDate.of(2026, 11, 13),
                onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onBack = {}, onOpenUrl = {},
                searchQuery = "", onSearchQueryChange = {},
                internetSchedule = InternetScheduleState(selected.id, sourceCandidates = listOf(candidate),
                    sourceHasWithdrawals = true, publicationRejected = true),
                onConfirmScheduleCandidate = { error("An alternative cannot clear withdrawal history") })
        } }
        reveal("schedule_candidate_history_${candidate.id}").assertIsDisplayed()
        reveal("confirm_candidate_${candidate.id}").assertIsNotEnabled()
    }

    private fun reveal(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag(tag))
        // The source card itself can exceed a compact large-font viewport. Finding
        // its lazy item does not ensure the nested button's centre is touchable.
        val target = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val bounds = target.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("church_content").getBoundsInRoot()
        assertTrue("The whole target must be reachable before injecting a real tap",
            bounds.top >= viewport.top - 1.dp && bounds.bottom <= viewport.bottom + 1.dp)
        return target
    }
}
