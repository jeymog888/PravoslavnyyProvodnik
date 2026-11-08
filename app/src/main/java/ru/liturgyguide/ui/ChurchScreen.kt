package ru.liturgyguide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import ru.liturgyguide.service.ChurchDirectory
import ru.liturgyguide.service.SavedChurchCodec
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.model.Parish
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChurchScreen(churches: List<Parish>, selectedId: String?, currentDate: LocalDate, onSelect: (String) -> Unit,
    onOpenUrl: (String) -> Unit, onBack: () -> Unit, showBack: Boolean = true, bottomBar: @Composable () -> Unit = {},
    favorites: Set<String> = emptySet(), onToggleFavorite: (String) -> Unit = {},
    onAddChurch: ((SavedChurchRecord) -> Boolean)? = null, onDiscover: (() -> Unit)? = null,
    pickerRequest: Int = 0, userStarts: List<UserServiceStart> = emptyList(),
    onSaveStart: ((UserServiceStart) -> Boolean)? = null, onRemoveStart: ((LocalDate) -> Boolean)? = null) {
    val church = churches.firstOrNull { it.id == selectedId } ?: churches.firstOrNull()
    var picker by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var locate by remember { mutableStateOf(false) }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var editingDate by rememberSaveable(church?.id) { mutableStateOf<String?>(null) }
    val formDate = editingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var handledPickerRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(pickerRequest) {
        if (pickerRequest != handledPickerRequest) {
            handledPickerRequest = pickerRequest
            picker = true
        }
    }
    val matches = remember(churches, query, favorites) { ChurchDirectory.search(churches, query, favorites) }
    if (picker) ModalBottomSheet(onDismissRequest = { picker = false; adding = false; locate = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (adding && onAddChurch != null) SavedChurchForm(
            onCancel = { adding = false },
            onSave = { record ->
                if (onAddChurch(record)) {
                    onSelect(record.id); adding = false; picker = false; locate = false; dayOffset = 0; query = ""
                    true
                } else false
            })
        else LazyColumn(Modifier.fillMaxWidth().imePadding().testTag("church_picker"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Выберите храм", style = MaterialTheme.typography.headlineMedium) }
            if (onDiscover != null) item {
                Button(onClick = { picker = false; locate = false; onDiscover() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discover_churches")) {
                    Icon(Icons.Default.Explore, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("Найти рядом или в другом городе")
                }
            }
            item {
                OutlinedTextField(query, { query = it.take(160) }, singleLine = true,
                    label = { Text("Город или название храма") },
                    supportingText = { Text("Среди добавленных храмов") },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить поиск храмов") } },
                    modifier = Modifier.fillMaxWidth().testTag("church_search"))
            }
            if (onAddChurch != null) item {
                OutlinedButton(onClick = { locate = false; adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_church")) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Добавить свой храм")
                }
            }
            item {
                TextButton(onClick = { locate = !locate }, modifier = Modifier.testTag("church_locate")) {
                    Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Сохраненные храмы рядом")
                }
                if (locate) LocateChurchCard(churches) { id -> onSelect(id); picker = false; locate = false; dayOffset = 0 }
            }
            if (matches.isEmpty()) item {
                Text("Среди добавленных храмов ничего не найдено", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("church_search_empty"))
                Text("Проверьте название или добавьте свой храм", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(matches, key = { _, item -> item.id }) { index, option ->
                if (index == 0 || (matches[index - 1].id in favorites) != (option.id in favorites)) {
                    Text(if (option.id in favorites) "Избранные" else "Добавленные храмы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().clickable { onSelect(option.id); picker = false; locate = false; dayOffset = 0 }
                        .testTag("church_${option.id}").semantics { role = Role.RadioButton; selected = option.id == selectedId }
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(option.shortName, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(option.city, option.address).filter(String::isNotBlank).distinct().joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (option.id == selectedId) Text("Выбран", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onToggleFavorite(option.id) }, modifier = Modifier.testTag("favorite_${option.id}")) {
                            Icon(if (option.id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                                if (option.id in favorites) "Убрать из избранного: ${option.shortName}" else "В избранное: ${option.shortName}",
                                tint = if (option.id in favorites) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (church != null && formDate != null && onSaveStart != null) ModalBottomSheet(
        onDismissRequest = { editingDate = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PersonalServiceTimeForm(church = church, currentDate = currentDate, initialDate = formDate,
            userStarts = userStarts, onCancel = { editingDate = null },
            onSave = { record ->
                if (onSaveStart(record)) {
                    dayOffset = ChronoUnit.DAYS.between(currentDate, LocalDate.parse(record.date)).toInt()
                    editingDate = null
                    true
                } else false
            }, onRemove = onRemoveStart?.let { remove -> { date: LocalDate ->
                if (remove(date)) { editingDate = null; true } else false
            } })
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { Column(Modifier.statusBarsPadding()) {
        if (showBack) PageHeader("Мой храм", onBack)
        else NavigationTextScale {
            Text("Мой храм", Modifier.padding(horizontal = 24.dp, vertical = 16.dp).testTag("church_title"), style = MaterialTheme.typography.headlineLarge)
        }
    } }, bottomBar = bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("church_content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (church == null) item {
                Text("Выберите или добавьте храм", style = MaterialTheme.typography.titleLarge)
                Button(onClick = { picker = true }, modifier = Modifier.testTag("change_church")) { Text("Выбрать храм") }
            }
            else {
                item {
                    Text(church.name, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(listOf(church.city, church.address).filter(String::isNotBlank).distinct().joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (church.id.startsWith("custom:") || church.id.startsWith("osm:")) {
                        Spacer(Modifier.height(8.dp))
                        Text(if (church.id.startsWith("custom:")) "Добавлен вами" else "Данные OpenStreetMap", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { picker = true }, modifier = Modifier.testTag("change_church"), contentPadding = PaddingValues(vertical = 10.dp)) { Text("Выбрать другой храм") }
                }
                item {
                    val date = currentDate.plusDays(dayOffset.toLong())
                    val entries = church.schedule.filter { it.date == date.toString() }.sortedBy { it.time }
                    val personalStart = personalStartForDate(userStarts, church, date)
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Расписание", style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = dayOffset == 0, onClick = { dayOffset = 0 }, label = { Text("Сегодня") },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("schedule_today"))
                            FilterChip(selected = dayOffset == 1, onClick = { dayOffset = 1 }, label = { Text("Завтра") },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("schedule_tomorrow"))
                        }
                        Text(date.format(DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", Locale.forLanguageTag("ru"))),
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("schedule_date"))
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Опубликованное расписание", style = MaterialTheme.typography.titleMedium)
                                if (entries.isEmpty()) Text("На этот день нет опубликованного расписания в приложении", style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.testTag("published_schedule_empty"))
                                else entries.forEach { service ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                        Text(service.time, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                        Text(service.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    }
                                }
                                Text(if (entries.isEmpty()) "Начало литургии можно уточнить в храме" else "Возможны изменения. Уточняйте расписание в храме или на сайте прихода",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (onSaveStart != null || personalStart != null) Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Ваше время", style = MaterialTheme.typography.titleMedium)
                                if (personalStart != null) {
                                    Text("${personalStart.time} · Литургия", style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("personal_start_summary"))
                                    Text(parishTimeZoneLabel(church, date, personalStart.time), style = MaterialTheme.typography.bodySmall)
                                    Text("Для подсказки используется ваше время. Опубликованное расписание осталось отдельно",
                                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("personal_start_source"))
                                } else Text("Если вы знаете начало литургии, укажите его для этой даты",
                                    style = MaterialTheme.typography.bodyMedium)
                                if (onSaveStart != null) OutlinedButton(onClick = { editingDate = date.toString() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("edit_personal_time")) {
                                    Text(if (personalStart == null) "Указать время" else "Изменить мое время")
                                }
                            }
                        }
                    }
                }
                if (SavedChurchCodec.isSafeWebsite(church.website) || church.phone.isNotBlank()) item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column {
                            if (SavedChurchCodec.isSafeWebsite(church.website)) GuideActionRow("Сайт прихода", "Актуальное расписание и объявления", Icons.Default.OpenInNew, { onOpenUrl(church.website) })
                            if (SavedChurchCodec.isSafeWebsite(church.website) && church.phone.isNotBlank()) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            if (church.phone.isNotBlank()) GuideActionRow("Позвонить в храм", church.phone, Icons.Default.Phone, { onOpenUrl("tel:" + church.phone.filter { it.isDigit() || it == '+' }) })
                        }
                    }
                }
                item { Text(church.scheduleCoverage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

internal fun friendlyDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru")))
