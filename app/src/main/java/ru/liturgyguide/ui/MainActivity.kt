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
import com.google.gson.Gson
import java.time.Instant
import kotlinx.coroutines.delay

private enum class Screen { MAIN, SERVICE, STAGE_SELECTOR, SETTINGS, SOURCES, CHURCH }

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
    val preferences = remember { context.getSharedPreferences("guide_preferences", 0) }
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(preferences.getBoolean("dark", systemDark)) }
    var textSize by rememberSaveable { mutableIntStateOf(preferences.getInt("text_size", 0).coerceIn(0, 2)) }
    var showPrayerTexts by rememberSaveable { mutableStateOf(preferences.getBoolean("show_texts", false)) }
    var selectedId by rememberSaveable { mutableStateOf(preferences.getString("stage_id", null)) }
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    var selectorOrigin by rememberSaveable { mutableStateOf(Screen.MAIN) }
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

    fun selectStage(id: String) {
        val engine = sessionEngine ?: return
        saveSession(session?.let { engine.correct(it, id) } ?: engine.start(id))
        screen = Screen.SERVICE
    }
    fun back() {
        screen = when (screen) {
            Screen.STAGE_SELECTOR -> selectorOrigin
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

    GuideTheme(dark = dark) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * listOf(1f, 1.15f, 1.3f)[textSize])) {
            Surface(Modifier.fillMaxSize()) {
                if (stages.isEmpty() || matcher == null) {
                    ContentUnavailable(onRetry = { reload++ })
                } else when (screen) {
                    Screen.MAIN -> MainScreen(
                        lastStage = selectedStage,
                        church = church, parishes = parishes,
                        suggestion = suggestion,
                        onJoinService = {
                            sessionEngine?.let { engine ->
                                saveSession(engine.start(suggestion?.approximateStageId ?: "preparation", approximate = suggestion?.approximateStageId != null,
                                    elapsedInStageMillis = suggestion?.approximateElapsedInStageMillis ?: 0))
                                screen = Screen.SERVICE
                            }
                        },
                        onChurch = { churchOrigin = Screen.MAIN; screen = Screen.CHURCH },
                        onStart = { sessionEngine?.let { saveSession(it.start(stages.first().id)); screen = Screen.SERVICE } },
                        onResume = {
                            if (position == null) selectedStage?.let { stage -> sessionEngine?.let { saveSession(it.start(stage.id)) } }
                            screen = Screen.SERVICE
                        },
                        onFindStage = { selectorOrigin = Screen.MAIN; screen = Screen.STAGE_SELECTOR },
                        onSettings = { screen = Screen.SETTINGS }
                    )
                    Screen.SERVICE -> ServiceScreen(
                        stageMatcher = matcher,
                        stageId = selectedStage?.id,
                        onStageChange = ::selectStage,
                        onBack = { screen = Screen.MAIN },
                        dark = dark,
                        onDarkChange = { dark = !dark; preferences.edit().putBoolean("dark", dark).apply() },
                        onOpenSource = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                        showTexts = showPrayerTexts,
                        onShowTextsChange = { showPrayerTexts = !showPrayerTexts; preferences.edit().putBoolean("show_texts", showPrayerTexts).apply() },
                        readingDay = container.readings?.days?.firstOrNull { it.date == java.time.LocalDate.now(java.time.ZoneId.of(church?.timezone ?: "Europe/Moscow")).toString() },
                        sessionPosition = position,
                        onToggleFollowing = {
                            val engine = sessionEngine
                            val current = session?.takeIf { engine?.isValid(it) == true }
                                ?: selectedStage?.let { engine?.start(it.id) }
                            if (engine != null && current != null) saveSession(if (position?.autoAdvanceActive == true) engine.pause(current) else engine.resume(current))
                        },
                        onSoundSettings = { openExternal(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                        onFinish = {
                            session?.let { current -> sessionEngine?.pause(current)?.let(::saveSession) }
                            screen = Screen.MAIN
                            Toast.makeText(context, "Если включали режим тишины, не забудьте проверить его после службы", Toast.LENGTH_LONG).show()
                        },
                        onSelectStage = { selectorOrigin = Screen.SERVICE; screen = Screen.STAGE_SELECTOR }
                    )
                    Screen.STAGE_SELECTOR -> StageSelector(
                        stageMatcher = matcher,
                        selectedId = selectedStage?.id,
                        onSelect = ::selectStage,
                        onBack = ::back
                    )
                    Screen.SETTINGS -> SettingsScreen(
                        dark = dark, textSize = textSize,
                        onDarkChange = { dark = it; preferences.edit().putBoolean("dark", it).apply() },
                        onTextSizeChange = { textSize = it; preferences.edit().putInt("text_size", it).apply() },
                        onSoundSettings = { openExternal(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                        churchName = church?.shortName,
                        onChurch = { churchOrigin = Screen.SETTINGS; screen = Screen.CHURCH },
                        onSources = { screen = Screen.SOURCES }, onBack = ::back
                    )
                    Screen.SOURCES -> SourcesScreen(
                        sourceUrl = matcher.getSourceUrl(), contentNote = matcher.getContentNote(),
                        onOpenUrl = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                        onBack = ::back
                    )
                    Screen.CHURCH -> ChurchScreen(
                        churches = parishes?.directory?.churches.orEmpty(), selectedId = church?.id,
                        onSelect = {
                            if (church?.id != it) {
                                session = null; selectedId = null
                                preferences.edit().remove("session").remove("stage_id").apply()
                            }
                            churchId = it; preferences.edit().putString("church_id", it).apply()
                        },
                        onOpenUrl = { openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }, onBack = ::back
                    )
                }
            }
        }
    }
}
