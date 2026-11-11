package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.WorshipKind
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

    private fun parish(name: String) = Parish("classification-test", name, name, "Москва", "", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
}
