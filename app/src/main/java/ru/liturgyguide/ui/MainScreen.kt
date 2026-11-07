package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.service.ContextSuggestion
import ru.liturgyguide.service.ServiceRelation
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun MainScreen(lastStage: LiturgyStage?, onStart: () -> Unit, onResume: () -> Unit, onFindStage: () -> Unit, onSettings: () -> Unit,
    church: Parish?, parishes: ParishRepository?, onChurch: () -> Unit,
    suggestion: ContextSuggestion?, onJoinService: () -> Unit) {
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("main_content"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FeoriaBrand(Modifier.weight(1f))
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("open_settings")) { Icon(Icons.Default.Settings, "Настройки") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Понимать службу", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    Text("Что происходит в храме, о чем звучит молитва и что будет дальше", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (church != null) item {
                TextButton(onClick = onChurch, modifier = Modifier.fillMaxWidth().testTag("open_church")) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(church.shortName, style = MaterialTheme.typography.labelLarge)
                        Text("Изменить храм", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Eyebrow("ПУТЕВОДИТЕЛЬ ПО СЛУЖБЕ")
                        Text("Литургия Иоанна Златоуста", style = MaterialTheme.typography.headlineMedium)
                        if (suggestion != null) {
                            Text(if (suggestion.relation == ServiceRelation.InProgress)
                                "По расписанию литургия началась в ${suggestion.service.time}. Возможно, она сейчас идет."
                                else "Ближайшая литургия по расписанию - в ${suggestion.service.time}", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onJoinService, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("join_service")) {
                                Text("Я на этой службе")
                            }
                        }
                        Text("Найдите этап по словам, которые слышите, или по тому, что видите", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onFindStage, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("find_stage"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
                            Icon(Icons.Default.Search, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Найти текущий этап")
                        }
                        TextButton(onClick = onStart, modifier = Modifier.fillMaxWidth().testTag("start_guide")) {
                            Text("Читать с начала")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (lastStage != null) item {
                OutlinedCard(onClick = onResume, modifier = Modifier.fillMaxWidth().testTag("resume_guide")) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow("ВЫ ОСТАНОВИЛИСЬ ЗДЕСЬ")
                            Text(lastStage.name, style = MaterialTheme.typography.titleMedium)
                            Text("Продолжить чтение", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (church != null && parishes != null) item {
                val date = LocalDate.now(ZoneId.of(church.timezone))
                val schedule = parishes.scheduleFor(church, date)
                OutlinedCard(onClick = onChurch, modifier = Modifier.fillMaxWidth().testTag("church_details")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow("МОЙ ХРАМ")
                        Text(church.shortName, style = MaterialTheme.typography.titleLarge)
                        Text(friendlyDate(date), style = MaterialTheme.typography.bodySmall)
                        Text(if (schedule.isEmpty()) "На сегодня нет подтвержденных данных в приложении" else schedule.joinToString("\n") { "${it.time} · ${it.title}" }, style = MaterialTheme.typography.bodyMedium)
                        Text(if (schedule.isEmpty()) "Проверьте расписание на сайте прихода" else "Только утренние литургии · по фото расписания. Возможны изменения", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Адрес и расписание →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("В храме - без лишних действий", style = MaterialTheme.typography.titleMedium)
                    Text("Тексты доступны без интернета. Выбранный этап сохраняется. В настройках можно увеличить шрифт и включить темный экран.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                Text("Этап можно найти по словам, действиям или короткому аудиофрагменту. Сверяйте выбранное место с тем, что происходит в храме.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
