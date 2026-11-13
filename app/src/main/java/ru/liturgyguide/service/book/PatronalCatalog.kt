package ru.liturgyguide.service.book

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.jsoup.parser.Parser
import java.io.StringReader
import java.net.URI
import java.text.Normalizer
import java.time.LocalDate

/** Metadata only: a Minea post is not a verified calendar event or a selected temple. */
data class PatronalCatalogEntry(
    val mineaId: String,
    val sourceTitle: String,
    val displayTitle: String,
    val sourceUrl: String,
    val dateRule: PatronalDateRule?,
    val candidateEligible: Boolean
)

/** Each date still requires an exact minea_id match in the source calendar. */
data class PatronalCatalogMatch(val entry: PatronalCatalogEntry, val dateCandidates: List<LocalDate>)

class PatronalCatalog private constructor(val entries: List<PatronalCatalogEntry>) {
    private data class Indexed(val entry: PatronalCatalogEntry, val words: List<String>, val normalized: String)
    private val indexed = entries.filter { it.candidateEligible }.map {
        val normalized = BookText.normalized(it.displayTitle)
        Indexed(it, words(normalized, query = false), normalized)
    }

    fun search(query: String, gregorianYear: Int, limit: Int = 30): List<PatronalCatalogMatch> {
        if (query.length !in 1..200 || gregorianYear !in PatronalDates.FIRST_YEAR..PatronalDates.LAST_YEAR || limit <= 0)
            return emptyList()
        val normalized = BookText.normalized(query)
        val terms = words(normalized, query = true).distinct()
        if (terms.isEmpty()) return emptyList()
        return indexed.mapNotNull { item ->
            // Trinity means Pentecost itself. A different feast merely mentioning
            // Pentecost, such as Mid-Pentecost, requires its own explicit query.
            if (terms == listOf("пятидесят") && item.words.firstOrNull() != "пятидесят") return@mapNotNull null
            val positions = terms.map { term -> item.words.indexOfFirst { word ->
                // The canonical short form of Elijah is a complete name, not
                // a prefix of other saints such as Iliodor.
                word == term || term != "илия" && word.startsWith(term)
            } }
            if (positions.any { it < 0 }) return@mapNotNull null
            val exact = terms.count { it in item.words }
            val phrase = if (item.normalized.contains(normalized)) 20 else 0
            val score = exact * 30 + phrase - positions.sum().coerceAtMost(100)
            score to PatronalCatalogMatch(item.entry, item.entry.dateRule?.let { PatronalDates.candidates(it, gregorianYear) }.orEmpty())
        }.sortedWith(compareByDescending<Pair<Int, PatronalCatalogMatch>> { it.first }
            .thenBy { it.second.dateCandidates.firstOrNull() ?: LocalDate.MAX }
            .thenBy { it.second.entry.displayTitle }
            .thenBy { it.second.entry.mineaId.toLong() })
            .take(limit.coerceAtMost(100)).map { it.second }
    }

    companion object {
        const val ASSET_NAME = "patronal-minea-index-v1.json"
        const val MAX_BYTES = 600_000
        const val SOURCE_URL = "https://azbyka.ru/worships/wp-json/wp/v2/minea"
        private val stress = Regex("[\\u0300\\u0301\\u0340\\u0341]")
        private val prefix = Regex("^(\\d{1,2})([-—])(\\d{1,2})\\s+(.+)$")
        private val identifier = Regex("[1-9][0-9]{0,8}")
        private val technical = Regex("\\b(?:предпраздн\\p{L}*|попраздн\\p{L}*|отдани\\p{L}*|родительск\\p{L}*)\\b")
        private val weekday = Regex("^(?:понедельник|вторник|среда|четверг|четверток|пяток|пятница|суббота|неделя|великий понедельник|великий вторник|великая среда|великий четверг|великий пяток|великая суббота)\\b")
        private val namedMovingFeast = Regex("(?:вход господень|день святаго духа|день святого духа|жен мироносиц|всех свят|торжество православия)")
        private val stop = setOf("храм", "церковь", "собор", "монастырь", "в", "во", "на", "и", "в честь", "честь",
            "св", "свят", "святой", "святого", "святаго", "святыя", "святых", "святые", "святая", "святителя",
            "пресвятой", "пресвятыя", "пресвятей", "пресвятая", "нашего", "нашея", "отца", "иже")
        private val endings = listOf("инского", "инской", "инская", "инский", "инские", "инских", "овского", "овской",
            "овская", "овский", "евского", "евской", "евская", "евский", "ского", "ской", "ская", "ский", "ских",
            "скими", "скому", "ским", "ском", "скою", "иями", "ыми", "ими", "ами", "ями", "ого", "ему", "ому",
            "ая", "яя", "ою", "ую", "ое", "ые", "ие", "ий", "ый", "ой", "ов", "ев", "ом", "ам", "ям", "ах", "ях", "ы", "и", "а", "я", "у", "ю", "е", "о", "ь")
        private val elijah = setOf("илия", "илии", "илию", "илие", "илией", "илья", "ильи", "илью", "илье", "ильей")
        private val paul = setOf("павел", "павла", "павлу", "павле", "павлом", "павлы", "павлов", "павлам")

        fun decode(json: String?): PatronalCatalog? = runCatching {
            require(json != null && json.length in 1..MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val root = uniqueObject(json)
            require(root.keySet() == setOf("schemaVersion", "capturedAt", "sourceUrl", "entries"))
            require(root["schemaVersion"].toString() == "1" && string(root["sourceUrl"]) == SOURCE_URL)
            val date = string(root["capturedAt"])
            require(LocalDate.parse(date).toString() == date)
            val values = root.getAsJsonArray("entries")
            require(values.size() in 1..2000)
            val entries = values.map { element ->
                val item = element.asJsonObject
                require(item.keySet() == setOf("mineaId", "sourceTitle", "sourceUrl", "sourcePage"))
                val id = string(item["mineaId"])
                require(identifier.matches(id))
                val rawTitle = string(item["sourceTitle"])
                require(rawTitle.length in 1..500 && rawTitle.none { it.code < 32 })
                val decoded = Parser.unescapeEntities(rawTitle, false)
                require('<' !in decoded && '>' !in decoded)
                val cleaned = BookText.clean(stress.replace(Normalizer.normalize(decoded, Normalizer.Form.NFC), ""))
                require(cleaned.isNotEmpty())
                val url = string(item["sourceUrl"])
                require(validSourceUrl(url))
                val page = item["sourcePage"]
                require(page.isJsonPrimitive && page.asJsonPrimitive.isNumber && page.toString().matches(Regex("[1-9][0-9]?")))
                val parsed = parseTitle(cleaned)
                PatronalCatalogEntry(id, rawTitle, parsed.first, url, parsed.second,
                    parsed.second != null && eligible(parsed.first))
            }
            require(entries.map { it.mineaId }.toSet().size == entries.size)
            PatronalCatalog(entries.toList())
        }.getOrNull()

        private fun string(value: JsonElement?): String {
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString
        }

        /** Gson's tree parser otherwise silently keeps the last duplicate field. */
        private fun uniqueObject(json: String): JsonObject {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                var remaining = 20_000
                fun consume(depth: Int) {
                    require(depth <= 8 && --remaining >= 0)
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            val names = mutableSetOf<String>()
                            while (reader.hasNext()) {
                                require(names.add(reader.nextName()))
                                consume(depth + 1)
                            }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            while (reader.hasNext()) consume(depth + 1)
                            reader.endArray()
                        }
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> error("Expected a catalog JSON value")
                    }
                }
                consume(0)
                require(reader.peek() == JsonToken.END_DOCUMENT)
            }
            return JsonParser.parseString(json).asJsonObject
        }

        private fun validSourceUrl(value: String): Boolean = runCatching {
            require(value.length in 1..2048 && value.none { it.code <= 32 })
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "azbyka.ru" && uri.port == -1 && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.path.startsWith("/worships/minea/") &&
                uri.path.length > "/worships/minea/".length && uri.path.split('/').none { it == "." || it == ".." }
        }.getOrDefault(false)

        private fun parseTitle(value: String): Pair<String, PatronalDateRule?> {
            val match = prefix.matchEntire(value) ?: return value to null
            val first = match.groupValues[1].toInt()
            val day = match.groupValues[3].toInt()
            val title = match.groupValues[4]
            val rule = runCatching {
                if (first == 0) PatronalDateRule.PaschaOffset(if (match.groupValues[2] == "—") -day else day)
                else {
                    require(match.groupValues[2] == "-")
                    PatronalDateRule.FixedJulian(first, day)
                }
            }.getOrNull()
            return title to rule
        }

        private fun eligible(title: String): Boolean {
            val normalized = BookText.normalized(title)
            if (technical.containsMatchIn(normalized)) return false
            return !weekday.containsMatchIn(normalized) || namedMovingFeast.containsMatchIn(normalized)
        }

        private fun words(normalized: String, query: Boolean): List<String> {
            val expanded = normalized.replace(Regex("\\bбож(?:ией|ьей|ия|ья) матер(?:и|ь)\\b"), "богородицы")
                .replace(Regex("\\bпетропавловск\\p{L}*\\b"), "петра павла")
            return expanded.split(' ').filter { it.isNotEmpty() && it !in stop && !it.startsWith("живоначальн") }
                .mapNotNull { word ->
                    if (word.length < 2) null
                    else if (query && word.startsWith("троиц")) "пятидесят"
                    else stem(word)
                }
        }

        private fun stem(word: String): String = when {
            word.startsWith("никола") || word.startsWith("николь") -> "никола"
            word.startsWith("елисавет") || word.startsWith("елизавет") -> "елисавет"
            word.startsWith("пятидесят") -> "пятидесят"
            word.startsWith("чудотвор") -> "чудотвор"
            word.startsWith("богородиц") -> "богородиц"
            word.startsWith("сретен") -> "сретен"
            word.startsWith("серги") -> "серги"
            word in elijah || word.startsWith("ильинск") -> "илия"
            word in paul -> "павел"
            word.startsWith("рождеств") -> "рождеств"
            word.startsWith("икон") -> "икон"
            else -> endings.firstOrNull { word.endsWith(it) && word.length - it.length >= 3 }
                ?.let { word.removeSuffix(it) } ?: word
        }
    }
}
