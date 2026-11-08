package ru.liturgyguide.service

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SavedChurchDirectoryTest {
    private fun church(name: String = "Храм Николая", city: String = "Казань") =
        SavedChurchRecord.create(name, city, "", "Europe/Moscow")

    @Test fun `a personal temple keeps a stable identity without borrowing a schedule`() {
        val first = church()
        val sameName = church()
        assertNotEquals(first.id, sameName.id)
        val restored = SavedChurchCodec.decode(SavedChurchCodec.encode(listOf(first, sameName)))
        assertEquals(listOf(first, sameName), restored)
        val parish = restored.first().asParish()
        assertTrue(parish.schedule.isEmpty())
        assertTrue(parish.website.isEmpty())
        assertTrue(parish.sourceUrl.isEmpty())
        assertTrue(parish.scheduleValidFrom.isEmpty())
        assertNull(parish.latitude)
    }

    @Test fun `invalid entries do not discard neighboring valid temples`() {
        val first = church()
        val good = SavedChurchCodec.encode(listOf(first)).removePrefix("[").removeSuffix("]")
        assertEquals(listOf(first), SavedChurchCodec.decode("[{},null,17,$good,$good]"))
        assertTrue(SavedChurchCodec.decode("broken").isEmpty())
        assertTrue(SavedChurchCodec.decode("{}").isEmpty())
        assertTrue(SavedChurchCodec.decode(" ".repeat(SavedChurchCodec.MAX_JSON_CHARS + 1)).isEmpty())
    }

    @Test fun `validation rejects invalid zones coordinates and invented sources`() {
        val first = church()
        assertNull(SavedChurchCodec.validated(first.copy(timezone = "Mars/Nowhere")))
        assertNull(SavedChurchCodec.validated(first.copy(latitude = 55.0)))
        assertNull(SavedChurchCodec.validated(first.copy(latitude = Double.NaN, longitude = 37.0)))
        assertNull(SavedChurchCodec.validated(first.copy(latitude = 95.0, longitude = 37.0)))
        assertNull(SavedChurchCodec.validated(first.copy(name = "Храм\nновый")))
        assertNull(SavedChurchCodec.validated(first.copy(sourceUrl = "https://example.com")))
        assertNull(SavedChurchCodec.validated(first.copy(website = "javascript:alert(1)")))
        assertNull(SavedChurchCodec.validated(first.copy(website = "https://user:password@example.com")))
        assertNotNull(SavedChurchCodec.validated(first.copy(latitude = 55.0, longitude = 37.0, locationAccuracyMeters = 120f)))
    }

    @Test fun `OSM record retains its source and has no made up schedule`() {
        val record = church().copy(id = "osm:way:123", origin = SavedChurchOrigin.OPEN_STREET_MAP,
            latitude = 55.7, longitude = 37.6, sourceUrl = "https://www.openstreetmap.org/way/123")
        assertEquals(record, SavedChurchCodec.decode(SavedChurchCodec.encode(listOf(record))).single())
        assertTrue(record.asParish().schedule.isEmpty())
        assertNull(SavedChurchCodec.validated(record.copy(sourceUrl = "https://example.com/way/123")))
    }

    @Test fun `city name and address search is case tolerant and favorites do not select anything`() {
        val a = church("Фёдоровский храм", "Санкт-Петербург").copy(address = "Миргородская улица")
        val b = church("Никольский храм", "Казань")
        val list = listOf(a.asParish(), b.asParish())
        assertEquals(listOf(a.id), ChurchDirectory.search(list, "Петербург федоровский").map { it.id })
        assertEquals(listOf(a.id), ChurchDirectory.search(list, "МИРГОРОДСКАЯ").map { it.id })
        assertTrue(ChurchDirectory.search(list, "Казань Федоровский").isEmpty())
        assertEquals(b.id, ChurchDirectory.search(list, "", setOf(b.id)).first().id)
        assertEquals(a.id, list.first().id)
    }

    @Test fun `approximate location returns options outside the old 500 meter radius`() {
        val near = church().copy(latitude = 55.8, longitude = 49.1).asParish()
        val far = church().copy(latitude = 59.93, longitude = 30.31).asParish()
        val results = ChurchDirectory.nearby(listOf(far, near), 55.79, 49.1, 2_000f)
        assertEquals(near.id, results.single().church.id)
        assertTrue(results.single().distanceMeters > 500)
        assertTrue(ChurchDirectory.nearby(listOf(near), 55.8, 49.1, Float.NaN).isEmpty())
        assertTrue(ChurchDirectory.nearby(listOf(near), 55.8, 49.1, 12_000f).isEmpty())
    }

    @Test fun `oversized saves fail without losing the previously saved records`() {
        val preferences = MemoryPreferences()
        val store = ChurchDirectoryStore(preferences)
        var lastValid = emptyList<SavedChurchRecord>()
        var failed = false
        repeat(100) {
            val long = church("Н".repeat(160), "Г".repeat(100)).copy(address = "А".repeat(240),
                website = "https://example.com/" + "x".repeat(2_020))
            if (store.save(long)) lastValid = store.records()
            else { failed = true; assertEquals(lastValid, store.records()) }
        }
        assertTrue("The valid records must exceed the bounded JSON capacity before the record count", failed)
        assertTrue(store.records().isNotEmpty())
        assertEquals(lastValid, store.records())
    }

    @Test fun `own temple without invented validity dates is safe for the shared schedule API`() {
        val bundled = requireNotNull(javaClass.classLoader?.getResourceAsStream("churches.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val repository = ParishRepository(bundled)
        assertTrue(repository.scheduleFor(church().asParish(), LocalDate.of(2026, 11, 8)).isEmpty())
    }

    @Test fun `pinning requires a fresh fix while nearby search can reuse a recent one`() {
        val now = 300_000_000_000L
        val thirtySecondsAgo = now - 30_000_000_000L
        assertTrue(LocationFixPolicy.accepts(55.7, 37.6, 20f, thirtySecondsAgo, now))
        assertFalse(LocationFixPolicy.accepts(55.7, 37.6, 20f, thirtySecondsAgo, now, 500f, 5_000L))
        assertTrue(LocationFixPolicy.accepts(55.7, 37.6, 20f, now - 5_000_000_000L, now, 500f, 5_000L))
        assertFalse(LocationFixPolicy.accepts(55.7, 37.6, 20f, now + 1, now, 500f, 5_000L))
        assertFalse(LocationFixPolicy.accepts(55.7, 37.6, 700f, now, now, 500f, 5_000L))
    }

    @Test fun `wrong preference types recover without crashing and favorites are independent`() {
        val preferences = MemoryPreferences()
        preferences.values[ChurchDirectoryStore.RECORDS_KEY] = 7
        preferences.values[ChurchDirectoryStore.FAVORITES_KEY] = true
        val store = ChurchDirectoryStore(preferences)
        assertTrue(store.records().isEmpty())
        assertTrue(store.favorites().isEmpty())
        val record = church()
        assertTrue(store.save(record))
        assertEquals(setOf(record.id), store.toggleFavorite(record.id, setOf(record.id)))
        assertEquals(listOf(record), store.records())
        assertEquals(emptySet<String>(), store.toggleFavorite(record.id, setOf(record.id)))
        assertEquals(listOf(record), store.records())
    }
}

private class MemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
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
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
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
        override fun commit(): Boolean { if (clear) values.clear(); updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; return true }
        override fun apply() { commit() }
    }
}
