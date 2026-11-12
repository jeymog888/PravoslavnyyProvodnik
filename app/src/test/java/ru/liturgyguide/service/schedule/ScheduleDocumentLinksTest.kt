package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleDocumentLinksTest {
    @Test fun sretenskyBankingModalsDoNotBorrowTheScheduleHeadingForTheirDownloads() {
        val source = "https://monastery.ru/bogosluzhenie/raspisanie-bogosluzheniy/"
        // The two req-modal wrappers, attributes and PDF links are from the public page.
        // Other controls below check that visibility does not erase explicit schedule links.
        val page = Jsoup.parse("""
            <h1>Расписание богослужений</h1>
            <a href='/docs/current.pdf'>Скачать PDF</a><img src='/files/current.png'>
            <div class='modal fade req-modal' id='req-modal' tabindex='-1' role='dialog' aria-hidden='true'>
              <div class='modal-dialog' role='document'><div class='modal-content'><div class='modal-body'>
                <p><b>Реквизиты счета и адреса</b></p>
                <p><b><a href='/docs/reqs.pdf' download='download'>Скачать PDF</a></b></p>
              </div></div></div>
            </div>
            <div class='modal fade req-modal' id='req-modal2' tabindex='-1' role='dialog' aria-hidden='true'>
              <div class='modal-dialog' role='document'><div class='modal-content'><div class='modal-body'>
                <p><b>USD</b></p><a href='/docs/source.pdf' download='requisites'>Скачать PDF</a>
                <p><b>EUR</b></p><a href='/docs/source.pdf' download='requisites'>Скачать PDF</a>
              </div></div></div>
            </div>
            <div hidden><a href='/docs/hidden.pdf'>Скачать</a><img src='/files/hidden.png'></div>
            <div aria-hidden='true'><a href='/docs/aria.pdf'>Скачать</a><img src='/files/aria.png'></div>
            <div class='modal'><a href='/docs/modal.pdf'>Скачать</a><img src='/files/modal.png'></div>
            <div class='modal' aria-hidden='true'><a href='/docs/next.pdf'>Расписание следующей недели</a></div>
            <nav hidden><a href='/bogosluzhenie/raspisanie-bogosluzheniy/?day=16.11.2026'>Следующая неделя</a></nav>
        """.trimIndent(), source)
        assertEquals(listOf(
            ScheduleDocumentLink("https://monastery.ru/docs/current.pdf", source, true),
            ScheduleDocumentLink("https://monastery.ru/docs/next.pdf", source, true),
            ScheduleDocumentLink("https://monastery.ru/bogosluzhenie/raspisanie-bogosluzheniy/?day=16.11.2026", source, false),
            ScheduleDocumentLink("https://monastery.ru/files/current.png", source, true)
        ), ScheduleDocumentLinks.find(page))
    }

    @Test fun contactPageServicesSubheadingDoesNotPromotePrayerPicturesOrSocialIcons() {
        val page = Jsoup.parse("""
            <div id='header'><img src='/rutube.png'></div>
            <h1>Храм иконы Божией Матери Нечаянная Радость</h1>
            <div class='entry-content'><h2>Богослужения</h2>
              <a href='/tropar.png'><img src='/tropar.png' width='260' height='567'></a>
              <a href='http://parish.example/raspisanie/'>График служб</a></div>
            <div id='footer'><img src='/youtube1.png'></div>
        """.trimIndent(), "https://parish.example/o-xrame/")
        assertEquals(listOf(ScheduleDocumentLink("https://parish.example/raspisanie/",
            "https://parish.example/o-xrame/", false)), ScheduleDocumentLinks.find(page))
        val mixed = Jsoup.parse("""
            <h1>Новости прихода</h1>
            <article><h2>Расписание богослужений</h2><a href='/files/current.pdf'>Скачать</a></article>
            <article><h1>Контакты</h1><h2>Богослужения</h2>
              <a href='/tropar.png'><img src='/tropar.png'></a></article>
        """.trimIndent(), "https://parish.example/")
        assertEquals(listOf(ScheduleDocumentLink("https://parish.example/files/current.pdf",
            "https://parish.example/", true)), ScheduleDocumentLinks.find(mixed))
    }

    @Test fun oldHttpHtmlAnchorsOnHttpsUpgradeOnlyTheExactValidatedHost() {
        val source = "https://parish.example:443/contact/"
        val page = Jsoup.parse("""
            <h1>Контакты</h1>
            <a href='http://parish.example/raspisanie/?day=12.11.2026&amp;title=a%26b'>Расписание</a>
            <a href='https://parish.example/raspisanie/?day=12.11.2026&amp;title=a%26b'>Расписание</a>
            <a href='http://parish.example:80/schedule/'>Расписание</a>
            <a href='http://parish.example/schedule.pdf'>Расписание PDF</a>
            <a href='http://other.example/schedule/'>Расписание</a>
            <a href='http://www.parish.example/schedule/'>Расписание</a>
            <a href='http://parish.example.other.org/schedule/'>Расписание</a>
            <a href='http://parish.example:8080/schedule/'>Расписание</a>
            <a href='http://parish.example:443/schedule/'>Расписание</a>
            <a href='https://parish.example:8443/schedule/'>Расписание</a>
            <a href='http://user@parish.example/schedule/'>Расписание</a>
            <a href='http://127.0.0.1/schedule/'>Расписание</a>
            <a href='javascript:alert(1)'>Расписание</a>
            <a href='data:text/html,schedule'>Расписание</a>
            <a href='file:///schedule/'>Расписание</a>
        """.trimIndent(), source)
        assertEquals(listOf(
            ScheduleDocumentLink("https://parish.example/raspisanie/?day=12.11.2026&title=a%26b", source, false),
            ScheduleDocumentLink("https://parish.example/schedule/", source, false),
            ScheduleDocumentLink("http://parish.example/schedule.pdf", source, true)
        ), ScheduleDocumentLinks.find(page))
        // Discovery must not relax the independent network redirect policy.
        assertTrue(!ScheduleUrl.permittedRedirect(source, "http://parish.example/schedule/"))
        val boundary = "http://parish.example/".padEnd(2_048, 'a')
        assertTrue(ScheduleDocumentLinks.find(Jsoup.parse("<a href='$boundary'>Расписание</a>", source)).isEmpty())
    }

    @Test fun realWordPressSharingButtonsDoNotConsumeSchedulePageBudget() {
        val source = "https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/"
        val html = requireNotNull(javaClass.getResource("/schedule/weekly/novosibirsk.html")).readText()
        val page = Jsoup.parse(html, source)
        assertEquals(4, page.select("a[data-shared]").size)
        assertTrue(ScheduleDocumentLinks.find(page).none { "share=" in it.url })
    }

    @Test fun sharingActionExclusionPreservesCalendarPaginationAndPublicShareTokenLinks() {
        val page = Jsoup.parse("""
            <h1>Расписание</h1>
            <a class='share-twitter' href='/schedule/?share=twitter'>Twitter</a>
            <a data-shared='sharing-vk-42' href='/schedule/?%73hare=vk'>ВКонтакте</a>
            <a class='share-icon' href='/schedule/?date=2026-11-12'>Расписание на дату</a>
            <a href='/schedule/?page=2'>Следующая страница</a>
            <a data-shared='public-document' href='/schedule.pdf?share_token=abc'>Расписание PDF</a>
        """.trimIndent(), "https://parish.example/")
        assertEquals(listOf("https://parish.example/schedule/?date=2026-11-12",
            "https://parish.example/schedule/?page=2", "https://parish.example/schedule.pdf?share_token=abc"),
            ScheduleDocumentLinks.find(page).map { it.url })
    }

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
