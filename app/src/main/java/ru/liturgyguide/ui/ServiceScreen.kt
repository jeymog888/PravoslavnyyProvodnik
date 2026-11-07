package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.liturgyguide.service.StageMatcher
import ru.liturgyguide.model.ReadingDay
import ru.liturgyguide.model.StageText
import ru.liturgyguide.model.DayReading
import androidx.compose.ui.text.style.TextOverflow
import ru.liturgyguide.service.SessionPosition
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.NotificationsOff

@Composable
fun ServiceScreen(
    stageMatcher: StageMatcher, stageId: String?, onStageChange: (String) -> Unit,
    onBack: () -> Unit, onSelectStage: () -> Unit,
    dark: Boolean, onDarkChange: () -> Unit, onOpenSource: (String) -> Unit,
    showTexts: Boolean, onShowTextsChange: () -> Unit, readingDay: ReadingDay?,
    sessionPosition: SessionPosition?, onToggleFollowing: () -> Unit,
    onSoundSettings: () -> Unit, onFinish: () -> Unit
) {
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val stages = stageMatcher.getAllStages()
    val stage = stageId?.let(stageMatcher::getStageById) ?: stages.first()
    val index = stages.indexOf(stage)
    val next = stages.getOrNull(index + 1)
    val previous = stages.getOrNull(index - 1)
    val scroll = rememberLazyListState()
    var confirmFollowing by remember { mutableStateOf(false) }
    val readings = readingDay?.readings.orEmpty().filter {
        (stage.id == "epistle" && it.kind == "epistle") || (stage.id == "gospel" && it.kind == "gospel")
    }
    LaunchedEffect(stage.id) { scroll.scrollToItem(0) }

    if (confirmFollowing) AlertDialog(
        onDismissRequest = { confirmFollowing = false },
        title = { Text("Примерное продвижение") },
        text = { Text("Этапы будут меняться по ориентировочной длительности. Приложение не слышит службу постоянно, поэтому может опережать ее или отставать. Можно поставить паузу и в любой момент поправить этап вручную или по короткому аудиофрагменту.") },
        confirmButton = { TextButton(onClick = { confirmFollowing = false; onToggleFollowing() }) { Text("Включить") } },
        dismissButton = { TextButton(onClick = { confirmFollowing = false }) { Text("Менять этапы самому") } }
    )

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                if (compact) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                        Text("${index + 1} / ${stages.size}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = onSelectStage, modifier = Modifier.testTag("open_map")) { Icon(Icons.Default.FormatListNumbered, "Все этапы") }
                        TextButton(onClick = {
                            if (sessionPosition?.autoAdvanceActive == true) onToggleFollowing() else confirmFollowing = true
                        }, modifier = Modifier.testTag("toggle_follow")) {
                            Icon(if (sessionPosition?.autoAdvanceActive == true) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (sessionPosition?.autoAdvanceActive == true) "Пауза" else "Авто")
                        }
                        IconButton(onClick = onSoundSettings) { Icon(Icons.Default.NotificationsOff, "Настроить режим тишины") }
                        IconButton(onClick = onDarkChange) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Сменить тему") }
                    }
                } else {
                PageHeader("На службе", onBack) {
                    IconButton(onClick = onSoundSettings) { Icon(Icons.Default.NotificationsOff, "Настроить режим тишины") }
                    IconButton(onClick = onDarkChange) {
                        Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            if (dark) "Включить светлую тему" else "Включить темную тему")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSelectStage, modifier = Modifier.weight(1f).testTag("open_map")) {
                        Icon(Icons.Default.FormatListNumbered, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("${index + 1} из ${stages.size} · Все этапы", Modifier.weight(1f))
                    }
                    TextButton(onClick = {
                        if (sessionPosition?.autoAdvanceActive == true) onToggleFollowing() else confirmFollowing = true
                    }, modifier = Modifier.testTag("toggle_follow")) {
                        Icon(if (sessionPosition?.autoAdvanceActive == true) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (sessionPosition?.autoAdvanceActive == true) "Пауза" else "Авто")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    stages.forEachIndexed { i, _ ->
                        Surface(Modifier.weight(1f).height(3.dp), shape = RoundedCornerShape(2.dp),
                            color = when { i == index -> MaterialTheme.colorScheme.primary; i < index -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f); else -> MaterialTheme.colorScheme.outlineVariant }) {}
                    }
                }
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.navigationBarsPadding().padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 6.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (previous != null) OutlinedIconButton(onClick = { onStageChange(previous.id) },
                        modifier = Modifier.size(52.dp).testTag("previous_stage")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Предыдущий этап")
                    }
                    Button(onClick = { if (next != null) onStageChange(next.id) else onFinish() },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("next_stage"),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (next != null && !compact) Text("ДАЛЬШЕ", style = MaterialTheme.typography.labelMedium)
                            Text(next?.shortTitle ?: "Завершить чтение", style = MaterialTheme.typography.labelLarge, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("service_content"), state = scroll,
            contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 12.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!compact) Eyebrow(if (sessionPosition?.isApproximate == true) "ПРИМЕРНЫЙ ЭТАП ПО ВРЕМЕНИ" else stage.section.uppercase())
                    Text(stage.name, style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.testTag("current_stage_title").semantics { heading() })
                    if (compact && sessionPosition?.isApproximate == true) Eyebrow("ПРИМЕРНЫЙ ЭТАП ПО ВРЕМЕНИ")
                    Text(stage.description, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (!stage.cue.isNullOrBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow("КАК УЗНАТЬ ЭТОТ МОМЕНТ")
                        Text(stage.cue, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Простыми словами", style = MaterialTheme.typography.titleLarge)
                    Text(stage.meaning, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (stage.whatToDo.isNotBlank()) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("На что обратить внимание", style = MaterialTheme.typography.titleLarge)
                    Text(stage.whatToDo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (stage.texts.isNotEmpty()) item {
                OutlinedButton(onClick = onShowTextsChange, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("toggle_texts")) {
                    Text(if (showTexts) "Свернуть тексты" else if (readings.isNotEmpty()) "Чтения дня по-русски" else "Смысл молитв по-русски")
                }
            }
            if (showTexts && readings.isNotEmpty() && readingDay != null) {
                item {
                    Text("Чтения на ${friendlyDate(java.time.LocalDate.parse(readingDay.date))}", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(readingDay.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                readings.forEachIndexed { i, reading -> item(key = "daily_${stage.id}_$i") {
                    DayReadingCard(reading, readingDay.sourceUrl, onOpenSource)
                } }
            }
            if (showTexts) stage.texts.forEachIndexed { textIndex, text ->
                item(key = "${stage.id}_text_$textIndex") {
                    PrayerTextCard(text, text.sourceUrl ?: stage.sourceUrl, onOpenSource)
                }
            }
            if (next != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("ЧТО БУДЕТ ДАЛЬШЕ")
                    Text(next.name, style = MaterialTheme.typography.titleMedium)
                    Text(next.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text("Выбранная позиция - ориентир. Порядок и длительность отдельных частей могут отличаться. Можно в любой момент исправить этап.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                stage.sourceUrl?.let { url -> TextButton(onClick = { onOpenSource(url) }) { Text(stage.sourceTitle ?: "Основание объяснения") } }
            }
        }
    }
}

@Composable
private fun PrayerTextCard(text: StageText, sourceUrl: String?, onOpenSource: (String) -> Unit) {
    var showOriginal by rememberSaveable(text.original) { mutableStateOf(false) }
    OutlinedCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("СМЫСЛ ПО-РУССКИ")
            Text(text.translation, style = MaterialTheme.typography.bodyLarge)
            Text(text.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showOriginal = !showOriginal }) {
                Text(if (showOriginal) "Скрыть церковнославянский текст" else "Церковнославянский текст")
            }
            if (showOriginal) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Eyebrow(text.type.uppercase() + if (text.isExcerpt) " · ФРАГМЕНТ" else " · ПОЛНЫЙ ТЕКСТ")
                Text(text.original, style = MaterialTheme.typography.titleLarge)
            }
            sourceUrl?.let { url -> TextButton(onClick = { onOpenSource(url) }) { Text("Источник текста") } }
        }
    }
}

@Composable
private fun DayReadingCard(reading: DayReading, sourceUrl: String, onOpenSource: (String) -> Unit) {
    var expanded by rememberSaveable(reading.reference) { mutableStateOf(false) }
    OutlinedCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(reading.reference, style = MaterialTheme.typography.titleLarge)
            if (expanded) {
                Eyebrow("СИНОДАЛЬНЫЙ ПЕРЕВОД")
                Text(reading.text, style = MaterialTheme.typography.bodyLarge)
                Text(reading.explanation, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onOpenSource(sourceUrl) }) { Text("Календарь и источник чтений") }
            } else Text(reading.explanation, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Свернуть чтение" else "Прочитать по-русски") }
        }
    }
}
