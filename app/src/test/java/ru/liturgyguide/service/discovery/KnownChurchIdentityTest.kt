package ru.liturgyguide.service.discovery

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.ui.toSavedChurch
import java.time.Instant

class KnownChurchIdentityTest {
    private fun asset(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader(Charsets.UTF_8)
    private fun available(): Map<String, Parish> = asset("churches.json").use { ParishRepository(it.readText()) }.directory.churches.associateBy { it.id }
    private fun places() = asset("discovery/churches-ru.json").use {
        LocalChurchCatalog.decode(it, Instant.parse("2026-11-12T06:00:00Z"))
    }
    private fun opalikha() = places().nearby(GeoPoint(55.82553, 37.24345), 500) {}
        .single { it.id == "osm:way:186160039" }

    @Test fun actualPackagedObjectsSelectTheirSeparatePreparedParishes() {
        val catalog = places()
        val known = available()
        val opalikha = catalog.nearby(GeoPoint(55.82553, 37.24345), 500) {}.single { it.id == "osm:way:186160039" }
        assertSame(known["elisavetinsky_opalikha"], KnownChurchIdentity.existingFor(opalikha, known))
        val monastery = catalog.nearby(GeoPoint(55.766, 37.63), 500) {}
        for ((mapId, parishId) in listOf("osm:way:51259798" to "sretensky_old", "osm:way:484755695" to "sretensky_new")) {
            val found = KnownChurchIdentity.existingFor(monastery.single { it.id == mapId }, known)
            assertSame(known[parishId], found)
            assertTrue(requireNotNull(found).schedule.isNotEmpty())
        }
        assertNull(KnownChurchIdentity.existingFor(monastery.single { it.id == "osm:relation:3325777" }, known))
    }

    @Test fun anAlreadySavedOsmIdentityHasPrioritySoItsReadingAndCacheKeyDoNotChange() {
        val place = opalikha()
        val own = requireNotNull(place.toSavedChurch()).asParish().copy(website = "https://own-source.example/schedule")
        val known = available() + (own.id to own)
        assertSame(own, KnownChurchIdentity.existingFor(place, known))
        assertEquals(place.id, KnownChurchIdentity.existingFor(place, known)?.id)
    }

    @Test fun namesAndDistanceNeverCreateAnAliasForAnotherObjectOrAnotherBuilding() {
        val place = opalikha()
        val known = available()
        assertNull(KnownChurchIdentity.existingFor(place.copy(id = "osm:way:123", sourceUrl = "https://www.openstreetmap.org/way/123"), known))
        assertNull(KnownChurchIdentity.existingFor(place.copy(sourceUrl = "https://www.openstreetmap.org/node/186160039"), known))
        assertNull(KnownChurchIdentity.existingFor(place.copy(name = "Храм прп. Сергия Радонежского"), known))
        assertNull(KnownChurchIdentity.existingFor(place.copy(isChapel = true, kind = DiscoveredPlaceKind.CHAPEL), known))
        assertNull(KnownChurchIdentity.existingFor(place.copy(kind = DiscoveredPlaceKind.MONASTERY), known))
        assertNull(KnownChurchIdentity.existingFor(place.copy(center = GeoPoint(55.84, 37.17)), known))
        assertNull(KnownChurchIdentity.existingFor(place, known - "elisavetinsky_opalikha"))
    }

    @Test fun anExpectedParishIdWithChangedIdentityDoesNotAuthorizeAnAlias() {
        val place = opalikha()
        val known = available()
        val original = known.getValue("elisavetinsky_opalikha")
        for (replacement in listOf(original.copy(name = "Другой храм"), original.copy(website = "https://other.example/"),
            original.copy(sourceUrl = "https://other.example/"), original.copy(id = "another"))) {
            assertNull(KnownChurchIdentity.existingFor(place, known + (original.id to replacement)))
        }
    }

    @Test fun thePreviouslyCapturedPhotonResponseResolvesOnlyTheTwoBuildingsNotTheirMonastery() {
        val captured = asset("discovery/name-parser-monastery-complex.json").use { DiscoveryJson.places(it.readText()) }
        val known = available()
        assertEquals("sretensky_old", KnownChurchIdentity.existingFor(captured.single { it.id == "osm:way:51259798" }, known)?.id)
        assertEquals("sretensky_new", KnownChurchIdentity.existingFor(captured.single { it.id == "osm:way:484755695" }, known)?.id)
        assertNull(KnownChurchIdentity.existingFor(captured.single { it.id == "osm:relation:3325777" }, known))
    }
}
