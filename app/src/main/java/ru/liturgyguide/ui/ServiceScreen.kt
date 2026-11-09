package ru.liturgyguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import ru.liturgyguide.service.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class OpenReadingDetails(val reading: ActiveServicePlayback, val restored: Boolean)

private data class ReaderFeedOwner(val generation: Int, val fingerprint: String, val stageId: String) {
    fun matches(player: ServicePlayerState): Boolean = player.generation == generation &&
        player.reading?.let { it.plan.fingerprint == fingerprint && it.stage.id == stageId } == true
}

private data class ReaderLanguageAnchor(val request: Int, val key: String, val original: Boolean)

/** Transient viewport intent only. The accepted position belongs exclusively to the player. */
@Stable
private class ReaderViewport(val owner: ReaderFeedOwner) {
    val scroll = LazyListState()
    var manual by mutableStateOf(false)
        private set
    var focusRequest by mutableIntStateOf(0)
        private set
    var initiallyFocused by mutableStateOf(false)
    var focusing by mutableStateOf(false)
    var bounds by mutableStateOf<Rect?>(null)
    var phraseBounds by mutableStateOf<Pair<String, Rect>?>(null)
    val visibleLineBounds = mutableMapOf<String, Rect>()
    var focusJob: Job? = null
    var languageAnchor by mutableStateOf<ReaderLanguageAnchor?>(null)
        private set
    var languageJob: Job? = null
    private var languageRequest = 0

    fun cancelLanguageAnchor() {
        languageAnchor = null
        languageJob?.cancel()
    }

    fun preserveLanguageAnchor(original: Boolean) {
        cancelLanguageAnchor()
        if (!manual) return
        val items = scroll.layoutInfo.visibleItemsInfo
        val readingArea = bounds
        // A partly visible header can be much taller in the other language. Keep the
        // words the reader can actually see, falling back to the header only without a line.
        val key = items.firstOrNull { item ->
            val line = (item.key as? String)?.let(visibleLineBounds::get)
            line != null && readingArea != null &&
                readerTextIntersectsViewport(line.top, line.bottom, readingArea.top, readingArea.bottom)
        }?.key as? String ?: items.firstOrNull { it.index == scroll.firstVisibleItemIndex }?.key as? String ?: return
        languageAnchor = ReaderLanguageAnchor(++languageRequest, key, original)
    }

    fun finishLanguageAnchor(request: ReaderLanguageAnchor) {
        if (languageAnchor == request) languageAnchor = null
    }

    fun browse() {
        cancelLanguageAnchor()
        manual = true
        // A user may interrupt the first animated focus after the list has been laid out.
        // They must still be able to return when that interrupted focus never completes.
        if (bounds != null) initiallyFocused = true
        focusJob?.cancel()
    }

    fun requestFocus() {
        cancelLanguageAnchor()
        focusJob?.cancel()
        manual = false
        focusRequest++
    }

    fun canReturn(text: PlaybackSegment.Text?): Boolean {
        if (text == null || !initiallyFocused || focusing) return false
        val viewport = bounds ?: return false
        if (scroll.layoutInfo.visibleItemsInfo.none { it.key == "line:${text.lineId}" }) return true
        val phrase = phraseBounds?.takeIf { it.first == text.lineId }?.second ?: return false
        return !readerTextIntersectsViewport(phrase.top, phrase.bottom, viewport.top, viewport.bottom)
    }
}

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
    onOriginalChange: (Boolean) -> Unit, restoredNotice: Boolean = false, onMicrophone: () -> Unit = onFindStage) {
    val reading = player.reading ?: return
    val generation = player.generation
    val requestedDensity = LocalDensity.current
    var meaning by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    var tempoOpen by remember { mutableStateOf(false) }
    var details by remember(generation) { mutableStateOf<OpenReadingDetails?>(null) }
    val owner = ReaderFeedOwner(generation, reading.plan.fingerprint, reading.stage.id)
    var controlsOpen by remember(owner) { mutableStateOf(false) }
    val viewport = remember(owner) { ReaderViewport(owner) }
    val feed = remember(owner) { ReaderFeed(reading.stage.id, reading.tracks) }
    val supportsOriginal = feed.supportsOriginal
    val original = preferredOriginal && supportsOriginal
    val selectedTab = if (meaning) 2 else if (original) 0 else 1
    val index = reading.plan.stages.indexOf(reading.stage)
    val missing = reading.stage.id in setOf("epistle", "gospel") && reading.tracks.none { it.isScripture }
    val presentation = readerPresentation(reading, meaning)
    val visibleStatus = readerVisibleStatus(reading, presentation, restoredNotice, original)
    // Sizing samples only: no engine tick or player write. Both labels must fit before
    // Play/Pause, otherwise a shorter status enlarges the list and moves its end position.
    val transportSamples = if (presentation.kind in setOf(ReaderStateKind.TEXT, ReaderStateKind.WAIT, ReaderStateKind.BEFORE_START))
        listOf<Long?>(null, 0L).map { reading.copy(state = reading.state.copy(anchorMonotonicMillis = it), isApproximate = true) }
    else emptyList()
    val statusSamples = listOf(visibleStatus) + transportSamples.map {
        readerVisibleStatus(it, readerPresentation(it, false), restoredNotice, original)
    }
    val primarySamples = listOf(presentation.primaryLabel) + transportSamples.map { readerPresentation(it, false).primaryLabel }
    val compactStatus = readerCompactStatus(reading, presentation)
    val compactStatusSamples = listOf(compactStatus) + transportSamples.map {
        readerCompactStatus(it, readerPresentation(it, false))
    }
    val compactPrimarySamples = listOf(readerCompactPrimary(presentation)) + transportSamples.map {
        readerCompactPrimary(readerPresentation(it, false))
    }
    val actualText = reading.segment as? PlaybackSegment.Text
    val showReturn by remember(viewport, actualText, meaning) {
        derivedStateOf { !meaning && viewport.canReturn(actualText) }
    }
    fun seekLine(line: ReaderFeedItem.Line) {
        if (!owner.matches(player)) return
        // A failed write must not let pause's boundary focus pretend this choice succeeded.
        viewport.browse()
        if (player.seekLine(line.trackId, line.line.id, generation) && owner.matches(player)) viewport.requestFocus()
    }
    fun returnToPhrase() {
        if (owner.matches(player) && player.reading?.segment is PlaybackSegment.Text) viewport.requestFocus()
    }
    // A restored explanation must never consume hidden playback time. The status reflects
    // the real player until this pause has been applied, rather than assuming it is paused.
    LaunchedEffect(generation, meaning, reading.state.isPlaying) {
        if (meaning && reading.state.isPlaying) player.pause(generation)
    }
    fun selectTab(value: Int) {
        if (generation != player.generation) return
        if (value == 2) { player.pause(generation); meaning = true }
        else {
            if (meaning) viewport.requestFocus()
            else if (original != (value == 0)) viewport.preserveLanguageAnchor(value == 0)
            else viewport.cancelLanguageAnchor()
            meaning = false
            onOriginalChange(value == 0)
        }
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
        if (generation != player.generation) return
        if (index < reading.plan.stages.lastIndex) onStageChange(reading.plan.stages[index + 1].id) else onFinish()
    }
    fun previousStage() {
        if (!owner.matches(player) || index <= 0) return
        player.pause(generation)
        if (generation == player.generation) onStageChange(reading.plan.stages[index - 1].id)
    }
    fun primaryAction() {
        if (generation != player.generation) return
        when (presentation.primaryAction) {
            ReaderPrimaryAction.PLAY -> {
                viewport.cancelLanguageAnchor()
                if (player.play(generation) && owner.matches(player)) viewport.requestFocus()
            }
            ReaderPrimaryAction.PAUSE -> player.pause(generation)
            ReaderPrimaryAction.BEGIN_LITURGY -> nextStage()
            ReaderPrimaryAction.RETURN_TO_TEXT -> {
                player.pause(generation)
                if (generation == player.generation) {
                    meaning = false
                    if (owner.matches(player)) viewport.requestFocus()
                }
            }
            ReaderPrimaryAction.CONTINUE_AFTER_MISSING -> player.continueAfterMissing(generation)
            ReaderPrimaryAction.FINISH -> {
                player.pause(generation)
                if (generation == player.generation) onFinish()
            }
        }
    }

    if (controlsOpen) key(owner) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { controlsOpen = false }, sheetState = sheetState) {
            CompositionLocalProvider(LocalDensity provides requestedDensity) {
            Text("Управление чтением", Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineMedium)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("reader_controls_list"),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "stage_title") {
                    Text(reading.stage.name, Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag("controls_stage_title").semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                }
                item(key = "reading_status") {
                    Text(visibleStatus, Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag("controls_reading_status"), style = MaterialTheme.typography.bodySmall)
                    if (presentation.kind in setOf(ReaderStateKind.TEXT, ReaderStateKind.WAIT, ReaderStateKind.BEFORE_START)) {
                        Text("Движение текста без звука", Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            .testTag("controls_soundless_note"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(3, key = { "mode_$it" }) { mode ->
                    ReaderControlAction(listOf("Оригинал", "По-русски", "Смысл")[mode],
                        listOf("controls_original", "controls_text", "controls_meaning")[mode],
                        enabled = mode != 0 || supportsOriginal, selection = selectedTab == mode, onClick = {
                            if (owner.matches(player)) { controlsOpen = false; selectTab(mode) }
                        })
                }
                if (showReturn) item(key = "return") {
                    ReaderControlAction("К выбранной фразе", "controls_return", onClick = {
                        if (owner.matches(player)) { controlsOpen = false; returnToPhrase() }
                    })
                }
                item(key = "clarify") {
                    ReaderControlAction("Уточнить место", "controls_clarify", onClick = {
                        if (owner.matches(player)) { controlsOpen = false; clarify() }
                    })
                }
                item(key = "order") {
                    ReaderControlAction("Порядок службы", "controls_order", onClick = {
                        if (owner.matches(player)) { controlsOpen = false; player.pause(generation); onSelectStage() }
                    })
                }
                if (presentation.kind == ReaderStateKind.TEXT) {
                    item(key = "previous_line") {
                        val previous = feed.adjacentLine(reading.segment, -1)
                        ReaderControlAction("Предыдущая фраза", "previous_line", enabled = previous != null, onClick = {
                            if (owner.matches(player)) { controlsOpen = false; previous?.let(::seekLine) }
                        })
                    }
                    item(key = "next_line") {
                        val next = feed.adjacentLine(reading.segment, 1)
                        ReaderControlAction("Следующая фраза", "next_line", enabled = next != null, onClick = {
                            if (owner.matches(player)) { controlsOpen = false; next?.let(::seekLine) }
                        })
                    }
                }
                if (presentation.kind in setOf(ReaderStateKind.TEXT, ReaderStateKind.WAIT)) {
                    item(key = "tempo") {
                        ReaderControlAction("Примерный темп: ${reading.state.speed.label()}", "scroll_options", onClick = {
                            if (owner.matches(player)) { controlsOpen = false; tempoOpen = true }
                        })
                    }
                    item(key = "previous_stage") {
                        ReaderControlAction("Предыдущий этап", "previous_stage", enabled = index > 0, onClick = {
                            if (owner.matches(player)) { controlsOpen = false; previousStage() }
                        })
                    }
                    item(key = "next_stage") {
                        val next = reading.plan.stages.getOrNull(index + 1)
                        ReaderControlAction(next?.let { "Следующий этап: ${it.shortTitle}" } ?: "Закончить чтение", "next_stage", onClick = {
                            if (owner.matches(player)) { controlsOpen = false; nextStage() }
                        })
                    }
                }
            }
            }
        }
    }

    details?.let { snapshot ->
        AlertDialog(onDismissRequest = { details = null }, title = {
            CompositionLocalProvider(LocalDensity provides requestedDensity) { Text("Открытое чтение") }
        },
            text = {
                CompositionLocalProvider(LocalDensity provides requestedDensity) {
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
                            Text(readerTrackLanguage(track, original), Modifier.testTag("reading_details_language"),
                                style = MaterialTheme.typography.bodySmall.readingParagraph())
                        }
                    }
                    Text(when {
                        snapshot.restored -> "Сохраненное чтение · уточните момент"
                        snapshot.reading.isApproximate -> "Примерное место · можно уточнить"
                        else -> "Чтение на паузе"
                    }, Modifier.testTag("reading_details_status"), style = MaterialTheme.typography.bodyMedium.readingParagraph())
                }
                }
            }, confirmButton = {
                CompositionLocalProvider(LocalDensity provides requestedDensity) {
                    TextButton(onClick = { details = null }, Modifier.testTag("close_reading_details")) { Text("Закрыть") }
                }
            })
    }

    if (tracksOpen) ModalBottomSheet(onDismissRequest = { tracksOpen = false }) {
        CompositionLocalProvider(LocalDensity provides requestedDensity) {
        Text("Тексты этого этапа", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineMedium)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("track_list"), contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(reading.tracks, key = { _, track -> track.id }) { i, track ->
                if (reading.tracks.any { it.isScripture } && (i == 0 || reading.tracks[i - 1].isScripture != track.isScripture)) {
                    Text(if (track.isScripture) "Чтения дня" else "Перед чтением", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
                ListItem(headlineContent = { Text(track.title) }, supportingContent = {
                    Text(if (track.isScripture) track.subtitle else if (track.isExcerpt) "Фрагмент молитвы" else "Полный текст")
                }, trailingContent = { if (track.id == reading.track?.id) Icon(Icons.Default.Check, "Выбранный текст") },
                    modifier = Modifier.clickable {
                        if (owner.matches(player)) {
                            viewport.browse()
                            if (player.selectTrack(track.id, generation)) {
                                tracksOpen = false
                                if (owner.matches(player)) viewport.requestFocus()
                            }
                        }
                    }.testTag("track_$i")
                        .semantics { selected = track.id == reading.track?.id })
            }
        }
        }
    }
    if (tempoOpen) AlertDialog(onDismissRequest = { tempoOpen = false },
        title = { CompositionLocalProvider(LocalDensity provides requestedDensity) { Text("Темп текста") } },
        text = {
            CompositionLocalProvider(LocalDensity provides requestedDensity) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaybackSpeed.entries.forEach { speed ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { player.changeSpeed(speed, generation) }
                        .testTag("tempo_${speed.name.lowercase()}").semantics { selected = reading.state.speed == speed; role = Role.RadioButton },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reading.state.speed == speed, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(when (speed) { PlaybackSpeed.SLOW -> "Медленнее · 0,75×"; PlaybackSpeed.NORMAL -> "Обычно · 1×"; PlaybackSpeed.FAST -> "Быстрее · 1,25×" })
                    }
                }
                Text("Текст движется без звука. Скорость примерная: чтение и пение в храме могут идти иначе. Чтобы поправить место, выберите нужную фразу или нажмите \"Уточнить\".")
                Text("После доступного фрагмента остается ориентир по происходящему. Если чтения на выбранную дату нет, плеер остановится.", style = MaterialTheme.typography.bodySmall)
            }
            }
        }, confirmButton = {
            CompositionLocalProvider(LocalDensity provides requestedDensity) { TextButton(onClick = { tempoOpen = false }, modifier = Modifier.testTag("tempo_done")) { Text("Готово") } }
        }, dismissButton = {
            CompositionLocalProvider(LocalDensity provides requestedDensity) {
                TextButton(onClick = { player.pause(generation); tempoOpen = false }, modifier = Modifier.testTag("manual_reading").semantics { contentDescription = "Поставить на паузу" }) { Text("Пауза") }
            }
        })

    val backButton: @Composable () -> Unit = {
        IconButton(onClick = { if (generation == player.generation) { player.pause(generation); onBack() } },
            modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "К службе")
        }
    }
    val stageButton: @Composable (ReaderChromeLayout, Modifier) -> Unit = { chrome, modifier ->
        key(generation) {
            Row(modifier.heightIn(min = 48.dp).clickable {
                if (generation == player.generation) { player.pause(generation); onSelectStage() }
            }.testTag("open_map").semantics {
                role = Role.Button
                contentDescription = "Порядок службы"
                stateDescription = "${reading.stage.name}, ${index + 1} из ${reading.plan.stages.size}"
            }.padding(horizontal = 8.dp, vertical = if (chrome.kind == ReaderChrome.COMPACT) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (chrome.kind == ReaderChrome.NORMAL) reading.stage.name else reading.stage.shortTitle,
                    Modifier.weight(1f, fill = false).testTag("current_stage_title").semantics { heading() },
                    style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
            }
        }
    }
    val menuButton: @Composable () -> Unit = {
        key(owner) {
            Box {
                IconButton(onClick = { if (owner.matches(player)) menu = true }, Modifier.size(48.dp).testTag("player_menu")) {
                    Icon(Icons.Default.MoreHoriz, "Действия со службой")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    CompositionLocalProvider(LocalDensity provides requestedDensity) {
                    if (reading.tracks.isNotEmpty()) DropdownMenuItem(text = { Text("Тексты этапа") },
                        onClick = { if (owner.matches(player)) { menu = false; openTracks() } },
                        modifier = Modifier.testTag("choose_track").semantics {
                            contentDescription = "Тексты этапа"
                            stateDescription = reading.track?.title.orEmpty()
                        })
                    DropdownMenuItem(text = { Text("Открытое чтение") },
                        onClick = { if (owner.matches(player)) { menu = false; openDetails() } }, modifier = Modifier.testTag("open_reading_details"))
                    DropdownMenuItem(text = { Text(if (dark) "Светлый экран" else "Темный экран") },
                        onClick = { menu = false; if (owner.matches(player)) onDarkChange() })
                    DropdownMenuItem(text = { Text("Размер текста и настройки") },
                        onClick = { menu = false; if (owner.matches(player)) { player.pause(generation); onSettings() } })
                    DropdownMenuItem(text = { Text("Тишина на телефоне") },
                        onClick = { menu = false; if (owner.matches(player)) { player.pause(generation); onSoundSettings() } })
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val soundless = presentation.kind in setOf(ReaderStateKind.TEXT, ReaderStateKind.WAIT, ReaderStateKind.BEFORE_START)
        val chrome = readerChromeLayout(maxWidth, maxHeight, reading.stage.name, reading.stage.shortTitle,
            statusSamples, primarySamples, compactStatusSamples, compactPrimarySamples, soundless)
        val compact = chrome.kind != ReaderChrome.NORMAL
        val body: @Composable (Modifier) -> Unit = { modifier ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp),
                modifier = modifier.padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp)
                    .testTag("reader_surface")) {
                if (meaning) ReaderMeaning(reading, missing, Modifier.fillMaxSize()) { if (generation == player.generation) onOpenSource(it) }
                else key(owner) {
                    ReaderLyrics(reading, player, viewport, feed, original, compact, missing, presentation, onSeekLine = { seekLine(it) })
                }
            }
        }
        val footer: @Composable (Modifier) -> Unit = { modifier ->
            ReaderFooter(reading, player, generation, owner, presentation, visibleStatus,
                if (compact) compactStatus else visibleStatus,
                if (compact) compactStatusSamples else statusSamples,
                if (compact) compactPrimarySamples else primarySamples, chrome.kind, selectedTab, showReturn, modifier,
                onPrimary = { primaryAction() }, onReturn = { returnToPhrase() },
                onMicrophone = { if (owner.matches(player)) { player.pause(generation); onMicrophone() } }, onControls = { if (owner.matches(player)) controlsOpen = true })
        }
        Column(Modifier.fillMaxSize().testTag("reader_layout_${chrome.kind.name.lowercase()}")) {
            if (chrome.kind == ReaderChrome.COMPACT) {
                stageButton(chrome, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    backButton()
                    key(owner) {
                        ReaderModeControls(selectedTab, Modifier.weight(1f), soundless = soundless,
                            onClick = { if (owner.matches(player)) controlsOpen = true })
                    }
                    menuButton()
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    backButton()
                    stageButton(chrome, Modifier.weight(1f))
                    menuButton()
                }
            }
            if (chrome.kind == ReaderChrome.NORMAL) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                key(owner) {
                    ReaderModes(selectedTab, supportsOriginal, { value -> selectTab(value) }, Modifier.weight(1f))
                }
            }
            if (chrome.kind == ReaderChrome.SIDEBAR) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    body(Modifier.weight(1f).fillMaxHeight())
                    footer(Modifier.width(chrome.panelWidth))
                }
            } else {
                body(Modifier.weight(1f).fillMaxWidth())
                footer(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ReaderMicrophoneButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = modifier.heightIn(min = 48.dp).testTag("reader_microphone")) { Text("Микрофон") }
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
                    Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

private fun PlaybackSpeed.label(): String = when (this) { PlaybackSpeed.SLOW -> "0,75×"; PlaybackSpeed.NORMAL -> "1×"; PlaybackSpeed.FAST -> "1,25×" }

@Composable
private fun ReaderControlAction(label: String, tag: String, enabled: Boolean = true, selection: Boolean? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, shape = RectangleShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selection == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            contentColor = when (selection) {
                true -> MaterialTheme.colorScheme.onSecondaryContainer
                false -> MaterialTheme.colorScheme.onSurface
                null -> MaterialTheme.colorScheme.primary
            }),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
            contentDescription = label
            selection?.let { this.selected = it; role = Role.Tab }
        }) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (selection != null) {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selection) Icon(Icons.Default.Check, contentDescription = null,
                    modifier = Modifier.testTag("${tag}_selected_marker"))
            }
        }
    }
}

@Composable
private fun ReaderFooter(reading: ActiveServicePlayback, player: ServicePlayerState, generation: Int,
    owner: ReaderFeedOwner, presentation: ReaderPresentation, fullStatus: String, status: String,
    statusSamples: List<String>, primarySamples: List<String>, chrome: ReaderChrome, selectedTab: Int, showReturn: Boolean,
    modifier: Modifier = Modifier, onPrimary: () -> Unit, onReturn: () -> Unit,
    onMicrophone: () -> Unit, onControls: () -> Unit) {
    val soundless = presentation.kind in setOf(ReaderStateKind.TEXT, ReaderStateKind.WAIT, ReaderStateKind.BEFORE_START)
    val compact = chrome != ReaderChrome.NORMAL
    val primaryTag = when (presentation.primaryAction) {
        ReaderPrimaryAction.PLAY, ReaderPrimaryAction.PAUSE -> "play_lyrics"
        ReaderPrimaryAction.BEGIN_LITURGY -> "start_liturgy"
        ReaderPrimaryAction.RETURN_TO_TEXT -> "return_to_text"
        ReaderPrimaryAction.CONTINUE_AFTER_MISSING -> "continue_missing_reading"
        ReaderPrimaryAction.FINISH -> "finish_reading"
    }
    val statusText: @Composable (Modifier) -> Unit = { target ->
        StableReaderText(status, statusSamples, "reader_status", MaterialTheme.typography.bodySmall,
            MaterialTheme.colorScheme.onSurfaceVariant, target, fullStatus)
    }
    val soundlessText: @Composable () -> Unit = {
        Text("Без звука", Modifier.testTag("soundless_note"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val primary: @Composable (Modifier) -> Unit = { target ->
        key(generation) {
            Button(onClick = onPrimary, contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 12.dp, vertical = 8.dp),
                modifier = target.heightIn(min = if (compact) 48.dp else 56.dp).testTag(primaryTag).semantics {
                    contentDescription = presentation.primaryLabel
                    stateDescription = fullStatus
                }) {
                StableReaderText(if (compact) readerCompactPrimary(presentation) else presentation.primaryLabel,
                    primarySamples, "reader_primary_label", MaterialTheme.typography.labelLarge, LocalContentColor.current)
            }
        }
    }
    val beginningPlaying = presentation.kind == ReaderStateKind.BEFORE_START && reading.state.isPlaying
    val onPause = { if (owner.matches(player)) player.pause(generation); Unit }
    val positionAction: @Composable () -> Unit = {
        key(owner) {
            when {
                showReturn -> IconButton(onClick = onReturn, modifier = Modifier.size(48.dp).testTag("return_to_phrase")) {
                    Icon(Icons.Default.MyLocation, "К выбранной фразе")
                }
                beginningPlaying -> IconButton(onClick = onPause, modifier = Modifier.size(48.dp).testTag("before_start_pause")) {
                    Icon(Icons.Default.Pause, "Пауза")
                }
                else -> Spacer(Modifier.size(48.dp).clearAndSetSemantics { })
            }
        }
    }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        when (chrome) {
            ReaderChrome.NORMAL -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    statusText(Modifier.weight(1f))
                    if (soundless) soundlessText()
                }
                primary(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ReaderDirectSecondary(Modifier.weight(1f), owner, onMicrophone)
                    ReaderPositionSlot(Modifier.weight(1f), owner, showReturn, beginningPlaying, onReturn, onPause)
                    key(owner) { ReaderControlsButton(onControls, Modifier.weight(1f)) }
                }
            }
            ReaderChrome.COMPACT -> Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                statusText(Modifier.fillMaxWidth())
                primary(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ReaderDirectSecondary(Modifier.weight(1f), owner, onMicrophone)
                    positionAction()
                }
            }
            ReaderChrome.SIDEBAR -> Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                key(owner) { ReaderModeControls(selectedTab, Modifier.fillMaxWidth(), soundless, onControls) }
                statusText(Modifier.fillMaxWidth())
                primary(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ReaderDirectSecondary(Modifier.weight(1f), owner, onMicrophone)
                    positionAction()
                }
            }
        }
    }
}

@Composable
private fun ReaderControlsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = modifier.heightIn(min = 48.dp).testTag("reader_controls")) {
        Text("Управление", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ReaderModeControls(selected: Int, modifier: Modifier, soundless: Boolean = false, onClick: () -> Unit) {
    val mode = listOf("Оригинал", "По-русски", "Смысл")[selected]
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag("reader_controls").semantics {
        contentDescription = "Режим чтения и управление"
        stateDescription = if (soundless) "$mode · Без звука" else mode
    },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
        Row(Modifier.testTag("reader_mode_chooser"), verticalAlignment = Alignment.CenterVertically) {
            Text(mode, Modifier.weight(1f, fill = false).testTag("reader_selected_mode"),
                style = MaterialTheme.typography.bodySmall)
            Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
    }
}

/** Keep the microphone action in the space already reserved by the reader layout. */
@Composable
private fun ReaderDirectSecondary(modifier: Modifier, owner: ReaderFeedOwner, onMicrophone: () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val heights = listOf("Уточнить", "Микрофон").mapIndexed { index, label ->
            subcompose("measure_$index") {
                ReaderSecondaryMeasure(label, MaterialTheme.typography.labelLarge, 4)
            }.single().measure(constraints.copy(minHeight = 0)).height
        }
        val actual = subcompose("content") {
            key(owner) { ReaderMicrophoneButton(onMicrophone, Modifier.fillMaxWidth()) }
        }.single().measure(constraints.copy(minHeight = maxOf(heights.max(), constraints.minHeight)))
        layout(actual.width, actual.height) { actual.placeRelative(0, 0) }
    }
}
/** Measure alternate labels without placing them or exposing hidden text to accessibility. */
@Composable
private fun StableReaderText(text: String, samples: List<String>, tag: String, style: TextStyle, color: Color,
    modifier: Modifier = Modifier, description: String? = null) {
    SubcomposeLayout(modifier) { constraints ->
        val sampleSizes = samples.distinct().mapIndexed { index, sample ->
            subcompose("measure_$index") { Text(sample, Modifier.clearAndSetSemantics { }, style = style) }
                .single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        val actual = subcompose("visible") { Text(text, Modifier.testTag(tag).semantics {
            description?.let { stateDescription = it }
        }, style = style, color = color) }
            .single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = actual.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = maxOf(actual.height, sampleSizes.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { actual.placeRelative(0, (height - actual.height) / 2) }
    }
}

/** The two visible alternatives reserve the same measured slot, without hidden actions. */
@Composable
private fun ReaderPositionSlot(modifier: Modifier, owner: ReaderFeedOwner,
    showReturn: Boolean, beginningPlaying: Boolean, onReturn: () -> Unit, onPause: () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val returnHeight = subcompose("return_measure") {
            ReaderSecondaryMeasure("К выбранной фразе", MaterialTheme.typography.bodySmall, 8)
        }.single().measure(constraints.copy(minHeight = 0)).height
        val pauseHeight = subcompose("pause_measure") {
            ReaderSecondaryMeasure("Пауза", MaterialTheme.typography.labelLarge, 8)
        }.single().measure(constraints.copy(minHeight = 0)).height
        val content = subcompose("content") {
            key(owner) {
                when {
                    showReturn -> TextButton(onClick = onReturn, contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("return_to_phrase")) {
                        Text("К выбранной фразе", style = MaterialTheme.typography.bodySmall)
                    }
                    beginningPlaying -> TextButton(onClick = onPause, contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("before_start_pause")) { Text("Пауза") }
                    else -> Spacer(Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { })
                }
            }
        }.single().measure(constraints.copy(minHeight = maxOf(returnHeight, pauseHeight, constraints.minHeight)))
        layout(content.width, content.height) { content.placeRelative(0, 0) }
    }
}

@Composable
private fun ReaderSecondaryMeasure(label: String, style: TextStyle, horizontalPadding: Int) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = horizontalPadding.dp, vertical = 8.dp).clearAndSetSemantics { },
        contentAlignment = Alignment.Center) { Text(label, Modifier.clearAndSetSemantics { }, style = style) }
}

@Composable
private fun ReaderLyrics(reading: ActiveServicePlayback, player: ServicePlayerState, viewport: ReaderViewport,
    feed: ReaderFeed, original: Boolean, compact: Boolean, missing: Boolean, presentation: ReaderPresentation,
    onSeekLine: (ReaderFeedItem.Line) -> Unit) {
    val owner = viewport.owner
    val scroll = viewport.scroll
    val actualText = reading.segment as? PlaybackSegment.Text
    val textSize = 28f
    val block = actualText == null
    val dragConnection = remember(viewport, player) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f && owner.matches(player)) {
                    // Runs before the list consumes its first user delta, unlike a post-composition
                    // drag observer. Pause may materialize a boundary; its focus must be suppressed.
                    val wasManual = viewport.manual
                    viewport.browse()
                    if (!wasManual) player.pause(owner.generation)
                }
                return Offset.Zero
            }
        }
    }
    val languageAnchor = viewport.languageAnchor
    LaunchedEffect(viewport, languageAnchor, original) {
        val request = languageAnchor ?: return@LaunchedEffect
        if (!viewport.manual || !owner.matches(player) || original != request.original) return@LaunchedEffect
        val job = currentCoroutineContext().job
        viewport.languageJob = job
        try {
            withFrameNanos { }
            if (!viewport.manual || !owner.matches(player) || viewport.languageAnchor != request) return@LaunchedEffect
            val target = feed.items.indexOfFirst { it.key == request.key }
            if (target >= 0) scroll.scrollToItem(target)
            viewport.finishLanguageAnchor(request)
        } finally {
            if (viewport.languageJob === job) viewport.languageJob = null
        }
    }
    LaunchedEffect(viewport, actualText?.lineId, player.focusRequest, viewport.focusRequest, original, block) {
        if (viewport.manual || !owner.matches(player)) return@LaunchedEffect
        val job = currentCoroutineContext().job
        viewport.focusJob = job
        viewport.focusing = true
        try {
            withFrameNanos { }
            if (viewport.manual || !owner.matches(player)) return@LaunchedEffect
            val current = player.reading ?: return@LaunchedEffect
            val target = feed.targetIndex(current.segment)
            val inset = if (compact || current.segment !is PlaybackSegment.Text) 0
                else (scroll.layoutInfo.viewportEndOffset / 4).coerceAtLeast(0)
            scroll.animateScrollToItem(target, scrollOffset = -inset)
            if (!viewport.manual && owner.matches(player)) viewport.initiallyFocused = true
        } finally {
            if (viewport.focusJob === job) {
                viewport.focusJob = null
                viewport.focusing = false
            }
        }
    }
    DisposableEffect(viewport) {
        onDispose {
            viewport.focusJob?.cancel()
            viewport.cancelLanguageAnchor()
            viewport.bounds = null
            viewport.phraseBounds = null
            viewport.visibleLineBounds.clear()
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("lyrics_list").nestedScroll(dragConnection)
        .onGloballyPositioned { viewport.bounds = it.boundsInRoot() }, state = scroll,
        contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 12.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 28.dp)) {
        items(feed.items, key = { it.key }, contentType = {
            when (it) {
                is ReaderFeedItem.Header -> "header"
                is ReaderFeedItem.Line -> "line"
                is ReaderFeedItem.Continuation -> "continuation"
            }
        }) { item ->
            when (item) {
                is ReaderFeedItem.Header -> {
                    val track = item.track
                    Column(Modifier.fillMaxWidth().testTag("feed_header_${track.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (track.id == reading.tracks.firstOrNull()?.id) reading.migrationNote?.let { ReaderMigrationNote(it) }
                        Text(track.title, Modifier.testTag("feed_title_${track.id}").semantics { heading() },
                            style = MaterialTheme.typography.titleMedium.readingParagraph())
                        if (missing) DailyReadingUnavailable()
                        Text(readerTrackLanguage(track, original), Modifier.testTag("feed_language_${track.id}"),
                            style = MaterialTheme.typography.bodySmall.readingParagraph(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        track.takeUnless { it.isScripture }?.contextNote?.let { note ->
                            Text(note, Modifier.testTag("feed_context_${track.id}"), style = MaterialTheme.typography.bodySmall.readingParagraph(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is ReaderFeedItem.Line -> {
                    val line = item.line
                    val selected = actualText?.let { it.trackId == item.trackId && it.lineId == line.id } == true
                    DisposableEffect(viewport, line.id, selected) {
                        onDispose {
                            if (viewport.phraseBounds?.first == line.id) viewport.phraseBounds = null
                            viewport.visibleLineBounds.remove(item.key)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.speaker?.let { speaker ->
                            Text(speaker, Modifier.testTag("feed_speaker_${line.id}"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.padding(top = 8.dp).width(3.dp).height(24.dp)
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                                    RoundedCornerShape(2.dp)))
                            Text(readerLineText(line, original),
                                Modifier.weight(1f).heightIn(min = 48.dp).clickable { if (owner.matches(player)) onSeekLine(item) }
                                    .testTag("feed_line_${line.id}").semantics { this.selected = selected; role = Role.Button }
                                    .onGloballyPositioned { coordinates ->
                                        if (owner.matches(player)) {
                                            val bounds = coordinates.boundsInRoot()
                                            viewport.visibleLineBounds[item.key] = bounds
                                            if (selected) viewport.phraseBounds = line.id to bounds
                                        }
                                    },
                                fontSize = textSize.sp, lineHeight = (textSize * 1.4f).sp,
                                style = MaterialTheme.typography.bodyLarge.readingParagraph(),
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                is ReaderFeedItem.Continuation -> Column(Modifier.fillMaxWidth().testTag("feed_continuation_${item.stageId}")) {
                    if (reading.tracks.isEmpty()) reading.migrationNote?.let { ReaderMigrationNote(it) }
                    if (block) ReaderStageProgress(reading, presentation)
                    else Text("Здесь заканчиваются доступные тексты этапа",
                        Modifier.padding(top = 12.dp, bottom = 48.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ReaderStageProgress(reading: ActiveServicePlayback, presentation: ReaderPresentation) {
    val kind = presentation.kind
    val next = reading.plan.stages.getOrNull(reading.plan.stages.indexOf(reading.stage) + 1)
    Column(Modifier.fillMaxWidth().testTag(when (kind) {
        ReaderStateKind.MISSING_READING -> "missing_reading_barrier"
        ReaderStateKind.END -> "playback_end"
        ReaderStateKind.BEFORE_START -> "before_start"
        else -> "stage_wait"
        }).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(when (kind) {
            ReaderStateKind.MISSING_READING -> "Текста чтения пока нет"
            ReaderStateKind.END -> "Конец порядка в приложении"
            ReaderStateKind.BEFORE_START -> "Ожидаем начало литургии"
            else -> "Слушаем службу"
        }, style = MaterialTheme.typography.titleLarge)
        when (kind) {
            ReaderStateKind.MISSING_READING -> {
                val date = reading.plan.serviceDate.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru")))
                Text("В приложении нет текста на $date. Слушайте чтение в храме. Когда оно закончится, подтвердите переход.",
                    style = MaterialTheme.typography.bodyLarge.readingParagraph())
            }
            ReaderStateKind.END -> Text("Это конец порядка в приложении. Ориентируйтесь на происходящее в храме.",
                style = MaterialTheme.typography.bodyLarge.readingParagraph())
            ReaderStateKind.BEFORE_START -> {
                Text("До литургии в храме могут читать часы. Когда услышите начальный возглас, подтвердите начало литургии.",
                    style = MaterialTheme.typography.bodyLarge.readingParagraph())
                next?.cue?.let { cue ->
                    Text("Как узнать начало", style = MaterialTheme.typography.titleMedium)
                    Text(cue, Modifier.testTag("next_stage_cue"), style = MaterialTheme.typography.bodyLarge.readingParagraph())
                }
            }
            else -> {
                Text(if (next != null) "Доступный текст закончился, но этот этап в храме может продолжаться."
                    else "Доступный текст закончился. Продолжайте слушать заключительные молитвы в храме.",
                    style = MaterialTheme.typography.bodyLarge.readingParagraph())
                Text(reading.stage.description, style = MaterialTheme.typography.bodyLarge.readingParagraph())
                next?.let { stage ->
                    Text("Далее: ${stage.shortTitle}", style = MaterialTheme.typography.titleMedium)
                    stage.cue?.let { cue -> Text(cue, Modifier.testTag("next_stage_cue"), style = MaterialTheme.typography.bodyLarge.readingParagraph()) }
                }
            }
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
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        reading.migrationNote?.let { note -> item(key = "migration_notice") { ReaderMigrationNote(note) } }
        item { MeaningSection("Что происходит") {
            Text(reading.stage.description, style = MaterialTheme.typography.bodyLarge.readingParagraph())
            if (missing) DailyReadingUnavailable(meaning = true)
        } }
        item { MeaningSection("В чем смысл") { Text(reading.stage.meaning, style = MaterialTheme.typography.bodyLarge.readingParagraph()) } }
        reading.stage.cue?.let { cue -> item { MeaningSection("Как узнать этот момент") { Text(cue, style = MaterialTheme.typography.bodyLarge.readingParagraph()) } } }
        if (reading.stage.whatToDo.isNotBlank()) item { MeaningSection("На что обратить внимание") { Text(reading.stage.whatToDo, style = MaterialTheme.typography.bodyLarge.readingParagraph()) } }
        if (track != null && track.explanation.isNotBlank()) item {
            MeaningSection(if (track.isScripture) "О чем это чтение" else "Об этом тексте") {
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
private fun MeaningSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun ReaderMigrationNote(note: PlaybackMigrationNote) {
    Text(note.message(), Modifier.fillMaxWidth().testTag("playback_migration_notice"),
        style = MaterialTheme.typography.bodySmall.readingParagraph(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DailyReadingUnavailable(meaning: Boolean = false) {
    Text(if (meaning) "Текста чтения на эту дату пока нет. Здесь объясняется этот этап службы"
        else "Чтение на эту дату пока не добавлено. Здесь - только возглас перед чтением", Modifier.testTag("reading_unavailable"),
        style = MaterialTheme.typography.bodySmall.readingParagraph(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
