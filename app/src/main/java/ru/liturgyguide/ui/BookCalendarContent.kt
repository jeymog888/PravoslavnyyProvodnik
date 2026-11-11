package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.BookCalendarDay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Kept outside the prayer stream: a commemoration is not an extra part of this service. */
@Composable
internal fun BookCalendarContent(day: BookCalendarDay, onOpenUrl: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("book_calendar"),
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("О церковном дне", style = MaterialTheme.typography.headlineSmall)
                Text(LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru"))),
                    style = MaterialTheme.typography.titleMedium)
                if (day.weekTitle.isNotBlank()) Text(day.weekTitle, style = MaterialTheme.typography.bodyLarge)
                day.voice?.let {
                    Text("Глас $it - один из восьми напевов церковного круга", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Памяти дня по календарю Азбуки веры. Выбор песнопений в конкретном храме может отличаться",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(day.commemorations) { commemoration ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(commemoration.title, style = MaterialTheme.typography.titleMedium)
                    commemoration.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        item {
            TextButton(onClick = { onOpenUrl(day.sourceUrl) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Открыть источник в Азбуке веры")
            }
        }
    }
}
