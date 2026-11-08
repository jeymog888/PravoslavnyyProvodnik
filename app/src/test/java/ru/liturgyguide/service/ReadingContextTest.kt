package ru.liturgyguide.service

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.time.Instant

class ReadingContextTest {
    private val published = ReadingContext("church", "2026-11-08", "Europe/Moscow", ReadingContextSource.PUBLISHED,
        Instant.parse("2026-11-08T06:30:15Z").toEpochMilli())

    @Test fun `all sources round trip with the accepted date and zone`() {
        val values = listOf(published, published.copy(source = ReadingContextSource.USER,
            startEpochMillis = Instant.parse("2026-11-08T06:30:00Z").toEpochMilli()),
            published.copy(source = ReadingContextSource.MANUAL, startEpochMillis = null),
            published.copy(parishId = null, source = ReadingContextSource.MANUAL, startEpochMillis = null))
        for (value in values) assertEquals(value, ReadingContextCodec.decode(ReadingContextCodec.encode(value)))
    }

    @Test fun `suggestion factory checks the original instant against its stated local start before rounding`() {
        val original = ContextSuggestion(ParishService("2026-11-08", "09:30:15.000123", "Литургия", "liturgy"),
            ServiceRelation.InProgress, Instant.parse("2026-11-08T06:30:15.000123Z"),
            source = ServiceStartSource.PUBLISHED, parishId = "church", timezone = "Europe/Moscow")
        assertEquals(original.startInstant.toEpochMilli(), ReadingContextFactory.fromSuggestion(original)?.startEpochMillis)
        assertNull(ReadingContextFactory.fromSuggestion(original.copy(service = original.service.copy(time = "09:30"))))
        assertNull(ReadingContextFactory.fromSuggestion(original.copy(service = original.service.copy(kind = "vespers"))))
        val inconsistentPersonal = original.copy(source = ServiceStartSource.USER,
            service = original.service.copy(time = "09:30"), startInstant = Instant.parse("2026-11-08T06:30:00.000123Z"))
        assertNull(ReadingContextFactory.fromSuggestion(inconsistentPersonal))
        assertNotNull(ReadingContextFactory.fromSuggestion(inconsistentPersonal.copy(startInstant = Instant.parse("2026-11-08T06:30:00Z"))))
    }

    @Test fun `manual context captures the parish date rather than UTC or the phone date`() {
        val instant = Instant.parse("2026-11-08T16:00:00Z")
        val context = ReadingContextFactory.manual(parish("Asia/Tokyo"), instant)
        assertEquals(ReadingContext("church", "2026-11-09", "Asia/Tokyo", ReadingContextSource.MANUAL), context)
        val withoutParish = ReadingContextFactory.manual(null, instant, "Pacific/Honolulu")
        assertEquals(ReadingContext(null, "2026-11-08", "Pacific/Honolulu", ReadingContextSource.MANUAL), withoutParish)
        assertNull(ReadingContextFactory.manual(parish("Mars/Nowhere"), instant))
    }

    @Test fun `restoring after midnight preserves the stored date instead of rebinding it`() {
        val preferences = ReadingMemoryPreferences()
        val before = requireNotNull(ReadingContextFactory.manual(parish(), Instant.parse("2026-11-08T20:59:00Z")))
        assertTrue(ReadingContextStore(preferences).save(before))
        val after = requireNotNull(ReadingContextFactory.manual(parish(), Instant.parse("2026-11-08T21:01:00Z")))
        assertEquals("2026-11-09", after.serviceDate)
        assertEquals("2026-11-08", ReadingContextStore(preferences).restore()?.serviceDate)
    }

    @Test fun `invalid date zone identity and source relationships are rejected`() {
        for (value in listOf(published.copy(serviceDate = "2026-02-30"), published.copy(serviceDate = "2026-11-8"),
            published.copy(serviceDate = "0000-11-08"), published.copy(timezone = " Mars/Nowhere"),
            published.copy(timezone = "Europe/Moscow "), published.copy(parishId = ""), published.copy(parishId = " church"),
            published.copy(parishId = "x".repeat(161)), published.copy(parishId = "bad\n"),
            published.copy(parishId = null), published.copy(startEpochMillis = null),
            published.copy(source = ReadingContextSource.MANUAL))) {
            assertNull(value.toString(), ReadingContextCodec.validated(value))
            assertTrue(runCatching { ReadingContextCodec.encode(value) }.isFailure)
        }
    }

    @Test fun `published start must fall on its stored local date and personal start must use a whole minute`() {
        assertNull(ReadingContextCodec.validated(published.copy(serviceDate = "2026-11-09")))
        assertNull(ReadingContextCodec.validated(published.copy(startEpochMillis = Long.MAX_VALUE)))
        assertNull(ReadingContextCodec.validated(published.copy(source = ReadingContextSource.USER)))
        assertNotNull(ReadingContextCodec.validated(published.copy(startEpochMillis = published.startEpochMillis!! + 123)))
        val midnight = published.copy(serviceDate = "2026-11-09", startEpochMillis = Instant.parse("2026-11-08T21:00:00Z").toEpochMilli())
        assertNotNull(ReadingContextCodec.validated(midnight))
    }

    @Test fun `a persisted ambiguous DST occurrence cannot bypass the schedule restriction`() {
        val ambiguous = published.copy(serviceDate = "2026-10-25", timezone = "Europe/Berlin",
            startEpochMillis = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli())
        assertNull(ReadingContextCodec.validated(ambiguous))
        assertNull(ReadingContextCodec.validated(ambiguous.copy(startEpochMillis = Instant.parse("2026-10-25T01:30:00Z").toEpochMilli())))
        assertNotNull(ReadingContextCodec.validated(ambiguous.copy(startEpochMillis = Instant.parse("2026-10-25T03:00:00Z").toEpochMilli())))
    }

    @Test fun `numbers booleans missing duplicate and unknown fields never become a context`() {
        val json = ReadingContextCodec.encode(published)
        val bad = listOf(json.replace("\"church\"", "123"), json.replace("\"church\"", "true"),
            json.replace("\"PUBLISHED\"", "\"UNKNOWN\""), json.replace("\"version\":1", "\"version\":\"1\""),
            json.replace("\"version\":1", "\"version\":2"), json.replace("\"version\":1", "\"version\":1.0"),
            json.replace("\"version\":1,", ""), json.replace("\"version\":1", "\"version\":1,\"version\":1"),
            json.replace("\"version\":1", "\"version\":1,\"extra\":null"),
            json.replace(published.startEpochMillis.toString(), "\"${published.startEpochMillis}\""),
            json.replace(published.startEpochMillis.toString(), "1e3"),
            json.replace(published.startEpochMillis.toString(), "9223372036854775808"))
        for (value in bad) assertNull(value, ReadingContextCodec.decode(value))
    }

    @Test fun `nonstandard JSON and oversized payloads are rejected without throwing`() {
        val manual = published.copy(source = ReadingContextSource.MANUAL, startEpochMillis = null)
        val json = ReadingContextCodec.encode(manual)
        val bad = listOf(null, "", "{}", "[]", "broken", "$json true", "$json $json", "/* comment */$json",
            json.replace("null", "NULL"), json.replace("\"church\"", "\"church\\'s\""),
            json.replace("\"church\"", "'church'"), json.replace("\"church\"", "\"bad\nvalue\""),
            json.replace("\"version\":1", "\"version\":01"), json.dropLast(1) + ",}",
            json + " ".repeat(ReadingContextCodec.MAX_JSON_CHARS))
        for (value in bad) assertNull(value, ReadingContextCodec.decode(value))
        val escaped = manual.copy(parishId = "TRUE/'\\\"храм")
        assertEquals(escaped, ReadingContextCodec.decode(ReadingContextCodec.encode(escaped)))
    }

    @Test fun `wrong preference type and invalid saves preserve unrelated settings`() {
        val preferences = ReadingMemoryPreferences()
        preferences.values[ReadingContextStore.CONTEXT_KEY] = 42
        preferences.values["stage_id"] = "creed"
        preferences.values["dark"] = true
        val store = ReadingContextStore(preferences)
        assertNull(store.restore())
        assertTrue(store.save(published))
        assertFalse(store.save(published.copy(serviceDate = "broken")))
        assertEquals(published, store.restore())
        assertTrue(store.clear())
        assertNull(store.restore())
        assertEquals("creed", preferences.values["stage_id"])
        assertEquals(true, preferences.values["dark"])
    }

    @Test fun `storage failures return false and do not mutate bookmarks or start a clock`() {
        val preferences = ReadingMemoryPreferences()
        val store = ReadingContextStore(preferences)
        assertTrue(store.save(published))
        preferences.throwOnWrite = true
        assertFalse(store.save(published.copy(source = ReadingContextSource.MANUAL, startEpochMillis = null)))
        assertFalse(store.clear())
        assertEquals(published, store.restore())
        preferences.throwOnWrite = false
        preferences.commitResult = false
        assertFalse(store.save(published))
        assertFalse(store.clear())
    }

    private fun parish(zone: String = "Europe/Moscow") = Parish("church", "Храм", "Храм", "Город", "", "", "", zone,
        "", "", "", "", emptyList(), "", "")
}

private class ReadingMemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    var throwOnWrite = false
    var commitResult = true
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
                if (!commitResult) return false
                if (clear) values.clear()
                updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
