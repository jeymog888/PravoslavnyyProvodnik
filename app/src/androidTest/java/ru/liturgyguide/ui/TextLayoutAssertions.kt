package ru.liturgyguide.ui

import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Check measured text rather than paragraph width: GetTextLayoutResult can expose a paragraph
 * created with the original maximum constraint even when Text measures to its intrinsic width.
 * This verifies layout clipping/truncation, not text color or clipping by an ancestor/viewport.
 */
internal fun assertTextFitsLayout(layout: TextLayoutResult, expected: String, singleLine: Boolean = false) {
    val detail = "'$expected' size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}"
    val tolerancePx = 1f // Text bounds are fractional; the measured surface uses whole pixels.
    assertEquals("The measured text must be the complete requested text: $detail", expected, layout.layoutInput.text.text)
    assertTrue("Text must have a measured line: $detail", layout.lineCount > 0)
    if (singleLine) assertEquals("The complete label must stay on one line: $detail", 1, layout.lineCount)
    assertFalse("No lines may be omitted: $detail", layout.multiParagraph.didExceedMaxLines)
    assertEquals("The final line must include the final character: $detail", expected.length,
        layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
    for (line in 0 until layout.lineCount) {
        val bounds = "line=$line bounds=[${layout.getLineLeft(line)},${layout.getLineTop(line)},${layout.getLineRight(line)},${layout.getLineBottom(line)}] $detail"
        assertFalse("No ellipsis: $bounds", layout.isLineEllipsized(line))
        assertTrue("Line starts outside the left edge: $bounds", layout.getLineLeft(line) >= -tolerancePx)
        assertTrue("Line ends outside the right edge: $bounds", layout.getLineRight(line) <= layout.size.width + tolerancePx)
        assertTrue("Line starts above the top edge: $bounds", layout.getLineTop(line) >= -tolerancePx)
        assertTrue("Line ends below the bottom edge: $bounds", layout.getLineBottom(line) <= layout.size.height + tolerancePx)
    }
    // Also check character advance boxes so an overflowing run cannot hide behind line metadata.
    expected.forEachIndexed { offset, character ->
        if (!character.isWhitespace()) {
            val box = layout.getBoundingBox(offset)
            assertTrue("Character $offset extends outside the text surface: $box $detail",
                box.left >= -tolerancePx && box.right <= layout.size.width + tolerancePx &&
                    box.top >= -tolerancePx && box.bottom <= layout.size.height + tolerancePx)
        }
    }
}
