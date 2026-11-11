package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import java.time.Instant

@RunWith(Parameterized::class)
class ArrivalLayoutTest(private val width: Int, private val fontScale: Float) {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedDayReadingAndSeparatedActionsRemainReadableAtTheActualFontScale() {
        val church = Parish("layout", "Храм Троицы в Листах", "Храм Троицы в Листах", "Москва", "Сретенка, 27", "", "",
            "Europe/Moscow", "", "", "", "", emptyList(), "", "")
        var refreshed = 0
        var detailsOpened = 0
        var sourceOpened = 0
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, fontScale)) {
                GuideTheme(false) { Box(Modifier.size(width.dp, 640.dp)) {
                    TodayScreen(church, Instant.parse("2026-11-11T10:00:00Z"),
                        InternetScheduleState(church.id, InternetScheduleStatus.UNAVAILABLE), null, null,
                        onFindChurch = {}, onChurchDetails = { detailsOpened++ }, onOpenBook = { _, _, _ -> },
                        onResumeBook = {}, onRefresh = { refreshed++ }, onResumeLegacy = null, bottomBar = {},
                        onOpenScheduleSource = { sourceOpened++ })
                } }
            }
        }
        listOf("today_day_today" to "Сегодня", "today_day_tomorrow" to "Завтра").forEach { (tag, label) ->
            scroll(tag).assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
            assertEquals("A day label must stay a whole word", 1, readable(label).lineCount)
        }
        scroll("today_service_date").assertTextEquals("12 ноября")
        scroll("today_schedule_empty").assertTextEquals("Расписание пока не найдено")
        readable("Расписание пока не найдено")
        scroll("today_refresh_schedule").assertHeightIsAtLeast(48.dp).performClick()
        readable("Обновить расписание")
        scroll("today_schedule_source").assertHeightIsAtLeast(48.dp).performClick()
        readable("Посмотреть на сайте")
        val retry = compose.onNodeWithTag("today_refresh_schedule").getUnclippedBoundsInRoot()
        val source = compose.onNodeWithTag("today_schedule_source").getUnclippedBoundsInRoot()
        assertTrue("The source belongs beside the failed schedule, with a separate touch target", source.top - retry.bottom >= 12.dp)
        scroll("start_guide").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        readable("Открыть литургию")
        scroll("today_church_details").assertHeightIsAtLeast(48.dp).performClick()
        readable("Сайт и сведения о храме")
        compose.runOnIdle { assertEquals(1, refreshed); assertEquals(1, sourceOpened); assertEquals(1, detailsOpened) }
    }

    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun readable(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val result = layouts.single()
        try {
            assertTextFitsLayout(result, text)
        } catch (failure: AssertionError) {
            val target = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "v09-arrival-${width}-${fontScale}.png")
            target.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            throw failure
        }
        assertEquals("Do not shrink the user's font scale", fontScale, result.layoutInput.density.fontScale, .001f)
        return result
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "width={0}dp fontScale={1}")
        fun sizes() = listOf(320, 360).flatMap { width -> listOf(1f, 1.3f, 1.5f, 2f).map { arrayOf<Any>(width, it) } }
    }
}
