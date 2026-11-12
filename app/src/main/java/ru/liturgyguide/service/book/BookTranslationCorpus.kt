package ru.liturgyguide.service.book

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.model.BookSource

/** Old pairs remain useful, but an old or partially loaded corpus is never declared complete. */
internal data class BookTranslationCorpus(
    val index: BookTranslationIndex = BookTranslationIndex.empty(),
    val completedSources: Set<String> = emptySet(),
    val version: Int = VERSION
) {
    val current: Boolean get() = version == VERSION
    fun completeFor(worship: WorshipKind): Boolean = completeFor(BookTranslationIndex.requiredSources(worship))
    fun completeFor(sources: Collection<BookSource>): Boolean = current && index.size > 0 &&
        completedSources.containsAll(sources.map { it.url })

    fun encode(): String = JsonObject().apply {
        addProperty("version", version)
        add("completedSources", JsonArray().apply { completedSources.sorted().forEach { add(it) } })
        add("entries", JsonParser.parseString(index.encode()))
    }.toString()

    companion object {
        // Bump when the required source set or exact extraction rules change.
        const val VERSION = 9
        const val MAX_BYTES = 2_000_000

        fun decode(json: String?): BookTranslationCorpus = runCatching {
            require(json != null && json.length <= MAX_BYTES)
            val root = JsonParser.parseString(json)
            if (root.isJsonArray) return@runCatching BookTranslationCorpus(BookTranslationIndex.decode(json), version = 0)
            val value = root.asJsonObject
            val version = value.get("version").asInt
            val sources = value.getAsJsonArray("completedSources").map { it.asString }.toSet()
            require(sources.all { url -> BookTranslationIndex.ALL_PARALLEL_SOURCES.any { it.url == url } })
            BookTranslationCorpus(BookTranslationIndex.decode(value.getAsJsonArray("entries").toString()), sources, version)
        }.getOrElse { BookTranslationCorpus() }
    }
}
