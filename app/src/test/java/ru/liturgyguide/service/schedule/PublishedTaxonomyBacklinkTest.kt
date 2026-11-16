package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class PublishedTaxonomyBacklinkTest {
    private val base = "https://parish.example/schedule/november/"
    private val category = "https://parish.example/category/raspisanie/"
    private val parish = Parish("osm:way:367166563", "Церковь Николая Чудотворца", "Церковь Николая Чудотворца",
        "", "", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "")
    private val event = InternetService("2026-11-21", "09:00", "Литургия", "liturgy")
    private val parsed = ScheduleParseResult(ScheduleParseDisposition.PARSED, listOf(event))
    private fun metadata(label: String = "Расписание богослужений", url: String = category) =
        "<div class=byline><a rel='category tag' href='$url'>$label</a></div>"
    private fun page(html: String, outside: String = "") = Jsoup.parse("$outside<article><h2>Расписание богослужений в ноябре 2026 года</h2>$html</article>", base)
    private fun filtered(html: String, result: ScheduleParseResult = parsed, outside: String = ""): List<ScheduleDocumentLink> {
        val document = page(html, outside)
        return ScheduleDocumentLinks.fromPublishedPage(document, result, ScheduleDocumentLinks.find(document), result.events)
    }
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/schedule/weekday-calendar-case099/$name")).use { it.readBytes() }

    @Test fun aPureTaxonomyBacklinkAndItsNeutralNavigationDuplicateDoNotLeaveTheDatedPublication() {
        assertTrue(filtered(metadata()).isEmpty())
        assertTrue(filtered(metadata(), outside = "<nav><a href='$category'>Расписание</a></nav>").isEmpty())
        assertTrue(filtered(metadata(), outside = "<div role=navigation><a href='$category'>Расписание служб</a></div>").isEmpty())
        assertTrue(filtered("", outside = "<nav><a href='$category'>Расписание</a></nav>").isEmpty())
    }

    @Test fun discoveryAndAnyUncertainOrWithdrawnResultRetainAllOldLinks() {
        for (result in listOf(parsed.copy(disposition = ScheduleParseDisposition.UNSUPPORTED), parsed.copy(events = emptyList()),
            parsed.copy(yearInferred = true), parsed.copy(safetyConflict = true), parsed.copy(unresolvedWithdrawal = true),
            parsed.copy(withdrawsAll = true), parsed.copy(withdrawnDates = setOf("2026-11-21")),
            parsed.copy(withdrawnStarts = setOf("2026-11-21T09:00")))) assertEquals(category, filtered(metadata(), result).single().url)
    }

    @Test fun contentUpdatesCancellationsAndUnknownOccurrencesKeepTheActualGet() {
        for (extra in listOf("<p><a href='$category'>Расписание</a></p>",
            "<nav><a href='$category'>Обновленное расписание</a></nav>",
            "<span hidden><a href='$category'>Отмена литургии - расписание</a></span>",
            "<div class=byline>17 ноября отмена литургии! <a rel=category href='$category'>Расписание</a></div>",
            "<div><a rel=category href='$category'>Расписание</a></div>"))
            assertEquals(extra, category, filtered(metadata() + extra).single().url)
    }

    @Test fun binaryAndAnotherParentsLinksAreNeverReclassified() {
        val page = page(metadata())
        val binary = ScheduleDocumentLink("https://parish.example/raspisanie.pdf", base, true)
        val anotherParent = ScheduleDocumentLink(category, "https://parish.example/other/", false)
        assertEquals(listOf(binary, anotherParent), ScheduleDocumentLinks.fromPublishedPage(page, parsed, listOf(binary, anotherParent), parsed.events))
        assertEquals(category, filtered(metadata("Расписание на 17 ноября")).single().url)
    }

    @Test fun exactRecordedArticleKeepsHistoricalFivePlusProvenMolebenWithoutTaxonomyRevocation() = runBlocking {
        val articleBytes = bytes("source.html")
        val archiveBytes = bytes("category.html")
        assertEquals("ef767c606c0e3da559ba99db8fef10fca56d88b98c36d5813b41244b8b1722be", ScheduleSourceCandidate.sha(archiveBytes))
        val articleUrl = "https://hram-strelkovo.ru/" + "%d1%80%d0%b0%d1%81%d0%bf%d0%b8%d1%81%d0%b0%d0%bd%d0%b8%d0%b5-%d0%b1%d0%be%d0%b3%d0%be%d1%81%d0%bb%d1%83%d0%b6%d0%b5%d0%bd%d0%b8%d0%b9-%d0%b2-%d0%bd%d0%be%d1%8f%d0%b1%d1%80%d0%b5-2026-%d0%b3%d0%be.html"
        val archiveUrl = "https://hram-strelkovo.ru/category/raspisanie-bogosluzhenij"
        val backgroundUrl = "https://hram-strelkovo.ru/bogosluzheniya.html"
        val background = bytes("worship-background.html")
        assertEquals("e9dfb0380883b218f96a4088347c9577c66b902825bf23b5d8a0a7f485eab849", ScheduleSourceCandidate.sha(background))
        val today = LocalDate.of(2026, 11, 15)
        val archive = ScheduleHtmlParser.inspect(Jsoup.parse(archiveBytes.toString(Charsets.UTF_8), archiveUrl), today, parish)
        assertTrue("Historical archive really triggers the original unresolved safety", archive.safetyConflict && archive.unresolvedWithdrawal)
        val calls = mutableListOf<String>()
        val values = mutableMapOf<String, String>()
        val cache = object : ScheduleCacheStore {
            override fun read(parishId: String) = values[parishId]
            override fun write(parishId: String, value: String) { values[parishId] = value }
        }
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when (url) {
                articleUrl -> ScheduleResponse(url, articleBytes, "text/html; charset=UTF-8")
                archiveUrl -> ScheduleResponse(url, archiveBytes, "text/html; charset=UTF-8")
                backgroundUrl -> ScheduleResponse(url, background, "text/html; charset=UTF-8")
                else -> throw IOException("Unrecorded request")
            }
        }, cache, siteResolver = ScheduleSiteResolver { null })
        val state = repository.refresh(parish, Instant.parse("2026-11-15T16:35:59Z"), sourceOverride = articleUrl)
        assertEquals(InternetScheduleStatus.READY, state.status)
        val publication = requireNotNull(state.publication)
        val historicalFive = listOf(14,15,21,26,29).map { InternetService("2026-11-$it", "09:00", "Литургия", "liturgy") }
        val expectedEvents = (historicalFive + InternetService("2026-11-18", "09:00", "Молебен", "service_block"))
            .sortedBy { "${it.date}T${it.time}" }
        val derived = JsonParser.parseString(bytes("EXPECTED-099-CALENDAR-MEMORY-V2.json").toString(Charsets.UTF_8)).asJsonObject
            .getAsJsonArray("expectedEvents").map { it.asJsonObject.let { e ->
                InternetService(e["date"].asString, e["time"].asString, e["title"].asString, e["kind"].asString)
            } }
        assertEquals(expectedEvents, derived)
        assertEquals(expectedEvents, publication.events)
        assertTrue(publication.knownPartial)
        assertEquals(articleUrl, publication.sourceUrl)
        assertFalse(state.publicationRejected || state.sourceHasWithdrawals)
        assertEquals(listOf(articleUrl), calls)
        assertFalse("Unrelated neutral site navigation is not a publication attachment", backgroundUrl in calls)
        assertFalse("Only the pure metadata archive edge is pruned", archiveUrl in calls)
        val offline = InternetScheduleRepository(ScheduleTransport { error("Offline cache read must not request HTTP") }, cache,
            siteResolver = ScheduleSiteResolver { null })
        assertEquals(state, offline.cached(parish))
        assertEquals(expectedEvents, requireNotNull(offline.cached(parish).publication).events)
    }

    @Test fun taxonomyNeedsOnePublicationOwnerOutsideContentAndBothRelationTokens() {
        for (html in listOf(metadata().replace("category tag", "tag"), metadata().replace("category tag", "category"),
            "<div class=entry-content><p>Изменения расписания опубликованы здесь</p>" + metadata() + "</div>",
            "<p>Изменения расписания опубликованы здесь</p>" + metadata(),
            metadata() + "<article><h2>Другой раздел</h2></article>",
            metadata() + "<p>Уточ\u2060нение расписания опубликовано здесь</p>",
            metadata() + "<p>Пере\u200bнос литургии</p>"))
            assertEquals(html, category, filtered(html).single().url)
    }

    @Test fun alreadyWithdrawnOrBindingFilteredEventsCannotHideTheDiscoveryEdge() {
        val document = page(metadata())
        val original = ScheduleDocumentLinks.find(document)
        assertEquals(original, ScheduleDocumentLinks.fromPublishedPage(document, parsed, original, emptyList()))
        assertEquals(original, ScheduleDocumentLinks.fromPublishedPage(document, parsed, original,
            listOf(event.copy(date = "2026-11-22"))))
    }

    @Test fun neutralNavigationCannotHideContentDatedTargetsOrAnyUpdateContext() {
        val nav = "<nav><a href='$category'>Расписание</a></nav>"
        assertTrue(filtered("", outside = nav).isEmpty())
        assertEquals(category, filtered(nav).single().url)
        assertEquals(category, filtered("<div class=entry-content>$nav</div>").single().url)
        assertEquals(category, filtered("", outside = "<p>Изменения расписания опубликованы здесь</p>$nav").single().url)
        assertEquals(category, filtered("", outside = "<p>New schedule is published here</p>$nav").single().url)
        assertEquals(category, filtered("", outside = "<p>Уточ\u2060нение расписания</p>$nav").single().url)
        val dated = category + "?date=2026-11-21"
        assertEquals(dated, filtered("", outside = nav.replace(category, dated)).single().url)
        val month = category + "noyabr/"
        assertEquals(month, filtered("", outside = nav.replace(category, month)).single().url)
        assertEquals(category, filtered("<p><a href='$category'>Расписание</a></p>", outside = nav).single().url)
        assertEquals(category, filtered("", result = parsed.copy(events = emptyList()), outside = nav).single().url)
    }
}
