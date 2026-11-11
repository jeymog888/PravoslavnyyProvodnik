package ru.liturgyguide.service.schedule

/** Extract only a strongly evidenced service column. Raw rows remain the safety evidence. */
internal object ScheduleOcrServiceColumn {
    private val clock = Regex("(?:[01]?\\d|2[0-3])[:.][0-5]\\d")
    private val service = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед|молеб|панихид|часы|лития|акафист)")
    private val date = Regex("(?iu)^\\d{1,2}\\s+(?:пн|вт|ср|чт|пт|сб|вс|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\.?(?![а-яё])")
    private val footer = Regex("(?iu)^(?:телефон|тел\\.|адрес|www\\.|https?://)")

    data class Text(val service: String, val venueEvidence: String? = null)

    fun texts(rows: List<List<ScheduleWord>>): Map<Int, Text> {
        val timed = rows.withIndex().filter { (_, words) -> words.any { clock.matches(it.text) } }
        if (timed.size < 6) return emptyMap()
        val clocks = timed.flatMap { (_, words) -> words.filter { clock.matches(it.text) } }
        val body = rows.subList(timed.first().index, timed.last().index + 1).flatten()
        val heights = body.map { it.bottom - it.top }.filter { it > 0 }.sorted()
        val height = heights.getOrNull(heights.size / 2)?.toDouble() ?: return emptyMap()
        val right = clocks.map { it.right }.sorted().let { it[it.size / 2] }
        // Side-by-side timetables and a second time column are not this layout.
        if (clocks.any { kotlin.math.abs(it.right - right) > height }) return emptyMap()
        val left = clocks.minOf { it.left }
        val dateRows = rows.count { words -> date.containsMatchIn(words.filter { it.right < left }.joinToString(" ") { it.text }) }
        if (dateRows < 3) return emptyMap()
        val spans = mutableListOf<IntRange>()
        body.sortedBy { it.left }.forEach { word ->
            val previous = spans.lastOrNull()
            if (previous != null && word.left <= previous.last) spans[spans.lastIndex] = previous.first..maxOf(previous.last, word.right)
            else spans += word.left..word.right
        }
        val gap = spans.zipWithNext().firstOrNull { (a, b) -> a.last > right && b.first - a.last >= height * 1.5 } ?: return emptyMap()
        val boundary = (gap.first.last + gap.second.first) / 2
        val recognizable = timed.count { (_, words) ->
            service.containsMatchIn(words.filter { it.left >= right && it.right < boundary }.joinToString(" ") { it.text })
        }
        if (recognizable < 6 || recognizable * 5 < timed.size * 4) return emptyMap()
        if (timed.count { (_, words) -> words.any { it.left > boundary } } < 3) return emptyMap()
        // A second persistent gutter isolates the calendar from the celebrant column.
        // Only full calendar names already exempted by ScheduleVenue may be reconstructed.
        // Never discard the rest of that column: it can contain a venue or cancellation.
        val calendarEnd = spans.zipWithNext().firstOrNull { (a, b) ->
            a.last > boundary && b.first - a.last >= height * 1.5
        }?.let { (a, b) -> (a.last + b.first) / 2 }
        val calendarWords = mutableListOf<CalendarWord>()
        val calendarText = StringBuilder()
        if (calendarEnd != null) rows.forEachIndexed { index, words ->
            if (index in timed.first().index..timed.last().index) words.forEach { word ->
                if (word.left > boundary && word.right < calendarEnd) {
                    if (calendarText.isNotEmpty()) calendarText.append(' ')
                    val start = calendarText.length
                    calendarText.append(word.text)
                    calendarWords += CalendarWord(index, word, start until calendarText.length)
                }
            }
        }
        val calendarOnly = mutableSetOf<ScheduleWord>()
        ScheduleVenue.calendarRanges(calendarText.toString()).forEach { range ->
            val matched = calendarWords.filter { it.range.first <= range.last && it.range.last >= range.first }
            val completeWords = matched.all { token ->
                val prefix = (range.first - token.range.first).coerceAtLeast(0)
                val suffix = (token.range.last - range.last).coerceAtLeast(0)
                (token.word.text.take(prefix) + token.word.text.takeLast(suffix)).none(Char::isLetterOrDigit)
            }
            if (matched.isNotEmpty() && completeWords && matched.last().row - matched.first().row <= 2 &&
                matched.all { it.word.confidence >= 45 } &&
                matched.last().word.bottom - matched.first().word.top <= height * 4) {
                calendarOnly += matched.map { it.word }
            }
        }
        return rows.mapIndexedNotNull { index, words ->
            val raw = words.joinToString(" ") { it.text }
            if (index < timed.first().index || footer.containsMatchIn(raw) ||
                words.any { it.left < boundary && it.right > boundary } || words.none { it.left > boundary }) null
            else index to Text(words.filter { it.right <= boundary }.joinToString(" ") { it.text },
                words.takeIf { row -> row.any { it in calendarOnly } }
                    ?.filterNot { it in calendarOnly }?.joinToString(" ") { it.text })
        }.toMap()
    }
    private data class CalendarWord(val row: Int, val word: ScheduleWord, val range: IntRange)
}
