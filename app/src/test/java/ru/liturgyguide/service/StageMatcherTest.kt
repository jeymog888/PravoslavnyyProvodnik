package ru.liturgyguide.service

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.LiturgyService

class StageMatcherTest {
    @Test
    fun `packaged guide loads all stages and positive snake case durations`() {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("liturgy_stages.json")) {
            "The production guide must be included in unit test resources"
        }
        val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val source = JsonParser.parseString(json).asJsonObject
        val matcher = StageMatcher(json)
        val stages = matcher.getAllStages()

        assertEquals(source.getAsJsonArray("stages").size(), stages.size)
        assertEquals("preparation", stages.first().id)
        assertEquals("dismissal", stages.last().id)
        assertTrue(stages.all { it.durationMin > 0 })
        assertTrue(stages.all { !it.cue.isNullOrBlank() })
        assertEquals(stages.sumOf { it.durationMin }, matcher.getTotalDuration())
        assertFalse(matcher.getContentNote().isNullOrBlank())
        assertTrue(matcher.getSourceUrl()?.startsWith("https://") == true)
    }

    @Test
    fun `model maps both snake case duration fields`() {
        val service = Gson().fromJson(testGuideJson(), LiturgyService::class.java)

        assertEquals(30, service.totalDurationMin)
        assertEquals(listOf(10, 10, 10), service.stages.map { it.durationMin })
        assertEquals("https://example.org/source", service.sourceUrl)
        assertEquals("Учебный справочник", service.contentNote)
    }

    @Test
    fun `stage order is normalized and navigation uses stable ids`() {
        val source = JsonParser.parseString(testGuideJson()).asJsonObject
        source.getAsJsonArray("stages")[0].asJsonObject.addProperty("order", 3)
        source.getAsJsonArray("stages")[2].asJsonObject.addProperty("order", 1)
        val matcher = StageMatcher(source.toString())

        assertEquals(listOf("third", "second", "first"), matcher.getAllStages().map { it.id })
        assertEquals("second", matcher.getNextStageById("third")?.id)
        assertEquals("third", matcher.getPreviousStage("second")?.id)
        assertEquals(3, matcher.getStageNumber(requireNotNull(matcher.getStageById("first"))))
        assertNull(matcher.getNextStageById("missing"))
        assertNull(matcher.getPreviousStage("missing"))
    }

    @Test
    fun `a stale declared total cannot disagree with stage durations`() {
        val matcher = StageMatcher(testGuideJson().replace("\"total_duration_min\": 30", "\"total_duration_min\": 0"))

        assertEquals(30, matcher.getTotalDuration())
    }

    @Test
    fun `missing required stage fields fail at load instead of in the screen`() {
        listOf("id", "order", "name", "description", "duration_min", "texts").forEach { field ->
            val source = JsonParser.parseString(testGuideJson()).asJsonObject
            source.getAsJsonArray("stages")[0].asJsonObject.remove(field)

            assertThrows("Missing $field", IllegalArgumentException::class.java) {
                StageMatcher(source.toString())
            }
        }
    }

    @Test
    fun `missing nested text is rejected instead of violating Kotlin nullability`() {
        val source = JsonParser.parseString(testGuideJson()).asJsonObject
        source.getAsJsonArray("stages")[1].asJsonObject
            .getAsJsonArray("texts")[0].asJsonObject.remove("translation")

        assertThrows(IllegalArgumentException::class.java) { StageMatcher(source.toString()) }
    }

    @Test
    fun `invalid and empty guides fail with a load error`() {
        listOf("not json", "null", "{}", "{\"service\":\"test\",\"stages\":[]}",
            "{\"service\":\"test\",\"stages\":[null]}").forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { StageMatcher(json) }
        }
    }

    @Test
    fun `zero negative and overflowing durations are rejected`() {
        listOf(0, -1, Int.MAX_VALUE).forEach { duration ->
            val source = JsonParser.parseString(testGuideJson()).asJsonObject
            source.getAsJsonArray("stages")[0].asJsonObject.addProperty("duration_min", duration)

            assertThrows(IllegalArgumentException::class.java) { StageMatcher(source.toString()) }
        }
    }

    @Test
    fun `duplicate identity or order cannot create ambiguous navigation`() {
        listOf("id", "order").forEach { field ->
            val source = JsonParser.parseString(testGuideJson()).asJsonObject
            val stages = source.getAsJsonArray("stages")
            stages[1].asJsonObject.add(field, stages[0].asJsonObject[field])

            assertThrows(IllegalArgumentException::class.java) { StageMatcher(source.toString()) }
        }
    }

    @Test fun `aligned prayer lines are validated and decoded without losing the legacy text`() {
        val matcher = StageMatcher(guideWithLines("""[{"original":"Исходный текст","translation":"Смысловой пересказ"}]"""))
        val text = matcher.getStageById("second")!!.texts.single()
        assertEquals(text.original, text.lines.single().original)
        assertEquals(text.translation, text.lines.single().translation)
    }

    @Test fun `missing or null lines remain compatible with legacy single blocks`() {
        assertTrue(StageMatcher(testGuideJson()).getStageById("second")!!.texts.single().lines.isEmpty())
        assertTrue(StageMatcher(guideWithLines("null")).getStageById("second")!!.texts.single().lines.isEmpty())
    }

    @Test fun `explicit empty null or incomplete prayer lines fail at content load`() {
        listOf("[]", "[null]", "[{}]", """[{"original":"Исходный текст"}]""",
            """[{"original":" ","translation":"Смысловой пересказ"}]""",
            """[{"original":"Исходный текст","translation":null}]""").forEach { lines ->
            assertThrows(IllegalArgumentException::class.java) { StageMatcher(guideWithLines(lines)) }
        }
    }

    @Test fun `prayer lines cannot silently disagree with either complete text`() {
        listOf("""[{"original":"Другой текст","translation":"Смысловой пересказ"}]""",
            """[{"original":"Исходный текст","translation":"Другой смысл"}]""").forEach { lines ->
            assertThrows(IllegalArgumentException::class.java) { StageMatcher(guideWithLines(lines)) }
        }
    }

    @Test fun `optional prayer title is retained but a declared blank title is rejected`() {
        val source = JsonParser.parseString(testGuideJson()).asJsonObject
        val text = source.getAsJsonArray("stages")[1].asJsonObject.getAsJsonArray("texts")[0].asJsonObject
        text.addProperty("title", "  Отче наш  ")
        assertEquals("Отче наш", StageMatcher(source.toString()).getStageById("second")!!.texts.single().title)
        text.addProperty("title", " ")
        assertThrows(IllegalArgumentException::class.java) { StageMatcher(source.toString()) }
        text.remove("title")
        assertNull(StageMatcher(source.toString()).getStageById("second")!!.texts.single().title)
    }

    private fun guideWithLines(lines: String): String {
        val source = JsonParser.parseString(testGuideJson()).asJsonObject
        source.getAsJsonArray("stages")[1].asJsonObject.getAsJsonArray("texts")[0].asJsonObject
            .add("lines", JsonParser.parseString(lines))
        return source.toString()
    }
}
