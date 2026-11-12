package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.liturgyguide.service.schedule.ScheduleUrl

private class ScheduleSourceSession {
    var active by mutableStateOf(true)
    fun close() { active = false }
}

/** One short editor for both entry screens. Its draft belongs to the displayed church. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSourceSheet(churchId: String, churchName: String, initialUrl: String,
    loading: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var input by rememberSaveable(churchId) { mutableStateOf(initialUrl) }
    var invalid by rememberSaveable(churchId) { mutableStateOf(false) }
    // This permission belongs to this mounted editor, not to the saved draft or church ID.
    // Cancel, disposal and a valid submission all consume it before invoking the parent.
    val session = remember(churchId) { ScheduleSourceSession() }
    DisposableEffect(session) { onDispose { session.close() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (session.active) {
            val normalized = ScheduleUrl.publicSource(input)
            if (normalized == null) invalid = true else {
                session.close()
                keyboard?.hide()
                onSubmit(normalized)
            }
        }
    }
    val dismiss = {
        if (session.active) {
            session.close()
            keyboard?.hide()
            onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GuideSheetSystemBars()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).imePadding().testTag("schedule_source_content"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("Сайт храма", style = MaterialTheme.typography.headlineSmall) }
            item { Text(churchName, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("schedule_source_church")) }
            item {
                OutlinedTextField(value = input, onValueChange = { input = it.take(2048); invalid = false },
                    label = { Text("Ссылка") }, singleLine = true, isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    supportingText = { Text(if (invalid) "Укажите полный адрес сайта, начиная с https:// или http://"
                        else if (loading) "Проверим эту ссылку вместо автоматического поиска"
                        else "Вставьте ссылку на сайт именно этого храма") },
                    modifier = Modifier.fillMaxWidth().testTag("schedule_website_input"))
            }
            item {
                Button(onClick = submit, enabled = session.active,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("save_schedule_website")) {
                    Text("Проверить расписание")
                }
            }
            item {
                OutlinedButton(onClick = dismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cancel_schedule_website")) {
                    Text("Отмена")
                }
            }
        }
    }
}
