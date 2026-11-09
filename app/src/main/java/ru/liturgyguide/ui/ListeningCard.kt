package ru.liturgyguide.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.service.*

internal typealias SpeechGuideFactory = (Context, (SpeechGuideState) -> Unit) -> SpeechGuide
private class ListeningResultIdentity(private val state: SpeechGuideState?) {
    override fun equals(other: Any?) = other is ListeningResultIdentity && state === other.state
    override fun hashCode() = System.identityHashCode(state)
}

@Composable
internal fun ListeningScreen(stages: List<LiturgyStage>, onSelect: (String, String?) -> Boolean,
    onManual: () -> Unit, onBack: () -> Unit, isCurrent: () -> Boolean,
    isForeground: () -> Boolean, interrupt: ListeningInterrupt,
    speechFactory: SpeechGuideFactory = { context, changed -> SpeechGuide(context, changed) }) {
    var active by remember { mutableStateOf(false) }
    Scaffold(topBar = { Column(Modifier.statusBarsPadding()) {
        PageHeader("Микрофон", { if (isCurrent()) onBack() }) {
            if (active) TextButton(onClick = { if (isCurrent()) interrupt.stop() }, modifier = Modifier.heightIn(min = 48.dp).testTag("listening_stop_toolbar")) { Text("Стоп") }
        }
    } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("listening_screen"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { ListeningCard(stages, onSelect, onManual, isCurrent, isForeground, interrupt, speechFactory) { active = it } }
        }
    }
}

@Composable
private fun ListeningCard(stages: List<LiturgyStage>, onSelect: (String, String?) -> Boolean,
    onManual: () -> Unit, isCurrent: () -> Boolean, isForeground: () -> Boolean,
    interrupt: ListeningInterrupt, speechFactory: SpeechGuideFactory, onActiveChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(SpeechGuideState(), referentialEqualityPolicy()) }
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf<Long?>(null) }
    var acceptanceFailed by remember { mutableStateOf(false) }
    val routeCurrent by rememberUpdatedState(isCurrent)
    val foreground by rememberUpdatedState(isForeground)
    // The route owns this instance; an equivalent factory lambda on recomposition must not restart it.
    val speech = remember(context) { speechFactory(context) { state = it } }
    val request = remember(speech) {
        ListeningRequestOwner({ routeCurrent() }, { foreground() }, start = { speech.start(it) },
            cancel = { pendingPermission = null; speech.stop() }, denied = { permissionDenied = true })
    }
    pendingPermission?.let { token -> key(token) {
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingPermission == token) pendingPermission = null
            request.permissionResult(token, granted)
        }
        LaunchedEffect(token) {
            if (pendingPermission != token || !routeCurrent() || !foreground()) return@LaunchedEffect
            try { permission.launch(Manifest.permission.RECORD_AUDIO) }
            catch (_: RuntimeException) { request.stop(); permissionDenied = true }
        }
    } }
    LaunchedEffect(speech) { state = speech.initialState }
    DisposableEffect(request, lifecycle, interrupt) {
        val unbind = interrupt.bind(request, request::foregroundChanged, request::stop)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> request.stop()
                Lifecycle.Event.ON_PAUSE -> request.foregroundChanged()
                Lifecycle.Event.ON_RESUME -> { speech.refreshAvailability(); request.foregroundChanged() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            unbind(); lifecycle.removeObserver(observer)
            request.dispose(); speech.destroy()
        }
    }
    // Only a granted, still-current explicit request may start after our permission overlay.
    val foregroundNow = isForeground()
    SideEffect {
        onActiveChanged(state.isActive || pendingPermission != null)
        if (foregroundNow) request.foregroundChanged()
    }
    val matcher = remember(stages) { PhraseMatcher(stages.map { StageAnchor(it.id, it.listenFor) }) }
    val match = remember(state, matcher) { if (state.status == SpeechStatus.RESULT) matcher.match(state.transcript) else null }
    val stage = match?.let { result -> stages.firstOrNull { it.id == result.stageId } }
    val renderedResult = state.takeIf { it.status == SpeechStatus.RESULT }
    fun listen(unchecked: Boolean = false) {
        permissionDenied = false; acceptanceFailed = false
        val token = request.request(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED, unchecked)
        if (token != null) pendingPermission = token
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Найти момент по звуку", style = MaterialTheme.typography.headlineMedium)
        Text("Послушаем короткую фразу и предложим этап. Вы сами подтвердите, подходит ли он. Запись не сохраняется",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (pendingPermission != null) "Ждем разрешение на микрофон" else state.message,
                    Modifier.testTag("listening_status").semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
                if (state.transcript.isNotBlank()) Text("Распознано: \"${state.transcript}\"",
                    Modifier.testTag("listening_transcript"), style = MaterialTheme.typography.bodyMedium)
                if (stage != null) {
                    Text("Похоже, сейчас: ${stage.name}", style = MaterialTheme.typography.titleLarge)
                    Text("Совпала фраза: \"${match?.matchedPhrase.orEmpty()}\"", style = MaterialTheme.typography.bodyMedium)
                    Text(stage.cue.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    key(ListeningResultIdentity(renderedResult)) {
                        Button(onClick = {
                            if (routeCurrent() && speech.state === renderedResult) acceptanceFailed = !onSelect(stage.id, match?.matchedPhrase)
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("accept_listening_result")) { Text("Открыть этот этап") }
                    }
                    if (acceptanceFailed) Text("Не удалось сохранить место. Можно повторить, найденная фраза осталась здесь",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else if (state.status == SpeechStatus.RESULT) Text("Не удалось уверенно найти этап. Попробуйте другую фразу или выберите момент вручную",
                    style = MaterialTheme.typography.bodyMedium)
                if (state.isActive || pendingPermission != null) {
                    Button(onClick = request::stop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stop_listening")) { Text("Выключить микрофон") }
                } else if (state.status != SpeechStatus.UNAVAILABLE) {
                    val label = if (state.status == SpeechStatus.IDLE) "Послушать до 15 секунд" else "Попробовать еще раз"
                    if (stage == null) Button(onClick = { listen() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("start_listening")) {
                        Icon(Icons.Default.MicNone, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(label)
                    } else OutlinedButton(onClick = { listen() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("start_listening")) { Text(label) }
                    if (state.canTryWithoutSupport) OutlinedButton(onClick = { listen(true) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("try_listening_unchecked")) { Text("Попробовать без проверки") }
                } else TextButton(onClick = { speech.refreshAvailability() }) { Text("Проверить еще раз") }
                if (permissionDenied) {
                    Text("Доступ к микрофону не разрешен. Можно продолжить вручную", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        request.stop()
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                    }) { Text("Разрешения приложения") }
                }
            }
        }
        OutlinedButton(onClick = { request.stop(); if (routeCurrent()) onManual() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("listening_manual")) { Text("Выбрать вручную") }
        Text("Локальное распознавание зависит от телефона. Хор и эхо могут мешать. Микрофон не следит за всей службой автоматически",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
