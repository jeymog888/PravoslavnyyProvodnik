package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDocumentLinksTest {
    @Test fun scheduleImagesInALaterArticleAreFoundWithoutBorrowingNewsImages() {
        val page = Jsoup.parse("""
            <article><h2>Новости прихода</h2><img data-src='/photos/procession.jpg'></article>
            <article><h2>Расписание богослужений</h2><img data-original='/files/current.jpg'></article>
            <footer><img src='/photos/contacts.jpg'></footer>
        """.trimIndent(), "https://parish.example/")
        assertEquals(listOf("https://parish.example/files/current.jpg"), ScheduleDocumentLinks.find(page).map { it.url })
    }

    @Test fun liveTildaScheduleFindsAllThreeOriginalsWithoutItsEmptyPreviewsOrBranding() {
        val source = "https://xn----7sbbgncsdakjgxi3cgfp8d.xn--p1ai/schedule"
        val html = requireNotNull(javaClass.getResource("/schedule/elisavetinsky-opalikha-tilda.html")).readText()
        val links = ScheduleDocumentLinks.find(Jsoup.parse(html, source))
        val documents = links.filter { it.document }
        assertEquals(listOf(
            "https://static.tildacdn.com/tila3733-6433-4166-b433-333039663365/__1.jpg",
            "https://static.tildacdn.com/tila6563-3962-4730-b334-656133333966/__2.jpg",
            "https://static.tildacdn.com/tila3563-3130-4934-a236-316330623066/_.jpg"
        ), documents.map { it.url })
        assertTrue(documents.all { it.linkedFrom == source })
    }

    @Test fun titleMetadataOrVisibleBuilderHeadingCanIdentifyAnOtherwiseUnlabelledSchedule() {
        for (heading in listOf("<title>Расписание богослужений</title>",
            "<meta property='og:title' content='Расписание богослужений'>",
            "<div class='t-title'>Расписание богослужений</div>",
            "<div role='heading' aria-level='1'>Расписание богослужений</div>")) {
            val page = Jsoup.parse("$heading<img data-original='/files/current.jpg'>", "https://parish.example/")
            assertEquals(heading, listOf("https://parish.example/files/current.jpg"),
                ScheduleDocumentLinks.find(page).map { it.url })
        }
    }

    @Test fun zoomImageWinsOverPreviewAndInvalidLazyAttributesFallBackToThePublishedOriginal() {
        val page = Jsoup.parse("""
            <h1>Расписание</h1>
            <img src='/preview.jpg' data-src='/medium.jpg' data-original='/full.jpg' data-img-zoom-url='/zoom.jpg'>
            <img src='/preview2.jpg' data-original='/full2.jpg' data-img-zoom-url='javascript:alert(1)'>
            <img src='/current.jpg' data-original='data:image/png;base64,AAAA'>
            <img src='/preview3.jpg' data-original='http://127.0.0.1/private.jpg'>
            <img src='https://cdn.example.org/id/-/empty/blank.jpg'>
        """.trimIndent(), "https://parish.example/")
        assertEquals(listOf("https://parish.example/zoom.jpg", "https://parish.example/full2.jpg",
            "https://parish.example/current.jpg", "https://parish.example/preview3.jpg"),
            ScheduleDocumentLinks.find(page).map { it.url })
    }

    @Test fun schedulePageMetadataDoesNotPromoteLogosMenusFooterPicturesOrExternalHtml() {
        val page = Jsoup.parse("""
            <title>Расписание богослужений</title>
            <div id='t-header'><img data-original='/masthead.jpg'></div>
            <div role='navigation'><img data-original='/menu.jpg'></div>
            <div id='t-footer'><img data-original='/footer.jpg'></div>
            <footer><img data-original='/footer2.jpg'></footer>
            <aside><img data-original='/sidebar.jpg'></aside>
            <img src='/logo.png'><img class='brand-logo' src='/brand.jpg'>
            <img alt='Логотип храма' data-original='/mark.jpg'>
            <img src='/small.jpg' width='80'><img src='/decoration.jpg' role='presentation'>
            <img src='/hidden.jpg' aria-hidden='true'>
            <a href='https://other.example.org/schedule/'>Расписание</a>
            <img data-original='https://cdn.example.org/current.jpg'>
        """.trimIndent(), "https://parish.example/")
        assertEquals(listOf("https://cdn.example.org/current.jpg"), ScheduleDocumentLinks.find(page).map { it.url })
    }

    @Test fun newsHeadingsDoNotInheritGenericScheduleMetadataOrNavigationLabels() {
        for (heading in listOf("<h1>Новости</h1>", "<div class='t-title'>Новости</div>")) {
            val page = Jsoup.parse("<title>Храм - расписание богослужений</title>$heading" +
                "<nav><a href='/schedule/'>Расписание</a></nav><img data-original='/news.jpg'>", "https://parish.example/")
            assertEquals(listOf(ScheduleDocumentLink("https://parish.example/schedule/", "https://parish.example/", false)),
                ScheduleDocumentLinks.find(page))
        }
        val page = Jsoup.parse("<nav><a href='/schedule/'>Расписание</a></nav><img data-original='/news.jpg'>", "https://parish.example/")
        assertTrue(ScheduleDocumentLinks.find(page).none { it.document })
    }

    @Test fun aScheduleInTheSecondArticleKeepsItsUnnamedPdf() {
        val page = Jsoup.parse("""
            <article><h2>Новости</h2><p>Жизнь прихода</p></article>
            <article><h2>Расписание</h2><a href='/files/current.pdf'>Скачать</a></article>
            <footer><a href='/policy.pdf'>Политика конфиденциальности</a></footer>
        """.trimIndent(), "https://parish.example/")
        assertEquals(listOf("https://parish.example/files/current.pdf"), ScheduleDocumentLinks.find(page).map { it.url })
    }

    @Test fun aScheduleHeadingDoesNotPromoteFooterPrivacyDocumentsOrSidebarPictures() {
        val page = Jsoup.parse("""
            <nav><a href='/raspisanie/'>Расписание</a><a href='/terms.pdf'>Условия</a></nav>
            <main><h1>Расписание богослужений</h1><article>
              <a href='/files/november.pdf'>Скачать</a><img src='/files/november.png'>
            </article></main>
            <aside><a href='/registration.pdf'>Анкета</a><img src='/logo.png'></aside>
            <footer><a href='/www/politika_konfidencialnosti.pdf'>Политика конфиденциальности</a>
              <a href='/files/services.pdf'>Расписание в PDF</a></footer>
        """.trimIndent(), "https://parish.example/")
        assertEquals(listOf("https://parish.example/raspisanie/", "https://parish.example/files/november.pdf",
            "https://parish.example/files/services.pdf", "https://parish.example/files/november.png"),
            ScheduleDocumentLinks.find(page).map { it.url })
    }

    @Test fun unnamedArticleAttachmentsStillWorkWithoutAMainElementButNeedAScheduleHeading() {
        fun page(heading: String) = Jsoup.parse("""
            <h1>$heading</h1><div class='entry-content'><a href='/files/current.pdf'>Открыть PDF</a></div>
            <footer><a href='/policy.pdf'>Политика конфиденциальности</a></footer>
        """.trimIndent(), "https://parish.example/")
        assertEquals(listOf("https://parish.example/files/current.pdf"),
            ScheduleDocumentLinks.find(page("Расписание")).map { it.url })
        assertEquals(emptyList<ScheduleDocumentLink>(), ScheduleDocumentLinks.find(page("Новости")))
    }
}
