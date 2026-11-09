package ru.liturgyguide.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.time.LocalDate

class ParishRepositoryTest {
    private fun packagedJson(): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("churches.json")
    ) { "The production parish directory must be included in unit test resources" }
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun repository() = ParishRepository(packagedJson())

    @Test
    fun `default is the intended Opalikha parish and not its separate Nakhabino church`() {
        val repository = repository()
        val churches = repository.directory.churches

        assertEquals(4, churches.size)
        assertEquals(setOf("elisavetinsky_opalikha", "vsetsaritsa_nakhabino", "sretensky_old", "sretensky_new"), churches.map { it.id }.toSet())
        assertEquals("elisavetinsky_opalikha", repository.directory.defaultChurchId)
        val main = repository.selected(null)
        val affiliated = repository.selected("vsetsaritsa_nakhabino")
        assertTrue(main.address.contains("Опалиха"))
        assertTrue(main.address.contains("Пушкина, 2"))
        assertTrue(affiliated.address.contains("Нахабино"))
        assertNotEquals(main.id, affiliated.id)
        assertNotEquals(main.phone, affiliated.phone)
    }

    @Test
    fun `Sretensky buildings retain separate published times and expire after the verified week`() {
        val repository = repository()
        val sunday = LocalDate.of(2026, 11, 15)
        val old = repository.selected("sretensky_old")
        val new = repository.selected("sretensky_new")
        assertEquals(listOf("07:00"), repository.scheduleFor(old, sunday).filter { it.kind == "liturgy" }.map { it.time })
        assertEquals(listOf("10:00"), repository.scheduleFor(new, sunday).filter { it.kind == "liturgy" }.map { it.time })
        assertTrue(repository.scheduleFor(old, sunday.plusDays(1)).isEmpty())
        assertTrue(repository.scheduleFor(new, sunday.plusDays(1)).isEmpty())
        assertEquals(2, ChurchDirectory.search(repository.directory.churches, "сретенский", emptySet()).size)
    }

    @Test
    fun `November 8 uses the separate published times of each church`() {
        val repository = repository()
        val date = LocalDate.of(2026, 11, 8)

        assertEquals(listOf("07:00", "09:30"), repository.scheduleFor(
            repository.selected("elisavetinsky_opalikha"), date
        ).map { it.time })
        assertEquals(listOf("08:00"), repository.scheduleFor(
            repository.selected("vsetsaritsa_nakhabino"), date
        ).map { it.time })
    }

    @Test
    fun `November schedule never carries forward to another month or year`() {
        val repository = repository()
        val outsideDates = listOf(
            LocalDate.of(2026, 10, 31), LocalDate.of(2026, 12, 1), LocalDate.of(2027, 11, 8)
        )

        repository.directory.churches.forEach { church ->
            outsideDates.forEach { date ->
                assertTrue("${church.id} must have no assumed schedule on $date", repository.scheduleFor(church, date).isEmpty())
            }
        }
    }

    @Test
    fun `a weekday liturgy in Opalikha does not become a service in Nakhabino`() {
        val repository = repository()
        val date = LocalDate.of(2026, 11, 5)

        assertEquals(listOf("08:00"), repository.scheduleFor(repository.selected("elisavetinsky_opalikha"), date).map { it.time })
        assertTrue(repository.scheduleFor(repository.selected("vsetsaritsa_nakhabino"), date).isEmpty())
    }

    @Test
    fun `stale saved church id falls back to the intended default`() {
        val repository = repository()

        assertEquals("elisavetinsky_opalikha", repository.selected("vsecaritsa").id)
        assertEquals("elisavetinsky_opalikha", repository.selected("deleted-church").id)
    }

    @Test
    fun `parish GPS coordinates retain the separate points published by the parish`() {
        val repository = repository()
        val main = repository.selected("elisavetinsky_opalikha")
        val affiliated = repository.selected("vsetsaritsa_nakhabino")

        assertEquals(55 + 49.0 / 60 + 31.91 / 3600, requireNotNull(main.latitude), 0.000001)
        assertEquals(37 + 14.0 / 60 + 36.45 / 3600, requireNotNull(main.longitude), 0.000001)
        assertEquals(55 + 50.0 / 60 + 26.30 / 3600, requireNotNull(affiliated.latitude), 0.000001)
        assertEquals(37 + 10.0 / 60 + 23.90 / 3600, requireNotNull(affiliated.longitude), 0.000001)
        assertNotEquals(main.latitude, affiliated.latitude)
        assertNotEquals(main.longitude, affiliated.longitude)
    }

    @Test
    fun `packaged schedule retains provenance coverage and validity instead of implying live data`() {
        val source: JsonObject = JsonParser.parseString(packagedJson()).asJsonObject
        LocalDate.parse(source["verifiedAt"].asString)
        assertTrue(source["note"].asString.isNotBlank())
        source.getAsJsonArray("churches").forEach { element ->
            val church = element.asJsonObject
            (listOf("sourceUrl", "website") + if (church.has("latitude")) listOf("coordinateSourceUrl") else emptyList()).forEach { key ->
                val url = URI(church[key].asString)
                assertEquals("https", url.scheme)
                assertFalse(url.host.isNullOrBlank())
            }
            listOf("scheduleSource", "scheduleCoverage", "scheduleNote", "phoneSource").forEach { key ->
                assertTrue("${church["id"]}: missing provenance field $key", church[key].asString.isNotBlank())
            }
            val from = LocalDate.parse(church["scheduleValidFrom"].asString)
            val until = LocalDate.parse(church["scheduleValidUntil"].asString)
            assertFalse(until.isBefore(from))
            val schedule = church.getAsJsonArray("schedule")
            val keys = schedule.map { it.asJsonObject.let { entry -> "${entry["date"].asString} ${entry["time"].asString}" } }
            assertEquals("Repeated times in ${church["id"]}", keys.size, keys.distinct().size)
            assertTrue(schedule.all { it.asJsonObject["kind"].asString in setOf("liturgy", "evening", "moleben") })
        }
    }
}
