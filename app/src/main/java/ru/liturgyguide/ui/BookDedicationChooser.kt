package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.parser.Parser
import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.service.book.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private class DedicationChoiceWork {
    var active = true
    var token: Any? = null
    var job: Job? = null
}

internal fun dedicationDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru")))
}.getOrDefault(date)

private fun dedicationDayUrl(proof: PatronalEventProof) = "https://azbyka.ru/days/${proof.calendarDate}"

/** Discovery never changes a parish choice. Only a confirmed event can be opened. */
@Composable
internal fun BookDedicationChooser(services: BookDedicationServices, parishId: String, parishTitle: String,
    calendarYear: Int, expectedProfile: PatronalProfileLookup,
    onOpen: suspend (PatronalEventProof, PatronalProfileLookup) -> BookDedicationStart,
    onDismiss: () -> Unit, onOpenUrl: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val work = remember(services, parishId, calendarYear, expectedProfile) { DedicationChoiceWork() }
    val latestOnOpen by rememberUpdatedState(onOpen)
    var query by rememberSaveable(parishId, calendarYear) { mutableStateOf("") }
    var catalog by remember(services) { mutableStateOf<PatronalCatalog?>(null) }
    var loading by remember(services) { mutableStateOf(true) }
    var catalogError by remember(services) { mutableStateOf<String?>(null) }
    var catalogAttempt by remember(services) { mutableIntStateOf(0) }
    var selectedEntry by remember(work) { mutableStateOf<PatronalCatalogEntry?>(null) }
    var resolution by remember(work) { mutableStateOf<PatronalResolution?>(null) }
    var confirmed by remember(work) { mutableStateOf<PatronalEventProof?>(null) }
    var resolving by remember(work) { mutableStateOf(false) }
    var opening by remember(work) { mutableStateOf(false) }
    var openError by remember(work) { mutableStateOf<String?>(null) }
    var showSource by remember(work) { mutableStateOf(false) }
    val unreadable = expectedProfile == PatronalProfileLookup.Unreadable
    val matches = remember(catalog, query, calendarYear) { catalog?.search(query, calendarYear, limit = 20).orEmpty() }

    DisposableEffect(work) {
        onDispose { work.active = false; work.token = null; work.job?.cancel() }
    }
    LaunchedEffect(services, parishId, unreadable, catalogAttempt) {
        if (unreadable) { loading = false; return@LaunchedEffect }
        loading = true
        catalogError = null
        try {
            val loaded = withContext(Dispatchers.IO) { services.loadCatalog() }
            currentCoroutineContext().ensureActive()
            catalog = loaded
            if (loaded == null) catalogError = "Не удалось открыть список имен и праздников на телефоне. Попробуйте еще раз"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { catalogError = "Не удалось открыть список имен и праздников на телефоне. Попробуйте еще раз" }
        finally { loading = false }
    }
    fun cancelChoice() {
        work.token = null
        work.job?.cancel()
        work.job = null
        selectedEntry = null
        resolution = null
        confirmed = null
        resolving = false
        opening = false
        openError = null
        showSource = false
    }
    fun select(entry: PatronalCatalogEntry) {
        cancelChoice()
        keyboard?.hide()
        val token = Any()
        work.token = token
        selectedEntry = entry
        resolving = true
        work.job = scope.launch {
            try {
                val found = services.resolver.resolve(entry, calendarYear)
                currentCoroutineContext().ensureActive()
                if (work.active && work.token === token) {
                    resolution = found
                    confirmed = (found as? PatronalResolution.Confirmed)?.proof
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (work.active && work.token === token) resolution = PatronalResolution.Unavailable(
                    "Не удалось проверить календарь. Проверьте связь и попробуйте еще раз", retryable = true)
            } finally {
                if (work.active && work.token === token) resolving = false
            }
        }
    }
    fun open(proof: PatronalEventProof) {
        if (opening || resolving || unreadable || confirmed != proof) return
        val token = work.token ?: return
        opening = true
        openError = null
        keyboard?.hide()
        work.job = scope.launch {
            try {
                currentCoroutineContext().ensureActive()
                if (!work.active || work.token !== token || confirmed != proof) return@launch
                val started = latestOnOpen(proof, expectedProfile)
                currentCoroutineContext().ensureActive()
                if (work.active && work.token === token) openError = when (started) {
                    BookDedicationStart.OPENED -> null
                    BookDedicationStart.STALE_ROUTE -> "Открытый текст или выбор храма изменился. Вернитесь к чтению и откройте выбор еще раз"
                    BookDedicationStart.STORAGE_FAILED -> "Не удалось сохранить место чтения. Прежний текст остался на месте. Попробуйте еще раз"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (work.active && work.token === token) openError = "Не удалось открыть выбранный текст. Попробуйте еще раз"
            } finally {
                if (work.active && work.token === token) opening = false
            }
        }
    }

    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).safeDrawingPadding().imePadding()
        .testTag("book_dedication_chooser"), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Песнопения храма", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text("Найдите святого или праздник из посвящения храма", style = MaterialTheme.typography.bodyMedium)
                Text(parishTitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (unreadable) item {
            Text("Сохраненный выбор для этого храма недоступен. Сначала проверьте его в сведениях о службе",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("book_dedication_profile_error"))
        } else {
            item {
                OutlinedTextField(value = query, onValueChange = { value ->
                    cancelChoice()
                    query = value.take(200)
                }, label = { Text("Имя или икона") }, placeholder = { Text("Например, Николай или Троица") },
                    trailingIcon = if (query.isNotEmpty()) { {
                        IconButton(onClick = { cancelChoice(); query = "" }, modifier = Modifier.size(48.dp)
                            .testTag("book_dedication_clear")) { Icon(Icons.Default.Close, "Очистить поиск") }
                    } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }), maxLines = 3,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("book_dedication_query"))
            }
            if (loading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Открываем список имен и праздников", style = MaterialTheme.typography.bodyMedium)
            }
            catalogError?.let { message -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { catalogAttempt++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("book_dedication_catalog_retry")) { Text("Попробовать еще раз") }
                }
            } }
            if (!loading && catalogError == null) item {
                Text(if (query.isBlank()) "Введите имя святого, праздник или название иконы"
                    else if (matches.isEmpty()) "Ничего не найдено. Попробуйте другое имя или название"
                    else "Праздники в календаре на $calendarYear год", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("book_dedication_results_status"))
            }
            items(matches, key = { it.entry.mineaId }) { match ->
                val isSelected = selectedEntry?.mineaId == match.entry.mineaId
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { select(match.entry) }, enabled = !opening,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("book_dedication_result_${match.entry.mineaId}").semantics { selected = isSelected }) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(match.entry.displayTitle, maxLines = if (isSelected) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(match.dateCandidates.joinToString(" или ") { dedicationDate(it.toString()) }
                                .ifEmpty { "Дату уточнит календарь" }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (isSelected) {
                        if (resolving) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("book_dedication_resolving"))
                            Text("Проверяем выбранный праздник в календаре", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            TextButton(onClick = ::cancelChoice, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("book_dedication_cancel")) { Text("Отменить проверку") }
                        }
                        val unavailable = resolution as? PatronalResolution.Unavailable
                        if (unavailable != null) {
                            Text(unavailable.message, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("book_dedication_unavailable").semantics { liveRegion = LiveRegionMode.Polite })
                            if (unavailable.retryable) OutlinedButton(onClick = { select(match.entry) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_retry")) {
                                Text("Проверить еще раз")
                            }
                        }
                        val ambiguous = resolution as? PatronalResolution.Ambiguous
                        if (ambiguous != null && confirmed == null) {
                            Text("Выберите событие, найденное в календаре", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("book_dedication_ambiguous"))
                            ambiguous.choices.forEach { proof ->
                                OutlinedButton(onClick = { confirmed = proof; openError = null },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .testTag("book_dedication_event_${proof.id}")) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(proof.title, style = MaterialTheme.typography.titleMedium)
                                        Text(dedicationDate(proof.calendarDate), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        confirmed?.let { proof ->
                            Column(Modifier.fillMaxWidth().testTag("book_dedication_confirmed"),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Событие найдено в календаре", style = MaterialTheme.typography.labelLarge)
                                Text(proof.title, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.testTag("book_dedication_confirmed_title"))
                                Text("Дата праздника: ${dedicationDate(proof.calendarDate)}")
                                Text("Разные праздники одного святого могут иметь разные тексты",
                                    style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { open(proof) }, enabled = !opening,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_open")) {
                                    Text(if (opening) "Открываем текст..." else "Открыть текст")
                                }
                                openError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("book_dedication_open_error").semantics { liveRegion = LiveRegionMode.Polite }) }
                                TextButton(onClick = { onOpenUrl(dedicationDayUrl(proof)) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_day_source")) {
                                    Text("Открыть праздник в календаре")
                                }
                            }
                        }
                        TextButton(onClick = { showSource = !showSource },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_source_details")) {
                            Text("Название в Минее и источник")
                        }
                        if (showSource) {
                            Text(Parser.unescapeEntities(match.entry.sourceTitle, false), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { onOpenUrl(match.entry.sourceUrl) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_minea_source")) {
                                Text("Открыть статью Минеи")
                            }
                        }
                    }
                }
            }
            if (matches.isNotEmpty()) item {
                Text("Показаны ближайшие совпадения. Если нужного праздника нет, уточните имя или название иконы",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("book_dedication_results_end"))
            }
        }
        item {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("book_dedication_close")) { Text("Вернуться к чтению") }
        }
    }
}

@Composable
internal fun BookDedicationSourceSection(controls: BookDedicationControls, onChoose: () -> Unit,
    onReturn: () -> Unit, returning: Boolean = false, actionError: String? = null,
    onOpenUrl: (String) -> Unit = {}) {
    Column(Modifier.fillMaxWidth().testTag("book_dedication_details"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Песнопения храма", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (controls.busy || returning) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("book_dedication_progress"))
        val proof = controls.currentSource
        if (proof == null) Text("Песнопения для конкретного святого или праздника не выбраны", style = MaterialTheme.typography.bodyMedium)
        else {
            val saved = (controls.expectedProfile as? PatronalProfileLookup.Found)?.proof
            val remembered = saved != null && saved.id == proof.id && saved.mineaId == proof.mineaId && saved.churchType == proof.churchType
            Text(proof.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("book_dedication_current_source"))
            Text("Праздник в источнике: ${dedicationDate(proof.calendarDate)}", style = MaterialTheme.typography.bodyMedium)
            Text(if (remembered) "Этот вариант используется для новых служб этого храма" else "Вариант открыт для этой службы",
                style = MaterialTheme.typography.bodyMedium)
            if (controls.onRememberSource != null) Text(
                "Запомните вариант, если он соответствует посвящению храма. Разные праздники одного святого могут иметь разные тексты",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onOpenUrl(dedicationDayUrl(proof)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_current_day")) {
                Text("Открыть праздник в календаре")
            }
        }
        if (controls.expectedProfile == PatronalProfileLookup.Unreadable) Text(
            "Сохраненный выбор для этого храма недоступен. Проверьте его еще раз, прежде чем менять текст",
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("book_dedication_profile_error"))
        (listOfNotNull(controls.message, actionError)).distinct().forEach {
            Text(it, color = if (it == actionError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("book_dedication_profile_message").semantics { liveRegion = LiveRegionMode.Polite })
        }
        controls.onRetryProfile?.let { retry ->
            OutlinedButton(onClick = retry, enabled = !controls.busy && !returning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_profile_retry")) {
                Text("Проверить сохраненный выбор")
            }
        }
        controls.onRememberSource?.let { remember ->
            OutlinedButton(onClick = remember, enabled = !controls.busy && !returning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_remember")) {
                Text("Запомнить для этого храма")
            }
        }
        OutlinedButton(onClick = onChoose,
            enabled = !controls.busy && !returning && controls.expectedProfile != PatronalProfileLookup.Unreadable,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_choose")) {
            Text(if (proof == null) "Выбрать песнопения" else "Выбрать другие песнопения")
        }
        if (controls.onReturn != null) OutlinedButton(onClick = onReturn, enabled = !controls.busy && !returning,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("book_dedication_return")) {
            Text(if (returning) "Возвращаем прежний текст..." else "Вернуться к прежнему тексту")
        }
        Text("Выбор уточняет храмовые песнопения. Порядок и сокращения в храме могут отличаться",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
