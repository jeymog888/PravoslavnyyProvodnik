package ru.liturgyguide.service.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import ru.liturgyguide.service.schedule.UrlConnectionScheduleTransport
import java.time.Clock

enum class BookLoadProgress(val label: String) {
    SOURCE("Загружаем службу"), READINGS("Подбираем чтения дня"),
    RUSSIAN("Добавляем русский текст"), SAVING("Сохраняем на телефоне")
}

enum class BookFailure { INVALID_KEY, NETWORK, INVALID_SOURCE, STORAGE }

sealed interface BookLoadResult {
    data class Ready(val book: ServiceBook, val fromCache: Boolean, val warning: String? = null, val saved: Boolean = true) : BookLoadResult
    data class Failed(val reason: BookFailure, val message: String, val cachedBook: ServiceBook? = null) : BookLoadResult
}

interface ServiceBooks {
    fun cached(key: ServiceBookKey): ServiceBook?
    fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? = cached(key)?.takeIf { it.contentHash == contentHash }
    suspend fun load(key: ServiceBookKey, force: Boolean = false,
        onProgress: (BookLoadProgress) -> Unit = {}): BookLoadResult
}

/** Network books never replace a reading object already accepted by the reader. */
class ServiceBookRepository(
    private val cache: BookCache,
    private val transport: ScheduleTransport = UrlConnectionScheduleTransport(timeoutMillis = 15_000),
    private val translations: BookTranslationIndex = BookTranslationIndex.empty(),
    private val clock: Clock = Clock.systemUTC(),
    private val downloadRussian: Boolean = true
) : ServiceBooks {
    private val mutex = Mutex()
    private val parser = ServiceBookParser()

    override fun cached(key: ServiceBookKey): ServiceBook? = if (key.isValid()) cache.read(key) else null
    override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? =
        if (key.isValid()) cache.readVersion(key, contentHash) else null

    override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
        if (!key.isValid()) return BookLoadResult.Failed(BookFailure.INVALID_KEY, "Не удалось выбрать дату или вид службы")
        return mutex.withLock {
            val previous = withContext(Dispatchers.IO) { cache.read(key) }
            if (previous != null && !force) return@withLock BookLoadResult.Ready(previous, fromCache = true, warning = BookWarnings.supplementWarning(previous))
            try {
                if (key.worship == WorshipKind.VIGIL) loadVigil(key, force, onProgress)
                else fetchBook(key, force, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InvalidServiceBook) {
                BookLoadResult.Failed(BookFailure.INVALID_SOURCE,
                    "Не удалось получить целую службу на эту дату. Сохраненный текст остался на телефоне", previous)
            } catch (_: Exception) {
                BookLoadResult.Failed(BookFailure.NETWORK,
                    "Не удалось загрузить службу. Проверьте связь и попробуйте еще раз", previous)
            }
        }
    }

    private suspend fun fetchBook(key: ServiceBookKey, refreshRussian: Boolean, progress: (BookLoadProgress) -> Unit): BookLoadResult.Ready {
        progress(BookLoadProgress.SOURCE)
        val page = transport.fetch(key.sourceUrl()).html() ?: throw InvalidServiceBook("Источник вернул не страницу службы")
        // Validate completeness before extra requests and before touching any cache.
        val plainBook = withContext(Dispatchers.Default) { parser.parse(page, key, clock.millis(), translations) }
        val hasScripture = plainBook.blocks.any { it.kind == BookBlockKind.SCRIPTURE && it.scriptureReferences.isNotEmpty() }
        val extras = coroutineScope {
            val verses = async {
                if (!hasScripture) null else {
                    progress(BookLoadProgress.READINGS)
                    safely { transport.fetch("https://azbyka.ru/biblia/days/${key.date}").html()?.let(BookScripture::fromPage) }
                }
            }
            val russian = async {
                if (!downloadRussian) BookTranslationIndex.empty() to BookSupplementStatus.NOT_REQUESTED
                else loadRussianCorpus(refreshRussian, progress)
            }
            verses.await() to russian.await()
        }
        val parsed = withContext(Dispatchers.Default) {
            parser.parse(page, key, plainBook.fetchedAtMillis, translations.plus(extras.second.first), extras.first ?: BookScripture.empty())
        }
        val scriptureStatus = when {
            !hasScripture -> BookSupplementStatus.NOT_NEEDED
            parsed.blocks.filter { it.kind == BookBlockKind.SCRIPTURE }.all { it.russian != null } -> BookSupplementStatus.AVAILABLE
            else -> BookSupplementStatus.RETRY_NEEDED
        }
        // An unsuccessful refresh of a supplement must not erase an exact, previously
        // verified translation. Never carry translations across changed original text.
        val previous = withContext(Dispatchers.IO) { cache.read(key) }?.blocks?.associateBy { it.id }.orEmpty()
        val chapters = parsed.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map { block ->
            val old = previous[block.id]
            if (block.russian == null && old != null && old.original == block.original && old.russian != null)
                block.copy(russian = old.russian, translationSource = old.translationSource, meaning = old.meaning ?: block.meaning)
            else block
        }) }
        val book = parsed.copy(chapters = chapters,
            sources = (parsed.sources + chapters.flatMap { it.blocks }.mapNotNull { it.translationSource }).distinct(),
            enrichment = BookEnrichment(extras.second.second, scriptureStatus)).let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it))
        }
        return save(book, progress)
    }

    private suspend fun loadRussianCorpus(refresh: Boolean, progress: (BookLoadProgress) -> Unit): Pair<BookTranslationIndex, BookSupplementStatus> {
        val saved = withContext(Dispatchers.IO) { BookTranslationCorpus.decode(cache.readTranslationCorpus()) }
        if (saved.complete && !refresh) return saved.index to BookSupplementStatus.AVAILABLE
        var merged = saved.index
        val completed = if (saved.current && saved.index.size > 0 && !refresh) saved.completedSources.toMutableSet()
            else mutableSetOf()
        progress(BookLoadProgress.RUSSIAN)
        // Keep one Russian request in flight alongside Scripture. Map/schedule requests share the
        // bounded transport pool, so launching all three sources concurrently can reject a request.
        for (source in BookTranslationIndex.PARALLEL_SOURCES.filterNot { it.url in completed }) {
            val loaded = safely {
                val response = transport.fetch(source.url)
                response.html()?.let { BookTranslationIndex.fromParallelPage(it, source.copy(url = response.url)) }
            } ?: BookTranslationIndex.empty()
            if (loaded.size > 0) {
                merged = loaded.plus(merged)
                completed += source.url
            }
        }
        val corpus = BookTranslationCorpus(merged, completed)
        // Even an unsuccessful forced refresh retains all previously verified exact pairs, while
        // incomplete source metadata makes the next network load retry what could not be refreshed.
        if (merged.size > 0) withContext(Dispatchers.IO) { safely { cache.writeTranslationCorpus(corpus.encode()) } }
        return merged to if (corpus.complete) BookSupplementStatus.AVAILABLE else BookSupplementStatus.RETRY_NEEDED
    }

    private suspend fun loadVigil(key: ServiceBookKey, force: Boolean, progress: (BookLoadProgress) -> Unit): BookLoadResult.Ready {
        val parts = mutableListOf<ServiceBook>()
        for (kind in listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1)) {
            val partKey = key.copy(worship = kind)
            val saved = if (force) null else withContext(Dispatchers.IO) { cache.read(partKey) }
            parts += saved ?: fetchBook(partKey, refreshRussian = force && parts.isEmpty(), progress = progress).book
        }
        val chapters = parts.flatMap { part -> part.chapters.mapIndexed { index, chapter ->
            if (index == 0) chapter.copy(title = "${part.title} · ${chapter.title}") else chapter
        } }
        val preliminary = ServiceBook(key = key, title = "Всенощное бдение", sourceUrl = key.sourceUrl(),
            sourceTitle = "Азбука веры: вечерня, утреня и первый час", fetchedAtMillis = clock.millis(),
            selectedEventTitle = parts.first().selectedEventTitle, chapters = chapters, contentHash = "",
            notices = (listOf("Собраны вечерня, утреня и первый час на одну церковную дату. Переходы и сокращения в вашем храме могут отличаться") +
                parts.flatMap { it.notices }).distinct(), sources = parts.flatMap { it.sources }.distinct(),
            mainEventOptions = parts.first().mainEventOptions, secondEventOptions = parts.first().secondEventOptions,
            enrichment = BookEnrichment(combineStatus(parts.map { it.enrichment.russian }), combineStatus(parts.map { it.enrichment.scripture })))
        return save(preliminary.copy(contentHash = ServiceBookIntegrity.contentHash(preliminary)), progress)
    }

    private suspend fun save(book: ServiceBook, progress: (BookLoadProgress) -> Unit): BookLoadResult.Ready {
        progress(BookLoadProgress.SAVING)
        val saved = withContext(Dispatchers.IO) { cache.write(book) }
        return BookLoadResult.Ready(book, fromCache = false, saved = saved,
            warning = listOfNotNull(BookWarnings.supplementWarning(book), if (saved) null else
                "Книга открыта, но сохранить ее на телефоне не удалось. Для повторного открытия понадобится сеть").joinToString(". ").ifEmpty { null })
    }

    private fun combineStatus(values: List<BookSupplementStatus>): BookSupplementStatus = when {
        BookSupplementStatus.RETRY_NEEDED in values -> BookSupplementStatus.RETRY_NEEDED
        BookSupplementStatus.NOT_REQUESTED in values -> BookSupplementStatus.NOT_REQUESTED
        BookSupplementStatus.AVAILABLE in values -> BookSupplementStatus.AVAILABLE
        else -> BookSupplementStatus.NOT_NEEDED
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try { block() } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
}
