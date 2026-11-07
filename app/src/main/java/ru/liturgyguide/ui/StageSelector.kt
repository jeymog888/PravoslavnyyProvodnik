package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.liturgyguide.service.StageMatcher
import java.text.Normalizer

internal fun normalizedSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").lowercase().replace('ё', 'е').replace(Regex("[^а-яa-z0-9]+"), " ").trim()

@Composable
fun StageSelector(stageMatcher: StageMatcher, selectedId: String?, onSelect: (String) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val stages = stageMatcher.getAllStages()
    val filtered = remember(query, stages) {
        val words = normalizedSearch(query).split(' ').filter { it.isNotEmpty() }
        stages.filter { stage ->
            val text = normalizedSearch(listOf(stage.name, stage.shortTitle, stage.description, stage.meaning, stage.cue.orEmpty(), stage.listenFor.joinToString(" "), stage.texts.joinToString(" ") { "${it.original} ${it.translation}" }).joinToString(" "))
            words.all { text.contains(it) }
        }
    }
    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding()) {
            PageHeader("Найти этап службы", onBack)
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("stage_search"),
                label = { Text("Какие слова вы слышите?") },
                placeholder = { Text("Например: херувимы") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить поиск") } },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.height(12.dp))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("stage_list"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (query.isBlank()) item { ListeningCard(stages, onSelect) }
            item {
                Text(if (query.isBlank()) "Можно выбрать по словам или действиям. Этапы расположены по порядку службы." else "Найдено этапов: ${filtered.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (filtered.isEmpty()) item {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Пока не нашли эти слова", style = MaterialTheme.typography.titleLarge)
                    Text("Попробуйте одно знакомое слово, например: Евангелие, верую или причащение. Можно также выбрать этап из полного списка.")
                    OutlinedButton(onClick = { query = "" }) { Text("Показать все этапы") }
                }
            }
            itemsIndexed(filtered, key = { _, stage -> stage.id }) { index, stage ->
                val selected = stage.id == selectedId
                if (query.isBlank() && (index == 0 || filtered[index - 1].section != stage.section)) {
                    Eyebrow(stage.section.uppercase(), Modifier.padding(top = 16.dp, bottom = 12.dp))
                }
                OutlinedCard(onClick = { onSelect(stage.id) }, modifier = Modifier.fillMaxWidth().testTag("stage_${stage.id}"), colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("${stageMatcher.getStageNumber(stage).toString().padStart(2, '0')} / ${stages.size}", Modifier.weight(1f))
                            if (selected) Icon(Icons.Default.Check, "Выбранный этап", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            else Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                        Text(stage.name, style = MaterialTheme.typography.titleLarge)
                        Text(stage.cue ?: stage.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
