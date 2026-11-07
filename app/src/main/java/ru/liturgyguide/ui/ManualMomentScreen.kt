package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class ManualLandmark(val label: String, val stageId: String, val query: String)

private val manualLandmarks = listOf(
    ManualLandmark("Выносят Евангелие", "little_entrance", "Премудрость прости"),
    ManualLandmark("Слышно \"Иже Херувимы\"", "cherubic_hymn", "Иже Херувимы"),
    ManualLandmark("Слышно \"Верую во единаго Бога\"", "creed", "Верую во единаго Бога"),
    ManualLandmark("Слышно \"Отче наш\"", "lords_prayer", "Отче наш"),
    ManualLandmark("Выносят чашу: \"Со страхом Божиим\"", "communion", "Со страхом Божиим")
)

@Composable
internal fun ManualMomentScreen(draft: ArrivalDraft, onLandmark: (ManualLandmark) -> Unit,
    onSearch: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding()) {
            PageHeader(if (draft.mode == ArrivalMode.CORRECTION) "Уточнить момент" else "Найти текущий момент", onBack)
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("manual_content"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(draft.churchName, style = MaterialTheme.typography.titleLarge)
                    if (draft.automationStopped) {
                        Text("Автоматические переходы остановлены", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Выберите то, что сейчас слышно или видно в храме, затем сверьте ориентир",
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Button(onClick = onSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("manual_search")) {
                    Text("Поиск по словам")
                }
            }
            items(manualLandmarks, key = { it.stageId }) { landmark ->
                OutlinedButton(onClick = { onLandmark(landmark) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("manual_${landmark.stageId}"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(landmark.label, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
