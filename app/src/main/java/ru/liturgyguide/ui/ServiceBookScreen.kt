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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.*

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
    LaunchedEffect(books, requested, parishId, retry) {
        refreshing = true
        try {
            val pinned = withContext(Dispatchers.IO) {
                readingStore.positionFor(requested, parishId)?.let { books.cachedVersion(requested, it.contentHash) }
            }
            if (retry == 0 && pinned != null) {
                result = BookLoadResult.Ready(pinned, fromCache = true, warning = BookWarnings.supplementWarning(pinned))
                // A downloaded update remains available after leaving the reader or going offline.
                // Keep the accepted text fixed until the reader explicitly chooses the new version.
                val latest = withContext(Dispatchers.IO) { books.cached(requested) }
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
            if (result == null) result = BookLoadResult.Failed(BookFailure.NETWORK, "Не удалось открыть службу. Попробуйте еще раз")
            else refreshWarning = "Не удалось обновить. Открытая книга осталась на месте"
        } finally { refreshing = false }
    }
    LaunchedEffect(books, requested, parishId, ownParish, result, refreshing) {
        recovery = null
        val failed = result as? BookLoadResult.Failed
        if (!refreshing && failed != null && failed.cachedBook == null &&
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
    LaunchedEffect(books, requested, parishId, loaded?.contentHash, retry, refreshing) {
        if (loaded == null || refreshing || (result as? BookLoadResult.Ready)?.saved == false) return@LaunchedEffect
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
            onRefresh = { retry++ }, refreshing = refreshing,
            onAcceptUpdate = acceptUpdate.takeIf { update != null },
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
