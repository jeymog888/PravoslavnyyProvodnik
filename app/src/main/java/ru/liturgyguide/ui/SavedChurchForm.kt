package ru.liturgyguide.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.liturgyguide.service.ChurchLocator
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.SavedChurchCodec
import ru.liturgyguide.service.SavedChurchOrigin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val russianZones = listOf(
    "Europe/Kaliningrad" to "Калининград", "Europe/Moscow" to "Москва", "Europe/Samara" to "Самара",
    "Asia/Yekaterinburg" to "Екатеринбург", "Asia/Omsk" to "Омск", "Asia/Krasnoyarsk" to "Красноярск",
    "Asia/Irkutsk" to "Иркутск", "Asia/Yakutsk" to "Якутск", "Asia/Vladivostok" to "Владивосток",
    "Asia/Magadan" to "Магадан", "Asia/Kamchatka" to "Камчатка"
)

@Composable
internal fun SavedChurchForm(onCancel: () -> Unit, onSave: (SavedChurchRecord) -> ChurchSelectionResult, initialRecord: SavedChurchRecord? = null) {
    // A failed selection can follow a successful directory save. Retrying must update that same record.
    val draftId = rememberSaveable(initialRecord?.id) { initialRecord?.id ?: "custom:${UUID.randomUUID()}" }
    var name by rememberSaveable(initialRecord?.id) { mutableStateOf(initialRecord?.name.orEmpty()) }
    var city by rememberSaveable(initialRecord?.id) { mutableStateOf(initialRecord?.city.orEmpty()) }
    var address by rememberSaveable(initialRecord?.id) { mutableStateOf(initialRecord?.address.orEmpty()) }
    var timezone by rememberSaveable(initialRecord?.id) { mutableStateOf(initialRecord?.timezone ?: ZoneId.systemDefault().id) }
    var otherZone by rememberSaveable(initialRecord?.id) { mutableStateOf(false) }
    var zoneMenu by remember(initialRecord?.id) { mutableStateOf(false) }
    var latitude by rememberSaveable(initialRecord?.id) { mutableStateOf(initialRecord?.latitude) }
    var longitude by rememberSaveable(initialRecord?.id) { mutableStateOf(initialRecord?.longitude) }
    var accuracy by rememberSaveable(initialRecord?.id) { mutableStateOf(initialRecord?.locationAccuracyMeters) }
    var error by rememberSaveable(initialRecord?.id) { mutableStateOf<String?>(null) }
    val imported = initialRecord?.origin == SavedChurchOrigin.OPEN_STREET_MAP
    var editDetails by rememberSaveable(initialRecord?.id) { mutableStateOf(!imported) }
    var zoneConfirmed by rememberSaveable(initialRecord?.id) { mutableStateOf(!imported) }
    val zone = runCatching { ZoneId.of(timezone.trim()) }.getOrNull()
    val cityRequired = initialRecord?.origin != SavedChurchOrigin.OPEN_STREET_MAP

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp)
        .padding(bottom = 32.dp).testTag("add_church_form"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (imported) "Проверьте место" else "Добавить свой храм", style = MaterialTheme.typography.headlineMedium)
        Text("Место сохранится на этом телефоне. Расписание нужно уточнить отдельно", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (imported && !editDetails) {
            Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("import_church_name"))
            listOf(city, address).filter(String::isNotBlank).distinct().joinToString(" · ").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("import_church_address"))
            }
            TextButton(onClick = { editDetails = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("edit_import_details")) {
                Text("Изменить название или адрес")
            }
        }
        if (editDetails) {
            OutlinedTextField(name, { if (it.length <= SavedChurchCodec.MAX_NAME_LENGTH) name = it; error = null }, label = { Text("Название храма") },
                modifier = Modifier.fillMaxWidth().testTag("new_church_name"), singleLine = true)
            OutlinedTextField(city, { if (it.length <= SavedChurchCodec.MAX_CITY_LENGTH) city = it; error = null }, label = { Text(if (cityRequired) "Город или населенный пункт" else "Город, если знаете") },
                modifier = Modifier.fillMaxWidth().testTag("new_church_city"), singleLine = true)
            OutlinedTextField(address, { if (it.length <= SavedChurchCodec.MAX_ADDRESS_LENGTH) address = it; error = null }, label = { Text("Адрес, если знаете") },
                modifier = Modifier.fillMaxWidth().testTag("new_church_address"), singleLine = true)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Время в храме", style = MaterialTheme.typography.titleMedium)
                Text("Сначала выбран часовой пояс телефона. Проверьте его, если добавляете храм в другом регионе",
                    style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { zoneMenu = true }, modifier = Modifier.fillMaxWidth().testTag("church_timezone_picker")) {
                        Text(russianZones.firstOrNull { it.first == timezone }?.second ?: timezone, Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = zoneMenu, onDismissRequest = { zoneMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        russianZones.forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { timezone = id; otherZone = false; zoneMenu = false; error = null; zoneConfirmed = true },
                                modifier = Modifier.testTag("zone_$id"))
                        }
                        DropdownMenuItem(text = { Text("Другой часовой пояс") }, onClick = { otherZone = true; zoneMenu = false })
                    }
                }
                if (otherZone) OutlinedTextField(timezone, { timezone = it.take(80); error = null; zoneConfirmed = false },
                    label = { Text("Например, Europe/Paris") }, singleLine = true,
                    isError = zone == null, modifier = Modifier.fillMaxWidth().testTag("new_church_timezone"))
                if (zone != null) {
                    val local = Instant.now().atZone(zone)
                    val offset = local.offset.id.let { if (it == "Z") "+00:00" else it }
                    Text("Сейчас ${local.format(DateTimeFormatter.ofPattern("HH:mm"))} · UTC$offset", Modifier.testTag("church_timezone_preview"),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (imported) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = zoneConfirmed, role = Role.Checkbox, onValueChange = { zoneConfirmed = it; error = null })
                    .testTag("confirm_import_timezone"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(checked = zoneConfirmed, onCheckedChange = null)
                    Text("Часовой пояс указан верно", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (initialRecord?.origin != SavedChurchOrigin.OPEN_STREET_MAP) ChurchLocationPin(hasLocation = latitude != null,
            onLocation = { lat, lon, meters -> latitude = lat; longitude = lon; accuracy = meters },
            onClear = { latitude = null; longitude = null; accuracy = null })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("new_church_error")) }
        Button(onClick = {
            when {
                name.isBlank() -> error = "Введите название храма"
                cityRequired && city.isBlank() -> error = "Укажите город или населенный пункт"
                zone == null -> error = "Проверьте часовой пояс или выберите его из списка"
                imported && !zoneConfirmed -> error = "Проверьте и подтвердите часовой пояс этого места"
                else -> {
                    val record = if (initialRecord == null) runCatching {
                        SavedChurchRecord.create(name, city, address, timezone, latitude, longitude, accuracy).copy(id = draftId)
                    }.getOrNull()
                    else SavedChurchCodec.validated(initialRecord.copy(name = name, city = city, address = address, timezone = timezone,
                        latitude = latitude, longitude = longitude, locationAccuracyMeters = accuracy))
                    if (record == null) error = "Проверьте название и город: уберите переносы строк и необычные символы"
                    else error = when (onSave(record)) {
                        ChurchSelectionResult.SELECTED, ChurchSelectionResult.PENDING -> null
                        ChurchSelectionResult.FAILED -> "Не удалось сохранить или выбрать храм. Проверьте свободное место; можно хранить до 100 своих храмов"
                        ChurchSelectionResult.STALE -> "Чтение или список храмов изменились. Выберите храм еще раз"
                    }
                }
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("save_new_church")) { Text(if (imported) "Выбрать этот храм" else "Сохранить и выбрать") }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("cancel_new_church")) { Text("Отмена") }
    }
}

@Composable
private fun ChurchLocationPin(hasLocation: Boolean, onLocation: (Double, Double, Float) -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val locator = remember(context) { ChurchLocator(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun locate() {
        busy = true; message = null
        locator.locate(maxAccuracyMeters = 500f, maxLocationAgeMillis = 5_000L) { location, error ->
            busy = false
            if (location != null) onLocation(location.latitude, location.longitude, location.accuracy)
            else message = error
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) locate()
        else message = "Для привязки места нужен точный доступ. Храм можно сохранить без местоположения"
    }
    DisposableEffect(locator, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { locator.stop(); busy = false } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); locator.stop() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Место на карте", style = MaterialTheme.typography.titleMedium)
        Text("Если вы уже внутри или рядом с храмом, можно сохранить это место для будущего поиска. Для привязки нужен точный доступ. Место останется только на телефоне",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hasLocation) {
            Text("Местоположение добавлено", Modifier.testTag("new_church_location_saved"))
            TextButton(onClick = { onClear(); message = null }) { Text("Убрать местоположение") }
        } else OutlinedButton(onClick = {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("pin_new_church")) {
            Text(if (busy) "Определяем место" else "Я сейчас в этом храме")
        }
        if (busy) TextButton(onClick = { locator.stop(); busy = false }) { Text("Отменить определение") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
