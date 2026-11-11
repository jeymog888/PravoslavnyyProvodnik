package ru.liturgyguide.model

import java.net.URLEncoder
import java.time.LocalDate

/** This date belongs to the liturgical book; an evening service usually uses tomorrow's book. */
data class ServiceBookKey(
    val date: String,
    val worship: WorshipKind = WorshipKind.LITURGY,
    val churchType: String = "30",
    val mainEvent: String? = null,
    val secondEvent: String? = null
) {
    fun isValid(): Boolean = runCatching {
        val parsed = LocalDate.parse(date)
        parsed.toString() == date && parsed.year in 1900..2200 && worship.queryValue.isNotBlank() &&
            churchType in setOf("10", "11", "12", "20", "30", "31", "32", "33", "34", "35") &&
            listOf(mainEvent, secondEvent).all { it == null || it.matches(Regex("[0-9]{0,6}")) }
    }.getOrDefault(false)

    fun canonical(): String = listOf(date, worship.name, churchType, mainEvent ?: "~", secondEvent ?: "~").joinToString("|")

    fun sourceUrl(): String {
        require(isValid())
        val kind = if (worship == WorshipKind.VIGIL) WorshipKind.VESPERS else worship
        val values = linkedMapOf("date" to date, "worship" to kind.queryValue, "size" to "full", "church" to churchType)
        mainEvent?.let { values["main_event"] = it }
        secondEvent?.let { values["second_event"] = it }
        return "https://azbyka.ru/worships/?" + values.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8") }"
        }
    }
}

enum class WorshipKind(val queryValue: String, val title: String) {
    LITURGY("liturgy", "Литургия"), VESPERS("vespers", "Вечерня"), MATINS("matins", "Утреня"),
    HOUR1("chas1", "Первый час"), HOUR3("chas3", "Третий час"), HOUR6("chas6", "Шестой час"),
    HOUR9("chas9", "Девятый час"), VIGIL("vigil", "Всенощное бдение")
}

enum class BookBlockKind { PRAYER, RUBRIC, SCRIPTURE, LOCAL_VARIATION }

/** AVAILABLE describes a verified supplement source, not translation of every prayer. */
enum class BookSupplementStatus { NOT_NEEDED, AVAILABLE, RETRY_NEEDED, NOT_REQUESTED }

data class BookEnrichment(
    val russian: BookSupplementStatus = BookSupplementStatus.NOT_REQUESTED,
    val scripture: BookSupplementStatus = BookSupplementStatus.NOT_REQUESTED
) {
    val needsRetry: Boolean get() = russian == BookSupplementStatus.RETRY_NEEDED || scripture == BookSupplementStatus.RETRY_NEEDED
}

data class BookSource(val title: String, val url: String)
data class BookVariantOption(val value: String, val title: String)

data class BookBlock(
    val id: String,
    val kind: BookBlockKind,
    val speaker: String?,
    val original: String,
    val russian: String? = null,
    val translationSource: BookSource? = null,
    val scriptureReferences: List<String> = emptyList(),
    val isAltarText: Boolean = false,
    val meaning: String? = null
)

data class BookChapter(
    val id: String,
    val title: String,
    val meaning: String,
    val blocks: List<BookBlock>
)

data class ServiceBook(
    val schemaVersion: Int = 1,
    val key: ServiceBookKey,
    val title: String,
    val sourceUrl: String,
    val sourceTitle: String,
    val fetchedAtMillis: Long,
    val selectedEventTitle: String?,
    val chapters: List<BookChapter>,
    val contentHash: String,
    val notices: List<String> = emptyList(),
    val sources: List<BookSource> = emptyList(),
    val mainEventOptions: List<BookVariantOption> = emptyList(),
    val secondEventOptions: List<BookVariantOption> = emptyList(),
    val enrichment: BookEnrichment = BookEnrichment()
) {
    val blocks: List<BookBlock> get() = chapters.flatMap { it.blocks }
    val translatedPrayerCount: Int get() = blocks.count { it.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE) && it.russian != null }
    val prayerCount: Int get() = blocks.count { it.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.SCRIPTURE) }
}
