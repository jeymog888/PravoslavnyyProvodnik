package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish

class PalomnikScheduleSiteResolverTest {
    private val gson = Gson()
    private val title = "Мужской монастырь Новомучеников Российских (Новосибирск)"
    private fun parish() = Parish("osm:way:55228075", "Храм в честь Святых Новомучеников и Исповедников Российских", "Новомучеников", "Новосибирск",
        "улица Немировича-Данченко, 120/1", "", "", "Asia/Novosibirsk", "https://www.openstreetmap.org/way/55228075", "", "", "", emptyList(), "", "", 54.9755397, 82.9086203)
    private fun fixture(name: String = "novosibirsk") = javaClass.classLoader!!.getResourceAsStream("schedule/palomnik/$name.wiki")!!.bufferedReader().use { it.readText() }
    private fun category(members: List<Pair<Int, String>> = listOf(6081 to title), more: String? = null): String = gson.toJson(buildMap<String, Any> {
        put("query", mapOf("categorymembers" to members.map { mapOf("pageid" to it.first, "title" to it.second) }))
        if (more != null) put("continue", mapOf("cmcontinue" to more))
    })
    private fun revisions(text: String = fixture(), name: String = title, id: Int = 6081): String = gson.toJson(mapOf("query" to mapOf("pages" to mapOf(id.toString() to mapOf(
        "pageid" to id, "title" to name, "revisions" to listOf(mapOf("slots" to mapOf("main" to mapOf("*" to text)))))))))
    private fun response(url: String, body: String) = ScheduleResponse(url, body.toByteArray(), "application/json")
    private fun resolver(text: String = fixture(), calls: MutableList<String> = mutableListOf()) = PalomnikScheduleSiteResolver(ScheduleTransport { url ->
        calls += url
        response(url, if (url.contains("categorymembers")) category() else revisions(text))
    })

    @Test fun `real Novosibirsk identity and explicit current schedule need no HTTP or wikipedia radius relaxation`() = runBlocking {
        val calls = mutableListOf<String>()
        val result = resolver(calls = calls).resolve(parish())
        assertEquals("https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/", result?.url)
        assertEquals("https://azbyka.ru/palomnik/index.php?curid=6081", result?.evidenceUrl)
        assertEquals(title, result?.matchedName)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.startsWith("https://azbyka.ru/palomnik/api.php?") })
        assertFalse(calls.any { "54.975" in it || "82.908" in it })
    }

    @Test fun `exact address field does not rescue wrong geography city house or dedication`() = runBlocking {
        val text = fixture()
        for (bad in listOf(
            text.replace("54.975263, 82.908625", "54.9834, 82.9067"),
            text.replace("Немировича-Данченко 120/1", "Немировича-Данченко 120/2"),
            text.replace("Немировича-Данченко 120/1", "Новогодняя 24"),
            text.replace("Адрес:", "Исторический адрес:"),
            text.replace("локация=", "другое="),
            text + "\n'''Адрес:''' Россия, Новосибирск, другой корпус\n"
        )) assertNull(resolver(bad).resolve(parish()))
        assertNull(resolver().resolve(parish().copy(city = "Калуга")))
        assertNull(resolver().resolve(parish().copy(name = "Храм Александра Невского")))
        assertNull(resolver().resolve(parish().copy(latitude = null)))
        assertNull(resolver().resolve(parish().copy(address = "улица Немировича-Данченко", latitude = 54.9770)))
    }

    @Test fun `external navigation and archive labels never become church schedule`() = runBlocking {
        val text = fixture()
        assertNull(resolver(text.replace("=== Богослужения ===", "=== Ссылки ===")).resolve(parish()))
        assertNull(resolver(text.replace("Актуальное расписание", "Архивное расписание")).resolve(parish()))
        assertNull(resolver(text.replace("https://nskmonastyr", "http://nskmonastyr")).resolve(parish()))
        val withNavigation = text + "\n== Ссылки ==\n[https://unrelated.example/ Расписание на сайте храма]\n"
        assertEquals("https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/", resolver(withNavigation).resolve(parish())?.url)
        assertNull(resolver(text + "\n[https://second.example/ Расписание на сайте храма]\n").resolve(parish()))
        assertNull(resolver(text.replace("https://nskmonastyr.cerkov.ru", "https://azbyka.ru")).resolve(parish()))
    }

    @Test fun `catalog identifies Yaroslavl but does not invent replacement domain for stale website`() = runBlocking {
        val p = parish().copy(name = "Храм Петра и Павла", city = "Ярославль", address = "Петропавловский парк, 25а", latitude = 57.602533, longitude = 39.8436998)
        val calls = mutableListOf<String>()
        val resolver = PalomnikScheduleSiteResolver(ScheduleTransport { url ->
            calls += url
            response(url, if (url.contains("categorymembers")) category(listOf(31296 to "Храм Петра и Павла (Ярославль)")) else revisions(fixture("yaroslavl"), "Храм Петра и Павла (Ярославль)", 31296))
        })
        assertEquals("https://hram-petrapavla.ru/", resolver.resolve(p)?.url)
        assertEquals(2, calls.size)
        assertFalse(calls.any { ".com" in it })
    }

    @Test fun `bounded category continuation is encoded and both pages must complete before selection`() = runBlocking {
        val calls = mutableListOf<String>()
        val resolver = PalomnikScheduleSiteResolver(ScheduleTransport { url ->
            calls += url
            response(url, when {
                "cmcontinue=" in url -> category(emptyList())
                "categorymembers" in url -> category(more = "page|two&unexpected=value")
                else -> revisions()
            })
        })
        assertNotNull(resolver.resolve(parish()))
        assertEquals(3, calls.size)
        assertTrue(calls[1].contains("cmcontinue=page%7Ctwo%26unexpected%3Dvalue"))
    }

    @Test fun `oversized incomplete and ambiguous categories stop without querying arbitrary candidates`() = runBlocking {
        for (kind in listOf("oversized", "incomplete", "ambiguous")) {
            val calls = mutableListOf<String>()
            val resolver = PalomnikScheduleSiteResolver(ScheduleTransport { url ->
                calls += url
                response(url, when (kind) {
                    "oversized" -> category((1..501).map { it to "Храм Александра Невского" })
                    "incomplete" -> category(more = "page${calls.size}")
                    else -> category((1..4).map { it to title })
                })
            })
            assertNull(resolver.resolve(parish()))
            assertEquals(if (kind == "incomplete") 2 else 1, calls.size)
            assertFalse(calls.any { "prop=revisions" in it })
        }
    }

    @Test fun `two matching nearby churches are ambiguous even when both return same URL`() = runBlocking {
        val one = com.google.gson.JsonParser.parseString(revisions()).asJsonObject
        val pages = one.getAsJsonObject("query").getAsJsonObject("pages")
        val duplicate = pages.getAsJsonObject("6081").deepCopy().apply { addProperty("pageid", 6082) }
        pages.add("6082", duplicate)
        val resolver = PalomnikScheduleSiteResolver(ScheduleTransport { url -> response(url,
            if ("categorymembers" in url) category(listOf(6081 to title, 6082 to title)) else one.toString()) })
        assertNull(resolver.resolve(parish()))
        // Missing site is not proof that the second matching building does not exist
        duplicate.getAsJsonArray("revisions")[0].asJsonObject.getAsJsonObject("slots").getAsJsonObject("main")
            .addProperty("*", fixture().replace("https://nskmonastyr", "http://nskmonastyr"))
        assertNull(resolver.resolve(parish()))
    }

    @Test fun `malformed response foreign response origin and unknown page IDs cannot inject a source`() = runBlocking {
        for (body in listOf("not json", "{\"query\":[]}", "{\"query\":{\"categorymembers\":42}}")) {
            assertNull(PalomnikScheduleSiteResolver(ScheduleTransport { response(it, body) }).resolve(parish()))
        }
        assertNull(PalomnikScheduleSiteResolver(ScheduleTransport { response("https://other.example/api.php", category()) }).resolve(parish()))
        assertNull(PalomnikScheduleSiteResolver(ScheduleTransport { response(it, "x".repeat(768 * 1024 + 1)) }).resolve(parish()))
        assertNull(PalomnikScheduleSiteResolver(ScheduleTransport { response(it,
            if ("categorymembers" in it) category() else revisions(id = 9999)) }).resolve(parish()))
    }

    @Test fun `request cancellation remains cancellation`() = runBlocking {
        try {
            PalomnikScheduleSiteResolver(ScheduleTransport { throw CancellationException("cancelled") }).resolve(parish())
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) { assertEquals("cancelled", expected.message) }
    }
}
