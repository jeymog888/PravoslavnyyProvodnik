package ru.liturgyguide.service.book

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.discovery.GeoPoint
import ru.liturgyguide.service.discovery.LocalChurchCatalog
import ru.liturgyguide.ui.toSavedChurch
import java.time.Instant
import java.time.LocalDate

class ChurchTypeClassificationTest {
    @Test fun explicitLordDedicationsUseLordVariantBeyondOneParish() {
        listOf("Храм Троицы Живоначальной в Листах", "Церковь Живоначальной Троицы в Хохлах",
            "Собор Святой Живоначальной Троицы", "Храм Святой Троицы", "Храм в честь Пресвятой Троицы",
            "Храм Воскресения Христова", "Храм Преображения Господня", "Храм Вознесения Господня",
            "Храм Рождества Христова", "Храм Сретения Господня").forEach { name ->
            assertEquals(name, ChurchTypeClassification("10", ChurchTypeEvidence.EXPLICIT_NAME), ChurchTypeClassifier.classifyName(name))
        }
    }

    @Test fun specificSupportedDedicationsAndIconNamesRemainRecognized() {
        mapOf("Храм святителя Николая" to "34", "Церковь Николая Чудотворца в Дербеневе" to "34",
            "Храм свт. Николая Мирликийского" to "34", "Храм Иоанна Предтечи" to "32",
            "Храм святителя Николая, архиепископа Мир Ликийских, чудотворца" to "34",
            "Храм Иоанна Предтечи и Крестителя Господня" to "32",
            "Храм Рождества Иоанна Крестителя" to "32", "Храм Воскресения Словущего" to "11",
            "Крестовоздвиженский храм в Москве" to "12",
            "Храм Воздвижения Честного и Животворящего Креста Господня" to "12",
            "Храм иконы Божией Матери \"Всецарица\" в Нахабино" to "20",
            "Храм Введения во храм Пресвятой Богородицы" to "20",
            "Храм Рождества Пресвятой Богородицы и Приснодевы Марии" to "20",
            "Сретенский монастырь. Храм Сретения Владимирской иконы Божией Матери" to "20").forEach { (name, expected) ->
            assertEquals(name, expected, ChurchTypeClassifier.classifyName(name).type)
        }
    }

    @Test fun nearbyPlacesAndMonasteriesCannotOverrideTheNamedBuilding() {
        assertEquals("34", ChurchTypeClassifier.classifyName("Храм Николая Чудотворца в Троицком").type)
        assertEquals("32", ChurchTypeClassifier.classifyName("Храм Иоанна Предтечи при Богородицком монастыре").type)
        assertEquals("34", ChurchTypeClassifier.classifyName("Храм Николая Чудотворца при храме Вознесения Господня").type)
        assertEquals("20", ChurchTypeClassifier.classifyName("Храм Успения Пресвятой Богородицы в Троицком").type)
        assertNull(ChurchTypeClassifier.classifyName("Храм в Троицком").type)
        assertNull(ChurchTypeClassifier.classifyName("Троицкая улица").type)
        for (name in listOf("Храм при монастыре Рождества Пресвятой Богородицы",
            "Храм на улице Рождества Богородицы", "Храм у церкви Успения Богородицы",
            "Храм в монастыре Рождества Пресвятой Богородицы")) {
            assertEquals(name, ChurchTypeEvidence.UNKNOWN, ChurchTypeClassifier.classifyName(name).evidence)
            assertNull(name, ChurchTypeClassifier.classifyName(name).type)
        }
        assertEquals("10", ChurchTypeClassifier.classifyName("Храм во имя Святой Троицы").type)
        assertEquals("20", ChurchTypeClassifier.classifyName("Храм в честь Рождества Пресвятой Богородицы").type)
    }

    @Test fun compositeDedicationsAreExplicitlyAmbiguousRatherThanFirstMatchWins() {
        listOf("Сретенский монастырь. Храм Воскресения Христова и Новомучеников и Исповедников Церкви Русской",
            "Храм Иоанна Предтечи и Двенадцати апостолов", "Храм Николая Чудотворца и Святой Троицы",
            "Храм Святой Троицы с приделом Николая Чудотворца",
            "Храм Николая Чудотворца. Храм Живоначальной Троицы").forEach { name ->
            val result = ChurchTypeClassifier.classifyName(name)
            assertEquals(name, ChurchTypeEvidence.AMBIGUOUS, result.evidence)
            assertNull(name, result.type)
        }
    }

    @Test fun unsupportedOrAbsentDedicationNeverClaimsVerifiedSaintCategory() {
        listOf("", "Сретенский монастырь", "Елисаветинский храм в Опалихе", "Храм святителя Николая Японского").forEach { name ->
            val result = ChurchTypeClassifier.classifyName(name)
            assertEquals(name, ChurchTypeEvidence.UNKNOWN, result.evidence)
            assertNull(name, result.type)
            assertEquals("30", result.requestType)
            assertTrue(ChurchTypeClassifier.description(result.requestType, result).contains("не подтвержден"))
        }
        assertNull(ChurchTypeClassifier.classify(null).type)
    }

    @Test fun metadataOutsideNameIsNotDedicationEvidenceAndCivilDateRulesAreUnchanged() {
        val church = parish("Храм Святой Троицы").copy(address = "улица Николая Чудотворца", shortName = "Богородичный храм")
        val evening = ServiceBookSelection.forCivilDate(LocalDate.parse("2026-11-14"), WorshipKind.VIGIL, church)
        assertEquals("2026-11-15", evening.date)
        assertEquals("10", evening.churchType)
        assertEquals("30", ServiceBookSelection.legacyChurchType(church))
        assertEquals("2026-11-14", ServiceBookSelection.forCivilDate(LocalDate.parse("2026-11-14"), WorshipKind.LITURGY, church).date)
    }

    @Test fun chosenVariantDescriptionDoesNotRelabelTheBookOrAssumeWhyItDiffers() {
        val classification = ChurchTypeClassifier.classifyName("Храм Святой Троицы")
        assertTrue(ChurchTypeClassifier.description("10", classification).contains("Господский"))
        val description = ChurchTypeClassifier.description("30", classification)
        assertEquals("Вариант источника: храм святого. По посвящению храма: Господский храм", description)
        assertFalse(description.contains("Сохранен"))
    }

    @Test fun theRealPackagedNikolskyCityLocatorDoesNotHideItsDedication() {
        val church = packagedNikolsky()
        assertEquals("Храм Николая Чудотворца (Воронеж)", church.name)
        assertEquals("Воронеж", church.city)
        assertEquals(ChurchTypeClassification("34", ChurchTypeEvidence.EXPLICIT_NAME), ChurchTypeClassifier.classify(church))
        assertEquals(ChurchTypeEvidence.UNKNOWN, ChurchTypeClassifier.classifyName(church.name).evidence)
        val key = ServiceBookSelection.forCivilDate(LocalDate.parse("2026-11-12"), WorshipKind.MATINS, church, eveningMatins = true)
        assertEquals(ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34"), key)
    }

    @Test fun anExactSeparateCityWorksForOtherSupportedDedicationsWithoutLosingPunctuation() {
        listOf(
            Triple("Храм Святой Троицы (Москва)", "Москва", "10"),
            Triple("Храм Иоанна Предтечи (Ростов-на-Дону)", "Ростов-на-Дону", "32"),
            Triple("Крестовоздвиженский храм (Санкт-Петербург)", "Санкт-Петербург", "12"),
            Triple("Храм Николая Чудотворца (  ВОРОНЕЖ  )  ", " Воронеж ", "34")
        ).forEach { (name, city, type) ->
            assertEquals(name, ChurchTypeClassification(type, ChurchTypeEvidence.EXPLICIT_NAME),
                ChurchTypeClassifier.classify(parish(name).copy(city = city)))
        }
    }

    @Test fun missingDifferentOrOnlyLexicallySimilarCitiesCannotAuthorizeTheSuffix() {
        val original = parish("Храм Николая Чудотворца (Воронеж)")
        listOf("", " ", "Москва", "Воронеж,", "г. Воронеж", "Воронежская область", "Воронеж и Москва").forEach { city ->
            assertEquals(city, ChurchTypeEvidence.UNKNOWN, ChurchTypeClassifier.classify(original.copy(city = city)).evidence)
        }
        listOf("Воронеж,", "г. Воронеж", "Воронежская область", "Воронеж и Москва", "Воро-неж").forEach { suffix ->
            val church = parish("Храм Николая Чудотворца ($suffix)").copy(city = "Воронеж")
            assertNull(suffix, ChurchTypeClassifier.classify(church).type)
        }
        val wrongCity = original.copy(city = "", address = "Воронеж", shortName = "Храм Николая Чудотворца")
        assertEquals(ChurchTypeEvidence.UNKNOWN, ChurchTypeClassifier.classify(wrongCity).evidence)
    }

    @Test fun onlyOneCompleteTerminalParenthesisCanBeUsedAsTheCityLocator() {
        listOf(
            "Храм святителя Николая ((Воронеж))",
            "Храм святителя Николая ( (Воронеж)",
            "Храм святителя Николая (Воронеж",
            "Храм святителя Николая (Воронеж))",
            "Храм святителя Николая (Воронеж) (Воронеж)",
            "Храм святителя Николая (Воронеж) закрыт",
            "Храм святителя Николая (Воронеж) в честь Николая Японского",
            "(Воронеж) Храм святителя Николая"
        ).forEach { name ->
            val original = ChurchTypeClassifier.classifyName(name)
            assertEquals(name, original, ChurchTypeClassifier.classify(parish(name).copy(city = "Воронеж")))
        }
        // A suffix-only match could peel the inner city off and turn this malformed name into type34.
        assertNull(ChurchTypeClassifier.classify(parish("Храм святителя Николая ( (Воронеж)").copy(city = "Воронеж")).type)
    }

    @Test fun missingGsonCityOrNameKeepsThePreviousSafeFallback() {
        val gson = Gson()
        val noCity = gson.fromJson("""{"name":"Храм Николая Чудотворца (Воронеж)"}""", Parish::class.java)
        assertEquals(ChurchTypeEvidence.UNKNOWN, ChurchTypeClassifier.classify(noCity).evidence)
        val plainName = gson.fromJson("""{"name":"Храм Николая Чудотворца"}""", Parish::class.java)
        assertEquals("34", ChurchTypeClassifier.classify(plainName).type)
        val noName = gson.fromJson("""{"city":"Воронеж"}""", Parish::class.java)
        assertEquals(ChurchTypeEvidence.UNKNOWN, ChurchTypeClassifier.classify(noName).evidence)
    }

    @Test fun saintQualifiersAndCompositeDedicationsNeverDisappearWithTheCity() {
        listOf("Храм святителя Николая (Японского)", "Храм святителя Николая (Японского) (Москва)",
            "Храм святителя Николая Японского (Москва)").forEach { name ->
            assertEquals(name, ChurchTypeEvidence.UNKNOWN, ChurchTypeClassifier.classify(parish(name)).evidence)
        }
        listOf("Храм Николая Чудотворца и Святой Троицы (Москва)",
            "Храм Святой Троицы с приделом Николая Чудотворца (Москва)",
            "Храм Николая Чудотворца. Храм Живоначальной Троицы (Москва)").forEach { name ->
            assertEquals(name, ChurchTypeEvidence.AMBIGUOUS, ChurchTypeClassifier.classify(parish(name)).evidence)
        }
    }

    @Test fun theNewRequestDoesNotRewriteOrBorrowAnAcceptedOldKeyAndPosition() {
        val church = packagedNikolsky()
        val oldKey = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "30", "0", "1")
        val position = BookReadingPosition(oldKey, "a".repeat(64), church.id, "accepted-block", "accepted-chapter",
            fraction = .42f, mode = BookReadingMode.RUSSIAN, lastTextMode = BookReadingMode.RUSSIAN)
        val preferences = AcceptanceMemoryPreferences()
        val store = BookReadingStore(preferences)
        assertTrue(store.save(position))
        val before = preferences.all.toMap()
        val oldRoute = BookRouteCodec.encode(BookRoute(oldKey, church.id))

        val newKey = ServiceBookSelection.forCivilDate(LocalDate.parse("2026-11-12"), WorshipKind.MATINS, church, eveningMatins = true)
        assertEquals("34", newKey.churchType)
        assertEquals("30", ChurchTypeClassifier.classifyName(church.name).requestType)
        assertEquals("34", ServiceBookSelection.legacyChurchType(church))
        assertEquals(position, store.last(church.id))
        assertEquals(position, store.positionFor(oldKey, church.id))
        assertNull(store.positionFor(newKey, church.id))
        assertNull(store.positionFor(oldKey.copy(churchType = "34"), church.id))
        assertEquals(before, preferences.all)
        assertEquals(BookRoute(oldKey, church.id), BookRouteCodec.decode(oldRoute))
        assertEquals("2026-11-13|MATINS|30|0|1", oldKey.canonical())
        assertEquals("https://azbyka.ru/worships/?date=2026-11-13&worship=matins&size=full&church=30&main_event=0&second_event=1", oldKey.sourceUrl())
    }

    private fun packagedNikolsky(): Parish {
        val catalog = requireNotNull(javaClass.classLoader?.getResourceAsStream("discovery/churches-ru.json"))
            .bufferedReader(Charsets.UTF_8).use { LocalChurchCatalog.decode(it, Instant.parse("2026-11-12T06:00:00Z")) }
        val place = catalog.nearby(GeoPoint(51.6596207, 39.2127643), 100) {}
            .single { it.id == "osm:way:77503842" }
        return requireNotNull(place.toSavedChurch()).asParish()
    }

    private fun parish(name: String) = Parish("classification-test", name, name, "Москва", "", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
}
