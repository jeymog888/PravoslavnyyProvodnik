package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.*

@Composable
internal fun ScheduleSourceChangeNotice(state: InternetScheduleState) {
    if (state.sourceChangeBlock != ScheduleSourceChangeBlock.NONE) Text(
        when (state.sourceChangeBlock) {
            ScheduleSourceChangeBlock.HISTORY -> "В прежнем источнике есть отмены или изменения. Обновите его перед сменой сайта"
            else -> "Этот сайт не подходит выбранному храму. Проверьте название и адрес"
        }, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().testTag("schedule_source_change_blocked"))
}

/** Group only the presentation. Every action still owns a current raw candidate ID. */
@Composable
internal fun ScheduleCandidateChoices(church: Parish, state: InternetScheduleState,
    onConfirm: (String) -> Unit, onDismiss: ((String) -> Unit)?, onOpenUrl: (String) -> Unit,
    stableSourceOrder: List<String>? = null, showSearchDiagnostic: Boolean = true) {
    val currentGroups = scheduleCandidateGroups(church, state)
    val groups = if (stableSourceOrder != null) currentGroups.sortedBy { group ->
        stableSourceOrder.indexOf(group.sourceUrl).takeIf { it >= 0 } ?: Int.MAX_VALUE
    } else currentGroups
    val currentChurch by rememberUpdatedState(church)
    val currentState by rememberUpdatedState(state)
    val currentConfirm by rememberUpdatedState(onConfirm)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentOpen by rememberUpdatedState(onOpenUrl)
    var mounted by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { mounted = false } }
    if (groups.isEmpty()) {
        if (state.parishId == church.id && state.candidateSearchUnavailable && !state.loading) Text(
            "Не удалось проверить источники расписания. Повторите поиск",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("schedule_candidates_unavailable"))
        return
    }
    val owner = ScheduleEntityIdentity.fingerprint(church)
    fun liveGroup(url: String): ScheduleCandidateGroup? =
        if (!mounted || ScheduleEntityIdentity.fingerprint(currentChurch) != owner) null else scheduleCandidateGroups(currentChurch, currentState)
            .singleOrNull { it.sourceUrl == url }

    Column(Modifier.fillMaxWidth().testTag("schedule_candidate_choices"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Найденные сайты", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("schedule_sources_heading").semantics { heading() })
        Text("Выбранный храм: " + church.name, style = MaterialTheme.typography.bodyMedium)
        churchAddressLine(church).takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Откройте сайт и сверьте название и адрес храма. Если они совпадают, нажмите \"Выбрать сайт\" - приложение попробует загрузить расписание",
            style = MaterialTheme.typography.bodyMedium)
        if (showSearchDiagnostic && state.candidateSearchUnavailable) Text("Не все источники удалось проверить. Можно повторить поиск",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("schedule_candidates_unavailable"))
        groups.forEach { group -> key(owner, group.sourceUrl) {
            val row = group.representative
            var expanded by rememberSaveable(church.id, group.sourceUrl) { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth().testTag("schedule_source_group_${row.id}")) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    if (row.currentContactAddress.isNotBlank()) {
                        Text("В текущих контактах: " + row.currentContactAddress, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("source_current_address_${row.id}"))
                        if (row.address.isNotBlank() && row.address != row.currentContactAddress)
                            Text("В справочнике: " + row.address, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text(row.address.ifBlank { "Адрес в карточке не указан" }, style = MaterialTheme.typography.bodyMedium)
                    Text(group.sourceUrl, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("schedule_candidate_url_${row.id}"))
                    Text(if (group.providerCount > 1) "Найден в ${group.providerCount} справочниках" else row.providerLabel,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (group.alreadyChosen) Text(if (group.conflicts.isEmpty()) "Этот сайт уже выбран"
                        else "Ранее выбранный сайт требует проверки", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("schedule_source_already_chosen_${row.id}"))
                    if (group.conflicts.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("schedule_candidate_conflict_${row.id}")) {
                        Text("Этот сайт нельзя подтвердить: сведения о храме противоречат друг другу",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        group.conflicts.forEach { conflict ->
                            Text(conflict.providerLabel + ": " + conflict.reason, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(listOf(conflict.name, conflict.address).filter(String::isNotBlank).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                            if (conflict.sourceUrl != group.sourceUrl) Text(conflict.sourceUrl,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (group.blockedByHistory) Text("Прежний источник сообщил об изменении расписания. Сначала обновите его",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("schedule_candidate_history_${row.id}"))
                    OutlinedButton(onClick = { if (liveGroup(group.sourceUrl) != null) currentOpen(group.sourceUrl) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open_candidate_${row.id}")) {
                        Text("Открыть сайт")
                    }
                    group.confirmationCandidate?.let { candidate ->
                        // Composition identity only. The complete live candidate stays in the action below.
                        key(owner, candidate.copy(checkedAt = "")) {
                        Button(onClick = {
                            val live = liveGroup(group.sourceUrl)
                            if (!currentState.loading && live != null && live.confirmationCandidate == candidate && !live.blockedByHistory)
                                currentConfirm(candidate.id)
                        }, enabled = !state.loading && !group.blockedByHistory,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("confirm_candidate_${candidate.id}")) {
                            Text(if (state.loading) "Проверяем источник" else "Выбрать сайт")
                        }
                    } }
                    TextButton(onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("source_details_${row.id}")
                            .semantics { stateDescription = if (expanded) "Развернуто" else "Свернуто" }) {
                        Text("Сведения об источнике", Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    if (expanded) group.rows.forEach { raw ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.fillMaxWidth().testTag("schedule_candidate_detail_${raw.id}"),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(raw.providerLabel, style = MaterialTheme.typography.titleSmall)
                            Text(raw.name, style = MaterialTheme.typography.bodyMedium)
                            Text(raw.address.ifBlank { "Адрес в карточке не указан" }, style = MaterialTheme.typography.bodyMedium)
                            if (raw.currentContactAddress.isNotBlank()) Text("В текущих контактах: " + raw.currentContactAddress,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(raw.reason, style = MaterialTheme.typography.bodyMedium)
                            val approved = state.siteBinding?.candidateConfirmation?.candidate?.id == raw.id
                            val dismissed = raw.id in state.dismissedCandidateIds
                            if (approved) Text("Выбранная карточка", style = MaterialTheme.typography.labelLarge)
                            if (dismissed) Text("Эта карточка отклонена", style = MaterialTheme.typography.labelLarge)
                            if (!approved && !dismissed && onDismiss != null) key(owner, raw) { OutlinedButton(onClick = {
                                val live = liveGroup(group.sourceUrl)
                                val latest = live?.rows?.singleOrNull { it.id == raw.id }
                                if (!currentState.loading && latest == raw && raw.id !in currentState.dismissedCandidateIds &&
                                    currentState.siteBinding?.candidateConfirmation?.candidate?.id != raw.id)
                                    currentDismiss?.invoke(raw.id)
                            }, enabled = !state.loading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dismiss_candidate_${raw.id}")) {
                                Text("Не использовать эту карточку")
                            } }
                        }
                    }
                }
            }
        } }
    }
}
