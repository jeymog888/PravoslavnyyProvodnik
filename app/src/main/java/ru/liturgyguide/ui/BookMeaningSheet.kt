package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liturgyguide.service.book.BookMeaningContent
import ru.liturgyguide.service.book.BookMeaningCueKind

/** The modal's scroll is independent of the mounted book, including its inter-paragraph gaps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookMeaningSheet(content: BookMeaningContent, textSize: Int,
    onDismiss: () -> Unit, onOpenUrl: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        GuideSheetSystemBars()
        BookMeaningPanel(content, textSize, onDismiss, onOpenUrl)
    }
}

@Composable
internal fun BookMeaningPanel(content: BookMeaningContent, textSize: Int,
    onDismiss: () -> Unit, onOpenUrl: (String) -> Unit) {
    var expanded by rememberSaveable(content.position) { mutableStateOf(false) }
    var fullExcerpt by rememberSaveable(content.position) { mutableStateOf(false) }
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = (19 + textSize * 2).sp,
        lineHeight = (29 + textSize * 3).sp)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .9f)
        .testTag("book_meaning_sheet").semantics { paneTitle = "Об этой части службы" }) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scroll)
            .padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("book_meaning_content"),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (content.privateText) Text("О тихой молитве",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(content.chapterTitle, style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("book_meaning_title").semantics { heading() })
                Text("Объяснение Феории", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content.brief?.let { brief ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Что происходит", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(brief.whatHappens, style = body, modifier = Modifier.testTag("book_meaning_what"))
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Зачем", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(brief.why, style = body, modifier = Modifier.testTag("book_meaning_why"))
                }
                brief.cue?.let { cue ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(when (cue.kind) {
                            BookMeaningCueKind.HEAR -> "Можно услышать"
                            BookMeaningCueKind.SEE -> "Можно увидеть"
                            BookMeaningCueKind.QUIET -> "Может звучать тихо"
                        }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(cue.text, style = body, modifier = Modifier.testTag("book_meaning_cue"))
                    }
                }
                OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("book_meaning_more").semantics { stateDescription = if (expanded) "Развернуто" else "Свернуто" }) {
                    Text(if (expanded) "Свернуть" else "Подробнее", Modifier.weight(1f))
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            if (content.brief == null || expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(content.detail.takeIf { it.isNotBlank() } ?: "Для этой части пока нет пояснения",
                    style = body, modifier = Modifier.testTag("book_meaning_detail"))
                content.brief?.let { brief ->
                    TextButton(onClick = { onOpenUrl(brief.sourceUrl) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("book_meaning_source")) { Text("Источник пояснения") }
                }
            }
            content.excerpt?.let { excerpt ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Из открытого текста", style = MaterialTheme.typography.labelLarge)
                    Text(excerpt, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (fullExcerpt) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("book_meaning_excerpt"))
                    TextButton(onClick = { fullExcerpt = !fullExcerpt }, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("book_meaning_excerpt_toggle").semantics {
                            stateDescription = if (fullExcerpt) "Развернуто" else "Свернуто"
                        }) { Text(if (fullExcerpt) "Свернуть фрагмент" else "Показать весь фрагмент") }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
            .heightIn(min = 48.dp).testTag("book_meaning_close")) { Text("К тексту") }
    }
}
