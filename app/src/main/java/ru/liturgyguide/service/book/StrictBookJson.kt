package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Validate the stream before Gson's tree parser can overwrite duplicate object fields. */
internal object StrictBookJson {
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
