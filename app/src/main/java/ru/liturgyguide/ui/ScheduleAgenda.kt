package ru.liturgyguide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.ScheduleTime
import ru.liturgyguide.service.schedule.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class AgendaDay(val date: LocalDate, val entries: List<ParishService>)
internal data class ScheduleAgenda(val owner: String, val publication: SchedulePublication,
    val sourceUrl: String, val today: LocalDate, val saved: Boolean, val days: List<AgendaDay>) {
    fun sameEdition(other: ScheduleAgenda?) = other != null && owner == other.owner && publication == other.publication &&
        today == other.today && saved == other.saved
    fun permits(other: ScheduleAgenda?, service: ParishService) = sameEdition(other) &&
        other!!.days.any { service in it.entries } && days.any { service in it.entries }
}

/** One publication for the entire list. Never fill a new edition's gaps from Parish.schedule. */
internal fun scheduleAgenda(church: Parish?, state: InternetScheduleState?, now: Instant?): ScheduleAgenda? {
    if (church == null || now == null || state == null || state.parishId != church.id ||
        state.publicationRejected || state.sourceNeedsVerification) return null
    val publication = state.publication?.takeIf { it.parishId == church.id && it.timezone == church.timezone &&
        it.hasCurrentParserRevision && it.sourceUrl == state.sourceUrl } ?: return null
    val source = ScheduleUrl.publicSource(publication.sourceUrl) ?: return null
    val zone = runCatching { ZoneId.of(church.timezone) }.getOrNull() ?: return null
    if (runCatching { Instant.parse(publication.fetchedAt) }.isFailure) return null
    val today = now.atZone(zone).toLocalDate()
    val days = publication.events.mapNotNull { event ->
        val date = ScheduleTime.date(event.date)?.takeIf { it >= today } ?: return@mapNotNull null
        val time = ScheduleTime.publishedTime(event.time) ?: return@mapNotNull null
        if (event.title.isBlank() || event.title.any(Char::isISOControl) || event.kind.isBlank() ||
            ScheduleTime.instant(date, time, zone) == null) return@mapNotNull null
        ParishService(event.date, event.time, event.title, event.kind)
    }.distinct().groupBy { LocalDate.parse(it.date) }.toSortedMap()
        .map { (date, events) -> AgendaDay(date, events.sortedBy { ScheduleTime.publishedTime(it.time) }) }
    if (days.isEmpty()) return null
    // Detach from a caller-owned mutable list before retaining the edition while the modal is open.
    return ScheduleAgenda(ScheduleEntityIdentity.fingerprint(church), publication.copy(events = publication.events.toList()),
        source, today, state.status != InternetScheduleStatus.READY || !publication.isFresh(now), days)
}

/** Retains the displayed edition; revocation immediately disables its actions until dismissal. */
@Composable
internal fun ScheduleAgendaEntry(church: Parish, state: InternetScheduleState?, now: Instant?,
    onOpenService: (ParishService) -> Unit, onOpenUrl: (String) -> Unit, onInteraction: () -> Boolean) {
    val agenda = scheduleAgenda(church, state, now)
    val current by rememberUpdatedState(agenda)
    val currentOpen by rememberUpdatedState(onOpenService)
    val currentUrl by rememberUpdatedState(onOpenUrl)
    val currentInteraction by rememberUpdatedState(onInteraction)
    var opened by remember(church.id) { mutableStateOf<ScheduleAgenda?>(null) }
    if (agenda != null) TextButton(onClick = {
        if (currentInteraction()) opened = current
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("church_agenda_open")) {
        Text("Службы по датам")
    }
    opened?.let { displayed ->
        val valid = displayed.sameEdition(agenda)
        ScheduleAgendaSheet(church.shortName.ifBlank { church.name }, displayed, valid, onDismiss = { opened = null },
            onOpenService = { service ->
                if (displayed.permits(current, service) && currentInteraction() && displayed.permits(current, service)) {
                    opened = null
                    currentOpen(service)
                }
            }, onOpenSource = {
                // The opening already stopped automatic search. Expiring it again here would
                // invalidate Main's current URL callback before recomposition.
                if (displayed.sameEdition(current)) currentUrl(displayed.sourceUrl)
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleAgendaSheet(churchName: String, agenda: ScheduleAgenda, valid: Boolean,
    onDismiss: () -> Unit, onOpenService: (ParishService) -> Unit, onOpenSource: () -> Unit) {
    val locale = Locale.forLanguageTag("ru")
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GuideSheetSystemBars()
        Column(Modifier.fillMaxWidth().testTag("schedule_agenda")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Расписание храма", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("schedule_agenda_close")) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть расписание")
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("schedule_agenda_list"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "source") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(churchName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!valid) Text("Расписание изменилось. Закройте список и откройте его снова", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("schedule_agenda_changed"))
                        if (agenda.saved) Text("Сохраненное расписание - проверьте изменения", style = MaterialTheme.typography.bodyMedium)
                        if (agenda.publication.yearInferred) Text("Год не указан - проверьте дату в расписании", style = MaterialTheme.typography.bodyMedium)
                        if (agenda.publication.knownPartial) Text("Не все записи удалось прочитать", style = MaterialTheme.typography.bodyMedium)
                        val fetched = runCatching { Instant.parse(agenda.publication.fetchedAt).atZone(ZoneId.of(agenda.publication.timezone))
                            .format(DateTimeFormatter.ofPattern("d MMMM uuuu, HH:mm", locale)) }.getOrNull()
                        fetched?.let { Text("Загружено: $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = onOpenSource, enabled = valid,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("schedule_agenda_source")) {
                            Text("Открыть источник расписания")
                        }
                    }
                }
                agenda.days.forEach { day ->
                    item(key = "day:${day.date}") {
                        val prefix = when (day.date) {
                            agenda.today -> "Сегодня"
                            agenda.today.plusDays(1) -> "Завтра"
                            else -> day.date.format(DateTimeFormatter.ofPattern("EEEE", locale)).replaceFirstChar { it.titlecase(locale) }
                        }
                        Text(prefix + ", " + day.date.format(dateFormat) +
                            if (day.date.year != agenda.today.year) " ${day.date.year}" else "",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    }
                    items(day.entries, key = { agendaServiceTag(it) }) { service ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.testTag(agendaServiceTag(service)).clickable(enabled = valid, role = Role.Button) { onOpenService(service) }
                                .heightIn(min = 48.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(service.time, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                val title = serviceKindTitle(service.title) ?: service.title
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                if (title != service.title) Text(service.title, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun agendaServiceTag(service: ParishService) = "schedule_agenda_${service.date}_${service.time}_${service.kind.length}:${service.kind}_${service.title.length}:${service.title}"
