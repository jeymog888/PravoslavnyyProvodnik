package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.ScheduleIdentityOutcome
import ru.liturgyguide.service.schedule.ScheduleSourceChangeBlock
import ru.liturgyguide.service.schedule.candidateChangeBlockedByHistory

@Composable
internal fun ScheduleSourceChangeNotice(state: InternetScheduleState) {
    if (state.sourceChangeBlock != ScheduleSourceChangeBlock.NONE) Text(
        when (state.sourceChangeBlock) {
            ScheduleSourceChangeBlock.HISTORY -> "В прежнем источнике есть отмены или изменения. Обновите его перед сменой сайта"
            else -> "Этот сайт не подходит выбранному храму. Проверьте название и адрес"
        }, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().testTag("schedule_source_change_blocked"))
}

/** All actions belong to the visible selected fingerprint, with no URL typing required. */
@Composable
internal fun ScheduleCandidateChoices(church: Parish, state: InternetScheduleState,
    onConfirm: (String) -> Unit, onDismiss: ((String) -> Unit)?, onOpenUrl: (String) -> Unit) {
    val approved = state.siteBinding?.candidateConfirmation?.candidate?.id
    val candidates = state.sourceCandidates.filter { it.validFor(church) && it.id !in state.dismissedCandidateIds && it.id != approved }
    if (candidates.isEmpty()) {
        if (state.candidateSearchUnavailable && !state.loading) Text(
            "Не удалось проверить источники расписания. Повторите поиск",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("schedule_candidates_unavailable"))
        return
    }
    Column(Modifier.fillMaxWidth().testTag("schedule_candidate_choices"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Найденные источники", style = MaterialTheme.typography.titleMedium)
        Text("Выбранный храм: " + church.name, style = MaterialTheme.typography.bodyMedium)
        churchAddressLine(church).takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Проверьте название и адрес на сайте. Подтверждайте только расписание выбранного храма",
            style = MaterialTheme.typography.bodyMedium)
        if (state.candidateSearchUnavailable) Text("Не все источники удалось проверить. Можно повторить поиск",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("schedule_candidates_unavailable"))
        candidates.forEach { candidate ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("schedule_candidate_${candidate.id}")) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(candidate.name, style = MaterialTheme.typography.titleMedium)
                    Text(candidate.address.ifBlank { "Адрес в карточке не указан" }, style = MaterialTheme.typography.bodyMedium)
                    if (candidate.currentContactAddress.isNotBlank()) Text("В текущих контактах: " + candidate.currentContactAddress,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(candidate.providerLabel, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(candidate.sourceUrl, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("schedule_candidate_url_${candidate.id}"))
                    Text(candidate.reason, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { onOpenUrl(candidate.sourceUrl) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open_candidate_${candidate.id}")) {
                        Text("Открыть источник")
                    }
                    if (candidate.outcome == ScheduleIdentityOutcome.CONFLICT) {
                        Text("Этот источник нельзя подтвердить: сведения о храме противоречат друг другу",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("schedule_candidate_conflict_${candidate.id}"))
                    } else {
                        val blockedByHistory = candidateChangeBlockedByHistory(state, candidate)
                        if (blockedByHistory) Text("Прежний источник сообщил об изменении расписания. Сначала обновите его",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("schedule_candidate_history_${candidate.id}"))
                        Button(onClick = { onConfirm(candidate.id) }, enabled = !state.loading && !blockedByHistory && candidate.canConfirm(church),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("confirm_candidate_${candidate.id}")) {
                            Text(if (state.loading) "Проверяем источник" else "Это расписание моего храма")
                        }
                    }
                    onDismiss?.let { dismiss ->
                        OutlinedButton(onClick = { dismiss(candidate.id) }, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dismiss_candidate_${candidate.id}")) {
                            Text("Не использовать этот источник")
                        }
                    }
                }
            }
        }
    }
}
