package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.BookJourneyContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Kept outside the prayer stream: a commemoration is not an extra part of this service. */
@Composable
internal fun BookCalendarContent(book: ServiceBook, onOpenUrl: (String) -> Unit,
    journeyContext: BookJourneyContext? = null, offlineControls: BookOfflineControls? = null,
    onChoosePart: (ServiceBookKey) -> Unit = {}, onReopenActive: () -> Unit = {},
    onRefresh: () -> Unit = {}, refreshing: Boolean = false, onAcceptUpdate: (() -> Unit)? = null,
    dedicationControls: BookDedicationControls? = null, onChooseDedication: () -> Unit = {},
    onReturnDedication: () -> Unit = {}, dedicationReturning: Boolean = false, dedicationActionError: String? = null) {
    val day = book.calendarDay
    val journey = journeyContext?.validatedFor(book.key, journeyContext.parishId)
    var showCommemorations by rememberSaveable(book.contentHash) { mutableStateOf(false) }
    var expandedIndex by rememberSaveable(book.contentHash) { mutableStateOf<Int?>(null) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("book_calendar"),
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("О дне и службе", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(LocalDate.parse(book.key.date).format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru"))),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("book_calendar_date"))
            }
        }
        if (offlineControls != null) item {
            BookOfflineSection(book.key, offlineControls, onChoosePart, onReopenActive,
                onRefresh, refreshing, onAcceptUpdate)
        }
        if (dedicationControls != null) item {
            BookDedicationSourceSection(dedicationControls, onChooseDedication, onReturnDedication,
                dedicationReturning, dedicationActionError, onOpenUrl)
        }
        if (journey != null) item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("В расписании храма", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(journey.title, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("book_calendar_schedule_title"))
                    BookJourneyDescription(journey, book.key)
                }
            }
        } else if (book.key.worship in setOf(WorshipKind.VESPERS, WorshipKind.VIGIL)) item {
            Text("Вечерняя служба относится к следующему церковному дню",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("book_evening_date_note"))
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Порядок службы", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(book.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("book_calendar_service_title"))
                    Text(if (book.key.worship == WorshipKind.LITURGY)
                        "Название литургии обозначает ее чин - порядок молитв. Праздник или память святых этого дня указываются отдельно"
                    else "Название указывает вид службы. Праздник и памяти святых относятся к дате, для которой подготовлен этот текст",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("book_calendar_service_explanation"))
                    book.selectedEventTitle?.takeIf(String::isNotBlank)?.let {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Праздник и памяти в этом последовании", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("book_calendar_selected_event"))
                    }
                }
            }
        }
        if (day != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Церковный календарь", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (day.weekTitle.isNotBlank()) Text(day.weekTitle, style = MaterialTheme.typography.bodyLarge)
                day.voice?.let {
                    Text("Глас $it - одна из восьми групп церковных напевов", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Памяти дня по календарю Азбуки веры. Выбор песнопений в конкретном храме может отличаться",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (day.commemorations.isNotEmpty()) OutlinedButton(onClick = {
                    showCommemorations = !showCommemorations
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_calendar_commemorations")
                    .semantics { stateDescription = if (showCommemorations) "Развернуто" else "Свернуто" }) {
                    Text("Памяти дня (${day.commemorations.size})", Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Icon(if (showCommemorations) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
        }
        if (showCommemorations && day != null) itemsIndexed(day.commemorations, key = { index, _ -> "commemoration-$index" }) { index, commemoration ->
            // Calendar order is not a parish's choice of principal commemoration. Only an
            // explicit tap opens this entry's own description; never borrow a neighbor's.
            val description = commemoration.description?.takeIf(String::isNotBlank)
            val expanded = expandedIndex == index
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().testTag("book_calendar_commemoration_$index")) {
                Column {
                    if (description == null) Text(commemoration.title, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp))
                    else TextButton(onClick = { expandedIndex = if (expanded) null else index },
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("book_calendar_expand_$index")
                            .semantics { stateDescription = if (expanded) "Развернуто" else "Свернуто" }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(commemoration.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                    }
                    if (expanded && description != null) Text(description, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp).testTag("book_calendar_description_$index"))
                }
            }
        }
        item {
            TextButton(onClick = { onOpenUrl(day?.sourceUrl ?: book.sourceUrl) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_calendar_source")) {
                Text(if (day != null) "Открыть источник в Азбуке веры" else "Открыть последование в Азбуке веры")
            }
        }
    }
}
