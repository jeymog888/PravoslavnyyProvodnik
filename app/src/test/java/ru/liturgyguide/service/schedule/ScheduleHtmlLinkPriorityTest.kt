package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleHtmlLinkPriorityTest {
    private val source = "https://parish.example/"
    private fun links(html: String) = ScheduleDocumentLinks.find(Jsoup.parse(html, source))
    private fun urls(html: String) = links(html).map { it.url }

    @Test fun visibleServiceScheduleWidgetPrecedesMenuIndexesAndCulturalProgramme() {
        val html = """
            <nav><a href='/schedule/culture'>Расписания центра</a></nav>
            <ul class='menu'><li><a href='/schedule/services-index'>Расписания служб</a></li></ul>
            <aside class='sidebar'><div class='menu-schedules'>
              <a href='/parish-week'><span>Расписание</span> <b>служб</b></a>
              <a href='/schedule/activities'>Расписание центра</a>
            </div></aside>
        """.trimIndent()
        assertEquals(listOf(source + "parish-week", source + "schedule/culture",
            source + "schedule/services-index", source + "schedule/activities"), urls(html))
    }

    @Test fun duplicateUsesFinalNormalizedUrlAndStableFirstAdmissionOrder() {
        val html = """
            <nav><a href='http://parish.example/schedule/first'>Расписание</a></nav>
            <article><a href='/schedule/second'>Расписание богослужений</a>
              <a href='/schedule/first'>Расписание служб</a></article>
            <a href='/schedule/fallback'>Расписание центра</a>
        """.trimIndent()
        assertEquals(listOf(source + "schedule/first", source + "schedule/second",
            source + "schedule/fallback"), urls(html))
        assertTrue(links(html).all { it.linkedFrom == source && !it.document })
    }

    @Test fun hiddenDescendantsAndHiddenOccurrencesCannotPromoteButRemainEligible() {
        for (attribute in listOf("hidden", "aria-hidden='true'", "class='modal'")) {
            val html = """
                <a href='/schedule/fallback'>Расписание</a>
                <a href='/schedule/news'>Новости<span $attribute>Расписание служб</span></a>
                <div $attribute><a href='/schedule/hidden'>Расписание богослужений</a></div>
                <article><a href='/week'>Расписание богослужений</a></article>
            """.trimIndent()
            assertEquals(attribute, listOf(source + "week", source + "schedule/fallback",
                source + "schedule/news", source + "schedule/hidden"), urls(html))
        }
    }

    @Test fun discussionCannotAdmitOrUpgradeButSimilarlyNamedContentCan() {
        val html = """
            <a href='/schedule/fallback'>Расписание</a>
            <a href='/schedule/shared'>Расписание</a>
            <div id='comments'><a href='/schedule/shared'>Расписание служб</a>
              <a href='/comments-only'>Расписание богослужений</a></div>
            <!-- <a href='/comment-node'>Расписание служб</a> -->
            <div class='comments-schedule' id='schedule-comments-note'>
              <a href='/real-week'>Расписание богослужений</a></div>
        """.trimIndent()
        assertEquals(listOf(source + "real-week", source + "schedule/fallback",
            source + "schedule/shared"), urls(html))
    }

    @Test fun cancellationDuplicatePinsItsOldSlotIndependentlyOfHiddenNavigationRank() {
        val html = """
            <a href='/schedule/fallback'>Расписание</a>
            <a href='/schedule/change'>Расписание</a>
            <article><a href='/new-week'>Расписание служб</a></article>
            <nav hidden><a href='/schedule/change'>Расписание богослужений отменено</a></nav>
        """.trimIndent()
        assertEquals(listOf(source + "new-week", source + "schedule/change",
            source + "schedule/fallback"), urls(html))
    }

    @Test fun cancellationInDecodedHrefPinsEvenWithoutAVisibleCancellationLabel() {
        val path = "/schedule/%D0%BE%D1%82%D0%BC%D0%B5%D0%BD%D0%B0"
        val html = """
            <a href='/schedule/fallback'>Расписание</a>
            <nav><a href='$path'>Расписание</a></nav>
            <a href='/new-week'>Расписание служб</a>
        """.trimIndent()
        assertEquals(listOf(source + "new-week", source.trimEnd('/') + path,
            source + "schedule/fallback"), urls(html))
    }

    @Test fun binaryLinksKeepTheirExactLocalPositionsAndParents() {
        val html = """
            <h1>Расписание</h1>
            <a href='/schedule/a'>Расписание</a>
            <a href='https://cdn.example.org/current.pdf'>Расписание PDF</a>
            <a href='/schedule/b'>Расписание центра</a>
            <a href='/files/part-one.jpg'>Расписание JPG</a>
            <a href='/week'>Расписание богослужений</a>
            <div class='modal' aria-hidden='true'><a href='/files/next.pdf'>Расписание PDF</a></div>
            <img src='/preview.png' data-original='/files/part-two.jpg' width='800' height='1000'>
        """.trimIndent()
        val found = links(html)
        assertEquals(listOf(source + "week", "https://cdn.example.org/current.pdf", source + "schedule/a",
            source + "files/part-one.jpg", source + "schedule/b", source + "files/next.pdf",
            source + "files/part-two.jpg"), found.map { it.url })
        assertEquals(listOf(1, 3, 5, 6), found.indices.filter { found[it].document })
        assertTrue(found.all { it.linkedFrom == source })
    }

    @Test fun pagersRemainFallbackWhileDateAndPageIdQueriesCanPromote() {
        val pagers = listOf(
            "<a href='/schedule/pager?page=2'>Расписание служб</a>",
            "<a href='/schedule/pager?%70aged=2'>Расписание служб</a>",
            "<a rel='alternate NEXT' href='/schedule/pager'>Расписание служб</a>",
            "<a rel='prev' href='/schedule/pager'>Расписание служб</a>",
            "<div class='pagination'><a href='/schedule/pager'>Расписание служб</a></div>",
            "<a href='/schedule/pager'>Расписание служб: страница 2</a>"
        )
        for (pager in pagers) for (query in listOf("date=2026-11-14", "day=14.11.2026",
            "current_date=2026-11", "page_id=2")) {
            val found = urls("<a href='/schedule/fallback'>Расписание</a>$pager" +
                "<a href='/week?$query'>Расписание богослужений</a>")
            assertEquals(source + "week?$query", found[0])
            assertEquals(source + "schedule/fallback", found[1])
            assertTrue(found[2].startsWith(source + "schedule/pager"))
        }
    }

    @Test fun genericNewsAndUrlOnlyLinksKeepTheirOldOrder() {
        val html = """
            <title>Расписание богослужений</title><h1>Расписание служб</h1>
            <a href='/schedule/culture'>Расписание центра</a>
            <nav><a href='/service-index'>Расписание богослужений</a></nav>
            <article><a href='/schedule/news'>Богослужение совершено</a>
              <a href='/raspisanie-bogosluzheniy'>Подробнее</a></article>
        """.trimIndent()
        assertEquals(listOf(source + "schedule/culture", source + "service-index",
            source + "schedule/news", source + "raspisanie-bogosluzheniy"), urls(html))
    }

    @Test fun takeTwelvePrecedesRankingButLaterDuplicateCanUpgradeAnAdmittedUrl() {
        val firstTwelve = (1..12).joinToString("") { "<a href='/schedule/$it'>Расписание</a>" }
        val html = firstTwelve + "<a href='/outside-cap'>Расписание служб</a>" +
            "<a href='/schedule/12'>Расписание богослужений</a>"
        val found = urls(html)
        assertEquals(listOf(source + "schedule/12") + (1..11).map { source + "schedule/$it" }, found)
        assertEquals(12, found.size)
        assertFalse(source + "outside-cap" in found)
    }

    @Test fun fiveHundredCountsNonDiscussionAnchorsBeforeAdmission() {
        val comments = "<div id='comments'>" + (1..20).joinToString("") {
            "<a href='/comments/$it'>Расписание служб</a>"
        } + "</div>"
        val nonCandidates = (1..499).joinToString("") { "<a href='/ordinary/$it'>Новости</a>" }
        val html = comments + "<a href='/schedule/fallback'>Расписание</a>" + nonCandidates +
            "<a href='/outside-scan'>Расписание служб</a>"
        assertEquals(listOf(source + "schedule/fallback"), urls(html))
    }

    @Test fun oneHtmlSlotDoesNotChangeOrderedImageIndex() {
        val html = """
            <h1>Расписание богослужений</h1>
            <a href='/'>Расписание служб</a>
            <img src='/files/first.jpg' width='800' height='1000'>
            <img src='/files/second.jpg' width='800' height='1000'>
        """.trimIndent()
        assertEquals(listOf(ScheduleDocumentLink(source, source, false),
            ScheduleDocumentLink(source + "files/first.jpg", source, true),
            ScheduleDocumentLink(source + "files/second.jpg", source, true)), links(html))
    }
}
