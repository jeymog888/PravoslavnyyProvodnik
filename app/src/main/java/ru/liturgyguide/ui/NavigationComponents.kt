package ru.liturgyguide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GuideBottomBar(selected: String, onHome: () -> Unit, onChurch: () -> Unit, onSettings: () -> Unit) {
    NavigationTextScale {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf(Triple("home", "Служба", Icons.Default.MenuBook), Triple("church", "Храм", Icons.Default.Place),
            Triple("settings", "Настройки", Icons.Default.Tune)).forEach { (id, title, icon) ->
            NavigationBarItem(selected = selected == id, onClick = when(id) { "church" -> onChurch; "settings" -> onSettings; else -> onHome },
                icon = { Icon(icon, null) }, label = { Text(title, style = LocalTextStyle.current.copy(letterSpacing = 0.sp)) },
                modifier = Modifier.testTag(if (id == "settings") "open_settings" else "nav_$id"),
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
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
