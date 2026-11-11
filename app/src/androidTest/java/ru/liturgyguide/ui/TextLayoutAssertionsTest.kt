package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Negative controls prove that the real-bound checks still reject damaged labels. */
@RunWith(AndroidJUnit4::class)
class TextLayoutAssertionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rejectsHorizontalClippingEllipsisHiddenLinesAndVerticalClippingButAllowsWrapping() {
        val label = "Оригинал"
        val sentence = "Сегодня читаем текст службы"
        compose.setContent {
            GuideTheme(false) {
                Column {
                    Text(label, Modifier.testTag("intrinsic"))
                    Text(label, Modifier.width(24.dp).testTag("clipped"), softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
                    Text(label, Modifier.width(24.dp).testTag("ellipsis"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sentence, Modifier.width(80.dp).testTag("omitted"), maxLines = 1, overflow = TextOverflow.Clip)
                    Text(label, Modifier.width(200.dp).height(4.dp).testTag("short"), softWrap = false)
                    Text(sentence, Modifier.width(120.dp).testTag("wrapped"))
                }
            }
        }
        assertTextFitsLayout(layout("intrinsic"), label, singleLine = true)
        for ((tag, text) in listOf("clipped" to label, "ellipsis" to label, "omitted" to sentence, "short" to label)) {
            val layout = layout(tag)
            assertThrows("The helper must reject intentionally broken text: $tag", AssertionError::class.java) {
                assertTextFitsLayout(layout, text)
            }
        }
        val wrapped = layout("wrapped")
        assertTrue("The positive control must actually wrap", wrapped.lineCount > 1)
        assertTextFitsLayout(wrapped, sentence)
        assertThrows("Single-line labels must reject wrapping", AssertionError::class.java) {
            assertTextFitsLayout(wrapped, sentence, singleLine = true)
        }
    }

    private fun layout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }
}
