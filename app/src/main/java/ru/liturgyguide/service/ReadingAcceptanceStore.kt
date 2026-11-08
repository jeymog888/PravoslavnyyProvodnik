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
        val encoded = runCatching {
            require(session.anchorStageId.isNotBlank() && session.elapsedInStageMillis >= 0)
            ReadingContextCodec.encode(context) to gson.toJson(session)
        }.getOrNull() ?: return false
        val snapshot = runCatching {
            val all = preferences.all
            val affected = mutableSetOf(ReadingContextStore.CONTEXT_KEY, SESSION_KEY, STAGE_KEY)
            if (resetBookmarks) affected += all.keys.filter(::isBookmarkKey)
            affected.associateWith { key ->
                if (all.containsKey(key)) Previous.Present(copyPreferenceValue(all[key])) else Previous.Absent
            }
        }.getOrNull() ?: return false

        val accepted = runCatching {
            val editor = preferences.edit()
                .putString(ReadingContextStore.CONTEXT_KEY, encoded.first)
                .putString(SESSION_KEY, encoded.second)
                .putString(STAGE_KEY, session.anchorStageId)
            if (resetBookmarks) snapshot.keys.filter(::isBookmarkKey).forEach { editor.remove(it) }
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
