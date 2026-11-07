package ru.liturgyguide.service

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.model.PlayerLine
import ru.liturgyguide.model.PrayerTrack

class PrayerTextSearchTest {
    private fun asset(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun stageTracks(id: String): List<PrayerTrack> = PrayerTrackFactory.build(
        requireNotNull(StageMatcher(asset("liturgy_stages.json")).getStageById(id)), null)

    private fun track(id: String, title: String, vararg texts: String): PrayerTrack = PrayerTrack(id, title, "",
        texts.mapIndexed { index, text -> PlayerLine("$id:$index", text, null) }, null, false)

    private fun phraseIds(tracks: List<PrayerTrack>, query: String): Pair<String, String> {
        val match = requireNotNull(PrayerTextSearch.find(tracks, query))
        val track = tracks[match.trackIndex]
        return track.id to track.lines[match.lineIndex].id
    }

    @Test fun `Veruyu opens the actual Creed instead of the introductory prayer`() {
        val tracks = stageTracks("creed")
        val match = requireNotNull(PrayerTextSearch.find(tracks, "Верую"))
        assertEquals("Символ веры", tracks[match.trackIndex].title)
        assertEquals(0, match.lineIndex)
        assertTrue(match.trackIndex > 0)
    }

    @Test fun `Our Father title and an internal petition locate their correct lines`() {
        val tracks = stageTracks("lords_prayer")
        val title = requireNotNull(PrayerTextSearch.find(tracks, "Отче наш"))
        assertEquals("Отче наш", tracks[title.trackIndex].title)
        assertEquals(0, title.lineIndex)
        val petition = requireNotNull(PrayerTextSearch.find(tracks, "Хлеб насущный"))
        assertEquals(title.trackIndex, petition.trackIndex)
        assertTrue(tracks[petition.trackIndex].lines[petition.lineIndex].original.orEmpty().contains("Хлеб наш насущный"))
        assertTrue(petition.lineIndex > 0)
    }

    @Test fun `Russian meaning finds its paired line even when the original uses different words`() {
        val tracks = stageTracks("creed")
        val match = requireNotNull(PrayerTextSearch.find(tracks, "Божественная сущность"))
        val line = tracks[match.trackIndex].lines[match.lineIndex]
        assertTrue(line.russian.contains("Божественная сущность"))
        assertTrue(line.original.orEmpty().contains("единосущна"))
    }

    @Test fun `title match takes global priority over an earlier track body`() {
        val tracks = listOf(track("intro", "Вступление", "Вступление перед молитвой Отче наш"),
            track("prayer", "Отче наш", "Наш Отец Небесный"))
        assertEquals(PrayerTextMatch(1, 0), PrayerTextSearch.find(tracks, "Отче наш"))
    }

    @Test fun `all query words are required and may appear in a different order`() {
        val tracks = listOf(track("first", "Первый", "Небесный Отец"),
            track("second", "Второй", "Наш Отец Небесный"))
        assertEquals(PrayerTextMatch(1, 0), PrayerTextSearch.find(tracks, "небесный наш"))
        assertNull(PrayerTextSearch.find(tracks, "наш солнце"))
    }

    @Test fun `case accents yo and punctuation are folded consistently with stage search`() {
        val tracks = listOf(track("first", "Другой текст", "Святой ещё поёт"))
        assertEquals(PrayerTextMatch(0, 0), PrayerTextSearch.find(tracks, "ЕЩЕ́, ПОЕ́Т!"))
        val prayers = stageTracks("lords_prayer")
        assertEquals(PrayerTextSearch.find(prayers, "хлеб насущный"),
            PrayerTextSearch.find(prayers, "ХЛЕ́Б, НАСУ́ЩНЫЙ!"))
    }

    @Test fun `both Gospel spellings locate the same original call and paired meaning`() {
        val tracks = stageTracks("gospel")
        val expected = requireNotNull(PrayerTextSearch.find(tracks, "святаго евангелия"))
        val expectedIds = phraseIds(tracks, "святаго евангелия")
        assertEquals(expectedIds, phraseIds(tracks, "святого евангелия"))
        assertEquals(expectedIds, phraseIds(tracks, "СВЯТО́ГО, ЕВАНГЕ́ЛИЯ!"))
        val line = tracks[expected.trackIndex].lines[expected.lineIndex]
        assertEquals("Премудрость, прости, услышим Святаго Евангелия", line.original)
        assertEquals("Премудрость! Станем прямо и внимательно послушаем Святое Евангелие", line.russian)
    }

    @Test fun `both Trisagion spellings locate the same phrase including original-only words`() {
        val tracks = stageTracks("troparia_trisagion")
        val expected = requireNotNull(PrayerTextSearch.find(tracks, "святый боже"))
        val expectedIds = phraseIds(tracks, "святый боже")
        listOf("святой боже", "святый крепкий", "святой крепкий").forEach { query ->
            assertEquals(query, expectedIds, phraseIds(tracks, query))
        }
        val line = tracks[expected.trackIndex].lines[expected.lineIndex]
        assertEquals("Святый Боже, Святый Крепкий, Святый Безсмертный, помилуй нас.", line.original)
        assertEquals("Святой Боже, Святой Всемогущий, Святой Бессмертный, помилуй нас", line.russian)
    }

    @Test fun `spelling variants find a later paired phrase instead of the track beginning`() {
        val tracks = stageTracks("creed")
        val expected = requireNotNull(PrayerTextSearch.find(tracks, "духа святаго животворящаго"))
        assertEquals(expected, PrayerTextSearch.find(tracks, "духа святого животворящаго"))
        assertTrue(expected.trackIndex > 0)
        assertTrue(expected.lineIndex > 0)
        val line = tracks[expected.trackIndex].lines[expected.lineIndex]
        assertEquals("И в Духа Святаго, Господа, Животворящаго,", line.original)
        assertEquals("И в Святого Духа, Господа, дарующего жизнь.", line.russian)
    }

    @Test fun `unknown words and unlisted spelling guesses do not choose a real prayer`() {
        val stages = StageMatcher(asset("liturgy_stages.json")).getAllStages()
        val tracks = stages.flatMap { PrayerTrackFactory.build(it, null) }
        listOf("святого евангелия квазислово", "святова евангелия", "святаво евангелия", "квазислово")
            .forEach { query -> assertNull(query, PrayerTextSearch.find(tracks, query)) }
    }

    @Test fun `search folds the curated whole words without rewriting unknown word fragments`() {
        assertEquals(
            "благословено святого святой соборного единого небесного бессмертный бессмертного божьих божьим",
            TextSearch.normalize("благословенно святаго святый соборнаго единаго небеснаго безсмертный безсмертнаго божиих божиим")
        )
        assertEquals("несвятаго святыйство единагообразный",
            TextSearch.normalize("несвятаго святыйство единагообразный"))
    }

    @Test fun `search removes stress but preserves the difference between short i and i`() {
        assertEquals("святой май", TextSearch.normalize("СВЯТЫ́Й, МА́Й!"))
        assertEquals("святой май", TextSearch.normalize("святыи\u0306 маи\u0306"))
        assertNotEquals(TextSearch.normalize("май"), TextSearch.normalize("маи"))
        assertNotEquals(TextSearch.normalize("святый"), TextSearch.normalize("святыи"))
    }

    @Test fun `a long original anchor remains searchable across paired phrases`() {
        val tracks = stageTracks("creed")
        val match = requireNotNull(PrayerTextSearch.find(tracks,
            "Верую во единаго Бога Отца Вседержителя Творца небу и земли видимым же всем и невидимым"))
        assertEquals("Символ веры", tracks[match.trackIndex].title)
        assertEquals(0, match.lineIndex)
    }

    @Test fun `a long scripture query remains searchable across display chunks`() {
        val stage = requireNotNull(StageMatcher(asset("liturgy_stages.json")).getStageById("epistle"))
        val day = Gson().fromJson(asset("daily_readings.json"), DailyReadings::class.java).days.first()
        val reading = day.readings.first { it.kind == "epistle" && it.text.lines().any { line -> line.length > 120 } }
        val query = reading.text.lines().first { it.length > 120 }
        val tracks = PrayerTrackFactory.build(stage, day)
        val match = requireNotNull(PrayerTextSearch.find(tracks, query))
        assertFalse(tracks.first().isScripture)
        assertTrue(match.trackIndex > 0)
        assertTrue(tracks[match.trackIndex].isScripture)
        assertEquals(reading.reference, tracks[match.trackIndex].title)
        assertEquals(0, match.lineIndex)
    }

    @Test fun `a Gospel verse opens the reading after its introductory call`() {
        val stage = requireNotNull(StageMatcher(asset("liturgy_stages.json")).getStageById("gospel"))
        val day = Gson().fromJson(asset("daily_readings.json"), DailyReadings::class.java).days.first()
        val tracks = PrayerTrackFactory.build(stage, day)
        val match = requireNotNull(PrayerTextSearch.find(tracks, "что вы так боязливы маловерные"))
        assertFalse(tracks.first().isScripture)
        assertTrue(match.trackIndex > 0)
        assertTrue(tracks[match.trackIndex].isScripture)
        assertEquals("От Матфея 8:23-27", tracks[match.trackIndex].title)
        assertTrue(tracks[match.trackIndex].lines[match.lineIndex].russian.contains("маловерные"))
    }

    @Test fun `common repeated terms are deterministic in track and line order`() {
        val tracks = listOf(track("first", "Первый", "Вступление", "Помилуй нас", "Помилуй нас снова"),
            track("second", "Второй", "Помилуй нас"))
        repeat(5) { assertEquals(PrayerTextMatch(0, 1), PrayerTextSearch.find(tracks, "помилуй")) }
    }

    @Test fun `null blank punctuation and unmatched queries do not choose an arbitrary track`() {
        val tracks = stageTracks("creed")
        listOf(null, "", "  \n ", "...,?!", "неизвестное слово 987654321").forEach { query ->
            assertNull(PrayerTextSearch.find(tracks, query))
        }
        assertNull(PrayerTextSearch.find(emptyList(), "Верую"))
    }

    @Test fun `an empty titled track cannot produce an invalid line index`() {
        val tracks = listOf(track("empty", "Отче наш"), track("valid", "Другая молитва", "Отче наш"))
        assertEquals(PrayerTextMatch(1, 0), PrayerTextSearch.find(tracks, "Отче наш"))
    }
}
