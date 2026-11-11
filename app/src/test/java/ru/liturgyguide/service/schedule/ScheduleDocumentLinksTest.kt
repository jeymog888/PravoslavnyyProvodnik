package ru.liturgyguide.service.schedule

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleDocumentLinksTest {
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
