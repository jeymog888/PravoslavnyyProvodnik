package ru.liturgyguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liturgyguide.service.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class OpenReadingDetails(val reading: ActiveServicePlayback, val restored: Boolean)

private fun PlaybackMigrationNote.message(): String = when (this) {
    PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE ->
        "Тексты дополнены. Вернулись к последней сохраненной фразе"
}

private fun TextStyle.readingParagraph() = copy(
    hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph, localeList = LocaleList("ru")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceScreen(player: ServicePlayerState, onStageChange: (String) -> Unit,
    onBack: () -> Unit, onSelectStage: () -> Unit, onFindStage: () -> Unit, onSettings: () -> Unit,
    dark: Boolean, onDarkChange: () -> Unit, onOpenSource: (String) -> Unit,
    onSoundSettings: () -> Unit, onFinish: () -> Unit, preferredOriginal: Boolean,
    onOriginalChange: (Boolean) -> Unit, restoredNotice: Boolean = false) {
    val reading = player.reading ?: return
    val generation = player.generation
    val config = LocalConfiguration.current
    val outerDensity = LocalDensity.current
    val fontScale = outerDensity.fontScale
    val landscape = config.screenHeightDp < 480
    val compact = landscape || (config.screenHeightDp < 720 && fontScale > 1.2f)
    val compactControls = compact || fontScale > 1.5f
    val clarifyScaleLimit = if (!landscape && config.screenWidthDp < 400 && fontScale > 1.5f) 1.15f else 1.5f
    val clarifyDensity = Density(outerDensity.density, fontScale.coerceAtMost(clarifyScaleLimit))
    var meaning by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    var tempoOpen by remember { mutableStateOf(false) }
    var details by remember(generation) { mutableStateOf<OpenReadingDetails?>(null) }
    val supportsOriginal = reading.track?.lines?.any { it.original != null } == true
    val original = preferredOriginal && supportsOriginal
    val selectedTab = if (meaning) 2 else if (original) 0 else 1
    val index = reading.plan.stages.indexOf(reading.stage)
    val missing = reading.stage.id in setOf("epistle", "gospel") && reading.tracks.none { it.isScripture }
    fun selectTab(value: Int) {
        if (generation != player.generation) return
        if (value == 2) { player.pause(generation); meaning = true }
        else { meaning = false; onOriginalChange(value == 0) }
    }
    fun clarify() { if (generation == player.generation) onFindStage() }
    fun openTracks() { if (generation == player.generation) { player.pause(generation); tracksOpen = true } }
    fun openDetails() {
        if (generation != player.generation) return
        player.pause(generation)
        if (generation != player.generation) return
        player.reading?.let { details = OpenReadingDetails(it, restoredNotice) }
    }
    fun nextStage() {
        if (generation != player.generation) return
        player.pause(generation)
        if (index < reading.plan.stages.lastIndex) onStageChange(reading.plan.stages[index + 1].id) else onFinish()
    }

    details?.let { snapshot ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text("Открытое чтение") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val date = snapshot.reading.plan.serviceDate.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru")))
                    Text("Тексты на $date", Modifier.testTag("reading_details_date"), style = MaterialTheme.typography.bodyLarge.readingParagraph())
                    snapshot.reading.migrationNote?.let { note ->
                        Text(note.message(), Modifier.testTag("reading_details_migration_notice"),
                            style = MaterialTheme.typography.bodyMedium.readingParagraph())
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Этап", style = MaterialTheme.typography.bodySmall)
                        Text(snapshot.reading.stage.name, Modifier.testTag("reading_details_stage"), style = MaterialTheme.typography.titleMedium)
                    }
                    snapshot.reading.track?.let { track ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Текст", style = MaterialTheme.typography.bodySmall)
                            Text(track.title, Modifier.testTag("reading_details_track"), style = MaterialTheme.typography.bodyLarge.readingParagraph())
                        }
                    }
                    Text(when {
                        snapshot.restored -> "Сохраненное чтение · уточните момент"
                        snapshot.reading.isApproximate -> "Примерное место · можно уточнить"
                        else -> "Чтение на паузе"
                    }, Modifier.testTag("reading_details_status"), style = MaterialTheme.typography.bodyMedium.readingParagraph())
                }
            }, confirmButton = { TextButton(onClick = { details = null }, Modifier.testTag("close_reading_details")) { Text("Закрыть") } })
    }

    if (tracksOpen) ModalBottomSheet(onDismissRequest = { tracksOpen = false }) {
        Text("Тексты этого этапа", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineMedium)
        LazyColumn(Modifier.fillMaxWidth().testTag("track_list"), contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(reading.tracks, key = { _, track -> track.id }) { i, track ->
                if (reading.tracks.any { it.isScripture } && (i == 0 || reading.tracks[i - 1].isScripture != track.isScripture)) {
                    Text(if (track.isScripture) "Чтения дня" else "Перед чтением", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
                ListItem(headlineContent = { Text(track.title) }, supportingContent = {
                    Text(if (track.isScripture) track.subtitle else if (track.isExcerpt) "Фрагмент молитвы" else "Полный текст")
                }, trailingContent = { if (track.id == reading.track?.id) Icon(Icons.Default.Check, "Выбранный текст") },
                    modifier = Modifier.clickable { if (player.selectTrack(track.id, generation)) tracksOpen = false }.testTag("track_$i")
                        .semantics { selected = track.id == reading.track?.id })
            }
        }
    }
    if (tempoOpen) AlertDialog(onDismissRequest = { tempoOpen = false },
        title = { Text("Примерный темп") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Плеер двигает текст без звука. Строки и этапы сменяются по расчету, поэтому скорость чтения и пения в храме может отличаться. Нажмите нужную строку или кнопку \"Уточнить\", чтобы поправить место.")
                PlaybackSpeed.entries.forEach { speed ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { player.changeSpeed(speed, generation) }
                        .testTag("tempo_${speed.name.lowercase()}").semantics { selected = reading.state.speed == speed; role = Role.RadioButton },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reading.state.speed == speed, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(when (speed) { PlaybackSpeed.SLOW -> "Медленнее · 0,75×"; PlaybackSpeed.NORMAL -> "Обычно · 1×"; PlaybackSpeed.FAST -> "Быстрее · 1,25×" })
                    }
                }
                Text("После доступного фрагмента остается ориентир по происходящему. Если чтения на выбранную дату нет, плеер остановится.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { tempoOpen = false }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = { player.pause(generation); tempoOpen = false }, modifier = Modifier.testTag("manual_reading")) { Text("Поставить на паузу") } })

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Column(Modifier.statusBarsPadding()) {
            NavigationTextScale {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (compactControls) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (generation == player.generation) { player.pause(generation); onBack() } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "К службе") }
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { if (generation == player.generation) { player.pause(generation); onSelectStage() } }
                        .testTag("open_map").semantics {
                            role = Role.Button
                            contentDescription = "Порядок службы"
                            stateDescription = "${reading.stage.name}, ${index + 1} из ${reading.plan.stages.size}"
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (landscape) reading.stage.shortTitle else reading.stage.name,
                                Modifier.weight(1f, fill = false).testTag("current_stage_title").semantics { heading() },
                                style = MaterialTheme.typography.titleMedium, maxLines = if (landscape) 1 else Int.MAX_VALUE)
                            Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                        }
                        if (!compactControls) Text("${index + 1} из ${reading.plan.stages.size} · Порядок", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (landscape && (restoredNotice || reading.isApproximate)) PlayerControlScale {
                        Text("Примерно", Modifier.padding(horizontal = 4.dp)
                            .testTag(if (restoredNotice) "saved_earlier_notice" else "approximate_notice"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, Modifier.testTag("player_menu")) { Icon(Icons.Default.MoreHoriz, "Действия со службой") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Открытое чтение") },
                                onClick = { menu = false; openDetails() }, modifier = Modifier.testTag("open_reading_details"))
                            DropdownMenuItem(text = { Text(if (dark) "Светлый экран" else "Темный экран") }, onClick = { menu = false; onDarkChange() })
                            DropdownMenuItem(text = { Text("Размер текста и настройки") }, onClick = { menu = false; if (generation == player.generation) { player.pause(generation); onSettings() } })
                            DropdownMenuItem(text = { Text("Тишина на телефоне") }, onClick = { menu = false; if (generation == player.generation) { player.pause(generation); onSoundSettings() } })
                        }
                    }
                }
            }
            if (!landscape) {
                if (!compactControls) NavigationTextScale {
                    val date = reading.plan.serviceDate.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru")))
                    Text("Тексты на $date", Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("reading_service_date"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (restoredNotice || reading.isApproximate) PlayerControlScale {
                    Text(if (compactControls) "Примерно" else if (restoredNotice) "Сохраненное чтение · уточните момент" else "Примерное место · можно уточнить",
                        Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
                            .testTag(if (restoredNotice) "saved_earlier_notice" else "approximate_notice"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            PlayerControlScale {
                if (landscape) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReaderModes(selectedTab, supportsOriginal, { value -> selectTab(value) }, Modifier.width(300.dp))
                    if (reading.track != null) ReaderTrackHeader(reading, true, { openTracks() }, Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    CompositionLocalProvider(LocalDensity provides clarifyDensity) { ReaderClarifyButton({ clarify() }) }
                } else {
                    ReaderModes(selectedTab, supportsOriginal, { value -> selectTab(value) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (reading.track != null) ReaderTrackHeader(reading, compactControls, { openTracks() }, Modifier.weight(1f))
                        else Spacer(Modifier.weight(1f))
                        CompositionLocalProvider(LocalDensity provides clarifyDensity) { ReaderClarifyButton({ clarify() }) }
                    }
                }
            }
        }
    }, bottomBar = {
        ReaderFooter(reading, player, generation, textVisible = !meaning, compact = compactControls,
            onPreviousStage = { if (generation == player.generation && index > 0) { player.pause(generation); onStageChange(reading.plan.stages[index - 1].id) } },
            onNextStage = { nextStage() }, onTempo = { if (generation == player.generation) tempoOpen = true })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            reading.migrationNote?.let { note ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(note.message(), Modifier.padding(12.dp).testTag("playback_migration_notice"),
                        style = MaterialTheme.typography.bodySmall.readingParagraph(),
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (meaning) ReaderMeaning(reading, missing, Modifier.weight(1f)) { if (generation == player.generation) onOpenSource(it) }
            else Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (compact) 8.dp else 16.dp, vertical = 8.dp)) {
                ReaderLyrics(reading, player, generation, original, compact, missing, { clarify() }, { nextStage() })
            }
        }
    }
}

@Composable
private fun ReaderClarifyButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag("clarify_moment")) { Text("Уточнить", maxLines = 1) }
}

/** Keep short controls usable; the prayer and explanation retain the full requested font scale. */
@Composable
private fun PlayerControlScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.15f)), content = content)
}

@Composable
private fun ReaderModes(selected: Int, originalAvailable: Boolean, onSelect: (Int) -> Unit, modifier: Modifier) {
    Row(modifier.padding(vertical = 6.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(3.dp)) {
        listOf("Оригинал", "По-русски", "Смысл").forEachIndexed { index, label ->
            val enabled = index != 0 || originalAvailable
            Surface(color = if (selected == index) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(13.dp), modifier = Modifier.weight(1f)) {
                TextButton(onClick = { onSelect(index) }, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag(listOf("player_original_tab", "player_text_tab", "player_meaning_tab")[index])
                        .semantics { this.selected = selected == index; role = Role.Tab }) {
                    Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ReaderTrackHeader(reading: ActiveServicePlayback, compact: Boolean, onTracks: () -> Unit, modifier: Modifier) {
    val track = reading.track ?: return
    val position = reading.tracks.indexOf(track) + 1
    val kind = when {
        track.isScripture -> "Чтение дня"
        track.isExcerpt -> "Фрагмент молитвы"
        else -> "Полный текст"
    }
    val repeatsStageTitle = track.title == reading.stage.name
    val label = if (repeatsStageTitle) "$kind · $position из ${reading.tracks.size}" else track.title
    val content: @Composable RowScope.() -> Unit = {
        Column(Modifier.weight(1f)) {
            Text(label, Modifier.testTag("current_track_title"), style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!compact && !repeatsStageTitle) Text(if (track.isScripture) "Чтения дня · ${reading.tracks.count { it.isScripture }}" else kind,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (reading.tracks.size > 1) Icon(Icons.Default.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp))
    }
    if (reading.tracks.size > 1) TextButton(onClick = onTracks, shape = RectangleShape,
        modifier = modifier.heightIn(min = 48.dp).testTag("choose_track")
        .semantics {
            contentDescription = "Выбрать текст: ${track.title}"
            stateDescription = "$position из ${reading.tracks.size}"
        },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp), content = content)
    else Row(modifier.padding(vertical = if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

private fun PlaybackSpeed.label(): String = when (this) { PlaybackSpeed.SLOW -> "0,75×"; PlaybackSpeed.NORMAL -> "1×"; PlaybackSpeed.FAST -> "1,25×" }

@Composable
private fun ReaderFooter(reading: ActiveServicePlayback, player: ServicePlayerState, generation: Int, textVisible: Boolean, compact: Boolean,
    onPreviousStage: () -> Unit, onNextStage: () -> Unit, onTempo: () -> Unit) {
    val index = reading.plan.stages.indexOf(reading.stage)
    val hasNext = index < reading.plan.stages.lastIndex
    val nextTitle = if (hasNext) reading.plan.stages[index + 1].shortTitle else "Закончить чтение"
    val playable = reading.segment is PlaybackSegment.Text || reading.segment is PlaybackSegment.Wait
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        PlayerControlScale {
            Column(Modifier.navigationBarsPadding().padding(horizontal = if (compact || textVisible) 8.dp else 16.dp, vertical = 4.dp)) {
                if (textVisible && compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    if (compact) IconButton(onClick = onPreviousStage, enabled = index > 0, modifier = Modifier.size(48.dp).testTag("previous_stage")) {
                        Icon(Icons.Default.SkipPrevious, "Предыдущий этап")
                    }
                    IconButton(onClick = { reading.track?.let { track -> track.lines.getOrNull(reading.lineIndex - 1)?.let { player.seekLine(track.id, it.id, generation) } } }, enabled = reading.track != null && reading.lineIndex > 0,
                        modifier = Modifier.size(48.dp).testTag("previous_line")) { Icon(Icons.Default.KeyboardArrowUp, "Предыдущая фраза", Modifier.size(28.dp)) }
                    FilledTonalIconButton(onClick = { if (reading.state.isPlaying) player.pause(generation) else player.play(generation) }, enabled = playable,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.size(if (compact) 48.dp else 56.dp).testTag("play_lyrics").semantics {
                            stateDescription = if (reading.state.isPlaying) "Примерное движение включено" else "На паузе"
                        }) {
                        Icon(if (reading.state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (reading.state.isPlaying) "Остановить плеер" else "Включить плеер", Modifier.size(28.dp))
                    }
                    IconButton(onClick = { reading.track?.let { track -> track.lines.getOrNull(reading.lineIndex + 1)?.let { player.seekLine(track.id, it.id, generation) } } },
                        enabled = reading.track?.let { reading.lineIndex < it.lines.lastIndex } == true, modifier = Modifier.size(48.dp).testTag("next_line")) {
                        Icon(Icons.Default.KeyboardArrowDown, "Следующая фраза", Modifier.size(28.dp))
                    }
                    TextButton(onClick = onTempo, contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).testTag("scroll_options")) { Text(reading.state.speed.label(), style = MaterialTheme.typography.bodySmall) }
                    if (compact) IconButton(onClick = onNextStage, modifier = Modifier.size(48.dp).testTag("next_stage")) {
                        Icon(if (hasNext) Icons.Default.SkipNext else Icons.Default.Done, if (hasNext) "Следующий этап: $nextTitle" else nextTitle)
                    }
                }
                if (textVisible && !compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { reading.track?.let { track -> track.lines.getOrNull(reading.lineIndex - 1)?.let { player.seekLine(track.id, it.id, generation) } } },
                        enabled = reading.track != null && reading.lineIndex > 0, modifier = Modifier.size(48.dp).testTag("previous_line")) {
                        Icon(Icons.Default.KeyboardArrowUp, "Предыдущая фраза", Modifier.size(28.dp))
                    }
                    Button(onClick = { if (reading.state.isPlaying) player.pause(generation) else player.play(generation) }, enabled = playable,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).widthIn(min = 144.dp).heightIn(min = 56.dp).testTag("play_lyrics").semantics {
                            stateDescription = if (reading.state.isPlaying) "Примерное движение включено" else "На паузе"
                        }) {
                        Icon(if (reading.state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (reading.state.isPlaying) "Остановить плеер" else "Включить плеер", Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(when (reading.segment) {
                                is PlaybackSegment.Text, is PlaybackSegment.Wait -> if (reading.state.isPlaying) "По расчету" else "На паузе"
                                is PlaybackSegment.MissingReading -> "Нет текста"
                                is PlaybackSegment.End -> "Завершено"
                            }, Modifier.testTag("line_status"), style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                            if (reading.segment !is PlaybackSegment.End) Text(when (reading.segment) {
                                is PlaybackSegment.Text -> "Фраза ${reading.lineIndex + 1}/${reading.track?.lines?.size ?: 0}"
                                is PlaybackSegment.Wait -> "Переходы"
                                is PlaybackSegment.MissingReading -> "Пауза"
                                is PlaybackSegment.End -> ""
                            }, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), maxLines = 1, softWrap = false)
                        }
                    }
                    IconButton(onClick = { reading.track?.let { track -> track.lines.getOrNull(reading.lineIndex + 1)?.let { player.seekLine(track.id, it.id, generation) } } },
                        enabled = reading.track?.let { reading.lineIndex < it.lines.lastIndex } == true,
                        modifier = Modifier.size(48.dp).testTag("next_line")) {
                        Icon(Icons.Default.KeyboardArrowDown, "Следующая фраза", Modifier.size(28.dp))
                    }
                    TextButton(onClick = onTempo, contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.width(48.dp).heightIn(min = 48.dp).testTag("scroll_options")) {
                        Text(reading.state.speed.label(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!compact || !textVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPreviousStage, enabled = index > 0, modifier = Modifier.size(48.dp).testTag("previous_stage")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Предыдущий этап") }
                    TextButton(onClick = onNextStage, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("next_stage")) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (hasNext) Text("Следующий этап", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(nextTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderLyrics(reading: ActiveServicePlayback, player: ServicePlayerState, generation: Int, original: Boolean, compact: Boolean,
    missing: Boolean, onClarify: () -> Unit, onNextStage: () -> Unit) {
    val scroll = rememberLazyListState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val track = reading.track
    val current = reading.lineIndex
    val textSize = if (compact) maxOf(30f / LocalDensity.current.fontScale, 20f) else 28f
    val hasIntro = track != null
    val offset = if (hasIntro) 1 else 0
    val block = reading.segment !is PlaybackSegment.Text
    LaunchedEffect(dragged) { if (dragged) player.pause(generation) }
    LaunchedEffect(track?.id, current, player.focusRequest, original, block) {
        withFrameNanos { }
        val target = if (block) (track?.lines?.size ?: 0) + offset else current + offset
        val inset = if (compact || block) 0 else (scroll.layoutInfo.viewportEndOffset / 4).coerceAtLeast(0)
        scroll.animateScrollToItem(target, scrollOffset = -inset)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("lyrics_list"), state = scroll,
        contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 12.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 28.dp)) {
        if (hasIntro) item(key = "text_note") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (missing) DailyReadingUnavailable()
                else Text(if (track!!.isScripture) "${track.subtitle}. Церковнославянский текст пока не добавлен"
                    else if (original) "Церковнославянский текст" else "Русское изложение смысла молитвы",
                    Modifier.testTag(if (track!!.isScripture) "original_unavailable" else "language_note"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                track?.takeUnless { it.isScripture }?.contextNote?.let { note ->
                    Text(note, Modifier.testTag("prayer_context_note"),
                        style = MaterialTheme.typography.bodySmall.readingParagraph(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(track?.lines.orEmpty(), key = { _, line -> line.id }) { index, line ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                line.speaker?.let { speaker ->
                    Text(speaker, Modifier.testTag("prayer_speaker_$index"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (original) line.original ?: line.russian else line.russian,
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { player.seekLine(requireNotNull(track).id, line.id, generation) }
                        .testTag("lyric_line_$index").semantics { selected = index == current; role = Role.Button },
                    fontSize = textSize.sp, lineHeight = (textSize * 1.4f).sp,
                    style = MaterialTheme.typography.bodyLarge.readingParagraph(),
                    fontWeight = if (index == current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "stage_continuation") {
            if (block) ReaderStageProgress(reading, player, generation, onClarify, onNextStage)
            else Column(Modifier.padding(top = 12.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (track?.isExcerpt == true) "Конец фрагмента" else "Конец текста", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val trackIndex = reading.tracks.indexOf(track)
                if (trackIndex >= 0 && trackIndex < reading.tracks.lastIndex) OutlinedButton(onClick = { player.selectTrack(reading.tracks[trackIndex + 1].id, generation) }, Modifier.testTag("next_track")) {
                    Text("Следующий текст: ${reading.tracks[trackIndex + 1].title}")
                }
            }
        }
    }
}

@Composable
private fun ReaderStageProgress(reading: ActiveServicePlayback, player: ServicePlayerState, generation: Int, onClarify: () -> Unit, onNextStage: () -> Unit) {
    val missing = reading.segment is PlaybackSegment.MissingReading
    val end = reading.segment is PlaybackSegment.End
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(when { missing -> "missing_reading_barrier"; end -> "playback_end"; else -> "stage_wait" })) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(when { missing -> "Текста чтения пока нет"; end -> "Путеводитель завершен"; reading.track == null -> reading.stage.shortTitle; else -> "Дальше слушаем службу" }, style = MaterialTheme.typography.titleLarge)
            if (missing) {
                val date = reading.plan.serviceDate.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru")))
                Text("В приложении нет текста на $date. Плеер на паузе. Когда чтение в храме закончится, можно перейти дальше.")
                Button(onClick = { player.continueAfterMissing(generation) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("continue_missing_reading")) { Text("Чтение в храме закончилось") }
            }
            else if (end) Text("Это конец порядка в приложении. Ориентируйтесь на происходящее в храме.")
            else {
                if (reading.track != null) Text(
                    if (reading.stage.id == reading.plan.stages.last().id)
                        "Доступный фрагмент закончился. Продолжайте слушать заключительные молитвы в храме."
                    else "Доступный фрагмент закончился. Переход к следующему этапу будет примерным.",
                    style = MaterialTheme.typography.bodySmall.readingParagraph())
                Text(reading.stage.description, style = MaterialTheme.typography.bodyLarge.readingParagraph())
                Text(reading.stage.meaning, style = MaterialTheme.typography.bodyLarge.readingParagraph())
            }
            reading.stage.cue?.let {
                Text("Как узнать этот момент", style = MaterialTheme.typography.titleMedium)
                Text(it, style = MaterialTheme.typography.bodyLarge.readingParagraph())
            }
            if (!missing && !end && reading.stage.whatToDo.isNotBlank()) Text(reading.stage.whatToDo, style = MaterialTheme.typography.bodyLarge.readingParagraph())
            if (!missing) OutlinedButton(onClick = onNextStage, modifier = Modifier.fillMaxWidth().testTag("wait_next_stage")) { Text(if (end) "Закончить чтение" else "Перейти дальше вручную") }
            TextButton(onClick = onClarify, modifier = Modifier.fillMaxWidth()) { Text("Уточнить место по службе") }
        }
    }
}

@Composable
private fun ReaderMeaning(reading: ActiveServicePlayback, missing: Boolean, modifier: Modifier, onOpenSource: (String) -> Unit) {
    val scroll = rememberLazyListState()
    val track = reading.track
    var showDayNote by rememberSaveable(track?.id) { mutableStateOf(false) }
    val source = explanationSource(reading.stage.sourceUrl, reading.stage.sourceTitle)
    // The saved-state key includes the source identity, including after activity restoration.
    var sourceExpanded by key(reading.stage.id, reading.stage.sourceUrl) {
        rememberSaveable { mutableStateOf(false) }
    }
    LaunchedEffect(reading.stage.id, track?.id) { scroll.scrollToItem(0) }
    LazyColumn(modifier.fillMaxSize().testTag("service_content"), state = scroll,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { MeaningCard("Что происходит") {
            Text(reading.stage.description, style = MaterialTheme.typography.bodyLarge.readingParagraph())
            if (missing) DailyReadingUnavailable(meaning = true)
        } }
        item { MeaningCard("В чем смысл") { Text(reading.stage.meaning, style = MaterialTheme.typography.bodyLarge.readingParagraph()) } }
        reading.stage.cue?.let { cue -> item { MeaningCard("Как узнать этот момент") { Text(cue, style = MaterialTheme.typography.bodyLarge.readingParagraph()) } } }
        if (reading.stage.whatToDo.isNotBlank()) item { MeaningCard("На что обратить внимание") { Text(reading.stage.whatToDo, style = MaterialTheme.typography.bodyLarge.readingParagraph()) } }
        if (track != null && track.explanation.isNotBlank()) item {
            MeaningCard(if (track.isScripture) "О чем это чтение" else "Об этом тексте") {
                Text(track.explanation, Modifier.testTag("track_explanation"), style = MaterialTheme.typography.bodyLarge.readingParagraph())
                track.contextNote?.let { note ->
                    if (track.isScripture) {
                        TextButton(onClick = { showDayNote = !showDayNote }, Modifier.testTag("reading_note_toggle")) { Text("О чтениях этого дня") }
                        if (showDayNote) Text(note, Modifier.testTag("reading_day_note"), style = MaterialTheme.typography.bodySmall.readingParagraph())
                    } else Text(note, Modifier.testTag("prayer_context_note"),
                        style = MaterialTheme.typography.bodySmall.readingParagraph())
                }
                track.sourceUrl?.let { url -> TextButton(onClick = { onOpenSource(url) }) { Text("Источник текста") } }
            }
        }
        source?.let { publication ->
            item(key = "explanation_source_toggle") {
                TextButton(onClick = { sourceExpanded = !sourceExpanded },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("explanation_source_toggle")
                        .semantics { stateDescription = if (sourceExpanded) "Развернуто" else "Свернуто" }) {
                    Text("Источник пояснения", Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Icon(if (sourceExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            if (sourceExpanded) item(key = "explanation_source_details") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Публикация, использованная при подготовке пояснения",
                        style = MaterialTheme.typography.bodySmall.readingParagraph(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(publication.title, Modifier.testTag("explanation_source_title"),
                        style = MaterialTheme.typography.bodyLarge.readingParagraph())
                    publication.siteLabel?.let { label ->
                        Text(label, Modifier.testTag("explanation_source_site"),
                            style = MaterialTheme.typography.bodyMedium.readingParagraph(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onOpenSource(publication.url) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("explanation_source_open")) {
                        Text("Открыть публикацию")
                    }
                }
            }
        }
    }
}

@Composable
private fun MeaningCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DailyReadingUnavailable(meaning: Boolean = false) {
    Text(if (meaning) "Текста чтения на эту дату пока нет. Здесь объясняется этот этап службы"
        else "Чтение на эту дату пока не добавлено. Здесь - только возглас перед чтением", Modifier.testTag("reading_unavailable"),
        style = MaterialTheme.typography.bodySmall.readingParagraph(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
