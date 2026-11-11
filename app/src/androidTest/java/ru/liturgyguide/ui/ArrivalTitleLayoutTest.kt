package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ArrivalTitleLayoutTest(private val original: String, private val expectedKind: String?) {
    @get:Rule val compose = createComposeRule()

    @Test fun serviceTypeAndFullSourceRemainReadableAt320dpAnd200Percent() {
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density, 2f)) {
                GuideTheme(false) { Box(Modifier.width(320.dp)) { ArrivalCard {
                    ArrivalServiceTitle(original, "fixture_service")
                } } }
            }
        }
        compose.onNodeWithTag("fixture_service_title").assertTextEquals(original)
        if (expectedKind == null) compose.onNodeWithTag("fixture_service_kind").assertDoesNotExist()
        else {
            compose.onNodeWithTag("fixture_service_kind").assertIsDisplayed()
            readable(expectedKind)
        }
        readable(original)
        if (original == expectedKind) compose.onAllNodesWithText(original, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun readable(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTextFitsLayout(layouts.single(), text)
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, .001f)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "title={0}")
        fun titles() = listOf(
            arrayOf<Any?>("Литургия", "Литургия"),
            arrayOf<Any?>("Сщмч. Зиновия, еп. Егейского, и сестры его мц. Зиновии. Часы, Литургия", "Часы и литургия"),
            arrayOf<Any?>("Молебен перед началом учения", null)
        )
    }
}
