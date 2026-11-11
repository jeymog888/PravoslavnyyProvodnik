package ru.liturgyguide.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import ru.liturgyguide.model.*
import ru.liturgyguide.service.SpeechGuide
import ru.liturgyguide.service.SpeechGuideState
import ru.liturgyguide.service.SpeechStatus
import ru.liturgyguide.service.book.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class BookPageItem(val id: String, val chapter: BookChapter, val block: BookBlock?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(book: ServiceBook, parishId: String?, readingStore: BookReadingStore,
    textSize: Int, onBack: () -> Unit, onOpenUrl: (String) -> Unit,
    onChooseVersion: (ServiceBookKey) -> Unit, onSettings: () -> Unit,
    isSaved: Boolean = true, loadWarning: String? = null,
    onRefresh: () -> Unit = {}, refreshing: Boolean = false, onAcceptUpdate: (() -> Unit)? = null) {
    val initial = remember(book.contentHash, parishId) { readingStore.restore(book, parishId) }
    var mode by rememberSaveable(book.contentHash) { mutableStateOf(initial?.mode ?: BookReadingMode.ORIGINAL) }
    var wordsPerMinute by rememberSaveable(book.contentHash) { mutableIntStateOf(initial?.wordsPerMinute ?: 85) }
    var showAltar by rememberSaveable(book.contentHash) { mutableStateOf(initial?.showAltar ?: false) }
    var playing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var microphoneFirst by remember { mutableStateOf(false) }
    val cursor = remember(book.contentHash, parishId) { BookReadingCursor(initial ?: BookReadingPosition(
        book.key, book.contentHash, parishId, book.blocks.first().id, book.chapters.first().id,
        anchorKind = BookAnchorKind.HEADER)) }
    var saveError by remember { mutableStateOf(false) }
    var positioning by remember { mutableStateOf(true) }
    var seekRevision by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val items = remember(book.contentHash, showAltar, mode) {
        book.chapters.flatMap { chapter ->
            listOf(BookPageItem("chapter:${chapter.id}", chapter, null)) +
                if (mode == BookReadingMode.MEANING) emptyList() else chapter.blocks.filter { showAltar || !it.isAltarText }
                    .map { BookPageItem(it.id, chapter, it) }
        }
    }
    val list = rememberLazyListState()
    val currentItem by remember(items, list) { derivedStateOf { items.getOrNull(list.firstVisibleItemIndex) ?: items.first() } }
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
    fun position(): BookReadingPosition {
        if (!positioning) cursor.observe(viewport(), mode)
        return cursor.snapshot(mode, wordsPerMinute, showAltar)
    }
    suspend fun scrollToCursor() {
        var anchor = cursor.value
        if (mode != BookReadingMode.MEANING && anchor.anchorKind == BookAnchorKind.BLOCK && items.none { it.block?.id == anchor.blockId }) {
            val chapter = book.chapters.firstOrNull { it.id == anchor.chapterId } ?: book.chapters.first()
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
            saveError = !readingStore.save(cursor.snapshot(mode, wordsPerMinute, showAltar))
        }
    }
    DisposableEffect(lifecycle, book.contentHash, view) {
        view.keepScreenOn = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view.keepScreenOn = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                view.keepScreenOn = false
                playing = false
                if (!positioning) readingStore.save(latestPosition())
            }
        }
        lifecycle.addObserver(observer)
        onDispose { playing = false; view.keepScreenOn = false; if (!positioning) readingStore.save(latestPosition()); lifecycle.removeObserver(observer) }
    }
    val manualScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) playing = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(playing, wordsPerMinute, mode) {
        if (!playing || mode == BookReadingMode.MEANING) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (playing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previous) / 1_000_000_000f).coerceIn(0f, .1f)
            previous = frame
            val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            val block = currentItem.block
            val text = if (mode == BookReadingMode.RUSSIAN) block?.russian ?: block?.original else block?.original
            val words = text?.split(Regex("\\s+"))?.size?.coerceAtLeast(10) ?: 12
            val height = visible?.size?.toFloat() ?: with(density) { 100.dp.toPx() }
            val speed = (height * wordsPerMinute / (words * 60f)).coerceIn(with(density) { 5.dp.toPx() }, with(density) { 65.dp.toPx() })
            val consumed = list.scrollBy(speed * elapsed)
            if (consumed == 0f && !list.canScrollForward) playing = false
        }
    }
    fun changeMode(value: BookReadingMode) {
        if (value == mode) return
        position()
        playing = false
        positioning = true
        mode = value
    }
    fun openSearch(useMicrophone: Boolean) { playing = false; microphoneFirst = useMicrophone; sheet = "search" }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                Column(Modifier.statusBarsPadding()) {
                    if (onAcceptUpdate != null) TextButton(onClick = {
                        playing = false; readingStore.save(position()); onAcceptUpdate()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_accept_update")) { Text("Обновленный текст готов · Открыть") }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("book_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                        TextButton(onClick = { playing = false; sheet = "order" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(currentChapter.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("book_chapter"))
                                Text("Порядок службы", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ExpandMore, null)
                        }
                        IconButton(onClick = { playing = false; sheet = "options" }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.TextFields, "Настроить чтение")
                        }
                    }
                    BookModePicker(mode, ::changeMode)
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (playing) Text("Примерный темп · $wordsPerMinute слов/мин", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { openSearch(false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("book_find")) {
                            Icon(Icons.Default.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Найти")
                        }
                        FilledTonalIconButton(onClick = {
                            if (mode == BookReadingMode.MEANING) { changeMode(BookReadingMode.ORIGINAL) }
                            else playing = !playing
                        }, modifier = Modifier.size(52.dp).testTag("book_play")) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (playing) "Остановить прокрутку" else if (mode == BookReadingMode.MEANING) "Вернуться к тексту" else "Прокручивать текст")
                        }
                        TextButton(onClick = { openSearch(true) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("book_microphone")) {
                            Icon(Icons.Default.Mic, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Слушать")
                        }
                    }
                }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.fillMaxHeight().widthIn(max = 600.dp).fillMaxWidth().nestedScroll(manualScroll).testTag("book_content"),
                state = list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(items, key = { it.id }) { item ->
                    if (item.block == null) Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (item.chapter.id == book.chapters.first().id) {
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
                        if (mode == BookReadingMode.MEANING) Text(item.chapter.meaning,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = (19 + textSize * 2).sp, lineHeight = (29 + textSize * 3).sp))
                    } else BookTextBlock(item.block, mode, textSize)
                }
                item {
                    // A full end page lets the last short chapter align at the top too.
                    // Otherwise switching to Meaning near the end would silently select
                    // an earlier visible chapter and overwrite the reading position.
                    Column(Modifier.fillParentMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    HorizontalDivider()
                    Text("Конец последования", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("book_end"))
                    TextButton(onClick = { sheet = "sources" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Источники и состав текста") }
                    if (saveError) Text("Не удалось сохранить место. Проверьте свободное место на телефоне", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        when (sheet) {
            "search" -> BookSearchSheet(book, microphoneFirst, onSelect = { hit ->
                positioning = true
                cursor.seek(hit.chapterId, hit.blockId)
                if (mode == BookReadingMode.MEANING) mode = BookReadingMode.ORIGINAL
                val hidden = book.blocks.firstOrNull { it.id == hit.blockId }?.isAltarText == true
                if (hidden) showAltar = true
                sheet = null
                seekRevision++
            })
            "order" -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Порядок службы", style = MaterialTheme.typography.headlineSmall) }
                items(book.chapters, key = { it.id }) { chapter ->
                    OutlinedButton(onClick = {
                        cursor.seek(chapter.id, chapter.blocks.firstOrNull { showAltar || !it.isAltarText }?.id ?: chapter.blocks.first().id, BookAnchorKind.HEADER)
                        positioning = true; sheet = null
                        seekRevision++
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(chapter.title, Modifier.fillMaxWidth()) }
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
private fun BookModePicker(mode: BookReadingMode, onSelect: (BookReadingMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    if (LocalDensity.current.fontScale > 1.5f) Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
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
private fun BookTextBlock(block: BookBlock, mode: BookReadingMode, textSize: Int) {
    val rubric = block.kind == BookBlockKind.RUBRIC || block.kind == BookBlockKind.LOCAL_VARIATION
    Column(Modifier.fillMaxWidth().testTag("book_block_${block.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (block.speaker != null) Text(block.speaker, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        if (mode == BookReadingMode.RUSSIAN && block.russian == null && !rubric)
            Text("Этот фрагмент пока в оригинале", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val text = if (mode == BookReadingMode.RUSSIAN) block.russian ?: block.original else block.original
        Text(text, style = if (rubric) MaterialTheme.typography.bodyMedium else
            MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = (21 + textSize * 2).sp, lineHeight = (32 + textSize * 3).sp),
            color = if (rubric) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun BookSearchSheet(book: ServiceBook, startMicrophone: Boolean, onSelect: (BookSearchHit) -> Unit) {
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
    val hits = remember(book.contentHash, query) { BookSearch.find(book, query) }
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
                    Text(hit.excerpt, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
