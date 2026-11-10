package ru.liturgyguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.service.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class BookOwner(val generation: Int, val fingerprint: String, val context: ReadingContext) {
    fun matches(player: ServicePlayerState) = generation == player.generation &&
        player.reading?.plan?.fingerprint == fingerprint && player.reading?.context == context
}

@Stable
private class BookViewport(val owner: BookOwner, initialIndex: Int, initialOffset: Int = 0) {
    val scroll = LazyListState(initialIndex, initialOffset)
    var manual by mutableStateOf(false)
    var touched by mutableStateOf(false)
    var positioning by mutableStateOf(false)
    var focusJob: Job? = null
    var playStartKey: String? = null
    var playStartOffset = 0
    var playStartElapsedQuarterMillis = 0L
    fun browse() {
        manual = true
        touched = true
        focusJob?.cancel()
    }
}

private fun bookViewportSaver(owner: BookOwner, feed: ContinuousServiceFeed, player: ServicePlayerState) = listSaver<BookViewport, Any>(
    save = { viewport ->
        val state = player.reading?.state
        if (!owner.matches(player) || state == null) emptyList()
        else listOf(owner.generation, owner.fingerprint, feed.visibleItem(viewport.scroll.firstVisibleItemIndex).key,
            viewport.scroll.firstVisibleItemScrollOffset, viewport.manual, ReadingContextCodec.encode(owner.context),
            state.segmentIndex, state.elapsedQuarterMillis, viewport.playStartKey.orEmpty(), viewport.playStartOffset,
            viewport.playStartElapsedQuarterMillis)
    },
    restore = {
        val state = player.reading?.state
        // A recreated player starts its runtime generation again. Durable identity
        // comes from context and position, while callbacks still use generation.
        if (it.size != 11 || it[1] != owner.fingerprint ||
            it[5] != ReadingContextCodec.encode(owner.context) || it[6] != state?.segmentIndex ||
            it[7] != state?.elapsedQuarterMillis) null
        else (it[2] as? String)?.let(feed::indexOf)?.let { index ->
            BookViewport(owner, index, (it[3] as Int).coerceAtLeast(0)).apply {
                manual = it[4] as Boolean
                playStartKey = (it[8] as String).takeIf(String::isNotEmpty)
                playStartOffset = (it[9] as Int).coerceAtLeast(0)
                playStartElapsedQuarterMillis = (it[10] as Long).coerceAtLeast(0)
            }
        }
    }
)

private fun visibleBookItem(feed: ContinuousServiceFeed, viewport: BookViewport, segment: PlaybackSegment): ServiceBookItem {
    val layout = viewport.scroll.layoutInfo
    val visible = layout.visibleItemsInfo.firstOrNull { info ->
        val item = feed.items[info.index]
        info.size > 0 && info.offset + info.size > layout.viewportStartOffset &&
            (item !is ServiceBookItem.Transition || segment is PlaybackSegment.Wait && segment.stageId == item.stageId)
    }
    val item = feed.visibleItem(visible?.index ?: viewport.scroll.firstVisibleItemIndex)
    return if (item is ServiceBookItem.Transition && (segment !is PlaybackSegment.Wait || segment.stageId != item.stageId)) feed.readableBefore(item) else item
}

private data class BookScrollPosition(val scrolling: Boolean, val touched: Boolean, val positioning: Boolean, val index: Int)

/** One service book. Manual reading owns the visible place; playback is an optional follower. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceScreen(player: ServicePlayerState, onStageChange: (String) -> Unit,
    onBack: () -> Unit, onSelectStage: () -> Unit, onFindStage: () -> Unit, onSettings: () -> Unit,
    dark: Boolean, onDarkChange: () -> Unit, onOpenSource: (String) -> Unit,
    onSoundSettings: () -> Unit, onFinish: () -> Unit, preferredOriginal: Boolean,
    onOriginalChange: (Boolean) -> Unit, restoredNotice: Boolean = false, onMicrophone: () -> Unit = onFindStage) {
    val reading = player.reading ?: return
    val owner = BookOwner(player.generation, reading.plan.fingerprint, reading.context)
    val feed = remember(owner) { ContinuousServiceFeed(reading.plan) }
    val viewport = rememberSaveable(owner, saver = bookViewportSaver(owner, feed, player)) {
        BookViewport(owner, feed.targetIndex(reading.segment))
    }
    val scope = rememberCoroutineScope()
    val requestedDensity = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var meaning by rememberSaveable { mutableStateOf(false) }
    var menu by remember(owner) { mutableStateOf(false) }
    var modeMenu by remember(owner) { mutableStateOf(false) }
    var tempoOpen by remember(owner) { mutableStateOf(false) }
    var detailsOpen by remember(owner) { mutableStateOf(false) }
    var failedSave by remember(owner) { mutableStateOf(false) }
    val original = preferredOriginal && feed.supportsOriginal
    val currentItem by remember(viewport, feed) { derivedStateOf { visibleBookItem(feed, viewport, requireNotNull(player.reading).segment) } }
    val visibleStage = feed.stage(currentItem)
    val visibleTrack = feed.track(currentItem)
    val visibleOriginalAvailable = when (val item = currentItem) {
        is ServiceBookItem.Line -> item.line.original != null
        is ServiceBookItem.Track -> item.track.lines.any { it.original != null }
        else -> feed.supportsOriginal
    }
    val russianFallback = original && !visibleOriginalAvailable
    val mode = if (meaning) 2 else if (original && !russianFallback) 0 else 1

    fun savePlace(item: ServiceBookItem): Boolean {
        if (!owner.matches(player)) return false
        val current = player.reading ?: return false
        // Missing readings and the end have no readable phrase. Keep the last real bookmark;
        // their explicit actions below are the only way to pass an automatic barrier.
        if (item is ServiceBookItem.Missing || item is ServiceBookItem.End) return true
        if (item is ServiceBookItem.Transition) {
            return if (current.segment is PlaybackSegment.Wait && current.stage.id == item.stageId) player.pause(owner.generation)
            else savePlace(feed.readableBefore(item))
        }
        if (current.state.segmentIndex == feed.segmentIndex(item)) return player.pause(owner.generation)
        return when (item) {
            is ServiceBookItem.Line -> player.seekLine(item.trackId, item.line.id, owner.generation)
            is ServiceBookItem.Track -> player.seekLine(item.track.id, item.track.lines.first().id, owner.generation)
            else -> player.seekStage(item.stageId, owner.generation)
        }.also { failedSave = !it }
    }
    fun saveVisible(): Boolean = if (viewport.manual) savePlace(currentItem) else player.pause(owner.generation)
    fun leave(action: () -> Unit) {
        if (!owner.matches(player)) return
        saveVisible()
        action()
    }
    fun focusItem(index: Int) {
        viewport.focusJob?.cancel()
        viewport.focusJob = scope.launch(start = CoroutineStart.LAZY) {
            val job = currentCoroutineContext().job
            viewport.positioning = true
            try { viewport.scroll.animateScrollToItem(index) } finally {
                if (viewport.focusJob === job) { viewport.positioning = false; viewport.focusJob = null }
            }
        }
        viewport.focusJob?.start()
    }
    fun chooseMode(value: Int) {
        if (!owner.matches(player)) return
        val anchor = currentItem.key
        val oldIndex = feed.indexOf(anchor)
        val oldHeight = viewport.scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == oldIndex }?.size ?: 0
        val oldOffset = if (oldIndex == viewport.scroll.firstVisibleItemIndex) viewport.scroll.firstVisibleItemScrollOffset else 0
        saveVisible()
        if (value == 2) meaning = true
        else {
            val changed = original != (value == 0)
            meaning = false
            onOriginalChange(value == 0)
            if (changed) {
                viewport.manual = true
                viewport.focusJob?.cancel()
                viewport.focusJob = scope.launch(start = CoroutineStart.LAZY) {
                    val job = currentCoroutineContext().job
                    viewport.positioning = true
                    try {
                        withFrameNanos { }
                        feed.indexOf(anchor)?.let { index ->
                            viewport.scroll.scrollToItem(index)
                            withFrameNanos { }
                            val height = viewport.scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
                            viewport.scroll.scrollToItem(index, translatedParagraphOffset(oldOffset, oldHeight, height))
                        }
                    } finally {
                        if (viewport.focusJob === job) { viewport.positioning = false; viewport.focusJob = null }
                    }
                }
                viewport.focusJob?.start()
            }
        }
    }
    fun playOrPause() {
        if (!owner.matches(player)) return
        if (player.reading?.state?.isPlaying == true) {
            player.pause(owner.generation)
            return
        }
        if (meaning) { meaning = false; return }
        if (currentItem is ServiceBookItem.End || (!viewport.manual && reading.segment is PlaybackSegment.End)) { leave(onFinish); return }
        if (currentItem is ServiceBookItem.Missing || (!viewport.manual && reading.segment is PlaybackSegment.MissingReading)) {
            val stageId = if (currentItem is ServiceBookItem.Missing) currentItem.stageId else reading.stage.id
            val next = reading.plan.stages.getOrNull(reading.plan.stages.indexOfFirst { it.id == stageId } + 1)
            if (next != null && player.seekStage(next.id, owner.generation)) {
                viewport.manual = false
                viewport.touched = false
                focusItem(feed.stageIndex(next.id))
            }
            return
        }
        if (!saveVisible()) return
        // A normal resume keeps its original interpolation baseline. A new manual
        // starting point consumes only the time still remaining in this paragraph.
        if (viewport.manual || viewport.playStartKey != currentItem.key) {
            viewport.playStartKey = currentItem.key
            viewport.playStartOffset = viewport.scroll.firstVisibleItemScrollOffset
            viewport.playStartElapsedQuarterMillis = player.reading?.state?.elapsedQuarterMillis ?: 0L
        }
        if (player.play(owner.generation)) {
            viewport.manual = false
            viewport.touched = false
            failedSave = false
        }
    }

    val drag = remember(viewport, player) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f && owner.matches(player)) {
                    viewport.browse()
                    player.pause(owner.generation)
                }
                return Offset.Zero
            }
        }
    }
    // One write after a manual gesture settles, never on each pixel or programmatic animation.
    LaunchedEffect(viewport) {
        snapshotFlow { BookScrollPosition(viewport.scroll.isScrollInProgress, viewport.touched, viewport.positioning, viewport.scroll.firstVisibleItemIndex) }
            .collectLatest { position ->
                if (!position.scrolling && position.touched && viewport.manual && !position.positioning && owner.matches(player)) {
                    delay(180)
                    if (!viewport.scroll.isScrollInProgress && !viewport.positioning && owner.matches(player)) {
                        if (savePlace(visibleBookItem(feed, viewport, requireNotNull(player.reading).segment))) viewport.touched = false
                    }
                }
            }
    }
    LaunchedEffect(owner, player.focusRequest, reading.state.isPlaying, reading.state.elapsedQuarterMillis, meaning) {
        if (meaning) { player.pause(owner.generation); return@LaunchedEffect }
        if (!viewport.manual && owner.matches(player)) {
            val job = currentCoroutineContext().job
            viewport.focusJob?.takeUnless { it === job }?.cancel()
            viewport.focusJob = job
            viewport.positioning = true
            try {
                val current = player.reading ?: return@LaunchedEffect
                val target = feed.targetIndex(current.segment)
                if (viewport.scroll.firstVisibleItemIndex != target) viewport.scroll.animateScrollToItem(target)
                withFrameNanos { }
                if (viewport.manual || !owner.matches(player)) return@LaunchedEffect
                val segment = current.segment
                val geometry = viewport.scroll.layoutInfo
                val item = geometry.visibleItemsInfo.firstOrNull { it.index == target }
                val start = if (feed.items[target].key == viewport.playStartKey) viewport.playStartOffset else 0
                val startElapsed = if (feed.items[target].key == viewport.playStartKey) viewport.playStartElapsedQuarterMillis else 0L
                val offset = if (segment is PlaybackSegment.Text && item != null)
                    timedParagraphOffset(item.size, geometry.viewportEndOffset - maxOf(0, geometry.viewportStartOffset),
                        current.state.elapsedQuarterMillis, segment.durationBaseMillis, start, startElapsed) else 0
                if (viewport.scroll.firstVisibleItemIndex == target) {
                    val delta = offset - viewport.scroll.firstVisibleItemScrollOffset
                    if (delta != 0) viewport.scroll.animateScrollBy(delta.toFloat(), tween(200, easing = LinearEasing))
                }
            } finally {
                if (viewport.focusJob === job) { viewport.positioning = false; viewport.focusJob = null }
            }
        }
    }
    DisposableEffect(viewport, lifecycle) {
        fun flushBookmark() {
            if (viewport.manual && viewport.touched && owner.matches(player)) {
                if (savePlace(visibleBookItem(feed, viewport, requireNotNull(player.reading).segment))) viewport.touched = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) flushBookmark()
        }
        lifecycle.addObserver(observer)
        onDispose { flushBookmark(); viewport.focusJob?.cancel(); lifecycle.removeObserver(observer) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val fontScale = LocalDensity.current.fontScale
        val compact = maxHeight < 500.dp || fontScale >= 1.8f || (maxWidth < 360.dp && fontScale > 1.25f)
        Column(Modifier.fillMaxSize().testTag("service_book")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { leave(onBack) }, Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "К службе")
                }
                Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { leave(onSelectStage) }
                    .testTag("open_map").semantics { role = Role.Button; contentDescription = "Порядок службы"; stateDescription = visibleStage.name },
                    verticalArrangement = Arrangement.Center) {
                    Text(if (compact) visibleStage.shortTitle else visibleStage.name,
                        Modifier.testTag("current_stage_title").semantics { heading() },
                        style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (!compact) Text("${reading.plan.stages.indexOf(visibleStage) + 1} из ${reading.plan.stages.size} · Порядок службы",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }, Modifier.size(48.dp).testTag("player_menu")) {
                        Icon(Icons.Default.MoreHoriz, "Настройки чтения")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        CompositionLocalProvider(LocalDensity provides requestedDensity) {
                        DropdownMenuItem(text = { Text("Скорость автопрокрутки") }, onClick = { menu = false; tempoOpen = true }, Modifier.testTag("scroll_options"))
                        DropdownMenuItem(text = { Text("Открытое чтение") }, onClick = { menu = false; saveVisible(); detailsOpen = true }, Modifier.testTag("open_reading_details"))
                        DropdownMenuItem(text = { Text(if (dark) "Светлый экран" else "Темный экран") }, onClick = { menu = false; onDarkChange() })
                        DropdownMenuItem(text = { Text("Размер текста") }, onClick = { menu = false; leave(onSettings) })
                        DropdownMenuItem(text = { Text("Тишина на телефоне") }, onClick = { menu = false; leave(onSoundSettings) })
                        }
                    }
                }
            }
            if (compact) Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                val labels = listOf("Оригинал", "Русский", "Смысл")
                OutlinedButton(onClick = { modeMenu = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("reader_mode_chooser").semantics { contentDescription = "Язык и пояснение"; stateDescription = labels[mode] }) {
                    Text(labels[mode], Modifier.testTag("reader_selected_mode"))
                    Spacer(Modifier.width(12.dp)); Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp))
                }
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false },
                    modifier = Modifier.testTag("reader_controls_list").semantics { dismiss { modeMenu = false; true } }) {
                    CompositionLocalProvider(LocalDensity provides requestedDensity) {
                    if (russianFallback && !meaning) Text("Для этого чтения доступен русский текст",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("scripture_language_notice"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    labels.forEachIndexed { index, label ->
                        val tag = listOf("controls_original", "controls_text", "controls_meaning")[index]
                        DropdownMenuItem(text = { Text(label) }, enabled = index != 0 || visibleOriginalAvailable,
                            onClick = { modeMenu = false; chooseMode(index) },
                            trailingIcon = { if (mode == index) Icon(Icons.Default.Check, null, Modifier.testTag("${tag}_selected_marker")) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag(tag).semantics { selected = mode == index })
                        if (index < labels.lastIndex) Spacer(Modifier.height(12.dp))
                    }
                    }
                }
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("Оригинал", "Русский", "Смысл").forEachIndexed { index, label ->
                    val selected = mode == index
                    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                        TextButton(onClick = { chooseMode(index) }, enabled = index != 0 || visibleOriginalAvailable,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag(listOf("player_original_tab", "player_text_tab", "player_meaning_tab")[index])
                                .semantics { this.selected = selected }) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current)
                        }
                    }
                }
            }
            if (russianFallback && !meaning && !compact) Text("Для этого чтения доступен русский текст",
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag("scripture_language_notice"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp).testTag("reader_surface"), color = MaterialTheme.colorScheme.surface) {
                if (meaning) BookMeaning(visibleStage, visibleTrack, onOpenSource,
                    reading.plan.serviceDate.takeIf { reading.plan.segments.any { segment ->
                        segment is PlaybackSegment.MissingReading && segment.stageId == visibleStage.id
                    } })
                else LazyColumn(state = viewport.scroll,
                    modifier = Modifier.fillMaxSize().testTag("lyrics_list").nestedScroll(drag),
                    contentPadding = PaddingValues(top = 20.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    items(feed.items, key = { it.key }, contentType = { it::class }) { item ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                                when (item) {
                                    is ServiceBookItem.Stage -> {
                                        if (item.stage.order != reading.plan.stages.first().order) {
                                            HorizontalDivider(Modifier.padding(top = 28.dp, bottom = 28.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                        Text(item.stage.section, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                                        Spacer(Modifier.height(8.dp))
                                        Text(item.stage.name, Modifier.testTag("book_stage_${item.stageId}").semantics { heading() },
                                            style = MaterialTheme.typography.headlineMedium)
                                        if (reading.plan.tracksByStage.getValue(item.stageId).any { it.isExcerpt }) {
                                            Text("Некоторые тексты этой части приведены фрагментами",
                                                Modifier.padding(top = 12.dp).testTag("book_scope_${item.stageId}"),
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    is ServiceBookItem.Track -> {
                                        Text(item.track.title, Modifier.testTag("feed_header_${item.track.id}").semantics { heading() },
                                            style = if (item.track.isScripture || item.track.lines.size > 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                                            color = if (item.track.isScripture || item.track.lines.size > 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (item.track.isScripture) Text(item.track.subtitle + if (original) " · русский текст" else "",
                                            Modifier.padding(top = 8.dp).testTag("feed_language_${item.track.id}"),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        item.track.contextNote?.takeIf { it.isNotBlank() }?.let { note ->
                                            BookContextDisclosure(item.track.id, note)
                                        }
                                    }
                                    is ServiceBookItem.Line -> {
                                        val selected = reading.segment.let { it is PlaybackSegment.Text && it.lineId == item.line.id }
                                        item.line.speaker?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(bottom = 8.dp).testTag("feed_speaker_${item.line.id}")) }
                                        Text(readerLineText(item.line, original),
                                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("feed_line_${item.line.id}")
                                                .clickable {
                                                    if (owner.matches(player)) {
                                                        viewport.browse()
                                                        if (savePlace(item)) {
                                                            viewport.touched = false
                                                            feed.indexOf(item.key)?.let(::focusItem)
                                                        }
                                                    }
                                                }.semantics { this.selected = selected; role = Role.Button },
                                            fontSize = 25.sp, lineHeight = 36.sp,
                                            fontWeight = if (selected && reading.state.isPlaying) FontWeight.Medium else FontWeight.Normal,
                                            color = if (selected && reading.state.isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    }
                                    is ServiceBookItem.Guidance -> {
                                        val stage = feed.stage(item)
                                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(stage.description, style = MaterialTheme.typography.bodyLarge)
                                                stage.cue?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                if (stage.id == "preparation") TextButton(onClick = {
                                                    val next = reading.plan.stages.getOrNull(reading.plan.stages.indexOf(stage) + 1)
                                                    if (next != null && owner.matches(player) && player.seekStage(next.id, owner.generation)) {
                                                        viewport.manual = false; viewport.touched = false
                                                        focusItem(feed.stageIndex(next.id))
                                                    }
                                                }, modifier = Modifier.heightIn(min = 48.dp).testTag("start_liturgy")) { Text("Перейти к началу литургии") }
                                            }
                                        }
                                    }
                                    is ServiceBookItem.Missing -> Surface(modifier = Modifier.testTag("missing_reading_${item.stageId}"),
                                        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                                        Column(Modifier.padding(20.dp).testTag("missing_reading_barrier"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("Чтение этого дня", style = MaterialTheme.typography.titleMedium)
                                            Text("Текст на ${reading.plan.serviceDate.format(bookDateFormat)} пока не добавлен. Послушайте чтение в храме, затем продолжайте ниже",
                                                style = MaterialTheme.typography.bodyLarge)
                                            Text("Автопрокрутка остановится здесь", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    is ServiceBookItem.Transition -> {
                                        if (reading.segment is PlaybackSegment.Wait && reading.stage.id == item.stageId) {
                                            val next = reading.plan.stages.getOrNull(reading.plan.stages.indexOf(feed.stage(item)) + 1)
                                            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                                                Column(Modifier.padding(20.dp).testTag("stage_wait"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    Text("Слушаем службу", style = MaterialTheme.typography.titleMedium)
                                                    Text("Эта часть в храме может продолжаться. Следующий переход по времени будет примерным", style = MaterialTheme.typography.bodyMedium)
                                                    next?.cue?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                    if (next != null) TextButton(onClick = {
                                                        if (owner.matches(player) && player.seekStage(next.id, owner.generation)) {
                                                            viewport.manual = false; viewport.touched = false
                                                            focusItem(feed.stageIndex(next.id))
                                                        }
                                                    }, Modifier.heightIn(min = 48.dp).testTag("next_stage")) { Text("Дальше: ${next.shortTitle}") }
                                                }
                                            }
                                        }
                                    }
                                    is ServiceBookItem.End -> Column(Modifier.padding(vertical = 24.dp).testTag("book_end"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("Конец порядка службы", style = MaterialTheme.typography.headlineMedium)
                                        Text("Можно вернуться к любому месту выше", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (reading.segment !is PlaybackSegment.End && currentItem !is ServiceBookItem.End) {
                                            TextButton(onClick = { leave(onFinish) }, Modifier.heightIn(min = 48.dp)
                                                .testTag("finish_book_reading")) { Text("Закончить чтение") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val missing = currentItem is ServiceBookItem.Missing || (!viewport.manual && reading.segment is PlaybackSegment.MissingReading)
            val end = currentItem is ServiceBookItem.End || (!viewport.manual && reading.segment is PlaybackSegment.End)
            val label = when { reading.state.isPlaying -> "Пауза"; meaning -> "К тексту"; missing -> "Чтение закончилось"; end -> "Закончить чтение"; else -> "Автопрокрутка" }
            val primaryTag = when { meaning -> "return_to_text"; missing -> "continue_missing_reading"; end -> "finish_reading"; else -> "play_lyrics" }
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (failedSave) Text("Не удалось сохранить место. Попробуйте ещё раз", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (reading.state.isPlaying && !compact) Text("Автопрокрутка · примерный темп",
                        Modifier.testTag("reading_status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (compact) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val compactLabel = when { reading.state.isPlaying -> "Пауза"; meaning -> "Текст"; missing -> "Далее"; end -> "Готово"; else -> "Пуск" }
                        Button(onClick = ::playOrPause, Modifier.weight(1f).heightIn(min = 48.dp).testTag(primaryTag)
                            .semantics { contentDescription = label }) { Text(compactLabel) }
                        IconButton(onClick = { leave(onFindStage) }, Modifier.size(48.dp).testTag("clarify_stage")) { Icon(Icons.Default.Search, "Найти место") }
                        IconButton(onClick = { leave(onMicrophone) }, Modifier.size(48.dp).testTag("reader_microphone")) { Icon(Icons.Default.MicNone, "Микрофон") }
                    } else {
                        Button(onClick = ::playOrPause, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(primaryTag)) {
                            Icon(if (reading.state.isPlaying) Icons.Default.Pause else if (meaning) Icons.Default.Notes else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(12.dp)); Text(label)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { leave(onFindStage) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("clarify_stage"), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                                Icon(Icons.Default.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Найти место")
                            }
                            OutlinedButton(onClick = { leave(onMicrophone) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("reader_microphone"), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
                                Icon(Icons.Default.MicNone, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Микрофон")
                            }
                        }
                    }
                }
            }
        }
    }

    if (tempoOpen) AlertDialog(onDismissRequest = { tempoOpen = false }, title = {
        CompositionLocalProvider(LocalDensity provides requestedDensity) { Text("Автопрокрутка") }
    }, text = {
        CompositionLocalProvider(LocalDensity provides requestedDensity) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Текст движется без звука, с примерной скоростью службы. Любая ручная прокрутка остановит его")
            PlaybackSpeed.entries.forEach { speed ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { player.changeSpeed(speed, owner.generation) }
                    .testTag("tempo_${speed.name.lowercase()}").semantics { selected = reading.state.speed == speed; role = Role.RadioButton },
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = reading.state.speed == speed, onClick = null)
                    Text(when (speed) { PlaybackSpeed.SLOW -> "Медленнее · 0,75×"; PlaybackSpeed.NORMAL -> "Обычно · 1×"; PlaybackSpeed.FAST -> "Быстрее · 1,25×" })
                }
            }
            Text("Перед отсутствующим чтением плеер остановится. Продолжить можно после того, как чтение прозвучит в храме", style = MaterialTheme.typography.bodySmall)
        }
        }
    }, confirmButton = {
        CompositionLocalProvider(LocalDensity provides requestedDensity) {
            TextButton(onClick = { tempoOpen = false }, Modifier.heightIn(min = 48.dp).testTag("tempo_done")) { Text("Готово") }
        }
    })
    if (detailsOpen) AlertDialog(onDismissRequest = { detailsOpen = false }, title = {
        CompositionLocalProvider(LocalDensity provides requestedDensity) { Text("Открытое чтение") }
    }, text = {
        CompositionLocalProvider(LocalDensity provides requestedDensity) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Тексты на ${reading.plan.serviceDate.format(bookDateFormat)}", Modifier.testTag("reading_details_date"))
            reading.migrationNote?.let {
                Text("Тексты дополнены. Вернулись к последней сохраненной фразе", Modifier.testTag("reading_details_migration_notice"),
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text(visibleStage.name, Modifier.testTag("reading_details_stage"), style = MaterialTheme.typography.titleMedium)
            visibleTrack?.let { Text(it.title, Modifier.testTag("reading_details_track")); Text(readerTrackLanguage(it, original), style = MaterialTheme.typography.bodySmall) }
            Text(if (restoredNotice) "Сохранённое чтение. Место в храме можно уточнить по словам или микрофону" else "Вы читаете самостоятельно. Автопрокрутка показывает только примерный ход службы")
            Text("Постоянные тексты собраны по порядку. Песнопения и чтения, которые меняются по дате и храму, могут отличаться", style = MaterialTheme.typography.bodySmall)
        }
        }
    }, confirmButton = {
        CompositionLocalProvider(LocalDensity provides requestedDensity) {
            TextButton(onClick = { detailsOpen = false }, Modifier.heightIn(min = 48.dp).testTag("close_reading_details")) { Text("Закрыть") }
        }
    })
}

private val bookDateFormat = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru"))

@Composable
private fun BookContextDisclosure(trackId: String, note: String) {
    var expanded by rememberSaveable(trackId) { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { expanded = !expanded }, Modifier.heightIn(min = 48.dp).testTag("feed_context_toggle_$trackId"),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
            Text("Об этом месте", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(12.dp))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        if (expanded) Text(note, Modifier.testTag("feed_context_$trackId"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookMeaning(stage: LiturgyStage, track: PrayerTrack?, onOpenSource: (String) -> Unit,
    missingReadingDate: java.time.LocalDate?) {
    // Save the publication identity with its disclosure. Restoration must not open
    // a different source merely because it occupies the same composition slot.
    val sourceIdentity = listOf(stage.id, track?.id.orEmpty(), stage.sourceUrl.orEmpty(), track?.sourceUrl.orEmpty())
    var expandedSource by rememberSaveable(sourceIdentity) { mutableStateOf<List<String>?>(null) }
    val sourceOpen = expandedSource == sourceIdentity
    val source = explanationSource(stage.sourceUrl, stage.sourceTitle)
    val scroll = key(stage.id, track?.id) { rememberScrollState() }
    Box(Modifier.fillMaxSize().verticalScroll(scroll).testTag("service_content"), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            BookMeaningSection("Что происходит", stage.description)
            if (missingReadingDate != null) Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp)) {
                Text("Текст чтения на ${missingReadingDate.format(bookDateFormat)} пока не добавлен. Здесь объясняется общий смысл этой части службы",
                    Modifier.padding(20.dp).testTag("reading_unavailable"), style = MaterialTheme.typography.bodyMedium)
            }
            BookMeaningSection("В чём смысл", stage.meaning)
            stage.cue?.takeIf { it.isNotBlank() }?.let { BookMeaningSection("Как узнать этот момент", it) }
            stage.whatToDo.takeIf { it.isNotBlank() }?.let { BookMeaningSection("На что обратить внимание", it) }
            track?.explanation?.takeIf { it.isNotBlank() }?.let {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Об этом тексте", style = MaterialTheme.typography.titleMedium)
                    Text(it, Modifier.testTag("track_explanation"), style = MaterialTheme.typography.bodyLarge)
                }
            }
            track?.contextNote?.takeIf { it.isNotBlank() }?.let {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Особенности этого текста", style = MaterialTheme.typography.titleMedium)
                    Text(it, Modifier.testTag("prayer_context_note"), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (source != null || track?.sourceUrl != null) {
                TextButton(onClick = { expandedSource = if (sourceOpen) null else sourceIdentity },
                    Modifier.heightIn(min = 48.dp).testTag("explanation_source_toggle")
                        .semantics { stateDescription = if (sourceOpen) "Развернуто" else "Свернуто" }) {
                    Text("Источники текста и пояснения")
                }
                if (sourceOpen) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    source?.let {
                        Text("Публикация для подготовки пояснения", style = MaterialTheme.typography.bodySmall)
                        Text(it.title, Modifier.testTag("explanation_source_title"), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { onOpenSource(it.url) }, Modifier.heightIn(min = 48.dp).testTag("explanation_source_open")) { Text("Открыть пояснение") }
                    }
                    track?.sourceUrl?.let { url -> OutlinedButton(onClick = { onOpenSource(url) }, Modifier.heightIn(min = 48.dp)) { Text("Источник текста") } }
                }
            }
        }
    }
}

@Composable
private fun BookMeaningSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
