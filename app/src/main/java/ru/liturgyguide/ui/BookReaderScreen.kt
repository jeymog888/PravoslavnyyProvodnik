package ru.liturgyguide.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

private data class BookPageItem(val id: String, val chapter: BookChapter, val block: BookBlock?,
    val quietGroup: BookQuietPrayerGroup? = null)
private data class BookSearchLine(val hit: BookSearchHit, val offset: Int)
private data class BookSearchResult(val bookKey: ServiceBookKey, val bookHash: String, val query: String,
    val mode: BookReadingMode, val corpus: List<Pair<ServiceBookKey, String>>, val hits: List<BookLocatedSearchHit>)
private class BookPositioningOwner(var job: Job? = null)
private data class BookDedicationReaderSession(val owner: Any, val controls: BookDedicationControls)
private class BookDedicationReturnWork { var job: Job? = null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(book: ServiceBook, parishId: String?, readingStore: BookReadingStore,
    textSize: Int, onBack: () -> Unit, onOpenUrl: (String) -> Unit,
    onChooseVersion: (ServiceBookKey) -> Unit, onSettings: () -> Unit,
    isSaved: Boolean = true, loadWarning: String? = null,
    onRefresh: () -> Unit = {}, refreshing: Boolean = false, onAcceptUpdate: ((BookReadingPosition) -> Boolean)? = null,
    churchTypeInfo: String? = null, journeyContext: BookJourneyContext? = null,
    onChoosePart: (ServiceBookKey) -> Boolean = { false },
    onChooseJourneyVersion: (ServiceBookKey) -> Boolean = { false },
    searchCorpusLoader: (suspend () -> BookSearchCorpus)? = null,
    onChooseSearchHit: (BookLocatedSearchHit) -> Boolean = { false },
    pendingSearchTarget: BookLocatedSearchHit? = null,
    onPendingSearchHandled: (BookLocatedSearchHit) -> Boolean = { true },
    offlineControls: BookOfflineControls? = null, dedicationControls: BookDedicationControls? = null) {
    val journey = journeyContext?.validatedFor(book.key, parishId)
    val polyeleosBook = book.key.topLevel == "3" && book.rankSelection?.topLevel == "3"
    val uncheckedPolyeleosJourney = book.key.worship == WorshipKind.MATINS && book.key.topLevel == null &&
        journey?.let { ServiceBookSelection.isPolyeleosMatins(it.title) } == true
    val visibility = remember(book.key, book.contentHash) { BookVisibilityScope.of(book) }
    val initial = remember(book.contentHash, parishId) { readingStore.restore(book, parishId) }
    val initialSearch = remember(book.contentHash, parishId) { pendingSearchTarget?.validatedFor(book) }
    var targetUnresolved by remember { mutableStateOf(pendingSearchTarget != null) }
    var targetError by remember { mutableStateOf<String?>(null) }
    val latestPendingHandled by rememberUpdatedState(onPendingSearchHandled)
    fun cancelPendingTarget(): Boolean {
        if (!targetUnresolved) return true
        if (initialSearch == null || !latestPendingHandled(initialSearch)) {
            targetError = "Не удалось отменить переход. Попробуйте еще раз"
            return false
        }
        targetUnresolved = false
        targetError = null
        return true
    }
    var mode by rememberSaveable(book.contentHash, initialSearch?.identity) { mutableStateOf(initialSearch?.hit?.mode ?: initial?.mode ?: BookReadingMode.ORIGINAL) }
    var lastTextMode by rememberSaveable(book.contentHash, initialSearch?.identity) {
        mutableStateOf(initialSearch?.hit?.mode ?: initial?.lastTextMode ?: initial?.mode?.takeUnless { it == BookReadingMode.MEANING } ?: BookReadingMode.ORIGINAL)
    }
    var pendingSearch by remember { mutableStateOf(initialSearch?.hit) }
    var searchLine by remember { mutableStateOf<BookSearchLine?>(null) }
    var wordsPerMinute by rememberSaveable(book.contentHash) { mutableIntStateOf(initial?.wordsPerMinute ?: BookAutoScroll.DEFAULT_RATE) }
    var showAltar by rememberSaveable(book.contentHash, initialSearch?.identity) {
        mutableStateOf(initial?.showAltar == true || initial?.blockId?.let(visibility::isQuiet) == true ||
            initialSearch?.let { target -> book.blocks.singleOrNull { it.id == target.hit.blockId }?.let(visibility::isPrivate) } == true)
    }
    fun visible(block: BookBlock) = visibility.isVisible(block, showAltar)
    fun firstVisible(chapter: BookChapter) = visibility.firstVisible(chapter, showAltar)
    var playing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var microphoneFirst by remember { mutableStateOf(false) }
    val cursor = remember(book.contentHash, parishId) { BookReadingCursor((initial ?: BookReadingPosition(
        book.key, book.contentHash, parishId, book.blocks.first().id, book.chapters.first().id,
        anchorKind = BookAnchorKind.HEADER)).let { saved -> initialSearch?.hit?.let { hit ->
            saved.copy(chapterId = hit.chapterId, blockId = hit.blockId, fraction = 0f, followingGapFraction = 0f,
                anchorKind = BookAnchorKind.BLOCK, mode = hit.mode, lastTextMode = hit.mode)
        } ?: saved }) }
    var saveError by remember { mutableStateOf(false) }
    var partSwitchError by remember { mutableStateOf<String?>(null) }
    var switchingPart by remember { mutableStateOf(false) }
    var superseded by remember { mutableStateOf(false) }
    val dedicationOwner = remember(book.key, book.contentHash, parishId, dedicationControls?.services,
        dedicationControls?.parishId) { Any() }
    val latestDedicationOwner by rememberUpdatedState(dedicationOwner)
    val latestDedicationControls by rememberUpdatedState(dedicationControls)
    var dedicationSession by remember { mutableStateOf<BookDedicationReaderSession?>(null) }
    var dedicationActionError by remember { mutableStateOf<String?>(null) }
    var dedicationReturning by remember { mutableStateOf(false) }
    val dedicationReturnWork = remember { BookDedicationReturnWork() }
    val dedicationEntryId = remember(book.contentHash, book.patronalSelection) {
        book.blocks.firstOrNull { !visibility.isPrivate(it) && it.kind == BookBlockKind.LOCAL_VARIATION }?.id
            ?: if (book.patronalSelection == null) book.blocks.firstOrNull {
                !visibility.isPrivate(it) && BookText.normalized(it.original) == "тропарь храма"
            }?.id else null
    }
    LaunchedEffect(dedicationOwner) {
        if (dedicationSession?.owner?.let { it !== dedicationOwner } == true) {
            dedicationSession = null
            if (sheet == "dedication") sheet = null
        }
    }
    val scope = rememberCoroutineScope()
    var positioning by remember { mutableStateOf(true) }
    val positioningOwner = remember { BookPositioningOwner() }
    var seekRevision by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val itemSpacingPx = with(density) { 20.dp.roundToPx() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val items = remember(book.contentHash, showAltar, mode) {
        book.chapters.filter { chapter -> mode == BookReadingMode.MEANING || chapter.blocks.any(::visible) }.flatMap { chapter ->
            buildList {
                add(BookPageItem("chapter:${chapter.id}", chapter, null))
                if (mode != BookReadingMode.MEANING) for (block in chapter.blocks) {
                    if (visible(block)) add(BookPageItem(block.id, chapter, block))
                    visibility.quietGroup?.takeIf { it.introductionId == block.id }?.let { group ->
                        add(BookPageItem("quiet:${group.introductionId}", chapter, block, group))
                    }
                }
            }
        }
    }
    val spokenWords = remember(items, mode, book.key) {
        items.associate { item -> item.id to item.block?.takeIf { item.quietGroup == null && visible(it) }?.let { block ->
            BookBlockPresentation.spokenText(book.key, block, mode)?.let(BookAutoScroll::wordCount)
        } }
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
    val currentLocalVariation by remember(itemKeys, list) { derivedStateOf {
        val measured = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
        (measured?.key as? String)?.let(itemKeys::get)?.block?.takeIf { it.kind == BookBlockKind.LOCAL_VARIATION }
    } }

    fun viewport(): BookViewport? {
        val index = list.firstVisibleItemIndex
        val measured = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index && it.size > 0 } ?: return null
        val visible = (measured.key as? String)?.let(itemKeys::get) ?: if (measured.key == endKey) items.last() else return null
        // Compose may retire the preceding item while its following space is still
        // visible. Recover that semantic anchor from the next item's positive offset.
        if (measured.offset in 1..itemSpacingPx && index > 0) {
            val preceding = items.getOrNull(index - 1) ?: return null
            if (preceding.quietGroup != null) return BookViewport(preceding.chapter.id,
                preceding.quietGroup.introductionId, 0f, BookAnchorKind.BLOCK)
            val blockId = preceding.block?.id ?: firstVisible(preceding.chapter)?.id
                ?: preceding.chapter.blocks.first().id
            return BookViewport(preceding.chapter.id, blockId, 1f,
                if (preceding.block == null) BookAnchorKind.HEADER else BookAnchorKind.BLOCK,
                ((itemSpacingPx - measured.offset).toFloat() / itemSpacingPx).coerceIn(0f, 1f))
        }
        if (visible.quietGroup != null) return BookViewport(visible.chapter.id,
            visible.quietGroup.introductionId, 0f, BookAnchorKind.BLOCK)
        val kind = when { measured.key == endKey -> BookAnchorKind.END; visible.block == null -> BookAnchorKind.HEADER; else -> BookAnchorKind.BLOCK }
        val blockId = visible.block?.id ?: firstVisible(visible.chapter)?.id ?: visible.chapter.blocks.first().id
        val fraction = (-measured.offset.toFloat() / measured.size).coerceIn(0f, 1f)
        val gap = if (kind == BookAnchorKind.END) 0f else
            ((-measured.offset - measured.size).toFloat() / itemSpacingPx).coerceIn(0f, 1f)
        return BookViewport(visible.chapter.id, blockId, fraction, kind, gap)
    }
    // The active route owns occurrence metadata, including explicit null for standalone reading.
    fun snapshot() = cursor.snapshot(mode, wordsPerMinute, showAltar)
        .copy(lastTextMode = lastTextMode, journeyContext = journey)
    fun position(): BookReadingPosition {
        if (!positioning) viewport()?.let { cursor.observe(it, mode) }
        return snapshot()
    }
    suspend fun scrollToCursor(): Boolean {
        var anchor = cursor.value
        val hiddenAnchor = when (anchor.anchorKind) {
            BookAnchorKind.BLOCK -> items.none { it.block?.id == anchor.blockId }
            BookAnchorKind.HEADER -> items.none { it.chapter.id == anchor.chapterId }
            BookAnchorKind.END -> false
        }
        if (mode != BookReadingMode.MEANING && hiddenAnchor) {
            val oldIndex = book.chapters.indexOfFirst { it.id == anchor.chapterId }.coerceAtLeast(0)
            val chapter = items.firstOrNull { item -> book.chapters.indexOf(item.chapter) >= oldIndex }?.chapter ?: items.last().chapter
            cursor.seek(chapter.id, firstVisible(chapter)?.id ?: chapter.blocks.first().id,
                BookAnchorKind.HEADER)
            anchor = cursor.value
        }
        val index = when {
            mode == BookReadingMode.MEANING || anchor.anchorKind == BookAnchorKind.HEADER -> items.indexOfFirst { it.chapter.id == anchor.chapterId }
            anchor.anchorKind == BookAnchorKind.END -> items.size
            else -> items.indexOfFirst { it.quietGroup == null && it.block?.id == anchor.blockId }.takeIf { it >= 0 }
                ?: items.indexOfFirst { it.chapter.id == anchor.chapterId }
        }.coerceAtLeast(0)
        val targetKey = if (index == items.size) endKey else "${layoutPrefix}page:${items[index].id}"
        val nextKey = if (index + 1 == items.size) endKey else items.getOrNull(index + 1)?.let { "${layoutPrefix}page:${it.id}" }
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
            targetOffset = if (anchor.followingGapFraction > 0f)
                height + (itemSpacingPx * anchor.followingGapFraction).roundToInt().coerceIn(0, itemSpacingPx - 1)
            else (height * anchor.fraction).toInt().coerceIn(0, height - 1)
            list.scrollToItem(index, targetOffset)
        }
        val target = pendingSearch?.takeIf { it.mode == mode && it.blockId == anchor.blockId }
        if (targetUnresolved && target == null) {
            targetError = "Координаты найденной строки недоступны. Повторите переход или продолжите чтение вручную"
            return false
        }
        if (target != null) {
            // First compose this block, then use its real line layout and the text's
            // offset below speaker/translation labels. No character-to-height estimate.
            val line = withTimeoutOrNull(1_500) {
                snapshotFlow { pendingSearch to searchLine }.first { (pending, line) -> pending != target || line?.hit == target }.second
            }
            if (line != null && pendingSearch == target) {
                targetOffset = (line.offset - with(density) { 12.dp.roundToPx() }).coerceIn(0, height - 1)
                list.scrollToItem(index, targetOffset)
            } else if (targetUnresolved) {
                targetError = "Не удалось найти строку в открытом тексте. Повторите переход или продолжите чтение вручную"
                return false
            }
        }
        snapshotFlow {
            val measured = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
            measured != null && (measured.key == targetKey && kotlin.math.abs(measured.offset + targetOffset) <= 1 ||
                anchor.followingGapFraction > 0f && mode != BookReadingMode.MEANING &&
                measured.index == index + 1 && measured.key == nextKey &&
                kotlin.math.abs(measured.offset - (height + itemSpacingPx - targetOffset)) <= 1)
        }.first { it }
        if (target != null && pendingSearch == target) {
            if (targetUnresolved) {
                val incoming = initialSearch
                val measured = viewport()
                if (!isSaved || incoming == null || incoming.validatedFor(book) == null || incoming.hit != target ||
                    measured == null || measured.chapterId != target.chapterId || measured.blockId != target.blockId ||
                    measured.kind != BookAnchorKind.BLOCK) {
                    targetError = "Не удалось проверить найденную строку. Повторите переход или продолжите чтение вручную"
                    return false
                }
                // position() deliberately ignores the viewport while positioning. Commit the
                // measured line, including its fraction and following gap, before consuming
                // the serialized request so recreation can never lose an uncommitted jump.
                cursor.observe(measured, mode)
                if (!readingStore.saveReading(snapshot())) {
                    targetError = "Не удалось сохранить найденную строку. Повторите переход или продолжите чтение вручную"
                    return false
                }
                if (!latestPendingHandled(incoming)) {
                    targetError = "Не удалось завершить переход. Повторите переход или продолжите чтение вручную"
                    return false
                }
                targetUnresolved = false
                targetError = null
            }
            pendingSearch = null
        }
        return true
    }
    val positioningRequest = layoutPrefix to seekRevision
    val latestPositioningRequest by rememberUpdatedState(positioningRequest)
    LaunchedEffect(book.contentHash, mode, showAltar, seekRevision) {
        val work = currentCoroutineContext()[Job]!!
        positioningOwner.job = work
        positioning = true
        playing = false
        try {
            val jumped = if (targetUnresolved) withTimeoutOrNull(4_000) { scrollToCursor() } ?: false else scrollToCursor()
            if (!jumped && targetUnresolved && targetError == null)
                targetError = "Не удалось найти строку в открытом тексте. Повторите переход или продолжите чтение вручную"
        } finally {
            if (positioningOwner.job === work) {
                positioningOwner.job = null
                // A superseded job cannot declare the new layout ready. A manual drag can
                // cancel this request and resume saving the actual visible reading position.
                if (latestPositioningRequest == positioningRequest) positioning = false
            }
        }
    }
    val latestPosition by rememberUpdatedState(newValue = { position() })
    val canSavePosition by rememberUpdatedState(isSaved && !targetUnresolved)
    LaunchedEffect(book.contentHash, mode, showAltar, wordsPerMinute, isSaved, targetUnresolved) {
        snapshotFlow { if (positioning || targetUnresolved) null else viewport() }.collectLatest { visible ->
            if (visible == null) return@collectLatest
            cursor.observe(visible, mode)
            delay(400)
            // Small cursor writes are serialized on the owner thread. A late IO job cannot
            // overwrite the more recent synchronous lifecycle snapshot.
            if (!superseded && isSaved && !targetUnresolved) saveError = !readingStore.saveReading(snapshot())
        }
    }
    DisposableEffect(lifecycle, book.contentHash, view) {
        view.keepScreenOn = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view.keepScreenOn = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                view.keepScreenOn = false
                playing = false
                if (!positioning && !superseded && canSavePosition) readingStore.saveReading(latestPosition())
            }
        }
        lifecycle.addObserver(observer)
        onDispose { playing = false; view.keepScreenOn = false; if (!positioning && !superseded && canSavePosition) readingStore.saveReading(latestPosition()); lifecycle.removeObserver(observer) }
    }
    val manualScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    playing = false
                    if (!cancelPendingTarget()) return Offset.Zero
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
            val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex } ?: continue
            val item = (visible.key as? String)?.let(itemKeys::get)
            if (item == null && visible.key != endKey) continue
            // The source does not contain this hymn, so its duration cannot be inferred.
            // Keep the actual viewport unchanged until the reader explicitly continues.
            if (item?.block?.kind == BookBlockKind.LOCAL_VARIATION) {
                playing = false
                break
            }
            val speed = BookAutoScroll.pixelsPerSecond(visible.size.toFloat(), with(density) { 20.dp.toPx() },
                item?.let { spokenWords[it.id] }, wordsPerMinute)
            val remaining = visible.offset + visible.size + with(density) { 20.dp.toPx() } - list.layoutInfo.viewportStartOffset
            val consumed = list.scrollBy(BookAutoScroll.frameDistance(speed, elapsed, remaining))
            if (consumed == 0f && !list.canScrollForward) playing = false
        }
    }
    fun changeMode(value: BookReadingMode) {
        if (value == mode) return
        if (!cancelPendingTarget()) return
        position()
        playing = false
        positioning = true
        pendingSearch = null
        if (value != BookReadingMode.MEANING) lastTextMode = value
        mode = value
    }
    fun changePrivateVisibility(nextAltar: Boolean) {
        if (switchingPart || superseded || !cancelPendingTarget()) return
        playing = false
        scope.launch {
            val pendingPosition = if (positioning) position() else null
            positioningOwner.job?.cancel()
            list.stopScroll()
            currentCoroutineContext().ensureActive()
            if (switchingPart || superseded) return@launch
            val current = pendingPosition ?: position()
            val currentBlock = book.blocks.singleOrNull { it.id == current.blockId }
            var target = current.copy(showAltar = nextAltar)
            if (current.anchorKind != BookAnchorKind.END && currentBlock != null &&
                !visibility.isVisible(currentBlock, nextAltar)) {
                val group = visibility.quietGroup?.takeIf { current.blockId in it.blockIds }
                if (group != null) target = target.copy(chapterId = group.chapterId, blockId = group.introductionId,
                    fraction = 0f, followingGapFraction = 0f, anchorKind = BookAnchorKind.BLOCK)
                else {
                    val oldIndex = book.chapters.indexOfFirst { it.id == current.chapterId }.coerceAtLeast(0)
                    val chapter = book.chapters.drop(oldIndex).firstOrNull { visibility.firstVisible(it, nextAltar) != null }
                        ?: book.chapters.lastOrNull { visibility.firstVisible(it, nextAltar) != null }
                        ?: return@launch
                    target = target.copy(chapterId = chapter.id,
                        blockId = requireNotNull(visibility.firstVisible(chapter, nextAltar)).id,
                        fraction = 0f, followingGapFraction = 0f, anchorKind = BookAnchorKind.HEADER)
                }
            }
            if (isSaved && !readingStore.saveReading(target)) { saveError = true; return@launch }
            if (target.blockId != current.blockId || target.anchorKind != current.anchorKind)
                cursor.seek(target.chapterId, target.blockId, target.anchorKind, target.fraction)
            pendingSearch = null
            searchLine = null
            positioning = true
            showAltar = nextAltar
            seekRevision++
        }
    }
    fun openSearch(useMicrophone: Boolean) { playing = false; microphoneFirst = useMicrophone; sheet = "search" }
    fun openCalendar() {
        playing = false
        sheet = "calendar"
        offlineControls?.onCheck?.invoke()
    }
    fun openDedication() {
        val controls = latestDedicationControls ?: return
        if (controls.parishId != parishId || controls.busy || switchingPart || superseded) return
        playing = false
        dedicationActionError = null
        // Retain all three typed states. An unreadable profile is never absence.
        dedicationSession = BookDedicationReaderSession(dedicationOwner, controls)
        sheet = "dedication"
    }
    suspend fun beginDedication(proof: PatronalEventProof, expectedProfile: PatronalProfileLookup): BookDedicationStart {
        val selection = dedicationSession ?: return BookDedicationStart.STALE_ROUTE
        if (selection.owner !== latestDedicationOwner || sheet != "dedication" || switchingPart || superseded ||
            selection.controls.expectedProfile != expectedProfile) return BookDedicationStart.STALE_ROUTE
        if (!cancelPendingTarget()) return BookDedicationStart.STORAGE_FAILED
        playing = false
        microphoneFirst = false
        val pendingPosition = if (positioning) position() else null
        positioningOwner.job?.cancel()
        list.stopScroll()
        currentCoroutineContext().ensureActive()
        val controls = latestDedicationControls ?: return BookDedicationStart.STALE_ROUTE
        if (selection.owner !== latestDedicationOwner || dedicationSession !== selection ||
            sheet != "dedication" || controls.parishId != parishId || superseded) return BookDedicationStart.STALE_ROUTE
        val current = pendingPosition ?: position()
        // The live parent verifies ownership before it commits this cursor. No
        // child save may race a changed route or an independently accepted book.
        val started = controls.beginSourceChange(proof, expectedProfile, current)
        if (started == BookDedicationStart.OPENED) {
            superseded = true
            sheet = null
            dedicationSession = null
            pendingSearch = null
            searchLine = null
        }
        return started
    }
    fun returnDedicationSource() {
        val controls = latestDedicationControls ?: return
        if (controls.onReturn == null || controls.busy || dedicationReturning || switchingPart || superseded) return
        if (!cancelPendingTarget()) return
        val owner = dedicationOwner
        playing = false
        dedicationReturning = true
        dedicationActionError = null
        dedicationReturnWork.job = scope.launch {
            try {
                val pendingPosition = if (positioning) position() else null
                positioningOwner.job?.cancel()
                list.stopScroll()
                currentCoroutineContext().ensureActive()
                if (owner !== latestDedicationOwner || sheet != "calendar" || superseded) return@launch
                if (latestDedicationControls?.onReturn?.invoke(pendingPosition ?: position()) == true) {
                    superseded = true
                    sheet = null
                } else dedicationActionError = "Не удалось вернуться к прежнему тексту. Попробуйте еще раз"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { dedicationActionError = "Не удалось вернуться к прежнему тексту. Попробуйте еще раз" }
            finally { dedicationReturning = false }
        }
    }
    fun reopenActive() {
        if (switchingPart || superseded || !cancelPendingTarget()) return
        playing = false
        microphoneFirst = false
        sheet = null
        // Retire this reader before its parent can mount another accepted edition.
        // Neither disposal nor the lifecycle observer may publish the old cursor.
        superseded = true
        if (offlineControls?.onReopenActive?.invoke() == true) {
            positioningOwner.job?.cancel()
            pendingSearch = null
            searchLine = null
        } else {
            superseded = false
            partSwitchError = "Не удалось открыть текст заново. Попробуйте еще раз"
        }
    }
    fun continueAfterLocalVariation() {
        if (positioning || switchingPart || superseded || !cancelPendingTarget()) return
        // Read the measured item again after resolving any pending search transaction.
        val measured = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex } ?: return
        val current = (measured.key as? String)?.let(itemKeys::get)
            ?.takeIf { it.block?.kind == BookBlockKind.LOCAL_VARIATION } ?: return
        val index = measured.index
        if (items.getOrNull(index)?.id != current.id) return
        val next = items.getOrNull(index + 1)
        playing = false
        positioningOwner.job?.cancel()
        positioning = true
        pendingSearch = null
        searchLine = null
        if (next == null) cursor.seek(current.chapter.id, requireNotNull(current.block).id, BookAnchorKind.END)
        else {
            val blockId = next.block?.id ?: firstVisible(next.chapter)?.id
                ?: next.chapter.blocks.first().id
            cursor.seek(next.chapter.id, blockId, if (next.block == null) BookAnchorKind.HEADER else BookAnchorKind.BLOCK)
        }
        seekRevision++
    }
    fun acceptUpdate() {
        if (!cancelPendingTarget()) return
        playing = false
        // The current text may not exist on disk. Publish only the saved successor's
        // rebased cursor, in one commit, without an intermediate dangling bookmark.
        superseded = onAcceptUpdate?.invoke(position()) == true
        if (superseded) sheet = null else saveError = true
    }
    fun saveAndChangeBook(target: ServiceBookKey, accept: (ServiceBookKey) -> Boolean) {
        if (journey == null || target == book.key || switchingPart || superseded) return
        if (!cancelPendingTarget()) return
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
                    !readingStore.saveReading(current) -> "Не удалось сохранить место. Вы остались в этой части"
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
    fun chooseLocatedHit(located: BookLocatedSearchHit) {
        if (switchingPart || superseded) return
        if (located.bookKey == book.key) {
            val hit = located.validatedFor(book)?.hit ?: return
            if (!cancelPendingTarget()) return
            playing = false
            positioning = true
            cursor.seek(hit.chapterId, hit.blockId)
            searchLine = null
            pendingSearch = hit
            mode = hit.mode
            lastTextMode = hit.mode
            if (book.blocks.singleOrNull { it.id == hit.blockId }?.let(visibility::isPrivate) == true) showAltar = true
            sheet = null
            seekRevision++
            return
        }
        if (journey == null || located.bookKey !in journey.parts || located.validated() == null) return
        if (!cancelPendingTarget()) return
        playing = false
        switchingPart = true
        partSwitchError = null
        scope.launch {
            try {
                val pendingPosition = if (positioning) position() else null
                positioningOwner.job?.cancel()
                list.stopScroll()
                val current = pendingPosition ?: position()
                partSwitchError = when {
                    !isSaved -> "Текст пока не сохранен. Обновите его в настройках чтения"
                    !readingStore.saveReading(current) -> "Не удалось сохранить место. Вы остались в этой части"
                    !onChooseSearchHit(located) -> "Не удалось открыть найденный текст. Попробуйте еще раз"
                    else -> { superseded = true; sheet = null; null }
                }
            } finally { switchingPart = false }
        }
    }
    val acceptCalendarUpdate: () -> Unit = ::acceptUpdate
    val chooseDedication: () -> Unit = ::openDedication

    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Use this reader's actual space, including split-screen and in-app constraints.
    val shortViewport = maxHeight < 420.dp
    val compactIntro = density.fontScale >= 1.5f || shortViewport
    val toolbarMode = compactIntro
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
                                if (!compactLabels) Text(if (journey?.isEveningTexts == true) "${book.key.worship.title} · базовый текст" else if (journey != null)
                                    "${book.key.worship.title} · ${journey.parts.indexOf(book.key) + 1} из 2" else "Порядок службы",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ExpandMore, if (compactLabels || journey != null) "Порядок службы" else null)
                        }
                        if (toolbarMode) BookModePicker(mode, ::changeMode, onOpen = { playing = false }, compact = true, iconOnly = true)
                        IconButton(onClick = { playing = false; sheet = "options" }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.TextFields, "Настроить чтение")
                        }
                    }
                    if (!toolbarMode) BookModePicker(mode, ::changeMode, onOpen = { playing = false })
                    if (journey?.isEveningTexts == true) journey.parts.singleOrNull { it != book.key }?.let { other ->
                        TextButton(onClick = { choosePart(other) }, enabled = !switchingPart,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_open_other_part")) {
                            Text(if (other.worship == WorshipKind.MATINS) "Открыть утреню" else "Открыть вечерню")
                        }
                    }
                    if (saveError) Text("Не удалось сохранить место",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("book_save_error"))
                    targetError?.let { message ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("book_search_jump_error")) {
                            Text(message, color = MaterialTheme.colorScheme.error)
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val retryButton: @Composable (Modifier) -> Unit = { modifier ->
                                    TextButton(onClick = { targetError = null; searchLine = null; seekRevision++ },
                                        modifier = modifier.heightIn(min = 48.dp).testTag("book_search_jump_retry")) { Text("Повторить переход") }
                                }
                                val manualButton: @Composable (Modifier) -> Unit = { modifier ->
                                    TextButton(onClick = {
                                        if (cancelPendingTarget()) { pendingSearch = null; positioningOwner.job?.cancel() }
                                    }, modifier = modifier.heightIn(min = 48.dp).testTag("book_search_jump_cancel")) { Text("Читать вручную") }
                                }
                                if (maxWidth < 340.dp || density.fontScale > 1.3f) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        retryButton(Modifier.fillMaxWidth())
                                        manualButton(Modifier.fillMaxWidth())
                                    }
                                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    retryButton(Modifier.weight(1f))
                                    manualButton(Modifier.weight(1f))
                                }
                            }
                        }
                    }
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
                        BookReaderAction(if (mode == BookReadingMode.MEANING) "К тексту" else if (currentLocalVariation != null) "Дальше"
                            else if (playing) "Пауза" else "Прокрутка",
                            if (mode == BookReadingMode.MEANING) Icons.Default.Article else if (currentLocalVariation != null)
                                Icons.AutoMirrored.Filled.ArrowForward else if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (mode == BookReadingMode.MEANING) "Вернуться к тексту" else if (currentLocalVariation != null)
                                "Продолжить после местного песнопения" else if (playing) "Остановить прокрутку" else "Прокручивать текст",
                            { if (mode == BookReadingMode.MEANING) changeMode(lastTextMode)
                            else if (currentLocalVariation != null) continueAfterLocalVariation() else {
                                val cancellingTarget = targetUnresolved
                                if (cancelPendingTarget()) {
                                    if (cancellingTarget) { pendingSearch = null; positioningOwner.job?.cancel() }
                                    playing = !playing
                                }
                            } },
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
                        val cancellingTarget = targetUnresolved
                        if (cancelPendingTarget()) {
                            pendingSearch = null
                            if (cancellingTarget) positioningOwner.job?.cancel()
                        }
                    }
                }.testTag("book_content"),
                state = list, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(items, key = { "${layoutPrefix}page:${it.id}" }) { item ->
                    // Keep the top inset inside the header: LazyColumn's leading content
                    // padding would reveal a clipped tail of the previous prayer after a jump.
                    if (item.quietGroup != null) {
                        val expanded = showAltar
                        OutlinedCard(Modifier.fillMaxWidth().testTag("book_quiet_group")) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(item.quietGroup.title, style = MaterialTheme.typography.titleMedium)
                                Text(item.quietGroup.explanation, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { changePrivateVisibility(!expanded) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_quiet_toggle")) {
                                    Text(if (expanded) "Свернуть тихие и алтарные тексты" else "Показать тихие и алтарные тексты")
                                }
                            }
                        }
                    } else if (item.block == null) Column(Modifier.fillMaxWidth().padding(
                        top = if (item.chapter.id == items.first().chapter.id && !compactIntro) 28.dp else 12.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (item.chapter.id == items.first().chapter.id) {
                            if (!compactIntro) Text(if (polyeleosBook) "Полиелейная утреня" else book.title,
                                style = MaterialTheme.typography.headlineMedium)
                            if (polyeleosBook || uncheckedPolyeleosJourney) Text(
                                if (polyeleosBook) "По календарю Азбуки веры"
                                else "Прежний вариант утрени - полиелейный порядок не проверен",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("book_rank_note"))
                            val date = LocalDate.parse(book.key.date).format(DateTimeFormatter.ofPattern(
                                if (compactIntro) "dd.MM.uuuu" else "d MMMM uuuu", Locale.forLanguageTag("ru")))
                            Text(if (compactIntro) "${if (polyeleosBook) "Полиелейная утреня" else book.key.worship.title} · $date"
                                else "Тексты на $date",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("book_saved"))
                            loadWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            BookDetailsButton(offlineControls, isSaved, ::openCalendar, Modifier.testTag("book_open_calendar"))
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
                    } else BookTextBlock(book.key, item.block, mode, textSize,
                        searchHit = pendingSearch?.takeIf { it.blockId == item.block.id && it.mode == mode },
                        searchRevision = seekRevision,
                        onSearchLine = { line -> if (pendingSearch == line.hit) searchLine = line },
                        onChooseDedication = chooseDedication.takeIf { dedicationControls != null && item.block.id == dedicationEntryId },
                        dedicationBusy = dedicationControls?.busy == true)
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
                            Text(if (journey.isEveningTexts) "Открыть утреню" else "Перейти к утрене")
                        }
                    }
                    TextButton(onClick = { sheet = "sources" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Источники и состав текста") }
                    }
                }
            }
        }
    }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = {
        dedicationReturnWork.job?.cancel()
        sheet = null
        dedicationSession = null
    }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GuideSheetSystemBars()
        when (sheet) {
            "search" -> BookSearchSheet(book, microphoneFirst, lastTextMode,
                onSelect = { chooseLocatedHit(BookLocatedSearchHit(book.key, book.contentHash, it)) },
                otherPart = journey?.parts?.singleOrNull { it != book.key }?.worship,
                onOpenOtherPart = { journey?.parts?.singleOrNull { it != book.key }?.let(::choosePart) },
                corpusLoader = searchCorpusLoader, onSelectLocated = ::chooseLocatedHit,
                selectionError = partSwitchError, selecting = switchingPart)
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
                        if (!cancelPendingTarget()) return@OutlinedButton
                        pendingSearch = null
                        cursor.seek(chapter.id, firstVisible(chapter)?.id ?: chapter.blocks.first().id, BookAnchorKind.HEADER)
                        if (mode != BookReadingMode.MEANING && chapter.blocks.none(::visible)) showAltar = true
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
                BookDetailsButton(offlineControls, isSaved, ::openCalendar, Modifier.testTag("book_options_offline"))
                Text("Темп прокрутки: $wordsPerMinute слов/мин", style = MaterialTheme.typography.titleMedium)
                Slider(value = wordsPerMinute.toFloat(), onValueChange = { wordsPerMinute = it.toInt() },
                    valueRange = BookAutoScroll.MIN_RATE.toFloat()..BookAutoScroll.MAX_RATE.toFloat())
                Text("Прокрутка помогает читать в выбранном темпе. Чтобы найти реальное место службы, используйте слова или микрофон",
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Тихие молитвы и алтарные тексты", Modifier.weight(1f))
                    Switch(showAltar, { changePrivateVisibility(it) })
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
            "calendar" -> BookCalendarContent(book, onOpenUrl, journey, offlineControls,
                onChoosePart = ::choosePart, onReopenActive = ::reopenActive,
                onRefresh = onRefresh, refreshing = refreshing,
                onAcceptUpdate = acceptCalendarUpdate.takeIf { onAcceptUpdate != null },
                dedicationControls = dedicationControls, onChooseDedication = chooseDedication,
                onReturnDedication = ::returnDedicationSource, dedicationReturning = dedicationReturning,
                dedicationActionError = dedicationActionError)
            "dedication" -> dedicationSession?.let { selection ->
                val controls = selection.controls
                BookDedicationChooser(controls.services, controls.parishId, controls.parishTitle,
                    controls.calendarYear, controls.expectedProfile, onOpen = ::beginDedication,
                    onDismiss = { sheet = null; dedicationSession = null }, onOpenUrl = onOpenUrl)
            }
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
                    OutlinedButton(onClick = ::openCalendar, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
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
        Text(if (journey.isEveningTexts) "В расписании: ${LocalDate.parse(journey.civilDate).format(format)}, ${journey.time}"
            else "По расписанию: ${LocalDate.parse(journey.civilDate).format(format)}, ${journey.time} - начало всей службы",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("book_journey_schedule"))
        Text("Тексты на ${LocalDate.parse(key.date).format(format)}. Вечерняя служба относится к следующему церковному дню",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("book_journey_date"))
        if (journey.isEveningTexts) Text("Это базовые тексты. Расписание не уточняет порядок службы; песнопения и дополнительные части в храме могут отличаться",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("book_evening_texts_note"))
    }
}

@Composable
private fun BookJourneyParts(journey: BookJourneyContext, activeKey: ServiceBookKey,
    onChoose: (ServiceBookKey) -> Unit, tag: String, switching: Boolean) {
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (journey.isEveningTexts) "Доступные тексты · выбрана ${if (activeKey.worship == WorshipKind.MATINS) "утреня" else "вечерня"}"
            else "${activeKey.worship.title} · ${journey.parts.indexOf(activeKey) + 1} из 2",
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
private fun BookTextBlock(key: ServiceBookKey, block: BookBlock, mode: BookReadingMode, textSize: Int,
    searchHit: BookSearchHit? = null, searchRevision: Int = 0, onSearchLine: (BookSearchLine) -> Unit = {},
    onChooseDedication: (() -> Unit)? = null, dedicationBusy: Boolean = false) {
    var textLayout by remember(block.id, mode) { mutableStateOf<TextLayoutResult?>(null) }
    var textTop by remember(block.id, mode) { mutableStateOf<Float?>(null) }
    LaunchedEffect(searchHit, textLayout, textTop, searchRevision) {
        val hit = searchHit
        val layout = textLayout
        val top = textTop
        if (hit != null && layout != null && top != null && hit.matchStart in layout.layoutInput.text.text.indices) {
            val line = layout.getLineForOffset(hit.matchStart)
            onSearchLine(BookSearchLine(hit, (top + layout.getLineTop(line)).roundToInt()))
        }
    }
    val rubric = block.kind == BookBlockKind.RUBRIC || block.kind == BookBlockKind.LOCAL_VARIATION
    val localVariation = block.kind == BookBlockKind.LOCAL_VARIATION
    val effectiveRussian = BookBlockPresentation.effectiveRussian(key, block)
    val legacyPrayer = BookBlockPresentation.isLegacyPrayer(key, block)
    val container = Modifier.fillMaxWidth().testTag("book_block_${block.id}").let {
        if (localVariation) it.background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large).padding(16.dp) else it
    }
    Column(container, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (localVariation) Text("Местное песнопение", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        if (block.speaker != null) Text(block.speaker, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        if (mode == BookReadingMode.RUSSIAN && effectiveRussian == null && (!rubric || legacyPrayer))
            Text("Этот фрагмент пока в оригинале", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("book_missing_russian_${block.id}"))
        if (mode == BookReadingMode.RUSSIAN && effectiveRussian != null && block.translationSource == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE && !rubric)
            Text("Перевод Феории", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("book_feoria_translation_${block.id}"))
        val text = if (mode == BookReadingMode.RUSSIAN) effectiveRussian ?: block.original else block.original
        Text(text, style = if (rubric) MaterialTheme.typography.bodyMedium else
            MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = (21 + textSize * 2).sp, lineHeight = (32 + textSize * 3).sp),
            color = if (rubric) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("book_text_${block.id}").onGloballyPositioned { textTop = it.positionInParent().y },
            onTextLayout = { textLayout = it })
        if (localVariation) Text("Прокрутка останавливается здесь. После пения нажмите \"Дальше\"",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("book_local_gap_${block.id}"))
        if (onChooseDedication != null) OutlinedButton(onClick = onChooseDedication, enabled = !dedicationBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_entry")) {
            Text(if (localVariation) "Подобрать текст" else "Выбрать песнопения")
        }
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
    speechQuerySelector: (suspend (ServiceBook, List<String>) -> String)? = null,
    otherPart: WorshipKind? = null, onOpenOtherPart: () -> Unit = {},
    corpusLoader: (suspend () -> BookSearchCorpus)? = null,
    onSelectLocated: (BookLocatedSearchHit) -> Unit = { onSelect(it.hit) },
    selectionError: String? = null, selecting: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    var speechState by remember { mutableStateOf(SpeechGuideState()) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var pendingPermission by remember { mutableStateOf<Long?>(null) }
    var mounted by remember { mutableStateOf(true) }
    var initialRequestMade by remember { mutableStateOf(false) }
    var resolvingSpeech by remember { mutableStateOf(false) }
    var searchResult by remember { mutableStateOf<BookSearchResult?>(null) }
    var corpus by remember(book.key, book.contentHash) { mutableStateOf(BookSearchCorpus(listOf(book), otherPart)) }
    val resultScopes = remember(corpus.books.map { it.key to it.contentHash }) {
        corpus.books.associate { it.key to BookVisibilityScope.of(it) }
    }
    var corpusLoading by remember { mutableStateOf(corpusLoader != null) }
    var manualRevision by remember { mutableLongStateOf(0L) }
    var voiceRevision by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val resolver = remember(scope) { BookSpeechQueryResolver(scope, speechQuerySelector) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val currentCorpus by rememberUpdatedState(corpus)
    val currentLoader by rememberUpdatedState(corpusLoader)
    LaunchedEffect(book.key, book.contentHash) {
        val loader = currentLoader ?: return@LaunchedEffect
        corpusLoading = true
        try {
            val loaded = loader()
            currentCoroutineContext().ensureActive()
            // An opening owns its current edition even if an external loader returns stale data.
            corpus = if (loaded.books.firstOrNull() == book && loaded.books.map { it.key }.distinct().size == loaded.books.size) loaded
                else BookSearchCorpus(listOf(book), otherPart, "Не удалось проверить другую часть. Поиск выполнен только в открытой книге")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { corpus = BookSearchCorpus(listOf(book), otherPart, "Не удалось проверить другую часть. Поиск выполнен только в открытой книге") }
        finally { corpusLoading = false }
    }
    fun resolveSpeech(state: SpeechGuideState) {
        val sources = currentCorpus.books
        val revision = manualRevision
        voiceRevision = revision
        resolvingSpeech = true
        resolver.resolve(sources, state.alternatives.ifEmpty { listOf(state.transcript) }) { chosen ->
            if (mounted && speechState === state && manualRevision == revision && voiceRevision == revision &&
                currentCorpus.books.map { it.key to it.contentHash } == sources.map { it.key to it.contentHash }) {
                query = chosen
                resolvingSpeech = false
            }
        }
    }
    val guide = remember(context) { speechFactory(context) { state ->
        resolver.cancel()
        resolvingSpeech = false
        speechState = state
        if (state.status == SpeechStatus.RESULT && state.transcript.isNotBlank()) {
            // Show the engine's words immediately. Refining alternatives must never block input.
            query = state.transcript
            resolveSpeech(state)
        }
    } }
    LaunchedEffect(corpus.books) {
        val state = speechState
        // A late neighbouring book may refine only the still-owned voice request.
        if (state.status == SpeechStatus.RESULT && voiceRevision == manualRevision && state.transcript.isNotBlank())
            resolveSpeech(state)
    }
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
    val corpusIdentity = corpus.books.map { it.key to it.contentHash }
    LaunchedEffect(book.key, book.contentHash, corpusIdentity, requestedQuery, preferredMode, searchAllowed) {
        if (!searchAllowed) return@LaunchedEffect
        // Avoid competing full-book scans while a person is still typing.
        delay(100)
        val found = withContext(Dispatchers.Default) {
            val searchContext = currentCoroutineContext()
            BookCorpusSearch.find(corpus.books, requestedQuery, preferredMode) { searchContext.ensureActive() }
        }
        currentCoroutineContext().ensureActive()
        searchResult = BookSearchResult(book.key, book.contentHash, requestedQuery, preferredMode, corpusIdentity, found)
    }
    val currentResult = searchResult?.takeIf { it.bookKey == book.key && it.bookHash == book.contentHash && it.query == query && it.mode == preferredMode }
    val searching = resolvingSpeech || (searchAllowed && currentResult?.corpus != corpusIdentity)
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
                manualRevision++
                voiceRevision = null
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
        if (corpusLoading) item {
            Text("Проверяем текст другой части. Совпадения в открытой книге уже доступны",
                modifier = Modifier.testTag("book_search_corpus_loading"), style = MaterialTheme.typography.bodyMedium)
        }
        corpus.error?.let { message -> item {
            Text(message, modifier = Modifier.testTag("book_search_corpus_error"), color = MaterialTheme.colorScheme.error)
        } }
        selectionError?.let { message -> item {
            Text(message, modifier = Modifier.testTag("book_search_selection_error"), color = MaterialTheme.colorScheme.error)
        } }
        if (!speechState.isActive && pendingPermission == null) {
            if (searching) item {
                Text("Ищу в тексте службы", modifier = Modifier.testTag("book_search_progress"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!searching && query.trim().length >= 2 && hits.isEmpty()) item {
                Text(if (corpusLoading || corpus.error != null || corpus.books.size == 1)
                    "Совпадений в открытой книге нет. Исправьте услышанные слова или попробуйте другую короткую фразу"
                    else "Совпадений в обеих книгах нет. Исправьте услышанные слова или попробуйте другую короткую фразу")
                if (otherPart != null && corpusLoader == null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenOtherPart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("book_search_other_part")) {
                        Text(if (otherPart == WorshipKind.MATINS) "Открыть утреню" else "Открыть вечерню")
                    }
                }
            }
            if (query.isBlank()) item { Text("Введите несколько слов подряд из того, что сейчас слышите", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!searching && hits.size > 1) item {
                Text("Найдено мест: ${hits.size}. Сравните соседние фразы", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(hits, key = { it.identity }) { located ->
                val hit = located.hit
                val tag = if (located.bookKey == book.key) "book_hit_${hit.blockId}" else "book_cross_hit_${located.identity}"
                OutlinedCard(onClick = { if (!selecting) onSelectLocated(located) }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(located.bookKey.worship.title, style = MaterialTheme.typography.labelLarge)
                        Text(hit.chapterTitle, style = MaterialTheme.typography.titleMedium)
                        corpus.books.singleOrNull { it.key == located.bookKey }?.blocks?.singleOrNull { it.id == hit.blockId }?.let { block ->
                            val scope = resultScopes[located.bookKey]
                            if (scope?.isPrivate(block) == true) Text(
                                if (scope.isQuiet(block.id)) "Тихая молитва священника" else "Алтарный текст",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("book_hit_private_${hit.blockId}"))
                        }
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
