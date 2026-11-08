package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.LiturgyStage

@Composable
internal fun ArrivalScreen(draft: ArrivalDraft, stage: LiturgyStage?, preparationCue: String?, canAccept: Boolean,
    onAccept: () -> Unit, onSearch: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding()) { PageHeader("Сверить момент", onBack) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("arrival_content"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(draft.churchName, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    draft.pendingServiceLabel()?.let { label ->
                        Text(label, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("pending_service_context"))
                    }
                    if (draft.source == ArrivalSource.SCHEDULE) {
                        Text("Начало по расписанию в ${draft.serviceTime}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (draft.automationStopped) {
                        Text("Автоматические переходы остановлены", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (draft.source == ArrivalSource.SCHEDULE) "Предположение по времени" else "Вы выбрали ориентир", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    if (draft.upcoming) {
                        Text("По расписанию служба еще не началась", style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.testTag("arrival_upcoming"))
                        preparationCue?.let {
                            Text("Ориентир до начала", style = MaterialTheme.typography.titleMedium)
                            Text(it, style = MaterialTheme.typography.bodyLarge)
                        }
                        Text("Можно открыть подготовку или найти момент по тому, что слышно в храме", style = MaterialTheme.typography.bodyLarge)
                    } else if (stage != null) {
                        Text(stage.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("arrival_stage"))
                        stage.cue?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    } else {
                        Text("По времени не удалось определить этап", style = MaterialTheme.typography.headlineSmall)
                        Text("Можно открыть подготовку или найти момент вручную", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (!canAccept) item {
                Text("Предложение больше не актуально. Найдите момент вручную или вернитесь к службе",
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("arrival_expired"))
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onAccept, enabled = canAccept,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("accept_arrival")) {
                        Text(if (draft.stageId == null) "Открыть подготовку" else "Открыть этот момент")
                    }
                    OutlinedButton(onClick = onSearch,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("arrival_search")) {
                        Text("Найти другой момент")
                    }
                }
            }
        }
    }
}
