package ru.liturgyguide.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import ru.liturgyguide.model.*
import ru.liturgyguide.service.SpeechGuide
import ru.liturgyguide.service.SpeechGuideState
import ru.liturgyguide.service.SpeechStatus
import ru.liturgyguide.service.book.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class BookPageItem(val id: String, val chapter: BookChapter, val block: BookBlock?)
private data class BookSearchLine(val hit: BookSearchHit, val offset: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(book: ServiceBook, parishId: String?, readingStore: BookReadingStore,
    textSize: Int, onBack: () -> Unit, onOpenUrl: (String) -> Unit,
    onChooseVersion: (ServiceBookKey) -> Unit, onSettings: () -> Unit,
    isSaved: Boolean = true, loadWarning: String? = null,
    onRefresh: () -> Unit = {}, refreshing: Boolean = false, onAcceptUpdate: (() -> Boolean)? = null) {
    val initial = remember(book.contentHash, parishId) { readingStore.restore(book, parishId) }
    var mode by rememberSaveable(book.contentHash) { mutableStateOf(initial?.mode ?: BookReadingMode.ORIGINAL) }
    var lastTextMode by rememberSaveable(book.contentHash) {
        mutableStateOf(initial?.lastTextMode ?: initial?.mode?.takeUnless { it == BookReadingMode.MEANING } ?: BookReadingMode.ORIGINAL)
    }
    var pendingSearch by remember { mutableStateOf<BookSearchHit?>(null) }
    var searchLine by remember { mutableStateOf<BookSearchLine?>(null) }
    var wordsPerMinute by rememberSaveable(book.contentHash) { mutableIntStateOf(initial?.wordsPerMinute ?: 85) }
    var showAltar by rememberSaveable(book.contentHash) { mutableStateOf(initial?.showAltar ?: false) }
    var playing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var microphoneFirst by remember { mutableStateOf(false) }
    val cursor = remember(book.contentHash, parishId) { BookReadingCursor(initial ?: BookReadingPosition(
        book.key, book.contentHash, parishId, book.blocks.first().id, book.chapters.first().id,
        anchorKind = BookAnchorKind.HEADER)) }
    var saveError by remember { mutableStateOf(false) }
    var superseded by remember { mutableStateOf(false) }
    var positioning by remember { mutableStateOf(true) }
    var seekRevision by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val items = remember(book.contentHash, showAltar, mode) {
        book.chapters.filter { chapter -> mode == BookReadingMode.MEANING || showAltar || chapter.blocks.any { !it.isAltarText } }.flatMap { chapter ->
            listOf(BookPageItem("chapter:${chapter.id}", chapter, null)) +
                if (mode == BookReadingMode.MEANING) emptyList() else chapter.blocks.filter { showAltar || !it.isAltarText }
                    .map { BookPageItem(it.id, chapter, it) }
        }
    }
    val list = rememberLazyListState()
    val currentItem by remember(items, list) { derivedStateOf { items.getOrNull(list.firstVisibleItemIndex) ?: items.last() } }
    val currentChapter = currentItem.chapter

    fun viewport(): BookViewport {
        val index = list.firstVisibleItemIndex
        val visible = items.getOrNull(index) ?: items.last()
        val kind = when { index >= items.size -> BookAnchorKind.END; visible.block == null -> BookAnchorKind.HEADER; else -> BookAnchorKind.BLOCK }
        val blockId = visible.block?.id ?: visible.chapter.blocks.firstOrNull { showAltar || !it.isAltarText }?.id ?: visible.chapter.blocks.first().id
        val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 1
        val fraction = (list.firstVisibleItemScrollOffset.toFloat() / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        return BookViewport(visible.chapter.id, blockId, fraction, kind)
    }
    fun snapshot() = cursor.snapshot(mode, wordsPerMinute, showAltar).copy(lastTextMode = lastTextMode)
    fun position(): BookReadingPosition {
        if (!positioning) cursor.observe(viewport(), mode)
        return snapshot()
    }
    suspend fun scrollToCursor() {
        var anchor = cursor.value
        val hiddenAnchor = when (anchor.anchorKind) {
            BookAnchorKind.BLOCK -> items.none { it.block?.id == anchor.blockId }
            BookAnchorKind.HEADER -> items.none { it.chapter.id == anchor.chapterId }
            BookAnchorKind.END -> false
        }
        if (mode != BookReadingMode.MEANING && hiddenAnchor) {
            val oldIndex = book.chapters.indexOfFirst { it.id == anchor.chapterId }.coerceAtLeast(0)
            val chapter = items.firstOrNull { item -> book.chapters.indexOf(item.chapter) >= oldIndex }?.chapter ?: items.last().chapter
            cursor.seek(chapter.id, chapter.blocks.firstOrNull { showAltar || !it.isAltarText }?.id ?: chapter.blocks.first().id,
                BookAnchorKind.HEADER)
            anchor = cursor.value
        }
        val index = when {
            mode == BookReadingMode.MEANING || anchor.anchorKind == BookAnchorKind.HEADER -> items.indexOfFirst { it.chapter.id == anchor.chapterId }
            anchor.anchorKind == BookAnchorKind.END -> items.size
            else -> items.indexOfFirst { it.block?.id == anchor.blockId }.takeIf { it >= 0 }
                ?: items.indexOfFirst { it.chapter.id == anchor.chapterId }
        }.coerceAtLeast(0)
        list.scrollToItem(index)
        withFrameNanos { }
        val height = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
        if (mode != BookReadingMode.MEANING && height > 0 && anchor.fraction > 0f)
            list.scrollToItem(index, (height * anchor.fraction).toInt())
        val target = pendingSearch?.takeIf { it.mode == mode && it.blockId == anchor.blockId }
        if (target != null) {
            // First compose this block, then use its real line layout and the text's
            // offset below speaker/translation labels. No character-to-height estimate.
            val line = withTimeoutOrNull(1_500) {
                snapshotFlow { pendingSearch to searchLine }.first { (pending, line) -> pending != target || line?.hit == target }.second
            }
            if (line != null && pendingSearch == target) list.scrollToItem(index,
                (line.offset - with(density) { 12.dp.roundToPx() }).coerceAtLeast(0))
            if (pendingSearch == target) pendingSearch = null
        }
    }
    LaunchedEffect(book.contentHash, mode, showAltar, seekRevision) {
        positioning = true
        playing = false
        try { scrollToCursor() } finally { positioning = false }
    }
    val latestPosition by rememberUpdatedState(newValue = { position() })
    LaunchedEffect(book.contentHash, mode, showAltar, wordsPerMinute) {
        snapshotFlow { if (positioning) null else viewport() }.collectLatest { visible ->
            if (visible == null) return@collectLatest
            cursor.observe(visible, mode)
            delay(400)
            // Small cursor writes are serialized on the owner thread. A late IO job cannot
            // overwrite the more recent synchronous lifecycle snapshot.
            if (!superseded) saveError = !readingStore.save(snapshot())
        }
    }
    DisposableEffect(lifecycle, book.contentHash, view) {
        view.keepScreenOn = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view.keepScreenOn = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                view.keepScreenOn = false
                playing = false
                if (!positioning && !superseded) readingStore.save(latestPosition())
            }
        }
        lifecycle.addObserver(observer)
        onDispose { playing = false; view.keepScreenOn = false; if (!positioning && !superseded) readingStore.save(latestPosition()); lifecycle.removeObserver(observer) }
    }
    val manualScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) { playing = false; pendingSearch = null }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(playing, wordsPerMinute, mode) {
        if (!playing || mode == BookReadingMode.MEANING) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (playing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val frame = withFrameNanos { it }
            // Pause can arrive while awaiting this frame. Do not move one last pixel
            // after the lifecycle observer has already saved the visible line.
            if (!playing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) break
            val elapsed = ((frame - previous) / 1_000_000_000f).coerceIn(0f, .1f)
            previous = frame
            val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            val block = currentItem.block
            val text = if (mode == BookReadingMode.RUSSIAN) block?.russian ?: block?.original else block?.original
            val words = text?.split(Regex("\\s+"))?.size?.coerceAtLeast(10) ?: 12
            val height = visible?.size?.toFloat() ?: with(density) { 100.dp.toPx() }
            // Keep the chosen words/min proportional even on wide paragraphs. A 5dp/s
            // floor makes slow rates indistinguishable; 0.1dp only guards degenerate geometry.
            val speed = (height * wordsPerMinute / (words * 60f)).coerceIn(with(density) { .1.dp.toPx() }, with(density) { 65.dp.toPx() })
            val consumed = list.scrollBy(speed * elapsed)
            if (consumed == 0f && !list.canScrollForward) playing = false
        }
    }
    fun changeMode(value: BookReadingMode) {
        if (value == mode) return
        position()
        playing = false
        positioning = true
        pendingSearch = null
        if (value != BookReadingMode.MEANING) lastTextMode = value
        mode = value
    }
    fun openSearch(useMicrophone: Boolean) { playing = false; microphoneFirst = useMicrophone; sheet = "search" }
    fun acceptUpdate() {
        playing = false
        if (!readingStore.save(position())) {
            saveError = true
            return
        }
        sheet = null
        superseded = onAcceptUpdate?.invoke() == true
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Use this reader's actual space, including split-screen and in-app constraints.
    val shortViewport = maxHeight < 420.dp
    val compactLabels = shortViewport || density.fontScale > 1.5f ||
        (maxWidth < 360.dp && density.fontScale > 1.1f)
    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                Column(Modifier.statusBarsPadding()) {
                    if (onAcceptUpdate != null) TextButton(onClick = ::acceptUpdate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_accept_update")) { Text("Обновленный текст готов · Открыть") }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("book_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                        TextButton(onClick = { playing = false; sheet = "order" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("book_order")) {
                            Column(Modifier.weight(1f)) {
                                Text(currentChapter.title, style = MaterialTheme.typography.titleSmall,
                                    maxLines = if (compactLabels) 1 else 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("book_chapter"))
                                if (!compactLabels) Text("Порядок службы", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ExpandMore, if (compactLabels) "Порядок службы" else null)
                        }
                        if (shortViewport) BookModePicker(mode, ::changeMode, onOpen = { playing = false }, compact = true, iconOnly = true)
                        IconButton(onClick = { playing = false; sheet = "options" }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.TextFields, "Настроить чтение")
                        }
                    }
                    if (!shortViewport) BookModePicker(mode, ::changeMode, onOpen = { playing = false }, compact = compactLabels)
                    if (saveError) Text("Не удалось сохранить место. Проверьте свободное место на телефоне",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("book_save_error"))
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (playing) Text("Примерный темп · $wordsPerMinute слов/мин", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BookReaderAction("Найти", Icons.Default.Search, "Найти место", { openSearch(false) },
                            Modifier.weight(1f).testTag("book_find"), iconsOnly = compactLabels)
                        BookReaderAction(if (mode == BookReadingMode.MEANING) "К тексту" else if (playing) "Пауза" else "Прокрутка",
                            if (mode == BookReadingMode.MEANING) Icons.Default.Article else if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (playing) "Остановить прокрутку" else if (mode == BookReadingMode.MEANING) "Вернуться к тексту" else "Прокручивать текст",
                            { if (mode == BookReadingMode.MEANING) changeMode(lastTextMode) else playing = !playing },
                            Modifier.weight(1f).testTag("book_play"), emphasized = true, iconsOnly = compactLabels)
                        BookReaderAction("Микрофон", Icons.Default.Mic, "Найти по микрофону", { openSearch(true) },
                            Modifier.weight(1f).testTag("book_microphone"), iconsOnly = compactLabels)
                    }
                }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.fillMaxHeight().widthIn(max = 600.dp).fillMaxWidth().nestedScroll(manualScroll)
                .pointerInput(Unit) {
                    // Touch means "let me read" even without dragging. Observe the
                    // initial event without consuming links, scrolling or other gestures.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        playing = false
                        pendingSearch = null
                    }
                }.testTag("book_content"),
                state = list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(items, key = { it.id }) { item ->
                    if (item.block == null) Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (item.chapter.id == items.first().chapter.id) {
                            Text(book.title, style = MaterialTheme.typography.headlineMedium)
                            Text(LocalDate.parse(book.key.date).format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru"))) +
                                if (isSaved) " · Сохранено на телефоне" else " · Пока не сохранено", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("book_saved"))
                            loadWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            book.selectedEventTitle?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(item.chapter.title, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() })
                        if (mode == BookReadingMode.MEANING) {
                            Text("Объяснение Феории", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.chapter.meaning,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = (19 + textSize * 2).sp, lineHeight = (29 + textSize * 3).sp))
                        }
                    } else BookTextBlock(item.block, mode, textSize,
                        searchHit = pendingSearch?.takeIf { it.blockId == item.block.id && it.mode == mode },
                        onSearchLine = { line -> if (pendingSearch == line.hit) searchLine = line })
                }
                item {
                    // A full end page lets the last short chapter align at the top too.
                    // Otherwise switching to Meaning near the end would silently select
                    // an earlier visible chapter and overwrite the reading position.
                    Column(Modifier.fillParentMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    HorizontalDivider()
                    Text("Конец последования", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("book_end"))
                    TextButton(onClick = { sheet = "sources" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Источники и состав текста") }
                    }
                }
            }
        }
    }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        when (sheet) {
            "search" -> BookSearchSheet(book, microphoneFirst, lastTextMode, onSelect = { hit ->
                positioning = true
                cursor.seek(hit.chapterId, hit.blockId)
                searchLine = null
                pendingSearch = hit
                mode = hit.mode
                lastTextMode = hit.mode
                val hidden = book.blocks.firstOrNull { it.id == hit.blockId }?.isAltarText == true
                if (hidden) showAltar = true
                sheet = null
                seekRevision++
            })
            "order" -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("book_order_list"),
                state = rememberLazyListState(initialFirstVisibleItemIndex = book.chapters.indexOfFirst { it.id == currentChapter.id }.coerceAtLeast(0) + 1),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Порядок службы", style = MaterialTheme.typography.headlineSmall) }
                itemsIndexed(book.chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                    val isCurrent = chapter.id == currentChapter.id
                    OutlinedButton(onClick = {
                        cursor.seek(chapter.id, chapter.blocks.firstOrNull { showAltar || !it.isAltarText }?.id ?: chapter.blocks.first().id, BookAnchorKind.HEADER)
                        if (mode != BookReadingMode.MEANING && chapter.blocks.all { it.isAltarText }) showAltar = true
                        positioning = true; sheet = null
                        seekRevision++
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_order_item_${chapter.id}")
                        .semantics { selected = isCurrent },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(chapter.title)
                            Text(if (isCurrent) "${index + 1} · Вы здесь" else "${index + 1}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            "options" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).testTag("book_options"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Чтение", style = MaterialTheme.typography.headlineSmall)
                Text("Темп прокрутки: $wordsPerMinute слов/мин", style = MaterialTheme.typography.titleMedium)
                Slider(value = wordsPerMinute.toFloat(), onValueChange = { wordsPerMinute = it.toInt() }, valueRange = 30f..180f)
                Text("Прокрутка помогает читать в выбранном темпе. Чтобы найти реальное место службы, используйте слова или микрофон",
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Молитвы, читаемые в алтаре", Modifier.weight(1f))
                    Switch(showAltar, { position(); positioning = true; showAltar = it })
                }
                OutlinedButton(onClick = { sheet = null; onSettings() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Размер текста и тема") }
                TextButton(onClick = { sheet = "sources" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Источники и вариант службы") }
                if (onAcceptUpdate != null) Button(onClick = ::acceptUpdate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_options_accept_update")) { Text("Открыть обновленный текст") }
                OutlinedButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_refresh")) {
                    Text(if (refreshing) "Обновляем текст" else "Обновить текст и перевод")
                }
                loadWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            "sources" -> LazyColumn(Modifier.heightIn(max = 600.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("Состав книги", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("Полное последование источника. На приходе отдельные молитвы и песнопения могут сокращаться или отличаться")
                    Spacer(Modifier.height(12.dp))
                    Text("Краткие пояснения в разделе \"Смысл\" подготовлены Феорией", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (refreshing) "Обновляем текст" else "Обновить текст и перевод")
                    }
                }
                items(book.notices) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                items((listOf(BookSource(book.sourceTitle, book.sourceUrl)) + book.sources).distinctBy { it.url }) { source ->
                    TextButton(onClick = { onOpenUrl(source.url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(source.title) }
                }
                if (book.mainEventOptions.size > 1) {
                    item { Text("Вариант дня", style = MaterialTheme.typography.titleMedium) }
                    items(book.mainEventOptions) { option ->
                        OutlinedButton(onClick = { sheet = null; onChooseVersion(book.key.copy(mainEvent = option.value)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(option.title) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookModePicker(mode: BookReadingMode, onSelect: (BookReadingMode) -> Unit, onOpen: () -> Unit, compact: Boolean, iconOnly: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    if (compact) Box(if (iconOnly) Modifier else Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (iconOnly) IconButton(onClick = { onOpen(); expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Translate, "Режим чтения: ${mode.title}")
        } else OutlinedButton(onClick = { onOpen(); expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(mode.title); Spacer(Modifier.width(12.dp)); Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            BookReadingMode.entries.forEach { value -> DropdownMenuItem(text = { Text(value.title) }, onClick = { expanded = false; onSelect(value) }) }
        }
    } else Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BookReadingMode.entries.forEach { value ->
            FilterChip(selected = mode == value, onClick = { onSelect(value) }, label = { Text(value.title) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("book_mode_${value.name.lowercase()}"))
        }
    }
}

@Composable
private fun BookTextBlock(block: BookBlock, mode: BookReadingMode, textSize: Int,
    searchHit: BookSearchHit? = null, onSearchLine: (BookSearchLine) -> Unit = {}) {
    var textLayout by remember(block.id, mode) { mutableStateOf<TextLayoutResult?>(null) }
    var textTop by remember(block.id, mode) { mutableStateOf<Float?>(null) }
    LaunchedEffect(searchHit, textLayout, textTop) {
        val hit = searchHit
        val layout = textLayout
        val top = textTop
        if (hit != null && layout != null && top != null && hit.matchStart in layout.layoutInput.text.text.indices) {
            val line = layout.getLineForOffset(hit.matchStart)
            onSearchLine(BookSearchLine(hit, (top + layout.getLineTop(line)).roundToInt()))
        }
    }
    val rubric = block.kind == BookBlockKind.RUBRIC || block.kind == BookBlockKind.LOCAL_VARIATION
    Column(Modifier.fillMaxWidth().testTag("book_block_${block.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (block.speaker != null) Text(block.speaker, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        if (mode == BookReadingMode.RUSSIAN && block.russian == null && !rubric)
            Text("Этот фрагмент пока в оригинале", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val text = if (mode == BookReadingMode.RUSSIAN) block.russian ?: block.original else block.original
        Text(text, style = if (rubric) MaterialTheme.typography.bodyMedium else
            MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = (21 + textSize * 2).sp, lineHeight = (32 + textSize * 3).sp),
            color = if (rubric) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("book_text_${block.id}").onGloballyPositioned { textTop = it.positionInParent().y },
            onTextLayout = { textLayout = it })
    }
}

@Composable
private fun BookReaderAction(label: String, icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, emphasized: Boolean = false, iconsOnly: Boolean = false) {
    // Keep full spoken names and large touch targets when space calls for icons.
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = if (iconsOnly) 56.dp else 72.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, description, Modifier.size(24.dp))
            if (!iconsOnly) Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BookSearchSheet(book: ServiceBook, startMicrophone: Boolean, preferredMode: BookReadingMode, onSelect: (BookSearchHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var speechState by remember { mutableStateOf(SpeechGuideState()) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val guide = remember(context) { SpeechGuide(context) { state ->
        speechState = state
        if (state.status == SpeechStatus.RESULT && state.transcript.isNotBlank()) query = state.transcript
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { permissionMessage = null; guide.stop(); guide.start() }
        else permissionMessage = "Микрофон не разрешен. Можно найти место по словам"
    }
    fun listen() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            guide.stop(); guide.start()
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { speechState = guide.initialState; if (startMicrophone) listen() }
    DisposableEffect(guide, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) guide.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); guide.destroy() }
    }
    val hits = remember(book.contentHash, query, preferredMode) { BookSearch.find(book, query, preferredMode) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Найти место", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(query, { query = it.take(300) }, label = { Text("Какие слова вы услышали?") },
                modifier = Modifier.fillMaxWidth().testTag("book_search_query"), singleLine = true)
        }
        item {
            OutlinedButton(onClick = { if (speechState.isActive) guide.stop() else listen() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_listen")) {
                Icon(if (speechState.isActive) Icons.Default.Stop else Icons.Default.Mic, null)
                Spacer(Modifier.width(12.dp)); Text(if (speechState.isActive) "Остановить микрофон" else "Послушать службу")
            }
            permissionMessage?.let { Text(it, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall) }
            if (speechState.status != SpeechStatus.IDLE) Text(speechState.message, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            if (speechState.canTryWithoutSupport) TextButton(onClick = { guide.start(skipSupportCheck = true) }) { Text("Попробовать распознавание") }
        }
        if (query.trim().length >= 2 && hits.isEmpty()) item { Text("Совпадений в этой книге нет. Попробуйте два-три других слова") }
        if (query.isBlank()) item { Text("Например: Иже Херувимы, Верую, Отче наш", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(hits, key = { it.blockId }) { hit ->
            OutlinedCard(onClick = { onSelect(hit) }, modifier = Modifier.fillMaxWidth().testTag("book_hit_${hit.blockId}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(hit.chapterTitle, style = MaterialTheme.typography.titleMedium)
                    Text(hit.mode.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(hit.excerpt, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
