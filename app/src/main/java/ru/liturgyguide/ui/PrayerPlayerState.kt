package ru.liturgyguide.ui

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.service.LyricPlaybackEngine
import java.time.Instant

@Stable
class PrayerPlayerState(private val preferences: SharedPreferences, private val stageId: String, val tracks: List<PrayerTrack>) {
    var trackIndex by mutableIntStateOf(tracks.indexOfFirst { it.id == preferences.getString("lyric_track_$stageId", null) }.coerceAtLeast(0))
        private set
    val track get() = tracks[trackIndex]
    private var engine = LyricPlaybackEngine(track)
    var playback by mutableStateOf(restore())
        private set
    var now by mutableStateOf(Instant.now())
        private set
    var original by mutableStateOf(preferences.getBoolean("lyric_original", false))
        private set
    var focusRequest by mutableIntStateOf(0)
        private set
    var instructionsSeen by mutableStateOf(preferences.getBoolean("lyric_intro_seen", false))
        private set
    val position get() = engine.position(playback, now)
    val supportsOriginal get() = track.lines.any { it.original != null }

    private fun restore(): ru.liturgyguide.service.LyricPlaybackState {
        val currentTempo = preferences.getInt("lyric_tempo", 12)
        val savedTempo = preferences.getInt("lyric_saved_tempo_${track.id}", 0)
        val phraseId = preferences.getString("lyric_phrase_${track.id}", null)
        val matchedIndex = phraseId?.let { id -> track.lines.indexOfFirst { it.id == id } }
        val line = matchedIndex?.coerceAtLeast(0) ?: preferences.getInt("lyric_line_${track.id}", 0)
        val elapsed = if (savedTempo in LyricPlaybackEngine.TEMPOS && matchedIndex != -1)
            preferences.getLong("lyric_offset_${track.id}", 0L) else 0L
        val restored = engine.start(lineIndex = line, secondsPerLine = if (savedTempo in LyricPlaybackEngine.TEMPOS) savedTempo else currentTempo,
            elapsedInLineMillis = elapsed)
        return engine.changeTempo(restored, currentTempo)
    }

    private fun persist() {
        val p = position
        preferences.edit().putString("lyric_track_$stageId", track.id)
            .putInt("lyric_line_${track.id}", p.lineIndex)
            .putString("lyric_phrase_${track.id}", track.lines[p.lineIndex].id)
            .putInt("lyric_saved_tempo_${track.id}", playback.secondsPerLine)
            .putLong("lyric_offset_${track.id}", p.elapsedInLineMillis)
            .putInt("lyric_tempo", playback.secondsPerLine).apply()
    }
    fun tick() {
        val previousLine = position.lineIndex
        now = Instant.now()
        if (playback.isPlaying && !position.isPlaying) pause()
        else if (position.lineIndex != previousLine) persist()
    }
    fun pause() { playback = engine.pause(playback); now = Instant.now(); persist() }
    fun play() {
        if (position.atEnd) playback = engine.reset(playback)
        playback = engine.resume(playback); now = Instant.now(); focusRequest++; persist()
    }
    fun seek(index: Int) { playback = engine.seek(playback, index); now = Instant.now(); focusRequest++; persist() }
    fun previous() = seek(position.lineIndex - 1)
    fun next() = seek(position.lineIndex + 1)
    fun selectTrack(index: Int) {
        if (index !in tracks.indices || index == trackIndex) return
        pause(); trackIndex = index; engine = LyricPlaybackEngine(track); playback = restore(); now = Instant.now(); focusRequest++; persist()
    }
    fun toggleOriginal() { original = !original; preferences.edit().putBoolean("lyric_original", original).apply(); focusRequest++ }
    fun tempo(seconds: Int) { playback = engine.changeTempo(playback, seconds); now = Instant.now(); persist() }
    fun confirmInstructions() { instructionsSeen = true; preferences.edit().putBoolean("lyric_intro_seen", true).apply() }
}

@Composable
fun rememberPrayerPlayer(stageId: String, tracks: List<PrayerTrack>): PrayerPlayerState? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(stageId, tracks) {
        if (tracks.isEmpty()) null else PrayerPlayerState(context.getSharedPreferences("guide_preferences", 0), stageId, tracks)
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player?.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.pause() }
    }
    LaunchedEffect(player, player?.playback?.isPlaying) {
        while (player?.playback?.isPlaying == true) { player.tick(); delay(250L) }
    }
    return player
}
