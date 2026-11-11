package ru.liturgyguide.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.service.PublishedSchedule
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.SchedulePublication
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A selected day stays a selected day. A future occurrence never silently replaces its date. */
internal data class ArrivalSchedule(
    val entries: List<ParishService>, val featured: ParishService?, val relation: String?,
    val saved: Boolean, val publication: SchedulePublication?
)

internal fun arrivalSchedule(church: Parish, date: LocalDate, now: Instant?, state: InternetScheduleState?): ArrivalSchedule {
    val ownState = state?.takeIf { it.parishId == church.id }
    val currentSource = ownState?.sourceUrl.orEmpty()
    val publication = ownState?.takeUnless { it.publicationRejected }?.publication?.takeIf {
        it.parishId == church.id && it.timezone == church.timezone &&
            (currentSource.isBlank() || currentSource == it.sourceUrl)
    }
    val published = PublishedSchedule.forDate(church, date)
    val cached = publication?.forDate(date).orEmpty().filter { it.title.isNotBlank() && runCatching { LocalTime.parse(it.time) }.isSuccess }
        .map { ParishService(it.date, it.time, it.title, it.kind) }
    val entries = published.ifEmpty { cached }.distinctBy { "${it.date}|${it.time}|${it.title}" }.sortedBy { it.time }
    val saved = published.isEmpty() && cached.isNotEmpty()
    val localNow = now?.let { runCatching { it.atZone(ZoneId.of(church.timezone)) }.getOrNull() }
    val today = localNow?.toLocalDate() == date
    val recent = if (today && !saved) entries.lastOrNull { service ->
        runCatching {
            val elapsed = Duration.between(LocalTime.parse(service.time), localNow!!.toLocalTime())
            !elapsed.isNegative && elapsed <= Duration.ofHours(2)
        }.getOrDefault(false)
    } else null
    val upcoming = if (today) entries.firstOrNull { service ->
        runCatching { LocalTime.parse(service.time) > localNow!!.toLocalTime() }.getOrDefault(false)
    } else null
    val featured = recent ?: upcoming ?: if (today) entries.lastOrNull() else entries.firstOrNull()
    val inferredYear = publication?.yearInferred == true && (saved || publication.sourceUrl == church.sourceUrl)
    val relation = featured?.let {
        when {
            saved -> "Сохраненное расписание - проверьте изменения"
            inferredYear -> "Время из расписания храма"
            localNow == null -> "По расписанию храма"
            recent != null -> "Возможно, идет · по времени начала"
            today && upcoming == null -> "Начало уже прошло"
            localNow != null && date < localNow.toLocalDate() -> "Служба на выбранную дату"
            else -> "Ближайшая служба по расписанию"
        }
    }
    return ArrivalSchedule(entries, featured, relation, saved, publication)
}

/** No data is not the same thing as no services. Keep the distinction visible beside the date. */
internal fun scheduleEmptyDescription(state: InternetScheduleState?): String = when {
    state?.loading == true -> "Проверяем сайт храма. Тексты ниже уже можно читать"
    state?.status == InternetScheduleStatus.UNAVAILABLE -> "Не удалось обновить расписание. Попробуйте еще раз или откройте сайт храма"
    state?.status == InternetScheduleStatus.NO_WEBSITE -> "Пока не нашли источник расписания. Можно указать сайт в сведениях о храме"
    state?.status == InternetScheduleStatus.UNSUPPORTED -> "На сайте не удалось прочитать даты и время. Расписание можно посмотреть на самом сайте"
    else -> "Пока нет подтвержденных записей на эту дату. Это не означает, что служб не будет"
}

internal fun scheduleEmptyTitle(state: InternetScheduleState?, hasPublication: Boolean): String = when {
    state?.loading == true -> "Ищем расписание"
    hasPublication -> "Нет записей на эту дату"
    else -> "Расписание пока не найдено"
}

@Composable
internal fun ArrivalCard(modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
internal fun ArrivalAction(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, subtitle: String? = null) {
    val enlargedText = LocalDensity.current.fontScale >= 1.3f
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!enlargedText) Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (!enlargedText) Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun TypicalScheduleContent(date: LocalDate) {
    var expanded by rememberSaveable(date.toString()) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column {
            Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("typical_schedule")
                    .semantics { stateDescription = if (expanded) "Развернуто" else "Свернуто" }) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Как обычно проходят службы", style = MaterialTheme.typography.titleMedium)
                        Text("Это типовой порядок, не расписание храма", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            if (expanded) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Суббота вечером", style = MaterialTheme.typography.labelLarge)
                    Text("Вечернее богослужение", style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Воскресенье утром", style = MaterialTheme.typography.labelLarge)
                    Text("Литургия", style = MaterialTheme.typography.bodyLarge)
                }
                Text("Службы в будни и точное время зависят от прихода", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
