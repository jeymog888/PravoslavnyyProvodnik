package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.BookJourneyContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EveningServiceChoice(occurrence: BookJourneyContext, onDismiss: () -> Unit,
    onSelect: (WorshipKind) -> Unit) {
    val date = remember(occurrence.civilDate) { LocalDate.parse(occurrence.civilDate)
        .format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru"))) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GuideSheetSystemBars()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("evening_service_choice"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Text("Какая часть идет сейчас?", style = MaterialTheme.typography.headlineSmall) }
            item { Text("$date · ${occurrence.time}\n${occurrence.title}", style = MaterialTheme.typography.bodyMedium) }
            item { Text("Расписание не уточняет порядок вечернего богослужения. Выберите нужный базовый текст. В храме могут быть дополнительные части",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                OutlinedButton(onClick = { onSelect(WorshipKind.VESPERS) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("evening_choose_vespers")) { Text("Вечерня") }
            }
            item {
                OutlinedButton(onClick = { onSelect(WorshipKind.MATINS) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag("evening_choose_matins")) { Text("Утреня") }
            }
            item { Text("Позже можно переключить текст в одно нажатие. Дата текстов - следующий церковный день",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
