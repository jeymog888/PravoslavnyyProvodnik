package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    onOpenCommonLiturgy: () -> Unit) {
    val ownParish = parish?.takeIf { it.id == parishId }
    var requested by remember(initialKey, parishId) { mutableStateOf(initialKey) }
    var retry by remember(requested) { mutableIntStateOf(0) }
    var result by remember(requested, parishId) { mutableStateOf<BookLoadResult?>(null) }
    var update by remember(requested, parishId) { mutableStateOf<BookLoadResult.Ready?>(null) }
    var refreshing by remember(requested, parishId) { mutableStateOf(false) }
    var refreshWarning by remember(requested, parishId) { mutableStateOf<String?>(null) }
    var progress by remember(requested) { mutableStateOf(BookLoadProgress.SOURCE) }
    var recovery by remember(requested, parishId) { mutableStateOf<ServiceBook?>(null) }
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
                val accepted = (result as? BookLoadResult.Ready)?.book ?: (result as? BookLoadResult.Failed)?.cachedBook
                if (accepted != null && retry > 0) when (fetched) {
                    is BookLoadResult.Ready -> {
                        if (fetched.book.contentHash != accepted.contentHash) update = fetched
                        else refreshWarning = fetched.warning ?: "На телефоне актуальная версия текста"
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
    // Saved BOOK routes can reopen the same text for another church. Language and
    // visibility controls must share the same identity as the persisted reading cursor.
    if (loaded != null) key(loaded.key.canonical(), loaded.contentHash, parishId) {
        BookReaderScreen(loaded, parishId, readingStore, textSize, onBack, onOpenUrl,
            onChooseVersion = { requested = it; onRequestChanged(it) }, onSettings = onSettings,
            onRefresh = { retry++ }, refreshing = refreshing,
            onAcceptUpdate = if (update == null) null else { {
                val candidate = update
                if (candidate != null && readingStore.acceptVersion(loaded, candidate.book, parishId)) {
                    result = candidate; update = null; refreshWarning = null
                    true
                } else {
                    refreshWarning = "Не удалось сохранить место. Открытый текст остался на месте"
                    false
                }
            } },
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
                        OutlinedButton(onClick = { requested = saved.key; onRequestChanged(saved.key) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_open_previous_variant")) {
                            Text("Открыть сохраненный текст")
                        }
                    }
                    if (requested.worship == WorshipKind.LITURGY) OutlinedButton(onClick = onOpenCommonLiturgy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Открыть общий порядок литургии") }
                }
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Вернуться к службам") }
            }
        }
    }
}
