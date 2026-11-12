package ru.liturgyguide.service.book

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.BookSource
import ru.liturgyguide.service.schedule.ScheduleTransport

class CompoundScriptureTest {
    private val reference = "1Thes.5:9-13,24-28"
    private val directUrl = "https://azbyka.ru/biblia/?$reference&c~r"
    // Independently read from the published daily passage, in its printed order.
    private val expected = listOf(
        "потому что Бог определил нас не на гнев, но к получению спасения через Господа нашего Иисуса Христа,",
        "умершего за нас, чтобы мы, бодрствуем ли, или спим, жили вместе с Ним.",
        "Посему увещавайте друг друга и назидайте один другого, как вы и делаете.",
        "Просим же вас, братия, уважать трудящихся у вас, и предстоятелей ваших в Господе, и вразумляющих вас,",
        "и почитать их преимущественно с любовью за дело их; будьте в мире между собою.",
        "Верен Призывающий вас, Который и сотворит сие.",
        "Братия! молитесь о нас.",
        "Приветствуйте всех братьев лобзанием святым.",
        "Заклинаю вас Господом прочитать сие послание всем святым братиям.",
        "Благодать Господа нашего Иисуса Христа с вами. Аминь."
    ).joinToString("\n\n")

    @Test fun realDailyPassageHasAllTenOrderedVersesAndDoesNotRequestAnExtraPage() = runBlocking {
        val document = page("daily-2026-11-13.html")
        // Repeated identical readings retain the existing daily merge policy, never duplicate output.
        verse(document, 9).after(verse(document, 9).clone())
        val daily = BookScripture.fromPage(document)
        val translated = requireNotNull(daily.russian(reference))
        assertEquals(expected, translated.russian)
        assertEquals(10, translated.russian.split("\n\n").size)
        assertEquals(BookSource("Библия, Синодальный перевод", "https://azbyka.ru/biblia/?$reference&r"), translated.source)
        val networkCalls = java.util.concurrent.atomic.AtomicInteger()
        val retained = BookScriptureFallback.load(daily, listOf(reference), ScheduleTransport {
            networkCalls.incrementAndGet()
            error("Daily-complete must not use network")
        })
        assertEquals("A complete daily reading must not request a supplement", 0, networkCalls.get())
        assertEquals(translated, retained.russian(reference))
    }

    @Test fun realDirectPageUsesOnlyTheRequestedVersesAndItsExactVerifiedUrl() {
        val document = page("direct-compound-2026-11-13.html")
        assertEquals(directUrl, BookScripture.directUrl(reference))
        val translated = requireNotNull(BookScripture.empty().withDirectPage(reference, document, directUrl).russian(reference))
        assertEquals(expected, translated.russian)
        assertEquals(BookSource("Библия, Синодальный перевод", directUrl), translated.source)
        // The actual direct page includes the whole chapter. Skipped verses must stay skipped.
        assertEquals(28, document.select(".verse[data-lang=r][data-verse]").size)
        for (number in 14..23) assertFalse(translated.russian.contains(verse(document, number).text()))
        for (url in listOf(directUrl.replace("24-28", "24-27"), directUrl.replace("&c~r", "&r"), "$directUrl#r"))
            assertNull(BookScripture.empty().withDirectPage(reference, document, url).russian(reference))
    }

    @Test fun partialConflictingForeignOrNestedRowsCannotPublishACompleteCompoundReading() {
        val common: List<Pair<String, (Document) -> Unit>> = listOf(
            "missing" to { verse(it, 24).remove() },
            "empty" to { verse(it, 24).empty() },
            "wrong chapter" to { verse(it, 24).attr("data-verse", "1Thes.6:24") },
            "wrong language" to { verse(it, 24).attr("data-lang", "c") },
            "conflicting" to { val row = verse(it, 24); row.after(row.clone().text("Конфликтующий текст QA")) }
        )
        for ((name, mutate) in common) {
            val daily = page("daily-2026-11-13.html").also(mutate)
            assertNull("daily $name", BookScripture.fromPage(daily).russian(reference))
            val direct = page("direct-compound-2026-11-13.html").also(mutate)
            assertNull("direct $name", BookScripture.empty().withDirectPage(reference, direct, directUrl).russian(reference))
        }
        val duplicate = page("direct-compound-2026-11-13.html")
        verse(duplicate, 24).after(verse(duplicate, 24).clone())
        assertNull(BookScripture.empty().withDirectPage(reference, duplicate, directUrl).russian(reference))
        val nested = page("direct-compound-2026-11-13.html")
        val skipped = verse(nested, 14); skipped.remove(); verse(nested, 13).appendChild(skipped)
        assertNull(BookScripture.empty().withDirectPage(reference, nested, directUrl).russian(reference))
        val dailyHalf = page("daily-2026-11-13.html").also { d -> (24..28).forEach { verse(d, it).remove() } }
        val directHalf = page("direct-compound-2026-11-13.html").also { d -> (9..13).forEach { verse(d, it).remove() } }
        assertNull("Two partial responses must not be stitched into complete provenance",
            BookScripture.fromPage(dailyHalf).withDirectPage(reference, directHalf, directUrl).russian(reference))
    }

    @Test fun commaRangesAreStrictlyOrderedNonoverlappingAndBoundedTo201VersesInOneChapter() {
        val daily = BookScripture.fromPage(page("daily-2026-11-13.html"))
        val malformed = listOf("1Thes.5:,9-13", "1Thes.5:9-13,", "1Thes.5:9-13,,24-28",
            "1Thes.5:24-28,9-13", "1Thes.5:9-13,13-14", "1Thes.5:9,9", "1Thes.5:0,9",
            "1Thes.0:9,10", "1Thes.5:13-9,24", "1Thes.5:9-6:1,24", "1Thes.5:9-13,6:24",
            "1Thes.5:9-13, 24-28", "1Thes.5:9-13;24-28", "1Thes.5:1-200,201-202", "$reference&x=1")
        for (bad in malformed) {
            assertNull(bad, BookScripture.directUrl(bad))
            assertNull(bad, daily.russian(bad))
        }
        assertEquals("https://azbyka.ru/biblia/?Lk.10:1,3&c~r", BookScripture.directUrl("Lk.10:1,3"))
        assertEquals("https://azbyka.ru/biblia/?Lk.1:1-100,101-201&c~r", BookScripture.directUrl("Lk.1:1-100,101-201"))
        assertNull(BookScripture.directUrl("Lk.1:1-100,101-202"))
        assertNull("Cross-chapter direct remains unsupported", BookScripture.directUrl("Gal.5:26-6:2"))
    }

    private fun page(name: String) = Jsoup.parse(requireNotNull(javaClass.classLoader?.getResource("scripture-fallback/$name")).readText())
    private fun verse(document: Document, number: Int) = document.select(".verse[data-lang=r][data-verse='1Thes.5:$number']").single()
}
