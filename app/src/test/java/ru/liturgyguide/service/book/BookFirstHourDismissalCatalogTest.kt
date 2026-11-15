package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.junit.Assert.*
import org.junit.Test

class BookFirstHourDismissalCatalogTest {
    private fun resource(name: String) = requireNotNull(javaClass.classLoader
        ?.getResource("book/first-hour-dismissal/$name")).readText()

    @Test fun previous193LiteralEntriesKeepTheirCompleteTextOrderRevisionAndOriginalSource() {
        val raw = resource("historical193.json")
        assertEquals("38a5ecb2af96c0b3a9c055227d0492ef691b986c02a9db190495ac5ab00ebadb", BookText.sha256(raw))
        val historical = JsonParser.parseString(raw).asJsonObject["entries"].asJsonArray.map {
            Gson().fromJson(it, FeoriaTranslationCatalog.Entry::class.java)
        }
        assertEquals(193, historical.size)
        assertEquals(historical, FeoriaTranslationCatalog.entries.take(193))
        assertEquals(9, FeoriaTranslationCatalog.REVISION)
        assertEquals(195, FeoriaTranslationCatalog.entries.size)
        assertEquals(195, FeoriaTranslationCatalog.entries.map { it.id }.distinct().size)
        assertEquals(195, FeoriaTranslationCatalog.entries.map { BookText.normalized(it.original) }.distinct().size)
        assertEquals(195, BookTranslationIndex.fromFeoriaCatalog().size)
        assertEquals(14, BookTranslationCorpus.VERSION)
        assertEquals(listOf(BookTranslationIndex.HOUR1_SOURCE, BookTranslationIndex.VIGIL_SOURCE),
            BookTranslationIndex.requiredSources(ru.liturgyguide.model.WorshipKind.HOUR1))
    }

    @Test fun onlyTheTwoExactApprovedWholePairsAndMeaningsWereAdded() {
        val approved = JsonParser.parseString(resource("accepted-two.json")).asJsonArray
        assertEquals(2, approved.size())
        val entries = FeoriaTranslationCatalog.entries.drop(193)
        val index = BookTranslationIndex.fromFeoriaCatalog()
        approved.zip(entries).forEach { (value, entry) ->
            val row = value.asJsonObject
            assertEquals(row["id"].asString, entry.id)
            assertEquals(row["original"].asString, entry.original)
            assertEquals(row["russian"].asString, entry.russian)
            assertEquals(row["originalSourceUrl"].asString, entry.originalSourceUrl)
            assertEquals(9, entry.revision)
            assertEquals(row["russianUtf8Sha256"].asString, BookText.sha256(entry.russian))
            val meaning = requireNotNull(BookFirstHourDismissal.forBody(entry.original)).meaning
            assertEquals(row["meaning"].asString, meaning)
            assertEquals(row["meaningUtf8Sha256"].asString, BookText.sha256(meaning))
            val translation = requireNotNull(index.find(entry.original))
            assertEquals(BookTranslation(entry.russian, BookTranslationIndex.FEORIA_TRANSLATION_SOURCE), translation)
            assertTrue(FeoriaTranslationCatalog.recognizes(entry.original, translation))
            assertFalse(FeoriaTranslationCatalog.recognizes(entry.original, translation.copy(meaning = meaning)))
            assertFalse(FeoriaTranslationCatalog.recognizes(entry.original, translation.copy(russian = entry.russian + " еще")))
            assertNull(index.find(entry.original.substringBefore(", моли")))
            assertNull(index.find(entry.original.replace("Пречи́стыя", "Пречистой")))
        }
    }

    @Test fun aVerifiedPublishedWholePairStillWinsOverTheOwnFallback() {
        for (entry in FeoriaTranslationCatalog.entries.drop(193)) {
            val page = "<table><tr><td>${Entities.escape(entry.original)}</td><td>Проверенный опубликованный русский текст</td></tr></table>"
            val published = BookTranslationIndex.fromParallelPage(Jsoup.parse(page), BookTranslationIndex.HOUR1_SOURCE)
            val merged = published.plus(BookTranslationIndex.fromFeoriaCatalog())
            assertEquals("Проверенный опубликованный русский текст", merged.find(entry.original)?.russian)
            assertEquals(BookTranslationIndex.HOUR1_SOURCE, merged.find(entry.original)?.source)
        }
    }
}
