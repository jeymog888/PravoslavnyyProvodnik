package ru.liturgyguide.service.book

import com.google.gson.Gson
import ru.liturgyguide.model.ServiceBook

object ServiceBookIntegrity {
    private val gson = Gson()

    /** Fetch time is not content: reopening an unchanged book keeps every address valid. */
    fun contentHash(book: ServiceBook): String = BookText.sha256(gson.toJson(book.copy(fetchedAtMillis = 0, contentHash = "")))

    fun valid(book: ServiceBook): Boolean = runCatching {
        require(book.schemaVersion == 1 && book.key.isValid())
        require(book.enrichment.russian.name.isNotBlank() && book.enrichment.scripture.name.isNotBlank())
        require(book.title.length in 1..300 && book.sourceTitle.length in 1..300)
        require(book.sourceUrl.startsWith("https://azbyka.ru/worships/?") && book.sourceUrl.length <= 2_048)
        require(book.fetchedAtMillis > 0 && book.chapters.size in 1..800)
        require(book.chapters.all { it.id.length in 1..180 && it.title.length in 1..600 && it.meaning.length <= 10_000 && it.blocks.isNotEmpty() })
        require(book.chapters.map { it.id }.toSet().size == book.chapters.size)
        val blocks = book.blocks
        require(blocks.size in 15..8_000 && blocks.sumOf { it.original.length } in 2_000..3_000_000)
        require(blocks.map { it.id }.toSet().size == blocks.size)
        require(blocks.all { it.id.length in 1..240 && it.original.length in 1..60_000 && it.kind.name.isNotBlank() &&
            (it.russian == null || it.russian.length in 1..80_000) &&
            (it.meaning == null || it.meaning.length <= 10_000) && it.scriptureReferences.size <= 20 })
        require(book.notices.size <= 30 && book.notices.all { it.length <= 2_000 })
        require(book.calendarDay == null || PublishedCalendarSupplements.valid(book.calendarDay, book.key.date))
        require(MatinsRankSource.validBook(book))
        require(book.contentHash.matches(Regex("[a-f0-9]{64}")) && book.contentHash == contentHash(book))
        true
    }.getOrDefault(false)
}
