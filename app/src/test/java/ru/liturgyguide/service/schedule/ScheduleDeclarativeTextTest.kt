package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.LocalDate

class ScheduleDeclarativeTextTest {
    private val today = LocalDate.of(2026, 11, 13)
    private val source = "https://example.org/raspisanie/"
    private val parish = Parish(id = "qa:declarative", name = "Храм Рождества Пресвятой Богородицы",
        shortName = "Рождество", city = "Москва", address = "", phone = "", website = source,
        timezone = "Europe/Moscow", sourceUrl = source, scheduleSource = "", scheduleValidFrom = "",
        scheduleValidUntil = "", schedule = emptyList(), scheduleCoverage = "", scheduleNote = "")
    private val realDays = """
        === пятница, 13 ноября 2026 г. | 2026-11-13 | обычный
        Апп. от 70-ти Ста́хия, А́мплия, Урва́на, Нарки́сса, Апе́ллия и Аристову́ла. Мч. Епима́ха
        08:40 | Исповедь, Часы, Божественная Литургия, Молебен, Панихида | служащий: (иер. Олег); помогающий: иер. Виктор
        17:00 | Вечернее богослужение | служащий: (иер. Олег) (дежурный)

        === суббота, 14 ноября 2026 г. | 2026-11-14 | обычный
        Бессребреников и чудотворцев Космы́ и Дамиа́на Аси́йских
        08:40 | Исповедь, Часы, Божественная Литургия, Молебен с последованием малого освящения воды, Панихида | служащие: иер. Виктор, диак. Алексий; помогающий: прот. Иоанн
        14:00 | Огласительные беседы готовящимся к Таинству Крещения (актовый зал Воскресной школы) | иер. Димитрий
        17:00 | Исповедь, Всенощное бдение | служащие: иер. Павел, диак. Алексий; исповедующие: прот. Сергий, иер. Димитрий, иер. Виктор (дежурный), иер. Павел
        19:00 | Евангельские встречи (Воскресная школа, аудитория №5)

        === воскресенье, 15 ноября ♪ 2026 г. | 2026-11-15 | красный
        Неделя 24-я по Пятидесятнице. Мчч. Акинди́на, Пига́сия, Аффо́ния, Елпидифо́ра и Анемподи́ста
        06:40 | Исповедь, Часы, ранняя Божественная Литургия, Молебен, Лития | служащие: прот. Сергий, диак. Алексий; помогающие: прот. Иоанн, иер. Димитрий, иер. Виктор
        09:40 | Исповедь, Часы, поздняя Божественная Литургия, Молебен, Лития о всех трагически погибших в результате автомобильных катастроф | служащие: иер. Павел, диак. Алексий; помогающие: прот. Иоанн, иер. Димитрий ✧☩, иер. Виктор †
        17:00 | Вечерня с Акафистом Пресвятой Богородице перед Её Рудненской чудотворной иконой | служащий: иер. Виктор (дежурный)
    """.trimIndent()

    private fun inspect(data: String = realDays, before: String = "", after: String = "", type: String = "text/plain") =
        ScheduleHtmlParser.inspect(Jsoup.parse("<main><h1>${parish.name}</h1>$before<script type='$type'>$data</script>$after</main>", source), today, parish)

    @Test fun actualPublishedDayRowsKeepClockAndCombinedServiceStart() {
        val result = inspect()
        assertEquals(ScheduleParseDisposition.PARSED, result.disposition)
        assertEquals(listOf("2026-11-13T08:40", "2026-11-13T17:00", "2026-11-14T08:40", "2026-11-14T17:00",
            "2026-11-15T06:40", "2026-11-15T09:40", "2026-11-15T17:00"), result.events.map { "${it.date}T${it.time}" })
        assertEquals("Исповедь, Часы, Божественная Литургия, Молебен, Панихида", result.events.first().title)
        assertEquals("service_block", result.events.first().kind)
        assertFalse(result.events.any { "служащ" in it.title })
        assertFalse(result.yearInferred)
    }

    @Test fun surroundingGlobalCancellationWinsOverAllScriptEvents() {
        val result = inspect(before = "<p>Все богослужения отменены</p>")
        assertTrue(result.events.isEmpty())
        assertTrue(result.withdrawsAll)
    }

    @Test fun surroundingDatedCancellationSurvivesAtEitherSideOfScript() {
        for (before in listOf(true, false)) {
            val notice = "<p>14 ноября 2026 года все службы отменены</p>"
            val result = inspect(before = if (before) notice else "", after = if (before) "" else notice)
            assertFalse(result.events.any { it.date == "2026-11-14" })
            assertTrue("2026-11-14" in result.withdrawnDates)
            assertTrue(result.events.any { it.date == "2026-11-13" })
        }
    }

    @Test fun cancellationInsideDayAndCelebrantMetadataNeverDisappearWithTitleCleanup() {
        val result = inspect(realDays.replace("служащий: (иер. Олег) (дежурный)", "служба отменена"))
        assertFalse(result.events.any { it.date == "2026-11-13" && it.time == "17:00" })
        assertTrue("2026-11-13T17:00" in result.withdrawnStarts)
        val entireDay = inspect(realDays.replace("Апп. от 70-ти", "Все богослужения отменены\nАпп. от 70-ти"))
        assertFalse(entireDay.events.any { it.date == "2026-11-13" })
    }

    @Test fun surroundingDifferentBuildingCannotBeDiscarded() {
        val result = inspect(before = "<h2>Храм святителя Николая Чудотворца</h2>")
        assertTrue(result.events.isEmpty())
        assertEquals(ScheduleParseDisposition.REJECTED, result.disposition)
    }

    @Test fun differentBuildingInsideDayOnlyBlocksItsOwnEvents() {
        val result = inspect(realDays.replace("Апп. от 70-ти", "В храме святителя Николая Чудотворца\nАпп. от 70-ти"))
        assertFalse(result.events.toString(), result.events.any { it.date == "2026-11-13" })
        assertTrue(result.events.toString(), result.events.any { it.date == "2026-11-14" })
    }

    @Test fun conflictingPrintedDateOrWeekdayIsNotRepairedFromIso() {
        for (data in listOf(realDays.replace("13 ноября 2026", "12 ноября 2026"), realDays.replace("пятница, 13", "четверг, 13"))) {
            val result = inspect(data)
            assertEquals(ScheduleParseDisposition.REJECTED, result.disposition)
            assertTrue(result.safetyConflict)
            assertTrue(result.events.isEmpty())
        }
    }

    @Test fun largeArchiveDoesNotConsumeWindowBeforeCurrentDays() {
        val old = (1..300).joinToString("\n") { index ->
            val date = LocalDate.of(2025, 1, 1).plusDays(index.toLong())
            val month = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")[date.monthValue - 1]
            "=== ${date.dayOfMonth} $month ${date.year} г. | $date | обычный\n08:00 | Литургия\n17:00 | Вечерня\nСведения о дне\n"
        }
        assertEquals(7, inspect(old + "\n" + realDays).events.size)
    }

    @Test fun executableScriptsAndArbitraryTextAreNeverExecutedOrParsedAsThisFormat() {
        assertTrue(inspect(type = "application/javascript").events.isEmpty())
        assertTrue(inspect(data = "const schedule = '$realDays';").events.isEmpty())
        assertEquals(ScheduleParseDisposition.UNSUPPORTED, inspect(data = realDays.replace("2026-11", "2025-11").replace("2026 г.", "2025 г.").replace("пятница, ", "").replace("суббота, ", "").replace("воскресенье, ", "")).disposition)
    }

    @Test fun inputDocumentIsUnchangedAndEveryPublicParseEntryPointAgrees() {
        val document = Jsoup.parse("<main><script type='text/plain'>$realDays</script></main>", source)
        val before = document.outerHtml()
        val expected = ScheduleHtmlParser.inspect(document, today).events
        assertEquals(expected, ScheduleHtmlParser.parse(document, today))
        assertEquals(expected, ScheduleHtmlParser.parse(document.outerHtml(), source, today))
        assertEquals(before, document.outerHtml())
    }

    @Test fun visitorCommentDataCannotPoisonOrSupplyTheParishSchedule() {
        val comment = "<div id='comments'><script type='text/plain'>=== malformed date</script></div>"
        assertEquals(7, inspect(after = comment).events.size)
        val onlyComment = Jsoup.parse("<main><div class='comments'><script type='text/plain'>$realDays</script></div></main>", source)
        assertTrue(ScheduleHtmlParser.inspect(onlyComment, today, parish).events.isEmpty())
    }

    @Test fun incompleteContextCannotHideCancellationBeyondTheExtractionBudget() {
        val day = "=== пятница, 13 ноября 2026 г. | 2026-11-13 | обычный\n08:00 | Литургия\n" +
            List(2_001) { "Сведения о дне" }.joinToString("\n")
        val result = inspect(day, after = "<p>Все богослужения отменены</p>")
        assertTrue(result.events.isEmpty())
        assertEquals(ScheduleParseDisposition.REJECTED, result.disposition)
        assertTrue(result.safetyConflict)
        assertFalse(result.withdrawsAll) // Incomplete evidence is not a proven cancellation.
    }

    @Test fun aPlainVenueRestrictionCannotBeOverriddenByTheOuterParishName() {
        val result = inspect(before = "<p>В храме святителя Николая Чудотворца</p>")
        assertTrue(result.events.isEmpty())
    }

    @Test fun aNewExplicitVenueHeadingEndsThePreviousPlainVenueScope() {
        val result = inspect(before = "<p>В храме святителя Николая Чудотворца</p><h2>${parish.name}</h2>")
        assertEquals(7, result.events.size)
    }

    @Test fun aGenericScheduleHeadingCannotEraseTheUnderlyingParishSignature() {
        val before = "<p>В храме святителя Николая Чудотворца</p><h2>Храм святителя Николая Чудотворца</h2><h1>Расписание</h1>"
        assertTrue(inspect(before = before).events.isEmpty())
    }
}
