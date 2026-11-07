package ru.liturgyguide.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.service.*

@Composable
fun ListeningCard(stages: List<LiturgyStage>, onSelect: (String, String?) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(SpeechGuideState()) }
    var permissionDenied by remember { mutableStateOf(false) }
    val speech = remember(context) { SpeechGuide(context) { state = it } }
    LaunchedEffect(speech) { state = speech.initialState }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) speech.start()
    }
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) speech.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.destroy() }
    }
    val matcher = remember(stages) { PhraseMatcher(stages.map { StageAnchor(it.id, it.listenFor) }) }
    val match = remember(state) { if (state.status == SpeechStatus.RESULT) matcher.match(state.transcript) else null }
    val stage = match?.let { m -> stages.firstOrNull { it.id == m.stageId } }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Подсказка по звуку", style = MaterialTheme.typography.titleLarge)
            if (state.status == SpeechStatus.UNAVAILABLE) {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(if (state.status == SpeechStatus.IDLE) "Послушаем до 15 секунд и попробуем найти знакомую фразу. Работает на телефоне, без сохранения записи." else state.message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                if (state.transcript.isNotBlank()) Text("Распознано: \"${state.transcript}\"", style = MaterialTheme.typography.bodySmall)
                if (stage != null) {
                    Text("Похоже, сейчас: ${stage.name}", style = MaterialTheme.typography.titleMedium)
                    Text("Совпала фраза: \"${match?.matchedPhrase.orEmpty()}\"", style = MaterialTheme.typography.bodySmall)
                    Text(stage.cue.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { speech.stop(); onSelect(stage.id, match?.matchedPhrase) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть этот этап") }
                } else if (state.status == SpeechStatus.RESULT) {
                    Text("По этой фразе не удалось уверенно найти этап. Попробуйте поиск по словам.", style = MaterialTheme.typography.bodyMedium)
                }
                if (state.isActive) {
                    OutlinedButton(onClick = { speech.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Выключить микрофон") }
                } else {
                    OutlinedButton(onClick = {
                        permissionDenied = false
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.start()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.status == SpeechStatus.IDLE) "Послушать фрагмент" else "Попробовать еще раз")
                    }
                }
                if (permissionDenied) {
                    Text("Микрофон не разрешен. Поиск по словам и список этапов работают без него.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) {
                        Text("Разрешения приложения")
                    }
                }
                Text("Экспериментальная подсказка: хор и эхо могут мешать. Сверяйте результат со службой.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
