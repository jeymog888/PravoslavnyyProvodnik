package ru.liturgyguide.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
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
private data class BookSearchResult(val bookKey: ServiceBookKey, val bookHash: String, val query: String,
    val mode: BookReadingMode, val hits: List<BookSearchHit>)
private class BookPositioningOwner(var job: Job? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(book: ServiceBook, parishId: String?, readingStore: BookReadingStore,
    textSize: Int, onBack: () -> Unit, onOpenUrl: (String) -> Unit,
    onChooseVersion: (ServiceBookKey) -> Unit, onSettings: () -> Unit,
    isSaved: Boolean = true, loadWarning: String? = null,
    onRefresh: () -> Unit = {}, refreshing: Boolean = false, onAcceptUpdate: ((BookReadingPosition) -> Boolean)? = null,
    churchTypeInfo: String? = null, journeyContext: BookJourneyContext? = null,
    onChoosePart: (ServiceBookKey) -> Boolean = { false },
    onChooseJourneyVersion: (ServiceBookKey) -> Boolean = { false }) {
    val journey = journeyContext?.validatedFor(book.key, parishId)
    val polyeleosBook = book.key.topLevel == "3" && book.rankSelection?.topLevel == "3"
    val uncheckedPolyeleosJourney = book.key.worship == WorshipKind.MATINS && book.key.topLevel == null &&
        journey?.let { ServiceBookSelection.isPolyeleosMatins(it.title) } == true
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
    var partSwitchError by remember { mutableStateOf<String?>(null) }
    var switchingPart by remember { mutableStateOf(false) }
    var superseded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var positioning by remember { mutableStateOf(true) }
    val positioningOwner = remember { BookPositioningOwner() }
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
    // These are presentation keys only. Published block IDs and saved bookmarks stay unchanged.
    // A list measured in Meaning must never be interpreted with the indices of Original.
    val layoutPrefix = "${book.contentHash}:${mode.name}:$showAltar:"
    val itemKeys = remember(items, layoutPrefix) { items.associateBy { "${layoutPrefix}page:${it.id}" } }
    val endKey = "${layoutPrefix}end"
    val currentItem by remember(items, itemKeys, endKey, list) { derivedStateOf {
        val measured = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
        (measured?.key as? String)?.let(itemKeys::get) ?: if (measured?.key == endKey) items.last()
        else items.firstOrNull { it.chapter.id == cursor.value.chapterId } ?: items.first()
    } }
    val currentChapter = currentItem.chapter

    fun viewport(): BookViewport? {
        val index = list.firstVisibleItemIndex
        val measured = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index && it.size > 0 } ?: return null
        val visible = (measured.key as? String)?.let(itemKeys::get) ?: if (measured.key == endKey) items.last() else return null
        val kind = when { measured.key == endKey -> BookAnchorKind.END; visible.block == null -> BookAnchorKind.HEADER; else -> BookAnchorKind.BLOCK }
        val blockId = visible.block?.id ?: visible.chapter.blocks.firstOrNull { showAltar || !it.isAltarText }?.id ?: visible.chapter.blocks.first().id
        val fraction = (-measured.offset.toFloat() / measured.size).coerceIn(0f, 1f)
        return BookViewport(visible.chapter.id, blockId, fraction, kind)
    }
    // The active route owns occurrence metadata, including explicit null for standalone reading.
    fun snapshot() = cursor.snapshot(mode, wordsPerMinute, showAltar)
        .copy(lastTextMode = lastTextMode, journeyContext = journey)
    fun position(): BookReadingPosition {
        if (!positioning) viewport()?.let { cursor.observe(it, mode) }
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
        val targetKey = if (index == items.size) endKey else "${layoutPrefix}page:${items[index].id}"
        // Composition and Android measure need not complete in one frame. First wait for this
        // edition/mode's item provider, then wait for the requested fragment's actual geometry.
        snapshotFlow { list.layoutInfo.visibleItemsInfo.any {
            (it.key as? String)?.let(itemKeys::containsKey) == true || it.key == endKey
        } }.first { it }
        list.scrollToItem(index)
        suspend fun measuredTarget() = snapshotFlow {
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index && it.key == targetKey && it.size > 0 }
        }.first { it != null }!!
        val height = measuredTarget().size
        var targetOffset = 0
        if (mode != BookReadingMode.MEANING && anchor.anchorKind != BookAnchorKind.END && anchor.fraction > 0f) {
            targetOffset = (height * anchor.fraction).toInt().coerceIn(0, height - 1)
            list.scrollToItem(index, targetOffset)
        }
        val target = pendingSearch?.takeIf { it.mode == mode && it.blockId == anchor.blockId }
        if (target != null) {
            // First compose this block, then use its real line layout and the text's
            // offset below speaker/translation labels. No character-to-height estimate.
            val line = withTimeoutOrNull(1_500) {
                snapshotFlow { pendingSearch to searchLine }.first { (pending, line) -> pending != target || line?.hit == target }.second
            }
            if (line != null && pendingSearch == target) {
                targetOffset = (line.offset - with(density) { 12.dp.roundToPx() }).coerceIn(0, height - 1)
                list.scrollToItem(index, targetOffset)
            }
            if (pendingSearch == target) pendingSearch = null
        }
        snapshotFlow {
            val measured = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            measured != null && measured.key == targetKey && kotlin.math.abs(measured.offset + targetOffset) <= 1
        }.first { it }
    }
    val positioningRequest = layoutPrefix to seekRevision
    val latestPositioningRequest by rememberUpdatedState(positioningRequest)
    LaunchedEffect(book.contentHash, mode, showAltar, seekRevision) {
        val work = currentCoroutineContext()[Job]!!
        positioningOwner.job = work
        positioning = true
        playing = false
        try { scrollToCursor() } finally {
            if (positioningOwner.job === work) {
                positioningOwner.job = null
                // A superseded job cannot declare the new layout ready. A manual drag can
                // cancel this request and resume saving the actual visible reading position.
                if (latestPositioningRequest == positioningRequest) positioning = false
            }
        }
    }
    val latestPosition by rememberUpdatedState(newValue = { position() })
    val canSavePosition by rememberUpdatedState(isSaved)
    LaunchedEffect(book.contentHash, mode, showAltar, wordsPerMinute, isSaved) {
        snapshotFlow { if (positioning) null else viewport() }.collectLatest { visible ->
            if (visible == null) return@collectLatest
            cursor.observe(visible, mode)
            delay(400)
            // Small cursor writes are serialized on the owner thread. A late IO job cannot
            // overwrite the more recent synchronous lifecycle snapshot.
            if (!superseded && isSaved) saveError = !readingStore.save(snapshot())
        }
    }
    DisposableEffect(lifecycle, book.contentHash, view) {
        view.keepScreenOn = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view.keepScreenOn = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                view.keepScreenOn = false
                playing = false
                if (!positioning && !superseded && canSavePosition) readingStore.save(latestPosition())
            }
        }
        lifecycle.addObserver(observer)
        onDispose { playing = false; view.keepScreenOn = false; if (!positioning && !superseded && canSavePosition) readingStore.save(latestPosition()); lifecycle.removeObserver(observer) }
    }
    val manualScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    playing = false
                    pendingSearch = null
                    positioningOwner.job?.cancel()
                }
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
        // The current text may not exist on disk. Publish only the saved successor's
        // rebased cursor, in one commit, without an intermediate dangling bookmark.
        superseded = onAcceptUpdate?.invoke(position()) == true
        if (superseded) sheet = null else saveError = true
    }
    fun saveAndChangeBook(target: ServiceBookKey, accept: (ServiceBookKey) -> Boolean) {
        if (journey == null || target == book.key || switchingPart || superseded) return
        playing = false
        microphoneFirst = false
        pendingSearch = null
        searchLine = null
        sheet = null
        switchingPart = true
        scope.launch {
            try {
                // Preserve an in-progress semantic seek, otherwise use the stopped viewport.
                val pendingPosition = if (positioning) position() else null
                positioningOwner.job?.cancel()
                list.stopScroll()
                val current = pendingPosition ?: position()
                partSwitchError = when {
                    !isSaved -> "Текст пока не сохранен. Обновите его в настройках чтения"
                    !readingStore.save(current) -> "Не удалось сохранить место. Вы остались в этой части"
                    !accept(target) -> "Не удалось открыть выбранный текст. Попробуйте еще раз"
                    else -> { superseded = true; null }
                }
            } finally { switchingPart = false }
        }
    }
    fun choosePart(target: ServiceBookKey) {
        if (journey != null && target in journey.parts) saveAndChangeBook(target, onChoosePart)
    }
    fun chooseVersion(target: ServiceBookKey) {
        if (journey == null) { sheet = null; onChooseVersion(target) }
        else if (journey.replacePart(book.key, target) != null) saveAndChangeBook(target, onChooseJourneyVersion)
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
                                if (!compactLabels) Text(if (journey != null)
                                    "${book.key.worship.title} · ${journey.parts.indexOf(book.key) + 1} из 2" else "Порядок службы",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ExpandMore, if (compactLabels || journey != null) "Порядок службы" else null)
                        }
                        if (shortViewport) BookModePicker(mode, ::changeMode, onOpen = { playing = false }, compact = true, iconOnly = true)
                        IconButton(onClick = { playing = false; sheet = "options" }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.TextFields, "Настроить чтение")
                        }
                    }
                    if (!shortViewport) BookModePicker(mode, ::changeMode, onOpen = { playing = false })
                    if (saveError) Text("Не удалось сохранить место. Проверьте свободное место на телефоне",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("book_save_error"))
                    partSwitchError?.let { Text(it,
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                            .testTag("book_part_switch_error").semantics { liveRegion = LiveRegionMode.Polite }) }
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
                state = list, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(items, key = { "${layoutPrefix}page:${it.id}" }) { item ->
                    // Keep the top inset inside the header: LazyColumn's leading content
                    // padding would reveal a clipped tail of the previous prayer after a jump.
                    if (item.block == null) Column(Modifier.fillMaxWidth().padding(
                        top = if (item.chapter.id == items.first().chapter.id) 28.dp else 12.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (item.chapter.id == items.first().chapter.id) {
                            Text(if (polyeleosBook) "Полиелейная утреня" else book.title, style = MaterialTheme.typography.headlineMedium)
                            if (polyeleosBook || uncheckedPolyeleosJourney) Text(
                                if (polyeleosBook) "По календарю Азбуки веры"
                                else "Прежний вариант утрени - полиелейный порядок не проверен",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("book_rank_note"))
                            Text("Тексты на " + LocalDate.parse(book.key.date).format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru"))) +
                                if (isSaved) " · Сохранено на телефоне" else " · Пока не сохранено", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("book_saved"))
                            if (journey == null && book.key.worship in setOf(WorshipKind.VESPERS, WorshipKind.VIGIL)) {
                                Text("Вечерняя служба относится к следующему церковному дню",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("book_evening_date_note"))
                            }
                            journey?.let {
                                BookJourneyDescription(it, book.key)
                                BookJourneyParts(it, book.key, ::choosePart, "book_intro_parts", switchingPart)
                            }
                            loadWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            book.selectedEventTitle?.takeIf(String::isNotBlank)?.let {
                                Text("Праздник и памяти дня: $it", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("book_selected_event"))
                            }
                            OutlinedButton(onClick = { playing = false; sheet = "calendar" },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_open_calendar")) {
                                Text("О дне и службе")
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
                item(key = endKey) {
                    // A full end page lets the last short chapter align at the top too.
                    // Otherwise switching to Meaning near the end would silently select
                    // an earlier visible chapter and overwrite the reading position.
                    Column(Modifier.fillParentMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    HorizontalDivider()
                    Text("Конец последования", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("book_end"))
                    journey?.parts?.getOrNull(journey.parts.indexOf(book.key) + 1)?.let { next ->
                        Button(onClick = { choosePart(next) }, enabled = !switchingPart,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_next_part")) {
                            Text("Перейти к утрене")
                        }
                    }
                    TextButton(onClick = { sheet = "sources" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Источники и состав текста") }
                    }
                }
            }
        }
    }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GuideSheetSystemBars()
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
                state = rememberLazyListState(initialFirstVisibleItemIndex = if (journey != null) 0 else
                    book.chapters.indexOfFirst { it.id == currentChapter.id }.coerceAtLeast(0) + 1),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Порядок службы", style = MaterialTheme.typography.headlineSmall) }
                journey?.let { context -> item {
                    BookJourneyParts(context, book.key, ::choosePart, "book_journey_parts", switchingPart)
                } }
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
            "calendar" -> BookCalendarContent(book, onOpenUrl)
            "sources" -> LazyColumn(Modifier.heightIn(max = 600.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("Состав книги", style = MaterialTheme.typography.headlineSmall)
                    churchTypeInfo?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("book_church_type"))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(when {
                        polyeleosBook -> "Полиелейный вариант по календарю Азбуки веры. Основную память выбрал источник; в храме выбор песнопений может отличаться"
                        uncheckedPolyeleosJourney -> "Продолжено прежнее чтение утрени. Для этого варианта полиелейный порядок не проверялся"
                        else -> "Полное последование источника. На приходе отдельные молитвы и песнопения могут сокращаться или отличаться"
                    })
                    Spacer(Modifier.height(12.dp))
                    Text("Краткие пояснения в разделе \"Смысл\" подготовлены Феорией", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (refreshing) "Обновляем текст" else "Обновить текст и перевод")
                    }
                }
                items(book.notices) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                item {
                    OutlinedButton(onClick = { sheet = "calendar" }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("О дне и службе")
                    }
                }
                if (book.blocks.any { it.russian != null && it.translationSource == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE }) {
                    item {
                        Text("Перевод Феории: отдельные фрагменты переведены нами с церковнославянского оригинала. Это русский текст молитв, а не краткие пояснения раздела \"Смысл\"",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("book_feoria_translation_origin"))
                    }
                }
                items((listOf(BookSource(book.sourceTitle, book.sourceUrl)) + book.sources)
                    .filterNot { it == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE }.distinctBy { it.url }) { source ->
                    TextButton(onClick = { onOpenUrl(source.url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(source.title) }
                }
                if (book.key.topLevel == null && book.mainEventOptions.size > 1) {
                    item { Text("Вариант дня", style = MaterialTheme.typography.titleMedium) }
                    items(book.mainEventOptions) { option ->
                        OutlinedButton(onClick = { chooseVersion(book.key.copy(mainEvent = option.value)) }, enabled = !switchingPart,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(option.title) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BookJourneyDescription(journey: BookJourneyContext, key: ServiceBookKey) {
    val format = remember { DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("По расписанию: ${LocalDate.parse(journey.civilDate).format(format)}, ${journey.time} - начало всей службы",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("book_journey_schedule"))
        Text("Тексты на ${LocalDate.parse(key.date).format(format)}. Вечерняя служба относится к следующему церковному дню",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("book_journey_date"))
    }
}

@Composable
private fun BookJourneyParts(journey: BookJourneyContext, activeKey: ServiceBookKey,
    onChoose: (ServiceBookKey) -> Unit, tag: String, switching: Boolean) {
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${activeKey.worship.title} · ${journey.parts.indexOf(activeKey) + 1} из 2",
            style = MaterialTheme.typography.labelLarge)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val button: @Composable (ServiceBookKey, Modifier) -> Unit = { part, modifier ->
                val current = part == activeKey
                OutlinedButton(onClick = { onChoose(part) }, enabled = !switching,
                    modifier = modifier.heightIn(min = 48.dp)
                        .testTag((if (tag == "book_journey_parts") "book_part_" else "book_intro_part_") + part.worship.name.lowercase())
                        .semantics { selected = current; role = Role.Tab },
                    colors = ButtonDefaults.outlinedButtonColors(containerColor =
                        if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                    Text(part.worship.title)
                }
            }
            if (fontScale > 1.5f || maxWidth < 280.dp) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    journey.parts.forEach { button(it, Modifier.fillMaxWidth()) }
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                journey.parts.forEach { button(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun BookModePicker(mode: BookReadingMode, onSelect: (BookReadingMode) -> Unit, onOpen: () -> Unit,
    compact: Boolean = false, iconOnly: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    // Measure the actual font, including the user's scale, before allocating touch targets.
    val minimumWidths = BookReadingMode.entries.map { value ->
        val measured = measurer.measure(AnnotatedString(value.title), style = style, softWrap = false, maxLines = 1)
        with(density) { measured.size.width.toDp() + 18.dp }.coerceAtLeast(48.dp)
    }
    BoxWithConstraints(if (iconOnly) Modifier else Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val needsMenu = compact || minimumWidths.fold(24.dp) { total, width -> total + width } > maxWidth
        if (needsMenu) {
            if (iconOnly) IconButton(onClick = { onOpen(); expanded = true }, modifier = Modifier.size(48.dp).testTag("book_mode_menu")) {
                Icon(Icons.Default.Translate, "Режим чтения: ${mode.title}")
            } else OutlinedButton(onClick = { onOpen(); expanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_mode_menu")) {
                Text(mode.title, style = style, maxLines = 1, softWrap = false, modifier = Modifier.testTag("book_mode_menu_label"))
                Spacer(Modifier.width(12.dp)); Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded, { expanded = false }) {
                BookReadingMode.entries.forEach { value -> DropdownMenuItem(
                    text = { Text(value.title, modifier = Modifier.testTag("book_mode_menu_label_${value.name.lowercase()}")) },
                    modifier = Modifier.testTag("book_mode_${value.name.lowercase()}").semantics { selected = mode == value },
                    onClick = { expanded = false; onSelect(value) }) }
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookReadingMode.entries.forEachIndexed { index, value ->
                Surface(onClick = { onSelect(value) }, shape = MaterialTheme.shapes.small,
                    color = if (mode == value) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = if (mode == value) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(minimumWidths[index].value).heightIn(min = 48.dp)
                        .testTag("book_mode_${value.name.lowercase()}").semantics { selected = mode == value; role = Role.Tab }) {
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(value.title, style = style, maxLines = 1, softWrap = false,
                            modifier = Modifier.testTag("book_mode_label_${value.name.lowercase()}"))
                    }
                }
            }
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
        if (mode == BookReadingMode.RUSSIAN && block.russian != null && block.translationSource == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE && !rubric)
            Text("Перевод Феории", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("book_feoria_translation_${block.id}"))
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
internal fun BookSearchSheet(book: ServiceBook, startMicrophone: Boolean, preferredMode: BookReadingMode,
    onSelect: (BookSearchHit) -> Unit,
    speechFactory: SpeechGuideFactory = { context, changed -> SpeechGuide(context, changed) },
    hasMicrophonePermission: (() -> Boolean)? = null,
    speechQuerySelector: (suspend (ServiceBook, List<String>) -> String)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var speechState by remember { mutableStateOf(SpeechGuideState()) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var pendingPermission by remember { mutableStateOf<Long?>(null) }
    var mounted by remember { mutableStateOf(true) }
    var initialRequestMade by remember { mutableStateOf(false) }
    var resolvingSpeech by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<BookSearchResult?>(null) }
    val scope = rememberCoroutineScope()
    val resolver = remember(scope) { BookSpeechQueryResolver(scope, speechQuerySelector) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val currentBook by rememberUpdatedState(book)
    val guide = remember(context) { speechFactory(context) { state ->
        resolver.cancel()
        resolvingSpeech = false
        speechState = state
        if (state.status == SpeechStatus.RESULT && state.transcript.isNotBlank()) {
            // Show the engine's words immediately. Refining alternatives must never block input.
            query = state.transcript
            val sourceBook = currentBook
            resolvingSpeech = true
            resolver.resolve(sourceBook, state.alternatives.ifEmpty { listOf(state.transcript) }) { chosen ->
                if (mounted && speechState === state && currentBook.key == sourceBook.key && currentBook.contentHash == sourceBook.contentHash) {
                    query = chosen
                    resolvingSpeech = false
                }
            }
        }
    } }
    val request = remember(guide, lifecycle) { ListeningRequestOwner(
        isCurrent = { mounted }, isForeground = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
        start = { guide.start(skipSupportCheck = it) }, cancel = { pendingPermission = null; guide.stop() },
        denied = { permissionMessage = "Микрофон не разрешен. Можно найти место по словам" }) }
    pendingPermission?.let { token -> key(token) {
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingPermission == token) pendingPermission = null
            request.permissionResult(token, granted)
        }
        LaunchedEffect(token) {
            if (pendingPermission != token || !mounted) return@LaunchedEffect
            try { permission.launch(Manifest.permission.RECORD_AUDIO) }
            catch (_: RuntimeException) {
                request.stop()
                permissionMessage = "Не удалось запросить микрофон. Можно найти место по словам"
            }
        }
    } }
    fun listen(unchecked: Boolean = false) {
        focus.clearFocus()
        keyboard?.hide()
        permissionMessage = null
        val permitted = hasMicrophonePermission?.invoke()
            ?: (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        pendingPermission = request.request(permitted, unchecked)
    }
    fun initialListen() {
        if (startMicrophone && !initialRequestMade && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            initialRequestMade = true
            listen()
        }
    }
    LaunchedEffect(guide) { speechState = guide.initialState; initialListen() }
    DisposableEffect(guide, lifecycle, request) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> request.stop()
            Lifecycle.Event.ON_PAUSE -> request.foregroundChanged()
            Lifecycle.Event.ON_RESUME -> { guide.refreshAvailability(); request.foregroundChanged(); initialListen() }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { mounted = false; resolver.cancel(); lifecycle.removeObserver(observer); request.dispose(); guide.destroy() }
    }
    DisposableEffect(book.key, book.contentHash, resolver) { onDispose { resolver.cancel(); resolvingSpeech = false } }
    val searchAllowed = !speechState.isActive && pendingPermission == null && !resolvingSpeech && query.trim().length >= 2
    val requestedQuery = query
    LaunchedEffect(book.key, book.contentHash, requestedQuery, preferredMode, searchAllowed) {
        if (!searchAllowed) return@LaunchedEffect
        // Avoid competing full-book scans while a person is still typing.
        delay(100)
        val found = withContext(Dispatchers.Default) { BookSearch.find(book, requestedQuery, preferredMode) }
        searchResult = BookSearchResult(book.key, book.contentHash, requestedQuery, preferredMode, found)
    }
    val currentResult = searchResult?.takeIf { it.bookKey == book.key && it.bookHash == book.contentHash && it.query == query && it.mode == preferredMode }
    val searching = resolvingSpeech || (searchAllowed && currentResult == null)
    val hits = if (searchAllowed) currentResult?.hits.orEmpty() else emptyList()
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding().testTag("book_search_sheet")
        .semantics { stateDescription = if (searching) "Ищу в тексте службы" else "Поиск готов" }, contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Найти место", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(query, {
                resolver.cancel()
                resolvingSpeech = false
                query = it.take(300)
            }, label = { Text("Какие слова вы услышали?") },
                modifier = Modifier.fillMaxWidth().testTag("book_search_query"), singleLine = true,
                enabled = !speechState.isActive && pendingPermission == null)
        }
        item {
            BookSpeechPanel(speechState, pendingPermission != null, permissionMessage,
                onListen = { if (speechState.status == SpeechStatus.UNAVAILABLE) guide.refreshAvailability() else listen() },
                onFinish = guide::finishListening, onCancel = request::stop,
                onTryWithoutSupport = { listen(true) })
        }
        if (!speechState.isActive && pendingPermission == null) {
            if (searching) item {
                Text("Ищу в тексте службы", modifier = Modifier.testTag("book_search_progress"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!searching && query.trim().length >= 2 && hits.isEmpty()) item {
                Text("Совпадений в этой книге нет. Исправьте услышанные слова или попробуйте другую короткую фразу")
            }
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
}

@Composable
private fun BookSpeechPanel(state: SpeechGuideState, pendingPermission: Boolean, permissionMessage: String?,
    onListen: () -> Unit, onFinish: () -> Unit, onCancel: () -> Unit, onTryWithoutSupport: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val title = when {
                pendingPermission -> "Нужно разрешение на микрофон"
                state.status == SpeechStatus.CHECKING -> "Проверяю распознавание"
                state.status == SpeechStatus.STARTING -> "Включаю микрофон"
                state.status == SpeechStatus.LISTENING -> "Слушаю службу"
                state.status == SpeechStatus.PROCESSING -> "Распознаю фразу"
                state.status == SpeechStatus.RESULT -> "Фраза распознана"
                state.status == SpeechStatus.ERROR -> "Не получилось распознать"
                state.status == SpeechStatus.UNAVAILABLE -> "Микрофон недоступен"
                else -> "Найти по микрофону"
            }
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("book_listening_status").semantics { liveRegion = LiveRegionMode.Polite })
            if (state.isCapturing || state.status == SpeechStatus.PROCESSING) {
                if (state.status == SpeechStatus.LISTENING) {
                    Text(if (state.heardSpeech || state.transcript.isNotBlank()) "Слышу фразу. Можно нажать \"Готово\"" else "Микрофон включен. Послушаем короткую фразу",
                        style = MaterialTheme.typography.bodyMedium)
                    state.audioLevel?.let { level ->
                        LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(6.dp).testTag("book_microphone_level"))
                    }
                } else if (state.status == SpeechStatus.PROCESSING) {
                    Text("Микрофон выключен. Ждем результат от телефона", style = MaterialTheme.typography.bodyMedium)
                }
                if (state.transcript.isNotBlank()) {
                    Text("Предварительно", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.transcript, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("book_listening_partial"))
                }
            } else if (state.status == SpeechStatus.IDLE) {
                Text("Послушаем до 15 секунд. Затем вы выберете подходящий фрагмент", style = MaterialTheme.typography.bodyMedium)
            } else if (!pendingPermission) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
            }
            when {
                state.isCapturing -> {
                    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_listen")) {
                        Icon(Icons.Default.Check, null); Spacer(Modifier.width(12.dp)); Text("Готово")
                    }
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_listening_cancel")) { Text("Отменить запись") }
                }
                state.isActive || pendingPermission -> {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_listening_cancel")) { Text("Отмена") }
                }
                else -> {
                    OutlinedButton(onClick = onListen, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_listen")) {
                        Icon(Icons.Default.Mic, null); Spacer(Modifier.width(12.dp))
                        Text(when (state.status) {
                            SpeechStatus.IDLE -> "Послушать службу"
                            SpeechStatus.UNAVAILABLE -> "Проверить микрофон"
                            else -> "Послушать еще раз"
                        })
                    }
                }
            }
            permissionMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (state.canTryWithoutSupport) TextButton(onClick = onTryWithoutSupport,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Попробовать без проверки") }
        }
    }
}
