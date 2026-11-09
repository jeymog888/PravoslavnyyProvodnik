package ru.liturgyguide.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ReadingEntryPreservationTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val parish get() = container.parishes.getOrThrow().selected(null)
    // Outside the bundled schedule: exercises the generic Open text action.
    private val now = Instant.parse("2026-12-05T09:00:00Z")
    private val date = LocalDate.of(2026, 12, 5)

    @Test fun openingWithoutAScheduleStartsAtAudibleText() {
        clear()
        launch()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
        compose.onNodeWithTag("start_guide").assertTextEquals("Открыть текст").performClick()
        compose.onNodeWithTag("service_book").assertIsDisplayed()
        assertEquals("beginning", preferences.getString("stage_id", null))
        assertEquals(date.toString(), ReadingContextStore(preferences).restore()!!.serviceDate)
    }

    @Test fun reopeningTheSameDayFromChurchPreservesTheSelectedPhrase() {
        clear()
        seed(ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), date, null))
        val before = preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)
        launch()
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_read_liturgy"))
        compose.onNodeWithTag("church_read_liturgy").assertTextEquals("Продолжить чтение").performClick()
        compose.onNodeWithTag("service_book").assertIsDisplayed()
        assertEquals("creed", preferences.getString("stage_id", null))
        assertEquals(before, preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))
    }

    @Test fun aHistoricalPreviewIsNotAcceptedByVisitingTheChurchPage() {
        clear()
        val old = requireNotNull(Historical06Content { name ->
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.loadPlan(date))
        seed(old)
        val before = preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)
        launch()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
        compose.onNodeWithTag("start_guide").assertTextEquals("Читать с начала")
        compose.onNodeWithTag("nav_church").performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("church_read_liturgy"))
        compose.onNodeWithTag("church_read_liturgy").assertTextEquals("Читать с начала")
        assertEquals(before, preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))
    }

    private fun clear() {
        check(preferences.edit().clear().putBoolean("dark", false).putString("church_id", parish.id).commit())
    }
    private fun seed(plan: ServicePlaybackPlan) {
        val track = plan.tracksByStage.getValue("creed").single { it.title == "Символ веры" }
        val engine = ServicePlaybackEngine(plan)
        val state = engine.seekLine(engine.start("creed"), track.id, track.lines[3].id)
        val reading = requireNotNull(ReadingContextFactory.manualOnDate(parish, date))
        check(ReadingAcceptanceStore(preferences).acceptPlayback(plan, reading, engine.snapshot(state, 0L),
            ServiceSession("creed", now.toEpochMilli()), resetBookmarks = false, bookmark = PlaybackBookmark.at(plan, state)))
    }
    private fun launch() { compose.setContent { AppNavigation(container, Clock.fixed(now, ZoneOffset.UTC)) } }
}
