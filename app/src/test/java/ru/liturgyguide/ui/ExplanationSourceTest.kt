package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test

class ExplanationSourceTest {
    private val known = "https://ikoni.cerkov.ru/posledovanie-bozhestvennoj-liturgii/kratkij-ustav-sxema-liturgii-svyatitelya-ioanna-zlatousta/"

    @Test fun `known publication has verified metadata without inheriting the old composite label`() {
        val source = requireNotNull(explanationSource(known, "Другой выбранный храм"))
        assertEquals("Краткий устав-схема Литургии святителя Иоанна Златоуста", source.title)
        assertEquals("Сайт храма Пророка Илии", source.siteLabel)
        assertEquals(known, source.url)
    }

    @Test fun `another page on the same site never inherits this publication attribution`() {
        listOf("https://ikoni.cerkov.ru/another-page/", "$known?view=other").forEach { url ->
            val source = requireNotNull(explanationSource(url, "  Другая публикация  "))
            assertEquals("Другая публикация", source.title)
            assertEquals("Сайт: ikoni.cerkov.ru", source.siteLabel)
            assertEquals(url, source.url)
        }
    }

    @Test fun `blank title and malformed address have honest nonthrowing fallbacks`() {
        listOf(null, "", " \n ").forEach { title ->
            val source = requireNotNull(explanationSource("https://example.org/article", title))
            assertEquals("Публикация", source.title)
            assertEquals("Сайт: example.org", source.siteLabel)
        }
        val malformed = requireNotNull(explanationSource("not a valid URI [", "  Название  "))
        assertEquals("Название", malformed.title)
        assertNull(malformed.siteLabel)
        assertEquals("not a valid URI [", malformed.url)
    }

    @Test fun `missing address never creates a disclosure action`() {
        listOf(null, "", " \t\n ").forEach { url ->
            assertNull(explanationSource(url, "Даже если название известно"))
        }
    }
}
