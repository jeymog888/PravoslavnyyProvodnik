package ru.liturgyguide.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liturgyguide.model.*
import ru.liturgyguide.service.PublishedSchedule
import ru.liturgyguide.service.TodayServiceSelection
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(church: Parish?, now: Instant, internetSchedule: InternetScheduleState?,
    importProgress: ScheduleImportProgress?, lastBook: BookReadingPosition?,
    onFindChurch: () -> Unit, onChurchDetails: () -> Unit,
    onOpenBook: (LocalDate, WorshipKind, Boolean) -> Unit,
    onResumeBook: (BookReadingPosition) -> Unit, onRefresh: () -> Unit,
    onResumeLegacy: (() -> Unit)?, bottomBar: @Composable () -> Unit,
    userStarts: List<UserServiceStart> = emptyList(), readingNotice: String? = null) {
    val zone = remember(church?.timezone) { ZoneId.of(church?.timezone ?: "Europe/Moscow") }
    val today = now.atZone(zone).toLocalDate()
    var dayOffset by rememberSaveable(church?.id) { mutableIntStateOf(0) }
    val date = today.plusDays(dayOffset.toLong())
    val services = church?.let { PublishedSchedule.forDate(it, date) }.orEmpty()
    val personalStart = church?.let { personalStartForDate(userStarts, it, date) }
    val selection = church?.let { TodayServiceSelection.pick(it, date, now, lookAhead = dayOffset == 0) }
    val featured = selection?.service
    // The selector belongs to the tapped occurrence, which can be later than the visible day tab.
    var chooserDateIso by rememberSaveable(church?.id) { mutableStateOf<String?>(null) }
    val chooserDate = chooserDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val kind = featured?.let { ServiceBookSelection.kindFor(it.title) }
    val format = remember { DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")) }
    val loading = internetSchedule?.loading == true
    val phase = importProgress?.takeIf { it.parishId == church?.id }?.phase

    fun open(service: ParishService) {
        val selectedKind = ServiceBookSelection.kindFor(service.title)
        if (selectedKind == null) chooserDateIso = service.date else onOpenBook(LocalDate.parse(service.date), selectedKind,
            selectedKind == WorshipKind.MATINS && runCatching { LocalTime.parse(service.time).hour >= 12 }.getOrDefault(false))
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("main_content"), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Феория", style = MaterialTheme.typography.titleLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif, fontWeight = FontWeight.SemiBold))
                    Text("θεωρία", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            readingNotice?.let { notice -> item {
                Text(notice, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("reading_restore_notice"))
            } }
            if (church == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Понимать\nслужбу", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp, lineHeight = 42.sp))
                        Text("Выберите храм, чтобы увидеть расписание и открыть текст богослужения", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onFindChurch, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("open_church")) {
                            Icon(Icons.Default.Search, null); Spacer(Modifier.width(12.dp)); Text("Найти храм")
                        }
                        TextButton(onClick = { chooserDateIso = date.toString() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("today_choose_without_church")) { Text("Открыть тексты без выбора храма") }
                    }
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(church.shortName, style = MaterialTheme.typography.titleLarge)
                        Text(listOf(church.city, church.address).filter(String::isNotBlank).distinct().joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onFindChurch, modifier = Modifier.heightIn(min = 48.dp).testTag("change_church")) {
                            Icon(Icons.Default.Place, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Сменить храм")
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(dayOffset == 0, { dayOffset = 0 }, label = { Text("Сегодня") }, modifier = Modifier.heightIn(min = 48.dp).weight(1f).testTag("today_day_today"))
                        FilterChip(dayOffset == 1, { dayOffset = 1 }, label = { Text("Завтра") }, modifier = Modifier.heightIn(min = 48.dp).weight(1f).testTag("today_day_tomorrow"))
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text((selection?.date ?: date).format(format), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("today_service_date"))
                            if (featured != null) {
                                Text(featured.time, style = MaterialTheme.typography.displaySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif), modifier = Modifier.testTag("today_service_time"))
                                Text(featured.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("today_service_title"))
                                val relation = if (selection?.mayBeInProgress == true) "Возможно, идет · по времени начала" else "Ближайшая служба по расписанию"
                                Text(relation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("today_service_relation"))
                                if (kind != null) Button(onClick = { open(featured) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("open_service_book")) {
                                    Text(if (kind == WorshipKind.LITURGY && featured.kind == "service_block") "Открыть литургию" else "Открыть службу")
                                } else OutlinedButton(onClick = { open(featured) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("today_choose_unknown_service")) { Text("Выбрать текст службы") }
                            } else if (personalStart != null) {
                                Text(personalStart.time, style = MaterialTheme.typography.displaySmall,
                                    modifier = Modifier.testTag("today_service_time"))
                                Text("Литургия", style = MaterialTheme.typography.headlineSmall)
                                Text("Начало указано вами", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("today_personal_source"))
                                Button(onClick = { onOpenBook(LocalDate.parse(personalStart.date), WorshipKind.LITURGY, false) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("today_open_personal_liturgy")) { Text("Открыть литургию") }
                            } else {
                                Text("Тексты службы", style = MaterialTheme.typography.headlineMedium)
                                Text("Оригинал, русский текст и объяснения. Можно читать в своем темпе", style = MaterialTheme.typography.bodyLarge)
                                Button(onClick = { onOpenBook(date, WorshipKind.LITURGY, false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("start_guide")) { Text("Открыть литургию") }
                            }
                            TextButton(onClick = { chooserDateIso = (selection?.date ?: date).toString() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("today_other_services")) { Text("Другие службы") }
                        }
                    }
                }
                // A personal start is a separate source; it never replaces a published occurrence.
                if (personalStart != null && featured != null) item {
                    TextButton(onClick = { onOpenBook(LocalDate.parse(personalStart.date), WorshipKind.LITURGY, false) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("today_open_personal_liturgy")) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${date.format(format)} · ${personalStart.time} · Литургия", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.testTag("today_personal_summary"))
                            Text("Начало указано вами", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("today_personal_source"))
                        }
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
                if (services.isEmpty() && featured == null && personalStart == null) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Обычный порядок", style = MaterialTheme.typography.titleMedium)
                        Text("Суббота вечером · вечернее богослужение\nВоскресенье утром · литургия", style = MaterialTheme.typography.bodyLarge)
                        Text("Это типовой порядок. Время и службы вашего храма могут отличаться", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("typical_schedule"))
                    }
                }
                if (services.any { it != featured }) {
                    item { Text("Расписание на ${date.format(format)}", style = MaterialTheme.typography.titleMedium) }
                    items(services.filterNot { it == featured }, key = { "${it.date}|${it.time}|${it.title}" }) { service ->
                        OutlinedCard(onClick = { open(service) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(service.time, style = MaterialTheme.typography.titleMedium)
                                Text(service.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (loading) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(when (phase) {
                                ScheduleImportPhase.FINDING_SITE -> "Находим сайт храма"
                                ScheduleImportPhase.READING_DOCUMENT -> "Читаем расписание из документа"
                                else -> "Обновляем расписание"
                            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("today_schedule_loading"))
                        } else TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp).testTag("today_refresh_schedule")) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Обновить расписание")
                        }
                        TextButton(onClick = onChurchDetails, modifier = Modifier.heightIn(min = 48.dp)) { Text("Сайт и сведения о храме") }
                    }
                }
            }
            lastBook?.let { saved -> item {
                OutlinedCard(onClick = { onResumeBook(saved) }, modifier = Modifier.fillMaxWidth().testTag("resume_book")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Продолжить чтение", style = MaterialTheme.typography.titleMedium)
                        Text("${saved.key.worship.title} · ${LocalDate.parse(saved.key.date).format(format)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } }
            onResumeLegacy?.let { resume -> item { TextButton(onClick = resume, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Прежнее сохраненное чтение") } } }
        }
    }
    if (chooserDate != null) ServiceBookChooser(chooserDate, onDismiss = { chooserDateIso = null },
        onSelect = { selectedDate, worship -> onOpenBook(selectedDate, worship, false) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceBookChooser(civilDate: LocalDate, onDismiss: () -> Unit, onSelect: (LocalDate, WorshipKind) -> Unit) {
    val dateTitle = remember(civilDate) { civilDate.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru"))) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("service_book_chooser"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Тексты на $dateTitle", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("service_chooser_date")) }
            items(listOf(WorshipKind.LITURGY, WorshipKind.VIGIL, WorshipKind.VESPERS, WorshipKind.MATINS), key = { it.name }) { worship ->
                OutlinedButton(onClick = { onDismiss(); onSelect(civilDate, worship) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("service_book_choose_${worship.name.lowercase(Locale.ROOT)}")) { Text(worship.title) }
            }
        }
    }
}
