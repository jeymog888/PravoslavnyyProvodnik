package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.book.BookJourneyContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** No reader is mounted here: merely inspecting a candidate cannot accept its reading position. */
@Composable
internal fun BookServiceFitScreen(book: ServiceBook, journey: BookJourneyContext, onBack: () -> Unit,
    onChooseVariant: (ServiceBookKey) -> Boolean, onReadBase: () -> Unit) {
    var choosingMemory by rememberSaveable(book.key.canonical(), book.contentHash, journey.civilDate, journey.time, journey.title) {
        mutableStateOf(false)
    }
    var selectionError by remember(book.key, book.contentHash) { mutableStateOf(false) }
    val options = remember(book.mainEventOptions) {
        book.mainEventOptions.filter { it.value.matches(Regex("[0-9]{1,6}")) && it.title.isNotBlank() }
            .distinctBy { it.value }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { Column(Modifier.statusBarsPadding()) {
            PageHeader(if (choosingMemory) "Память службы" else "Уточнить службу",
                if (choosingMemory) ({ choosingMemory = false }) else onBack)
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(24.dp).testTag("book_service_fit"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("В расписании указан полиелей", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("book_service_fit_title"))
            Text(LocalDate.parse(journey.civilDate).format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))) +
                " · " + journey.time, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Загруженный вариант не подтверждает этот порядок. Его песнопения могут отличаться от службы в храме",
                style = MaterialTheme.typography.bodyLarge)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("В загруженном тексте", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(book.title, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("book_service_fit_book_title"))
                    book.selectedEventTitle?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("book_service_fit_memory"))
                    }
                }
            }
            if (selectionError) Text("Не удалось открыть этот вариант. Текущий текст остался на месте",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("book_service_fit_error"))
            if (choosingMemory) {
                Text("Выберите память, которую сейчас служат в храме. Ниже - варианты из источника текста; календарь сам по себе не подтверждает выбор прихода",
                    style = MaterialTheme.typography.bodyMedium)
                options.forEach { option ->
                    OutlinedButton(onClick = {
                        val next = book.key.copy(mainEvent = option.value, secondEvent = null)
                        selectionError = !onChooseVariant(next)
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .testTag("book_service_fit_variant_${option.value}")) { Text(option.title) }
                }
            } else {
                if (options.isNotEmpty()) Button(onClick = { choosingMemory = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_service_fit_choose_memory")) {
                    Text("Выбрать память службы")
                } else Text("В загруженном источнике нет других вариантов памяти на эту дату",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onReadBase,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("book_service_fit_read_base")) {
                    Text("Читать базовый текст")
                }
            }
        }
    }
}
