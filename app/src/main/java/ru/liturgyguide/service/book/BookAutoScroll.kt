package ru.liturgyguide.service.book

/** Reading assistance, not an estimate of where a parish service has reached. */
object BookAutoScroll {
    const val MIN_RATE = 30
    const val MAX_RATE = 360
    const val DEFAULT_RATE = 150
    private const val SILENT_ITEM_SECONDS = .8f

    private val repeatInstruction = Regex("(?is)^(.+?)\\s*\\((трижды|дважды|(?:[1-9]|[1-3][0-9]|40)\\s+раз(?:а)?)(?:,\\s*без поклонов)?\\)[.!]?\\s*$")

    fun wordCount(text: String): Int {
        val repeated = repeatInstruction.matchEntire(text.trim().lowercase())
        val body = repeated?.groupValues?.get(1) ?: text
        val count = BookText.normalized(body).split(' ').count(String::isNotBlank)
        val instruction = repeated?.groupValues?.get(2)?.lowercase()
        val repeats = when (instruction) {
            "трижды" -> 3
            "дважды" -> 2
            else -> instruction?.substringBefore(' ')?.toIntOrNull() ?: 1
        }
        return count * repeats
    }

    /** null denotes a heading or an instruction which is not spoken aloud. */
    fun seconds(words: Int?, wordsPerMinute: Int): Float = if (words == null) SILENT_ITEM_SECONDS
        else words.coerceAtLeast(1) * 60f / wordsPerMinute.coerceIn(MIN_RATE, MAX_RATE)

    fun pixelsPerSecond(height: Float, gap: Float, words: Int?, wordsPerMinute: Int): Float {
        if (!height.isFinite() || height <= 0f || !gap.isFinite() || gap < 0f) return 0f
        return (height + gap) / seconds(words, wordsPerMinute)
    }

    /** Do not carry the speed of a tall heading into the short prayer after it. */
    fun frameDistance(speed: Float, seconds: Float, remainingPixels: Float): Float {
        if (!speed.isFinite() || !seconds.isFinite() || !remainingPixels.isFinite() || speed <= 0f || seconds <= 0f) return 0f
        // One pixel lets LazyColumn retire an item exactly on its layout boundary.
        return minOf(speed * seconds, remainingPixels.coerceAtLeast(1f))
    }
}
