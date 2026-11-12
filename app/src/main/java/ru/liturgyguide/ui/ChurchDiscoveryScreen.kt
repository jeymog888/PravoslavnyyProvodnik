package ru.liturgyguide.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
import ru.liturgyguide.service.ChurchLocationSource
import ru.liturgyguide.service.ForegroundLocationResult
import ru.liturgyguide.service.LocationFailure
import ru.liturgyguide.service.ChurchSelectionResult
import ru.liturgyguide.service.SavedChurchCodec
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.discovery.*
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ChurchDirectory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

enum class ChurchSearchMode { NAME, CITY }

/** Owns one foreground request. A request generation also rejects non-cooperative late callbacks. */
@Stable
private class ChurchDiscoveryState(private val discovery: PlaceDiscovery, private val scope: CoroutineScope) {
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var locationFailure by mutableStateOf<LocationFailure?>(null); private set
    var cities by mutableStateOf<DiscoveryResult.Success<DiscoveryCity>?>(null); private set
    var churches by mutableStateOf<DiscoveryResult.Success<DiscoveredChurch>?>(null); private set
    var areaLabel by mutableStateOf(""); private set
    var cityName by mutableStateOf(""); private set
    var approximateLocation by mutableStateOf(false); private set
    var resultVersion by mutableIntStateOf(0); private set
    private var generation = 0
    private var job: Job? = null
    private var lastAttempt: (() -> Unit)? = null
    val canRetry: Boolean get() = lastAttempt != null
    fun retry() { lastAttempt?.invoke() }

    fun cancel(message: String? = null, clearResults: Boolean = false) {
        generation++
        job?.cancel(); job = null; busy = null
        this.message = message
        locationFailure = null
        if (clearResults) { cities = null; churches = null; areaLabel = ""; cityName = ""; approximateLocation = false; lastAttempt = null }
    }
    fun current(token: Int) = token == generation
    private fun begin(label: String): Int {
        cancel(clearResults = true)
        busy = label; resultVersion++
        return generation
    }
    fun beginLocation(onRetry: () -> Unit): Int = begin("Определяем местоположение").also { lastAttempt = onRetry }
    fun locationFailed(token: Int, failure: LocationFailure) {
        if (!current(token)) return
        busy = null; message = failure.message; locationFailure = failure; resultVersion++
    }
    fun invalidSelection() {
        message = "Не удалось открыть данные этого храма. Можно выбрать другой или добавить храм вручную"
        resultVersion++
    }
    fun searchCities(query: String) {
        val token = begin("Ищем город")
        lastAttempt = { searchCities(query) }
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
    fun searchPlaces(query: String) {
        val token = begin(if (LandmarkSearch.parse(query) != null) "Ищем станцию и храмы рядом" else "Ищем по названию")
        lastAttempt = { searchPlaces(query) }
        areaLabel = "Найденные места"
        job = scope.launch {
            try {
                val response = discovery.searchPlaces(query)
                if (!current(token)) return@launch
                when (response) {
                    is DiscoveryResult.Success -> {
                        churches = response
                        response.landmark?.let { areaLabel = it.label; cityName = it.city }
                    }
                    is DiscoveryResult.Failure -> message = discoveryError(response.reason)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(token)) message = discoveryError(DiscoveryFailure.UNAVAILABLE) }
            finally { if (current(token)) { busy = null; resultVersion++ } }
        }
    }
    fun searchChurches(center: GeoPoint, label: String, city: String, radius: Int = 3_000, approximate: Boolean = false,
        landmark: DiscoveryLandmark? = null, churchQuery: String = "") {
        val token = begin("Ищем храмы")
        lastAttempt = { searchChurches(center, label, city, radius, approximate, landmark, churchQuery) }
        areaLabel = label; cityName = city; approximateLocation = approximate
        job = scope.launch {
            try {
                val response = if (landmark == null) discovery.findChurches(center, radius)
                    else discovery.findChurchesNearLandmark(landmark, churchQuery)
                if (!current(token)) return@launch
                when (response) {
                    is DiscoveryResult.Success -> churches = if (landmark == null) response else response.copy(
                        items = LandmarkSearch.rank(response.items, churchQuery, landmark.center), landmark = landmark,
                        landmarkChurchQuery = churchQuery, notice = "Сверьте название и адрес храма")
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
    onSave: (SavedChurchRecord, onSelected: () -> Unit) -> ChurchSelectionResult, onOpenUrl: (String) -> Unit,
    onSelectionCancelled: () -> Unit = {}, query: String, onQueryChange: (String) -> Unit,
    mode: ChurchSearchMode, onModeChange: (ChurchSearchMode) -> Unit,
    savedChurches: List<Parish> = emptyList(),
    onSelectSaved: ((String, () -> Unit) -> ChurchSelectionResult)? = null,
    favorites: Set<String> = emptySet(), onToggleFavorite: ((String) -> Unit)? = null,
    locationSource: ChurchLocationSource? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val state = remember(discovery, scope) { ChurchDiscoveryState(discovery, scope) }
    val locator = remember(context, locationSource) { locationSource ?: ChurchLocator(context) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberLazyListState()
    var nameFilter by rememberSaveable { mutableStateOf("") }
    var pendingPermission by remember { mutableStateOf<Int?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val manualDraft = rememberSaveableStateHolder()

    fun cancelSearch(clear: Boolean = false) {
        pendingPermission = null; locator.stop()
        state.cancel(if (clear) null else "Поиск остановлен. Можно попробовать снова или выбрать храм вручную", clear)
    }
    fun cancelImport() { onSelectionCancelled(); adding = false; manualDraft.removeState("manual_church") }
    fun leave() { onSelectionCancelled(); cancelSearch(); onBack() }
    fun locate(token: Int) {
        if (!state.current(token)) return
        locator.locateResult(maxAccuracyMeters = 5_000f) locationResult@{ result ->
            if (!state.current(token)) return@locationResult
            when (result) {
                is ForegroundLocationResult.Failed -> state.locationFailed(token, result.reason)
                is ForegroundLocationResult.Located -> {
                    val location = result.fix
                    val accuracy = location.accuracyMeters
                    if (accuracy == null || !accuracy.isFinite() || accuracy <= 0f || accuracy > 5_000f) {
                        state.locationFailed(token, LocationFailure.INACCURATE)
                        return@locationResult
                    }
                    nameFilter = ""
                    val radius = maxOf(3_000, ceil(accuracy / 1_000.0).toInt() * 1_000).coerceAtMost(5_000)
                    state.searchChurches(GeoPoint(location.latitude, location.longitude), "Рядом с вами", "", radius,
                        approximate = accuracy > 500f)
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val token = pendingPermission
        pendingPermission = null
        if (token != null && state.current(token)) {
            if (result.values.any { it }) locate(token)
            else state.locationFailed(token, LocationFailure.PERMISSION)
        }
    }
    fun startNearby() {
        focus.clearFocus(); keyboard?.hide(); nameFilter = ""; pendingPermission = null; locator.stop()
        val token = state.beginLocation(::startNearby)
        if (locator.hasPermission()) locate(token)
        else { pendingPermission = token; permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }
    }
    fun submitSearch() {
        if (query.trim().length < 2 || state.busy != null) return
        locator.stop(); pendingPermission = null; focus.clearFocus(); keyboard?.hide(); nameFilter = ""
        if (mode == ChurchSearchMode.NAME) state.searchPlaces(query) else state.searchCities(query)
    }
    fun changeMode(value: ChurchSearchMode) {
        if (value != mode) { cancelSearch(clear = true); nameFilter = ""; onModeChange(value) }
        scope.launch { scroll.animateScrollToItem(0) }
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
    val currentCancel by rememberUpdatedState(onSelectionCancelled)
    DisposableEffect(Unit) { onDispose { currentCancel() } }
    BackHandler { if (adding) cancelImport() else leave() }

    if (adding) ModalBottomSheet(onDismissRequest = ::cancelImport,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        GuideSheetSystemBars()
        manualDraft.SaveableStateProvider("manual_church") {
            SavedChurchForm(onCancel = ::cancelImport, onSave = { record ->
                onSave(record) { adding = false; manualDraft.removeState("manual_church"); onQueryChange("") }
            })
        }
    }
    val savedMatches = remember(savedChurches, query, favorites) { ChurchDirectory.search(savedChurches, query, favorites).take(6) }
    // Once a search answers, its results replace the initial preview instead of appearing
    // below six tall saved rows. Empty/failed searches keep the saved path available.
    val showSavedPreview = state.busy == null && state.cities?.items.isNullOrEmpty() &&
        state.churches?.items.isNullOrEmpty() && state.churches?.landmarkChoices.isNullOrEmpty()
    val visibleSavedIds = if (showSavedPreview && onSelectSaved != null) savedMatches.map { it.id }.toSet() else emptySet()
    // The preview is limited to six rows; the identity check must include the whole directory.
    // Reimporting a sparse map record could erase its saved website and invalidate its schedule cache.
    val savedById = remember(savedChurches) { savedChurches.associateBy { it.id } }
    LaunchedEffect(state.resultVersion) {
        // A large-font search form may fill the viewport. Bring the response (including
        // loading, permission errors and an empty result) into view after an explicit search.
        // Editing/filtering does not change resultVersion and must not move the reader.
        if (state.resultVersion > 0) scroll.animateScrollToItem(1)
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { Column(Modifier.statusBarsPadding()) { PageHeader("Найти храм", ::leave) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().imePadding().testTag("discovery_content"), state = scroll,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "search_controls") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(query, { value ->
                        if (value != query) { cancelSearch(clear = true); onQueryChange(value.take(DefaultPlaceDiscovery.MAX_QUERY_LENGTH)); nameFilter = "" }
                    }, label = { Text(if (mode == ChurchSearchMode.NAME) "Храм или адрес" else "Город или село") },
                        placeholder = { if (mode == ChurchSearchMode.NAME) Text("Сретенский монастырь, Москва") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { cancelSearch(clear = true); onQueryChange("") }) {
                            Icon(Icons.Default.Close, "Очистить поисковый запрос")
                        } }, modifier = Modifier.fillMaxWidth().testTag("discovery_query"))
                    // A local function reference compares equal across captured mode changes.
                    // Keep the current mode in the callback passed to remembered chip actions.
                    ChurchSearchModePicker(mode) { value -> changeMode(value) }
                    Button(onClick = ::submitSearch, enabled = query.trim().length >= 2 && state.busy == null,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discovery_submit")) {
                        Icon(Icons.Default.Search, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                        Text(if (mode == ChurchSearchMode.NAME) "Найти" else "Найти город")
                    }
                    OutlinedButton(onClick = ::startNearby, enabled = state.busy == null, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("discovery_nearby")) {
                        Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Рядом со мной")
                    }
                }
            }
            if (state.busy != null) item(key = "search_loading") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(requireNotNull(state.busy), Modifier.weight(1f).testTag("discovery_loading"), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { cancelSearch() }, modifier = Modifier.heightIn(min = 48.dp).testTag("discovery_cancel")) { Text("Отменить") }
                    }
                }
            }
            state.message?.let { message -> item(key = "search_message") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(message, modifier = Modifier.testTag("discovery_message"), style = MaterialTheme.typography.bodyMedium)
                        if (state.locationFailure == LocationFailure.DISABLED) Button(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }.onFailure {
                                state.cancel("Не удалось открыть настройки телефона. Можно найти храм по названию")
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp).testTag("discovery_location_settings")) { Text("Включить местоположение") }
                        if (state.canRetry) OutlinedButton(onClick = { state.retry() }, modifier = Modifier.heightIn(min = 48.dp).testTag("discovery_retry")) { Text("Повторить поиск") }
                    }
                }
            } }
            state.cities?.let { result ->
                item(key = "cities_heading") {
                    Text(if (result.items.isEmpty()) "Город не найден" else "Выберите город", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("discovery_city_heading").semantics { heading() })
                    CacheNotice(result.cacheStatus, result.fetchedAtEpochMillis)
                    if (result.items.isEmpty()) Text("Уточните название или добавьте храм вручную", style = MaterialTheme.typography.bodyMedium)
                }
                items(result.items, key = { "city:${it.id}" }) { city ->
                    DiscoveryRow(city.name, listOf(city.region, city.country).filter(String::isNotBlank).distinct().joinToString(" · "),
                        "discovery_city_${city.id}") {
                        focus.clearFocus(); keyboard?.hide(); nameFilter = ""
                        state.searchChurches(city.center, listOf(city.name, city.region).filter(String::isNotBlank).distinct().joinToString(" · "), city.name)
                    }
                }
            }
            state.churches?.let { result ->
                val byName = result.provider in setOf(DiscoveryProvider.PHOTON, DiscoveryProvider.LOCAL_CATALOG) && result.queryCenter == null
                val nearbyByName = result.provider == DiscoveryProvider.PHOTON && result.queryCenter != null
                val filtered = result.items.filter { church ->
                    val needle = nameFilter.trim().lowercase(Locale.ROOT).replace('ё', 'е')
                    (church.name + " " + church.address).lowercase(Locale.ROOT).replace('ё', 'е').contains(needle)
                }.filterNot { nameFilter.isBlank() && onSelectSaved != null &&
                    KnownChurchIdentity.existingFor(it, savedById)?.id in visibleSavedIds }
                if (result.landmarkChoices.isNotEmpty()) {
                    item(key = "landmark_heading") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Уточните станцию", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.testTag("discovery_landmark_heading").semantics { heading() })
                            Text("Выберите нужное место, чтобы найти храмы вокруг него", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(result.landmarkChoices, key = { "landmark:${it.id}" }) { landmark ->
                        DiscoveryRow(landmark.name, listOf(landmark.city, landmark.region, landmark.country)
                            .filter(String::isNotBlank).distinct().joinToString(" · "), "discovery_landmark_${landmark.id}") {
                            focus.clearFocus(); keyboard?.hide(); nameFilter = ""
                            state.searchChurches(landmark.center, landmark.label, landmark.city,
                                DefaultPlaceDiscovery.LANDMARK_RADIUS_METERS, landmark = landmark, churchQuery = result.landmarkChurchQuery)
                        }
                    }
                }
                if (result.items.isNotEmpty() || result.landmarkChoices.isNotEmpty()) item(key = "results_heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.areaLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(discoveryResultCaption(result, state.approximateLocation), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(when {
                                result.provider == DiscoveryProvider.LOCAL_CATALOG -> "discovery_catalog_notice"
                                nearbyByName -> "discovery_nearby_notice"
                                byName && result.notice != null -> "discovery_result_notice"
                                byName -> "discovery_name_notice"
                                else -> "discovery_area"
                            }))
                        CacheNotice(result.cacheStatus, result.fetchedAtEpochMillis)
                        if (!byName && (result.items.size > 5 || nameFilter.isNotBlank())) OutlinedTextField(nameFilter, { nameFilter = it.take(160) },
                            label = { Text("Название среди найденных") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("discovery_name_filter"))
                    }
                }
                if (result.landmarkChoices.isEmpty() && filtered.isEmpty() && (result.items.isEmpty() || nameFilter.isNotBlank())) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (result.items.isEmpty()) {
                            if (visibleSavedIds.isNotEmpty()) "Поиск не дал новых результатов"
                            else if (byName) "По этому названию ничего не найдено"
                            else if (nearbyByName) "По названиям рядом ничего не найдено"
                            else "В этом районе храмы не найдены"
                        } else "Нет храма с таким названием в найденном списке",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("discovery_empty"))
                        if (result.items.isEmpty()) result.notice?.takeIf(String::isNotBlank)?.let { notice ->
                            Text(notice, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("discovery_result_notice"))
                        }
                        Text(if (visibleSavedIds.isNotEmpty()) "Выберите сохраненный храм ниже или уточните поиск"
                            else "Уточните город или добавьте храм вручную", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(filtered, key = { "network:${it.id}" }) { church ->
                    val location = listOf(church.city.takeUnless { it.isNotBlank() && church.address.startsWith(it) }.orEmpty(),
                        church.region, church.country, church.address).filter(String::isNotBlank).distinct().joinToString(" · ")
                    DiscoveryRow(church.name, location, "discovery_church_${church.id}") {
                        focus.clearFocus(); keyboard?.hide()
                        val saved = KnownChurchIdentity.existingFor(church, savedById)
                        val selection = if (saved != null && onSelectSaved != null) {
                            onSelectSaved(saved.id) { onQueryChange("") }
                        } else {
                            church.toSavedChurch()?.let { record -> onSave(record) { onQueryChange("") } }
                                ?: ChurchSelectionResult.FAILED
                        }
                        if (selection == ChurchSelectionResult.FAILED || selection == ChurchSelectionResult.STALE) state.invalidSelection()
                    }
                }
            }
            if (showSavedPreview && onSelectSaved != null && mode == ChurchSearchMode.NAME && query.isNotBlank() && savedMatches.isEmpty() &&
                state.churches == null) item(key = "saved_empty") {
                Text("В сохраненных храмах совпадений нет", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("discovery_saved_empty"))
            }
            if (showSavedPreview && savedMatches.isNotEmpty() && onSelectSaved != null) {
                item(key = "saved_heading") {
                    Text(if (query.isBlank()) "Доступны без сети" else "Сохраненные совпадения", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp).semantics { heading() })
                }
                items(savedMatches, key = { "saved:${it.id}" }) { saved ->
                    DiscoveryRow(saved.shortName, listOf(saved.city, saved.address).filter(String::isNotBlank).distinct().joinToString(" · "), "discovery_saved_${saved.id}",
                        trailing = {
                            if (onToggleFavorite != null) IconButton(onClick = { onToggleFavorite(saved.id) },
                                modifier = Modifier.size(48.dp).testTag("favorite_${saved.id}")) {
                                Icon(if (saved.id in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                                    if (saved.id in favorites) "Убрать из избранного: ${saved.shortName}" else "В избранное: ${saved.shortName}",
                                    tint = if (saved.id in favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }) {
                        focus.clearFocus(); keyboard?.hide()
                        val result = onSelectSaved(saved.id) { onQueryChange("") }
                        if (result == ChurchSelectionResult.FAILED || result == ChurchSelectionResult.STALE) state.invalidSelection()
                    }
                }
            }
            item(key = "manual_and_sources") {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()
                    TextButton(onClick = {
                        cancelSearch(clear = true); focus.clearFocus(); keyboard?.hide(); manualDraft.removeState("manual_church"); adding = true
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("discovery_manual")) {
                        Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Добавить храм вручную")
                    }
                    TextButton(onClick = {
                        val mapQuery = if (mode == ChurchSearchMode.NAME && query.isNotBlank()) query.trim() else "православный храм " + query.trim()
                        onOpenUrl("geo:0,0?q=" + Uri.encode(mapQuery))
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("discovery_maps")) { Text("Посмотреть в картах") }
                    Text(DefaultPlaceDiscovery.ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Для поиска в интернете отправляется название или район местоположения. Поиск в каталоге на телефоне работает без сети", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("discovery_privacy"))
                    TextButton(onClick = { onOpenUrl(DefaultPlaceDiscovery.COPYRIGHT_URL) }, modifier = Modifier.heightIn(min = 48.dp).testTag("discovery_attribution")) { Text("Об источнике данных") }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryRow(title: String, subtitle: String, tag: String,
    trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Selection and favorite are separate touch targets. A star must never
            // choose another church or trigger the reading-reset confirmation.
            Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(onClick = onClick).semantics { role = Role.Button }
                .testTag(tag).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CacheNotice(status: DiscoveryCacheStatus, fetchedAt: Long) {
    if (status == DiscoveryCacheStatus.FRESH_NETWORK) return
    val date = runCatching { Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (status == DiscoveryCacheStatus.LOCAL_SNAPSHOT) "d MMMM uuuu" else "d MMMM, HH:mm",
            Locale.forLanguageTag("ru"))) }.getOrDefault("")
    Text((when (status) {
        DiscoveryCacheStatus.LOCAL_SNAPSHOT -> "Каталог на телефоне. Данные"
        DiscoveryCacheStatus.STALE_CACHE -> "Не удалось обновить. Сохраненные результаты"
        else -> "Сохраненные результаты"
    }) +
        if (date.isBlank()) "" else " от $date", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp).testTag("discovery_cache_notice"), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun DiscoveredChurch.toSavedChurch(): SavedChurchRecord? {
    fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()
    val churchTimezone = ChurchTimeZone.at(center) ?: return null
    // The center of a search area is not evidence that every nearby church is in that city.
    return SavedChurchCodec.validated(SavedChurchRecord(id, clean(name), clean(city),
        clean(address), churchTimezone, center.latitude, center.longitude,
        origin = SavedChurchOrigin.OPEN_STREET_MAP, website = website.orEmpty(), sourceUrl = sourceUrl))
}

private fun discoveryError(reason: DiscoveryFailure): String = when (reason) {
    DiscoveryFailure.INVALID_QUERY -> "Проверьте поисковый запрос: нужно хотя бы два символа"
    DiscoveryFailure.TIMEOUT -> "Сервис не успел ответить. Попробуйте еще раз или выберите храм вручную"
    DiscoveryFailure.RATE_LIMITED -> "Поиск временно ограничен. Попробуйте чуть позже"
    DiscoveryFailure.BUSY -> "Поиск уже выполняется. Можно отменить его и попробовать снова"
    DiscoveryFailure.TOO_LARGE, DiscoveryFailure.INVALID_RESPONSE -> "Не удалось прочитать данные карты. Можно выбрать храм вручную"
    DiscoveryFailure.SERVER_UNAVAILABLE -> "Сервис поиска временно недоступен. Попробуйте еще раз или добавьте храм вручную"
    DiscoveryFailure.UNAVAILABLE -> "Не удалось связаться с сервисом поиска. Проверьте подключение или добавьте храм вручную"
}

/** Keep source limitations in one caption; a failed station lookup must not disappear. */
internal fun discoveryResultCaption(result: DiscoveryResult.Success<DiscoveredChurch>, approximate: Boolean): String {
    val area = buildList {
        if (result.queryCenter != null) add("В радиусе ${(result.radiusMeters ?: 3_000) / 1_000} км")
        if (approximate) add("местоположение приблизительное")
        if (result.provider == DiscoveryProvider.PHOTON && result.queryCenter != null) add("список может быть неполным")
    }.joinToString("; ")
    val sourceNotice = result.notice?.takeUnless { it in setOf(
        "Рядом может быть несколько храмов с похожим названием. Сверьте адрес", "Сверьте название и адрес храма") }
    val verification = if (result.provider == DiscoveryProvider.PHOTON && result.queryCenter != null)
        "Сверьте название и адрес православного храма" else "Сверьте название и адрес храма"
    return listOf(area, sourceNotice ?: verification).filter(String::isNotBlank).joinToString(". ")
}

/** Preserve whole labels at accessibility text sizes instead of splitting a word into letters. */
@Composable
private fun ChurchSearchModePicker(mode: ChurchSearchMode, onChange: (ChurchSearchMode) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = (constraints.maxWidth - with(density) { 12.dp.roundToPx() }) / 2
        val textWidth = (cellWidth - with(density) { 32.dp.roundToPx() }).coerceAtLeast(1)
        val stacked = listOf("По названию", "По городу").any {
            measurer.measure(it, style, constraints = Constraints(maxWidth = textWidth)).lineCount > 1
        }
        @Composable fun choice(value: ChurchSearchMode, label: String, tag: String, modifier: Modifier) {
            FilterChip(selected = mode == value, onClick = { onChange(value) },
                label = { Text(label, style = style, modifier = Modifier.testTag("${tag}_label")) },
                modifier = modifier.heightIn(min = 48.dp).testTag(tag))
        }
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            choice(ChurchSearchMode.NAME, "По названию", "discovery_mode_name", Modifier.fillMaxWidth())
            choice(ChurchSearchMode.CITY, "По городу", "discovery_mode_city", Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            choice(ChurchSearchMode.NAME, "По названию", "discovery_mode_name", Modifier.weight(1f))
            choice(ChurchSearchMode.CITY, "По городу", "discovery_mode_city", Modifier.weight(1f))
        }
    }
}
