package ru.liturgyguide.service

import android.content.SharedPreferences
import ru.liturgyguide.model.Parish
import java.time.LocalDate

/** Personal starts are stored apart from the parish directory and published schedule. */
class UserServiceStartStore(private val preferences: SharedPreferences) {
    fun records(): List<UserServiceStart> = UserServiceStartCodec.decode(
        runCatching { preferences.getString(RECORDS_KEY, null) }.getOrNull())

    fun forDate(parishId: String, date: LocalDate): UserServiceStart? =
        records().firstOrNull { it.parishId == parishId && it.date == date.toString() }

    /** Replaces only this parish's explicit date after zone/DST validation. */
    fun save(record: UserServiceStart, parish: Parish): Boolean {
        val valid = UserServiceStartCodec.validatedFor(record, parish) ?: return false
        val updated = records().filterNot { it.parishId == valid.parishId && it.date == valid.date } + valid
        return write(updated)
    }

    fun remove(parishId: String, date: LocalDate): Boolean = write(
        records().filterNot { it.parishId == parishId && it.date == date.toString() })

    private fun write(records: List<UserServiceStart>): Boolean {
        val encoded = runCatching { UserServiceStartCodec.encode(records) }.getOrNull() ?: return false
        return runCatching { preferences.edit().putString(RECORDS_KEY, encoded).commit() }.getOrDefault(false)
    }

    companion object { const val RECORDS_KEY = "user_service_starts_v1" }
}
