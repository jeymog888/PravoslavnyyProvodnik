package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.discovery.*
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.book.ServiceBookSelection

class ServiceTitlePresentationTest {
    @Test fun anotherServiceMentionedOnlyAsATimeReferenceCannotChooseTheWrongBookOrHeading() {
        for (title in listOf("Молебен после литургии", "Панихида после поздней литургии", "Молебен перед Божественной литургией",
            "Молебен после утренней литургии", "Молебен после вечерней литургии")) {
            assertNull(title, ServiceBookSelection.kindFor(title))
            assertNull(title, serviceKindTitle(title))
        }
        assertEquals(WorshipKind.LITURGY, ServiceBookSelection.kindFor("Часы, Литургия. Панихида после литургии"))
        assertEquals(WorshipKind.VESPERS, ServiceBookSelection.kindFor("Вечерня после литургии"))
        assertEquals(WorshipKind.LITURGY, ServiceBookSelection.kindFor("После вечерни - Литургия"))
        assertEquals(WorshipKind.LITURGY, ServiceBookSelection.kindFor("После утрени - Литургия"))
    }

    @Test fun dayCommemorationDoesNotHideTheActualServiceAndTheOriginalSurvives() {
        val original = "Сщмч. Зиновия, еп. Егейского, и сестры его мц. Зиновии. Часы, Литургия"
        assertEquals("Часы и литургия", serviceKindTitle(original))
        assertEquals(original, serviceSourceTitle(original))
        assertEquals("08:00\nВ расписании храма: " + original, serviceActionSubtitle(original, "08:00"))
    }

    @Test fun compoundEveningServiceIsNotRelabeledAsVigilWithoutThatWord() {
        assertEquals("Вечерня и утреня", serviceKindTitle("Святые дня. Вечерня, Утреня, 1 час"))
        assertEquals("Всенощное бдение", serviceKindTitle("Праздник. Всенощное бдение"))
        assertEquals("Вечерня", serviceKindTitle("Вечерня"))
    }

    @Test fun shortAndUnknownTitlesAreNotDuplicatedOrInvented() {
        assertEquals("Литургия", serviceKindTitle("Литургия"))
        assertNull(serviceSourceTitle("Литургия"))
        assertNull(serviceKindTitle("Молебен перед началом учения"))
        assertNull(serviceSourceTitle("Молебен перед началом учения"))
    }

    @Test fun compactCaptionRetainsUncertaintyAndActualStationFailure() {
        val nearby = DiscoveryResult.Success<DiscoveredChurch>(emptyList(), 1L, DiscoveryCacheStatus.FRESH_NETWORK,
            DiscoveryProvider.PHOTON, GeoPoint(55.7, 37.6), 2_000,
            notice = "Рядом может быть несколько храмов с похожим названием. Сверьте адрес")
        val caption = discoveryResultCaption(nearby, approximate = true)
        assertTrue(caption.contains("2 км"))
        assertTrue(caption.contains("приблизительное"))
        assertTrue(caption.contains("список может быть неполным"))
        assertEquals(1, Regex("Сверьте").findAll(caption).count())
        val failure = "Не удалось проверить станцию. Ниже результаты по названию - сверьте адрес"
        assertEquals(failure, discoveryResultCaption(nearby.copy(queryCenter = null, notice = failure), approximate = false))
    }
}
