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
import ru.liturgyguide.service.ScheduleOverview
import ru.liturgyguide.service.ReadingContext
import ru.liturgyguide.service.ReadingAcceptanceStore
import ru.liturgyguide.service.ReadingContextFactory
import ru.liturgyguide.service.ReadingContextStore
import ru.liturgyguide.service.ServiceEntryResolver
import ru.liturgyguide.service.ServiceEntryResult
import ru.liturgyguide.service.ContextSuggestion
import java.time.Instant
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
    val readingContextStore = remember(preferences) { ReadingContextStore(preferences) }
    val readingAcceptanceStore = remember(preferences) { ReadingAcceptanceStore(preferences) }
    var readingContext by remember { mutableStateOf(readingContextStore.restore()) }
    var hasReadingMetadata by remember { mutableStateOf(preferences.contains(ReadingContextStore.CONTEXT_KEY)) }
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
    val parishes = container.parishes.getOrNull()
    val churches = remember(parishes, savedChurches) { ChurchDirectory.merge(parishes?.directory?.churches.orEmpty(), savedChurches) }
    val church = churches.firstOrNull { it.id == churchId } ?: parishes?.selected(null) ?: churches.firstOrNull()
    val contextEngine = remember(stages, clock) { if (stages.isEmpty()) null else ContextSuggestionEngine(stages, clock) }
    val overview = church?.let { contextEngine?.overview(it, now, userStarts) } ?: ScheduleOverview()
    val currentDate = now.atZone(ZoneId.of(church?.timezone ?: "Europe/Moscow")).toLocalDate()
    val invalidReadingMetadata = hasReadingMetadata && (readingContext == null || readingContext?.parishId != church?.id)
    val validSession = session?.takeIf { !invalidReadingMetadata && sessionEngine?.isValid(it) == true }
    val position = validSession?.let { sessionEngine?.position(it, now) }
    val selectedStage = if (invalidReadingMetadata) null else stages.firstOrNull { it.id == (position?.stageId ?: selectedId) }
    // Old versions did not store a reading date. Resolve it once from the saved anchor, including
    // restoration straight into SERVICE, rather than silently attaching an old bookmark to today.
    val legacyReadingContext = if (!hasReadingMetadata) validSession?.let { saved ->
        runCatching { ReadingContextFactory.manual(church, Instant.ofEpochMilli(saved.anchorEpochMillis)) }.getOrNull()
    } else null
    val activeReadingContext = (readingContext?.takeIf { !invalidReadingMetadata } ?: legacyReadingContext)
        ?.takeIf { selectedStage != null }
    val readingNeedsExplicitDate = selectedStage != null && activeReadingContext == null
    val visibleScreen = if (screen == Screen.SERVICE && (invalidReadingMetadata || activeReadingContext == null)) Screen.MAIN else screen
    LaunchedEffect(visibleScreen, screen) { if (visibleScreen != screen) screen = visibleScreen }
    val readingDate = activeReadingContext?.serviceDate ?: currentDate.toString()
    val readingDay = container.readings?.days?.firstOrNull { it.date == readingDate }
    val searchReadingDay = if (arrivalDraft?.mode == ArrivalMode.NEW_SERVICE)
        container.readings?.days?.firstOrNull { it.date == currentDate.toString() } else readingDay
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = clock.instant(); delay(5_000L) }
        }
    }
    fun clearReadingPositions() {
        val editor = preferences.edit()
        preferences.all.keys.filter(ReadingAcceptanceStore::isBookmarkKey).forEach { editor.remove(it) }
        editor.apply()
        screenStateHolder.removeState(Screen.SERVICE.name)
    }

    // Date, stage and bookmark reset form one accepted reading, including after process death.
    fun beginReading(value: ServiceSession, acceptedContext: ReadingContext, resetBookmarks: Boolean, resetReaderUi: Boolean = true): Boolean {
        val stored = readingAcceptanceStore.accept(acceptedContext, value, resetBookmarks)
        if (!stored) {
            Toast.makeText(context, "Не удалось сохранить место чтения. Проверьте свободное место", Toast.LENGTH_LONG).show()
            return false
        }
        if (resetReaderUi) screenStateHolder.removeState(Screen.SERVICE.name)
        readingContext = acceptedContext; hasReadingMetadata = true
        session = value; selectedId = value.anchorStageId; now = clock.instant()
        return true
    }

    fun saveSession(value: ServiceSession): Boolean {
        val accepted = activeReadingContext ?: ReadingContextFactory.manual(church, clock.instant()) ?: return false
        return beginReading(value, accepted, resetBookmarks = false, resetReaderUi = false)
    }

    fun openScheduledService(selected: ContextSuggestion) {
        val parish = church ?: return
        val engine = contextEngine ?: return
        val actualNow = clock.instant()
        now = actualNow
        when (val result = ServiceEntryResolver(engine).resolve(selected, parish, actualNow, startStore.records())) {
            ServiceEntryResult.Expired -> Toast.makeText(context,
                "Время службы изменилось. Выберите актуальную карточку", Toast.LENGTH_LONG).show()
            is ServiceEntryResult.Available -> {
                val candidate = result.suggestion
                val stageId = candidate.approximateStageId?.takeIf { id -> stages.any { it.id == id } }
                    ?: stages.first().id
                val value = sessionEngine?.start(stageId, now = actualNow, autoAdvance = false,
                    approximate = candidate.relation == ServiceRelation.InProgress && candidate.approximateStageId != null,
                    elapsedInStageMillis = candidate.approximateElapsedInStageMillis) ?: return
                if (beginReading(value, result.context, resetBookmarks = true)) {
                    arrivalDraft = null; screen = Screen.SERVICE
                }
            }
        }
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
        val actualNow = clock.instant()
        val existing = validSession
        val accepted = activeReadingContext ?: ReadingContextFactory.manual(church, actualNow) ?: return
        val value = existing?.let { engine.correct(it, id, actualNow) } ?: engine.start(id, now = actualNow)
        if (!beginReading(value, accepted, resetBookmarks = existing == null)) return
        saveSearchPosition(id, query, accepted.serviceDate)
        screen = Screen.SERVICE
    }
    fun acceptArrival(id: String, query: String? = null, fromSchedule: Boolean) {
        val draft = arrivalDraft ?: return
        val engine = sessionEngine ?: return
        val actualNow = clock.instant()
        now = actualNow
        if (church?.id != draft.churchId || stages.none { it.id == id }) return
        // Schedule entry now goes straight through the resolver. Old unaccepted drafts cannot bypass it.
        if (fromSchedule) { screen = Screen.MAIN; arrivalDraft = null; return }
        val accepted = if (draft.mode == ArrivalMode.CORRECTION) activeReadingContext
            ?: ReadingContextFactory.manual(church, actualNow) else ReadingContextFactory.manual(church, actualNow)
        if (accepted == null) return
        val newSession = engine.start(id, now = actualNow, autoAdvance = false)
        if (!beginReading(newSession, accepted, resetBookmarks = draft.mode == ArrivalMode.NEW_SERVICE)) return
        saveSearchPosition(id, query, accepted.serviceDate)
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
            if (!saveSession(engine.pause(current, now = now))) return
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
            session = null; selectedId = null; readingContext = null; hasReadingMetadata = false
            preferences.edit().remove("session").remove("stage_id").remove(ReadingContextStore.CONTEXT_KEY).apply()
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
                screenStateHolder.SaveableStateProvider(visibleScreen.name) {
                if (stages.isEmpty() || matcher == null) {
                    ContentUnavailable(onRetry = { reload++ })
                } else when (visibleScreen) {
                    Screen.MAIN -> MainScreen(
                        lastStage = selectedStage,
                        currentDate = currentDate,
                        readingNotice = if (invalidReadingMetadata) "Не удалось восстановить сохраненное чтение. Выберите службу или начните с начала" else null,
                        savedReadingNote = if (readingNeedsExplicitDate) "Дата не сохранена · откроется на сегодня" else null,
                        church = church,
                        overview = overview,
                        onOpenService = ::openScheduledService,
                        onChurch = { churchOrigin = Screen.MAIN; screen = Screen.CHURCH },
                        onStart = {
                            val actualNow = clock.instant()
                            val accepted = ReadingContextFactory.manual(church, actualNow)
                            val value = sessionEngine?.start(stages.first().id, now = actualNow)
                            if (accepted != null && value != null && beginReading(value, accepted, resetBookmarks = true)) screen = Screen.SERVICE
                        },
                        onResume = {
                            val actualNow = clock.instant()
                            val accepted = activeReadingContext ?: ReadingContextFactory.manual(church, actualNow)
                            val value = validSession
                                ?: selectedStage?.let { stage -> sessionEngine?.start(stage.id, now = actualNow) }
                            if (accepted != null && value != null && beginReading(value, accepted, resetBookmarks = false, resetReaderUi = false)) screen = Screen.SERVICE
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
                        serviceDate = readingDate,
                        onToggleFollowing = {
                            val engine = sessionEngine
                            val current = session?.takeIf { engine?.isValid(it) == true }
                                ?: selectedStage?.let { engine?.start(it.id) }
                            if (engine != null && current != null) saveSession(if (position?.autoAdvanceActive == true) engine.pause(current) else engine.resume(current))
                        },
                        onSoundSettings = { openExternal(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                        onFinish = {
                            session = null; selectedId = null; readingContext = null; hasReadingMetadata = false
                            preferences.edit().remove("session").remove("stage_id").remove(ReadingContextStore.CONTEXT_KEY).apply()
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
                        readingDay = searchReadingDay,
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
