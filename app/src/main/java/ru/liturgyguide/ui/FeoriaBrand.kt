package ru.liturgyguide.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Greek wordmark is decorative; assistive technology reads the Russian name. */
@Composable
fun FeoriaBrand(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val gold = MaterialTheme.colorScheme.secondary
    Row(modifier.clearAndSetSemantics { contentDescription = "Феория" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(width = 32.dp, height = 42.dp)) {
            drawOval(ink, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()), style = Stroke(2.5.dp.toPx()))
            drawLine(gold, start = Offset(9.dp.toPx(), size.height / 2),
                end = Offset(size.width - 9.dp.toPx(), size.height / 2), strokeWidth = 3.dp.toPx())
        }
        Column {
            Text("θεωρία", color = ink, fontFamily = FontFamily.Serif, fontSize = 30.sp,
                lineHeight = 34.sp, maxLines = 1, softWrap = false)
            Text("ФЕОРИЯ", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = 2.sp, maxLines = 1)
        }
    }
}
