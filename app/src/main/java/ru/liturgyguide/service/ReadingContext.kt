package ru.liturgyguide.service

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import ru.liturgyguide.model.Parish
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The selected service's date is immutable while reading, even after local midnight. */
data class ReadingContext(
    val parishId: String?,
    val serviceDate: String,
    val timezone: String,
    val source: ReadingContextSource,
    val startEpochMillis: Long? = null
)

enum class ReadingContextSource { MANUAL, PUBLISHED, USER }

object ReadingContextFactory {
    /** A date chosen in the church page is a reading date, never an invented service start. */
    fun manualOnDate(parish: Parish?, date: LocalDate, fallbackTimezone: String = "Europe/Moscow"): ReadingContext? =
        runCatching {
            ReadingContextCodec.validated(ReadingContext(parish?.id, date.toString(),
                ZoneId.of(parish?.timezone ?: fallbackTimezone).id, ReadingContextSource.MANUAL))
        }.getOrNull()

    /** A new manual reading belongs to the parish's local date at this explicit action. */
    fun manual(parish: Parish?, now: Instant, fallbackTimezone: String = "Europe/Moscow"): ReadingContext? =
        runCatching {
            val zone = ZoneId.of(parish?.timezone ?: fallbackTimezone)
            ReadingContextCodec.validated(ReadingContext(parish?.id, now.atZone(zone).toLocalDate().toString(),
                zone.id, ReadingContextSource.MANUAL))
        }.getOrNull()

    fun fromSuggestion(suggestion: ContextSuggestion): ReadingContext? = runCatching {
        require(suggestion.service.kind == "liturgy")
        val date = requireNotNull(ScheduleTime.date(suggestion.service.date))
        val time = requireNotNull(when (suggestion.source) {
            ServiceStartSource.PUBLISHED -> ScheduleTime.publishedTime(suggestion.service.time)
            ServiceStartSource.USER -> ScheduleTime.manualTime(suggestion.service.time)
        })
        // Validate before reducing to milliseconds: otherwise a fabricated USER start a fraction
        // after the stated minute could be rounded into a seemingly valid personal context.
        require(ScheduleTime.instant(date, time, ZoneId.of(suggestion.timezone)) == suggestion.startInstant)
        ReadingContextCodec.validated(ReadingContext(suggestion.parishId, suggestion.service.date,
            suggestion.timezone, when (suggestion.source) {
                ServiceStartSource.PUBLISHED -> ReadingContextSource.PUBLISHED
                ServiceStartSource.USER -> ReadingContextSource.USER
            }, suggestion.startInstant.toEpochMilli()))
    }.getOrNull()
}

/** A small, typed storage format. Missing, unknown or coerced fields do not produce a context. */
object ReadingContextCodec {
    const val MAX_JSON_CHARS = 4_096
    private val fields = setOf("version", "parishId", "serviceDate", "timezone", "source", "startEpochMillis")
    private val integer = Regex("-?(0|[1-9][0-9]*)")

    fun validated(value: ReadingContext): ReadingContext? = runCatching {
        require(value.parishId == null || (value.parishId.length in 1..160 &&
            value.parishId == value.parishId.trim() && value.parishId.none(Char::isISOControl)))
        val date = requireNotNull(ScheduleTime.date(value.serviceDate))
        require(value.timezone.length in 1..80 && value.timezone == value.timezone.trim())
        val zone = ZoneId.of(value.timezone)
        when (value.source) {
            ReadingContextSource.MANUAL -> require(value.startEpochMillis == null)
            ReadingContextSource.PUBLISHED, ReadingContextSource.USER -> {
                require(value.parishId != null)
                val local = Instant.ofEpochMilli(requireNotNull(value.startEpochMillis)).atZone(zone)
                require(local.toLocalDate() == date)
                // Sources accepted by overview never guess a repeated DST hour.
                require(zone.rules.getValidOffsets(local.toLocalDateTime()).size == 1)
                if (value.source == ReadingContextSource.USER) require(local.second == 0 && local.nano == 0)
            }
        }
        value
    }.getOrNull()

    fun encode(value: ReadingContext): String {
        val valid = requireNotNull(validated(value)) { "Invalid reading context" }
        return JsonObject().apply {
            addProperty("version", 1)
            if (valid.parishId == null) add("parishId", JsonNull.INSTANCE) else addProperty("parishId", valid.parishId)
            addProperty("serviceDate", valid.serviceDate)
            addProperty("timezone", valid.timezone)
            addProperty("source", valid.source.name)
            if (valid.startEpochMillis == null) add("startEpochMillis", JsonNull.INSTANCE)
            else addProperty("startEpochMillis", valid.startEpochMillis)
        }.toString().also { require(it.length <= MAX_JSON_CHARS) }
    }

    fun decode(json: String?): ReadingContext? {
        if (json.isNullOrBlank() || json.length > MAX_JSON_CHARS || !standardLexemes(json)) return null
        return runCatching {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                val seen = mutableSetOf<String>()
                var parishId: String? = null
                var date: String? = null
                var timezone: String? = null
                var source: ReadingContextSource? = null
                var start: Long? = null
                while (reader.hasNext()) {
                    val field = reader.nextName()
                    require(field in fields && seen.add(field))
                    when (field) {
                        "version" -> require(number(reader) == 1L)
                        "parishId" -> parishId = optionalString(reader)
                        "serviceDate" -> date = string(reader)
                        "timezone" -> timezone = string(reader)
                        "source" -> source = ReadingContextSource.valueOf(string(reader))
                        "startEpochMillis" -> start = if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull(); null
                        } else number(reader)
                    }
                }
                reader.endObject()
                require(seen == fields && reader.peek() == JsonToken.END_DOCUMENT)
                validated(ReadingContext(parishId, requireNotNull(date), requireNotNull(timezone), requireNotNull(source), start))
            }
        }.getOrNull()
    }

    private fun string(reader: JsonReader): String {
        require(reader.peek() == JsonToken.STRING)
        return reader.nextString()
    }

    private fun optionalString(reader: JsonReader): String? = if (reader.peek() == JsonToken.NULL) {
        reader.nextNull(); null
    } else string(reader)

    private fun number(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        val raw = reader.nextString()
        require(integer.matches(raw))
        return raw.toLong()
    }

    /** Gson 2.10's non-lenient reader still accepts uppercase literals and escaped apostrophes.
     * This format is flat: lexical spelling is checked here, structure by JsonReader above. */
    private fun standardLexemes(json: String): Boolean {
        var index = 0
        while (index < json.length) {
            when (json[index]) {
                ' ', '\t', '\r', '\n', '{', '}', ':', ',' -> index++
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
                    while (index < json.length && json[index] !in " \t\r\n{}:,\"") index++
                    val token = json.substring(start, index)
                    if (token != "null" && !integer.matches(token)) return false
                }
            }
        }
        return true
    }
}

sealed interface ServiceEntryResult {
    data class Available(val suggestion: ContextSuggestion, val context: ReadingContext) : ServiceEntryResult
    data object Expired : ServiceEntryResult
}

/** Re-checks the chosen occurrence at the action's own wall clock. Never swaps to another card. */
class ServiceEntryResolver(private val engine: ContextSuggestionEngine) {
    fun resolve(selected: ContextSuggestion, parish: Parish, now: Instant,
        userStarts: List<UserServiceStart> = emptyList()): ServiceEntryResult {
        val parishZone = runCatching { ZoneId.of(parish.timezone).id }.getOrNull() ?: return ServiceEntryResult.Expired
        if (selected.parishId != parish.id || selected.timezone != parishZone) return ServiceEntryResult.Expired
        val overview = engine.overview(parish, now, userStarts)
        val actual = listOfNotNull(overview.currentHypothesis, overview.nextKnownService).firstOrNull {
            it.parishId == selected.parishId && it.startInstant == selected.startInstant &&
                it.source == selected.source && it.timezone == selected.timezone && it.service.date == selected.service.date
        } ?: return ServiceEntryResult.Expired
        val context = ReadingContextFactory.fromSuggestion(actual) ?: return ServiceEntryResult.Expired
        return ServiceEntryResult.Available(actual, context)
    }
}
