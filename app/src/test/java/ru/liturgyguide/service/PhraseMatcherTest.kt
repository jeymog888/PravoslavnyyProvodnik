package ru.liturgyguide.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhraseMatcherTest {
    private val matcher = PhraseMatcher(listOf(
        StageAnchor("beginning", listOf("Благословенно Царство")),
        StageAnchor("entrance", listOf("Премудрость, прости", "Приидите, поклонимся")),
        StageAnchor("cherubic", listOf("Иже херувимы тайно образующе")),
        StageAnchor("creed", listOf("Верую во единаго Бога"))
    ))

    @Test
    fun `distinctive phrase suggests its stage and retains evidence`() {
        val match = matcher.match("Благословенно Царство Отца и Сына и Святаго Духа")
        assertEquals("beginning", match?.stageId)
        assertEquals("Благословенно Царство", match?.matchedPhrase)
    }

    @Test
    fun `punctuation and case do not hide a complete phrase`() {
        assertEquals("entrance", matcher.match("Аминь. ПРЕМУДРОСТЬ! Прости.")?.stageId)
    }

    @Test
    fun `church accents and historic letters are normalized`() {
        assertEquals("creed", matcher.match("Вѣ́рую во еди́наго Бо́га Отца Вседержителя")?.stageId)
    }

    @Test
    fun `Russian recognition spelling can match a canonical phrase`() {
        val canonical = PhraseMatcher(listOf(
            StageAnchor("beginning", listOf("Благословено Царство Отца и Сына и Святаго Духа"))
        ))
        assertEquals("beginning", canonical.match("Благословенно царство отца и сына и святого духа")?.stageId)
    }

    @Test
    fun `historic terminal hard signs before punctuation do not hide a phrase`() {
        val canonical = PhraseMatcher(listOf(StageAnchor("first", listOf("Един Господь"))))
        assertEquals("first", canonical.match("Единъ, Господь!")?.stageId)
    }

    @Test
    fun `multiple anchors for the same stage remain one suggestion`() {
        assertEquals("entrance", matcher.match("Премудрость прости. Приидите поклонимся")?.stageId)
    }

    @Test
    fun `shared phrase alone cannot identify a stage`() {
        val ambiguous = PhraseMatcher(listOf(
            StageAnchor("first", listOf("Святый Боже")),
            StageAnchor("second", listOf("Святый Боже"))
        ))
        assertNull(ambiguous.match("Святый Боже, Святый Крепкий"))
    }

    @Test
    fun `a transcript spanning two stages needs manual correction`() {
        assertNull(matcher.match("Премудрость прости иже херувимы тайно образующе"))
    }

    @Test
    fun `ordinary repeated prayers are never treated as distinctive`() {
        val generic = PhraseMatcher(listOf(
            StageAnchor("first", listOf("Господи помилуй", "Господи помилуй Господи помилуй", "Мир всем", "Аминь"))
        ))
        assertNull(generic.match("Мир всем. Господи помилуй Господи помилуй. Аминь"))
    }

    @Test
    fun `fragments and substring words are not sufficient evidence`() {
        assertNull(matcher.match("херувимы"))
        assertNull(matcher.match("неблагословенно царство"))
        assertNull(matcher.match("верую во"))
    }

    @Test
    fun `unrecognized input never advances the service`() {
        assertNull(matcher.match(""))
        assertNull(matcher.match("Сегодня очень тихо"))
        assertNull(matcher.match("Господи помилуй"))
    }

    @Test
    fun `matching keeps word order`() {
        assertNull(matcher.match("Царство благословенно"))
    }

    @Test
    fun `duplicate content entries do not make one stage ambiguous`() {
        val duplicate = PhraseMatcher(listOf(
            StageAnchor("first", listOf("Благословенно Царство", "Благословенно Царство"))
        ))
        assertEquals("first", duplicate.match("Благословенно Царство")?.stageId)
    }
}
