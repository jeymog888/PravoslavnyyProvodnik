package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.net.URLDecoder

class PalomnikCitylessScheduleSiteResolverTest {
    private val gson = Gson()
    private val title = "Храм Троицы Живоначальной в Листах (Москва)"
    private fun parish() = Parish("osm:relation:19595736", "Храм Троицы Живоначальной в Листах", "Троицы в Листах", "",
        "улица Сретенка, 27 с3", "", "", "Europe/Moscow", "https://www.openstreetmap.org/relation/19595736", "", "", "", emptyList(), "", "", 55.7716959, 37.63184935)
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("schedule/palomnik/cityless/$name")!!.bufferedReader().use { it.readText() }
    private fun search(rows: List<Pair<Int, String>> = listOf(14059 to title), total: Int = rows.size, more: Boolean = false): String = gson.toJson(buildMap<String, Any> {
        put("query", mapOf("searchinfo" to mapOf("totalhits" to total), "search" to rows.map { mapOf("ns" to 0, "pageid" to it.first, "title" to it.second) }))
        if (more) put("continue", mapOf("sroffset" to 10, "continue" to "-||"))
    })
    private fun revisions(text: String = fixture("listi.wiki"), name: String = title, id: Int = 14059): String = gson.toJson(mapOf("query" to mapOf("pages" to mapOf(id.toString() to mapOf(
        "pageid" to id, "title" to name, "revisions" to listOf(mapOf("slots" to mapOf("main" to mapOf("*" to text)))))))))
    private fun response(url: String, body: String) = ScheduleResponse(url, body.toByteArray(), "application/json")
    private fun resolver(
        first: String = fixture("listi-name.json"), second: String = fixture("listi-street.json"),
        revision: String = revisions(), calls: MutableList<String> = mutableListOf(),
    ) = PalomnikScheduleSiteResolver(ScheduleTransport { url ->
        calls += url
        response(url, when {
            "prop=revisions" in url -> revision
            calls.count { "list=search" in it } == 1 -> first
            else -> second
        })
    })
    private fun query(url: String) = URLDecoder.decode(url.substringAfter("srsearch="), "UTF-8")

    @Test fun `real Listi is resolved without inventing city and both queries and revisions remain bounded`() = runBlocking {
        val calls = mutableListOf<String>()
        val selected = parish()
        val result = resolver(calls = calls).resolve(selected)
        assertEquals("https://hram-troicy.ru/raspisanie/", result?.url)
        assertEquals("https://azbyka.ru/palomnik/index.php?curid=14059", result?.evidenceUrl)
        assertEquals("", selected.city)
        assertEquals(3, calls.size)
        assertEquals(selected.name, query(calls[0]))
        assertEquals("\"сретенка\" Живоначальной", query(calls[1]))
        assertTrue(calls.take(2).all { "srwhat=text" in it && "srlimit=10" in it && "srinfo=totalhits" in it })
        assertFalse(calls.any { "55.771" in it || "37.631" in it || "categorymembers" in it })
        assertEquals("14059", calls.last().substringAfter("pageids="))
    }

    @Test fun `complete street search can find exact church after empty or too broad name search`() = runBlocking {
        for (first in listOf(search(emptyList()), search(listOf(70081 to title), total = 218, more = true))) {
            val calls = mutableListOf<String>()
            assertNotNull(resolver(first = first, calls = calls).resolve(parish()))
            assertEquals("14059", calls.last().substringAfter("pageids=")) // No revision from the partial first ten
        }
    }

    @Test fun `quoted hyphenated street finds real Novosibirsk article but shared monastery does not identify a single building schedule`() = runBlocking {
        val selected = parish().copy(id = "osm:way:55228075", name = "Храм в честь Святых Новомучеников и Исповедников Российских",
            address = "улица Немировича-Данченко, 120/1", latitude = 54.97553165, longitude = 82.9086244)
        val calls = mutableListOf<String>()
        val source = resolver(fixture("novosibirsk-name.json"), fixture("novosibirsk-street.json"),
            revisions(fixture("novosibirsk-complex.wiki"), "Мужской монастырь Новомучеников Российских (Новосибирск)", 6081), calls)
        assertNull(source.resolve(selected))
        assertEquals(3, calls.size)
        assertEquals("\"немировича-данченко\" Новомучеников", query(calls[1]))
        assertEquals("6081", calls.last().substringAfter("pageids="))
    }

    @Test fun `random Balahna distant namesake and Bezhetsk incomplete name search are not guessed`() = runBlocking {
        val balahna = parish().copy(id = "osm:way:201738093", name = "Воскресенская церковь", address = "улица Демьяна Бедного, 26", latitude = 56.4831536, longitude = 43.61806575)
        val balahnaCalls = mutableListOf<String>()
        assertNull(resolver(fixture("balahna-name.json"), fixture("balahna-street.json"),
            calls = balahnaCalls).resolve(balahna))
        assertEquals(2, balahnaCalls.size)
        assertFalse(balahnaCalls.any { "prop=revisions" in it })
        // Even if the distant namesake were the only complete result, geography still rejects it.
        val distantTitle = "Воскресенская церковь (Воскресенское)"
        assertNull(resolver(search(listOf(70081 to distantTitle)), search(emptyList()),
            revisions(fixture("balahna-distant-namesake.wiki"), distantTitle, 70081)).resolve(balahna))
        val bezhetsk = parish().copy(id = "osm:way:808541076", name = "Преображения Господня", address = "площадь Константина Чернышова, 31", latitude = 57.77781725, longitude = 36.70376955)
        val calls = mutableListOf<String>()
        assertNull(resolver(fixture("bezhetsk-name.json"), fixture("bezhetsk-street.json"), calls = calls).resolve(bezhetsk))
        assertEquals(2, calls.size)
        assertFalse(calls.any { "prop=revisions" in it })
    }

    @Test fun `actual location structured address and explicit direct church must all agree`() = runBlocking {
        val text = fixture("listi.wiki")
        for (changed in listOf(
            text.replace("27, стр. 3", "27, стр. 4"), text.replace("27, стр. 3", "27, корп. 3"),
            text.replace("27, стр. 3", "27/3"), text.replace("27, стр. 3", "27"),
            text.replace("ул. Сретенка", "пер. Сретенка"), text.replace("ул. Сретенка", "ул. Петровка"),
            text.replace("55.7717194", "55.7757194"), text.replace("вид=Приходской храм", "вид=Монастырь"),
            text.replace("Адрес:", "Исторический адрес:"),
            text + "\n'''Адрес:''' Москва, ул. Сретенка, 27, стр. 3\n",
            text + "\n{{Местоположение святыни|локация=55.7717194,37.6317972}}",
            text + "\n{{Местоположение святыни|локация=неизвестна}}",
        )) assertNull(resolver(revision = revisions(changed)).resolve(parish()))
        assertNotNull(resolver(revision = revisions(text.replace("{{Местоположение|", "{{Местоположение святыни|"))).resolve(parish()))
        val chapel = "Часовня Троицы Живоначальной в Листах (Москва)"
        assertNull(resolver(search(listOf(14059 to chapel)), search(emptyList()), revisions(text, chapel)).resolve(parish()))
    }

    @Test fun `missing address details or coordinates never fall back to a namesake`() = runBlocking {
        for (selected in listOf(parish().copy(address = "улица Сретенка"), parish().copy(address = "Сретенка 27 с3"),
            parish().copy(latitude = null), parish().copy(longitude = Double.NaN))) {
            val calls = mutableListOf<String>()
            assertNull(resolver(calls = calls).resolve(selected))
            assertTrue(calls.isEmpty())
        }
        assertNull(resolver().resolve(parish().copy(address = "улица Сретенка, 27")))
        // A selected chapel may be searched, but a church article still cannot stand in for it.
        assertNull(resolver().resolve(parish().copy(name = "Часовня Троицы Живоначальной в Листах")))
    }

    @Test fun `city if present must still match through existing category branch`() = runBlocking {
        val calls = mutableListOf<String>()
        val source = PalomnikScheduleSiteResolver(ScheduleTransport { url ->
            calls += url
            response(url, if ("categorymembers" in url) gson.toJson(mapOf("query" to mapOf("categorymembers" to listOf(mapOf("pageid" to 14059, "title" to title))))) else revisions())
        })
        assertNull(source.resolve(parish().copy(city = "Калуга")))
        assertTrue(calls.first().contains("categorymembers"))
    }

    @Test fun `incomplete or invalid final query cannot promote the first result`() = runBlocking {
        val wrongNs = JsonParser.parseString(search()).asJsonObject.apply { getAsJsonObject("query").getAsJsonArray("search")[0].asJsonObject.addProperty("ns", 14) }.toString()
        val missingId = JsonParser.parseString(search()).asJsonObject.apply { getAsJsonObject("query").getAsJsonArray("search")[0].asJsonObject.remove("pageid") }.toString()
        for (second in listOf(search(total = 2), search(total = 10, more = true), search(total = 218),
            search(listOf(14059 to title, 14059 to title)), search((1..11).map { it to title }), wrongNs, missingId,
            "{\"query\":{\"searchinfo\":{},\"search\":[]}}", "not json")) {
            val calls = mutableListOf<String>()
            assertNull(resolver(second = second, calls = calls).resolve(parish()))
            assertEquals(2, calls.size)
        }
        val calls = mutableListOf<String>()
        assertNull(resolver(first = search((1..4).map { it to title }), calls = calls).resolve(parish()))
        assertEquals(1, calls.size)
    }

    @Test fun `union of complete queries preserves ambiguity before exclusion and without second website`() = runBlocking {
        val one = JsonParser.parseString(revisions()).asJsonObject
        val pages = one.getAsJsonObject("query").getAsJsonObject("pages")
        val duplicate = pages.getAsJsonObject("14059").deepCopy().apply { addProperty("pageid", 14060) }
        pages.add("14060", duplicate)
        val otherText = fixture("listi.wiki").replace("https://hram-troicy.ru", "https://another.example")
        for (secondText in listOf(otherText, otherText.replace("https://", "http://"))) {
            duplicate.getAsJsonArray("revisions")[0].asJsonObject.getAsJsonObject("slots").getAsJsonObject("main").addProperty("*", secondText)
            for (excluded in listOf(emptySet(), setOf("https://hram-troicy.ru/raspisanie/", "https://hram-troicy.ru/"))) {
                val calls = mutableListOf<String>()
                val source = resolver(first = search(), second = search(listOf(14060 to title)), revision = one.toString(), calls = calls)
                assertNull(source.resolve(parish(), excluded))
                assertEquals("14059%7C14060", calls.last().substringAfter("pageids="))
            }
        }
    }

    @Test fun `revision identity missing pages continuation and extra pages fail closed`() = runBlocking {
        val valid = JsonParser.parseString(revisions()).asJsonObject
        val missing = valid.deepCopy().apply { getAsJsonObject("query").getAsJsonObject("pages").remove("14059") }
        val wrongId = valid.deepCopy().apply { getAsJsonObject("query").getAsJsonObject("pages").getAsJsonObject("14059").addProperty("pageid", 14060) }
        val wrongTitle = valid.deepCopy().apply { getAsJsonObject("query").getAsJsonObject("pages").getAsJsonObject("14059").addProperty("title", "Другая церковь") }
        val extra = valid.deepCopy().apply { getAsJsonObject("query").getAsJsonObject("pages").add("14060", JsonObject()) }
        val continued = valid.deepCopy().apply { add("continue", JsonObject()) }
        for (changed in listOf(missing, wrongId, wrongTitle, extra, continued)) assertNull(resolver(revision = changed.toString()).resolve(parish()))
        val unreadableSecond = valid.deepCopy().apply { getAsJsonObject("query").getAsJsonObject("pages").add("14060", com.google.gson.JsonNull.INSTANCE) }
        assertNull(resolver(second = search(listOf(14060 to title)), revision = unreadableSecond.toString()).resolve(parish()))
    }

    @Test fun `deadline cancels hanging work and caller cancellation propagates`() = runBlocking {
        var cleaned = false
        val hanging = ScheduleTransport { try { awaitCancellation() } finally { cleaned = true } }
        assertNull(PalomnikScheduleSiteResolver(hanging, citylessTimeoutMillis = 20).resolve(parish()))
        assertTrue(cleaned)
        cleaned = false
        try {
            withTimeout(20) { PalomnikScheduleSiteResolver(hanging).resolve(parish()) }
            fail("Caller timeout must remain cancellation")
        } catch (_: CancellationException) { assertTrue(cleaned) }
    }
}
