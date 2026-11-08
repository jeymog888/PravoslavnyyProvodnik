package ru.liturgyguide.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.ServiceSession
import ru.liturgyguide.service.ServiceRelation
import ru.liturgyguide.service.ServiceSessionEngine
import ru.liturgyguide.service.ContextSuggestionEngine
import ru.liturgyguide.service.PrayerTrackFactory
import ru.liturgyguide.service.PrayerTextSearch
import ru.liturgyguide.service.ChurchDirectory
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.UserServiceStartStore
import com.google.gson.Gson
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.delay

private enum class Screen { MAIN, MANUAL, ARRIVAL, SERVICE, STAGE_SELECTOR, SEARCH, SETTINGS, SOURCES, CHURCH, DISCOVERY }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { AppNavigation((application as LiturgyGuideApplication).container) }
    }
}

@Composable
fun AppNavigation(container: AppContainer, clock: Clock = Clock.systemUTC()) {
    val context = LocalContext.current
    val screenStateHolder = rememberSaveableStateHolder()
    val preferences = remember { context.getSharedPreferences("guide_preferences", 0) }
    val churchStore = remember(preferences) { ChurchDirectoryStore(preferences) }
    val startStore = remember(preferences) { UserServiceStartStore(preferences) }
    var userStarts by remember { mutableStateOf(startStore.records()) }
    var savedChurches by remember { mutableStateOf(churchStore.records()) }
    var favoriteChurches by remember { mutableStateOf(churchStore.favorites()) }
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(preferences.getBoolean("dark", systemDark)) }
    var textSize by rememberSaveable { mutableIntStateOf(preferences.getInt("text_size", 0).coerceIn(0, 2)) }
    var selectedId by rememberSaveable { mutableStateOf(preferences.getString("stage_id", null)) }
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var selectorOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var searchOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var settingsOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var churchOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var churchPickerRequest by rememberSaveable { mutableIntStateOf(0) }
    var arrivalDraft by rememberSaveable(stateSaver = ArrivalDraft.Saver) { mutableStateOf<ArrivalDraft?>(null) }
    var churchId by rememberSaveable { mutableStateOf(preferences.getString("church_id", null)) }
    var session by remember {
        mutableStateOf(runCatching { Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java) }.getOrNull())
    }
    var now by remember(clock) { mutableStateOf(clock.instant()) }
    var reload by remember { mutableIntStateOf(0) }
    val loaded = remember(container, reload) { container.getStageMatcherResult(forceReload = reload > 0) }
    val matcher = loaded.getOrNull()
    val stages = matcher?.getAllStages().orEmpty()
    val sessionEngine = remember(stages, clock) { if (stages.isEmpty()) null else ServiceSessionEngine(stages, clock) }
    val position = session?.takeIf { sessionEngine?.isValid(it) == true }?.let { sessionEngine?.position(it, now) }
    val selectedStage = stages.firstOrNull { it.id == (position?.stageId ?: selectedId) }
    val parishes = container.parishes.getOrNull()
    val churches = remember(parishes, savedChurches) { ChurchDirectory.merge(parishes?.directory?.churches.orEmpty(), savedChurches) }
    val church = churches.firstOrNull { it.id == churchId } ?: parishes?.selected(null) ?: churches.firstOrNull()
    val contextEngine = remember(stages, clock) { if (stages.isEmpty()) null else ContextSuggestionEngine(stages, clock) }
    val suggestion = church?.let { contextEngine?.suggest(it, now) }
    val currentDate = now.atZone(ZoneId.of(church?.timezone ?: "Europe/Moscow")).toLocalDate()
    val readingDate = currentDate.toString()
    val readingDay = container.readings?.days?.firstOrNull { it.date == readingDate }
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = clock.instant(); delay(5_000L) }
        }
    }
    fun saveSession(value: ServiceSession) {
        session = value
        selectedId = value.anchorStageId
        preferences.edit().putString("session", Gson().toJson(value)).putString("stage_id", selectedId).apply()
        now = clock.instant()
    }

    fun clearReadingPositions() {
        val editor = preferences.edit()
        preferences.all.keys.filter { key -> key.startsWith("lyric_track_") || key.startsWith("lyric_line_") || key.startsWith("lyric_offset_") || key.startsWith("lyric_phrase_") || key.startsWith("lyric_saved_tempo_") }.forEach { editor.remove(it) }
        editor.apply()
        screenStateHolder.removeState(Screen.SERVICE.name)
    }
    fun saveSearchPosition(id: String, query: String?, date: String = readingDate) {
        if (!query.isNullOrBlank()) {
            val stage = matcher?.getStageById(id)
            val day = container.readings?.days?.firstOrNull { it.date == date }
            val tracks = stage?.let { PrayerTrackFactory.build(it, day) }.orEmpty()
            PrayerTextSearch.find(tracks, query)?.let { hit ->
                screenStateHolder.removeState(Screen.SERVICE.name)
                val track = tracks[hit.trackIndex]
                preferences.edit().putString("lyric_track_$id", track.id)
                    .putInt("lyric_line_${track.id}", hit.lineIndex).putString("lyric_phrase_${track.id}", track.lines[hit.lineIndex].id)
                    .putInt("lyric_saved_tempo_${track.id}", preferences.getInt("lyric_tempo", 12)).putLong("lyric_offset_${track.id}", 0L).apply()
            }
        }
    }
    fun selectStage(id: String, query: String? = null) {
        val engine = sessionEngine ?: return
        val existing = session?.takeIf(engine::isValid)
        if (existing == null) clearReadingPositions()
        saveSearchPosition(id, query)
        saveSession(existing?.let { engine.correct(it, id) } ?: engine.start(id))
        screen = Screen.SERVICE
    }
    fun acceptArrival(id: String, query: String? = null, fromSchedule: Boolean) {
        val draft = arrivalDraft ?: return
        val engine = sessionEngine ?: return
        val actualNow = clock.instant()
        now = actualNow
        if (church?.id != draft.churchId || stages.none { it.id == id }) return
        // A manual choice is valid today even when yesterday's time estimate has expired.
        val actualDate = actualNow.atZone(ZoneId.of(church?.timezone ?: "Europe/Moscow")).toLocalDate().toString()
        if (fromSchedule && draft.serviceDate != actualDate) return
        val newSession = engine.start(id, now = actualNow, autoAdvance = false,
            approximate = fromSchedule && draft.stageId != null,
            elapsedInStageMillis = if (fromSchedule && draft.stageId != null) draft.elapsedInStageMillis else 0L)
        if (draft.mode == ArrivalMode.NEW_SERVICE) clearReadingPositions()
        else screenStateHolder.removeState(Screen.SERVICE.name)
        if (!fromSchedule) saveSearchPosition(id, query, actualDate)
        saveSession(newSession)
        arrivalDraft = null
        screen = Screen.SERVICE
    }
    fun beginArrival(draft: ArrivalDraft, destination: Screen) {
        // A fresh flow resets its screens; back and recreation preserve the current draft.
        listOf(Screen.MANUAL, Screen.ARRIVAL, Screen.SEARCH).forEach { screenStateHolder.removeState(it.name) }
        arrivalDraft = draft
        screen = destination
    }
    fun openManual(mode: ArrivalMode) {
        val engine = sessionEngine
        val current = session?.takeIf { engine?.isValid(it) == true }
        val stopTimer = mode == ArrivalMode.CORRECTION && position?.autoAdvanceActive == true
        if (stopTimer && engine != null && current != null) {
            // Freeze the displayed stage, even if the clock crossed its boundary since the last tick.
            saveSession(engine.pause(current, now = now))
        }
        beginArrival(ArrivalDraft(church?.id, church?.shortName ?: "Храм не выбран", mode,
            ArrivalSource.MANUAL, automationStopped = stopTimer), Screen.MANUAL)
    }
    fun openOrder(origin: Screen) {
        // A fresh entry follows the current stage; search/back and recreation keep browsing position.
        screenStateHolder.removeState(Screen.STAGE_SELECTOR.name)
        selectorOrigin = origin
        screen = Screen.STAGE_SELECTOR
    }
    fun chooseChurch(id: String) {
        if (church?.id != id) {
            arrivalDraft = null
            clearReadingPositions()
            session = null; selectedId = null
            preferences.edit().remove("session").remove("stage_id").apply()
        }
        churchId = id; preferences.edit().putString("church_id", id).apply()
    }
    fun closeDiscovery() { churchPickerRequest++; screen = Screen.CHURCH }
    fun back() {
        val destination = when (screen) {
            Screen.MANUAL -> if (arrivalDraft?.mode == ArrivalMode.CORRECTION) Screen.SERVICE else Screen.MAIN
            Screen.ARRIVAL -> if (arrivalDraft?.source == ArrivalSource.MANUAL) Screen.MANUAL else Screen.MAIN
            Screen.STAGE_SELECTOR -> selectorOrigin
            Screen.SEARCH -> searchOrigin
            Screen.SETTINGS -> settingsOrigin
            Screen.SOURCES -> Screen.SETTINGS
            Screen.CHURCH -> churchOrigin
            Screen.DISCOVERY -> Screen.CHURCH
            else -> Screen.MAIN
        }
        if (screen == Screen.MANUAL || (screen == Screen.ARRIVAL && destination == Screen.MAIN)) arrivalDraft = null
        screen = destination
    }
    fun openExternal(intent: Intent) {
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "Не найдено приложение для открытия", Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(enabled = screen != Screen.MAIN) { back() }

    val navigationBar: @Composable () -> Unit = {
        GuideBottomBar(selected = when (screen) { Screen.CHURCH -> "church"; Screen.SETTINGS -> "settings"; else -> "home" },
            onHome = { screen = Screen.MAIN },
            onChurch = { churchOrigin = Screen.MAIN; screen = Screen.CHURCH },
            onSettings = { settingsOrigin = Screen.MAIN; screen = Screen.SETTINGS })
    }
    GuideTheme(dark = dark) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * listOf(1f, 1.15f, 1.3f)[textSize])) {
            Surface(Modifier.fillMaxSize()) {
                screenStateHolder.SaveableStateProvider(screen.name) {
                if (stages.isEmpty() || matcher == null) {
                    ContentUnavailable(onRetry = { reload++ })
                } else when (screen) {
                    Screen.MAIN -> MainScreen(
                        lastStage = selectedStage,
                        currentDate = currentDate,
                        savedEarlier = position?.requiresResume ?: (selectedStage != null),
                        church = church,
                        suggestion = suggestion,
                        onJoinService = {
                            if (church != null && suggestion != null) {
                                beginArrival(ArrivalDraft(church.id, church.shortName,
                                    ArrivalMode.NEW_SERVICE, ArrivalSource.SCHEDULE, suggestion.service.date,
                                    suggestion.service.time, suggestion.relation == ServiceRelation.Upcoming,
                                    suggestion.approximateStageId, suggestion.approximateElapsedInStageMillis), Screen.ARRIVAL)
                            }
                        },
                        onChurch = { churchOrigin = Screen.MAIN; screen = Screen.CHURCH },
                        onStart = { clearReadingPositions(); sessionEngine?.let { saveSession(it.start(stages.first().id)); screen = Screen.SERVICE } },
                        onResume = {
                            if (position == null) selectedStage?.let { stage -> sessionEngine?.let { saveSession(it.start(stage.id)) } }
                            screen = Screen.SERVICE
                        },
                        onFindStage = { openManual(ArrivalMode.NEW_SERVICE) },
                        onOrder = { openOrder(Screen.MAIN) },
                        bottomBar = navigationBar
                    )
                    Screen.MANUAL -> arrivalDraft?.let { draft ->
                        ManualMomentScreen(draft = draft,
                            onLandmark = { landmark ->
                                arrivalDraft = draft.copy(stageId = landmark.stageId, query = landmark.query)
                                // A newly chosen landmark must show its cue before the accept button.
                                screenStateHolder.removeState(Screen.ARRIVAL.name)
                                screen = Screen.ARRIVAL
                            },
                            onSearch = { searchOrigin = Screen.MANUAL; screen = Screen.SEARCH },
                            onBack = ::back)
                    }
                    Screen.ARRIVAL -> arrivalDraft?.let { draft ->
                        val stage = stages.firstOrNull { it.id == draft.stageId }
                        ArrivalScreen(draft = draft, stage = stage,
                            preparationCue = stages.firstOrNull { it.id == "preparation" }?.cue,
                            canAccept = church?.id == draft.churchId &&
                                (draft.source != ArrivalSource.SCHEDULE || draft.serviceDate == readingDate) &&
                                stages.any { it.id == (draft.stageId ?: "preparation") },
                            onAccept = { acceptArrival(draft.stageId ?: "preparation", draft.query,
                                fromSchedule = draft.source == ArrivalSource.SCHEDULE) },
                            onSearch = { searchOrigin = Screen.ARRIVAL; screen = Screen.SEARCH },
                            onBack = ::back)
                    }
                    Screen.SERVICE -> ServiceScreen(
                        stageMatcher = matcher,
                        stageId = selectedStage?.id,
                        onStageChange = { selectStage(it) },
                        onBack = { screen = Screen.MAIN },
                        dark = dark,
                        onDarkChange = { dark = !dark; preferences.edit().putBoolean("dark", dark).apply() },
                        onOpenSource = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                        readingDay = readingDay,
                        sessionPosition = position,
                        onToggleFollowing = {
                            val engine = sessionEngine
                            val current = session?.takeIf { engine?.isValid(it) == true }
                                ?: selectedStage?.let { engine?.start(it.id) }
                            if (engine != null && current != null) saveSession(if (position?.autoAdvanceActive == true) engine.pause(current) else engine.resume(current))
                        },
                        onSoundSettings = { openExternal(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                        onFinish = {
                            session = null; selectedId = null
                            preferences.edit().remove("session").remove("stage_id").apply()
                            screen = Screen.MAIN
                            Toast.makeText(context, "Если включали режим тишины, не забудьте проверить его после службы", Toast.LENGTH_LONG).show()
                        },
                        onSelectStage = { openOrder(Screen.SERVICE) },
                        onFindStage = { openManual(ArrivalMode.CORRECTION) },
                        onSettings = { settingsOrigin = Screen.SERVICE; screen = Screen.SETTINGS }
                    )
                    Screen.STAGE_SELECTOR, Screen.SEARCH -> StageSelector(
                        stageMatcher = matcher,
                        selectedId = if (screen == Screen.SEARCH && searchOrigin in setOf(Screen.MANUAL, Screen.ARRIVAL) && arrivalDraft != null) null else selectedStage?.id,
                        searchMode = screen == Screen.SEARCH,
                        readingDay = readingDay,
                        onSearch = { searchOrigin = Screen.STAGE_SELECTOR; screen = Screen.SEARCH },
                        onSelect = { id, query ->
                            if (screen == Screen.SEARCH && searchOrigin in setOf(Screen.MANUAL, Screen.ARRIVAL) && arrivalDraft != null)
                                acceptArrival(id, query, fromSchedule = false)
                            else selectStage(id, query)
                        },
                        onBack = ::back
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        dark = dark, textSize = textSize,
                        onDarkChange = { dark = it; preferences.edit().putBoolean("dark", it).apply() },
                        onTextSizeChange = { textSize = it; preferences.edit().putInt("text_size", it).apply() },
                        onSoundSettings = { openExternal(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                        onSources = { screen = Screen.SOURCES }, onBack = ::back,
                        showBack = settingsOrigin != Screen.MAIN,
                        bottomBar = { if (settingsOrigin == Screen.MAIN) navigationBar() }
                    )
                    Screen.SOURCES -> SourcesScreen(
                        sourceUrl = matcher.getSourceUrl(), contentNote = matcher.getContentNote(),
                        onOpenUrl = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                        onBack = ::back
                    )
                    Screen.CHURCH -> ChurchScreen(
                        churches = churches, selectedId = church?.id,
                        favorites = favoriteChurches,
                        onToggleFavorite = { id -> favoriteChurches = churchStore.toggleFavorite(id, churches.map { it.id }.toSet()) },
                        onAddChurch = { record ->
                            val saved = churchStore.save(record)
                            if (saved) savedChurches = churchStore.records()
                            saved
                        },
                        onDiscover = { screen = Screen.DISCOVERY },
                        pickerRequest = churchPickerRequest,
                        userStarts = userStarts,
                        onSaveStart = { value ->
                            val saved = church?.let { startStore.save(value, it) } == true
                            if (saved) userStarts = startStore.records()
                            saved
                        },
                        onRemoveStart = { date ->
                            val removed = church?.let { startStore.remove(it.id, date) } == true
                            if (removed) userStarts = startStore.records()
                            removed
                        },
                        currentDate = currentDate,
                        onSelect = ::chooseChurch,
                        onOpenUrl = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }, onBack = ::back,
                        showBack = churchOrigin != Screen.MAIN,
                        bottomBar = { if (churchOrigin == Screen.MAIN) navigationBar() }
                    )
                    Screen.DISCOVERY -> ChurchDiscoveryScreen(
                        discovery = container.placeDiscovery,
                        onBack = ::closeDiscovery,
                        onSave = { record ->
                            val saved = churchStore.save(record)
                            if (saved) {
                                savedChurches = churchStore.records()
                                chooseChurch(record.id)
                                screen = Screen.CHURCH
                            }
                            saved
                        },
                        onOpenUrl = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                    )
                }
                }
            }
        }
    }
}
