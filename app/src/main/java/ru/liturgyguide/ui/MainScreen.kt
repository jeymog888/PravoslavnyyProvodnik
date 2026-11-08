package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ContextSuggestion
import ru.liturgyguide.service.ServiceRelation
import ru.liturgyguide.service.ServiceStartSource
import ru.liturgyguide.service.ScheduleOverview
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MainScreen(lastStage: LiturgyStage?, currentDate: LocalDate, savedEarlier: Boolean = false, onStart: () -> Unit, onResume: () -> Unit, onFindStage: () -> Unit,
    church: Parish?, onChurch: () -> Unit, suggestion: ContextSuggestion?, onJoinService: () -> Unit,
    onOrder: () -> Unit, bottomBar: @Composable () -> Unit,
    overview: ScheduleOverview? = null, onOpenService: ((ContextSuggestion) -> Unit)? = null) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("main_content"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item {
                FeoriaBrand()
            }
            if (overview != null) {
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Text(friendlyDate(currentDate), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = onChurch, contentPadding = PaddingValues(vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open_church")) {
                                Icon(Icons.Default.Place, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(church?.shortName ?: "Выбрать храм", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                            }
                        }
                    }
                }
                overview.currentHypothesis?.let { current -> item(key = "current_service") {
                    ServiceSuggestionCard(current, currentDate, isCurrent = true, primaryAction = true, onOpenService = onOpenService)
                } }
                overview.nextKnownService?.let { next -> item(key = "next_service") {
                    ServiceSuggestionCard(next, currentDate, isCurrent = false,
                        primaryAction = overview.currentHypothesis == null, onOpenService = onOpenService)
                } }
                if (overview.currentHypothesis == null && overview.nextKnownService == null) item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (church == null) "Начните с выбора храма" else "Нет данных о ближайшей литургии",
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("schedule_unknown"))
                            Text(if (church == null) "Так можно увидеть известное время службы. Тексты доступны и без выбора храма"
                                else "Уточните начало в храме или найдите текущий момент по словам службы",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // Kept during the separately verified transition to direct entry in S2.3.
                if (onOpenService == null && suggestion != null && suggestion.service.date == currentDate.toString() &&
                    listOfNotNull(overview.currentHypothesis, overview.nextKnownService).contains(suggestion)) item {
                    OutlinedButton(onClick = onJoinService,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("join_service")) {
                        Text("Сверить момент по времени")
                    }
                }
                if (lastStage != null) item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        GuideActionRow("Сохраненное чтение", lastStage.name, Icons.Default.BookmarkBorder, onResume, "resume_guide")
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column {
                            GuideActionRow("Найти текущий момент", "По ориентиру или словам молитвы", Icons.Default.Search, onFindStage, "find_stage")
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            GuideActionRow("Порядок службы", "Части и этапы литургии", Icons.Default.FormatListNumbered, onOrder, "open_order")
                        }
                    }
                }
            } else {
                item {
                    Text(friendlyDate(currentDate),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onChurch, contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.testTag("open_church")) {
                        Icon(Icons.Default.Place, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(church?.shortName ?: "Выбрать храм", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Литургия", style = MaterialTheme.typography.displaySmall)
                            Text("Иоанна Златоуста", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (lastStage != null && (!savedEarlier || suggestion == null)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (savedEarlier) "Сохранено ранее" else "Вы остановились здесь", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(lastStage.name, style = MaterialTheme.typography.titleLarge)
                            }
                            Button(onClick = onResume, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("resume_guide")) {
                                Text(if (savedEarlier) "Открыть сохраненное" else "Продолжить")
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                            }
                        } else if (suggestion != null) {
                            Text(if (suggestion.relation == ServiceRelation.InProgress)
                                "По расписанию началась в ${suggestion.service.time}. Текущий этап можно уточнить."
                                else "По расписанию начало в ${suggestion.service.time}",
                                style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = onJoinService, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("join_service")) {
                                Text("Я на этой службе")
                            }
                        } else {
                            Text("Найдите момент по тому, что сейчас слышно или видно в храме", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onFindStage, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("find_stage")) {
                                Text("Я в храме")
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                            }
                        }
                    }
                }
                if (savedEarlier && lastStage != null && suggestion != null) item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        GuideActionRow("Сохраненное чтение", lastStage.name, Icons.Default.BookmarkBorder, onResume, "resume_guide")
                    }
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column {
                            if (lastStage != null || suggestion != null) {
                                GuideActionRow("Найти текущий момент", "По ориентиру или словам молитвы", Icons.Default.Search, onFindStage, "find_stage")
                                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            GuideActionRow("Порядок службы", "Части и этапы литургии", Icons.Default.FormatListNumbered, onOrder, "open_order")
                        }
                    }
                }
            }
            item {
                TextButton(onClick = onStart, modifier = Modifier.fillMaxWidth().testTag("start_guide")) { Text("Читать с начала") }
            }
            item {
                Text("Тексты доступны без интернета", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The callback retains this exact occurrence, even when both cards are visible. */
@Composable
private fun ServiceSuggestionCard(suggestion: ContextSuggestion, currentDate: LocalDate, isCurrent: Boolean,
    primaryAction: Boolean, onOpenService: ((ContextSuggestion) -> Unit)?) {
    val key = if (isCurrent) "current" else "next"
    val local = runCatching { suggestion.startInstant.atZone(ZoneId.of(suggestion.timezone)) }.getOrNull()
    val date = local?.toLocalDate() ?: runCatching { LocalDate.parse(suggestion.service.date) }.getOrNull()
    val dateLabel = date?.let { value -> value.format(DateTimeFormatter.ofPattern(
        if (value.year == currentDate.year) "d MMMM" else "d MMMM uuuu", Locale.forLanguageTag("ru"))) } ?: suggestion.service.date
    // Preserve second precision if a published occurrence has it. Personal starts are minute precision.
    val timeLabel = local?.let { value ->
        value.format(when {
            value.nano != 0 -> DateTimeFormatter.ISO_LOCAL_TIME
            value.second != 0 -> DateTimeFormatter.ofPattern("HH:mm:ss")
            else -> DateTimeFormatter.ofPattern("HH:mm")
        })
    } ?: suggestion.service.time
    val offset = local?.offset?.id?.let { if (it == "Z") "+00:00" else it }
    val sourceLabel = if (suggestion.source == ServiceStartSource.USER) "Начало указано вами" else "Начало по опубликованному расписанию"
    Surface(color = if (isCurrent) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large, modifier = Modifier.testTag("service_card_$key")) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (isCurrent) "Возможно, уже идет" else if (suggestion.source == ServiceStartSource.USER)
                "Следующая по вашему времени" else "Следующая по расписанию",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("service_status_$key"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(suggestion.service.title, style = MaterialTheme.typography.titleLarge)
                Text("$dateLabel · $timeLabel", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("service_start_$key"))
                Text(if (offset != null) "Местное время храма · UTC$offset" else "Часовой пояс нужно уточнить",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("service_zone_$key"))
            }
            Text(sourceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("service_source_$key"))
            if (onOpenService != null) {
                if (primaryAction) Button(onClick = { onOpenService(suggestion) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("watch_$key")) { Text("Смотреть службу") }
                else OutlinedButton(onClick = { onOpenService(suggestion) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("watch_$key")) { Text("Смотреть службу") }
            }
        }
    }
}
