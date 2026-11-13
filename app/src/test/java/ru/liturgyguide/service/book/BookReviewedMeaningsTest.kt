package ru.liturgyguide.service.book

import com.google.gson.Gson
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*

/** Current app help is independently readable without replacing the accepted source edition. */
class BookReviewedMeaningsTest {
    private val gson = Gson()
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResource(name)).readText()

    @Test fun realAcceptedWeekdayPraisesNoLongerPromiseSticheraAndTheEditionStaysByteForByteTheSame() {
        val bytes = resource("legacy-presentation/matins-accepted-unchanged.json")
        assertEquals("c1a622cb4fad548172bf44179d0c34433cff89a6e7b377fb8a8d14b3adbe7ca1", BookText.sha256(bytes))
        val book = gson.fromJson(bytes, ServiceBook::class.java)
        assertEquals("bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", book.contentHash)
        val snapshot = gson.toJson(book)
        val praises = book.chapters.single { it.title == "Хвалитны" }
        val stichera = book.chapters.single { it.title == "Стихиры на стиховне" }
        assertTrue(praises.meaning.contains("чередуются"))
        assertEquals(listOf("Псалом 148", "Псалом 149", "Псалом 150"), praises.blocks
            .filter { it.kind == BookBlockKind.RUBRIC && it.original.startsWith("Псалом ") }.map { it.original })
        val shown = BookMeaningCatalog.displayMeaning(book, praises)
        assertTrue(shown.contains("Хвалите Господа"))
        assertTrue(shown.contains("В зависимости от дня"))
        assertFalse(shown.contains("чередуются"))
        assertNotEquals(shown, BookMeaningCatalog.displayMeaning(book, stichera))
        assertEquals(shown, BookMeaningCatalog.meaningFor(praises.title, praises.blocks.first().original, "matins"))
        // The remaining old help is not silently fed through the broad fallback catalog.
        for (title in listOf("Перед началом", "Ектения сугубая", "Песнь Пресвятыя Богородицы", "Шестопсалмие")) {
            val chapter = book.chapters.single { it.title == title }
            assertEquals(chapter.meaning, BookMeaningCatalog.displayMeaning(book, chapter))
        }
        book.chapters.forEach { BookMeaningCatalog.displayMeaning(book, it) }
        assertEquals(snapshot, gson.toJson(book))
        assertEquals(bytes, resource("legacy-presentation/matins-accepted-unchanged.json"))
    }

    @Test fun liturgyPsalmsBeatitudesAndRefrainedAntiphonsRemainDifferentFromPrayersAndMatinsAntiphons() {
        val antiphon = requireNotNull(BookMeaningCatalog.meaningFor("Первый вседневный антифон"))
        val psalms = requireNotNull(BookMeaningCatalog.meaningFor("Первый изобразительный антифон", "Псалом 102"))
        val beatitudes = requireNotNull(BookMeaningCatalog.meaningFor("Блаженства"))
        assertEquals(3, setOf(antiphon, psalms, beatitudes).size)
        assertTrue(antiphon.contains("припевом"))
        assertTrue(psalms.contains("Благослови, душе моя"))
        assertTrue(beatitudes.contains("Нагорной проповеди"))
        for (title in listOf("Молитва первого антифона", "Молитва второго антифона", "Молитва третьего антифона")) {
            val prayer = requireNotNull(BookMeaningCatalog.meaningFor(title))
            assertTrue(prayer.contains("Священник"))
            assertTrue(prayer.contains("тихо"))
            assertNotEquals(antiphon, prayer)
        }
        val steps = requireNotNull(BookMeaningCatalog.meaningFor("Степенны антифоны, глас 4", worship = "matins"))
        assertTrue(steps.contains("Святого Духа"))
        assertFalse(steps.contains("входу с Евангелием"))
        for ((title, worship) in listOf("Антифон 1" to "matins", "Первый антифон кафизмы" to "matins",
            "Изобразительны" to "figurative", "Блаженства" to "figurative")) {
            assertNull("Unreviewed service context: $title/$worship", BookMeaningCatalog.meaningFor(title, "Псалом 102", worship))
            val (book, chapter) = presentation(title, worship = if (worship == "matins") WorshipKind.MATINS else WorshipKind.HOUR9)
            assertEquals(STORED, BookMeaningCatalog.displayMeaning(book, chapter))
        }
        assertNull(BookMeaningCatalog.meaningFor("Изобразительны", "Отче наш", "liturgy"))
    }

    @Test fun eucharisticCanonIsNotACanonOdeAndSpecialEntranceHymnsDoNotReceiveOrdinaryCherubicHelp() {
        val anaphora = requireNotNull(BookMeaningCatalog.meaningFor("Евхаристический канон"))
        val ode = requireNotNull(BookMeaningCatalog.meaningFor("Песнь 9", worship = "matins"))
        assertTrue(anaphora.contains("Святого Духа"))
        assertTrue(anaphora.contains("Тебе поем"))
        assertFalse(anaphora.contains("припев"))
        assertNotEquals(anaphora, ode)
        assertFalse(requireNotNull(BookMeaningCatalog.meaningFor("Песнь Пресвятыя Богородицы", worship = "matins")).contains("частей канона"))
        for (title in listOf("Неизвестный канон местной службы", "Песнь 10", "Молитва после кафизмы")) {
            val (book, chapter) = presentation(title)
            assertEquals("Unreviewed heading must keep its accepted help: $title", STORED, BookMeaningCatalog.displayMeaning(book, chapter))
        }
        val ordinary = requireNotNull(BookMeaningCatalog.meaningFor("Херувимская песнь", "Иже Херувимы"))
        assertTrue(ordinary.contains("священный стол в алтаре"))
        assertTrue(ordinary.contains("освящение будет позже"))
        for ((title, text) in listOf("Вместо Херувимской песни" to "Да молчит всякая плоть человеча",
            "Херувимская песнь" to "Ныне Силы небесныя с нами невидимо служат",
            "Херувимская песнь" to "Вечери Твоея тайныя днесь, Сыне Божий",
            "Великий вход" to "Ныне Силы небесныя")) {
            val (book, chapter) = presentation(title, text)
            assertEquals("Special or unreviewed entrance: $title", STORED, BookMeaningCatalog.displayMeaning(book, chapter))
        }
        val (presanctified, chapter) = presentation("Херувимская песнь", "Иже Херувимы", bookTitle = "Литургия Преждеосвященных Даров")
        assertEquals(STORED, BookMeaningCatalog.displayMeaning(presanctified, chapter))
    }

    @Test fun kathismaSeventeenDoesNotPromiseSeveralPsalmsAndOnlyTheRealLatinCAliasIsRecognized() {
        val seventeenth = requireNotNull(BookMeaningCatalog.meaningFor("Кафизма 17", worship = "matins"))
        assertTrue(seventeenth.contains("Псалтири"))
        assertFalse(seventeenth.contains("несколько псалмов"))
        val svetilen = requireNotNull(BookMeaningCatalog.meaningFor("Светильны", worship = "matins"))
        assertEquals(svetilen, BookMeaningCatalog.meaningFor("Cветильны", worship = "matins"))
        assertTrue(svetilen.contains("после канона"))
        val (book, chapter) = presentation("Cедален", worship = WorshipKind.MATINS)
        assertEquals(STORED, BookMeaningCatalog.displayMeaning(book, chapter))
        assertNull(BookMeaningCatalog.meaningFor("Cедален", worship = "matins"))
    }

    @Test fun realThirdFigurativeAntiphonOnTwoDatesGetsBeatitudesHelpWithoutBroadeningOtherBlessedHeadings() {
        val sources = listOf("reviewed-meanings/liturgy-2026-11-14-church30.html" to "2026-11-14",
            "book/liturgy-2026-11-15.html" to "2026-11-15")
        for ((path, date) in sources) {
            val book = ServiceBookParser().parse(Jsoup.parse(resource(path)), ServiceBookKey(date), 12345)
            val chapter = book.chapters.single { it.title == "Блаженны, третий изобразительный антифон (на 8)" }
            assertTrue(BookText.normalized(chapter.blocks.first().original).startsWith("во царствии твоем помяни нас господи"))
            assertTrue(chapter.meaning.contains("Нагорной проповеди"))
            assertTrue(chapter.meaning.contains("Во Царствии Твоем"))
            assertEquals(chapter.meaning, BookMeaningCatalog.displayMeaning(book, chapter.copy(meaning = STORED)))
            for (title in listOf("Первый изобразительный антифон", "Второй изобразительный антифон")) {
                val psalm = book.chapters.single { it.title == title }
                assertTrue(psalm.meaning.contains("Благослови, душе моя"))
                assertNotEquals(psalm.meaning, chapter.meaning)
            }
            for (count in listOf(4, 6, 8, 10, 12)) {
                assertEquals(chapter.meaning, BookMeaningCatalog.displayMeaning(book, chapter.copy(
                    title = "Блаженны, третий изобразительный антифон (на $count)", meaning = STORED)))
            }
            for (title in listOf("Блаженны чистые сердцем", "Блаженны, первый изобразительный антифон (на 8)",
                "Блаженны, третий изобразительный антифон (на 9)", "Блаженны, третий изобразительный антифон (на 80)",
                "Блаженны, третий изобразительный антифон (на 8) местной редакции")) {
                assertEquals("Unreviewed title: $title", STORED,
                    BookMeaningCatalog.displayMeaning(book, chapter.copy(title = title, meaning = STORED)))
            }
            val wrongOpening = chapter.copy(meaning = STORED, blocks = listOf(chapter.blocks.first().copy(original = "Другой текст")))
            assertEquals(STORED, BookMeaningCatalog.displayMeaning(book, wrongOpening))
            assertEquals(STORED, BookMeaningCatalog.displayMeaning(book.copy(key = book.key.copy(worship = WorshipKind.MATINS)),
                chapter.copy(meaning = STORED)))
        }
    }

    private fun presentation(title: String, original: String = "Исходный текст", worship: WorshipKind = WorshipKind.LITURGY,
        bookTitle: String = worship.title): Pair<ServiceBook, BookChapter> {
        val chapter = BookChapter("chapter", title, STORED, listOf(BookBlock("block", BookBlockKind.PRAYER, null, original)))
        return ServiceBook(key = ServiceBookKey("2026-11-14", worship), title = bookTitle, sourceUrl = "https://azbyka.ru/",
            sourceTitle = "Test", fetchedAtMillis = 1, selectedEventTitle = null, chapters = listOf(chapter), contentHash = "") to chapter
    }
    private companion object { const val STORED = "Сохраненное пояснение этой редакции" }
}
