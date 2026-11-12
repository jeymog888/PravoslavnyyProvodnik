package ru.liturgyguide.service.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind

/** One search opening owns this snapshot. Loading never accepts an edition or writes a cursor. */
data class BookSearchCorpus(val books: List<ServiceBook>, val otherPart: WorshipKind? = null,
    val error: String? = null)

object BookSearchCorpusLoader {
    suspend fun load(current: ServiceBook, journey: BookJourneyContext?, parishId: String?,
        books: ServiceBooks, readingStore: BookReadingStore): BookSearchCorpus = withContext(Dispatchers.IO) {
        val neighbour = journey?.validatedFor(current.key, parishId)?.parts?.singleOrNull { it != current.key }
            ?: return@withContext BookSearchCorpus(listOf(current))
        fun unavailable(message: String) = BookSearchCorpus(listOf(current), neighbour.worship, message)
        try {
            val accepted = readingStore.positionFor(neighbour, parishId)
            val exact = if (accepted != null) {
                // A saved cursor pins an edition. Its absence is never permission to substitute latest.
                books.cachedVersion(neighbour, accepted.contentHash)
                    ?.takeIf { it.key == neighbour && it.contentHash == accepted.contentHash }
                    ?: return@withContext unavailable("Сохраненная редакция ${partName(neighbour.worship)} недоступна. Эта часть не проверена")
            } else {
                val cached = books.cached(neighbour)?.takeIf { it.key == neighbour }
                val candidate = if (cached != null) cached else {
                    val loaded = books.load(neighbour, force = false)
                    currentCoroutineContext().ensureActive()
                    if (loaded !is BookLoadResult.Ready || !loaded.saved)
                        return@withContext unavailable("Не удалось сохранить текст ${partName(neighbour.worship)}. Эта часть не проверена")
                    loaded.book
                }
                if (candidate.key != neighbour) return@withContext unavailable("Получен другой текст. ${neighbour.worship.title} не проверена")
                books.cachedVersion(neighbour, candidate.contentHash)?.takeIf { it == candidate }
                    ?: return@withContext unavailable("Точная редакция ${partName(neighbour.worship)} не сохранена. Эта часть не проверена")
            }
            currentCoroutineContext().ensureActive()
            BookSearchCorpus(listOf(current, exact), neighbour.worship)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable("Не удалось проверить текст ${partName(neighbour.worship)}. Поиск выполнен только в открытой книге") }
    }

    private fun partName(kind: WorshipKind) = if (kind == WorshipKind.MATINS) "утрени" else "вечерни"
}
