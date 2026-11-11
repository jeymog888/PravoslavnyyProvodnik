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
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.*

/** Owns a single requested book. A cancelled response can never replace another route's text. */
@Composable
fun ServiceBookScreen(initialKey: ServiceBookKey, parishId: String?, books: ServiceBooks,
    readingStore: BookReadingStore, textSize: Int, onBack: () -> Unit, onSettings: () -> Unit,
    onOpenUrl: (String) -> Unit, onRequestChanged: (ServiceBookKey) -> Unit = {}, onOpenCommonLiturgy: () -> Unit) {
    var requested by remember(initialKey, parishId) { mutableStateOf(initialKey) }
    var retry by remember(requested) { mutableIntStateOf(0) }
    var result by remember(requested, parishId) { mutableStateOf<BookLoadResult?>(null) }
    var update by remember(requested, parishId) { mutableStateOf<BookLoadResult.Ready?>(null) }
    var refreshing by remember(requested, parishId) { mutableStateOf(false) }
    var refreshWarning by remember(requested, parishId) { mutableStateOf<String?>(null) }
    var progress by remember(requested) { mutableStateOf(BookLoadProgress.SOURCE) }
    LaunchedEffect(books, requested, parishId, retry) {
        refreshing = true
        try {
            val pinned = withContext(Dispatchers.IO) {
                readingStore.last(parishId)?.takeIf { it.key == requested }?.let { books.cachedVersion(requested, it.contentHash) }
            }
            if (retry == 0 && pinned != null) result = BookLoadResult.Ready(pinned, fromCache = true,
                warning = BookWarnings.supplementWarning(pinned))
            else {
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
    val loaded = when (val state = result) {
        is BookLoadResult.Ready -> state.book
        is BookLoadResult.Failed -> state.cachedBook
        else -> null
    }
    if (loaded != null) key(loaded.contentHash) {
        BookReaderScreen(loaded, parishId, readingStore, textSize, onBack, onOpenUrl,
            onChooseVersion = { requested = it; onRequestChanged(it) }, onSettings = onSettings,
            onRefresh = { retry++ }, refreshing = refreshing,
            onAcceptUpdate = if (update == null) null else { { result = update; update = null; refreshWarning = null } },
            isSaved = (result as? BookLoadResult.Ready)?.saved != false,
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
                    if (requested.worship == WorshipKind.LITURGY) OutlinedButton(onClick = onOpenCommonLiturgy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Открыть общий порядок литургии") }
                }
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Вернуться к службам") }
            }
        }
    }
}
