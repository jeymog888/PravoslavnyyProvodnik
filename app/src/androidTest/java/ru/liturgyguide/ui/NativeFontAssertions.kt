package ru.liturgyguide.ui

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import kotlin.math.roundToInt

/** Check the rendered text path against the native density used by the layout predictor. */
internal fun assertNativeTextMetrics(actual: TextLayoutResult, native: Density) {
    val input = actual.layoutInput
    // Compose 1.7.3 String semantics rebuilds the paragraph and drops minWidth.
    // Use the same paragraph width so centered glyph positions are comparable;
    // density, font metrics, line wrapping and every extent remain independent.
    val paragraphWidth = actual.multiParagraph.width.roundToInt()
    val predicted = TextMeasurer(input.fontFamilyResolver, native, input.layoutDirection, cacheSize = 0)
        .measure(text = input.text, style = input.style, overflow = input.overflow,
            softWrap = input.softWrap, maxLines = input.maxLines, placeholders = input.placeholders,
            constraints = input.constraints.copy(minWidth = paragraphWidth, maxWidth = paragraphWidth))
    val message = "Native text measurement: ${input.text} at ${native.fontScale}"
    assertEquals(message, predicted.lineCount, actual.lineCount)
    for (line in 0 until actual.lineCount) {
        assertEquals(message, predicted.getLineLeft(line), actual.getLineLeft(line), 1f)
        assertEquals(message, predicted.getLineRight(line), actual.getLineRight(line), 1f)
        assertEquals(message, predicted.getLineTop(line), actual.getLineTop(line), 1f)
        assertEquals(message, predicted.getLineBottom(line), actual.getLineBottom(line), 1f)
    }
    assertEquals(message, predicted.firstBaseline, actual.firstBaseline, 1f)
    assertEquals(message, predicted.lastBaseline, actual.lastBaseline, 1f)
}
