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
    onOpenService: ((ParishService) -> Unit)? = null, now: Instant? = null,
    onReadServiceText: ((LocalDate, WorshipKind) -> Unit)? = null) {
    val church = churches.firstOrNull { it.id == selectedId }
    var picker by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val query = searchQuery
    var locate by remember { mutableStateOf(false) }
    var dayOffset by rememberSaveable(church?.id) { mutableIntStateOf(0) }
    var editingDate by rememberSaveable(church?.id) { mutableStateOf<String?>(null) }
    val formDate = editingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var handledPickerRequest by rememberSaveable { mutableIntStateOf(0) }
    var handledTimeRequest by rememberSaveable { mutableIntStateOf(0) }
    var selectionFailed by remember { mutableStateOf(false) }
    var details by rememberSaveable(church?.id) { mutableStateOf(false) }
    var editingSource by rememberSaveable(church?.id) { mutableStateOf(false) }
    var chooserDateIso by rememberSaveable(church?.id) { mutableStateOf<String?>(null) }
    val currentChurchId by rememberUpdatedState(church?.id)
    val openSourceEditor: () -> Unit = { editingSource = true }
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
        GuideSheetSystemBars()
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
        GuideSheetSystemBars()
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("church_content"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
                val ownState = internetSchedule?.takeIf { it.parishId == church.id }
                val schedule = arrivalSchedule(church, date, now, ownState)
                val entries = schedule.entries
                val featured = schedule.featured
                val cachedPublication = schedule.publication
                val personalStart = personalStartForDate(userStarts, church, date)
                item {
                    ArrivalCard(Modifier.testTag("church_identity_card")) {
                        Text(church.shortName, style = MaterialTheme.typography.titleLarge)
                        Text(churchAddressLine(church), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = ::changeChurch, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("change_church")) {
                            Icon(Icons.Default.SwapHoriz, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Сменить храм")
                        }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(selected = dayOffset == 0, onClick = { dayOffset = 0 }, label = { Text("Сегодня") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("schedule_today"))
                        FilterChip(selected = dayOffset == 1, onClick = { dayOffset = 1 }, label = { Text("Завтра") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("schedule_tomorrow"))
                    }
                }
                item {
                    ArrivalCard(Modifier.testTag("church_schedule_card")) {
                        Text(if (schedule.saved) "Сохраненное расписание" else "Расписание", style = MaterialTheme.typography.titleMedium)
                        Text(date.format(DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", Locale.forLanguageTag("ru"))),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("schedule_date"))
                        if (featured != null) {
                            Text(featured.time, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("church_service_time"))
                            ArrivalServiceTitle(featured.title, "church_service")
                            Text(schedule.relation.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("church_service_relation"))
                            if (cachedPublication?.yearInferred == true && (schedule.saved || cachedPublication.sourceUrl == church.sourceUrl)) {
                                Text("Год не указан - проверьте дату в расписании", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("schedule_inferred_year"))
                                OutlinedButton(onClick = { onOpenUrl(cachedPublication.sourceUrl) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Проверить источник") }
                            }
                            if (onOpenService != null) Button(onClick = { onOpenService(featured) }, shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("church_open_service")) {
                                Text(serviceReadingAction(featured))
                            }
                            if (entries.size > 1) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text("Еще в этот день", style = MaterialTheme.typography.titleMedium)
                                entries.filterNot { it == featured }.forEach { service ->
                                    if (onOpenService != null) ArrivalAction(serviceKindTitle(service.title) ?: service.title, Icons.Default.Schedule, { onOpenService(service) },
                                        subtitle = serviceActionSubtitle(service.title, service.time))
                                    else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(service.time, style = MaterialTheme.typography.labelLarge)
                                        Text(serviceKindTitle(service.title) ?: service.title, style = MaterialTheme.typography.bodyLarge)
                                        serviceSourceTitle(service.title)?.let { Text("В расписании храма: " + it, style = MaterialTheme.typography.bodyMedium) }
                                    }
                                }
                            }
                        } else {
                            Text(scheduleEmptyTitle(ownState, schedule.publication != null || church.schedule.isNotEmpty()),
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("church_schedule_empty"))
                            val source = scheduleSourceFor(church, ownState)
                            Text(scheduleEmptyDescription(ownState, source != null), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            onRefreshSchedule?.let { refresh -> OutlinedButton(onClick = refresh, enabled = ownState?.loading != true,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("refresh_schedule")) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Обновить расписание")
                            } }
                            source?.let { url ->
                                OutlinedButton(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .testTag("open_schedule_website")) {
                                    Icon(Icons.Default.OpenInNew, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp)); Text("Открыть сайт")
                                }
                            }
                            if (source == null && onSetScheduleSource != null) {
                                OutlinedButton(onClick = openSourceEditor,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("edit_schedule_website")) {
                                    Icon(Icons.Default.Edit, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp)); Text("Указать сайт")
                                }
                            }
                        }
                        if (ownState?.loading == true) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Обновляем расписание", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("schedule_loading_summary"))
                        }
                        ScheduleCheckReceipt(ownState, church.timezone, Modifier.testTag("church_schedule_checked"))
                    }
                }
                if (personalStart != null) item {
                    ArrivalCard(Modifier.testTag("church_personal_card")) {
                        Text(personalStart.time + " · Литургия", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("personal_start_summary"))
                        Text("Начало указано вами · " + parishTimeZoneLabel(church, date, personalStart.time),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("personal_start_source"))
                        if (onReadLiturgy != null) OutlinedButton(onClick = { onReadLiturgy(LocalDate.parse(personalStart.date)) },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_time_to_service")) { Text("Открыть литургию") }
                    }
                }
                if (onReadLiturgy != null) item {
                    ArrivalCard(Modifier.testTag("church_reading_card"), accent = featured == null && personalStart == null) {
                        Text("Читать службу", style = MaterialTheme.typography.titleLarge)
                        Text("Оригинал, доступный русский перевод и объяснения. Можно читать в своем темпе", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (featured == null || onOpenService == null) Button(onClick = { onReadLiturgy(date) }, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("church_read_liturgy")) {
                            Text(readingEntryLabel(date))
                        } else OutlinedButton(onClick = { onReadLiturgy(date) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("church_read_liturgy")) { Text("Текст литургии на этот день") }
                        if (featured == null && onReadServiceText != null) OutlinedButton(onClick = { chooserDateIso = date.toString() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("church_other_services")) {
                            Text("Выбрать другой текст")
                        }
                    }
                }
                if (entries.isEmpty()) item { TypicalScheduleContent(date) }
                item {
                    ArrivalCard(Modifier.testTag("church_information_card")) {
                        Text("О храме", style = MaterialTheme.typography.titleMedium)
                        if (ownState != null && onRefreshSchedule != null) InternetScheduleControls(
                            state = ownState, timezone = church.timezone, onRefresh = onRefreshSchedule,
                            onOpenUrl = onOpenUrl,
                            onEditSource = openSourceEditor.takeIf {
                                onSetScheduleSource != null && (featured != null || scheduleSourceFor(church, ownState) != null)
                            },
                            showRefresh = featured != null, showWebsite = featured != null)
                        ArrivalAction(if (details) "Скрыть сведения" else "Адрес и другие сведения", Icons.Default.Info,
                            { details = !details }, Modifier.testTag("church_details"))
                        if (details) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(churchAddressLine(church), style = MaterialTheme.typography.bodyMedium)
                            if (entries.isNotEmpty()) {
                                Text(church.scheduleNote.ifBlank { "Время может измениться. Проверьте расписание перед поездкой" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val source = if (schedule.saved) cachedPublication?.sourceUrl.orEmpty() else church.sourceUrl
                                if (SavedChurchCodec.isSafeWebsite(source)) ArrivalAction("Источник расписания", Icons.Default.OpenInNew,
                                    { onOpenUrl(source) }, Modifier.testTag("schedule_source"))
                            }
                            if (onSaveStart != null) ArrivalAction(
                                if (personalStart == null) "Указать известное мне время" else "Изменить мое время", Icons.Default.Edit,
                                { editingDate = date.toString() }, Modifier.testTag("edit_personal_time"))
                            if (SavedChurchCodec.isSafeWebsite(church.website)) ArrivalAction("Сайт прихода", Icons.Default.OpenInNew, { onOpenUrl(church.website) })
                            if (church.phone.isNotBlank()) ArrivalAction("Позвонить: " + church.phone, Icons.Default.Phone,
                                { onOpenUrl("tel:" + church.phone.filter { it.isDigit() || it == '+' }) })
                            ArrivalAction("Сохраненные и избранные храмы", Icons.Default.StarBorder,
                                { picker = true }, Modifier.testTag("saved_churches"))
                        }
                    }
                }
            }
        }
    }
    if (church != null) {
        val ownerId = church.id
        val chooserDate = chooserDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (chooserDate != null && onReadServiceText != null) ServiceBookChooser(chooserDate,
            onDismiss = { chooserDateIso = null }, onSelect = { selectedDate, worship ->
                if (currentChurchId == ownerId) onReadServiceText(selectedDate, worship)
            })
        if (editingSource && onSetScheduleSource != null) {
            val ownState = internetSchedule?.takeIf { it.parishId == ownerId }
            ScheduleSourceSheet(churchId = ownerId, churchName = church.shortName,
                initialUrl = ownState?.website.orEmpty().ifBlank { church.website }, loading = ownState?.loading == true,
                onDismiss = { editingSource = false }, onSubmit = { url ->
                    if (currentChurchId == ownerId) {
                        editingSource = false
                        onSetScheduleSource(url)
                    }
                })
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
    onOpenUrl: (String) -> Unit, onEditSource: (() -> Unit)?, showRefresh: Boolean = true, showWebsite: Boolean = true) {
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
        if (showRefresh) ArrivalAction(if (state.status == InternetScheduleStatus.UNAVAILABLE) "Повторить обновление" else "Обновить расписание",
            Icons.Default.Refresh, onRefresh, Modifier.testTag("refresh_schedule"), enabled = !state.loading)
        if (showWebsite && ScheduleUrl.publicSource(source) != null) ArrivalAction("Открыть сайт храма", Icons.Default.OpenInNew,
            { onOpenUrl(source) }, Modifier.testTag("open_schedule_website"))
        onEditSource?.let { edit ->
            ArrivalAction(if (state.website.isBlank()) "Указать сайт" else "Изменить сайт", Icons.Default.Edit,
                edit, Modifier.testTag("edit_schedule_website"))
        }
    }
}

internal fun friendlyDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru")))
