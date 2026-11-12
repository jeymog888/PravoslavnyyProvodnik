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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.WindowCompat
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.ScheduleUrl
import ru.liturgyguide.service.schedule.withInternetSchedule
import java.time.Instant
import com.google.gson.Gson
import java.time.Duration
import java.time.LocalDate
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.delay

private enum class Screen { MAIN, MANUAL, ARRIVAL, SERVICE, STAGE_SELECTOR, SEARCH, LISTENING, SETTINGS, SOURCES, CHURCH, DISCOVERY, BOOK }
private enum class ChurchSelectionOrigin { FIRST_HOME, DIRECTORY }
private class ListeningRoute(val origin: Screen, val draft: ArrivalDraft, val previousDraft: ArrivalDraft?,
    val churchId: String?, val generation: Int, val previousContext: ReadingContext?,
    val targetContext: ReadingContext, val speed: PlaybackSpeed)

class MainActivity : ComponentActivity() {
    private val activityForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ActivityForegroundState() else null
    private val screenAwakeOwner by lazy { readerScreenAwakeOwner(window) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { AppNavigation((application as LiturgyGuideApplication).container,
            activityForeground = activityForeground, screenAwakeOwner = screenAwakeOwner) }
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
    activityForeground: ActivityForegroundState? = null, screenAwakeOwner: ReaderScreenAwakeOwner? = null,
    speechFactory: (android.content.Context, (SpeechGuideState) -> Unit) -> SpeechGuide = { context, changed -> SpeechGuide(context, changed) }) {
    val context = LocalContext.current
    val screenStateHolder = rememberSaveableStateHolder()
    val preferences = remember { context.getSharedPreferences("guide_preferences", 0) }
    val churchStore = remember(preferences) { ChurchDirectoryStore(preferences) }
    val bookStore = remember(context) { BookReadingStore(context.getSharedPreferences("service_book_reading", 0)) }
    var bookRequestJson by rememberSaveable { mutableStateOf<String?>(null) }
    val bookRoute = remember(bookRequestJson) { BookRouteCodec.decode(bookRequestJson) }
    val bookRequest = bookRoute?.activeKey
    val startStore = remember(preferences) { UserServiceStartStore(preferences) }
    var userStarts by remember { mutableStateOf(startStore.records()) }
    val readingContextStore = remember(preferences) { ReadingContextStore(preferences) }
    val readingAcceptanceStore = remember(preferences) { ReadingAcceptanceStore(preferences) }
    val playbackStore = remember(preferences, container) { ServicePlaybackStore(preferences, container.historical05Content, container.historical06Content) }
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
    var listeningOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var listeningRoute by remember { mutableStateOf<ListeningRoute?>(null) }
    val listeningInterrupt = remember { ListeningInterrupt() }
    var settingsOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var churchOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var churchSelectionOrigin by rememberSaveable { mutableStateOf(ChurchSelectionOrigin.DIRECTORY) }
    var churchPickerRequest by rememberSaveable { mutableIntStateOf(0) }
    var churchSearchQuery by rememberSaveable { mutableStateOf("") }
    var churchSearchMode by rememberSaveable { mutableStateOf(ChurchSearchMode.NAME) }
    var personalTimeRequest by rememberSaveable { mutableIntStateOf(0) }
    var availableTextsDate by rememberSaveable { mutableStateOf<String?>(null) }
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
    val baseChurches = remember(parishes, savedChurches) { ChurchDirectory.merge(parishes?.directory?.churches.orEmpty(), savedChurches) }
    val validatedSession = session?.takeIf { runCatching { sessionEngine?.isValid(it) == true }.getOrDefault(false) }
    val baseChurch = ChurchSelection.resolve(baseChurches, hasChurchSelection, churchId,
        hasReadingMetadata, readingContext, hasPlaybackSnapshot,
        hasLegacyReading = validatedSession != null || stages.any { it.id == selectedId },
        legacyDefaultId = parishes?.directory?.defaultChurchId)
    val internetSchedules = container.internetSchedules
    val scheduleImport = internetSchedules.progress?.collectAsState()?.value
    var internetSchedule by remember(internetSchedules, baseChurch?.id) {
        mutableStateOf(baseChurch?.let(internetSchedules::cached))
    }
    var scheduleRefresh by remember(baseChurch?.id) { mutableIntStateOf(0) }
    var scheduleSourceOverride by remember(baseChurch?.id) { mutableStateOf<String?>(null) }
    val parishDay = baseChurch?.let { now.atZone(ZoneId.of(it.timezone)).toLocalDate() }
    LaunchedEffect(internetSchedules, baseChurch?.id, parishDay, scheduleRefresh) {
        val selected = baseChurch ?: return@LaunchedEffect
        internetSchedule = (internetSchedule ?: InternetScheduleState(selected.id)).copy(loading = true)
        internetSchedule = internetSchedules.refresh(selected, now, force = scheduleRefresh > 0,
            sourceOverride = scheduleSourceOverride)
    }
    val church = baseChurch?.let { original -> internetSchedule?.let { original.withInternetSchedule(it, now) } ?: original }
    val churches = remember(baseChurches, church) { baseChurches.map { if (it.id == church?.id) requireNotNull(church) else it } }
    val contextEngine = remember(stages, clock) { if (stages.isEmpty()) null else ContextSuggestionEngine(stages, clock) }
    val overview = church?.let { contextEngine?.overview(it, now, userStarts) } ?: ScheduleOverview()
    val lastBook = remember(screen, church?.id) { bookStore.last(church?.id) }
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
    val restored = remember(playbackStore, availablePlan, availableContext, hasPlaybackSnapshot, hasReadingMetadata, session, playbackRevision) {
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
    // A verified address is a preview until the existing atomic writer accepts it explicitly.
    val migrationCandidate = (restored as? PlaybackRestoreResult.MigrationCandidate)?.let { result ->
        ActiveServicePlayback(requireNotNull(availablePlan), result.originalStored.context, result.state,
            isApproximate = validSession?.anchorIsApproximate == true, migrationNote = result.note)
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
    val selectedStage = if (invalidRecovery) null else player.reading?.stage ?: restoredReading?.stage ?: migrationCandidate?.stage ?: legacyStage
    val activeReadingContext = if (invalidRecovery) null else player.reading?.context ?: availableContext?.takeIf { selectedStage != null }
    val readingNeedsExplicitDate = selectedStage != null && activeReadingContext == null
    val scopedSearch = screen == Screen.SEARCH && searchOrigin in setOf(Screen.MANUAL, Screen.ARRIVAL)
    val validArrivalDraft = arrivalDraft?.takeIf { it.isValidFor(church) }
    val invalidArrival = (screen == Screen.MANUAL || screen == Screen.ARRIVAL || scopedSearch) && validArrivalDraft == null
    fun isCurrentListening(route: ListeningRoute): Boolean = screen == Screen.LISTENING &&
        listeningRoute === route && arrivalDraft === route.draft && route.draft.isValidFor(church) &&
        church?.id == route.churchId && player.generation == route.generation && player.reading?.context == route.previousContext
    val listeningRecovery = screen == Screen.LISTENING && listeningRoute?.let(::isCurrentListening) != true
    val listeningReturn = when (listeningOrigin) {
        Screen.SERVICE -> if (!invalidRecovery && player.reading != null) Screen.SERVICE else Screen.MAIN
        Screen.MANUAL, Screen.ARRIVAL -> if (validArrivalDraft != null) listeningOrigin else Screen.MAIN
        Screen.SEARCH -> if (searchOrigin !in setOf(Screen.MANUAL, Screen.ARRIVAL) || validArrivalDraft != null) Screen.SEARCH else Screen.MAIN
        else -> Screen.MAIN
    }
    val visibleScreen = when {
        screen == Screen.BOOK && (bookRequest == null || bookRoute?.parishId != church?.id) -> Screen.MAIN
        invalidArrival || (screen == Screen.SERVICE && (invalidRecovery || player.reading == null)) -> Screen.MAIN
        listeningRecovery -> listeningReturn
        else -> screen
    }
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
    val awakeBinding = rememberReaderScreenAwakeBinding(player, visibleScreen == Screen.SERVICE,
        lifecycle, activityForeground, screenAwakeOwner, onForegroundLost = listeningInterrupt::foregroundChanged) { lifecycleResumed = it }
    val playing = player.reading?.state?.isPlaying == true
    val topResumed = activityForeground?.isTopResumed != false
    LaunchedEffect(player, visibleScreen, lifecycleResumed, activityForeground, topResumed, playing, player.generation) {
        if (visibleScreen != Screen.SERVICE || !lifecycleResumed || !topResumed) {
            awakeBinding.pause()
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
        awakeBinding.pause { listeningInterrupt.stop() }
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
    // A generic open action resumes the same dated reading; only an explicitly
    // labelled restart replaces a different/legacy pending reading.
    fun resumableText(date: LocalDate): ActiveServicePlayback? =
        if (invalidRecovery) null else (player.reading ?: restoredReading)?.takeIf {
            it.context.parishId == church?.id && it.context.serviceDate == date.toString()
        }
    fun openBook(key: ServiceBookKey, journeyContext: BookJourneyContext? = null) {
        if (!key.isValid()) return
        bookRequestJson = BookRouteCodec.encode(BookRoute(key, church?.id,
            journeyContext?.validatedFor(key, church?.id)))
        navigate(Screen.BOOK)
    }
    fun openBookOnDate(date: LocalDate, kind: WorshipKind, eveningMatins: Boolean = false) {
        openBook(ServiceBookSelection.forCivilDate(date, kind, church, eveningMatins))
    }
    fun openPublishedBook(service: ParishService) {
        val route = ServiceBookSelection.resolve(service, church)
        if (route != null) {
            val expected = route.journeyContext
            val saved = bookStore.last(route.parishId)?.journeyContext?.takeIf { previous ->
                expected != null && previous.parishId == expected.parishId && previous.civilDate == expected.civilDate &&
                    previous.time == expected.time && previous.title == expected.title &&
                    previous.parts.zip(expected.parts).all { (old, fresh) ->
                        old.date == fresh.date && old.worship == fresh.worship && old.churchType == fresh.churchType &&
                            old.topLevel == fresh.topLevel
                    }
            }
            // Reopening this exact publication keeps its accepted per-part variants.
            // Another occurrence, church or standalone cursor cannot supply its context.
            bookRequestJson = BookRouteCodec.encode(if (saved != null)
                route.copy(activeKey = saved.parts.first(), journeyContext = saved) else route)
            navigate(Screen.BOOK)
        } else availableTextsDate = service.date
    }
    fun resumeLegacyReading() {
        if (invalidRecovery) return
        if (player.reading != null) { navigate(Screen.SERVICE); return }
        val pending = restoredReading ?: migrationCandidate ?: legacyCandidate ?: selectedStage?.let { stage ->
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
    fun openReadingText(date: LocalDate) {
        val existing = resumableText(date)
        if (existing != null) {
            if (player.reading != null || beginReading(existing, resetBookmarks = false,
                    resetReaderUi = false, preserveRestoreNotice = true)) {
                arrivalDraft = null
                navigate(Screen.SERVICE)
            }
            return
        }
        val accepted = ReadingContextFactory.manualOnDate(church, date)
        val first = stages.firstOrNull { it.id == "beginning" } ?: stages.first()
        val value = accepted?.let { candidate(it, first.id) }
        if (value != null && beginReading(value, resetBookmarks = true)) {
            arrivalDraft = null
            navigate(Screen.SERVICE)
        }
    }
    val hasReadingToReplace = selectedStage != null || hasReadingMetadata || hasPlaybackSnapshot
    fun readingEntryLabel(date: LocalDate, freshLabel: String): String = when {
        resumableText(date) != null -> "Продолжить чтение"
        hasReadingToReplace -> "Читать с начала"
        else -> freshLabel
    }

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
    fun speedForContext(accepted: ReadingContext): PlaybackSpeed =
        player.reading?.takeIf { it.context == accepted }?.state?.speed
            ?: migrationCandidate?.takeIf { it.context == accepted }?.state?.speed
            ?: PlaybackSpeed.NORMAL
    fun selectStage(id: String, query: String? = null) {
        val existing = player.reading
        val accepted = existing?.context ?: activeReadingContext ?: ReadingContextFactory.manual(church, clock.instant()) ?: return
        val value = candidate(accepted, id, query, speed = speedForContext(accepted)) ?: return
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
            speedForContext(accepted) else PlaybackSpeed.NORMAL) ?: return
        if (!beginReading(value, resetBookmarks = draft.mode == ArrivalMode.NEW_SERVICE,
                resetReaderUi = draft.mode == ArrivalMode.NEW_SERVICE)) return
        arrivalDraft = null
        navigate(Screen.SERVICE)
    }
    fun openManual(mode: ArrivalMode, wasPlayingInReader: Boolean = false) {
        val stopped = mode == ArrivalMode.CORRECTION &&
            (wasPlayingInReader || player.reading?.state?.isPlaying == true)
        player.pause()
        beginArrival(ArrivalDraft(church?.id, church?.shortName ?: "Храм не выбран", mode,
            ArrivalSource.MANUAL, automationStopped = stopped), Screen.MANUAL)
    }
    fun openListening(origin: Screen) {
        if (screen != origin) return
        awakeBinding.pause()
        val previous = arrivalDraft
        val draft = if (origin in setOf(Screen.MANUAL, Screen.ARRIVAL) ||
            (origin == Screen.SEARCH && searchOrigin in setOf(Screen.MANUAL, Screen.ARRIVAL))) {
            validArrivalDraft ?: return
        } else ArrivalDraft(church?.id, church?.shortName ?: "Храм не выбран",
            if (player.reading != null) ArrivalMode.CORRECTION else ArrivalMode.NEW_SERVICE, ArrivalSource.MANUAL)
        if (!draft.isValidFor(church)) return
        val target = draft.pendingServiceContext ?: if (draft.mode == ArrivalMode.CORRECTION)
            player.reading?.context ?: return else ReadingContextFactory.manual(church, clock.instant()) ?: return
        arrivalDraft = draft
        listeningOrigin = origin
        listeningRoute = ListeningRoute(origin, draft, previous, church?.id, player.generation,
            player.reading?.context, target, if (draft.mode == ArrivalMode.CORRECTION) speedForContext(target) else PlaybackSpeed.NORMAL)
        navigate(Screen.LISTENING)
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
        } else if (!clearAcceptedReading(id)) return false
        if (!hasChurchSelection || churchId != id) {
            // These routes can be unmounted while the picker is open. rememberSaveable
            // inputs alone do not invalidate their restored state for a different church.
            screenStateHolder.removeState(Screen.CHURCH.name)
            screenStateHolder.removeState(Screen.MAIN.name)
            // Their consumed request counters were cleared with the saved route.
            churchPickerRequest = 0
            personalTimeRequest = 0
        }
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
    fun closeDiscovery() { navigate(if (churchSelectionOrigin == ChurchSelectionOrigin.FIRST_HOME) Screen.MAIN else Screen.CHURCH) }
    fun back() {
        if (invalidArrival) { recoverArrival(); return }
        if (screen == Screen.LISTENING) {
            val route = listeningRoute
            listeningInterrupt.stop()
            arrivalDraft = if (route != null) route.previousDraft else arrivalDraft.takeIf { listeningOrigin in setOf(Screen.MANUAL, Screen.ARRIVAL, Screen.SEARCH) }
            listeningRoute = null
            navigate(listeningReturn)
            return
        }
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
    LaunchedEffect(screen, bookRequestJson, church?.id) {
        // A damaged restored BOOK request displays Home. Normalize its owner too,
        // so Home actions remain usable without weakening stale callback guards.
        if (screen == Screen.BOOK && (bookRequest == null || bookRoute?.parishId != church?.id)) navigate(Screen.MAIN)
    }
    LaunchedEffect(invalidArrival) {
        // Never render a scoped search without its draft: its selection cannot fall through
        // to the old accepted reading or silently acquire today's date.
        if (invalidArrival) recoverArrival()
    }
    LaunchedEffect(listeningRecovery, listeningReturn) {
        if (listeningRecovery) { listeningRoute = null; navigate(listeningReturn) }
    }
    // A saved SERVICE screen may be a 0.4 restoration. Migrate before rendering a live reader.
    LaunchedEffect(screen, player.reading == null, invalidRecovery, legacyCandidate, restoredReading, migrationCandidate) {
        if (screen == Screen.SERVICE && player.reading == null) {
            val pending = if (invalidRecovery) null else restoredReading ?: legacyCandidate
            if (pending == null || !beginReading(pending, resetBookmarks = false, resetReaderUi = false,
                    preserveRestoreNotice = true)) screen = Screen.MAIN
        } else if (screen == Screen.SERVICE && invalidRecovery) navigate(Screen.MAIN)
    }
    BackHandler(enabled = screen != Screen.MAIN) { back() }

    // A callback held by an old MAIN frame cannot revive a candidate after accept/clear.
    val resumeGeneration = player.generation
    val resumeChurchId = churchId
    val searchGeneration = player.generation

    val navigationBar: @Composable () -> Unit = {
        GuideBottomBar(selected = when (screen) { Screen.CHURCH -> "church"; Screen.SETTINGS -> "settings"; else -> "home" },
            onHome = { navigate(Screen.MAIN) },
            onChurch = { openChurch(fromHome = false) },
            onSettings = { settingsOrigin = Screen.MAIN; navigate(Screen.SETTINGS) })
    }
    GuideTheme(dark = dark) {
        // Keep Text and TextMeasurer on Android's same nonlinear scaling curve.
        // Compose 1.7.3 MeasureScope does not retain custom SP conversion overrides.
        val appDensity = remember(density, textSize) { Density(density.density, density.fontScale * listOf(1f, 1.15f, 1.3f)[textSize]) }
        CompositionLocalProvider(LocalDensity provides appDensity) {
            Surface(Modifier.fillMaxSize()) {
                screenStateHolder.SaveableStateProvider(visibleScreen.name) {
                if (stages.isEmpty() || matcher == null) {
                    ContentUnavailable(onRetry = { reload++ })
                } else when (visibleScreen) {
                    Screen.MAIN -> TodayScreen(
                        church = church, now = now, internetSchedule = internetSchedule,
                        importProgress = scheduleImport, lastBook = lastBook,
                        onFindChurch = { churchOrigin = Screen.MAIN; churchSelectionOrigin = ChurchSelectionOrigin.FIRST_HOME; navigate(Screen.DISCOVERY) },
                        onChurchDetails = { openChurch(fromHome = false) },
                        onOpenBook = { date, kind, evening ->
                            if (screen == Screen.MAIN && churchId == resumeChurchId) openBookOnDate(date, kind, evening)
                        },
                        onResumeBook = { if (screen == Screen.MAIN && churchId == resumeChurchId)
                            openBook(it.key, it.journeyContext?.validatedFor(it.key, church?.id)) },
                        onOpenService = { if (screen == Screen.MAIN && churchId == resumeChurchId) openPublishedBook(it) },
                        onRefresh = { scheduleSourceOverride = null; scheduleRefresh++ },
                        onSetScheduleSource = { url ->
                            if (screen == Screen.MAIN && churchId == resumeChurchId) {
                                scheduleSourceOverride = url; scheduleRefresh++
                            }
                        },
                        onResumeLegacy = if (selectedStage == null) null else { {
                            if (screen == Screen.MAIN && churchId == resumeChurchId && player.generation == resumeGeneration)
                                resumeLegacyReading()
                        } },
                        bottomBar = navigationBar, userStarts = userStarts,
                        readingNotice = when {
                            invalidArrival || arrivalRecoveryNotice -> "Не удалось восстановить выбранную службу. Выберите ее заново"
                            invalidRecovery -> "Не удалось восстановить сохраненное чтение. Выберите службу или начните с начала"
                            else -> null
                        },
                        legacyReadingSummary = selectedStage?.let { stage -> listOfNotNull(
                            stage.name,
                            activeReadingContext?.let { "Тексты на ${LocalDate.parse(it.serviceDate).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM uuuu", java.util.Locale.forLanguageTag("ru")))}" },
                            if (readingNeedsExplicitDate) "Дата не сохранена · откроется на сегодня" else null,
                            if (migrationCandidate?.migrationNote != null && player.reading == null)
                                "Тексты дополнены. При открытии вернемся к последней сохраненной фразе" else null
                        ).joinToString(" · ") },
                        onOpenScheduleSource = church?.let { scheduleSourceFor(it, internetSchedule) }?.let { url ->
                            { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                    )
                    Screen.BOOK -> bookRoute?.let { route ->
                        val request = route.activeKey
                        ServiceBookScreen(request, route.parishId, container.serviceBooks, bookStore, textSize,
                            parish = church,
                            route = route,
                            onRouteChanged = { next ->
                                if (screen == Screen.BOOK && churchId == resumeChurchId &&
                                    BookRouteCodec.decode(bookRequestJson) == route) {
                                    bookRequestJson = BookRouteCodec.encode(next)
                                    true
                                } else false
                            },
                            onBack = { navigate(Screen.MAIN) },
                            onSettings = { settingsOrigin = Screen.BOOK; navigate(Screen.SETTINGS) },
                            onOpenUrl = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                            onOpenCommonLiturgy = { openReadingText(LocalDate.parse(request.date)) })
                    }
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
                            onMicrophone = { if (isCurrentArrival(draft, Screen.MANUAL)) openListening(Screen.MANUAL) },
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
                    Screen.SERVICE -> {
                        // The book pauses before leaving so its visible bookmark is saved.
                        // Preserve the visible running state for the destination's explanation.
                        val wasPlayingInReader = player.reading?.state?.isPlaying == true
                        ServiceScreen(
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
                            onFindStage = { openManual(ArrivalMode.CORRECTION, wasPlayingInReader) },
                            onMicrophone = { openListening(Screen.SERVICE) },
                            onSettings = { settingsOrigin = Screen.SERVICE; navigate(Screen.SETTINGS) }
                        )
                    }
                    Screen.STAGE_SELECTOR, Screen.SEARCH -> StageSelector(
                        stageMatcher = matcher,
                        selectedId = if (scopedSearch) null else selectedStage?.id,
                        searchMode = screen == Screen.SEARCH,
                        readingDay = searchReadingDay,
                        onSearch = { searchOrigin = Screen.STAGE_SELECTOR; navigate(Screen.SEARCH) },
                        onMicrophone = {
                            if (searchGeneration == player.generation && (!scopedSearch ||
                                (validArrivalDraft != null && isCurrentArrival(validArrivalDraft, Screen.SEARCH)))) openListening(Screen.SEARCH)
                        },
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
                    Screen.LISTENING -> listeningRoute?.let { route ->
                        ListeningScreen(stages, isCurrent = { isCurrentListening(route) },
                            isForeground = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activityForeground?.isTopResumed != false },
                            interrupt = listeningInterrupt, speechFactory = speechFactory, onBack = ::back,
                            onManual = {
                                if (isCurrentListening(route)) {
                                    listeningRoute = null
                                    if (route.origin == Screen.SEARCH) {
                                        arrivalDraft = route.previousDraft
                                        navigate(Screen.SEARCH)
                                    } else navigate(Screen.MANUAL)
                                }
                            },
                            onSelect = select@{ id, query ->
                                if (!isCurrentListening(route)) return@select false
                                val value = candidate(route.targetContext, id, query, speed = route.speed) ?: return@select false
                                val fresh = route.draft.mode == ArrivalMode.NEW_SERVICE
                                if (!beginReading(value, resetBookmarks = fresh, resetReaderUi = fresh)) return@select false
                                arrivalDraft = null; listeningRoute = null
                                navigate(Screen.SERVICE)
                                true
                            })
                    }
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
                        internetSchedule = internetSchedule,
                        onRefreshSchedule = { scheduleSourceOverride = null; scheduleRefresh++ },
                        onSetScheduleSource = { url ->
                            if (screen == Screen.CHURCH && churchId == resumeChurchId) {
                                scheduleSourceOverride = url; scheduleRefresh++
                            }
                        },
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
                        onReadLiturgy = { openBookOnDate(it, WorshipKind.LITURGY) },
                        onReadServiceText = { date, kind ->
                            if (screen == Screen.CHURCH && churchId == resumeChurchId) openBookOnDate(date, kind)
                        },
                        onOpenService = { service ->
                            if (screen == Screen.CHURCH && churchId == resumeChurchId) openPublishedBook(service)
                        },
                        now = now,
                        readingEntryLabel = { "Открыть литургию" },
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
                        savedChurches = churches,
                        onSelectSaved = ::chooseChurch,
                        favorites = favoriteChurches,
                        onToggleFavorite = { id -> favoriteChurches = churchStore.toggleFavorite(id, churches.map { it.id }.toSet()) },
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
            availableTextsDate?.let { selectedDate ->
                ServiceBookChooser(LocalDate.parse(selectedDate), onDismiss = { availableTextsDate = null },
                    onSelect = { date, kind -> availableTextsDate = null; openBookOnDate(date, kind) })
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
