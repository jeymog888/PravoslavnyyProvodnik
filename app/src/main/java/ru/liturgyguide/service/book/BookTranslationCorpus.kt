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
        const val VERSION = 14
        // Version 12 entries stay valid offline. Only these changed extractors need another page pass.
        internal val REEXTRACTED_SOURCES = setOf(BookTranslationIndex.TIMROT_FULL_SOURCE.url,
            BookTranslationIndex.OCTOECHOS_SOURCES[6].url, BookTranslationIndex.OCTOECHOS_SOURCES[7].url)
        internal val HOUR_PRAYER_REEXTRACTED_SOURCES = setOf(BookTranslationIndex.HOUR1_SOURCE.url,
            BookTranslationIndex.HOUR3_SOURCE.url, BookTranslationIndex.HOUR6_SOURCE.url, BookTranslationIndex.HOUR9_SOURCE.url)
        const val MAX_BYTES = 4_000_000

        fun decode(json: String?): BookTranslationCorpus = runCatching {
            require(json != null && json.length <= MAX_BYTES)
            val root = JsonParser.parseString(json)
            if (root.isJsonArray) return@runCatching BookTranslationCorpus(BookTranslationIndex.decode(json), version = 0)
            val value = root.asJsonObject
            val version = value.get("version").asInt
            val sources = value.getAsJsonArray("completedSources").map { it.asString }.toSet()
            require(sources.all { url -> BookTranslationIndex.ALL_PARALLEL_SOURCES.any { it.url == url } })
            val index = BookTranslationIndex.decode(value.getAsJsonArray("entries").toString())
            when (version) {
                12 -> BookTranslationCorpus(index, sources - REEXTRACTED_SOURCES - HOUR_PRAYER_REEXTRACTED_SOURCES)
                13 -> BookTranslationCorpus(index, sources - HOUR_PRAYER_REEXTRACTED_SOURCES)
                else -> BookTranslationCorpus(index, sources, version)
            }
        }.getOrElse { BookTranslationCorpus() }
    }
}
