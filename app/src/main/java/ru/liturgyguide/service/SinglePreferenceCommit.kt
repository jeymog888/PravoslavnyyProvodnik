package ru.liturgyguide.service

import android.content.SharedPreferences

/**
 * A failed commit can already have changed Android's in-memory map. Restore just the written key
 * once, preserving its previous type or absence. A failed rollback is never a successful write.
 * Same-key writes must be serialized by the caller; unrelated preference writers remain independent.
 */
internal object SinglePreferenceCommit {
    fun write(preferences: SharedPreferences, key: String, value: Any): Boolean {
        val next = runCatching { copyValue(value) }.getOrNull() ?: return false
        val previous = runCatching {
            val all = preferences.all
            if (all.containsKey(key)) Previous.Present(copyValue(all[key])) else Previous.Absent
        }.getOrNull() ?: return false
        val accepted = runCatching {
            val editor = preferences.edit()
            putValue(editor, key, next)
            editor.commit()
        }.getOrDefault(false)
        if (accepted) return true

        runCatching {
            val editor = preferences.edit()
            when (previous) {
                Previous.Absent -> editor.remove(key)
                is Previous.Present -> putValue(editor, key, previous.value)
            }
            editor.commit()
        }
        return false
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
