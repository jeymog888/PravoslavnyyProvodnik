package ru.liturgyguide.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.InternetService
import ru.liturgyguide.service.schedule.SchedulePublication
import java.time.Instant
import java.time.LocalDate

@RunWith(Parameterized::class)
class UpcomingScheduleOfferTest(private val churchDetails: Boolean) {
    @get:Rule val compose = createComposeRule()

    @Test fun emptySelectedDayKeepsItsDateWhileTheSeparateOfferOpensTheOriginalFutureOccurrence() {
        val date = LocalDate.of(2026, 11, 12)
        val now = Instant.parse("2026-11-12T03:00:00Z")
        val church = Parish("future", "Храм в поездке", "Храм в поездке", "Москва", "Улица, 1", "",
            "https://parish.example", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
        val service = ParishService("2026-11-13", "18:00",
            "Вечернее богослужение. Акафист Пресвятой Богородице", "service_block")
        val publication = SchedulePublication(church.id, church.timezone, church.website,
            "https://parish.example/schedule", "2026-11-08T03:00:00Z",
            listOf(InternetService(service.date, service.time, service.title, service.kind)), yearInferred = true)
        val state = mutableStateOf(InternetScheduleState(church.id, InternetScheduleStatus.UNAVAILABLE,
            sourceUrl = publication.sourceUrl, publication = publication))
        val opened = mutableListOf<ParishService>()
        val container = if (churchDetails) "church_content" else "main_content"
        val prefix = if (churchDetails) "church" else "today"
        val selectedDate = if (churchDetails) "schedule_date" else "today_service_date"
        val selectedChip = if (churchDetails) "schedule_today" else "today_day_today"
        val expectedDate = if (churchDetails) "12 ноября 2026, четверг" else "12 ноября"
        compose.setContent { GuideTheme(false) {
            if (churchDetails) ChurchScreen(listOf(church), church.id, date,
                onSelect = { _, _ -> error("The offer must not change the parish") }, onOpenUrl = {}, onBack = {},
                internetSchedule = state.value, searchQuery = "", onSearchQueryChange = {}, now = now,
                onOpenService = { opened += it })
            else TodayScreen(church, now, state.value, null, null,
                onFindChurch = {}, onChurchDetails = {}, onOpenBook = { _, _, _ -> error("Use the original occurrence") },
                onResumeBook = {}, onRefresh = {}, onResumeLegacy = null, bottomBar = {},
                onOpenService = { opened += it })
        } }
        fun scroll(tag: String): SemanticsNodeInteraction {
            compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
            return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        scroll(selectedChip).assertIsSelected()
        scroll(selectedDate).assertTextEquals(expectedDate)
        scroll("${prefix}_schedule_empty").assertTextEquals("Нет записей на эту дату")
        scroll("${prefix}_next_date_time").assertTextEquals("13 ноября 2026 · 18:00")
        scroll("${prefix}_next_title").assertTextEquals(service.title)
        scroll("${prefix}_next_saved").assertTextEquals("Сохраненное расписание - проверьте изменения")
        scroll("${prefix}_next_inferred_year").assertTextEquals("Год не указан - проверьте дату в расписании")
        scroll("${prefix}_open_next_service").assertTextEquals("Открыть службу").performClick()
        compose.runOnIdle { assertEquals(listOf(service), opened) }
        scroll(selectedDate).assertTextEquals(expectedDate)
        scroll(selectedChip).assertIsSelected()

        // Unknown kinds also reach the navigation owner with their real civil date/title.
        val unknown = service.copy(title = "Панихида", kind = "other")
        compose.runOnIdle {
            state.value = state.value.copy(publication = publication.copy(yearInferred = false,
                events = listOf(InternetService(unknown.date, unknown.time, unknown.title, unknown.kind))))
        }
        scroll("${prefix}_open_next_service").assertTextEquals("Выбрать текст службы").performClick()
        compose.runOnIdle { assertEquals(listOf(service, unknown), opened) }
        compose.onNodeWithTag("${prefix}_next_inferred_year").assertDoesNotExist()
        scroll(selectedDate).assertTextEquals(expectedDate)
        scroll(selectedChip).assertIsSelected()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "churchDetails={0}")
        fun screens() = listOf(arrayOf(false), arrayOf(true))
    }
}
