package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class WeekdayCalendarRootSafetyTest {
    private val site = "https://calendar.example/"
    private val today = LocalDate.of(2026, 11, 15)
    private val now = Instant.parse("2026-11-15T16:35:59Z")
    private val parish = Parish("custom:root-calendar", "Никольский храм", "Никольский храм", "", "", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
    private fun row(day: Int = 21, weekday: String = "Сб.", time: String = "09:00",
        feast: String = "Память святого", service: String = "Литургия") =
        "<tr><td>$weekday</td><td>$day</td><td>$feast</td><td>$time</td><td>$service</td></tr>"
    private fun calendar(rows: String = row(), before: String = "", caption: String = "") =
        "<article><h2>Расписание богослужений в ноябре 2026 года</h2>$before<table>$caption$rows</table></article>"
    private val neighbors = "<article><h2>Прочие службы</h2>" +
        "<p>21 ноября 2026 суббота 10:00 Литургия</p><p>26 ноября 2026 четверг 10:00 Литургия</p></article>"
    private val neighborEvents = listOf(InternetService("2026-11-21", "10:00", "Литургия", "liturgy"),
        InternetService("2026-11-26", "10:00", "Литургия", "liturgy"))
    private fun inspect(html: String) = ScheduleHtmlParser.inspect(Jsoup.parse(html, site), today, parish)
    private fun response(url: String, html: String) = ScheduleResponse(url, html.toByteArray(), "text/html; charset=UTF-8")
    private class Cache : ScheduleCacheStore {
        val values = mutableMapOf<String, String>()
        val safetyWrites = mutableListOf<String>()
        override fun read(parishId: String) = values[parishId]
        override fun write(parishId: String, value: String) { values[parishId] = value }
        override fun writeSafety(parishId: String, value: String) { safetyWrites += value; write(parishId, value) }
    }
    private fun repository(cache: Cache, transport: ScheduleTransport) = InternetScheduleRepository(transport, cache,
        siteResolver = ScheduleSiteResolver { null })

    @Test fun nestedQualifiersNeverAcquireTheWiderAuthorityOfTheirInlineCancellationFragment() {
        val qualified = listOf(
            calendar(row() + row(26, "Чт."), caption = "<caption>25 ноября <strong>Все богослужения отменены</strong></caption>"),
            calendar(row() + row(26, "Чт."), caption = "<caption>после 16:00 <span>Все службы отменены</span></caption>"),
            calendar(before = "<p>25 ноября <strong>Все богослужения отменены</strong></p>"),
            calendar(before = "<div>после 16:00 <p>Все службы отменены</p></div>"),
            calendar(before = "<div>2025 <p>Все богослужения отменены</p></div>"))
        for (html in qualified) {
            val result = inspect(html)
            assertTrue(result.events.isEmpty())
            assertTrue(result.safetyConflict && result.unresolvedWithdrawal)
            assertTrue(result.withdrawnStarts.isEmpty())
            assertTrue(result.withdrawnDates.isEmpty())
            assertFalse(result.withdrawsAll)
        }
    }

    @Test fun ownerNoticeIsConsumedOnceAndOnlyItsListedStartIsWithdrawn() {
        for (notice in listOf("<p>Все богослужения отменены</p>",
            "<p>Все <strong>богослужения</strong> отменены</p>",
            "<p>Все богослужения не<br>состоятся</p>")) {
            val result = inspect(calendar(before = notice) + neighbors)
            assertEquals(neighborEvents, result.events)
            assertEquals(setOf("2026-11-21T09:00"), result.withdrawnStarts)
            assertTrue(result.withdrawnDates.isEmpty())
            assertFalse(result.withdrawsAll || result.unresolvedWithdrawal || result.safetyConflict)
        }
    }

    @Test fun ownerWithdrawalIsDurableBeforeFollowingIoAndCannotReviveOffline() = runBlocking {
        for (notice in listOf("<p>Все богослужения отменены</p>", "<p>Все богослужения не<br>состоятся</p>")) {
            val cache = Cache()
            var changed = false
            var sawSafetyBeforeIo = false
            val follow = "<a href='/schedule-unavailable/'>Расписание богослужений</a>"
            val repo = repository(cache, ScheduleTransport { url -> when (url) {
                site -> response(url, calendar(before = if (changed) notice else "") + neighbors + follow)
                site + "schedule-unavailable/" -> {
                    if (changed) {
                        val durable = requireNotNull(ScheduleCacheCodec.decode(cache.read(parish.id), parish))
                        assertTrue(cache.safetyWrites.isNotEmpty())
                        assertTrue(durable.sourceHasWithdrawals)
                        assertEquals(neighborEvents, requireNotNull(durable.publication).events)
                        sawSafetyBeforeIo = true
                    }
                    throw IOException("Controlled failure after the source safety write")
                }
                else -> throw IOException("Unmapped controlled request")
            } })
            assertEquals(3, requireNotNull(repo.refresh(parish, now, sourceOverride = site).publication).events.size)
            changed = true
            val after = repo.refresh(parish, now.plusSeconds(60), force = true)
            assertTrue(sawSafetyBeforeIo)
            assertTrue(after.sourceHasWithdrawals)
            assertEquals(neighborEvents, requireNotNull(after.publication).events)
            val offline = repository(cache, ScheduleTransport { throw IOException("Controlled offline") })
            assertEquals(neighborEvents, requireNotNull(offline.cached(parish).publication).events)
            val failed = offline.refresh(parish, now.plusSeconds(120), force = true)
            assertEquals(neighborEvents, requireNotNull(failed.publication).events)
            assertTrue(failed.sourceHasWithdrawals)
        }
    }

    @Test fun foreignRowsNeverWithdrawSelectedParishStartsAndUnknownCalendarWordsRemainUnknown() {
        for (foreign in listOf(row(service = "Литургия в Сергиевском храме"), row(feast = "В Сергиевском храме"))) {
            val result = inspect(calendar(foreign, caption = "<caption>Все богослужения отменены</caption>") + neighbors)
            assertEquals(neighborEvents, result.events)
            assertTrue(result.withdrawnStarts.isEmpty())
            assertTrue(result.withdrawnDates.isEmpty())
            assertFalse(result.unresolvedWithdrawal || result.withdrawsAll || result.safetyConflict)
        }
        val mixed = inspect(calendar(row() + row(26, "Чт.", service = "Литургия в Сергиевском храме"),
            caption = "<caption>Все богослужения отменены</caption>") + neighbors)
        assertEquals(neighborEvents, mixed.events)
        assertEquals(setOf("2026-11-21T09:00"), mixed.withdrawnStarts)
        val canonical = "Отцов Поместного Собора Церкви Русской 1917–1918 годов"
        val known = inspect(calendar(row(feast = canonical), caption = "<caption>Все богослужения отменены</caption>") + neighbors)
        assertEquals(neighborEvents, known.events)
        assertEquals(setOf("2026-11-21T09:00"), known.withdrawnStarts)
        assertTrue(known.withdrawnDates.isEmpty())
        assertFalse(known.withdrawsAll || known.unresolvedWithdrawal || known.safetyConflict)
        // A genuinely incomplete name keeps the previous unresolved raw-venue safeguard.
        val unknown = inspect(calendar(row(feast = canonical.removeSuffix(" годов")),
            caption = "<caption>Все богослужения отменены</caption>"))
        assertTrue(unknown.unresolvedWithdrawal && unknown.safetyConflict)
        assertTrue(unknown.events.isEmpty())
        assertTrue(unknown.withdrawnStarts.isEmpty() && unknown.withdrawnDates.isEmpty())
        assertFalse(unknown.withdrawsAll)
    }

    @Test fun foreignCancellationCannotErasePreviouslyAcceptedSelectedServiceAtTheSameTime() = runBlocking {
        val cache = Cache()
        var html = calendar()
        val repo = repository(cache, ScheduleTransport { response(it, html) })
        val accepted = requireNotNull(repo.refresh(parish, now, sourceOverride = site).publication)
        html = calendar(row(service = "Литургия в Сергиевском храме"), caption = "<caption>Все богослужения отменены</caption>")
        val after = repo.refresh(parish, now.plusSeconds(60), force = true)
        assertEquals(accepted, after.publication)
        assertFalse(after.sourceHasWithdrawals || after.publicationRejected)
        assertEquals(accepted, repository(cache, ScheduleTransport { error("No HTTP on cache read") }).cached(parish).publication)
    }

    @Test fun malformedGridNoticeCannotDisappearBehindTheLegacyHtmlFastPath() {
        val malformed = "<article><h2>Расписание богослужений в ноябре 2026 года</h2>" +
            "<p>Все богослужения не<br>состоятся</p><table><tr><td>Неполная таблица</td></tr></table></article>"
        val legacyPositive = "<article><h1 class=entry-title>Ноябрь 2026</h1><div class=entry-content><p>21 суббота 10:00 Литургия</p></div></article>"
        assertEquals(1, inspect(legacyPositive).events.size)
        val result = inspect(malformed + legacyPositive)
        assertTrue(result.events.isEmpty())
        assertTrue(result.safetyConflict && result.unresolvedWithdrawal)
        assertTrue(result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty())
        assertFalse(result.withdrawsAll)
    }

    @Test fun canonicalNoticeKeepsOriginalWordsAndForeignVenueForLaterOrdinaryRows() {
        for (notice in listOf("<p>В Сергиевском храме все богослужения отменены</p>",
            "<h3>В Сергиевском храме все богослужения отменены</h3>",
            "<div>В Сергиевском <p>храме все богослужения отменены</p></div>")) {
            val html = calendar(before = notice).replace("</article>",
                "<p>26 ноября 2026 четверг 10:00 Литургия</p></article>")
            val document = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, site))
            assertTrue(document.pages.flatMap { it.lines }.any {
                it.htmlCalendarNotice == null && it.text.contains("богослужения отменены")
            })
            val result = inspect(html)
            assertTrue(notice, result.events.isEmpty())
            assertTrue(notice, result.withdrawnStarts.isEmpty() && result.withdrawnDates.isEmpty())
            assertFalse(notice, result.withdrawsAll)
        }
    }

    @Test fun negativeAlternativeAndExceptionLocationsCannotProveAnotherBuilding() {
        for (location in listOf("не в Сергиевском храме", "кроме служб в Сергиевском храме",
            "вместо службы в Сергиевском храме", "в Сергиевском храме или в другом месте",
            "либо в Сергиевском храме", "только в Сергиевском храме")) {
            val raw = "Литургия $location"
            assertFalse(location, ScheduleVenue.provesDifferentLocatedBuilding(raw, parish))
            val result = inspect(calendar(row(service = raw), caption = "<caption>Все богослужения отменены</caption>"))
            assertTrue(location, result.unresolvedWithdrawal && result.safetyConflict)
            assertTrue(location, result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty())
            assertFalse(location, result.withdrawsAll)
        }
    }

    @Test fun ownerCancellationWithAdditionalTimedRowsCannotNarrowItselfToTheTable() {
        val html = calendar(before = "<p>Все богослужения отменены</p>").replace("</article>",
            "<p>26 ноября 2026 четверг 10:00 Литургия</p></article>")
        val result = inspect(html + neighbors)
        assertTrue(result.events.isEmpty())
        assertTrue(result.safetyConflict && result.unresolvedWithdrawal)
        assertTrue(result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty())
        assertFalse(result.withdrawsAll)
    }

    @Test fun anUnemittedOrMixedOriginNoticeCannotSilentlySuppressCancellation() {
        val hiddenByOrdinaryWalk = "<article><h2>Расписание богослужений в ноябре 2026 года</h2>" +
            "<ul><li>Все богослужения не состоятся<table>${row()}</table></li></ul></article>"
        val positive = "<article><h1 class=entry-title>Ноябрь 2026</h1><div class=entry-content>" +
            "<p>21 суббота 10:00 Литургия</p></div></article>"
        val document = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(hiddenByOrdinaryWalk, site))
        assertTrue(document.pages.flatMap { it.lines }.none { it.htmlCalendarNotice != null })
        val result = inspect(hiddenByOrdinaryWalk + positive)
        assertTrue(result.events.isEmpty())
        assertTrue(result.safetyConflict && result.unresolvedWithdrawal)
        assertTrue(result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty())
        assertFalse(result.withdrawsAll)
    }

    @Test fun matchingSelectedNameDoesNotEraseRawNegativeOrAlternativeCancellationScope() {
        for (scope in listOf("не в Никольском храме", "кроме служб в Никольском храме",
            "в Никольском храме или в другом месте")) {
            for (html in listOf(
                calendar(row(service = "Литургия $scope"), caption = "<caption>Все богослужения отменены</caption>"),
                calendar(before = "<h3>Богослужения $scope</h3>", caption = "<caption>Все богослужения отменены</caption>"),
                calendar(before = "<p>Богослужения $scope</p>", caption = "<caption>Все богослужения отменены</caption>"))) {
                val result = inspect(html)
                assertTrue(scope, result.safetyConflict && result.unresolvedWithdrawal)
                assertTrue(scope, result.events.isEmpty())
                assertTrue(scope, result.withdrawnDates.isEmpty() && result.withdrawnStarts.isEmpty())
                assertFalse(scope, result.withdrawsAll)
            }
        }
    }

    @Test fun explicitNavigationDoesNotBecomeTheVenueOfTheFollowingCalendar() {
        for (menu in listOf("<nav>", "<div role='navigation'>")) {
            val close = if (menu.startsWith("<nav")) "</nav>" else "</div>"
            val links = menu + "<ul><li><a href='/sacraments'>Таинства Церкви</a></li>" +
                "<li><a href='/anointing'>Таинство Соборования</a></li>" +
                "<li><a href='/feasts'>Церковные праздники</a></li></ul>" + close
            val result = inspect(links + calendar())
            assertEquals(listOf(InternetService("2026-11-21", "09:00", "Литургия", "liturgy")), result.events)
            val foreign = inspect(links + calendar(before = "<h3>В Сергиевском храме</h3>"))
            assertTrue(foreign.events.isEmpty())
        }
    }

    @Test fun inlineSentenceEdgesDoNotSplitWordsOrHideNegativeCancellation() {
        val html = calendar(row(feast = "<strong>Глас 1-й.</strong>Апостола Матфея", service = "Литур<strong>гия</strong>"))
        val doc = HtmlScheduleDocumentExtractor.fromHtml(Jsoup.parse(html, site))
        val cells = doc.pages.flatMap { it.lines }.mapNotNull { it.htmlCalendarRow }.single().cells
        assertEquals("Глас 1-й. Апостола Матфея", cells[2])
        assertEquals("Литургия", cells[4])
        assertEquals(1, inspect(html).events.size)
        val negative = inspect(calendar(row(service = "Литургия н<strong>е</strong> состоится")))
        assertTrue(negative.events.isEmpty())
        assertTrue(negative.withdrawnDates.isNotEmpty() || negative.withdrawnStarts.isNotEmpty() || negative.safetyConflict)
    }
}
