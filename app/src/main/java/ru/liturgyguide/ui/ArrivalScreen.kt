package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.LiturgyStage

/** A preview only: neither this snapshot nor navigation writes a service or reading position. */
internal data class ArrivalDraft(
    val churchId: String,
    val churchName: String,
    val serviceDate: String,
    val serviceTime: String,
    val upcoming: Boolean,
    val stageId: String?,
    val elapsedInStageMillis: Long
) {
    companion object {
        val Saver = listSaver<ArrivalDraft?, Any>(
            save = { draft ->
                draft?.let { listOf(it.churchId, it.churchName, it.serviceDate, it.serviceTime,
                    it.upcoming, it.stageId.orEmpty(), it.elapsedInStageMillis) }.orEmpty()
            },
            restore = { values ->
                if (values.isEmpty()) null else ArrivalDraft(values[0] as String, values[1] as String,
                    values[2] as String, values[3] as String, values[4] as Boolean,
                    (values[5] as String).ifEmpty { null }, values[6] as Long)
            }
        )
    }
}

@Composable
internal fun ArrivalScreen(draft: ArrivalDraft, stage: LiturgyStage?, preparationCue: String?, canAccept: Boolean,
    onAccept: () -> Unit, onSearch: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding()) { PageHeader("Сверить момент", onBack) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("arrival_content"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(draft.churchName, style = MaterialTheme.typography.titleLarge)
                    Text("Начало по расписанию в ${draft.serviceTime}", style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Предположение по времени", style = MaterialTheme.typography.labelLarge,
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
                        Text(stage.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("arrival_stage"))
                        stage.cue?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        Text("Сверьте ориентир с тем, что сейчас происходит в храме", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
