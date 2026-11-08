package ru.liturgyguide.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.ContextSuggestion
import ru.liturgyguide.service.ServiceRelation
import ru.liturgyguide.service.ScheduleOverview
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainContextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun anOldReadingKeepsTheCurrentServicePrimaryAndCanStillBeOpened() {
        val container = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
        val parish = container.parishes.getOrThrow().selected(null)
        val stage = requireNotNull(container.getStageMatcher().getStageById("creed"))
        val joined = AtomicInteger()
        val resumed = AtomicInteger()
        val suggestion = ContextSuggestion(ParishService("2026-11-08", "07:00", "Литургия", "liturgy"),
            ServiceRelation.InProgress, Instant.parse("2026-11-08T04:00:00Z"), "gospel",
            parishId = parish.id, timezone = parish.timezone)
        compose.setContent {
            GuideTheme(dark = false) {
                MainScreen(lastStage = stage, currentDate = LocalDate.of(2026, 11, 8), onStart = {}, onResume = { resumed.incrementAndGet() },
                    onFindStage = {}, church = parish, onChurch = {}, overview = ScheduleOverview(currentHypothesis = suggestion),
                    onOpenService = { assertSame(suggestion, it); joined.incrementAndGet() }, onOrder = {}, bottomBar = {})
            }
        }
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("watch_current"))
        compose.onNodeWithTag("watch_current").assertIsDisplayed().performClick()
        assertEquals(1, joined.get())
        assertEquals(0, resumed.get())
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("resume_guide"))
        compose.onNodeWithText("Сохраненное чтение").assertIsDisplayed()
        compose.onNodeWithTag("resume_guide").performClick()
        assertEquals(1, resumed.get())
    }
}
