package ru.liturgyguide.model

data class DailyReadings(val days: List<ReadingDay>)
data class ReadingDay(val date: String, val sourceUrl: String, val sourceTitle: String, val note: String, val readings: List<DayReading>)
data class DayReading(
    val kind: String,
    val reference: String,
    val text: String,
    val explanation: String,
    val occasion: String? = null,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    val translation: String? = null
)
