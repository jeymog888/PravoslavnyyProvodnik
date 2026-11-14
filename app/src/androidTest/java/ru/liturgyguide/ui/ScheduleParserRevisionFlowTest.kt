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
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.InternetService
import ru.liturgyguide.service.schedule.SchedulePublication
import ru.liturgyguide.service.schedule.withInternetSchedule
import java.time.Instant
import java.time.LocalDate

/** Controlled DTO transitions in real Compose screens; not a network or process-upgrade test. */
@RunWith(Parameterized::class)
class ScheduleParserRevisionFlowTest(private val churchDetails: Boolean, private val dark: Boolean) {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 11, 12)
    private val now = Instant.parse("2026-11-12T03:00:00Z")
    private val source = "https://parish.example/schedule/"
    private val church = Parish("revision-flow", "Храм святителя Николая", "Никольский храм", "Москва", "Улица, 1", "",
        "https://parish.example/", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val container get() = if (churchDetails) "church_content" else "main_content"
    private val prefix get() = if (churchDetails) "church" else "today"
    private val serviceTime get() = if (churchDetails) "church_service_time" else "today_service_time"
    private val refreshTag get() = if (churchDetails) "refresh_schedule" else "today_refresh_schedule"
    private val receiptTag get() = if (churchDetails) "church_schedule_checked" else "today_schedule_checked"
    private val todayChip get() = if (churchDetails) "schedule_today" else "today_day_today"
    private val tomorrowChip get() = if (churchDetails) "schedule_tomorrow" else "today_day_tomorrow"
    private fun event(day: LocalDate, time: String) = InternetService(day.toString(), time, "Божественная Литургия", "liturgy")
    private fun state(revision: Int) = InternetScheduleState(church.id, InternetScheduleStatus.READY,
        website = church.website, sourceUrl = source, checkedAt = now.toString(),
        publication = SchedulePublication(church.id, church.timezone, church.website, source, now.toString(),
            listOf(event(date, "08:00"), event(date.plusDays(1), "10:00"), event(date.plusDays(2), "12:00")),
            parserRevision = revision))

    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun assertNoTimedOffer() {
        // Mount the complete schedule card before checking absent children in its semantics.
        scroll("${prefix}_schedule_card")
        compose.onNodeWithTag(serviceTime).assertDoesNotExist()
        compose.onNodeWithTag("${prefix}_next_service").assertDoesNotExist()
        compose.onNodeWithTag(if (churchDetails) "church_open_service" else "open_service_book").assertDoesNotExist()
    }

    @Test fun oldRevisionStaysUntimedThroughLoadingAndFailureThenFreshAndCurrentSavedRowsReturn() {
        val old = state(SchedulePublication.LEGACY_PARSER_REVISION)
        val own = mutableStateOf(old)
        val opened = mutableListOf<ParishService>()
        val websites = mutableListOf<String>()
        var refreshes = 0
        val refresh = { refreshes++; own.value = own.value.copy(loading = true) }
        compose.setContent { GuideTheme(dark) {
            // This is the actual Main projection input invariant: start from the base parish each time.
            val projected = church.withInternetSchedule(own.value, now)
            if (churchDetails) ChurchScreen(listOf(projected), church.id, date,
                onSelect = { _, _ -> error("Refresh must not change the parish") }, onOpenUrl = { websites += it }, onBack = {},
                internetSchedule = own.value, onRefreshSchedule = refresh,
                searchQuery = "", onSearchQueryChange = {}, now = now, onOpenService = { opened += it })
            else TodayScreen(projected, now, own.value, null, null,
                onFindChurch = {}, onChurchDetails = {}, onOpenBook = { _, _, _ -> error("Use the published occurrence") },
                onResumeBook = {}, onRefresh = refresh, onResumeLegacy = null, bottomBar = {},
                onOpenScheduleSource = { websites += source }, onOpenService = { opened += it })
        } }
        assertNoTimedOffer()
        scroll("${prefix}_schedule_empty").assertTextEquals("Обновите расписание")
        scroll(receiptTag).assertTextEquals("Обновите расписание\nПопытка обновления: 12 ноября 2026, 06:00:00")
        if (churchDetails) scroll("schedule_network_status").assertTextEquals("Обновите расписание")
        scroll(tomorrowChip).performClick()
        assertNoTimedOffer()
        scroll("${prefix}_schedule_empty").assertTextEquals("Обновите расписание")
        scroll(todayChip).performClick()
        scroll(if (churchDetails) "open_schedule_website" else "today_schedule_source").performClick()
        compose.runOnIdle { assertEquals(listOf(source), websites) }
        scroll(refreshTag).assertIsEnabled().performClick()
        assertNoTimedOffer()
        scroll(if (churchDetails) "schedule_loading_summary" else "today_schedule_loading")
        compose.onNodeWithTag(receiptTag).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(old.publication, own.value.publication)
            own.value = own.value.copy(status = InternetScheduleStatus.UNAVAILABLE, loading = false,
                checkedAt = now.plusSeconds(1).toString())
        }
        assertNoTimedOffer()
        scroll("${prefix}_schedule_empty").assertTextEquals("Обновите расписание")
        scroll(receiptTag).assertTextEquals("Обновление не удалось\nПопытка обновления: 12 ноября 2026, 06:00:01")
        if (churchDetails) scroll("schedule_network_status").assertTextEquals("Не удалось обновить расписание")
        scroll(refreshTag).assertIsEnabled()
        compose.runOnIdle {
            own.value = state(SchedulePublication.CURRENT_PARSER_REVISION).copy(publication = old.publication!!.copy(
                parserRevision = SchedulePublication.CURRENT_PARSER_REVISION,
                events = listOf(event(date, "09:30"), event(date.plusDays(1), "11:30"), event(date.plusDays(2), "17:00"))))
        }
        scroll(serviceTime).assertTextEquals("09:30")
        scroll(if (churchDetails) "church_open_service" else "open_service_book").performClick()
        compose.runOnIdle { assertEquals(listOf(ParishService(date.toString(), "09:30", "Божественная Литургия", "liturgy")), opened) }
        scroll(tomorrowChip).performClick()
        scroll(serviceTime).assertTextEquals("11:30")
        compose.runOnIdle {
            own.value = own.value.copy(status = InternetScheduleStatus.UNAVAILABLE,
                publication = own.value.publication!!.copy(fetchedAt = now.minusSeconds(48 * 3_600L).toString()))
        }
        scroll(serviceTime).assertTextEquals("11:30")
        scroll("${prefix}_service_relation").assertTextEquals("Сохраненное расписание - проверьте изменения")
        compose.runOnIdle {
            own.value = own.value.copy(publication = own.value.publication!!.copy(events = listOf(event(date.plusDays(2), "17:00"))))
        }
        scroll("${prefix}_next_date_time").assertTextEquals("14 ноября 2026 · 17:00")
        scroll("${prefix}_next_saved").assertTextEquals("Сохраненное расписание - проверьте изменения")
        scroll("${prefix}_open_next_service").performClick()
        compose.runOnIdle { assertEquals(date.plusDays(2).toString(), opened.last().date); assertEquals("17:00", opened.last().time) }
    }

    @Test fun noncurrentCacheDoesNotSuppressIndependentBundledOrExplicitPersonalStarts() {
        val old = state(SchedulePublication.LEGACY_PARSER_REVISION)
        val bundled = church.copy(scheduleValidFrom = date.toString(), scheduleValidUntil = date.plusDays(1).toString(),
            schedule = listOf(ParishService(date.toString(), "10:15", "Божественная Литургия", "liturgy")))
        val personal = listOf(UserServiceStart(church.id, date.toString(), "06:30"))
        compose.setContent { GuideTheme(dark) {
            val projected = bundled.withInternetSchedule(old, now)
            if (churchDetails) ChurchScreen(listOf(projected), church.id, date,
                onSelect = { _, _ -> error("The cache must not change the parish") }, onOpenUrl = {}, onBack = {},
                internetSchedule = old, onRefreshSchedule = {}, searchQuery = "", onSearchQueryChange = {}, now = now,
                userStarts = personal, onReadServiceText = { _, _ -> }, onOpenService = {})
            else TodayScreen(projected, now, old, null, null,
                onFindChurch = {}, onChurchDetails = {}, onOpenBook = { _, _, _ -> }, onResumeBook = {},
                onRefresh = {}, onResumeLegacy = null, bottomBar = {}, userStarts = personal, onOpenService = {})
        } }
        scroll(serviceTime).assertTextEquals("10:15")
        scroll(if (churchDetails) "personal_start_summary" else "today_personal_summary")
            .assertTextEquals(if (churchDetails) "06:30 · Литургия" else "12 ноября · 06:30 · Литургия")
        compose.onNodeWithTag("${prefix}_next_service").assertDoesNotExist()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "churchDetails={0} dark={1}")
        fun screens() = listOf(false, true).flatMap { details -> listOf(false, true).map { dark -> arrayOf<Any>(details, dark) } }
    }
}
