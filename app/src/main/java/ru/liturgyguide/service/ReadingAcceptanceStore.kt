package ru.liturgyguide.service

import android.content.SharedPreferences
import com.google.gson.Gson

/**
 * Accepts one coherent reading with one Editor. The UI must update only after true.
 * Android applies commit() to memory before reporting a disk failure, so a failed acceptance
 * makes one best-effort rollback of the same keys. Rollback failure is never reported as success.
 * Call on the reading owner's serialized thread; this lock cannot serialize arbitrary prefs users.
 */
class ReadingAcceptanceStore(private val preferences: SharedPreferences) {
    private val gson = Gson()

    @Synchronized
    fun accept(context: ReadingContext, session: ServiceSession, resetBookmarks: Boolean): Boolean {
        val changes = runCatching {
            require(session.anchorStageId.isNotBlank() && session.elapsedInStageMillis >= 0)
            linkedMapOf<String, Any?>(
                ReadingContextStore.CONTEXT_KEY to ReadingContextCodec.encode(context),
                SESSION_KEY to gson.toJson(session),
                STAGE_KEY to session.anchorStageId
            ).apply {
                // A legacy caller cannot leave a new-format position tied to a different entry.
                // Keep the old call's affected-key set unchanged when there is no new snapshot.
                if (preferences.contains(ServicePlaybackStore.PLAYBACK_KEY)) put(ServicePlaybackStore.PLAYBACK_KEY, null)
            }
        }.getOrNull() ?: return false
        return commit(changes, resetBookmarks)
    }

    /**
     * Accept a captured (already materialized) snapshot and its paused compatibility marker.
     * No monotonic clock is read here: the controller chooses one timestamp for its whole action.
     */
    @Synchronized
    fun acceptPlayback(
        plan: ServicePlaybackPlan,
        context: ReadingContext,
        snapshot: ServicePlaybackSnapshot,
        marker: ServiceSession,
        resetBookmarks: Boolean,
        bookmark: PlaybackBookmark? = null
    ): Boolean {
        val changes = runCatching {
            require(context.serviceDate == plan.serviceDate.toString())
            val state = requireNotNull(ServicePlaybackEngine(plan).restore(snapshot))
            require(!marker.autoAdvance && marker.elapsedInStageMillis == 0L)
            require(marker.anchorStageId == plan.stageAt(state.segmentIndex).id)
            linkedMapOf<String, Any?>(
                ReadingContextStore.CONTEXT_KEY to ReadingContextCodec.encode(context),
                SESSION_KEY to gson.toJson(marker),
                STAGE_KEY to marker.anchorStageId,
                ServicePlaybackStore.PLAYBACK_KEY to StoredServicePlaybackCodec.encode(StoredServicePlayback(context, snapshot))
            ).apply {
                if (bookmark != null) {
                    // The bookmark mirrors the visible text, including retained text in Wait/Missing.
                    require(bookmark == PlaybackBookmark.at(plan, state))
                    val track = requireNotNull(plan.track(bookmark.trackId))
                    val lineIndex = track.lines.indexOfFirst { it.id == bookmark.lineId }
                    require(lineIndex >= 0)
                    put("lyric_track_${bookmark.stageId}", bookmark.trackId)
                    put("lyric_line_${bookmark.trackId}", lineIndex)
                    put("lyric_phrase_${bookmark.trackId}", bookmark.lineId)
                    put("lyric_offset_${bookmark.trackId}", 0L)
                    put("lyric_saved_tempo_${bookmark.trackId}", null)
                }
            }
        }.getOrNull() ?: return false
        return commit(changes, resetBookmarks)
    }

    private fun commit(changes: Map<String, Any?>, resetBookmarks: Boolean): Boolean {
        val snapshot = runCatching {
            val all = preferences.all
            val affected = changes.keys.toMutableSet()
            if (resetBookmarks) affected += all.keys.filter(::isBookmarkKey)
            affected.associateWith { key ->
                if (all.containsKey(key)) Previous.Present(copyPreferenceValue(all[key])) else Previous.Absent
            }
        }.getOrNull() ?: return false

        val accepted = runCatching {
            val editor = preferences.edit()
            // Reset before updates: a new reading's initial bookmark must survive its own reset.
            if (resetBookmarks) snapshot.keys.filter(::isBookmarkKey).forEach { editor.remove(it) }
            changes.forEach { (key, value) ->
                if (value == null) editor.remove(key) else restoreValue(editor, key, value)
            }
            editor.commit()
        }.getOrDefault(false)
        if (accepted) return true

        // Exactly one attempt. Even commit=false normally restores Android's in-memory map.
        // Never clear(): unrelated settings can change while the disk write is in progress.
        runCatching {
            val editor = preferences.edit()
            snapshot.forEach { (key, previous) ->
                when (previous) {
                    Previous.Absent -> editor.remove(key)
                    is Previous.Present -> restoreValue(editor, key, previous.value)
                }
            }
            editor.commit()
        }
        return false
    }

    private sealed interface Previous {
        data object Absent : Previous
        data class Present(val value: Any) : Previous
    }

    /** Copy sets: SharedPreferences.getAll() does not promise deep copies of its values. */
    private fun copyPreferenceValue(value: Any?): Any = when (value) {
        is String, is Int, is Long, is Float, is Boolean -> value
        is Set<*> -> {
            require(value.all { it is String })
            value.filterIsInstance<String>().toSet()
        }
        else -> error("Unsupported affected preference type")
    }

    private fun restoreValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported snapshot type")
        }
    }

    companion object {
        const val SESSION_KEY = "session"
        const val STAGE_KEY = "stage_id"
        private val bookmarkPrefixes = listOf("lyric_track_", "lyric_line_", "lyric_offset_", "lyric_phrase_", "lyric_saved_tempo_")
        fun isBookmarkKey(key: String): Boolean = bookmarkPrefixes.any(key::startsWith)
    }
}
