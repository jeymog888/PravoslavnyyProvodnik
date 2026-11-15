package ru.liturgyguide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import ru.liturgyguide.service.ChurchDirectory
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.SavedChurchCodec
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.ServiceBookSelection
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.InternetScheduleStatus
import ru.liturgyguide.service.schedule.ScheduleUrl
import ru.liturgyguide.service.schedule.ScheduleEntityIdentity
import ru.liturgyguide.service.schedule.ScheduleSourceChangeBlock
import ru.liturgyguide.service.schedule.permitsOtherSourceSearch
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChurchScreen(churches: List<Parish>, selectedId: String?, currentDate: LocalDate,
    onSelect: (String, onSelected: () -> Unit) -> ChurchSelectionResult,
    onOpenUrl: (String) -> Unit, onBack: () -> Unit, showBack: Boolean = true, bottomBar: @Composable () -> Unit = {},
    favorites: Set<String> = emptySet(), onToggleFavorite: (String) -> Unit = {},
    onAddChurch: ((SavedChurchRecord) -> Boolean)? = null, onDiscover: (() -> Unit)? = null,
    pickerRequest: Int = 0, userStarts: List<UserServiceStart> = emptyList(),
    onSaveStart: ((UserServiceStart) -> Boolean)? = null, onRemoveStart: ((LocalDate) -> Boolean)? = null,
    personalTimeRequest: Int = 0, onGoToService: (() -> Unit)? = null,
    onReadLiturgy: ((LocalDate) -> Unit)? = null,
    readingEntryLabel: (LocalDate) -> String = { "Читать литургию" },
    internetSchedule: InternetScheduleState? = null, onRefreshSchedule: (() -> Unit)? = null,
    onSetScheduleSource: ((String) -> Unit)? = null,
    onConfirmScheduleCandidate: ((String) -> Unit)? = null,
    onDismissScheduleCandidate: ((String) -> Unit)? = null,
    onSelectionCancelled: () -> Unit = {}, searchQuery: String, onSearchQueryChange: (String) -> Unit,
    onOpenService: ((ParishService) -> Unit)? = null, now: Instant? = null,
    onReadServiceText: ((LocalDate, WorshipKind) -> Unit)? = null,
    sourceReviewRequest: Int = 0, sourceReviewIdentity: String? = null, sourceReviewParish: Parish? = null,
    sourceSearchActive: Boolean? = null, sourceSearchOrder: List<String>? = null,
    onReviewSourceSearch: (() -> Unit)? = null,
    onSourceSearchDrawn: ((androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    onSourceSearchInteraction: () -> Boolean = { true },
    onFindOtherScheduleSources: (() -> Unit)? = null, sourceSearchViewKey: Any? = null) {
    val church = churches.firstOrNull { it.id == selectedId }
    var picker by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val query = searchQuery
    var locate by remember { mutableStateOf(false) }
    var dayOffset by rememberSaveable(church?.id) { mutableIntStateOf(0) }
    var editingDate by rememberSaveable(church?.id) { mutableStateOf<String?>(null) }
    val formDate = editingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var handledPickerRequest by rememberSaveable { mutableIntStateOf(0) }
    var handledTimeRequest by rememberSaveable { mutableIntStateOf(0) }
    var selectionFailed by remember { mutableStateOf(false) }
    var details by rememberSaveable(church?.id) { mutableStateOf(false) }
    var editingSource by rememberSaveable(church?.id) { mutableStateOf(false) }
    var chooserDateIso by rememberSaveable(church?.id) { mutableStateOf<String?>(null) }
    val currentChurchId by rememberUpdatedState(church?.id)
    val openSourceEditor: () -> Unit = { if (onSourceSearchInteraction()) editingSource = true }
    // The draft belongs to this screen, even while the sheet uses a separate dialog registry.
    // There is at most one draft, and every explicit close/new attempt removes it.
    val churchDraft = rememberSaveableStateHolder()
    fun selectChurch(id: String): ChurchSelectionResult {
        val result = onSelect(id) {
            churchDraft.removeState("new_church")
            selectionFailed = false; adding = false; picker = false; locate = false; dayOffset = 0; onSearchQueryChange("")
        }
        selectionFailed = result == ChurchSelectionResult.FAILED || result == ChurchSelectionResult.STALE
        return result
    }
    val currentCancel by rememberUpdatedState(onSelectionCancelled)
    DisposableEffect(Unit) {
        onDispose { currentCancel() }
    }
    LaunchedEffect(pickerRequest) {
        if (pickerRequest != handledPickerRequest) {
            handledPickerRequest = pickerRequest
            if (onDiscover != null) onDiscover() else picker = true
        }
    }
    LaunchedEffect(personalTimeRequest) {
        if (personalTimeRequest != handledTimeRequest) {
            handledTimeRequest = personalTimeRequest
            if (church != null && onSaveStart != null) {
                churchDraft.removeState("new_church")
                picker = false; adding = false; locate = false; dayOffset = 0
                editingDate = currentDate.toString()
            }
        }
    }
    val matches = remember(churches, query, favorites) { ChurchDirectory.search(churches, query, favorites) }
    if (picker) ModalBottomSheet(onDismissRequest = {
        onSelectionCancelled()
        churchDraft.removeState("new_church"); picker = false; adding = false; locate = false
    },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GuideSheetSystemBars()
        if (adding && onAddChurch != null) churchDraft.SaveableStateProvider("new_church") {
            SavedChurchForm(
                onCancel = { onSelectionCancelled(); churchDraft.removeState("new_church"); adding = false },
                onSave = { record -> if (onAddChurch(record)) selectChurch(record.id) else ChurchSelectionResult.FAILED })
        }
        else LazyColumn(Modifier.fillMaxWidth().imePadding().testTag("church_picker"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Выберите храм", style = MaterialTheme.typography.headlineMedium) }
            if (selectionFailed) item {
                Text("Не удалось выбрать храм. Попробуйте еще раз", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("church_selection_error"))
            }
            item {
                OutlinedTextField(query, { onSearchQueryChange(it.take(120)) }, singleLine = true,
                    label = { Text("Название храма или монастыря") },
                    supportingText = { Text("Сохраненные совпадения появятся ниже") },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onSearchQueryChange("") }) { Icon(Icons.Default.Close, "Очистить поиск храмов") } },
                    modifier = Modifier.fillMaxWidth().testTag("church_search"))
            }
            if (onDiscover != null) item {
                Button(onClick = { picker = false; locate = false; onDiscover() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discover_churches")) {
                    Icon(Icons.Default.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text("Найти в сети")
                }
                Text("По названию, городу или рядом с вами", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onAddChurch != null) item {
                OutlinedButton(onClick = { churchDraft.removeState("new_church"); locate = false; adding = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_church")) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Добавить свой храм")
                }
            }
            item {
                TextButton(onClick = { locate = !locate }, modifier = Modifier.testTag("church_locate")) {
                    Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Сохраненные храмы рядом")
                }
                if (locate) LocateChurchCard(churches) { id -> selectChurch(id) }
            }
            if (matches.isEmpty()) item {
                Text("Среди добавленных храмов ничего не найдено", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("church_search_empty"))
                Text(when {
                    onDiscover != null -> "Нажмите \"Найти в сети\" или добавьте храм вручную"
                    onAddChurch != null -> "Попробуйте другое название или добавьте храм вручную"
                    else -> "Попробуйте другое название"
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(matches, key = { _, item -> item.id }) { index, option ->
                if (index == 0 || (matches[index - 1].id in favorites) != (option.id in favorites)) {
                    Text(if (option.id in favorites) "Избранные" else "Добавленные храмы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().clickable { selectChurch(option.id) }
                        .testTag("church_${option.id}").semantics { role = Role.RadioButton; selected = option.id == selectedId }
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(option.shortName, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(option.city, option.address).filter(String::isNotBlank).distinct().joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (option.id == selectedId) Text("Выбран", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onToggleFavorite(option.id) }, modifier = Modifier.testTag("favorite_${option.id}")) {
                            Icon(if (option.id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                                if (option.id in favorites) "Убрать из избранного: ${option.shortName}" else "В избранное: ${option.shortName}",
                                tint = if (option.id in favorites) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (church != null && formDate != null && onSaveStart != null) ModalBottomSheet(
        onDismissRequest = { editingDate = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GuideSheetSystemBars()
        PersonalServiceTimeForm(church = church, currentDate = currentDate, initialDate = formDate,
            userStarts = userStarts, onCancel = { editingDate = null },
            onSave = { record ->
                if (onSaveStart(record)) {
                    dayOffset = ChronoUnit.DAYS.between(currentDate, LocalDate.parse(record.date)).toInt()
                    editingDate = null
                    true
                } else false
            }, onRemove = onRemoveStart?.let { remove -> { date: LocalDate ->
                if (remove(date)) { editingDate = null; true } else false
            } })
    }
    fun changeChurch() {
        if (!onSourceSearchInteraction()) return
        if (onDiscover != null) onDiscover() else picker = true
    }
    val date = currentDate.plusDays(dayOffset.toLong())
    val ownState = internetSchedule?.takeIf { it.parishId == church?.id }
    val arrival = church?.let { arrivalSchedule(it, date, now, ownState) }
    val listState = rememberLazyListState()
    val sourceChurch = scheduleReviewParish(church, sourceReviewParish)
    val sourceReviewOwner = sourceChurch?.let(ScheduleEntityIdentity::fingerprint)
    val currentSourceReviewOwner by rememberUpdatedState(sourceReviewOwner)
    val currentSourceSearchViewKey by rememberUpdatedState(sourceSearchViewKey)
    // A review can free this view's fixed strip; a new view must render its own strip again.
    var reviewedSourceStrip by remember(sourceReviewOwner, sourceSearchViewKey) { mutableStateOf(false) }
    var handledSourceReview by rememberSaveable(sourceReviewOwner) { mutableIntStateOf(0) }
    val readingBeforeSchedule = rememberReadingBeforeSchedule(listState, church?.id, date, arrival?.featured != null || arrival?.next != null,
        readingKey = "church_reading", personalKey = "church_personal")
    val sourceGroups = if (sourceChurch != null && ownState != null) scheduleCandidateGroups(sourceChurch, ownState) else emptyList()
    val sourceSectionVisible = sourceChurch != null && ownState != null && onConfirmScheduleCandidate != null &&
        (sourceGroups.isNotEmpty() || ownState.candidateSearchUnavailable && !ownState.loading)
    // A presentation change only: no accepted source or safety notice can enter this branch.
    val compactPending = ownState != null && sourceChurch != null && arrival != null && sourceSectionVisible &&
        sourceGroups.isNotEmpty() && sourceGroups.none { it.conflicts.isNotEmpty() || it.alreadyChosen || it.blockedByHistory } &&
        ownState.candidateSearchIdentity == sourceReviewOwner && church?.schedule?.isEmpty() == true &&
        arrival.entries.isEmpty() && arrival.featured == null && arrival.next == null && ownState.publication == null &&
        ownState.siteBinding == null && sourceChurch.website.isBlank() && ownState.configuredWebsite.isBlank() &&
        ownState.website.isBlank() && ownState.sourceUrl.isBlank() && ownState.customSource.isBlank() && ownState.siteEvidenceUrl.isBlank() &&
        !ownState.loading && !ownState.publicationRejected && !ownState.sourceHasWithdrawals && !ownState.sourceNeedsVerification &&
        ownState.sourceChangeBlock == ScheduleSourceChangeBlock.NONE
    val currentCompactPending by rememberUpdatedState(compactPending)
    // Header (optional), identity, day chips, then the optional retained reading cards.
    val hasPersonalCard = church != null && personalStartForDate(userStarts, church, date) != null
    val readingOwner = church?.id.orEmpty() + "\u0000" + date.toString()
    // One saved latch retains the full form and its control variants. Source-view
    // replacement must not shrink or repurpose an already composed reading item.
    var fullReadingForm by rememberSaveable(church?.id, date.toString()) { mutableStateOf<String?>(null) }
    val retainedReadingControls = fullReadingForm?.takeIf {
        it.length == readingOwner.length + 3 && it.startsWith(readingOwner + "\u0000")
    }?.takeLast(2)?.takeIf { controls -> controls.all { it == '0' || it == '1' } }
    val hasPersonalReading = hasPersonalCard && onReadLiturgy != null
    val showReadingLiturgy = onReadLiturgy != null && !hasPersonalReading
    val showReadingChooser = churchReadingChooser(arrival?.featured, hasPersonalReading,
        onReadLiturgy != null, onReadServiceText != null, onOpenService != null,
        retainedChooser = retainedReadingControls?.get(1)?.let { it == '1' })
    val readingEntry = churchReadingEntry(church, date, arrival?.featured,
        showLiturgy = showReadingLiturgy, showChooser = showReadingChooser,
        hasChooser = onReadServiceText != null, hasOpenService = onOpenService != null,
        keepFull = retainedReadingControls != null)
    val hasReadingCard = readingEntry != ChurchReadingEntry.NONE
    val scheduleItemIndex = (if (showBack) 0 else 1) + 2 +
        (if (readingBeforeSchedule) (if (hasPersonalCard) 1 else 0) + (if (hasReadingCard) 1 else 0) else 0)
    // Sources are the next stable item, independent of the selected date or featured service.
    val reviewItemIndex = scheduleItemIndex + if (sourceSectionVisible) 1 else 0
    val currentReviewItemIndex by rememberUpdatedState(reviewItemIndex)
    val reviewScope = key(sourceReviewOwner, sourceSearchViewKey) { rememberCoroutineScope() }

    LaunchedEffect(sourceReviewRequest, sourceReviewIdentity, sourceReviewOwner, sourceSectionVisible,
        ownState?.loading, reviewItemIndex, sourceSearchViewKey) {
        if (church == null || sourceReviewRequest == 0 || sourceReviewRequest == handledSourceReview ||
            sourceReviewIdentity != null && sourceReviewIdentity != sourceReviewOwner) return@LaunchedEffect
        // A pending request may wait for this attempt, but never reset the person's date.
        if (!sourceSectionVisible && ownState?.loading == true) return@LaunchedEffect
        val reviewView = sourceSearchViewKey
        val reviewOwner = sourceReviewOwner
        val collapseAfterReview = compactPending && sourceSearchActive != null
        picker = false; adding = false; locate = false
        withFrameNanos { }
        if (currentSourceSearchViewKey !== reviewView || currentSourceReviewOwner != reviewOwner) return@LaunchedEffect
        listState.scrollToItem(currentReviewItemIndex)
        withFrameNanos { }
        if (currentSourceSearchViewKey !== reviewView || currentSourceReviewOwner != reviewOwner) return@LaunchedEffect
        handledSourceReview = sourceReviewRequest
        if (collapseAfterReview && currentCompactPending) reviewedSourceStrip = true
    }
    // Render a new source view with its current captures. Durable day/list/review
    // state and source jobs stay outside this presentation lifetime.
    key(sourceReviewOwner, sourceSearchViewKey) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { Column(Modifier.statusBarsPadding()) {
        if (showBack) PageHeader("Храм", onBack)
    } }, bottomBar = bottomBar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        if (!reviewedSourceStrip) sourceSearchActive?.let { searching ->
            ScheduleSourceSearchStrip(searching, ownState?.candidateSearchUnavailable == true,
                onReview = { if (currentSourceReviewOwner == sourceReviewOwner && currentChurchId == church?.id) {
                    val review = onReviewSourceSearch
                    if (review != null) review()
                    else {
                        val reviewView = sourceSearchViewKey
                        val reviewOwner = sourceReviewOwner
                        val collapseAfterReview = compactPending
                        reviewScope.launch {
                            withFrameNanos { }
                            if (currentSourceSearchViewKey !== reviewView || currentSourceReviewOwner != reviewOwner) return@launch
                            listState.scrollToItem(currentReviewItemIndex)
                            withFrameNanos { }
                            if (currentSourceSearchViewKey === reviewView && currentSourceReviewOwner == reviewOwner &&
                                collapseAfterReview && currentCompactPending) reviewedSourceStrip = true
                        }
                    }
                } },
                onDrawn = onSourceSearchDrawn)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("church_content"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (!showBack) item(key = "church_header") {
                Text("Мой храм", Modifier.testTag("church_title").semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            }
            if (church == null) item(key = "church_find") {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Найдите свой храм", style = MaterialTheme.typography.headlineMedium)
                    Text("По названию, городу или рядом с вами", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = ::changeChurch, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("change_church")) {
                        Icon(Icons.Default.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Найти храм")
                    }
                }
            }
            else {
                val schedule = requireNotNull(arrival)
                val entries = schedule.entries
                val featured = schedule.featured
                val cachedPublication = schedule.publication
                val personalStart = personalStartForDate(userStarts, church, date)
                fun personalItem() {
                    val start = personalStart ?: return
                    item(key = "church_personal") {
                        ArrivalCard(Modifier.testTag("church_personal_card")) {
                            Text(start.time + " · Литургия", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("personal_start_summary"))
                            Text("Начало указано вами · " + parishTimeZoneLabel(church, date, start.time),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("personal_start_source"))
                            onReadLiturgy?.let { read ->
                                val openPersonal = { read(LocalDate.parse(start.date)) }
                                if (featured == null || onOpenService == null) Button(onClick = openPersonal,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("personal_time_to_service")) { Text("Открыть литургию") }
                                else OutlinedButton(onClick = openPersonal, shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_time_to_service")) { Text("Открыть литургию") }
                            }
                        }
                    }
                }
                fun readingItem() {
                    if (readingEntry == ChurchReadingEntry.NONE) return
                    val read = onReadLiturgy
                    val primaryReading = retainedReadingControls?.get(0)?.let { it == '1' }
                        ?: (featured == null || onOpenService == null || read == null && !hasPersonalReading)
                    val choose = { if (onSourceSearchInteraction()) chooserDateIso = date.toString() }
                    item(key = "church_reading") {
                        if (readingEntry == ChurchReadingEntry.COMPACT) Box(Modifier.testTag("church_reading_card")) {
                            ArrivalAction("Выбрать другой текст", Icons.Default.MenuBook, choose,
                                Modifier.testTag("church_other_services"))
                        } else {
                            SideEffect {
                                if (retainedReadingControls == null) fullReadingForm = readingOwner + "\u0000" +
                                    (if (primaryReading) "1" else "0") + (if (showReadingChooser) "1" else "0")
                            }
                            ArrivalCard(Modifier.testTag("church_reading_card"), accent = primaryReading && !hasPersonalReading) {
                                Text("Читать службу", style = MaterialTheme.typography.titleLarge)
                                Text("Оригинал, доступный русский перевод и объяснения. Можно читать в своем темпе", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (read != null && showReadingLiturgy) {
                                    if (primaryReading) Button(onClick = { read(date) }, shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("church_read_liturgy")) {
                                        Text(readingEntryLabel(date))
                                    } else OutlinedButton(onClick = { read(date) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("church_read_liturgy")) { Text("Текст литургии на этот день") }
                                }
                                if (showReadingChooser) {
                                    if (read == null && primaryReading) Button(onClick = choose, shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("church_other_services")) { Text("Выбрать другой текст") }
                                    else OutlinedButton(onClick = choose,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("church_other_services")) { Text("Выбрать другой текст") }
                                }
                            }
                        }
                    }
                }
                item(key = "church_identity") {
                    ArrivalCard(Modifier.testTag("church_identity_card")) {
                        Text(church.shortName, style = MaterialTheme.typography.titleLarge)
                        Text(churchAddressLine(church), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = ::changeChurch, shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("change_church")) {
                            Icon(Icons.Default.SwapHoriz, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Сменить храм")
                        }
                    }
                }
                item(key = "church_days") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(selected = dayOffset == 0, onClick = { if (onSourceSearchInteraction()) dayOffset = 0 }, label = { Text("Сегодня") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("schedule_today"))
                        FilterChip(selected = dayOffset == 1, onClick = { if (onSourceSearchInteraction()) dayOffset = 1 }, label = { Text("Завтра") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("schedule_tomorrow"))
                    }
                }
                // Once reached, this group stays in place even if a publication arrives.
                // The published service still receives the primary action.
                if (readingBeforeSchedule) {
                    personalItem()
                    readingItem()
                }
                item(key = "church_schedule") {
                    if (compactPending) Column(Modifier.fillMaxWidth().testTag("church_schedule_card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(date.format(DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", Locale.forLanguageTag("ru"))),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("schedule_date"))
                        Text("Расписание пока не получено", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("church_schedule_empty"))
                    } else ArrivalCard(Modifier.testTag("church_schedule_card")) {
                        Text(if (schedule.saved) "Сохраненное расписание" else "Расписание", style = MaterialTheme.typography.titleMedium)
                        Text(date.format(DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", Locale.forLanguageTag("ru"))),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("schedule_date"))
                        ownState?.let { ScheduleSourceChangeNotice(it) }
                        if (featured != null) {
                            Text(featured.time, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("church_service_time"))
                            ArrivalServiceHeading(featured.title, "church_service")
                            Text(schedule.relation.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("church_service_relation"))
                            if (cachedPublication?.yearInferred == true && (schedule.saved || cachedPublication.sourceUrl == church.sourceUrl)) {
                                Text("Год не указан - проверьте дату в расписании", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("schedule_inferred_year"))
                            }
                            if (onOpenService != null) Button(onClick = { onOpenService(featured) }, shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("church_open_service")) {
                                Text(serviceReadingAction(featured))
                            }
                            if (schedule.knownPartial) PartialScheduleNotice(schedule.sourceUrl, onOpenUrl, "church_service")
                            PublishedServiceScopeNote(featured.title, "church_service")
                            ArrivalServiceSourceTitle(featured.title, "church_service")
                            if (!schedule.knownPartial && cachedPublication?.yearInferred == true && (schedule.saved || cachedPublication.sourceUrl == church.sourceUrl)) {
                                OutlinedButton(onClick = { onOpenUrl(cachedPublication.sourceUrl) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("church_verify_schedule_source")) {
                                    Text("Проверить источник")
                                }
                            }
                            if (entries.size > 1) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text("Еще в этот день", style = MaterialTheme.typography.titleMedium)
                                entries.filterNot { it == featured }.forEach { service ->
                                    if (onOpenService != null) ArrivalAction(serviceKindTitle(service.title) ?: service.title, Icons.Default.Schedule, { onOpenService(service) },
                                        subtitle = serviceActionSubtitle(service.title, service.time))
                                    else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(service.time, style = MaterialTheme.typography.labelLarge)
                                        Text(serviceKindTitle(service.title) ?: service.title, style = MaterialTheme.typography.bodyLarge)
                                        serviceSourceTitle(service.title)?.let { Text("В расписании храма: " + it, style = MaterialTheme.typography.bodyMedium) }
                                    }
                                    PublishedServiceScopeNote(service.title, "church_other_${service.time}")
                                }
                            }
                        } else {
                            Text(scheduleEmptyTitle(ownState, schedule.publication != null || church.schedule.isNotEmpty(), church, schedule.knownPartial),
                                style = if (schedule.next == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("church_schedule_empty"))
                            val source = scheduleSourceFor(church, ownState)
                            if (schedule.next == null) {
                                val description = scheduleEmptyDescription(ownState, source != null, church, schedule.knownPartial)
                                if (schedule.knownPartial) PartialScheduleNotice(schedule.sourceUrl, onOpenUrl, "church_empty", description)
                                else Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            schedule.next?.let { offer -> UpcomingScheduleOffer(offer, "church", onOpenService, onOpenUrl,
                                partialDescription = scheduleEmptyDescription(ownState, source != null, church, offer.knownPartial)) }
                            if (!sourceSectionVisible) ChurchScheduleSourceActions(church, ownState,
                                onRefreshSchedule, openSourceEditor.takeIf { onSetScheduleSource != null }, onOpenUrl,
                                showSource = !schedule.knownPartial || schedule.sourceUrl == null)
                        }
                        if (ownState?.loading == true) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Обновляем расписание", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("schedule_loading_summary"))
                        }
                        ScheduleCheckReceipt(ownState, church.timezone, Modifier.testTag("church_schedule_checked"), church)
                    }
                }
                if (sourceSectionVisible && ownState != null) item(key = "church_schedule_sources") {
                    val sourceContent: @Composable ColumnScope.() -> Unit = {
                        val ownerIdentity = ScheduleEntityIdentity.fingerprint(requireNotNull(sourceChurch))
                        ScheduleCandidateChoices(requireNotNull(sourceChurch), ownState,
                            onConfirm = { id -> if (currentSourceReviewOwner == ownerIdentity) onConfirmScheduleCandidate?.invoke(id) },
                            onDismiss = onDismissScheduleCandidate?.let { dismiss ->
                                { id: String -> if (currentSourceReviewOwner == ownerIdentity) dismiss(id) }
                            }, onOpenUrl = onOpenUrl, stableSourceOrder = sourceSearchOrder,
                            showSearchDiagnostic = sourceSearchActive == null || reviewedSourceStrip, compactPending = compactPending)
                        if (featured == null) ChurchScheduleSourceActions(church, ownState,
                            onRefreshSchedule, openSourceEditor.takeIf { onSetScheduleSource != null }, onOpenUrl)
                        val findOtherSources = onFindOtherScheduleSources
                            ?.takeIf { permitsOtherSourceSearch(requireNotNull(sourceChurch), ownState) }
                        if (findOtherSources != null) OutlinedButton(onClick = findOtherSources,
                            enabled = !ownState.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("find_other_schedule_sources")) {
                            Text("Искать другие сайты")
                        }
                        if (compactPending) ScheduleCheckReceipt(ownState, church.timezone, Modifier.testTag("church_schedule_checked"), church)
                    }
                    if (compactPending) Column(Modifier.fillMaxWidth().testTag("church_sources_card"),
                        verticalArrangement = Arrangement.spacedBy(16.dp), content = sourceContent)
                    else ArrivalCard(Modifier.testTag("church_sources_card"), content = sourceContent)
                }
                if (!readingBeforeSchedule) {
                    personalItem()
                    readingItem()
                }
                if (entries.isEmpty()) item(key = "church_typical") { TypicalScheduleContent(date) }
                item(key = "church_information") {
                    ArrivalCard(Modifier.testTag("church_information_card")) {
                        Text("О храме", style = MaterialTheme.typography.titleMedium)
                        if (ownState != null && onRefreshSchedule != null) InternetScheduleControls(
                            state = ownState, church = church, timezone = church.timezone, onRefresh = onRefreshSchedule,
                            onOpenUrl = onOpenUrl,
                            onEditSource = openSourceEditor.takeIf {
                                onSetScheduleSource != null && (featured != null || scheduleSourceFor(church, ownState) != null)
                            },
                            showRefresh = featured != null, showWebsite = featured != null)
                        ArrivalAction(if (details) "Скрыть сведения" else "Адрес и другие сведения", Icons.Default.Info,
                            { details = !details }, Modifier.testTag("church_details"))
                        if (details) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(churchAddressLine(church), style = MaterialTheme.typography.bodyMedium)
                            if (entries.isNotEmpty()) {
                                Text(church.scheduleNote.ifBlank { "Время может измениться. Проверьте расписание перед поездкой" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val source = if (schedule.saved) cachedPublication?.sourceUrl.orEmpty() else church.sourceUrl
                                if (SavedChurchCodec.isSafeWebsite(source)) ArrivalAction("Источник расписания", Icons.Default.OpenInNew,
                                    { onOpenUrl(source) }, Modifier.testTag("schedule_source"))
                            }
                            if (onSaveStart != null) ArrivalAction(
                                if (personalStart == null) "Указать известное мне время" else "Изменить мое время", Icons.Default.Edit,
                                { if (onSourceSearchInteraction()) editingDate = date.toString() }, Modifier.testTag("edit_personal_time"))
                            if (SavedChurchCodec.isSafeWebsite(church.website)) ArrivalAction("Сайт прихода", Icons.Default.OpenInNew, { onOpenUrl(church.website) })
                            if (church.phone.isNotBlank()) ArrivalAction("Позвонить: " + church.phone, Icons.Default.Phone,
                                { onOpenUrl("tel:" + church.phone.filter { it.isDigit() || it == '+' }) })
                            ArrivalAction("Сохраненные и избранные храмы", Icons.Default.StarBorder,
                                { if (onSourceSearchInteraction()) picker = true }, Modifier.testTag("saved_churches"))
                        }
                    }
                }
            }
        }
        }
    }
    }
    if (church != null) {
        val ownerId = church.id
        val chooserDate = chooserDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (chooserDate != null && onReadServiceText != null) ServiceBookChooser(chooserDate,
            onDismiss = { chooserDateIso = null }, onSelect = { selectedDate, worship ->
                if (currentChurchId == ownerId) onReadServiceText(selectedDate, worship)
            })
        if (editingSource && onSetScheduleSource != null) {
            val ownState = internetSchedule?.takeIf { it.parishId == ownerId }
            ScheduleSourceSheet(churchId = ownerId, churchName = church.shortName,
                initialUrl = ownState?.website.orEmpty().ifBlank { church.website }, loading = ownState?.loading == true,
                onDismiss = { editingSource = false }, onSubmit = { url ->
                    if (currentChurchId == ownerId) {
                        editingSource = false
                        onSetScheduleSource(url)
                    }
                })
        }
    }
}

internal fun serviceReadingAction(service: ParishService): String = ServiceBookSelection.readingAction(service.title)

internal fun churchAddressLine(church: Parish): String {
    val address = church.address.trim()
    val cityWords = church.city.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }
    return if (address.isNotEmpty() && cityWords.isNotEmpty() && cityWords.all { address.lowercase().contains(it) }) address
    else listOf(church.city, address).filter(String::isNotBlank).distinct().joinToString(" · ")
}

@Composable
private fun ChurchScheduleSourceActions(church: Parish, state: InternetScheduleState?,
    onRefreshSchedule: (() -> Unit)?, onEditSource: (() -> Unit)?, onOpenUrl: (String) -> Unit, showSource: Boolean = true) {
    val source = scheduleSourceFor(church, state)
    onRefreshSchedule?.let { refresh -> OutlinedButton(onClick = refresh, enabled = state?.loading != true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("refresh_schedule")) {
        Icon(Icons.Default.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Обновить расписание")
    } }
    if (showSource) source?.let { url ->
        OutlinedButton(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .testTag("open_schedule_website")) {
            Icon(Icons.Default.OpenInNew, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp)); Text("Открыть сайт")
        }
    }
    if (onEditSource != null && canChooseUnverifiedScheduleSource(church, state)) {
        Text("Проверьте на сайте, что это расписание вашего храма", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onEditSource,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("church_schedule_source_choice")) {
            Icon(Icons.Default.Edit, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp)); Text("Указать источник")
        }
    }
    if (source == null && onEditSource != null) {
        OutlinedButton(onClick = onEditSource,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("edit_schedule_website")) {
            Icon(Icons.Default.Edit, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp)); Text("Указать сайт")
        }
    }
}

@Composable
private fun InternetScheduleControls(state: InternetScheduleState, church: Parish, timezone: String, onRefresh: () -> Unit,
    onOpenUrl: (String) -> Unit, onEditSource: (() -> Unit)?, showRefresh: Boolean = true, showWebsite: Boolean = true) {
    val source = state.sourceUrl.ifBlank { state.website }
    val fetched = state.publication?.fetchedAt?.let { timestamp -> runCatching {
        Instant.parse(timestamp).atZone(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru")))
    }.getOrNull() }
    Column(Modifier.fillMaxWidth().testTag("internet_schedule"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val message = when {
            state.loading -> "Проверяем расписание на сайте"
            state.publicationRejected -> "Расписание не подтверждено источником"
            state.sourceHasWithdrawals -> "В источнике есть отмены или изменения"
            state.sourceNeedsVerification -> "Источник требует проверки"
            state.status == InternetScheduleStatus.READY && state.publication?.hasCurrentParserRevision == false -> "Обновите расписание"
            state.status == InternetScheduleStatus.READY && state.publication != null -> "Расписание загружено с сайта"
            hasUnreviewedScheduleSources(state, church) -> "Найдены сайты, ожидается проверка"
            state.status == InternetScheduleStatus.READY -> "Расписание не получено"
            state.status == InternetScheduleStatus.NO_WEBSITE -> "У этого храма пока не указан сайт"
            state.status == InternetScheduleStatus.UNSUPPORTED -> "Не удалось прочитать даты и время на сайте"
            state.status == InternetScheduleStatus.UNAVAILABLE -> "Не удалось обновить расписание"
            else -> null
        }
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("schedule_network_status"))
        if (fetched != null) Text("Сохранено $fetched", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showRefresh) ArrivalAction(if (state.status == InternetScheduleStatus.UNAVAILABLE) "Повторить обновление" else "Обновить расписание",
            Icons.Default.Refresh, onRefresh, Modifier.testTag("refresh_schedule"), enabled = !state.loading)
        if (showWebsite && ScheduleUrl.publicSource(source) != null) ArrivalAction("Открыть сайт храма", Icons.Default.OpenInNew,
            { onOpenUrl(source) }, Modifier.testTag("open_schedule_website"))
        onEditSource?.let { edit ->
            ArrivalAction(if (state.website.isBlank()) "Указать сайт" else "Изменить сайт", Icons.Default.Edit,
                edit, Modifier.testTag("edit_schedule_website"))
        }
    }
}

internal fun friendlyDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru")))
