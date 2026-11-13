package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test

class BookMeaningCatalogTest {
    @Test fun accentedHeadingKeepsCatechumensDistinctFromFerventPrayer() {
        val catechumens = requireNotNull(BookMeaningCatalog.meaningFor("Моли́тва об оглаше́нных"))
        val fervent = requireNotNull(BookMeaningCatalog.meaningFor("Сугу́бая ектения"))
        assertTrue(catechumens.contains("готовятся к Крещению"))
        assertTrue(catechumens.contains("древний порядок"))
        assertTrue(fervent.contains("трижды"))
        assertNotEquals(catechumens, fervent)
    }

    @Test fun greatEntranceDoesNotClaimTheGiftsAreAlreadyConsecrated() {
        val entrance = requireNotNull(BookMeaningCatalog.meaningFor("Великий вход"))
        val anaphora = requireNotNull(BookMeaningCatalog.meaningFor("Святое Возношение"))
        assertTrue(entrance.contains("освящение еще впереди"))
        assertTrue(anaphora.contains("Святого Духа"))
        assertNotEquals(entrance, anaphora)
    }

    @Test fun matinsMeaningDoesNotAssertItIsCurrentlyMorning() {
        val meaning = requireNotNull(BookMeaningCatalog.meaningFor("Утреня", worship = "matins"))
        assertTrue(meaning.contains("могут служить вечером"))
    }

    @Test fun scriptureMentionInsideUnknownPrayerDoesNotMisidentifyTheChapter() {
        assertNull(BookMeaningCatalog.meaningFor("Молитва неизвестной редакции",
            "Просим помощи. В конце упоминается чтение Евангелия", "liturgy"))
        assertNull(BookMeaningCatalog.meaningFor("Новости прихода", "Отче наш", "liturgy"))
    }

    @Test fun unknownChapterRemainsUnknownInsteadOfAConfidentGenericExplanation() {
        assertNull(BookMeaningCatalog.meaningFor("Служба особого местного чина", worship = "liturgy"))
    }

    @Test fun numberedCanonSongsAndOrdinaryPsalmDoNotShareTheSameExplanation() {
        val song = requireNotNull(BookMeaningCatalog.meaningFor("Песнь 9", worship = "matins"))
        val psalm = requireNotNull(BookMeaningCatalog.meaningFor("Псалом 33", worship = "liturgy"))
        assertTrue(song.contains("канон", ignoreCase = true))
        assertTrue(psalm.contains("библейский псалом", ignoreCase = true))
        assertNotEquals(song, psalm)
    }

    @Test fun altarTextExplainsParallelQuietPrayerInsteadOfAnotherPublicStage() {
        val meaning = requireNotNull(BookMeaningCatalog.meaningFor("В алтаре"))
        assertTrue(meaning.contains("тихо"))
        assertTrue(meaning.contains("другой текст"))
    }

    @Test fun antiphonPrayersExplainThePriestsPetitionRatherThanTheChoirsSong() {
        val first = requireNotNull(BookMeaningCatalog.meaningFor("Моли́тва пе́рвого антифо́на"))
        val second = requireNotNull(BookMeaningCatalog.meaningFor("Молитва второго антифона"))
        val third = requireNotNull(BookMeaningCatalog.meaningFor("Молитва третьего антифона"))
        val sung = requireNotNull(BookMeaningCatalog.meaningFor("Первый вседневный антифон"))
        listOf(first, second, third).forEach { explanation ->
            assertTrue(explanation.contains("Священник"))
            assertTrue(explanation.contains("тихо"))
            assertNotEquals(sung, explanation)
        }
        assertTrue(first.contains("милости"))
        assertTrue(second.contains("сохранить Церковь"))
        assertTrue(third.contains("согласную молитву"))
        assertTrue(third.contains("вечную жизнь"))
    }

    @Test fun matinsAntiphonsDoNotClaimAnApproachingSmallEntranceOfTheLiturgy() {
        val meaning = requireNotNull(BookMeaningCatalog.meaningFor("Степенны антифоны, глас 4", worship = "matins"))
        assertTrue(meaning.contains("утрене"))
        assertTrue(meaning.contains("перед чтением Евангелия"))
        assertTrue(meaning.contains("Святого Духа"))
        assertFalse(meaning.contains("входу с Евангелием"))
    }
}
