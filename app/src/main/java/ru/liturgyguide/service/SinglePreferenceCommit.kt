package ru.liturgyguide.service

import android.content.SharedPreferences

/**
 * A failed commit can already have changed Android's in-memory map. Restore only the written
 * keys once, preserving their previous types or absence. Failure is never reported as success.
 * Writers sharing this helper are serialized; arbitrary external preference writers remain independent.
 */
internal object SinglePreferenceCommit {
    fun write(preferences: SharedPreferences, key: String, value: Any): Boolean = write(preferences, mapOf(key to value))

    /** One editor for a small coherent group; null removes a key. */
    fun write(preferences: SharedPreferences, changes: Map<String, Any?>): Boolean = synchronized(preferences) {
        if (changes.isEmpty()) return@synchronized true
        val next = runCatching { changes.mapValues { (_, value) -> value?.let(::copyValue) } }.getOrNull()
            ?: return@synchronized false
        val previous = runCatching {
            val all = preferences.all
            next.keys.associateWith { key ->
                if (all.containsKey(key)) Previous.Present(copyValue(all[key])) else Previous.Absent
            }
        }.getOrNull() ?: return@synchronized false
        val accepted = runCatching {
            val editor = preferences.edit()
            next.forEach { (key, value) -> if (value == null) editor.remove(key) else putValue(editor, key, value) }
            editor.commit()
        }.getOrDefault(false)
        if (accepted) return@synchronized true

        runCatching {
            // A listener or an independent writer may already have installed newer
            // values. Roll back only while the whole group still belongs to this try.
            val current = preferences.all
            val unchanged = next.all { (key, value) ->
                if (value == null) !current.containsKey(key) else current[key] == value
            }
            if (!unchanged) return@runCatching
            val editor = preferences.edit()
            previous.forEach { (key, old) -> when (old) {
                Previous.Absent -> editor.remove(key)
                is Previous.Present -> putValue(editor, key, old.value)
            } }
            editor.commit()
        }
        false
    }

    private sealed interface Previous {
        data object Absent : Previous
        data class Present(val value: Any) : Previous
    }

    private fun copyValue(value: Any?): Any = when (value) {
        is String, is Int, is Long, is Float, is Boolean -> value
        is Set<*> -> {
            require(value.all { it is String })
            value.filterIsInstance<String>().toSet()
        }
        else -> error("Unsupported preference type")
    }

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported preference type")
        }
    }
}
