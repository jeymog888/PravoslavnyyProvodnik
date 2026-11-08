package ru.liturgyguide.service

import android.content.SharedPreferences

/** One accepted reading context, independent of bookmarks and all playback clocks. */
class ReadingContextStore(private val preferences: SharedPreferences) {
    fun restore(): ReadingContext? = ReadingContextCodec.decode(
        runCatching { preferences.getString(CONTEXT_KEY, null) }.getOrNull())

    fun save(context: ReadingContext): Boolean {
        val encoded = runCatching { ReadingContextCodec.encode(context) }.getOrNull() ?: return false
        return runCatching { preferences.edit().putString(CONTEXT_KEY, encoded).commit() }.getOrDefault(false)
    }

    fun clear(): Boolean = runCatching { preferences.edit().remove(CONTEXT_KEY).commit() }.getOrDefault(false)

    companion object { const val CONTEXT_KEY = "reading_context_v1" }
}
