package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.BookOfflinePartStatus
import ru.liturgyguide.service.book.BookOfflineState
import ru.liturgyguide.service.book.BookPartSetKind

/** A reader can omit these controls; production owns their work outside the reader. */
data class BookOfflineControls(
    val state: BookOfflineState?,
    val busy: Boolean,
    val preparing: Boolean,
    val onCheck: () -> Unit,
    val onPrepare: () -> Unit,
    val onReopenActive: () -> Boolean
)

private fun BookOfflineControls.summary(): String {
    val checked = state ?: return "Проверяем загрузку"
    return when {
        busy && preparing -> "Сохраняем тексты"
        busy -> "Проверяем загрузку"
        checked.contextError != null || checked.parts.any { it.status == BookOfflinePartStatus.STORAGE_ERROR } ->
            "Проверьте загрузку"
        checked.parts.any { it.status == BookOfflinePartStatus.REVIEW_REQUIRED } ->
            "Сохранено ${checked.storedCount} из ${checked.total} · требуется сверка"
        checked.total > 1 -> "Сохранено ${checked.readyCount} из ${checked.total}"
        checked.isComplete -> "Доступен без сети"
        checked.needsRecovery -> "Нужно восстановить текст"
        else -> "Пока не сохранен"
    }
}

@Composable
internal fun BookDetailsButton(controls: BookOfflineControls?, isSaved: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    val summary = controls?.summary() ?: if (isSaved) "Текст доступен без сети" else "Пока не сохранен"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Reserve the same space for every status, including at a large system font.
        val statusLines = if (LocalDensity.current.fontScale > 1.3f || maxWidth < 300.dp) 2 else 1
        OutlinedButton(onClick = onClick, shape = MaterialTheme.shapes.medium,
            modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { stateDescription = summary }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("О дне и службе")
                Text(summary, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = statusLines,
                    maxLines = statusLines, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("book_offline_summary"))
            }
        }
    }
}

@Composable
internal fun BookOfflineSection(activeKey: ServiceBookKey, controls: BookOfflineControls,
    onChoosePart: (ServiceBookKey) -> Unit, onReopenActive: () -> Unit,
    onRefresh: () -> Unit, refreshing: Boolean, onAcceptUpdate: (() -> Unit)? = null) {
    val state = controls.state
    val blocked = controls.busy || refreshing
    Column(Modifier.fillMaxWidth().testTag("book_offline_state").semantics { stateDescription = controls.summary() },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Без сети", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (state == null) Text("Проверяем загрузку на телефоне", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("book_offline_checking"))
        else {
            state.parts.forEach { part ->
                val name = if (state.total > 1) part.key.worship.title else "Текст"
                val feminine = state.total > 1 && part.key.worship in setOf(WorshipKind.LITURGY, WorshipKind.VESPERS, WorshipKind.MATINS)
                val status = when (part.status) {
                    BookOfflinePartStatus.READY -> if (feminine) "сохранена" else "сохранен"
                    BookOfflinePartStatus.MISSING, BookOfflinePartStatus.FAILED,
                    BookOfflinePartStatus.NEEDS_REFRESH -> if (feminine) "не сохранена" else "не сохранен"
                    BookOfflinePartStatus.NEEDS_RECOVERY -> "нужно восстановить"
                    BookOfflinePartStatus.STORAGE_ERROR -> "не удалось проверить"
                    BookOfflinePartStatus.SAVING -> "сохраняем"
                    BookOfflinePartStatus.REVIEW_REQUIRED -> if (feminine) "сохранена - требуется сверка" else "сохранен - требуется сверка"
                }
                Text("$name - $status", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("book_offline_part_${part.key.worship.name}"))
            }
            if (state.isComplete) Text(when {
                state.total == 1 -> "Текст доступен без сети"
                state.partSet == BookPartSetKind.EVENING_TEXTS -> "Оба текста доступны без сети"
                state.partSet == BookPartSetKind.HOURS_LITURGY_TEXTS -> "Три текста сохранены для чтения без сети"
                else -> "Обе части доступны без сети"
            }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("book_offline_complete"))
            (listOfNotNull(state.contextError) + state.parts.mapNotNull { it.message }).distinct().forEach { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("book_offline_message").semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (controls.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("book_offline_progress"))
            if (state != null) Text(if (controls.preparing) "Сохраняем на телефоне" else "Проверяем загрузку",
                style = MaterialTheme.typography.bodyMedium)
        }
        val recovery = state?.parts?.firstOrNull { it.status == BookOfflinePartStatus.NEEDS_RECOVERY }
        val refresh = state?.parts?.firstOrNull { it.status == BookOfflinePartStatus.NEEDS_REFRESH }
        val review = state?.parts?.firstOrNull { it.status == BookOfflinePartStatus.REVIEW_REQUIRED }
        when {
            controls.preparing -> Button(onClick = controls.onPrepare, enabled = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_prepare_service")) {
                Text("Сохраняем...")
            }
            state == null -> Unit
            state.contextError != null || state.total == 0 || state.parts.any { it.status == BookOfflinePartStatus.STORAGE_ERROR } ->
                OutlinedButton(onClick = controls.onCheck, enabled = !blocked,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_recheck_service")) {
                    Text("Проверить еще раз")
                }
            recovery != null -> Button(onClick = {
                if (recovery.key == activeKey) onReopenActive() else onChoosePart(recovery.key)
            }, enabled = !blocked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("book_recover_part_${recovery.key.worship.name}")) {
                Text(if (recovery.key == activeKey) "Открыть текст заново"
                    else bookPartAction(recovery.key.worship))
            }
            refresh != null -> Button(onClick = {
                if (refresh.key != activeKey) onChoosePart(refresh.key)
                else if (onAcceptUpdate != null) onAcceptUpdate() else onRefresh()
            }, enabled = !blocked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(if (onAcceptUpdate != null) "book_offline_accept_update" else "book_offline_refresh_current")) {
                Text(if (refresh.key != activeKey) "Открыть текст"
                    else if (onAcceptUpdate != null) "Открыть обновленный текст"
                    else if (refreshing) "Обновляем текст" else "Обновить открытый текст")
            }
            state.canPrepare -> Button(onClick = controls.onPrepare, enabled = !blocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_prepare_service")) {
                Text(when {
                    state.parts.any { it.status == BookOfflinePartStatus.FAILED } -> "Попробовать еще раз"
                    state.total == 1 -> "Сохранить текст"
                    state.partSet == BookPartSetKind.EVENING_TEXTS -> "Сохранить оба текста"
                    state.partSet == BookPartSetKind.HOURS_LITURGY_TEXTS -> "Сохранить три текста"
                    else -> "Сохранить обе части"
                })
            }
            review != null -> OutlinedButton(onClick = {
                if (review.key != activeKey) onChoosePart(review.key)
                else if (onAcceptUpdate != null) onAcceptUpdate() else onRefresh()
            }, enabled = !blocked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_review_part_${review.key.worship.name}")) {
                Text(if (review.key != activeKey) "Открыть текст для сверки"
                    else if (onAcceptUpdate != null) "Открыть обновленный текст" else "Обновить и проверить текст")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
