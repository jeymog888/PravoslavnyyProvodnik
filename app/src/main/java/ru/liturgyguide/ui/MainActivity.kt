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
import ru.liturgyguide.service.ServiceSessionEngine
import ru.liturgyguide.service.ContextSuggestionEngine
import ru.liturgyguide.service.PrayerTrackFactory
import ru.liturgyguide.service.PrayerTextSearch
import com.google.gson.Gson
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

private enum class Screen { MAIN, SERVICE, STAGE_SELECTOR, SEARCH, SETTINGS, SOURCES, CHURCH }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { AppNavigation((application as LiturgyGuideApplication).container) }
    }
}

@Composable
fun AppNavigation(container: AppContainer) {
    val context = LocalContext.current
    val screenStateHolder = rememberSaveableStateHolder()
    val preferences = remember { context.getSharedPreferences("guide_preferences", 0) }
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(preferences.getBoolean("dark", systemDark)) }
    var textSize by rememberSaveable { mutableIntStateOf(preferences.getInt("text_size", 0).coerceIn(0, 2)) }
    var selectedId by rememberSaveable { mutableStateOf(preferences.getString("stage_id", null)) }
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var selectorOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var searchOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var settingsOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var churchOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var churchId by rememberSaveable { mutableStateOf(preferences.getString("church_id", null)) }
    var session by remember {
        mutableStateOf(runCatching { Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java) }.getOrNull())
    }
    var now by remember { mutableStateOf(Instant.now()) }
    var reload by remember { mutableIntStateOf(0) }
    val loaded = remember(container, reload) { container.getStageMatcherResult(forceReload = reload > 0) }
    val matcher = loaded.getOrNull()
    val stages = matcher?.getAllStages().orEmpty()
    val sessionEngine = remember(stages) { if (stages.isEmpty()) null else ServiceSessionEngine(stages) }
    val position = session?.takeIf { sessionEngine?.isValid(it) == true }?.let { sessionEngine?.position(it, now) }
    val selectedStage = stages.firstOrNull { it.id == (position?.stageId ?: selectedId) }
    val parishes = container.parishes.getOrNull()
    val church = parishes?.selected(churchId)
    val contextEngine = remember(stages) { if (stages.isEmpty()) null else ContextSuggestionEngine(stages) }
    val suggestion = church?.let { contextEngine?.suggest(it, now) }
    val currentDate = now.atZone(ZoneId.of(church?.timezone ?: "Europe/Moscow")).toLocalDate()
    val readingDate = currentDate.toString()
    val readingDay = container.readings?.days?.firstOrNull { it.date == readingDate }
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(5_000L) }
        }
    }
    fun saveSession(value: ServiceSession) {
        session = value
        selectedId = value.anchorStageId
        preferences.edit().putString("session", Gson().toJson(value)).putString("stage_id", selectedId).apply()
        now = Instant.now()
    }

    fun clearReadingPositions() {
        val editor = preferences.edit()
        preferences.all.keys.filter { key -> key.startsWith("lyric_track_") || key.startsWith("lyric_line_") || key.startsWith("lyric_offset_") || key.startsWith("lyric_phrase_") || key.startsWith("lyric_saved_tempo_") }.forEach { editor.remove(it) }
        editor.apply()
        screenStateHolder.removeState(Screen.SERVICE.name)
    }
    fun selectStage(id: String, query: String? = null) {
        val engine = sessionEngine ?: return
        val existing = session?.takeIf(engine::isValid)
        if (existing == null) clearReadingPositions()
        if (!query.isNullOrBlank()) {
            val stage = matcher?.getStageById(id)
            val tracks = stage?.let { PrayerTrackFactory.build(it, readingDay) }.orEmpty()
            PrayerTextSearch.find(tracks, query)?.let { hit ->
                screenStateHolder.removeState(Screen.SERVICE.name)
                val track = tracks[hit.trackIndex]
                preferences.edit().putString("lyric_track_$id", track.id)
                    .putInt("lyric_line_${track.id}", hit.lineIndex).putString("lyric_phrase_${track.id}", track.lines[hit.lineIndex].id)
                    .putInt("lyric_saved_tempo_${track.id}", preferences.getInt("lyric_tempo", 12)).putLong("lyric_offset_${track.id}", 0L).apply()
            }
        }
        saveSession(existing?.let { engine.correct(it, id) } ?: engine.start(id))
        screen = Screen.SERVICE
    }
    fun openOrder(origin: Screen) {
        // A fresh entry follows the current stage; search/back and recreation keep browsing position.
        screenStateHolder.removeState(Screen.STAGE_SELECTOR.name)
        selectorOrigin = origin
        screen = Screen.STAGE_SELECTOR
    }
    fun back() {
        screen = when (screen) {
            Screen.STAGE_SELECTOR -> selectorOrigin
            Screen.SEARCH -> searchOrigin
            Screen.SETTINGS -> settingsOrigin
            Screen.SOURCES -> Screen.SETTINGS
            Screen.CHURCH -> churchOrigin
            else -> Screen.MAIN
        }
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
                            clearReadingPositions()
                            sessionEngine?.let { engine ->
                                saveSession(engine.start(suggestion?.approximateStageId ?: "preparation", approximate = suggestion?.approximateStageId != null,
                                    elapsedInStageMillis = suggestion?.approximateElapsedInStageMillis ?: 0))
                                screen = Screen.SERVICE
                            }
                        },
                        onChurch = { churchOrigin = Screen.MAIN; screen = Screen.CHURCH },
                        onStart = { clearReadingPositions(); sessionEngine?.let { saveSession(it.start(stages.first().id)); screen = Screen.SERVICE } },
                        onResume = {
                            if (position == null) selectedStage?.let { stage -> sessionEngine?.let { saveSession(it.start(stage.id)) } }
                            screen = Screen.SERVICE
                        },
                        onFindStage = { searchOrigin = Screen.MAIN; screen = Screen.SEARCH },
                        onOrder = { openOrder(Screen.MAIN) },
                        bottomBar = navigationBar
                    )
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
                        onFindStage = { searchOrigin = Screen.SERVICE; screen = Screen.SEARCH },
                        onSettings = { settingsOrigin = Screen.SERVICE; screen = Screen.SETTINGS }
                    )
                    Screen.STAGE_SELECTOR, Screen.SEARCH -> StageSelector(
                        stageMatcher = matcher,
                        selectedId = selectedStage?.id,
                        searchMode = screen == Screen.SEARCH,
                        readingDay = readingDay,
                        onSearch = { searchOrigin = Screen.STAGE_SELECTOR; screen = Screen.SEARCH },
                        onSelect = { id, query -> selectStage(id, query) },
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
                        churches = parishes?.directory?.churches.orEmpty(), selectedId = church?.id,
                        currentDate = currentDate,
                        onSelect = {
                            if (church?.id != it) {
                                clearReadingPositions()
                                session = null; selectedId = null
                                preferences.edit().remove("session").remove("stage_id").apply()
                            }
                            churchId = it; preferences.edit().putString("church_id", it).apply()
                        },
                        onOpenUrl = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }, onBack = ::back,
                        showBack = churchOrigin != Screen.MAIN,
                        bottomBar = { if (churchOrigin == Screen.MAIN) navigationBar() }
                    )
                }
                }
            }
        }
    }
}
