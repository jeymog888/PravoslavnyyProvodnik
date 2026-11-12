package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liturgyguide.model.*
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(church: Parish?, now: Instant, internetSchedule: InternetScheduleState?,
    importProgress: ScheduleImportProgress?, lastBook: BookReadingPosition?,
    onFindChurch: () -> Unit, onChurchDetails: () -> Unit,
    onOpenBook: (LocalDate, WorshipKind, Boolean) -> Unit,
    onResumeBook: (BookReadingPosition) -> Unit, onRefresh: () -> Unit,
    onResumeLegacy: (() -> Unit)?, bottomBar: @Composable () -> Unit,
    userStarts: List<UserServiceStart> = emptyList(), readingNotice: String? = null,
    onOpenScheduleSource: (() -> Unit)? = null, legacyReadingSummary: String? = null,
    onSetScheduleSource: ((String) -> Unit)? = null,
    onOpenService: ((ParishService) -> Unit)? = null) {
    val zone = remember(church?.timezone) {
        runCatching { ZoneId.of(church?.timezone ?: "Europe/Moscow") }.getOrDefault(ZoneId.of("Europe/Moscow"))
    }
    val today = now.atZone(zone).toLocalDate()
    var dayOffset by rememberSaveable(church?.id) { mutableIntStateOf(0) }
    val date = today.plusDays(dayOffset.toLong())
    val ownState = internetSchedule?.takeIf { it.parishId == church?.id }
    val schedule = church?.let { arrivalSchedule(it, date, now, ownState) }
    val featured = schedule?.featured
    val personalStart = church?.let { personalStartForDate(userStarts, it, date) }
    val listState = rememberLazyListState()
    val readingBeforeSchedule = rememberReadingBeforeSchedule(listState, church?.id, date, featured != null,
        readingKey = "today_reading", personalKey = "today_personal")
    val inferredYear = schedule?.publication?.let {
        it.yearInferred && (schedule?.saved == true || it.sourceUrl == church?.sourceUrl)
    } == true
    var chooserDateIso by rememberSaveable(church?.id) { mutableStateOf<String?>(null) }
    var editingSource by rememberSaveable(church?.id) { mutableStateOf(false) }
    val currentChurchId by rememberUpdatedState(church?.id)
    val chooserDate = chooserDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val kind = featured?.let { ServiceBookSelection.kindFor(it.title) }
    val format = remember { DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")) }
    val loading = ownState?.loading == true
    val phase = importProgress?.takeIf { it.parishId == church?.id }?.phase
    val loadingTitle = when (phase) {
        ScheduleImportPhase.FINDING_SITE -> "Находим сайт храма"
        ScheduleImportPhase.READING_DOCUMENT -> "Читаем расписание из документа"
        else -> "Обновляем расписание"
    }

    fun open(service: ParishService) {
        if (onOpenService != null && ServiceBookSelection.kindFor(service.title) != null) {
            onOpenService(service)
            return
        }
        val selectedKind = ServiceBookSelection.kindFor(service.title)
        if (selectedKind == null) chooserDateIso = service.date else onOpenBook(LocalDate.parse(service.date), selectedKind,
            selectedKind == WorshipKind.MATINS && runCatching { LocalTime.parse(service.time).hour >= 12 }.getOrDefault(false))
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("main_content"), state = listState, contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            fun personalItem() {
                val start = personalStart ?: return
                item(key = "today_personal") {
                    ArrivalCard(Modifier.testTag("today_personal_card")) {
                        if (featured == null) Text(start.time, style = MaterialTheme.typography.displaySmall,
                            modifier = Modifier.testTag("today_service_time"))
                        Text(date.format(format) + " · " + start.time + " · Литургия", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("today_personal_summary"))
                        Text("Начало указано вами", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("today_personal_source"))
                        val openPersonal = { onOpenBook(LocalDate.parse(start.date), WorshipKind.LITURGY, false) }
                        if (featured == null) Button(onClick = openPersonal,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("today_open_personal_liturgy")) { Text("Открыть литургию") }
                        else OutlinedButton(onClick = openPersonal,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("today_open_personal_liturgy")) { Text("Открыть литургию") }
                    }
                }
            }
            fun readingItems() {
                item(key = "today_reading") {
                    ArrivalCard(Modifier.testTag("today_reading_card"), accent = featured == null && personalStart == null) {
                        Text("Читать службу", style = MaterialTheme.typography.titleLarge)
                        Text("Тексты на " + date.format(format) + ": оригинал, доступный русский перевод и объяснения",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (featured == null && personalStart == null) Button(onClick = { onOpenBook(date, WorshipKind.LITURGY, false) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("start_guide")) { Text("Открыть литургию") }
                        if (featured != null || personalStart != null) OutlinedButton(onClick = { chooserDateIso = date.toString() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("today_other_services")) { Text("Выбрать другой текст") }
                    }
                }
                if (featured == null && personalStart == null) item(key = "today_other_texts") {
                    ArrivalAction("Выбрать другой текст", Icons.Default.MenuBook, { chooserDateIso = date.toString() },
                        Modifier.testTag("today_other_services"))
                }
            }
            item(key = "today_header") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Феория", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text("θεωρία", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            readingNotice?.let { notice -> item(key = "today_reading_notice") {
                Text(notice, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("reading_restore_notice"))
            } }
            if (church == null) {
                item(key = "today_find_church") {
                    ArrivalCard(accent = true) {
                        Text("Понимать\nслужбу", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp, lineHeight = 42.sp))
                        Text("Выберите храм, чтобы увидеть расписание и открыть текст богослужения", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onFindChurch, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("open_church")) {
                            Icon(Icons.Default.Search, null); Spacer(Modifier.width(12.dp)); Text("Найти храм")
                        }
                    }
                }
                item(key = "today_without_church") {
                    ArrivalAction("Открыть тексты без выбора храма", Icons.Default.MenuBook, { chooserDateIso = date.toString() },
                        Modifier.testTag("today_choose_without_church"))
                }
            } else {
                item(key = "today_church") {
                    ArrivalCard(Modifier.testTag("today_church_card")) {
                        Text(church.shortName, style = MaterialTheme.typography.titleLarge)
                        Text(churchAddressLine(church), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onFindChurch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("change_church")) {
                            Icon(Icons.Default.Place, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Сменить храм")
                        }
                    }
                }
                item(key = "today_days") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(dayOffset == 0, { dayOffset = 0 }, label = { Text("Сегодня") }, modifier = Modifier.heightIn(min = 48.dp).testTag("today_day_today"))
                        FilterChip(dayOffset == 1, { dayOffset = 1 }, label = { Text("Завтра") }, modifier = Modifier.heightIn(min = 48.dp).testTag("today_day_tomorrow"))
                    }
                }
                // Once reached, this group stays in place even if a publication arrives.
                // The published service still receives the primary action.
                if (readingBeforeSchedule) {
                    personalItem()
                    readingItems()
                }
                item(key = "today_schedule") {
                    ArrivalCard(Modifier.testTag("today_schedule_card")) {
                        Text(if (schedule?.saved == true) "Сохраненное расписание" else "Расписание", style = MaterialTheme.typography.titleMedium)
                        Text(date.format(format), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("today_service_date"))
                        if (featured != null) {
                            Text(featured.time, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("today_service_time"))
                            ArrivalServiceHeading(featured.title, "today_service")
                            Text(schedule?.relation.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("today_service_relation"))
                            if (inferredYear) {
                                Text("Год не указан - проверьте дату в расписании", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("schedule_inferred_year"))
                            }
                            if (kind != null) Button(onClick = { open(featured) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("open_service_book")) {
                                Text(if (kind == WorshipKind.LITURGY && featured.kind == "service_block") "Открыть литургию" else "Открыть службу")
                            } else OutlinedButton(onClick = { open(featured) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("today_choose_unknown_service")) {
                                Text("Выбрать текст службы")
                            }
                            ArrivalServiceSourceTitle(featured.title, "today_service")
                            if (inferredYear) onOpenScheduleSource?.let { source -> OutlinedButton(onClick = source,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("today_verify_schedule_source")) {
                                Text("Проверить источник")
                            } }
                            schedule?.entries.orEmpty().filterNot { it == featured }.forEach { service ->
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                ArrivalAction(serviceKindTitle(service.title) ?: service.title, Icons.Default.Schedule, { open(service) },
                                    subtitle = serviceActionSubtitle(service.title, service.time + " · " + date.format(format)))
                            }
                        } else {
                            Text(scheduleEmptyTitle(ownState, schedule?.publication != null || church.schedule.isNotEmpty()), style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.testTag("today_schedule_empty"))
                            Text(scheduleEmptyDescription(ownState, onOpenScheduleSource != null), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!loading) OutlinedButton(onClick = onRefresh,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("today_refresh_schedule")) {
                                Icon(Icons.Default.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Обновить расписание")
                            }
                            onOpenScheduleSource?.let { source ->
                                OutlinedButton(onClick = source, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .testTag("today_schedule_source")) {
                                    Icon(Icons.Default.OpenInNew, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp)); Text("Открыть сайт")
                                }
                            }
                            if (onOpenScheduleSource == null && onSetScheduleSource != null) {
                                OutlinedButton(onClick = { editingSource = true },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("today_edit_schedule_website")) {
                                    Icon(Icons.Default.Edit, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp)); Text("Указать сайт")
                                }
                            }
                        }
                        if (loading) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(loadingTitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("today_schedule_loading"))
                        }
                        ScheduleCheckReceipt(ownState, church.timezone, Modifier.testTag("today_schedule_checked"))
                    }
                }
                if (!readingBeforeSchedule) personalItem()
            }
            lastBook?.let { saved -> item(key = "resume_book") {
                ArrivalAction("Продолжить чтение", Icons.Default.Bookmark, { onResumeBook(saved) }, Modifier.testTag("resume_book"),
                    subtitle = saved.key.worship.title + " · " + LocalDate.parse(saved.key.date).format(format))
            } }
            if (church != null) {
                if (!readingBeforeSchedule) readingItems()
                if (schedule?.entries.isNullOrEmpty()) item(key = "today_typical") { TypicalScheduleContent(date) }
                item(key = "today_details") {
                    ArrivalCard(Modifier.testTag("today_details_card")) {
                        Text("О храме", style = MaterialTheme.typography.titleMedium)
                        if (!loading && featured != null) ArrivalAction("Обновить расписание", Icons.Default.Refresh, onRefresh, Modifier.testTag("today_refresh_schedule"))
                        onOpenScheduleSource?.takeIf { featured != null }?.let { source ->
                            ArrivalAction("Расписание на сайте", Icons.Default.OpenInNew, source, Modifier.testTag("today_schedule_source"))
                        }
                        ArrivalAction("Сайт и сведения о храме", Icons.Default.Info, onChurchDetails, Modifier.testTag("today_church_details"))
                    }
                }
            }
            onResumeLegacy?.let { resume -> item(key = "today_legacy") {
                ArrivalAction("Прежнее сохраненное чтение", Icons.Default.BookmarkBorder, resume,
                    Modifier.testTag("resume_guide"), subtitle = legacyReadingSummary)
            } }
        }
    }
    if (chooserDate != null) ServiceBookChooser(chooserDate, onDismiss = { chooserDateIso = null },
        onSelect = { selectedDate, worship -> onOpenBook(selectedDate, worship, false) })
    if (editingSource && church != null && onSetScheduleSource != null) {
        val editorChurchId = church.id
        ScheduleSourceSheet(churchId = editorChurchId, churchName = church.shortName,
            initialUrl = ownState?.website.orEmpty().ifBlank { church.website }, loading = loading,
            onDismiss = { editingSource = false }, onSubmit = { url ->
                if (currentChurchId == editorChurchId) {
                    editingSource = false
                    onSetScheduleSource(url)
                }
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceBookChooser(civilDate: LocalDate, onDismiss: () -> Unit, onSelect: (LocalDate, WorshipKind) -> Unit) {
    val dateTitle = remember(civilDate) { civilDate.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru"))) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GuideSheetSystemBars()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("service_book_chooser"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Тексты на " + dateTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("service_chooser_date"))
                    Text("Это библиотека текстов. Время служб смотрите в расписании выбранного храма", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(listOf(WorshipKind.LITURGY, WorshipKind.VIGIL, WorshipKind.VESPERS, WorshipKind.MATINS), key = { it.name }) { worship ->
                ArrivalAction(worship.title, Icons.Default.MenuBook, { onDismiss(); onSelect(civilDate, worship) },
                    Modifier.testTag("service_book_choose_" + worship.name.lowercase(Locale.ROOT)), subtitle = when (worship) {
                        WorshipKind.LITURGY -> "Главная служба с причащением"
                        WorshipKind.VIGIL -> "Вечерня, утреня и первый час вместе"
                        WorshipKind.VESPERS -> "Благодарение Богу за прошедший день"
                        WorshipKind.MATINS -> "Псалмы и песнопения, посвященные событиям дня"
                        else -> null
                    })
            }
        }
    }
}
