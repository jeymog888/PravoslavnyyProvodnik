package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.schedule.*
import java.time.Instant
import java.time.LocalDate

/** Controlled valid DTOs isolate pointer/semantics mechanics; real Map replay lives in the auto suite. */
@RunWith(Parameterized::class)
class ScheduleSourceTimestampFlowTest(private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val church = Parish("osm:way:42", "Храм святителя Николая", "Николая", "Москва",
        "Осенняя улица, 32 с1", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.76964415, 37.4061332)
    private fun row() = ScheduleSourceCandidate(selectedIdentity = ScheduleEntityIdentity.fingerprint(church),
        provider = "public_index", providerLabel = "Справочник", providerObjectId = "42", name = church.name,
        address = church.address, sourceUrl = "https://parish.example/schedule/", evidenceUrl = "https://index.example/church/42",
        evidenceSha256 = "a".repeat(64), checkedAt = "2026-11-14T06:00:00Z", latitude = church.latitude!!,
        longitude = church.longitude!!, outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Проверьте название и адрес")
    private fun state(row: ScheduleSourceCandidate) = InternetScheduleState(church.id, InternetScheduleStatus.NO_WEBSITE,
        checkedAt = row.checkedAt, sourceCandidates = listOf(row), candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(church))
    private fun mount(own: State<InternetScheduleState>, confirmed: MutableList<ScheduleSourceCandidate>) {
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, 2f)) {
                GuideTheme(dark) { Box(Modifier.size(320.dp, 640.dp)) {
                    ChurchScreen(listOf(church), church.id, LocalDate.of(2026, 11, 14),
                        onSelect = { _, done -> done(); ChurchSelectionResult.SELECTED }, onOpenUrl = {}, onBack = {},
                        searchQuery = "", onSearchQueryChange = {}, internetSchedule = own.value,
                        sourceSearchActive = true,
                        onConfirmScheduleCandidate = { id -> confirmed += own.value.sourceCandidates.single { it.id == id } },
                        onDismissScheduleCandidate = {})
                } }
            }
        }
        compose.onNodeWithTag("schedule_source_search_strip").assertIsDisplayed()
        compose.onNodeWithTag("source_search_review").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
    private fun confirm(id: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("confirm_candidate_$id"))
        return compose.onNodeWithTag("confirm_candidate_$id").performScrollTo().assertIsDisplayed()
    }

    @Test fun timestampOnlyHeldSemanticWrapperDispatchesTheLatestSafeActionOfTheSameNode() {
        val first = row(); val own = mutableStateOf(state(first)); val confirmed = mutableListOf<ScheduleSourceCandidate>()
        mount(own, confirmed)
        val node = confirm(first.id)
        val nodeId = node.fetchSemanticsNode().id
        val held = requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val fresh = first.copy(checkedAt = Instant.parse(first.checkedAt).plusSeconds(1).toString())
        assertTrue(fresh.canConfirm(church)); assertNotEquals(first, fresh)
        assertEquals(first.copy(checkedAt = fresh.checkedAt), fresh)
        compose.runOnIdle { own.value = state(fresh) }; compose.waitForIdle()
        assertEquals(nodeId, compose.onNodeWithTag("confirm_candidate_${first.id}").fetchSemanticsNode().id)
        compose.runOnIdle { held() }
        assertEquals(listOf(fresh), confirmed)
        assertEquals(fresh.checkedAt, own.value.sourceCandidates.single().checkedAt)
    }

    @Test fun aGestureAndHeldWrapperFromTheOldProofCannotConfirmAChangedProof() {
        val first = row(); val own = mutableStateOf(state(first)); val confirmed = mutableListOf<ScheduleSourceCandidate>()
        mount(own, confirmed)
        val node = confirm(first.id)
        val nodeId = node.fetchSemanticsNode().id
        val held = requireNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        node.performTouchInput { down(center) }
        val fresh = first.copy(evidenceSha256 = "b".repeat(64), checkedAt = Instant.parse(first.checkedAt).plusSeconds(1).toString())
        compose.runOnIdle { own.value = state(fresh) }; compose.waitForIdle()
        assertNotEquals(nodeId, compose.onNodeWithTag("confirm_candidate_${first.id}").fetchSemanticsNode().id)
        compose.runOnIdle { held() }
        compose.onNodeWithTag("confirm_candidate_${first.id}").performTouchInput { up() }
        compose.waitForIdle(); assertTrue(confirmed.isEmpty())
        confirm(first.id).performClick()
        assertEquals(listOf(fresh), confirmed)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}") fun themes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }
}
