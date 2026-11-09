package ru.liturgyguide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GuideBottomBar(selected: String, onHome: () -> Unit, onChurch: () -> Unit, onSettings: () -> Unit) {
    val destinations = listOf(Triple("home", "Служба", Icons.Default.MenuBook),
        Triple("church", "Храм", Icons.Default.Place), Triple("settings", "Настройки", Icons.Default.Tune))
    val labelStyle = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp,
        localeList = LocaleList("ru"), hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
            val cellTextWidth = (constraints.maxWidth / destinations.size - 2 * with(density) { 6.dp.roundToPx() }).coerceAtLeast(1)
            // Keep complete, native-size labels visible. Larger text gets full-width destinations,
            // rather than narrower letters or an extra navigation menu.
            val stacked = destinations.any { (_, title, _) ->
                measurer.measure(title, labelStyle, constraints = Constraints(maxWidth = cellTextWidth)).lineCount > 1
            }
            @Composable
            fun destination(id: String, title: String, icon: ImageVector, modifier: Modifier) {
                val active = selected == id
                val action = when (id) { "church" -> onChurch; "settings" -> onSettings; else -> onHome }
                val target = modifier.selectable(selected = active, role = Role.Tab, onClick = action)
                    .testTag(if (id == "settings") "open_settings" else "nav_$id")
                val foreground = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                @Composable
                fun marker() {
                    Box(Modifier.background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Icon(icon, null, Modifier.testTag("nav_${id}_icon"), tint = foreground)
                    }
                }
                if (stacked) {
                    Row(target.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        marker()
                        Text(title, Modifier.weight(1f), style = labelStyle, color = foreground)
                    }
                } else {
                    Column(target.heightIn(min = 80.dp).padding(horizontal = 6.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top)) {
                        marker()
                        Text(title, Modifier.fillMaxWidth(), style = labelStyle.copy(textAlign = TextAlign.Center), color = foreground)
                    }
                }
            }
            if (stacked) {
                Column(Modifier.fillMaxWidth().selectableGroup().testTag("navigation_stacked")) {
                    destinations.forEach { (id, title, icon) -> destination(id, title, icon, Modifier.fillMaxWidth()) }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup().testTag("navigation_columns")) {
                    destinations.forEach { (id, title, icon) -> destination(id, title, icon, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
fun GuideActionRow(title: String, subtitle: String?, icon: ImageVector, onClick: () -> Unit, tag: String = "") {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
