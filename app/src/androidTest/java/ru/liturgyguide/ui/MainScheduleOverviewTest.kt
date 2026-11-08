package ru.liturgyguide.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.ContextSuggestion
import ru.liturgyguide.service.ScheduleOverview
import ru.liturgyguide.service.ServiceRelation
import ru.liturgyguide.service.ServiceStartSource
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MainScheduleOverviewTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 11, 8)
    private val parish = Parish("test", "Храм в поездке", "Храм в поездке", "Москва", "", "", "", "Europe/Moscow",
        "", "", "2026-11-01", "2026-11-30", emptyList(), "", "")
    private val current = ContextSuggestion(ParishService("2026-11-08", "07:00", "Ранняя литургия", "liturgy"),
        ServiceRelation.InProgress, Instant.parse("2026-11-08T04:00:00Z"), "gospel",
        parishId = parish.id, timezone = parish.timezone)
    private val next = ContextSuggestion(ParishService("2026-11-08", "10:00", "Поздняя литургия", "liturgy"),
        ServiceRelation.Upcoming, Instant.parse("2026-11-08T07:00:00Z"), parishId = parish.id, timezone = parish.timezone)

    @Test fun eachServiceButtonRetainsItsExactOccurrence() {
        val opened = mutableListOf<ContextSuggestion>()
        var legacyJoins = 0
        compose.setContent { GuideTheme(false) { MainScreen(null, today, onStart = {}, onResume = {}, onFindStage = {},
            church = parish, onChurch = {}, suggestion = next, onJoinService = { legacyJoins++ }, onOrder = {}, bottomBar = {},
            overview = ScheduleOverview(current, next), onOpenService = { opened += it }) } }
        scrollTo("service_status_current")
        compose.onNodeWithTag("service_status_current").assertTextEquals("Возможно, уже идет")
        scrollTo("watch_current"); compose.onNodeWithTag("watch_current").performClick()
        scrollTo("service_status_next")
        compose.onNodeWithTag("service_status_next").assertTextEquals("Следующая по расписанию")
        scrollTo("watch_next"); compose.onNodeWithTag("watch_next").performClick()
        compose.runOnIdle {
            assertEquals(2, opened.size)
            assertSame(current, opened[0]); assertSame(next, opened[1])
            assertEquals(0, legacyJoins)
        }
        compose.onNodeWithTag("join_service").assertDoesNotExist()
    }

    @Test fun evenARecentSavedReadingNeverMasksCurrentOrNextService() {
        val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
        val stage = requireNotNull(container.getStageMatcher().getStageById("creed"))
        var resumed = 0
        compose.setContent { GuideTheme(false) { MainScreen(stage, today, savedEarlier = false,
            onStart = {}, onResume = { resumed++ }, onFindStage = {}, church = parish, onChurch = {},
            suggestion = current, onJoinService = {}, onOrder = {}, bottomBar = {},
            overview = ScheduleOverview(current, next), onOpenService = {}) } }
        scrollTo("service_card_current")
        compose.onNodeWithText("Ранняя литургия").assertIsDisplayed()
        scrollTo("service_card_next")
        compose.onNodeWithText("Поздняя литургия").assertIsDisplayed()
        scrollTo("resume_guide")
        compose.onNodeWithText("Сохраненное чтение").assertIsDisplayed()
        compose.onNodeWithTag("resume_guide").performClick()
        compose.runOnIdle { assertEquals(1, resumed) }
        scrollTo("watch_current"); compose.onNodeWithTag("watch_current").assertIsDisplayed()
    }

    @Test fun aFuturePersonalStartShowsItsYearAndTheParishTimeZone() {
        val trip = parish.copy(timezone = "Asia/Vladivostok")
        val personal = ContextSuggestion(ParishService("2027-01-01", "00:30", "Литургия", "liturgy"),
            ServiceRelation.Upcoming, Instant.parse("2026-12-31T14:30:00Z"), source = ServiceStartSource.USER,
            parishId = trip.id, timezone = trip.timezone)
        compose.setContent { GuideTheme(false) { MainScreen(null, LocalDate.of(2026, 12, 31),
            onStart = {}, onResume = {}, onFindStage = {}, church = trip, onChurch = {}, suggestion = null,
            onJoinService = {}, onOrder = {}, bottomBar = {}, overview = ScheduleOverview(nextKnownService = personal),
            onOpenService = {}) } }
        scrollTo("service_start_next")
        compose.onNodeWithTag("service_start_next").assertTextEquals("1 января 2027 · 00:30")
        compose.onNodeWithTag("service_zone_next").assertTextEquals("Местное время храма · UTC+10:00")
        scrollTo("service_status_next")
        compose.onNodeWithTag("service_status_next").assertTextEquals("Следующая по вашему времени")
        scrollTo("service_source_next")
        compose.onNodeWithTag("service_source_next").assertTextEquals("Начало указано вами")
        compose.onNodeWithTag("service_card_current").assertDoesNotExist()
    }

    @Test fun unknownScheduleKeepsManualReadingAndTempleSelectionAvailable() {
        var chosen = 0; var found = 0; var ordered = 0; var started = 0
        compose.setContent { GuideTheme(false) { MainScreen(null, today, onStart = { started++ }, onResume = {},
            onFindStage = { found++ }, church = parish, onChurch = { chosen++ }, suggestion = current,
            onJoinService = { fail("Legacy suggestion must not leak into an empty overview") }, onOrder = { ordered++ },
            bottomBar = {}, overview = ScheduleOverview(), onOpenService = { fail("Unknown service cannot be opened") }) } }
        scrollTo("open_church"); compose.onNodeWithTag("open_church").performClick()
        scrollTo("schedule_unknown")
        compose.onNodeWithTag("schedule_unknown").assertTextEquals("Нет данных о ближайшей литургии")
        scrollTo("find_stage"); compose.onNodeWithTag("find_stage").performClick()
        scrollTo("open_order"); compose.onNodeWithTag("open_order").performClick()
        scrollTo("start_guide"); compose.onNodeWithTag("start_guide").performClick()
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1), listOf(chosen, found, ordered, started)) }
        compose.onNodeWithTag("watch_current").assertDoesNotExist()
        compose.onNodeWithTag("watch_next").assertDoesNotExist()
    }

    @Test fun informationOnlyCardsRemainVisibleUntilDirectOpeningIsConnected() {
        val exhausted = current.copy(estimatedGuideExhausted = true)
        compose.setContent { GuideTheme(false) { MainScreen(null, today, onStart = {}, onResume = {}, onFindStage = {},
            church = parish, onChurch = {}, suggestion = null, onJoinService = {}, onOrder = {}, bottomBar = {},
            overview = ScheduleOverview(exhausted, next)) } }
        scrollTo("service_card_current")
        compose.onNodeWithTag("service_status_current").assertTextEquals("Возможно, уже идет")
        compose.onNodeWithTag("watch_current").assertDoesNotExist()
        scrollTo("service_card_next")
        compose.onNodeWithTag("service_start_next").assertTextEquals("8 ноября · 10:00")
        compose.onNodeWithTag("watch_next").assertDoesNotExist()
    }

    @Test fun publishedFractionalSecondsAreNotSilentlyRounded() {
        val precise = next.copy(service = next.service.copy(time = "10:00:15.500"),
            startInstant = Instant.parse("2026-11-08T07:00:15.500Z"))
        compose.setContent { GuideTheme(false) { MainScreen(null, today, onStart = {}, onResume = {}, onFindStage = {},
            church = parish, onChurch = {}, suggestion = null, onJoinService = {}, onOrder = {}, bottomBar = {},
            overview = ScheduleOverview(nextKnownService = precise)) } }
        scrollTo("service_start_next")
        compose.onNodeWithTag("service_start_next").assertTextEquals("8 ноября · 10:00:15.5")
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
    }
}
