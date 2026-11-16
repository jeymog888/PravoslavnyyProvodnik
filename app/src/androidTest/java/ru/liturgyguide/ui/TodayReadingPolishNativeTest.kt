package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.schedule.*
import java.time.Instant
import java.time.LocalDate

/** Isolated production Today rendering; publication provenance/network/book correctness are separate gates. */
@RunWith(Parameterized::class)
class TodayReadingPolishNativeTest(private val dark: Boolean, width: Int, height: Int, scale: Float) :
    FallbackReadingHarness(false, width, height, scale, dark) {
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val service = ParishService("2026-11-12", "09:00", "Часы. Литургия. Панихида", "service_block")
    private val source = "https://parish.example/schedule/"
    private val church = Parish("church", "Никольский храм", "Никольский храм", "Москва", "", "", "https://parish.example/",
        "Europe/Moscow", source, "", "2026-11-01", "2026-11-30", listOf(service), "", "")
    private val publication = SchedulePublication(church.id, church.timezone, church.website, source, now.toString(),
        listOf(InternetService(service.date, service.time, service.title, service.kind)))
    private val ready = InternetScheduleState(church.id, InternetScheduleStatus.READY, website = church.website,
        sourceUrl = source, checkedAt = now.toString(), publication = publication)

    @Test fun freshPublishedPrimaryLeavesOneCompactAlternativeAndAPlainCheckTime() = evidence("today-quiet-$dark") { report ->
        val selected = mutableListOf<Pair<LocalDate, WorshipKind>>()
        val services = mutableListOf<ParishService>()
        compose.setContent { GuideTheme(dark) {
            Box(Modifier.fillMaxSize().testTag("today_polish_host")) {
                TodayScreen(church, now, ready, null, null, onFindChurch = {}, onChurchDetails = {},
                    onOpenBook = { date, kind, _ -> selected += date to kind }, onResumeBook = {}, onRefresh = {},
                    onResumeLegacy = null, bottomBar = {}, onOpenService = { services += it })
            }
        } }
        reveal("open_service_book").assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertEquals(listOf(service), services) }
        reveal("today_other_services").assertHeightIsAtLeast(48.dp)
        labelFits("today_other_services", "Выбрать другой текст")
        compose.onNodeWithTag("start_guide").assertDoesNotExist()
        compose.onNodeWithText("Читать службу").assertDoesNotExist()
        compose.onNodeWithTag("today_schedule_checked").assertTextEquals("Проверено сегодня в 09:00:00")
        capture("compact", report)
        compose.onNodeWithTag("today_other_services").performClick()
        compose.onNodeWithTag("service_chooser_date").assertTextEquals("Тексты на 12 ноября 2026")
        reveal("service_book_choose_vespers", "service_book_chooser").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf(LocalDate.of(2026, 11, 12) to WorshipKind.VESPERS), selected) }
        report.put("compactAlternative", true).put("exactPublishedEvent", true)
    }

    @Test fun previouslyShownFallbackRetainsItsPrimarySeparateChooserAndRestoresThemAfterLatePublication() = evidence("today-retained-$dark") { report ->
        var state by mutableStateOf(InternetScheduleState(church.id, InternetScheduleStatus.NO_WEBSITE))
        val selected = mutableListOf<Pair<LocalDate, WorshipKind>>()
        val restorer = StateRestorationTester(compose)
        restorer.setContent { GuideTheme(dark) {
            val currentChurch = if (state.publication == null) church.copy(schedule = emptyList()) else church
            Box(Modifier.fillMaxSize()) {
                TodayScreen(currentChurch, now, state, null, null, onFindChurch = {}, onChurchDetails = {},
                    onOpenBook = { date, kind, _ -> selected += date to kind }, onResumeBook = {}, onRefresh = {},
                    onResumeLegacy = null, bottomBar = { GuideBottomBar("home", {}, {}, {}) },
                    onSetScheduleSource = {}, onOpenService = {})
            }
        } }
        reveal("start_guide").assertHeightIsAtLeast(56.dp)
        reveal("today_other_services").assertHeightIsAtLeast(48.dp)
        reveal("start_guide", searchBackwards = true)
        capture("before-reaching-reading", report)
        alignReadingCardWithViewport()
        val card = bounds("today_reading_card")
        val viewport = compose.onNodeWithTag("main_content").getBoundsInRoot()
        assertTrue(card.top <= viewport.top && card.bottom > viewport.top + 48.dp)
        report.put("readingCardReachedViewportTop", card.top.value).put("viewportTop", viewport.top.value)
        val anchor = NativeRevealProbe(compose, "main_content", "start_guide")
        val before = requireNotNull(anchor.bounds().targetBounds)
        compose.runOnIdle { state = ready }
        compose.waitForIdle()
        val after = requireNotNull(anchor.bounds().targetBounds)
        assertEquals(before.top.value, after.top.value, .5f)
        assertEquals(before.bottom.value, after.bottom.value, .5f)
        report.put("lateAnchorTopBefore", before.top.value).put("lateAnchorTopAfter", after.top.value)
        reveal("start_guide", searchBackwards = true).assertIsEnabled()
        compose.onNodeWithText("Читать службу").assertExists()
        restorer.emulateSavedInstanceStateRestore()
        reveal("start_guide", searchBackwards = true).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(LocalDate.of(2026, 11, 12) to WorshipKind.LITURGY), selected) }
        reveal("today_other_services").performClick()
        reveal("service_book_choose_vespers", "service_book_chooser").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 11, 12) to WorshipKind.VESPERS, selected.last()) }
        report.put("fullPrimaryRetained", true).put("separateChooserRetained", true).put("restored", true)
        capture("restored", report)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}x{2}-font{3}")
        fun profiles() = listOf(arrayOf<Any>(false, 411, 914, 1f), arrayOf<Any>(false, 320, 800, 2f), arrayOf<Any>(true, 320, 800, 2f))
    }
}
