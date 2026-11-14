package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Validate the stream before Gson's tree parser can overwrite duplicate object fields. */
internal object StrictBookJson {
    /** Scan every raw occurrence before a tree can hide a v2 marker with a later duplicate. */
    fun hasPartialPublishedScope(json: String): Boolean {
        var marked = false
        var remaining = 4096
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            fun consume(depth: Int, journey: Boolean = false, field: String? = null) {
                require(depth <= 16 && --remaining >= 0)
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName() // Escaped names are decoded by the stream reader.
                            consume(depth + 1, journey || name == "journeyContext", if (journey) name else null)
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        var elements = 0
                        while (reader.hasNext()) { elements++; consume(depth + 1, journey, field) }
                        reader.endArray()
                        // A singleton is a mandatory new-format signal even when both other markers are damaged.
                        if (journey && field == "parts" && elements == 1) marked = true
                    }
                    JsonToken.STRING, JsonToken.NUMBER -> {
                        val value = reader.nextString()
                        if (field == "partSet" && value == BookPartSetKind.PARTIAL_PUBLISHED_TEXT.name ||
                            field == "schemaVersion" && value.toBigDecimalOrNull()?.compareTo(java.math.BigDecimal(2)) == 0)
                            marked = true
                    }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> error("Expected a JSON value")
                }
            }
            consume(0)
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        return marked
    }

    fun objectValue(json: String, maximumDepth: Int, maximumValues: Int): JsonObject {
        require(maximumDepth >= 0 && maximumValues > 0)
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            var remaining = maximumValues
            fun consume(depth: Int) {
                require(depth <= maximumDepth && --remaining >= 0)
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
                    else -> error("Expected a JSON value")
                }
            }
            consume(0)
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        return JsonParser.parseString(json).asJsonObject
    }
}
