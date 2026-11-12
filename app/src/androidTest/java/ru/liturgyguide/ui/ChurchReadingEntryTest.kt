package ru.liturgyguide.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.SchedulePublication
import ru.liturgyguide.service.schedule.InternetService
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ChurchReadingEntryTest {
    @get:Rule val compose = createComposeRule()
    private val parish = Parish("trip", "Храм в поездке", "Храм в поездке", "Москва", "Улица, 1", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")

    @Test fun aChurchWithoutPublicationOpensTheChosenDayAndKeepsAnObviousChangeAction() {
        val today = LocalDate.of(2026, 12, 31)
        val opened = mutableListOf<LocalDate>()
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(parish), parish.id, today,
            onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED },
            onOpenUrl = {}, onBack = {}, searchQuery = "", onSearchQueryChange = {},
            onReadLiturgy = { opened += it }) } }
        compose.onNodeWithTag("change_church").assertIsDisplayed().assertTextEquals("Сменить храм")
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_tomorrow"))
        compose.onNodeWithTag("schedule_tomorrow").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("typical_schedule"))
        compose.onNodeWithTag("typical_schedule").assertTextContains("не расписание храма", substring = true)
        compose.onNodeWithTag("published_schedule_empty").assertDoesNotExist()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_read_liturgy"))
        compose.onNodeWithTag("church_read_liturgy").performClick()
        compose.runOnIdle { assertEquals(listOf(LocalDate.of(2027, 1, 1)), opened) }
    }

    @Test fun inferredYearKeepsTheActualSourceOneTapAwayOnTheChurchPage() {
        val url = "https://parish.example/schedule"
        val publication = SchedulePublication(parish.id, parish.timezone, "https://parish.example", url,
            "2026-11-11T07:00:00Z", listOf(InternetService("2026-11-11", "10:00", "Литургия", "liturgy")), yearInferred = true)
        val opened = mutableListOf<String>()
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(parish), parish.id, LocalDate.of(2026, 11, 11),
            onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED }, onOpenUrl = { opened += it },
            onBack = {}, searchQuery = "", onSearchQueryChange = {}, onReadLiturgy = {},
            internetSchedule = InternetScheduleState(parish.id, InternetScheduleStatus.READY, publication = publication)) } }
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_inferred_year"))
        compose.onNodeWithTag("schedule_inferred_year").assertTextEquals("Год не указан - проверьте дату в расписании")
        compose.onNodeWithText("Проверить источник").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(url), opened) }
    }

    @Test fun loadingTheWebsiteDoesNotBlockReadingAndCannotStartADuplicateRefresh() {
        var opened = 0
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(parish), parish.id, LocalDate.of(2026, 11, 10),
            onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED },
            onOpenUrl = {}, onBack = {}, searchQuery = "", onSearchQueryChange = {}, onReadLiturgy = { opened++ },
            internetSchedule = InternetScheduleState(parish.id, loading = true), onRefreshSchedule = { error("Duplicate refresh") }) } }
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_read_liturgy"))
        compose.onNodeWithTag("church_read_liturgy").assertIsEnabled().performClick()
        openDetails()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("refresh_schedule"))
        compose.onNodeWithTag("refresh_schedule").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun missingWebsiteCanBeSuppliedExplicitlyWithoutPresentingATypicalPlanAsPublished() {
        val sites = mutableListOf<String>()
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(parish), parish.id, LocalDate.of(2026, 11, 10),
            onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED },
            onOpenUrl = {}, onBack = {}, searchQuery = "", onSearchQueryChange = {}, onReadLiturgy = {},
            internetSchedule = InternetScheduleState(parish.id, InternetScheduleStatus.NO_WEBSITE),
            onRefreshSchedule = {}, onSetScheduleSource = { sites += it }) } }
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("edit_schedule_website"))
        compose.onNodeWithTag("edit_schedule_website").assert(hasAnyAncestor(hasTestTag("church_schedule_card"))).performClick()
        compose.onNodeWithTag("schedule_source_content").performScrollToIndex(2)
        compose.onNodeWithTag("schedule_website_input").performTextInput("https://example.org/schedule/")
        compose.onNodeWithTag("schedule_source_content").performScrollToIndex(3)
        compose.onNodeWithTag("save_schedule_website").performClick()
        compose.runOnIdle { assertEquals(listOf("https://example.org/schedule/"), sites) }
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("typical_schedule"))
        compose.onNodeWithTag("typical_schedule").assertTextContains("не расписание храма", substring = true)
        compose.onNodeWithText("Расписание прихода").assertDoesNotExist()
    }

    @Test fun changeChurchOpensUnifiedSearchDirectly() {
        var discoveries = 0
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(parish), parish.id, LocalDate.of(2026, 11, 10),
            onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED },
            onOpenUrl = {}, onBack = {}, searchQuery = "", onSearchQueryChange = {},
            onDiscover = { discoveries++ }, onReadLiturgy = {}) } }
        compose.onNodeWithTag("change_church").assertIsDisplayed().performClick()
        compose.onNodeWithTag("church_picker").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, discoveries) }
    }

    @Test fun readingActionNamesTheAvailableBookAndPreservesTheActualScheduleEntry() {
        val date = LocalDate.of(2026, 11, 10)
        val morning = ParishService(date.toString(), "08:00", "Часы, литургия", "service_block")
        val evening = ParishService(date.toString(), "17:00", "Вечерня", "service_block")
        val scheduled = parish.copy(scheduleValidFrom = date.toString(), scheduleValidUntil = date.toString(),
            schedule = listOf(morning, evening))
        val opened = mutableListOf<ParishService>()
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(scheduled), scheduled.id, date,
            onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED },
            onOpenUrl = {}, onBack = {}, searchQuery = "", onSearchQueryChange = {}, onReadLiturgy = {},
            onOpenService = { opened += it }, now = Instant.parse("2026-11-10T05:30:00Z")) } }
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_open_service"))
        compose.onNodeWithTag("church_open_service").assertTextEquals("Открыть литургию").performClick()
        compose.runOnIdle { assertEquals(listOf(morning), opened) }
        compose.onNodeWithTag("schedule_source").assertDoesNotExist()
        compose.onNodeWithTag("church_content").performScrollToNode(hasText("Еще в этот день"))
        compose.onNodeWithText("Еще в этот день").assertIsDisplayed()
    }

    @Test fun personalStartOpensItsExactTomorrowDateInsteadOfReturningToToday() {
        val today = LocalDate.of(2026, 12, 31)
        val tomorrow = LocalDate.of(2027, 1, 1)
        val opened = mutableListOf<LocalDate>()
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(parish), parish.id, today,
            onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED },
            onOpenUrl = {}, onBack = {}, searchQuery = "", onSearchQueryChange = {},
            userStarts = listOf(UserServiceStart(parish.id, tomorrow.toString(), "09:30")),
            onReadLiturgy = { opened += it }, onGoToService = { error("An undated Home route would lose the chosen service") }) } }
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_tomorrow"))
        compose.onNodeWithTag("schedule_tomorrow").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("personal_start_summary"))
        compose.onNodeWithTag("personal_start_summary").assertTextEquals("09:30 · Литургия")
        compose.onNodeWithTag("personal_start_source").assertTextContains("Начало указано вами", substring = true)
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("personal_time_to_service"))
        compose.onNodeWithTag("personal_time_to_service").assertTextEquals("Открыть литургию").performClick()
        compose.runOnIdle { assertEquals(listOf(tomorrow), opened) }
    }

    @Test fun afterAllStartsTheChurchPageShowsTheLastStartAsPastAndKeepsReadingAvailable() {
        val today = LocalDate.of(2026, 11, 11)
        val morning = ParishService(today.toString(), "08:00", "Литургия", "service_block")
        val evening = ParishService(today.toString(), "17:00", "Вечерня", "service_block")
        val scheduled = parish.copy(scheduleValidFrom = today.toString(), scheduleValidUntil = today.toString(),
            schedule = listOf(morning, evening))
        val opened = mutableListOf<ParishService>()
        compose.setContent { GuideTheme(false) { ChurchScreen(listOf(scheduled), scheduled.id, today,
            onSelect = { _, selected -> selected(); ChurchSelectionResult.SELECTED }, onOpenUrl = {}, onBack = {},
            searchQuery = "", onSearchQueryChange = {}, onReadLiturgy = {}, onOpenService = { opened += it },
            now = Instant.parse("2026-11-11T19:00:00Z")) } }
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_service_time"))
        compose.onNodeWithTag("church_service_time").assertTextEquals("17:00")
        compose.onNodeWithTag("church_service_relation").assertTextEquals("Начало уже прошло")
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_open_service"))
        compose.onNodeWithTag("church_open_service").performClick()
        compose.runOnIdle { assertEquals(listOf(evening), opened) }
    }

    private fun openDetails() {
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_details"))
        compose.onNodeWithTag("church_details").performClick()
    }

}
