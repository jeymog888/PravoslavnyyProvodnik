package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ContextSuggestion
import ru.liturgyguide.service.ServiceRelation
import java.time.LocalDate

@Composable
fun MainScreen(lastStage: LiturgyStage?, currentDate: LocalDate, savedEarlier: Boolean = false, onStart: () -> Unit, onResume: () -> Unit, onFindStage: () -> Unit,
    church: Parish?, onChurch: () -> Unit, suggestion: ContextSuggestion?, onJoinService: () -> Unit,
    onOrder: () -> Unit, bottomBar: @Composable () -> Unit) {
    Scaffold(bottomBar = bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("main_content"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item {
                FeoriaBrand()
            }
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
