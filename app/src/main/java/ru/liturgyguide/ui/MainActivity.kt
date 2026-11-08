package ru.liturgyguide.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.Instant
import com.google.gson.Gson
import java.time.Duration
import java.time.LocalDate
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.delay

private enum class Screen { MAIN, MANUAL, ARRIVAL, SERVICE, STAGE_SELECTOR, SEARCH, SETTINGS, SOURCES, CHURCH, DISCOVERY }
private enum class ChurchSelectionOrigin { FIRST_HOME, DIRECTORY }

class MainActivity : ComponentActivity() {
    private val activityForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ActivityForegroundState() else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { AppNavigation((application as LiturgyGuideApplication).container, activityForeground = activityForeground) }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            super.onTopResumedActivityChanged(isTopResumedActivity)
            activityForeground?.onTopResumedActivityChanged(isTopResumedActivity)
        }
    }
}

@Composable
fun AppNavigation(container: AppContainer, clock: Clock = Clock.systemUTC(),
    monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    activityForeground: ActivityForegroundState? = null) {
    val context = LocalContext.current
    val screenStateHolder = rememberSaveableStateHolder()
    val preferences = remember { context.getSharedPreferences("guide_preferences", 0) }
    val churchStore = remember(preferences) { ChurchDirectoryStore(preferences) }
    val startStore = remember(preferences) { UserServiceStartStore(preferences) }
    var userStarts by remember { mutableStateOf(startStore.records()) }
    val readingContextStore = remember(preferences) { ReadingContextStore(preferences) }
    val readingAcceptanceStore = remember(preferences) { ReadingAcceptanceStore(preferences) }
    val playbackStore = remember(preferences) { ServicePlaybackStore(preferences) }
    var readingContext by remember { mutableStateOf(readingContextStore.restore()) }
    var hasReadingMetadata by remember { mutableStateOf(preferences.contains(ReadingContextStore.CONTEXT_KEY)) }
    var hasPlaybackSnapshot by remember { mutableStateOf(preferences.contains(ServicePlaybackStore.PLAYBACK_KEY)) }
    var playbackRevision by remember { mutableIntStateOf(0) }
    var preferredOriginal by rememberSaveable { mutableStateOf(preferences.getBoolean("lyric_original", false)) }
    var savedChurches by remember { mutableStateOf(churchStore.records()) }
    var favoriteChurches by remember { mutableStateOf(churchStore.favorites()) }
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(preferences.getBoolean("dark", systemDark)) }
    var textSize by rememberSaveable { mutableIntStateOf(preferences.getInt("text_size", 0).coerceIn(0, 2)) }
    var selectedId by rememberSaveable { mutableStateOf(runCatching { preferences.getString("stage_id", null) }.getOrNull()) }
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var selectorOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var searchOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var settingsOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var churchOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var churchSelectionOrigin by rememberSaveable { mutableStateOf(ChurchSelectionOrigin.DIRECTORY) }
    var churchPickerRequest by rememberSaveable { mutableIntStateOf(0) }
    var churchSearchQuery by rememberSaveable { mutableStateOf("") }
    var churchSearchMode by rememberSaveable { mutableStateOf(ChurchSearchMode.NAME) }
    var personalTimeRequest by rememberSaveable { mutableIntStateOf(0) }
    var arrivalDraft by rememberSaveable(stateSaver = ArrivalDraft.Saver) { mutableStateOf<ArrivalDraft?>(null) }
    var arrivalRecoveryNotice by rememberSaveable { mutableStateOf(false) }
    var hasChurchSelection by rememberSaveable { mutableStateOf(preferences.contains("church_id")) }
    var churchId by rememberSaveable { mutableStateOf(runCatching { preferences.getString("church_id", null) }.getOrNull()) }
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
    val validatedSession = session?.takeIf { runCatching { sessionEngine?.isValid(it) == true }.getOrDefault(false) }
    val church = ChurchSelection.resolve(churches, hasChurchSelection, churchId,
        hasReadingMetadata, readingContext, hasPlaybackSnapshot,
        hasLegacyReading = validatedSession != null || stages.any { it.id == selectedId },
        legacyDefaultId = parishes?.directory?.defaultChurchId)
    val contextEngine = remember(stages, clock) { if (stages.isEmpty()) null else ContextSuggestionEngine(stages, clock) }
    val overview = church?.let { contextEngine?.overview(it, now, userStarts) } ?: ScheduleOverview()
    val currentDate = now.atZone(ZoneId.of(church?.timezone ?: "Europe/Moscow")).toLocalDate()
    val invalidReadingMetadata = hasReadingMetadata && (readingContext == null || readingContext?.parishId != church?.id)
    val validSession = validatedSession?.takeIf { !invalidReadingMetadata }
    // A legacy running timer is an address only. Never advance its saved stage from wall clock.
    val legacyStage = if (invalidReadingMetadata) null else stages.firstOrNull { it.id == (validSession?.anchorStageId ?: selectedId) }
    val legacyReadingContext = if (!hasReadingMetadata) validSession?.let { saved ->
        runCatching { ReadingContextFactory.manual(church, Instant.ofEpochMilli(saved.anchorEpochMillis)) }.getOrNull()
    } else null
    val availableContext = (readingContext?.takeIf { !invalidReadingMetadata } ?: legacyReadingContext)
    val availablePlan = remember(stages, availableContext?.serviceDate, container) {
        availableContext?.let { accepted -> runCatching {
            ServicePlaybackPlan.build(stages, LocalDate.parse(accepted.serviceDate),
                container.readings?.days?.firstOrNull { it.date == accepted.serviceDate })
        }.getOrNull() }
    }
    val restored = remember(availablePlan, availableContext, hasPlaybackSnapshot, hasReadingMetadata, session, playbackRevision) {
        if (!hasPlaybackSnapshot) PlaybackRestoreResult.Absent
        else if (!hasReadingMetadata || invalidReadingMetadata || availablePlan == null || availableContext == null)
            PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.CONTEXT_MISMATCH)
        else playbackStore.restore(availablePlan, availableContext)
    }
    val invalidRecovery = invalidReadingMetadata || restored is PlaybackRestoreResult.Invalid
    val restoredReading = (restored as? PlaybackRestoreResult.Valid)?.let { result ->
        ActiveServicePlayback(requireNotNull(availablePlan), result.stored.context, result.state,
            isApproximate = validSession?.anchorIsApproximate == true)
    }
    val legacyCandidate = remember(availablePlan, availableContext, legacyStage?.id, hasPlaybackSnapshot, invalidRecovery) {
        if (hasPlaybackSnapshot || invalidRecovery || availablePlan == null || availableContext == null || legacyStage == null) null
        else runCatching {
            LegacyPlaybackMigration.resolve(availablePlan, legacyStage.id, preferences.all)?.let { state ->
                ActiveServicePlayback(availablePlan, availableContext, state,
                    isApproximate = validSession?.anchorIsApproximate == true || validSession?.autoAdvance == true)
            }
        }.getOrNull()
    }
    var restoredNotice by rememberSaveable {
        mutableStateOf(validSession?.let { saved -> runCatching {
            Duration.between(Instant.ofEpochMilli(saved.anchorEpochMillis), clock.instant()) >= ServiceSessionEngine.STALE_AFTER
        }.getOrDefault(false) } == true)
    }
    val currentClock by rememberUpdatedState(clock)
    val currentMonotonicMillis by rememberUpdatedState(monotonicMillis)
    fun savingFailed() {
        Toast.makeText(context, "Не удалось сохранить место чтения. Проверьте свободное место", Toast.LENGTH_LONG).show()
    }
    val playbackWriter by rememberUpdatedState(ServicePlaybackWriter { value, reset, sampled ->
        val snapshot = value.engine.snapshot(value.state, sampled)
        val marker = ServiceSession(value.stage.id, currentClock.instant().toEpochMilli(),
            autoAdvance = false, elapsedInStageMillis = 0, anchorIsApproximate = value.isApproximate)
        val stored = readingAcceptanceStore.acceptPlayback(value.plan, value.context, snapshot, marker, reset,
            PlaybackBookmark.at(value.plan, value.state))
        if (stored) {
            readingContext = value.context; hasReadingMetadata = true; hasPlaybackSnapshot = true
            session = marker; selectedId = marker.anchorStageId; restoredNotice = false
            playbackRevision++
        } else savingFailed()
        stored
    })
    // This owner is not keyed by stage, date, language, theme or a new acceptance.
    val player = remember(preferences) {
        ServicePlayerState(restoredReading, { currentMonotonicMillis() },
            ServicePlaybackWriter { value, reset, sampled -> playbackWriter.save(value, reset, sampled) },
            savedPhrase = { track ->
                val values = preferences.all
                val phraseKey = "lyric_phrase_${track.id}"
                if (values.containsKey(phraseKey)) track.lines.firstOrNull { it.id == values[phraseKey] }?.id
                    ?: track.lines.first().id
                else (values["lyric_line_${track.id}"] as? Int)?.let { track.lines.getOrNull(it)?.id }
                    ?: track.lines.first().id
            })
    }
    val churchSelectionGuard = remember(preferences, player) { ChurchSelectionGuard() }
    var pendingChurchSelection by remember { mutableStateOf<ChurchSelectionGuard.Pending?>(null) }
    var churchSelectionFailed by remember { mutableStateOf(false) }
    fun cancelChurchSelection() {
        churchSelectionGuard.cancel()
        pendingChurchSelection = null
        churchSelectionFailed = false
    }
    DisposableEffect(churchSelectionGuard) {
        onDispose { churchSelectionGuard.close() }
    }
    val selectedStage = if (invalidRecovery) null else player.reading?.stage ?: restoredReading?.stage ?: legacyStage
    val activeReadingContext = if (invalidRecovery) null else player.reading?.context ?: availableContext?.takeIf { selectedStage != null }
    val readingNeedsExplicitDate = selectedStage != null && activeReadingContext == null
    val scopedSearch = screen == Screen.SEARCH && searchOrigin in setOf(Screen.MANUAL, Screen.ARRIVAL)
    val validArrivalDraft = arrivalDraft?.takeIf { it.isValidFor(church) }
    val invalidArrival = (screen == Screen.MANUAL || screen == Screen.ARRIVAL || scopedSearch) && validArrivalDraft == null
    val visibleScreen = if (invalidArrival || (screen == Screen.SERVICE && (invalidRecovery || player.reading == null)))
        Screen.MAIN else screen
    val readingDate = activeReadingContext?.serviceDate ?: currentDate.toString()
    val readingDay = container.readings?.days?.firstOrNull { it.date == readingDate }
    val searchReadingDay = when {
        invalidArrival -> null
        validArrivalDraft?.pendingServiceContext != null -> container.readings?.days?.firstOrNull {
            it.date == validArrivalDraft.pendingServiceContext.serviceDate
        }
        validArrivalDraft?.mode == ArrivalMode.NEW_SERVICE ->
            container.readings?.days?.firstOrNull { it.date == currentDate.toString() }
        else -> readingDay
    }
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lifecycleResumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    LaunchedEffect(lifecycle, clock) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = clock.instant(); delay(5_000L) }
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            lifecycleResumed = event == Lifecycle.Event.ON_RESUME || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                lifecycleResumed = false
                player.pause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.pause() }
    }
    DisposableEffect(player, activityForeground) {
        val unbind = activityForeground?.bind { player.pause() }
        onDispose { unbind?.invoke() }
    }
    val playing = player.reading?.state?.isPlaying == true
    val topResumed = activityForeground?.isTopResumed != false
    LaunchedEffect(player, visibleScreen, lifecycleResumed, activityForeground, topResumed, playing, player.generation) {
        if (visibleScreen != Screen.SERVICE || !lifecycleResumed || !topResumed) {
            player.pause()
        } else if (playing) {
            val generation = player.generation
            while (player.reading?.state?.isPlaying == true && generation == player.generation &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activityForeground?.isTopResumed != false) {
                player.tick(generation)
                delay(250L)
            }
        }
    }

    fun navigate(destination: Screen) {
        cancelChurchSelection()
        if (destination != Screen.CHURCH && destination != Screen.DISCOVERY) {
            churchSelectionOrigin = ChurchSelectionOrigin.DIRECTORY
        }
        player.pause()
        screen = destination
    }
    fun beginReading(value: ActiveServicePlayback, resetBookmarks: Boolean, resetReaderUi: Boolean = true,
        preserveRestoreNotice: Boolean = false): Boolean {
        val notice = restoredNotice
        if (!player.accept(value, resetBookmarks)) return false
        arrivalRecoveryNotice = false
        if (resetReaderUi) screenStateHolder.removeState(Screen.SERVICE.name)
        if (preserveRestoreNotice) restoredNotice = notice
        now = clock.instant()
        return true
    }
    fun candidate(accepted: ReadingContext, stageId: String, query: String? = null,
        approximate: Boolean = false, speed: PlaybackSpeed = PlaybackSpeed.NORMAL): ActiveServicePlayback? = runCatching {
        val plan = player.reading?.takeIf { it.context == accepted }?.plan
            ?: ServicePlaybackPlan.build(stages, LocalDate.parse(accepted.serviceDate),
                container.readings?.days?.firstOrNull { it.date == accepted.serviceDate })
        val engine = ServicePlaybackEngine(plan)
        var state = engine.start(stageId, speed)
        if (!query.isNullOrBlank()) {
            val tracks = plan.tracksByStage.getValue(stageId)
            PrayerTextSearch.find(tracks, query)?.let { hit ->
                val track = tracks[hit.trackIndex]
                state = engine.seekLine(state, track.id, track.lines[hit.lineIndex].id)
            }
        }
        ActiveServicePlayback(plan, accepted, state, approximate)
    }.getOrNull()
    fun recoverArrival() {
        arrivalDraft = null
        searchOrigin = Screen.MAIN
        arrivalRecoveryNotice = true
        navigate(Screen.MAIN)
    }
    fun isCurrentArrival(draft: ArrivalDraft, origin: Screen): Boolean =
        screen == origin && arrivalDraft === draft && draft.isValidFor(church)
    fun beginArrival(draft: ArrivalDraft, destination: Screen) {
        if (!draft.isValidFor(church)) { recoverArrival(); return }
        listOf(Screen.MANUAL, Screen.ARRIVAL, Screen.SEARCH).forEach { screenStateHolder.removeState(it.name) }
        arrivalRecoveryNotice = false
        arrivalDraft = draft
        navigate(destination)
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
                val suggested = result.suggestion
                if (suggested.estimatedGuideExhausted) {
                    beginArrival(ArrivalDraft(parish.id, parish.shortName, ArrivalMode.NEW_SERVICE,
                        ArrivalSource.MANUAL, pendingServiceContext = result.context), Screen.MANUAL)
                    return
                }
                val stageId = suggested.approximateStageId?.takeIf { id -> stages.any { it.id == id } } ?: stages.first().id
                val value = candidate(result.context, stageId,
                    approximate = suggested.relation == ServiceRelation.InProgress && suggested.approximateStageId != null) ?: return
                if (beginReading(value, resetBookmarks = true)) {
                    arrivalDraft = null; navigate(Screen.SERVICE)
                }
            }
        }
    }
    fun selectStage(id: String, query: String? = null) {
        val existing = player.reading
        val accepted = existing?.context ?: activeReadingContext ?: ReadingContextFactory.manual(church, clock.instant()) ?: return
        val value = candidate(accepted, id, query, speed = existing?.state?.speed ?: PlaybackSpeed.NORMAL) ?: return
        if (!beginReading(value, resetBookmarks = selectedStage == null)) return
        navigate(Screen.SERVICE)
    }
    fun acceptArrival(id: String, query: String? = null, fromSchedule: Boolean) {
        val draft = arrivalDraft?.takeIf { it.isValidFor(church) }
        if (draft == null) { recoverArrival(); return }
        val actualNow = clock.instant()
        now = actualNow
        if (stages.none { it.id == id }) return
        if (fromSchedule) { navigate(Screen.MAIN); arrivalDraft = null; return }
        val accepted = draft.pendingServiceContext ?: if (draft.mode == ArrivalMode.CORRECTION)
            player.reading?.context ?: activeReadingContext else ReadingContextFactory.manual(church, actualNow)
        if (accepted == null) return
        val value = candidate(accepted, id, query, speed = if (draft.mode == ArrivalMode.CORRECTION)
            player.reading?.state?.speed ?: PlaybackSpeed.NORMAL else PlaybackSpeed.NORMAL) ?: return
        if (!beginReading(value, resetBookmarks = draft.mode == ArrivalMode.NEW_SERVICE)) return
        arrivalDraft = null
        navigate(Screen.SERVICE)
    }
    fun openManual(mode: ArrivalMode) {
        val stopped = mode == ArrivalMode.CORRECTION && player.reading?.state?.isPlaying == true
        player.pause()
        beginArrival(ArrivalDraft(church?.id, church?.shortName ?: "Храм не выбран", mode,
            ArrivalSource.MANUAL, automationStopped = stopped), Screen.MANUAL)
    }
    fun openOrder(origin: Screen) {
        player.pause()
        screenStateHolder.removeState(Screen.STAGE_SELECTOR.name)
        selectorOrigin = origin
        navigate(Screen.STAGE_SELECTOR)
    }
    fun clearAcceptedReading(nextChurchId: String? = null): Boolean {
        player.pause()
        if (!readingAcceptanceStore.clearReading(nextChurchId)) { savingFailed(); return false }
        player.forgetAccepted()
        session = null; selectedId = null; readingContext = null
        hasReadingMetadata = false; hasPlaybackSnapshot = false; restoredNotice = false
        playbackRevision++
        screenStateHolder.removeState(Screen.SERVICE.name)
        arrivalDraft = null
        return true
    }
    fun commitChurchChoice(id: String, preserveReading: Boolean): Boolean {
        if (preserveReading) {
            if (hasChurchSelection && churchId == id) return true
            if (!readingAcceptanceStore.confirmChurch(id)) { savingFailed(); return false }
            churchId = id; hasChurchSelection = true
            return true
        }
        if (!clearAcceptedReading(id)) return false
        churchId = id; hasChurchSelection = true
        return true
    }
    fun churchChoiceSnapshot(): ChurchSelectionSnapshot? = runCatching {
        ChurchSelectionSnapshot.capture(church?.id, player.generation, player.reading != null, preferences.all)
    }.getOrNull()
    fun availableChurchIds(): Set<String> = ChurchDirectory.merge(
        parishes?.directory?.churches.orEmpty(), churchStore.records()).map { it.id }.toSet()
    fun chooseChurch(id: String, onSelected: () -> Unit): ChurchSelectionResult {
        val snapshot = churchChoiceSnapshot() ?: return ChurchSelectionResult.FAILED
        val completionScreen = when {
            churchSelectionOrigin == ChurchSelectionOrigin.FIRST_HOME -> Screen.MAIN
            screen == Screen.DISCOVERY -> Screen.CHURCH
            else -> null
        }
        val result = churchSelectionGuard.request(id, snapshot, availableChurchIds(), ::commitChurchChoice) {
            // Clean up the child's picker or form before leaving its saved screen state.
            onSelected()
            churchSelectionOrigin = ChurchSelectionOrigin.DIRECTORY
            completionScreen?.let(::navigate)
        }
        pendingChurchSelection = churchSelectionGuard.pending
        churchSelectionFailed = false
        return result
    }
    fun openChurch(fromHome: Boolean) {
        churchOrigin = Screen.MAIN
        churchSelectionOrigin = if (fromHome && church == null) ChurchSelectionOrigin.FIRST_HOME
            else ChurchSelectionOrigin.DIRECTORY
        if (church == null) churchPickerRequest++
        navigate(Screen.CHURCH)
    }
    fun openPersonalTime() {
        if (church == null) { openChurch(fromHome = true); return }
        churchOrigin = Screen.MAIN
        churchSelectionOrigin = ChurchSelectionOrigin.DIRECTORY
        personalTimeRequest++
        navigate(Screen.CHURCH)
    }
    fun closeDiscovery() { churchPickerRequest++; navigate(Screen.CHURCH) }
    fun back() {
        if (invalidArrival) { recoverArrival(); return }
        // Both root and screen Back callbacks must reopen the saved picker.
        if (screen == Screen.DISCOVERY) { closeDiscovery(); return }
        val destination = when (screen) {
            Screen.MANUAL -> if (arrivalDraft?.mode == ArrivalMode.CORRECTION) Screen.SERVICE else Screen.MAIN
            Screen.ARRIVAL -> if (arrivalDraft?.source == ArrivalSource.MANUAL) Screen.MANUAL else Screen.MAIN
            Screen.STAGE_SELECTOR -> selectorOrigin
            Screen.SEARCH -> searchOrigin
            Screen.SETTINGS -> settingsOrigin
            Screen.SOURCES -> Screen.SETTINGS
            Screen.CHURCH -> churchOrigin
            else -> Screen.MAIN
        }
        if (screen == Screen.MANUAL || (screen == Screen.ARRIVAL && destination == Screen.MAIN)) arrivalDraft = null
        navigate(destination)
    }
    fun openExternal(intent: Intent) {
        player.pause()
        try { context.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "Не найдено приложение для открытия", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(invalidArrival) {
        // Never render a scoped search without its draft: its selection cannot fall through
        // to the old accepted reading or silently acquire today's date.
        if (invalidArrival) recoverArrival()
    }
    // A saved SERVICE screen may be a 0.4 restoration. Migrate before rendering a live reader.
    LaunchedEffect(screen, player.reading == null, invalidRecovery, legacyCandidate, restoredReading) {
        if (screen == Screen.SERVICE && player.reading == null) {
            val pending = if (invalidRecovery) null else restoredReading ?: legacyCandidate
            if (pending == null || !beginReading(pending, resetBookmarks = false, resetReaderUi = false,
                    preserveRestoreNotice = true)) screen = Screen.MAIN
        } else if (screen == Screen.SERVICE && invalidRecovery) navigate(Screen.MAIN)
    }
    BackHandler(enabled = screen != Screen.MAIN) { back() }

    val navigationBar: @Composable () -> Unit = {
        GuideBottomBar(selected = when (screen) { Screen.CHURCH -> "church"; Screen.SETTINGS -> "settings"; else -> "home" },
            onHome = { navigate(Screen.MAIN) },
            onChurch = { openChurch(fromHome = false) },
            onSettings = { settingsOrigin = Screen.MAIN; navigate(Screen.SETTINGS) })
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
                        readingNotice = when {
                            invalidArrival || arrivalRecoveryNotice -> "Не удалось восстановить выбранную службу. Выберите ее заново"
                            invalidRecovery -> "Не удалось восстановить сохраненное чтение. Выберите службу или начните с начала"
                            else -> null
                        },
                        savedReadingNote = if (readingNeedsExplicitDate) "Дата не сохранена · откроется на сегодня" else null,
                        church = church,
                        overview = overview,
                        onOpenService = ::openScheduledService,
                        onChurch = { openChurch(fromHome = true) },
                        onPersonalTime = ::openPersonalTime,
                        onStart = {
                            val accepted = ReadingContextFactory.manual(church, clock.instant())
                            val value = accepted?.let { candidate(it, stages.first().id) }
                            if (value != null && beginReading(value, resetBookmarks = true)) navigate(Screen.SERVICE)
                        },
                        onResume = {
                            if (!invalidRecovery) {
                                if (player.reading != null) navigate(Screen.SERVICE)
                                else {
                                    // A bookmark without a usable old date is explicitly opened on today.
                                    val pending = restoredReading ?: legacyCandidate ?: selectedStage?.let { stage ->
                                        ReadingContextFactory.manual(church, clock.instant())?.let { accepted ->
                                            candidate(accepted, stage.id)?.let { value ->
                                                val migrated = LegacyPlaybackMigration.resolve(value.plan, stage.id, preferences.all)
                                                value.copy(state = migrated ?: value.state)
                                            }
                                        }
                                    }
                                    if (pending != null && beginReading(pending, resetBookmarks = false,
                                            resetReaderUi = false, preserveRestoreNotice = true)) navigate(Screen.SERVICE)
                                }
                            }
                        },
                        onFindStage = { openManual(ArrivalMode.NEW_SERVICE) },
                        onOrder = { openOrder(Screen.MAIN) },
                        bottomBar = navigationBar
                    )
                    Screen.MANUAL -> validArrivalDraft?.let { draft ->
                        ManualMomentScreen(draft = draft,
                            onLandmark = landmark@{ landmark ->
                                if (!isCurrentArrival(draft, Screen.MANUAL)) return@landmark
                                arrivalDraft = draft.copy(stageId = landmark.stageId, query = landmark.query)
                                // A newly chosen landmark must show its cue before the accept button.
                                screenStateHolder.removeState(Screen.ARRIVAL.name)
                                navigate(Screen.ARRIVAL)
                            },
                            onSearch = {
                                if (isCurrentArrival(draft, Screen.MANUAL)) {
                                    searchOrigin = Screen.MANUAL; navigate(Screen.SEARCH)
                                }
                            },
                            onBack = ::back)
                    }
                    Screen.ARRIVAL -> validArrivalDraft?.let { draft ->
                        val stage = stages.firstOrNull { it.id == draft.stageId }
                        ArrivalScreen(draft = draft, stage = stage,
                            preparationCue = stages.firstOrNull { it.id == "preparation" }?.cue,
                            canAccept = draft.isValidFor(church) &&
                                (draft.source != ArrivalSource.SCHEDULE || draft.serviceDate == readingDate) &&
                                stages.any { it.id == (draft.stageId ?: "preparation") },
                            onAccept = {
                                if (isCurrentArrival(draft, Screen.ARRIVAL)) acceptArrival(draft.stageId ?: "preparation", draft.query,
                                    fromSchedule = draft.source == ArrivalSource.SCHEDULE)
                            },
                            onSearch = {
                                if (isCurrentArrival(draft, Screen.ARRIVAL)) {
                                    searchOrigin = Screen.ARRIVAL; navigate(Screen.SEARCH)
                                }
                            },
                            onBack = ::back)
                    }
                    Screen.SERVICE -> ServiceScreen(
                        player = player,
                        onStageChange = { selectStage(it) },
                        onBack = { navigate(Screen.MAIN) },
                        dark = dark,
                        onDarkChange = { dark = !dark; preferences.edit().putBoolean("dark", dark).apply() },
                        onOpenSource = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                        preferredOriginal = preferredOriginal,
                        onOriginalChange = { preferredOriginal = it; preferences.edit().putBoolean("lyric_original", it).apply() },
                        restoredNotice = restoredNotice,
                        onSoundSettings = { openExternal(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                        onFinish = {
                            if (clearAcceptedReading()) {
                                navigate(Screen.MAIN)
                                Toast.makeText(context, "Если включали режим тишины, не забудьте проверить его после службы", Toast.LENGTH_LONG).show()
                            }
                        },
                        onSelectStage = { openOrder(Screen.SERVICE) },
                        onFindStage = { openManual(ArrivalMode.CORRECTION) },
                        onSettings = { settingsOrigin = Screen.SERVICE; navigate(Screen.SETTINGS) }
                    )
                    Screen.STAGE_SELECTOR, Screen.SEARCH -> StageSelector(
                        stageMatcher = matcher,
                        selectedId = if (scopedSearch) null else selectedStage?.id,
                        searchMode = screen == Screen.SEARCH,
                        readingDay = searchReadingDay,
                        onSearch = { searchOrigin = Screen.STAGE_SELECTOR; navigate(Screen.SEARCH) },
                        onSelect = select@{ id, query ->
                            if (scopedSearch) {
                                // A callback from the discarded scoped search never becomes an
                                // ordinary selector for the previous accepted reading.
                                if (validArrivalDraft == null || !isCurrentArrival(validArrivalDraft, Screen.SEARCH)) return@select
                                acceptArrival(id, query, fromSchedule = false)
                            } else selectStage(id, query)
                        },
                        onBack = ::back
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        dark = dark, textSize = textSize,
                        onDarkChange = { dark = it; preferences.edit().putBoolean("dark", it).apply() },
                        onTextSizeChange = { textSize = it; preferences.edit().putInt("text_size", it).apply() },
                        onSoundSettings = { openExternal(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                        onSources = { navigate(Screen.SOURCES) }, onBack = ::back,
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
                        onDiscover = { churchSearchMode = ChurchSearchMode.NAME; navigate(Screen.DISCOVERY) },
                        searchQuery = churchSearchQuery, onSearchQueryChange = { churchSearchQuery = it },
                        pickerRequest = churchPickerRequest,
                        personalTimeRequest = personalTimeRequest,
                        onGoToService = { navigate(Screen.MAIN) },
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
                        onSelectionCancelled = ::cancelChurchSelection,
                        onOpenUrl = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }, onBack = ::back,
                        showBack = churchOrigin != Screen.MAIN,
                        bottomBar = { if (churchOrigin == Screen.MAIN) navigationBar() }
                    )
                    Screen.DISCOVERY -> ChurchDiscoveryScreen(
                        discovery = container.placeDiscovery,
                        query = churchSearchQuery, onQueryChange = { churchSearchQuery = it },
                        mode = churchSearchMode, onModeChange = { churchSearchMode = it },
                        onBack = ::closeDiscovery,
                        onSave = { record, onSelected ->
                            val saved = churchStore.save(record)
                            if (saved) savedChurches = churchStore.records()
                            if (!saved) ChurchSelectionResult.FAILED
                            else chooseChurch(record.id) { onSelected(); churchSearchQuery = "" }
                        },
                        onSelectionCancelled = ::cancelChurchSelection,
                        onOpenUrl = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                    )
                }
                }
            }
            pendingChurchSelection?.let { pending ->
                ChurchChangeDialog(
                    message = if (churchSelectionFailed)
                        "Не удалось выбрать храм. Сохраненное чтение осталось на месте. Попробуйте еще раз"
                    else "Место чтения и закладки будут сброшены\n\nНовый храм: ${churches.firstOrNull { it.id == pending.targetId }?.shortName ?: "Выбранный храм"}",
                    onDismiss = ::cancelChurchSelection,
                    onConfirm = {
                        val snapshot = churchChoiceSnapshot()
                        val result = if (snapshot == null) ChurchSelectionResult.FAILED
                            else churchSelectionGuard.confirm(pending, snapshot, availableChurchIds())
                        pendingChurchSelection = churchSelectionGuard.pending
                        churchSelectionFailed = result == ChurchSelectionResult.FAILED
                        if (result == ChurchSelectionResult.STALE) Toast.makeText(context,
                            "Чтение или список храмов изменились. Выберите храм еще раз", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }
}
