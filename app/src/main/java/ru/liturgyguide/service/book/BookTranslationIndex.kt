package ru.liturgyguide.service.book

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.nodes.Document
import ru.liturgyguide.model.BookSource

data class BookTranslation(val russian: String, val source: BookSource, val meaning: String? = null)

/** A lexical concordance, not a machine translator. Absent matches deliberately remain absent. */
class BookTranslationIndex private constructor(private val entries: Map<String, BookTranslation>) {
    fun find(original: String): BookTranslation? = entries[normal(original)]

    fun plus(other: BookTranslationIndex): BookTranslationIndex = BookTranslationIndex(other.entries + entries)

    val size: Int get() = entries.size

    fun encode(): String = JsonArray().apply {
        entries.forEach { (key, value) -> add(JsonObject().apply {
            addProperty("original", key); addProperty("russian", value.russian)
            addProperty("source", value.source.title); addProperty("url", value.source.url)
            value.meaning?.let { addProperty("meaning", it) }
        }) }
    }.toString()

    companion object {
        val EDITORIAL_SOURCE = BookSource("Русское изложение: редакция Феории", "https://github.com/jeymog888/PravoslavnyyProvodnik")
        val TIMROT_SOURCE = BookSource("Перевод иеромонаха Амвросия (Тимрота), Азбука веры",
            "https://azbyka.ru/bogosluzhenie/liturgiya-svt-ioanna-zlatousta/")

        fun empty(): BookTranslationIndex = BookTranslationIndex(emptyMap())

        private fun normal(value: String): String = BookText.normalized(BookText.withoutSpeaker(value).second)

        fun fromAssets(legacyStagesJson: String?, commonJson: String? = null): BookTranslationIndex {
            val result = linkedMapOf<String, BookTranslation>()
            fun add(original: String?, russian: String?, source: BookSource = EDITORIAL_SOURCE, meaning: String? = null) {
                if (original.isNullOrBlank() || russian.isNullOrBlank()) return
                val key = normal(original)
                if (key.isNotBlank()) result.putIfAbsent(key, BookTranslation(BookText.clean(russian), source, meaning))
            }
            runCatching {
                if (legacyStagesJson != null) JsonParser.parseString(legacyStagesJson).asJsonObject.getAsJsonArray("stages")?.forEach { value ->
                    val stage = value.asJsonObject
                    stage.getAsJsonArray("texts")?.forEach { textValue ->
                        val text = textValue.asJsonObject
                        val meaning = text.string("explanation")
                        add(text.string("original"), text.string("translation"), meaning = meaning)
                        val pairs = text.getAsJsonArray("lines")?.mapNotNull { line ->
                            val item = line.asJsonObject
                            val original = item.string("original")
                            val russian = item.string("translation")
                            if (original.isNullOrBlank() || russian.isNullOrBlank()) null else original to russian
                        }.orEmpty()
                        // Source paragraphs and the existing reader split the same prayer differently.
                        // A concatenation is accepted only when every original word is present in order.
                        for (start in pairs.indices) {
                            for (end in start until minOf(pairs.size, start + 32)) {
                                val sequence = pairs.subList(start, end + 1)
                                add(sequence.joinToString(" ") { it.first }, sequence.joinToString(" ") { it.second }, meaning = meaning)
                            }
                        }
                    }
                }
            }
            runCatching {
                if (commonJson != null) JsonParser.parseString(commonJson).asJsonArray.forEach { value ->
                    val item = value.asJsonObject
                    add(item.string("original"), item.string("russian"), meaning = item.string("meaning"))
                }
            }
            return BookTranslationIndex(result)
        }

        /** The public bilingual edition is downloaded for this reading, never bundled into the APK. */
        fun fromParallelPage(document: Document): BookTranslationIndex {
            val result = linkedMapOf<String, BookTranslation>()
            for (row in document.select("table tr")) {
                val cells = row.children().filter { it.tagName() == "td" }
                if (cells.size != 2) continue
                val original = BookText.clean(cells[0].text())
                val russian = BookText.clean(cells[1].text())
                if (original.length < 3 || russian.length < 3 || original == "Церковнославянский") continue
                val sourceParts = speakerSegments(original)
                val russianParts = speakerSegments(russian)
                // Paragraphs occasionally contain several replies. Matching an entire Russian cell
                // to one shorter original reply would change its meaning and is not allowed.
                if (sourceParts.size == russianParts.size && sourceParts.size > 1) {
                    sourceParts.zip(russianParts).forEach { (left, right) ->
                        if (left.first == right.first) addParallel(result, left.second, right.second)
                    }
                }
                addParallel(result, original, russian)
            }
            return BookTranslationIndex(result)
        }

        fun decode(json: String?): BookTranslationIndex = runCatching {
            require(json != null && json.length <= 1_500_000)
            val result = linkedMapOf<String, BookTranslation>()
            JsonParser.parseString(json).asJsonArray.forEach { value ->
                val item = value.asJsonObject
                val original = requireNotNull(item.string("original"))
                val russian = requireNotNull(item.string("russian"))
                val source = BookSource(requireNotNull(item.string("source")), requireNotNull(item.string("url")))
                require(original.length <= 30_000 && russian.length <= 40_000)
                result[normal(original)] = BookTranslation(russian, source, item.string("meaning"))
            }
            BookTranslationIndex(result)
        }.getOrElse { empty() }

        private fun addParallel(target: MutableMap<String, BookTranslation>, original: String, russian: String) {
            val left = BookText.withoutSpeaker(original).second
            val right = BookText.withoutSpeaker(russian).second
            val key = normal(left)
            if (key.isNotBlank() && right.isNotBlank()) target.putIfAbsent(key, BookTranslation(right, TIMROT_SOURCE))
        }

        private fun speakerSegments(value: String): List<Pair<String?, String>> {
            val starts = Regex("(?i)(?:(?:[12] )?(?:Лик|Хор)|Диакон|Священник|Иерей|Чтец|Народ|Предстоятель):\\s*")
                .findAll(value).toList()
            if (starts.isEmpty()) return listOf(null to value)
            if (starts.first().range.first != 0) return listOf(null to value)
            return starts.mapIndexed { index, match ->
                val end = starts.getOrNull(index + 1)?.range?.first ?: value.length
                BookText.withoutSpeaker(value.substring(match.range.first, end))
            }
        }

        private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }
}
