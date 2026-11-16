package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.schedule.*
import java.time.Instant

/** Production UI with isolated saved schedule states; not a source discovery or live network claim. */
@RunWith(Parameterized::class)
class ScheduleAgendaNativeTest(private val dark: Boolean, width: Int, height: Int, scale: Float) :
    FallbackReadingHarness(true, width, height, scale, dark) {
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val first = ParishService("2026-11-12", "09:00", "Часы. Литургия. Панихида", "service_block")
    private val future = ParishService("2026-11-15", "18:00", "Всенощное бдение. Исповедь", "service_block")

    @Test fun churchAgendaGroupsDatesAndReturnsWithoutChangingDateOrScroll() = evidence("agenda-church-$dark") { report ->
        val base = publishedState(first, now)
        val events = listOf(first, future).map { InternetService(it.date, it.time, it.title, it.kind) }
        fixture = FallbackFixture(now = now, state = base.copy(status = InternetScheduleStatus.UNAVAILABLE,
            publication = base.publication!!.copy(events = events, yearInferred = true, knownPartial = true)))
        launch()
        reveal(tomorrowTag).performClick()
        reveal("church_agenda_open").assertHeightIsAtLeast(48.dp)
        labelFits("church_agenda_open", "Службы по датам")
        fun scrollPosition(): Float {
            val node = compose.onAllNodesWithTag(contentTag).fetchSemanticsNodes().single()
            return compose.runOnUiThread { node.config[SemanticsProperties.VerticalScrollAxisRange].value() }
        }
        val before = scrollPosition()
        compose.onNodeWithTag("church_agenda_open").performClick()
        compose.onNodeWithTag("schedule_agenda").assertExists()
        assertSheetSystemBars()
        compose.onNode(hasText("Год не указан - проверьте дату в расписании", substring = false) and hasAnyAncestor(hasTestTag("schedule_agenda"))).assertExists()
        compose.onNode(hasText("Не все записи удалось прочитать", substring = false) and hasAnyAncestor(hasTestTag("schedule_agenda"))).assertExists()
        compose.onNode(hasText("Сохраненное расписание - проверьте изменения", substring = false) and hasAnyAncestor(hasTestTag("schedule_agenda"))).assertExists()
        reveal("schedule_agenda_source", "schedule_agenda_list").assertHeightIsAtLeast(48.dp)
        labelFits("schedule_agenda_source", "Открыть источник расписания")
        capture("source", report)
        compose.onNodeWithTag("schedule_agenda_close").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("schedule_agenda").assertDoesNotExist()
        assertEquals(before, scrollPosition(), .01f)
        compose.onNodeWithTag("schedule_date").assertTextEquals("13 ноября 2026, пятница")
        compose.onNodeWithTag("church_agenda_open").performClick()
        reveal(agendaServiceTag(future), "schedule_agenda_list").assertHeightIsAtLeast(48.dp)
        compose.onNode(hasText(future.title, substring = false) and hasAnyAncestor(hasTestTag("schedule_agenda"))).assertExists()
        capture("future", report)
        compose.onNodeWithTag(agendaServiceTag(future)).performClick()
        compose.runOnIdle { assertEquals(listOf(future), calls.services); assertTrue(calls.books.isEmpty()) }
        compose.onNodeWithTag("schedule_agenda").assertDoesNotExist()
        compose.onNodeWithTag("schedule_date").assertTextEquals("13 ноября 2026, пятница")
        report.put("exactFutureCallback", true).put("selectedDateUnchanged", true).put("scrollUnchanged", true)
    }

    @Test fun sourceUsesCurrentCallbackWithoutExpiringItAndRevokedEditionCannotOpenAnything() = evidence("agenda-authority-$dark") { report ->
        val church = Parish("church", "Никольский храм", "Никольский храм", "Москва", "", "", "https://parish.example/",
            "Europe/Moscow", "https://parish.example/schedule/", "", "", "", emptyList(), "", "")
        val publication = SchedulePublication(church.id, church.timezone, church.website, church.sourceUrl, now.toString(),
            listOf(InternetService(first.date, first.time, first.title, first.kind)))
        var state by mutableStateOf(InternetScheduleState(church.id, InternetScheduleStatus.READY,
            sourceUrl = church.sourceUrl, publication = publication))
        var interactions = 0
        var callbackOwner by mutableIntStateOf(0)
        var opens = 0
        val urls = mutableListOf<String>()
        compose.setContent { GuideTheme(dark) {
            val capturedOwner = callbackOwner
            Column(Modifier.fillMaxSize()) {
                ScheduleAgendaEntry(church, state, now, onOpenService = { opens++ }, onOpenUrl = {
                    if (capturedOwner == callbackOwner) urls += it
                }, onInteraction = { interactions++; callbackOwner++; true })
            }
        } }
        compose.onNodeWithTag("church_agenda_open").performClick()
        compose.waitForIdle()
        assertSheetSystemBars()
        val openedInteractions = interactions
        reveal("schedule_agenda_source", "schedule_agenda_list").performClick()
        compose.runOnIdle { assertEquals(listOf(church.sourceUrl), urls); assertEquals(openedInteractions, interactions) }
        compose.runOnIdle { state = state.copy(publicationRejected = true) }
        compose.onNodeWithTag("schedule_agenda_changed").assertExists()
        reveal(agendaServiceTag(first), "schedule_agenda_list").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, opens); assertEquals(1, urls.size) }
        capture("revoked", report)
        compose.onNodeWithTag("schedule_agenda_close").performClick()
        compose.onNodeWithTag("church_agenda_open").assertDoesNotExist()
        report.put("sourceCallbackOwnerPreserved", true).put("revokedActionsDisabled", true)
    }

    private fun assertSheetSystemBars() {
        compose.waitForIdle()
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check { view, missing ->
                if (missing != null) throw missing
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val controller = requireNotNull(requireNotNull(view).windowInsetsController)
                    val appearance = controller.systemBarsAppearance
                    assertEquals("Status icons must match the actual selected sheet theme", !dark,
                        (appearance and android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0)
                    assertEquals("Navigation icons must match the actual selected sheet theme", !dark,
                        (appearance and android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) != 0)
                } else error("This selected native window check requires the owned API35 emulator")
            }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}x{2}-font{3}")
        fun profiles() = listOf(arrayOf<Any>(false, 411, 914, 1f), arrayOf<Any>(false, 320, 800, 2f), arrayOf<Any>(true, 320, 800, 2f))
    }
}
