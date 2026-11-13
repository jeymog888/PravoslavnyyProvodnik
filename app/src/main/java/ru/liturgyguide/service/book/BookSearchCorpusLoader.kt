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
    val error: String? = null, val otherParts: List<WorshipKind> = listOfNotNull(otherPart),
    val warnings: List<String> = emptyList())

object BookSearchCorpusLoader {
    suspend fun load(current: ServiceBook, journey: BookJourneyContext?, parishId: String?,
        books: ServiceBooks, readingStore: BookReadingStore): BookSearchCorpus = withContext(Dispatchers.IO) {
        val neighbours = journey?.validatedFor(current.key, parishId)?.parts?.filter { it != current.key }.orEmpty()
        if (neighbours.isEmpty()) return@withContext BookSearchCorpus(listOf(current))
        val found = mutableListOf(current)
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (neighbour in neighbours) {
            currentCoroutineContext().ensureActive()
            fun unavailable(message: String) { errors += message }
            try {
                val reading = readingStore.lookup(neighbour, parishId)
                if (reading == BookReadingLookup.Unreadable) {
                    unavailable("Не удалось проверить сохраненное место ${partName(neighbour.worship)}. Эта часть не проверена")
                    continue
                }
                val accepted = (reading as? BookReadingLookup.Found)?.position
                val exact = if (accepted != null) {
                    // An accepted neighbour is pinned to its exact edition, never latest.
                    val saved = books.cachedVersion(neighbour, accepted.contentHash)
                        ?.takeIf { it.key == neighbour && it.contentHash == accepted.contentHash }
                    if (saved == null) {
                        unavailable("Сохраненная редакция ${partName(neighbour.worship)} недоступна. Эта часть не проверена")
                        continue
                    }
                    saved
                } else {
                    val cached = books.cached(neighbour)?.takeIf { it.key == neighbour }
                    val candidate = if (cached != null) cached else {
                        val loaded = books.load(neighbour, force = false)
                        currentCoroutineContext().ensureActive()
                        if (loaded !is BookLoadResult.Ready || !loaded.saved) {
                            unavailable("Не удалось сохранить текст ${partName(neighbour.worship)}. Эта часть не проверена")
                            continue
                        }
                        loaded.book
                    }
                    if (candidate.key != neighbour) {
                        unavailable("Получен другой текст. ${neighbour.worship.title} не проверена")
                        continue
                    }
                    val saved = books.cachedVersion(neighbour, candidate.contentHash)?.takeIf { it == candidate }
                    if (saved == null) {
                        unavailable("Точная редакция ${partName(neighbour.worship)} не сохранена. Эта часть не проверена")
                        continue
                    }
                    saved
                }
                currentCoroutineContext().ensureActive()
                val review = BookHourSourceReview.of(exact)
                if (review.status == BookHourReviewStatus.CONFLICT) {
                    unavailable("${neighbour.worship.title}: текст требует сверки с указаниями. Поиск по этой части не выполнен")
                    continue
                }
                if (review.status == BookHourReviewStatus.UNKNOWN) warnings +=
                    "${neighbour.worship.title}: кондак не сопоставлен с указаниями. Поиск выполняется в сохраненном тексте"
                found += exact
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unavailable("Не удалось проверить текст ${partName(neighbour.worship)}. Эта часть не проверена") }
        }
        currentCoroutineContext().ensureActive()
        BookSearchCorpus(found, neighbours.singleOrNull()?.worship,
            errors.joinToString("\n").takeIf(String::isNotBlank), neighbours.map { it.worship }, warnings)
    }

    private fun partName(kind: WorshipKind) = when (kind) {
        WorshipKind.MATINS -> "утрени"
        WorshipKind.VESPERS -> "вечерни"
        WorshipKind.HOUR3 -> "третьего часа"
        WorshipKind.HOUR6 -> "шестого часа"
        WorshipKind.LITURGY -> "литургии"
        else -> kind.title
    }
}
