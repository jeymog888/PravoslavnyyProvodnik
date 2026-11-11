package ru.liturgyguide.service.book

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.BookCalendarDay
import ru.liturgyguide.model.BookCommemoration
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.schedule.ScheduleTransport
import ru.liturgyguide.service.schedule.UrlConnectionScheduleTransport

data class CalendarSupplement(val day: BookCalendarDay, val translations: BookTranslationIndex)

fun interface CalendarSupplements {
    suspend fun load(date: String): CalendarSupplement?
}

/** The phone only receives public, dated text. The API credential never enters the app. */
class PublishedCalendarSupplements(
    private val transport: ScheduleTransport = UrlConnectionScheduleTransport(timeoutMillis = 3_000, limitBytes = MAX_BYTES)
) : CalendarSupplements {
    override suspend fun load(date: String): CalendarSupplement? {
        if (!ServiceBookKey(date).isValid()) return null
        val url = "$BASE_URL/$date.json"
        val response = transport.fetch(url)
        if (response.url != url || response.bytes.size > MAX_BYTES) return null
        return withContext(Dispatchers.Default) { decode(response.bytes.toString(Charsets.UTF_8), date) }
    }

    companion object {
        const val MAX_BYTES = 524_288
        const val BASE_URL = "https://raw.githubusercontent.com/jeymog888/PravoslavnyyProvodnik/calendar-cache/days"

        /** Reject an invalid package atomically; an incomplete page must never supply another day. */
        fun decode(json: String, date: String): CalendarSupplement? = runCatching {
            require(ServiceBookKey(date).isValid() && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val root = JsonParser.parseString(json).asJsonObject
            require(root.int("schemaVersion") == 1 && root.text("date", 10) == date)
            val source = "https://azbyka.ru/days/$date"
            require(root.text("sourceUrl", 200) == source)
            val week = root.text("weekTitle", 300, allowEmpty = true)
            val voice = root.get("voice")?.takeUnless { it.isJsonNull }?.let { strictInt(it).also { n -> require(n in 1..8) } }
            val commemorations = root.getAsJsonArray("commemorations").also { require(it.size() <= 100) }.map { value ->
                val item = value.asJsonObject
                require(item.text("sourceUrl", 200) == source)
                BookCommemoration(item.text("title", 500), item.optionalText("description", 1_800), source)
            }
            val pairs = JsonArray()
            val originals = mutableMapOf<String, String>()
            root.getAsJsonArray("translations").also { require(it.size() <= 200) }.forEach { value ->
                val item = value.asJsonObject
                require(item.int("apiId") > 0 && item.text("sourceUrl", 200) == source)
                val original = item.text("original", 12_000)
                val russian = item.text("russian", 16_000)
                val key = BookText.normalized(BookText.withoutSpeaker(original).second)
                require(key.isNotBlank())
                val previous = originals.putIfAbsent(key, russian)
                require(previous == null || previous == russian) // Conflicting editions require human selection.
                pairs.add(JsonObject().apply {
                    addProperty("original", original); addProperty("russian", russian)
                    addProperty("source", item.text("sourceTitle", 300)); addProperty("url", source)
                })
            }
            CalendarSupplement(BookCalendarDay(date, week, voice, commemorations, source), BookTranslationIndex.decode(pairs.toString()))
        }.getOrNull()

        internal fun valid(day: BookCalendarDay, date: String): Boolean = runCatching {
            val source = "https://azbyka.ru/days/$date"
            require(day.date == date && day.sourceUrl == source && day.weekTitle.length <= 300)
            require(day.voice == null || day.voice in 1..8)
            require(day.commemorations.size <= 100 && day.commemorations.all {
                it.title.length in 1..500 && (it.description == null || it.description.length in 1..1_800) && it.sourceUrl == source
            })
            true
        }.getOrDefault(false)

        private fun strictInt(value: JsonElement): Int {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            val number = value.asString
            require(number.matches(Regex("[0-9]{1,9}")))
            return number.toInt()
        }
        private fun JsonObject.int(name: String) = strictInt(requireNotNull(get(name)))
        private fun JsonObject.text(name: String, max: Int, allowEmpty: Boolean = false): String {
            val value = requireNotNull(get(name))
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString.also {
                require(it.length in (if (allowEmpty) 0 else 1)..max && it == it.trim())
                require(!it.contains('<') && !it.contains('>') && it.none { c -> c.isISOControl() && c !in "\n\t" })
            }
        }
        private fun JsonObject.optionalText(name: String, max: Int): String? =
            get(name)?.takeUnless { it.isJsonNull }?.let { text(name, max) }
    }
}
