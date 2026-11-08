package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.UserServiceStart
import ru.liturgyguide.service.UserServiceStartCodec
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

private val personalDateFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

/** No normalization: an impossible date or ambiguous local time must remain visible for correction. */
internal fun parsePersonalDate(value: String): LocalDate? =
    if (Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}").matches(value))
        runCatching { LocalDate.parse(value, personalDateFormat).takeIf { it.year in 1..9999 } }.getOrNull()
    else null

internal fun personalStartForDate(starts: List<UserServiceStart>, church: Parish, date: LocalDate?): UserServiceStart? =
    starts.filter { it.parishId == church.id && it.date == date?.toString() }
        .mapNotNull { UserServiceStartCodec.validatedFor(it, church) }.distinct().singleOrNull()

/** An offset is shown only for the exact, unambiguous local start, never guessed at noon. */
internal fun parishTimeZoneLabel(church: Parish, date: LocalDate, time: String? = null): String {
    val zone = runCatching { ZoneId.of(church.timezone) }.getOrNull() ?: return "Часовой пояс храма не указан"
    val localTime = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    val offset = localTime?.let { zone.rules.getValidOffsets(date.atTime(it)).singleOrNull() }
        ?.id?.let { if (it == "Z") "+00:00" else it }
    return if (offset != null) "Местное время храма · UTC$offset" else "Часовой пояс храма: ${zone.id}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonalServiceTimeForm(
    church: Parish,
    currentDate: LocalDate,
    initialDate: LocalDate = currentDate,
    userStarts: List<UserServiceStart>,
    onSave: (UserServiceStart) -> Boolean,
    onRemove: ((LocalDate) -> Boolean)? = null,
    onCancel: () -> Unit
) {
    // The absolute date stays fixed if midnight arrives while this sheet is open.
    var dateInput by rememberSaveable(church.id, initialDate.toString()) { mutableStateOf(initialDate.format(personalDateFormat)) }
    var customDate by rememberSaveable(church.id, initialDate.toString()) {
        mutableStateOf(initialDate != currentDate && initialDate != currentDate.plusDays(1))
    }
    var timeInput by rememberSaveable(church.id, initialDate.toString()) {
        mutableStateOf(personalStartForDate(userStarts, church, initialDate)?.time.orEmpty())
    }
    var error by rememberSaveable(church.id, initialDate.toString()) { mutableStateOf<String?>(null) }
    val date = parsePersonalDate(dateInput)
    val existing = personalStartForDate(userStarts, church, date)
    fun changeDate(value: String, custom: Boolean) {
        val newDate = parsePersonalDate(value)
        if (newDate != date) timeInput = personalStartForDate(userStarts, church, newDate)?.time.orEmpty()
        dateInput = value
        customDate = custom
        error = null
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
        .padding(horizontal = 24.dp).padding(bottom = 32.dp).testTag("personal_time_form"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Время литургии", style = MaterialTheme.typography.headlineMedium)
        Text(church.shortName, style = MaterialTheme.typography.titleMedium)
        Text("Укажите начало, которое вы узнали в храме. Оно сохранится только для выбранной даты и поможет найти примерный этап службы",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !customDate && date == currentDate,
                onClick = { changeDate(currentDate.format(personalDateFormat), false) }, label = { Text("Сегодня") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("personal_date_today"))
            FilterChip(selected = !customDate && date == currentDate.plusDays(1),
                onClick = { changeDate(currentDate.plusDays(1).format(personalDateFormat), false) }, label = { Text("Завтра") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("personal_date_tomorrow"))
            FilterChip(selected = customDate, onClick = { customDate = true; error = null }, label = { Text("Другая дата") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("personal_date_other"))
        }
        if (customDate) OutlinedTextField(dateInput, { changeDate(it.take(32), true) },
            label = { Text("Дата") }, supportingText = { Text("Например, 09.11.2026") },
            singleLine = true, isError = error != null && date == null,
            modifier = Modifier.fillMaxWidth().testTag("personal_date_input"))
        if (date != null) Text(date.format(DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", Locale.forLanguageTag("ru"))),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("personal_date_preview"))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(parishTimeZoneLabel(church, date ?: initialDate, timeInput), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("personal_time_zone"))
                OutlinedTextField(timeInput, { timeInput = it.take(32); error = null },
                    label = { Text("Начало литургии") }, supportingText = { Text("В формате 09:30") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("personal_time_input"))
            }
        }
        Text("Опубликованное расписание останется отдельно. Для подсказки на эту дату будет использоваться ваше время",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("personal_time_error")) }
        Button(onClick = {
            if (date == null) error = "Введите существующую дату в формате ДД.ММ.ГГГГ"
            else {
                val record = UserServiceStart(church.id, date.toString(), timeInput)
                when {
                    UserServiceStartCodec.validated(record) == null -> error = "Введите время от 00:00 до 23:59, например 09:30"
                    UserServiceStartCodec.validatedFor(record, church) == null -> error = "Это время нельзя однозначно определить в часовом поясе храма. Проверьте дату и время"
                    !onSave(record) -> error = "Не удалось сохранить время. Проверьте свободное место; можно хранить до 100 дат"
                }
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("personal_time_save")) {
            Text("Сохранить время")
        }
        if (existing != null && onRemove != null && date != null) {
            TextButton(onClick = {
                if (!onRemove(date)) error = "Не удалось убрать время. Попробуйте еще раз"
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_time_remove")) { Text("Убрать мое время на эту дату") }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("personal_time_cancel")) { Text("Отмена") }
    }
}
