package ru.liturgyguide.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.liturgyguide.service.ChurchLocator
import ru.liturgyguide.service.SavedChurchCodec
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.discovery.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** Owns one foreground request. A request generation also rejects non-cooperative late callbacks. */
@Stable
private class ChurchDiscoveryState(private val discovery: PlaceDiscovery, private val scope: CoroutineScope) {
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var cities by mutableStateOf<DiscoveryResult.Success<DiscoveryCity>?>(null); private set
    var churches by mutableStateOf<DiscoveryResult.Success<DiscoveredChurch>?>(null); private set
    var areaLabel by mutableStateOf(""); private set
    var cityName by mutableStateOf(""); private set
    var approximateLocation by mutableStateOf(false); private set
    var resultVersion by mutableIntStateOf(0); private set
    private var generation = 0
    private var job: Job? = null

    fun cancel(message: String? = null, clearResults: Boolean = false) {
        generation++
        job?.cancel(); job = null; busy = null
        this.message = message
        if (clearResults) { cities = null; churches = null; areaLabel = ""; cityName = "" }
    }
    fun current(token: Int) = token == generation
    private fun begin(label: String): Int {
        cancel(clearResults = true)
        busy = label; resultVersion++
        return generation
    }
    fun beginLocation(): Int = begin("Определяем местоположение")
    fun locationFailed(token: Int, explanation: String) {
        if (!current(token)) return
        busy = null; message = explanation; resultVersion++
    }
    fun invalidSelection() {
        message = "Не удалось открыть данные этого храма. Можно выбрать другой или добавить храм вручную"
        resultVersion++
    }
    fun searchCities(query: String) {
        val token = begin("Ищем город")
        job = scope.launch {
            try {
                val response = discovery.searchCities(query)
                if (!current(token)) return@launch
                when (response) {
                    is DiscoveryResult.Success -> cities = response
                    is DiscoveryResult.Failure -> message = discoveryError(response.reason)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(token)) message = discoveryError(DiscoveryFailure.UNAVAILABLE) }
            finally { if (current(token)) { busy = null; resultVersion++ } }
        }
    }
    fun searchChurches(center: GeoPoint, label: String, city: String, radius: Int = 3_000, approximate: Boolean = false) {
        val token = begin("Ищем храмы")
        areaLabel = label; cityName = city; approximateLocation = approximate
        job = scope.launch {
            try {
                val response = discovery.findChurches(center, radius)
                if (!current(token)) return@launch
                when (response) {
                    is DiscoveryResult.Success -> churches = response
                    is DiscoveryResult.Failure -> message = discoveryError(response.reason)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(token)) message = discoveryError(DiscoveryFailure.UNAVAILABLE) }
            finally { if (current(token)) { busy = null; resultVersion++ } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChurchDiscoveryScreen(discovery: PlaceDiscovery, onBack: () -> Unit,
    onSave: (SavedChurchRecord) -> Boolean, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val state = remember(discovery, scope) { ChurchDiscoveryState(discovery, scope) }
    val locator = remember(context) { ChurchLocator(context) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var nameFilter by rememberSaveable { mutableStateOf("") }
    var pendingPermission by remember { mutableStateOf<Int?>(null) }
    var importJson by rememberSaveable { mutableStateOf<String?>(null) }
    val importRecord = remember(importJson) { SavedChurchCodec.decode(importJson).firstOrNull() }

    fun cancelSearch(clear: Boolean = false) {
        pendingPermission = null; locator.stop()
        state.cancel(if (clear) null else "Поиск остановлен. Можно попробовать снова или выбрать храм вручную", clear)
    }
    fun leave() { cancelSearch(); onBack() }
    fun locate(token: Int) {
        if (!state.current(token)) return
        locator.locate locationResult@{ location, error ->
            if (!state.current(token)) return@locationResult
            when {
                location == null -> state.locationFailed(token, error ?: "Не удалось определить место. Укажите город или выберите храм вручную")
                location.accuracy > 5_000f -> state.locationFailed(token,
                    "Местоположение слишком приблизительное для поиска рядом. Укажите город или разрешите точное местоположение в настройках телефона")
                else -> {
                    nameFilter = ""
                    val radius = maxOf(3_000, ceil(location.accuracy / 1_000.0).toInt() * 1_000).coerceAtMost(5_000)
                    state.searchChurches(GeoPoint(location.latitude, location.longitude), "Рядом с вами", "", radius,
                        approximate = location.accuracy > 500f)
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val token = pendingPermission
        pendingPermission = null
        if (token != null && state.current(token)) {
            if (result.values.any { it }) locate(token)
            else state.locationFailed(token, "Можно продолжить без геолокации: укажите город или выберите храм вручную")
        }
    }
    fun submitCity() {
        if (query.trim().length < 2 || state.busy != null) return
        locator.stop(); pendingPermission = null; focus.clearFocus(); keyboard?.hide(); nameFilter = ""
        state.searchCities(query)
    }
    DisposableEffect(state, locator, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                pendingPermission = null; locator.stop()
                state.cancel(if (state.busy != null) "Поиск остановлен после выхода из приложения" else null)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { pendingPermission = null; locator.stop(); state.cancel(); lifecycle.removeObserver(observer) }
    }
    BackHandler { if (importRecord != null) importJson = null else leave() }
    LaunchedEffect(state.resultVersion) {
        if (state.resultVersion > 0) scroll.animateScrollToItem(1)
    }

    if (importRecord != null) ModalBottomSheet(onDismissRequest = { importJson = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SavedChurchForm(initialRecord = importRecord, onCancel = { importJson = null }, onSave = { record ->
            if (onSave(record)) { importJson = null; true } else false
        })
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { Column(Modifier.statusBarsPadding()) { PageHeader("Найти храм", ::leave) } },
        bottomBar = { NavigationTextScale {
            Surface(color = MaterialTheme.colorScheme.surface) {
                TextButton(onClick = ::leave, modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .heightIn(min = 52.dp).testTag("discovery_manual")) { Text("К сохраненным храмам") }
            }
        } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("discovery_content"), state = scroll,
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Рядом или в другом городе", style = MaterialTheme.typography.titleLarge)
                        Text("Город отправится в Photon, а район поиска - в VK Maps. Сервисы используют данные OpenStreetMap",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("discovery_privacy"))
                        OutlinedTextField(query, { value ->
                            if (value != query) { cancelSearch(clear = true); query = value.take(DefaultPlaceDiscovery.MAX_QUERY_LENGTH); nameFilter = "" }
                        }, label = { Text("Город или населенный пункт") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitCity() }),
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { cancelSearch(clear = true); query = "" }) {
                                Icon(Icons.Default.Close, "Очистить название города")
                            } }, modifier = Modifier.fillMaxWidth().testTag("discovery_city_query"))
                        Button(onClick = ::submitCity, enabled = query.trim().length >= 2 && state.busy == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("discovery_find_city")) { Text("Найти город") }
                        OutlinedButton(onClick = {
                            focus.clearFocus(); keyboard?.hide(); nameFilter = ""; pendingPermission = null; locator.stop()
                            val token = state.beginLocation()
                            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate(token)
                            else { pendingPermission = token; permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }
                        }, enabled = state.busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("discovery_nearby")) {
                            Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Рядом со мной")
                        }
                    }
                }
            }
            item {
                if (state.busy != null) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(requireNotNull(state.busy), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("discovery_loading"))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Поиск может занять немного времени. Можно отменить его и выбрать храм вручную", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { cancelSearch() }, modifier = Modifier.heightIn(min = 48.dp).testTag("discovery_cancel")) { Text("Отменить поиск") }
                    }
                }
                state.message?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message, modifier = Modifier.testTag("discovery_message"), style = MaterialTheme.typography.bodyLarge)
                            Text("Сохраненные храмы и тексты службы доступны без сети", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            state.cities?.let { result ->
                item {
                    Text(if (result.items.isEmpty()) "Город не найден" else "Выберите город", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("discovery_city_heading").semantics { heading() })
                    CacheNotice(result.cacheStatus, result.fetchedAtEpochMillis)
                    if (result.items.isEmpty()) Text("Попробуйте другое название или добавьте храм вручную", style = MaterialTheme.typography.bodyMedium)
                }
                items(result.items, key = { it.id }) { city ->
                    DiscoveryRow(city.name, listOf(city.region, city.country).filter(String::isNotBlank).distinct().joinToString(" · "),
                        "discovery_city_${city.id}") {
                        focus.clearFocus(); keyboard?.hide(); nameFilter = ""
                        state.searchChurches(city.center, listOf(city.name, city.region).filter(String::isNotBlank).distinct().joinToString(" · "), city.name)
                    }
                }
            }
            state.churches?.let { result ->
                val filtered = result.items.filter { church ->
                    val needle = nameFilter.trim().lowercase(Locale.ROOT).replace('ё', 'е')
                    (church.name + " " + church.address).lowercase(Locale.ROOT).replace('ё', 'е').contains(needle)
                }
                item {
                    Text(state.areaLabel, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(8.dp))
                    val radius = (result.radiusMeters ?: 3_000) / 1_000
                    Text("В радиусе $radius км от выбранного центра. Выдача может быть неполной", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("discovery_area"))
                    if (state.approximateLocation) Text("Местоположение приблизительное. Сверьте адрес храма", style = MaterialTheme.typography.bodySmall)
                    CacheNotice(result.cacheStatus, result.fetchedAtEpochMillis)
                    Spacer(Modifier.height(12.dp))
                    Text("Расписание нужно уточнить отдельно", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (result.items.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(nameFilter, { nameFilter = it.take(160) }, label = { Text("Название среди найденных") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("discovery_name_filter"))
                    }
                }
                if (filtered.isEmpty()) item {
                    Text(if (result.items.isEmpty()) "В этом районе храмы не найдены" else "Нет храма с таким названием в найденном списке",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("discovery_empty"))
                    Text("Данные карты могут быть неполными. Можно проверить карты или добавить свой храм", style = MaterialTheme.typography.bodyMedium)
                }
                items(filtered, key = { it.id }) { church ->
                    DiscoveryRow(church.name, listOf(if (church.isChapel) "Часовня" else "Храм", church.address).filter(String::isNotBlank).joinToString(" · "),
                        "discovery_church_${church.id}") {
                        focus.clearFocus(); keyboard?.hide()
                        val record = church.toSavedChurch(state.cityName)
                        if (record != null) importJson = SavedChurchCodec.encode(listOf(record))
                        else state.invalidSelection()
                    }
                }
            }
            item {
                Text(DefaultPlaceDiscovery.ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onOpenUrl(DefaultPlaceDiscovery.COPYRIGHT_URL) }, modifier = Modifier.testTag("discovery_attribution")) { Text("Об источнике данных") }
                TextButton(onClick = { onOpenUrl("geo:0,0?q=" + Uri.encode("православный храм " + query.trim())) },
                    modifier = Modifier.testTag("discovery_maps")) { Text("Открыть поиск в картах") }
            }
        }
    }
}

@Composable
private fun DiscoveryRow(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).semantics { role = Role.Button }
            .testTag(tag).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CacheNotice(status: DiscoveryCacheStatus, fetchedAt: Long) {
    if (status == DiscoveryCacheStatus.FRESH_NETWORK) return
    val date = runCatching { Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru"))) }.getOrDefault("")
    Text((if (status == DiscoveryCacheStatus.STALE_CACHE) "Не удалось обновить. Сохраненные результаты" else "Сохраненные результаты") +
        if (date.isBlank()) "" else " от $date", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp).testTag("discovery_cache_notice"), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun DiscoveredChurch.toSavedChurch(fallbackCity: String): SavedChurchRecord? {
    fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()
    return SavedChurchCodec.validated(SavedChurchRecord(id, clean(name), clean(city.ifBlank { fallbackCity }),
        clean(address), ZoneId.systemDefault().id, center.latitude, center.longitude,
        origin = SavedChurchOrigin.OPEN_STREET_MAP, website = website.orEmpty(), sourceUrl = sourceUrl))
}

private fun discoveryError(reason: DiscoveryFailure): String = when (reason) {
    DiscoveryFailure.INVALID_QUERY -> "Проверьте название города: нужно хотя бы две буквы"
    DiscoveryFailure.TIMEOUT -> "Сервис не успел ответить. Попробуйте еще раз или выберите храм вручную"
    DiscoveryFailure.RATE_LIMITED, DiscoveryFailure.BUSY -> "Поиск сейчас занят. Попробуйте чуть позже"
    DiscoveryFailure.TOO_LARGE, DiscoveryFailure.INVALID_RESPONSE -> "Не удалось прочитать данные карты. Можно выбрать храм вручную"
    DiscoveryFailure.UNAVAILABLE -> "Поиск в сети недоступен. Проверьте подключение или выберите храм вручную"
}
