package ru.liturgyguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.ReadingDay
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.service.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceScreen(stageMatcher: StageMatcher, stageId: String?, onStageChange: (String) -> Unit,
    onBack: () -> Unit, onSelectStage: () -> Unit, onFindStage: () -> Unit, onSettings: () -> Unit,
    dark: Boolean, onDarkChange: () -> Unit, onOpenSource: (String) -> Unit, readingDay: ReadingDay?,
    sessionPosition: SessionPosition?, onToggleFollowing: () -> Unit, onSoundSettings: () -> Unit, onFinish: () -> Unit) {
    val stages = stageMatcher.getAllStages()
    val stage = stageId?.let(stageMatcher::getStageById) ?: stages.first()
    val index = stages.indexOf(stage)
    val config = LocalConfiguration.current
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val landscape = config.screenHeightDp < 480
    val compact = landscape || (config.screenHeightDp < 720 && fontScale > 1.2f)
    val tracks = remember(stage, readingDay) { PrayerTrackFactory.build(stage, readingDay) }
    val player = rememberPrayerPlayer(stage.id, tracks)
    val readingMissing = stage.id in setOf("epistle", "gospel") && tracks.none { it.isScripture }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    var scrollOptions by remember { mutableStateOf(false) }
    var stageTimerDialog by remember { mutableStateOf(false) }
    val isScrolling = player?.position?.isPlaying == true
    fun playOrPause() {
        if (player == null) return
        if (isScrolling) player.pause()
        else if (!player.instructionsSeen) scrollOptions = true
        else { if (sessionPosition?.autoAdvanceActive == true) onToggleFollowing(); player.play() }
    }
    fun selectTab(value: Int) { if (value != tab) player?.pause(); tab = value }
    fun clarifyMoment() { player?.pause(); onFindStage() }

    if (tracksOpen && player != null) ModalBottomSheet(onDismissRequest = { tracksOpen = false }) {
        Text("Тексты этого этапа", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineMedium)
        LazyColumn(Modifier.fillMaxWidth().testTag("track_list"), contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(tracks, key = { _, track -> track.id }) { i, track ->
                if (tracks.any { it.isScripture } && (i == 0 || tracks[i - 1].isScripture != track.isScripture)) {
                    Text(if (track.isScripture) "Чтения дня" else "Перед чтением", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
                ListItem(headlineContent = { Text(track.title) }, supportingContent = { Text((if (player.original && track.lines.any { it.original != null }) "Церковнославянский оригинал" else track.subtitle) + if (track.isExcerpt) " · фрагмент" else "") },
                    trailingContent = { if (i == player.trackIndex) Icon(Icons.Default.Check, "Выбранный текст") },
                    modifier = Modifier.clickable { player.selectTrack(i); tracksOpen = false }.testTag("track_$i").semantics { selected = i == player.trackIndex })
            }
        }
    }
    if (scrollOptions && player != null) AlertDialog(onDismissRequest = { scrollOptions = false },
        title = { Text("Автопрокрутка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LyricPlaybackEngine.TEMPOS.forEach { seconds ->
                        FilterChip(selected = player.playback.secondsPerLine == seconds, onClick = { player.tempo(seconds) }, label = { Text("$seconds с") }, modifier = Modifier.testTag("tempo_$seconds"))
                    }
                }
                Text("Время на одну фразу", style = MaterialTheme.typography.bodySmall)
                Text("Фразы сменяются по таймеру, без синхронизации с хором. Нажмите нужную строку, чтобы поправить место.")
                Text("В конце текста прокрутка остановится", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = {
            player.confirmInstructions(); scrollOptions = false
            if (sessionPosition?.autoAdvanceActive == true) onToggleFollowing()
            player.play()
        }, modifier = Modifier.testTag("confirm_scroll")) { Text("Начать прокрутку") } },
        dismissButton = { TextButton(onClick = { player.pause(); scrollOptions = false }, modifier = Modifier.testTag("manual_reading")) { Text("Читать вручную") } })
    if (stageTimerDialog) AlertDialog(onDismissRequest = { stageTimerDialog = false },
        title = { Text("Этапы по времени") },
        text = { Text("Переходы будут следовать примерной длительности частей службы. Они могут опережать происходящее или отставать. Автопрокрутка отдельных строк при этом выключена.", Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { player?.pause(); stageTimerDialog = false; onToggleFollowing() }) { Text("Включить") } },
        dismissButton = { TextButton(onClick = { stageTimerDialog = false }) { Text("Менять этапы самому") } })

    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (compact) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "К службе") }
                Column(Modifier.weight(1f).clickable(onClick = onSelectStage).testTag("open_map").semantics { role = Role.Button; contentDescription = "Порядок службы" }.padding(horizontal = 8.dp, vertical = if (compact) 2.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stage.name, style = MaterialTheme.typography.titleMedium, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).testTag("current_stage_title").semantics { heading() })
                        Icon(Icons.Default.ExpandMore, null, Modifier.padding(start = 4.dp).size(16.dp))
                    }
                    if (!compact) Text("${index + 1} из ${stages.size} · Порядок службы", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (landscape) ReaderTabs(tab, ::selectTab, true, inline = true)
                TextButton(onClick = ::clarifyMoment, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.testTag("clarify_moment")) { Text("Уточнить", maxLines = 1) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("player_menu")) { Icon(Icons.Default.MoreHoriz, "Действия со службой") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (sessionPosition?.autoAdvanceActive == true) "Остановить переходы этапов" else "Этапы по времени") },
                            onClick = { menu = false; if (sessionPosition?.autoAdvanceActive == true) onToggleFollowing() else stageTimerDialog = true }, modifier = Modifier.testTag("toggle_follow"))
                        DropdownMenuItem(text = { Text(if (dark) "Светлый экран" else "Темный экран") }, leadingIcon = { Icon(Icons.Default.Contrast, null) }, onClick = { menu = false; onDarkChange() })
                        if (player != null) DropdownMenuItem(text = { Text("Темп автопрокрутки") }, onClick = { menu = false; scrollOptions = true }, modifier = Modifier.testTag("menu_scroll_options"))
                        DropdownMenuItem(text = { Text("Размер текста и настройки") }, leadingIcon = { Icon(Icons.Default.TextFields, null) }, onClick = { menu = false; player?.pause(); onSettings() })
                        DropdownMenuItem(text = { Text("Тишина на телефоне") }, leadingIcon = { Icon(Icons.Default.NotificationsOff, null) }, onClick = { menu = false; onSoundSettings() })
                    }
                }
            }
            if (sessionPosition?.requiresResume == true) {
                Text("Сохранено ранее · уточните этап",
                    Modifier.fillMaxWidth().clickable(onClick = ::clarifyMoment).padding(horizontal = 24.dp, vertical = 6.dp).testTag("saved_earlier_notice"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            } else if (sessionPosition?.isApproximate == true) {
                Text(if (sessionPosition.autoAdvanceActive) "Примерные переходы этапов включены" else "Этап предложен по времени · можно уточнить",
                    Modifier.fillMaxWidth().clickable(onClick = ::clarifyMoment).padding(horizontal = 24.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            if (!landscape) ReaderTabs(tab, ::selectTab, compact)
            if (player != null) PlayerTrackHeader(player, compact, tab == 0) { player.pause(); tracksOpen = true }
        }
    }, bottomBar = {
        PlayerFooter(player, tab == 0, compact, stages, index, onStageChange, onFinish, ::playOrPause, { scrollOptions = true })
    }) { padding ->
        if (tab == 1) StageMeaning(stage, player?.track, readingMissing, Modifier.padding(padding), onOpenSource)
        else if (player != null) PrayerLyrics(player, compact, readingMissing, Modifier.padding(padding))
        else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("service_content"), contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Text(stage.section, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text(stage.description, style = MaterialTheme.typography.headlineMedium) }
            stage.cue?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item { OutlinedButton(onClick = { selectTab(1) }) { Text("Что происходит и почему") } }
        }
    }
}

@Composable
private fun ReaderTabs(selected: Int, onSelect: (Int) -> Unit, compact: Boolean, inline: Boolean = false) {
    Row((if (inline) Modifier.width(180.dp) else Modifier.fillMaxWidth()).padding(horizontal = if (inline) 4.dp else 24.dp, vertical = if (inline) 0.dp else if (compact) 2.dp else 8.dp)
        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(if (compact) 2.dp else 4.dp)) {
        listOf("Текст", "Смысл").forEachIndexed { index, title ->
            Surface(color = if (selected == index) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(11.dp), modifier = Modifier.weight(1f)) {
                Box(Modifier.clickable { onSelect(index) }.semantics { role = Role.Tab; this.selected = selected == index }
                    .testTag(if (index == 0) "player_text_tab" else "player_meaning_tab").heightIn(min = 48.dp).padding(vertical = if (compact) 4.dp else 10.dp), contentAlignment = Alignment.Center) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StageMeaning(stage: LiturgyStage, track: PrayerTrack?, readingMissing: Boolean, modifier: Modifier, onOpenSource: (String) -> Unit) {
    val scroll = rememberLazyListState()
    var showDayNote by rememberSaveable(track?.id) { mutableStateOf(false) }
    LaunchedEffect(stage.id, track?.id) { scroll.scrollToItem(0) }
    LazyColumn(modifier.fillMaxSize().testTag("service_content"), state = scroll, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        if (readingMissing) item { DailyReadingUnavailable() }
        if (track != null && track.explanation.isNotBlank()) item {
            Text(if (track.isScripture) "О чем это чтение" else "Об этом тексте", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(track.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(track.explanation, Modifier.testTag("track_explanation"), style = MaterialTheme.typography.bodyLarge)
            track.contextNote?.let { note ->
                TextButton(onClick = { showDayNote = !showDayNote }, modifier = Modifier.testTag("reading_note_toggle")) {
                    Text("О чтениях этого дня")
                    Icon(if (showDayNote) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp))
                }
                if (showDayNote) Text(note, Modifier.testTag("reading_day_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            track.sourceUrl?.let { url -> TextButton(onClick = { onOpenSource(url) }) { Text("Источник текста") } }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            Text("Что происходит", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp)); Text(stage.description, style = MaterialTheme.typography.bodyLarge)
        }
        item {
            Text("В чем смысл", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp)); Text(stage.meaning, style = MaterialTheme.typography.bodyLarge)
        }
        stage.cue?.let { cue -> item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Как узнать этот момент", style = MaterialTheme.typography.titleMedium)
                    Text(cue, style = MaterialTheme.typography.bodyLarge)
                }
            }
        } }
        if (stage.whatToDo.isNotBlank()) item {
            Text("На что обратить внимание", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp)); Text(stage.whatToDo, style = MaterialTheme.typography.bodyLarge)
        }
        stage.sourceUrl?.let { url -> item { TextButton(onClick = { onOpenSource(url) }) { Text(stage.sourceTitle ?: "Источник объяснения") } } }
    }
}

@Composable
private fun PlayerTrackHeader(player: PrayerPlayerState, compact: Boolean, showLanguage: Boolean, onTracks: () -> Unit) {
    val readingCount = player.tracks.count { it.isScripture }
    val choiceLabel = if (readingCount > 0) {
        if (compact) "Чтения · $readingCount" else "Выбрать чтение · $readingCount"
    } else if (compact) "Тексты · ${player.tracks.size}" else "Выбрать текст · ${player.tracks.size}"
    Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (compact) {
                if (player.tracks.size > 1) TextButton(onClick = onTracks, modifier = Modifier.weight(1f).testTag("choose_track"),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(player.track.title, Modifier.weight(1f).testTag("current_track_title"),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ExpandMore, "Выбрать текст", Modifier.size(18.dp))
                } else Text(player.track.title, Modifier.weight(1f).padding(vertical = 8.dp).testTag("current_track_title"), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(player.track.title, modifier = Modifier.testTag("current_track_title"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (showLanguage) Text((if (player.original && player.supportsOriginal) "Церковнославянский оригинал" else player.track.subtitle) +
                    if (player.track.isExcerpt) " · фрагмент" else "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showLanguage && player.supportsOriginal) PlayerLanguageMenu(player)
        }
        if (!compact && player.tracks.size > 1) TextButton(onClick = onTracks, modifier = Modifier.testTag("choose_track"),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
            Text(choiceLabel, style = MaterialTheme.typography.bodySmall)
            Icon(Icons.Default.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp))
        }
    }
}

@Composable
private fun PlayerLanguageMenu(player: PrayerPlayerState) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("toggle_original"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            Text(if (player.original) "Оригинал" else "По-русски", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Icon(Icons.Default.ExpandMore, "Выбрать язык текста", Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Смысл по-русски") }, onClick = { if (player.original) player.toggleOriginal(); expanded = false }, modifier = Modifier.testTag("language_russian"))
            DropdownMenuItem(text = { Text("Церковнославянский оригинал") }, onClick = { if (!player.original) player.toggleOriginal(); expanded = false }, modifier = Modifier.testTag("language_original"))
        }
    }
}

@Composable
private fun PlayerFooter(player: PrayerPlayerState?, textVisible: Boolean, compact: Boolean, stages: List<LiturgyStage>, index: Int,
    onStageChange: (String) -> Unit, onFinish: () -> Unit, onPlay: () -> Unit, onTempo: () -> Unit) {
    val scrolling = player?.position?.isPlaying == true
    val nextStage = { if (index < stages.lastIndex) onStageChange(stages[index + 1].id) else onFinish() }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 2.dp else 8.dp)) {
            if (compact && textVisible && player != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) IconButton(onClick = { onStageChange(stages[index - 1].id) }, modifier = Modifier.testTag("previous_stage")) {
                        Icon(Icons.Default.SkipPrevious, "Предыдущий этап")
                    } else Spacer(Modifier.size(48.dp))
                    IconButton(onClick = { player.previous() }, enabled = player.position.lineIndex > 0, modifier = Modifier.testTag("previous_line")) {
                        Icon(Icons.Default.KeyboardArrowUp, "Предыдущая фраза", Modifier.size(28.dp))
                    }
                    FilledTonalIconButton(onClick = onPlay, modifier = Modifier.size(48.dp).testTag("play_lyrics").semantics {
                        stateDescription = (if (scrolling) "Автопрокрутка" else "Вручную") + ", фраза ${player.position.lineIndex + 1} из ${player.track.lines.size}"
                    }) {
                        Icon(if (scrolling) Icons.Default.Pause else if (player.position.atEnd) Icons.Default.Replay else Icons.Default.PlayArrow,
                            if (scrolling) "Остановить прокрутку" else if (player.position.atEnd) "Повторить текст сначала" else "Начать автопрокрутку")
                    }
                    IconButton(onClick = { player.next() }, enabled = player.position.lineIndex < player.track.lines.lastIndex, modifier = Modifier.testTag("next_line")) {
                        Icon(Icons.Default.KeyboardArrowDown, "Следующая фраза", Modifier.size(28.dp))
                    }
                    IconButton(onClick = nextStage, modifier = Modifier.testTag("next_stage")) {
                        Icon(if (index < stages.lastIndex) Icons.Default.SkipNext else Icons.Default.Done,
                            if (index < stages.lastIndex) "Следующий этап: ${stages[index + 1].name}" else "Закончить чтение")
                    }
                }
            } else {
                if (textVisible && player != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        IconButton(onClick = { player.previous() }, enabled = player.position.lineIndex > 0, modifier = Modifier.testTag("previous_line")) {
                            Icon(Icons.Default.KeyboardArrowUp, "Предыдущая фраза", Modifier.size(30.dp))
                        }
                        FilledTonalIconButton(onClick = onPlay, modifier = Modifier.padding(horizontal = 20.dp).size(52.dp).testTag("play_lyrics")) {
                            Icon(if (scrolling) Icons.Default.Pause else if (player.position.atEnd) Icons.Default.Replay else Icons.Default.PlayArrow,
                                if (scrolling) "Остановить прокрутку" else if (player.position.atEnd) "Повторить текст сначала" else "Начать автопрокрутку", Modifier.size(26.dp))
                        }
                        IconButton(onClick = { player.next() }, enabled = player.position.lineIndex < player.track.lines.lastIndex, modifier = Modifier.testTag("next_line")) {
                            Icon(Icons.Default.KeyboardArrowDown, "Следующая фраза", Modifier.size(30.dp))
                        }
                        TextButton(onClick = onTempo, modifier = Modifier.testTag("scroll_options")) {
                            Text(if (scrolling) "${player.playback.secondsPerLine} с" else "Темп", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(if (scrolling) "Автопрокрутка · фраза ${player.position.lineIndex + 1} из ${player.track.lines.size}" else "Фраза ${player.position.lineIndex + 1} из ${player.track.lines.size} · вручную",
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp).testTag("line_status"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) IconButton(onClick = { onStageChange(stages[index - 1].id) }, modifier = Modifier.testTag("previous_stage")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Предыдущий этап", Modifier.size(20.dp))
                    }
                    TextButton(onClick = nextStage, modifier = Modifier.weight(1f).testTag("next_stage")) {
                        Column(Modifier.weight(1f)) {
                            if (index < stages.lastIndex && !compact) Text("Следующий этап", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (index < stages.lastIndex) stages[index + 1].shortTitle else "Закончить чтение", maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 8.dp).size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PrayerLyrics(player: PrayerPlayerState, compact: Boolean, readingMissing: Boolean, modifier: Modifier) {
    val scroll = rememberLazyListState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    val current = player.position.lineIndex
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val lyricSize = if (compact) maxOf(30f / scale, 20f) else 28f
    LaunchedEffect(dragged) { if (dragged) player.pause() }
    LaunchedEffect(player.track.id, current, player.focusRequest) {
        withFrameNanos { }
        val focusOffset = if (compact) 0 else (scroll.layoutInfo.viewportEndOffset / 5).coerceAtLeast(0)
        val item = if (readingMissing && current > 0) current + 1 else current
        scroll.animateScrollToItem(item, scrollOffset = -focusOffset)
    }
    LazyColumn(modifier.fillMaxSize().testTag("lyrics_list"), state = scroll,
        contentPadding = PaddingValues(horizontal = if (compact) 20.dp else 24.dp, vertical = if (compact) 8.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 28.dp)) {
        if (readingMissing) item { DailyReadingUnavailable() }
        itemsIndexed(player.track.lines, key = { _, line -> line.id }) { index, line ->
            val active = current == index
            Text(if (player.original && line.original != null) line.original else line.russian,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { player.seek(index) }
                    .semantics { selected = active; role = Role.Button }.testTag("lyric_line_$index"),
                fontSize = lyricSize.sp, lineHeight = (lyricSize * (if (compact) 1.35f else 1.4f)).sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Column(Modifier.padding(top = 12.dp, bottom = if (compact) 32.dp else 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (player.track.isExcerpt) "Конец фрагмента" else "Конец текста", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (player.trackIndex < player.tracks.lastIndex) {
                    val next = player.tracks[player.trackIndex + 1]
                    OutlinedButton(onClick = { player.selectTrack(player.trackIndex + 1) }, modifier = Modifier.testTag("next_track")) {
                        Text((if (next.isScripture) "Следующее чтение: " else "Следующий текст: ") + next.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyReadingUnavailable() {
    Text("Чтение на сегодня пока не добавлено. Здесь - только возглас перед чтением",
        Modifier.testTag("reading_unavailable"), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
