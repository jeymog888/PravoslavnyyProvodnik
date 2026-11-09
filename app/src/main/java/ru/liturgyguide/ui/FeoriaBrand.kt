package ru.liturgyguide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One bounded logo graphic, not reading text. Its accessible name stays Russian. */
@Composable
fun FeoriaBrand(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val gold = MaterialTheme.colorScheme.secondary
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // Only the artwork has a fixed physical design size. No LocalDensity provider
    // surrounds UI text, which keeps the full system and app font setting.
    val artworkDensity = Density(density.density, 1f)
    val greek = measurer.measure("θεωρία", TextStyle(color = ink, fontFamily = FontFamily.Serif,
        fontSize = 30.sp, lineHeight = 34.sp), density = artworkDensity, softWrap = false)
    val russian = measurer.measure("ФЕОРИЯ", TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = 2.sp), density = artworkDensity, softWrap = false)
    val wordLeft = with(density) { 44.dp.toPx() }
    val widthPx = wordLeft + maxOf(greek.size.width, russian.size.width)
    val heightPx = maxOf(with(density) { 42.dp.toPx() }, (greek.size.height + russian.size.height).toFloat())
    BoxWithConstraints(modifier.clearAndSetSemantics { contentDescription = "Феория" }) {
        val factor = (with(density) { maxWidth.toPx() } / widthPx).coerceAtMost(1f)
        Canvas(Modifier.size(with(density) { (widthPx * factor).toDp() },
            with(density) { (heightPx * factor).toDp() })) {
            scale(factor, factor, pivot = Offset.Zero) {
                val markTop = (heightPx - 42.dp.toPx()) / 2
                drawOval(ink, topLeft = Offset(3.dp.toPx(), markTop + 3.dp.toPx()),
                    size = Size(26.dp.toPx(), 36.dp.toPx()), style = Stroke(2.5.dp.toPx()))
                drawLine(gold, start = Offset(9.dp.toPx(), heightPx / 2),
                    end = Offset(23.dp.toPx(), heightPx / 2), strokeWidth = 3.dp.toPx())
                drawText(greek, topLeft = Offset(wordLeft, 0f))
                drawText(russian, topLeft = Offset(wordLeft, greek.size.height.toFloat()))
            }
        }
    }
}
