package ru.liturgyguide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.Parish
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChurchScreen(churches: List<Parish>, selectedId: String?, currentDate: LocalDate, onSelect: (String) -> Unit,
    onOpenUrl: (String) -> Unit, onBack: () -> Unit, showBack: Boolean = true, bottomBar: @Composable () -> Unit = {}) {
    val church = churches.firstOrNull { it.id == selectedId } ?: churches.firstOrNull()
    var picker by rememberSaveable { mutableStateOf(false) }
    var locate by remember { mutableStateOf(false) }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    if (picker) ModalBottomSheet(onDismissRequest = { picker = false; locate = false }) {
        LazyColumn(Modifier.fillMaxWidth().testTag("church_picker"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Выберите храм", style = MaterialTheme.typography.headlineMedium) }
            item {
                TextButton(onClick = { locate = !locate }, modifier = Modifier.testTag("church_locate")) {
                    Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Подсказать по местоположению")
                }
                if (locate) LocateChurchCard(churches) { id -> onSelect(id); picker = false; locate = false; dayOffset = 0 }
            }
            items(churches, key = { it.id }) { option ->
                ListItem(headlineContent = { Text(option.shortName) }, supportingContent = { Text(option.address) },
                    trailingContent = { RadioButton(selected = option.id == selectedId, onClick = null) },
                    modifier = Modifier.clickable { onSelect(option.id); picker = false; locate = false; dayOffset = 0 }.testTag("church_${option.id}"))
            }
        }
    }
    Scaffold(topBar = { Column(Modifier.statusBarsPadding()) {
        if (showBack) PageHeader("Мой храм", onBack)
        else NavigationTextScale {
            Text("Мой храм", Modifier.padding(horizontal = 24.dp, vertical = 16.dp).testTag("church_title"), style = MaterialTheme.typography.headlineLarge)
        }
    } }, bottomBar = bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("church_content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (church == null) item { Text("Список храмов недоступен", style = MaterialTheme.typography.titleLarge) }
            else {
                item {
                    Text(church.name, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(church.address, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { picker = true }, modifier = Modifier.testTag("change_church"), contentPadding = PaddingValues(vertical = 10.dp)) { Text("Выбрать другой храм") }
                }
                item {
                    val date = currentDate.plusDays(dayOffset.toLong())
                    val entries = church.schedule.filter { it.date == date.toString() }.sortedBy { it.time }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Расписание", style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = dayOffset == 0, onClick = { dayOffset = 0 }, label = { Text("Сегодня") }, modifier = Modifier.testTag("schedule_today"))
                            FilterChip(selected = dayOffset == 1, onClick = { dayOffset = 1 }, label = { Text("Завтра") }, modifier = Modifier.testTag("schedule_tomorrow"))
                        }
                        Text(friendlyDate(date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (entries.isEmpty()) Text("На этот день нет подтвержденного расписания в приложении", style = MaterialTheme.typography.bodyLarge)
                                else entries.forEach { service ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                        Text(service.time, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                        Text(service.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        Text("Указано время начала утренних литургий. Возможны изменения; вечерние службы уточняйте на сайте прихода.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column {
                            GuideActionRow("Сайт прихода", "Актуальное расписание и объявления", Icons.Default.OpenInNew, { onOpenUrl(church.website) })
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            GuideActionRow("Позвонить в храм", church.phone, Icons.Default.Phone, { onOpenUrl("tel:" + church.phone.filter { it.isDigit() || it == '+' }) })
                        }
                    }
                }
                item { Text(church.scheduleCoverage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

internal fun friendlyDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru")))
