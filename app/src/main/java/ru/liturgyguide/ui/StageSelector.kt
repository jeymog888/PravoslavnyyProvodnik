package ru.liturgyguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.liturgyguide.service.StageMatcher
import ru.liturgyguide.service.PrayerTrackFactory
import ru.liturgyguide.model.ReadingDay
import ru.liturgyguide.service.TextSearch
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch

internal fun normalizedSearch(value: String): String = TextSearch.normalize(value)

@Composable
fun StageSelector(stageMatcher: StageMatcher, selectedId: String?, searchMode: Boolean, readingDay: ReadingDay?,
    onSelect: (String, String?) -> Unit, onSearch: () -> Unit, onBack: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var showListening by rememberSaveable { mutableStateOf(false) }
    val stages = stageMatcher.getAllStages()
    val listState = rememberLazyListState(
        // Item 0 is the order heading and the fallback when there is no selected stage.
        initialFirstVisibleItemIndex = if (searchMode) 0 else stages.indexOfFirst { it.id == selectedId } + 1
    )
    val scope = rememberCoroutineScope()
    fun updateQuery(value: String) {
        query = value
        // Reset only after an explicit edit, leaving restored browsing positions intact.
        scope.launch { listState.scrollToItem(0) }
    }
    val filtered = remember(query, stages, searchMode, readingDay) {
        val words = if (searchMode) normalizedSearch(query).split(' ').filter { it.isNotEmpty() } else emptyList()
        stages.filter { stage ->
            val text = normalizedSearch(listOf(stage.name, stage.shortTitle, stage.description, stage.meaning,
                stage.cue.orEmpty(), stage.listenFor.joinToString(" "), PrayerTrackFactory.build(stage, readingDay).joinToString(" ") { track -> track.title + " " + track.lines.joinToString(" ") { "${it.russian} ${it.original.orEmpty()}" } }).joinToString(" "))
            words.all { text.contains(it) }
        }
    }
    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding()) {
            PageHeader(if (searchMode) "Найти момент" else "Порядок службы", onBack) {
                if (!searchMode) IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Найти по словам") }
            }
            if (searchMode) {
                OutlinedTextField(value = query, onValueChange = ::updateQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("stage_search"),
                    label = { Text("Слова, которые слышите") }, placeholder = { Text("Например: херувимы") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { updateQuery("") }) { Icon(Icons.Default.Close, "Очистить поиск") } },
                    singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.height(12.dp))
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("stage_list"),
            state = listState,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            if (searchMode && query.isBlank()) item {
                TextButton(onClick = { showListening = !showListening }, modifier = Modifier.fillMaxWidth().testTag("open_listening")) {
                    Icon(Icons.Default.MicNone, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (showListening) "Скрыть аудиоподсказку" else "Подсказка по звуку")
                }
                if (showListening) ListeningCard(stages, onSelect)
                Text("Или выберите знакомый момент ниже", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!searchMode) item {
                Text("Литургия Иоанна Златоуста", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                Text("${stages.map { it.section }.distinct().size} разделов · ${stages.size} этапов", Modifier.padding(top = 6.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (searchMode && query.isNotBlank() && filtered.isNotEmpty()) item {
                Text("Найдено: ${filtered.size}", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (filtered.isEmpty()) item {
                Column(Modifier.padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Пока не нашли эти слова", style = MaterialTheme.typography.titleLarge)
                    Text("Попробуйте одно слово: Евангелие, верую или причащение", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { updateQuery(""); keyboard?.hide(); focus.clearFocus() }) { Text("Показать все этапы") }
                }
            }
            itemsIndexed(filtered, key = { _, stage -> stage.id }) { index, stage ->
                val current = stage.id == selectedId
                if (query.isBlank() && (index == 0 || filtered[index - 1].section != stage.section)) {
                    Text(stage.section, Modifier.padding(top = 28.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge)
                }
                Surface(color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                    shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().clickable { keyboard?.hide(); focus.clearFocus(); onSelect(stage.id, if (searchMode) query else null) }.testTag("stage_${stage.id}")
                        .semantics { selected = current }.padding(horizontal = 12.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).background(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                            CircleShape), contentAlignment = Alignment.Center) {
                            Text(stage.order.toString(), style = MaterialTheme.typography.bodySmall,
                                color = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(stage.name, style = MaterialTheme.typography.titleMedium)
                            if (current) Text("Выбранный этап", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            if (searchMode) Text(stage.cue ?: stage.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (current) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!current) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
    }
}
