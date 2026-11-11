package ru.liturgyguide.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.book.BookReadingPosition
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.ScheduleImportPhase
import ru.liturgyguide.service.schedule.ScheduleImportProgress
import ru.liturgyguide.service.schedule.SchedulePublication
import ru.liturgyguide.service.schedule.InternetService
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val parish = Parish("trip", "Храм в поездке", "Храм в поездке", "Москва", "Улица, 1", "", "",
        "Europe/Moscow", "", "", "2026-11-01", "2026-11-30", emptyList(), "", "")
    private data class Request(val date: LocalDate, val kind: WorshipKind, val eveningMatins: Boolean = false)

    @Test fun anUnknownTomorrowServiceIsOnlyShownAfterSelectingTomorrow() {
        val future = service("2026-11-12", "09:00", "Панихида")
        val opened = mutableListOf<Request>()
        launch(withServices(future), opened = opened)
        scrollTo("today_service_date").assertTextEquals("11 ноября")
        scrollTo("today_day_today").assertIsSelected()
        scrollTo("today_schedule_empty").assertTextEquals("Нет записей на эту дату")
        compose.onNodeWithTag("today_choose_unknown_service").assertDoesNotExist()
        scrollTo("today_day_tomorrow").performClick()
        scrollTo("today_service_date").assertTextEquals("12 ноября")
        scrollTo("today_choose_unknown_service").performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 12 ноября 2026")
        choose(WorshipKind.LITURGY)
        compose.onNodeWithTag("service_book_chooser").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(Request(LocalDate.of(2026, 11, 12), WorshipKind.LITURGY)), opened) }
    }

    @Test fun libraryKeepsTheChosenDayEvenWhenOnlyALaterServiceIsPublished() {
        val future = service("2026-11-14", "17:00", "Всенощное бдение")
        val opened = mutableListOf<Request>()
        launch(withServices(future), opened = opened)
        scrollTo("today_service_date").assertTextEquals("11 ноября")
        scrollTo("today_other_services").performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 11 ноября 2026")
        choose(WorshipKind.VESPERS)
        // Today passes the civil date; the navigation owner performs the evening liturgical-day conversion once.
        compose.runOnIdle { assertEquals(listOf(Request(LocalDate.of(2026, 11, 11), WorshipKind.VESPERS)), opened) }
    }

    @Test fun todayAndTomorrowFollowTheParishTimeZoneAcrossTheYearBoundary() {
        val trip = withServices(service("2027-01-01", "09:00", "Литургия"), service("2027-01-02", "08:00", "Литургия"))
            .copy(timezone = "Asia/Vladivostok", city = "Владивосток")
        val opened = mutableListOf<Request>()
        // Moscow and UTC still have December 31, but the parish already has January 1.
        launch(trip, Instant.parse("2026-12-31T15:00:00Z"), opened = opened)
        scrollTo("today_service_date").assertTextEquals("1 января")
        scrollTo("today_service_relation").assertTextEquals("Ближайшая служба по расписанию")
        scrollTo("open_service_book").performClick()
        scrollTo("today_day_tomorrow").performClick().assertIsSelected()
        scrollTo("today_service_date").assertTextEquals("2 января")
        scrollTo("open_service_book").performClick()
        scrollTo("today_day_today").performClick().assertIsSelected()
        scrollTo("today_service_date").assertTextEquals("1 января")
        compose.runOnIdle {
            assertEquals(listOf(Request(LocalDate.of(2027, 1, 1), WorshipKind.LITURGY),
                Request(LocalDate.of(2027, 1, 2), WorshipKind.LITURGY)), opened)
        }
    }

    @Test fun aRecentlyStartedServiceIsAnEstimateAndKeepsItsOwnDateAndKind() {
        val morning = service("2026-11-11", "08:00", "Часы, литургия")
        val later = service("2026-11-11", "10:00", "Поздняя литургия")
        val opened = mutableListOf<Request>()
        launch(withServices(morning, later), Instant.parse("2026-11-11T05:30:00Z"), opened = opened)
        scrollTo("today_service_time").assertTextEquals("08:00")
        scrollTo("today_service_title").assertTextEquals(morning.title)
        scrollTo("today_service_relation").assertTextEquals("Возможно, идет · по времени начала")
        scrollTo("open_service_book").assertTextEquals("Открыть литургию").performClick()
        compose.runOnIdle { assertEquals(listOf(Request(LocalDate.of(2026, 11, 11), WorshipKind.LITURGY)), opened) }
    }

    @Test fun emptyScheduleAndRefreshNeverBlockReadingOrOfferADuplicateRequest() {
        val state = mutableStateOf(InternetScheduleState(parish.id, InternetScheduleStatus.UNAVAILABLE))
        var refreshes = 0
        val opened = mutableListOf<Request>()
        launch(parish, opened = opened, schedule = { state.value },
            progress = ScheduleImportProgress(parish.id, ScheduleImportPhase.FINDING_SITE), onRefresh = {
                refreshes++
                state.value = state.value.copy(loading = true)
            })
        scrollTo("typical_schedule").assertTextContains("типовой порядок", substring = true)
        compose.onNodeWithTag("open_service_book").assertDoesNotExist()
        scrollTo("today_refresh_schedule").performClick()
        scrollTo("today_schedule_loading").assertTextEquals("Находим сайт храма")
        compose.onNodeWithTag("today_refresh_schedule").assertDoesNotExist()
        scrollTo("start_guide").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(listOf(Request(LocalDate.of(2026, 11, 11), WorshipKind.LITURGY)), opened)
            state.value = state.value.copy(loading = false)
        }
        scrollTo("today_refresh_schedule").assertIsEnabled()
    }

    @Test fun firstVisitCanFindAChurchOrReadWithoutOne() {
        var discoveries = 0
        val opened = mutableListOf<Request>()
        launch(null, opened = opened, onFindChurch = { discoveries++ })
        scrollTo("open_church").performClick()
        scrollTo("today_choose_without_church").performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 11 ноября 2026")
        choose(WorshipKind.LITURGY)
        compose.runOnIdle {
            assertEquals(1, discoveries)
            assertEquals(listOf(Request(LocalDate.of(2026, 11, 11), WorshipKind.LITURGY)), opened)
        }
    }

    @Test fun theLastChoiceRemainsReachableAtTwoHundredPercentText() {
        val opened = mutableListOf<Request>()
        launch(parish, opened = opened, fontScale = 2f)
        scrollTo("today_other_services").performClick()
        compose.onNodeWithTag("service_book_chooser")
            .performScrollToNode(hasTestTag("service_book_choose_matins"))
        compose.onNodeWithTag("service_book_choose_matins").assertIsDisplayed().performClick()
        compose.onNodeWithTag("service_book_chooser").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(Request(LocalDate.of(2026, 11, 11), WorshipKind.MATINS)), opened) }
    }

    @Test fun todaysScheduleAndSavedReadingStayReachableAtTwoHundredPercentWithALongChurchName() {
        val selected = withServices(service("2026-11-11", "17:00", "Вечерня"), service("2026-11-12", "09:00", "Литургия"))
            .copy(shortName = "Храм святых равноапостольных Кирилла и Мефодия при большом историческом монастыре",
                city = "Длинное название города", address = "Очень длинное название улицы, дом 123, строение 45")
        val position = BookReadingPosition(ServiceBookKey("2026-11-10"), "a".repeat(64), selected.id, "prayer-17", "gospel", fraction = 0.4f)
        val resumed = mutableListOf<BookReadingPosition>()
        val opened = mutableListOf<Request>()
        launch(selected, opened = opened, fontScale = 2f, lastBook = position, onResumeBook = { resumed += it })
        // Today's service takes precedence; resuming an older book is an independent action below it.
        scrollTo("today_service_date").assertTextEquals("11 ноября")
        scrollTo("open_service_book").assertIsEnabled().performClick()
        scrollTo("resume_book").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(position), resumed) }
        scrollTo("today_day_tomorrow").performClick()
        scrollTo("today_service_date").assertTextEquals("12 ноября")
        scrollTo("open_service_book").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Request(LocalDate.of(2026, 11, 11), WorshipKind.VESPERS),
                Request(LocalDate.of(2026, 11, 12), WorshipKind.LITURGY)), opened)
        }
    }

    @Test fun readingWithoutASelectedChurchStillOffersTheSavedBookAfterTheIntroduction() {
        val position = BookReadingPosition(ServiceBookKey("2026-11-10"), "b".repeat(64), null, "prayer-3", "beginning")
        val resumed = mutableListOf<BookReadingPosition>()
        launch(null, lastBook = position, onResumeBook = { resumed += it })
        scrollTo("resume_book").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(position), resumed) }
        scrollTo("open_church").assertIsEnabled()
    }

    @Test fun tomorrowsPersonalStartKeepsItsSourceAndDateWithoutReplacingPublishedTime() {
        val tomorrow = LocalDate.of(2026, 11, 12)
        val published = service(tomorrow.toString(), "09:00", "Литургия")
        val displayedParish = mutableStateOf(withServices(published))
        val starts = listOf(UserServiceStart(parish.id, tomorrow.toString(), "10:30"),
            UserServiceStart("another-parish", "2026-11-11", "12:00"))
        val opened = mutableListOf<Request>()
        compose.setContent { GuideTheme(false) {
            TodayScreen(displayedParish.value, now, null, null, lastBook = null,
                onFindChurch = {}, onChurchDetails = {},
                onOpenBook = { date, kind, evening -> opened += Request(date, kind, evening) },
                onResumeBook = {}, onRefresh = {}, onResumeLegacy = null, bottomBar = {}, userStarts = starts)
        } }
        scrollTo("today_day_today").assertIsSelected()
        compose.onNodeWithTag("today_personal_source").assertDoesNotExist()
        scrollTo("today_day_tomorrow").performClick()
        scrollTo("today_service_time").assertTextEquals("09:00")
        scrollTo("today_service_relation").assertTextEquals("Ближайшая служба по расписанию")
        scrollTo("today_open_personal_liturgy")
        compose.onNodeWithTag("today_personal_summary", useUnmergedTree = true).assertTextEquals("12 ноября · 10:30 · Литургия")
        compose.onNodeWithTag("today_personal_source", useUnmergedTree = true).assertTextEquals("Начало указано вами")
        scrollTo("today_open_personal_liturgy").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Request(tomorrow, WorshipKind.LITURGY)), opened)
            displayedParish.value = parish
        }
        // With no published schedule, the personal time stays usable and is still not called published.
        scrollTo("today_service_time").assertTextEquals("10:30")
        scrollTo("today_personal_source").assertTextEquals("Начало указано вами")
        compose.onNodeWithTag("today_service_relation").assertDoesNotExist()
        compose.onNodeWithTag("open_service_book").assertDoesNotExist()
        scrollTo("today_open_personal_liturgy").performClick()
        compose.runOnIdle { assertEquals(listOf(Request(tomorrow, WorshipKind.LITURGY), Request(tomorrow, WorshipKind.LITURGY)), opened) }
    }

    @Test fun inferredYearIsDisclosedBeforeOpeningAndNeverClaimsTheServiceIsUnderway() {
        val url = "https://parish.example/schedule"
        val selected = withServices(service("2026-11-11", "12:30", "Литургия")).copy(sourceUrl = url)
        val publication = SchedulePublication(selected.id, selected.timezone, "https://parish.example", url,
            now.toString(), listOf(InternetService("2026-11-11", "12:30", "Литургия", "liturgy")), yearInferred = true)
        val opened = mutableListOf<Request>()
        var sourceOpens = 0
        compose.setContent { GuideTheme(false) {
            TodayScreen(selected, now, InternetScheduleState(selected.id, InternetScheduleStatus.READY, publication = publication),
                null, lastBook = null, onFindChurch = {}, onChurchDetails = {},
                onOpenBook = { date, kind, evening -> opened += Request(date, kind, evening) },
                onResumeBook = {}, onRefresh = {}, onResumeLegacy = null, bottomBar = {},
                onOpenScheduleSource = { sourceOpens++ })
        } }
        scrollTo("schedule_inferred_year").assertTextEquals("Год не указан - проверьте дату в расписании")
        scrollTo("today_service_relation").assertTextEquals("Время из расписания храма")
        compose.onNodeWithText("Проверить источник").performScrollTo().performClick()
        scrollTo("open_service_book").performClick()
        compose.runOnIdle {
            assertEquals(1, sourceOpens)
            assertEquals(listOf(Request(LocalDate.of(2026, 11, 11), WorshipKind.LITURGY)), opened)
        }
    }

    @Test fun pastStartsStayOnTodayWithAnExplicitStatusInsteadOfJumpingToTomorrow() {
        val opened = mutableListOf<Request>()
        launch(withServices(service("2026-11-11", "08:00", "Литургия"), service("2026-11-11", "17:00", "Вечерня"),
            service("2026-11-12", "08:00", "Литургия")), Instant.parse("2026-11-11T19:00:00Z"), opened)
        scrollTo("today_service_date").assertTextEquals("11 ноября")
        scrollTo("today_service_time").assertTextEquals("17:00")
        scrollTo("today_service_relation").assertTextEquals("Начало уже прошло")
        scrollTo("open_service_book").performClick()
        compose.runOnIdle { assertEquals(listOf(Request(LocalDate.of(2026, 11, 11), WorshipKind.VESPERS)), opened) }
    }

    @Test fun typicalOrderIsCollapsedAndCannotBeMistakenForTodaysPublishedSchedule() {
        launch(parish)
        scrollTo("today_schedule_empty").assertTextEquals("Расписание пока не найдено")
        scrollTo("typical_schedule").assertTextContains("не расписание храма", substring = true)
        compose.onNodeWithText("Суббота вечером").assertDoesNotExist()
        scrollTo("typical_schedule").performClick()
        compose.onNodeWithText("Суббота вечером").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Воскресенье утром").performScrollTo().assertIsDisplayed()
        scrollTo("typical_schedule").performClick()
        compose.onNodeWithText("Суббота вечером").assertDoesNotExist()
    }

    private fun launch(church: Parish?, instant: Instant = now, opened: MutableList<Request> = mutableListOf(),
        schedule: () -> InternetScheduleState? = { null }, progress: ScheduleImportProgress? = null,
        onRefresh: () -> Unit = {}, onFindChurch: () -> Unit = {}, fontScale: Float = 1f,
        lastBook: BookReadingPosition? = null, onResumeBook: (BookReadingPosition) -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GuideTheme(false) {
                    TodayScreen(church, instant, schedule(), progress, lastBook = lastBook,
                        onFindChurch = onFindChurch, onChurchDetails = {},
                        onOpenBook = { date, kind, evening -> opened += Request(date, kind, evening) },
                        onResumeBook = onResumeBook, onRefresh = onRefresh, onResumeLegacy = null, bottomBar = {})
                }
            }
        }
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun choose(kind: WorshipKind) {
        val tag = "service_book_choose_${kind.name.lowercase(java.util.Locale.ROOT)}"
        compose.onNodeWithTag("service_book_chooser").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun service(date: String, time: String, title: String) = ParishService(date, time, title, "service_block")
    private fun withServices(vararg services: ParishService) = parish.copy(schedule = services.toList(),
        scheduleValidFrom = services.minOf { it.date }, scheduleValidUntil = services.maxOf { it.date })
}
