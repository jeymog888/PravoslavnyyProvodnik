package ru.liturgyguide.service

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.DayReading
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.PrayerLine
import ru.liturgyguide.model.ReadingDay
import ru.liturgyguide.model.StageText

class PrayerTrackFactoryTest {
    private val pairs = listOf(PrayerLine("Первая исходная", "Первый смысл"), PrayerLine("Вторая исходная", "Второй смысл"))
    private val prayer = StageText("Молитва", pairs.joinToString("\n") { it.original },
        pairs.joinToString("\n") { it.translation }, "Пояснение", lines = pairs)
    private val stage = LiturgyStage("epistle", 1, "Апостол", "Описание", 8, listOf(prayer), sourceUrl = "https://example.org/stage")
    private val day = ReadingDay("2026-11-08", "https://example.org/day", "Календарь", "Примечание", listOf(
        DayReading("epistle", "Рим. 8:1-2", "Первый стих.\n\nВторой стих, в т.ч. продолжение.", "Смысл",
            occasion = "Воскресное чтение", sourceUrl = "https://example.org/romans"),
        DayReading("gospel", "Мф. 8:1", "Св. апостол сказал: и т. д.", "Смысл")
    ))

    @Test fun `paired languages share exactly one line identity and source`() {
        val track = PrayerTrackFactory.build(stage, null).single()
        assertEquals(pairs.map { it.translation }, track.lines.map { it.russian })
        assertEquals(pairs.map { it.original }, track.lines.map { it.original })
        assertEquals(2, track.lines.map { it.id }.distinct().size)
        assertEquals("https://example.org/stage", track.sourceUrl)
        assertTrue(track.isExcerpt)
    }

    @Test fun `legacy text remains one aligned block without guessing sentence pairs`() {
        val legacy = prayer.copy(lines = emptyList())
        val track = PrayerTrackFactory.build(stage.copy(texts = listOf(legacy)), null).single()
        assertEquals(1, track.lines.size)
        assertEquals(legacy.original, track.lines.single().original)
        assertEquals(legacy.translation, track.lines.single().russian)
    }

    @Test fun `matching daily readings follow the introduction and keep source verse boundaries`() {
        val tracks = PrayerTrackFactory.build(stage, day)
        assertEquals(2, tracks.size)
        assertEquals(prayer.type, tracks.first().title)
        val reading = tracks.last()
        assertEquals("Рим. 8:1-2", reading.title)
        assertEquals(listOf("Первый стих.", "Второй стих, в т.ч. продолжение."), reading.lines.map { it.russian })
        assertTrue(reading.lines.all { it.original == null })
        assertEquals("https://example.org/romans", reading.sourceUrl)
        assertTrue(reading.subtitle.contains("Синодальный перевод"))
        assertFalse(reading.isExcerpt)
    }

    @Test fun `reference abbreviations do not split a single source verse`() {
        val track = PrayerTrackFactory.build(stage.copy(id = "gospel", texts = emptyList()), day).single()
        assertEquals("Мф. 8:1", track.title)
        assertEquals(1, track.lines.size)
        assertEquals("Св. апостол сказал: и т. д.", track.lines.single().russian)
        assertEquals(day.sourceUrl, track.sourceUrl)
    }

    @Test fun `unrelated stage and missing day do not borrow another reading`() {
        assertTrue(PrayerTrackFactory.build(stage.copy(id = "creed", texts = emptyList()), day).isEmpty())
        assertTrue(PrayerTrackFactory.build(stage.copy(texts = emptyList()), null).isEmpty())
    }

    @Test fun `track identity survives insertion reorder and translation refinement`() {
        val original = PrayerTrackFactory.build(stage, day).single { !it.isScripture }
        val added = prayer.copy(type = "Другое песнопение", original = "Другое", translation = "Иное", lines = emptyList())
        val refined = prayer.copy(translation = "Уточнено", lines = pairs.map { it.copy(translation = "Уточнено ${it.translation}") })
        val altered = PrayerTrackFactory.build(stage.copy(texts = listOf(added, refined)), day.copy(readings = day.readings.reversed())).single { it.title == prayer.type }
        assertEquals(original.id, altered.id)
        assertEquals(original.lines.map { it.id }, altered.lines.map { it.id })
        val changedSource = PrayerTrackFactory.build(stage.copy(texts = listOf(prayer.copy(lines = pairs.reversed()))), null).single()
        assertNotEquals(original.id, changedSource.id)
    }

    @Test fun `duplicate prayers and repeated lines still have unique stable ids`() {
        val repeated = prayer.copy(lines = listOf(pairs.first(), pairs.first()))
        val tracks = PrayerTrackFactory.build(stage.copy(texts = listOf(repeated, repeated)), null)
        assertEquals(2, tracks.map { it.id }.distinct().size)
        assertEquals(4, tracks.flatMap { it.lines }.map { it.id }.distinct().size)
        assertEquals(tracks, PrayerTrackFactory.build(stage.copy(texts = listOf(repeated, repeated)), null))
    }

    @Test fun `empty or broken direct content never creates a blank track`() {
        val texts = listOf(prayer.copy(original = " ", lines = emptyList()),
            prayer.copy(lines = listOf(PrayerLine("Исходник", " "))))
        val emptyDay = day.copy(readings = listOf(day.readings.first().copy(text = " \n ")))
        assertTrue(PrayerTrackFactory.build(stage.copy(texts = texts), emptyDay).isEmpty())
    }

    @Test fun `daily track ids include date and text revision for safe saved positions`() {
        val original = PrayerTrackFactory.build(stage, day).single { it.isScripture }
        val differentDay = PrayerTrackFactory.build(stage, day.copy(date = "2026-11-09")).single { it.isScripture }
        val differentText = PrayerTrackFactory.build(stage, day.copy(readings = listOf(day.readings.first().copy(text = "Новый стих")))).single { it.isScripture }
        assertNotEquals(original.id, differentDay.id)
        assertNotEquals(original.id, differentText.id)
    }

    @Test fun `selected text meaning context and scripture identity remain available to the screen`() {
        val tracks = PrayerTrackFactory.build(stage, day)
        val reading = tracks.single { it.isScripture }
        val prayerTrack = tracks.single { !it.isScripture }
        assertEquals(day.readings.first().explanation, reading.explanation)
        assertEquals(day.note, reading.contextNote)
        assertEquals(prayer.explanation, prayerTrack.explanation)
        assertNull(prayerTrack.contextNote)
        assertEquals("Смысл по-русски", prayerTrack.subtitle)
    }

    @Test fun `recognizable prayer title takes precedence without changing track identity`() {
        val before = PrayerTrackFactory.build(stage, null).single()
        val named = PrayerTrackFactory.build(stage.copy(texts = listOf(prayer.copy(title = "Отче наш"))), null).single()
        assertEquals("Отче наш", named.title)
        assertEquals(before.id, named.id)
        assertEquals(prayer.type, before.title)
    }

    @Test fun `long verses split at readable word boundaries without changing source wording`() {
        val source = "Ибо Господь, кого любит, того наказывает; бьет же всякого сына, которого принимает. " +
            "Если вы терпите наказание, то Бог поступает с вами, как с сынами. Ибо есть ли какой сын, которого бы не наказывал отец?"
        val longDay = day.copy(readings = listOf(day.readings.first().copy(text = source)))
        val lines = PrayerTrackFactory.build(stage.copy(texts = emptyList()), longDay).single().lines.map { it.russian }
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= 120 })
        assertEquals(source, lines.joinToString(" "))
        assertTrue(lines.first().endsWith("принимает."))
    }

    @Test fun `a single long token is kept intact and never causes an endless split`() {
        val word = "Неразрывное".repeat(15)
        val source = "Начало. $word Конец."
        val longDay = day.copy(readings = listOf(day.readings.first().copy(text = source)))
        val lines = PrayerTrackFactory.build(stage.copy(texts = emptyList()), longDay).single().lines.map { it.russian }
        assertEquals(source, lines.joinToString(" "))
        assertTrue(lines.contains(word))
    }

    @Test fun `abbreviations stay attached to their next word near a display boundary`() {
        val source = "А".repeat(112) + " Св. апостол сказал еще одно слово"
        val longDay = day.copy(readings = listOf(day.readings.first().copy(text = source)))
        val lines = PrayerTrackFactory.build(stage.copy(texts = emptyList()), longDay).single().lines.map { it.russian }
        assertEquals(source, lines.joinToString(" "))
        assertFalse(lines.any { it.endsWith("Св.") })
        assertTrue(lines.any { it.startsWith("Св. апостол") })
    }

    @Test fun `packaged prayers and dated readings produce complete usable player tracks`() {
        fun asset(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream(name))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val stages = StageMatcher(asset("liturgy_stages.json")).getAllStages()
        val readingDays = Gson().fromJson(asset("daily_readings.json"), DailyReadings::class.java).days
        val prayers = stages.flatMap { current ->
            assertTrue(current.texts.all { it.lines.isNotEmpty() })
            PrayerTrackFactory.build(current, null).also { assertEquals(current.texts.size, it.size) }
        }
        assertTrue(prayers.isNotEmpty())
        assertEquals(prayers.size, prayers.map { it.id }.distinct().size)
        assertTrue(prayers.all { it.lines.isNotEmpty() && it.lines.all { line -> line.russian.isNotBlank() && !line.original.isNullOrBlank() } })
        readingDays.forEach { readingDay ->
            val readings = stages.flatMap { current -> PrayerTrackFactory.build(current.copy(texts = emptyList()), readingDay) }
            assertEquals(readingDay.readings.count { it.kind in setOf("epistle", "gospel") }, readings.size)
            assertTrue(readings.all { it.lines.isNotEmpty() && it.lines.all { line -> line.russian.isNotBlank() && line.original == null } })
            readingDay.readings.filter { it.kind in setOf("epistle", "gospel") }.forEach { source ->
                val matchingStage = stages.single { it.id == source.kind }
                val track = PrayerTrackFactory.build(matchingStage.copy(texts = emptyList()), readingDay.copy(readings = listOf(source))).single()
                assertEquals(source.text.trim().replace(Regex("\\s+"), " "), track.lines.joinToString(" ") { it.russian })
                assertTrue(track.lines.all { it.russian.length <= 120 })
            }
            stages.filter { it.id in setOf("epistle", "gospel") }.forEach { current ->
                val tracks = PrayerTrackFactory.build(current, readingDay)
                val introductions = PrayerTrackFactory.build(current, null)
                val scripture = PrayerTrackFactory.build(current.copy(texts = emptyList()), readingDay)
                assertEquals(introductions + scripture, tracks)
                assertEquals(readingDay.readings.filter { it.kind == current.id }.map { it.reference },
                    tracks.filter { it.isScripture }.map { it.title })
                assertTrue(tracks.last().isScripture)
            }
        }
    }
}
