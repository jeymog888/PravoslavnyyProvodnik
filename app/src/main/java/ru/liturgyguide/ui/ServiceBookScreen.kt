package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.*

private enum class AcceptedEditionAction { CHECK, DOWNLOAD, OPEN_NEW }
private class BookOfflineWork { var run: Any? = null }

/** Owns a single requested book. A cancelled response can never replace another route's text. */
@Composable
fun ServiceBookScreen(initialKey: ServiceBookKey, parishId: String?, books: ServiceBooks,
    readingStore: BookReadingStore, textSize: Int, onBack: () -> Unit, onSettings: () -> Unit,
    onOpenUrl: (String) -> Unit, onRequestChanged: (ServiceBookKey) -> Unit = {}, parish: Parish? = null,
    route: BookRoute? = null, onRouteChanged: (BookRoute) -> Boolean = { false },
    onOpenCommonLiturgy: () -> Unit) {
    val ownParish = parish?.takeIf { it.id == parishId }
    val scope = rememberCoroutineScope()
    // AppNavigation owns managed routes. Direct standalone readers retain their existing API.
    var standaloneRequest by remember(initialKey, parishId) { mutableStateOf(initialKey) }
    val ownedRoute = route?.validated()?.takeIf { it.parishId == parishId }
    val requested = ownedRoute?.activeKey ?: standaloneRequest
    val journey = ownedRoute?.journeyContext?.validatedFor(requested, parishId)
    fun changeRequest(key: ServiceBookKey, savedCurrentPart: Boolean): Boolean {
        if (ownedRoute != null) return ownedRoute.withVariant(key, savedCurrentPart)?.let(onRouteChanged) == true
        standaloneRequest = key
        onRequestChanged(key)
        return true
    }
    fun selectPart(key: ServiceBookKey, savedCurrentPart: Boolean): Boolean =
        ownedRoute?.selectPart(key, savedCurrentPart)?.let(onRouteChanged) == true
    var retry by remember(requested) { mutableIntStateOf(0) }
    var result by remember(requested, parishId) { mutableStateOf<BookLoadResult?>(null) }
    var update by remember(requested, parishId) { mutableStateOf<BookLoadResult.Ready?>(null) }
    var refreshing by remember(requested, parishId) { mutableStateOf(false) }
    var refreshWarning by remember(requested, parishId) { mutableStateOf<String?>(null) }
    var progress by remember(requested) { mutableStateOf(BookLoadProgress.SOURCE) }
    var recovery by remember(requested, parishId) { mutableStateOf<ServiceBook?>(null) }
    var acceptedRecovery by remember(requested, parishId, journey) { mutableStateOf<BookReadingLookup?>(null) }
    var acceptedAction by remember(requested, parishId, journey) { mutableStateOf(AcceptedEditionAction.CHECK) }
    var acceptedCandidate by remember(requested, parishId, journey) { mutableStateOf<BookLoadResult.Ready?>(null) }
    // Only a durable candidate's hash survives recreation. Its text is reverified before offering it.
    var acceptedCandidateHash by rememberSaveable(requested.canonical(), parishId, journey) { mutableStateOf<String?>(null) }
    var acceptedRecoveryWarning by remember(requested, parishId, journey) { mutableStateOf<String?>(null) }
    val returnCandidate = ownedRoute?.returnToLastSuccessful()?.takeIf { it.activeKey != requested }
    var savedReturn by remember(returnCandidate, parishId) { mutableStateOf<BookRoute?>(null) }
    suspend fun verifiedReturn(candidate: BookRoute): BookRoute? = withContext(Dispatchers.IO) {
        val position = readingStore.positionFor(candidate.activeKey, parishId) ?: return@withContext null
        val context = position.journeyContext?.validatedFor(candidate.activeKey, parishId) ?: return@withContext null
        val expected = candidate.journeyContext ?: return@withContext null
        // The route names a possible return. Only this publication's saved cursor
        // and its exact accepted edition prove that it is safe to offer the action.
        if (context.civilDate != expected.civilDate || context.time != expected.time || context.title != expected.title ||
            context.effectivePartSet != expected.effectivePartSet ||
            context.parts.zip(expected.parts).any { (saved, pending) ->
                saved.date != pending.date || saved.worship != pending.worship || saved.churchType != pending.churchType ||
                    saved.topLevel != pending.topLevel
            }) return@withContext null
        val book = books.cachedVersion(candidate.activeKey, position.contentHash) ?: return@withContext null
        if (book.key != candidate.activeKey || book.contentHash != position.contentHash) return@withContext null
        candidate.copy(journeyContext = context)
    }
    LaunchedEffect(books, returnCandidate, parishId) {
        savedReturn = null
        val candidate = returnCandidate ?: return@LaunchedEffect
        try {
            val accepted = verifiedReturn(candidate)
            currentCoroutineContext().ensureActive()
            savedReturn = accepted
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Do not advertise a return to an unverified saved edition. */ }
    }
    LaunchedEffect(books, requested, parishId, journey, ownedRoute?.pendingSearchTarget, retry) {
        refreshing = true
        val action = acceptedAction
        acceptedAction = AcceptedEditionAction.CHECK
        val previousRecovery = acceptedRecovery
        var checkedReading: BookReadingLookup? = null
        try {
            val target = ownedRoute?.pendingSearchTarget
            if (target != null) {
                acceptedRecovery = null
                acceptedCandidate = null
                acceptedCandidateHash = null
                acceptedRecoveryWarning = null
                // Search navigation pins the edition from its session. Neither retry nor
                // recreation is permission to load a newer text under these coordinates.
                val exact = withContext(Dispatchers.IO) { books.cachedVersion(requested, target.contentHash) }
                currentCoroutineContext().ensureActive()
                result = if (exact != null && target.validatedFor(exact) != null)
                    BookLoadResult.Ready(exact, fromCache = true, warning = BookWarnings.supplementWarning(exact))
                else BookLoadResult.Failed(BookFailure.STORAGE,
                    "Найденная редакция или строка недоступна на телефоне. Вернитесь к прежней книге и повторите поиск")
                update = null
                return@LaunchedEffect
            }
            val reading = withContext(Dispatchers.IO) { readingStore.lookup(requested, parishId) }
            checkedReading = reading
            fun stopIfReadingChanged(): Boolean {
                if (readingStore.lookup(requested, parishId) == reading) return false
                acceptedRecovery = reading
                acceptedCandidate = null
                acceptedCandidateHash = null
                acceptedRecoveryWarning = "Сохраненное место изменилось. Проверьте его еще раз"
                result = BookLoadResult.Failed(BookFailure.STORAGE,
                    "Открытие остановлено, чтобы сохранить ваше место чтения")
                update = null
                return true
            }
            val position = (reading as? BookReadingLookup.Found)?.position
            val pinned = position?.let { accepted -> withContext(Dispatchers.IO) {
                books.cachedVersion(requested, accepted.contentHash)?.takeIf {
                    it.key == requested && it.contentHash == accepted.contentHash && ServiceBookIntegrity.valid(it)
                }
            } }
            currentCoroutineContext().ensureActive()
            val opened = (result as? BookLoadResult.Ready)?.book ?: (result as? BookLoadResult.Failed)?.cachedBook
            if (action == AcceptedEditionAction.OPEN_NEW && previousRecovery != null) {
                // A button click does not authorize replacing a cursor changed by another owner.
                if (reading != previousRecovery) {
                    acceptedRecoveryWarning = "Сохраненное место изменилось. Проверьте его еще раз"
                    return@LaunchedEffect
                }
                val hash = acceptedCandidateHash
                val candidate = if (hash == null) null else withContext(Dispatchers.IO) {
                    books.cachedVersion(requested, hash)?.takeIf {
                        it.key == requested && it.contentHash == hash && ServiceBookIntegrity.valid(it)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (candidate == null) {
                    acceptedCandidate = null
                    acceptedCandidateHash = null
                    acceptedRecoveryWarning = "Загруженная редакция недоступна на телефоне. Загрузите текст еще раз"
                    return@LaunchedEffect
                }
                val previous = (previousRecovery as? BookReadingLookup.Found)?.position
                val firstChapter = candidate.chapters.firstOrNull { chapter ->
                    chapter.blocks.any { previous?.showAltar == true || !it.isAltarText }
                }
                val firstBlock = firstChapter?.blocks?.firstOrNull { previous?.showAltar == true || !it.isAltarText }
                val freshPosition = if (firstChapter == null || firstBlock == null) null else BookReadingPosition(
                    requested, candidate.contentHash, parishId, firstBlock.id, firstChapter.id,
                    mode = previous?.mode ?: BookReadingMode.ORIGINAL,
                    wordsPerMinute = previous?.wordsPerMinute ?: BookAutoScroll.DEFAULT_RATE,
                    showAltar = previous?.showAltar ?: false, anchorKind = BookAnchorKind.HEADER,
                    lastTextMode = previous?.lastTextMode, journeyContext = journey)
                // The small atomic preference commit stays on this active UI turn: no suspension
                // may separate the final owner check from accepting the new reading pointer.
                if (freshPosition == null || readingStore.lookup(requested, parishId) != previousRecovery ||
                    !readingStore.save(freshPosition)) {
                    acceptedRecoveryWarning = "Не удалось сохранить новое место. Прежнее сохраненное место не изменено"
                    return@LaunchedEffect
                }
                result = BookLoadResult.Ready(candidate, fromCache = true, warning = BookWarnings.supplementWarning(candidate))
                acceptedRecovery = null
                acceptedCandidate = null
                acceptedCandidateHash = null
                acceptedRecoveryWarning = null
                update = null
                return@LaunchedEffect
            }
            if ((reading == BookReadingLookup.Unreadable || position != null && pinned == null) && opened == null) {
                acceptedRecovery = reading
                update = null
                result = BookLoadResult.Failed(BookFailure.STORAGE, if (reading == BookReadingLookup.Unreadable)
                    "Сохраненное место повреждено или недоступно. Его можно проверить еще раз или загрузить текст заново"
                    else "Книга, в которой вы читали, недоступна на телефоне. Место чтения сохранено")
                if (action != AcceptedEditionAction.DOWNLOAD) {
                    val hash = acceptedCandidateHash
                    acceptedCandidate = if (hash == null) null else withContext(Dispatchers.IO) {
                        books.cachedVersion(requested, hash)?.takeIf {
                            it.key == requested && it.contentHash == hash && ServiceBookIntegrity.valid(it)
                        }?.let { BookLoadResult.Ready(it, fromCache = true) }
                    }
                    currentCoroutineContext().ensureActive()
                    return@LaunchedEffect
                }
                acceptedCandidate = null
                acceptedCandidateHash = null
                acceptedRecoveryWarning = null
                val fetched = books.load(requested, force = true) { progress = it }
                currentCoroutineContext().ensureActive()
                if (readingStore.lookup(requested, parishId) != reading) {
                    acceptedRecoveryWarning = "Сохраненное место изменилось. Проверьте его еще раз"
                    return@LaunchedEffect
                }
                val durable = (fetched as? BookLoadResult.Ready)?.takeIf { ready ->
                    ready.saved && ready.book.key == requested && ServiceBookIntegrity.valid(ready.book)
                }?.let { ready -> withContext(Dispatchers.IO) {
                    ready.takeIf { books.cachedVersion(requested, ready.book.contentHash) == ready.book }
                } }
                currentCoroutineContext().ensureActive()
                // Durability verification suspends too. A different reader may accept another
                // edition in that last IO; never mount this one under its newer coordinates.
                if (stopIfReadingChanged()) return@LaunchedEffect
                when {
                    durable == null -> acceptedRecoveryWarning = (fetched as? BookLoadResult.Failed)?.message
                        ?: "Не удалось сохранить загруженную редакцию на телефоне. Сохраненное место не изменено"
                    position != null && durable.book.contentHash == position.contentHash -> {
                        // Exact content can restore the existing coordinates; no new edition is accepted.
                        result = durable
                        acceptedRecovery = null
                        acceptedRecoveryWarning = null
                    }
                    else -> {
                        acceptedCandidate = durable
                        acceptedCandidateHash = durable.book.contentHash
                    }
                }
                return@LaunchedEffect
            }
            if (reading == BookReadingLookup.Unreadable) {
                // A storage problem during a refresh must not take an already readable book away.
                refreshWarning = "Не удалось проверить сохраненное место. Открытая книга осталась на месте"
                return@LaunchedEffect
            }
            acceptedRecovery = null
            acceptedCandidate = null
            acceptedCandidateHash = null
            acceptedRecoveryWarning = null
            if (pinned != null && (opened == null || retry == 0 || previousRecovery != null)) {
                // A downloaded update remains available after leaving the reader or going offline.
                // Keep the accepted text fixed until the reader explicitly chooses the new version.
                var latestReadFailed = false
                val latest = try { withContext(Dispatchers.IO) { books.cached(requested) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { latestReadFailed = true; null }
                currentCoroutineContext().ensureActive()
                // No suspension may separate this final owner check from mounting the reader.
                if (stopIfReadingChanged()) return@LaunchedEffect
                result = BookLoadResult.Ready(pinned, fromCache = true, warning = BookWarnings.supplementWarning(pinned))
                if (latestReadFailed) refreshWarning = "Не удалось проверить обновления. Сохраненная книга открыта"
                if (latest != null && latest.contentHash != pinned.contentHash)
                    update = BookLoadResult.Ready(latest, fromCache = true, warning = BookWarnings.supplementWarning(latest))
            } else {
                val fetched = books.load(requested, force = retry > 0) { progress = it }
                currentCoroutineContext().ensureActive()
                val accepted = (result as? BookLoadResult.Ready)?.book ?: (result as? BookLoadResult.Failed)?.cachedBook
                if (accepted != null && retry > 0) when (fetched) {
                    is BookLoadResult.Ready -> {
                        val saved = fetched.saved && fetched.book.key == requested && withContext(Dispatchers.IO) {
                            books.cachedVersion(requested, fetched.book.contentHash) == fetched.book
                        }
                        if (!saved) refreshWarning = "Обновленный текст не удалось сохранить на телефоне. Открытая книга осталась на месте. Попробуйте обновить еще раз"
                        else if (fetched.book.contentHash != accepted.contentHash) { update = fetched; refreshWarning = null }
                        else {
                            // Retrying can save the same text that previously failed to persist.
                            // Keep the reader identity, but enable its durable bookmark now.
                            result = fetched; update = null
                            refreshWarning = fetched.warning ?: "На телефоне актуальная версия текста"
                        }
                    }
                    is BookLoadResult.Failed -> refreshWarning = fetched.message
                } else result = fetched
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (acceptedRecovery != null) acceptedRecoveryWarning = "Не удалось проверить или загрузить книгу. Сохраненное место не изменено"
            else if (checkedReading != null && checkedReading != BookReadingLookup.Absent &&
                result !is BookLoadResult.Ready && (result as? BookLoadResult.Failed)?.cachedBook == null) {
                acceptedRecovery = checkedReading
                result = BookLoadResult.Failed(BookFailure.STORAGE, "Не удалось проверить сохраненную редакцию. Сохраненное место не изменено")
                update = null
            } else if (result == null) result = BookLoadResult.Failed(BookFailure.NETWORK, "Не удалось открыть службу. Попробуйте еще раз")
            else refreshWarning = "Не удалось обновить. Открытая книга осталась на месте"
        } finally { refreshing = false }
    }
    LaunchedEffect(books, requested, parishId, ownParish, result, refreshing) {
        recovery = null
        val failed = result as? BookLoadResult.Failed
        if (ownedRoute?.pendingSearchTarget == null && !refreshing && failed != null && failed.cachedBook == null &&
            failed.reason in setOf(BookFailure.NETWORK, BookFailure.INVALID_SOURCE)) {
            recovery = try {
                withContext(Dispatchers.IO) {
                    BookVariantRecovery.candidate(requested, parishId, ownParish, readingStore::positionFor, books::cachedVersion)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    val loaded = when (val state = result) {
        is BookLoadResult.Ready -> state.book
        is BookLoadResult.Failed -> state.cachedBook
        else -> null
    }
    // This identity owns both checks and preparation. Even a non-cooperative late
    // response must not publish into another text, edition, journey or parish.
    val offlineOwner = remember(books, readingStore, loaded?.key, loaded?.contentHash, journey, parishId) { Any() }
    val currentOfflineOwner by rememberUpdatedState(offlineOwner)
    val offlineWork = remember(offlineOwner) { BookOfflineWork() }
    var offlineState by remember(offlineOwner) { mutableStateOf<BookOfflineState?>(null) }
    var offlineBusy by remember(offlineOwner) { mutableStateOf(false) }
    var offlinePreparing by remember(offlineOwner) { mutableStateOf(false) }
    var offlineRequest by remember(offlineOwner) { mutableIntStateOf(0) }
    var prepareOffline by remember(offlineOwner) { mutableStateOf(false) }
    // Keep this whole group in the same composition position while readiness and
    // update candidates change. The reader must retain its measured prayer line.
    val offlineControls = BookOfflineControls(offlineState, offlineBusy, offlinePreparing,
        onCheck = {
            if (currentOfflineOwner === offlineOwner && loaded != null && !offlineBusy && !refreshing) {
                offlineBusy = true
                prepareOffline = false
                offlineRequest++
            }
        },
        onPrepare = {
            if (currentOfflineOwner === offlineOwner && loaded != null && !offlineBusy && !refreshing &&
                offlineState?.canPrepare == true) {
                offlineBusy = true
                prepareOffline = true
                offlineRequest++
            }
        },
        onReopenActive = {
            if (currentOfflineOwner !== offlineOwner || loaded == null || offlineBusy || refreshing) false
            else {
                // Reader has already retired its old cursor. The ordinary opening
                // path rechecks the accepted edition and supplies its recovery UI.
                result = null
                update = null
                refreshWarning = null
                acceptedRecovery = null
                acceptedCandidate = null
                acceptedCandidateHash = null
                acceptedRecoveryWarning = null
                acceptedAction = AcceptedEditionAction.CHECK
                retry++
                true
            }
        })
    LaunchedEffect(offlineOwner, result, refreshing, offlineRequest) {
        val run = Any()
        offlineWork.run = run
        fun ownsRun() = currentOfflineOwner === offlineOwner && offlineWork.run === run
        val active = loaded
        if (active == null || refreshing) {
            if (ownsRun()) {
                offlineBusy = false
                offlinePreparing = false
                prepareOffline = false
            }
            return@LaunchedEffect
        }
        val shouldPrepare = prepareOffline
        prepareOffline = false
        offlineBusy = true
        offlinePreparing = shouldPrepare
        val job = currentCoroutineContext()[Job]
        try {
            val checked = if (shouldPrepare) BookOfflinePreparation.prepare(active, journey, parishId, books, readingStore) { state ->
                if (job?.isActive == true && ownsRun()) offlineState = state
            } else BookOfflinePreparation.inspect(active, journey, parishId, books, readingStore)
            currentCoroutineContext().ensureActive()
            if (ownsRun()) offlineState = checked
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (ownsRun()) offlineState = BookOfflineState(
                parts = (journey?.parts ?: listOf(active.key)).map { BookOfflinePart(it, BookOfflinePartStatus.STORAGE_ERROR) },
                partSet = journey?.effectivePartSet,
                contextError = "Не удалось проверить загрузку. Попробуйте еще раз")
        } finally {
            if (ownsRun()) {
                offlineBusy = false
                offlinePreparing = false
            }
        }
    }
    LaunchedEffect(books, requested, parishId, loaded?.contentHash, retry, refreshing) {
        if (loaded == null || refreshing || ownedRoute?.pendingSearchTarget != null ||
            (result as? BookLoadResult.Ready)?.saved == false) return@LaunchedEffect
        try {
            val candidate = books.enrichSaved(loaded) ?: return@LaunchedEffect
            currentCoroutineContext().ensureActive()
            if (!candidate.saved || candidate.book.key != requested || candidate.book.contentHash == loaded.contentHash)
                return@LaunchedEffect
            val saved = withContext(Dispatchers.IO) {
                books.cachedVersion(requested, candidate.book.contentHash) == candidate.book
            }
            currentCoroutineContext().ensureActive()
            if (saved) update = candidate
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional improvement must not interrupt offline reading. */ }
    }
    // Saved BOOK routes can reopen the same text for another church. Language and
    // visibility controls must share the same identity as the persisted reading cursor.
    if (loaded != null) key(loaded.key.canonical(), loaded.contentHash, parishId) {
        var acknowledgedMismatch by rememberSaveable { mutableStateOf<String?>(null) }
        val mismatchIdentity = BookServiceFit.polyeleosMismatchIdentity(loaded, journey, parishId)
        // Keep this remembered callback group present even before an update exists.
        // A conditional memoized lambda here remounts the reader when it first appears.
        val acceptUpdate: (BookReadingPosition) -> Boolean = { position ->
            val candidate = update
            if (candidate != null && candidate.saved && candidate.book.key == loaded.key &&
                readingStore.acceptVersion(loaded, candidate.book, parishId, position)) {
                result = candidate; update = null; refreshWarning = null
                true
            } else {
                refreshWarning = "Не удалось сохранить место. Открытый текст остался на месте"
                false
            }
        }
        if (mismatchIdentity != null && acknowledgedMismatch != mismatchIdentity) {
            BookServiceFitScreen(loaded, requireNotNull(journey), onBack,
                onChooseVariant = { changeRequest(it, savedCurrentPart = false) },
                onReadBase = { acknowledgedMismatch = mismatchIdentity })
        } else BookReaderScreen(loaded, parishId, readingStore, textSize, onBack, onOpenUrl,
            onChooseVersion = { changeRequest(it, savedCurrentPart = false) }, onSettings = onSettings,
            journeyContext = journey,
            onChoosePart = { selectPart(it, savedCurrentPart = true) },
            onChooseJourneyVersion = { changeRequest(it, savedCurrentPart = true) },
            searchCorpusLoader = if (journey != null && ownedRoute != null) {
                { BookSearchCorpusLoader.load(loaded, journey, parishId, books, readingStore) }
            } else null,
            onChooseSearchHit = { target ->
                ownedRoute?.selectSearchHit(target, savedCurrentPart = true)?.let(onRouteChanged) == true
            },
            pendingSearchTarget = ownedRoute?.pendingSearchTarget,
            onPendingSearchHandled = { target ->
                // A stale reader cannot consume another navigation request.
                ownedRoute?.takeIf { it.activeKey == loaded.key && it.pendingSearchTarget == target }
                    ?.clearPendingSearch()?.let(onRouteChanged) == true
            },
            onRefresh = { retry++ }, refreshing = refreshing,
            onAcceptUpdate = acceptUpdate.takeIf { update != null },
            offlineControls = offlineControls,
            isSaved = (result as? BookLoadResult.Ready)?.saved != false,
            churchTypeInfo = ChurchTypeClassifier.description(loaded.key.churchType, ChurchTypeClassifier.classify(ownParish)),
            loadWarning = refreshWarning ?: when (val state = result) {
                is BookLoadResult.Ready -> state.warning
                is BookLoadResult.Failed -> state.message
                else -> null
            })
    } else Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { Column(Modifier.statusBarsPadding()) { PageHeader(requested.worship.title, onBack) } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (result == null) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Text(progress.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("book_loading"))
                    Text("Сохраним текст для чтения без сети", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (acceptedRecovery != null) {
                    Text(if (acceptedRecovery == BookReadingLookup.Unreadable) "Сохраненное место недоступно"
                        else "Сохраненная редакция недоступна", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("book_accepted_edition_missing"))
                    Text((result as? BookLoadResult.Failed)?.message.orEmpty(), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("book_load_error"))
                    if (acceptedCandidate != null) {
                        Text("Загружена другая редакция. Ее можно открыть с начала. Прежнее сохраненное место пока не изменено",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("book_new_edition_notice"))
                        Button(onClick = { acceptedAction = AcceptedEditionAction.OPEN_NEW; retry++ }, enabled = !refreshing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_open_new_edition")) {
                            Text("Открыть новую редакцию")
                        }
                    } else {
                        Button(onClick = { acceptedAction = AcceptedEditionAction.DOWNLOAD; retry++ }, enabled = !refreshing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_download_edition")) {
                            Text("Загрузить текст заново")
                        }
                    }
                    OutlinedButton(onClick = {
                        acceptedAction = AcceptedEditionAction.CHECK
                        acceptedRecoveryWarning = null
                        retry++
                    }, enabled = !refreshing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_check_accepted_edition")) {
                        Text("Проверить сохраненную редакцию")
                    }
                    if (refreshing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("book_accepted_recovery_progress"))
                        Text(progress.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    acceptedRecoveryWarning?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("book_accepted_recovery_warning"))
                    }
                } else {
                    Text("Текст пока не загружен", style = MaterialTheme.typography.headlineSmall)
                    Text((result as? BookLoadResult.Failed)?.message.orEmpty(), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("book_load_error"))
                    Button(onClick = { result = null; retry++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Попробовать еще раз") }
                    recovery?.let { saved ->
                        Text("На телефоне есть текст этой службы с прежним вариантом храма", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("book_previous_variant_notice"))
                        OutlinedButton(onClick = { changeRequest(saved.key, savedCurrentPart = false) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_open_previous_variant")) {
                            Text("Открыть сохраненный текст")
                        }
                    }
                    if (requested.worship == WorshipKind.LITURGY) OutlinedButton(onClick = onOpenCommonLiturgy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Открыть общий порядок литургии") }
                }
                journey?.let { BookJourneyDescription(it, requested) }
                savedReturn?.let { previous ->
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                // Recheck on action: the accepted cache can disappear after composition.
                                val accepted = verifiedReturn(previous)
                                currentCoroutineContext().ensureActive()
                                if (accepted != null) onRouteChanged(accepted) else savedReturn = null
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { savedReturn = null }
                        }
                    },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_journey_return")) {
                        Text(if (previous.activeKey.worship == WorshipKind.VESPERS) "Вернуться к вечерне" else "Вернуться к утрене")
                    }
                }
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Вернуться к службам") }
            }
        }
    }
}
