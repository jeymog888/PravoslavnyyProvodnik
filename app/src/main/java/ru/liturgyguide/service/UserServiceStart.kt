package ru.liturgyguide.service

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import ru.liturgyguide.model.Parish
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One explicit local start for one parish and date; never a recurring timetable. */
data class UserServiceStart(val parishId: String, val date: String, val time: String)

enum class ServiceStartSource { PUBLISHED, USER }

/** Parsing is shared by published and personal starts. Ambiguous local times are not guessed. */
internal object ScheduleTime {
    fun date(value: String): LocalDate? = if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
        runCatching { LocalDate.parse(value).takeIf { it.year in 1..9999 } }.getOrNull() else null

    fun manualTime(value: String): LocalTime? = if (Regex("[0-9]{2}:[0-9]{2}").matches(value))
        runCatching { LocalTime.parse(value) }.getOrNull() else null

    // Published data keeps ParishRepository's ISO LocalTime contract, including seconds.
    fun publishedTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()

    fun instant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant? {
        val local = date.atTime(time)
        val offsets = zone.rules.getValidOffsets(local)
        return offsets.singleOrNull()?.let { local.atOffset(it).toInstant() }
    }
}

/** Strict primitive checks prevent Gson's number/boolean-to-string coercion in untrusted storage. */
object UserServiceStartCodec {
    const val MAX_RECORDS = 100
    const val MAX_PARISH_ID_LENGTH = 160
    const val MAX_JSON_CHARS = 65_536
    private val gson = Gson()
    private val fields = setOf("parishId", "date", "time")

    fun validated(record: UserServiceStart): UserServiceStart? = record.takeIf {
        it.parishId.length in 1..MAX_PARISH_ID_LENGTH && it.parishId == it.parishId.trim() &&
            it.parishId.none(Char::isISOControl) && ScheduleTime.date(it.date) != null && ScheduleTime.manualTime(it.time) != null
    }

    /** The zone belongs to the selected parish, never to the phone or the clock. */
    fun validatedFor(record: UserServiceStart, parish: Parish): UserServiceStart? {
        val valid = validated(record)?.takeIf { it.parishId == parish.id } ?: return null
        val zone = runCatching { ZoneId.of(parish.timezone) }.getOrNull() ?: return null
        return valid.takeIf { ScheduleTime.instant(requireNotNull(ScheduleTime.date(it.date)),
            requireNotNull(ScheduleTime.manualTime(it.time)), zone) != null }
    }

    fun decode(json: String?): List<UserServiceStart> {
        if (json.isNullOrBlank() || json.length > MAX_JSON_CHARS) return emptyList()
        if (!hasStandardJsonLexemes(json)) return emptyList()
        return runCatching {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                val records = mutableListOf<UserServiceStart>()
                var count = 0
                while (reader.hasNext()) {
                    require(++count <= MAX_RECORDS)
                    readRecord(reader)?.let(records::add)
                }
                reader.endArray()
                require(reader.peek() == JsonToken.END_DOCUMENT)
                unambiguous(records)
            }
        }.getOrDefault(emptyList())
    }

    /** Never silently drop records while saving, including invalid ones. */
    fun encode(records: List<UserServiceStart>): String {
        require(records.size <= MAX_RECORDS) { "The personal start list is full" }
        require(records.all { validated(it) != null }) { "A personal start is invalid" }
        require(records.map { it.parishId to it.date }.distinct().size == records.size) { "Only one personal start per parish and date" }
        return gson.toJson(records).also { require(it.length <= MAX_JSON_CHARS) { "The personal start list is too large" } }
    }

    /** Conflicting duplicates are not resolved by an undocumented first/last-wins rule. */
    internal fun unambiguous(records: List<UserServiceStart>): List<UserServiceStart> = records
        .mapNotNull(::validated).groupBy { it.parishId to it.date }.values.mapNotNull { group ->
            group.distinct().singleOrNull()
        }

    /** Gson 2.10 accepts case-insensitive literals and escaped apostrophes even when not lenient.
     * Check only lexical spelling here; JsonReader still owns the JSON grammar. */
    private fun hasStandardJsonLexemes(json: String): Boolean {
        var index = 0
        while (index < json.length) {
            when (json[index]) {
                ' ', '\t', '\r', '\n', '{', '}', '[', ']', ':', ',' -> index++
                '"' -> {
                    index++
                    var closed = false
                    while (index < json.length) {
                        val character = json[index++]
                        if (character == '"') { closed = true; break }
                        if (character < ' ') return false
                        if (character == '\\') {
                            if (index == json.length) return false
                            val escape = json[index++]
                            if (escape == 'u') {
                                if (index + 4 > json.length) return false
                                repeat(4) {
                                    val hex = json[index++]
                                    if (hex !in '0'..'9' && hex !in 'a'..'f' && hex !in 'A'..'F') return false
                                }
                            } else if (escape !in "\"\\/bfnrt") return false
                        }
                    }
                    if (!closed) return false
                }
                else -> {
                    val start = index
                    while (index < json.length && json[index] !in " \t\r\n{}[]:,\"") index++
                    val token = json.substring(start, index)
                    val literal = token == "true" || token == "false" || token == "null"
                    val number = (token.first() in '0'..'9' || token.first() == '-') &&
                        token.all { it in "0123456789+-.eE" }
                    if (!literal && !number) return false
                }
            }
        }
        return true
    }

    private fun readRecord(reader: JsonReader): UserServiceStart? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
        reader.beginObject()
        val values = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        var valid = true
        while (reader.hasNext()) {
            val field = reader.nextName()
            val stringValue = reader.peek() == JsonToken.STRING
            if (field !in fields || !seen.add(field) || !stringValue) valid = false
            if (stringValue) values[field] = reader.nextString() else reader.skipValue()
        }
        reader.endObject()
        if (!valid || seen != fields) return null
        return validated(UserServiceStart(values.getValue("parishId"), values.getValue("date"), values.getValue("time")))
    }
}
