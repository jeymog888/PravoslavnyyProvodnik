package ru.liturgyguide.service

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

class UserServiceStartTest {
    private val record = UserServiceStart("test", "2026-11-08", "09:30")

    @Test fun `personal start round trips with exact calendar date and local minute`() {
        val next = record.copy(date = "2026-12-01", time = "00:00")
        assertEquals(listOf(record, next), UserServiceStartCodec.decode(UserServiceStartCodec.encode(listOf(record, next))))
    }

    @Test fun `syntax calendar and minute fields are strict`() {
        for (date in listOf("2026-1-08", "26-11-08", "2026-02-30", "0000-11-08", "+10000-11-08", "2026-11-08 ", "2026-11-08T00:00"))
            assertNull(date, UserServiceStartCodec.validated(record.copy(date = date)))
        for (time in listOf("9:30", "09:3", "24:00", "09:60", "09:30:00", "09:30:15", "09:30Z", " 09:30"))
            assertNull(time, UserServiceStartCodec.validated(record.copy(time = time)))
        assertNotNull(UserServiceStartCodec.validated(record.copy(date = "2028-02-29", time = "23:59")))
        assertNull(UserServiceStartCodec.validated(record.copy(date = "2026-02-29")))
    }

    @Test fun `parish identity is bounded and cannot be whitespace or control characters`() {
        for (id in listOf("", " ", " test", "test ", "test\n", "a".repeat(UserServiceStartCodec.MAX_PARISH_ID_LENGTH + 1)))
            assertNull(UserServiceStartCodec.validated(record.copy(parishId = id)))
        assertNotNull(UserServiceStartCodec.validated(record.copy(parishId = "a".repeat(UserServiceStartCodec.MAX_PARISH_ID_LENGTH))))
    }

    @Test fun `invalid entries do not erase well formed neighbors`() {
        val good = UserServiceStartCodec.encode(listOf(record)).removePrefix("[").removeSuffix("]")
        assertEquals(listOf(record), UserServiceStartCodec.decode("[{},null,17,[],true,$good]"))
    }

    @Test fun `malformed nonstandard and trailing JSON is rejected`() {
        val good = UserServiceStartCodec.encode(listOf(record))
        for (json in listOf("broken", "{}", "null", "[", "$good true", "$good $good", "[{'parishId':'test','date':'2026-11-08','time':'09:30'}]",
            "[{parishId:\"test\",date:\"2026-11-08\",time:\"09:30\"}]", "[$good,]", "/* comment */$good"))
            assertTrue(json, UserServiceStartCodec.decode(json).isEmpty())
    }

    @Test fun `uppercase or mixed case JSON literals reject the complete malformed payload`() {
        val good = UserServiceStartCodec.encode(listOf(record)).removePrefix("[").removeSuffix("]")
        for (literal in listOf("TRUE", "FALSE", "NULL", "True", "False", "Null", "truE", "falsE", "nUll")) {
            assertTrue(literal, UserServiceStartCodec.decode("[$literal,$good]").isEmpty())
            assertTrue(literal, UserServiceStartCodec.decode("[{\"unused\":$literal},$good]").isEmpty())
        }
        assertEquals(listOf(record), UserServiceStartCodec.decode("[true,false,null,$good]"))
    }

    @Test fun `nonstandard apostrophe escape is rejected while standard escaped strings round trip`() {
        val escapedApostrophe = "[{\"parishId\":\"test\\'s\",\"date\":\"2026-11-08\",\"time\":\"09:30\"}]"
        assertTrue(UserServiceStartCodec.decode(escapedApostrophe).isEmpty())
        assertEquals(listOf(record.copy(parishId = "test's")), UserServiceStartCodec.decode(escapedApostrophe.replace("\\'", "'")))
        // Capitals and apostrophes inside quoted strings are legitimate data, not JSON literals.
        val strings = listOf("TRUE", "FALSE", "NULL", "test's", "test\\path", "test\"quote", "test/part", "храм")
            .map { record.copy(parishId = it) }
        assertEquals(strings, UserServiceStartCodec.decode(UserServiceStartCodec.encode(strings)))
    }

    @Test fun `lexical guard leaves standard number and structure validation to JsonReader`() {
        val good = UserServiceStartCodec.encode(listOf(record)).removePrefix("[").removeSuffix("]")
        assertEquals(listOf(record), UserServiceStartCodec.decode("[0,-1,1.5,1e2,1E+2,-2e-3,$good]"))
        for (token in listOf("01", "1e", "-", "1.", "1+2"))
            assertTrue(token, UserServiceStartCodec.decode("[$token,$good]").isEmpty())
        assertTrue(UserServiceStartCodec.decode("[$good $good]").isEmpty())
    }

    @Test fun `numbers booleans nulls and wrong field names are not coerced to valid strings`() {
        for (json in listOf(
            "[{\"parishId\":123,\"date\":\"2026-11-08\",\"time\":\"09:30\"}]",
            "[{\"parishId\":true,\"date\":\"2026-11-08\",\"time\":\"09:30\"}]",
            "[{\"parishId\":null,\"date\":\"2026-11-08\",\"time\":\"09:30\"}]",
            "[{\"parishId\":\"test\",\"Date\":\"2026-11-08\",\"time\":\"09:30\"}]",
            "[{\"parishId\":\"test\",\"date\":\"2026-11-08\",\"time\":\"09:30\",\"source\":\"PUBLISHED\"}]",
            "[{\"parishId\":\"test\",\"date\":\"2026-11-08\"}]"
        )) assertTrue(json, UserServiceStartCodec.decode(json).isEmpty())
    }

    @Test fun `duplicate JSON fields never silently replace the selected start`() {
        val duplicate = "[{\"parishId\":\"test\",\"date\":\"2026-11-08\",\"time\":\"09:30\",\"time\":\"10:00\"}]"
        assertTrue(UserServiceStartCodec.decode(duplicate).isEmpty())
    }

    @Test fun `conflicting date duplicates are discarded independently of array order`() {
        val good = UserServiceStartCodec.encode(listOf(record)).removePrefix("[").removeSuffix("]")
        val other = good.replace("09:30", "10:00")
        assertTrue(UserServiceStartCodec.decode("[$good,$other]").isEmpty())
        assertTrue(UserServiceStartCodec.decode("[$other,$good]").isEmpty())
        assertEquals(listOf(record), UserServiceStartCodec.decode("[$good,$good]"))
        val neighbor = good.replace("2026-11-08", "2026-11-09")
        assertEquals(listOf(record.copy(date = "2026-11-09")), UserServiceStartCodec.decode("[$good,$other,$neighbor]"))
    }

    @Test fun `decode caps complete input and array length`() {
        val good = UserServiceStartCodec.encode(listOf(record))
        assertTrue(UserServiceStartCodec.decode(good + " ".repeat(UserServiceStartCodec.MAX_JSON_CHARS)).isEmpty())
        assertTrue(UserServiceStartCodec.decode(List(UserServiceStartCodec.MAX_RECORDS + 1) { "null" }.joinToString(",", "[", "]")).isEmpty())
    }

    @Test fun `encode rejects invalid duplicate and oversized records without dropping them`() {
        assertTrue(runCatching { UserServiceStartCodec.encode(listOf(record.copy(time = "24:00"))) }.isFailure)
        assertTrue(runCatching { UserServiceStartCodec.encode(listOf(record, record)) }.isFailure)
        assertTrue(runCatching { UserServiceStartCodec.encode((0..UserServiceStartCodec.MAX_RECORDS).map { record.copy(parishId = "test$it") }) }.isFailure)
        val expanded = (0 until 100).map { record.copy(parishId = "<".repeat(156) + it) }
        assertTrue(expanded.all { UserServiceStartCodec.validated(it) != null })
        assertTrue(runCatching { UserServiceStartCodec.encode(expanded) }.isFailure)
    }

    @Test fun `saving same date replaces only this parish and date while deletion restores absence`() {
        val preferences = StartMemoryPreferences()
        val store = UserServiceStartStore(preferences)
        val tomorrow = record.copy(date = "2026-11-09")
        val anotherParish = record.copy(parishId = "other")
        assertTrue(store.save(record, parish()))
        assertTrue(store.save(tomorrow, parish()))
        assertTrue(store.save(anotherParish, parish("other")))
        assertTrue(store.save(record.copy(time = "10:00"), parish()))
        assertEquals(3, store.records().size)
        assertEquals("10:00", store.forDate("test", LocalDate.parse(record.date))?.time)
        assertEquals(store.records(), UserServiceStartStore(preferences).records())
        assertTrue(store.remove("test", LocalDate.parse(record.date)))
        assertNull(store.forDate("test", LocalDate.parse(record.date)))
        assertEquals(setOf(tomorrow, anotherParish), store.records().toSet())
    }

    @Test fun `wrong preference type recovers and unrelated settings stay intact`() {
        val preferences = StartMemoryPreferences()
        preferences.values[UserServiceStartStore.RECORDS_KEY] = 42
        preferences.values["saved_churches_v1"] = "personal directory"
        preferences.values["progress"] = "middle"
        val store = UserServiceStartStore(preferences)
        assertTrue(store.records().isEmpty())
        assertTrue(store.save(record, parish()))
        assertEquals(listOf(record), store.records())
        assertEquals("personal directory", preferences.values["saved_churches_v1"])
        assertEquals("middle", preferences.values["progress"])
    }

    @Test fun `rejected identity timezone and DST saves preserve previous explicit start`() {
        val store = UserServiceStartStore(StartMemoryPreferences())
        assertTrue(store.save(record, parish()))
        assertFalse(store.save(record.copy(parishId = "elsewhere"), parish()))
        assertFalse(store.save(record.copy(time = "24:00"), parish()))
        assertFalse(store.save(record, parish().copy(timezone = "Mars/Nowhere")))
        assertFalse(store.save(record.copy(date = "2026-03-29", time = "02:30"), parish().copy(timezone = "Europe/Berlin")))
        assertFalse(store.save(record.copy(date = "2026-10-25", time = "02:30"), parish().copy(timezone = "Europe/Berlin")))
        assertEquals(listOf(record), store.records())
    }

    @Test fun `full store allows replacing an existing date and rejects a new date`() {
        val store = UserServiceStartStore(StartMemoryPreferences())
        val day = LocalDate.parse(record.date)
        for (offset in 0 until UserServiceStartCodec.MAX_RECORDS)
            assertTrue(store.save(record.copy(date = day.plusDays(offset.toLong()).toString()), parish()))
        val previous = store.records()
        assertFalse(store.save(record.copy(date = day.plusDays(UserServiceStartCodec.MAX_RECORDS.toLong()).toString()), parish()))
        assertEquals(previous, store.records())
        assertTrue(store.save(record.copy(time = "10:00"), parish()))
        assertEquals(UserServiceStartCodec.MAX_RECORDS, store.records().size)
    }

    @Test fun `escaped JSON size is checked before writing and survives restart`() {
        val preferences = StartMemoryPreferences()
        val store = UserServiceStartStore(preferences)
        val id = "<".repeat(UserServiceStartCodec.MAX_PARISH_ID_LENGTH)
        val church = parish(id)
        var failed = false
        for (offset in 0 until UserServiceStartCodec.MAX_RECORDS) {
            val previous = store.records()
            val next = record.copy(parishId = id, date = LocalDate.parse(record.date).plusDays(offset.toLong()).toString())
            if (!store.save(next, church)) { failed = true; assertEquals(previous, store.records()) }
        }
        assertTrue(failed)
        assertTrue(store.records().isNotEmpty())
        assertEquals(store.records(), UserServiceStartStore(preferences).records())
    }

    @Test fun `storage exceptions are reported as failure instead of crashing`() {
        val preferences = StartMemoryPreferences()
        val store = UserServiceStartStore(preferences)
        assertTrue(store.save(record, parish()))
        preferences.throwOnWrite = true
        assertFalse(store.save(record.copy(time = "10:00"), parish()))
        assertFalse(store.remove("test", LocalDate.parse(record.date)))
        assertEquals(listOf(record), store.records())
    }

    private fun parish(id: String = "test") = Parish(id = id, name = "Храм", shortName = "Храм", city = "Город",
        address = "", phone = "", website = "", timezone = "Europe/Moscow", sourceUrl = "", scheduleSource = "",
        scheduleValidFrom = "", scheduleValidUntil = "", schedule = emptyList(), scheduleCoverage = "", scheduleNote = "")
}

private class StartMemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    var throwOnWrite = false
    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?): String? = if (key in values) values[key] as String? else defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (key in values) (values[key] as Set<String>?)?.toMutableSet() else defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor {
        check(!throwOnWrite) { "Storage unavailable" }
        return object : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String?, value: String?) = apply { updates[requireNotNull(key)] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { updates[requireNotNull(key)] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { updates[requireNotNull(key)] = value }
            override fun putLong(key: String?, value: Long) = apply { updates[requireNotNull(key)] = value }
            override fun putFloat(key: String?, value: Float) = apply { updates[requireNotNull(key)] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { updates[requireNotNull(key)] = value }
            override fun remove(key: String?) = apply { updates[requireNotNull(key)] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
