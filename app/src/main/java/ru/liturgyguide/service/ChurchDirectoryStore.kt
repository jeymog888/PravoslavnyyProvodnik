package ru.liturgyguide.service

import android.content.SharedPreferences

class ChurchDirectoryStore(private val preferences: SharedPreferences) {
    fun records(): List<SavedChurchRecord> = SavedChurchCodec.decode(runCatching { preferences.getString(RECORDS_KEY, null) }.getOrNull())
    fun favorites(): Set<String> = runCatching { preferences.getStringSet(FAVORITES_KEY, emptySet()).orEmpty().toSet() }.getOrDefault(emptySet())

    /** Return false without changing storage if a record is invalid or the personal list is full. */
    fun save(record: SavedChurchRecord): Boolean {
        val valid = SavedChurchCodec.validated(record) ?: return false
        val current = records()
        if (current.size >= SavedChurchCodec.MAX_RECORDS && current.none { it.id == valid.id }) return false
        val encoded = runCatching { SavedChurchCodec.encode(current.filterNot { it.id == valid.id } + valid) }.getOrNull() ?: return false
        return runCatching { preferences.edit().putString(RECORDS_KEY, encoded).commit() }.getOrDefault(false)
    }

    fun toggleFavorite(id: String, availableIds: Set<String>): Set<String> {
        val current = favorites().intersect(availableIds)
        if (id !in availableIds) return current
        val updated = if (id in current) current - id else current + id
        return if (runCatching { preferences.edit().putStringSet(FAVORITES_KEY, updated).commit() }.getOrDefault(false)) updated else current
    }

    companion object {
        const val RECORDS_KEY = "saved_churches_v1"
        const val FAVORITES_KEY = "favorite_churches_v1"
    }
}
