package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import java.time.Instant
import java.time.LocalDate

@RunWith(Parameterized::class)
class ScheduleRefreshFeedbackTest(private val churchDetails: Boolean, private val status: InternetScheduleStatus,
    private val needsVerification: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val church = Parish("refresh", "Елисаветинский храм", "Елисаветинский храм", "Опалиха", "Пушкина, 2", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")

    @Test fun repeatedUnsuccessfulRefreshShowsLoadingThenANewAccessibleReceipt() {
        val source = if (status == InternetScheduleStatus.NO_WEBSITE) null else "https://parish.example/schedule/"
        var state by mutableStateOf(InternetScheduleState(church.id, status, website = source.orEmpty(),
            sourceUrl = source.orEmpty(), checkedAt = "2026-11-11T10:00:00Z", sourceNeedsVerification = needsVerification))
        val opened = mutableListOf<String>()
        var refreshes = 0
        val refresh = { refreshes++; state = state.copy(loading = true) }
        val containerTag = if (churchDetails) "church_content" else "main_content"
        val refreshTag = if (churchDetails) "refresh_schedule" else "today_refresh_schedule"
        val loadingTag = if (churchDetails) "schedule_loading_summary" else "today_schedule_loading"
        val receiptTag = if (churchDetails) "church_schedule_checked" else "today_schedule_checked"
        val expected = if (needsVerification) "Источник требует проверки" else when (status) {
            InternetScheduleStatus.NO_WEBSITE -> "Источник расписания не найден"
            InternetScheduleStatus.UNSUPPORTED -> "Не удалось прочитать даты и время"
            else -> "Обновление не удалось"
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                GuideTheme(false) { Box(Modifier.size(320.dp, 640.dp)) {
                    if (churchDetails) ChurchScreen(listOf(church), church.id, LocalDate.of(2026, 11, 11),
                        onSelect = { _, _ -> error("Refresh must not change the parish") }, onOpenUrl = { opened += it }, onBack = {},
                        internetSchedule = state, onRefreshSchedule = refresh,
                        searchQuery = "", onSearchQueryChange = {}, now = Instant.parse("2026-11-11T10:00:00Z"))
                    else TodayScreen(church, Instant.parse("2026-11-11T10:00:00Z"), state, null, null,
                        onFindChurch = {}, onChurchDetails = {}, onOpenBook = { _, _, _ -> }, onResumeBook = {},
                        onRefresh = refresh, onResumeLegacy = null, bottomBar = {},
                        onOpenScheduleSource = source?.let { url -> { opened += url; Unit } })
                } }
            }
        }
        fun scroll(tag: String): SemanticsNodeInteraction {
            val container = compose.onNodeWithTag(containerTag)
            container.performScrollToIndex(0)
            // Exercise the same touch route as a reader. A refresh changes this card's
            // height, so do not issue nested semantic scroll requests against old geometry.
            repeat(30) {
                compose.mainClock.advanceTimeBy(300)
                compose.waitForIdle()
                val target = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
                if (target != null) {
                    val viewport = container.getBoundsInRoot()
                    val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                    if (bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
                        return compose.onNodeWithTag(tag).assertIsDisplayed()
                }
                container.performTouchInput {
                    swipe(Offset(centerX, height * .8f), Offset(centerX, height * .55f), durationMillis = 800)
                }
            }
            throw AssertionError("Could not reveal $tag with bounded touch scrolling")
        }
        if (needsVerification) {
            compose.onNodeWithTag(containerTag).performScrollToNode(hasText("Нужно уточнить расписание"))
            compose.onNodeWithText("Нужно уточнить расписание").assertIsDisplayed()
            compose.onNodeWithText("Расписание пока не найдено").assertDoesNotExist()
        }
        scroll(receiptTag).assertTextEquals("$expected\nПопытка обновления: 11 ноября 2026, 13:00:00")
        val sourceTag = if (churchDetails) "open_schedule_website" else "today_schedule_source"
        if (source == null) compose.onNodeWithTag(sourceTag).assertDoesNotExist()
        else {
            scroll(sourceTag).assertHeightIsAtLeast(48.dp)
                .assert(hasAnyAncestor(hasTestTag(if (churchDetails) "church_schedule_card" else "today_schedule_card")))
                .performClick()
            compose.runOnIdle { assertEquals(listOf(source), opened) }
        }
        repeat(2) { attempt ->
            scroll(refreshTag).assertIsEnabled().performClick()
            scroll(loadingTag)
            compose.onNodeWithTag(receiptTag).assertDoesNotExist()
            // The result is still empty: only the completed attempt's timestamp changes.
            compose.runOnIdle { state = state.copy(loading = false, checkedAt = "2026-11-11T10:00:0${attempt + 1}Z") }
            scroll(receiptTag)
                .assertTextEquals("$expected\nПопытка обновления: 11 ноября 2026, 13:00:0${attempt + 1}")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(receiptTag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTextFitsLayout(layouts.single(), "$expected\nПопытка обновления: 11 ноября 2026, 13:00:0${attempt + 1}")
            compose.onNodeWithTag(loadingTag).assertDoesNotExist()
            scroll(refreshTag).assertIsEnabled()
        }
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "details={0} result={1} verification={2}")
        fun screens() = listOf(false, true).flatMap { details ->
            listOf(InternetScheduleStatus.NO_WEBSITE, InternetScheduleStatus.UNSUPPORTED, InternetScheduleStatus.UNAVAILABLE)
                .map { arrayOf<Any>(details, it, false) } +
                listOf(arrayOf<Any>(details, InternetScheduleStatus.UNAVAILABLE, true))
        }
    }
}
