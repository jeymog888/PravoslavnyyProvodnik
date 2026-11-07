package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.Parish
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ChurchScreen(churches: List<Parish>, selectedId: String?, onSelect: (String) -> Unit, onOpenUrl: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { Column(Modifier.statusBarsPadding()) { PageHeader("Мой храм", onBack) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text("Храм, в который вы ходите", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text("У Елисаветинского храма и приписного храма \"Всецарица\" разные адреса и расписания. Выберите нужный.", style = MaterialTheme.typography.bodyMedium)
            }
            if (churches.any { it.latitude != null && it.longitude != null }) item {
                LocateChurchCard(churches, onSelect)
            }
            items(churches, key = { it.id }) { church ->
                OutlinedCard(onClick = { onSelect(church.id) }, colors = CardDefaults.outlinedCardColors(containerColor =
                    if (selectedId == church.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(church.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            RadioButton(selected = selectedId == church.id, onClick = null)
                        }
                        Text(church.address)
                        Text(church.phone, style = MaterialTheme.typography.bodyMedium)
                        Text(church.scheduleCoverage, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onOpenUrl(church.website) }) { Text("Сайт и актуальное расписание") }
                    }
                }
            }
            item {
                Text("Встроенное расписание взято из присланных изображений на ноябрь 2026. Перед посещением проверьте изменения на сайте прихода. Расписание не подтверждает, что служба уже идет.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun friendlyDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru")))
