package ru.liturgyguide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
                    Text(draft.churchName, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    draft.pendingServiceLabel()?.let { label ->
                        Text(label, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("pending_service_context"))
                    }
                    if (draft.automationStopped) {
                        Text("Автоматические переходы остановлены", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Button(onClick = onSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("manual_search")) {
                    Text("Поиск по словам")
                }
            }
            item {
                Text("Что сейчас слышно или видно?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column {
                        manualLandmarks.forEachIndexed { index, landmark ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(role = Role.Button) { onLandmark(landmark) }
                                .testTag("manual_${landmark.stageId}")
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(landmark.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            if (index < manualLandmarks.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp),
                                color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
