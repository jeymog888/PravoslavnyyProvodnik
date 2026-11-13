package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.ParishRepository

class KnownOtherScheduleVenueTest {
    private val curated = ParishRepository(requireNotNull(javaClass.getResource("/churches.json")).readText()).directory.churches
    private val child = curated.single { it.id == "vsetsaritsa_nakhabino" }
    private val publisher = curated.single { it.id == "elisavetinsky_opalikha" }
    private val site = ImageScheduleFixture.parent

    @Test fun twoIndependentCuratedIdentitiesCanProveTheOtherPublisherWithoutChangingSelectedAuthority() {
        val selected = child.copy(website = site)
        val known = requireNotNull(KnownOtherScheduleVenue.resolve(selected, site, site, curated))
        assertEquals(publisher, known.parish)
        assertTrue(known.validFor(selected, site, site))
        assertEquals(site, selected.website)
        assertNull(KnownOtherScheduleVenue.resolve(publisher, site, site, curated))
    }

    @Test fun anotherIdOrBorrowedAddressNeverBecomesACuratedSelectedIdentity() {
        for (unknown in listOf(child.copy(id = "osm:way:226509018"), child.copy(name = "Церковь Казанской иконы Божией Матери"),
            child.copy(address = publisher.address), child.copy(latitude = null), child.copy(longitude = publisher.longitude)))
            assertNull(KnownOtherScheduleVenue.resolve(unknown, site, site, curated))
        assertNull(KnownOtherScheduleVenue.resolve(child, site, site, emptyList()))
    }

    @Test fun exactPublisherPathAndUniqueOwnerAreRequiredInsteadOfJustTheHost() {
        val scoped = publisher.copy(website = "https://shared.example/publisher")
        val records = listOf(child, scoped)
        val page = "https://shared.example/publisher/schedule"
        assertNotNull(KnownOtherScheduleVenue.resolve(child, page, page, records))
        for (foreign in listOf("https://shared.example/publisher-other/schedule", "https://shared.example/other",
            "http://shared.example/publisher/schedule", "https://different.example/publisher/schedule",
            "https://shared.example/publisher/%2e%2e/other")) {
            assertNull(foreign, KnownOtherScheduleVenue.resolve(child, foreign, page, records))
            assertNull(foreign, KnownOtherScheduleVenue.resolve(child, page, foreign, records))
        }
        assertNull(KnownOtherScheduleVenue.resolve(child, page, page,
            records + scoped.copy(id = "another-owner", name = "Казанский храм")))
    }

    @Test fun DuplicateOrSameBuildingPointsAndUnprovedPublisherMetadataCannotBeOtherEvidence() {
        for (other in listOf(publisher.copy(latitude = child.latitude, longitude = child.longitude),
            publisher.copy(latitude = null), publisher.copy(sourceUrl = ""), publisher.copy(website = ""),
            publisher.copy(name = "Елисаветинский монастырь")))
            assertNull(KnownOtherScheduleVenue.resolve(child, site, site, listOf(child, other)))
        assertNull(KnownOtherScheduleVenue.resolve(child, site, site, curated + publisher))
        val witness = requireNotNull(KnownOtherScheduleVenue.resolve(child, site, site, curated))
        assertFalse(witness.validFor(child.copy(city = publisher.city), site, site))
    }
}
