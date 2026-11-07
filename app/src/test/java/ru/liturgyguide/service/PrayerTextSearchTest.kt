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
