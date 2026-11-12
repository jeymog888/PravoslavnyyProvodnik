package ru.liturgyguide.service.book

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.ServiceBookKey

class BookAutoScrollTest {
    @Test fun oldSavedRatesAndTheExtendedRateSurviveTheReadingCodec() {
        val position = BookReadingPosition(ServiceBookKey("2026-11-13"), "a".repeat(64), "parish", "prayer", "chapter")
        for (rate in listOf(30, 85, 180, 360)) {
            assertEquals(rate, requireNotNull(BookReadingCodec.decode(BookReadingCodec.encode(position.copy(wordsPerMinute = rate)))).wordsPerMinute)
        }
        val json = BookReadingCodec.encode(position.copy(wordsPerMinute = 360))
        assertNull(BookReadingCodec.decode(json.replace("\"wordsPerMinute\":360", "\"wordsPerMinute\":361")))
        assertNull(BookReadingCodec.decode(json.replace("\"wordsPerMinute\":360", "\"wordsPerMinute\":29")))
    }
    @Test fun shortResponsesConsumeTheirActualNumberOfWords() {
        assertEquals(1, BookAutoScroll.wordCount("Ами́нь."))
        assertEquals(2, BookAutoScroll.wordCount("Го́споди, поми́луй."))
        assertEquals(1f / 3, BookAutoScroll.seconds(1, 180), .0001f)
        assertEquals(2f / 3, BookAutoScroll.seconds(2, 180), .0001f)
        val responses = listOf("Аминь", "Господи, помилуй", "Аминь", "Подай, Господи", "Тебе, Господи")
        assertEquals(8f / 3, responses.sumOf { BookAutoScroll.seconds(BookAutoScroll.wordCount(it), 180).toDouble() }.toFloat(), .0001f)
    }

    @Test fun accentsAndPunctuationDoNotCreateExtraWords() {
        assertEquals(3, BookAutoScroll.wordCount("Сла́ва Тебѣ́, Бо́же!"))
        assertEquals(3, BookAutoScroll.wordCount("Слава\nТебе,\u00a0Боже"))
        assertEquals(0, BookAutoScroll.wordCount("…"))
    }

    @Test fun printedRepetitionIsTimeForThePrayerRatherThanASpokenInstruction() {
        assertEquals(6, BookAutoScroll.wordCount("Го́споди, поми́луй (трижды)."))
        assertEquals(24, BookAutoScroll.wordCount("Господи, помилуй (12 раз)."))
        assertEquals(80, BookAutoScroll.wordCount("Господи, помилуй (40 раз)"))
        assertEquals(18, BookAutoScroll.wordCount("Аллилу́ия, аллилу́ия, аллилу́ия, сла́ва Тебе́, Бо́же. (Трижды, без поклонов)"))
        assertEquals(2f, BookAutoScroll.seconds(BookAutoScroll.wordCount("Го́споди, поми́луй (трижды)."), 180), .0001f)
    }

    @Test fun geometryAndSpacingDoNotChangeTheTimeSpentOnTheSameWords() {
        for (height in listOf(48f, 120f, 500f)) for (gap in listOf(0f, 20f)) {
            val speed = BookAutoScroll.pixelsPerSecond(height, gap, 2, 180)
            assertEquals(2f / 3, (height + gap) / speed, .0001f)
        }
        assertEquals(4f, BookAutoScroll.pixelsPerSecond(120f, 20f, 30, 120) /
            BookAutoScroll.pixelsPerSecond(120f, 20f, 30, 30), .0001f)
    }

    @Test fun silentHeadingsAndDirectionsDoNotAddFictionalSpokenWords() {
        assertEquals(.8f, BookAutoScroll.seconds(null, 30), .0001f)
        assertEquals(.8f, BookAutoScroll.seconds(null, 360), .0001f)
        assertEquals(0f, BookAutoScroll.pixelsPerSecond(Float.NaN, 20f, 2, 150), 0f)
        assertEquals(0f, BookAutoScroll.pixelsPerSecond(0f, 20f, 2, 150), 0f)
    }

    @Test fun aTallHeadingCannotSkipTheShortPrayerOnTheNextFrame() {
        val headingSpeed = BookAutoScroll.pixelsPerSecond(1000f, 20f, null, 180)
        assertEquals(127.5f, headingSpeed * .1f, .001f)
        assertEquals(1f, BookAutoScroll.frameDistance(headingSpeed, .1f, 1f), .001f)
        assertEquals(1f, BookAutoScroll.frameDistance(headingSpeed, .1f, 0f), .001f)
        assertEquals(0f, BookAutoScroll.frameDistance(Float.NaN, .1f, 10f), 0f)
    }
}
