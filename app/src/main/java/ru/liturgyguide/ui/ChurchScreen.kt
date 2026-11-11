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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import ru.liturgyguide.service.ChurchDirectory
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.SavedChurchCodec
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.PublishedSchedule
import ru.liturgyguide.service.TypicalSchedule
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.ServiceBookSelection
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.ScheduleUrl
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChurchScreen(churches: List<Parish>, selectedId: String?, currentDate: LocalDate,
    onSelect: (String, onSelected: () -> Unit) -> ChurchSelectionResult,
    onOpenUrl: (String) -> Unit, onBack: () -> Unit, showBack: Boolean = true, bottomBar: @Composable () -> Unit = {},
    favorites: Set<String> = emptySet(), onToggleFavorite: (String) -> Unit = {},
    onAddChurch: ((SavedChurchRecord) -> Boolean)? = null, onDiscover: (() -> Unit)? = null,
    pickerRequest: Int = 0, userStarts: List<UserServiceStart> = emptyList(),
    onSaveStart: ((UserServiceStart) -> Boolean)? = null, onRemoveStart: ((LocalDate) -> Boolean)? = null,
    personalTimeRequest: Int = 0, onGoToService: (() -> Unit)? = null,
    onReadLiturgy: ((LocalDate) -> Unit)? = null,
    readingEntryLabel: (LocalDate) -> String = { "Читать литургию" },
    internetSchedule: InternetScheduleState? = null, onRefreshSchedule: (() -> Unit)? = null,
    onSetScheduleSource: ((String) -> Unit)? = null,
    onSelectionCancelled: () -> Unit = {}, searchQuery: String, onSearchQueryChange: (String) -> Unit,
    onOpenService: ((ParishService) -> Unit)? = null, now: Instant? = null) {
    val church = churches.firstOrNull { it.id == selectedId }
    var picker by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val query = searchQuery
    var locate by remember { mutableStateOf(false) }
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    var editingDate by rememberSaveable(church?.id) { mutableStateOf<String?>(null) }
    val formDate = editingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var handledPickerRequest by rememberSaveable { mutableIntStateOf(0) }
    var handledTimeRequest by rememberSaveable { mutableIntStateOf(0) }
    var selectionFailed by remember { mutableStateOf(false) }
    var details by rememberSaveable(church?.id) { mutableStateOf(false) }
    // The draft belongs to this screen, even while the sheet uses a separate dialog registry.
    // There is at most one draft, and every explicit close/new attempt removes it.
    val churchDraft = rememberSaveableStateHolder()
    fun selectChurch(id: String): ChurchSelectionResult {
        val result = onSelect(id) {
            churchDraft.removeState("new_church")
            selectionFailed = false; adding = false; picker = false; locate = false; dayOffset = 0; onSearchQueryChange("")
        }
        selectionFailed = result == ChurchSelectionResult.FAILED || result == ChurchSelectionResult.STALE
        return result
    }
    val currentCancel by rememberUpdatedState(onSelectionCancelled)
    DisposableEffect(Unit) {
        onDispose { currentCancel() }
    }
    LaunchedEffect(pickerRequest) {
        if (pickerRequest != handledPickerRequest) {
            handledPickerRequest = pickerRequest
            if (onDiscover != null) onDiscover() else picker = true
        }
    }
    LaunchedEffect(personalTimeRequest) {
        if (personalTimeRequest != handledTimeRequest) {
            handledTimeRequest = personalTimeRequest
            if (church != null && onSaveStart != null) {
                churchDraft.removeState("new_church")
                picker = false; adding = false; locate = false; dayOffset = 0
                editingDate = currentDate.toString()
            }
        }
    }
    val matches = remember(churches, query, favorites) { ChurchDirectory.search(churches, query, favorites) }
    if (picker) ModalBottomSheet(onDismissRequest = {
        onSelectionCancelled()
        churchDraft.removeState("new_church"); picker = false; adding = false; locate = false
    },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (adding && onAddChurch != null) churchDraft.SaveableStateProvider("new_church") {
            SavedChurchForm(
                onCancel = { onSelectionCancelled(); churchDraft.removeState("new_church"); adding = false },
                onSave = { record -> if (onAddChurch(record)) selectChurch(record.id) else ChurchSelectionResult.FAILED })
        }
        else LazyColumn(Modifier.fillMaxWidth().imePadding().testTag("church_picker"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Выберите храм", style = MaterialTheme.typography.headlineMedium) }
            if (selectionFailed) item {
                Text("Не удалось выбрать храм. Попробуйте еще раз", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("church_selection_error"))
            }
            item {
                OutlinedTextField(query, { onSearchQueryChange(it.take(120)) }, singleLine = true,
                    label = { Text("Название храма или монастыря") },
                    supportingText = { Text("Сохраненные совпадения появятся ниже") },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onSearchQueryChange("") }) { Icon(Icons.Default.Close, "Очистить поиск храмов") } },
                    modifier = Modifier.fillMaxWidth().testTag("church_search"))
            }
            if (onDiscover != null) item {
                Button(onClick = { picker = false; locate = false; onDiscover() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discover_churches")) {
                    Icon(Icons.Default.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("Найти в сети")
                }
                Text("По названию, городу или рядом с вами", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onAddChurch != null) item {
                OutlinedButton(onClick = { churchDraft.removeState("new_church"); locate = false; adding = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_church")) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Добавить свой храм")
                }
            }
            item {
                TextButton(onClick = { locate = !locate }, modifier = Modifier.testTag("church_locate")) {
                    Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Сохраненные храмы рядом")
                }
                if (locate) LocateChurchCard(churches) { id -> selectChurch(id) }
            }
            if (matches.isEmpty()) item {
                Text("Среди добавленных храмов ничего не найдено", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("church_search_empty"))
                Text(when {
                    onDiscover != null -> "Нажмите \"Найти в сети\" или добавьте храм вручную"
                    onAddChurch != null -> "Попробуйте другое название или добавьте храм вручную"
                    else -> "Попробуйте другое название"
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(matches, key = { _, item -> item.id }) { index, option ->
                if (index == 0 || (matches[index - 1].id in favorites) != (option.id in favorites)) {
                    Text(if (option.id in favorites) "Избранные" else "Добавленные храмы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().clickable { selectChurch(option.id) }
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
    fun changeChurch() {
        if (onDiscover != null) onDiscover() else picker = true
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { Column(Modifier.statusBarsPadding()) {
        if (showBack) PageHeader("Храм", onBack)
    } }, bottomBar = bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("church_content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (!showBack) item {
                Text("Мой храм", Modifier.testTag("church_title").semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
            if (church == null) item {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Найдите свой храм", style = MaterialTheme.typography.headlineMedium)
                    Text("По названию, городу или рядом с вами", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = ::changeChurch, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("change_church")) {
                        Icon(Icons.Default.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Найти храм")
                    }
                }
            }
            else {
                val date = currentDate.plusDays(dayOffset.toLong())
                val publishedEntries = PublishedSchedule.forDate(church, date)
                val cachedPublication = internetSchedule?.publication?.takeIf { it.parishId == church.id && it.timezone == church.timezone }
                val cachedEntries = cachedPublication?.forDate(date).orEmpty()
                val entries = publishedEntries.ifEmpty { cachedEntries.map { ParishService(it.date, it.time, it.title, "service_block") } }
                val showingCache = publishedEntries.isEmpty() && cachedEntries.isNotEmpty()
                val personalStart = personalStartForDate(userStarts, church, date)
                val parishNow = now?.let { runCatching { it.atZone(ZoneId.of(church.timezone)) }.getOrNull() }
                val featured = if (parishNow != null && parishNow.toLocalDate() == date) {
                    entries.lastOrNull { service -> runCatching {
                        val elapsed = java.time.Duration.between(java.time.LocalTime.parse(service.time), parishNow.toLocalTime())
                        !elapsed.isNegative && elapsed <= java.time.Duration.ofHours(2)
                    }.getOrDefault(false) } ?: entries.firstOrNull { service -> runCatching {
                        java.time.LocalTime.parse(service.time) >= parishNow.toLocalTime()
                    }.getOrDefault(false) } ?: entries.firstOrNull()
                } else entries.firstOrNull()
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(church.shortName, style = MaterialTheme.typography.titleLarge)
                        if (church.city.isNotBlank()) Text(church.city, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = ::changeChurch, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("change_church")) {
                            Icon(Icons.Default.SwapHoriz, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Сменить храм")
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(selected = dayOffset == 0, onClick = { dayOffset = 0 }, label = { Text("Сегодня") },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("schedule_today"))
                            FilterChip(selected = dayOffset == 1, onClick = { dayOffset = 1 }, label = { Text("Завтра") },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("schedule_tomorrow"))
                        }
                        Text(date.format(DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", Locale.forLanguageTag("ru"))),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("schedule_date"))
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (featured != null) {
                                Text(if (showingCache) "Сохраненное расписание" else "По расписанию храма", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                                    Text(featured.time, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(featured.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                }
                                if (onOpenService != null) Button(onClick = { onOpenService(featured) }, shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("church_open_service")) {
                                    Icon(Icons.Default.MenuBook, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(serviceReadingAction(featured))
                                }
                            } else {
                                Text("Тексты богослужения", style = MaterialTheme.typography.titleLarge)
                                Text("Читайте оригинал, русский текст и объяснения в своем темпе", style = MaterialTheme.typography.bodyLarge)
                            }
                            if (onReadLiturgy != null) {
                                if (featured == null || onOpenService == null) Button(onClick = { onReadLiturgy(date) }, shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("church_read_liturgy")) {
                                    Icon(Icons.Default.MenuBook, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(readingEntryLabel(date))
                                } else TextButton(onClick = { onReadLiturgy(date) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("church_read_liturgy")) {
                                    Text("Текст литургии на этот день")
                                }
                            }
                            if (showingCache) Text("Проверьте изменения на сайте храма. По этим данным момент службы не определяется автоматически",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (entries.size > 1) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Еще в этот день", style = MaterialTheme.typography.titleMedium)
                        entries.filterNot { it == featured }.forEach { service ->
                            val target = if (onOpenService != null) Modifier.clickable { onOpenService(service) }.semantics { role = Role.Button } else Modifier
                            Row(Modifier.fillMaxWidth().then(target).heightIn(min = 64.dp).padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(service.time, style = MaterialTheme.typography.titleMedium)
                                Text(service.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (onOpenService != null) Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                if (entries.isEmpty()) item { TypicalScheduleContent(date) }
                if (internetSchedule?.loading == true) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Обновляем расписание", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("schedule_loading_summary"))
                    }
                }
                if (personalStart != null) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${personalStart.time} · Литургия", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("personal_start_summary"))
                        Text("Начало указано вами · ${parishTimeZoneLabel(church, date, personalStart.time)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("personal_start_source"))
                        if (onReadLiturgy != null) OutlinedButton(onClick = { onReadLiturgy(LocalDate.parse(personalStart.date)) },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_time_to_service")) { Text("Открыть литургию") }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("church_details")) {
                            Text("О храме и расписании", Modifier.weight(1f))
                            Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                        if (details) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(churchAddressLine(church), style = MaterialTheme.typography.bodyMedium)
                            if (entries.isNotEmpty()) {
                                Text(church.scheduleNote.ifBlank { "Время может измениться. Проверьте расписание перед поездкой" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val source = if (showingCache) cachedPublication?.sourceUrl.orEmpty() else church.sourceUrl
                                if (SavedChurchCodec.isSafeWebsite(source)) TextButton(onClick = { onOpenUrl(source) },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("schedule_source")) { Text("Источник расписания") }
                            }
                            if (internetSchedule != null && onRefreshSchedule != null) InternetScheduleControls(
                                state = internetSchedule, timezone = church.timezone, onRefresh = onRefreshSchedule,
                                onOpenUrl = onOpenUrl, onSetSource = onSetScheduleSource)
                            if (onSaveStart != null) OutlinedButton(onClick = { editingDate = date.toString() }, shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("edit_personal_time")) {
                                Text(if (personalStart == null) "Указать известное мне время" else "Изменить мое время")
                            }
                            if (SavedChurchCodec.isSafeWebsite(church.website)) TextButton(onClick = { onOpenUrl(church.website) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Сайт прихода") }
                            if (church.phone.isNotBlank()) TextButton(onClick = { onOpenUrl("tel:" + church.phone.filter { it.isDigit() || it == '+' }) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Позвонить: ${church.phone}") }
                            TextButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("saved_churches")) {
                                Text("Сохраненные и избранные храмы")
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun serviceReadingAction(service: ParishService): String = when (ServiceBookSelection.kindFor(service.title)) {
    WorshipKind.LITURGY -> "Открыть литургию"
    WorshipKind.VESPERS -> "Открыть вечерню"
    WorshipKind.MATINS -> "Открыть утреню"
    WorshipKind.VIGIL -> "Открыть всенощное бдение"
    WorshipKind.HOUR1 -> "Открыть первый час"
    WorshipKind.HOUR3 -> "Открыть третий час"
    WorshipKind.HOUR6 -> "Открыть шестой час"
    WorshipKind.HOUR9 -> "Открыть девятый час"
    null -> "Выбрать доступные тексты"
}

internal fun churchAddressLine(church: Parish): String {
    val address = church.address.trim()
    val cityWords = church.city.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
    return if (address.isNotEmpty() && cityWords.isNotEmpty() && cityWords.all { address.lowercase().contains(it) }) address
    else listOf(church.city, address).filter(String::isNotBlank).distinct().joinToString(" · ")
}

@Composable
private fun InternetScheduleControls(state: InternetScheduleState, timezone: String, onRefresh: () -> Unit,
    onOpenUrl: (String) -> Unit, onSetSource: ((String) -> Unit)?) {
    var editing by rememberSaveable(state.parishId) { mutableStateOf(false) }
    var input by rememberSaveable(state.parishId) { mutableStateOf("") }
    var invalid by rememberSaveable(state.parishId) { mutableStateOf(false) }
    val source = state.sourceUrl.ifBlank { state.website }
    val fetched = state.publication?.fetchedAt?.let { timestamp -> runCatching {
        Instant.parse(timestamp).atZone(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru")))
    }.getOrNull() }
    Column(Modifier.fillMaxWidth().testTag("internet_schedule"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val message = when {
            state.loading -> "Проверяем расписание на сайте"
            state.status == InternetScheduleStatus.READY -> "Расписание загружено с сайта"
            state.status == InternetScheduleStatus.NO_WEBSITE -> "У этого храма пока не указан сайт"
            state.status == InternetScheduleStatus.UNSUPPORTED -> "Не удалось прочитать даты и время на сайте"
            state.status == InternetScheduleStatus.UNAVAILABLE -> "Не удалось обновить расписание"
            else -> null
        }
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("schedule_network_status"))
        if (fetched != null) Text("Сохранено $fetched", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRefresh, enabled = !state.loading,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("refresh_schedule")) {
                Text(if (state.status == InternetScheduleStatus.UNAVAILABLE) "Повторить" else "Обновить")
            }
            if (ScheduleUrl.normalized(source) != null) OutlinedButton(onClick = { onOpenUrl(source) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("open_schedule_website")) { Text("Открыть сайт") }
        }
        if (onSetSource != null) {
            if (!editing) TextButton(onClick = { input = state.website; invalid = false; editing = true },
                modifier = Modifier.heightIn(min = 48.dp).testTag("edit_schedule_website")) {
                Text(if (state.website.isBlank()) "Указать сайт храма" else "Изменить сайт")
            } else {
                OutlinedTextField(value = input, onValueChange = { input = it.take(2048); invalid = false },
                    label = { Text("Сайт или страница расписания") }, singleLine = true, isError = invalid,
                    supportingText = { Text(if (invalid) "Укажите полный адрес сайта, начиная с https://" else "Ссылка на сайт выбранного храма") },
                    modifier = Modifier.fillMaxWidth().testTag("schedule_website_input"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val normalized = ScheduleUrl.normalized(input)
                        if (normalized == null) invalid = true else { onSetSource(normalized); editing = false }
                    }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("save_schedule_website")) { Text("Проверить") }
                    TextButton(onClick = { editing = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") }
                }
            }
        }
    }
}

internal fun friendlyDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru")))


/** A weekly orientation remains visibly separate from a parish publication and never starts a timer. */
@Composable
internal fun TypicalScheduleContent(date: LocalDate) {
    val today = TypicalSchedule.forDate(date)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Типовой порядок", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("typical_schedule"))
        Text("Это общий ориентир, а не расписание выбранного храма",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (today.isNotEmpty()) {
            today.forEach { service ->
                Text("${service.period} · ${service.title}", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Text("Суббота вечером · Вечерняя служба\nВоскресенье утром · Литургия",
                style = MaterialTheme.typography.bodyLarge)
            Text("Службы в будни зависят от прихода", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
