package ru.liturgyguide.ui

import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Deterministic books for actual navigation tests; no live network or calendar API. */
internal class NavigationMigrationBooks : ServiceBooks {
    var fail = false
    val requests = CopyOnWriteArrayList<ServiceBookKey>()
    private val saved = ConcurrentHashMap<ServiceBookKey, ServiceBook>()
    fun publish(book: ServiceBook) {
        require(ServiceBookIntegrity.valid(book))
        saved[book.key] = book
    }
    override fun cached(key: ServiceBookKey): ServiceBook? = saved[key]
    override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
        requests += key
        if (fail) return BookLoadResult.Failed(BookFailure.NETWORK, "Нет связи в проверяемом сценарии")
        val book = saved.getOrPut(key) {
            val value = ServiceBook(key = key, title = key.worship.title, sourceUrl = key.sourceUrl(),
                sourceTitle = "Источник теста", fetchedAtMillis = 1_800_000_000_000L, selectedEventTitle = null,
                chapters = listOf(BookChapter("opening", "Начало", "Начинается общая молитва",
                    (1..15).map { index -> BookBlock("prayer-$index", BookBlockKind.PRAYER, null,
                        "Благословено Царство. ".repeat(40), "Благословенно Царство. ".repeat(40)) })), contentHash = "")
            value.copy(contentHash = ServiceBookIntegrity.contentHash(value))
        }
        return BookLoadResult.Ready(book, fromCache = false)
    }
}
